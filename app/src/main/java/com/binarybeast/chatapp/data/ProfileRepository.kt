package com.binarybeast.chatapp.data

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object ProfileRepository {
    /** أعمدة مسموح قراءتها من الداتابيز (رقم الهاتف مقفول عن الغير على مستوى الداتابيز). */
    private const val COLS = "id,username,discoverable,display_name,about,avatar_file_id,public_key,settings,last_seen"
    private val cols get() = io.github.jan.supabase.postgrest.query.Columns.raw(COLS)

    /** رقمي أنا من بيانات الجلسة (مش من جدول البروفايلات). */
    private fun myPhone(): String =
        Supa.client.auth.currentUserOrNull()?.userMetadata?.get("phone")?.jsonPrimitive?.contentOrNull.orEmpty()

    suspend fun me(): Profile? {
        val id = AuthRepository.currentUserId ?: return null
        return byId(id)?.copy(phone = myPhone())
    }

    suspend fun byId(id: String): Profile? =
        Supa.client.from("profiles").select(cols) { filter { eq("id", id) } }.decodeSingleOrNull<Profile>()

    suspend fun byIds(ids: Collection<String>): List<Profile> {
        if (ids.isEmpty()) return emptyList()
        return Supa.client.from("profiles").select(cols) { filter { isIn("id", ids.toList()) } }.decodeList()
    }

    /** البحث عن مستخدم برقم الهاتف (صيغة دولية). */
    suspend fun findByPhone(phone: String): Profile? {
        val p = AuthRepository.normalizePhone(phone)
        // GoTrue بيخزّن الرقم من غير "+" عادةً، فنجرّب الشكلين
        val variants = listOf(p, p.removePrefix("+"))
        val ids = Supa.client.postgrest.rpc("find_user_by_phone", buildJsonObject {
            put("p", kotlinx.serialization.json.JsonArray(variants.map { JsonPrimitive(it) }))
        }).decodeList<String>()
        return ids.firstOrNull()?.let { byId(it) }
    }

    private val usernameRe = Regex("^[a-z][a-z0-9_]{2,19}$")
    fun cleanUsername(raw: String) = raw.trim().removePrefix("@").lowercase()
    fun validUsername(raw: String) = usernameRe.matches(cleanUsername(raw))

    suspend fun findByUsername(raw: String): Profile? =
        Supa.client.from("profiles").select(cols) { filter { eq("username", cleanUsername(raw)) } }.decodeSingleOrNull<Profile>()

    suspend fun usernameFree(raw: String): Boolean {
        val u = cleanUsername(raw)
        val owner = findByUsername(u) ?: return true
        return owner.id == AuthRepository.currentUserId
    }

    /** يرجّع null لو نجح، أو رسالة الخطأ. */
    suspend fun setUsername(raw: String): String? {
        val u = cleanUsername(raw)
        if (!validUsername(u)) return "اليوزر نيم 3–20 حرف: إنجليزي صغير وأرقام و _ ويبدأ بحرف"
        val id = AuthRepository.currentUserId ?: return "غير مسجّل"
        return runCatching {
            Supa.client.from("profiles").update(buildJsonObject { put("username", u) }) { filter { eq("id", id) } }
            null
        }.getOrElse { "اليوزر نيم ده مستخدم، جرّب غيره" }
    }

    /** بحث بـ @username أو برقم هاتف. */
    suspend fun find(query: String): Profile? {
        val q = query.trim()
        return if (q.startsWith("@") || q.any { it.isLetter() }) findByUsername(q) else findByPhone(q)
    }

    suspend fun updateProfile(name: String? = null, about: String? = null, avatarFileId: String? = null) {
        val id = AuthRepository.currentUserId ?: return
        val patch = buildJsonObject {
            name?.let { put("display_name", it) }
            about?.let { put("about", it) }
            avatarFileId?.let { put("avatar_file_id", it) }
        }
        if (patch.isEmpty()) return
        Supa.client.from("profiles").update(patch) { filter { eq("id", id) } }
    }

    suspend fun updateSetting(current: JsonObject?, key: String, value: Any) {
        val id = AuthRepository.currentUserId ?: return
        val merged = buildJsonObject {
            current?.forEach { (k, v) -> if (k != key) put(k, v) }
            when (value) {
                is Boolean -> put(key, value)
                is String -> put(key, value)
                else -> put(key, value.toString())
            }
        }
        Supa.client.from("profiles").update(buildJsonObject { put("settings", merged) }) {
            filter { eq("id", id) }
        }
    }

    suspend fun setDiscoverable(v: Boolean) {
        val id = AuthRepository.currentUserId ?: return
        Supa.client.from("profiles").update(buildJsonObject { put("discoverable", v) }) { filter { eq("id", id) } }
    }

    suspend fun touchLastSeen() {
        val id = AuthRepository.currentUserId ?: return
        // لو "آخر ظهور" مقفول: نمسح القيمة علشان محدش يشوفها
        val visible = AppSettings.now.lastSeenVisible
        runCatching {
            Supa.client.from("profiles").update(
                buildJsonObject {
                    if (visible) put("last_seen", java.time.Instant.now().toString())
                    else put("last_seen", kotlinx.serialization.json.JsonNull)
                }
            ) { filter { eq("id", id) } }
        }
    }
}

fun JsonObject?.bool(key: String, default: Boolean = true): Boolean =
    this?.get(key)?.jsonPrimitive?.booleanOrNull ?: default

fun JsonObject?.str(key: String, default: String = ""): String =
    this?.get(key)?.jsonPrimitive?.contentOrNull ?: default
