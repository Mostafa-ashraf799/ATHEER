package com.binarybeast.chatapp.data

import android.content.Context
import com.binarybeast.chatapp.call.CallManager
import com.binarybeast.chatapp.data.local.AppDb
import com.binarybeast.chatapp.data.local.GroupDao
import com.binarybeast.chatapp.data.local.GroupEntity
import com.binarybeast.chatapp.data.local.PollVote
import com.binarybeast.chatapp.data.local.previewText
import com.binarybeast.chatapp.data.local.LocalMessage
import com.binarybeast.chatapp.data.local.MessageDao
import com.binarybeast.chatapp.data.local.MsgStatus
import com.binarybeast.chatapp.data.local.ScheduledMsg
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * محرك الشات:
 *  - الرسايل مشفّرة (ECDH + AES-GCM) وبتتخزن محليًا على الجهاز (Room).
 *  - لو الطرف التاني أونلاين: بتروح **P2P مباشرة** عن طريق WebRTC DataChannel.
 *  - لو أوفلاين (أو التطبيق مقفول): بتتحط في صندوق مؤقت (mailbox) مشفّرة وبتتبعت إشارة FCM،
 *    والمستلم بيسحبها وبتتمسح من السيرفر فورًا.
 *  - Supabase بيُستخدم فقط لتبادل إشارات الاتصال (SDP/ICE) ولنقل الرسايل المشفّرة الغايبة.
 */
object ChatEngine {
    private lateinit var db: AppDb
    val dao: MessageDao get() = db.messages()
    val groups: GroupDao get() = db.groups()
    private val acked: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val me get() = AuthRepository.currentUserId ?: error("not signed in")

    /** المحادثة المفتوحة دلوقتي (علشان منبعتش إشعار ليها). */
    @Volatile var openPeerId: String? = null

    /** بيتنده لما توصل رسالة جديدة (يتضبط في App لعرض الإشعار). */
    @Volatile var onIncoming: ((LocalMessage, Profile?) -> Unit)? = null

    private val profileCache = ConcurrentHashMap<String, Profile>()
    private val links = ConcurrentHashMap<String, Link>()
    private val drainLock = Mutex()
    private var jobs: List<Job> = emptyList()

    fun init(ctx: Context) {
        CryptoBox.init(ctx)
        db = AppDb.create(ctx)
    }

    // ------------------------------------------------------------------ دورة الحياة

    fun start() {
        if (jobs.any { it.isActive }) return
        val uid = me
        jobs = listOf(
            scope.launch { publishKey(uid); drainMailbox() },
            scope.launch { listenMailbox(uid) },
            scope.launch { listenSignals(uid) },
            scope.launch { while (true) { runCatching { expireGroupMessages() }; delay(60_000) } },
        )
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        links.values.forEach { it.close() }
        links.clear()
    }

    /** عند تسجيل الخروج: امسح الرسايل المحلية وكاش البروفايلات. */
    /** يمسح كل الرسايل والمجموعات من الجهاز ده من غير تسجيل خروج. */
    suspend fun clearAllMessages() {
        runCatching { dao.clear(); groups.clearGroups(); groups.clearVotes(); scheduled.clear() }
    }

    /** بصمة التحقق من التشفير بين مفتاحي ومفتاح الطرف التاني (نفس الرقم لازم يظهر عند الطرفين). */
    fun safetyNumber(theirKey: String): String {
        val keys = listOf(CryptoBox.myPublicKeyB64(), theirKey).sorted()
        val h = java.security.MessageDigest.getInstance("SHA-256").digest(keys.joinToString("|").toByteArray())
        return h.take(15).joinToString("") { "%03d".format(it.toInt() and 0xFF) }.chunked(5).joinToString(" ")
    }

    suspend fun wipeLocal() {
        stop()
        AppSettings.clearUserData()
        profileCache.clear()
        runCatching { dao.clear(); groups.clearGroups(); groups.clearVotes(); scheduled.clear() }
    }

    private suspend fun publishKey(uid: String) {
        runCatching {
            Supa.client.from("profiles").update(
                buildJsonObject { put("public_key", CryptoBox.myPublicKeyB64()) }
            ) { filter { eq("id", uid) } }
        }
    }

    suspend fun profile(id: String, refresh: Boolean = false): Profile? {
        if (!refresh) profileCache[id]?.let { return it }
        return runCatching { ProfileRepository.byId(id) }.getOrNull()?.also { profileCache[id] = it }
    }

    // ------------------------------------------------------------------ الإرسال

    fun isGroup(peerId: String) = peerId.startsWith("g:")
    private fun gid(peerId: String) = peerId.removePrefix("g:")

    /** بروفايل «وهمي» للمجموعة علشان نعرضها في نفس شاشات الشات. */
    /** «الرسائل المحفوظة»: محادثة مع نفسك، محلية بالكامل (مبتروحش لأي حد). */
    fun savedProfile(): Profile = Profile(id = me, displayName = "الرسائل المحفوظة", about = "مساحتك الخاصة")

