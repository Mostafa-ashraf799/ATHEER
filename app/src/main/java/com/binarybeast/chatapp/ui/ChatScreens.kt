package com.binarybeast.chatapp.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.binarybeast.chatapp.call.CallManager
import com.binarybeast.chatapp.data.AppSettings
import com.binarybeast.chatapp.data.ChatEngine
import com.binarybeast.chatapp.data.Profile
import com.binarybeast.chatapp.data.TelegramStorage
import com.binarybeast.chatapp.data.local.LocalMessage
import com.binarybeast.chatapp.data.local.MsgStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** يقرأ ملف من Uri ويرجّع (bytes, name, mime).  */
fun readUri(ctx: Context, uri: Uri): Triple<ByteArray, String, String> {
    val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    val size = runCatching { ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
    // مفيش حد من التطبيق (الملفات الكبيرة بتتقسّم عند الرفع)؛ بس حماية من نفاد ذاكرة الجهاز
    require(size < 0 || size <= 400L * 1024 * 1024) { "الملف أكبر من اللي ذاكرة الجهاز تستحمله (400 ميجا)" }
    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
    return Triple(bytes, name, mime)
}

// ======================================================================= قايمة المحادثات

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatListScreen(onOpen: (Profile) -> Unit, vm: ChatListVm = viewModel()) {
    val chats by vm.chats.collectAsState()
    val searchError by vm.searchError.collectAsState()
    var showNew by remember { mutableStateOf(false) }
    var phone by remember { mutableStateOf("") }
    val prefs by AppSettings.prefs.collectAsState()
    var showArchived by remember { mutableStateOf(false) }
    var showGroup by remember { mutableStateOf(false) }
    var gName by remember { mutableStateOf("") }
    var gPhones by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<String?>(null) }
    val myId0 = com.binarybeast.chatapp.data.AuthRepository.currentUserId
    val visible = chats
        .filter { it.peer.id != myId0 }
        .filter { (it.peer.id in prefs.archived) == showArchived }
        .sortedWith(compareByDescending<com.binarybeast.chatapp.data.ChatPreview> { it.peer.id in prefs.pinned }.thenByDescending { it.last.createdAt })

    Scaffold(
        topBar = {
            GlassTopBar(
                title = { Text(if (showArchived) "المؤرشفة" else "الشات", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { showGroup = true }) { Icon(AIcons.GroupAdd, "مجموعة جديدة") }
                    IconButton(onClick = { showArchived = !showArchived }) {
                        Icon(if (showArchived) AIcons.Unarchive else AIcons.Archive, "المؤرشفة")
                    }
                },
            )
        },
        floatingActionButton = {
            GlassCircleButton(AIcons.PersonAdd, { showNew = true }, size = 60.dp, accent = true, contentDescription = "محادثة جديدة")
        },
    ) { pad ->
        if (visible.isEmpty() && showArchived) {
            Box(Modifier.padding(pad).fillMaxSize(), Alignment.Center) {
                Text(if (showArchived) "مفيش محادثات مؤرشفة." else "مفيش محادثات لسه.\nدوس على ➕ وابدأ برقم صاحبك.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else LazyColumn(Modifier.padding(pad)) {
            if (!showArchived) item {
                // «الرسائل المحفوظة»: محادثة مع نفسك، محلية بالكامل
                val savedLast = chats.firstOrNull { it.peer.id == myId0 }?.last
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(ChatEngine.savedProfile()) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                        Text("🔖", style = MaterialTheme.typography.titleLarge)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text("الرسائل المحفوظة", fontWeight = FontWeight.SemiBold)
                        Text(
                            savedLast?.body ?: "احفظ رسايل وملاحظات ليك انت بس",
                            maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (visible.isEmpty() && !showArchived) item {
                Text("مفيش محادثات لسه.\nدوس على ➕ وابدأ برقم صاحبك.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(visible, key = { it.peer.id }) { c ->
                Row(
                    Modifier.fillMaxWidth().combinedClickable(onClick = { onOpen(c.peer) }, onLongClick = { menuFor = c.peer.id }).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(c.peer.avatarFileId, c.peer.displayName.ifBlank { c.peer.phone }, 52.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(
                            c.peer.displayName.ifBlank { c.peer.phone },
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (prefs.hideListPreview) "••••••" else if (c.last.deleted) "🚫 الرسالة اتمسحت" else c.last.body ?: when (c.last.mediaType) {
                                "image" -> "📷 صورة"; "video" -> "🎬 فيديو"; "audio" -> "🎤 رسالة صوتية"; "poll" -> "📊 استطلاع"; else -> "📎 ملف"
                            },
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (c.peer.id in prefs.muted) Text("🔕", style = MaterialTheme.typography.labelSmall)
                            if (c.peer.id in prefs.pinned) Icon(AIcons.PushPin, null, Modifier.size(12.dp))
                            Text(hhmmMs(c.last.createdAt), style = MaterialTheme.typography.labelSmall)
                        }
                        DropdownMenu(expanded = menuFor == c.peer.id, onDismissRequest = { menuFor = null }) {
                            DropdownMenuItem(
                                text = { Text(if (c.peer.id in prefs.pinned) "إلغاء التثبيت" else "تثبيت") },
                                onClick = { AppSettings.togglePin(c.peer.id); menuFor = null },
                            )
                            DropdownMenuItem(
                                text = { Text(if (c.peer.id in prefs.muted) "تشغيل الإشعارات" else "كتم الإشعارات") },
                                onClick = { AppSettings.toggleMute(c.peer.id); menuFor = null },
                            )
                            DropdownMenuItem(
                                text = { Text(if (c.peer.id in prefs.archived) "إلغاء الأرشفة" else "أرشفة") },
                                onClick = { AppSettings.toggleArchive(c.peer.id); menuFor = null },
                            )
                        }
                        if (c.unread > 0) {
                            Spacer(Modifier.height(4.dp))
                            Box(
                                Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primary).size(22.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text("${c.unread}", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }
        }
    }

    if (showGroup) AlertDialog(
        onDismissRequest = { showGroup = false },
        title = { Text("مجموعة جديدة") },
        text = {
            Column {
                OutlinedTextField(value = gName, onValueChange = { gName = it }, label = { Text("اسم المجموعة") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = gPhones, onValueChange = { gPhones = it }, label = { Text("أرقام الأعضاء (بفاصلة أو سطر جديد)") },
                    minLines = 3, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
                searchError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.createGroup(gName, gPhones) { p -> if (p != null) { showGroup = false; gName = ""; gPhones = ""; onOpen(p) } }
            }) { Text("إنشاء") }
        },
        dismissButton = { TextButton(onClick = { showGroup = false }) { Text("إلغاء") } },
    )

    if (showNew) AlertDialog(
        onDismissRequest = { showNew = false },
        title = { Text("محادثة جديدة") },
        text = {
            Column {
                OutlinedTextField(
                    value = phone, onValueChange = { phone = it },
                    label = { Text("رقم الموبايل أو @يوزر_نيم") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
                searchError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.findUser(phone) { showNew = false; phone = ""; onOpen(it) } }) { Text("ابدأ") }
        },
        dismissButton = { TextButton(onClick = { showNew = false }) { Text("إلغاء") } },
    )
}

// ======================================================================= شاشة المحادثة

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(peer: Profile, onBack: () -> Unit) {
    val wp by com.binarybeast.chatapp.data.AppSettings.prefs.collectAsState()
    Box(Modifier.fillMaxSize()) {
        ChatWallpaper(wp.wallpaper)
        ChatScreenInner(peer, onBack)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ChatScreenInner(peer: Profile, onBack: () -> Unit) {
    val vm: ChatVm = viewModel(key = "chat-${peer.id}", factory = viewModelFactory { initializer { ChatVm(peer) } })
    val messages by vm.messages.collectAsState()
    val sending by vm.sending.collectAsState()
    val notice by vm.notice.collectAsState()
    val prefs by AppSettings.prefs.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var text by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Pair<Uri, Boolean>?>(null) }
    var viewer by remember { mutableStateOf<LocalMessage?>(null) }
    var starOnly by remember { mutableStateOf(false) }
    var silentMode by remember { mutableStateOf(false) }
    var ttlPick by remember { mutableStateOf(false) }
    var schedPick by remember { mutableStateOf(false) }
    var schedList by remember { mutableStateOf(false) }
    var pendingMedia by remember { mutableStateOf<MediaDraft?>(null) }
    var restrictedView by remember { mutableStateOf<Pair<LocalMessage, Int>?>(null) }   // الرسالة + الثواني المتبقية (0 = من غير مؤقّت)
    var attachMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var replyTo by remember { mutableStateOf<LocalMessage?>(null) }
    var editTarget by remember { mutableStateOf<LocalMessage?>(null) }
    var actionMsg by remember { mutableStateOf<LocalMessage?>(null) }
    var confirmDelete by remember { mutableStateOf<LocalMessage?>(null) }
    var searchOn by remember { mutableStateOf(false) }
    val typingSet by vm.typing.collectAsState()
    val grp by vm.group.collectAsState()
    val votes by vm.votes.collectAsState()
    val names by vm.names.collectAsState()
    var showInfo by remember { mutableStateOf(false) }
    var showSafety by remember { mutableStateOf(false) }
    var forwardMsg by remember { mutableStateOf<LocalMessage?>(null) }
    val listVm: ChatListVm = viewModel()
    val allChats by listVm.chats.collectAsState()
    var online by remember { mutableStateOf(false) }
    LaunchedEffect(peer.id) {
        while (!vm.isGroup) { online = ChatEngine.isLinkOpen(peer.id); kotlinx.coroutines.delay(2000) }
    }
    var showPoll by remember { mutableStateOf(false) }
    val myId = com.binarybeast.chatapp.data.AuthRepository.currentUserId
    val recorder = remember { com.binarybeast.chatapp.media.VoiceRecorder(ctx) }
    var recording by remember { mutableStateOf(false) }
    var recMs by remember { mutableStateOf(0L) }
    LaunchedEffect(recording) {
        val t0 = System.currentTimeMillis()
        while (recording) { recMs = System.currentTimeMillis() - t0; kotlinx.coroutines.delay(200) }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (recorder.running) recorder.cancel() } }
    var query by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val shown0 = if (searchOn && query.isNotBlank()) messages.filter { it.body?.contains(query, ignoreCase = true) == true } else messages
    val shown = if (starOnly) shown0.filter { it.starred } else shown0
    val blocked = peer.id in prefs.blocked
    if (showSafety) androidx.compose.material3.AlertDialog(
        onDismissRequest = { showSafety = false },
        title = { Text("التحقق من التشفير") },
        text = {
            val k = peer.publicKey
            Column {
                Text(if (k == null) "مفتاح الطرف التاني مش متاح دلوقتي." else com.binarybeast.chatapp.data.ChatEngine.safetyNumber(k),
                    style = MaterialTheme.typography.titleMedium)
                Text("قارن الرقم ده مع الطرف التاني (مكالمة أو وجهًا لوجه). لو متطابق يبقى مفيش حد معترض الرسايل بينكم.",
                    Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { showSafety = false }) { Text("تمام") } },
    )

    if (ttlPick) AlertDialog(
        onDismissRequest = { ttlPick = false },
        title = { Text("مسح الرسايل تلقائيًا") },
        text = {
            Column {
                listOf(0 to "إيقاف", 3600 to "بعد ساعة", 86400 to "بعد 24 ساعة", 7 * 86400 to "بعد 7 أيام", 30 * 86400 to "بعد 30 يوم").forEach { (sec, label) ->
                    TextButton(onClick = { AppSettings.setChatTtl(peer.id, sec); ttlPick = false }) {
                        Text(label + if (AppSettings.chatTtl(peer.id) == sec) "  ✓" else "")
                    }
                }
                Text("بينطبّق على الرسايل الجاية اللي إنت بتبعتها وبتستقبلها. الطرف التاني بيمسحها عنده كمان.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { ttlPick = false }) { Text("تمام") } },
    )
    if (schedPick) AlertDialog(
        onDismissRequest = { schedPick = false },
        title = { Text("ابعتها بعد…") },
        text = {
            Column {
                listOf(5 to "5 دقايق", 30 to "30 دقيقة", 60 to "ساعة", 180 to "3 ساعات", 720 to "12 ساعة", 1440 to "غدًا").forEach { (min, label) ->
                    TextButton(onClick = {
                        val t = text; text = ""; schedPick = false
                        scope.launch { ChatEngine.schedule(peer, t, System.currentTimeMillis() + min * 60_000L) }
                    }) { Text(label) }
                }
                Text("بتتبعت لوحدها في الميعاد طول ما التطبيق (أو الخدمة الخلفية) شغّال.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { schedPick = false }) { Text("إلغاء") } },
    )
    if (schedList) {
        var items by remember { mutableStateOf<List<com.binarybeast.chatapp.data.local.ScheduledMsg>>(emptyList()) }
        LaunchedEffect(schedList) { items = ChatEngine.scheduled.forPeer(peer.id) }
        AlertDialog(
            onDismissRequest = { schedList = false },
            title = { Text("الرسايل المجدولة") },
            text = {
                Column {
                    if (items.isEmpty()) Text("مفيش رسايل مجدولة.")
                    items.forEach { it0 ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(it0.body, maxLines = 2)
                                Text(hhmmMs(it0.sendAt), style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { scope.launch { ChatEngine.scheduled.delete(it0.id); items = ChatEngine.scheduled.forPeer(peer.id) } }) { Text("إلغاء") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { schedList = false }) { Text("تمام") } },
        )
    }

    // أي صورة/فيديو/صوت بيعدّي على اختيار طريقة العرض قبل الإرسال
    fun queueMedia(bytes: ByteArray, name: String, mime: String, caption: String?) {
        if (mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")) pendingMedia = MediaDraft(bytes, name, mime, caption)
        else vm.sendMedia(bytes, name, mime, caption)
    }
    pendingMedia?.let { d ->
        ViewModeDialog(
            onCancel = { pendingMedia = null },
            onSend = { mode, secs -> pendingMedia = null; vm.sendMedia(d.bytes, d.name, d.mime, d.caption, mode, secs) },
        )
    }
    restrictedView?.let { (m, secs) ->
        RestrictedViewer(m, secs, onClose = {
            restrictedView = null
            if (m.viewMode == 1) scope.launch { ChatEngine.burn(m) }
        })
    }

    val source = rememberMediaSource(
        onMedia = { uri, isVideo -> editing = uri to isVideo },
        onFile = { uri ->
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { readUri(ctx, uri) } }
                    .onSuccess { (b, n, m) -> queueMedia(b, n, m, null) }
                    .onFailure { vm.notice.value = it.message ?: "تعذّر قراءة الملف" }
            }
        },
    )

    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }

    // محرر الصور/الفيديو قبل الإرسال
    editing?.let { (uri, isVideo) ->
        MediaEditorScreen(
            uri = uri, isVideo = isVideo,
            onDone = { em -> editing = null; queueMedia(em.bytes, em.name, em.mime, em.caption) },
            onCancel = { editing = null },
        )
        return
    }

    fun send() {
        if (text.isBlank()) return
        val et = editTarget
        if (et != null) { vm.edit(et, text); editTarget = null } else vm.sendText(text, replyTo)
        replyTo = null; text = ""
    }

    Scaffold(
        topBar = {
            GlassTopBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(AIcons.ArrowBack, "رجوع") } },
                title = {
                    if (searchOn) OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text("ابحث في المحادثة") }, modifier = Modifier.fillMaxWidth(),
                    ) else Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(peer.avatarFileId, peer.displayName.ifBlank { peer.phone }, 38.dp)
                        Column(Modifier.padding(start = 10.dp)) {
                            Text((grp?.name ?: peer.displayName).ifBlank { peer.phone }, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            Text(if (vm.isGroup) "${grp?.memberList()?.size ?: 0} أعضاء" else if (peer.id in typingSet) "بيكتب…" else if (online) "متصل الآن" else peer.username?.let { "@$it" } ?: peer.about, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                color = if (peer.id in typingSet) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { searchOn = !searchOn; query = "" }) {
                        Icon(if (searchOn) AIcons.Close else AIcons.Search, "بحث")
                    }
                    if (!vm.isGroup && peer.id != myId) {
                        IconButton(onClick = { CallManager.startCall(peer.id, video = false) }) { Icon(AIcons.Call, "مكالمة صوت") }
                        IconButton(onClick = { CallManager.startCall(peer.id, video = true) }) { Icon(AIcons.Videocam, "مكالمة فيديو") }
                    } else if (vm.isGroup) IconButton(onClick = { showInfo = true }) { Icon(AIcons.Group, "معلومات المجموعة") }
                    IconButton(onClick = { moreMenu = true }) { Icon(AIcons.MoreVert, "المزيد") }
                    DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (starOnly) "عرض كل الرسايل" else "⭐ الرسايل المميّزة بنجمة") },
                            onClick = { moreMenu = false; starOnly = !starOnly },
                        )
                        DropdownMenuItem(
                            text = { Text(if (silentMode) "🔔 إلغاء الإرسال الصامت" else "🔕 إرسال صامت (من غير إشعار)") },
                            onClick = { moreMenu = false; silentMode = !silentMode; vm.silent = silentMode },
                        )
                        if (!vm.isGroup && peer.id != myId) DropdownMenuItem(
                            text = { Text("⏳ مسح تلقائي للرسايل") },
                            onClick = { moreMenu = false; ttlPick = true },
                        )
                        DropdownMenuItem(
                            text = { Text("🕒 جدولة الرسالة المكتوبة") },
                            onClick = { moreMenu = false; if (text.isNotBlank()) schedPick = true },
                        )
                        DropdownMenuItem(
                            text = { Text("الرسايل المجدولة") },
                            onClick = { moreMenu = false; schedList = true },
                        )
                        DropdownMenuItem(
                            text = { Text("تصدير المحادثة") },
                            onClick = {
                                moreMenu = false
                                val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                                val txt = messages.filter { it.mediaType != "system" }.joinToString("\n") { m ->
                                    val who = if (m.mine) "أنا" else if (vm.isGroup) (names[m.senderId] ?: "؟") else (peer.displayName.ifBlank { peer.phone })
                                    val body = if (m.deleted) "[اتمسحت]" else m.body ?: "[${m.mediaType ?: "ملف"}]"
                                    "[${fmt.format(java.util.Date(m.createdAt))}] $who: $body"
                                }
                                runCatching {
                                    // ملف نصي كامل (من غير حد على عدد الرسايل ولا حجم Intent)
                                    val dir = java.io.File(ctx.cacheDir, "exports").also { it.mkdirs() }
                                    val f = java.io.File(dir, "chat_${System.currentTimeMillis()}.txt").also { it.writeText(txt) }
                                    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
                                    ctx.startActivity(
                                        android.content.Intent.createChooser(
                                            android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                                                .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                            "تصدير المحادثة",
                                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            },
                        )
                        if (!vm.isGroup) DropdownMenuItem(
                            text = { Text("التحقق من التشفير") },
                            onClick = { moreMenu = false; showSafety = true },
                        )
                        if (!vm.isGroup) DropdownMenuItem(
                            text = { Text(if (blocked) "إلغاء الحظر" else "حظر") },
                            onClick = { moreMenu = false; if (blocked) AppSettings.unblock(peer.id) else AppSettings.block(peer.id) },
                        )
                    }
                },
            )
        },
        bottomBar = {
            Surface(color = androidx.compose.ui.graphics.Color.Transparent) {
                Column {
                    if (blocked) Text(
                        "المستخدم ده محظور: مش هتوصلك رسايله ومكالماته.",
                        Modifier.fillMaxWidth().padding(8.dp), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    (editTarget ?: replyTo)?.let { t ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (editTarget != null) AIcons.Edit else AIcons.Reply, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                Text(if (editTarget != null) "تعديل الرسالة" else "رد على", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(t.body ?: "📎 ميديا", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { editTarget = null; replyTo = null; text = "" }) { Icon(AIcons.Close, "إلغاء") }
                        }
                    }
                    notice?.let { Text(it, Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium) }
                                        if (recording) Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { recorder.cancel(); recording = false }) { Icon(AIcons.Delete, "إلغاء", tint = MaterialTheme.colorScheme.error) }
                        Text("● " + "%d:%02d".format(recMs / 60000, (recMs / 1000) % 60), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                        GlassCircleButton(AIcons.Send, {
                            recording = false
                            val f = recorder.stop()
                            if (f != null) scope.launch {
                                val bytes = withContext(Dispatchers.IO) { f.readBytes().also { f.delete() } }
                                queueMedia(bytes, "voice.m4a", "audio/mp4", null)
                            }
                        }, size = 48.dp, accent = true, contentDescription = "إرسال")
                    } else Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box {
                            GlassCircleButton(AIcons.AttachFile, { attachMenu = true }, enabled = !sending, contentDescription = "إرفاق")
                            DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                                DropdownMenuItem(text = { Text("المعرض") }, leadingIcon = { Icon(AIcons.PhotoLibrary, null) },
                                    onClick = { attachMenu = false; source.gallery() })
                                DropdownMenuItem(text = { Text("كاميرا (صورة)") }, leadingIcon = { Icon(AIcons.CameraAlt, null) },
                                    onClick = { attachMenu = false; source.photo() })
                                DropdownMenuItem(text = { Text("كاميرا (فيديو)") }, leadingIcon = { Icon(AIcons.Videocam, null) },
                                    onClick = { attachMenu = false; source.video() })
                                if (vm.isGroup) DropdownMenuItem(text = { Text("استطلاع") }, leadingIcon = { Icon(AIcons.Poll, null) },
                                    onClick = { attachMenu = false; showPoll = true })
                                DropdownMenuItem(text = { Text("ملف") }, leadingIcon = { Icon(AIcons.InsertDriveFile, null) },
                                    onClick = { attachMenu = false; source.file() })
                            }
                        }
                        OutlinedTextField(
                            value = text, onValueChange = { text = it; if (it.isNotBlank()) com.binarybeast.chatapp.data.ChatEngine.sendTyping(peer.id) },
                            placeholder = { Text(if (sending) "جاري الرفع…" else "اكتب رسالة") },
                            modifier = Modifier.weight(1f), maxLines = 4,
                            shape = RoundedCornerShape(26.dp),
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = if (prefs.enterToSend) ImeAction.Send else ImeAction.Default),
                            keyboardActions = KeyboardActions(onSend = { send() }),
                        )
                        if (text.isBlank() && editTarget == null) GlassCircleButton(AIcons.Mic, {
                            if (recorder.start()) recording = true else vm.notice.value = "تعذّر بدء التسجيل (تأكد من إذن المايك)"
                        }, size = 48.dp, accent = true, enabled = !sending && !blocked, contentDescription = "تسجيل صوتي")
                        else GlassCircleButton(AIcons.Send, { send() }, size = 48.dp, accent = true, contentDescription = "إرسال")
                    }
                }
            }
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize().padding(horizontal = 10.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(shown, key = { it.id }) { m ->
                val mine = m.mine
                if (m.mediaType == "system") {
                    Text(m.body ?: "", Modifier.fillMaxWidth().padding(vertical = 4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    return@items
                }
                // في RTL: Start = اليمين، فرسايلي على اليمين
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.Start else Arrangement.End) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (mine) MaterialTheme.colorScheme.primary.copy(alpha = if (LocalDark.current) 0.22f else 0.16f)
                                else MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (mine) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outline),
                        modifier = Modifier.widthIn(max = 290.dp).combinedClickable(
                            onClick = {}, onLongClick = { if (!m.deleted) actionMsg = m },
                        ),
                    ) {
                        Column(Modifier.padding(8.dp)) {
                            if (vm.isGroup && !mine) Text(names[m.senderId] ?: "…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            if (m.deleted) Text("🚫 الرسالة دي اتمسحت", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            m.replyText?.let { rt ->
                                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)) {
                                    Text(rt, Modifier.fillMaxWidth().padding(6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            val fid = m.mediaFileId
                            val restricted = m.viewMode != 0 && m.mediaType in setOf("image", "video", "audio")
                            if (m.mediaType == "gone") Text("⏳ انتهت صلاحية الميديا", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (fid != null && restricted) {
                                val what = when (m.mediaType) { "image" -> "صورة"; "video" -> "فيديو"; else -> "صوت" }
                                val label = if (m.viewMode == 1) "👁 $what تتشاف مرة واحدة" else "⏱ $what مؤقتة (${fmtSecs(m.viewSecs)})"
                                Box(
                                    Modifier.fillMaxWidth().height(80.dp).clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surface)
                                        .clickable(enabled = !mine) {
                                            scope.launch {
                                                val now = System.currentTimeMillis()
                                                val alreadyOpened = m.openedAt > 0
                                                val left = if (m.viewMode == 2) m.viewSecs - ((if (alreadyOpened) now - m.openedAt else 0L) / 1000).toInt() else 0
                                                if ((m.viewMode == 1 && alreadyOpened) || (m.viewMode == 2 && left <= 0)) ChatEngine.burn(m)
                                                else { ChatEngine.markOpened(m); restrictedView = m to left }
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) { Text(if (mine) "🔒 $label" + (if (m.openedAt > 0) "\n✅ اتفتحت" else "") else "$label — اضغط للفتح", Modifier.padding(8.dp)) }
                            } else if (fid != null) when (m.mediaType) {
                                "image" -> {
                                    // لو "التحميل التلقائي" مقفول، الصورة مبتتحمّلش غير لما تدوس عليها
                                    var loaded by remember(m.id) { mutableStateOf(prefs.autoDownloadImages || mine) }
                                    if (loaded) TgImage(
                                        fid,
                                        Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(10.dp)).clickable { viewer = m },
                                        ContentScale.Crop,
                                    ) else Box(
                                        Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(10.dp))
                                            .background(MaterialTheme.colorScheme.surface).clickable { loaded = true },
                                        contentAlignment = Alignment.Center,
                                    ) { Text("📷 اضغط لتحميل الصورة") }
                                }
                                "video" -> Box(
                                    Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surface).clickable { viewer = m },
                                    contentAlignment = Alignment.Center,
                                ) { Icon(AIcons.PlayCircle, "تشغيل", Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary) }
                                "audio" -> VoiceBubble(fid)
                                else -> Text("📎 ملف", fontWeight = FontWeight.Medium)
                            }
                            if (m.mediaType == "poll" && !m.deleted) PollBubble(m, votes.filter { it.msgId == m.msgId }, myId) { idx -> vm.vote(m, idx) }
                            else if (!restricted) m.body?.let { LinkifiedText(it, style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface)) }
                            val rx = listOfNotNull(m.myReaction, m.theirReaction).distinct().joinToString("")
                            if (rx.isNotEmpty()) Text(rx, style = MaterialTheme.typography.bodyMedium)
                            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                                if (m.starred) Text("⭐ ", style = MaterialTheme.typography.labelSmall)
                                if (m.edited) Text("معدّلة · ", style = MaterialTheme.typography.labelSmall)
                                Text(hhmmMs(m.createdAt), style = MaterialTheme.typography.labelSmall)
                                if (mine) {
                                    Spacer(Modifier.size(4.dp))
                                    when (m.status) {
                                        // ✓✓ أخضر = وصلت وجه تأكيد الاستلام
                                        MsgStatus.READ -> Icon(AIcons.DoneAll, null, Modifier.size(14.dp), tint = androidx.compose.ui.graphics.Color(0xFF2196F3))   // أزرق = اتقرت
                                        MsgStatus.DELIVERED -> Icon(AIcons.DoneAll, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        // ✓ اتبعتت (صندوق احتياطي أو P2P بنستنى التأكيد)
                                        MsgStatus.SENT, MsgStatus.P2P_PENDING -> Icon(AIcons.Done, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        MsgStatus.FAILED -> Text("!", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                                        else -> Text("…", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPoll) {
        var q by remember { mutableStateOf("") }
        var opts by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPoll = false },
            title = { Text("استطلاع جديد") },
            text = {
                Column {
                    OutlinedTextField(value = q, onValueChange = { q = it }, label = { Text("السؤال") })
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = opts, onValueChange = { opts = it }, label = { Text("الاختيارات (كل واحد في سطر، 2 إلى 20)") }, minLines = 3)
                }
            },
            confirmButton = { TextButton(onClick = { vm.sendPoll(q, opts.lines()); showPoll = false }) { Text("إرسال") } },
            dismissButton = { TextButton(onClick = { showPoll = false }) { Text("إلغاء") } },
        )
    }

    val gNow = grp
    if (showInfo && gNow != null) {
        val isAdmin = gNow.adminId == myId
        var newName by remember(gNow.name) { mutableStateOf(gNow.name) }
        var addPhones by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("معلومات المجموعة") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (isAdmin) {
                        OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("الاسم") }, singleLine = true)
                        TextButton(onClick = { vm.renameGroup(newName) }) { Text("حفظ الاسم") }
                    }
                    Text("الأعضاء", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    gNow.memberList().forEach { id ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text((if (id == myId) "أنا" else names[id] ?: "…") + if (id == gNow.adminId) "  (أدمن)" else "", Modifier.weight(1f))
                            if (isAdmin && id != myId) TextButton(onClick = { vm.removeMember(id) }) { Text("إزالة") }
                        }
                    }
                    if (isAdmin) {
                        OutlinedTextField(value = addPhones, onValueChange = { addPhones = it }, label = { Text("ضيف أرقام") }, minLines = 2)
                        TextButton(onClick = { vm.addMembers(addPhones); addPhones = "" }) { Text("إضافة") }
                        Text("مسح الرسايل تلقائيًا", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Row {
                            listOf("معطّل" to 0L, "ساعة" to 3600L, "يوم" to 86400L, "أسبوع" to 604800L).forEach { (label, sec) ->
                                TextButton(onClick = { vm.setTtl(sec) }) {
                                    Text(label, fontWeight = if (gNow.ttlSec == sec) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                    }
                    TextButton(onClick = { showInfo = false; vm.leaveGroup(onBack) }) {
                        Text("خروج من المجموعة", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("تمام") } },
        )
    }

    actionMsg?.let { m ->
        AlertDialog(
            onDismissRequest = { actionMsg = null },
            confirmButton = {},
            text = {
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        listOf("👍", "❤️", "😂", "😮", "😢", "🙏").forEach { e ->
                            Text(e, Modifier.clickable { vm.react(m, e); actionMsg = null }.padding(6.dp), style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                    TextButton(onClick = { replyTo = m; editTarget = null; actionMsg = null }) { Icon(AIcons.Reply, null); Text("  رد") }
                    m.body?.let { b ->
                        TextButton(onClick = { clipboard.setText(AnnotatedString(b)); actionMsg = null }) { Icon(AIcons.ContentCopy, null); Text("  نسخ") }
                    }
                    if (m.mine && m.mediaFileId == null && m.body != null) TextButton(onClick = {
                        editTarget = m; replyTo = null; text = m.body ?: ""; actionMsg = null
                    }) { Icon(AIcons.Edit, null); Text("  تعديل") }
                    if (m.mediaType != "poll" && m.mediaType != "system" && m.mediaType != "gone" && m.viewMode == 0) {
                        TextButton(onClick = { forwardMsg = m; actionMsg = null }) { Icon(AIcons.Reply, null); Text("  تحويل") }
                        if (peer.id != myId) TextButton(onClick = {
                            scope.launch { ChatEngine.send(ChatEngine.savedProfile(), m.body, m.mediaFileId, m.mediaType) }; actionMsg = null
                        }) { Text("🔖  حفظ في رسايلي المحفوظة") }
                    }
                    if (m.mediaType != "system" && m.mediaType != "gone") TextButton(onClick = { scope.launch { ChatEngine.toggleStar(m) }; actionMsg = null }) { Text(if (m.starred) "☆  شيل النجمة" else "⭐  نجمة") }
                    TextButton(onClick = { confirmDelete = m; actionMsg = null }) { Icon(AIcons.Delete, null); Text("  حذف") }
                }
            },
        )
    }

    forwardMsg?.let { fm ->
        AlertDialog(
            onDismissRequest = { forwardMsg = null },
            title = { Text("تحويل إلى") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    allChats.filter { it.peer.id != myId }.forEach { c ->
                        TextButton(onClick = {
                            scope.launch { ChatEngine.send(c.peer, fm.body, fm.mediaFileId, fm.mediaType) }
                            forwardMsg = null
                        }) { Text(c.peer.displayName.ifBlank { c.peer.phone }) }
                    }
                    if (allChats.none { it.peer.id != myId }) Text("مفيش محادثات تانية.")
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { forwardMsg = null }) { Text("إلغاء") } },
        )
    }

    confirmDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("حذف الرسالة") },
            text = { Text(if (m.mine) "تتمسح عندك بس، أو عند الطرفين؟" else "هتتمسح من عندك بس.") },
            confirmButton = {
                if (m.mine) TextButton(onClick = { vm.deleteForEveryone(m); confirmDelete = null }) { Text("عند الطرفين") }
            },
            dismissButton = {
                TextButton(onClick = { vm.deleteForMe(m); confirmDelete = null }) { Text("عندي بس") }
            },
        )
    }

    viewer?.let { m ->
        MediaViewer(m.mediaFileId ?: "", m.mediaType ?: "image", m.body, onClose = { viewer = null })
    }
}


/** مشغّل رسالة صوتية: بينزّل الملف من تيليجرام (مرة واحدة) ويشغّله بـ MediaPlayer. */
@Composable
private fun VoiceBubble(fileId: String) {
    var player by remember(fileId) { mutableStateOf<android.media.MediaPlayer?>(null) }
    var playing by remember(fileId) { mutableStateOf(false) }
    var loading by remember(fileId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    androidx.compose.runtime.DisposableEffect(fileId) { onDispose { runCatching { player?.release() } } }

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(enabled = !loading, onClick = {
            val p = player
            if (p != null) {
                if (playing) { p.pause(); playing = false } else { p.start(); playing = true }
            } else scope.launch {
                loading = true
                runCatching {
                    val f = TelegramStorage.download(fileId)
                    val mp = android.media.MediaPlayer()
                    mp.setDataSource(f.absolutePath)
                    mp.setOnCompletionListener { playing = false; runCatching { it.seekTo(0) } }
                    withContext(Dispatchers.IO) { mp.prepare() }
                    mp.start(); player = mp; playing = true
                }
                loading = false
            }
        }) { Icon(if (playing) AIcons.Pause else AIcons.PlayCircle, "تشغيل", tint = MaterialTheme.colorScheme.primary) }
        Text(if (loading) "جاري التحميل…" else "🎤 رسالة صوتية")
    }
}


/** استطلاع: بيعرض الاختيارات وعدد الأصوات؛ الضغط على اختيار بيصوّت (والتصويت تاني بيغيّر اختيارك). */
@Composable
private fun PollBubble(m: LocalMessage, votes: List<com.binarybeast.chatapp.data.local.PollVote>, myId: String?, onVote: (Int) -> Unit) {
    val (question, options) = remember(m.body) {
        runCatching {
            val j = org.json.JSONObject(m.body ?: "{}")
            val arr = j.getJSONArray("o")
            j.optString("q") to List(arr.length()) { arr.getString(it) }
        }.getOrDefault("" to emptyList())
    }
    val total = votes.size.coerceAtLeast(1)
    Column {
        Text("📊 $question", fontWeight = FontWeight.SemiBold)
        options.forEachIndexed { i, label ->
            val count = votes.count { it.option == i }
            val mine = votes.any { it.voterId == myId && it.option == i }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (mine) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clickable { onVote(i) },
            ) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text((if (mine) "✓ " else "") + label, Modifier.weight(1f))
                    Text("$count (${count * 100 / total}%)", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}


// ======================================================================= ميديا مرة واحدة / مؤقّتة

private class MediaDraft(val bytes: ByteArray, val name: String, val mime: String, val caption: String?)

private fun fmtSecs(s: Int): String = when {
    s < 60 -> "$s ث"
    s < 3600 -> "${s / 60} د"
    s < 86400 -> "${s / 3600} س"
    else -> "${s / 86400} يوم"
}

@Composable
private fun ViewModeDialog(onCancel: () -> Unit, onSend: (mode: Int, secs: Int) -> Unit) {
    var mode by remember { mutableStateOf(0) }
    var secs by remember { mutableStateOf(30) }
    var custom by remember { mutableStateOf("") }
    val presets = listOf(10 to "10 ث", 30 to "30 ث", 60 to "دقيقة", 3600 to "ساعة", 86400 to "24 ساعة")
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("طريقة العرض عند الطرف التاني") },
        text = {
            Column {
                @Composable fun opt(m: Int, label: String, sub: String) = Row(
                    Modifier.fillMaxWidth().clickable { mode = m }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.RadioButton(selected = mode == m, onClick = { mode = m })
                    Column { Text(label, fontWeight = FontWeight.SemiBold); Text(sub, style = MaterialTheme.typography.bodySmall) }
                }
                opt(0, "عادي", "يفضل في المحادثة")
                opt(1, "👁 مرة واحدة", "يتشاف مرة واحدة وبعدها بيتشال أول ما يقفله")
                opt(2, "⏱ مؤقّت", "بيتشال بعد المدة دي من أول ما يفتحه")
                if (mode == 2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        presets.take(3).forEach { (v, l) ->
                            androidx.compose.material3.FilterChip(selected = custom.isBlank() && secs == v, onClick = { secs = v; custom = "" }, label = { Text(l) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        presets.drop(3).forEach { (v, l) ->
                            androidx.compose.material3.FilterChip(selected = custom.isBlank() && secs == v, onClick = { secs = v; custom = "" }, label = { Text(l) })
                        }
                    }
                    OutlinedTextField(
                        custom, { custom = it.filter(Char::isDigit).take(6) }, singleLine = true,
                        label = { Text("أو اكتب عدد الثواني") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
                if (mode != 0) Text("بيتمنع تحويلها وحفظها، ولقطات الشاشة بتتقفل وقت العرض. (الملف نفسه بيفضل في تخزين تيليجرام بتاعك.)",
                    Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(mode, (custom.toIntOrNull() ?: secs).coerceIn(1, 7 * 86400)) }) { Text("إرسال") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("إلغاء") } },
    )
}

/** عارض الميديا المقيّدة: لقطات الشاشة مقفولة، ولو مؤقّت بيتقفل لوحده لما الوقت يخلص. */
@Composable
private fun RestrictedViewer(m: LocalMessage, secsLeft: Int, onClose: () -> Unit) {
    var left by remember { mutableStateOf(secsLeft) }
    val timed = m.viewMode == 2
    LaunchedEffect(Unit) {
        if (!timed) return@LaunchedEffect
        while (left > 0) { kotlinx.coroutines.delay(1000); left-- }
        onClose()
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)
            ?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
            val fid = m.mediaFileId
            if (fid != null) when (m.mediaType) {
                "video" -> TgVideoPlayer(fid, Modifier.fillMaxSize())
                "audio" -> Box(Modifier.fillMaxSize(), Alignment.Center) { VoiceBubble(fid) }
                else -> TgImage(fid, Modifier.fillMaxSize(), ContentScale.Fit)
            }
            m.body?.takeIf { it.isNotBlank() }?.let {
                Text(it, Modifier.align(Alignment.BottomCenter).padding(24.dp), color = androidx.compose.ui.graphics.Color.White)
            }
            Text(
                if (timed) "⏱ ${fmtSecs(left)}" else "👁 مرة واحدة",
                Modifier.align(Alignment.TopCenter).padding(16.dp), color = androidx.compose.ui.graphics.Color.White,
            )
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                Icon(AIcons.Close, "إغلاق", tint = androidx.compose.ui.graphics.Color.White)
            }
        }
    }
}
