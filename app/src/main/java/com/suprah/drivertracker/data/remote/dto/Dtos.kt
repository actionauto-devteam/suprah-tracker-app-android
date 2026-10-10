package com.suprah.drivertracker.data.remote.dto

import kotlinx.serialization.Serializable

// Wire formats of suprah-backend/src/controllers/trackingApp.controller.ts (spec section 3).
// Defaults keep the app working if Suprah leaves a field out.

@Serializable
data class Envelope<T>(val data: T? = null)

@Serializable
data class ErrorBody(val reason: String? = null, val message: String? = null)

@Serializable
data class PairRequest(
    val code: String,
    val deviceName: String,
    val platform: String,
    val appVersion: String,
)

@Serializable
data class PairData(
    val deviceId: String,
    val deviceKey: String,
    val status: String? = null,
    val driverName: String? = null,
    val shouldTrack: Boolean = false,
    val activeLoadCount: Int = 0,
    val uploadIntervalSeconds: Int? = null,
    val locationIntervalSeconds: Int? = null,
)

@Serializable
data class StatusData(
    val status: String? = null,
    val driverName: String? = null,
    val shouldTrack: Boolean = false,
    val activeLoadCount: Int = 0,
    val uploadIntervalSeconds: Int? = null,
    val locationIntervalSeconds: Int? = null,
)

@Serializable
data class PositionData(
    val lat: Double,
    val lng: Double,
    /** ISO 8601. */
    val measuredAt: String,
    val accuracy: Double? = null,
    /** Metres per second. */
    val speed: Double? = null,
    /** Degrees. */
    val heading: Double? = null,
)

@Serializable
data class PositionsRequest(val positions: List<PositionData>)

@Serializable
data class UploadData(
    val received: Int = 0,
    val accepted: Int = 0,
    val status: String? = null,
    val driverName: String? = null,
    val shouldTrack: Boolean = false,
    val activeLoadCount: Int = 0,
    val uploadIntervalSeconds: Int? = null,
    val locationIntervalSeconds: Int? = null,
)