    fun isLinkOpen(peerId: String): Boolean = links[peerId]?.open == true

    suspend fun peerProfile(peerId: String): Profile? {
        if (peerId == me) return savedProfile()
        if (!isGroup(peerId)) return profile(peerId)
        val g = groups.get(gid(peerId)) ?: return null
        return Profile(id = peerId, displayName = g.name, about = "${g.memberList().size} أعضاء", avatarFileId = g.avatarFileId)
    }

    suspend fun send(
        peer: Profile, body: String?, fileId: String? = null, mediaType: String? = null, reply: LocalMessage? = null,
        viewMode: Int = 0, viewSecs: Int = 0, silent: Boolean = false,
    ) {
        val msgId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        if (peer.id == me) {   // حفظ لنفسي: محلي بس
            dao.insert(LocalMessage(msgId = msgId, peerId = me, mine = true, body = body, mediaFileId = fileId,
                mediaType = mediaType, createdAt = now, status = MsgStatus.DELIVERED, read = true))
            return
        }
        val grp = if (isGroup(peer.id)) groups.get(gid(peer.id)) ?: return else null
        // مسح تلقائي للمحادثات العادية (إعداد لكل محادثة)
        val ex = if (grp == null) AppSettings.chatTtl(peer.id) else 0
        val snippet = reply?.let { it.previewText().take(80) }
        dao.insert(
            LocalMessage(
                msgId = msgId, peerId = peer.id, mine = true, body = body,
                mediaFileId = fileId, mediaType = mediaType, createdAt = now, status = MsgStatus.SENDING,
                replyToId = reply?.msgId, replyText = snippet, senderId = if (grp != null) me else null,
                viewMode = viewMode, viewSecs = viewSecs,
                expireAt = if (ex > 0) now + ex * 1000L else 0,
            )
        )
        val payload = JSONObject().put("id", msgId).put("t", now).apply {
            body?.let { put("b", it) }
            fileId?.let { put("f", it) }
            mediaType?.let { put("m", it) }
            reply?.let { put("r", it.msgId); put("rt", snippet ?: "") }
            grp?.let { put("g", it.groupId) }
            if (viewMode != 0) { put("vm", viewMode); put("vs", viewSecs) }
            if (ex > 0) put("ex", ex)
            if (silent) put("sl", 1)
        }
        if (grp == null) { deliver(peer, msgId, payload); return }
        // مجموعة: نفس الرسالة بتتشفّر لكل عضو لوحده (P2P أولًا، وبعدين الصندوق)
        val members = grp.memberList().filter { it != me }.mapNotNull { profile(it) }
        deliverMany(members, msgId, payload, push = true)
        dao.setStatus(msgId, if (members.isNotEmpty()) MsgStatus.SENT else MsgStatus.FAILED)
    }

    /** أعضاء الهدف: لو محادثة عادية الطرف التاني، ولو مجموعة كل الأعضاء ما عدا أنا. */
    private suspend fun targets(peer: Profile): List<Profile> {
        if (!isGroup(peer.id)) return listOf(peer)
        val g = groups.get(gid(peer.id)) ?: return emptyList()
        return g.memberList().filter { it != me }.mapNotNull { profile(it) }
    }

    /** رسالة تحكّم (تفاعل/تعديل/حذف/تصويت): بتتشفّر وتتبعت زي الرسايل بس مبتتخزّنش كرسالة. */
    private suspend fun sendControl(peer: Profile, x: String, target: String, build: JSONObject.() -> Unit = {}) {
        val msgId = UUID.randomUUID().toString()
        val payload = JSONObject().put("id", msgId).put("t", System.currentTimeMillis())
            .put("x", x).put("tg", target).apply(build)
        if (isGroup(peer.id)) payload.put("g", gid(peer.id))
        // تحكّم: من غير FCM (محدش لازم يتصحّى علشان تفاعل/تصويت)، وللمجموعات بطلب إرسال واحد
        if (isGroup(peer.id)) deliverMany(targets(peer), msgId, payload, push = false)
        else for (t in targets(peer)) deliver(t, msgId, payload, trackStatus = false, push = false)
    }

    suspend fun react(peer: Profile, m: LocalMessage, emoji: String?) {
        val e = if (m.myReaction == emoji) null else emoji   // الضغط على نفس التفاعل بيشيله
        dao.setMyReaction(m.msgId, e)
        sendControl(peer, "react", m.msgId) { put("e", e ?: "") }
    }

    suspend fun editMessage(peer: Profile, m: LocalMessage, newBody: String) {
        if (!m.mine || m.deleted || newBody.isBlank()) return
        dao.editBody(peer.id, m.msgId, newBody.trim(), true, me)
        sendControl(peer, "edit", m.msgId) { put("b", newBody.trim()) }
    }

