package com.cardnudge.domain

import com.cardnudge.domain.Category.DINING
import com.cardnudge.domain.Category.ENTERTAINMENT
import com.cardnudge.domain.Category.FUEL
import com.cardnudge.domain.Category.GROCERY
import com.cardnudge.domain.Category.OTHER
import com.cardnudge.domain.Category.PHARMACY
import com.cardnudge.domain.Category.SHOPPING
import com.cardnudge.domain.Category.TRAVEL

enum class Network(val label: String) { AMEX("AMERICAN EXPRESS"), VISA("VISA"), MASTERCARD("MASTERCARD"), DISCOVER("DISCOVER") }

/**
 * A known card. [rates] are base earn rates per $1, simplified to our categories:
 * points (x) for points cards, percent for cash back. Users can edit them after adding.
 */
data class CatalogCard(
    val id: String,
    val bank: String,
    val name: String,
    val network: Network,
    val points: Boolean,
    val rates: Map<Category, Double>,
    val variants: List<CardArt>,
    val note: String? = null,
) {
    val fullName get() = "$bank $name"

    internal val searchText: String = buildString {
        append("$bank $name ${network.label}".lowercase())
        if (network == Network.AMEX) append(" amex")
    }
}

private fun metal(id: String, label: String, vararg c: Long, dark: Boolean = false) = CardArt(id, label, c.toList(), Finish.METAL, dark)
private fun gloss(id: String, label: String, vararg c: Long, dark: Boolean = false) = CardArt(id, label, c.toList(), Finish.GLOSS, dark)
private fun matte(id: String, label: String, vararg c: Long) = CardArt(id, label, c.toList(), Finish.MATTE)

