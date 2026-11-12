package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.sync.SiteXLSyncManager
import helium314.keyboard.sync.SiteXLSyncScheduler
import kotlinx.coroutines.launch

@Composable
fun SiteXLAuthScreen(
    allowSkip: Boolean,
    onAuthenticated: () -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current
    var serverUrl by remember {
        mutableStateOf(context.prefs().getString(Settings.PREF_SERVER_URL, "").orEmpty())
    }
    var username by remember { mutableStateOf(SiteXLSyncManager.getUsername(context)) }
    var password by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun submit(register: Boolean) {
        if (working) return
        working = true
        status = ""
        scope.launch {
            try {
                val session = SiteXLSyncManager.authenticate(
                    serverUrl = serverUrl,
                    username = username,
                    password = password,
                    register = register
                )
                SiteXLSyncManager.saveAccount(context, serverUrl, session.username, session.token)
                password = ""
                onAuthenticated()
            } catch (error: Exception) {
                status = error.message ?: context.getString(R.string.sitexl_auth_error_default)
            } finally {
                working = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.sitexl_auth_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.sitexl_auth_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.sitexl_auth_server)) },
                    placeholder = { Text("https://sync.example.net") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.sitexl_auth_user)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it.take(128) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.sitexl_auth_password)) },
                    supportingText = {
                        Text(stringResource(R.string.sitexl_auth_password_hint))
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                if (status.isNotBlank()) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Button(
                    onClick = { submit(register = false) },
                    enabled = !working && serverUrl.isNotBlank() &&
                        username.isNotBlank() && password.length >= 8,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (working) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 10.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    Text(stringResource(R.string.sitexl_auth_login))
                }
                OutlinedButton(
                    onClick = { submit(register = true) },
                    enabled = !working && serverUrl.isNotBlank() &&
                        username.isNotBlank() && password.length >= 8,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.sitexl_auth_register))
                }
            }
        }
        if (allowSkip) {
            Spacer(Modifier.height(12.dp))
            TextButton(
                onClick = onSkip,
                enabled = !working,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.sitexl_auth_skip))
            }
        }
    }
}

@Composable
fun SiteXLSyncScreen(onClickBack: () -> Unit) {
    val context = LocalContext.current
    val syncPrefs = remember {
        context.getSharedPreferences("sitexl_sync_state", android.content.Context.MODE_PRIVATE)
    }
    var loggedIn by remember { mutableStateOf(SiteXLSyncManager.isLoggedIn(context)) }
    var username by remember { mutableStateOf(SiteXLSyncManager.getUsername(context)) }
    var serverUrl by remember {
        mutableStateOf(context.prefs().getString(Settings.PREF_SERVER_URL, "").orEmpty())
    }
    var autoSync by remember { mutableStateOf(SiteXLSyncManager.isAutoSyncEnabled(context)) }
    var syncing by remember { mutableStateOf(false) }
    var loggingOut by remember { mutableStateOf(false) }
    var status by remember {
        mutableStateOf(
            syncPrefs.getLong("last_sync_completed_at", 0L).takeIf { it > 0L }?.let {
                "Last successful sync: " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it))
            } ?: "Not synchronised yet."
        )
    }
    val scope = rememberCoroutineScope()

    LaunchedEffect(autoSync, loggedIn) {
        SiteXLSyncManager.setAutoSyncEnabled(context, autoSync)
        SiteXLSyncScheduler.schedule(context, autoSync && loggedIn)
    }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.sitexl_sync_title),
        settings = emptyList()
    ) {
        if (!loggedIn) {
            SiteXLAuthScreen(
                allowSkip = false,
                onAuthenticated = {
                    username = SiteXLSyncManager.getUsername(context)
                    serverUrl = context.prefs().getString(Settings.PREF_SERVER_URL, "").orEmpty()
                    autoSync = SiteXLSyncManager.isAutoSyncEnabled(context)
                    status = "Signed in successfully."
                    loggedIn = true
                },
                onSkip = {}
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    stringResource(R.string.sitexl_sync_signed_in_as, username),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    stringResource(R.string.sitexl_sync_server_label, serverUrl),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.sitexl_sync_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    enabled = !syncing && !loggingOut,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (syncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 10.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    Text(stringResource(R.string.sitexl_sync_now))
                }
                Text(status, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(
                    onClick = {
                        loggingOut = true
                        scope.launch {
                            status = SiteXLSyncManager.logout(context)
                            loggedIn = false
                            loggingOut = false
                        }
                    },
                    enabled = !syncing && !loggingOut,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (loggingOut) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 10.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    Text(stringResource(R.string.sitexl_sync_logout))
                }
                Text(
                    stringResource(R.string.sitexl_sync_privacy_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
