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
    val palette: Int,
    /** Reward rate in percent per category. A missing category falls back to [Category.OTHER]. */
    val rates: Map<Category, Double>,
) {
    fun rateFor(category: Category): Double = rates[category] ?: rates[Category.OTHER] ?: 0.0

    fun topCategory(): Pair<Category, Double>? {
        var best: Category? = null
        var bestRate = 0.0
        for ((cat, rate) in rates) if (rate > bestRate) { best = cat; bestRate = rate }
        return best?.let { it to bestRate }
    }
}

@Serializable
data class Place(
    val id: String,
    val name: String,
    val category: Category,
    val lat: Double,
    val lng: Double,
    val radiusM: Int,
)

data class GeoPoint(val lat: Double, val lng: Double, val accuracyM: Float)

data class Pick(val card: RewardCard, val rate: Double, val runnerUp: RewardCard?, val runnerUpRate: Double)

/** A ready-to-show notification; each platform only decides how to display it. */
data class Alert(val id: String, val title: String, val body: String)

@OptIn(ExperimentalUuidApi::class)
fun newId(): String = Uuid.random().toString()

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
