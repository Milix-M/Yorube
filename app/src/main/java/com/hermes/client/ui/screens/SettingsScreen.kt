package com.hermes.client.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import com.hermes.client.HermesApp
import com.hermes.client.R
import com.hermes.client.ui.ds.Hermes
import com.hermes.client.ui.ds.HermesButton
import com.hermes.client.ui.ds.HermesCard
import com.hermes.client.ui.ds.HermesDialog
import com.hermes.client.ui.ds.HermesIconButton
import com.hermes.client.ui.ds.HermesScaffold
import com.hermes.client.ui.ds.HermesTextField
import com.hermes.client.ui.ds.HermesTopBar

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

    HermesScaffold(
        topBar = {
            HermesTopBar(
                title = stringResource(R.string.settings),
                onBack = onNavigateBack,
                backDesc = stringResource(R.string.back),
                backTestTag = "backButton",
            )
        }
    ) {
        Column(
            Modifier.fillMaxSize().padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HermesCard(title = stringResource(R.string.section_server)) {
                SettingField(stringResource(R.string.base_url), vm.baseUrl, "https://host:port", tag = "baseUrl") { vm.baseUrl = it }
                SettingField(stringResource(R.string.api_key), vm.apiKey, "Bearer", secret = true, tag = "apiKey") { vm.apiKey = it }
            }

            HermesCard(title = stringResource(R.string.section_cloudflare)) {
                SectionHelp(stringResource(R.string.section_cloudflare_help))
                SettingField(stringResource(R.string.cf_id), vm.cfClientId, "", tag = "cfId") { vm.cfClientId = it }
                SettingField(stringResource(R.string.cf_secret), vm.cfClientSecret, "", secret = true, tag = "cfSecret") { vm.cfClientSecret = it }
            }

            HermesCard(title = stringResource(R.string.section_notifications)) {
                SettingField(stringResource(R.string.relay_optional), vm.relayUrl, "https://relay:port", tag = "relayUrl") { vm.relayUrl = it }
                BasicText(
                    if (vm.fcmToken.isBlank()) stringResource(R.string.fcm_token_none)
                    else stringResource(R.string.fcm_token, vm.fcmToken.take(12)),
                    style = Hermes.type.labelSmall.copy(color = Hermes.colors.onSurfaceVariant),
                )
            }

            HermesCard(title = stringResource(R.string.section_firebase)) {
                SectionHelp(stringResource(R.string.section_firebase_help))
                SettingField(stringResource(R.string.fb_project), vm.firebaseProjectId, "", tag = "fbProject") { vm.firebaseProjectId = it }
                SettingField(stringResource(R.string.fb_app), vm.firebaseAppId, "", tag = "fbApp") { vm.firebaseAppId = it }
                SettingField(stringResource(R.string.fb_key), vm.firebaseApiKey, "", secret = true, tag = "fbKey") { vm.firebaseApiKey = it }
                SettingField(stringResource(R.string.fb_sender), vm.firebaseSenderId, "", KeyboardType.Number, tag = "fbSender") {
                    vm.firebaseSenderId = it
                }
            }

            HermesButton(
                if (vm.saved) stringResource(R.string.saved) else stringResource(R.string.save),
                onClick = vm::save,
                modifier = Modifier.fillMaxWidth().testTag("save"),
            )
            HermesButton(
                if (vm.testing) stringResource(R.string.testing) else stringResource(R.string.test),
                onClick = vm::test,
                primary = false,
                enabled = !vm.testing,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showResult && vm.testResult.isNotBlank()) {
        HermesDialog(
            visible = true,
            onDismiss = { showResult = false },
            title = stringResource(R.string.test_result_title),
            confirmLabel = stringResource(R.string.close),
            onConfirm = { showResult = false },
        ) {
            BasicText(vm.testResult, style = Hermes.type.body.copy(color = Hermes.colors.onSurface))
        }
    }
}

@Composable
private fun SectionHelp(text: String) {
    BasicText(
        text,
        style = Hermes.type.labelSmall.copy(color = Hermes.colors.onSurfaceVariant),
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
    HermesTextField(
        value = value,
        onValueChange = onChange,
        label = label,
        placeholder = placeholder,
        secret = secret && !visible,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
        trailing = if (secret) {
            {
                HermesIconButton(
                    onClick = { visible = !visible },
                    desc = if (visible) stringResource(R.string.hide) else stringResource(R.string.show),
                    modifier = Modifier.testTag("visibility-$tag").size(40.dp),
                ) {
                    Image(
                        imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = null,
                    )
                }
            }
        } else null,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag(tag),
    )
}
