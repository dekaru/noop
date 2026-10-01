package com.noop.journalsync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.push.PushEndpointPolicy
import com.noop.ui.NoopButton
import com.noop.ui.NoopButtonKind
import com.noop.ui.NoopType
import com.noop.ui.Palette
import com.noop.ui.ScreenScaffold
import com.noop.ui.SettingsCard
import com.noop.ui.SettingsToggleRow
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Journal Sync settings screen (spec zhoop-journal-sync-SPEC.md §3.4). Mirrors SelfHostedPushScreen. */
@Composable
fun JournalSyncScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val settings = remember { JournalSyncSettings.from(context) }
    var endpoint by remember { mutableStateOf(settings.endpointText()) }
    var token by remember { mutableStateOf("") }
    var cfId by remember { mutableStateOf("") }
    var cfSecret by remember { mutableStateOf("") }
    var snapshot by remember { mutableStateOf(settings.snapshot()) }
    var message by remember { mutableStateOf<String?>(null) }

    // Settings wrap SharedPreferences, not snapshot state: poll while visible (same as the push screen).
    LaunchedEffect(settings) {
        while (currentCoroutineContext().isActive) {
            snapshot = settings.snapshot()
            kotlinx.coroutines.delay(750)
        }
    }

    val endpointValid = PushEndpointPolicy.validate(endpoint) is PushEndpointPolicy.Result.Valid
    val tokenAvailable = token.isNotBlank() || snapshot.hasToken
    val syncing = snapshot.state == JournalSyncSettings.SyncState.SYNCING

    ScreenScaffold(
        title = stringResource(R.string.journal_sync_title),
        subtitle = stringResource(R.string.journal_sync_subtitle),
    ) {
        SettingsCard(
            icon = Icons.Filled.CloudSync,
            title = stringResource(R.string.journal_sync_destination_title),
            blurb = stringResource(R.string.journal_sync_disclosure),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                JournalSyncTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it; message = null },
                    label = stringResource(R.string.journal_sync_url),
                    secret = false,
                )
                JournalSyncTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = if (snapshot.hasToken) {
                        stringResource(R.string.journal_sync_token_saved)
                    } else {
                        stringResource(R.string.journal_sync_token)
                    },
                    secret = true,
                )
                JournalSyncTextField(
                    value = cfId,
                    onValueChange = { cfId = it },
                    label = stringResource(R.string.journal_sync_cf_id),
                    secret = false,
                )
                JournalSyncTextField(
                    value = cfSecret,
                    onValueChange = { cfSecret = it },
                    label = if (snapshot.hasCfCredentials) {
                        stringResource(R.string.journal_sync_cf_secret_saved)
                    } else {
                        stringResource(R.string.journal_sync_cf_secret)
                    },
                    secret = true,
                )
                message?.let {
                    Text(it, style = NoopType.footnote, color = Palette.statusWarning)
                }
                NoopButton(
                    text = stringResource(R.string.journal_sync_save),
                    kind = NoopButtonKind.Secondary,
                    fullWidth = true,
                    enabled = endpointValid && tokenAvailable,
                    onClick = {
                        when (val result = settings.saveEndpoint(endpoint)) {
                            is PushEndpointPolicy.Result.Invalid ->
                                message = context.getString(R.string.journal_sync_invalid_url)
                            is PushEndpointPolicy.Result.Valid -> {
                                endpoint = result.endpoint.url
                                if (token.isNotBlank()) settings.saveToken(token)
                                token = ""
                                if (cfId.isNotBlank() || cfSecret.isNotBlank()) {
                                    settings.saveCfCredentials(cfId, cfSecret)
                                }
                                cfId = ""
                                cfSecret = ""
                                snapshot = settings.snapshot()
                                message = context.getString(R.string.journal_sync_saved)
                            }
                        }
                    },
                )
                NoopButton(
                    text = stringResource(R.string.journal_sync_paste),
                    kind = NoopButtonKind.Secondary,
                    fullWidth = true,
                    onClick = {
                        val config = JournalSyncPairing.parse(clipboard.getText()?.text.orEmpty())
                        if (config == null) {
                            message = context.getString(R.string.journal_sync_paste_invalid)
                        } else {
                            when (val result = settings.saveEndpoint(config.url)) {
                                is PushEndpointPolicy.Result.Invalid ->
                                    message = context.getString(R.string.journal_sync_invalid_url)
                                is PushEndpointPolicy.Result.Valid -> {
                                    settings.saveToken(config.token)
                                    settings.saveCfCredentials(config.cfId.orEmpty(), config.cfSecret.orEmpty())
                                    endpoint = result.endpoint.url
                                    token = ""
                                    cfId = ""
                                    cfSecret = ""
                                    snapshot = settings.snapshot()
                                    message = context.getString(R.string.journal_sync_paste_ok)
                                }
                            }
                        }
                    },
                )
                SettingsToggleRow(
                    title = stringResource(R.string.journal_sync_enabled),
                    detail = stringResource(R.string.journal_sync_enabled_detail),
                    checked = snapshot.enabled,
                    onCheckedChange = { requested ->
                        if (!requested) {
                            settings.setEnabled(false)
                            JournalSyncScheduler.disable(context)
                        } else {
                            val current = settings.snapshot()
                            if (current.endpoint == null || !current.hasToken) {
                                message = context.getString(R.string.journal_sync_config_required)
                            } else {
                                settings.setEnabled(true)
                                JournalSyncScheduler.enablePeriodic(context)
                                JournalSyncScheduler.syncNow(context)
                            }
                        }
                        snapshot = settings.snapshot()
                    },
                )
                NoopButton(
                    text = stringResource(R.string.journal_sync_sync_now),
                    kind = NoopButtonKind.Secondary,
                    fullWidth = true,
                    enabled = snapshot.ready && !syncing,
                    onClick = {
                        JournalSyncScheduler.syncNow(context)
                        snapshot = settings.snapshot()
                    },
                )
            }
        }

        SettingsCard(
            icon = Icons.Filled.CloudSync,
            title = stringResource(R.string.journal_sync_status_title),
            blurb = stringResource(R.string.journal_sync_status_detail),
        ) {
            if (syncing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Palette.accent)
            }
            val state = when (snapshot.state) {
                JournalSyncSettings.SyncState.IDLE -> stringResource(R.string.journal_sync_state_idle)
                JournalSyncSettings.SyncState.SYNCING -> stringResource(R.string.journal_sync_state_syncing)
                JournalSyncSettings.SyncState.OK -> stringResource(R.string.journal_sync_state_ok)
                JournalSyncSettings.SyncState.AUTH_FAILED -> stringResource(R.string.journal_sync_state_auth_failed)
                JournalSyncSettings.SyncState.ERROR -> stringResource(R.string.journal_sync_state_error)
            }
            Text(stringResource(R.string.journal_sync_current_state, state), style = NoopType.body, color = Palette.textPrimary)
            val last = snapshot.lastSuccessAt?.let {
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
            } ?: stringResource(R.string.journal_sync_never)
            Text(stringResource(R.string.journal_sync_last_success, last), style = NoopType.body, color = Palette.textPrimary)
            snapshot.lastError?.let {
                Text(stringResource(R.string.journal_sync_last_error, it), style = NoopType.footnote, color = Palette.statusWarning)
            }
        }
    }
}

@Composable
private fun JournalSyncTextField(value: String, onValueChange: (String) -> Unit, label: String, secret: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = NoopType.mono(13f),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Palette.textPrimary,
            unfocusedTextColor = Palette.textPrimary,
            focusedBorderColor = Palette.accent,
            unfocusedBorderColor = Palette.hairline,
            cursorColor = Palette.accent,
            focusedContainerColor = Palette.surfaceInset,
            unfocusedContainerColor = Palette.surfaceInset,
        ),
    )
}
