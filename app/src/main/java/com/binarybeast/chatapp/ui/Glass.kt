package com.binarybeast.chatapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * الهوية البصرية — "Liquid Neon":
 *  داكن: أسود + كحلي + نيون (سماوي/ليموني/وردي)   فاتح: عاجي + الألوان المقابلة (قرمزي/بنفسجي/زمردي)
 *  أزرار دايرية، شرائط عائمة (جزر)، وزجاج سائل (Liquid Glass) على طريقة iOS 26.
 * ملحوظة: Compose مفيهوش blur للخلفية من غير مكتبة؛ فالزجاج هنا محاكاة (تدرّج شفاف + لمعة + حافة مضيئة + ظل ملوّن).
 */

/** خلفية التطبيق: أسود بتوهجات نيون (داكن) / عاجي بتوهجات ناعمة (فاتح). */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier.fillMaxSize()) {
    val dark = LocalDark.current
    val a = LocalAccent.current
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (dark) {
            drawRect(Brand.Black)
            drawRect(Brush.radialGradient(listOf(a.copy(alpha = 0.20f), Color.Transparent), Offset(w * 0.05f, h * 0.02f), w * 0.95f))
            drawRect(Brush.radialGradient(listOf(Brand.NeonMagenta.copy(alpha = 0.12f), Color.Transparent), Offset(w * 1.0f, h * 0.55f), w * 0.9f))
            drawRect(Brush.radialGradient(listOf(Brand.NeonLime.copy(alpha = 0.08f), Color.Transparent), Offset(w * 0.1f, h * 1.0f), w * 0.9f))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Brand.Navy.copy(alpha = 0.55f))))
        } else {
            drawRect(Brand.Ivory)
            drawRect(Brush.radialGradient(listOf(a.copy(alpha = 0.16f), Color.Transparent), Offset(w * 0.05f, h * 0.02f), w * 0.9f))
            drawRect(Brush.radialGradient(listOf(Brand.Violet.copy(alpha = 0.10f), Color.Transparent), Offset(w * 1.0f, h * 0.55f), w * 0.9f))
            drawRect(Brush.radialGradient(listOf(Brand.Emerald.copy(alpha = 0.10f), Color.Transparent), Offset(w * 0.1f, h * 1.0f), w * 0.9f))
        }
    }
}

/** زجاج سائل: ظل ملوّن + تدرّج شفاف + لمعة علوية + حافة مضيئة. tint = لون الزجاج (للأزرار المميزة). */
@Composable
fun Modifier.glass(shape: Shape = CircleShape, tint: Color? = null): Modifier {
    val dark = LocalDark.current
    val glow = tint ?: LocalAccent.current
    val top: Color
    val bottom: Color
    if (tint != null) {
        top = tint.copy(alpha = 0.95f); bottom = tint.copy(alpha = 0.62f)
    } else if (dark) {
        top = Color.White.copy(alpha = 0.13f); bottom = Color.White.copy(alpha = 0.04f)
    } else {
        top = Color.White.copy(alpha = 0.78f); bottom = Color.White.copy(alpha = 0.42f)
    }
    val rimTop = if (dark || tint != null) Color.White.copy(alpha = 0.55f) else Color.White
    val rimBottom = if (dark) Color.White.copy(alpha = 0.06f) else Color(0x1F000000)
    return this
        .shadow(if (dark) 12.dp else 8.dp, shape, clip = false, ambientColor = glow.copy(alpha = 0.5f), spotColor = glow.copy(alpha = 0.5f))
        .clip(shape)
        .drawBehind {
            val o = shape.createOutline(size, layoutDirection, this)
            drawOutline(o, Brush.verticalGradient(listOf(top, bottom)))
            drawOutline(o, Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.16f else 0.35f), Color.Transparent), 0f, size.height * 0.5f))
            drawOutline(o, Brush.verticalGradient(listOf(rimTop, rimBottom)), style = Stroke(1.2.dp.toPx()))
        }
}

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    tint: Color? = null,
    content: @Composable () -> Unit,
) {
    Box(modifier.glass(shape, tint)) { content() }
}

