package com.binarybeast.chatapp.ui

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

/** مصادر الميديا: المعرض (صور وفيديو)، الكاميرا (صورة/فيديو)، وملف عادي. كلها من غير صلاحيات تخزين. */
class MediaSource(
    val gallery: () -> Unit,
    val photo: () -> Unit,
    val video: () -> Unit,
    val file: () -> Unit,
)

@Composable
fun rememberMediaSource(
    onMedia: (uri: Uri, isVideo: Boolean) -> Unit,
    onFile: (Uri) -> Unit,
): MediaSource {
    val ctx = LocalContext.current
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { onMedia(it, (ctx.contentResolver.getType(it) ?: "").startsWith("video/")) }
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(onFile) }
    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = pendingUri; pendingUri = null
        if (ok && u != null) onMedia(u, false)
    }
    val videoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        val u = pendingUri; pendingUri = null
        if (ok && u != null) onMedia(u, true)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val a = pendingAction; pendingAction = null
        if (granted) a?.invoke()
    }

    fun tempUri(ext: String): Uri {
        val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
        val f = File.createTempFile("cap_", ext, dir)
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    }

    fun withCamera(action: () -> Unit) {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) action() else { pendingAction = action; permLauncher.launch(Manifest.permission.CAMERA) }
    }

    return MediaSource(
        gallery = { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
        photo = { withCamera { val u = tempUri(".jpg"); pendingUri = u; photoLauncher.launch(u) } },
        video = { withCamera { val u = tempUri(".mp4"); pendingUri = u; videoLauncher.launch(u) } },
        file = { fileLauncher.launch("*/*") },
    )
}
