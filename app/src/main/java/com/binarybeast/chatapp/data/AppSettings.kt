package com.binarybeast.chatapp.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** كل الإعدادات محلية على الجهاز. كل إعداد هنا متوصّل بسلوك فعلي في التطبيق. */
data class Prefs(
    // المظهر والشات
    val darkMode: String = "system",          // system | light | dark
    val fontScale: Float = 1f,                // 0.85 .. 1.3
    val enterToSend: Boolean = false,
    // الإشعارات
    val notifMessages: Boolean = true,
    val notifPreview: Boolean = true,         // إظهار نص الرسالة في الإشعار
    val notifCalls: Boolean = true,
    // التخزين والبيانات
    val autoDownloadImages: Boolean = true,
    val uploadQuality: String = "standard",   // data | standard | high
    // المكالمات
    val lowDataCalls: Boolean = false,
    val noiseSuppression: Boolean = true,
    // الخصوصية والأمان
    val lastSeenVisible: Boolean = true,
    val whoCanCall: String = "everyone",      // everyone | nobody
    val screenSecurity: Boolean = false,      // منع لقطات الشاشة وإخفاء المعاينة في آخر التطبيقات
    val blocked: Set<String> = emptySet(),
    val typingVisible: Boolean = true,        // إرسال "بيكتب…" للطرف التاني
    val blockUnknown: Boolean = false,        // تجاهل رسايل اللي ماليش معاهم محادثة سابقة
    val groupInvites: String = "everyone",    // everyone | contacts | nobody
    val hideListPreview: Boolean = false,     // إخفاء آخر رسالة في قايمة المحادثات
    val hideNotifSender: Boolean = false,     // إخفاء اسم المرسل في الإشعار
    val autoLockSec: Int = 15,
    val pinFails: Int = 0,
    val pinLockUntil: Long = 0,               // قفل مؤقت بعد محاولات PIN غلط
    val readReceipts: Boolean = true,         // إرسال إيصال القراءة والفتح (ولو قفلته مش هتشوف بتوع غيرك)
    val chatTtls: Set<String> = emptySet(),   // "peerId=ثواني": مسح تلقائي لكل محادثة عادية
    val cacheLimitMb: Int = 500,              // حد الكاش المحلي للميديا (-1 = من غير حد)
    val cacheDays: Int = 7,                   // مسح الميديا المحمّلة بعد X يوم (0 = مش بتتمسح بالوقت)                // 0 = فوري
    // المتصفح
    val browserInApp: Boolean = true,
    val browserDesktop: Boolean = false,
    // بديل Firebase: خدمة بتفضل شغالة وبتستقبل الرسايل والمكالمات والتطبيق مقفول
    val keepAlive: Boolean = true,
    // تنظيم المحادثات
    val pinned: Set<String> = emptySet(),
    val archived: Set<String> = emptySet(),
    val muted: Set<String> = emptySet(),      // محادثات مكتومة (من غير إشعارات)
    val wallpaper: String = "doodle",        // خلفية الشات: doodle/aurora/dots/plain
    val accent: String = "indigo",             // لون التطبيق
    val pinHash: String = "",
    val dataSaver: Boolean = false,           // وضع توفير البيانات (مفتاح رئيسي)                 // قفل التطبيق (salt:hash)، فاضي = معطّل
) {
    /** أقصى بُعد للصورة المرفوعة، وجودة JPEG. */
    // "standard" و"data" بيتبعتوا كـ photo (تيليجرام بيصغّرها لـ 1280 أصلًا)، و"high" كـ document بالجودة الكاملة
    val imageMaxDim: Int get() = when (uploadQuality) { "data" -> 1080; "high" -> 2560; else -> 1280 }
    val jpegQuality: Int get() = when (uploadQuality) { "data" -> 70; "high" -> 95; else -> 85 }
    /** ارتفاع الفيديو عند التصدير. */
    val videoHeight: Int get() = when (uploadQuality) { "data" -> 480; "high" -> 1080; else -> 720 }
}

object AppSettings {
    fun chatTtl(peerId: String): Int = _prefs.value.chatTtls.firstOrNull { it.startsWith("$peerId=") }?.substringAfter("=")?.toIntOrNull() ?: 0
    fun setChatTtl(peerId: String, sec: Int) = update { p ->
        val rest = p.chatTtls.filterNot { it.startsWith("$peerId=") }.toSet()
        p.copy(chatTtls = if (sec > 0) rest + "$peerId=$sec" else rest)
    }

