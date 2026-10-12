package com.binarybeast.chatapp

import com.binarybeast.chatapp.call.CallManager
import com.binarybeast.chatapp.data.ChatEngine
import com.binarybeast.chatapp.data.Push
import com.binarybeast.chatapp.data.Supa
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * بيصحّي التطبيق وهو مقفول. الإشعار مفيهوش أي محتوى: بس "في جديد".
 *  - kind=msg  : نسحب الرسايل المشفّرة من الصندوق ونفكّها محليًا (Notifier بيعرض الإشعار).
 *  - kind=call : نشترك في الإشارات ونسحب الـ offer اللي لسه ماعدّاش 45 ثانية.
 */
class PushService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        scope.launch {
            Supa.client.auth.awaitInitialization()
            Push.registerToken(token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val kind = message.data["kind"] ?: return
        scope.launch {
            // استنى لحد ما الـ session تترجّع من التخزين
            Supa.client.auth.awaitInitialization()
            if (Supa.client.auth.currentUserOrNull() == null) return@launch
            withTimeoutOrNull(20_000) {
                ChatEngine.start()
                when (kind) {
                    "msg" -> ChatEngine.drainMailbox()
                    "call" -> {
                        CallManager.startListening()
                        CallManager.checkPending()
                    }
                }
            }
        }
    }
}
