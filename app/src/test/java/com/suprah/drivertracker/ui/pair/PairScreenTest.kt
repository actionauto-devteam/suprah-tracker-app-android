package com.suprah.drivertracker.ui.pair

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import com.suprah.drivertracker.ui.theme.TrackerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Spec P-1 to P-7: what the pairing screen shows. */
@RunWith(RobolectricTestRunner::class)
class PairScreenTest {
    @get:Rule val compose = createComposeRule()

    private var paired = 0

    private fun show(state: PairUiState) {
        compose.setContent {
            TrackerTheme {
                PairScreen(state = state, onCodeChange = {}, onPair = { paired++ }, onEditServer = {}, onServerChange = {})
            }
        }
    }

    @Test
    fun `intro and instructions are shown (P-2)`() {
        show(PairUiState())

        compose.onNodeWithText("Driver Tracker").assertIsDisplayed()
        compose.onNodeWithText(
            "In the Driver Portal, open Settings → Phone tracking and tap Set up phone tracking. Enter the 8-character code it shows.",
        ).assertIsDisplayed()
    }

    @Test
    fun `Pair is enabled only with 8 characters (P-3)`() {
        show(PairUiState(code = "ABCD-234"))
        compose.onAllNodesWithText("Pair this phone")[1].assertIsNotEnabled()
    }

    @Test
    fun `Pair with a full code pairs (P-3)`() {
        show(PairUiState(code = "ABCD-2345"))

        compose.onAllNodesWithText("Pair this phone")[1].assertIsEnabled().performClick()

        assertThat(paired).isEqualTo(1)
    }

    @Test
    fun `dev builds show the full server address, live builds show none (B-2, P-4)`() {
        show(PairUiState(serverUrl = "http://localhost:5000", serverEditable = true))
        compose.onNodeWithText("Server: http://localhost:5000 · Change").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the live build has no server address to change (B-2)`() {
        show(PairUiState(serverUrl = "https://api.suprah-app.com", serverEditable = false))
        assertThat(compose.onAllNodesWithText("Change", substring = true).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `errors are put into words (P-7)`() {
        show(PairUiState(code = "ABCD2345", error = PairError.Offline))
        compose.onNodeWithText("Couldn't reach Suprah. Check your internet connection and try again.").assertIsDisplayed()
    }

    @Test
    fun `Suprah's own message is shown for a refusal (P-7)`() {
        show(PairUiState(code = "ABCD2345", error = PairError.Server("That code has expired.", 404)))
        compose.onNodeWithText("That code has expired.").assertIsDisplayed()
    }
}
