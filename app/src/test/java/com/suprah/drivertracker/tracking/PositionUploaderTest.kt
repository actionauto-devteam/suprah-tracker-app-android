@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.suprah.drivertracker.tracking

import com.google.common.truth.Truth.assertThat
import com.suprah.drivertracker.domain.ApiResult
import com.suprah.drivertracker.domain.AppStatus
import com.suprah.drivertracker.domain.RefusalReason
import com.suprah.drivertracker.domain.UploadResult
import com.suprah.drivertracker.testing.FakeClock
import com.suprah.drivertracker.testing.FakeSettingsRepository
import com.suprah.drivertracker.testing.FakeTrackerApi
import com.suprah.drivertracker.testing.InMemoryPositionStore
import com.suprah.drivertracker.testing.approvedWithLoad
import com.suprah.drivertracker.testing.reading
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Spec T-9 to T-14: how saved readings are sent. */
class PositionUploaderTest {
    private val api = FakeTrackerApi()
    private val store = InMemoryPositionStore()
    private val settings = FakeSettingsRepository()
    private val state = TrackerStateHolder()
    private var now = 100L * 60 * 60_000
    private var saved = 0
    private val statuses = mutableListOf<AppStatus>()
    private val refusals = mutableListOf<ApiResult.Refused>()
    private var stopOnRefusal = false
    private var missingCredentials = false

    private val uploader =
        PositionUploader(
            store,
            api,
            settings,
            FakeClock { now },
            state,
            object : UploadCallbacks {
                override suspend fun onStatus(status: AppStatus) {
                    statuses += status
                }

                override suspend fun onRefused(refusal: ApiResult.Refused): Boolean {
                    refusals += refusal
                    return stopOnRefusal
                }

                override suspend fun onMissingCredentials() {
                    missingCredentials = true
                }
            },
        )

    /** Saves [count] readings measured a second apart, each newer than the last. */
    private suspend fun save(count: Int) = store.add((1..count).map { reading(timeOf(++saved)) })

    private fun timeOf(index: Int) = now - 60 * 60_000 + index * 1_000L

    private val ok = ApiResult.Ok(UploadResult(1, 1, approvedWithLoad))

    @Test
    fun `sends oldest first in batches of 100, with the newest ahead of a backlog (T-10)`() = runTest {
        save(250)

        uploader.sendQueued()

        assertThat(api.sentBatches.map { it.size }).containsExactly(1, 100, 100, 50).inOrder()
        assertThat(api.sentBatches[0].single().measuredAtMs).isEqualTo(timeOf(250))
        assertThat(api.sentBatches[1].first().measuredAtMs).isEqualTo(timeOf(1))
        assertThat(api.sentBatches[2].first().measuredAtMs).isEqualTo(timeOf(101))
        assertThat(store.size()).isEqualTo(0)
        assertThat(settings.current().lastSentAt).isEqualTo(now)
        assertThat(state.state.value.lastSentAt).isEqualTo(now)
        assertThat(statuses).hasSize(4)
    }

    @Test
    fun `everything fits in one batch - no separate send for the newest`() = runTest {
        save(5)

        uploader.sendQueued()

        assertThat(api.sentBatches.map { it.size }).containsExactly(5)
    }

    @Test
    fun `a slow answer halves the batch, successes grow it back, never below 10 (T-11)`() = runTest {
        save(5)
        api.sendAnswers += ApiResult.Offline(timedOut = true)

        uploader.sendQueued()

        assertThat(uploader.batchSize).isEqualTo(50)
        assertThat(state.state.value.problem).isEqualTo(TrackingProblem.SendSlow(5))
        assertThat(store.size()).isEqualTo(5)

        uploader.sendQueued()

        assertThat(uploader.batchSize).isEqualTo(60)
        assertThat(state.state.value.problem).isNull()
        assertThat(store.size()).isEqualTo(0)

        repeat(6) {
            save(1)
            api.sendAnswers += ApiResult.Offline(timedOut = true)
            uploader.sendQueued()
        }
        assertThat(uploader.batchSize).isEqualTo(UploadPolicy.MIN_BATCH)
    }

    @Test
    fun `with no connection the readings stay saved and sending stops (T-14)`() = runTest {
        save(5)
        api.sendAnswers += ApiResult.Offline()

        uploader.sendQueued()

        assertThat(api.sentBatches).hasSize(1)
        assertThat(store.size()).isEqualTo(5)
        assertThat(state.state.value.problem).isEqualTo(TrackingProblem.SendOffline(5))
    }

    @Test
    fun `waiting for approval deletes the saved readings (T-13)`() = runTest {
        save(5)
        api.sendAnswers += ApiResult.Refused(403, RefusalReason.WAITING_FOR_APPROVAL, "Waiting")

        uploader.sendQueued()

        assertThat(store.size()).isEqualTo(0)
        assertThat(refusals).isEmpty()
    }

    @Test
    fun `a bad batch is dropped so it can't block the rest (T-13)`() = runTest {
        save(150)
        api.sendAnswers += ok
        api.sendAnswers += ApiResult.Refused(400, null, "Bad batch")

        uploader.sendQueued()

        assertThat(store.size()).isEqualTo(50)
    }

    @Test
    fun `other refusals go to the engine, which can stop sending (T-13)`() = runTest {
        save(5)
        stopOnRefusal = true
        api.sendAnswers += ApiResult.Refused(401, RefusalReason.DEVICE_UNLINKED, "Unlinked")

        uploader.sendQueued()

        assertThat(refusals.single().reason).isEqualTo(RefusalReason.DEVICE_UNLINKED)
        assertThat(api.sentBatches).hasSize(1)
    }

    @Test
    fun `readings older than 23 hours are dropped before sending (T-12)`() = runTest {
        store.add(listOf(reading(now - 24 * 60 * 60_000L), reading(now - 60_000)))

        uploader.sendQueued()

        assertThat(api.sentBatches.flatten().map { it.measuredAtMs }).containsExactly(now - 60_000)
    }

    @Test
    fun `a new reading is sent at once, except right after no answer (T-9)`() = runTest {
        save(1)
        api.sendAnswers += ApiResult.Offline()
        uploader.sendQueued()
        assertThat(api.sentBatches).hasSize(1)

        save(1)
        uploader.sendSoon(this)
        runCurrent()
        assertThat(api.sentBatches).hasSize(1)

        now += 31_000
        uploader.sendSoon(this)
        runCurrent()
        assertThat(api.sentBatches).hasSize(2)
        assertThat(store.size()).isEqualTo(0)
    }

    @Test
    fun `no credentials while sending means the phone was unpaired`() = runTest {
        save(1)
        settings.clearPairing()

        uploader.sendQueued()

        assertThat(missingCredentials).isTrue()
        assertThat(api.sentBatches).isEmpty()
    }
}
