package com.suprah.drivertracker.ui.home

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.suprah.drivertracker.R
import com.suprah.drivertracker.domain.Approval
import com.suprah.drivertracker.permissions.PhoneSettingsActions
import com.suprah.drivertracker.permissions.PhoneSettingsStatus
import com.suprah.drivertracker.ui.theme.TrackerTheme

/** Paired: go on or off duty, see what's being shared, fix phone settings (spec section 5). */
@Composable
fun HomeRoute(viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.keepStatusFresh() }
    }

    val locationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.refreshPhoneSettings() }
    val backgroundLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.refreshPhoneSettings()
            if (!granted) PhoneSettingsActions.openAppSettings(context)
        }
    val notificationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refreshPhoneSettings() }

    HomeScreen(
        state = state,
        onToggleDuty = viewModel::toggleDuty,
        settingsActions =
        SettingsActions(
            onPrecise = {
                locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            },
            onAllTheTime = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            },
            onNotifications = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onBattery = { PhoneSettingsActions.requestBatteryExemption(context) },
            onLocationServices = { PhoneSettingsActions.openLocationSettings(context) },
            onAutostart = { PhoneSettingsActions.openAutostartSettings(context) },
        ),
        onOpenPortal = { PhoneSettingsActions.openUrl(context, viewModel.driverPortalUrl) },
        onAskUnlink = viewModel::askToUnlink,
        onConfirmUnlink = viewModel::unlink,
        onCancelUnlink = viewModel::cancelUnlink,
    )
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    onToggleDuty: () -> Unit,
    settingsActions: SettingsActions,
    onOpenPortal: () -> Unit,
    onAskUnlink: () -> Unit,
    onConfirmUnlink: () -> Unit,
    onCancelUnlink: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                Text(
                    stringResource(R.string.home_brand),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                    fontSize = 12.sp,
                )
                Text(
                    state.driverName.ifBlank { stringResource(R.string.home_driver_fallback) },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            StatusCard(state)
            DutyButton(state, onToggleDuty)

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoRow(stringResource(R.string.info_last_sent), timeText(state.lastSentAt))
                    InfoRow(
                        stringResource(R.string.info_last_fix),
                        state.lastFixAccuracy?.let { stringResource(R.string.info_accuracy, timeText(state.lastFixAt), it.toInt()) }
                            ?: timeText(state.lastFixAt),
                    )
                    InfoRow(stringResource(R.string.info_queued), state.saved.toString())
                    SavedLocationsNote(state)
                }
            }

            state.phone?.let { PhoneSettingsChecklist(it, settingsActions) }

            OutlinedButton(onClick = onOpenPortal, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.home_open_portal))
            }
            TextButton(onClick = onAskUnlink, enabled = !state.unlinking) {
                Text(stringResource(R.string.unlink_button), color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (state.confirmingUnlink) {
        AlertDialog(
            onDismissRequest = onCancelUnlink,
            title = { Text(stringResource(R.string.unlink_title)) },
            text = { Text(stringResource(R.string.unlink_body)) },
            confirmButton = {
                TextButton(onClick = onConfirmUnlink) {
                    Text(stringResource(R.string.unlink_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = onCancelUnlink) { Text(stringResource(R.string.unlink_cancel)) } },
        )
    }
}

@Composable
private fun DutyButton(state: HomeUiState, onToggleDuty: () -> Unit) {
    Button(
        onClick = onToggleDuty,
        enabled = state.dutyEnabled,
        colors =
        if (state.onDuty) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            ButtonDefaults.buttonColors()
        },
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Text(
            stringResource(if (state.onDuty) R.string.duty_stop else R.string.duty_start),
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
        )
    }
    if (!state.dutyEnabled) {
        Text(
            stringResource(if (state.approval != Approval.APPROVED) R.string.duty_hint_approval else R.string.duty_hint_permissions),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview
@Composable
private fun HomeScreenPreview() {
    TrackerTheme {
        HomeScreen(
            state =
            HomeUiState(
                driverName = "Jo Rivera",
                onDuty = true,
                approval = Approval.APPROVED,
                gpsOn = true,
                loads = 1,
                saved = 3,
                phone = PhoneSettingsStatus(true, true, true, false, true, xiaomi = true, autostart = null),
            ),
            onToggleDuty = {},
            settingsActions = SettingsActions({}, {}, {}, {}, {}, {}),
            onOpenPortal = {},
            onAskUnlink = {},
            onConfirmUnlink = {},
            onCancelUnlink = {},
        )
    }
}
