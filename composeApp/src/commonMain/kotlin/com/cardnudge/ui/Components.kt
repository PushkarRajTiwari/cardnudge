package com.cardnudge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .shadow(14.dp, Tokens.R18, ambientColor = Tokens.Violet, spotColor = Tokens.Violet)
            .clip(Tokens.R18)
            .background(Tokens.AccentH)
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun GhostButton(text: String, color: Color = Tokens.Text, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text,
        color = color,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .clip(Tokens.Pill)
            .background(Color(0x14FFFFFF))
            .border(1.dp, Tokens.Stroke, Tokens.Pill)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
fun SelectChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val base = Modifier.clip(Tokens.Pill)
    val styled = if (selected) base.background(Tokens.AccentH)
    else base.background(Tokens.Surface2).border(1.dp, Tokens.Stroke, Tokens.Pill)
    Text(
        text,
        color = if (selected) Color.White else Tokens.Muted,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = styled.clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
fun RateBadge(text: String, brush: Brush) {
    Text(
        text,
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(Tokens.Pill).background(brush).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        color = Tokens.Muted,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.4.sp,
        modifier = modifier,
    )
}

@Composable
fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    textAlign: TextAlign = TextAlign.Start,
) {
    val style = TextStyle(color = Tokens.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = textAlign)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(Tokens.Cyan),
        keyboardOptions = KeyboardOptions(
            capitalization = if (numeric) KeyboardCapitalization.None else KeyboardCapitalization.Words,
            keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text,
        ),
        modifier = modifier
            .clip(Tokens.R14)
            .background(Tokens.Surface2)
            .border(1.dp, Tokens.Stroke, Tokens.R14)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, style = style.copy(color = Tokens.Hint), modifier = Modifier.fillMaxWidth())
                inner()
            }
        },
    )
}

@Composable
fun EmptyState(emoji: String, title: String, text: String, cta: String, onCta: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Tokens.R24)
            .background(Tokens.Surface)
            .border(1.dp, Tokens.Stroke, Tokens.R24)
            .padding(horizontal = 24.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(emoji, fontSize = 40.sp)
        Spacer(Modifier.height(6.dp))
        Text(title, color = Tokens.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(text, color = Tokens.Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        PrimaryButton(cta, onClick = onCta)
    }
}
