package com.cardnudge

import com.cardnudge.data.KeyValueStore
import com.cardnudge.data.Repository
import com.cardnudge.domain.Category
import com.cardnudge.domain.Store
import com.cardnudge.domain.Recommender
import com.cardnudge.domain.RewardCard
import com.cardnudge.domain.formatDecimal
import com.cardnudge.domain.formatRate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RecommenderTest {
    private val millennia = RewardCard("m", "Millennia", "HDFC", 0, mapOf(Category.DINING to 5.0, Category.OTHER to 1.0))
    private val cashback = RewardCard("c", "Cashback", "SBI", 1, mapOf(Category.GROCERY to 5.0, Category.OTHER to 1.0))
    private val travel = RewardCard("t", "Travel", "Axis", 2, mapOf(Category.TRAVEL to 4.0))

    @Test fun picksHighestRateWithRunnerUp() {
        val pick = assertNotNull(Recommender.best(listOf(millennia, cashback, travel), Category.GROCERY))
        assertEquals("c", pick.card.id)
        assertEquals(5.0, pick.rate)
        assertEquals("m", pick.runnerUp?.id)
        assertEquals(1.0, pick.runnerUpRate)
    }

    @Test fun fallsBackToEverythingElseRate() {
        assertEquals(1.0, millennia.rateFor(Category.FUEL))
        assertEquals(0.0, travel.rateFor(Category.FUEL))
    }

    @Test fun noCardsMeansNoPick() = assertNull(Recommender.best(emptyList(), Category.DINING))

    @Test fun alertNamesBestCard() {
        val store = Store("n1", "Truffles", Category.DINING, 0.0, 0.0)
        val alert = Recommender.arrivalAlert(store, listOf(millennia, cashback))
        assertEquals("🍽️ Use Millennia at Truffles", alert.title)
        assertEquals("5% on Dining. Next best: Cashback at 1%.", alert.body)
    }

    @Test fun formatsNumbers() {
        assertEquals("5%", formatRate(5.0))
        assertEquals("1.5%", formatRate(1.5))
        assertEquals("0.25%", formatRate(0.25))
        assertEquals("10%", formatRate(10.0))
        assertEquals("12.97160", formatDecimal(12.9716, 5))
        assertEquals("-0.50", formatDecimal(-0.5, 2))
    }

    @Test fun repositoryPersistsCards() {
        val store = MemoryStore()
        Repository(store).saveCard(millennia)
        assertEquals(listOf(millennia), Repository(store).cards.value)
    }

    class MemoryStore : KeyValueStore {
        private val strings = mutableMapOf<String, String>()
        private val longs = mutableMapOf<String, Long>()
        override fun getString(key: String) = strings[key]
        override fun putString(key: String, value: String) { strings[key] = value }
        override fun getLong(key: String, default: Long) = longs[key] ?: default
        override fun putLong(key: String, value: Long) { longs[key] = value }
    }
}

class CatalogTest {
    @Test fun searchFindsAmexGoldByNickname() {
        val hit = com.cardnudge.domain.CardCatalog.search("amex gold").first()
        assertEquals("amex-gold", hit.id)
        assertEquals(listOf("Gold", "Rose Gold", "White Gold"), hit.variants.map { it.label })
    }

    @Test fun artIdsAreUniqueAndResolve() {
        val ids = com.cardnudge.domain.CardCatalog.cards.flatMap { c -> c.variants.map { it.id } }
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { assertNotNull(com.cardnudge.domain.CardCatalog.art(it)) }
    }

    @Test fun pointsCardsShowMultiplier() {
        val card = RewardCard("g", "Gold Card", "Amex", rates = mapOf(Category.DINING to 4.0), points = true)
        assertEquals("4x", card.reward(4.0))
        assertEquals("1.5x", card.reward(1.5))
    }

    @Test fun oldSavesStillLoad() {
        val json = """[{"id":"a","name":"Old","bank":"X","palette":2,"rates":{"DINING":3.0}}]"""
        val store = object : KeyValueStore {
            override fun getString(key: String) = if (key == "cards") json else null
            override fun putString(key: String, value: String) {}
            override fun getLong(key: String, default: Long) = default
            override fun putLong(key: String, value: Long) {}
        }
        val card = Repository(store).cards.value.single()
        assertEquals("look.sunset", card.art.id)
        assertEquals(false, card.points)
    }
}
