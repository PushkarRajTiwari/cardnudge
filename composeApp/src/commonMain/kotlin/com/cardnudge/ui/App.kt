@file:OptIn(ExperimentalFoundationApi::class)

package com.cardnudge.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cardnudge.domain.Category
import com.cardnudge.domain.Pick
import com.cardnudge.domain.Place
import com.cardnudge.domain.Recommender
import com.cardnudge.domain.RewardCard
import com.cardnudge.domain.Looks
import com.cardnudge.platform.Platform
import com.cardnudge.platform.SetupItem
import com.cardnudge.platform.SetupStep
import com.cardnudge.platform.deleteCardPhoto

private enum class Tab(val title: String) { CARDS("Cards"), PLACES("Places"), PICKS("Best picks") }

private sealed interface Sheet {
    data class CardEditor(val card: RewardCard?) : Sheet
    data object PlaceEditor : Sheet
}

private class PendingDelete(val message: String, val action: () -> Unit)

/** The whole app UI, shared by Android and iOS. */
@Composable
fun App(platform: Platform) {
    CardNudgeTheme {
        val repo = platform.repository
        val cards by repo.cards.collectAsStateWithLifecycle()
        val places by repo.places.collectAsStateWithLifecycle()
        val setupVersion by platform.setupVersion.collectAsStateWithLifecycle()

        // Permissions can change in system settings while we're away, so re-read them on every resume.
        var resumeCount by remember { mutableIntStateOf(0) }
        LifecycleResumeEffect(Unit) {
            resumeCount++
            onPauseOrDispose { }
        }
        val setup = remember(setupVersion, resumeCount) { platform.setupItems() }
        val granted = remember(setup) { setup.filter { it.done }.map { it.step } }

        // Re-register geofences only when places or permissions actually change.
        LaunchedEffect(places, granted) { platform.syncGeofences(places) }

        val picks = remember(cards) { Category.entries.associateWith { Recommender.best(cards, it) } }

        var tab by rememberSaveable { mutableStateOf(Tab.CARDS) }
        var sheet by remember { mutableStateOf<Sheet?>(null) }
        var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }

        Box(Modifier.fillMaxSize().background(Tokens.Bg).glowBackdrop()) {
            LazyColumn(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 130.dp),
            ) {
                item(key = "header") { Header() }
                item(key = "stats") { StatsRow(cards, places) }
                item(key = "tabs") { Tabs(tab) { tab = it } }
                when (tab) {
                    Tab.CARDS -> cardsSection(
                        cards,
                        onAdd = { sheet = Sheet.CardEditor(null) },
                        onEdit = { sheet = Sheet.CardEditor(it) },
                    )
                    Tab.PLACES -> placesSection(
                        places, picks, setup,
                        onFix = platform::fixSetup,
                        onAdd = { sheet = Sheet.PlaceEditor },
                        onTest = { place ->
                            if (setup.any { it.step == SetupStep.NOTIFICATIONS && !it.done }) platform.fixSetup(SetupStep.NOTIFICATIONS)
                            else platform.postAlert(Recommender.arrivalAlert(place, cards))
                        },
                        onDelete = { place ->
                            pendingDelete = PendingDelete("Remove ${place.name}?") { repo.deletePlace(place.id) }
                        },
                    )
                    Tab.PICKS -> picksSection(picks)
                }
            }

            if (tab != Tab.PICKS) {
                Fab(
                    Modifier.align(Alignment.BottomEnd)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(end = 22.dp, bottom = 28.dp)
                ) {
                    sheet = if (tab == Tab.CARDS) Sheet.CardEditor(null) else Sheet.PlaceEditor
                }
            }
        }

        when (val s = sheet) {
            is Sheet.CardEditor -> CardEditorSheet(
                card = s.card,
                defaultLook = Looks.all[cards.size % Looks.all.size],
                onSave = repo::saveCard,
                onDelete = s.card?.let { card ->
                    {
                        repo.deleteCard(card.id)
                        card.photo?.let(::deleteCardPhoto)
                    }
                },
                onDismiss = { sheet = null },
            )
            Sheet.PlaceEditor -> PlaceEditorSheet(
                platform = platform,
                locationAllowed = setup.none { it.step == SetupStep.LOCATION && !it.done },
                atLimit = places.size >= platform.maxPlaces,
                onSave = repo::addPlace,
                onDismiss = { sheet = null },
            )
            null -> Unit
        }

        pendingDelete?.let { pd ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text(pd.message) },
                confirmButton = { TextButton(onClick = { pd.action(); pendingDelete = null }) { Text("Remove") } },
                dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
                containerColor = Tokens.Surface,
                titleContentColor = Tokens.Text,
            )
        }
    }
}

