package com.binarybeast.chatapp.ui

import android.content.Intent
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.binarybeast.chatapp.data.AppSettings
import com.binarybeast.chatapp.data.ChatEngine
import com.binarybeast.chatapp.data.Profile
import com.binarybeast.chatapp.data.ProfileRepository
import kotlinx.coroutines.launch
import java.io.File

private enum class Page(val title: String) {
    Main("حسابي"), Account("الحساب"), Privacy("الخصوصية والأمان"), Chats("الشات"),
    Notifications("الإشعارات"), Storage("التخزين والبيانات"), Calls("المكالمات"),
    Browser("المتصفح"), Blocked("المحظورين"), About("عن التطبيق"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(session: SessionVm) {
    var page by remember { mutableStateOf(Page.Main) }
    BackHandler(enabled = page != Page.Main) { page = Page.Main }

    Scaffold(
        topBar = {
            GlassTopBar(
                title = { Text(page.title, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (page != Page.Main) IconButton(onClick = { page = Page.Main }) {
                        Icon(AIcons.ArrowBack, "رجوع")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            when (page) {
                Page.Main -> MainPage(session) { page = it }
                Page.Account -> AccountPage(session)
                Page.Privacy -> PrivacyPage { page = it }
                Page.Chats -> ChatsPage()
                Page.Notifications -> NotificationsPage()
                Page.Storage -> StoragePage()
                Page.Calls -> CallsPage()
                Page.Browser -> BrowserPage()
                Page.Blocked -> BlockedPage()
                Page.About -> AboutPage()
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ============================================================ الرئيسية

@Composable
private fun MainPage(session: SessionVm, go: (Page) -> Unit) {
    val me by session.me.collectAsState()
    val ctx = LocalContext.current
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { val (b, _, m) = readUri(ctx, it); session.setAvatar(b, m) }
    }

    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Avatar(me?.avatarFileId, me?.displayName.orEmpty().ifBlank { me?.phone.orEmpty() }, 110.dp)
            IconButton(onClick = { avatarPicker.launch("image/*") }, modifier = Modifier.align(Alignment.BottomEnd)) {
                Icon(AIcons.CameraAlt, "تغيير الصورة", tint = MaterialTheme.colorScheme.primary)
            }
        }
        Text(me?.displayName?.ifBlank { "اكتب اسمك من «الحساب»" } ?: "", style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 10.dp))
        Text(me?.about.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
    NavRow(AIcons.Person, "الحساب", "الاسم والحالة والصورة") { go(Page.Account) }
    NavRow(AIcons.Lock, "الخصوصية والأمان", "آخر ظهور، المكالمات، منع لقطات الشاشة") { go(Page.Privacy) }
    NavRow(AIcons.Chat, "الشات", "الثيم، حجم الخط، زر الإدخال") { go(Page.Chats) }
    NavRow(AIcons.Notifications, "الإشعارات", "الرسايل والمكالمات والمعاينة") { go(Page.Notifications) }
    NavRow(AIcons.Storage, "التخزين والبيانات", "التحميل التلقائي وجودة الرفع") { go(Page.Storage) }
    NavRow(AIcons.Call, "المكالمات", "توفير البيانات وإلغاء الضوضاء") { go(Page.Calls) }
    NavRow(AIcons.Public, "المتصفح", "فتح الروابط داخل التطبيق") { go(Page.Browser) }
    NavRow(AIcons.Block, "المحظورين", "${AppSettings.now.blocked.size} محظور") { go(Page.Blocked) }
    NavRow(AIcons.Info, "عن التطبيق", "الإصدار وملاحظات التشفير") { go(Page.About) }
}

// ============================================================ الحساب

@Composable
private fun AccountPage(session: SessionVm) {
    val me by session.me.collectAsState()
    var name by remember(me) { mutableStateOf(me?.displayName.orEmpty()) }
    var about by remember(me) { mutableStateOf(me?.about.orEmpty()) }
    var confirmLogout by remember { mutableStateOf(false) }
    var uname by remember(me) { mutableStateOf(me?.username.orEmpty()) }
    var unameMsg by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(uname, { uname = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' }.take(20) },
            label = { Text("اليوزر نيم") }, prefix = { Text("@") }, singleLine = true,
            supportingText = { Text(unameMsg ?: "الناس تقدر تدوّر عليك بيه (3–20: a-z و 0-9 و _)") },
            modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { scope.launch { unameMsg = session.saveUsername(uname) ?: "اتحفظ ✅" } },
            enabled = uname != me?.username.orEmpty(), modifier = Modifier.fillMaxWidth()) { Text("حفظ اليوزر نيم") }
        OutlinedTextField(name, { name = it.take(100) }, label = { Text("الاسم") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(about, { about = it.take(500) }, label = { Text("الحالة") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField("+" + me?.phone.orEmpty().removePrefix("+"), {}, label = { Text("رقم الهاتف (مؤكَّد عن طريق تيليجرام)") },
            readOnly = true, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { session.saveProfile(name.trim(), about.trim()) }, modifier = Modifier.fillMaxWidth()) { Text("حفظ") }
        HorizontalDivider()
        OutlinedButton(onClick = { confirmLogout = true }, modifier = Modifier.fillMaxWidth()) {
            Text("تسجيل الخروج", color = MaterialTheme.colorScheme.error)
        }
        Text(
            "تسجيل الخروج بيمسح الرسايل المحفوظة على الجهاز ده (مفيش نسخة احتياطية).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false },
        title = { Text("تسجيل الخروج؟") },
        text = { Text("هتتمسح كل الرسايل المحفوظة على الجهاز ده ومش هتقدر ترجّعها.") },
        confirmButton = { TextButton(onClick = { confirmLogout = false; session.signOut() }) { Text("خروج ومسح") } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("إلغاء") } },
    )
}

// ============================================================ الخصوصية

@Composable
private fun PrivacyPage(go: (Page) -> Unit) {
    val p by AppSettings.prefs.collectAsState()
    var pick by remember { mutableStateOf(false) }
    var pinDialog by remember { mutableStateOf(false) }
    var invPick by remember { mutableStateOf(false) }
    var lockPick by remember { mutableStateOf(false) }
    var wipeAsk by remember { mutableStateOf(false) }
    var discoverable by remember { mutableStateOf(true) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        runCatching { ProfileRepository.me() }.getOrNull()?.let { discoverable = it.discoverable }
    }

    ValueRow("قفل التطبيق (PIN)", if (p.pinHash.isNotEmpty()) "مفعّل" else "معطّل") { pinDialog = true }
    SwitchRow("إظهار آخر ظهور", "لو قفلته، آخر ظهور بيتمسح من بروفايلك", p.lastSeenVisible) {
        AppSettings.update { s -> s.copy(lastSeenVisible = it) }
    }
    ValueRow("مين يقدر يتصل بيا", if (p.whoCanCall == "nobody") "محدش" else "الكل") { pick = true }
    SwitchRow("منع لقطات الشاشة", "بيخفي محتوى التطبيق في الصور وفي قايمة التطبيقات الأخيرة", p.screenSecurity) {
        AppSettings.update { s -> s.copy(screenSecurity = it) }
    }
    SwitchRow("إيصالات القراءة والفتح", "بيبلّغ الطرف التاني إنك قريت رسايله وفتحت ميديا المرة الواحدة. لو قفلته مش هتشوف إيصالاتهم كمان", p.readReceipts) {
        AppSettings.update { s -> s.copy(readReceipts = it) }
    }
    SwitchRow("إظهار «بيكتب…»", "لو قفلته، محدش هيشوفك بتكتب", p.typingVisible) {
        AppSettings.update { s -> s.copy(typingVisible = it) }
    }
    SwitchRow("ممكن حد يلاقيني برقم تليفوني", "لو قفلته، محدش يقدر يضيفك بالرقم؛ بس باليوزر نيم (رقمك مخفي عن الكل في كل الأحوال)", discoverable) {
        discoverable = it
        scope.launch { runCatching { ProfileRepository.setDiscoverable(it) } }
    }
    SwitchRow("تجاهل الغرباء", "رسايل اللي ماليش معاهم محادثة سابقة بتتجاهل ومش بتظهرلك", p.blockUnknown) {
        AppSettings.update { s -> s.copy(blockUnknown = it) }
    }
    ValueRow("مين يضيفني في مجموعات", when (p.groupInvites) { "nobody" -> "محدش"; "contacts" -> "اللي ليا معاهم محادثة"; else -> "الكل" }) { invPick = true }
    ValueRow("القفل التلقائي", when (p.autoLockSec) { 0 -> "فوري"; 15 -> "بعد 15 ثانية"; 60 -> "بعد دقيقة"; else -> "بعد 5 دقايق" }) { lockPick = true }
    SwitchRow("إخفاء آخر رسالة في القايمة", "بتظهر «••••» بدل نص آخر رسالة", p.hideListPreview) {
        AppSettings.update { s -> s.copy(hideListPreview = it) }
    }
    SwitchRow("إخفاء اسم المرسل في الإشعار", "الإشعار بيظهر باسم التطبيق بس", p.hideNotifSender) {
        AppSettings.update { s -> s.copy(hideNotifSender = it) }
    }
    SwitchRow("إخفاء محتوى الإشعارات", "بيظهر «رسالة جديدة» من غير النص", !p.notifPreview) {
        AppSettings.update { s -> s.copy(notifPreview = !it) }
    }
    NavRow(AIcons.Block, "المحظورين", "${p.blocked.size} محظور") { go(Page.Blocked) }
    OutlinedButton(onClick = { wipeAsk = true }, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text("مسح كل الرسايل من الجهاز ده", color = MaterialTheme.colorScheme.error)
    }
    Hint("رقم هاتفك مخفي عن باقي المستخدمين على مستوى الداتابيز. الصور اللي بتتعدّل جوه التطبيق بتتبعت من غير بيانات الموقع (EXIF)، لكن الملفات اللي بتتبعت كـ «ملف» بتتبعت زي ما هي.")
    Hint("الحظر بيتنفّذ على جهازك: رسايل ومكالمات المحظور بتتجاهل والطرف التاني مبيعرفش.")

    if (pinDialog) {
        var newPin by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pinDialog = false },
            title = { Text("قفل التطبيق") },
            text = {
                Column {
                    Text("رقم سري 4 إلى 8 أرقام. التطبيق بيتقفل لما تفتحه أو ترجعله بعد 15 ثانية. لو نسيته مفيش استرجاع: هتضطر تمسح بيانات التطبيق من إعدادات النظام.")
                    OutlinedTextField(
                        newPin, { newPin = it.filter(Char::isDigit).take(8) }, singleLine = true,
                        label = { Text("الرقم السري الجديد") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                    )
                }
            },
            confirmButton = { TextButton(enabled = newPin.length >= 4, onClick = { AppLock.setPin(newPin); pinDialog = false }) { Text("تفعيل") } },
            dismissButton = {
                if (p.pinHash.isNotEmpty()) TextButton(onClick = { AppLock.setPin(null); pinDialog = false }) { Text("إلغاء القفل") }
                else TextButton(onClick = { pinDialog = false }) { Text("إلغاء") }
            },
        )
    }

    if (invPick) ChoiceDialog(
        "مين يضيفني في مجموعات", listOf("everyone" to "الكل", "contacts" to "اللي ليا معاهم محادثة", "nobody" to "محدش"), p.groupInvites,
        { v -> AppSettings.update { s -> s.copy(groupInvites = v) } }, { invPick = false },
    )
    if (lockPick) ChoiceDialog(
        "القفل التلقائي (محتاج PIN)", listOf("0" to "فوري", "15" to "بعد 15 ثانية", "60" to "بعد دقيقة", "300" to "بعد 5 دقايق"), p.autoLockSec.toString(),
        { v -> AppSettings.update { s -> s.copy(autoLockSec = v.toInt()) } }, { lockPick = false },
    )
    if (wipeAsk) AlertDialog(
        onDismissRequest = { wipeAsk = false },
        title = { Text("مسح كل الرسايل؟") },
        text = { Text("هتتمسح كل المحادثات والمجموعات من الجهاز ده ومش هتتسترجع. حسابك هيفضل زي ما هو.") },
        confirmButton = { TextButton(onClick = { wipeAsk = false; scope.launch { com.binarybeast.chatapp.data.ChatEngine.clearAllMessages() } }) { Text("مسح") } },
        dismissButton = { TextButton(onClick = { wipeAsk = false }) { Text("إلغاء") } },
    )
    if (pick) ChoiceDialog(
        "مين يقدر يتصل بيا", listOf("everyone" to "الكل", "nobody" to "محدش"), p.whoCanCall,
        { v -> AppSettings.update { s -> s.copy(whoCanCall = v) } }, { pick = false },
    )
}

// ============================================================ الشات

@Composable
private fun ChatsPage() {
    val p by AppSettings.prefs.collectAsState()
    var theme by remember { mutableStateOf(false) }
    var font by remember { mutableStateOf(false) }
    var accent by remember { mutableStateOf(false) }
    var wall by remember { mutableStateOf(false) }

    ValueRow("لون التطبيق", ACCENTS.firstOrNull { it.first == p.accent }?.second?.first ?: "نيون سماوي") { accent = true }
    ValueRow("خلفية الشات", when (p.wallpaper) { "aurora" -> "أورورا"; "dots" -> "نقط"; "plain" -> "سادة"; else -> "رسومات" }) { wall = true }
    ValueRow("الثيم", when (p.darkMode) { "dark" -> "داكن"; "light" -> "فاتح"; else -> "زي الجهاز" }) { theme = true }
    ValueRow("حجم الخط", when (p.fontScale) { 0.9f -> "صغير"; 1.15f -> "كبير"; 1.3f -> "كبير جدًا"; else -> "عادي" }) { font = true }
    SwitchRow("زر Enter يبعت الرسالة", "لو مقفول، Enter بينزل سطر جديد", p.enterToSend) {
        AppSettings.update { s -> s.copy(enterToSend = it) }
    }
    if (wall) ChoiceDialog(
        "خلفية الشات", listOf("doodle" to "رسومات (SVG)", "aurora" to "أورورا نيون", "dots" to "نقط", "plain" to "سادة"), p.wallpaper,
        { v -> AppSettings.update { s -> s.copy(wallpaper = v) } }, { wall = false },
    )
    if (accent) ChoiceDialog(
        "لون التطبيق", ACCENTS.map { it.first to it.second.first }, p.accent,
        { v -> AppSettings.update { s -> s.copy(accent = v) } }, { accent = false },
    )
    if (theme) ChoiceDialog(
        "الثيم", listOf("system" to "زي الجهاز", "light" to "فاتح", "dark" to "داكن"), p.darkMode,
        { v -> AppSettings.update { s -> s.copy(darkMode = v) } }, { theme = false },
    )
    if (font) ChoiceDialog(
        "حجم الخط", listOf(0.9f to "صغير", 1f to "عادي", 1.15f to "كبير", 1.3f to "كبير جدًا"), p.fontScale,
        { v -> AppSettings.update { s -> s.copy(fontScale = v) } }, { font = false },
    )
}

// ============================================================ الإشعارات

@Composable
private fun NotificationsPage() {
    val p by AppSettings.prefs.collectAsState()
    val ctx = LocalContext.current
    SwitchRow("إشعارات الرسايل", null, p.notifMessages) { AppSettings.update { s -> s.copy(notifMessages = it) } }
    SwitchRow("معاينة الرسالة في الإشعار", "لو مقفول، الإشعار بيقول «رسالة جديدة» من غير النص", p.notifPreview) {
        AppSettings.update { s -> s.copy(notifPreview = it) }
    }
    SwitchRow("إشعارات المكالمات", "لو مقفول، المكالمة بتظهر بس والتطبيق مفتوح", p.notifCalls) {
        AppSettings.update { s -> s.copy(notifCalls = it) }
    }
    SwitchRow("استقبال في الخلفية (من غير Firebase)", "خدمة صغيرة بتفضل متصلة علشان الرسايل والمكالمات توصل والتطبيق مقفول. بتظهر إشعار صامت.", p.keepAlive) {
        AppSettings.update { s -> s.copy(keepAlive = it) }
        if (it) com.binarybeast.chatapp.KeepAliveService.start(ctx) else com.binarybeast.chatapp.KeepAliveService.stop(ctx)
    }
    ValueRow("السماح بالعمل في الخلفية", "اختار «بدون قيود» علشان النظام ميقفلش الخدمة") {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    ValueRow("إعدادات إشعارات النظام", "الصوت والاهتزاز") {
        ctx.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

// ============================================================ التخزين والبيانات

@Composable
private fun StoragePage() {
    val p by AppSettings.prefs.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var quality by remember { mutableStateOf(false) }
    var confirmChats by remember { mutableStateOf(false) }
    var cleared by remember { mutableStateOf(false) }
    var limitPick by remember { mutableStateOf(false) }
    var daysPick by remember { mutableStateOf(false) }

    SwitchRow("توفير البيانات", "بيقفل التحميل التلقائي، وبيخلّي رفع الصور/الفيديو بأقل جودة، والمكالمات بجودة منخفضة", p.dataSaver) {
        AppSettings.update { s ->
            if (it) s.copy(dataSaver = true, autoDownloadImages = false, uploadQuality = "data", lowDataCalls = true)
            else s.copy(dataSaver = false, autoDownloadImages = true, uploadQuality = "standard", lowDataCalls = false)
        }
    }
    SwitchRow("تحميل الصور تلقائيًا", "لو مقفول، الصورة بتتحمّل لما تضغط عليها", p.autoDownloadImages) {
        AppSettings.update { s -> s.copy(autoDownloadImages = it) }
    }
    ValueRow("جودة الصور والفيديو المرفوعة", when (p.uploadQuality) { "data" -> "توفير بيانات"; "high" -> "عالية"; else -> "عادية" }) { quality = true }
    Hint("الميديا مبتتحفظش في المعرض ولا الملفات: بتتحمّل كاش مؤقت جوه التطبيق (زي تيليجرام) وبتتمسح تلقائي، وأصلها على السحابة.")
    ValueRow("أقصى حجم للكاش", if (p.cacheLimitMb < 0) "من غير حد" else if (p.cacheLimitMb >= 1024) "${p.cacheLimitMb / 1024} جيجا" else "${p.cacheLimitMb} ميجا") { limitPick = true }
    ValueRow("مسح الميديا المحمّلة تلقائيًا", if (p.cacheDays == 0) "أبدًا" else "بعد ${p.cacheDays} يوم") { daysPick = true }
    ValueRow("مسح الكاش الآن", if (cleared) "اتمسح ✓" else "المستخدم حاليًا: ${"%.1f".format(com.binarybeast.chatapp.data.TelegramStorage.cacheSize() / 1048576.0)} ميجا") {
        scope.launch {
            coil.Coil.imageLoader(ctx).apply { diskCache?.clear(); memoryCache?.clear() }
            com.binarybeast.chatapp.data.TelegramStorage.clearCache()
            cleared = true
        }
    }
    if (limitPick) ChoiceDialog("أقصى حجم للكاش", listOf(100 to "100 ميجا", 500 to "500 ميجا", 2048 to "2 جيجا", -1 to "من غير حد"), p.cacheLimitMb,
        { v -> AppSettings.update { s -> s.copy(cacheLimitMb = v) }; com.binarybeast.chatapp.data.TelegramStorage.trim() }, { limitPick = false })
    if (daysPick) ChoiceDialog("مسح الميديا بعد", listOf(1 to "يوم", 7 to "7 أيام", 30 to "30 يوم", 0 to "أبدًا"), p.cacheDays,
        { v -> AppSettings.update { s -> s.copy(cacheDays = v) }; com.binarybeast.chatapp.data.TelegramStorage.trim() }, { daysPick = false })
    ValueRow("مسح كل الرسايل من الجهاز", "مش بترجع") { confirmChats = true }

    if (quality) ChoiceDialog(
        "جودة الرفع", listOf("data" to "توفير بيانات", "standard" to "عادية", "high" to "عالية"), p.uploadQuality,
        { v -> AppSettings.update { s -> s.copy(uploadQuality = v) } }, { quality = false },
    )
    if (confirmChats) AlertDialog(
        onDismissRequest = { confirmChats = false },
        title = { Text("مسح كل الرسايل؟") },
        text = { Text("الرسايل محفوظة على الجهاز ده بس، ومفيش نسخة تانية. مش هتقدر ترجّعها.") },
        confirmButton = { TextButton(onClick = { confirmChats = false; scope.launch { ChatEngine.dao.clear() } }) { Text("مسح") } },
        dismissButton = { TextButton(onClick = { confirmChats = false }) { Text("إلغاء") } },
    )
}

// ============================================================ المكالمات

@Composable
private fun CallsPage() {
    val p by AppSettings.prefs.collectAsState()
    SwitchRow("توفير البيانات في المكالمات", "فيديو بدقة 480p و15 إطار بدل 720p و30", p.lowDataCalls) {
        AppSettings.update { s -> s.copy(lowDataCalls = it) }
    }
    SwitchRow("إلغاء الضوضاء", "بيتطبّق على المكالمات الجاية", p.noiseSuppression) {
        AppSettings.update { s -> s.copy(noiseSuppression = it) }
    }
}

// ============================================================ المتصفح

@Composable
private fun BrowserPage() {
    val p by AppSettings.prefs.collectAsState()
    val ctx = LocalContext.current
    var cleared by remember { mutableStateOf(false) }
    SwitchRow("فتح الروابط داخل التطبيق", "لو مقفول، الروابط بتفتح في متصفح الجهاز", p.browserInApp) {
        AppSettings.update { s -> s.copy(browserInApp = it) }
    }
    SwitchRow("وضع الكمبيوتر كافتراضي", "بيطلب نسخة سطح المكتب من المواقع", p.browserDesktop) {
        AppSettings.update { s -> s.copy(browserDesktop = it) }
    }
    ValueRow("مسح بيانات التصفح", if (cleared) "اتمسحت ✓" else "الكوكيز والكاش والتخزين") {
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        runCatching { WebView(ctx).apply { clearCache(true); clearHistory(); destroy() } }
        cleared = true
    }
}

// ============================================================ المحظورين

@Composable
private fun BlockedPage() {
    val p by AppSettings.prefs.collectAsState()
    var profiles by remember { mutableStateOf<List<Profile>>(emptyList()) }
    LaunchedEffect(p.blocked) { profiles = runCatching { ProfileRepository.byIds(p.blocked) }.getOrDefault(emptyList()) }

    if (p.blocked.isEmpty()) Hint("مفيش حد محظور. تقدر تحظر حد من القايمة (⋮) جوه المحادثة.")
    profiles.forEach { u ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(u.avatarFileId, u.displayName.ifBlank { u.phone }, 44.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(u.displayName.ifBlank { u.phone }, fontWeight = FontWeight.SemiBold)
                Text(u.username?.let { "@$it" } ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { AppSettings.unblock(u.id) }) { Text("إلغاء الحظر") }
        }
    }
}

// ============================================================ عن التطبيق

@Composable
private fun AboutPage() {
    ValueRow("الإصدار", "1.0")
    Hint(
        "• الرسايل مشفّرة بين الطرفين (ECDH + AES-GCM) وبتتحفظ على جهازك بس.\n" +
            "• الشات والمكالمات P2P؛ لو الطرف التاني مقفول بتستنى مشفّرة في صندوق مؤقت وتتمسح من السيرفر أول ما توصل.\n" +
            "• التشفير ده مش Signal Protocol: مفيش forward secrecy ولا تحقق من هوية المفاتيح.\n" +
            "• الصور والستوريز مخزّنة على تيليجرام وبتتحمّل عن طريق السيرفر بتاعك."
    )
}

// ============================================================ مكوّنات مشتركة

@Composable
private fun NavRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun ValueRow(title: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f))
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, current: T, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(value); onDismiss() },
                        verticalAlignment = Alignment.CenterVertically,
                    ) { RadioButton(current == value, { onPick(value); onDismiss() }); Text(label) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } },
    )
}
