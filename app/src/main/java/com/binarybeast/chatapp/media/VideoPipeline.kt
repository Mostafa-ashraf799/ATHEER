@file:OptIn(UnstableApi::class)

package com.binarybeast.chatapp.media

import android.content.Context
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.TextOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** معالجة الفيديو بـ Media3 Transformer: قص، كتم، ضغط لحد حجم أقصى، فلاتر، ونص محروق على الفيديو. */
object VideoPipeline {

    data class Info(val durationMs: Long, val width: Int, val height: Int)

    val FILTERS = listOf("none" to "أصلي", "vivid" to "حيوي", "bw" to "أبيض وأسود", "fade" to "باهت")

    fun info(ctx: Context, uri: Uri): Info {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            fun num(key: Int) = r.extractMetadata(key)?.toLongOrNull() ?: 0L
            val rot = num(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION).toInt()
            var w = num(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
            var h = num(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            return Info(num(MediaMetadataRetriever.METADATA_KEY_DURATION), w, h)
        } finally {
            runCatching { r.release() }
        }
    }

    /** فلاتر الفيديو: بتُستخدم في المعاينة (ExoPlayer.setVideoEffects) وفي التصدير. */
    fun filterEffects(filter: String): List<Effect> = when (filter) {
        "bw" -> listOf(RgbFilter.createGrayscaleFilter())
        "vivid" -> listOf(HslAdjustment.Builder().adjustSaturation(35f).build(), Contrast(0.15f))
        "fade" -> listOf(HslAdjustment.Builder().adjustSaturation(-25f).build(), Brightness(0.05f), Contrast(-0.1f))
        else -> emptyList()
    }

    /**
     * يصدّر الفيديو المعدّل لملف MP4 في الكاش.
     * @param maxBytes الحد الأقصى للحجم (بنضبط الـ bitrate علشان نفضل تحته)
     * @param heightPx أقصى ارتفاع (مش بيكبّر الفيديو لو أصغر)
     */
    suspend fun export(
        ctx: Context,
        uri: Uri,
        startMs: Long,
        endMs: Long,
        mute: Boolean,
        filter: String,
        burnedText: String?,
        heightPx: Int,
        maxBytes: Long,
        onProgress: (Float) -> Unit,
    ): File {
        val info = info(ctx, uri)
        val clipMs = (endMs - startMs).coerceAtLeast(500)
        val outH = minOf(heightPx, info.height.takeIf { it > 0 } ?: heightPx).let { if (it % 2 == 1) it - 1 else it }

        // bitrate مستهدف حسب الجودة، ومحدود بالحجم الأقصى
        val wanted = when {
            outH <= 480 -> 1_000_000
            outH <= 720 -> 2_000_000
            else -> 4_000_000
        }
        val audioBps = if (mute) 0 else 128_000
        val allowed = ((maxBytes * 8 * 0.92) / (clipMs / 1000.0) - audioBps).toLong()
        val videoBps = minOf(wanted.toLong(), allowed).coerceAtLeast(250_000).toInt()

        val out = File(ctx.cacheDir, "export_${System.currentTimeMillis()}.mp4")

        val effects = mutableListOf<Effect>()
        effects += filterEffects(filter)
        if (!burnedText.isNullOrBlank()) {
            val span = SpannableString(burnedText).apply {
                setSpan(ForegroundColorSpan(Color.WHITE), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(AbsoluteSizeSpan(44, true), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val settings = OverlaySettings.Builder().setBackgroundFrameAnchor(0f, -0.7f).build()
            effects += OverlayEffect(ImmutableList.of(TextOverlay.createStaticTextOverlay(span, settings)))
        }
        if (outH > 0) effects += Presentation.createForHeight(outH)

        val item = MediaItem.Builder().setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build()
            ).build()
        val edited = EditedMediaItem.Builder(item)
            .setRemoveAudio(mute)
            .setEffects(Effects(listOf<AudioProcessor>(), effects))
            .build()

        val encoder = DefaultEncoderFactory.Builder(ctx)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(videoBps).build())
            .build()

        // Transformer لازم يتبنى ويشتغل على thread فيه Looper (الـ Main)
        withContext(Dispatchers.Main) {
            coroutineScope {
                lateinit var transformer: Transformer
                var poll: kotlinx.coroutines.Job? = null
                try {
                suspendCancellableCoroutine<Unit> { cont ->
                    transformer = Transformer.Builder(ctx)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setEncoderFactory(encoder)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (cont.isActive) cont.resume(Unit)
                            }
                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                if (cont.isActive) cont.resumeWithException(exportException)
                            }
                        })
                        .build()
                    cont.invokeOnCancellation { runCatching { transformer.cancel() } }
                    transformer.start(edited, out.absolutePath)

                    poll = launch {
                        val holder = ProgressHolder()
                        while (isActive) {
                            delay(250)
                            if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                        }
                    }
                }
                } finally {
                    poll?.cancel()
                }
            }
        }

        if (out.length() > maxBytes) {
            out.delete()
            error("الفيديو لسه أكبر من ${maxBytes / 1024 / 1024} ميجا. قصّ جزء منه وجرّب تاني.")
        }
        return out
    }
}
