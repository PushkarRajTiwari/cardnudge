package com.cardnudge

import com.cardnudge.data.LocationBackend
import com.cardnudge.data.Nearby
import com.cardnudge.data.NearbyStatus
import com.cardnudge.data.Repository
import com.cardnudge.domain.Category
import com.cardnudge.domain.GeoPoint
import com.cardnudge.domain.Geo
import com.cardnudge.domain.Osm
import com.cardnudge.domain.RewardCard
import com.cardnudge.domain.Store
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Trimmed real-shape Overpass output: a node, a way with a centre, and things to skip. */
private const val OVERPASS = """
{"version":0.6,"elements":[
 {"type":"node","id":101,"lat":40.7506,"lon":-73.9935,"tags":{"name":"Trader Joe's","shop":"supermarket"}},
 {"type":"way","id":202,"center":{"lat":40.7510,"lon":-73.9900},"nodes":[1,2,3],"tags":{"name":"Target","shop":"department_store"}},
 {"type":"node","id":303,"lat":40.7490,"lon":-73.9950,"tags":{"name":"CVS Pharmacy","amenity":"pharmacy","shop":"chemist"}},
 {"type":"node","id":404,"lat":40.7520,"lon":-73.9940,"tags":{"name":"Shake Shack","amenity":"fast_food"}},
 {"type":"node","id":505,"lat":40.7480,"lon":-73.9960,"tags":{"brand":"Shell","amenity":"fuel"}},
 {"type":"node","id":606,"lat":40.7500,"lon":-73.9930,"tags":{"shop":"convenience"}},
 {"type":"node","id":707,"lat":40.7500,"lon":-73.9930,"tags":{"name":"Empty unit","shop":"vacant"}},
 {"type":"node","id":808,"lat":40.7500,"lon":-73.9930,"tags":{"name":"Bench","amenity":"bench"}}
]}
"""

class OsmTest {
    @Test fun parsesStoresAndCategories() {
        val stores = Osm.parse(OVERPASS).associateBy { it.id }
        assertEquals(setOf("n101", "w202", "n303", "n404", "n505"), stores.keys)
        assertEquals(Category.GROCERY, stores.getValue("n101").category)
        assertEquals(Category.SHOPPING, stores.getValue("w202").category)
        assertEquals(40.7510, stores.getValue("w202").lat)
        assertEquals(Category.PHARMACY, stores.getValue("n303").category)
        assertEquals(Category.DINING, stores.getValue("n404").category)
        assertEquals("Shell", stores.getValue("n505").name)
        assertEquals(Category.FUEL, stores.getValue("n505").category)
    }

    @Test fun badResponsesParseToNothingOrFail() {
        assertEquals(emptyList(), Osm.parse("""{"elements":[]}"""))
        assertEquals(emptyList(), Osm.parse("""{"remark":"timeout"}"""))
    }

    @Test fun queryAndEncoding() {
        val q = Osm.query(40.75, -73.99, 1200)
        assertTrue("(around:1200,40.750000,-73.990000)" in q, q)
        assertTrue(q.startsWith("[out:json]") && q.endsWith("out center;"))
        assertEquals("a+b%3D%22%C3%A9%22", Osm.formEncode("a b=\"é\""))
    }

    @Test fun distances() {
        // Times Square to Empire State Building is about 1.1 km.
        val d = Geo.distanceM(40.7580, -73.9855, 40.7484, -73.9857)
        assertTrue(d in 1000.0..1150.0, "$d")
    }
}

class NearbyTest {
    private val here = GeoPoint(40.7505, -73.9934, 10f)
    private val amexGold = RewardCard("g", "Gold Card", "Amex", rates = mapOf(Category.GROCERY to 4.0, Category.DINING to 4.0, Category.OTHER to 1.0), points = true)

    private class FakeBackend(var location: GeoPoint?, override val maxWatched: Int = 3) : LocationBackend {
        var watched: List<Store> = emptyList()
        var exitRadius = 0.0
        var watchCalls = 0
        override fun canWatch() = true
        override suspend fun currentLocation() = location
        override fun watch(stores: List<Store>, center: GeoPoint, exitRadiusM: Double) {
            watched = stores; exitRadius = exitRadiusM; watchCalls++
        }
    }

    private fun setup(response: String? = OVERPASS): Triple<Nearby, RecommenderTest.MemoryStore, () -> Int> {
        val kv = RecommenderTest.MemoryStore()
        val repo = Repository(kv).also { it.saveCard(amexGold) }
        var fetches = 0
        val nearby = Nearby(kv, repo) { fetches++; response }
        return Triple(nearby, kv, { fetches })
    }

    @Test fun fetchesWatchesClosestAndReusesCache() = runTest {
        val (nearby, _, fetches) = setup()
        val backend = FakeBackend(here)
        assertEquals(NearbyStatus.OK, nearby.update(backend, nowMs = 0))
        assertEquals(1, fetches())
        // One slot is kept for the area-exit circle.
        assertEquals(listOf("n101", "n404"), backend.watched.map { it.id })
        assertTrue(backend.exitRadius in 200.0..800.0)

        // A short walk later: saved stores still cover it, so no new download.
        backend.location = GeoPoint(40.7530, -73.9934, 10f)
        assertEquals(NearbyStatus.OK, nearby.update(backend, nowMs = 60_000))
        assertEquals(1, fetches())
        assertEquals(2, backend.watchCalls)

        // Far away: fetch again.
        backend.location = GeoPoint(40.7800, -73.9700, 10f)
        nearby.update(backend, nowMs = 120_000)
        assertEquals(2, fetches())
    }

    @Test fun offlineKeepsSavedStores() = runTest {
        val (first, kv, _) = setup()
        first.update(FakeBackend(here), nowMs = 0)

        val repo = Repository(kv)
        val offline = Nearby(kv, repo) { null }
        assertEquals(5, offline.area.value?.stores?.size, "saved stores load after restart")
        val backend = FakeBackend(here)
        assertEquals(NearbyStatus.OFFLINE, offline.update(backend, nowMs = 1_000, force = true))
        assertEquals(2, backend.watched.size, "still watches saved stores")
    }

    @Test fun noLocationUsesFallbackOrReports() = runTest {
        val (nearby, _, _) = setup()
        assertEquals(NearbyStatus.NO_LOCATION, nearby.update(FakeBackend(null), nowMs = 0))
        assertEquals(NearbyStatus.OK, nearby.update(FakeBackend(null), nowMs = 0, fallback = here))
    }

    @Test fun dwellAlertPicksClosestStoreAndThrottles() = runTest {
        val (nearby, _, _) = setup()
        nearby.update(FakeBackend(here), nowMs = 0)

        val inTraderJoes = GeoPoint(40.7506, -73.9935, 5f)
        val alert = assertNotNull(nearby.dwellAlert(listOf("n101", "w202"), inTraderJoes, nowMs = 1_000_000))
        assertEquals("🛒 Use Gold Card at Trader Joe's", alert.title)
        assertEquals("4x on Groceries.", alert.body)

        assertNull(nearby.dwellAlert(listOf("n404"), null, nowMs = 1_000_000 + 60_000), "too soon after any alert")
        assertNotNull(nearby.dwellAlert(listOf("n404"), null, nowMs = 1_000_000 + 6 * 60_000))
        assertNull(nearby.dwellAlert(listOf("n101"), null, nowMs = 1_000_000 + 60 * 60_000), "same store within hours")
        assertNull(nearby.dwellAlert(listOf(Nearby.EXIT_ID), null, nowMs = 1_000_000 + 24 * 60 * 60_000))
    }
}
