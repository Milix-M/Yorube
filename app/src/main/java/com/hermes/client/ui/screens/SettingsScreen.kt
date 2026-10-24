package com.hermes.client.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextField
import androidx.compose.material.TopAppBar
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.Lucide
import com.hermes.client.HermesApp
import com.hermes.client.R

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
                            imageVector = Lucide.ArrowLeft,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                elevation = 0.dp,
            )
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
            }

            SettingsCard(title = stringResource(R.string.section_cloudflare)) {
                SectionHelp(stringResource(R.string.section_cloudflare_help))
                SettingField(stringResource(R.string.cf_id), vm.cfClientId, "", tag = "cfId") { vm.cfClientId = it }
                SettingField(stringResource(R.string.cf_secret), vm.cfClientSecret, "", secret = true, tag = "cfSecret") { vm.cfClientSecret = it }
            }

            SettingsCard(title = stringResource(R.string.section_notifications)) {
                SettingField(stringResource(R.string.relay_optional), vm.relayUrl, "https://relay:port", tag = "relayUrl") { vm.relayUrl = it }
                Text(
                    if (vm.fcmToken.isBlank()) stringResource(R.string.fcm_token_none)
                    else stringResource(R.string.fcm_token, vm.fcmToken.take(12)),
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                )
            }

            SettingsCard(title = stringResource(R.string.section_firebase)) {
                SectionHelp(stringResource(R.string.section_firebase_help))
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
            ) {
                Text(if (vm.saved) stringResource(R.string.saved) else stringResource(R.string.save))
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
            title = { Text(stringResource(R.string.test_result_title)) },
            text = {
                Text(vm.testResult, style = MaterialTheme.typography.body2)
            },
            confirmButton = {
                TextButton(onClick = { showResult = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.subtitle1,
            )
            content()
        }
    }
}

@Composable
private fun SectionHelp(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.caption,
        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
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
    var visible by remember(tag) { mutableStateOf(false) }
    TextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag(tag),
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        visualTransformation = if (secret && !visible) {
            androidx.compose.ui.text.input.PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        trailingIcon = if (secret) {
            {
                IconButton(
                    onClick = { visible = !visible },
                    modifier = Modifier.testTag("visibility-$tag"),
                ) {
                    Icon(
                        imageVector = if (visible) Lucide.EyeOff else Lucide.Eye,
                        contentDescription = if (visible) {
                            stringResource(R.string.hide)
                        } else {
                            stringResource(R.string.show)
                        },
                    )
                }
            }
        } else {
            null
        },
        singleLine = true,
    )
}