    suspend fun deleteForEveryone(peer: Profile, m: LocalMessage) {
        if (!m.mine) return
        dao.markDeleted(peer.id, m.msgId, true, me)
        sendControl(peer, "del", m.msgId)
    }

    suspend fun deleteForMe(m: LocalMessage) = dao.deleteLocal(m.msgId)

    // ------------------------------------------------------------------ المجموعات

    suspend fun createGroup(name: String, members: List<Profile>): Profile? {
        val ids = (listOf(me) + members.map { it.id }).distinct()
        if (ids.size < 2 || name.isBlank()) return null
        val g = GroupEntity(UUID.randomUUID().toString(), name.trim(), me, ids.joinToString(","), 0, System.currentTimeMillis())
        groups.upsert(g)
        systemNote(g.groupId, "أنشأت المجموعة «${g.name}»")
        broadcastMeta(g, ids)
        return peerProfile("g:" + g.groupId)
    }

    /** الأدمن بس: تغيير الاسم / إضافة / إزالة عضو / مؤقّت المسح التلقائي (ثواني، 0 = معطّل). */
    suspend fun updateGroup(groupId: String, name: String? = null, add: List<String> = emptyList(), remove: String? = null, ttlSec: Long? = null, avatar: String? = null) {
        val g = groups.get(groupId) ?: return
        if (g.adminId != me) return
        val newMembers = (g.memberList() + add).distinct().filter { it != remove }
        val ng = g.copy(name = name?.trim()?.ifBlank { null } ?: g.name, members = newMembers.joinToString(","), ttlSec = ttlSec ?: g.ttlSec, avatarFileId = avatar ?: g.avatarFileId)
        groups.upsert(ng)
        if (ng.name != g.name) systemNote(groupId, "غيّرت اسم المجموعة إلى «${ng.name}»")
        if (avatar != null) systemNote(groupId, "غيّرت صورة المجموعة")
        if (add.isNotEmpty()) systemNote(groupId, "ضفت ${add.size} عضو")
        if (remove != null) systemNote(groupId, "شلت عضو من المجموعة")
        if (ng.ttlSec != g.ttlSec) systemNote(groupId, if (ng.ttlSec == 0L) "اتقفل المسح التلقائي" else "المسح التلقائي: بعد ${ng.ttlSec / 3600} ساعة")
        // الأعضاء القدام (بمن فيهم اللي اتشال) والجداد كلهم لازم يعرفوا
        broadcastMeta(ng, (g.memberList() + newMembers).distinct())
    }

    suspend fun leaveGroup(groupId: String) {
        val g = groups.get(groupId) ?: return
        val pseudo = Profile(id = "g:$groupId")
        sendControl(pseudo, "gleave", groupId)
        dao.deleteChat("g:$groupId"); groups.delete(groupId)
    }

    private suspend fun broadcastMeta(g: GroupEntity, to: List<String>) {
        val msgId = UUID.randomUUID().toString()
        val payload = JSONObject().put("id", msgId).put("t", System.currentTimeMillis())
            .put("x", "gmeta").put("tg", g.groupId).put("n", g.name).put("a", g.adminId)
            .put("m", g.members).put("ttl", g.ttlSec).put("av", g.avatarFileId ?: "")
        deliverMany(to.filter { it != me }.mapNotNull { profile(it) }, msgId, payload, push = false)
    }

    private suspend fun systemNote(groupId: String, text: String) {
        dao.insert(
            LocalMessage(
                msgId = UUID.randomUUID().toString(), peerId = "g:$groupId", mine = false, body = "ℹ️ $text",
                mediaFileId = null, mediaType = "system", createdAt = System.currentTimeMillis(),
                status = MsgStatus.DELIVERED, read = true,
            )
        )
    }

    suspend fun sendPoll(peer: Profile, question: String, options: List<String>) {
        val opts = options.map { it.trim() }.filter { it.isNotEmpty() }.take(20)
        if (question.isBlank() || opts.size < 2) return
        val body = JSONObject().put("q", question.trim()).put("o", org.json.JSONArray(opts)).toString()
        send(peer, body, mediaType = "poll")
    }

    suspend fun vote(peer: Profile, m: LocalMessage, option: Int) {
        groups.vote(PollVote(m.msgId, me, option))
        sendControl(peer, "vote", m.msgId) { put("i", option) }
    }

    /** المسح التلقائي: رسايل المجموعات اللي عدّى عليها المؤقّت بتتمسح من الجهاز. */
    // ------------------------------------------------------------------ رسايل مجدولة / نجمة

    val scheduled get() = db.scheduled()

    suspend fun schedule(peer: Profile, body: String, sendAt: Long) {
        if (body.isBlank()) return
        scheduled.insert(ScheduledMsg(peerId = peer.id, body = body.trim(), sendAt = sendAt))
    }

