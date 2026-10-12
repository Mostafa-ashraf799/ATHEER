@file:OptIn(UnstableApi::class)

package com.binarybeast.chatapp.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.binarybeast.chatapp.data.TelegramStorage

/**
 * مشغّل فيديو من تيليجرام: بينزّل الملف الأول (الـ proxy مبيدعمش Range) وبعدين يشغّله من الكاش.
 * @param onEnded لو موجودة (زي الستوريز) بتتنده لما الفيديو يخلص ومبنعرضش أزرار تحكم.
 */
@Composable
fun TgVideoPlayer(fileId: String, modifier: Modifier = Modifier, onEnded: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    var file by remember(fileId) { mutableStateOf<java.io.File?>(null) }
    var failed by remember(fileId) { mutableStateOf(false) }
    val player = remember(fileId) { ExoPlayer.Builder(ctx).build() }

    LaunchedEffect(fileId) {
        runCatching { TelegramStorage.download(fileId) }
            .onSuccess { file = it }
            .onFailure { failed = true }
    }
    LaunchedEffect(file) {
        val f = file ?: return@LaunchedEffect
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(f)))
        player.prepare()
        player.playWhenReady = true
    }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) onEnded?.invoke() }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            failed -> Text("تعذّر تحميل الفيديو", color = Color.White)
            file == null -> CircularProgressIndicator(color = Color.White)
            else -> AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c -> PlayerView(c).apply { this.player = player; useController = onEnded == null } },
            )
        }
    }
}

/** عارض ميديا بملء الشاشة: صورة (تكبير بالقرص) أو فيديو. */
@Composable
fun MediaViewer(fileId: String, type: String, caption: String?, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (type == "video") {
                TgVideoPlayer(fileId, Modifier.fillMaxSize())
            } else {
                var scale by remember { mutableFloatStateOf(1f) }
                var ox by remember { mutableFloatStateOf(0f) }
                var oy by remember { mutableFloatStateOf(0f) }
                val state = rememberTransformableState { zoom, pan, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    if (scale > 1f) { ox += pan.x; oy += pan.y } else { ox = 0f; oy = 0f }
                }
                TgImage(
                    fileId,
                    Modifier.fillMaxSize()
                        .graphicsLayer { scaleX = scale; scaleY = scale; translationX = ox; translationY = oy }
                        .transformable(state),
                    ContentScale.Fit,
                )
            }
            caption?.takeIf { it.isNotBlank() }?.let {
                Text(it, Modifier.align(Alignment.BottomCenter).padding(24.dp), color = Color.White)
            }
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                Icon(AIcons.Close, "إغلاق", tint = Color.White)
            }
        }
    }
}
