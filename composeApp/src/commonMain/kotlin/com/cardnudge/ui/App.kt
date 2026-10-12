package com.cardnudge.ui

import androidx.compose.foundation.background
import kotlin.math.absoluteValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cardnudge.data.NearbyStatus
import com.cardnudge.domain.Category
import com.cardnudge.domain.Geo
import com.cardnudge.domain.Osm
import com.cardnudge.domain.Store
import com.cardnudge.domain.formatDecimal
import com.cardnudge.domain.Pick
import com.cardnudge.domain.Recommender
import com.cardnudge.domain.RewardCard
import com.cardnudge.domain.Looks
import com.cardnudge.platform.Platform
import com.cardnudge.platform.SetupItem
import com.cardnudge.platform.SetupStep
import com.cardnudge.platform.currentTimeMillis
import com.cardnudge.platform.deleteCardPhoto
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class Tab(val title: String) { CARDS("Cards"), NEARBY("Nearby"), PICKS("Best picks") }

/** A store on the Nearby list with its distance from you, computed once per update. */
private class NearStore(val store: Store, val distanceM: Int)

/** The whole app UI, shared by Android and iOS. */
@Composable
fun App(platform: Platform) {
    CardNudgeTheme {
        val repo = platform.repository
        val cards by repo.cards.collectAsStateWithLifecycle()
        val nearby = platform.nearby
        val area by nearby.area.collectAsStateWithLifecycle()
        val here by nearby.here.collectAsStateWithLifecycle()
        val busy by nearby.busy.collectAsStateWithLifecycle()
        val status by nearby.status.collectAsStateWithLifecycle()
        val setupVersion by platform.setupVersion.collectAsStateWithLifecycle()

        // Permissions can change in system settings while we're away, so re-read them on every resume.
        var resumeCount by remember { mutableIntStateOf(0) }
        LifecycleResumeEffect(Unit) {
            resumeCount++
            onPauseOrDispose { }
        }
        val setup = remember(setupVersion, resumeCount) { platform.setupItems() }
        val granted = remember(setup) { setup.filter { it.done }.map { it.step } }

        // Look around on every return to the app and whenever permissions change. The lookup
        // reuses saved stores unless you've moved, and is cancelled if the app goes away.
        val locationOn = SetupStep.LOCATION in granted
        LaunchedEffect(locationOn, granted, resumeCount) {
            if (locationOn) platform.refreshNearby(force = false)
        }
        val scope = rememberCoroutineScope()

        val picks = remember(cards) { Category.entries.associateWith { Recommender.best(cards, it) } }
        val nearStores = remember(area, here) {
            val stores = area?.stores.orEmpty()
            val at = here
            if (at == null) emptyList<NearStore>()
            else Geo.nearest(stores, at.lat, at.lng, NEARBY_LIST_SIZE)
                .map { NearStore(it, Geo.distanceM(it, at.lat, at.lng).roundToInt()) }
        }

        var tab by rememberSaveable { mutableStateOf(Tab.CARDS) }
        var editing by remember { mutableStateOf<CardEdit?>(null) }

        Box(Modifier.fillMaxSize().background(Tokens.Bg).glowBackdrop()) {
            LazyColumn(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 130.dp),
            ) {
                item(key = "header") { Header() }
                item(key = "stats") { StatsRow(cards, area?.stores?.size ?: 0) }
                item(key = "tabs") { Tabs(tab) { tab = it } }
                when (tab) {
                    Tab.CARDS -> cardsSection(
                        cards, nearStores, picks,
                        onAdd = { editing = CardEdit(null) },
                        onEdit = { editing = CardEdit(it) },
                    )
                    Tab.NEARBY -> nearbySection(
                        nearStores, picks, setup,
                        updatedAtMs = area?.fetchedAtMs,
                        busy = busy,
                        status = status,
                        onFix = platform::fixSetup,
                        onRefresh = { scope.launch { platform.refreshNearby(force = true) } },
                        onTest = { store ->
                            if (SetupStep.NOTIFICATIONS !in granted) platform.fixSetup(SetupStep.NOTIFICATIONS)
                            else platform.postAlert(Recommender.arrivalAlert(store, cards))
                        },
                    )
                    Tab.PICKS -> picksSection(picks)
                }
            }

            if (tab == Tab.CARDS) {
                Fab(
                    Modifier.align(Alignment.BottomEnd)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(end = 22.dp, bottom = 28.dp)
                ) { editing = CardEdit(null) }
            }
        }

        editing?.let { e ->
            CardEditorSheet(
                card = e.card,
                defaultLook = Looks.all[cards.size % Looks.all.size],
                onSave = repo::saveCard,
                onDelete = e.card?.let { card ->
                    {
                        repo.deleteCard(card.id)
                        card.photo?.let(::deleteCardPhoto)
                    }
                },
                onDismiss = { editing = null },
            )
        }
    }
}

/** The card editor is open; [card] is null when adding a new one. */
private class CardEdit(val card: RewardCard?)

