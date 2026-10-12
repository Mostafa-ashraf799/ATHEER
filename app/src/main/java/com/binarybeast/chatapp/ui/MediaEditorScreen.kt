@file:OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)

package com.binarybeast.chatapp.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.binarybeast.chatapp.data.AppSettings
import com.binarybeast.chatapp.data.TelegramStorage
import com.binarybeast.chatapp.media.ImagePipeline
import com.binarybeast.chatapp.media.VideoPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** ناتج المحرر: ملف جاهز للرفع + تعليق اختياري. */
class EditedMedia(
    val bytes: ByteArray,
    val name: String,
    val mime: String,
    val kind: String,          // image | video
    val caption: String?,
)

/**
 * محرر الميديا (للشات والستوريز). بيتفتح بعد اختيار صورة/فيديو من المعرض أو الكاميرا.
 * @param maxVideoMs أقصى طول للفيديو (الستوريز 30 ثانية).
 */
@Composable
fun MediaEditorScreen(
    uri: Uri,
    isVideo: Boolean,
    maxVideoMs: Long = Long.MAX_VALUE,
    onDone: (EditedMedia) -> Unit,
    onCancel: () -> Unit,
) {
    BackHandler(onBack = onCancel)
    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Box(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            if (isVideo) VideoEditor(uri, maxVideoMs, onDone, onCancel) else ImageEditor(uri, onDone, onCancel)
        }
    }
}

private val PALETTE = listOf(
    Color.White, Color.Black, Color(0xFFE53935), Color(0xFFFFD600),
    Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFFEC407A),
)
private val EMOJIS = listOf("😀", "😂", "😍", "😎", "🔥", "❤️", "👍", "🎉", "🥳", "😢", "⭐", "💯")

private enum class EditTab(val label: String) { Crop("قص"), Filter("فلاتر"), Adjust("ضبط"), Draw("رسم"), Text("نص") }

@Composable
private fun ColorDots(selected: Color, onPick: (Color) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(PALETTE) { c ->
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(c)
                    .border(if (c == selected) 3.dp else 1.dp, if (c == selected) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                    .clickable { onPick(c) }
            )
        }
    }
}

// =============================================================== محرر الصور

