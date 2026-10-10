package com.suprah.drivertracker.tracking

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.suprah.drivertracker.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Going on and off duty (spec T-1, T-17) and resuming tracking (H-6, T-3). */
interface TrackingControl {
    suspend fun goOnDuty()

    /** Starts tracking for a driver who is already on duty. */
    fun resume()

    /** Tracking sends what's saved, then stops. */
    suspend fun goOffDuty()
}

@Singleton
class TrackingLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) : TrackingControl {
    override suspend fun goOnDuty() {
        settings.setOnDuty(true)
        resume()
    }

    override fun resume() {
        ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))
    }

    override suspend fun goOffDuty() {
        settings.setOnDuty(false)
        try {
            context.startService(
                Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_GO_OFF_DUTY),
            )
        } catch (_: IllegalStateException) {
            // The app isn't in the foreground: the service, if running, still stops at its next start check.
        }
    }
}
