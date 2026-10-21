package com.hermes.client.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) { Text("‹") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SectionTitle(stringResource(R.string.section_server))
            SettingField(stringResource(R.string.base_url), vm.baseUrl, "https://host:port", tag = "baseUrl") { vm.baseUrl = it }
            SettingField(stringResource(R.string.api_key), vm.apiKey, "Bearer", secret = true, tag = "apiKey") { vm.apiKey = it }
            SettingField(stringResource(R.string.model), vm.model, "hermes-agent", tag = "model") { vm.model = it }
            SettingField(stringResource(R.string.provider_optional), vm.provider, "openrouter", tag = "provider") { vm.provider = it }

            SectionTitle(stringResource(R.string.section_cloudflare))
            SettingField(stringResource(R.string.cf_id), vm.cfClientId, "", tag = "cfId") { vm.cfClientId = it }
            SettingField(stringResource(R.string.cf_secret), vm.cfClientSecret, "", secret = true, tag = "cfSecret") { vm.cfClientSecret = it }

            SectionTitle(stringResource(R.string.section_notifications))
            SettingField(stringResource(R.string.relay_optional), vm.relayUrl, "https://relay:port", tag = "relayUrl") { vm.relayUrl = it }
            Text(
                if (vm.fcmToken.isBlank()) stringResource(R.string.fcm_token_none)
                else stringResource(R.string.fcm_token, vm.fcmToken.take(12)),
                style = MaterialTheme.typography.labelSmall,
            )

            SectionTitle(stringResource(R.string.section_firebase))
            SettingField(stringResource(R.string.fb_project), vm.firebaseProjectId, "", tag = "fbProject") { vm.firebaseProjectId = it }
            SettingField(stringResource(R.string.fb_app), vm.firebaseAppId, "", tag = "fbApp") { vm.firebaseAppId = it }
            SettingField(stringResource(R.string.fb_key), vm.firebaseApiKey, "", secret = true, tag = "fbKey") { vm.firebaseApiKey = it }
            SettingField(stringResource(R.string.fb_sender), vm.firebaseSenderId, "", KeyboardType.Number, tag = "fbSender") {
                vm.firebaseSenderId = it
            }

            Button(
                onClick = vm::save,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("save"),
            ) {
                Text(if (vm.saved) stringResource(R.string.saved) else stringResource(R.string.save))
            }
            OutlinedButton(
                onClick = vm::test,
                enabled = !vm.testing,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(if (vm.testing) stringResource(R.string.testing) else stringResource(R.string.test))
            }
            if (vm.testResult.isNotBlank()) {
                Text(vm.testResult, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingField(
    label: String,
    value: String,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    tag: String = "",
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag(tag),
    )
}
