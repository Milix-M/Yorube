package com.hermes.client.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.Lucide
import com.composeunstyled.theme.Theme
import com.composables.ui.components.AlertDialog
import com.composables.ui.components.Button
import com.composables.ui.components.ButtonStyle
import com.composables.ui.components.Icon
import com.composables.ui.components.IconButton
import com.composables.ui.components.Text
import com.composables.ui.components.Toolbar
import com.composables.ui.theme.colors
import com.composables.ui.theme.mutedColor
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

    Column(Modifier.fillMaxSize()) {
        Toolbar(
            modifier = Modifier.fillMaxWidth(),
            title = {
                Text(
                    stringResource(R.string.settings),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            leading = {
                IconButton(
                    onClick = onNavigateBack,
                    style = ButtonStyle.Ghost,
                    modifier = Modifier.testTag("backButton"),
                ) {
                    Icon(
                        imageVector = Lucide.ArrowLeft,
                        contentDescription = stringResource(R.string.back),
                    )
                }
            },
        )
        Column(
            Modifier.fillMaxSize().padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSection(title = stringResource(R.string.section_server)) {
                SettingField(stringResource(R.string.base_url), vm.baseUrl, "https://host:port", tag = "baseUrl") { vm.baseUrl = it }
                SettingField(stringResource(R.string.api_key), vm.apiKey, "Bearer", secret = true, tag = "apiKey") { vm.apiKey = it }
            }

            SettingsSection(title = stringResource(R.string.section_cloudflare)) {
                SectionHelp(stringResource(R.string.section_cloudflare_help))
                SettingField(stringResource(R.string.cf_id), vm.cfClientId, "", tag = "cfId") { vm.cfClientId = it }
                SettingField(stringResource(R.string.cf_secret), vm.cfClientSecret, "", secret = true, tag = "cfSecret") { vm.cfClientSecret = it }
            }

            SettingsSection(title = stringResource(R.string.section_notifications)) {
                SettingField(stringResource(R.string.relay_optional), vm.relayUrl, "https://relay:port", tag = "relayUrl") { vm.relayUrl = it }
                Text(
                    if (vm.fcmToken.isBlank()) stringResource(R.string.fcm_token_none)
                    else stringResource(R.string.fcm_token, vm.fcmToken.take(12)),
                    fontSize = 12.sp,
                    color = Theme[colors][mutedColor],
                )
            }

            SettingsSection(title = stringResource(R.string.section_firebase)) {
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
                style = ButtonStyle.Primary,
                modifier = Modifier.fillMaxWidth().testTag("save"),
            ) {
                Text(if (vm.saved) stringResource(R.string.saved) else stringResource(R.string.save))
            }
            Button(
                onClick = vm::test,
                style = ButtonStyle.Secondary,
                enabled = !vm.testing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (vm.testing) stringResource(R.string.testing) else stringResource(R.string.test))
            }
        }
    }

    if (showResult && vm.testResult.isNotBlank()) {
        AlertDialog(
            visible = true,
            onDismissRequest = { showResult = false },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.test_result_title),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(vm.testResult, fontSize = 15.sp)
                Button(
                    onClick = { showResult = false },
                    style = ButtonStyle.Primary,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        content()
    }
}

@Composable
private fun SectionHelp(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        color = Theme[colors][mutedColor],
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
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label,
            fontSize = 12.sp,
            color = Theme[colors][mutedColor],
            modifier = Modifier.padding(bottom = 4.dp),
        )
        AppTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = placeholder,
            secret = secret && !visible,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            trailing = if (secret) {
                {
                    IconButton(
                        onClick = { visible = !visible },
                        style = ButtonStyle.Ghost,
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
            fieldTag = tag,
        )
    }
}
