package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.sync.SiteXLSyncManager
import helium314.keyboard.sync.SiteXLSyncScheduler
import androidx.core.content.edit
import kotlinx.coroutines.launch

@Composable
fun SiteXLSyncScreen(onClickBack: () -> Unit) {
    val context = LocalContext.current
    val syncPrefs = remember { context.getSharedPreferences("sitexl_sync_state", android.content.Context.MODE_PRIVATE) }
    var serverUrl by remember { mutableStateOf(context.prefs().getString(Settings.PREF_SERVER_URL, "").orEmpty()) }
    var token by remember { mutableStateOf(SiteXLSyncManager.getToken(context)) }
    var autoSync by remember { mutableStateOf(SiteXLSyncManager.isAutoSyncEnabled(context)) }
    var syncing by remember { mutableStateOf(false) }
    var status by remember {
        mutableStateOf(
            syncPrefs.getLong("last_sync_completed_at", 0L).takeIf { it > 0L }?.let {
                "Last successful sync: " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it))
            } ?: "Not synchronised yet."
        )
    }
    val scope = rememberCoroutineScope()

    LaunchedEffect(autoSync) {
        SiteXLSyncManager.setAutoSyncEnabled(context, autoSync)
        SiteXLSyncScheduler.schedule(context, autoSync)
    }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.sitexl_sync_title),
        settings = emptyList()
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.sitexl_sync_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.sitexl_sync_server_url)) },
                placeholder = { Text("https://sync.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.sitexl_sync_api_token)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.sitexl_sync_daily), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.sitexl_sync_daily_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = autoSync, onCheckedChange = { autoSync = it })
            }
            Button(
                onClick = {
                    context.prefs().edit { putString(Settings.PREF_SERVER_URL, serverUrl.trim()) }
                    SiteXLSyncManager.storeToken(context, token)
                    syncing = true
                    status = "Synchronising…"
                    scope.launch {
                        try {
                            status = SiteXLSyncManager.sync(context)
                        } catch (error: Exception) {
                            status = error.message ?: "Sync failed."
                        } finally {
                            syncing = false
                        }
                    }
                },
                enabled = !syncing && serverUrl.isNotBlank() && token.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (syncing) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.sitexl_sync_now))
            }
            Text(status, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.sitexl_sync_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
