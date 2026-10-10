package com.suprah.drivertracker.data.remote

import com.suprah.drivertracker.data.remote.dto.ErrorBody
import com.suprah.drivertracker.data.remote.dto.PairRequest
import com.suprah.drivertracker.data.remote.dto.PositionData
import com.suprah.drivertracker.data.remote.dto.PositionsRequest
import com.suprah.drivertracker.domain.ApiResult
import com.suprah.drivertracker.domain.AppStatus
import com.suprah.drivertracker.domain.Approval
import com.suprah.drivertracker.domain.DeviceCredentials
import com.suprah.drivertracker.domain.Pairing
import com.suprah.drivertracker.domain.Position
import com.suprah.drivertracker.domain.TrackerApi
import com.suprah.drivertracker.domain.UploadResult
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException

class RetrofitTrackerApi @Inject constructor(
    private val service: TrackerApiService,
    private val json: Json,
) : TrackerApi {
    override suspend fun pair(serverUrl: String, code: String, deviceName: String, appVersion: String): ApiResult<Pairing> = call {
        val body = PairRequest(code = code, deviceName = deviceName, platform = PLATFORM, appVersion = appVersion)
        val data = service.pair(endpoint(serverUrl, "pair"), body).data ?: throw MissingDataException()
        Pairing(
            credentials = DeviceCredentials(data.deviceId, data.deviceKey),
            status =
            statusOf(
                data.status,
                data.driverName,
                data.shouldTrack,
                data.activeLoadCount,
                data.uploadIntervalSeconds,
                data.locationIntervalSeconds,
            ),
        )
    }

    override suspend fun status(serverUrl: String, credentials: DeviceCredentials): ApiResult<AppStatus> = call {
        val data = service.status(endpoint(serverUrl, "status"), credentials.header()).data ?: throw MissingDataException()
        statusOf(
            data.status,
            data.driverName,
            data.shouldTrack,
            data.activeLoadCount,
            data.uploadIntervalSeconds,
            data.locationIntervalSeconds,
        )
    }

    override suspend fun sendPositions(
        serverUrl: String,
        credentials: DeviceCredentials,
        positions: List<Position>,
    ): ApiResult<UploadResult> = call {
        val body = PositionsRequest(positions.map { it.toData() })
        val data =
            service.sendPositions(endpoint(serverUrl, "positions"), credentials.header(), body).data
                ?: throw MissingDataException()
        UploadResult(
            received = data.received,
            accepted = data.accepted,
            status =
            statusOf(
                data.status,
                data.driverName,
                data.shouldTrack,
                data.activeLoadCount,
                data.uploadIntervalSeconds,
                data.locationIntervalSeconds,
            ),
        )
    }

    override suspend fun unlink(serverUrl: String, credentials: DeviceCredentials): ApiResult<Unit> =
        call { service.unlink(endpoint(serverUrl, "device"), credentials.header()) }

    /** Turns every way a call can end into an [ApiResult]; never throws (except cancellation). */
    private suspend fun <T> call(block: suspend () -> T): ApiResult<T> = try {
        ApiResult.Ok(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: HttpException) {
        refused(error)
    } catch (_: SocketTimeoutException) {
        ApiResult.Offline(timedOut = true)
    } catch (_: IOException) {
        ApiResult.Offline()
    } catch (_: Exception) {
        // An answer the app can't read (for example a proxy's error page): treat it like no answer.
        ApiResult.Offline()
    }

    private fun refused(error: HttpException): ApiResult.Refused {
        val body =
            error.response()?.errorBody()?.string()?.let { text ->
                runCatching { json.decodeFromString<ErrorBody>(text) }.getOrNull()
            }
        return ApiResult.Refused(
            httpStatus = error.code(),
            reason = body?.reason?.takeIf { it.isNotBlank() },
            message = body?.message?.takeIf { it.isNotBlank() },
        )
    }

    private class MissingDataException : IOException("Suprah's answer had no data")

    companion object {
        const val PLATFORM = "android"

        fun endpoint(serverUrl: String, path: String) = "${serverUrl.trimEnd('/')}/api/tracking-app/$path"

        private fun DeviceCredentials.header() = "Device $deviceId:$deviceKey"

        private fun Position.toData() = PositionData(
            lat = lat,
            lng = lng,
            measuredAt = Instant.ofEpochMilli(measuredAtMs).toString(),
            accuracy = accuracy?.toDouble(),
            speed = speed?.toDouble(),
            heading = heading?.toDouble(),
        )

        @Suppress("LongParameterList")
        private fun statusOf(
            status: String?,
            driverName: String?,
            shouldTrack: Boolean,
            activeLoadCount: Int,
            uploadIntervalSeconds: Int?,
            locationIntervalSeconds: Int?,
        ) = AppStatus(
            approval = Approval.fromWire(status),
            driverName = driverName?.takeIf { it.isNotBlank() } ?: "Driver",
            shouldTrack = shouldTrack,
            activeLoadCount = activeLoadCount,
            uploadIntervalSeconds = (uploadIntervalSeconds ?: DEFAULT_UPLOAD_SECONDS).coerceIn(10, 300),
            locationIntervalSeconds = (locationIntervalSeconds ?: DEFAULT_LOCATION_SECONDS).coerceIn(5, 120),
        )

        private const val DEFAULT_UPLOAD_SECONDS = 30
        private const val DEFAULT_LOCATION_SECONDS = 15
    }
}
