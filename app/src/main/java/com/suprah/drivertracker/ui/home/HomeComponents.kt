package com.suprah.drivertracker.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suprah.drivertracker.R
import com.suprah.drivertracker.domain.Approval
import com.suprah.drivertracker.permissions.PhoneSettingsStatus
import com.suprah.drivertracker.tracking.ProblemText
import com.suprah.drivertracker.ui.theme.Amber
import com.suprah.drivertracker.ui.theme.AmberLight
import java.text.DateFormat
import java.util.Date

/** What the "Fix" buttons do (spec H-7). */
data class SettingsActions(
    val onPrecise: () -> Unit,
    val onAllTheTime: () -> Unit,
    val onNotifications: () -> Unit,
    val onBattery: () -> Unit,
    val onLocationServices: () -> Unit,
    val onAutostart: () -> Unit,
)

/** First match wins (spec H-2). */
@Composable
internal fun StatusCard(state: HomeUiState) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    val (dot, title, body) =
        when {
            state.approval != Approval.APPROVED ->
                Triple(if (dark) AmberLight else Amber, R.string.status_waiting_title, R.string.status_waiting_body)
            !state.onDuty -> Triple(colors.onSurfaceVariant, R.string.status_off_duty_title, R.string.status_off_duty_body)
            state.gpsOn -> Triple(colors.primary, R.string.status_sharing_title, R.string.status_sharing_body)
            else -> Triple(colors.onSurfaceVariant, R.string.status_no_load_title, R.string.status_no_load_body)
        }
    Card(colors = CardDefaults.cardColors(containerColor = colors.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(dot, size = 12)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            val loads = state.loads
            if (loads != null && state.approval == Approval.APPROVED) {
                Text(
                    if (loads == 0) {
                        stringResource(
                            R.string.status_no_loads,
                        )
                    } else {
                        pluralStringResource(R.plurals.status_loads, loads, loads)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            state.problem?.let { problem ->
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(problemText(problem), style = MaterialTheme.typography.bodySmall, color = colors.error)
            }
        }
    }
}

@Composable
private fun problemText(problem: HomeProblem): String = when (problem) {
    is HomeProblem.Tracking -> ProblemText.describe(LocalContext.current.resources, problem.problem)
    HomeProblem.CheckOffline -> stringResource(R.string.home_offline)
    is HomeProblem.CheckRefused -> problem.message ?: stringResource(R.string.problem_server_error, problem.httpStatus)
}

@Composable
internal fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Says in plain words what the saved count means (spec H-4): catching up after no signal while on duty, or
 * waiting for the next shift when off duty. Hidden when a problem message already explains it.
 */
@Composable
internal fun SavedLocationsNote(state: HomeUiState) {
    val text =
        when {
            state.onDuty && state.problem == null && state.saved >= CATCHING_UP_FROM ->
                pluralStringResource(R.plurals.info_catching_up, state.saved, state.saved)
            !state.onDuty && state.saved > 0 ->
                pluralStringResource(R.plurals.info_saved_off_duty, state.saved, state.saved)
            else -> return
        }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Below this, readings are simply on their way (normal while on duty). */
private const val CATCHING_UP_FROM = 20

@Composable
internal fun timeText(timeMs: Long): String =
    if (timeMs <= 0L) stringResource(R.string.info_none) else DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timeMs))

/** Spec H-7: each row turns green when done. */
@Composable
internal fun PhoneSettingsChecklist(status: PhoneSettingsStatus, actions: SettingsActions) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            CheckRow(R.string.settings_location_services, status.locationServicesOn, R.string.settings_turn_on, actions.onLocationServices)
            CheckRow(R.string.settings_precise, status.preciseLocation, R.string.settings_allow, actions.onPrecise)
            CheckRow(
                R.string.settings_all_the_time,
                status.allTheTime,
                R.string.settings_allow,
                actions.onAllTheTime,
                enabled = status.preciseLocation,
            )
            CheckRow(R.string.settings_notifications, status.notifications, R.string.settings_allow, actions.onNotifications)
            CheckRow(R.string.settings_battery, status.batteryUnrestricted, R.string.settings_fix, actions.onBattery)
            if (status.xiaomi) {
                val autostart = status.autostart
                if (autostart != null) {
                    CheckRow(R.string.settings_autostart, autostart, R.string.settings_fix, actions.onAutostart)
                } else {
                    // This phone doesn't tell apps whether Autostart is on, so the row has no status.
                    UnknownRow(R.string.settings_autostart, actions.onAutostart)
                }
                if (autostart != true) Note(R.string.settings_xiaomi_note)
            }
            if (!status.allGood) Note(R.string.settings_note)
        }
    }
}

@Composable
private fun CheckRow(label: Int, ok: Boolean, action: Int, onFix: () -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Dot(if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(10.dp))
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (ok) {
            Text(stringResource(R.string.settings_done), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        } else {
            TextButton(onClick = onFix, enabled = enabled) { Text(stringResource(action)) }
        }
    }
}

@Composable
private fun UnknownRow(label: Int, onCheck: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Dot(MaterialTheme.colorScheme.outline)
        Spacer(Modifier.width(10.dp))
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onCheck) { Text(stringResource(R.string.settings_check)) }
    }
}

@Composable
private fun Note(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Dot(color: Color, size: Int = 10) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}
