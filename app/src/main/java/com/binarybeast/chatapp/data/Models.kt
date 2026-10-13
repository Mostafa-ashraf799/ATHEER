package com.binarybeast.chatapp.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class Profile(
    val id: String,
    val phone: String = "",
    val username: String? = null,
    val discoverable: Boolean = true,
    @SerialName("display_name") val displayName: String = "",
    val about: String = "",
    @SerialName("avatar_file_id") val avatarFileId: String? = null,
    @SerialName("public_key") val publicKey: String? = null,
    val settings: JsonObject? = null,
    @SerialName("last_seen") val lastSeen: String? = null,
)

/** صف في الصندوق الاحتياطي: رسالة مشفّرة مستنية المستلم يسحبها. السيرفر مبيقدرش يقراها. */
@Serializable
data class MailboxRow(
    val id: Long = 0,
    @SerialName("sender_id") val senderId: String,
    @SerialName("receiver_id") val receiverId: String,
    @SerialName("msg_id") val msgId: String,
    val ciphertext: String,
)

@Serializable
data class NewMailboxRow(
    @SerialName("sender_id") val senderId: String,
    @SerialName("receiver_id") val receiverId: String,
    @SerialName("msg_id") val msgId: String,
    val ciphertext: String,
)

@Serializable
data class Story(
    val id: Long = 0,
    @SerialName("user_id") val userId: String,
    @SerialName("file_id") val fileId: String,
    @SerialName("media_type") val mediaType: String = "image",
    val caption: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("expires_at") val expiresAt: String = "",
)

@Serializable
data class NewStory(
    @SerialName("user_id") val userId: String,
    @SerialName("file_id") val fileId: String,
    @SerialName("media_type") val mediaType: String = "image",
    val caption: String? = null,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
data class StoryView(
    @SerialName("story_id") val storyId: Long,
    @SerialName("viewer_id") val viewerId: String,
)

@Serializable
data class CallSignal(
    val id: Long = 0,
    @SerialName("call_id") val callId: String,
    @SerialName("from_id") val fromId: String,
    @SerialName("to_id") val toId: String,
    val type: String,                       // offer | answer | ice | hangup | reject | hello
    val video: Boolean = false,
    val payload: JsonElement? = null,
    val purpose: String = "call",           // call | chat (اتصال الشات P2P بيستخدم نفس الجدول)
)

@Serializable
data class NewCallSignal(
    @SerialName("call_id") val callId: String,
    @SerialName("from_id") val fromId: String,
    @SerialName("to_id") val toId: String,
    val type: String,
    val video: Boolean = false,
    val payload: JsonElement? = null,
    val purpose: String = "call",
)

@Serializable
data class CallLog(
    val id: String,
    @SerialName("caller_id") val callerId: String,
    @SerialName("callee_id") val calleeId: String,
    val video: Boolean = false,
    val status: String = "ringing",
    @SerialName("started_at") val startedAt: String = "",
    @SerialName("duration_s") val durationS: Int = 0,
)

/** عنصر في قايمة المحادثات. */
data class ChatPreview(
    val peer: Profile,
    val last: com.binarybeast.chatapp.data.local.LocalMessage,
    val unread: Int,
)

/** ستوريز مجمّعة حسب صاحبها. */
data class StoryGroup(
    val user: Profile,
    val stories: List<Story>,
    val seen: Boolean,
)

@Serializable
data class Channel(
    val id: Long = 0,
    @SerialName("owner_id") val ownerId: String,
    val handle: String,
    val name: String,
    val description: String = "",
    @SerialName("avatar_file_id") val avatarFileId: String? = null,
    @SerialName("members_count") val membersCount: Int = 0,
)

@Serializable
data class NewChannel(
    @SerialName("owner_id") val ownerId: String,
    val handle: String,
    val name: String,
    val description: String = "",
)

@Serializable
data class ChannelMember(
    @SerialName("channel_id") val channelId: Long,
    @SerialName("user_id") val userId: String,
)

@Serializable
data class ChannelPost(
    val id: Long = 0,
    @SerialName("channel_id") val channelId: Long,
    @SerialName("author_id") val authorId: String,
    val body: String? = null,
    @SerialName("media_file_id") val mediaFileId: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class NewChannelPost(
    @SerialName("channel_id") val channelId: Long,
    @SerialName("author_id") val authorId: String,
    val body: String? = null,
    @SerialName("media_file_id") val mediaFileId: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
)
