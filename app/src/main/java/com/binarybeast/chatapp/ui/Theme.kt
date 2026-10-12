package com.binarybeast.chatapp.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.binarybeast.chatapp.data.AppSettings

/** يفتح رابط (في المتصفح المدمج أو الخارجي حسب الإعدادات). AppRoot هو اللي بيوفّره. */
val LocalOpenLink = staticCompositionLocalOf<(String) -> Unit> { {} }

/** الهوية البصرية: داكن = أسود + كحلي + نيون، فاتح = عاجي + الألوان المقابلة للنيون. */
object Brand {
    // داكن
    val Black = Color(0xFF000000)
    val Navy = Color(0xFF070B1A)
    val NavyHi = Color(0xFF0B1330)
    val NavyLine = Color(0xFF1B2A55)
    val NeonCyan = Color(0xFF00E5FF)
    val NeonLime = Color(0xFFB6FF2B)
    val NeonMagenta = Color(0xFFFF3DF2)
    // فاتح (سمني)
    val Ivory = Color(0xFFF8F4EA)
    val IvorySurface = Color(0xFFFFFDF7)
    val IvoryVariant = Color(0xFFEFE9DA)
    val Vermilion = Color(0xFFF2441D)
    val Violet = Color(0xFF6D28FF)
    val Emerald = Color(0xFF00A86B)
}

/** (مفتاح) → (اسم) → (لون داكن، لون فاتح مقابل). */
val ACCENTS = listOf(
    "neon" to ("نيون سماوي" to Color(0xFF00E5FF)),
    "lime" to ("نيون ليموني" to Color(0xFFB6FF2B)),
    "magenta" to ("نيون وردي" to Color(0xFFFF3DF2)),
    "violet" to ("نيون بنفسجي" to Color(0xFF9D5CFF)),
    "orange" to ("نيون برتقالي" to Color(0xFFFF8A1F)),
)
private val LIGHT_PAIR = mapOf(
    "neon" to Color(0xFFF2441D),
    "lime" to Color(0xFF6D28FF),
    "magenta" to Color(0xFF00A86B),
    "violet" to Color(0xFFE59A00),
    "orange" to Color(0xFF1F6BFF),
)
private fun accentOf(key: String, dark: Boolean): Color {
    val k = ACCENTS.firstOrNull { it.first == key }?.first ?: "neon"
    return if (dark) ACCENTS.first { it.first == k }.second.second else LIGHT_PAIR.getValue(k)
}

val LocalDark = staticCompositionLocalOf { true }
val LocalAccent = staticCompositionLocalOf { Brand.NeonCyan }

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val prefs by AppSettings.prefs.collectAsState()
    val dark = when (prefs.darkMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val base = LocalDensity.current
    CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Rtl,
        LocalDensity provides Density(base.density, base.fontScale * prefs.fontScale),
        LocalDark provides dark,
    ) {
        val c = accentOf(prefs.accent, dark)
        val scheme = if (dark) darkColorScheme(
            primary = c, onPrimary = Color.Black,
            primaryContainer = c.copy(alpha = 0.18f), onPrimaryContainer = Color(0xFFE8F0FF),
            secondary = Brand.NeonLime, onSecondary = Color.Black,
            tertiary = Brand.NeonMagenta,
            background = Color.Transparent, onBackground = Color(0xFFE8F0FF),
            surface = Brand.Navy, onSurface = Color(0xFFE8F0FF),
            surfaceVariant = Brand.NavyHi, onSurfaceVariant = Color(0xFF8FA3D6),
            surfaceContainer = Brand.Navy, surfaceContainerHigh = Brand.NavyHi, surfaceContainerHighest = Brand.NavyHi,
            outline = Brand.NavyLine, outlineVariant = Brand.NavyLine,
            error = Color(0xFFFF4D6D),
        ) else lightColorScheme(
            primary = c, onPrimary = Color.White,
            primaryContainer = c.copy(alpha = 0.14f), onPrimaryContainer = Color(0xFF1B1A17),
            secondary = Brand.Violet, tertiary = Brand.Emerald,
            background = Color.Transparent, onBackground = Color(0xFF1B1A17),
            surface = Brand.IvorySurface, onSurface = Color(0xFF1B1A17),
            surfaceVariant = Brand.IvoryVariant, onSurfaceVariant = Color(0xFF6B665A),
            surfaceContainer = Brand.IvorySurface, surfaceContainerHigh = Brand.IvoryVariant, surfaceContainerHighest = Brand.IvoryVariant,
            outline = Color(0xFFD8D0BC), outlineVariant = Color(0xFFE6DFCC),
            error = Color(0xFFD62839),
        )
        MaterialTheme(
            colorScheme = scheme,
            shapes = androidx.compose.material3.Shapes(
                extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                small = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                medium = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                large = androidx.compose.foundation.shape.RoundedCornerShape(30.dp),
                extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(36.dp),
            ),
        ) {
            CompositionLocalProvider(LocalAccent provides c) {
                Box(Modifier.fillMaxSize()) {
                    AuroraBackground()
                    CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides scheme.onBackground) { content() }
                }
            }
        }
    }
}
