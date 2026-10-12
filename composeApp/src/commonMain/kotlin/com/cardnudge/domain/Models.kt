package com.cardnudge.domain

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
enum class Category(val label: String, val emoji: String) {
    GROCERY("Groceries", "🛒"),
    DINING("Dining", "🍽️"),
    FUEL("Fuel", "⛽"),
    SHOPPING("Shopping", "🛍️"),
    TRAVEL("Travel", "✈️"),
    ENTERTAINMENT("Entertainment", "🎬"),
    PHARMACY("Pharmacy", "💊"),
    OTHER("Everything else", "💳"),
}

@Serializable
data class RewardCard(
    val id: String,
    val name: String,
    val bank: String,
    /** Legacy colour index from before [artId]; still read for old saves. */
    val palette: Int = 0,
    /** Earn rate per \$1 per category. A missing category falls back to [Category.OTHER]. */
    val rates: Map<Category, Double>,
    /** True when [rates] are points (shown as "4x"), false for cash back ("4%"). */
    val points: Boolean = false,
    /** Which [CardCatalog] card this came from, if any. */
    val catalogId: String? = null,
    /** Chosen face, a [CardArt] id from the catalog or [Looks]. */
    val artId: String? = null,
    /** File name of the user's own photo of the card, stored in app-private storage. */
    val photo: String? = null,
    /** Last 4 digits, shown only to tell cards apart; never the full number. */
    val last4: String? = null,
) {
    val art: CardArt get() = CardCatalog.art(artId) ?: Looks.fromLegacyPalette(palette)
    val network: Network? get() = CardCatalog.card(catalogId)?.network

    fun reward(rate: Double): String = formatReward(rate, points)

    /** "Gold Card ••1008", or just the name when no digits were entered. */
    val displayName: String get() = if (last4.isNullOrBlank()) name else "$name ••$last4"

    fun rateFor(category: Category): Double = rates[category] ?: rates[Category.OTHER] ?: 0.0

    fun topCategory(): Pair<Category, Double>? {
        var best: Category? = null
        var bestRate = 0.0
        for ((cat, rate) in rates) if (rate > bestRate) { best = cat; bestRate = rate }
        return best?.let { it to bestRate }
    }
}

/** A shop, restaurant or other place to pay, as found on OpenStreetMap. */
@Serializable
data class Store(
    /** OSM element id such as "n123" or "w456"; stable across refreshes. */
    val id: String,
    val name: String,
    val category: Category,
    val lat: Double,
    val lng: Double,
)

/** The stores fetched around one spot, kept so alerts work offline and in the background. */
@Serializable
data class NearbyArea(
    val lat: Double,
    val lng: Double,
    val fetchedAtMs: Long,
    val stores: List<Store>,
)

data class GeoPoint(val lat: Double, val lng: Double, val accuracyM: Float)

data class Pick(val card: RewardCard, val rate: Double, val runnerUp: RewardCard?, val runnerUpRate: Double)

/** A ready-to-show notification; each platform only decides how to display it. */
data class Alert(val id: String, val title: String, val body: String)

@OptIn(ExperimentalUuidApi::class)
fun newId(): String = Uuid.random().toString()

/** "4x" for points, "4%" for cash back. */
fun formatReward(rate: Double, points: Boolean): String =
    if (points) formatRate(rate).removeSuffix("%") + "x" else formatRate(rate)

/** 5.0 -> "5%", 1.5 -> "1.5%", 0.25 -> "0.25%". */
fun formatRate(rate: Double): String = formatDecimal(rate, 2).trimEnd('0').trimEnd('.') + "%"

/** Fixed-decimal formatting without String.format, which common Kotlin lacks. */
fun formatDecimal(value: Double, decimals: Int): String {
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    val scaled = (abs(value) * factor).roundToLong()
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    val whole = scaled / factor
    if (decimals == 0) return "$sign$whole"
    val frac = (scaled % factor).toString().padStart(decimals, '0')
    return "$sign$whole.$frac"
}
