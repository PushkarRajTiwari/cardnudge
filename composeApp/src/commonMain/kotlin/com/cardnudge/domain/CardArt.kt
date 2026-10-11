package com.cardnudge.domain

/** How a card face is drawn: metal gets a moving-light sheen, matte stays flat. */
enum class Finish { GLOSS, METAL, MATTE }

/**
 * One colour variant of a card face, e.g. Amex Gold in "Gold" or "Rose Gold".
 * Colours are ARGB longs so the domain layer stays free of UI types.
 */
data class CardArt(
    val id: String,
    val label: String,
    val colors: List<Long>,
    val finish: Finish = Finish.GLOSS,
    /** Use dark text on light faces such as gold or platinum. */
    val darkText: Boolean = false,
)

/** Generic looks for cards that aren't in the catalog. Ids stay stable because they are saved. */
object Looks {
    val Aurora = CardArt("look.aurora", "Aurora", listOf(0xFF7C5CFF, 0xFF00D4FF))
    val Orchid = CardArt("look.orchid", "Orchid", listOf(0xFFFF4FD8, 0xFF7C5CFF))
    val Sunset = CardArt("look.sunset", "Sunset", listOf(0xFFFF8A3D, 0xFFFF3D7F))
    val Lagoon = CardArt("look.lagoon", "Lagoon", listOf(0xFF00C9A7, 0xFF0072FF))
    val Graphite = CardArt("look.graphite", "Graphite", listOf(0xFF2B2B3F, 0xFF4A4A6A), Finish.MATTE)
    val Gold = CardArt("look.gold", "Gold", listOf(0xFFF6D98B, 0xFFC9A227, 0xFF9C7A1C), Finish.METAL, darkText = true)
    val RoseGold = CardArt("look.rosegold", "Rose Gold", listOf(0xFFF7CDBF, 0xFFE0A291, 0xFFB76E79), Finish.METAL, darkText = true)
    val Platinum = CardArt("look.platinum", "Platinum", listOf(0xFFF2F3F5, 0xFFC9CDD3, 0xFF9AA0A8), Finish.METAL, darkText = true)
    val BlackMetal = CardArt("look.black", "Black metal", listOf(0xFF2A2A2E, 0xFF0D0D10, 0xFF1F1F24), Finish.METAL)

    val all = listOf(Aurora, Orchid, Sunset, Lagoon, Graphite, Gold, RoseGold, Platinum, BlackMetal)

    /** Older saves stored a palette index instead of an art id. */
    fun fromLegacyPalette(index: Int): CardArt = all.getOrElse(index) { Aurora }
}
