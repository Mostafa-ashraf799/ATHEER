package com.binarybeast.chatapp.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

object MsgStatus {
    const val SENDING = 0
    const val SENT = 1        // اتبعتت (للسيرفر الاحتياطي أو P2P)
    const val DELIVERED = 2   // الطرف التاني أكّد الاستلام عن طريق P2P
    const val P2P_PENDING = 3 // اتبعتت P2P وبنستنى التأكيد
    const val READ = 4        // الطرف التاني فتح المحادثة وقرأها (إيصال القراءة)
    const val FAILED = -1
}

/** الرسايل بتتخزن على الجهاز بس. مفيش نسخة على أي سيرفر بعد ما بتوصل. */
@Entity(tableName = "messages", indices = [Index(value = ["msgId"], unique = true), Index("peerId")])
data class LocalMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val msgId: String,
    val peerId: String,
    val mine: Boolean,
    val body: String?,
    val mediaFileId: String?,
    val mediaType: String?,
    val createdAt: Long,
    val status: Int = MsgStatus.SENDING,
    val read: Boolean = false,
    val replyToId: String? = null,     // msgId للرسالة اللي بنرد عليها
    val replyText: String? = null,     // مقتطف من الرسالة الأصلية
    val myReaction: String? = null,    // تفاعلي على الرسالة
    val theirReaction: String? = null, // تفاعل الطرف التاني
    val edited: Boolean = false,
    val deleted: Boolean = false,
    val senderId: String? = null,      // في المجموعات: مين بعت الرسالة
    val viewMode: Int = 0,             // 0 عادي | 1 مرة واحدة | 2 مؤقّت (بيبدأ العدّ من أول فتح)
    val viewSecs: Int = 0,             // مدة الظهور بالثواني لو مؤقّت
    val openedAt: Long = 0,            // وقت أول فتح عند المستلم (أو عند المرسل: وقت وصول إيصال الفتح)
    val starred: Boolean = false,      // رسالة مميّزة بنجمة
    val expireAt: Long = 0,            // مسح تلقائي للمحادثات العادية (0 = مفيش)
)

/** رسالة نصية مجدولة بتتبعت لوحدها في الميعاد (والتطبيق شغّال/الخدمة الخلفية شغّالة). */
@Entity(tableName = "scheduled_messages")
data class ScheduledMsg(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val peerId: String,
    val body: String,
    val sendAt: Long,
)

@Dao
interface ScheduledDao {
    @Insert suspend fun insert(m: ScheduledMsg): Long
    @Query("SELECT * FROM scheduled_messages WHERE sendAt <= :now ORDER BY sendAt") suspend fun due(now: Long): List<ScheduledMsg>
    @Query("SELECT * FROM scheduled_messages WHERE peerId = :peer ORDER BY sendAt") suspend fun forPeer(peer: String): List<ScheduledMsg>
    @Query("DELETE FROM scheduled_messages WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM scheduled_messages") suspend fun clear()
}

/** مجموعة: الأدمن هو الوحيد اللي يغيّر الأعضاء/الاسم/المؤقّت. الأعضاء مخزّنين كـ ids بفاصلة. */
@Entity(tableName = "chat_groups")
data class GroupEntity(
    @PrimaryKey val groupId: String,
    val name: String,
    val adminId: String,
    val members: String,
    val ttlSec: Long = 0,
    val createdAt: Long,
) {
    fun memberList(): List<String> = members.split(",").filter { it.isNotBlank() }
}

