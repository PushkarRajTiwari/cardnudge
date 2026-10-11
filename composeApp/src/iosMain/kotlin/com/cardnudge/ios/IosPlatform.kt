@file:OptIn(ExperimentalForeignApi::class)

package com.cardnudge.ios

import com.cardnudge.data.KeyValueStore
import com.cardnudge.data.LocationBackend
import com.cardnudge.data.Nearby
import com.cardnudge.data.NearbyStatus
import com.cardnudge.data.Repository
import com.cardnudge.domain.Alert
import com.cardnudge.domain.GeoPoint
import com.cardnudge.domain.Store
import com.cardnudge.platform.Platform
import com.cardnudge.platform.SetupItem
import com.cardnudge.platform.SetupStep
import com.cardnudge.platform.currentTimeMillis
import com.cardnudge.platform.overpass
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLCircularRegion
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationCoordinate2DMake
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.CLRegion
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSinceNow
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIBackgroundTaskInvalid
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.NSObject

private class DefaultsStore : KeyValueStore {
    private val defaults = NSUserDefaults.standardUserDefaults
    override fun getString(key: String): String? = defaults.stringForKey(key)
    override fun putString(key: String, value: String) = defaults.setObject(value, forKey = key)
    override fun getLong(key: String, default: Long): Long =
        if (defaults.objectForKey(key) == null) default else defaults.integerForKey(key)
    override fun putLong(key: String, value: Long) = defaults.setInteger(value, forKey = key)
}

/**
 * iOS side of [Platform]. A single instance is created at app launch so region events are
 * handled even when iOS relaunches the app in the background.
 */
class IosPlatform private constructor() : Platform, LocationBackend {

    private val store = DefaultsStore()
    override val repository = Repository(store)
    override val nearby = Nearby(store, repository, ::overpass)
    override val maxWatched = 20 // iOS monitors at most 20 regions per app.

    // Lives as long as the app; only used for short background refreshes.
    private val scope = MainScope()
    private var refreshJob: Job? = null

    private val _setupVersion = MutableStateFlow(0)
    override val setupVersion: StateFlow<Int> = _setupVersion

    private val manager = CLLocationManager()
    // CLLocationManager holds its delegate weakly, so keep a strong reference here.
    private val delegate = LocationDelegate(this)
    private var pendingFix: CompletableDeferred<GeoPoint?>? = null
    private var notificationStatus: Long = UNAuthorizationStatusNotDetermined

    init {
        manager.delegate = delegate
        manager.desiredAccuracy = kCLLocationAccuracyBest
        refreshNotificationStatus()
        startSignificantChanges()
    }

    private val locationStatus: CLAuthorizationStatus get() = manager.authorizationStatus

    override fun setupItems(): List<SetupItem> {
        refreshNotificationStatus()
        val status = locationStatus
        val inUse = status == kCLAuthorizationStatusAuthorizedWhenInUse || status == kCLAuthorizationStatusAuthorizedAlways
        return listOf(
            SetupItem(SetupStep.LOCATION, "Location access", inUse),
            SetupItem(SetupStep.BACKGROUND_LOCATION, "Location “Always”", status == kCLAuthorizationStatusAuthorizedAlways),
            SetupItem(SetupStep.NOTIFICATIONS, "Notifications", notificationStatus == UNAuthorizationStatusAuthorized),
        )
    }

    override fun fixSetup(step: SetupStep) {
        when (step) {
            SetupStep.LOCATION ->
                if (locationStatus == kCLAuthorizationStatusNotDetermined) manager.requestWhenInUseAuthorization() else openSettings()
            SetupStep.BACKGROUND_LOCATION -> when (locationStatus) {
                kCLAuthorizationStatusNotDetermined -> manager.requestWhenInUseAuthorization()
                kCLAuthorizationStatusAuthorizedWhenInUse -> manager.requestAlwaysAuthorization()
                else -> openSettings()
            }
            SetupStep.NOTIFICATIONS ->
                if (notificationStatus == UNAuthorizationStatusNotDetermined) {
                    UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(
                        UNAuthorizationOptionAlert or UNAuthorizationOptionSound
                    ) { _, _ -> refreshNotificationStatus() }
                } else {
                    openSettings()
                }
            SetupStep.BATTERY -> Unit // Not a thing on iOS.
        }
    }

    override suspend fun refreshNearby(force: Boolean): NearbyStatus = nearby.update(this, currentTimeMillis(), force)

    override fun canWatch(): Boolean = locationStatus == kCLAuthorizationStatusAuthorizedAlways

    override suspend fun currentLocation(): GeoPoint? {
        val status = locationStatus
        if (status != kCLAuthorizationStatusAuthorizedWhenInUse && status != kCLAuthorizationStatusAuthorizedAlways) return null
        // A fix from the last minute is good enough and saves the background time we have.
        manager.location?.takeIf { -it.timestamp.timeIntervalSinceNow < 60.0 && it.horizontalAccuracy in 0.0..100.0 }
            ?.let { return it.toGeoPoint() }
        pendingFix?.complete(null)
        val fix = CompletableDeferred<GeoPoint?>()
        pendingFix = fix
        manager.requestLocation()
        return try {
            withTimeoutOrNull(20_000) { fix.await() }
        } finally {
            if (pendingFix === fix) pendingFix = null
        }
    }

