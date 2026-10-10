package com.suprah.drivertracker.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import com.suprah.drivertracker.domain.Approval
import com.suprah.drivertracker.permissions.PhoneSettingsStatus
import com.suprah.drivertracker.tracking.TrackingProblem
import com.suprah.drivertracker.ui.theme.TrackerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Spec H-1 to H-9: what the home screen shows. */
@RunWith(RobolectricTestRunner::class)
class HomeScreenTest {
    @get:Rule val compose = createComposeRule()

    private val allSet = PhoneSettingsStatus(true, true, true, true, true, xiaomi = false, autostart = null)
    private var toggled = 0
    private var unlinkConfirmed = false

    private fun show(state: HomeUiState) {
        compose.setContent {
            TrackerTheme {
                HomeScreen(
                    state = state,
                    onToggleDuty = { toggled++ },
                    settingsActions = SettingsActions({}, {}, {}, {}, {}, {}),
                    onOpenPortal = {},
                    onAskUnlink = {},
                    onConfirmUnlink = { unlinkConfirmed = true },
                    onCancelUnlink = {},
                )
            }
        }
    }

    private fun shows(text: String) = compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()

    private fun absent(text: String) = compose.onAllNodesWithText(text).assertCountEquals(0)

    @Test
    fun `waiting for approval - status, no load count, Start disabled with the approval hint (H-2, H-3)`() {
        show(HomeUiState(driverName = "Jo Rivera", approval = Approval.WAITING, loads = 1, phone = allSet))

        shows("Jo Rivera")
        shows("Waiting for approval")
        absent("1 accepted load.")
        compose.onNodeWithText("Start on duty").assertIsNotEnabled()
        shows("You can start once Dispatch approves this phone.")
    }

    @Test
    fun `off duty and approved - Start enabled (H-2, H-3)`() {
        show(HomeUiState(approval = Approval.APPROVED, onDuty = false, loads = 0, phone = allSet))

        shows("Off duty")
        shows("No accepted load right now.")
        compose.onNodeWithText("Start on duty").assertIsEnabled().performClick()
        assertThat(toggled).isEqualTo(1)
    }

    @Test
    fun `approved without location - Start disabled with the permissions hint (H-3)`() {
        show(HomeUiState(approval = Approval.APPROVED, phone = allSet.copy(preciseLocation = false)))

        compose.onNodeWithText("Start on duty").assertIsNotEnabled()
        shows("Allow precise location and turn on location services first (below).")
    }

    @Test
    fun `on duty with GPS - sharing, loads, Go off duty always possible (H-2, H-3)`() {
        show(
            HomeUiState(approval = Approval.APPROVED, onDuty = true, gpsOn = true, loads = 2, phone = allSet.copy(preciseLocation = false)),
        )

        shows("Sharing your location")
        shows("2 accepted loads.")
        compose.onNodeWithText("Go off duty").assertIsEnabled()
    }

    @Test
    fun `on duty without a load - location paused (H-2)`() {
        show(HomeUiState(approval = Approval.APPROVED, onDuty = true, gpsOn = false, phone = allSet))

        shows("On duty · no active load")
    }

    @Test
    fun `problems show under the status (H-2)`() {
        show(
            HomeUiState(
                approval = Approval.APPROVED,
                onDuty = true,
                problem = HomeProblem.Tracking(TrackingProblem.SendOffline(3)),
                phone = allSet,
            ),
        )
        compose.onNodeWithText("No internet connection. 3 locations are saved on this phone", substring = true).assertExists()
    }

    @Test
    fun `the status check's problems show too (H-5)`() {
        show(HomeUiState(approval = Approval.APPROVED, problem = HomeProblem.CheckOffline, phone = allSet))

        compose.onNodeWithText("No internet connection right now.", substring = true).assertExists()
    }

    @Test
    fun `info rows show sent, GPS reading with accuracy, and saved count (H-4)`() {
        show(HomeUiState(approval = Approval.APPROVED, lastFixAt = 1_000, lastFixAccuracy = 4.6f, saved = 12, phone = allSet))

        shows("Last location sent")
        compose.onNodeWithText("±4 m", substring = true).assertExists()
        shows("12")
    }

