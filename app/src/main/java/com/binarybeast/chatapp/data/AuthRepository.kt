package com.binarybeast.chatapp.data

import com.binarybeast.chatapp.BuildConfig
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * تسجيل الدخول برقم الهاتف عن طريق بوت تيليجرام (مجاني، من غير SMS).
 * الدالة tg-auth على Supabase هي اللي بتتأكد من الرقم وبترجّع Session.
 */
object AuthRepository {
    private val auth get() = Supa.client.auth
    private val http = OkHttpClient()

    val sessionStatus: Flow<SessionStatus> get() = auth.sessionStatus

    val currentUserId: String? get() = auth.currentUserOrNull()?.id

    /** يحوّل أي رقم لصيغة دولية. (مصر افتراضيًا لو بيبدأ بـ 0) */
    fun normalizePhone(raw: String): String {
        var p = raw.filter { it.isDigit() || it == '+' }
        if (p.startsWith("00")) p = "+" + p.drop(2)
        if (!p.startsWith("+")) p = if (p.startsWith("0")) "+20" + p.drop(1) else "+$p"
        return p
    }

    private suspend fun post(body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(BuildConfig.TG_AUTH_URL)
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) error("auth error ${res.code}: $text")
            JSONObject(text)
        }
    }

    /** بيرجّع (token, رابط البوت اللي لازم المستخدم يفتحه). */
    suspend fun startTelegramLogin(): Pair<String, String> {
        val r = post(JSONObject().put("action", "start"))
        return r.getString("token") to r.getString("url")
    }

    /**
     * بيسأل السيرفر لو المستخدم أكّد على تيليجرام.
     * @return true لو اتأكد واتفتحت Session، false لو لسه، وبيرمي استثناء لو الطلب انتهى.
     */
    suspend fun pollTelegramLogin(token: String): Boolean {
        val r = post(JSONObject().put("action", "poll").put("token", token))
        return when (r.optString("status")) {
            "ok" -> {
                auth.importAuthToken(r.getString("access_token"), r.getString("refresh_token"))
                true
            }
            "expired" -> error("expired")
            else -> false
        }
    }

    suspend fun signOut() = auth.signOut()
}