    override fun watch(stores: List<Store>, center: GeoPoint, exitRadiusM: Double) {
        manager.monitoredRegions.forEach { (it as? CLRegion)?.let(manager::stopMonitoringForRegion) }
        if (!canWatch()) return
        stores.take(maxWatched - 1).forEach { s ->
            val region = CLCircularRegion(
                center = CLLocationCoordinate2DMake(s.lat, s.lng),
                radius = Nearby.STORE_RADIUS_M.toDouble(),
                identifier = s.id,
            )
            region.notifyOnEntry = true
            region.notifyOnExit = false
            manager.startMonitoringForRegion(region)
        }
        val exit = CLCircularRegion(
            center = CLLocationCoordinate2DMake(center.lat, center.lng),
            radius = minOf(exitRadiusM, manager.maximumRegionMonitoringDistance),
            identifier = Nearby.EXIT_ID,
        )
        exit.notifyOnEntry = false
        exit.notifyOnExit = true
        manager.startMonitoringForRegion(exit)
    }

    override fun postAlert(alert: Alert) {
        val content = UNMutableNotificationContent()
        content.setTitle(alert.title)
        content.setBody(alert.body)
        content.setSound(UNNotificationSound.defaultSound)
        val request = UNNotificationRequest.requestWithIdentifier(alert.id, content, null)
        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request, null)
    }

    private fun refreshNotificationStatus() {
        UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
            val status = settings?.authorizationStatus ?: return@getNotificationSettingsWithCompletionHandler
            if (status != notificationStatus) {
                notificationStatus = status
                _setupVersion.update { it + 1 }
            }
        }
    }

    private fun openSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url, emptyMap<Any?, Any?>(), null)
    }

    internal fun onAuthorizationChanged() {
        _setupVersion.update { it + 1 }
        startSignificantChanges()
    }

    /** iOS can't wait for a dwell in the background, so store alerts fire on entry. */
    internal fun onEntered(regionId: String) {
        if (regionId == Nearby.EXIT_ID) return
        nearby.dwellAlert(listOf(regionId), manager.location?.toGeoPoint(), currentTimeMillis())?.let(::postAlert)
    }

    internal fun onExited(regionId: String) {
        if (regionId == Nearby.EXIT_ID) refreshInBackground(manager.location?.toGeoPoint())
    }

    internal fun onLocations(location: CLLocation?) {
        val fix = pendingFix
        if (fix != null) {
            fix.complete(location?.toGeoPoint())
        } else if (location != null) {
            // A significant-change update (about 500 m moved), possibly after a background relaunch.
            refreshInBackground(location.toGeoPoint())
        }
    }

    internal fun onLocationError() {
        pendingFix?.complete(null)
    }

    /** Wakes the app after big moves, as a backup in case the area-exit region is missed. */
    private fun startSignificantChanges() {
        if (canWatch() && CLLocationManager.significantLocationChangeMonitoringAvailable()) {
            manager.startMonitoringSignificantLocationChanges()
        }
    }

    /** Refreshes stores within the short time iOS gives a backgrounded app. */
    private fun refreshInBackground(fallback: GeoPoint?) {
        if (refreshJob?.isActive == true) return
        val app = UIApplication.sharedApplication
        var taskId = UIBackgroundTaskInvalid
        fun end() {
            if (taskId != UIBackgroundTaskInvalid) {
                app.endBackgroundTask(taskId)
                taskId = UIBackgroundTaskInvalid
            }
        }
        val job = scope.launch {
            try {
                nearby.update(this@IosPlatform, currentTimeMillis(), fallback = fallback)
            } finally {
                end()
            }
        }
        refreshJob = job
        taskId = app.beginBackgroundTaskWithName("nearby-refresh") {
            job.cancel()
            end()
        }
    }

    companion object {
        val shared: IosPlatform by lazy { IosPlatform() }
    }
}

/** Separate NSObject so the Kotlin platform class stays a plain Kotlin class. */
private class LocationDelegate(private val owner: IosPlatform) : NSObject(), CLLocationManagerDelegateProtocol {

    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) = owner.onAuthorizationChanged()

    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) =
        owner.onLocations(didUpdateLocations.lastOrNull() as? CLLocation)

    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) = owner.onLocationError()

    @ObjCSignatureOverride
    override fun locationManager(manager: CLLocationManager, didEnterRegion: CLRegion) =
        owner.onEntered(didEnterRegion.identifier)

    @ObjCSignatureOverride
    override fun locationManager(manager: CLLocationManager, didExitRegion: CLRegion) =
        owner.onExited(didExitRegion.identifier)
}

private fun CLLocation.toGeoPoint(): GeoPoint =
    coordinate.useContents { GeoPoint(latitude, longitude, horizontalAccuracy.toFloat()) }
