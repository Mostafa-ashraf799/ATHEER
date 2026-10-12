package com.binarybeast.chatapp.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.binarybeast.chatapp.call.CallManager
import com.binarybeast.chatapp.call.CallState
import com.binarybeast.chatapp.data.AppSettings
import com.binarybeast.chatapp.data.AuthRepository
import com.binarybeast.chatapp.data.ChatEngine
import com.binarybeast.chatapp.data.Profile
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

private enum class Tab(val label: String) { Chats("الشات"), Stories("الستوريز"), Channels("القنوات"), Calls("المكالمات"), Profile("حسابي") }

@Composable
fun AppRoot() {
    val status by AuthRepository.sessionStatus.collectAsState(initial = SessionStatus.Initializing)
    val ctx0 = LocalContext.current

    when (status) {
        is SessionStatus.Authenticated -> AuthedApp()
        is SessionStatus.Initializing -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        else -> {
            LaunchedEffect(Unit) { com.binarybeast.chatapp.KeepAliveService.stop(ctx0); CallManager.stopListening(); ChatEngine.stop(); com.binarybeast.chatapp.data.ChannelRepository.stopListening() }
            LoginScreen()
        }
    }
}

@Composable
private fun AuthedApp() {
    val session: SessionVm = viewModel()
    val me by session.me.collectAsState()
    val callState by CallManager.state.collectAsState()
    val prefs by AppSettings.prefs.collectAsState()
    val ctx = LocalContext.current

    LaunchedEffect(Unit) {
        session.load()
        CallManager.startListening()
        ChatEngine.start()
        com.binarybeast.chatapp.data.ChannelRepository.startListening(ctx)
        CallManager.checkPending()
        if (prefs.keepAlive) com.binarybeast.chatapp.KeepAliveService.start(ctx)
        // تسجيل FCM token (بيتخطّى لو Firebase مش متفعّل)
        runCatching {
            if (com.google.firebase.FirebaseApp.getApps(ctx).isNotEmpty()) {
                com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                    .addOnSuccessListener { t -> GlobalScope.launch { com.binarybeast.chatapp.data.Push.registerToken(t) } }
            }
        }
    }

    // أول دخول: لازم يختار يوزر نيم قبل ما يكمّل
    if (me != null && me!!.username.isNullOrBlank()) {
        UsernameScreen(defaultName = me!!.displayName, session = session)
        return
    }
    if (me == null) { Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }; return }

    var tab by remember { mutableStateOf(Tab.Chats) }
    var openPeer by remember { mutableStateOf<Profile?>(null) }
    var openChannel by remember { mutableStateOf<com.binarybeast.chatapp.data.Channel?>(null) }
    var browserUrl by remember { mutableStateOf<String?>(null) }

    // فتح الرابط: في المتصفح المدمج أو الخارجي حسب الإعدادات. بيقبل http/https بس.
    val openLink: (String) -> Unit = { raw ->
        val u = normalizeUrl(raw)
        val scheme = Uri.parse(u).scheme?.lowercase()
        if (scheme == "http" || scheme == "https") {
            if (prefs.browserInApp) browserUrl = u
            else runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    CompositionLocalProvider(LocalOpenLink provides openLink) {
        val url = browserUrl
        when {
            callState != CallState.Idle -> CallScreen()
            url != null -> BrowserScreen(url, onClose = { browserUrl = null })
            openChannel != null -> ChannelView(openChannel!!, onBack = { openChannel = null })
            openPeer != null -> {
                BackHandler { openPeer = null }
                ChatScreen(peer = openPeer!!, onBack = { openPeer = null })
            }
            else -> Scaffold(
                bottomBar = {
                    FloatingNavBar(
                        items = Tab.entries.map { t ->
                            (when (t) {
                                Tab.Chats -> AIcons.Chat
                                Tab.Stories -> AIcons.AutoAwesome
                                Tab.Channels -> AIcons.Campaign
                                Tab.Calls -> AIcons.Call
                                Tab.Profile -> AIcons.Person
                            }) to t.label
                        },
                        selected = tab.ordinal,
                        onSelect = { tab = Tab.entries[it] },
                    )
                },
            ) { pad ->
                Box(Modifier.padding(pad).fillMaxSize()) {
                    when (tab) {
                        Tab.Chats -> ChatListScreen(onOpen = { openPeer = it })
                        Tab.Stories -> StoriesScreen(me = me)
                        Tab.Channels -> ChannelsScreen(onOpen = { openChannel = it })
                        Tab.Calls -> CallsScreen(onOpenChat = { openPeer = it; tab = Tab.Chats })
                        Tab.Profile -> ProfileScreen(session = session)
                    }
                }
            }
        }
    }
}
