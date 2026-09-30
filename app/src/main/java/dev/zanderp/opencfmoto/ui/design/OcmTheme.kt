// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** OpenCfMoto colour tokens (spec §3.1). Same names as the XML colours used by the View screens. */
data class OcmColors(
    val bg: Color, val bgTop: Color, val surface: Color, val surface2: Color,
    val accent: Color, val onAccent: Color, val text: Color, val text2: Color, val outline: Color,
    val live: Color, val busy: Color, val error: Color, val idle: Color,
)

val LightOcmColors = OcmColors(
    bg = Color(0xFFF4F7FA), bgTop = Color(0xFFE6EDF2), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFEEF2F5),
    accent = Color(0xFF0B8C9B), onAccent = Color(0xFFFFFFFF), text = Color(0xFF14191F), text2 = Color(0xFF5B6773),
    outline = Color(0xFFDCE3E9), live = Color(0xFF1FA66A), busy = Color(0xFFC98A00), error = Color(0xFFD93A3A),
    idle = Color(0xFF8A93A3),
)

val DarkOcmColors = OcmColors(
    bg = Color(0xFF0F1419), bgTop = Color(0xFF1C2430), surface = Color(0xFF182029), surface2 = Color(0xFF212B36),
    accent = Color(0xFF27C1D3), onAccent = Color(0xFF06262B), text = Color(0xFFEEF2F6), text2 = Color(0xFF9AA7B4),
    outline = Color(0xFF2A3440), live = Color(0xFF34D399), busy = Color(0xFFFBBF24), error = Color(0xFFF87171),
    idle = Color(0xFF8A93A3),
)

val LocalOcmColors = staticCompositionLocalOf { DarkOcmColors }

object Ocm {
    val colors: OcmColors
        @Composable get() = LocalOcmColors.current
}

/** Type scale (spec §3.2). */
object OcmType {
    val heroModel = TextStyle(fontSize = 72.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-2).sp, lineHeight = 70.sp)
    val screenTitle = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
    val title = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 21.sp)
    val label = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.4.sp)
    val stat = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold)
}

@Composable
fun OcmTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) DarkOcmColors else LightOcmColors
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent, background = c.bg, onBackground = c.text,
            surface = c.surface, onSurface = c.text, surfaceVariant = c.surface2, onSurfaceVariant = c.text2,
            outline = c.outline, error = c.error, secondaryContainer = c.surface2, onSecondaryContainer = c.text,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.onAccent, background = c.bg, onBackground = c.text,
            surface = c.surface, onSurface = c.text, surfaceVariant = c.surface2, onSurfaceVariant = c.text2,
            outline = c.outline, error = c.error, secondaryContainer = c.surface2, onSecondaryContainer = c.text,
        )
    }
    CompositionLocalProvider(LocalOcmColors provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
