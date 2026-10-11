@file:OptIn(ExperimentalMaterial3Api::class)

package com.cardnudge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardnudge.domain.Category
import com.cardnudge.domain.GeoPoint
import com.cardnudge.domain.Place
import com.cardnudge.domain.RewardCard
import com.cardnudge.domain.formatDecimal
import com.cardnudge.domain.formatRate
import com.cardnudge.domain.newId
import com.cardnudge.platform.Platform
import com.cardnudge.platform.SetupStep
import kotlinx.coroutines.launch

private val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
private val Danger = Color(0xFFFF6B8A)

/**
 * Dark bottom sheet with a title, a scrolling body and one primary action.
 * [onAction] returns true when the sheet should close.
 */
@Composable
private fun EditorSheet(
    title: String,
    action: String,
    error: String?,
    onAction: () -> Boolean,
    onDismiss: () -> Unit,
    body: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { state.hide() }.invokeOnCompletion { onDismiss() } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = SheetShape,
        containerColor = Tokens.Surface,
        scrimColor = Color(0x99000000),
        dragHandle = {
            Box(Modifier.padding(top = 12.dp).size(40.dp, 4.dp).clip(Tokens.Pill).background(Color(0x33FFFFFF)))
        },
    ) {
        Column(Modifier.fillMaxWidth().imePadding().padding(start = 22.dp, end = 22.dp, bottom = 22.dp)) {
            Text(title, color = Tokens.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { body(close) }
            if (error != null) {
                Text(error, color = Danger, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton(action) { if (onAction()) close() }
        }
    }
}

@Composable
fun CardEditorSheet(
    card: RewardCard?,
    defaultPalette: Int,
    onSave: (RewardCard) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(card?.name.orEmpty()) }
    var bank by remember { mutableStateOf(card?.bank.orEmpty()) }
    var palette by remember { mutableIntStateOf(card?.palette ?: defaultPalette) }
    var error by remember { mutableStateOf<String?>(null) }
    val rates = remember {
        mutableStateMapOf<Category, String>().apply {
            card?.rates?.forEach { (cat, rate) -> put(cat, formatRate(rate).removeSuffix("%")) }
        }
    }

    EditorSheet(
        title = if (card == null) "Add a card" else "Edit card",
        action = "Save card",
        error = error,
        onDismiss = onDismiss,
        onAction = {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) {
                error = "Give the card a name."
                false
            } else {
                val parsed = rates.mapNotNull { (cat, text) -> text.toDoubleOrNull()?.takeIf { it > 0 }?.let { cat to it } }.toMap()
                onSave(RewardCard(card?.id ?: newId(), trimmed, bank.trim(), palette, parsed))
                true
            }
        },
    ) { close ->
        SectionLabel("Card", Modifier.padding(top = 12.dp))
        Field(name, { name = it; error = null }, "Card name, e.g. Millennia", Modifier.fillMaxWidth().padding(top = 8.dp))
        Field(bank, { bank = it }, "Bank, e.g. HDFC", Modifier.fillMaxWidth().padding(top = 10.dp))

        SectionLabel("Colour", Modifier.padding(top = 20.dp))
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tokens.Palettes.indices.forEach { i ->
                val ring = if (i == palette) Modifier.border(3.dp, Color.White, CircleShape) else Modifier
                Box(Modifier.size(38.dp).clip(CircleShape).background(Tokens.paletteBrush(i)).then(ring).clickable { palette = i })
            }
        }

        SectionLabel("Reward rate per category (%)", Modifier.padding(top = 20.dp))
        Text("Leave blank to use the “Everything else” rate.", color = Tokens.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        Category.entries.forEach { cat ->
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${cat.emoji}  ${cat.label}", color = Tokens.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Field(
                    rates[cat].orEmpty(),
                    { input -> rates[cat] = input.filter { it.isDigit() || it == '.' }.take(6) },
                    "0",
                    Modifier.width(96.dp),
                    numeric = true,
                    textAlign = TextAlign.End,
                )
                Text("%", color = Tokens.Muted, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }

        if (onDelete != null) {
            GhostButton("Remove this card", Danger, Modifier.padding(top = 22.dp)) {
                onDelete()
                close()
            }
        }
    }
}

