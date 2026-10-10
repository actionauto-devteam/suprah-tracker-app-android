package com.suprah.drivertracker.tracking

import com.suprah.drivertracker.core.Clock
import com.suprah.drivertracker.data.positions.PositionStore
import com.suprah.drivertracker.data.settings.SettingsRepository
import com.suprah.drivertracker.domain.ApiResult
import com.suprah.drivertracker.domain.AppStatus
import com.suprah.drivertracker.domain.Position
import com.suprah.drivertracker.domain.RefusalReason
import com.suprah.drivertracker.domain.TrackerApi
import com.suprah.drivertracker.permissions.PhoneSettingsChecker
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Alerts the driver sees as notifications (spec T-2, T-13). */
enum class TrackingAlert { PERMISSION_NEEDED, RESUME, UNLINKED }

/** What the engine needs from the Android service that runs it. */
interface TrackingHost {
    fun alert(alert: TrackingAlert)

    fun stop()
}

/**
 * Tracking while on duty (spec section 6), independent of Android so it can be tested:
 *
 * - asks Suprah every minute whether the driver has an accepted load;
 * - keeps GPS on only while on duty, approved and with an accepted load;
 * - saves every reading and sends it straight away (see [PositionUploader]);
 * - reacts to Suprah's refusals (unlinked, tracking switched off).
 *
 * One engine per service start; the service's scope bounds everything it launches.
 */
class TrackingEngine @Inject constructor(
    private val settings: SettingsRepository,
    private val store: PositionStore,
    private val api: TrackerApi,
    private val clock: Clock,
    private val state: TrackerStateHolder,
    private val locationSource: LocationSource,
    private val phoneSettings: PhoneSettingsChecker,
) : UploadCallbacks {
    private lateinit var scope: CoroutineScope
    private lateinit var host: TrackingHost
    private lateinit var uploader: PositionUploader

    @Volatile private var onDuty = false

    private val gpsLock = Mutex()
    private var gpsJob: Job? = null
    private var gpsIntervalSeconds = 0

    private fun attach(scope: CoroutineScope, host: TrackingHost) {
        if (::uploader.isInitialized) return
        this.scope = scope
        this.host = host
        uploader = PositionUploader(store, api, settings, clock, state, callbacks = this)
    }

    /** Starts tracking if the driver is on duty and the phone is paired; otherwise stops the host (spec T-2). */
    fun start(scope: CoroutineScope, host: TrackingHost) {
        attach(scope, host)
        scope.launch {
            val current = settings.current()
            if (!current.onDuty || !current.isPaired) {
                host.stop()
                return@launch
            }
            onDuty = true
            launch { statusLoop() }
            launch { uploadLoop() }
        }
    }

    /** Off duty: GPS off at once, send what's saved, then stop (spec T-17). */
    suspend fun goOffDuty(scope: CoroutineScope, host: TrackingHost) {
        attach(scope, host)
        onDuty = false
        settings.setOnDuty(false)
        stopGps()
        state.update { copy(gpsOn = false) }
        uploader.sendQueued()
        host.stop()
    }

    private suspend fun statusLoop() {
        while (currentCoroutineContext().isActive) {
            val current = settings.current()
            val credentials = current.credentials ?: return unlinked()
            when (val result = api.status(current.serverUrl, credentials)) {
                is ApiResult.Ok -> onStatus(result.value)
                is ApiResult.Refused -> if (onRefused(result)) return
                is ApiResult.Offline -> state.update { copy(problem = TrackingProblem.StatusOffline) }
            }
            delay(UploadPolicy.STATUS_INTERVAL_MS)
        }
    }

    /** The regular retry (spec T-9): readings normally go out as soon as they're measured. */
    private suspend fun uploadLoop() {
        while (currentCoroutineContext().isActive) {
            delay(uploader.uploadIntervalSeconds * MILLIS)
            uploader.sendQueued()
        }
    }

    override suspend fun onStatus(status: AppStatus) {
        uploader.uploadIntervalSeconds = status.uploadIntervalSeconds
        settings.updateFromStatus(status.driverName, status.approval)
        // Off duty (saved readings still being sent): GPS stays off, and the shared state belongs to the
        // session that's on duty now, if the driver went straight back on duty.
        if (!onDuty) return
        val wanted = GpsPolicy.shouldUseGps(onDuty, status)
        val gpsOn =
            if (wanted) {
                startGps(status.locationIntervalSeconds)
            } else {
                stopGps()
                false
            }
        state.update {
            copy(
                approval = status.approval,
                shouldTrack = status.shouldTrack,
                activeLoadCount = status.activeLoadCount,
                gpsOn = gpsOn,
                problem = if (wanted && !gpsOn) TrackingProblem.PermissionOff else null,
            )
        }
    }

    override suspend fun onRefused(refusal: ApiResult.Refused): Boolean = when {
        RefusalReason.isUnlinked(refusal.reason) -> {
            unlinked()
            true
        }

        refusal.reason == RefusalReason.TRACKING_APP_OFF -> {
            stopGps()
            state.update { copy(gpsOn = false, problem = TrackingProblem.TrackingOff) }
            false
        }

        else -> {
            state.update { copy(problem = TrackingProblem.Server(refusal.message, refusal.httpStatus)) }
            false
        }
    }

    override suspend fun onMissingCredentials() {
        // Unlinked from this phone's own screen while the last readings were being sent: nothing to announce.
        if (!onDuty) return
        unlinked()
    }

    /** This phone was unlinked: forget everything and go back to pairing (spec T-13). */
    private suspend fun unlinked() {
        stopGps()
        store.clear()
        settings.clearPairing()
        state.update { TrackerState(unlinked = true, problem = TrackingProblem.Unlinked) }
        host.alert(TrackingAlert.UNLINKED)
        host.stop()
    }

    /** Returns whether GPS is running. */
    private suspend fun startGps(intervalSeconds: Int): Boolean = gpsLock.withLock {
        // Checked again here: going off duty may have happened while Suprah's answer was on its way (spec T-17).
        if (!onDuty) return@withLock false
        if (gpsJob?.isActive == true && gpsIntervalSeconds == intervalSeconds) return@withLock true
        if (!phoneSettings.hasPreciseLocation()) return@withLock false
        gpsJob?.cancel()
        gpsIntervalSeconds = intervalSeconds
        gpsJob =
            scope.launch {
                try {
                    locationSource.updates(intervalSeconds).collect(::onReadings)
                } catch (_: SecurityException) {
                    state.update { copy(gpsOn = false, problem = TrackingProblem.PermissionOff) }
                }
            }
        true
    }

    private suspend fun stopGps() {
        gpsLock.withLock {
            gpsJob?.cancel()
            gpsJob = null
            gpsIntervalSeconds = 0
        }
    }

    /** Save first, then send straight away (spec T-8, T-9). */
    private suspend fun onReadings(readings: List<Position>) {
        if (readings.isEmpty()) return
        store.add(readings)
        val last = readings.last()
        state.update { copy(lastFixAt = last.measuredAtMs, lastFixAccuracy = last.accuracy ?: lastFixAccuracy) }
        if (onDuty) uploader.sendSoon(scope)
    }

    private companion object {
        const val MILLIS = 1000L
    }
}
