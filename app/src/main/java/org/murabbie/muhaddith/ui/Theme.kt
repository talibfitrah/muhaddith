package org.murabbie.muhaddith.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

private val Green = Color(0xFF0E5A4A)
private val GreenLight = Color(0xFF2E7D68)
private val Gold = Color(0xFFB8891F)
private val Sand = Color(0xFFFFFBF6)
private val Ink = Color(0xFF15201C)

private val LightColors = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7EDE5),
    onPrimaryContainer = Color(0xFF06251E),
    secondary = Gold,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF7EBCF),
    onSecondaryContainer = Color(0xFF3D2C05),
    background = Sand,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF1EDE6),
    onSurfaceVariant = Color(0xFF4A514D),
    outline = Color(0xFFBFC6C1)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD7C0),
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF0B4638),
    onPrimaryContainer = Color(0xFFB9F0DF),
    secondary = Color(0xFFE9C46A),
    onSecondary = Color(0xFF3B2C00),
    background = Color(0xFF111512),
    onBackground = Color(0xFFE3E4E0),
    surface = Color(0xFF191E1B),
    onSurface = Color(0xFFE3E4E0),
    surfaceVariant = Color(0xFF262C29),
    onSurfaceVariant = Color(0xFFC3C9C5),
    outline = Color(0xFF55605A)
)

/** طباعة مهيّأة للنص العربي: أحجام أكبر وارتفاع سطر أوسع */
private val ArabicTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontFamily = FontFamily.Serif),
        headlineMedium = base.headlineMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, lineHeight = 34.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = 18.sp, lineHeight = 33.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 16.sp, lineHeight = 29.sp),
        bodySmall = base.bodySmall.copy(fontSize = 14.sp, lineHeight = 24.sp),
        labelLarge = base.labelLarge.copy(fontSize = 14.sp)
    )
}

/** نمط المتن — يتأثر بإعداد حجم الخط ونوعه */
val LocalMatnStyle = androidx.compose.runtime.staticCompositionLocalOf {
    TextStyle(fontFamily = FontFamily.Serif, fontSize = 20.sp, lineHeight = 40.sp, textAlign = TextAlign.Start)
}

val MatnStyle: TextStyle
    @Composable get() = LocalMatnStyle.current

private fun Typography.scaled(f: Float, serif: Boolean): Typography {
    fun TextStyle.s() = copy(
        fontSize = fontSize * f, lineHeight = lineHeight * f,
        fontFamily = if (serif && fontFamily == FontFamily.Serif) FontFamily.Serif else if (!serif && fontFamily == FontFamily.Serif) FontFamily.SansSerif else fontFamily
    )
    return copy(
        displaySmall = displaySmall.s(), headlineMedium = headlineMedium.s(), headlineSmall = headlineSmall.s(),
        titleLarge = titleLarge.s(), titleMedium = titleMedium.s(), bodyLarge = bodyLarge.s(),
        bodyMedium = bodyMedium.s(), bodySmall = bodySmall.s(), labelLarge = labelLarge.s()
    )
}

@Composable
fun MuhaddithTheme(
    theme: String = "system",
    fontScale: Float = 1f,
    serif: Boolean = true,
    content: @Composable () -> Unit
) {
    val dark = when (theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val matn = TextStyle(
        fontFamily = if (serif) FontFamily.Serif else FontFamily.SansSerif,
        fontSize = 20.sp * fontScale, lineHeight = 40.sp * fontScale, textAlign = TextAlign.Start
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalMatnStyle provides matn) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = ArabicTypography.scaled(fontScale, serif),
            content = content
        )
    }
}
