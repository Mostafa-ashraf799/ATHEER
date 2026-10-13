package com.binarybeast.chatapp.data

import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order

object StoryRepository {
    private val me get() = AuthRepository.currentUserId ?: error("not signed in")

    /** كل الستوريز الشغالة (آخر 24 ساعة) مجمّعة حسب صاحبها. */
    suspend fun feed(): List<StoryGroup> {
        val uid = me
        purgeMyExpired()
        val stories = Supa.client.from("stories").select {
            filter { gt("expires_at", java.time.Instant.now().toString()) }
            order("created_at", Order.ASCENDING)
        }.decodeList<Story>()
        if (stories.isEmpty()) return emptyList()

        val seenIds = Supa.client.from("story_views").select {
            filter { eq("viewer_id", uid) }
        }.decodeList<StoryView>().map { it.storyId }.toSet()

        val profiles = ProfileRepository.byIds(stories.map { it.userId }.toSet()).associateBy { it.id }

        return stories.groupBy { it.userId }.mapNotNull { (userId, list) ->
            val p = profiles[userId] ?: return@mapNotNull null
            StoryGroup(p, list, seen = list.all { it.id in seenIds })
        }.sortedWith(
            // بتاعتي الأول، بعدين اللي لسه ماتشافتش
            compareByDescending<StoryGroup> { it.user.id == uid }.thenBy { it.seen }
        )
    }

    const val MAX_ACTIVE = 5

    suspend fun myActiveCount(): Int = Supa.client.from("stories").select {
        filter { eq("user_id", me); gt("expires_at", java.time.Instant.now().toString()) }
    }.decodeList<Story>().size

    /** مدة الستوري بالساعات يحددها المستخدم (لحد 720 = 30 يوم). */
    suspend fun post(fileId: String, mediaType: String, caption: String?, hours: Double) {
        val exp = java.time.Instant.now().plusSeconds((hours.coerceIn(0.1, 720.0) * 3600).toLong()).toString()
        Supa.client.from("stories").insert(NewStory(me, fileId, mediaType, caption, exp))
    }

    /** تنضيف ستوريز صاحبها المنتهية. */
    suspend fun purgeMyExpired() {
        runCatching {
            Supa.client.from("stories").delete {
                filter { eq("user_id", me); lt("expires_at", java.time.Instant.now().toString()) }
            }
        }
    }

    suspend fun markSeen(storyId: Long) {
        runCatching {
            Supa.client.from("story_views").upsert(StoryView(storyId, me))
        }
    }

    suspend fun delete(storyId: Long) {
        Supa.client.from("stories").delete { filter { eq("id", storyId) } }
    }
}
