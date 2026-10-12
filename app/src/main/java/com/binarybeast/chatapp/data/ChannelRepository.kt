package com.binarybeast.chatapp.data

import android.content.Context
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * القنوات: بث من صاحب القناة لمشتركين. المنشورات بتتخزن على السيرفر (مش مشفّرة end-to-end لأنها عامة
 * ومقروءة لأي حد يشترك)، والميديا على تيليجرام. منشور واحد في الداتابيز مهما كان عدد المشتركين = أقل استهلاك بيانات.
 */
object ChannelRepository {
    private val me get() = AuthRepository.currentUserId ?: error("not signed in")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listener: Job? = null

    fun validHandle(raw: String) = Regex("^[a-z][a-z0-9_]{2,29}$").matches(raw.trim().removePrefix("@").lowercase())
    fun cleanHandle(raw: String) = raw.trim().removePrefix("@").lowercase()

    /** القنوات اللي أنا مشترك فيها (أو صاحبها). */
    suspend fun mine(): List<Channel> {
        val ids = Supa.client.from("channel_members").select { filter { eq("user_id", me) } }
            .decodeList<ChannelMember>().map { it.channelId }
        if (ids.isEmpty()) return emptyList()
        return Supa.client.from("channels").select { filter { isIn("id", ids) } }.decodeList()
    }

    /** بحث بالاسم أو الـ @handle. */
    suspend fun search(q: String): List<Channel> {
        val t = q.trim().removePrefix("@").replace(Regex("[%,()*]"), "")
        if (t.isEmpty()) return emptyList()
        return Supa.client.from("channels").select {
            filter { or { ilike("handle", "%$t%"); ilike("name", "%$t%") } }
            limit(30)
        }.decodeList()
    }

    /** يرجّع null لو نجح، أو رسالة خطأ. */
    suspend fun create(handle: String, name: String, description: String): String? {
        val h = cleanHandle(handle)
        if (!validHandle(h)) return "الرابط 3–30 حرف: a-z وأرقام و _ ويبدأ بحرف"
        if (name.isBlank()) return "اكتب اسم القناة"
        return runCatching {
            val ch = Supa.client.from("channels").insert(NewChannel(me, h, name.trim(), description.trim())) { select() }
                .decodeSingle<Channel>()
            Supa.client.from("channel_members").insert(ChannelMember(ch.id, me))
            null
        }.getOrElse { "الرابط ده مستخدم، جرّب غيره" }
    }

    suspend fun join(channelId: Long) {
        runCatching { Supa.client.from("channel_members").insert(ChannelMember(channelId, me)) }
    }

    suspend fun leave(channelId: Long) {
        Supa.client.from("channel_members").delete { filter { eq("channel_id", channelId); eq("user_id", me) } }
    }

    suspend fun deleteChannel(channelId: Long) {
        Supa.client.from("channels").delete { filter { eq("id", channelId) } }
    }

    suspend fun posts(channelId: Long, limit: Long = 100): List<ChannelPost> =
        Supa.client.from("channel_posts").select {
            filter { eq("channel_id", channelId) }
            order("created_at", Order.DESCENDING)
            limit(limit)
        }.decodeList()

    suspend fun post(channelId: Long, body: String?, fileId: String?, mediaType: String?) {
        Supa.client.from("channel_posts").insert(NewChannelPost(channelId, me, body?.ifBlank { null }, fileId, mediaType))
    }

    suspend fun deletePost(postId: Long) {
        Supa.client.from("channel_posts").delete { filter { eq("id", postId) } }
    }

    suspend fun isMember(channelId: Long): Boolean =
        Supa.client.from("channel_members").select { filter { eq("channel_id", channelId); eq("user_id", me) } }
            .decodeList<ChannelMember>().isNotEmpty()

    /** إشعارات منشورات القنوات اللي مشترك فيها (بيشتغل طول ما التطبيق/الخدمة شغّالة). */
    fun startListening(ctx: Context) {
        if (listener?.isActive == true) return
        val app = ctx.applicationContext
        listener = scope.launch {
            val uid = AuthRepository.currentUserId ?: return@launch
            runCatching {
                var mineIds = mine().associateBy { it.id }
                val ch = Supa.client.channel("chposts-$uid")
                val flow = ch.postgresChangeFlow<PostgresAction.Insert>(schema = "public") { table = "channel_posts" }
                ch.subscribe()
                flow.collect { change ->
                    val p = runCatching { change.decodeRecord<ChannelPost>() }.getOrNull() ?: return@collect
                    if (p.authorId == uid) return@collect
                    if (mineIds[p.channelId] == null) mineIds = runCatching { mine().associateBy { it.id } }.getOrDefault(mineIds)
                    val c = mineIds[p.channelId] ?: return@collect
                    com.binarybeast.chatapp.Notifier.channelPost(
                        app, c.id, c.name,
                        p.body ?: when (p.mediaType) { "image" -> "📷 صورة"; "video" -> "🎬 فيديو"; else -> "📎 ملف" },
                    )
                }
            }
        }
    }

    fun stopListening() { listener?.cancel(); listener = null }
}
