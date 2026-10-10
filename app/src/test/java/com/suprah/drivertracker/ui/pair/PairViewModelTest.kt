package com.suprah.drivertracker.ui.pair

import com.google.common.truth.Truth.assertThat
import com.suprah.drivertracker.core.AppConfig
import com.suprah.drivertracker.domain.ApiResult
import com.suprah.drivertracker.domain.Approval
import com.suprah.drivertracker.domain.DeviceCredentials
import com.suprah.drivertracker.domain.Pairing
import com.suprah.drivertracker.testing.FakeSettingsRepository
import com.suprah.drivertracker.testing.FakeTrackerApi
import com.suprah.drivertracker.testing.MainDispatcherRule
import com.suprah.drivertracker.testing.approvedWithLoad
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** Spec section 4: pairing. */
class PairViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val api = FakeTrackerApi()
    private val settings =
        FakeSettingsRepository().apply {
            flow.value = flow.value.copy(serverUrl = "http://localhost:5000", credentials = null, onDuty = false)
        }
    private val dev = AppConfig("http://localhost:5000", serverEditable = true, allowHttp = true, "https://portal", "1.0.0", "Xiaomi 2211")
    private val prod = dev.copy(defaultServerUrl = "https://api.suprah-app.com", serverEditable = false, allowHttp = false)

    private fun viewModel(config: AppConfig = dev) = PairViewModel(api, settings, config)

    @Test
    fun `the code is upper-cased, at most 8 letters or digits, separators ignored (P-3)`() {
        val viewModel = viewModel()

        viewModel.onCodeChange("abcd-2345")
        viewModel.onCodeChange("ABCD-23456")

        val state = viewModel.state.value
        assertThat(state.code).isEqualTo("ABCD-2345")
        assertThat(state.cleanCode).isEqualTo("ABCD2345")
        assertThat(state.canPair).isTrue()
    }

    @Test
    fun `pair is only possible with exactly 8 characters (P-3)`() {
        val viewModel = viewModel()

        viewModel.onCodeChange("ABC123")
        viewModel.pair()

        assertThat(viewModel.state.value.canPair).isFalse()
        assertThat(api.pairCalls).isEmpty()
    }

    @Test
    fun `success saves the pairing and the server used (P-5, P-6)`() = runTest {
        val viewModel = viewModel()
        viewModel.editServer()
        viewModel.onServerChange("http://192.168.1.20:5000/")
        viewModel.onCodeChange("ABCD2345")
        api.pairAnswers += ApiResult.Ok(Pairing(DeviceCredentials("dev-9", "k-9"), approvedWithLoad.copy(approval = Approval.WAITING)))

        viewModel.pair()

        assertThat(api.pairCalls.single()).isEqualTo("http://192.168.1.20:5000" to "ABCD2345")
        val saved = settings.current()
        assertThat(saved.credentials).isEqualTo(DeviceCredentials("dev-9", "k-9"))
        assertThat(saved.approval).isEqualTo(Approval.WAITING)
        assertThat(saved.serverUrl).isEqualTo("http://192.168.1.20:5000/")
        assertThat(viewModel.state.value.busy).isFalse()
        assertThat(viewModel.state.value.error).isNull()
    }

    @Test
    fun `the server address must start with http or https in dev builds (P-4)`() {
        val viewModel = viewModel()
        viewModel.editServer()
        viewModel.onServerChange("192.168.1.20:5000")
        viewModel.onCodeChange("ABCD2345")

        viewModel.pair()

        assertThat(viewModel.state.value.error).isEqualTo(PairError.Scheme)
        assertThat(api.pairCalls).isEmpty()
    }

    @Test
    fun `the live build always pairs with the live server (B-2)`() {
        val viewModel = viewModel(prod)
        viewModel.onCodeChange("ABCD2345")

        viewModel.pair()

        assertThat(api.pairCalls.single().first).isEqualTo("https://api.suprah-app.com")
    }

    @Test
    fun `a refusal shows Suprah's message, no connection shows the offline text (P-7)`() {
        val viewModel = viewModel()
        viewModel.onCodeChange("ABCD2345")
        api.pairAnswers += ApiResult.Refused(404, null, "That code has expired.")
        api.pairAnswers += ApiResult.Offline()

        viewModel.pair()
        val refused = viewModel.state.value.error
        viewModel.pair()
        val offline = viewModel.state.value.error

        assertThat(refused).isEqualTo(PairError.Server("That code has expired.", 404))
        assertThat(offline).isEqualTo(PairError.Offline)
        assertThat(settings.flow.value.isPaired).isFalse()
    }
}
