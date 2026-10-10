package com.suprah.drivertracker.tracking

import com.google.common.truth.Truth.assertThat
import com.suprah.drivertracker.domain.Approval
import com.suprah.drivertracker.testing.approvedWithLoad
import org.junit.Test

/** Spec S-3, T-5, T-9, T-11: the pure rules. */
class PoliciesTest {
    @Test
    fun `batch size halves on a timeout, grows by 10 on success, stays within 10 to 100 (T-11)`() {
        assertThat(UploadPolicy.batchAfterTimeout(100)).isEqualTo(50)
        assertThat(UploadPolicy.batchAfterTimeout(15)).isEqualTo(10)
        assertThat(UploadPolicy.batchAfterSuccess(50)).isEqualTo(60)
        assertThat(UploadPolicy.batchAfterSuccess(95)).isEqualTo(100)
    }

    @Test
    fun `new readings wait only right after no answer (T-9)`() {
        assertThat(UploadPolicy.waitingAfterNoAnswer(nowMs = 0, lastNoAnswerAtMs = null, uploadIntervalSeconds = 30)).isFalse()
        assertThat(UploadPolicy.waitingAfterNoAnswer(nowMs = 10_000, lastNoAnswerAtMs = 0, uploadIntervalSeconds = 30)).isTrue()
        assertThat(UploadPolicy.waitingAfterNoAnswer(nowMs = 30_000, lastNoAnswerAtMs = 0, uploadIntervalSeconds = 30)).isFalse()
    }

    @Test
    fun `GPS only on duty, approved, with an accepted load (S-3, T-5)`() {
        assertThat(GpsPolicy.shouldUseGps(onDuty = true, approvedWithLoad)).isTrue()
        assertThat(GpsPolicy.shouldUseGps(onDuty = false, approvedWithLoad)).isFalse()
        assertThat(GpsPolicy.shouldUseGps(onDuty = true, approvedWithLoad.copy(approval = Approval.WAITING))).isFalse()
        assertThat(GpsPolicy.shouldUseGps(onDuty = true, approvedWithLoad.copy(shouldTrack = false))).isFalse()
    }
}
