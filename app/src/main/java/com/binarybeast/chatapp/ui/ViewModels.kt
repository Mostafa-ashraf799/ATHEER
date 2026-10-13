package com.binarybeast.chatapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.binarybeast.chatapp.data.AuthRepository
import com.binarybeast.chatapp.data.CallLog
import com.binarybeast.chatapp.data.ChatPreview
import com.binarybeast.chatapp.data.ChatEngine
import com.binarybeast.chatapp.data.local.LocalMessage
import com.binarybeast.chatapp.data.Profile
import com.binarybeast.chatapp.data.ProfileRepository
import com.binarybeast.chatapp.data.StoryGroup
import com.binarybeast.chatapp.data.StoryRepository
import com.binarybeast.chatapp.data.Supa
import com.binarybeast.chatapp.data.TelegramStorage
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ Session / Profile

class SessionVm : ViewModel() {
    private val _me = MutableStateFlow<Profile?>(null)
    val me: StateFlow<Profile?> = _me.asStateFlow()

    fun load() = viewModelScope.launch {
        runCatching { ProfileRepository.me() }.onSuccess { _me.value = it }
        ProfileRepository.touchLastSeen()
    }

    /** يرجّع null لو نجح أو رسالة خطأ. */
    suspend fun saveUsername(raw: String): String? {
        val err = ProfileRepository.setUsername(raw)
        if (err == null) load()
        return err
    }

    fun saveProfile(name: String, about: String) = viewModelScope.launch {
        runCatching { ProfileRepository.updateProfile(name = name, about = about) }
        load()
    }

    fun setAvatar(bytes: ByteArray, mime: String) = viewModelScope.launch {
        runCatching {
            val id = TelegramStorage.upload(bytes, "avatar.jpg", mime, "image", "avatar")
            ProfileRepository.updateProfile(avatarFileId = id)
        }
        load()
    }

    fun setSetting(key: String, value: Any) = viewModelScope.launch {
        runCatching { ProfileRepository.updateSetting(_me.value?.settings, key, value) }
        load()
    }

    fun signOut() = viewModelScope.launch {
        ChatEngine.wipeLocal()          // امسح الرسايل المحلية والمفاتيح المؤقتة من الجهاز
        AuthRepository.signOut()
    }
}

// ------------------------------------------------------------------ Login

class LoginVm : ViewModel() {
    val waiting = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    private var token: String? = null
    private var url: String? = null
    private var pollJob: kotlinx.coroutines.Job? = null

    /** بيعمل طلب دخول جديد، يفتح رابط البوت، ويبدأ يسأل السيرفر كل ثانيتين. */
    fun start(openUrl: (String) -> Unit) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            error.value = null; waiting.value = true
            val (t, u) = runCatching { AuthRepository.startTelegramLogin() }.getOrElse {
                error.value = "تعذّر بدء الدخول: ${it.message}"; waiting.value = false; return@launch
            }
            token = t; url = u
            openUrl(u)
            val deadline = System.currentTimeMillis() + 5 * 60_000
            while (System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(2000)
                val ok = runCatching { AuthRepository.pollTelegramLogin(t) }.getOrElse {
                    error.value = "الطلب انتهى. جرّب تاني."; waiting.value = false; return@launch
                }
                if (ok) { waiting.value = false; return@launch }   // الـ session اتفتحت، AppRoot هيتنقل لوحده
            }
            error.value = "الوقت خلص. جرّب تاني."; waiting.value = false
        }
    }

    fun reopen(openUrl: (String) -> Unit) { url?.let(openUrl) }

    fun cancel() { pollJob?.cancel(); waiting.value = false; token = null; url = null }
}

// ------------------------------------------------------------------ Chats

class ChatListVm : ViewModel() {
    private val _chats = MutableStateFlow<List<ChatPreview>>(emptyList())
    val chats: StateFlow<List<ChatPreview>> = _chats.asStateFlow()
    val searchError = MutableStateFlow<String?>(null)

    init {
        // قايمة المحادثات بتتبني من قاعدة البيانات المحلية وبتتحدّث لحظيًا
        viewModelScope.launch {
            ChatEngine.dao.observeAll().collect { all ->
                val previews = all.groupBy { it.peerId }.mapNotNull { (peerId, msgs) ->
                    val peer = ChatEngine.peerProfile(peerId) ?: return@mapNotNull null
                    ChatPreview(peer, msgs.first(), msgs.count { !it.mine && !it.read })
                }.sortedByDescending { it.last.createdAt }
                _chats.value = previews
            }
        }
    }

    fun load() { /* مفيش حاجة: الـ Flow بيحدّث لوحده */ }