    private suspend fun sendDueScheduled() {
        for (m in scheduled.due(System.currentTimeMillis())) {
            val peer = if (isGroup(m.peerId)) Profile(id = m.peerId) else profile(m.peerId) ?: continue
            scheduled.delete(m.id)
            runCatching { send(peer, m.body) }
        }
    }

    suspend fun toggleStar(m: LocalMessage) = dao.setStarred(m.msgId, !m.starred)

    /** فتح رسالة ميديا مقيّدة: بيبدأ العدّ لو أول مرة. */
    suspend fun markOpened(m: LocalMessage) {
        dao.markOpened(m.id, System.currentTimeMillis())
        if (m.openedAt == 0L && AppSettings.now.readReceipts && !isGroup(m.peerId)) {
            profile(m.peerId)?.let { runCatching { sendControl(it, "op", m.msgId) } }
        }
    }

    /** حرق الرسالة (مرة واحدة بعد القفل). */
    suspend fun burn(m: LocalMessage) { dao.burn(m.id) }

    private suspend fun expireGroupMessages() {
        val nowMs = System.currentTimeMillis()
        dao.burnExpired(nowMs)
        dao.deleteExpired(nowMs)
        TelegramStorage.trim()
        sendDueScheduled()
        val now = System.currentTimeMillis()
        for (g in groups.all()) if (g.ttlSec > 0) dao.deleteOlder("g:${g.groupId}", now - g.ttlSec * 1000)
    }

    /**
     * إرسال نفس الرسالة لعدّة أعضاء (مجموعة) بأقل استهلاك داتا:
     *  - اللي معاه قناة P2P مفتوحة بياخدها مباشرة.
     *  - الباقي بيتحط في الصندوق بـ **طلب واحد** (insert جماعي) بدل طلب لكل عضو.
     *  - إشارة التصحية FCM **نداء واحد** لكل الأعضاء، ومبتتبعتش لرسايل التحكّم (تفاعل/تصويت/تعديل).
     *  - مبنفتحش اتصالات P2P جديدة للمجموعات (كل اتصال بيكلّف عشرات صفوف signaling).
     */
    private suspend fun deliverMany(peers: List<Profile>, msgId: String, payloadObj: JSONObject, push: Boolean) {
        val payload = payloadObj.toString()
        val pid = payloadObj.optString("id")
        val rows = mutableListOf<NewMailboxRow>()
        for (peer in peers) {
            if (peer.id == me) continue
            val key = peer.publicKey ?: profile(peer.id, refresh = true)?.publicKey ?: continue
            val cipher = try { CryptoBox.encrypt(key, payload, "$me>${peer.id}") } catch (e: Exception) { continue }
            val link = links[peer.id]
            val viaP2p = link != null && link.open &&
                runCatching { link.sendJson(JSONObject().put("k", "msg").put("c", cipher)) }.isSuccess
            if (viaP2p) {
                val ackKey = "${peer.id}:$pid"
                scope.launch { delay(4000); if (!acked.contains(ackKey)) viaMailbox(peer.id, msgId, cipher, false, push) }
            } else rows += NewMailboxRow(me, peer.id, msgId, cipher)
        }
        if (rows.isEmpty()) return
        runCatching {
            Supa.client.from("mailbox").insert(rows)
            if (push) Push.notifyMany(rows.map { it.receiverId }, "msg")
        }
    }

    private suspend fun deliver(peer: Profile, msgId: String, payloadObj: JSONObject, trackStatus: Boolean = true, push: Boolean = true) {
        if (peer.id == me) return   // «الرسائل المحفوظة» محلية بس
        // المستلم لازم يكون فتح التطبيق مرة على الأقل علشان مفتاحه العام يتنشر
        val key = peer.publicKey ?: profile(peer.id, refresh = true)?.publicKey
        if (key == null) { if (trackStatus) dao.setStatus(msgId, MsgStatus.FAILED); return }

        val payload = payloadObj.toString()
        val cipher = try { CryptoBox.encrypt(key, payload, "$me>${peer.id}") } catch (e: Exception) {
            if (trackStatus) dao.setStatus(msgId, MsgStatus.FAILED); return
        }

        // 1) P2P لو القناة مفتوحة
        val link = links[peer.id]
        if (link != null && link.open) {
            val ok = runCatching { link.sendJson(JSONObject().put("k", "msg").put("c", cipher)) }.isSuccess
            if (ok) {
                if (trackStatus) dao.setStatus(msgId, MsgStatus.P2P_PENDING)
                // لو التأكيد ما وصلش في 4 ثواني، نبعتها عن طريق الصندوق الاحتياطي
                val ackKey = "${peer.id}:${payloadObj.optString("id")}"
                scope.launch {
                    delay(4000)
                    if (!acked.contains(ackKey)) viaMailbox(peer.id, msgId, cipher, trackStatus, push)
                }
                return
            }
        }

        // 2) صندوق احتياطي + صحّي الطرف التاني + حاول تفتح P2P للرسايل الجاية
        viaMailbox(peer.id, msgId, cipher, trackStatus, push)
        if (push) ensureLink(peer.id)
    }

