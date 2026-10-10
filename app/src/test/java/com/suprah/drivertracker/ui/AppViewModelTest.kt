@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.suprah.drivertracker.ui

import com.google.common.truth.Truth.assertThat
import com.suprah.drivertracker.domain.Approval
import com.suprah.drivertracker.domain.DeviceCredentials
import com.suprah.drivertracker.testing.FakeSettingsRepository
import com.suprah.drivertracker.testing.MainDispatcherRule
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** Spec P-1 and T-13: pairing until paired, then home; back to pairing after an unlink from anywhere. */
class AppViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test
    fun `follows the saved pairing`() = runTest {
        val settings = FakeSettingsRepository()
        val viewModel = AppViewModel(settings)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.paired.collect {} }

        assertThat(viewModel.paired.value).isTrue()

        settings.clearPairing()
        assertThat(viewModel.paired.value).isFalse()

        settings.savePairing(DeviceCredentials("dev-2", "key-2"), "Jo", Approval.WAITING)
        assertThat(viewModel.paired.value).isTrue()
    }
}