    /** ينشئ مجموعة من أرقام (بفاصلة أو سطر جديد). الأرقام اللي مش مسجّلة بتتجاهل وبنقول كام واحد اتجاهل. */
    fun createGroup(name: String, phonesRaw: String, onDone: (Profile?) -> Unit) = viewModelScope.launch {
        searchError.value = null
        val phones = phonesRaw.split(',', '\n', '،', ' ').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val found = phones.mapNotNull { runCatching { ProfileRepository.findByPhone(it) }.getOrNull() }
            .filter { it.id != AuthRepository.currentUserId }
        if (name.isBlank() || found.isEmpty()) { searchError.value = "اكتب اسم وأرقام مسجّلة في التطبيق"; onDone(null); return@launch }
        if (found.size < phones.size) searchError.value = "${phones.size - found.size} رقم مش مسجّل واتجاهل"
        onDone(ChatEngine.createGroup(name, found))
    }

    /** يدوّر على مستخدم برقمه ويفتح معاه محادثة. */
    fun findUser(phone: String, onFound: (Profile) -> Unit) = viewModelScope.launch {
        searchError.value = null
        val p = runCatching { ProfileRepository.find(phone) }.getOrNull()
        if (p == null) searchError.value = "الرقم ده مش مسجّل في التطبيق"
        else if (p.id == AuthRepository.currentUserId) searchError.value = "ده رقمك انت 🙂"
        else onFound(p)
    }
}

class ChatVm(val peer: Profile) : ViewModel() {
    private val _messages = MutableStateFlow<List<LocalMessage>>(emptyList())
    val messages: StateFlow<List<LocalMessage>> = _messages.asStateFlow()
    val sending = MutableStateFlow(false)

    val typing = ChatEngine.typing
    val isGroup = ChatEngine.isGroup(peer.id)
    val group = MutableStateFlow<com.binarybeast.chatapp.data.local.GroupEntity?>(null)
    val votes = MutableStateFlow<List<com.binarybeast.chatapp.data.local.PollVote>>(emptyList())
    val names = MutableStateFlow<Map<String, String>>(emptyMap())   // id -> اسم (لعرض المرسل في المجموعات)

    init {
        ChatEngine.openPeerId = peer.id
        ChatEngine.ensureLink(peer.id)          // افتح قناة P2P مع الطرف التاني لو أونلاين
        if (isGroup) {
            val gid = peer.id.removePrefix("g:")
            viewModelScope.launch {
                ChatEngine.groups.observe(gid).collect { g ->
                    group.value = g
                    g?.memberList()?.forEach { id ->
                        if (names.value[id] == null) {
                            val p = ChatEngine.profile(id)
                            if (p != null) names.update { it + (id to p.displayName.ifBlank { p.phone }) }
                        }
                    }
                }
            }
            viewModelScope.launch { ChatEngine.groups.observeVotes(peer.id).collect { votes.value = it } }
        }
        viewModelScope.launch {
            ChatEngine.dao.observe(peer.id).collect { list ->
                _messages.value = list
                if (list.any { !it.mine && !it.read }) ChatEngine.markRead(peer.id)
            }
        }
    }

    override fun onCleared() {
        if (ChatEngine.openPeerId == peer.id) ChatEngine.openPeerId = null
    }

    /** إرسال صامت: المستلم مبيجيلوش إشعار. */
    var silent = false

    fun sendText(text: String, reply: LocalMessage? = null) {
        if (text.isBlank()) return
        viewModelScope.launch { ChatEngine.send(peer, text.trim(), reply = reply, silent = silent) }
    }

    fun react(m: LocalMessage, emoji: String) { viewModelScope.launch { ChatEngine.react(peer, m, emoji) } }
    fun edit(m: LocalMessage, newBody: String) { viewModelScope.launch { ChatEngine.editMessage(peer, m, newBody) } }
    fun deleteForEveryone(m: LocalMessage) { viewModelScope.launch { ChatEngine.deleteForEveryone(peer, m) } }
    fun vote(m: LocalMessage, option: Int) { viewModelScope.launch { ChatEngine.vote(peer, m, option) } }
    fun sendPoll(q: String, options: List<String>) { viewModelScope.launch { ChatEngine.sendPoll(peer, q, options) } }
    fun renameGroup(name: String) { viewModelScope.launch { ChatEngine.updateGroup(peer.id.removePrefix("g:"), name = name) } }
    fun setTtl(sec: Long) { viewModelScope.launch { ChatEngine.updateGroup(peer.id.removePrefix("g:"), ttlSec = sec) } }
    fun setGroupAvatar(bytes: ByteArray, mime: String) { viewModelScope.launch {
        runCatching {
            val id = TelegramStorage.upload(bytes, "group.jpg", mime, "image", "avatar")
            ChatEngine.updateGroup(peer.id.removePrefix("g:"), avatar = id)
        }.onFailure { notice.value = "تعذّر رفع صورة المجموعة" }
    } }
    fun removeMember(id: String) { viewModelScope.launch { ChatEngine.updateGroup(peer.id.removePrefix("g:"), remove = id) } }
    fun addMembers(phonesRaw: String) = viewModelScope.launch {
        val found = phonesRaw.split(',', '\n', '،', ' ').map { it.trim() }.filter { it.isNotEmpty() }
            .mapNotNull { runCatching { ProfileRepository.find(it) }.getOrNull() }
        if (found.isEmpty()) notice.value = "مفيش أرقام مسجّلة" else ChatEngine.updateGroup(peer.id.removePrefix("g:"), add = found.map { it.id })
    }
    fun leaveGroup(onDone: () -> Unit) { viewModelScope.launch { ChatEngine.leaveGroup(peer.id.removePrefix("g:")); onDone() } }
    fun deleteForMe(m: LocalMessage) { viewModelScope.launch { ChatEngine.deleteForMe(m) } }

