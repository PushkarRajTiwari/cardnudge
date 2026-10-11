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
import com.cardnudge.domain.CardArt
import com.cardnudge.domain.CardCatalog
import com.cardnudge.domain.CatalogCard
import com.cardnudge.domain.Category
import com.cardnudge.domain.Looks
import com.cardnudge.domain.RewardCard
import com.cardnudge.domain.formatRate
import com.cardnudge.domain.newId
import com.cardnudge.platform.deleteCardPhoto
import com.cardnudge.platform.rememberPhotoPicker
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
    defaultLook: CardArt,
    onSave: (RewardCard) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var catalogId by remember { mutableStateOf(card?.catalogId) }
    var name by remember { mutableStateOf(card?.name.orEmpty()) }
    var bank by remember { mutableStateOf(card?.bank.orEmpty()) }
    var art by remember { mutableStateOf(card?.art ?: defaultLook) }
    var points by remember { mutableStateOf(card?.points ?: false) }
    var photo by remember { mutableStateOf(card?.photo) }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    val rates = remember {
        mutableStateMapOf<Category, String>().apply {
            card?.rates?.forEach { (cat, rate) -> put(cat, formatRate(rate).removeSuffix("%")) }
        }
    }
    val catalogCard = CardCatalog.card(catalogId)
    val results = remember(query) { CardCatalog.search(query) }
    val originalPhoto = card?.photo

    val picker = rememberPhotoPicker { picked ->
        if (picked != null) {
            // Replacing a photo picked in this session: the old one was never saved, so drop it.
            photo?.takeIf { it != originalPhoto }?.let(::deleteCardPhoto)
            photo = picked
        }
    }

    fun select(c: CatalogCard) {
        catalogId = c.id
        name = c.name
        bank = c.bank
        art = c.variants.first()
        points = c.points
        rates.clear()
        c.rates.forEach { (cat, rate) -> rates[cat] = formatRate(rate).removeSuffix("%") }
        query = ""
        error = null
    }

    EditorSheet(
        title = if (card == null) "Add a card" else "Edit card",
        action = "Save card",
        error = error,
        onDismiss = {
            if (!saved) photo?.takeIf { it != originalPhoto }?.let(::deleteCardPhoto)
            onDismiss()
        },
        onAction = {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) {
                error = "Pick a card from the list or type its name."
                false
            } else {
                val parsed = rates.mapNotNull { (cat, text) -> text.toDoubleOrNull()?.takeIf { it > 0 }?.let { cat to it } }.toMap()
                onSave(
                    RewardCard(
                        id = card?.id ?: newId(), name = trimmed, bank = bank.trim(), rates = parsed,
                        points = points, catalogId = catalogId, artId = art.id, photo = photo,
                    )
                )
                if (originalPhoto != null && originalPhoto != photo) deleteCardPhoto(originalPhoto)
                saved = true
                true
            }
        },
    ) { close ->
        CardFace(art, name.ifBlank { "Your card" }, bank, catalogCard?.network, photo, Modifier.padding(top = 10.dp, bottom = 4.dp))

        SectionLabel("Find your card", Modifier.padding(top = 18.dp))
        Field(query, { query = it }, "Search, e.g. amex gold", Modifier.fillMaxWidth().padding(top = 8.dp))
        if (query.isNotBlank() || (card == null && catalogId == null)) {
            Column(Modifier.padding(top = 8.dp)) {
                if (results.isEmpty()) {
                    Text("Not in the list yet. Type the details below.", color = Tokens.Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
                }
                results.forEach { c -> CatalogRow(c, selected = c.id == catalogId) { select(c) } }
            }
        }
        catalogCard?.note?.let {
            Text("ⓘ $it", color = Tokens.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        }

        SectionLabel("Card", Modifier.padding(top = 20.dp))
        Field(name, { name = it; error = null }, "Card name, e.g. Gold Card", Modifier.fillMaxWidth().padding(top = 8.dp))
        Field(bank, { bank = it }, "Bank, e.g. American Express", Modifier.fillMaxWidth().padding(top = 10.dp))

        SectionLabel("Colour", Modifier.padding(top = 20.dp))
        val looks = remember(catalogId) { catalogCard?.variants.orEmpty() + Looks.all }
        Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            looks.forEach { look -> LookChip(look, look.id == art.id) { art = look } }
        }

        SectionLabel("Card photo", Modifier.padding(top = 20.dp))
        Text(
            "Optional. Shows your real card instead of the drawn one, and stays on this phone.",
            color = Tokens.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
        )
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("📷  Take photo", Tokens.Cyan, onClick = picker::takePhoto)
            GhostButton("🖼  Choose photo", Tokens.Cyan, onClick = picker::choosePhoto)
        }
        if (photo != null) {
            GhostButton("Use the drawn card instead", Tokens.Muted, Modifier.padding(top = 8.dp)) {
                photo?.takeIf { it != originalPhoto }?.let(::deleteCardPhoto)
                photo = null
            }
        }

        SectionLabel("Rewards per \$1", Modifier.padding(top = 20.dp))
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectChip("Points (x)", points) { points = true }
            SelectChip("Cash back (%)", !points) { points = false }
        }
        Text("Leave blank to use the “Everything else” rate.", color = Tokens.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
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
                Text(if (points) "x" else "%", color = Tokens.Muted, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp).width(16.dp))
            }
        }

        if (onDelete != null) {
            GhostButton("Remove this card", Danger, Modifier.padding(top = 22.dp)) {
                onDelete()
                saved = true // The card's photo is deleted with the card.
                close()
            }
        }
    }
}

@Composable
private fun CatalogRow(card: CatalogCard, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp).clip(Tokens.R14)
            .background(if (selected) Tokens.Violet.copy(alpha = 0.18f) else Tokens.Surface2)
            .border(1.dp, if (selected) Tokens.Violet else Tokens.Stroke, Tokens.R14)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp, 25.dp).clip(RoundedCornerShape(5.dp)).background(card.variants.first().brush()))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(card.fullName, color = Tokens.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            val kind = if (card.points) "Points" else "Cash back"
            val colours = if (card.variants.size > 1) " · ${card.variants.joinToString(", ") { it.label }}" else ""
            Text("$kind$colours", color = Tokens.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun LookChip(look: CardArt, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(Tokens.Pill)
            .background(if (selected) Tokens.Violet.copy(alpha = 0.22f) else Tokens.Surface2)
            .border(if (selected) 2.dp else 1.dp, if (selected) Color.White else Tokens.Stroke, Tokens.Pill)
            .clickable(onClick = onClick)
            .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(look.brush()))
        Text(look.label, color = if (selected) Color.White else Tokens.Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp))
    }
}