@Composable
private fun ImageEditor(uri: Uri, onDone: (EditedMedia) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val prefs by AppSettings.prefs.collectAsState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var source by remember { mutableStateOf<Bitmap?>(null) }
    var loadError by remember { mutableStateOf(false) }
    LaunchedEffect(uri) {
        val maxDim = prefs.imageMaxDim
        runCatching { withContext(Dispatchers.IO) { ImagePipeline.load(ctx, uri, maxDim) } }
            .onSuccess { source = it }.onFailure { loadError = true }
    }

    var tab by remember { mutableStateOf(EditTab.Crop) }
    var turns by remember { mutableIntStateOf(0) }
    var flip by remember { mutableStateOf(false) }
    var aspect by remember { mutableStateOf<Float?>(null) }
    var look by remember { mutableStateOf(ImagePipeline.Look()) }
    val strokes = remember { mutableStateListOf<ImagePipeline.Stroke>() }
    val current = remember { mutableStateListOf<Pair<Float, Float>>() }
    val overlays = remember { mutableStateListOf<ImagePipeline.Overlay>() }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var drawColor by remember { mutableStateOf(Color(0xFFE53935)) }
    var drawWidth by remember { mutableFloatStateOf(0.012f) }
    var textColor by remember { mutableStateOf(Color.White) }
    var caption by remember { mutableStateOf("") }
    var addText by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    // الرسم والنصوص نسبية للصورة المقصوصة، فأي تغيير في القص/الدوران بيمسحها
    fun geometryChanged() { strokes.clear(); current.clear(); overlays.clear(); selectedId = null }

    val base = remember(source, turns, flip, aspect) { source?.let { ImagePipeline.applyGeometry(it, turns, flip, aspect) } }

    if (loadError) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("تعذّر فتح الصورة", color = Color.White)
                TextButton(onClick = onCancel) { Text("رجوع") }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        // ------- الشريط العلوي
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel) { Icon(AIcons.Close, "إلغاء", tint = Color.White) }
            Box(Modifier.weight(1f))
            Button(
                enabled = base != null && !exporting,
                onClick = {
                    val b = base ?: return@Button
                    exporting = true
                    scope.launch {
                        val bytes = withContext(Dispatchers.Default) {
                            var q = prefs.jpegQuality
                            var out = ImagePipeline.render(b, look, strokes.toList(), overlays.toList(), q)
                            while (out.size > TelegramStorage.MAX_BYTES && q > 40) {
                                q -= 15; out = ImagePipeline.render(b, look, strokes.toList(), overlays.toList(), q)
                            }
                            out
                        }
                        onDone(EditedMedia(bytes, "img_${System.currentTimeMillis()}.jpg", "image/jpeg", "image", caption.trim().ifBlank { null }))
                    }
                },
            ) { if (exporting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("تم") }
        }

        // ------- المعاينة
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val b = base
            if (b == null) CircularProgressIndicator(color = Color.White) else {
                val img = remember(b) { b.asImageBitmap() }
                val cm = remember(look) { androidx.compose.ui.graphics.ColorMatrix(ImagePipeline.colorMatrix(look).array) }
                Box(Modifier.aspectRatio(b.width.toFloat() / b.height).onSizeChanged { boxSize = it }) {
                    androidx.compose.foundation.Image(
                        img, null, Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds, colorFilter = ColorFilter.colorMatrix(cm),
                    )
                    // الرسم
                    Canvas(
                        Modifier.fillMaxSize().pointerInput(tab, drawColor, drawWidth) {
                            if (tab != EditTab.Draw) return@pointerInput
                            val w = size.width.toFloat(); val h = size.height.toFloat()
                            detectDragGestures(
                                onDragStart = { o -> current.clear(); current.add(o.x / w to o.y / h) },
                                onDrag = { change, _ -> current.add(change.position.x / w to change.position.y / h); change.consume() },
                                onDragEnd = {
                                    if (current.size > 1) strokes.add(ImagePipeline.Stroke(drawColor.toArgb(), drawWidth, current.toList()))
                                    current.clear()
                                },
                                onDragCancel = { current.clear() },
                            )
                        }
                    ) {
                        fun draw(pts: List<Pair<Float, Float>>, color: Int, width: Float) {
                            if (pts.size < 2) return
                            val p = Path().apply {
                                moveTo(pts[0].first * size.width, pts[0].second * size.height)
                                for (i in 1 until pts.size) lineTo(pts[i].first * size.width, pts[i].second * size.height)
                            }
                            drawPath(p, Color(color), style = Stroke(width * size.width, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                        strokes.forEach { draw(it.pts, it.color, it.width) }
                        draw(current.toList(), drawColor.toArgb(), drawWidth)
                    }
                    // النصوص والإيموجي
                    overlays.forEach { o ->
                        val selected = o.id == selectedId
                        Text(
                            o.text,
                            color = Color(o.color),
                            fontSize = with(density) { (o.size * boxSize.width).toSp() },
                            style = TextStyle(shadow = Shadow(Color(0xAA000000), blurRadius = 6f)),
                            modifier = Modifier
                                .offset { IntOffset((o.x * boxSize.width).roundToInt(), (o.y * boxSize.height).roundToInt()) }
                                .then(if (selected) Modifier.border(1.dp, Color.White) else Modifier)
                                .pointerInput(o.id, tab) {
                                    detectDragGestures(onDragStart = { selectedId = o.id }) { change, drag ->
                                        change.consume()
                                        val i = overlays.indexOfFirst { it.id == o.id }
                                        if (i >= 0 && boxSize.width > 0) {
                                            val cur = overlays[i]
                                            overlays[i] = cur.copy(
                                                x = (cur.x + drag.x / boxSize.width).coerceIn(-0.1f, 0.95f),
                                                y = (cur.y + drag.y / boxSize.height).coerceIn(-0.05f, 0.95f),
                                            )
                                        }
                                    }
                                },
                        )
                    }
                }
            }
        }

        // ------- لوحة الأداة
        Column(Modifier.fillMaxWidth().background(Color(0xFF111111)).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.CenterStart) {
                when (tab) {
                    EditTab.Crop -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(listOf("أصلي" to null, "1:1" to 1f, "4:3" to 4f / 3, "3:4" to 3f / 4, "16:9" to 16f / 9, "9:16" to 9f / 16)) { (label, a) ->
                                FilterChip(selected = aspect == a, onClick = { aspect = a; geometryChanged() }, label = { Text(label) })
                            }
                        }
                        Row {
                            IconButton(onClick = { turns -= 1; geometryChanged() }) { Icon(AIcons.RotateLeft, "يسار", tint = Color.White) }
                            IconButton(onClick = { turns += 1; geometryChanged() }) { Icon(AIcons.RotateRight, "يمين", tint = Color.White) }
                            IconButton(onClick = { flip = !flip; geometryChanged() }) { Icon(AIcons.Flip, "قلب", tint = Color.White) }
                        }
                    }
                    EditTab.Filter -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(ImagePipeline.FILTERS) { (key, label) ->
                            FilterChip(selected = look.filter == key, onClick = { look = look.copy(filter = key) }, label = { Text(label) })
                        }
                    }
                    EditTab.Adjust -> Column {
                        LabeledSlider("السطوع", look.brightness, -0.5f..0.5f) { look = look.copy(brightness = it) }
                        LabeledSlider("التباين", look.contrast, 0.5f..1.5f) { look = look.copy(contrast = it) }
                        LabeledSlider("التشبّع", look.saturation, 0f..2f) { look = look.copy(saturation = it) }
                    }
                    EditTab.Draw -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ColorDots(drawColor) { drawColor = it }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            listOf(0.006f to "رفيع", 0.012f to "وسط", 0.024f to "عريض").forEach { (w, label) ->
                                FilterChip(selected = drawWidth == w, onClick = { drawWidth = w }, label = { Text(label) },
                                    modifier = Modifier.padding(end = 8.dp))
                            }
                            IconButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }, enabled = strokes.isNotEmpty()) {
                                Icon(AIcons.Undo, "تراجع", tint = if (strokes.isNotEmpty()) Color.White else Color.Gray)
                            }
                        }
                    }
                    EditTab.Text -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { addText = true }) { Text("＋ نص") }
                            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(EMOJIS) { e ->
                                    Text(e, Modifier.clickable {
                                        val o = ImagePipeline.Overlay(System.nanoTime(), e, 0.38f, 0.38f, 0.2f, Color.White.toArgb())
                                        overlays.add(o); selectedId = o.id
                                    }.padding(4.dp), style = MaterialTheme.typography.titleLarge)
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { ColorDots(textColor) { c -> textColor = c
                                val i = overlays.indexOfFirst { it.id == selectedId }
                                if (i >= 0) overlays[i] = overlays[i].copy(color = c.toArgb())
                            } }
                            val sel = overlays.firstOrNull { it.id == selectedId }
                            if (sel != null) {
                                TextButton(onClick = { val i = overlays.indexOfFirst { it.id == sel.id }; overlays[i] = sel.copy(size = (sel.size * 0.85f).coerceAtLeast(0.03f)) }) { Text("A−") }
                                TextButton(onClick = { val i = overlays.indexOfFirst { it.id == sel.id }; overlays[i] = sel.copy(size = (sel.size * 1.2f).coerceAtMost(0.5f)) }) { Text("A＋") }
                                IconButton(onClick = { overlays.removeAll { it.id == sel.id }; selectedId = null }) { Icon(AIcons.Delete, "حذف", tint = Color.White) }
                            }
                        }
                    }
                }
            }
            OutlinedTextField(
                value = caption, onValueChange = { caption = it.take(500) },
                placeholder = { Text("أضف تعليق…") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(
                    EditTab.Crop to AIcons.Crop, EditTab.Filter to AIcons.AutoFixHigh, EditTab.Adjust to AIcons.Tune,
                    EditTab.Draw to AIcons.Brush, EditTab.Text to AIcons.TextFields,
                ).forEach { (t, icon) ->
                    Column(Modifier.clickable { tab = t; if (t != EditTab.Text) selectedId = null }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(icon, t.label, tint = if (tab == t) MaterialTheme.colorScheme.primary else Color.White)
                        Text(t.label, color = if (tab == t) MaterialTheme.colorScheme.primary else Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }

    if (addText) {
        var t by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addText = false },
            title = { Text("اكتب النص") },
            text = { OutlinedTextField(t, { t = it.take(60) }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = t.isNotBlank(), onClick = {
                    val o = ImagePipeline.Overlay(System.nanoTime(), t.trim(), 0.2f, 0.4f, 0.07f, textColor.toArgb())
                    overlays.add(o); selectedId = o.id; addText = false
                }) { Text("إضافة") }
            },
            dismissButton = { TextButton(onClick = { addText = false }) { Text("إلغاء") } },
        )
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(30.dp)) {
        Text(label, color = Color.White, modifier = Modifier.width(64.dp), style = MaterialTheme.typography.labelMedium)
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
    }
}

// =============================================================== محرر الفيديو

@Composable
private fun VideoEditor(uri: Uri, maxMs: Long, onDone: (EditedMedia) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val prefs by AppSettings.prefs.collectAsState()
    val scope = rememberCoroutineScope()

    var info by remember { mutableStateOf<VideoPipeline.Info?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(uri) {
        runCatching { withContext(Dispatchers.IO) { VideoPipeline.info(ctx, uri) } }
            .onSuccess { if (it.durationMs <= 0) failed = true else info = it }.onFailure { failed = true }
    }

    var startMs by remember { mutableLongStateOf(0L) }
    var endMs by remember { mutableLongStateOf(0L) }
    var applied by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var mute by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("none") }
    var burned by remember { mutableStateOf("") }
    var caption by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    var exporting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(info) {
        val i = info ?: return@LaunchedEffect
        startMs = 0; endMs = minOf(i.durationMs, maxMs); applied = startMs to endMs
    }

    val player = remember { ExoPlayer.Builder(ctx).build() }
    DisposableEffect(Unit) { onDispose { player.release() } }
    LaunchedEffect(applied) {
        val a = applied ?: return@LaunchedEffect
        player.setMediaItem(
            MediaItem.Builder().setUri(uri).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(a.first).setEndPositionMs(a.second).build()
            ).build()
        )
        player.prepare(); player.playWhenReady = true
    }
    LaunchedEffect(mute) { player.volume = if (mute) 0f else 1f }
    LaunchedEffect(filter) { runCatching { player.setVideoEffects(VideoPipeline.filterEffects(filter)) } }

    if (failed) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("تعذّر فتح الفيديو", color = Color.White)
                TextButton(onClick = onCancel) { Text("رجوع") }
            }
        }
        return
    }
    val i = info
    if (i == null) { Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = Color.White) }; return }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel, enabled = !exporting) { Icon(AIcons.Close, "إلغاء", tint = Color.White) }
            Box(Modifier.weight(1f))
            Button(
                enabled = !exporting && endMs - startMs >= 500,
                onClick = {
                    exporting = true; error = null; progress = 0f
                    scope.launch {
                        runCatching {
                            val f = VideoPipeline.export(
                                ctx, uri, startMs, endMs, mute, filter, burned.trim().ifBlank { null },
                                prefs.videoHeight, TelegramStorage.MAX_BYTES * 9L / 10, { progress = it },
                            )
                            val bytes = withContext(Dispatchers.IO) { f.readBytes().also { f.delete() } }
                            onDone(EditedMedia(bytes, "vid_${System.currentTimeMillis()}.mp4", "video/mp4", "video", caption.trim().ifBlank { null }))
                        }.onFailure { error = it.message ?: "فشل تصدير الفيديو" }
                        exporting = false
                    }
                },
            ) { Text(if (exporting) "${(progress * 100).roundToInt()}%" else "تم") }
        }

        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { c -> PlayerView(c).apply { this.player = player; useController = true } },
        )

        Column(Modifier.fillMaxWidth().background(Color(0xFF111111)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (exporting) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            Text(
                "المقطع: ${fmt(startMs)} → ${fmt(endMs)}  (${fmt(endMs - startMs)})" +
                    if (maxMs != Long.MAX_VALUE) "  — الحد الأقصى ${fmt(maxMs)}" else "",
                color = Color.White, style = MaterialTheme.typography.labelMedium,
            )
            RangeSlider(
                value = startMs.toFloat()..endMs.toFloat(),
                valueRange = 0f..i.durationMs.toFloat(),
                enabled = !exporting,
                onValueChange = { r ->
                    var s = r.start.toLong(); var e = r.endInclusive.toLong()
                    if (e - s > maxMs) { if (s != startMs) e = s + maxMs else s = e - maxMs }
                    startMs = s.coerceAtLeast(0); endMs = e.coerceAtMost(i.durationMs)
                },
                onValueChangeFinished = { applied = startMs to endMs },
            )

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                item {
                    FilterChip(
                        selected = mute, onClick = { mute = !mute },
                        label = { Text(if (mute) "مكتوم" else "الصوت") },
                        leadingIcon = { Icon(if (mute) AIcons.VolumeOff else AIcons.VolumeUp, null, Modifier.size(18.dp)) },
                    )
                }
                items(VideoPipeline.FILTERS) { (key, label) ->
                    FilterChip(selected = filter == key, onClick = { filter = key }, label = { Text(label) })
                }
            }
            OutlinedTextField(
                burned, { burned = it.take(60) }, singleLine = true, enabled = !exporting,
                placeholder = { Text("نص على الفيديو (اختياري)") }, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                caption, { caption = it.take(500) }, singleLine = true, enabled = !exporting,
                placeholder = { Text("أضف تعليق…") }, modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "الناتج: ${minOf(prefs.videoHeight, i.height.takeIf { it > 0 } ?: prefs.videoHeight)}p، حد أقصى 18 ميجا (من إعدادات الجودة).",
                color = Color.Gray, style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
