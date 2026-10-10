package com.suprah.drivertracker.domain

/** Whether a dispatcher or admin has approved this phone (spec section 3, `status`). */
enum class Approval(val wire: String) {
    APPROVED("approved"),
    WAITING("waiting_for_approval"),
    ;

    companion object {
        fun fromWire(value: String?): Approval = if (value == APPROVED.wire) APPROVED else WAITING
    }
}

/** What Suprah says about this phone (spec section 3, status fields). */
data class AppStatus(
    val approval: Approval,
    val driverName: String,
    /** True while the driver has an Accepted, Picked Up or In-Transit load (and the phone is approved). */
    val shouldTrack: Boolean,
    val activeLoadCount: Int,
    val uploadIntervalSeconds: Int,
    val locationIntervalSeconds: Int,
)

/** The phone's identity on Suprah after pairing. The key is a secret: never logged or shown. */
data class DeviceCredentials(val deviceId: String, val deviceKey: String) {
    override fun toString(): String = "DeviceCredentials(deviceId=$deviceId, deviceKey=***)"
}

data class Pairing(val credentials: DeviceCredentials, val status: AppStatus)

data class UploadResult(val received: Int, val accepted: Int, val status: AppStatus)

/** One GPS reading (spec T-7). */
data class Position(
    val lat: Double,
    val lng: Double,
    /** When the phone measured it, milliseconds since 1970 (UTC). */
    val measuredAtMs: Long,
    /** Metres. */
    val accuracy: Float? = null,
    /** Metres per second. */
    val speed: Float? = null,
    /** Degrees clockwise from north. */
    val heading: Float? = null,
)

/** A reading saved on the phone, waiting to be sent. */
data class SavedPosition(val id: Long, val position: Position)

/** The outcome of a call to Suprah. */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>

    /** Suprah answered with an error. [reason] is machine-readable (see [RefusalReason]); [message] is for people. */
    data class Refused(val httpStatus: Int, val reason: String?, val message: String?) : ApiResult<Nothing>

    /** No answer: no signal, server down, or [timedOut] (connected, but Suprah answered too slowly). */
    data class Offline(val timedOut: Boolean = false) : ApiResult<Nothing>
}

/** The `reason` values the app acts on (spec section 3). */
object RefusalReason {
    const val TRACKING_APP_OFF = "tracking_app_off"
    const val DEVICE_UNLINKED = "device_unlinked"
    const val DEVICE_UNKNOWN = "device_unknown"
    const val WAITING_FOR_APPROVAL = "waiting_for_approval"

    fun isUnlinked(reason: String?): Boolean = reason == DEVICE_UNLINKED || reason == DEVICE_UNKNOWN
}

/** The Suprah tracking-app endpoints (spec section 3). */
interface TrackerApi {
    suspend fun pair(serverUrl: String, code: String, deviceName: String, appVersion: String): ApiResult<Pairing>

    suspend fun status(serverUrl: String, credentials: DeviceCredentials): ApiResult<AppStatus>

    suspend fun sendPositions(serverUrl: String, credentials: DeviceCredentials, positions: List<Position>): ApiResult<UploadResult>

    suspend fun unlink(serverUrl: String, credentials: DeviceCredentials): ApiResult<Unit>
}