/** Popular US cards. Earn rates as published in 2025, simplified; check your card's terms. */
object CardCatalog {
    val cards: List<CatalogCard> = listOf(
        CatalogCard(
            "amex-gold", "American Express", "Gold Card", Network.AMEX, points = true,
            rates = mapOf(DINING to 4.0, GROCERY to 4.0, TRAVEL to 3.0, OTHER to 1.0),
            variants = listOf(
                metal("amex-gold.gold", "Gold", 0xFFF1D894, 0xFFCFA84E, 0xFF9C7A2A, dark = true),
                metal("amex-gold.rose", "Rose Gold", 0xFFF6CFC2, 0xFFDB9A8C, 0xFFB06A70, dark = true),
                metal("amex-gold.white", "White Gold", 0xFFF8F4EA, 0xFFE6DAC0, 0xFFC9B68E, dark = true),
            ),
            note = "4x at US supermarkets up to \$25k a year; 3x on flights booked direct.",
        ),
        CatalogCard(
            "amex-platinum", "American Express", "Platinum Card", Network.AMEX, points = true,
            rates = mapOf(TRAVEL to 5.0, OTHER to 1.0),
            variants = listOf(metal("amex-platinum.platinum", "Platinum", 0xFFF0F2F5, 0xFFC5CAD1, 0xFF8E959E, dark = true)),
            note = "5x on flights and prepaid hotels booked direct or through Amex Travel.",
        ),
        CatalogCard(
            "amex-green", "American Express", "Green Card", Network.AMEX, points = true,
            rates = mapOf(DINING to 3.0, TRAVEL to 3.0, OTHER to 1.0),
            variants = listOf(gloss("amex-green.green", "Green", 0xFF3C9A6E, 0xFF1B5A3D)),
        ),
        CatalogCard(
            "amex-bcp", "American Express", "Blue Cash Preferred", Network.AMEX, points = false,
            rates = mapOf(GROCERY to 6.0, ENTERTAINMENT to 6.0, FUEL to 3.0, OTHER to 1.0),
            variants = listOf(gloss("amex-bcp.blue", "Blue", 0xFF3D8BE0, 0xFF173F8F)),
            note = "6% at US supermarkets up to \$6k a year and on select US streaming.",
        ),
        CatalogCard(
            "amex-bce", "American Express", "Blue Cash Everyday", Network.AMEX, points = false,
            rates = mapOf(GROCERY to 3.0, FUEL to 3.0, SHOPPING to 3.0, OTHER to 1.0),
            variants = listOf(gloss("amex-bce.blue", "Light Blue", 0xFF6CCBF0, 0xFF2580C6)),
            note = "3% categories each capped at \$6k a year; shopping means US online retail.",
        ),
        CatalogCard(
            "chase-csp", "Chase", "Sapphire Preferred", Network.VISA, points = true,
            rates = mapOf(DINING to 3.0, TRAVEL to 2.0, ENTERTAINMENT to 3.0, OTHER to 1.0),
            variants = listOf(metal("chase-csp.blue", "Sapphire Blue", 0xFF2B63B8, 0xFF143A7A, 0xFF0C2453)),
            note = "5x on travel booked through Chase Travel; 3x on select streaming.",
        ),
        CatalogCard(
            "chase-csr", "Chase", "Sapphire Reserve", Network.VISA, points = true,
            rates = mapOf(DINING to 3.0, TRAVEL to 4.0, OTHER to 1.0),
            variants = listOf(metal("chase-csr.navy", "Navy", 0xFF26385A, 0xFF121D33, 0xFF0A1222)),
            note = "4x on flights and hotels booked direct; 8x through Chase Travel.",
        ),
        CatalogCard(
            "chase-cfu", "Chase", "Freedom Unlimited", Network.VISA, points = true,
            rates = mapOf(DINING to 3.0, PHARMACY to 3.0, OTHER to 1.5),
            variants = listOf(gloss("chase-cfu.blue", "Blue", 0xFF3A7BC8, 0xFF1C4C8C)),
        ),
        CatalogCard(
            "chase-cff", "Chase", "Freedom Flex", Network.MASTERCARD, points = true,
            rates = mapOf(DINING to 3.0, PHARMACY to 3.0, OTHER to 1.0),
            variants = listOf(gloss("chase-cff.blue", "Blue", 0xFF4A7FE0, 0xFF1F3F91)),
            note = "Plus 5x in quarterly rotating categories after activation.",
        ),
        CatalogCard(
            "capone-venturex", "Capital One", "Venture X", Network.VISA, points = true,
            rates = mapOf(TRAVEL to 2.0, OTHER to 2.0),
            variants = listOf(metal("capone-venturex.slate", "Slate", 0xFF3A3F47, 0xFF1D2025, 0xFF121417)),
            note = "10x hotels and cars, 5x flights through Capital One Travel.",
        ),
        CatalogCard(
            "capone-venture", "Capital One", "Venture", Network.VISA, points = true,
            rates = mapOf(OTHER to 2.0),
            variants = listOf(gloss("capone-venture.navy", "Navy", 0xFF44597A, 0xFF1E2B3F)),
        ),
        CatalogCard(
            "capone-savor", "Capital One", "Savor", Network.MASTERCARD, points = false,
            rates = mapOf(DINING to 3.0, ENTERTAINMENT to 3.0, GROCERY to 3.0, OTHER to 1.0),
            variants = listOf(metal("capone-savor.copper", "Copper", 0xFF8A4B3A, 0xFF5B2C24, 0xFF2E1614)),
        ),
        CatalogCard(
            "citi-doublecash", "Citi", "Double Cash", Network.MASTERCARD, points = false,
            rates = mapOf(OTHER to 2.0),
            variants = listOf(gloss("citi-doublecash.teal", "Teal", 0xFF2A8FC1, 0xFF0F4B70)),
        ),
        CatalogCard(
            "citi-customcash", "Citi", "Custom Cash", Network.MASTERCARD, points = false,
            rates = mapOf(OTHER to 1.0),
            variants = listOf(gloss("citi-customcash.mint", "Mint", 0xFF3BC4AE, 0xFF0F6E78)),
            note = "5% on your top eligible category each month up to \$500. Set that category to 5 yourself.",
        ),
        CatalogCard(
            "citi-strata-premier", "Citi", "Strata Premier", Network.MASTERCARD, points = true,
            rates = mapOf(DINING to 3.0, GROCERY to 3.0, FUEL to 3.0, TRAVEL to 3.0, OTHER to 1.0),
            variants = listOf(metal("citi-strata-premier.steel", "Steel", 0xFF4D6580, 0xFF27384A, 0xFF16212D)),
        ),
        CatalogCard(
            "wf-activecash", "Wells Fargo", "Active Cash", Network.VISA, points = false,
            rates = mapOf(OTHER to 2.0),
            variants = listOf(gloss("wf-activecash.red", "Red", 0xFFE0303A, 0xFF8E0F15)),
        ),
        CatalogCard(
            "wf-autograph", "Wells Fargo", "Autograph", Network.VISA, points = true,
            rates = mapOf(DINING to 3.0, TRAVEL to 3.0, FUEL to 3.0, ENTERTAINMENT to 3.0, OTHER to 1.0),
            variants = listOf(matte("wf-autograph.black", "Black", 0xFF2C2C2E, 0xFF151516)),
        ),
        CatalogCard(
            "discover-it", "Discover", "it Cash Back", Network.DISCOVER, points = false,
            rates = mapOf(OTHER to 1.0),
            variants = listOf(
                gloss("discover-it.orange", "Orange", 0xFFF7953A, 0xFFC4561A),
                gloss("discover-it.ocean", "Ocean", 0xFF2A9AD6, 0xFF0D4E7A),
                matte("discover-it.black", "Black", 0xFF2A2A2D, 0xFF0F0F11),
            ),
            note = "Plus 5% in quarterly rotating categories after activation.",
        ),
        CatalogCard(
            "boa-customized", "Bank of America", "Customized Cash", Network.VISA, points = false,
            rates = mapOf(SHOPPING to 3.0, GROCERY to 2.0, OTHER to 1.0),
            variants = listOf(gloss("boa-customized.red", "Red", 0xFFD4243F, 0xFF7A0A1C)),
            note = "3% category is your choice (online shopping by default); 3% and 2% share a \$2,500 quarterly cap.",
        ),
        CatalogCard(
            "apple-card", "Apple", "Card", Network.MASTERCARD, points = false,
            rates = mapOf(SHOPPING to 2.0, OTHER to 2.0),
            variants = listOf(metal("apple-card.titanium", "Titanium", 0xFFFAFAFB, 0xFFDCDEE2, 0xFFBEC1C7, dark = true)),
            note = "2% when paying with Apple Pay, 1% with the physical card, 3% at select merchants.",
        ),
        CatalogCard(
            "chase-prime", "Chase", "Prime Visa", Network.VISA, points = false,
            rates = mapOf(SHOPPING to 5.0, DINING to 2.0, FUEL to 2.0, OTHER to 1.0),
            variants = listOf(gloss("chase-prime.ink", "Ink", 0xFF34475E, 0xFF1A2533)),
            note = "5% applies at Amazon and Whole Foods.",
        ),
        CatalogCard(
            "citi-costco", "Citi", "Costco Anywhere Visa", Network.VISA, points = false,
            rates = mapOf(FUEL to 4.0, DINING to 3.0, TRAVEL to 3.0, OTHER to 1.0),
            variants = listOf(matte("citi-costco.charcoal", "Charcoal", 0xFF3A3A3C, 0xFF1C1C1E)),
            note = "2% at Costco. Gas is 4% up to \$7k a year.",
        ),
        CatalogCard(
            "bilt", "Bilt", "Mastercard", Network.MASTERCARD, points = true,
            rates = mapOf(DINING to 3.0, TRAVEL to 2.0, OTHER to 1.0),
            variants = listOf(metal("bilt.black", "Black", 0xFF2A2A2A, 0xFF111111, 0xFF1E1E1E)),
            note = "Also earns on rent with no fee.",
        ),
        CatalogCard(
            "usb-altitude-go", "U.S. Bank", "Altitude Go", Network.VISA, points = true,
            rates = mapOf(DINING to 4.0, GROCERY to 2.0, FUEL to 2.0, ENTERTAINMENT to 2.0, OTHER to 1.0),
            variants = listOf(gloss("usb-altitude-go.violet", "Violet", 0xFF5A4A9C, 0xFF261D4A)),
        ),
    )

    private val byId = cards.associateBy { it.id }
    private val artById: Map<String, CardArt> = (cards.flatMap { it.variants } + Looks.all).associateBy { it.id }

    fun card(id: String?): CatalogCard? = id?.let(byId::get)
    fun art(id: String?): CardArt? = id?.let(artById::get)

    /** Case-insensitive match on bank and card name; every word must match. */
    fun search(query: String, limit: Int = 8): List<CatalogCard> {
        val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return cards.take(limit)
        return cards.asSequence()
            .filter { c -> words.all { it in c.searchText } }
            .take(limit)
            .toList()
    }
}
