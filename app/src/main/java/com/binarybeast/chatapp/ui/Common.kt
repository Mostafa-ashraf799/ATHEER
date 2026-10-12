package com.binarybeast.chatapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.withLink
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.binarybeast.chatapp.data.TelegramStorage

/** صورة محمّلة من تيليجرام (عن طريق الـ proxy مع JWT). */
@Composable
fun TgImage(fileId: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    if (fileId == null) return
    val ctx = LocalContext.current
    val req = ImageRequest.Builder(ctx)
        .data(TelegramStorage.url(fileId))
        .apply { TelegramStorage.headers().forEach { (k, v) -> addHeader(k, v) } }
        .memoryCacheKey(fileId).diskCacheKey(fileId)
        .crossfade(true)
        .build()
    AsyncImage(model = req, contentDescription = null, modifier = modifier, contentScale = contentScale)
}

@Composable
fun Avatar(fileId: String?, name: String, size: Dp = 48.dp, ring: Color? = null) {
    val outer = if (ring != null) Modifier.border(2.5.dp, ring, CircleShape).padding(3.dp) else Modifier
    val inner = if (ring != null) size - 11.dp else size
    Box(
        outer.size(inner).clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
        contentAlignment = Alignment.Center,
    ) {
        if (fileId != null) TgImage(fileId, Modifier.size(inner))
        else Text(
            name.trim().firstOrNull()?.uppercase() ?: "؟",
            fontSize = (size.value * 0.4f).sp,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private val URL_REGEX = Regex("""(?i)\b((https?://|www\.)[^\s<>"']+)""")

/** نص بروابط قابلة للضغط (http/https بس) بتتفتح في المتصفح المدمج. */
@Composable
fun LinkifiedText(text: String, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = androidx.compose.material3.LocalTextStyle.current) {
    val open = LocalOpenLink.current
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, linkColor) {
        androidx.compose.ui.text.buildAnnotatedString {
            var last = 0
            for (m in URL_REGEX.findAll(text)) {
                val raw = m.value.trimEnd('.', ',', ')', '!', '?', ';', ':', '،', '؟')
                append(text.substring(last, m.range.first))
                val url = if (raw.startsWith("http", true)) raw else "https://$raw"
                withLink(
                    androidx.compose.ui.text.LinkAnnotation.Url(
                        url,
                        androidx.compose.ui.text.TextLinkStyles(
                            androidx.compose.ui.text.SpanStyle(color = linkColor, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)
                        ),
                        androidx.compose.ui.text.LinkInteractionListener { open(url) },
                    )
                ) { append(raw) }
                last = m.range.first + raw.length
            }
            append(text.substring(last))
        }
    }
    Text(annotated, modifier, style = style)
}

/** يعرض "10:45" من millis. */
fun hhmmMs(ms: Long): String {
    val t = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())
    return "%02d:%02d".format(t.hour, t.minute)
}

/** يعرض "10:45" من ISO timestamp. */
fun hhmm(iso: String): String = runCatching {
    val t = java.time.OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault())
    "%02d:%02d".format(t.hour, t.minute)
}.getOrDefault("")
