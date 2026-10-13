package com.binarybeast.chatapp

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.binarybeast.chatapp.call.CallManager
import com.binarybeast.chatapp.data.AppSettings
import com.binarybeast.chatapp.data.ChatEngine
import com.binarybeast.chatapp.data.Supa
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * بديل Firebase: خدمة foreground بتفضل شاغّلة اتصال Realtime مع Supabase،
 * فالرسايل (الصندوق المشفّر) والمكالمات بتوصل فورًا والتطبيق مقفول، من غير أي خدمة جوجل.
 * كل دقيقتين بتتأكد إن الاتصال شغال وبتسحب أي حاجة فاتت.
 */
class KeepAliveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = NotificationCompat.Builder(this, Notifier.CH_SERVICE)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("متصل — الرسايل والمكالمات شغّالة")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true).setSilent(true)
            .setContentIntent(open).build()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(7100, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(7100, n)

        scope.launch {
            Supa.client.auth.awaitInitialization()
            while (true) {
                if (Supa.client.auth.currentUserOrNull() == null) { stopSelf(); return@launch }
                runCatching {
                    ChatEngine.start()
                    CallManager.startListening()
                    ChatEngine.drainMailbox()
                    CallManager.checkPending()
                }
                delay(120_000)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        fun start(ctx: Context) {
            runCatching {
                val i = Intent(ctx, KeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            }
        }
        fun stop(ctx: Context) { runCatching { ctx.stopService(Intent(ctx, KeepAliveService::class.java)) } }
    }
}

/** بيشغّل الخدمة بعد إعادة تشغيل الجهاز أو تحديث التطبيق. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        AppSettings.init(ctx)
        if (AppSettings.now.keepAlive) KeepAliveService.start(ctx)
    }
}
