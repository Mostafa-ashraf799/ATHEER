package com.binarybeast.chatapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.binarybeast.chatapp.call.CallManager
import com.binarybeast.chatapp.data.AppSettings
import com.binarybeast.chatapp.data.Profile

/** بروفايل المستخدم (من الضغط على اسمه/صورته في الشات). لقطة الشاشة ممنوعة هنا. */
@Composable
fun PeerProfileDialog(peer: Profile, myId: String?, onClose: () -> Unit) {
    val prefs by AppSettings.prefs.collectAsState()
    val blocked = peer.id in prefs.blocked
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        (androidx.compose.ui.platform.LocalView.current.parent as? DialogWindowProvider)
            ?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        Box(Modifier.fillMaxSize()) {
            AuroraBackground()
            Column(Modifier.fillMaxSize()) {
                GlassTopBar(
                    title = { Text("معلومات المستخدم") },
                    navigationIcon = { GlassCircleButton(AIcons.ArrowBack, onClose, size = 40.dp, contentDescription = "رجوع") },
                )
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(12.dp))
                    Avatar(peer.avatarFileId, peer.displayName.ifBlank { peer.username ?: "؟" }, 132.dp, ring = LocalAccent.current)
                    Spacer(Modifier.height(16.dp))
                    Text(peer.displayName.ifBlank { peer.username ?: "مستخدم" }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    peer.username?.let { Text("@$it", color = LocalAccent.current, style = MaterialTheme.typography.titleMedium) }
                    Spacer(Modifier.height(20.dp))
                    if (peer.id != myId) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        ProfileAction(AIcons.Chat, "رسالة") { onClose() }
                        ProfileAction(AIcons.Call, "صوت") { onClose(); CallManager.startCall(peer.id, video = false) }
                        ProfileAction(AIcons.Videocam, "فيديو") { onClose(); CallManager.startCall(peer.id, video = true) }
                        ProfileAction(AIcons.Block, if (blocked) "إلغاء الحظر" else "حظر") {
                            if (blocked) AppSettings.unblock(peer.id) else AppSettings.block(peer.id)
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    InfoCard("النبذة", peer.about.ifBlank { "مفيش نبذة" })
                    peer.username?.let { InfoCard("اسم المستخدم", "@$it") }
                    peer.lastSeen?.let { InfoCard("آخر ظهور", it.replace("T", " ").take(16)) }
                    Spacer(Modifier.height(16.dp))
                    Text("🔒 لقطة الشاشة مقفولة في الصفحة دي", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ProfileAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        GlassCircleButton(icon, onClick, size = 56.dp, accent = true, contentDescription = label)
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun InfoCard(title: String, value: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 5.dp).glass(RoundedCornerShape(22.dp))) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
