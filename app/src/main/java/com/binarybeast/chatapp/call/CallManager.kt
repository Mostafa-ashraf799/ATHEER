package com.binarybeast.chatapp.call

import android.content.Context
import com.binarybeast.chatapp.BuildConfig
import com.binarybeast.chatapp.data.AuthRepository
import com.binarybeast.chatapp.data.CallLog
import com.binarybeast.chatapp.data.CallSignal
import com.binarybeast.chatapp.data.NewCallSignal
import com.binarybeast.chatapp.data.Supa
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.UUID

sealed class CallState {
    data object Idle : CallState()
    data class Outgoing(val callId: String, val peerId: String, val video: Boolean) : CallState()
    data class Incoming(val callId: String, val peerId: String, val video: Boolean) : CallState()
    data class Connecting(val callId: String, val peerId: String, val video: Boolean) : CallState()
    data class Connected(val callId: String, val peerId: String, val video: Boolean, val startedAtMs: Long) : CallState()
}

/**
 * مكالمات صوت/فيديو P2P بـ WebRTC.
 * - الصوت والفيديو بيمشوا مباشرة بين الجهازين (مفيش سيرفر وسيط للميديا).
 * - Supabase Realtime بيُستخدم فقط لتبادل SDP و ICE (signaling) — بيانات صغيرة جدًا.
 */
object CallManager {
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var eglBase: EglBase
        private set
    internal lateinit var factory: PeerConnectionFactory
        private set

    private val _state = MutableStateFlow<CallState>(CallState.Idle)
    val state: StateFlow<CallState> = _state.asStateFlow()

    private val _localVideo = MutableStateFlow<VideoTrack?>(null)
    val localVideo: StateFlow<VideoTrack?> = _localVideo.asStateFlow()
    private val _remoteVideo = MutableStateFlow<VideoTrack?>(null)
    val remoteVideo: StateFlow<VideoTrack?> = _remoteVideo.asStateFlow()

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()
    private val _cameraOn = MutableStateFlow(true)
    val cameraOn: StateFlow<Boolean> = _cameraOn.asStateFlow()

    private var pc: PeerConnection? = null
    private var audioTrack: AudioTrack? = null
    private var videoTrack: VideoTrack? = null
    private var videoSource: VideoSource? = null
    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null

    private var pendingOffer: SessionDescription? = null
    private val pendingIce = mutableListOf<IceCandidate>()
    private var remoteSet = false
    private var listenJob: Job? = null
    private var ringTimeout: Job? = null

    private val me get() = AuthRepository.currentUserId ?: error("not signed in")

