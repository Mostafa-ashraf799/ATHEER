package com.binarybeast.chatapp.data

import android.content.Context
import com.binarybeast.chatapp.BuildConfig
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * تخزين الملفات على تيليجرام عن طريق Edge Function.
 * التطبيق بيبعت JWT بتاع المستخدم، والدالة هي اللي معاها توكن البوت.
 * حد التنزيل في Bot API هو 20MB، فبنرفض أي ملف أكبر من كده.
 */
object TelegramStorage {
    /** حجم القطعة الواحدة على تيليجرام. الملفات الأكبر بتتقسّم تلقائي (مفيش حد للحجم الكلي غير ذاكرة الجهاز). */
    const val CHUNK = 15 * 1024 * 1024
    const val MAX_BYTES = Int.MAX_VALUE

    private val http = OkHttpClient()
    private lateinit var cache: File

    fun init(ctx: Context) {
        cache = File(ctx.cacheDir, "tg").also { it.mkdirs() }
        // ملفات مؤقتة قديمة (تصدير فيديو، كاميرا، تصدير محادثات)
        val old = System.currentTimeMillis() - 24 * 3600_000L
        ctx.cacheDir.listFiles()?.filter { it.isFile && it.name.startsWith("export_") && it.lastModified() < old }?.forEach { it.delete() }
        listOf("camera", "exports").forEach { d ->
            File(ctx.cacheDir, d).listFiles()?.filter { it.lastModified() < old }?.forEach { it.delete() }
        }
        trim()
    }

    /**
     * الميديا مبتتخزنش على الجهاز بشكل دائم زي تيليجرام: هي كاش مؤقت جوه التطبيق (مش في المعرض ولا الملفات)،
     * بيتمسح الأقدم أولًا لما يعدّي الحد، وبعد المدة اللي تختارها.
     */
    fun trim() {
        if (!::cache.isInitialized) return
        val p = AppSettings.now
        val files = cache.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") } ?: return
        if (p.cacheDays > 0) {
            val cutoff = System.currentTimeMillis() - p.cacheDays * 86_400_000L
            files.filter { it.lastModified() < cutoff }.forEach { it.delete() }
        }
        if (p.cacheLimitMb >= 0) {
            val limit = p.cacheLimitMb * 1024L * 1024L
            var total = 0L
            cache.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.forEach {
                total += it.length()
                if (total > limit) it.delete()
            }
        }
    }

    fun cacheSize(): Long = if (!::cache.isInitialized) 0 else cache.listFiles()?.sumOf { it.length() } ?: 0L

    fun clearCache() { if (::cache.isInitialized) cache.listFiles()?.forEach { it.delete() } }

    private fun token(): String =
        Supa.client.auth.currentAccessTokenOrNull() ?: error("not signed in")

    /** يرفع ملف ويرجّع file_id بتاع تيليجرام. kind = image | image_hq | video | audio | file */
    suspend fun upload(bytes: ByteArray, fileName: String, mime: String, kind: String, purpose: String = "chat"): String {
        if (bytes.size <= CHUNK) return uploadPart(bytes, fileName, mime, kind, purpose, 1, 1)
        val total = (bytes.size + CHUNK - 1) / CHUNK
        val ids = (0 until total).map { i ->
            val part = bytes.copyOfRange(i * CHUNK, minOf(bytes.size, (i + 1) * CHUNK))
            // القطع بتترفع كـ document دايمًا (الصور/الفيديو المقسّمة مش هتتفتح لوحدها)
            uploadPart(part, "$fileName.part${i + 1}", "application/octet-stream", "file", purpose, i + 1, total)
        }
        return "multi:" + ids.joinToString("|")
    }

    private suspend fun uploadPart(bytes: ByteArray, fileName: String, mime: String, kind: String, purpose: String, part: Int, total: Int): String =
        withContext(Dispatchers.IO) {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("kind", kind)
                .addFormDataPart("purpose", purpose)        // avatar | story | chat
                .addFormDataPart("mime", mime)
                .addFormDataPart("part", part.toString())
                .addFormDataPart("total", total.toString())
                .addFormDataPart("tz", java.util.TimeZone.getDefault().id)
                .addFormDataPart("client_time", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US).format(java.util.Date()))
                .addFormDataPart("file", fileName, bytes.toRequestBody(mime.toMediaType()))
                .build()
            val req = Request.Builder()
                .url(BuildConfig.TELEGRAM_PROXY_URL)
                .header("Authorization", "Bearer ${token()}")
                .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .post(body)
                .build()
            http.newCall(req).execute().use { res ->
                val text = res.body?.string().orEmpty()
                if (!res.isSuccessful) error("upload failed: ${res.code} $text")
                JSONObject(text).getString("file_id")
            }
        }

    /** الرابط والهيدرز اللي Coil بيحتاجهم لتحميل الصور. */
    fun url(fileId: String): String =
        "${BuildConfig.TELEGRAM_PROXY_URL}?file_id=${java.net.URLEncoder.encode(fileId, "UTF-8")}"

    fun headers(): Map<String, String> = mapOf(
        "Authorization" to "Bearer ${Supa.client.auth.currentAccessTokenOrNull().orEmpty()}",
        "apikey" to BuildConfig.SUPABASE_ANON_KEY,
    )

    /**
     * ينزّل الملف كامل في الكاش ويرجّعه (للفيديو: المشغّل بيشغّله من ملف محلي علشان الـ proxy مبيدعمش Range).
     * لو الملف موجود في الكاش مش بيتنزّل تاني.
     */
    suspend fun download(fileId: String): File = withContext(Dispatchers.IO) {
        val name = MessageDigest.getInstance("SHA-256").digest(fileId.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(40)
        val target = File(cache, name)
        if (target.exists() && target.length() > 0) { target.setLastModified(System.currentTimeMillis()); return@withContext target }
        val tmp = File(cache, "$name.part")
        val ids = if (fileId.startsWith("multi:")) fileId.removePrefix("multi:").split("|") else listOf(fileId)
        require(ids.size <= 200 && ids.all { it.length <= 300 }) { "bad file id" }
        tmp.outputStream().use { outS ->
            for (id in ids) {
                val req = Request.Builder().url(url(id)).apply { headers().forEach { (k, v) -> header(k, v) } }.build()
                http.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) error("download failed: ${res.code}")
                    res.body!!.byteStream().use { it.copyTo(outS) }
                }
            }
        }
        if (!tmp.renameTo(target)) error("cache write failed")
        trim()
        target
    }
}