/** Two soft colour blobs behind the header. Brushes are built once per size, not per frame. */
private fun Modifier.glowBackdrop() = drawWithCache {
    val violetCenter = Offset(size.width * 0.1f, -size.width * 0.15f)
    val cyanCenter = Offset(size.width * 1.05f, size.width * 0.15f)
    val violetR = size.width * 0.75f
    val cyanR = size.width * 0.6f
    val violet = Brush.radialGradient(listOf(Tokens.Violet.copy(alpha = 0.35f), Color.Transparent), violetCenter, violetR)
    val cyan = Brush.radialGradient(listOf(Tokens.Cyan.copy(alpha = 0.30f), Color.Transparent), cyanCenter, cyanR)
    onDrawBehind {
        drawCircle(violet, violetR, violetCenter)
        drawCircle(cyan, cyanR, cyanCenter)
    }
}

@Composable
private fun Header() {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("◆", color = Tokens.Cyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("  CARDNUDGE", color = Tokens.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.4.sp)
        }
        Text(
            "Pay smarter.\nEarn more.",
            style = TextStyle(brush = Tokens.HeadlineBrush, fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.ExtraBold),
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            "The right card, the moment you walk in.",
            color = Tokens.Muted, fontSize = 15.sp,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun StatsRow(cards: List<RewardCard>, places: List<Place>) {
    val top = remember(cards) {
        cards.mapNotNull { c -> c.topCategory()?.let { c to it.second } }.maxByOrNull { it.second }
    }
    Row(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile(cards.size.toString(), "cards", Modifier.weight(1f))
        StatTile(places.size.toString(), "places", Modifier.weight(1f))
        StatTile(top?.let { (c, r) -> c.reward(r) } ?: "–", "top rate", Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(value: String, caption: String, modifier: Modifier) {
    Column(
        modifier.clip(Tokens.R18).background(Tokens.Surface.copy(alpha = 0.7f))
            .border(1.dp, Tokens.Stroke, Tokens.R18).padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(value, color = Tokens.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(caption, color = Tokens.Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Tabs(selected: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 18.dp)
            .clip(Tokens.Pill).background(Tokens.Surface).border(1.dp, Tokens.Stroke, Tokens.Pill).padding(4.dp)
    ) {
        Tab.entries.forEach { t ->
            val on = t == selected
            val base = Modifier.weight(1f).clip(Tokens.Pill)
            Text(
                t.title,
                color = if (on) Color.White else Tokens.Muted,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = (if (on) base.background(Tokens.AccentH) else base)
                    .clickable { onSelect(t) }
                    .padding(vertical = 11.dp),
            )
        }
    }
}

@Composable
private fun Fab(modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(64.dp)
            .shadow(18.dp, CircleShape, ambientColor = Tokens.Violet, spotColor = Tokens.Violet)
            .clip(CircleShape).background(Tokens.AccentDiag).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("+", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
    }
}

// ------------------------------------------------------------------ cards

private fun LazyListScope.cardsSection(cards: List<RewardCard>, onAdd: () -> Unit, onEdit: (RewardCard) -> Unit) {
    if (cards.isEmpty()) {
        item(key = "cards-empty") {
            EmptyState("💳", "No cards yet", "Add the cards in your wallet with the reward rate for each kind of spend.", "Add your first card", onAdd)
        }
        return
    }
    items(cards, key = { it.id }) { card -> CardTile(card) { onEdit(card) } }
    item(key = "cards-hint") { Hint("Tap a card to edit or remove it") }
}

@Composable
private fun CardTile(card: RewardCard, onClick: () -> Unit) {
    val summary = remember(card) {
        card.rates.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(4)
            .joinToString("   ") { "${it.key.emoji} ${card.reward(it.value)}" }
    }
    Column(Modifier.padding(bottom = 22.dp).fillMaxWidth()) {
        CardFace(
            art = card.art,
            name = card.name,
            bank = card.bank,
            network = card.network,
            photo = card.photo,
            modifier = Modifier.clickable(onClick = onClick),
        )
        if (summary.isNotEmpty()) {
            Text(
                summary, color = Tokens.Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 10.dp, start = 6.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ places

private fun LazyListScope.placesSection(
    places: List<Place>,
    picks: Map<Category, Pick?>,
    setup: List<SetupItem>,
    onFix: (SetupStep) -> Unit,
    onAdd: () -> Unit,
    onTest: (Place) -> Unit,
    onDelete: (Place) -> Unit,
) {
    if (setup.any { !it.done }) item(key = "setup") { SetupPanel(setup, onFix) }
    if (places.isEmpty()) {
        item(key = "places-empty") {
            EmptyState("📍", "No places yet", "Save the shops you visit. When you arrive, CardNudge tells you which card to use.", "Add a place", onAdd)
        }
        return
    }
    items(places, key = { it.id }) { place ->
        PlaceRow(place, picks[place.category], onTest = { onTest(place) }, onLongClick = { onDelete(place) })
    }
    item(key = "places-hint") { Hint("Long-press a place to remove it") }
}

@Composable
private fun SetupPanel(items: List<SetupItem>, onFix: (SetupStep) -> Unit) {
    Column(
        Modifier.padding(bottom = 16.dp).fillMaxWidth().clip(Tokens.R22)
            .background(Brush.linearGradient(listOf(Tokens.Violet.copy(alpha = 0.16f), Tokens.Cyan.copy(alpha = 0.10f))))
            .border(1.dp, Tokens.Violet.copy(alpha = 0.35f), Tokens.R22)
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Text("⚡ Turn on arrival alerts", color = Tokens.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(
            "These let CardNudge notify you when you reach a saved place.",
            color = Tokens.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
        )
        items.forEach { item ->
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (item.done) "✓" else "•", color = if (item.done) Tokens.Green else Tokens.Amber,
                    fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp),
                )
                Text(
                    item.title, color = if (item.done) Tokens.Muted else Tokens.Text, fontSize = 14.sp,
                    fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
                )
                if (!item.done) GhostButton("Allow", Tokens.Cyan) { onFix(item.step) }
            }
        }
    }
}

@Composable
private fun PlaceRow(place: Place, pick: Pick?, onTest: () -> Unit, onLongClick: () -> Unit) {
    Row(
        Modifier.padding(bottom = 12.dp).fillMaxWidth().clip(Tokens.R20).background(Tokens.Surface)
            .border(1.dp, Tokens.Stroke, Tokens.R20)
            .combinedClickable(onClick = {}, onLongClick = onLongClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(50.dp).clip(CircleShape)
                .background(pick?.card?.art?.brush() ?: Tokens.AccentDiag),
            contentAlignment = Alignment.Center,
        ) { Text(place.category.emoji, fontSize = 22.sp) }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(place.name, color = Tokens.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("${place.category.label} · ${place.radiusM} m", color = Tokens.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            Text(
                pick?.let { "Use ${it.card.name} · ${it.card.reward(it.rate)}" } ?: "Add a card to get a pick",
                color = Tokens.Cyan, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 5.dp),
            )
        }
        GhostButton("Test", onClick = onTest)
    }
}

// ------------------------------------------------------------------ picks

private fun LazyListScope.picksSection(picks: Map<Category, Pick?>) {
    item(key = "picks-intro") {
        Text("Your best card for every kind of spend.", color = Tokens.Muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 14.dp))
    }
    items(Category.entries, key = { it.name }) { cat ->
        val pick = picks[cat]
        Row(
            Modifier.padding(bottom = 10.dp).fillMaxWidth().clip(Tokens.R18).background(Tokens.Surface)
                .border(1.dp, Tokens.Stroke, Tokens.R18).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(cat.emoji, fontSize = 22.sp, modifier = Modifier.width(40.dp))
            Column(Modifier.weight(1f)) {
                Text(cat.label, color = Tokens.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(pick?.card?.name ?: "Add a card", color = Tokens.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
            }
            if (pick != null && pick.rate > 0) RateBadge(pick.card.reward(pick.rate), pick.card.art.brush())
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text, color = Tokens.Muted, fontSize = 12.sp, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}