    private suspend fun viaMailbox(to: String, msgId: String, cipher: String, trackStatus: Boolean = true, push: Boolean = true) {
        runCatching {
            Supa.client.from("mailbox").insert(NewMailboxRow(me, to, msgId, cipher))
            if (trackStatus) dao.setStatus(msgId, MsgStatus.SENT)
            if (push) Push.notify(to, "msg")
        }.onFailure { if (trackStatus) dao.setStatus(msgId, MsgStatus.FAILED) }
    }

    suspend fun markRead(peerId: String) {
        if (!isGroup(peerId) && peerId != me && AppSettings.now.readReceipts) {
            val ids = dao.unreadIds(peerId).take(100)
            val peer = if (ids.isEmpty()) null else profile(peerId)
            if (peer != null) runCatching { sendControl(peer, "rd", "") { put("ids", org.json.JSONArray(ids)) } }
        }
        dao.markRead(peerId)
    }

    // ---- مؤشر «بيكتب…»: بيتبعت بس على قناة P2P المفتوحة (مفيش حاجة بتروح السيرفر)
    private val _typing = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val typing: kotlinx.coroutines.flow.StateFlow<Set<String>> = _typing
    private val typingJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private var lastTypingSent = 0L

    fun sendTyping(peerId: String) {
        val now = System.currentTimeMillis()
        if (now - lastTypingSent < 3000) return
        if (isGroup(peerId) || !AppSettings.now.typingVisible) return
        val l = links[peerId] ?: return
        if (!l.open) return
        lastTypingSent = now
        runCatching { l.sendJson(JSONObject().put("k", "typing")) }
    }

    private fun onTyping(peerId: String) {
        _typing.value = _typing.value + peerId
        typingJobs.remove(peerId)?.cancel()
        typingJobs[peerId] = scope.launch { delay(5000); _typing.value = _typing.value - peerId }
    }

    // ------------------------------------------------------------------ الاستقبال

    private enum class Outcome { NEW, DUP, RETRY, DROP }
    private data class Rx(val outcome: Outcome, val msg: LocalMessage? = null, val msgId: String? = null)

