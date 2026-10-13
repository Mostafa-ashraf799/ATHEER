package com.binarybeast.chatapp.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.binarybeast.chatapp.data.ChatEngine
import com.binarybeast.chatapp.data.local.GroupEntity

/** نافذة إضافة أعضاء (أرقام هاتف أو @username، واحد في كل سطر). */
@Composable
fun AddMembersDialog(onAdd: (String) -> Unit, onClose: () -> Unit) {
    var txt by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("إضافة أعضاء") },
        text = {
            OutlinedTextField(
                value = txt, onValueChange = { txt = it }, minLines = 3,
                label = { Text("أرقام أو @username (كل واحد في سطر)") },
            )
        },
        confirmButton = { TextButton(onClick = { if (txt.isNotBlank()) onAdd(txt); onClose() }) { Text("إضافة") } },
        dismissButton = { TextButton(onClick = onClose) { Text("إلغاء") } },
    )
}

/** بروفايل المجموعة: الصورة والاسم والأعضاء (بصورهم) وإضافة/إزالة، والمؤقّت. لقطة الشاشة ممنوعة. */
@Composable
fun GroupProfileDialog(
    group: GroupEntity,
    myId: String?,
    names: Map<String, String>,
    onRename: (String) -> Unit,
    onAvatar: (ByteArray, String) -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onTtl: (Long) -> Unit,
    onLeave: () -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val isAdmin = group.adminId == myId
    var newName by remember(group.name) { mutableStateOf(group.name) }
    var adding by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { val (b, _, m) = readUri(ctx, it); onAvatar(b, m) }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        (androidx.compose.ui.platform.LocalView.current.parent as? DialogWindowProvider)
            ?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        Box(Modifier.fillMaxSize()) {
            AuroraBackground()
            Column(Modifier.fillMaxSize()) {
                GlassTopBar(
                    title = { Text("معلومات المجموعة") },
                    navigationIcon = { GlassCircleButton(AIcons.ArrowBack, onClose, size = 40.dp, contentDescription = "رجوع") },
                )
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box {
                        Avatar(group.avatarFileId, group.name, 132.dp, ring = LocalAccent.current)
                        if (isAdmin) GlassCircleButton(
                            AIcons.CameraAlt, { AppLock.skipNext(); picker.launch("image/*") },
                            Modifier.align(Alignment.BottomEnd), size = 44.dp, accent = true, contentDescription = "تغيير صورة المجموعة",
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    if (isAdmin) {
                        OutlinedTextField(value = newName, onValueChange = { newName = it.take(100) }, label = { Text("اسم المجموعة") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (newName.trim() != group.name && newName.isNotBlank()) TextButton(onClick = { onRename(newName) }) { Text("حفظ الاسم") }
                    } else Text(group.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text("${group.memberList().size} أعضاء", color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Spacer(Modifier.height(16.dp))
                    if (isAdmin) Row(
                        Modifier.fillMaxWidth().height(52.dp).glass(CircleShape, LocalAccent.current).clickable { adding = true },
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AIcons.GroupAdd, null, tint = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text("إضافة أعضاء", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                    }

                    Spacer(Modifier.height(16.dp))
                    Text("الأعضاء", Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelLarge, color = LocalAccent.current)
                    Box(Modifier.fillMaxWidth().padding(top = 6.dp).glass(RoundedCornerShape(24.dp))) {
                        Column(Modifier.padding(vertical = 6.dp)) {
                            group.memberList().forEach { id ->
                                val prof by produceState<com.binarybeast.chatapp.data.Profile?>(null, id) { value = ChatEngine.profile(id) }
                                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Avatar(prof?.avatarFileId, names[id] ?: prof?.displayName ?: "؟", 44.dp)
                                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                        Text(if (id == myId) "أنا" else (names[id] ?: prof?.displayName?.ifBlank { null } ?: "…"), fontWeight = FontWeight.SemiBold)
                                        val sub = if (id == group.adminId) "أدمن" else prof?.username?.let { "@$it" }
                                        if (sub != null) Text(sub, style = MaterialTheme.typography.labelMedium, color = if (id == group.adminId) LocalAccent.current else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (isAdmin && id != myId) TextButton(onClick = { onRemove(id) }) { Text("إزالة", color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }

                    if (isAdmin) {
                        Spacer(Modifier.height(16.dp))
                        Text("مسح الرسايل تلقائيًا", Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelLarge, color = LocalAccent.current)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            listOf("معطّل" to 0L, "ساعة" to 3600L, "يوم" to 86400L, "أسبوع" to 604800L).forEach { (label, sec) ->
                                TextButton(onClick = { onTtl(sec) }) { Text(label, fontWeight = if (group.ttlSec == sec) FontWeight.Bold else FontWeight.Normal) }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onLeave) { Text("خروج من المجموعة", color = MaterialTheme.colorScheme.error) }
                    Text("🔒 لقطة الشاشة مقفولة في الصفحة دي", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (adding) AddMembersDialog(onAdd = onAdd, onClose = { adding = false })
}
