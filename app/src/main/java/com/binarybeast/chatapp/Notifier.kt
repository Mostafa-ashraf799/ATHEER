package com.binarybeast.chatapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.binarybeast.chatapp.data.local.LocalMessage

object Notifier {
    private const val CH_MSG = "messages"
    private const val CH_CALL = "calls"
    private const val CALL_NOTIF_ID = 7001
    const val CH_SERVICE = "service"

    fun init(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "الاتصال في الخلفية", NotificationManager.IMPORTANCE_MIN)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_MSG, "الرسايل", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_CALL, "المكالمات", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
    }

    private fun openApp(ctx: Context, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            ctx, requestCode,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun message(ctx: Context, m: LocalMessage, senderName: String) {
        val prefs = com.binarybeast.chatapp.data.AppSettings.now
        if (!prefs.notifMessages || m.peerId in prefs.muted) return
        val text = if (!prefs.notifPreview) "رسالة جديدة"
        else m.body ?: when (m.mediaType) { "image" -> "📷 صورة"; "video" -> "🎬 فيديو"; "audio" -> "🎤 رسالة صوتية"; "poll" -> "📊 استطلاع"; else -> "📎 ملف" }
        val n = NotificationCompat.Builder(ctx, CH_MSG)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(if (prefs.hideNotifSender) "ATHEER" else senderName)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(if (prefs.notifPreview && !prefs.hideNotifSender) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(openApp(ctx, m.peerId.hashCode()))
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(m.peerId.hashCode(), n) }
    }

    /** منشور جديد في قناة مشترك فيها. */
    fun channelPost(ctx: Context, channelId: Long, title: String, text: String) {
        val prefs = com.binarybeast.chatapp.data.AppSettings.now
        if (!prefs.notifMessages || "c:$channelId" in prefs.muted) return
        val n = NotificationCompat.Builder(ctx, CH_MSG)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(if (prefs.hideNotifSender) "ATHEER" else title)
            .setContentText(if (prefs.notifPreview) text else "منشور جديد")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(ctx, ("c:$channelId").hashCode()))
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(("c:$channelId").hashCode(), n) }
    }

    /** إشعار مكالمة واردة بشاشة كاملة (بيظهر فوق شاشة القفل). */
    fun call(ctx: Context, name: String, video: Boolean) {
        if (!com.binarybeast.chatapp.data.AppSettings.now.notifCalls) return
        val pi = openApp(ctx, CALL_NOTIF_ID)
        val n = NotificationCompat.Builder(ctx, CH_CALL)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(name)
            .setContentText(if (video) "مكالمة فيديو واردة" else "مكالمة صوت واردة")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setTimeoutAfter(45_000)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(CALL_NOTIF_ID, n) }
    }

    fun cancelCall(ctx: Context) {
        runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(CALL_NOTIF_ID) }
    }
}
