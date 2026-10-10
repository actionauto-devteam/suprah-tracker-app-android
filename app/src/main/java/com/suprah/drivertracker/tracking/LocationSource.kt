package com.suprah.drivertracker.tracking

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.suprah.drivertracker.domain.Position
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

interface LocationSource {
    /**
     * GPS readings about every [intervalSeconds] until collection stops (spec T-6). Fails with a
     * SecurityException when location permission is missing.
     */
    fun updates(intervalSeconds: Int): Flow<List<Position>>
}

class FusedLocationSource @Inject constructor(
    @ApplicationContext context: Context,
) : LocationSource {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission") // Checked by the engine before starting; a SecurityException is handled there.
    override fun updates(intervalSeconds: Int): Flow<List<Position>> =
        callbackFlow {
            val request =
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalSeconds * MILLIS)
                    .setMinUpdateIntervalMillis(intervalSeconds * MILLIS / 2)
                    .setWaitForAccurateLocation(false)
                    .build()
            val callback =
                object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        trySend(result.locations.map { it.toPosition() })
                    }
                }
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            awaitClose { client.removeLocationUpdates(callback) }
        }.buffer(Channel.UNLIMITED) // Readings must never be dropped while the engine is busy.

    private fun Location.toPosition() = Position(
        lat = latitude,
        lng = longitude,
        measuredAtMs = time,
        accuracy = if (hasAccuracy()) accuracy else null,
        speed = if (hasSpeed()) speed else null,
        heading = if (hasBearing()) bearing else null,
    )

    private companion object {
        const val MILLIS = 1000L
    }
}
