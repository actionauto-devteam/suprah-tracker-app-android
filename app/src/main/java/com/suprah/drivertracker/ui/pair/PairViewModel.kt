package com.suprah.drivertracker.ui.pair

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suprah.drivertracker.core.AppConfig
import com.suprah.drivertracker.data.settings.SettingsRepository
import com.suprah.drivertracker.domain.ApiResult
import com.suprah.drivertracker.domain.TrackerApi
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PairUiState(
    val code: String = "",
    val serverUrl: String = "",
    /** dev builds only (spec B-2, P-4). */
    val serverEditable: Boolean = false,
    val editingServer: Boolean = false,
    val allowHttp: Boolean = false,
    val busy: Boolean = false,
    val error: PairError? = null,
) {
    /** The code as sent: letters and digits only (spec P-3). */
    val cleanCode: String get() = code.filter { it.isLetterOrDigit() }

    val canPair: Boolean get() = cleanCode.length == CODE_LENGTH && !busy

    val serverValid: Boolean
        get() = serverUrl.startsWith("https://") || (allowHttp && serverUrl.startsWith("http://"))

    companion object {
        const val CODE_LENGTH = 8
    }
}

/** Why pairing didn't work, put into words by the screen (spec P-4, P-7). */
sealed interface PairError {
    data object HttpsOnly : PairError

    data object Scheme : PairError

    data object Offline : PairError

    /** Suprah's own words, or an error number. */
    data class Server(val message: String?, val httpStatus: Int) : PairError
}

@HiltViewModel
class PairViewModel @Inject constructor(
    private val api: TrackerApi,
    private val settings: SettingsRepository,
    private val config: AppConfig,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PairUiState(serverEditable = config.serverEditable, allowHttp = config.allowHttp))
    val state: StateFlow<PairUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val serverUrl = settings.current().serverUrl
            mutableState.update { it.copy(serverUrl = serverUrl) }
        }
    }

    /** At most 8 letters or digits, upper-cased (spec P-3). */
    fun onCodeChange(value: String) {
        if (value.count { it.isLetterOrDigit() } <= PairUiState.CODE_LENGTH) {
            mutableState.update { it.copy(code = value.uppercase(), error = null) }
        }
    }

    fun onServerChange(value: String) = mutableState.update { it.copy(serverUrl = value.trim(), error = null) }

    fun editServer() = mutableState.update { it.copy(editingServer = true) }

    /** On success the saved pairing switches the app to the home screen (spec P-6). */
    fun pair() {
        val current = mutableState.value
        if (!current.canPair) return
        if (config.serverEditable && !current.serverValid) {
            mutableState.update { it.copy(error = if (config.allowHttp) PairError.Scheme else PairError.HttpsOnly) }
            return
        }
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val serverUrl =
                if (config.serverEditable) {
                    settings.setServerUrl(current.serverUrl)
                    current.serverUrl.trimEnd('/')
                } else {
                    config.defaultServerUrl
                }
            val error =
                when (val result = api.pair(serverUrl, current.cleanCode, config.deviceName, config.appVersion)) {
                    is ApiResult.Ok -> {
                        val pairing = result.value
                        settings.savePairing(pairing.credentials, pairing.status.driverName, pairing.status.approval)
                        null
                    }
                    is ApiResult.Refused -> PairError.Server(result.message, result.httpStatus)
                    is ApiResult.Offline -> PairError.Offline
                }
            mutableState.update { it.copy(busy = false, error = error) }
        }
    }
}
