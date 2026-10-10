package com.suprah.drivertracker.tracking

import com.suprah.drivertracker.domain.AppStatus
import com.suprah.drivertracker.domain.Approval
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What's happening right now, shared by the tracking service and the screens. */
data class TrackerState(
    /** The tracking service is running (on duty). */
    val serviceRunning: Boolean = false,
    /** GPS is on (on duty, approved, with an accepted load). */
    val gpsOn: Boolean = false,
    /** Null until Suprah answers. */
    val approval: Approval? = null,
    val shouldTrack: Boolean = false,
    val activeLoadCount: Int = 0,
    val lastFixAt: Long = 0L,
    val lastFixAccuracy: Float? = null,
    val lastSentAt: Long = 0L,
    val problem: TrackingProblem? = null,
    /** Suprah said this phone was unlinked: the app goes back to pairing. */
    val unlinked: Boolean = false,
)

/** Something the driver should know about, turned into words by [ProblemText] (spec T-13, T-14). */
sealed interface TrackingProblem {
    data object StatusOffline : TrackingProblem

    data class SendOffline(val saved: Int) : TrackingProblem

    data class SendSlow(val saved: Int) : TrackingProblem

    data object TrackingOff : TrackingProblem

    data object PermissionOff : TrackingProblem

    data object Unlinked : TrackingProblem

    /** Suprah refused for another reason; [message] is Suprah's own words, if any. */
    data class Server(val message: String?, val httpStatus: Int) : TrackingProblem
}

@Singleton
class TrackerStateHolder @Inject constructor() {
    private val mutable = MutableStateFlow(TrackerState())
    val state: StateFlow<TrackerState> = mutable.asStateFlow()

    fun update(change: TrackerState.() -> TrackerState) = mutable.update(change)
}

/** The sending rules (spec T-9 to T-12). */
object UploadPolicy {
    const val MAX_BATCH = 100
    const val MIN_BATCH = 10
    const val BATCH_STEP = 10

    /** Suprah refuses readings older than 24 hours. */
    const val MAX_READING_AGE_MS = 23 * 60 * 60_000L
    const val DEFAULT_UPLOAD_INTERVAL_SECONDS = 30
    const val STATUS_INTERVAL_MS = 60_000L

    fun batchAfterSuccess(size: Int): Int = minOf(MAX_BATCH, size + BATCH_STEP)

    fun batchAfterTimeout(size: Int): Int = maxOf(MIN_BATCH, size / 2)

    /**
     * Right after Suprah didn't answer, new readings wait for the regular retry instead of trying every time.
     * [lastNoAnswerAtMs] is null when Suprah has answered since.
     */
    fun waitingAfterNoAnswer(nowMs: Long, lastNoAnswerAtMs: Long?, uploadIntervalSeconds: Int): Boolean =
        lastNoAnswerAtMs != null && nowMs - lastNoAnswerAtMs < uploadIntervalSeconds * 1000L
}

/** When GPS is on (spec S-3, T-5). */
object GpsPolicy {
    fun shouldUseGps(onDuty: Boolean, status: AppStatus): Boolean = onDuty && status.approval == Approval.APPROVED && status.shouldTrack
}
