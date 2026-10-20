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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.client.HermesApp

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
                title = { Text("Settings") },
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
            SectionTitle("Server")
            SettingField("Base URL", vm.baseUrl, "https://host:port") { vm.baseUrl = it }
            SettingField("API key", vm.apiKey, "Bearer key", secret = true) { vm.apiKey = it }
            SettingField("Model", vm.model, "hermes-agent") { vm.model = it }
            SettingField("Provider (optional)", vm.provider, "openrouter") { vm.provider = it }

            SectionTitle("Notifications (M3)")
            SettingField("Relay URL (optional)", vm.relayUrl, "https://relay:port") { vm.relayUrl = it }
            Text(
                "FCM token: ${vm.fcmToken.take(12)}${if (vm.fcmToken.isNotBlank()) "…" else "(none yet)"}",
                style = MaterialTheme.typography.labelSmall,
            )

            SectionTitle("Firebase manual setup (optional)")
            SettingField("Project ID", vm.firebaseProjectId, "") { vm.firebaseProjectId = it }
            SettingField("App ID", vm.firebaseAppId, "") { vm.firebaseAppId = it }
            SettingField("API key", vm.firebaseApiKey, "", secret = true) { vm.firebaseApiKey = it }
            SettingField("Sender ID", vm.firebaseSenderId, "", KeyboardType.Number) {
                vm.firebaseSenderId = it
            }

            Button(onClick = vm::save, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text(if (vm.saved) "Saved ✓" else "Save")
            }
            OutlinedButton(
                onClick = vm::test,
                enabled = !vm.testing,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(if (vm.testing) "Checking…" else "Test connection")
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