    private suspend fun receiveCipher(senderId: String, cipher: String): Rx {
        // المحظورين: نتجاهل الرسالة (DROP يمسحها من الصندوق من غير ما نخزّنها) والمرسل مبيعرفش
        if (AppSettings.isBlocked(senderId)) return Rx(Outcome.DROP)
        var prof = profile(senderId) ?: return Rx(Outcome.RETRY)
        var key = prof.publicKey
        if (key == null) {
            prof = profile(senderId, refresh = true) ?: return Rx(Outcome.RETRY)
            key = prof.publicKey ?: return Rx(Outcome.DROP)
        }
        val plain = try {
            CryptoBox.decrypt(key, cipher, "$senderId>$me")
        } catch (e: Exception) {
            // ممكن المفتاح اتغيّر: نجيبه تاني مرة واحدة
            val fresh = profile(senderId, refresh = true)?.publicKey
            if (fresh == null || fresh == key) return Rx(Outcome.DROP)
            try { CryptoBox.decrypt(fresh, cipher, "$senderId>$me") } catch (e2: Exception) { return Rx(Outcome.DROP) }
        }
        val j = try { JSONObject(plain) } catch (e: Exception) { return Rx(Outcome.DROP) }
        val msgId = j.optString("id").ifEmpty { return Rx(Outcome.DROP) }
        val ctl = j.optString("x")
        val sentAt = j.optLong("t", System.currentTimeMillis())

        // ---- إدارة المجموعات (الأدمن بس هو اللي يقدر يعمل/يعدّل)
        if (ctl == "gmeta") {
            val gId = j.optString("tg")
            val members = j.optString("m").split(",").filter { it.isNotBlank() }
            val admin = j.optString("a")
            val ex = groups.get(gId)
            val myId = me
            if (ex == null) {
                val inv = AppSettings.now.groupInvites
                val allowed = inv == "everyone" || (inv == "contacts" && dao.countFor(senderId) > 0)
                if (!allowed) return Rx(Outcome.DROP)
                if (senderId == admin && senderId in members && myId in members) {
                    groups.upsert(GroupEntity(gId, j.optString("n").take(100), admin, members.joinToString(","), j.optLong("ttl", 0), sentAt, j.optString("av").takeIf { it.isNotBlank() && validFileId(it) }))
                    systemNote(gId, "اتضفت للمجموعة «${j.optString("n")}»")
                }
            } else if (senderId == ex.adminId) {
                if (myId !in members) { dao.deleteChat("g:$gId"); groups.delete(gId) }
                else groups.upsert(ex.copy(name = j.optString("n").take(100), members = members.joinToString(","), ttlSec = j.optLong("ttl", 0), avatarFileId = j.optString("av").takeIf { it.isNotBlank() && validFileId(it) }))
            }
            return Rx(Outcome.DUP, msgId = msgId)
        }
        if (ctl == "gleave") {
            val ex = groups.get(j.optString("g"))
            if (ex != null && senderId in ex.memberList()) {
                val rest = ex.memberList().filter { it != senderId }
                val newAdmin = if (senderId == ex.adminId) rest.firstOrNull() ?: ex.adminId else ex.adminId
                groups.upsert(ex.copy(members = rest.joinToString(","), adminId = newAdmin))
                systemNote(ex.groupId, "عضو خرج من المجموعة")
            }
            return Rx(Outcome.DUP, msgId = msgId)
        }

        // ---- المحادثة المستهدفة: عادية (الطرف التاني) أو مجموعة (لازم أكون عضو والمرسل عضو)
        val gKey = j.strOrNull("g")
        val pid: String
        if (gKey != null) {
            val grp = groups.get(gKey)
                ?: return if (System.currentTimeMillis() - sentAt < 10 * 60_000) Rx(Outcome.RETRY) else Rx(Outcome.DROP)
            if (senderId !in grp.memberList()) return Rx(Outcome.DROP)
            pid = "g:$gKey"
        } else {
            pid = senderId
            // "تجاهل الغرباء": رسالة جديدة من حد ماليش معاه محادثة سابقة
            if (AppSettings.now.blockUnknown && ctl.isEmpty() && dao.countFor(senderId) == 0) return Rx(Outcome.DROP)
        }

        if (ctl.isNotEmpty()) {
            // تحكّم: بيتطبّق على رسايل نفس المحادثة بس، وعلى رسايل المرسل نفسه (تعديل/حذف)
            val tg = j.optString("tg")
            when (ctl) {
                "react" -> dao.setTheirReaction(pid, tg, j.optString("e").ifEmpty { null })
                "edit" -> dao.editBody(pid, tg, j.optString("b"), false, senderId)
                "del" -> dao.markDeleted(pid, tg, false, senderId)
                // إيصال القراءة: الطرف التاني قرأ رسايلي دي (محادثات عادية بس)
                "rd" -> if (gKey == null) j.optJSONArray("ids")?.let { a ->
                    dao.setReadByPeer(pid, (0 until minOf(a.length(), 200)).map { a.optString(it) })
                }
                // إيصال الفتح: الطرف التاني فتح ميديا مرة واحدة/مؤقتة
                "op" -> if (gKey == null) dao.markOpenedMine(pid, tg, System.currentTimeMillis())
                "vote" -> if (gKey != null && dao.peerOf(tg) == pid) groups.vote(PollVote(tg, senderId, j.optInt("i", -1)))
            }
            return Rx(Outcome.DUP, msgId = msgId)   // اتأكّد وتتمسح من الصندوق من غير إشعار
        }
        val msg = LocalMessage(
            msgId = msgId, peerId = pid, mine = false,
            body = j.strOrNull("b")?.take(20_000), mediaFileId = j.strOrNull("f")?.takeIf(::validFileId), mediaType = j.strOrNull("m")?.take(12),
            createdAt = sentAt,
            status = MsgStatus.DELIVERED, read = (openPeerId == pid),
            replyToId = j.strOrNull("r")?.take(64), replyText = j.strOrNull("rt")?.take(200),
            senderId = if (gKey != null) senderId else null,
            viewMode = j.optInt("vm", 0).coerceIn(0, 2), viewSecs = j.optInt("vs", 0).coerceIn(0, 7 * 86400),
            expireAt = j.optInt("ex", 0).coerceIn(0, 30 * 86400).let { if (it > 0 && gKey == null) System.currentTimeMillis() + it * 1000L else 0L },
        )
        val rowId = dao.insert(msg)
        if (rowId == -1L) return Rx(Outcome.DUP, msgId = msgId)
        val saved = msg.copy(id = rowId)
        if (j.optInt("sl", 0) != 1) onIncoming?.invoke(saved, prof)   // إرسال صامت: من غير إشعار
        return Rx(Outcome.NEW, saved, msgId)
    }

    /** معرّف ملف تيليجرام: حروف وأرقام و _ - بس (أو multi: لقطع كتير) — بيمنع أي حقن في الروابط. */
    private fun validFileId(f: String): Boolean {
        val ids = if (f.startsWith("multi:")) f.removePrefix("multi:").split("|") else listOf(f)
        return ids.size in 1..200 && ids.all { it.length in 8..300 && it.all { c -> c.isLetterOrDigit() && c.code < 128 || c == '_' || c == '-' } }
    }