private const val NEARBY_LIST_SIZE = 40

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
private fun StatsRow(cards: List<RewardCard>, storeCount: Int) {
    val top = remember(cards) {
        cards.mapNotNull { c -> c.topCategory()?.let { c to it.second } }.maxByOrNull { it.second }
    }
    Row(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile(cards.size.toString(), "cards", Modifier.weight(1f))
        StatTile(storeCount.toString(), "stores nearby", Modifier.weight(1f))
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

private fun LazyListScope.cardsSection(
    cards: List<RewardCard>,
    nearStores: List<NearStore>,
    picks: Map<Category, Pick?>,
    onAdd: () -> Unit,
    onEdit: (RewardCard) -> Unit,
) {
    if (cards.isEmpty()) {
        item(key = "cards-empty") {
            EmptyState("💳", "No cards yet", "Add the cards in your wallet with the reward rate for each kind of spend.", "Add your first card", onAdd)
        }
        return
    }
    item(key = "wallet") { Wallet(cards, nearStores, picks, onEdit) }
    item(key = "cards-hint") { Hint(if (cards.size > 1) "Swipe for your other cards · tap a card to edit" else "Tap the card to edit it") }
}

/** Google Wallet-style carousel: one big card, the next peeking in, and tiles for the card in front. */
@Composable
private fun Wallet(cards: List<RewardCard>, nearStores: List<NearStore>, picks: Map<Category, Pick?>, onEdit: (RewardCard) -> Unit) {
    val pager = rememberPagerState { cards.size }
    Column(Modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pager,
            key = { cards[it].id },
            contentPadding = PaddingValues(start = EDGE, end = EDGE + 36.dp),
            pageSpacing = 12.dp,
            modifier = Modifier.bleed(EDGE),
        ) { page ->
            val card = cards[page]
            CardFace(
                art = card.art,
                name = card.name,
                bank = card.bank,
                network = card.network,
                photo = card.photo,
                last4 = card.last4,
                modifier = Modifier
                    .padding(top = 6.dp, bottom = 18.dp)
                    // Cards behind the front one sit slightly smaller, read in the draw phase only.
                    .graphicsLayer {
                        val away = ((pager.currentPage - page) + pager.currentPageOffsetFraction).absoluteValue.coerceAtMost(1f)
                        val scale = 1f - 0.08f * away
                        scaleX = scale
                        scaleY = scale
                        alpha = 1f - 0.15f * away
                    }
                    .clickable { onEdit(card) },
            )
        }
        val front = cards.getOrNull(pager.currentPage) ?: return@Column
        Text(
            front.displayName, color = Tokens.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        if (cards.size > 1) PageDots(cards.size, pager.currentPage)
        val tiles = remember(front, nearStores, picks) { walletTiles(front, nearStores, picks) }
        TileGrid(front, tiles)
    }
}

private val EDGE = 20.dp

/** Lets the carousel run to the screen edges past the list's side padding, so the next card can peek in. */
private fun Modifier.bleed(edge: Dp) = layout { measurable, constraints ->
    val extra = edge.roundToPx()
    val wide = constraints.copy(minWidth = constraints.maxWidth + 2 * extra, maxWidth = constraints.maxWidth + 2 * extra)
    val placeable = measurable.measure(wide)
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
}

@Composable
private fun PageDots(count: Int, current: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center) {
        repeat(count) { i ->
            val on = i == current
            Box(
                Modifier.padding(horizontal = 3.dp).size(if (on) 18.dp else 6.dp, 6.dp).clip(Tokens.Pill)
                    .background(if (on) Tokens.Violet else Tokens.Stroke)
            )
        }
    }
}

private class WalletTile(val emoji: String, val caption: String, val value: String)

/** Best category, other strong rates, and the closest store where this card is your best pick. */
private fun walletTiles(card: RewardCard, nearStores: List<NearStore>, picks: Map<Category, Pick?>): List<WalletTile> {
    val unit = if (card.points) "points" else "cash back"
    val rates = card.rates.entries.filter { it.value > 0 }.sortedByDescending { it.value }
    val best = rates.firstOrNull { it.key != Category.OTHER } ?: rates.firstOrNull()
    val others = rates.filter { it != best }.map { (cat, rate) ->
        WalletTile(cat.emoji, if (cat == Category.OTHER) "Everything else" else cat.label, "${card.reward(rate)} $unit")
    }
    val nearby = nearStores.firstOrNull { picks[it.store.category]?.card?.id == card.id }?.let {
        WalletTile("📍", "Use it nearby at", "${it.store.name} · ${distanceLabel(it.distanceM)}")
    }
    return buildList {
        best?.let { (cat, rate) -> add(WalletTile(cat.emoji, "Best for", "${if (cat == Category.OTHER) "Everything" else cat.label} ${card.reward(rate)}")) }
        others.firstOrNull()?.let(::add)
        nearby?.let(::add)
        addAll(others.drop(1))
    }.take(4)
}

@Composable
private fun TileGrid(card: RewardCard, tiles: List<WalletTile>) {
    if (tiles.isEmpty()) return
    val art = card.art
    val glow = art.glowColor()
    Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(2).forEachIndexed { row, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEachIndexed { col, tile ->
                    val index = row * 2 + col
                    val shape = Tokens.R26
                    val base = Modifier.weight(1f).height(108.dp).clip(shape)
                    val (styled, ink) = when (index) {
                        0 -> base.background(art.brush()) to (if (art.darkText) Color(0xFF2A2116) else Color.White)
                        1 -> base.background(glow.copy(alpha = 0.22f)).background(Tokens.Surface.copy(alpha = 0.5f)) to Tokens.Text
                        else -> base.background(Tokens.Surface2).border(1.dp, Tokens.Stroke, shape) to Tokens.Text
                    }
                    Column(styled.padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        Box(Modifier.size(38.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
                            Text(tile.emoji, fontSize = 18.sp)
                        }
                        Column {
                            Text(tile.caption, color = ink.copy(alpha = 0.75f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(tile.value, color = ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// ------------------------------------------------------------------ nearby

private fun LazyListScope.nearbySection(
    stores: List<NearStore>,
    picks: Map<Category, Pick?>,
    setup: List<SetupItem>,
    updatedAtMs: Long?,
    busy: Boolean,
    status: NearbyStatus?,
    onFix: (SetupStep) -> Unit,
    onRefresh: () -> Unit,
    onTest: (Store) -> Unit,
) {
    if (setup.any { !it.done }) item(key = "setup") { SetupPanel(setup, onFix) }
    item(key = "nearby-status") { NearbyStatusRow(stores.size, updatedAtMs, busy, status, onRefresh) }
    if (stores.isEmpty()) {
        if (!busy) {
            item(key = "nearby-empty") {
                if (setup.any { it.step == SetupStep.LOCATION && !it.done }) {
                    EmptyState("📍", "Where are you?", "Allow location and CardNudge finds the stores around you on its own.", "Allow location") { onFix(SetupStep.LOCATION) }
                } else {
                    EmptyState("🧭", "No stores yet", "Nothing found around you so far. Try again from somewhere with shops nearby.", "Look again", onRefresh)
                }
            }
        }
    } else {
        items(stores, key = { it.store.id }) { near ->
            StoreRow(near, picks[near.store.category], onTest = { onTest(near.store) })
        }
    }
    item(key = "osm-credit") { Hint(Osm.ATTRIBUTION) }
}

@Composable
private fun NearbyStatusRow(count: Int, updatedAtMs: Long?, busy: Boolean, status: NearbyStatus?, onRefresh: () -> Unit) {
    val (text, color) = when {
        busy -> "Looking for stores near you…" to Tokens.Muted
        status == NearbyStatus.OFFLINE -> "Couldn't reach OpenStreetMap. Showing saved stores." to Tokens.Amber
        status == NearbyStatus.NO_LOCATION && count == 0 -> "Couldn't get your location yet." to Tokens.Amber
        updatedAtMs != null -> "Closest $count stores · updated ${ago(updatedAtMs)}" to Tokens.Muted
        else -> "Stores appear here once CardNudge knows where you are." to Tokens.Muted
    }
    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (!busy) GhostButton("Refresh", Tokens.Cyan, onClick = onRefresh)
    }
}

private fun ago(thenMs: Long): String {
    val minutes = ((currentTimeMillis() - thenMs) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 48 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} days ago"
    }
}

@Composable
private fun SetupPanel(items: List<SetupItem>, onFix: (SetupStep) -> Unit) {
    Column(
        Modifier.padding(bottom = 16.dp).fillMaxWidth().clip(Tokens.R22)
            .background(Brush.linearGradient(listOf(Tokens.Violet.copy(alpha = 0.16f), Tokens.Cyan.copy(alpha = 0.10f))))
            .border(1.dp, Tokens.Violet.copy(alpha = 0.35f), Tokens.R22)
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Text("⚡ Turn on store alerts", color = Tokens.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(
            "These let CardNudge notice the store you're in and tell you which card to use.",
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
private fun StoreRow(near: NearStore, pick: Pick?, onTest: () -> Unit) {
    val store = near.store
    Row(
        Modifier.padding(bottom = 12.dp).fillMaxWidth().clip(Tokens.R20).background(Tokens.Surface)
            .border(1.dp, Tokens.Stroke, Tokens.R20)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(50.dp).clip(CircleShape)
                .background(pick?.card?.art?.brush() ?: Tokens.AccentDiag),
            contentAlignment = Alignment.Center,
        ) { Text(store.category.emoji, fontSize = 22.sp) }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(store.name, color = Tokens.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${store.category.label} · ${distanceLabel(near.distanceM)}", color = Tokens.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            Text(
                pick?.let { "Use ${it.card.name} · ${it.card.reward(it.rate)}" } ?: "Add a card to get a pick",
                color = Tokens.Cyan, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 5.dp),
            )
        }
        GhostButton("Test", onClick = onTest)
    }
}

/** US units: feet up close, miles further out. */
private fun distanceLabel(metres: Int): String {
    val feet = metres * 3.28084
    return if (feet < 1000) "${(feet / 10).roundToInt() * 10} ft" else "${formatDecimal(metres / 1609.344, 1)} mi"
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
