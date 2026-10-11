package com.cardnudge.data

import com.cardnudge.domain.Alert
import com.cardnudge.domain.GeoPoint
import com.cardnudge.domain.Geo
import com.cardnudge.domain.NearbyArea
import com.cardnudge.domain.Osm
import com.cardnudge.domain.Recommender
import com.cardnudge.domain.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** What the OS provides for watching stores: Android geofences or iOS region monitoring. */
interface LocationBackend {
    /** OS cap on watched regions, including the one used to notice leaving the area. */
    val maxWatched: Int

    /** True when location can be read in the background, so alerts can work. */
    fun canWatch(): Boolean

    /** One location fix, or null if permission is missing or no fix arrived in time. */
    suspend fun currentLocation(): GeoPoint?

    /**
     * Replaces everything watched with [stores] (alert when you stay at one) plus one
     * circle of [exitRadiusM] around [center] (refresh when you leave it).
     */
    fun watch(stores: List<Store>, center: GeoPoint, exitRadiusM: Double)
}

enum class NearbyStatus { OK, NO_LOCATION, OFFLINE }

/**
 * Finds stores around you on OpenStreetMap, picks the closest ones to watch, and turns
 * "you've been at store X for a minute" into a card suggestion. Shared by both platforms
 * and by background work, so it holds no UI or platform objects.
 *
 * @param fetch runs an Overpass query and returns the response body, or null on failure.
 */
class Nearby(
    private val store: KeyValueStore,
    private val repository: Repository,
    private val fetch: suspend (query: String) -> String?,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    private val _area = MutableStateFlow(loadArea())
    /** The last stores fetched, saved so they survive restarts and work offline. */
    val area: StateFlow<NearbyArea?> = _area.asStateFlow()

    private val _here = MutableStateFlow(_area.value?.let { GeoPoint(it.lat, it.lng, 0f) })
    /** Where you were at the last update. */
    val here: StateFlow<GeoPoint?> = _here.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _status = MutableStateFlow<NearbyStatus?>(null)
    /** Outcome of the last update, or null before the first one. */
    val status: StateFlow<NearbyStatus?> = _status.asStateFlow()

    /**
     * Gets a fix, fetches stores unless the saved ones still cover this spot (or [force]),
     * then watches the closest. Safe to call from the UI and background work at once.
     */
    suspend fun update(backend: LocationBackend, nowMs: Long, force: Boolean = false, fallback: GeoPoint? = null): NearbyStatus =
        mutex.withLock {
            _busy.value = true
            try {
                run(backend, nowMs, force, fallback).also { _status.value = it }
            } finally {
                _busy.value = false
            }
        }

    private suspend fun run(backend: LocationBackend, nowMs: Long, force: Boolean, fallback: GeoPoint?): NearbyStatus {
        val at = backend.currentLocation() ?: fallback ?: return NearbyStatus.NO_LOCATION
        _here.value = at
        var area = _area.value
        var status = NearbyStatus.OK
        if (force || area == null || !covers(area, at, nowMs)) {
            val fresh = download(at, nowMs)
            if (fresh != null) {
                area = fresh
                _area.value = fresh
                store.putString(KEY_AREA, json.encodeToString(NearbyArea.serializer(), fresh))
            } else {
                status = NearbyStatus.OFFLINE
            }
        }
        if (area != null && backend.canWatch()) {
            val watched = Geo.nearest(area.stores, at.lat, at.lng, backend.maxWatched - 1)
            backend.watch(watched, at, exitRadius(watched, at))
        }
        return status
    }

    private suspend fun download(at: GeoPoint, nowMs: Long): NearbyArea? {
        val body = try {
            fetch(Osm.query(at.lat, at.lng, AREA_RADIUS_M))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        val stores = runCatching { Osm.parse(body) }.getOrNull() ?: return null
        // Busy downtowns return thousands of places; keep the closest so saves stay small.
        return NearbyArea(at.lat, at.lng, nowMs, Geo.nearest(stores, at.lat, at.lng, MAX_SAVED_STORES))
    }

    /**
     * The alert for having stayed inside the circles of [storeIds], or null when nothing should
     * be shown. With overlapping stores (a mall), the one closest to [at] wins. Throttled per
     * store and overall, so one shopping trip doesn't buzz your phone repeatedly.
     */
    fun dwellAlert(storeIds: Collection<String>, at: GeoPoint?, nowMs: Long): Alert? {
        val stores = _area.value?.stores?.filter { it.id in storeIds }.orEmpty()
        val target = (if (at != null) stores.minByOrNull { Geo.distanceM(it, at.lat, at.lng) } else stores.firstOrNull())
            ?: return null
        if (recently(KEY_LAST_ANY, nowMs, ANY_COOLDOWN_MS) || recently("nudged_${target.id}", nowMs, STORE_COOLDOWN_MS)) return null
        store.putLong(KEY_LAST_ANY, nowMs)
        store.putLong("nudged_${target.id}", nowMs)
        return Recommender.arrivalAlert(target, repository.cards.value)
    }

    private fun recently(key: String, nowMs: Long, windowMs: Long): Boolean {
        val last = store.getLong(key, -1L)
        return last >= 0 && nowMs - last in 0L until windowMs
    }

    private fun covers(area: NearbyArea, at: GeoPoint, nowMs: Long): Boolean =
        nowMs - area.fetchedAtMs < MAX_AGE_MS &&
            Geo.distanceM(area.lat, area.lng, at.lat, at.lng) <= AREA_RADIUS_M - REUSE_MARGIN_M

    /** Leave the circle holding the watched stores and it's time to look again. */
    private fun exitRadius(watched: List<Store>, at: GeoPoint): Double {
        val farthest = watched.maxOfOrNull { Geo.distanceM(it, at.lat, at.lng) } ?: MAX_EXIT_RADIUS_M
        return farthest.coerceIn(MIN_EXIT_RADIUS_M, MAX_EXIT_RADIUS_M)
    }

    private fun loadArea(): NearbyArea? =
        store.getString(KEY_AREA)?.let { runCatching { json.decodeFromString(NearbyArea.serializer(), it) }.getOrNull() }

    companion object {
        /** Radius of one store fetch. */
        const val AREA_RADIUS_M = 1200
        /** Size of each store's alert circle; the OS is unreliable below about 100 m. */
        const val STORE_RADIUS_M = 100f
        /** How long you must stay at a store before the alert (Android; iOS alerts on entry). */
        const val DWELL_MS = 60_000

        private const val REUSE_MARGIN_M = 400
        private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_SAVED_STORES = 400
        private const val MIN_EXIT_RADIUS_M = 200.0
        private const val MAX_EXIT_RADIUS_M = 800.0
        private const val STORE_COOLDOWN_MS = 3L * 60 * 60 * 1000
        private const val ANY_COOLDOWN_MS = 5L * 60 * 1000
        private const val KEY_AREA = "nearby_area"
        private const val KEY_LAST_ANY = "nudged_any"

        /** Watch-circle id for "you left the area"; never a store id (those start with n/w/r). */
        const val EXIT_ID = "area-exit"
    }
}