    // ---------------------------------------------------------------- init

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        eglBase = EglBase.create()
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    /** اندهها بعد تسجيل الدخول: بتسمع لإشارات المكالمات الواردة. */
    fun startListening() {
        if (listenJob?.isActive == true) return
        val uid = me
        listenJob = scope.launch {
            val channel = Supa.client.channel("signals-$uid")
            val flow = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
                table = "call_signals"
            }
            channel.subscribe()
            flow.collect { change ->
                val s = runCatching { change.decodeRecord<CallSignal>() }.getOrNull() ?: return@collect
                // إشارات الشات P2P بيتعامل معاها ChatEngine
                if (s.toId == uid && s.purpose == "call") handleSignal(s)
            }
        }
    }

    /**
     * لما التطبيق يتصحّى بإشعار FCM، الـ offer والـ ICE يكونوا اتبعتوا قبل ما نشترك في Realtime.
     * بنسحب إشارات آخر 45 ثانية ونعيد تشغيلها بالترتيب (لو المكالمة لسه ماتقفلتش).
     */
    suspend fun checkPending() {
        val uid = AuthRepository.currentUserId ?: return
        if (_state.value != CallState.Idle) return
        val since = java.time.Instant.now().minusSeconds(45).toString()
        val signals = runCatching {
            Supa.client.from("call_signals").select {
                filter { eq("to_id", uid); eq("purpose", "call"); gt("created_at", since) }
                order("id", io.github.jan.supabase.postgrest.query.Order.ASCENDING)
            }.decodeList<CallSignal>()
        }.getOrNull() ?: return
        val offer = signals.lastOrNull { it.type == "offer" } ?: return
        val ended = signals.any { it.callId == offer.callId && (it.type == "hangup" || it.type == "reject") }
        if (ended) return
        signals.filter { it.callId == offer.callId }.forEach { handleSignal(it) }
    }

    fun stopListening() {
        listenJob?.cancel(); listenJob = null
        cleanup()
    }

    // ---------------------------------------------------------------- public API

    fun startCall(peerId: String, video: Boolean) {
        if (_state.value != CallState.Idle) return
        val callId = UUID.randomUUID().toString()
        _state.value = CallState.Outgoing(callId, peerId, video)
        scope.launch {
            runCatching {
                Supa.client.from("call_logs").insert(
                    CallLog(id = callId, callerId = me, calleeId = peerId, video = video)
                )
                createPeer(peerId, callId, video)
                val offer = createOffer()
                pc!!.setLocalDescriptionAwait(offer)
                send(callId, peerId, "offer", video, sdpPayload(offer))
                // صحّي جهاز المستلم لو التطبيق عنده مقفول
                com.binarybeast.chatapp.data.Push.notify(peerId, "call", callId, video)
            }.onFailure { endLocal("ended") }
            ringTimeout = scope.launch {
                delay(45_000)
                if (_state.value is CallState.Outgoing) hangup("missed")
            }
        }
    }

    fun accept() {
        val st = _state.value as? CallState.Incoming ?: return
        val offer = pendingOffer ?: return
        _state.value = CallState.Connecting(st.callId, st.peerId, st.video)
        scope.launch {
            runCatching {
                createPeer(st.peerId, st.callId, st.video)
                pc!!.setRemoteDescriptionAwait(offer)
                remoteSet = true
                drainIce()
                val answer = createAnswer()
                pc!!.setLocalDescriptionAwait(answer)
                send(st.callId, st.peerId, "answer", st.video, sdpPayload(answer))
                updateLog(st.callId, "answered")
            }.onFailure { hangup("ended") }
        }
    }

    fun reject() {
        val st = _state.value as? CallState.Incoming ?: return
        scope.launch {
            runCatching { send(st.callId, st.peerId, "reject", st.video, null) }
            updateLog(st.callId, "rejected")
        }
        cleanup()
    }

    fun hangup(status: String = "ended") {
        val (callId, peerId, video, started) = when (val s = _state.value) {
            is CallState.Outgoing -> Quad(s.callId, s.peerId, s.video, 0L)
            is CallState.Connecting -> Quad(s.callId, s.peerId, s.video, 0L)
            is CallState.Connected -> Quad(s.callId, s.peerId, s.video, s.startedAtMs)
            else -> { cleanup(); return }
        }
        scope.launch {
            runCatching { send(callId, peerId, "hangup", video, null) }
            val secs = if (started > 0) ((System.currentTimeMillis() - started) / 1000).toInt() else 0
            updateLog(callId, status, secs)
        }
        cleanup()
    }

    fun toggleMute() {
        val m = !_muted.value
        _muted.value = m
        audioTrack?.setEnabled(!m)
    }

    fun toggleCamera() {
        val on = !_cameraOn.value
        _cameraOn.value = on
        videoTrack?.setEnabled(on)
    }

    fun switchCamera() { capturer?.switchCamera(null) }

    // ---------------------------------------------------------------- signaling

    private suspend fun handleSignal(s: CallSignal) {
        when (s.type) {
            "offer" -> {
                val p = com.binarybeast.chatapp.data.AppSettings.now
                if (_state.value != CallState.Idle || p.blocked.contains(s.fromId) || p.whoCanCall == "nobody") {
                    // مشغول أو محظور أو المكالمات مقفولة — ارفض تلقائيًا
                    runCatching { send(s.callId, s.fromId, "reject", s.video, null) }
                    return
                }
                val sdp = s.payload?.asObj()?.get("sdp")?.jsonPrimitive?.contentOrNull ?: return
                pendingOffer = SessionDescription(SessionDescription.Type.OFFER, sdp)
                synchronized(pendingIce) { pendingIce.clear() }; remoteSet = false
                _state.value = CallState.Incoming(s.callId, s.fromId, s.video)
                // إشعار بشاشة كاملة (بيظهر فوق شاشة القفل والتطبيق في الخلفية)
                scope.launch {
                    val p = com.binarybeast.chatapp.data.ChatEngine.profile(s.fromId)
                    com.binarybeast.chatapp.Notifier.call(appContext, p?.displayName?.ifBlank { p.phone } ?: "مكالمة واردة", s.video)
                }
            }
            "answer" -> {
                val st = _state.value as? CallState.Outgoing ?: return
                if (st.callId != s.callId) return
                ringTimeout?.cancel()
                val sdp = s.payload?.asObj()?.get("sdp")?.jsonPrimitive?.contentOrNull ?: return
                _state.value = CallState.Connecting(st.callId, st.peerId, st.video)
                pc?.setRemoteDescriptionAwait(SessionDescription(SessionDescription.Type.ANSWER, sdp))
                remoteSet = true
                drainIce()
            }
            "ice" -> {
                val o = s.payload?.asObj() ?: return
                val cand = IceCandidate(
                    o["sdpMid"]?.jsonPrimitive?.contentOrNull,
                    o["sdpMLineIndex"]?.jsonPrimitive?.intOrNull ?: 0,
                    o["candidate"]?.jsonPrimitive?.contentOrNull ?: return,
                )
                if (remoteSet) pc?.addIceCandidate(cand) else synchronized(pendingIce) { pendingIce += cand }
            }
            "hangup", "reject" -> {
                val cur = when (val st = _state.value) {
                    is CallState.Outgoing -> st.callId
                    is CallState.Incoming -> st.callId
                    is CallState.Connecting -> st.callId
                    is CallState.Connected -> st.callId
                    else -> null
                }
                if (cur == s.callId) cleanup()
            }
        }
    }

    private fun drainIce() {
        val list = synchronized(pendingIce) { pendingIce.toList().also { pendingIce.clear() } }
        list.forEach { pc?.addIceCandidate(it) }
    }

    private suspend fun send(callId: String, to: String, type: String, video: Boolean, payload: JsonObject?) {
        Supa.client.from("call_signals").insert(NewCallSignal(callId, me, to, type, video, payload))
    }

    private fun sdpPayload(d: SessionDescription) = buildJsonObject { put("sdp", d.description) }

    private fun kotlinx.serialization.json.JsonElement.asObj(): JsonObject? = this as? JsonObject

    private suspend fun updateLog(callId: String, status: String, secs: Int = 0) {
        runCatching {
            Supa.client.from("call_logs").update(
                buildJsonObject { put("status", status); put("duration_s", secs) }
            ) { filter { eq("id", callId) } }
        }
    }

    // ---------------------------------------------------------------- WebRTC

    internal fun iceServers(): List<PeerConnection.IceServer> {
        val list = mutableListOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        )
        if (BuildConfig.TURN_URL.isNotBlank()) {
            list += PeerConnection.IceServer.builder(BuildConfig.TURN_URL)
                .setUsername(BuildConfig.TURN_USER).setPassword(BuildConfig.TURN_PASS).createIceServer()
        }
        return list
    }

    private fun createPeer(peerId: String, callId: String, video: Boolean) {
        remoteSet = false
        val cfg = PeerConnection.RTCConfiguration(iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        pc = factory.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                scope.launch {
                    runCatching {
                        send(callId, peerId, "ice", video, buildJsonObject {
                            put("sdpMid", c.sdpMid ?: "")
                            put("sdpMLineIndex", c.sdpMLineIndex)
                            put("candidate", c.sdp)
                        })
                    }
                }
            }
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
                when (s) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        val cur = _state.value
                        if (cur is CallState.Connecting)
                            _state.value = CallState.Connected(cur.callId, cur.peerId, cur.video, System.currentTimeMillis())
                    }
                    PeerConnection.PeerConnectionState.FAILED,
                    PeerConnection.PeerConnectionState.CLOSED -> if (_state.value !is CallState.Idle) hangup("ended")
                    else -> {}
                }
            }
            override fun onTrack(t: RtpTransceiver) {
                val track = t.receiver.track()
                if (track is VideoTrack) _remoteVideo.value = track
            }
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(d: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) {}
        })

        // صوت
        val audioConstraints = MediaConstraints().apply {
            mandatory += MediaConstraints.KeyValuePair("googEchoCancellation", "true")
            val ns = com.binarybeast.chatapp.data.AppSettings.now.noiseSuppression.toString()
            mandatory += MediaConstraints.KeyValuePair("googNoiseSuppression", ns)
            mandatory += MediaConstraints.KeyValuePair("googAutoGainControl", "true")
        }
        val audioSource = factory.createAudioSource(audioConstraints)
        audioTrack = factory.createAudioTrack("audio0", audioSource).also {
            it.setEnabled(true)
            pc!!.addTrack(it, listOf("stream0"))
        }
        _muted.value = false

        // فيديو
        if (video) {
            val enumerator = Camera2Enumerator(appContext)
            val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                ?: enumerator.deviceNames.firstOrNull()
            if (name != null) {
                capturer = enumerator.createCapturer(name, null)
                textureHelper = SurfaceTextureHelper.create("capture", eglBase.eglBaseContext)
                videoSource = factory.createVideoSource(false)
                capturer!!.initialize(textureHelper, appContext, videoSource!!.capturerObserver)
                // وضع توفير البيانات: دقة وfps أقل
                if (com.binarybeast.chatapp.data.AppSettings.now.lowDataCalls) capturer!!.startCapture(480, 640, 15)
                else capturer!!.startCapture(720, 1280, 30)
                videoTrack = factory.createVideoTrack("video0", videoSource).also {
                    it.setEnabled(true)
                    pc!!.addTrack(it, listOf("stream0"))
                }
                _localVideo.value = videoTrack
                _cameraOn.value = true
            }
        }
    }

    private suspend fun createOffer(): SessionDescription = kotlinx.coroutines.suspendCancellableCoroutine { c ->
        pc!!.createOffer(object : SimpleSdp() {
            override fun onCreateSuccess(d: SessionDescription) = c.resumeWith(Result.success(d))
            override fun onCreateFailure(e: String?) = c.resumeWith(Result.failure(RuntimeException(e)))
        }, MediaConstraints().apply {
            mandatory += MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")
            mandatory += MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true")
        })
    }

    private suspend fun createAnswer(): SessionDescription = kotlinx.coroutines.suspendCancellableCoroutine { c ->
        pc!!.createAnswer(object : SimpleSdp() {
            override fun onCreateSuccess(d: SessionDescription) = c.resumeWith(Result.success(d))
            override fun onCreateFailure(e: String?) = c.resumeWith(Result.failure(RuntimeException(e)))
        }, MediaConstraints())
    }

    private suspend fun PeerConnection.setLocalDescriptionAwait(d: SessionDescription) =
        kotlinx.coroutines.suspendCancellableCoroutine { c ->
            setLocalDescription(object : SimpleSdp() {
                override fun onSetSuccess() = c.resumeWith(Result.success(Unit))
                override fun onSetFailure(e: String?) = c.resumeWith(Result.failure(RuntimeException(e)))
            }, d)
        }

    private suspend fun PeerConnection.setRemoteDescriptionAwait(d: SessionDescription) =
        kotlinx.coroutines.suspendCancellableCoroutine { c ->
            setRemoteDescription(object : SimpleSdp() {
                override fun onSetSuccess() = c.resumeWith(Result.success(Unit))
                override fun onSetFailure(e: String?) = c.resumeWith(Result.failure(RuntimeException(e)))
            }, d)
        }

    private open class SimpleSdp : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) {}
        override fun onSetFailure(e: String?) {}
    }

    private data class Quad(val a: String, val b: String, val c: Boolean, val d: Long)

    private fun endLocal(status: String) {
        val id = when (val s = _state.value) {
            is CallState.Outgoing -> s.callId
            else -> null
        }
        if (id != null) scope.launch { updateLog(id, status) }
        cleanup()
    }

    private fun cleanup() {
        runCatching { com.binarybeast.chatapp.Notifier.cancelCall(appContext) }
        ringTimeout?.cancel(); ringTimeout = null
        // 1) نفصل الواجهة الأول (الـ renderers تشيل نفسها من الـ tracks) قبل ما نفرّغ أي حاجة native
        val oldCapturer = capturer; capturer = null
        val oldVideoTrack = videoTrack; videoTrack = null
        val oldVideoSource = videoSource; videoSource = null
        val oldTexture = textureHelper; textureHelper = null
        val oldAudio = audioTrack; audioTrack = null
        val oldPc = pc; pc = null
        pendingOffer = null
        synchronized(pendingIce) { pendingIce.clear() }
        remoteSet = false
        _localVideo.value = null
        _remoteVideo.value = null
        _muted.value = false
        _cameraOn.value = true
        _state.value = CallState.Idle
        // 2) بعد ما الواجهة تفصل: نقفل ونفرّغ بالترتيب الصح، وكل خطوة محمية (أي استثناء هنا كان بيقفل التطبيق)
        scope.launch {
            kotlinx.coroutines.delay(400)
            runCatching { oldCapturer?.stopCapture() }
            runCatching { oldPc?.close() }
            runCatching { oldCapturer?.dispose() }
            runCatching { oldVideoTrack?.dispose() }
            runCatching { oldVideoSource?.dispose() }
            runCatching { oldTexture?.dispose() }
            runCatching { oldAudio?.dispose() }
            runCatching { oldPc?.dispose() }
        }
    }
}
