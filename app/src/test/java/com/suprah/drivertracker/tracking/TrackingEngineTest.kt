@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.suprah.drivertracker.tracking

import com.google.common.truth.Truth.assertThat
import com.suprah.drivertracker.domain.ApiResult
import com.suprah.drivertracker.domain.Approval
import com.suprah.drivertracker.domain.RefusalReason
import com.suprah.drivertracker.domain.UploadResult
import com.suprah.drivertracker.testing.FakeClock
import com.suprah.drivertracker.testing.FakeHost
import com.suprah.drivertracker.testing.FakeLocationSource
import com.suprah.drivertracker.testing.FakePhoneSettings
import com.suprah.drivertracker.testing.FakeSettingsRepository
import com.suprah.drivertracker.testing.FakeTrackerApi
import com.suprah.drivertracker.testing.InMemoryPositionStore
import com.suprah.drivertracker.testing.approvedWithLoad
import com.suprah.drivertracker.testing.reading
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Spec section 6: tracking while on duty. */
class TrackingEngineTest {
    private val api = FakeTrackerApi()
    private val store = InMemoryPositionStore()
    private val settings = FakeSettingsRepository()
    private val state = TrackerStateHolder()
    private val gps = FakeLocationSource()
    private val phone = FakePhoneSettings()
    private val host = FakeHost()

    private fun TestScope.engine() = TrackingEngine(settings, store, api, FakeClock { testScheduler.currentTime }, state, gps, phone)

    private fun TestScope.started(): TrackingEngine = engine().also { it.start(backgroundScope, host) }

    @Test
    fun `not on duty or not paired - tracking doesn't start (T-2)`() = runTest {
        settings.setOnDuty(false)

        started()
        runCurrent()

        assertThat(host.stopped).isTrue()
        assertThat(api.statusCalls).isEqualTo(0)
    }

    @Test
    fun `approved with an accepted load - GPS runs at Suprah's interval (T-5, T-6)`() = runTest {
        api.defaultStatus = approvedWithLoad.copy(locationIntervalSeconds = 10)

        started()
        runCurrent()

        assertThat(gps.requestedIntervals).containsExactly(10)
        assertThat(gps.activeCollectors).isEqualTo(1)
        assertThat(state.state.value.gpsOn).isTrue()
        assertThat(settings.current().driverName).isEqualTo("Jo")
    }

    @Test
    fun `each reading is saved and sent straight away (T-8, T-9)`() = runTest {
        started()
        runCurrent()

        gps.readings.emit(listOf(reading(testScheduler.currentTime)))
        runCurrent()

        assertThat(api.sentBatches.flatten()).hasSize(1)
        assertThat(store.size()).isEqualTo(0)
        assertThat(state.state.value.lastFixAt).isEqualTo(testScheduler.currentTime)
    }

    @Test
    fun `waiting for approval or no accepted load - no GPS (S-3, T-5)`() = runTest {
        api.defaultStatus = approvedWithLoad.copy(approval = Approval.WAITING, shouldTrack = false)
        started()
        runCurrent()
        assertThat(gps.activeCollectors).isEqualTo(0)

        api.defaultStatus = approvedWithLoad
        advanceTimeBy(UploadPolicy.STATUS_INTERVAL_MS + 1)
        assertThat(gps.activeCollectors).isEqualTo(1)

        api.defaultStatus = approvedWithLoad.copy(shouldTrack = false, activeLoadCount = 0)
        advanceTimeBy(UploadPolicy.STATUS_INTERVAL_MS + 1)
        assertThat(gps.activeCollectors).isEqualTo(0)
        assertThat(state.state.value.gpsOn).isFalse()
    }

    @Test
    fun `without precise location GPS doesn't start and the driver is told (T-2)`() = runTest {
        phone.precise = false

        started()
        runCurrent()

        assertThat(gps.activeCollectors).isEqualTo(0)
        assertThat(state.state.value.problem).isEqualTo(TrackingProblem.PermissionOff)
    }

    @Test
    fun `no answer to the status check is shown as a problem (T-14)`() = runTest {
        api.statusAnswers += ApiResult.Offline()

        started()
        runCurrent()

        assertThat(state.state.value.problem).isEqualTo(TrackingProblem.StatusOffline)
    }

