package com.cardnudge.platform

import com.cardnudge.data.Nearby
import com.cardnudge.data.NearbyStatus
import com.cardnudge.data.Repository
import com.cardnudge.domain.Alert
import com.cardnudge.domain.Osm
import kotlinx.coroutines.flow.StateFlow

enum class SetupStep { LOCATION, BACKGROUND_LOCATION, NOTIFICATIONS, BATTERY }

data class SetupItem(val step: SetupStep, val title: String, val done: Boolean)

/**
 * Everything the shared app needs from the OS. Android and iOS each implement this once;
 * all screens, state and business rules stay in common code.
 */
interface Platform {
    val repository: Repository

    val nearby: Nearby

    /** Bumped when permission state changes asynchronously, so the UI can refresh. */
    val setupVersion: StateFlow<Int>

    /** Permission and battery steps relevant on this OS, and whether each is done. */
    fun setupItems(): List<SetupItem>

    fun fixSetup(step: SetupStep)

    /** Looks up stores around you and starts watching the closest ones. */
    suspend fun refreshNearby(force: Boolean): NearbyStatus

    fun postAlert(alert: Alert)
}

expect fun currentTimeMillis(): Long

/** POSTs a form and returns the body of a 200 response, or null on any failure. */
expect suspend fun httpPostForm(url: String, form: String, userAgent: String): String?

/** Runs an Overpass query; the fetcher handed to [Nearby] on both platforms. */
suspend fun overpass(query: String): String? =
    httpPostForm(Osm.ENDPOINT, "data=" + Osm.formEncode(query), Osm.USER_AGENT)
