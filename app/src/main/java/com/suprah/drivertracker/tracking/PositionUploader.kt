package com.suprah.drivertracker.tracking

import com.suprah.drivertracker.core.Clock
import com.suprah.drivertracker.data.positions.PositionStore
import com.suprah.drivertracker.data.settings.SettingsRepository
import com.suprah.drivertracker.domain.ApiResult
import com.suprah.drivertracker.domain.AppStatus
import com.suprah.drivertracker.domain.RefusalReason
import com.suprah.drivertracker.domain.TrackerApi
import com.suprah.drivertracker.domain.UploadResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the uploader needs from whoever runs it. */
interface UploadCallbacks {
    /** Every successful send carries Suprah's latest status. */
    suspend fun onStatus(status: AppStatus)

    /** A refusal other than "waiting for approval" or a bad batch. Returns true when tracking should stop. */
    suspend fun onRefused(refusal: ApiResult.Refused): Boolean

    /** The phone has no credentials any more (unpaired while sending). */
    suspend fun onMissingCredentials()
}

/**
 * Sends saved readings (spec T-9 to T-14).
 *
 * - Oldest first, in batches of up to 100.
 * - While there's a backlog, the newest reading goes first on its own so Dispatch sees where the driver
 *   is now; it's sent again later with the others and Suprah keeps it once.
 * - A batch that times out is retried smaller (down to 10) and grows back after successes, so a slow
 *   connection still gets through.
 */
class PositionUploader(
    private val store: PositionStore,
    private val api: TrackerApi,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val state: TrackerStateHolder,
    private val callbacks: UploadCallbacks,
) {
    private val sendLock = Mutex()
    private val liveLock = Mutex()

    @Volatile var uploadIntervalSeconds = UploadPolicy.DEFAULT_UPLOAD_INTERVAL_SECONDS

    @Volatile var batchSize = UploadPolicy.MAX_BATCH
        private set

    /** When the newest reading sent ahead of a backlog was measured. */
    @Volatile private var liveSentAtMs = 0L

    /** When Suprah last didn't answer. */
    @Volatile private var lastNoAnswerAtMs: Long? = null

    /** A new reading arrived: send it now (spec T-9), unless Suprah just didn't answer. */
    fun sendSoon(scope: CoroutineScope) {
        if (UploadPolicy.waitingAfterNoAnswer(clock.nowMillis(), lastNoAnswerAtMs, uploadIntervalSeconds)) return
        when {
            !sendLock.isLocked -> scope.launch { sendQueued() }
            !liveLock.isLocked -> scope.launch { sendNewest() }
        }
    }

    /** Sends everything saved, oldest first. */
    suspend fun sendQueued(): Unit = sendLock.withLock {
        store.dropMeasuredBefore(clock.nowMillis() - UploadPolicy.MAX_READING_AGE_MS)
        while (true) {
            val batch = store.oldest(batchSize)
            if (batch.isEmpty()) break
            val current = settings.current()
            val credentials = current.credentials
            if (credentials == null) {
                callbacks.onMissingCredentials()
                return@withLock
            }

            val newestAt = store.newest()?.position?.measuredAtMs
            if (newestAt != null && newestAt > batch.last().position.measuredAtMs && newestAt > liveSentAtMs) {
                // A refusal is handled with the batch below, which gets the same answer.
                if (sendNewest() is ApiResult.Offline) break
            }

            when (val result = api.sendPositions(current.serverUrl, credentials, batch.map { it.position })) {
                is ApiResult.Ok -> {
                    store.remove(batch.map { it.id })
                    lastNoAnswerAtMs = null
                    batchSize = UploadPolicy.batchAfterSuccess(batchSize)
                    markSent()
                    state.update { copy(problem = null) }
                    callbacks.onStatus(result.value.status)
                }

                is ApiResult.Refused -> {
                    when {
                        // Not approved yet: Suprah won't use these readings, so don't keep them.
                        result.reason == RefusalReason.WAITING_FOR_APPROVAL -> store.clear()
                        // A malformed batch would block the queue forever.
                        result.httpStatus == HTTP_BAD_REQUEST -> store.remove(batch.map { it.id })
                        else -> if (callbacks.onRefused(result)) return@withLock
                    }
                    break
                }

                is ApiResult.Offline -> {
                    noAnswer(result)
                    break
                }
            }
        }
    }

    /** Sends the current position on its own, ahead of older readings (spec T-10). */
    suspend fun sendNewest(): ApiResult<UploadResult>? = liveLock.withLock {
        val newest = store.newest() ?: return@withLock null
        if (newest.position.measuredAtMs <= liveSentAtMs) return@withLock null
        val current = settings.current()
        val credentials = current.credentials ?: return@withLock null
        val result = api.sendPositions(current.serverUrl, credentials, listOf(newest.position))
        when (result) {
            is ApiResult.Ok -> {
                liveSentAtMs = newest.position.measuredAtMs
                lastNoAnswerAtMs = null
                markSent()
                callbacks.onStatus(result.value.status)
            }

            is ApiResult.Offline -> noAnswer(result)

            is ApiResult.Refused -> Unit
        }
        result
    }

    private suspend fun markSent() {
        val now = clock.nowMillis()
        settings.setLastSentAt(now)
        state.update { copy(lastSentAt = now) }
    }

    /** No answer: smaller batches if Suprah was too slow; the readings stay saved either way (spec T-11, T-14). */
    private suspend fun noAnswer(result: ApiResult.Offline) {
        lastNoAnswerAtMs = clock.nowMillis()
        val saved = store.size()
        if (result.timedOut) {
            batchSize = UploadPolicy.batchAfterTimeout(batchSize)
            state.update { copy(problem = TrackingProblem.SendSlow(saved)) }
        } else {
            state.update { copy(problem = TrackingProblem.SendOffline(saved)) }
        }
    }

    private companion object {
        const val HTTP_BAD_REQUEST = 400
    }
}
