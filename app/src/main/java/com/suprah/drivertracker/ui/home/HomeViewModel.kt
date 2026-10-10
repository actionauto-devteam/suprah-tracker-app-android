package com.suprah.drivertracker.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suprah.drivertracker.core.AppConfig
import com.suprah.drivertracker.data.positions.PositionStore
import com.suprah.drivertracker.data.settings.SettingsRepository
import com.suprah.drivertracker.data.settings.TrackerSettings
import com.suprah.drivertracker.domain.ApiResult
import com.suprah.drivertracker.domain.Approval
import com.suprah.drivertracker.domain.RefusalReason
import com.suprah.drivertracker.domain.TrackerApi
import com.suprah.drivertracker.permissions.PhoneSettingsChecker
import com.suprah.drivertracker.permissions.PhoneSettingsStatus
import com.suprah.drivertracker.tracking.TrackerState
import com.suprah.drivertracker.tracking.TrackerStateHolder
import com.suprah.drivertracker.tracking.TrackingControl
import com.suprah.drivertracker.tracking.TrackingProblem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the home screen shows (spec section 5). */
data class HomeUiState(
    val driverName: String = "",
    val onDuty: Boolean = false,
    val approval: Approval = Approval.WAITING,
    val gpsOn: Boolean = false,
    /** Accepted loads, once known. */
    val loads: Int? = null,
    val problem: HomeProblem? = null,
    val lastSentAt: Long = 0L,
    val lastFixAt: Long = 0L,
    val lastFixAccuracy: Float? = null,
    val saved: Int = 0,
    val phone: PhoneSettingsStatus? = null,
    val confirmingUnlink: Boolean = false,
    val unlinking: Boolean = false,
) {
    /** Start needs approval and location; going off duty is always possible (spec H-3). */
    val dutyEnabled: Boolean get() = onDuty || (phone?.canTrack == true && approval == Approval.APPROVED)
}

/** A problem to show on the status card. */
sealed interface HomeProblem {
    data class Tracking(val problem: TrackingProblem) : HomeProblem

    data object CheckOffline : HomeProblem

    data class CheckRefused(val message: String?, val httpStatus: Int) : HomeProblem
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val store: PositionStore,
    private val api: TrackerApi,
    private val tracker: TrackerStateHolder,
    private val phoneSettings: PhoneSettingsChecker,
    private val tracking: TrackingControl,
    private val config: AppConfig,
) : ViewModel() {
    private data class Local(
        val loads: Int? = null,
        val checkProblem: HomeProblem? = null,
        val confirmingUnlink: Boolean = false,
        val unlinking: Boolean = false,
    )

    private val phone = MutableStateFlow(phoneSettings.status())
    private val local = MutableStateFlow(Local())

    val driverPortalUrl: String get() = config.driverPortalUrl

    val state: StateFlow<HomeUiState> =
        combine(settings.settings, tracker.state, store.count, phone, local, ::toUiState)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState())

    private fun toUiState(saved: TrackerSettings, tracking: TrackerState, count: Int, phone: PhoneSettingsStatus, local: Local) =
        HomeUiState(
            driverName = saved.driverName,
            onDuty = saved.onDuty,
            approval = saved.approval,
            gpsOn = tracking.gpsOn,
            loads = if (tracking.serviceRunning && tracking.approval != null) tracking.activeLoadCount else local.loads,
            problem = tracking.problem?.let(HomeProblem::Tracking) ?: local.checkProblem,
            lastSentAt = if (tracking.lastSentAt > 0) tracking.lastSentAt else saved.lastSentAt,
            lastFixAt = tracking.lastFixAt,
            lastFixAccuracy = tracking.lastFixAccuracy,
            saved = count,
            phone = phone,
            confirmingUnlink = local.confirmingUnlink,
            unlinking = local.unlinking,
        )

    /** The screen came to the front: re-read phone settings and carry on tracking if it should be on (spec H-6). */
    fun onResume() {
        refreshPhoneSettings()
        viewModelScope.launch {
            val current = settings.current()
            if (current.onDuty && current.isPaired && phone.value.canTrack && !tracker.state.value.serviceRunning) {
                tracking.resume()
            }
        }
    }

    fun refreshPhoneSettings() {
        phone.value = phoneSettings.status()
    }

    /**
     * While the screen is visible: every 5 s while waiting for approval (so Start on duty unlocks soon after
     * Dispatch approves), otherwise every minute (spec H-5).
     */
    suspend fun keepStatusFresh() {
        while (true) {
            checkStatus()
            delay(if (settings.current().approval == Approval.APPROVED) APPROVED_CHECK_MS else WAITING_CHECK_MS)
        }
    }

    suspend fun checkStatus() {
        val current = settings.current()
        val credentials = current.credentials ?: return
        when (val result = api.status(current.serverUrl, credentials)) {
            is ApiResult.Ok -> {
                settings.updateFromStatus(result.value.driverName, result.value.approval)
                local.update { it.copy(loads = result.value.activeLoadCount, checkProblem = null) }
            }
            is ApiResult.Refused ->
                if (RefusalReason.isUnlinked(result.reason)) {
                    settings.clearPairing()
                } else {
                    local.update { it.copy(checkProblem = HomeProblem.CheckRefused(result.message, result.httpStatus)) }
                }
            is ApiResult.Offline -> local.update { it.copy(checkProblem = HomeProblem.CheckOffline) }
        }
    }

    fun toggleDuty() {
        viewModelScope.launch {
            if (settings.current().onDuty) tracking.goOffDuty() else tracking.goOnDuty()
        }
    }

    fun askToUnlink() = local.update { it.copy(confirmingUnlink = true) }

    fun cancelUnlink() = local.update { it.copy(confirmingUnlink = false) }

    /** Off duty, tell Suprah (best effort), delete saved readings, forget the pairing (spec H-9). */
    fun unlink() {
        local.update { it.copy(confirmingUnlink = false, unlinking = true) }
        viewModelScope.launch {
            val current = settings.current()
            tracking.goOffDuty()
            current.credentials?.let { api.unlink(current.serverUrl, it) }
            store.clear()
            settings.clearPairing()
            local.update { it.copy(unlinking = false) }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val APPROVED_CHECK_MS = 60_000L
        const val WAITING_CHECK_MS = 5_000L
    }
}
