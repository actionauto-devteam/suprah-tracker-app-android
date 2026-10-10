@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.suprah.drivertracker.ui.home

import com.google.common.truth.Truth.assertThat
import com.suprah.drivertracker.core.AppConfig
import com.suprah.drivertracker.domain.ApiResult
import com.suprah.drivertracker.domain.Approval
import com.suprah.drivertracker.domain.RefusalReason
import com.suprah.drivertracker.testing.FakePhoneSettings
import com.suprah.drivertracker.testing.FakeSettingsRepository
import com.suprah.drivertracker.testing.FakeTrackerApi
import com.suprah.drivertracker.testing.FakeTrackingControl
import com.suprah.drivertracker.testing.InMemoryPositionStore
import com.suprah.drivertracker.testing.MainDispatcherRule
import com.suprah.drivertracker.testing.approvedWithLoad
import com.suprah.drivertracker.testing.reading
import com.suprah.drivertracker.tracking.TrackerStateHolder
import com.suprah.drivertracker.tracking.TrackingProblem
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** Spec section 5: the home screen. */
class HomeViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val settings = FakeSettingsRepository()
    private val store = InMemoryPositionStore()
    private val api = FakeTrackerApi()
    private val tracker = TrackerStateHolder()
    private val phone = FakePhoneSettings()
    private val tracking = FakeTrackingControl()
    private val config = AppConfig("http://localhost:5000", true, true, "https://suprah-app.com/driver", "1.0.0")

    private fun viewModel() = HomeViewModel(settings, store, api, tracker, phone, tracking, config)

    /** The screen's state only updates while someone watches it; this watches from the start. */
    private fun TestScope.watched(viewModel: HomeViewModel) =
        viewModel.also { backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { it.state.collect {} } }

    @Test
    fun `opening the app resumes tracking that should be running (H-6)`() = runTest {
        viewModel().onResume()

        assertThat(tracking.calls).containsExactly("resume")
    }

    @Test
    fun `opening the app doesn't restart tracking that's running, or when off duty (H-6)`() = runTest {
        tracker.update { copy(serviceRunning = true) }
        viewModel().onResume()

        tracker.update { copy(serviceRunning = false) }
        settings.setOnDuty(false)
        viewModel().onResume()

        assertThat(tracking.calls).isEmpty()
    }

    @Test
    fun `the duty button starts or stops tracking from the saved state (H-3)`() = runTest {
        val viewModel = viewModel()

        viewModel.toggleDuty()
        settings.setOnDuty(false)
        viewModel.toggleDuty()

        assertThat(tracking.calls).containsExactly("off", "on").inOrder()
    }

    @Test
    fun `starting needs approval and location, going off duty is always possible (H-3)`() = runTest {
        val viewModel = watched(viewModel())
        settings.setOnDuty(false)
        settings.updateFromStatus("Jo", Approval.WAITING)
        assertThat(viewModel.state.value.dutyEnabled).isFalse()

        settings.updateFromStatus("Jo", Approval.APPROVED)
        assertThat(viewModel.state.value.dutyEnabled).isTrue()

        phone.precise = false
        viewModel.refreshPhoneSettings()
        assertThat(viewModel.state.value.dutyEnabled).isFalse()

        settings.setOnDuty(true)
        assertThat(viewModel.state.value.dutyEnabled).isTrue()
    }

    @Test
    fun `the status check updates the name, approval and loads (H-5)`() = runTest {
        val viewModel = watched(viewModel())
        api.statusAnswers += ApiResult.Ok(approvedWithLoad.copy(driverName = "Jo Rivera", activeLoadCount = 2))

        viewModel.checkStatus()

        val state = viewModel.state.value
        assertThat(state.driverName).isEqualTo("Jo Rivera")
        assertThat(state.loads).isEqualTo(2)
        assertThat(state.problem).isNull()
    }

    @Test
    fun `no connection is shown, and an unlinked phone goes back to pairing (H-5)`() = runTest {
        val viewModel = watched(viewModel())
        api.statusAnswers += ApiResult.Offline()
        viewModel.checkStatus()
        assertThat(viewModel.state.value.problem).isEqualTo(HomeProblem.CheckOffline)

        api.statusAnswers += ApiResult.Refused(401, RefusalReason.DEVICE_UNLINKED, "Unlinked")
        viewModel.checkStatus()
        assertThat(settings.current().isPaired).isFalse()
    }

    @Test
    fun `a tracking problem is shown before a status-check problem`() = runTest {
        val viewModel = watched(viewModel())
        api.statusAnswers += ApiResult.Offline()
        viewModel.checkStatus()

        tracker.update { copy(problem = TrackingProblem.TrackingOff) }

        assertThat(viewModel.state.value.problem).isEqualTo(HomeProblem.Tracking(TrackingProblem.TrackingOff))
    }

    @Test
    fun `saved readings are counted (H-4)`() = runTest {
        val viewModel = watched(viewModel())

        store.add(listOf(reading(1_000), reading(2_000)))

        assertThat(viewModel.state.value.saved).isEqualTo(2)
    }

    @Test
    fun `unlinking goes off duty, tells Suprah, deletes saved readings, forgets the pairing (H-9)`() = runTest {
        val viewModel = watched(viewModel())
        store.add(listOf(reading(1_000)))

        viewModel.askToUnlink()
        assertThat(viewModel.state.value.confirmingUnlink).isTrue()
        viewModel.unlink()

        assertThat(tracking.calls).containsExactly("off")
        assertThat(api.unlinkCalls).isEqualTo(1)
        assertThat(store.size()).isEqualTo(0)
        assertThat(settings.current().isPaired).isFalse()
        assertThat(viewModel.state.value.unlinking).isFalse()
    }

    @Test
    fun `waiting for approval it checks every 5 seconds, once approved every minute (H-5)`() = runTest {
        val viewModel = viewModel()
        settings.updateFromStatus("Jo", Approval.WAITING)
        api.defaultStatus = approvedWithLoad.copy(approval = Approval.WAITING)

        backgroundScope.launch { viewModel.keepStatusFresh() }
        runCurrent()
        assertThat(api.statusCalls).isEqualTo(1)

        advanceTimeBy(5_001)
        assertThat(api.statusCalls).isEqualTo(2)

        // Dispatch approves: the next check (5 s later) sees it.
        api.defaultStatus = approvedWithLoad
        advanceTimeBy(5_001)
        assertThat(api.statusCalls).isEqualTo(3)
        assertThat(settings.current().approval).isEqualTo(Approval.APPROVED)

        // Approved: back to once a minute.
        advanceTimeBy(30_000)
        assertThat(api.statusCalls).isEqualTo(3)
        advanceTimeBy(30_001)
        assertThat(api.statusCalls).isEqualTo(4)
    }
}
