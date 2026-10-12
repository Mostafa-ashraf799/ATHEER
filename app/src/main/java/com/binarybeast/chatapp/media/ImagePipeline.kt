package com.binarybeast.chatapp.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/** معالجة الصور: تحميل بالاتجاه الصحيح، قص/دوران، فلاتر وضبط ألوان، رسم ونص، وتصدير JPEG. */
object ImagePipeline {

    /** نقاط الرسم والأحجام كلها نسبية (0..1) لعرض/ارتفاع الصورة النهائية، علشان المعاينة والتصدير يطلعوا زي بعض. */
    data class Stroke(val color: Int, val width: Float, val pts: List<Pair<Float, Float>>)

    /** (x, y) = الركن العلوي الأيسر للنص بالنسبة للصورة، و size = ارتفاع الخط بالنسبة لعرض الصورة. */
    data class Overlay(val id: Long, val text: String, val x: Float, val y: Float, val size: Float, val color: Int)

    data class Look(
        val brightness: Float = 0f,   // -0.5 .. 0.5
        val contrast: Float = 1f,     // 0.5 .. 1.5
        val saturation: Float = 1f,   // 0 .. 2
        val filter: String = "none",
    )

    val FILTERS = listOf(
        "none" to "أصلي", "vivid" to "حيوي", "warm" to "دافئ", "cool" to "بارد",
        "bw" to "أبيض وأسود", "noir" to "نوار", "sepia" to "سيبيا", "fade" to "باهت",
    )

    /** يحمّل الصورة ويصلّح اتجاهها (EXIF) ويصغّرها لو أكبر من maxDim. */
    fun load(ctx: Context, uri: Uri, maxDim: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "صورة غير صالحة" }

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("تعذّر فتح الصورة")

        val orientation = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)

        val longest = max(bmp.width, bmp.height)
        if (longest > maxDim) {
            val s = maxDim.toFloat() / longest
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true)
        }
        return bmp
    }

    /** دوران بالربع (quarterTurns × 90°) + قلب أفقي + قص من النص لنسبة عرض/ارتفاع (aspect = w/h) لو موجودة. */
    fun applyGeometry(src: Bitmap, quarterTurns: Int, flipH: Boolean, aspect: Float?): Bitmap {
        var bmp = src
        val turns = ((quarterTurns % 4) + 4) % 4
        if (turns != 0 || flipH) {
            val m = Matrix()
            if (turns != 0) m.postRotate(90f * turns)
            if (flipH) m.postScale(-1f, 1f)
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        if (aspect != null && aspect > 0f) {
            val cur = bmp.width.toFloat() / bmp.height
            val (w, h) = if (cur > aspect) (bmp.height * aspect).toInt() to bmp.height
            else bmp.width to (bmp.width / aspect).toInt()
            val cw = min(w, bmp.width).coerceAtLeast(1)
            val ch = min(h, bmp.height).coerceAtLeast(1)
            bmp = Bitmap.createBitmap(bmp, (bmp.width - cw) / 2, (bmp.height - ch) / 2, cw, ch)
        }
        return bmp
    }

    private fun filterMatrix(name: String): ColorMatrix = when (name) {
        "bw" -> ColorMatrix().apply { setSaturation(0f) }
        "noir" -> ColorMatrix().apply { setSaturation(0f); postConcat(contrastBrightness(1.3f, -0.03f)) }
        "sepia" -> ColorMatrix(floatArrayOf(
            .393f, .769f, .189f, 0f, 0f,
            .349f, .686f, .168f, 0f, 0f,
            .272f, .534f, .131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))
        "vivid" -> ColorMatrix().apply { setSaturation(1.4f); postConcat(contrastBrightness(1.1f, 0f)) }
        "warm" -> ColorMatrix(floatArrayOf(
            1.12f, 0f, 0f, 0f, 6f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, .88f, 0f, -6f,
            0f, 0f, 0f, 1f, 0f,
        ))
        "cool" -> ColorMatrix(floatArrayOf(
            .9f, 0f, 0f, 0f, -4f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1.15f, 0f, 6f,
            0f, 0f, 0f, 1f, 0f,
        ))
        "fade" -> ColorMatrix().apply { setSaturation(0.8f); postConcat(contrastBrightness(0.85f, 0.05f)) }
        else -> ColorMatrix()
    }

    private fun contrastBrightness(contrast: Float, brightness: Float): ColorMatrix {
        val t = (1f - contrast) * 128f + brightness * 255f
        return ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, t,
            0f, contrast, 0f, 0f, t,
            0f, 0f, contrast, 0f, t,
            0f, 0f, 0f, 1f, 0f,
        ))
    }

    /** مصفوفة الألوان النهائية (فلتر ← تشبّع ← تباين/سطوع). الـ 20 رقم دول بيستخدمهم Compose في المعاينة والتصدير. */
    fun colorMatrix(look: Look): ColorMatrix {
        val m = ColorMatrix(filterMatrix(look.filter))
        m.postConcat(ColorMatrix().apply { setSaturation(look.saturation) })
        m.postConcat(contrastBrightness(look.contrast, look.brightness))
        return m
    }

    /** يرسم الصورة بالفلتر + الرسم + النصوص ويطلّع JPEG. */
    fun render(base: Bitmap, look: Look, strokes: List<Stroke>, overlays: List<Overlay>, quality: Int): ByteArray {
        val w = base.width
        val h = base.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(base, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix(look))
        })

        for (s in strokes) {
            if (s.pts.size < 2) continue
            val path = Path().apply {
                moveTo(s.pts[0].first * w, s.pts[0].second * h)
                for (i in 1 until s.pts.size) lineTo(s.pts[i].first * w, s.pts[i].second * h)
            }
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                color = s.color; strokeWidth = s.width * w
            })
        }

        for (o in overlays) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = o.color; textSize = o.size * w; textAlign = Paint.Align.LEFT
                setShadowLayer(o.size * w * 0.08f, 0f, 0f, 0xAA000000.toInt())
            }
            // الركن العلوي الأيسر → baseline
            canvas.drawText(o.text, o.x * w, o.y * h - paint.ascent(), paint)
        }

        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        if (out !== base) out.recycle()
        return bos.toByteArray()
    }
}