/** صوت في استطلاع: صوت واحد لكل عضو (التصويت تاني بيغيّر اختياره). */
@Entity(tableName = "poll_votes", primaryKeys = ["msgId", "voterId"])
data class PollVote(val msgId: String, val voterId: String, val option: Int)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE peerId = :peer ORDER BY createdAt ASC, id ASC")
    fun observe(peer: String): Flow<List<LocalMessage>>

    @Query("SELECT * FROM messages ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<LocalMessage>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(m: LocalMessage): Long

    @Query("UPDATE messages SET status = :status WHERE msgId = :msgId")
    suspend fun setStatus(msgId: String, status: Int)

    @Query("SELECT status FROM messages WHERE msgId = :msgId")
    suspend fun status(msgId: String): Int?

    @Query("UPDATE messages SET openedAt = :t WHERE id = :id AND openedAt = 0")
    suspend fun markOpened(id: Long, t: Long)

    /** حرق الميديا: بتتشال محليًا وبيفضل placeholder. */
    @Query("UPDATE messages SET mediaFileId = NULL, mediaType = 'gone', body = NULL WHERE id = :id")
    suspend fun burn(id: Long)

    @Query("UPDATE messages SET mediaFileId = NULL, mediaType = 'gone', body = NULL WHERE viewMode = 2 AND openedAt > 0 AND mediaType != 'gone' AND openedAt + viewSecs * 1000 < :now")
    suspend fun burnExpired(now: Long)

    @Query("SELECT msgId FROM messages WHERE peerId = :peer AND mine = 0 AND read = 0")
    suspend fun unreadIds(peer: String): List<String>

    @Query("UPDATE messages SET status = 4 WHERE peerId = :peer AND mine = 1 AND status >= 0 AND msgId IN (:ids)")
    suspend fun setReadByPeer(peer: String, ids: List<String>)

    @Query("UPDATE messages SET openedAt = :t WHERE peerId = :peer AND msgId = :msgId AND mine = 1 AND openedAt = 0")
    suspend fun markOpenedMine(peer: String, msgId: String, t: Long)

    @Query("UPDATE messages SET starred = :v WHERE msgId = :msgId")
    suspend fun setStarred(msgId: String, v: Boolean)

    @Query("DELETE FROM messages WHERE expireAt > 0 AND expireAt < :now")
    suspend fun deleteExpired(now: Long)

    @Query("SELECT COUNT(*) FROM messages WHERE peerId = :peer")
    suspend fun countFor(peer: String): Int

    @Query("UPDATE messages SET read = 1 WHERE peerId = :peer AND mine = 0 AND read = 0")
    suspend fun markRead(peer: String)

    @Query("DELETE FROM messages")
    suspend fun clear()

    @Query("UPDATE messages SET myReaction = :emoji WHERE msgId = :msgId")
    suspend fun setMyReaction(msgId: String, emoji: String?)

    // التعديلات القادمة من الطرف التاني بتتطبّق بس على رسايله هو (mine = 0)
    @Query("UPDATE messages SET theirReaction = :emoji WHERE msgId = :msgId AND peerId = :peer")
    suspend fun setTheirReaction(peer: String, msgId: String, emoji: String?)

    @Query("UPDATE messages SET body = :body, edited = 1 WHERE msgId = :msgId AND peerId = :peer AND mine = :mine AND deleted = 0 AND (senderId IS NULL OR senderId = :sender)")
    suspend fun editBody(peer: String, msgId: String, body: String, mine: Boolean, sender: String)

    @Query("UPDATE messages SET body = NULL, mediaFileId = NULL, mediaType = NULL, deleted = 1, theirReaction = NULL, myReaction = NULL WHERE msgId = :msgId AND peerId = :peer AND mine = :mine AND (senderId IS NULL OR senderId = :sender)")
    suspend fun markDeleted(peer: String, msgId: String, mine: Boolean, sender: String)

    @Query("DELETE FROM messages WHERE msgId = :msgId")
    suspend fun deleteLocal(msgId: String)

    @Query("DELETE FROM messages WHERE peerId = :peer AND createdAt < :cutoff")
    suspend fun deleteOlder(peer: String, cutoff: Long)

    @Query("DELETE FROM messages WHERE peerId = :peer")
    suspend fun deleteChat(peer: String)

    @Query("SELECT peerId FROM messages WHERE msgId = :msgId")
    suspend fun peerOf(msgId: String): String?
}

@Dao
interface GroupDao {
    @Query("SELECT * FROM chat_groups WHERE groupId = :id")
    suspend fun get(id: String): GroupEntity?

    @Query("SELECT * FROM chat_groups WHERE groupId = :id")
    fun observe(id: String): Flow<GroupEntity?>

    @Query("SELECT * FROM chat_groups")
    suspend fun all(): List<GroupEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(g: GroupEntity)

    @Query("DELETE FROM chat_groups WHERE groupId = :id")
    suspend fun delete(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun vote(v: PollVote)

    @Query("SELECT * FROM poll_votes WHERE msgId IN (SELECT msgId FROM messages WHERE peerId = :peer AND mediaType = 'poll')")
    fun observeVotes(peer: String): Flow<List<PollVote>>

    @Query("DELETE FROM chat_groups")
    suspend fun clearGroups()

    @Query("DELETE FROM poll_votes")
    suspend fun clearVotes()
}

@Database(entities = [LocalMessage::class, GroupEntity::class, PollVote::class, ScheduledMsg::class], version = 5, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun groups(): GroupDao
    abstract fun scheduled(): ScheduledDao

    companion object {
        fun create(ctx: Context): AppDb =
            Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "chat.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN starred INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN expireAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS `scheduled_messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `peerId` TEXT NOT NULL, `body` TEXT NOT NULL, `sendAt` INTEGER NOT NULL)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN viewMode INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN viewSecs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN openedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN senderId TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS `chat_groups` (`groupId` TEXT NOT NULL, `name` TEXT NOT NULL, `adminId` TEXT NOT NULL, `members` TEXT NOT NULL, `ttlSec` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`groupId`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `poll_votes` (`msgId` TEXT NOT NULL, `voterId` TEXT NOT NULL, `option` INTEGER NOT NULL, PRIMARY KEY(`msgId`, `voterId`))")
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN replyToId TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN replyText TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN myReaction TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN theirReaction TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN edited INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