    /** رسالة نصها/خطأها بيظهر للمستخدم (مثلًا: الملف أكبر من 20 ميجا). */
    val notice = MutableStateFlow<String?>(null)

    fun sendMedia(bytes: ByteArray, name: String, mime: String, caption: String? = null, viewMode: Int = 0, viewSecs: Int = 0) = viewModelScope.launch {
        sending.value = true
        notice.value = null
        runCatching {
            val kind = when {
                mime.startsWith("image/") -> "image"
                mime.startsWith("video/") -> "video"
                mime.startsWith("audio/") -> "audio"
                else -> "file"
            }
            // جودة "عالية" للصور بتترفع كـ document علشان تيليجرام ميصغّرهاش
            val uploadKind = if (kind == "image" && com.binarybeast.chatapp.data.AppSettings.now.uploadQuality == "high") "image_hq" else kind
            val id = TelegramStorage.upload(bytes, name, mime, uploadKind)
            ChatEngine.send(peer, body = caption, fileId = id, mediaType = kind, viewMode = if (kind == "file") 0 else viewMode, viewSecs = viewSecs, silent = silent)
        }.onFailure { notice.value = it.message?.takeIf { m -> m.contains("20") } ?: "تعذّر إرسال الملف" }
        sending.value = false
    }
}

// ------------------------------------------------------------------ Stories

class StoriesVm : ViewModel() {
    private val _groups = MutableStateFlow<List<StoryGroup>>(emptyList())
    val groups: StateFlow<List<StoryGroup>> = _groups.asStateFlow()
    val uploading = MutableStateFlow(false)
    val notice = MutableStateFlow<String?>(null)

    init { load() }

    fun load() = viewModelScope.launch {
        runCatching { StoryRepository.feed() }.onSuccess { _groups.value = it }
    }

    fun post(bytes: ByteArray, name: String, mime: String, caption: String?, hours: Double) = viewModelScope.launch {
        uploading.value = true
        notice.value = null
        val active = runCatching { StoryRepository.myActiveCount() }.getOrDefault(0)
        if (active >= StoryRepository.MAX_ACTIVE) {
            notice.value = "عندك ${StoryRepository.MAX_ACTIVE} ستوريز شغّالة، احذف واحدة أو استنى تخلص"
            uploading.value = false
            return@launch
        }
        runCatching {
            val kind = if (mime.startsWith("video/")) "video" else "image"
            val uploadKind = if (kind == "image" && com.binarybeast.chatapp.data.AppSettings.now.uploadQuality == "high") "image_hq" else kind
            val id = TelegramStorage.upload(bytes, name, mime, uploadKind, "story")
            StoryRepository.post(id, kind, caption, hours)
        }.onFailure { notice.value = "تعذّر نشر الستوري" }
        uploading.value = false
        load()
    }

    fun seen(id: Long) = viewModelScope.launch { StoryRepository.markSeen(id) }

    fun delete(id: Long) = viewModelScope.launch {
        runCatching { StoryRepository.delete(id) }
        load()
    }
}

// ------------------------------------------------------------------ Calls history

data class CallRow(val log: CallLog, val peer: Profile?, val outgoing: Boolean)

class CallsVm : ViewModel() {
    private val _rows = MutableStateFlow<List<CallRow>>(emptyList())
    val rows: StateFlow<List<CallRow>> = _rows.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        val uid = AuthRepository.currentUserId ?: return@launch
        runCatching {
            val logs = Supa.client.from("call_logs").select {
                filter { or { eq("caller_id", uid); eq("callee_id", uid) } }
                order("started_at", Order.DESCENDING)
                limit(100)
            }.decodeList<CallLog>()
            val peers = ProfileRepository.byIds(
                logs.map { if (it.callerId == uid) it.calleeId else it.callerId }.toSet()
            ).associateBy { it.id }
            _rows.value = logs.map {
                val outgoing = it.callerId == uid
                CallRow(it, peers[if (outgoing) it.calleeId else it.callerId], outgoing)
            }
        }
    }
}