    @Test
    fun `phone settings rows say Done or offer a fix, with the note while something is missing (H-7)`() {
        show(HomeUiState(approval = Approval.APPROVED, phone = allSet.copy(allTheTime = false, batteryUnrestricted = false)))

        shows("Location allowed all the time")
        shows("Allow")
        shows("Fix")
        assertThat(compose.onAllNodesWithText("Done").fetchSemanticsNodes()).hasSize(3)
        shows("Each one helps Android keep tracking running while your screen is locked.")
    }

    @Test
    fun `everything set - no note (H-7)`() {
        show(HomeUiState(approval = Approval.APPROVED, phone = allSet))

        absent("Each one helps Android keep tracking running while your screen is locked.")
        absent("Autostart on (Xiaomi)")
    }

    @Test
    fun `Xiaomi - Autostart row is Check when unknown, Fix when off, Done when on (H-7)`() {
        show(HomeUiState(approval = Approval.APPROVED, phone = allSet.copy(xiaomi = true, autostart = null)))
        shows("Autostart on (Xiaomi)")
        shows("Check")
        shows("Xiaomi phones need Autostart on so tracking comes back by itself after a restart or an app update.")
    }

    @Test
    fun `Xiaomi with Autostart on - Done and no Xiaomi note (H-7)`() {
        show(HomeUiState(approval = Approval.APPROVED, phone = allSet.copy(xiaomi = true, autostart = true)))

        shows("Autostart on (Xiaomi)")
        absent("Check")
        absent("Xiaomi phones need Autostart on so tracking comes back by itself after a restart or an app update.")
    }

    @Test
    fun `Xiaomi with Autostart off - Fix (H-7)`() {
        show(HomeUiState(approval = Approval.APPROVED, phone = allSet.copy(xiaomi = true, autostart = false)))

        shows("Fix")
        shows("Each one helps Android keep tracking running while your screen is locked.")
    }

    @Test
    fun `unlink asks first and confirms (H-9)`() {
        show(HomeUiState(approval = Approval.APPROVED, phone = allSet, confirmingUnlink = true))

        compose.onNodeWithText("Unlink this phone?").assertIsDisplayed()
        val body =
            "Dispatch stops receiving locations from this phone right away. " +
                "You can pair it again later with a new code from the Driver Portal."
        compose.onNodeWithText(body).assertIsDisplayed()
        compose.onNodeWithText("Unlink").performClick()
        assertThat(unlinkConfirmed).isTrue()
    }

    @Test
    fun `catching up after no signal - says what's happening and that nothing is needed (H-4)`() {
        show(HomeUiState(approval = Approval.APPROVED, onDuty = true, gpsOn = true, saved = 515, phone = allSet))

        compose.onNodeWithText("Sending 515 saved locations from earlier.", substring = true).assertExists()
        compose.onNodeWithText("you don't need to do anything", substring = true).assertExists()
    }

    @Test
    fun `a few readings on their way, or a problem message already explaining - no extra note (H-4)`() {
        show(HomeUiState(approval = Approval.APPROVED, onDuty = true, gpsOn = true, saved = 2, phone = allSet))
        absent("Sending 2 saved locations from earlier. Your current location is shared first, so Dispatch still sees where you are.")
    }

    @Test
    fun `while a slow-connection message shows, the note stays hidden (H-4)`() {
        show(
            HomeUiState(
                approval = Approval.APPROVED,
                onDuty = true,
                saved = 515,
                problem = HomeProblem.Tracking(TrackingProblem.SendSlow(515)),
                phone = allSet,
            ),
        )
        assertThat(compose.onAllNodesWithText("Sending 515 saved locations", substring = true).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `off duty with saved readings - says when they'll be sent (H-4)`() {
        show(HomeUiState(approval = Approval.APPROVED, onDuty = false, saved = 12, phone = allSet))

        compose.onNodeWithText("12 saved locations will be sent the next time you go on duty.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the portal link and unlink button are there (H-8, H-9)`() {
        show(HomeUiState(approval = Approval.APPROVED, phone = allSet))

        shows("Open the Driver Portal")
        shows("Unlink this phone")
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals(count: Int) {
    assertThat(fetchSemanticsNodes()).hasSize(count)
}
