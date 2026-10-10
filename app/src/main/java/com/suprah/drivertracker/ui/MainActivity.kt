package com.suprah.drivertracker.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.suprah.drivertracker.data.settings.SettingsRepository
import com.suprah.drivertracker.ui.home.HomeRoute
import com.suprah.drivertracker.ui.pair.PairRoute
import com.suprah.drivertracker.ui.theme.TrackerTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TrackerTheme { AppRoot() } }
    }
}

/**
 * Pairing until the phone is paired, then home. Driven by the saved pairing, so an unlink from anywhere
 * (this screen, the Driver Portal, Dispatch) returns to pairing by itself (spec P-1, T-13).
 */
@HiltViewModel
class AppViewModel @Inject constructor(settings: SettingsRepository) : ViewModel() {
    /** Null until the saved settings have been read. */
    val paired: StateFlow<Boolean?> =
        settings.settings
            .map { it.isPaired }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@Composable
private fun AppRoot(viewModel: AppViewModel = hiltViewModel()) {
    val paired by viewModel.paired.collectAsStateWithLifecycle()
    when (paired) {
        true -> HomeRoute()
        false -> PairRoute()
        null -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
    }
}
