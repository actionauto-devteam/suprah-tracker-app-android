package com.suprah.drivertracker.ui.pair

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suprah.drivertracker.R
import com.suprah.drivertracker.ui.theme.TrackerTheme

/** First launch: enter the one-time code from the Driver Portal (spec section 4). */
@Composable
fun PairRoute(viewModel: PairViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PairScreen(
        state = state,
        onCodeChange = viewModel::onCodeChange,
        onPair = viewModel::pair,
        onEditServer = viewModel::editServer,
        onServerChange = viewModel::onServerChange,
    )
}

@Composable
fun PairScreen(
    state: PairUiState,
    onCodeChange: (String) -> Unit,
    onPair: () -> Unit,
    onEditServer: () -> Unit,
    onServerChange: (String) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
            Modifier
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.pair_brand),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Black,
                letterSpacing = 3.sp,
                fontSize = 12.sp,
            )
            Text(stringResource(R.string.pair_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.pair_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.pair_heading), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.pair_instructions),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.code,
                onValueChange = onCodeChange,
                label = { Text(stringResource(R.string.pair_code_label)) },
                placeholder = { Text(stringResource(R.string.pair_code_placeholder)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 4.sp),
                keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onPair() }),
                isError = state.error != null,
                supportingText = { state.error?.let { Text(pairErrorText(it)) } },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onPair,
                enabled = state.canPair,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(20.dp))
                } else {
                    Text(stringResource(R.string.pair_button), fontWeight = FontWeight.SemiBold)
                }
            }

            if (state.serverEditable) {
                Spacer(Modifier.height(8.dp))
                ServerAddress(state, onEditServer, onServerChange)
            }
        }
    }
}

@Composable
private fun ServerAddress(state: PairUiState, onEditServer: () -> Unit, onServerChange: (String) -> Unit) {
    if (state.editingServer) {
        OutlinedTextField(
            value = state.serverUrl,
            onValueChange = onServerChange,
            label = { Text(stringResource(R.string.pair_server_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            supportingText = { Text(stringResource(R.string.pair_server_hint)) },
            isError = !state.serverValid,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        // The full address, including http:// or https:// (spec P-4).
        TextButton(onClick = onEditServer) {
            Text(stringResource(R.string.pair_server_change, state.serverUrl), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun pairErrorText(error: PairError): String = when (error) {
    PairError.HttpsOnly -> stringResource(R.string.pair_error_https_only)
    PairError.Scheme -> stringResource(R.string.pair_error_scheme)
    PairError.Offline -> stringResource(R.string.pair_error_offline)
    is PairError.Server -> error.message ?: stringResource(R.string.problem_server_error, error.httpStatus)
}

@Preview
@Composable
private fun PairScreenPreview() {
    TrackerTheme {
        PairScreen(
            state = PairUiState(code = "ABCD23", serverUrl = "http://localhost:5000", serverEditable = true, allowHttp = true),
            onCodeChange = {},
            onPair = {},
            onEditServer = {},
            onServerChange = {},
        )
    }
}
