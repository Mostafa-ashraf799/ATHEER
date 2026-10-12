package com.binarybeast.chatapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.binarybeast.chatapp.call.CallManager
import com.binarybeast.chatapp.call.CallState
import com.binarybeast.chatapp.data.Profile
import com.binarybeast.chatapp.data.ProfileRepository
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

// ======================================================================= سجل المكالمات

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallsScreen(onOpenChat: (Profile) -> Unit, vm: CallsVm = viewModel()) {
    val rows by vm.rows.collectAsState()
    val state by CallManager.state.collectAsState()
    LaunchedEffect(state) { if (state == CallState.Idle) vm.load() }

    Scaffold(topBar = { GlassTopBar(title = { Text("المكالمات", fontWeight = FontWeight.Bold) }) }) { pad ->
        if (rows.isEmpty()) Box(Modifier.padding(pad).fillMaxSize(), Alignment.Center) {
            Text("مفيش مكالمات لسه.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else LazyColumn(Modifier.padding(pad)) {
            items(rows, key = { it.log.id }) { r ->
                val missed = r.log.status == "missed" || r.log.status == "rejected"
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(r.peer?.avatarFileId, r.peer?.displayName ?: "?", 48.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(r.peer?.let { it.displayName.ifBlank { it.phone } } ?: "مستخدم", fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                when {
                                    missed && !r.outgoing -> AIcons.CallMissed
                                    r.outgoing -> AIcons.CallMade
                                    else -> AIcons.CallReceived
                                }, null, Modifier.size(16.dp),
                                tint = if (missed && !r.outgoing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                " ${hhmm(r.log.startedAt)}" + if (r.log.durationS > 0) " • ${r.log.durationS / 60}:%02d".format(r.log.durationS % 60) else "",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    r.peer?.let { p ->
                        IconButton(onClick = { CallManager.startCall(p.id, r.log.video) }) {
                            Icon(if (r.log.video) AIcons.Videocam else AIcons.Call, "اتصل", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

// ======================================================================= شاشة المكالمة

@Composable
fun CallScreen() {
    val state by CallManager.state.collectAsState()
    val local by CallManager.localVideo.collectAsState()
    val remote by CallManager.remoteVideo.collectAsState()
    val muted by CallManager.muted.collectAsState()
    val camOn by CallManager.cameraOn.collectAsState()

    val peerId = when (val s = state) {
        is CallState.Outgoing -> s.peerId
        is CallState.Incoming -> s.peerId
        is CallState.Connecting -> s.peerId
        is CallState.Connected -> s.peerId
        else -> null
    }
    val isVideo = when (val s = state) {
        is CallState.Outgoing -> s.video
        is CallState.Incoming -> s.video
        is CallState.Connecting -> s.video
        is CallState.Connected -> s.video
        else -> false
    }
    var peer: Profile? by remember { androidx.compose.runtime.mutableStateOf(null) }
    LaunchedEffect(peerId) { peerId?.let { peer = ProfileRepository.byId(it) } }

    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state) {
        val s = state
        if (s is CallState.Connected) while (true) { elapsed = (System.currentTimeMillis() - s.startedAtMs) / 1000; delay(1000) }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0B141A))) {
        if (isVideo && remote != null) VideoView(remote, Modifier.fillMaxSize())

        // معلومات الطرف التاني (قبل ما الفيديو يظهر أو في المكالمة الصوتية)
        if (!(isVideo && remote != null)) Column(
            Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Avatar(peer?.avatarFileId, peer?.displayName ?: "?", 120.dp)
            Text(peer?.displayName?.ifBlank { peer?.phone } ?: "", color = Color.White,
                style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
        }

        Text(
            when (val s = state) {
                is CallState.Outgoing -> "بيرن…"
                is CallState.Incoming -> if (s.video) "مكالمة فيديو واردة" else "مكالمة صوت واردة"
                is CallState.Connecting -> "جاري الاتصال…"
                is CallState.Connected -> "%02d:%02d".format(elapsed / 60, elapsed % 60)
                else -> ""
            },
            color = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp),
        )

        // صورتي (PIP)
        if (isVideo && local != null && camOn) VideoView(
            local, Modifier.align(Alignment.TopStart).padding(16.dp).padding(top = 64.dp)
                .width(110.dp).height(160.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
            mirror = true, overlay = true,
        )

        // الأزرار
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state is CallState.Incoming) {
                CircleBtn(AIcons.CallEnd, Color(0xFFE53935)) { CallManager.reject() }
                CircleBtn(if (isVideo) AIcons.Videocam else AIcons.Call, Color(0xFF25D366)) { CallManager.accept() }
            } else {
                CircleBtn(if (muted) AIcons.MicOff else AIcons.Mic, Color.White.copy(alpha = 0.2f)) { CallManager.toggleMute() }
                if (isVideo) {
                    CircleBtn(if (camOn) AIcons.Videocam else AIcons.VideocamOff, Color.White.copy(alpha = 0.2f)) { CallManager.toggleCamera() }
                    CircleBtn(AIcons.Cameraswitch, Color.White.copy(alpha = 0.2f)) { CallManager.switchCamera() }
                }
                CircleBtn(AIcons.CallEnd, Color(0xFFE53935)) { CallManager.hangup() }
            }
        }
    }
}

@Composable
private fun CircleBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, bg: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(64.dp).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(28.dp)) }
}

@Composable
private fun VideoView(track: VideoTrack?, modifier: Modifier, mirror: Boolean = false, overlay: Boolean = false) {
    val holder = remember { arrayOfNulls<SurfaceViewRenderer>(1) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(CallManager.eglBase.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                setMirror(mirror)
                setZOrderMediaOverlay(overlay)
                holder[0] = this
            }
        },
    )
    // لما الـ track يتغيّر نفصل القديم ونوصّل الجديد على نفس الـ renderer
    DisposableEffect(track) {
        val r = holder[0]
        if (r != null) track?.addSink(r)
        onDispose { if (r != null) track?.removeSink(r) }
    }
    DisposableEffect(Unit) {
        onDispose { holder[0]?.release(); holder[0] = null }
    }
}
