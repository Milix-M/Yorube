package com.hermes.client.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
            @Suppress("UnstableApiUsage", "UNCHECKED_CAST")
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
                title = {
                    Column {
                        Text(
                            stringResource(R.string.settings),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                },
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
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingsCard(
                title = stringResource(R.string.section_server),
                icon = Icons.Filled.Dns,
            ) {
                SettingField(stringResource(R.string.base_url), vm.baseUrl, "https://host:port", tag = "baseUrl") { vm.baseUrl = it }
                SettingField(stringResource(R.string.api_key), vm.apiKey, "Bearer", secret = true, tag = "apiKey") { vm.apiKey = it }
                SettingField(stringResource(R.string.model), vm.model, "default", tag = "model") { vm.model = it }
                SettingField(
                    stringResource(R.string.provider_optional),
                    vm.provider,
                    "",
                    tag = "provider",
                ) { vm.provider = it }
            }

            SettingsCard(
                title = stringResource(R.string.section_cloudflare),
                icon = Icons.Filled.Security,
            ) {
                SectionHelp(stringResource(R.string.section_cloudflare_help))
                SettingField(stringResource(R.string.cf_id), vm.cfClientId, "", tag = "cfId") { vm.cfClientId = it }
                SettingField(stringResource(R.string.cf_secret), vm.cfClientSecret, "", secret = true, tag = "cfSecret") { vm.cfClientSecret = it }
            }

            SettingsCard(
                title = stringResource(R.string.section_notifications),
                icon = Icons.Filled.Notifications,
            ) {
                SettingField(stringResource(R.string.relay_optional), vm.relayUrl, "https://relay:port", tag = "relayUrl") { vm.relayUrl = it }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (vm.fcmToken.isBlank()) MaterialTheme.colorScheme.outlineVariant
                                else Color(0xFF34A853)
                            ),
                    )
                    Text(
                        if (vm.fcmToken.isBlank()) stringResource(R.string.fcm_token_none)
                        else stringResource(R.string.fcm_token, vm.fcmToken.take(12)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            SettingsCard(
                title = stringResource(R.string.section_firebase),
                icon = Icons.Filled.Cloud,
            ) {
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
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
                shape = RoundedCornerShape(24.dp),
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
                shape = RoundedCornerShape(24.dp),
            ) {
                if (vm.testing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (vm.testing) stringResource(R.string.testing) else stringResource(R.string.test))
            }
            if (vm.testing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
                )
            }
            Spacer(Modifier.height(16.dp))
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
                // Fixed height so the dialog never resizes as the result grows.
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .height(220.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TestResultHeadline(testing = vm.testing, ok = vm.testOk)
                    Text(
                        stringResource(R.string.details),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SelectionContainer {
                        Text(
                            vm.testResult,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun TestResultHeadline(testing: Boolean, ok: Boolean?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            testing -> {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.testing),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            ok == true -> {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF137333),
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    stringResource(R.string.test_ok),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            else -> {
                Icon(
                    Icons.Filled.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    stringResource(R.string.test_failed),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionHelp(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
    )
}

@Composable
private fun SettingsCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
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
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag(tag),
    )
}