    private lateinit var sp: SharedPreferences
    private val _prefs = MutableStateFlow(Prefs())
    val prefs: StateFlow<Prefs> = _prefs.asStateFlow()
    val now: Prefs get() = _prefs.value

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _prefs.value = read()
    }

    private fun read(): Prefs {
        val d = Prefs()
        return Prefs(
            darkMode = sp.getString("darkMode", d.darkMode) ?: d.darkMode,
            fontScale = sp.getFloat("fontScale", d.fontScale).coerceIn(0.85f, 1.3f),
            enterToSend = sp.getBoolean("enterToSend", d.enterToSend),
            notifMessages = sp.getBoolean("notifMessages", d.notifMessages),
            notifPreview = sp.getBoolean("notifPreview", d.notifPreview),
            notifCalls = sp.getBoolean("notifCalls", d.notifCalls),
            autoDownloadImages = sp.getBoolean("autoDownloadImages", d.autoDownloadImages),
            uploadQuality = sp.getString("uploadQuality", d.uploadQuality) ?: d.uploadQuality,
            lowDataCalls = sp.getBoolean("lowDataCalls", d.lowDataCalls),
            noiseSuppression = sp.getBoolean("noiseSuppression", d.noiseSuppression),
            lastSeenVisible = sp.getBoolean("lastSeenVisible", d.lastSeenVisible),
            whoCanCall = sp.getString("whoCanCall", d.whoCanCall) ?: d.whoCanCall,
            screenSecurity = sp.getBoolean("screenSecurity", d.screenSecurity),
            blocked = sp.getStringSet("blocked", emptySet())?.toSet() ?: emptySet(),
            typingVisible = sp.getBoolean("typingVisible", d.typingVisible),
            blockUnknown = sp.getBoolean("blockUnknown", d.blockUnknown),
            groupInvites = sp.getString("groupInvites", d.groupInvites) ?: d.groupInvites,
            hideListPreview = sp.getBoolean("hideListPreview", d.hideListPreview),
            hideNotifSender = sp.getBoolean("hideNotifSender", d.hideNotifSender),
            autoLockSec = sp.getInt("autoLockSec", d.autoLockSec),
            pinFails = sp.getInt("pinFails", 0),
            pinLockUntil = sp.getLong("pinLockUntil", 0),
            readReceipts = sp.getBoolean("readReceipts", d.readReceipts),
            chatTtls = sp.getStringSet("chatTtls", emptySet())?.toSet() ?: emptySet(),
            cacheLimitMb = sp.getInt("cacheLimitMb", d.cacheLimitMb),
            cacheDays = sp.getInt("cacheDays", d.cacheDays),
            browserInApp = sp.getBoolean("browserInApp", d.browserInApp),
            browserDesktop = sp.getBoolean("browserDesktop", d.browserDesktop),
            keepAlive = sp.getBoolean("keepAlive", d.keepAlive),
            pinned = sp.getStringSet("pinned", emptySet())?.toSet() ?: emptySet(),
            archived = sp.getStringSet("archived", emptySet())?.toSet() ?: emptySet(),
            muted = sp.getStringSet("muted", emptySet())?.toSet() ?: emptySet(),
            accent = sp.getString("accent", d.accent) ?: d.accent,
            wallpaper = sp.getString("wallpaper", d.wallpaper) ?: d.wallpaper,
            pinHash = sp.getString("pinHash", "") ?: "",
            dataSaver = sp.getBoolean("dataSaver", d.dataSaver),
        )
    }

    private fun write(p: Prefs) {
        sp.edit()
            .putString("darkMode", p.darkMode)
            .putFloat("fontScale", p.fontScale)
            .putBoolean("enterToSend", p.enterToSend)
            .putBoolean("notifMessages", p.notifMessages)
            .putBoolean("notifPreview", p.notifPreview)
            .putBoolean("notifCalls", p.notifCalls)
            .putBoolean("autoDownloadImages", p.autoDownloadImages)
            .putString("uploadQuality", p.uploadQuality)
            .putBoolean("lowDataCalls", p.lowDataCalls)
            .putBoolean("noiseSuppression", p.noiseSuppression)
            .putBoolean("lastSeenVisible", p.lastSeenVisible)
            .putString("whoCanCall", p.whoCanCall)
            .putBoolean("screenSecurity", p.screenSecurity)
            .putStringSet("blocked", p.blocked)
            .putBoolean("typingVisible", p.typingVisible)
            .putBoolean("blockUnknown", p.blockUnknown)
            .putString("groupInvites", p.groupInvites)
            .putBoolean("hideListPreview", p.hideListPreview)
            .putBoolean("hideNotifSender", p.hideNotifSender)
            .putInt("autoLockSec", p.autoLockSec)
            .putInt("pinFails", p.pinFails)
            .putLong("pinLockUntil", p.pinLockUntil)
            .putBoolean("readReceipts", p.readReceipts)
            .putStringSet("chatTtls", p.chatTtls)
            .putInt("cacheLimitMb", p.cacheLimitMb)
            .putInt("cacheDays", p.cacheDays)
            .putBoolean("browserInApp", p.browserInApp)
            .putBoolean("browserDesktop", p.browserDesktop)
            .putBoolean("keepAlive", p.keepAlive)
            .putStringSet("pinned", p.pinned)
            .putStringSet("archived", p.archived)
            .putStringSet("muted", p.muted)
            .putString("accent", p.accent)
            .putString("wallpaper", p.wallpaper)
            .putString("pinHash", p.pinHash)
            .putBoolean("dataSaver", p.dataSaver)
            .apply()
    }

    fun update(transform: (Prefs) -> Prefs) {
        val n = transform(_prefs.value)
        _prefs.value = n
        write(n)
    }

    fun isBlocked(userId: String) = userId in _prefs.value.blocked
    fun block(userId: String) = update { it.copy(blocked = it.blocked + userId) }
    fun unblock(userId: String) = update { it.copy(blocked = it.blocked - userId) }
    fun togglePin(id: String) = update { it.copy(pinned = if (id in it.pinned) it.pinned - id else it.pinned + id) }
    fun toggleMute(id: String) = update { it.copy(muted = if (id in it.muted) it.muted - id else it.muted + id) }
    fun toggleArchive(id: String) = update { it.copy(archived = if (id in it.archived) it.archived - id else it.archived + id) }

    /** عند تسجيل الخروج: امسح اللي مرتبط بالمستخدم (قايمة الحظر)، وسيب تفضيلات الجهاز. */
    fun clearUserData() = update { it.copy(blocked = emptySet(), pinned = emptySet(), archived = emptySet(), muted = emptySet()) }
}
