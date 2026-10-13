package com.binarybeast.chatapp.ui

import android.net.Uri
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.binarybeast.chatapp.data.AuthRepository
import com.binarybeast.chatapp.data.Profile
import com.binarybeast.chatapp.data.StoryGroup
import kotlinx.coroutines.delay

private val DURATIONS = listOf(1.0 to "ساعة", 6.0 to "6 ساعات", 12.0 to "12 ساعة", 24.0 to "يوم", 48.0 to "يومين", 72.0 to "3 أيام", 168.0 to "أسبوع")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoriesScreen(me: Profile?, vm: StoriesVm = viewModel()) {
    val groups by vm.groups.collectAsState()
    val uploading by vm.uploading.collectAsState()
    val notice by vm.notice.collectAsState()
    var editing by remember { mutableStateOf<Pair<Uri, Boolean>?>(null) }
    var viewing by remember { mutableStateOf<StoryGroup?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<com.binarybeast.chatapp.ui.EditedMedia?>(null) }
    var hours by remember { mutableStateOf(24.0) }
    var custom by remember { mutableStateOf("") }

    val source = rememberMediaSource(onMedia = { uri, isVideo -> editing = uri to isVideo }, onFile = {})

    // المحرر (صور وفيديو بحد أقصى 30 ثانية) ثم النشر
    editing?.let { (uri, isVideo) ->
        MediaEditorScreen(
            uri = uri, isVideo = isVideo,
            onDone = { em -> editing = null; pending = em },
            onCancel = { editing = null },
        )
        return
    }

    pending?.let { em ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("الستوري تفضل قد إيه؟") },
            text = {
                Column {
                    DURATIONS.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { (h, label) ->
                                androidx.compose.material3.FilterChip(selected = hours == h && custom.isBlank(),
                                    onClick = { hours = h; custom = "" }, label = { Text(label) })
                            }
                        }
                    }
                    androidx.compose.material3.OutlinedTextField(
                        value = custom, onValueChange = { custom = it.filter { c -> c.isDigit() || c == '.' }.take(6) },
                        label = { Text("أو اكتب عدد الساعات (لحد 720)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val h = custom.toDoubleOrNull() ?: hours
                    pending = null; vm.post(em.bytes, em.name, em.mime, em.caption, h)
                }) { Text("نشر") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { pending = null }) { Text("إلغاء") } },
        )
    }

    val mine = groups.firstOrNull { it.user.id == AuthRepository.currentUserId }
    val others = groups.filter { it.user.id != AuthRepository.currentUserId }

    Scaffold(topBar = { GlassTopBar(title = { Text("الستوريز", fontWeight = FontWeight.Bold) }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            // ستوري بتاعتي
            item {
                Row(
                    Modifier.fillMaxWidth().clickable { if (mine != null) viewing = mine else addMenu = true }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        Avatar(me?.avatarFileId, me?.displayName.orEmpty(), 56.dp,
                            ring = if (mine != null) MaterialTheme.colorScheme.primary else null)
                        Box(
                            Modifier.align(Alignment.BottomEnd).size(20.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary).clickable { addMenu = true },
                            contentAlignment = Alignment.Center,
                        ) { Icon(AIcons.Add, null, Modifier.size(14.dp), tint = Color.White) }
                        DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                            DropdownMenuItem(text = { Text("المعرض") }, leadingIcon = { Icon(AIcons.PhotoLibrary, null) },
                                onClick = { addMenu = false; source.gallery() })
                            DropdownMenuItem(text = { Text("كاميرا (صورة)") }, leadingIcon = { Icon(AIcons.CameraAlt, null) },
                                onClick = { addMenu = false; source.photo() })
                            DropdownMenuItem(text = { Text("كاميرا (فيديو)") }, leadingIcon = { Icon(AIcons.Videocam, null) },
                                onClick = { addMenu = false; source.video() })
                        }
                    }
                    Column(Modifier.padding(horizontal = 14.dp).weight(1f)) {
                        Text("ستوريتي", fontWeight = FontWeight.SemiBold)
                        Text(
                            notice ?: if (uploading) "جاري الرفع…" else if (mine != null) "${mine.stories.size}/5 ستوري نشطة" else "اضغط ➕ علشان تنزّل ستوري",
                            color = if (notice != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (uploading) CircularProgressIndicator(Modifier.size(20.dp))
                }
            }
            item {
                Text("آخر التحديثات", Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            if (others.isEmpty()) item {
                Text("مفيش ستوريز من حد دلوقتي.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(others, key = { it.user.id }) { g ->
                Row(
                    Modifier.fillMaxWidth().clickable { viewing = g }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(
                        g.user.avatarFileId, g.user.displayName.ifBlank { g.user.phone }, 56.dp,
                        ring = if (g.seen) Color.Gray else MaterialTheme.colorScheme.primary,
                    )
                    Column(Modifier.padding(horizontal = 14.dp)) {
                        Text(g.user.displayName.ifBlank { g.user.phone }, fontWeight = FontWeight.SemiBold)
                        Text(hhmm(g.stories.last().createdAt), color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }

    viewing?.let { g ->
        StoryViewer(
            group = g,
            isMine = g.user.id == AuthRepository.currentUserId,
            onSeen = { vm.seen(it) },
            onDelete = { vm.delete(it) },
            onClose = { viewing = null; vm.load() },
        )
    }
}

@Composable
private fun StoryViewer(group: StoryGroup, isMine: Boolean, onSeen: (Long) -> Unit, onDelete: (Long) -> Unit, onClose: () -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    var progress by remember { mutableStateOf(0f) }
    val story = group.stories.getOrNull(index)

    fun next() { if (index < group.stories.lastIndex) index++ else onClose() }

    LaunchedEffect(index) {
        val s = group.stories.getOrNull(index) ?: return@LaunchedEffect onClose()
        onSeen(s.id)
        if (s.mediaType == "video") { progress = 1f; return@LaunchedEffect }   // الفيديو بيكمّل لوحده ويعدّي للي بعده لما يخلص
        progress = 0f
        val steps = 100
        repeat(steps) { delay(50); progress = (it + 1) / steps.toFloat() }   // 5 ثواني لكل صورة
        next()
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (story != null) {
                if (story.mediaType == "image")
                    TgImage(story.fileId, Modifier.fillMaxSize(), ContentScale.Fit)
                else
                    TgVideoPlayer(story.fileId, Modifier.fillMaxSize(), onEnded = { next() })

                story.caption?.let {
                    Text(it, Modifier.align(Alignment.BottomCenter).padding(32.dp), color = Color.White,
                        style = MaterialTheme.typography.titleMedium)
                }
            }
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    group.stories.forEachIndexed { i, _ ->
                        LinearProgressIndicator(
                            progress = { if (i < index) 1f else if (i == index) progress else 0f },
                            modifier = Modifier.weight(1f).height(3.dp),
                            color = Color.White, trackColor = Color.White.copy(alpha = 0.3f),
                        )
                    }
                }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(group.user.avatarFileId, group.user.displayName, 36.dp)
                    Text(group.user.displayName.ifBlank { group.user.phone }, color = Color.White,
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                    if (isMine && story != null) IconButton(onClick = { onDelete(story.id); onClose() }) {
                        Icon(AIcons.Delete, "حذف", tint = Color.White)
                    }
                    IconButton(onClick = onClose) { Icon(AIcons.Close, "إغلاق", tint = Color.White) }
                }
            }
        }
    }
}
