package com.cardnudge.platform

import com.cardnudge.data.Repository
import com.cardnudge.domain.Alert
import com.cardnudge.domain.GeoPoint
import com.cardnudge.domain.Place
import kotlinx.coroutines.flow.StateFlow

enum class SetupStep { LOCATION, BACKGROUND_LOCATION, NOTIFICATIONS, BATTERY }

data class SetupItem(val step: SetupStep, val title: String, val done: Boolean)

/**
 * Everything the shared app needs from the OS. Android and iOS each implement this once;
 * all screens, state and business rules stay in common code.
 */
interface Platform {
    val repository: Repository

    /** OS cap on monitored places: 100 geofences on Android, 20 regions on iOS. */
    val maxPlaces: Int

    /** Bumped when permission state changes asynchronously, so the UI can refresh. */
    val setupVersion: StateFlow<Int>

    /** Permission and battery steps relevant on this OS, and whether each is done. */
    fun setupItems(): List<SetupItem>

    fun fixSetup(step: SetupStep)

    /** One location fix, or null if permission is missing or no fix arrived in time. */
    suspend fun currentLocation(): GeoPoint?

    /** Replaces the monitored regions with [places] (capped at [maxPlaces]). */
    fun syncGeofences(places: List<Place>)

    fun postAlert(alert: Alert)
}

expect fun currentTimeMillis(): Long
