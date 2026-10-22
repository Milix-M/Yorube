package com.hermes.client.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.client.HermesApp
import com.hermes.client.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onNavigateBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as HermesApp
    val vm: SettingsViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SettingsViewModel(app.container) as T
        }
    )
    var showResult by remember { mutableStateOf(false) }
    LaunchedEffect(vm.testResult) {
        if (vm.testResult.isNotBlank()) showResult = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("backButton"),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                })
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsCard(title = stringResource(R.string.section_server)) {
                SettingField(stringResource(R.string.base_url), vm.baseUrl, "https://host:port", tag = "baseUrl") { vm.baseUrl = it }
                SettingField(stringResource(R.string.api_key), vm.apiKey, "Bearer", secret = true, tag = "apiKey") { vm.apiKey = it }
                SettingField(
                    label = stringResource(R.string.model),
                    value = vm.model,
                    placeholder = "hermes-agent",
                    tag = "model",
                    help = stringResource(R.string.model_help),
                    onChange = { vm.model = it },
                )
                SettingField(
                    label = stringResource(R.string.provider_optional),
                    value = vm.provider,
                    placeholder = "openrouter",
                    tag = "provider",
                    help = stringResource(R.string.provider_help),
                    onChange = { vm.provider = it },
                )
            }

            SettingsCard(title = stringResource(R.string.section_cloudflare)) {
                SettingField(stringResource(R.string.cf_id), vm.cfClientId, "", tag = "cfId") { vm.cfClientId = it }
                SettingField(stringResource(R.string.cf_secret), vm.cfClientSecret, "", secret = true, tag = "cfSecret") { vm.cfClientSecret = it }
            }

            SettingsCard(title = stringResource(R.string.section_notifications)) {
                SettingField(stringResource(R.string.relay_optional), vm.relayUrl, "https://relay:port", tag = "relayUrl") { vm.relayUrl = it }
                Text(
                    if (vm.fcmToken.isBlank()) stringResource(R.string.fcm_token_none)
                    else stringResource(R.string.fcm_token, vm.fcmToken.take(12)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsCard(title = stringResource(R.string.section_firebase)) {
                SettingField(stringResource(R.string.fb_project), vm.firebaseProjectId, "", tag = "fbProject") { vm.firebaseProjectId = it }
                SettingField(stringResource(R.string.fb_app), vm.firebaseAppId, "", tag = "fbApp") { vm.firebaseAppId = it }
                SettingField(stringResource(R.string.fb_key), vm.firebaseApiKey, "", secret = true, tag = "fbKey") { vm.firebaseApiKey = it }
                SettingField(stringResource(R.string.fb_sender), vm.firebaseSenderId, "", KeyboardType.Number, tag = "fbSender") {
                    vm.firebaseSenderId = it
                }
            }

            Button(
                onClick = vm::save,
                modifier = Modifier.fillMaxWidth().testTag("save"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp),
            ) {
                Icon(
                    Icons.Filled.Save,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    if (vm.saved) stringResource(R.string.saved) else stringResource(R.string.save),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            OutlinedButton(
                onClick = vm::test,
                enabled = !vm.testing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (vm.testing) stringResource(R.string.testing) else stringResource(R.string.test))
            }
        }
    }

    if (showResult && vm.testResult.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { showResult = false },
            confirmButton = {
                TextButton(onClick = { showResult = false }) { Text(stringResource(R.string.close)) }
            },
            title = { Text(stringResource(R.string.test_result_title)) },
            text = {
                SelectionContainer {
                    Text(vm.testResult)
                }
            },
        )
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun SettingField(
    label: String,
    value: String,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    tag: String = "",
    help: String? = null,
    onChange: (String) -> Unit,
) {
    var visible by remember(tag) { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
        trailingIcon = if (secret) {
            {
                IconButton(onClick = { visible = !visible }, modifier = Modifier.testTag("visibility-$tag")) {
                    Icon(
                        if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (visible) stringResource(R.string.hide) else stringResource(R.string.show),
                    )
                }
            }
        } else null,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag(tag),
    )
    help?.let {
        Text(
            it,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        )
    }
}
