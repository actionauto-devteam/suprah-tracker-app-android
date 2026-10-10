package com.suprah.drivertracker.tracking

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.suprah.drivertracker.domain.Approval
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Spec T-13, T-14, T-16: the wording drivers see, from the app's real text resources. */
@RunWith(RobolectricTestRunner::class)
class TrackingNotificationsTest {
    private val resources: Resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    private fun content(state: TrackerState) = TrackingNotifications.content(resources, state)

    @Test
    fun `before Suprah first answers, the Checking notification stays (T-16)`() {
        assertThat(content(TrackerState())).isNull()
    }

    @Test
    fun `notification text follows the first matching state (T-16)`() {
        assertThat(content(TrackerState(approval = Approval.WAITING)))
            .isEqualTo("On duty · waiting for approval" to "Dispatch needs to approve this phone. Tracking starts as soon as they do.")
        assertThat(content(TrackerState(approval = Approval.APPROVED, gpsOn = true)))
            .isEqualTo("Sharing your location" to "Starting…")
        assertThat(content(TrackerState(approval = Approval.APPROVED, gpsOn = true, lastSentAt = 1_000))!!.second)
            .startsWith("Last sent ")
        assertThat(content(TrackerState(approval = Approval.APPROVED, gpsOn = false)))
            .isEqualTo("On duty · no active load" to "Location is paused until you have an accepted load.")
        assertThat(content(TrackerState(approval = Approval.APPROVED, gpsOn = true, problem = TrackingProblem.TrackingOff)))
            .isEqualTo(
                "On duty" to
                    "Your company has switched phone tracking off for now, so your location isn't being shared. " +
                    "You don't need to do anything; it starts again when they switch it back on.",
            )
    }

    @Test
    fun `problem wording, with counts in the singular and plural (T-13, T-14)`() {
        fun text(problem: TrackingProblem) = ProblemText.describe(resources, problem)

        assertThat(text(TrackingProblem.SendOffline(1)))
            .isEqualTo(
                "No internet connection. 1 location is saved on this phone and will be sent automatically when you're back online. " +
                    "You don't need to do anything.",
            )
        assertThat(text(TrackingProblem.SendOffline(2641)))
            .isEqualTo(
                "No internet connection. 2641 locations are saved on this phone and will be sent automatically when you're back online. " +
                    "You don't need to do anything.",
            )
        assertThat(text(TrackingProblem.SendSlow(30)))
            .isEqualTo(
                "The connection is slow, so 30 saved locations are taking longer to send. Your current location is still shared first. " +
                    "This can take a few minutes; you don't need to do anything.",
            )
        assertThat(text(TrackingProblem.StatusOffline))
            .isEqualTo(
                "No internet connection. Your locations are saved on this phone and will be sent automatically when you're back online. " +
                    "You don't need to do anything.",
            )
        assertThat(text(TrackingProblem.PermissionOff))
            .isEqualTo(
                "Location permission was turned off, so your location isn't being shared. Open Suprah Tracker and allow location again.",
            )
        assertThat(text(TrackingProblem.Unlinked))
            .isEqualTo(
                "This phone was unlinked from your account, so your location isn't being shared. " +
                    "Pair it again with a new code from the Driver Portal.",
            )
        assertThat(text(TrackingProblem.Server("Your driver account isn't active.", 403)))
            .isEqualTo("Your driver account isn't active.")
        assertThat(text(TrackingProblem.Server(null, 502)))
            .isEqualTo(
                "Suprah couldn't accept this right now (error 502). The app keeps trying, and your locations stay saved on this phone.",
            )
    }
}
