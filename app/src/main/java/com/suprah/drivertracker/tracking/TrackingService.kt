package com.suprah.drivertracker.tracking

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import com.suprah.drivertracker.R
import com.suprah.drivertracker.data.settings.SettingsRepository
import com.suprah.drivertracker.permissions.PhoneSettingsChecker
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Runs while the driver is on duty: a foreground service with a notification, so Android keeps it alive
 * with the screen locked or another app open (spec T-1). The rules live in [TrackingEngine], one per
 * on-duty session ([TrackingSessions]); this class only connects them to Android.
 */
@AndroidEntryPoint
class TrackingService : Service() {
    @Inject lateinit var engines: Provider<TrackingEngine>

    @Inject lateinit var state: TrackerStateHolder

    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var phoneSettings: PhoneSettingsChecker

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var sessions: TrackingSessions
    private var notificationUpdates: Job? = null

    override fun onCreate() {
        super.onCreate()
        sessions =
            TrackingSessions(
                parent = scope,
                newEngine = engines::get,
                alert = { TrackingNotifications.alert(this, it) },
                stopService = ::stopSelf,
                stopServiceIfLatest = { startId -> stopSelf(startId) },
            )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_GO_OFF_DUTY) {
            // The notification stays as it is while the last readings are sent (spec T-17).
            notificationUpdates?.cancel()
            notificationUpdates = null
            sessions.goOffDuty(startId)
            return START_NOT_STICKY
        }
        if (!phoneSettings.hasPreciseLocation()) {
            TrackingNotifications.alert(this, TrackingAlert.PERMISSION_NEEDED)
            scope.launch {
                settings.setOnDuty(false)
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this,
                TrackingNotifications.ONGOING_ID,
                TrackingNotifications.ongoing(
                    this,
                    getString(R.string.notification_on_duty),
                    getString(R.string.notification_checking),
                ),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } catch (_: Exception) {
            // Android refused to start tracking in the background (for example right after a restart).
            TrackingNotifications.alert(this, TrackingAlert.RESUME)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        state.update { copy(serviceRunning = true, unlinked = false) }
        if (sessions.startIfIdle()) {
            notificationUpdates =
                scope.launch {
                    state.state
                        .map { TrackingNotifications.content(resources, it) }
                        .filterNotNull()
                        .distinctUntilChanged()
                        .collect { (title, text) ->
                            getSystemService<NotificationManager>()?.notify(
                                TrackingNotifications.ONGOING_ID,
                                TrackingNotifications.ongoing(this@TrackingService, title, text),
                            )
                        }
                }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        state.update { copy(serviceRunning = false, gpsOn = false) }
        super.onDestroy()
    }

    companion object {
        const val ACTION_GO_OFF_DUTY = "com.suprah.drivertracker.GO_OFF_DUTY"
    }
}
