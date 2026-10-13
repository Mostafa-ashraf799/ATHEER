package com.binarybeast.chatapp.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** تسجيل رسالة صوتية (AAC داخل m4a) في الكاش. */
class VoiceRecorder(private val ctx: Context) {
    private var rec: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val running: Boolean get() = rec != null

    fun start(): Boolean {
        stopQuietly()
        val f = File(ctx.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        return try {
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(48_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(f.absolutePath)
            r.prepare(); r.start()
            rec = r; file = f; startedAt = System.currentTimeMillis()
            true
        } catch (e: Exception) { stopQuietly(); f.delete(); false }
    }

    /** بيوقّف ويرجّع الملف (أو null لو التسجيل قصير جدًا أو فشل). */
    fun stop(): File? {
        val r = rec ?: return null
        val f = file
        val tooShort = System.currentTimeMillis() - startedAt < 700
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        rec = null; file = null
        if (!ok || tooShort || f == null) { f?.delete(); return null }
        return f
    }

    fun cancel() { stop()?.delete() }

    private fun stopQuietly() {
        runCatching { rec?.stop() }; runCatching { rec?.release() }
        rec = null; file?.delete(); file = null
    }
}