    private fun JSONObject.strOrNull(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null

    /** يسحب كل الرسايل الغايبة من الصندوق، يفكّها، يخزّنها محليًا، ويمسحها من السيرفر. */
    suspend fun drainMailbox() = drainLock.withLock {
        val uid = runCatching { me }.getOrNull() ?: return@withLock
        val rows = runCatching {
            Supa.client.from("mailbox").select {
                filter { eq("receiver_id", uid) }
                order("id", Order.ASCENDING)
                limit(500)
            }.decodeList<MailboxRow>()
        }.getOrElse { return@withLock }

        for (r in rows) {
            val rx = receiveCipher(r.senderId, r.ciphertext)
            if (rx.outcome != Outcome.RETRY) {   // NEW / DUP / DROP: مفيش داعي تفضل على السيرفر
                runCatching { Supa.client.from("mailbox").delete { filter { eq("id", r.id) } } }
            }
        }
    }

    private suspend fun listenMailbox(uid: String) {
        val ch = Supa.client.channel("mailbox-$uid")
        val flow = ch.postgresChangeFlow<PostgresAction.Insert>(schema = "public") { table = "mailbox" }
        ch.subscribe()
        flow.collect { drainMailbox() }
    }

    // ------------------------------------------------------------------ اتصال P2P (DataChannel)

    private class Link(val peerId: String, val sessionId: String) {
        @Volatile var open = false
        val createdAt = System.currentTimeMillis()
        var pc: PeerConnection? = null
        var dc: DataChannel? = null
        var remoteSet = false
        val pendingIce = mutableListOf<IceCandidate>()

        fun sendJson(o: JSONObject) {
            val ch = dc ?: error("no channel")
            check(ch.send(DataChannel.Buffer(ByteBuffer.wrap(o.toString().toByteArray()), false))) { "send failed" }
        }

        fun close() {
            open = false
            runCatching { dc?.close() }; runCatching { dc?.dispose() }
            runCatching { pc?.close() }
            dc = null; pc = null
        }
    }

    /** الطرف صاحب الـ id الأصغر هو اللي بيعمل offer دايمًا (علشان منتعارضش). */
    fun ensureLink(peerId: String) {
        if (peerId == me) return
        if (isGroup(peerId)) {
            return   // المجموعات: مبنفتحش P2P جديد (بيستهلك داتا كتير)، بنستخدم الاتصالات المفتوحة والصندوق
        }
        val l = links[peerId]
        if (l != null && (l.open || System.currentTimeMillis() - l.createdAt < 15_000)) return
        l?.close(); links.remove(peerId)
        scope.launch {
            runCatching {
                if (me < peerId) startAsOfferer(peerId)
                else sendSignal(UUID.randomUUID().toString(), peerId, "hello", null)
            }
        }
    }

    private suspend fun listenSignals(uid: String) {
        val ch = Supa.client.channel("chatsig-$uid")
        val flow = ch.postgresChangeFlow<PostgresAction.Insert>(schema = "public") { table = "call_signals" }
        ch.subscribe()
        flow.collect { change ->
            val s = runCatching { change.decodeRecord<CallSignal>() }.getOrNull() ?: return@collect
            if (s.toId == uid && s.purpose == "chat") runCatching { handleSignal(s) }
        }
    }

    private suspend fun handleSignal(s: CallSignal) {
        val payload = s.payload as? JsonObject
        when (s.type) {
            "hello" -> {
                val existing = links[s.fromId]
                if (me < s.fromId && !(existing?.open == true)) startAsOfferer(s.fromId)
            }
            "offer" -> {
                val sdp = payload?.get("sdp")?.jsonPrimitive?.contentOrNull ?: return
                links.remove(s.fromId)?.close()
                val link = Link(s.fromId, s.callId)
                links[s.fromId] = link
                val pc = newPc(link)
                link.pc = pc
                pc.setRemote(SessionDescription(SessionDescription.Type.OFFER, sdp))
                link.remoteSet = true
                drainIce(link)
                val answer = pc.createAnswerAwait()
                pc.setLocal(answer)
                sendSignal(s.callId, s.fromId, "answer", buildJsonObject { put("sdp", answer.description) })
            }
            "answer" -> {
                val link = links[s.fromId] ?: return
                if (link.sessionId != s.callId) return
                val sdp = payload?.get("sdp")?.jsonPrimitive?.contentOrNull ?: return
                link.pc?.setRemote(SessionDescription(SessionDescription.Type.ANSWER, sdp))
                link.remoteSet = true
                drainIce(link)
            }
            "ice" -> {
                val link = links[s.fromId] ?: return
                if (link.sessionId != s.callId) return
                val cand = IceCandidate(
                    payload?.get("sdpMid")?.jsonPrimitive?.contentOrNull,
                    payload?.get("sdpMLineIndex")?.jsonPrimitive?.intOrNull ?: 0,
                    payload?.get("candidate")?.jsonPrimitive?.contentOrNull ?: return,
                )
                if (link.remoteSet) link.pc?.addIceCandidate(cand) else synchronized(link.pendingIce) { link.pendingIce += cand }
            }
        }
    }

    private fun drainIce(link: Link) {
        val list = synchronized(link.pendingIce) { link.pendingIce.toList().also { link.pendingIce.clear() } }
        list.forEach { link.pc?.addIceCandidate(it) }
    }

    private suspend fun startAsOfferer(peerId: String) {
        links.remove(peerId)?.close()
        val sid = UUID.randomUUID().toString()
        val link = Link(peerId, sid)
        links[peerId] = link
        val pc = newPc(link)
        link.pc = pc
        attachChannel(link, pc.createDataChannel("chat", DataChannel.Init().apply { ordered = true }))
        val offer = pc.createOfferAwait()
        pc.setLocal(offer)
        sendSignal(sid, peerId, "offer", buildJsonObject { put("sdp", offer.description) })
    }

    private fun newPc(link: Link): PeerConnection {
        val cfg = PeerConnection.RTCConfiguration(CallManager.iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        return CallManager.factory.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                scope.launch {
                    runCatching {
                        sendSignal(link.sessionId, link.peerId, "ice", buildJsonObject {
                            put("sdpMid", c.sdpMid ?: ""); put("sdpMLineIndex", c.sdpMLineIndex); put("candidate", c.sdp)
                        })
                    }
                }
            }
            override fun onDataChannel(d: DataChannel) { attachChannel(link, d) }
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
                if (s == PeerConnection.PeerConnectionState.FAILED || s == PeerConnection.PeerConnectionState.CLOSED ||
                    s == PeerConnection.PeerConnectionState.DISCONNECTED
                ) {
                    link.open = false
                    if (links[link.peerId] === link) links.remove(link.peerId)
                    link.close()
                }
            }
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) {}
        })!!
    }

    private fun attachChannel(link: Link, dc: DataChannel) {
        link.dc = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previous: Long) {}
            override fun onStateChange() { link.open = dc.state() == DataChannel.State.OPEN }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                scope.launch { onChannelMessage(link, String(bytes)) }
            }
        })
        link.open = dc.state() == DataChannel.State.OPEN
    }

    private suspend fun onChannelMessage(link: Link, text: String) {
        val j = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (j.optString("k")) {
            "msg" -> {
                val rx = receiveCipher(link.peerId, j.optString("c"))
                val id = rx.msgId
                if (id != null && (rx.outcome == Outcome.NEW || rx.outcome == Outcome.DUP)) {
                    runCatching { link.sendJson(JSONObject().put("k", "ack").put("id", id)) }
                }
            }
            "typing" -> onTyping(link.peerId)
            "ack" -> j.optString("id").takeIf { it.isNotEmpty() }?.let {
                if (acked.size > 4000) acked.clear()
                acked.add("${link.peerId}:$it")
                dao.setStatus(it, MsgStatus.DELIVERED)
            }
        }
    }

    private suspend fun sendSignal(callId: String, to: String, type: String, payload: JsonObject?) {
        Supa.client.from("call_signals").insert(
            NewCallSignal(callId, me, to, type, false, payload, purpose = "chat")
        )
    }

    // ------------------------------------------------------------------ SDP helpers

    private open class Sdp : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) {}
        override fun onSetFailure(e: String?) {}
    }

    private suspend fun PeerConnection.createOfferAwait(): SessionDescription =
        suspendCancellableCoroutine { c ->
            createOffer(object : Sdp() {
                override fun onCreateSuccess(d: SessionDescription) = c.resumeWith(Result.success(d))
                override fun onCreateFailure(e: String?) = c.resumeWith(Result.failure(RuntimeException(e)))
            }, MediaConstraints())
        }

    private suspend fun PeerConnection.createAnswerAwait(): SessionDescription =
        suspendCancellableCoroutine { c ->
            createAnswer(object : Sdp() {
                override fun onCreateSuccess(d: SessionDescription) = c.resumeWith(Result.success(d))
                override fun onCreateFailure(e: String?) = c.resumeWith(Result.failure(RuntimeException(e)))
            }, MediaConstraints())
        }

    private suspend fun PeerConnection.setLocal(d: SessionDescription) =
        suspendCancellableCoroutine { c ->
            setLocalDescription(object : Sdp() {
                override fun onSetSuccess() = c.resumeWith(Result.success(Unit))
                override fun onSetFailure(e: String?) = c.resumeWith(Result.failure(RuntimeException(e)))
            }, d)
        }

    private suspend fun PeerConnection.setRemote(d: SessionDescription) =
        suspendCancellableCoroutine { c ->
            setRemoteDescription(object : Sdp() {
                override fun onSetSuccess() = c.resumeWith(Result.success(Unit))
                override fun onSetFailure(e: String?) = c.resumeWith(Result.failure(RuntimeException(e)))
            }, d)
        }
}
