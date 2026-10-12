package com.binarybeast.chatapp.data

import com.binarybeast.chatapp.BuildConfig
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** إشعارات FCM: الدالة push على Supabase هي اللي بتبعت (بتحتاج service account بتاع Firebase). */
object Push {
    private val http = OkHttpClient()

    /** يحفظ FCM token بتاع الجهاز في بروفايلي علشان الناس تقدر تصحّيني. */
    suspend fun registerToken(token: String) {
        val uid = AuthRepository.currentUserId ?: return
        runCatching {
            Supa.client.from("push_tokens").upsert(
                buildJsonObject { put("user_id", uid); put("token", token) }
            )
        }
    }

    /**
     * يصحّي جهاز المستلم. kind = "msg" | "call".
     * مبيحملش محتوى الرسالة نهائيًا — بس إشارة إن في جديد، والجهاز بيسحب الرسالة المشفّرة بنفسه.
     */
    /** مجموعة: نداء واحد بكل المستلمين (بدل نداء لكل عضو). */
    suspend fun notifyMany(to: List<String>, kind: String = "msg") {
        if (to.size > 50) { to.chunked(50).forEach { notifyMany(it, kind) }; return }   // الدالة بتقبل 50 مستلم في النداء
        if (to.isEmpty()) return
        val jwt = Supa.client.auth.currentAccessTokenOrNull() ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject().put("to_many", org.json.JSONArray(to)).put("kind", kind)
                val req = Request.Builder()
                    .url(BuildConfig.PUSH_URL)
                    .header("Authorization", "Bearer $jwt")
                    .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(req).execute().close()
            }
        }
    }

    suspend fun notify(to: String, kind: String, callId: String? = null, video: Boolean = false) {
        val jwt = Supa.client.auth.currentAccessTokenOrNull() ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject().put("to", to).put("kind", kind).put("video", video)
                callId?.let { body.put("call_id", it) }
                val req = Request.Builder()
                    .url(BuildConfig.PUSH_URL)
                    .header("Authorization", "Bearer $jwt")
                    .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(req).execute().close()
            }
        }
    }
}
