@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.suprah.drivertracker.tracking

import com.google.common.truth.Truth.assertThat
import com.suprah.drivertracker.testing.FakeClock
import com.suprah.drivertracker.testing.FakeLocationSource
import com.suprah.drivertracker.testing.FakePhoneSettings
import com.suprah.drivertracker.testing.FakeSettingsRepository
import com.suprah.drivertracker.testing.FakeTrackerApi
import com.suprah.drivertracker.testing.InMemoryPositionStore
import com.suprah.drivertracker.testing.reading
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Spec T-1 and T-17: on-duty sessions inside the tracking service. */
class TrackingSessionsTest {
    private val api = FakeTrackerApi()
    private val store = InMemoryPositionStore()
    private val settings = FakeSettingsRepository()
    private val state = TrackerStateHolder()
    private val gps = FakeLocationSource()

    private var serviceStopped = false
    private val stopIfLatestCalls = mutableListOf<Int>()
    private val alerts = mutableListOf<TrackingAlert>()

    private fun TestScope.sessions() = TrackingSessions(
        parent = backgroundScope,
        newEngine = {
            TrackingEngine(settings, store, api, FakeClock { testScheduler.currentTime }, state, gps, FakePhoneSettings())
        },
        alert = { alerts += it },
        stopService = { serviceStopped = true },
        stopServiceIfLatest = { stopIfLatestCalls += it },
    )

    @Test
    fun `going off duty sends what's saved, then asks to stop for that request (T-17)`() = runTest {
        val sessions = sessions()
        sessions.startIfIdle()
        runCurrent()
        store.add(listOf(reading(1_000)))

        sessions.goOffDuty(startId = 2)
        runCurrent()

        assertThat(store.size()).isEqualTo(0)
        assertThat(gps.activeCollectors).isEqualTo(0)
        assertThat(stopIfLatestCalls).containsExactly(2)
        assertThat(serviceStopped).isFalse()
        assertThat(sessions.isActive).isFalse()
    }

    @Test
    fun `back on duty while the last readings are still sending - tracking carries on (T-1, T-17)`() = runTest {
        val sessions = sessions()
        sessions.startIfIdle()
        runCurrent()
        assertThat(gps.activeCollectors).isEqualTo(1)
        store.add(listOf(reading(1_000)))
        api.sendDelayMs = 30_000

        settings.setOnDuty(false)
        sessions.goOffDuty(startId = 2)
        runCurrent()
        assertThat(gps.activeCollectors).isEqualTo(0)

        settings.setOnDuty(true)
        assertThat(sessions.startIfIdle()).isTrue()
        api.sendDelayMs = 0
        advanceTimeBy(30_001)

        // The old session's ending asked to stop only if nothing newer started; the service keeps running.
        assertThat(stopIfLatestCalls).containsExactly(2)
        assertThat(serviceStopped).isFalse()
        assertThat(sessions.isActive).isTrue()
        assertThat(gps.activeCollectors).isEqualTo(1)
        assertThat(state.state.value.gpsOn).isTrue()
    }

    @Test
    fun `a second start while on duty doesn't start a second session`() = runTest {
        val sessions = sessions()

        assertThat(sessions.startIfIdle()).isTrue()
        assertThat(sessions.startIfIdle()).isFalse()
        runCurrent()

        assertThat(gps.activeCollectors).isEqualTo(1)
    }

    @Test
    fun `going off duty when nothing was running still sends what's saved (T-17)`() = runTest {
        store.add(listOf(reading(1_000)))

        sessions().goOffDuty(startId = 1)
        runCurrent()

        assertThat(api.sentBatches.flatten()).hasSize(1)
        assertThat(stopIfLatestCalls).containsExactly(1)
    }

    @Test
    fun `a start when not on duty stops the service (T-2)`() = runTest {
        settings.setOnDuty(false)

        sessions().startIfIdle()
        runCurrent()

        assertThat(serviceStopped).isTrue()
        assertThat(api.statusCalls).isEqualTo(0)
    }
}
