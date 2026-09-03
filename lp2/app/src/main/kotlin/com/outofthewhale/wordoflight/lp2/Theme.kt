package com.outofthewhale.wordoflight.lp2

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The Light Phone look, rebuilt without the SDK.
 *
 * The LP3 tool gets `LightTheme` and its primitives from `com.thelightphone.*`,
 * none of which exists here. The palette is deliberately the same shape - a
 * background, a foreground, and one muted tone - because the constraint that
 * produced it still holds: these screens are black and white, so meaning has to
 * come from weight, size and placement rather than colour.
 */
data class Palette(
    val background: Color,
    val content: Color,
    val contentSecondary: Color,
) {
    companion object {
        val Dark = Palette(
            background = Color.Black,
            content = Color.White,
            contentSecondary = Color(0xFFBBBBBB),
        )
        val Light = Palette(
            background = Color.White,
            content = Color.Black,
            contentSecondary = Color(0xFF666666),
        )
    }
}

data class Typography(
    val heading: TextStyle,
    val subheading: TextStyle,
    val paragraph: TextStyle,
    val detail: TextStyle,
    val fine: TextStyle,
) {
    companion object {
        val Default = Typography(
            heading = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Normal),
            subheading = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Normal),
            paragraph = TextStyle(fontSize = 20.sp, lineHeight = 28.sp),
            detail = TextStyle(fontSize = 16.sp),
            fine = TextStyle(fontSize = 14.sp),
        )
    }
}

val LocalPalette = staticCompositionLocalOf { Palette.Dark }
val LocalTypography = staticCompositionLocalOf { Typography.Default }

/** App-wide theme state, so a toggle reaches every screen. */
object ThemeController {
    var palette by mutableStateOf(Palette.Dark)
        private set

    val isDark: Boolean get() = palette == Palette.Dark

    fun toggle() {
        palette = if (isDark) Palette.Light else Palette.Dark
    }
}

@Composable
fun WordOfLightTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalPalette provides ThemeController.palette,
        LocalTypography provides Typography.Default,
        content = content,
    )
}
