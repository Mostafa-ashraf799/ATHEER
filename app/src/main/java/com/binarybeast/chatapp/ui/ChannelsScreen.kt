package com.binarybeast.chatapp.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.binarybeast.chatapp.data.AppSettings
import com.binarybeast.chatapp.data.AuthRepository
import com.binarybeast.chatapp.data.Channel
import com.binarybeast.chatapp.data.ChannelPost
import com.binarybeast.chatapp.data.ChannelRepository
import com.binarybeast.chatapp.data.TelegramStorage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelsScreen(onOpen: (Channel) -> Unit) {
    var mine by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var results by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var showCreate by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun reload() = scope.launch { runCatching { ChannelRepository.mine() }.onSuccess { mine = it }; loading = false }
    LaunchedEffect(Unit) { reload() }
    LaunchedEffect(query) {
        if (query.isBlank()) { results = emptyList(); return@LaunchedEffect }
        delay(350)
        results = runCatching { ChannelRepository.search(query) }.getOrDefault(emptyList())
    }

    Scaffold(
        topBar = { GlassTopBar(title = { Text("القنوات", fontWeight = FontWeight.Bold) }) },
        floatingActionButton = { GlassCircleButton(AIcons.Add, { showCreate = true }, size = 60.dp, accent = true, contentDescription = "قناة جديدة") },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                query, { query = it }, singleLine = true, placeholder = { Text("دوّر على قناة بالاسم أو @الرابط") },
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            )
            val list = if (query.isBlank()) mine else results
            if (loading && query.isBlank()) Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() }
            else if (list.isEmpty()) Text(
                if (query.isBlank()) "مش مشترك في أي قناة. دوّر على قناة أو اعمل قناتك ➕" else "مفيش نتايج",
                Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn {
                items(list, key = { it.id }) { c ->
                    Row(Modifier.fillMaxWidth().clickable { onOpen(c) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Avatar(c.avatarFileId, c.name, 52.dp)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(c.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text("@${c.handle} · ${c.membersCount} مشترك", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        var handle by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("قناة جديدة") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it.take(100) }, label = { Text("اسم القناة") }, singleLine = true)
                    OutlinedTextField(handle, { handle = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' }.take(30); err = null },
                        label = { Text("الرابط") }, prefix = { Text("@") }, singleLine = true, isError = err != null,
                        supportingText = { Text(err ?: "الناس بتدوّر بيه") })
                    OutlinedTextField(desc, { desc = it.take(500) }, label = { Text("وصف (اختياري)") }, minLines = 2)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val e = ChannelRepository.create(handle, name, desc)
                        if (e == null) { showCreate = false; reload() } else err = e
                    }
                }) { Text("إنشاء") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("إلغاء") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelView(channel: Channel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val me = AuthRepository.currentUserId
    val isOwner = channel.ownerId == me
    var posts by remember { mutableStateOf<List<ChannelPost>>(emptyList()) }
    var member by remember { mutableStateOf(isOwner) }
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Pair<Uri, Boolean>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val prefs by AppSettings.prefs.collectAsState()
    val scope = rememberCoroutineScope()
    val muteKey = "c:${channel.id}"

    fun reload() = scope.launch { runCatching { ChannelRepository.posts(channel.id) }.onSuccess { posts = it } }
    LaunchedEffect(channel.id) {
        member = isOwner || runCatching { ChannelRepository.isMember(channel.id) }.getOrDefault(false)
        while (true) { reload().join(); delay(8000) }   // تحديث دوري خفيف بجانب الإشعارات اللحظية
    }

    val source = rememberMediaSource(onMedia = { uri, isVideo -> editing = uri to isVideo }, onFile = {})
    editing?.let { (uri, isVideo) ->
        MediaEditorScreen(
            uri = uri, isVideo = isVideo,
            onDone = { em ->
                editing = null
                scope.launch {
                    busy = true
                    runCatching {
                        val kind = if (em.mime.startsWith("video/")) "video" else "image"
                        val id = TelegramStorage.upload(em.bytes, em.name, em.mime, kind, "chat")
                        ChannelRepository.post(channel.id, em.caption, id, kind)
                    }
                    busy = false; reload()
                }
            },
            onCancel = { editing = null },
        )
        return
    }

    Scaffold(
        topBar = {
            GlassTopBar(
                title = {
                    Column {
                        Text(channel.name, maxLines = 1, fontWeight = FontWeight.Bold)
                        Text("@${channel.handle} · ${channel.membersCount} مشترك", style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(AIcons.ArrowBack, "رجوع") } },
                actions = {
                    if (isOwner) IconButton(onClick = { confirmDelete = true }) { Icon(AIcons.Delete, "حذف القناة") }
                    else if (member) TextButton(onClick = {
                        AppSettings.update { s -> s.copy(muted = if (muteKey in s.muted) s.muted - muteKey else s.muted + muteKey) }
                    }) { Text(if (muteKey in prefs.muted) "تفعيل الإشعارات" else "كتم") }
                },
            )
        },
        bottomBar = {
            if (isOwner) Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { source.gallery() }, enabled = !busy) { Icon(AIcons.AttachFile, "ميديا") }
                OutlinedTextField(text, { text = it }, placeholder = { Text("اكتب منشور…") }, modifier = Modifier.weight(1f))
                IconButton(enabled = text.isNotBlank() && !busy, onClick = {
                    val t = text; text = ""
                    scope.launch { busy = true; runCatching { ChannelRepository.post(channel.id, t, null, null) }; busy = false; reload() }
                }) { Icon(AIcons.Send, "نشر") }
            } else Row(Modifier.fillMaxWidth().padding(12.dp)) {
                if (member) OutlinedButton(onClick = { scope.launch { ChannelRepository.leave(channel.id); member = false } },
                    modifier = Modifier.fillMaxWidth()) { Text("إلغاء الاشتراك") }
                else Button(onClick = { scope.launch { ChannelRepository.join(channel.id); member = true } },
                    modifier = Modifier.fillMaxWidth()) { Text("اشتراك") }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), reverseLayout = true, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(posts, key = { it.id }) { p ->
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        if (p.mediaType == "image") TgImage(p.mediaFileId, Modifier.fillMaxWidth().height(240.dp), ContentScale.Fit)
                        else if (p.mediaType == "video" && p.mediaFileId != null) TgVideoPlayer(p.mediaFileId, Modifier.fillMaxWidth().height(240.dp))
                        p.body?.let { Text(it, Modifier.padding(top = 6.dp)) }
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(hhmm(p.createdAt), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (isOwner) TextButton(onClick = { scope.launch { ChannelRepository.deletePost(p.id); reload() } }) { Text("حذف") }
                        }
                    }
                }
            }
            if (posts.isEmpty()) item { Text("مفيش منشورات لسه.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (channel.description.isNotBlank()) item {
                Text(channel.description, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("حذف القناة؟") },
        text = { Text("القناة وكل منشوراتها هتتمسح للجميع.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; scope.launch { ChannelRepository.deleteChannel(channel.id); onBack() } }) { Text("حذف") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("إلغاء") } },
    )
}
