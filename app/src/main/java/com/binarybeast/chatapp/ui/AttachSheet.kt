package com.binarybeast.chatapp.ui

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class Recent(val uri: Uri, val video: Boolean)

private fun mediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

private fun hasMediaAccess(ctx: android.content.Context): Boolean {
    fun ok(p: String) = androidx.core.content.ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    return mediaPermissions().all { ok(it) } ||
        (Build.VERSION.SDK_INT >= 34 && ok(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
}

/** آخر صور وفيديوهات من الجهاز (MediaStore)، الأحدث أولًا. */
private suspend fun loadRecent(ctx: android.content.Context): List<Recent> = withContext(Dispatchers.IO) {
    val out = ArrayList<Recent>()
    runCatching {
        val q = ctx.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE),
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE},${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})",
            null, "${MediaStore.Files.FileColumns.DATE_ADDED} DESC",
        )
        q?.use { c ->
            while (c.moveToNext() && out.size < 40) {
                val id = c.getLong(0)
                val video = c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                out += Recent(ContentUris.withAppendedId(base, id), video)
            }
        }
    }
    out
}

@Composable
private fun Thumb(r: Recent, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, r.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(r.uri, Size(256, 256), null)
                else ctx.contentResolver.openInputStream(r.uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 })
                }
            }.getOrNull()
        }
    }
    Box(Modifier.size(104.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.size(104.dp), contentScale = ContentScale.Crop) }
        if (r.video) Icon(AIcons.PlayCircle, null, Modifier.align(Alignment.Center).size(30.dp), tint = androidx.compose.ui.graphics.Color.White)
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        GlassCircleButton(icon, onClick, size = 56.dp, accent = true, contentDescription = label)
        Text(label, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp), maxLines = 1)
    }
}

/** شيت المرفقات (زي تيليجرام): صور حديثة من الجهاز + معرض/كاميرا/فيديو/ملف/صوت/استطلاع. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachSheet(
    source: MediaSource,
    isGroup: Boolean,
    onPoll: () -> Unit,
    onRecent: (Uri, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var access by remember { mutableStateOf(hasMediaAccess(ctx)) }
    var recent by remember { mutableStateOf<List<Recent>>(emptyList()) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { access = hasMediaAccess(ctx) }
    LaunchedEffect(access) { if (access) recent = loadRecent(ctx) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            if (access && recent.isNotEmpty()) {
                LazyRow(Modifier.fillMaxWidth().height(112.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(recent) { r -> Thumb(r) { onDismiss(); onRecent(r.uri, r.video) } }
                }
            } else if (!access) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.Center) {
                    Box(Modifier.fillMaxWidth().height(52.dp).glass(androidx.compose.foundation.shape.CircleShape).clickable { AppLock.skipNext(); perm.launch(mediaPermissions()) }, contentAlignment = Alignment.Center) {
                        Text("اسمح بالوصول للصور عشان تظهر هنا")
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp, start = 8.dp, end = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Action(AIcons.PhotoLibrary, "المعرض") { onDismiss(); source.gallery() }
                Action(AIcons.CameraAlt, "كاميرا") { onDismiss(); source.photo() }
                Action(AIcons.Videocam, "فيديو") { onDismiss(); source.video() }
                Action(AIcons.InsertDriveFile, "ملف") { onDismiss(); source.file() }
                Action(AIcons.Music, "صوت") { onDismiss(); source.audio() }
                if (isGroup) Action(AIcons.Poll, "استطلاع") { onDismiss(); onPoll() }
            }
        }
    }
}