    @Test
    fun `tracking switched off by the company - GPS off, driver told (T-13)`() = runTest {
        started()
        runCurrent()
        assertThat(gps.activeCollectors).isEqualTo(1)

        api.statusAnswers += ApiResult.Refused(503, RefusalReason.TRACKING_APP_OFF, "Off")
        advanceTimeBy(UploadPolicy.STATUS_INTERVAL_MS + 1)

        assertThat(gps.activeCollectors).isEqualTo(0)
        assertThat(state.state.value.problem).isEqualTo(TrackingProblem.TrackingOff)
        assertThat(host.stopped).isFalse()
    }

    @Test
    fun `unlinked - forget everything, alert the driver, stop (T-13)`() = runTest {
        store.add(listOf(reading(1_000)))
        api.statusAnswers += ApiResult.Refused(401, RefusalReason.DEVICE_UNLINKED, "Unlinked")

        started()
        runCurrent()

        assertThat(store.size()).isEqualTo(0)
        assertThat(settings.current().isPaired).isFalse()
        assertThat(state.state.value.unlinked).isTrue()
        assertThat(host.alerts).containsExactly(TrackingAlert.UNLINKED)
        assertThat(host.stopped).isTrue()
    }

    @Test
    fun `going off duty - GPS off at once, saved readings sent, then stop (T-17)`() = runTest {
        val engine = started()
        runCurrent()
        assertThat(gps.activeCollectors).isEqualTo(1)
        store.add(listOf(reading(1_000), reading(2_000)))
        // Suprah's answer during the final send still says "accepted load": GPS must stay off.
        api.sendAnswers += ApiResult.Ok(UploadResult(2, 2, approvedWithLoad))

        engine.goOffDuty(backgroundScope, host)
        runCurrent()

        assertThat(gps.activeCollectors).isEqualTo(0)
        assertThat(state.state.value.gpsOn).isFalse()
        assertThat(store.size()).isEqualTo(0)
        assertThat(settings.current().onDuty).isFalse()
        assertThat(host.stopped).isTrue()
    }

    @Test
    fun `going off duty when tracking wasn't running still sends what's saved (T-17)`() = runTest {
        store.add(listOf(reading(1_000)))

        engine().goOffDuty(backgroundScope, host)

        assertThat(api.sentBatches.flatten()).hasSize(1)
        assertThat(host.stopped).isTrue()
    }

    @Test
    fun `a late answer from Suprah after going off duty doesn't switch GPS back on (T-17)`() = runTest {
        val engine = started()
        runCurrent()

        engine.goOffDuty(backgroundScope, host)
        engine.onStatus(approvedWithLoad)
        runCurrent()

        assertThat(gps.activeCollectors).isEqualTo(0)
        assertThat(state.state.value.gpsOn).isFalse()
    }

    @Test
    fun `unlinking from this phone during the last send ends quietly, without an alert`() = runTest {
        store.add(listOf(reading(1_000)))
        settings.clearPairing()

        engine().goOffDuty(backgroundScope, host)

        assertThat(host.alerts).isEmpty()
        assertThat(host.stopped).isTrue()
    }

    @Test
    fun `no pairing while on duty means the phone was unlinked (T-13)`() = runTest {
        started()
        runCurrent()
        settings.flow.value = settings.flow.value.copy(credentials = null)

        advanceTimeBy(UploadPolicy.STATUS_INTERVAL_MS + 1)

        assertThat(host.alerts).containsExactly(TrackingAlert.UNLINKED)
        assertThat(host.stopped).isTrue()
    }

    @Test
    fun `another refusal shows Suprah's own words (T-13)`() = runTest {
        api.statusAnswers += ApiResult.Refused(403, "driver_inactive", "Your driver account isn't active.")

        started()
        runCurrent()

        assertThat(state.state.value.problem).isEqualTo(TrackingProblem.Server("Your driver account isn't active.", 403))
        assertThat(host.stopped).isFalse()
    }

    @Test
    fun `a new GPS interval from Suprah restarts GPS at that interval (T-6)`() = runTest {
        api.defaultStatus = approvedWithLoad.copy(locationIntervalSeconds = 15)
        started()
        runCurrent()

        api.defaultStatus = approvedWithLoad.copy(locationIntervalSeconds = 5)
        advanceTimeBy(UploadPolicy.STATUS_INTERVAL_MS + 1)

        assertThat(gps.requestedIntervals).containsExactly(15, 5).inOrder()
        assertThat(gps.activeCollectors).isEqualTo(1)
    }
}
