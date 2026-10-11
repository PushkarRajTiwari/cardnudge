package com.cardnudge.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Great-circle distance in metres. */
    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = (lat2 - lat1) * PI / 180
        val dLng = (lng2 - lng1) * PI / 180
        val a = sin(dLat / 2).let { it * it } +
            cos(lat1 * PI / 180) * cos(lat2 * PI / 180) * sin(dLng / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    fun distanceM(store: Store, lat: Double, lng: Double) = distanceM(store.lat, store.lng, lat, lng)

    /** The [limit] stores closest to a point, closest first. */
    fun nearest(stores: List<Store>, lat: Double, lng: Double, limit: Int): List<Store> =
        stores.sortedBy { distanceM(it, lat, lng) }.take(limit)
}

/**
 * Store lookup through the public Overpass API, which serves OpenStreetMap data.
 * Data is © OpenStreetMap contributors (ODbL), so the app shows that credit.
 */
object Osm {
    const val ENDPOINT = "https://overpass-api.de/api/interpreter"
    const val USER_AGENT = "CardNudge/0.3 (personal Android/iOS app; github.com/PushkarRajTiwari/cardnudge)"
    const val ATTRIBUTION = "Store data © OpenStreetMap contributors"

    private val json = Json { ignoreUnknownKeys = true }

    private val dining = setOf("restaurant", "cafe", "fast_food", "bar", "pub", "ice_cream", "food_court", "biergarten")
    private val entertainmentAmenity = setOf("cinema", "theatre", "arts_centre", "nightclub")
    private val grocery = setOf(
        "supermarket", "grocery", "convenience", "greengrocer", "butcher", "bakery", "deli",
        "health_food", "seafood", "cheese", "frozen_food", "farm",
    )
    private val travel = setOf("hotel", "motel", "hostel", "guest_house")
    private val entertainmentLeisure = setOf("bowling_alley", "amusement_arcade", "escape_game", "water_park", "miniature_golf")

    /** Overpass QL for named places to pay within [radiusM] of a point, with way/relation centres. */
    fun query(lat: Double, lng: Double, radiusM: Int): String {
        val around = "(around:$radiusM,${formatDecimal(lat, 6)},${formatDecimal(lng, 6)})"
        val amenities = (dining + entertainmentAmenity + setOf("fuel", "pharmacy", "car_rental")).sorted().joinToString("|")
        return "[out:json][timeout:25];(" +
            "nwr$around[\"name\"][\"shop\"];" +
            "nwr$around[\"name\"][\"amenity\"~\"^($amenities)$\"];" +
            "nwr$around[\"name\"][\"tourism\"~\"^(${(travel + "theme_park").sorted().joinToString("|")})$\"];" +
            "nwr$around[\"name\"][\"leisure\"~\"^(${entertainmentLeisure.sorted().joinToString("|")})$\"];" +
            ");out center;"
    }

    /** Which reward category a place falls under, from its OSM tags; null if it isn't somewhere you pay. */
    fun categoryOf(tags: Map<String, String>): Category? {
        val amenity = tags["amenity"]
        val shop = tags["shop"]
        return when {
            amenity == "pharmacy" || shop == "chemist" -> Category.PHARMACY
            amenity == "fuel" -> Category.FUEL
            amenity in dining -> Category.DINING
            amenity in entertainmentAmenity -> Category.ENTERTAINMENT
            amenity == "car_rental" -> Category.TRAVEL
            shop in grocery -> Category.GROCERY
            shop == "vacant" || shop == "no" -> null
            shop != null -> Category.SHOPPING
            tags["tourism"] in travel -> Category.TRAVEL
            tags["tourism"] == "theme_park" || tags["leisure"] in entertainmentLeisure -> Category.ENTERTAINMENT
            else -> null
        }
    }

    /** Parses an Overpass JSON response. Unnamed or unrecognised places are skipped. */
    fun parse(body: String): List<Store> {
        val elements = json.parseToJsonElement(body).jsonObject["elements"]?.jsonArray ?: return emptyList()
        val out = ArrayList<Store>(elements.size)
        for (el in elements) {
            val obj = el as? JsonObject ?: continue
            val tags = (obj["tags"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() } ?: continue
            val name = tags["name"]?.takeIf { it.isNotBlank() } ?: tags["brand"] ?: continue
            val category = categoryOf(tags) ?: continue
            val point = (obj["center"] as? JsonObject) ?: obj
            val lat = point["lat"]?.jsonPrimitive?.doubleOrNull ?: continue
            val lng = point["lon"]?.jsonPrimitive?.doubleOrNull ?: continue
            val type = obj["type"]?.jsonPrimitive?.contentOrNull?.firstOrNull() ?: continue
            val id = obj["id"]?.jsonPrimitive?.longOrNull ?: continue
            out += Store("$type$id", name.trim(), category, lat, lng)
        }
        return out
    }

    /** application/x-www-form-urlencoded value encoding (UTF-8). */
    fun formEncode(value: String): String = buildString {
        for (b in value.encodeToByteArray()) {
            val c = (b.toInt() and 0xFF).toChar()
            when {
                c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-_.*" -> append(c)
                c == ' ' -> append('+')
                else -> append('%').append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
            }
        }
    }

    private const val HEX = "0123456789ABCDEF"
}
