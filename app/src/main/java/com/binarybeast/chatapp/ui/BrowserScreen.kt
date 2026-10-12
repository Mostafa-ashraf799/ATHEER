package com.binarybeast.chatapp.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.binarybeast.chatapp.data.AppSettings

private const val DESKTOP_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

/** يحوّل اللي المستخدم كتبه لرابط: بحث لو كلام عادي، أو https:// لو دومين. */
fun normalizeUrl(input: String): String {
    val t = input.trim()
    if (t.startsWith("http://", true) || t.startsWith("https://", true)) return t
    val looksLikeHost = !t.contains(' ') && t.contains('.')
    return if (looksLikeHost) "https://$t" else "https://duckduckgo.com/?q=" + Uri.encode(t)
}

/** الروابط بتتفتح هنا بس لو http/https. أي scheme تاني (tel, mailto...) بيتسلّم للنظام، و intent: بيتمنع. */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(initialUrl: String, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs by AppSettings.prefs.collectAsState()
    var url by remember { mutableStateOf(initialUrl) }
    var title by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }
    var desktop by remember { mutableStateOf(prefs.browserDesktop) }
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var addr by remember { mutableStateOf(initialUrl) }
    val web = remember { arrayOfNulls<WebView>(1) }
    val mobileUa = remember { WebSettings.getDefaultUserAgent(ctx) }

    BackHandler {
        val w = web[0]
        if (w != null && w.canGoBack()) w.goBack() else onClose()
    }
    DisposableEffect(Unit) { onDispose { web[0]?.apply { stopLoading(); destroy() }; web[0] = null } }

    val host = remember(url) { runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("") }
    val secure = url.startsWith("https://", true)

    Scaffold(
        topBar = {
            GlassTopBar(
                navigationIcon = { IconButton(onClick = onClose) { Icon(AIcons.Close, "إغلاق") } },
                title = {
                    if (editing) {
                        OutlinedTextField(
                            value = addr, onValueChange = { addr = it }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { editing = false; web[0]?.loadUrl(normalizeUrl(addr)) }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else Column(Modifier.clickable { addr = url; editing = true }) {
                        Text(title.ifBlank { host }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (secure) AIcons.Lock else AIcons.LockOpen, null, Modifier.size(12.dp),
                                tint = if (secure) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                            Text(" $host", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(AIcons.MoreVert, "المزيد") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("تحديث") }, onClick = { menu = false; web[0]?.reload() })
                        DropdownMenuItem(
                            text = { Text(if (desktop) "نسخة الموبايل" else "نسخة الكمبيوتر") },
                            onClick = {
                                menu = false; desktop = !desktop
                                web[0]?.apply { settings.userAgentString = if (desktop) DESKTOP_UA else mobileUa; reload() }
                            },
                        )
                        DropdownMenuItem(text = { Text("نسخ الرابط") }, onClick = {
                            menu = false
                            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("url", url))
                        })
                        DropdownMenuItem(text = { Text("مشاركة") }, onClick = {
                            menu = false
                            ctx.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), null
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        })
                        DropdownMenuItem(text = { Text("فتح في المتصفح") }, onClick = {
                            menu = false
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        })
                    }
                },
            )
        },
        bottomBar = {
            BottomAppBar {
                IconButton(onClick = { web[0]?.goBack() }, enabled = canBack) { Icon(AIcons.ArrowBack, "رجوع") }
                IconButton(onClick = { web[0]?.goForward() }, enabled = canForward) { Icon(AIcons.ArrowForward, "قدّام") }
                IconButton(onClick = { web[0]?.reload() }) { Icon(AIcons.Refresh, "تحديث") }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    WebView(c).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // تقليل سطح الهجوم: مفيش وصول لملفات الجهاز ولا JS bridge ولا نوافذ منبثقة
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.setSupportMultipleWindows(false)
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        if (Build.VERSION.SDK_INT >= 26) settings.safeBrowsingEnabled = true
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        if (desktop) settings.userAgentString = DESKTOP_UA

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val u = request.url
                                val scheme = u.scheme?.lowercase()
                                if (scheme == "http" || scheme == "https") return false
                                // tel / mailto / sms / geo: نسلّمها للنظام. أي حاجة تانية (intent: وغيره) بتتمنع
                                if (scheme in setOf("tel", "mailto", "sms", "geo")) {
                                    runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                }
                                return true
                            }
                            override fun onPageStarted(view: WebView, u: String, favicon: Bitmap?) { url = u; canBack = view.canGoBack(); canForward = view.canGoForward() }
                            override fun onPageFinished(view: WebView, u: String) { url = u; canBack = view.canGoBack(); canForward = view.canGoForward() }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, p: Int) { progress = p }
                            override fun onReceivedTitle(view: WebView, t: String?) { title = t.orEmpty() }
                        }
                        // التحميلات بتتسلّم للمتصفح الخارجي بدل ما التطبيق ينزّل ملفات
                        setDownloadListener(DownloadListener { dl, _, _, _, _ ->
                            runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        })
                        web[0] = this
                        loadUrl(initialUrl)
                    }
                },
            )
        }
    }
}
