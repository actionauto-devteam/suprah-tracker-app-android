package com.suprah.drivertracker.permissions

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Spec H-7: the phone-settings checks on real Android versions. */
@RunWith(RobolectricTestRunner::class)
class AndroidPhoneSettingsCheckerTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val checker = AndroidPhoneSettingsChecker(app)

    @Test
    fun `nothing allowed yet - tracking can't start`() {
        val status = checker.status()

        assertThat(status.preciseLocation).isFalse()
        assertThat(status.canTrack).isFalse()
        assertThat(checker.hasPreciseLocation()).isFalse()
    }

    @Test
    fun `from Android 10, all the time needs background location`() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertThat(checker.status().allTheTime).isFalse()

        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        assertThat(checker.status().allTheTime).isTrue()
    }

    @Test
    @Config(sdk = [28])
    fun `before Android 10, location access already counts as all the time`() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        val status = checker.status()

        assertThat(status.preciseLocation).isTrue()
        assertThat(status.allTheTime).isTrue()
    }

    @Test
    @Config(sdk = [28])
    fun `before Android 13, notifications need no permission`() {
        assertThat(checker.status().notifications).isTrue()
    }

    @Test
    fun `not a Xiaomi phone - no Autostart row`() {
        val status = checker.status()

        assertThat(status.xiaomi).isFalse()
        assertThat(status.autostart).isNull()
    }
}
