@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.suprah.drivertracker.testing

import com.suprah.drivertracker.core.Clock
import com.suprah.drivertracker.data.positions.PositionStore
import com.suprah.drivertracker.data.settings.SettingsRepository
import com.suprah.drivertracker.data.settings.TrackerSettings
import com.suprah.drivertracker.domain.ApiResult
import com.suprah.drivertracker.domain.AppStatus
import com.suprah.drivertracker.domain.Approval
import com.suprah.drivertracker.domain.DeviceCredentials
import com.suprah.drivertracker.domain.Pairing
import com.suprah.drivertracker.domain.Position
import com.suprah.drivertracker.domain.SavedPosition
import com.suprah.drivertracker.domain.TrackerApi
import com.suprah.drivertracker.domain.UploadResult
import com.suprah.drivertracker.permissions.PhoneSettingsChecker
import com.suprah.drivertracker.permissions.PhoneSettingsStatus
import com.suprah.drivertracker.tracking.LocationSource
import com.suprah.drivertracker.tracking.TrackingAlert
import com.suprah.drivertracker.tracking.TrackingControl
import com.suprah.drivertracker.tracking.TrackingHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

val approvedWithLoad =
    AppStatus(
        approval = Approval.APPROVED,
        driverName = "Jo",
        shouldTrack = true,
        activeLoadCount = 1,
        uploadIntervalSeconds = 30,
        locationIntervalSeconds = 15,
    )

fun reading(measuredAtMs: Long) = Position(lat = 40.0, lng = -111.0, measuredAtMs = measuredAtMs)

class FakeClock(var now: () -> Long) : Clock {
    override fun nowMillis(): Long = now()
}

/** Answers from queues, or with [defaultStatus] when nothing is queued. Records what was sent. */
class FakeTrackerApi : TrackerApi {
    var defaultStatus = approvedWithLoad

    /** How long Suprah takes to answer a send (virtual time in tests). */
    var sendDelayMs = 0L
    val statusAnswers = ArrayDeque<ApiResult<AppStatus>>()
    val sendAnswers = ArrayDeque<ApiResult<UploadResult>>()
    val pairAnswers = ArrayDeque<ApiResult<Pairing>>()
    val pairCalls = mutableListOf<Pair<String, String>>()
    val sentBatches = mutableListOf<List<Position>>()
    var statusCalls = 0
    var unlinkCalls = 0

    override suspend fun pair(serverUrl: String, code: String, deviceName: String, appVersion: String): ApiResult<Pairing> {
        pairCalls += serverUrl to code
        return pairAnswers.removeFirstOrNull() ?: ApiResult.Ok(Pairing(DeviceCredentials("dev", "key"), defaultStatus))
    }

    override suspend fun status(serverUrl: String, credentials: DeviceCredentials): ApiResult<AppStatus> {
        statusCalls++
        return statusAnswers.removeFirstOrNull() ?: ApiResult.Ok(defaultStatus)
    }

    override suspend fun sendPositions(
        serverUrl: String,
        credentials: DeviceCredentials,
        positions: List<Position>,
    ): ApiResult<UploadResult> {
        sentBatches += positions
        if (sendDelayMs > 0) delay(sendDelayMs)
        return sendAnswers.removeFirstOrNull() ?: ApiResult.Ok(UploadResult(positions.size, positions.size, defaultStatus))
    }

    override suspend fun unlink(serverUrl: String, credentials: DeviceCredentials): ApiResult<Unit> {
        unlinkCalls++
        return ApiResult.Ok(Unit)
    }
}

class FakeTrackingControl : TrackingControl {
    val calls = mutableListOf<String>()

    override suspend fun goOnDuty() {
        calls += "on"
    }

    override fun resume() {
        calls += "resume"
    }

    override suspend fun goOffDuty() {
        calls += "off"
    }
}

/** Runs viewModelScope work right away in tests. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(private val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)

    override fun finished(description: Description) = Dispatchers.resetMain()
}

/** Same ordering and cap as the Room store. */
class InMemoryPositionStore(private val cap: Int = 5_000) : PositionStore {
    private val saved = MutableStateFlow<List<SavedPosition>>(emptyList())
    private var nextId = 1L

    val all: List<Position> get() = saved.value.map { it.position }

    override val count: Flow<Int> = saved.map { it.size }

    override suspend fun add(positions: List<Position>) {
        saved.update { current ->
            (current + positions.map { SavedPosition(nextId++, it) })
                .sortedWith(compareBy({ it.position.measuredAtMs }, { it.id }))
                .takeLast(cap)
        }
    }

    override suspend fun oldest(limit: Int) = saved.value.take(limit)

    override suspend fun newest() = saved.value.lastOrNull()

    override suspend fun remove(ids: Collection<Long>) = saved.update { current -> current.filterNot { it.id in ids } }

    override suspend fun dropMeasuredBefore(cutoffMs: Long) = saved.update { current ->
        current.filter {
            it.position.measuredAtMs >= cutoffMs
        }
    }

    override suspend fun size() = saved.value.size

    override suspend fun clear() = saved.update { emptyList() }
}

class FakeSettingsRepository(
    initial: TrackerSettings =
        TrackerSettings(
            serverUrl = "http://test",
            credentials = DeviceCredentials("dev", "key"),
            driverName = "",
            approval = Approval.APPROVED,
            onDuty = true,
            lastSentAt = 0L,
        ),
) : SettingsRepository {
    val flow = MutableStateFlow(initial)
    override val settings: Flow<TrackerSettings> = flow

    override suspend fun setServerUrl(url: String) = flow.update { it.copy(serverUrl = url) }

    override suspend fun savePairing(credentials: DeviceCredentials, driverName: String, approval: Approval) =
        flow.update { it.copy(credentials = credentials, driverName = driverName, approval = approval) }

    override suspend fun updateFromStatus(driverName: String, approval: Approval) =
        flow.update { it.copy(driverName = driverName, approval = approval) }

    override suspend fun setOnDuty(onDuty: Boolean) = flow.update { it.copy(onDuty = onDuty) }

    override suspend fun setLastSentAt(timeMs: Long) = flow.update { it.copy(lastSentAt = timeMs) }

    override suspend fun clearPairing() =
        flow.update { it.copy(credentials = null, driverName = "", approval = Approval.WAITING, onDuty = false, lastSentAt = 0L) }
}

class FakePhoneSettings(var precise: Boolean = true) : PhoneSettingsChecker {
    override fun hasPreciseLocation() = precise

    override fun status() = PhoneSettingsStatus(
        preciseLocation = precise,
        allTheTime = true,
        notifications = true,
        batteryUnrestricted = true,
        locationServicesOn = true,
        xiaomi = false,
        autostart = null,
    )
}

/** GPS readings are pushed by the test through [readings]. */
class FakeLocationSource : LocationSource {
    val readings = MutableSharedFlow<List<Position>>(extraBufferCapacity = 64)
    val requestedIntervals = mutableListOf<Int>()
    var activeCollectors = 0
        private set

    override fun updates(intervalSeconds: Int): Flow<List<Position>> = flow {
        requestedIntervals += intervalSeconds
        activeCollectors++
        try {
            readings.collect { emit(it) }
        } finally {
            activeCollectors--
        }
    }
}

class FakeHost : TrackingHost {
    val alerts = mutableListOf<TrackingAlert>()
    var stopped = false

    override fun alert(alert: TrackingAlert) {
        alerts += alert
    }

    override fun stop() {
        stopped = true
    }
}
