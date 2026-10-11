package com.cardnudge.droid

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cardnudge.data.NearbyStatus
import com.cardnudge.domain.GeoPoint
import com.cardnudge.platform.currentTimeMillis
import java.util.concurrent.TimeUnit

/**
 * Looks up nearby stores in the background: right away after you leave the watched area,
 * and every few hours as a safety net in case the OS dropped the geofences.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val fallback = if (inputData.keyValueMap.containsKey(KEY_LAT)) {
            GeoPoint(inputData.getDouble(KEY_LAT, 0.0), inputData.getDouble(KEY_LNG, 0.0), 0f)
        } else {
            null
        }
        val status = AppGraph.nearby(applicationContext)
            .update(AndroidLocation(applicationContext), currentTimeMillis(), fallback = fallback)
        return if (status == NearbyStatus.OFFLINE && runAttemptCount < 3) Result.retry() else Result.success()
    }

    companion object {
        private const val KEY_LAT = "lat"
        private const val KEY_LNG = "lng"

        /** Refresh as soon as possible; [at] is used if a fresh fix can't be had. */
        fun runNow(context: Context, at: GeoPoint?) {
            val data = if (at == null) workDataOf() else workDataOf(KEY_LAT to at.lat, KEY_LNG to at.lng)
            val request = OneTimeWorkRequestBuilder<RefreshWorker>().setInputData(data).build()
            WorkManager.getInstance(context).enqueueUniqueWork("nearby-now", ExistingWorkPolicy.REPLACE, request)
        }

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(4, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("nearby-periodic", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
