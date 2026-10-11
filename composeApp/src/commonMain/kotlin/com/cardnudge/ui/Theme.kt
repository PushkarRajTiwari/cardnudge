package com.cardnudge.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Design tokens for the dark, glowing look. Brushes and shapes are allocated once, not per frame. */
object Tokens {
    val Bg = Color(0xFF0A0A12)
    val Surface = Color(0xFF14141F)
    val Surface2 = Color(0xFF1C1C2B)
    val Stroke = Color(0x26FFFFFF)
    val Text = Color(0xFFF4F4F8)
    val Muted = Color(0xFF9A9AB4)
    val Hint = Color(0x66FFFFFF)
    val Violet = Color(0xFF7C5CFF)
    val Cyan = Color(0xFF00D4FF)
    val Green = Color(0xFF3DDC97)
    val Amber = Color(0xFFFFB547)

    val Accent = listOf(Violet, Cyan)
    val AccentH = Brush.horizontalGradient(Accent)
    val AccentDiag = Brush.linearGradient(Accent)
    val HeadlineBrush = Brush.horizontalGradient(listOf(Color.White, Color(0xFFC9BEFF), Cyan))

    /** Gradients a card can wear; the index is stored on the card. */
    val Palettes: List<List<Color>> = listOf(
        listOf(Color(0xFF7C5CFF), Color(0xFF00D4FF)),
        listOf(Color(0xFFFF4FD8), Color(0xFF7C5CFF)),
        listOf(Color(0xFFFF8A3D), Color(0xFFFF3D7F)),
        listOf(Color(0xFF00C9A7), Color(0xFF0072FF)),
        listOf(Color(0xFF2B2B3F), Color(0xFF4A4A6A)),
        listOf(Color(0xFFC9A227), Color(0xFF6B4F12)),
    )
    private val paletteBrushes = Palettes.map { Brush.linearGradient(it) }

    fun paletteColors(index: Int) = Palettes[index.coerceIn(0, Palettes.lastIndex)]
    fun paletteBrush(index: Int) = paletteBrushes[index.coerceIn(0, paletteBrushes.lastIndex)]

    val Pill = RoundedCornerShape(100.dp)
    val R14 = RoundedCornerShape(14.dp)
    val R18 = RoundedCornerShape(18.dp)
    val R20 = RoundedCornerShape(20.dp)
    val R22 = RoundedCornerShape(22.dp)
    val R24 = RoundedCornerShape(24.dp)
    val R26 = RoundedCornerShape(26.dp)
}

@Composable
fun CardNudgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Tokens.Violet,
            secondary = Tokens.Cyan,
            background = Tokens.Bg,
            surface = Tokens.Surface,
            onSurface = Tokens.Text,
            onBackground = Tokens.Text,
        ),
        content = content,
    )
}
