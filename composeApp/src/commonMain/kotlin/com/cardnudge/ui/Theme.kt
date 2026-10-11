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