private sealed interface LocState {
    data object Unset : LocState
    data object Finding : LocState
    data class Found(val point: GeoPoint) : LocState
    data class Problem(val message: String) : LocState
}

@Composable
fun PlaceEditorSheet(
    platform: Platform,
    locationAllowed: Boolean,
    atLimit: Boolean,
    onSave: (Place) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(Category.GROCERY) }
    var radius by remember { mutableIntStateOf(150) }
    var coordsText by remember { mutableStateOf("") }
    var loc by remember { mutableStateOf<LocState>(LocState.Unset) }
    var error by remember { mutableStateOf<String?>(if (atLimit) "You've reached the limit of ${platform.maxPlaces} places on this phone." else null) }
    // Scoped to this sheet: a pending location request is cancelled when the sheet closes.
    val scope = rememberCoroutineScope()

    EditorSheet(
        title = "Add a place",
        action = "Save place",
        error = error,
        onDismiss = onDismiss,
        onAction = {
            val point = (loc as? LocState.Found)?.point ?: parseCoords(coordsText)
            when {
                atLimit -> false
                name.isBlank() -> { error = "Give the place a name."; false }
                point == null -> { error = "Set the location first."; false }
                else -> {
                    onSave(Place(newId(), name.trim(), category, point.lat, point.lng, radius))
                    true
                }
            }
        },
    ) {
        SectionLabel("Place", Modifier.padding(top = 12.dp))
        Field(name, { name = it; if (!atLimit) error = null }, "Place name, e.g. DMart Whitefield", Modifier.fillMaxWidth().padding(top = 8.dp))

        SectionLabel("What kind of spend?", Modifier.padding(top = 20.dp))
        Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Category.entries.forEach { c -> SelectChip("${c.emoji} ${c.label}", c == category) { category = c } }
        }

        SectionLabel("Alert radius", Modifier.padding(top = 20.dp))
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(100, 150, 250, 400).forEach { r -> SelectChip("$r m", r == radius) { radius = r } }
        }

        SectionLabel("Where is it?", Modifier.padding(top = 20.dp))
        GhostButton("📍  Use my current location", Tokens.Cyan, Modifier.padding(top = 10.dp)) {
            if (!locationAllowed) {
                platform.fixSetup(SetupStep.LOCATION)
                loc = LocState.Problem("Allow location, then tap again.")
                return@GhostButton
            }
            loc = LocState.Finding
            scope.launch {
                loc = platform.currentLocation()?.let { LocState.Found(it) }
                    ?: LocState.Problem("Couldn't get a fix. Try again outdoors, or paste coordinates below.")
            }
        }
        val (statusText, statusColor) = when (val s = loc) {
            LocState.Unset -> "Location not set yet" to Tokens.Muted
            LocState.Finding -> "Finding you…" to Tokens.Muted
            is LocState.Found -> "✓ ${formatDecimal(s.point.lat, 5)}, ${formatDecimal(s.point.lng, 5)}  (±${s.point.accuracyM.toInt()} m)" to Tokens.Green
            is LocState.Problem -> s.message to Tokens.Amber
        }
        Text(statusText, color = statusColor, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp))
        Field(coordsText, { coordsText = it }, "…or paste coordinates: 12.9716, 77.5946", Modifier.fillMaxWidth().padding(top = 12.dp))
    }
}

/** Accepts "12.97, 77.59" or "12.97 77.59", as copied from Google or Apple Maps. */
fun parseCoords(text: String): GeoPoint? {
    val parts = text.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.size != 2) return null
    val lat = parts[0].toDoubleOrNull() ?: return null
    val lng = parts[1].toDoubleOrNull() ?: return null
    if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
    return GeoPoint(lat, lng, 0f)
}
