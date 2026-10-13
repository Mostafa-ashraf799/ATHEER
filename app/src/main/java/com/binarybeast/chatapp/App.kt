package com.binarybeast.chatapp

import android.app.Application
import com.binarybeast.chatapp.call.CallManager
import com.binarybeast.chatapp.data.ChatEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        com.binarybeast.chatapp.data.AppSettings.init(this)
        com.binarybeast.chatapp.data.TelegramStorage.init(this)
        Notifier.init(this)
        CallManager.init(this)
        ChatEngine.init(this)
        ChatEngine.onIncoming = { msg, profile ->
            // مفيش إشعار لو المحادثة دي مفتوحة قدام المستخدم
            if (ChatEngine.openPeerId != msg.peerId) {
                val who = profile?.displayName?.ifBlank { profile.phone } ?: "رسالة جديدة"
                if (ChatEngine.isGroup(msg.peerId)) {
                    // في المجموعات: «اسم المجموعة — اسم المرسل»
                    CoroutineScope(Dispatchers.IO).launch {
                        val gn = ChatEngine.groups.get(msg.peerId.removePrefix("g:"))?.name ?: "مجموعة"
                        Notifier.message(this@App, msg, "$gn — $who")
                    }
                } else Notifier.message(this, msg, who)
            }
        }
    }
}
