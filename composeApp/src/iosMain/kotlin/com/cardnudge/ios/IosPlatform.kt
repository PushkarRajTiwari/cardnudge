@file:OptIn(ExperimentalForeignApi::class)

package com.cardnudge.ios

import com.cardnudge.data.KeyValueStore
import com.cardnudge.data.Repository
import com.cardnudge.domain.Alert
import com.cardnudge.domain.GeoPoint
import com.cardnudge.domain.Place
import com.cardnudge.platform.Platform
import com.cardnudge.platform.SetupItem
import com.cardnudge.platform.SetupStep
import com.cardnudge.platform.currentTimeMillis
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CompletableDeferred
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
import platform.CoreLocation.CLRegionState
import platform.CoreLocation.CLRegionStateInside
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
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
class IosPlatform private constructor() : Platform {

    override val repository = Repository(DefaultsStore())
    override val maxPlaces = 20 // iOS monitors at most 20 regions per app.

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

    override suspend fun currentLocation(): GeoPoint? {
        val status = locationStatus
        if (status != kCLAuthorizationStatusAuthorizedWhenInUse && status != kCLAuthorizationStatusAuthorizedAlways) return null
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

    override fun syncGeofences(places: List<Place>) {
        manager.monitoredRegions.forEach { (it as? CLRegion)?.let(manager::stopMonitoringForRegion) }
        if (locationStatus != kCLAuthorizationStatusAuthorizedAlways) return
        places.take(maxPlaces).forEach { p ->
            val region = CLCircularRegion(
                center = CLLocationCoordinate2DMake(p.lat, p.lng),
                radius = p.radiusM.toDouble(),
                identifier = p.id,
            )
            region.notifyOnEntry = true
            region.notifyOnExit = false
            manager.startMonitoringForRegion(region)
            // Like Android's initial trigger: alert if we're already inside when it's added.
            manager.requestStateForRegion(region)
        }
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

    internal fun onAuthorizationChanged() = _setupVersion.update { it + 1 }

    internal fun onArrived(regionId: String) {
        repository.arrivalAlert(regionId, currentTimeMillis())?.let(::postAlert)
    }

    internal fun onFix(location: CLLocation?) {
        val point = location?.let { loc ->
            loc.coordinate.useContents { GeoPoint(latitude, longitude, loc.horizontalAccuracy.toFloat()) }
        }
        pendingFix?.complete(point)
    }

    companion object {
        val shared: IosPlatform by lazy { IosPlatform() }
    }
}

/** Separate NSObject so the Kotlin platform class stays a plain Kotlin class. */
private class LocationDelegate(private val owner: IosPlatform) : NSObject(), CLLocationManagerDelegateProtocol {

    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) = owner.onAuthorizationChanged()

    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) =
        owner.onFix(didUpdateLocations.lastOrNull() as? CLLocation)

    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) = owner.onFix(null)

    override fun locationManager(manager: CLLocationManager, didEnterRegion: CLRegion) =
        owner.onArrived(didEnterRegion.identifier)

    override fun locationManager(manager: CLLocationManager, didDetermineState: CLRegionState, forRegion: CLRegion) {
        if (didDetermineState == CLRegionStateInside) owner.onArrived(forRegion.identifier)
    }
}