/** زر دايري زجاجي (accent = زجاج ملوّن بلون التطبيق، زي زر الإرسال). */
@Composable
fun GlassCircleButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    accent: Boolean = false,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val tint = if (accent) LocalAccent.current else null
    val fg = if (accent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier.size(size).glass(CircleShape, tint)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(size * 0.5f), tint = if (enabled) fg else fg.copy(alpha = 0.4f))
    }
}

/** الشريط العلوي: جزيرة عائمة بدل TopAppBar. */
@Composable
fun GlassTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    Box(modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).glass(RoundedCornerShape(28.dp)).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            navigationIcon()
            Box(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                androidx.compose.material3.ProvideTextStyle(
                    MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                ) { title() }
            }
            actions()
        }
    }
}

/** الشريط السفلي العائم: كبسولة زجاجية، والعنصر المختار دايرة ملوّنة. */
@Composable
fun FloatingNavBar(
    items: List<Pair<ImageVector, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().glass(RoundedCornerShape(36.dp)).padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { i, (icon, label) ->
                val sel = i == selected
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).clickable(role = Role.Tab) { onSelect(i) }.padding(vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(40.dp).then(if (sel) Modifier.glass(CircleShape, LocalAccent.current) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            icon, label, Modifier.size(22.dp),
                            tint = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        label, fontSize = 10.sp, maxLines = 1,
                        color = if (sel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

private val DOODLES get() = listOf(
    AIcons.Chat, AIcons.Heart, AIcons.Star, AIcons.Smile, AIcons.Music, AIcons.Moon,
    AIcons.Sun, AIcons.Cloud, AIcons.Bolt, AIcons.Gift, AIcons.Coffee, AIcons.Send,
    AIcons.CameraAlt, AIcons.Call, AIcons.Mic, AIcons.Public,
)

/** خلفية الشات: doodle (أيقونات SVG متكررة زي واتساب) / aurora / dots / plain. */
@Composable
fun ChatWallpaper(style: String, modifier: Modifier = Modifier.fillMaxSize()) {
    val dark = LocalDark.current
    val a = LocalAccent.current
    when (style) {
        "plain" -> Box(modifier.background(if (dark) Brand.Black else Brand.Ivory))
        "aurora" -> AuroraBackground(modifier)
        "dots" -> Canvas(modifier.background(if (dark) Brand.Black else Brand.Ivory)) {
            val step = 22.dp.toPx()
            val c = if (dark) a.copy(alpha = 0.22f) else a.copy(alpha = 0.25f)
            var y = step / 2
            while (y < size.height) {
                var x = step / 2
                while (x < size.width) { drawCircle(c, 1.4.dp.toPx(), Offset(x, y)); x += step }
                y += step
            }
        }
        else -> {
            val painters = DOODLES.map { rememberVectorPainter(it) }
            val color = if (dark) a.copy(alpha = 0.10f) else a.copy(alpha = 0.12f)
            Box(modifier.background(if (dark) Brand.Black else Brand.Ivory)) {
                AuroraBackground(Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize()) {
                    val cell = 76.dp.toPx()
                    val s = 26.dp.toPx()
                    var row = 0
                    var y = 0f
                    while (y < size.height + cell) {
                        var col = 0
                        var x = if (row % 2 == 0) 0f else cell / 2
                        while (x < size.width + cell) {
                            val k = (row * 7 + col * 3) % painters.size
                            val rot = ((row * 31 + col * 17) % 60 - 30).toFloat()
                            val jx = ((row * 13 + col * 29) % 20 - 10).dp.toPx()
                            val jy = ((row * 19 + col * 11) % 20 - 10).dp.toPx()
                            rotate(rot, Offset(x + jx + s / 2, y + jy + s / 2)) {
                                translate(x + jx, y + jy) {
                                    with(painters[k]) { draw(Size(s, s), 1f, ColorFilter.tint(color)) }
                                }
                            }
                            x += cell; col++
                        }
                        y += cell * 0.9f; row++
                    }
                }
            }
        }
    }
}
