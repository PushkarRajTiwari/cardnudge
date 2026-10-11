package com.cardnudge.droid

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.cardnudge.R
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
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "CardNudge"

/**
 * Process-wide objects. They hold only the application context, never an Activity,
 * so a rotated or closed screen can always be garbage-collected.
 */
object AppGraph {
    @Volatile private var repo: Repository? = null
    @Volatile private var near: Nearby? = null
    @Volatile private var store: PrefsStore? = null

    private fun store(context: Context): PrefsStore =
        store ?: synchronized(this) { store ?: PrefsStore(context.applicationContext).also { store = it } }

    fun repository(context: Context): Repository =
        repo ?: synchronized(this) { repo ?: Repository(store(context)).also { repo = it } }

    fun nearby(context: Context): Nearby =
        near ?: synchronized(this) { near ?: Nearby(store(context), repository(context), ::overpass).also { near = it } }
}

private class PrefsStore(context: Context) : KeyValueStore {
    private val prefs = context.getSharedPreferences("cardnudge", Context.MODE_PRIVATE)
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)
    override fun putLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
}

/**
 * Android side of [Platform]. Lives exactly as long as its Activity: it is created in
 * onCreate and only referenced from that Activity's composition.
 */
class AndroidPlatform(private val activity: Activity) : Platform {
    private val app: Context = activity.applicationContext

    override val repository: Repository = AppGraph.repository(app)
    override val nearby: Nearby = AppGraph.nearby(app)
    override val setupVersion: StateFlow<Int> = MutableStateFlow(0) // Resume events cover Android.

    override fun setupItems(): List<SetupItem> = buildList {
        add(SetupItem(SetupStep.LOCATION, "Location access", Perms.fine(app)))
        add(SetupItem(SetupStep.BACKGROUND_LOCATION, "Location “Allow all the time”", Perms.fine(app) && Perms.background(app)))
        add(SetupItem(SetupStep.NOTIFICATIONS, "Notifications", Perms.notifications(app)))
        val battery = app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName)
        add(SetupItem(SetupStep.BATTERY, "Ignore battery optimisation", battery))
    }

    override fun fixSetup(step: SetupStep) {
        when (step) {
            SetupStep.LOCATION -> activity.requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1
            )
            SetupStep.BACKGROUND_LOCATION -> when {
                !Perms.fine(app) -> fixSetup(SetupStep.LOCATION)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
            }
            SetupStep.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
            }
            SetupStep.BATTERY -> try {
                activity.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${app.packageName}"))
                )
            } catch (e: Exception) {
                activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    override suspend fun refreshNearby(force: Boolean): NearbyStatus {
        val status = nearby.update(AndroidLocation(app), currentTimeMillis(), force)
        // Keep the background refresh scheduled; cheap to repeat because it keeps the existing one.
        RefreshWorker.schedule(app)
        return status
    }

    override fun postAlert(alert: Alert) = Alerts.post(app, alert)
}

internal object Perms {
    fun fine(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)

    fun background(ctx: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun notifications(ctx: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(ctx, Manifest.permission.POST_NOTIFICATIONS)

    private fun granted(ctx: Context, perm: String) = ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
}

/** Location and geofences for [Nearby]. Holds only the application context, so background work can use it. */
internal class AndroidLocation(context: Context) : LocationBackend {
    private val app = context.applicationContext

    override val maxWatched: Int = 100 // Android allows 100 geofences per app.

    override fun canWatch(): Boolean = Perms.fine(app) && Perms.background(app)

    override suspend fun currentLocation(): GeoPoint? {
        if (!Perms.fine(app)) return null
        val client = LocationServices.getFusedLocationProviderClient(app)
        val cts = CancellationTokenSource()
        return try {
            val fix = withTimeoutOrNull(20_000) {
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token).await(cts)
            } ?: client.lastLocation.await()
            fix?.let { GeoPoint(it.latitude, it.longitude, it.accuracy) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Location fix failed", e)
            null
        } finally {
            cts.cancel()
        }
    }

    override fun watch(stores: List<Store>, center: GeoPoint, exitRadiusM: Double) = Geofences.sync(app, stores, center, exitRadiusM)
}

internal object Geofences {
    private fun pendingIntent(app: Context): PendingIntent = PendingIntent.getBroadcast(
        app, 0, Intent(app, GeofenceReceiver::class.java),
        // Geofencing fills in the triggering geofences, so the intent must be mutable.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    /**
     * Store fences fire after you stay inside for a minute, so driving past doesn't count.
     * One larger fence around you fires on exit, which triggers the next lookup.
     */
    fun sync(context: Context, stores: List<Store>, center: GeoPoint, exitRadiusM: Double) {
        val app = context.applicationContext
        val client = LocationServices.getGeofencingClient(app)
        client.removeGeofences(pendingIntent(app)).addOnCompleteListener {
            if (!Perms.fine(app) || !Perms.background(app)) return@addOnCompleteListener
            val storeFences = stores.take(99).map { s ->
                Geofence.Builder()
                    .setRequestId(s.id)
                    .setCircularRegion(s.lat, s.lng, Nearby.STORE_RADIUS_M)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_DWELL)
                    .setLoiteringDelay(Nearby.DWELL_MS)
                    .setNotificationResponsiveness(30_000)
                    .build()
            }
            val exitFence = Geofence.Builder()
                .setRequestId(Nearby.EXIT_ID)
                .setCircularRegion(center.lat, center.lng, exitRadiusM.toFloat())
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
                .setNotificationResponsiveness(60_000)
                .build()
            // No initial trigger: being near a store when the list refreshes (say, at home) isn't a visit.
            val requests = buildList {
                if (storeFences.isNotEmpty()) add(GeofencingRequest.Builder().setInitialTrigger(0).addGeofences(storeFences).build())
                add(GeofencingRequest.Builder().setInitialTrigger(0).addGeofence(exitFence).build())
            }
            try {
                requests.forEach { request ->
                    client.addGeofences(request, pendingIntent(app))
                        .addOnSuccessListener { Log.i(TAG, "Registered ${request.geofences.size} geofences") }
                        .addOnFailureListener { e -> Log.w(TAG, "Geofence registration failed", e) }
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Missing location permission", e)
            }
        }
    }
}

internal object Alerts {
    private const val CHANNEL = "nudges"

    fun post(context: Context, alert: Alert) {
        if (!Perms.notifications(context)) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Card suggestions", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Which card to use at the store you're in"
                }
            )
        }
        val open = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(0xFF7C5CFF.toInt())
            .setContentTitle(alert.title)
            .setContentText(alert.body)
            .setStyle(Notification.BigTextStyle().bigText(alert.body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(alert.id.hashCode(), n)
    }
}

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.w(TAG, "Geofence error ${event.errorCode}")
            return
        }
        val ids = event.triggeringGeofences.orEmpty().map { it.requestId }
        val at = event.triggeringLocation?.let { GeoPoint(it.latitude, it.longitude, it.accuracy) }
        when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_EXIT -> if (Nearby.EXIT_ID in ids) RefreshWorker.runNow(context, at)
            Geofence.GEOFENCE_TRANSITION_DWELL ->
                AppGraph.nearby(context).dwellAlert(ids, at, currentTimeMillis())?.let { Alerts.post(context, it) }
        }
    }
}

/** Geofences are cleared on reboot and app update, so look around again and put them back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            RefreshWorker.runNow(context, null)
            RefreshWorker.schedule(context)
        }
    }
}
