package com.hermes.client.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.AppContainer
import com.hermes.client.data.api.featureEnabled
import com.hermes.client.data.fcm.FirebaseInit
import com.hermes.client.data.store.ClientSettings
import com.hermes.client.data.store.SettingsStore
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val store: SettingsStore get() = container.settingsStore

    var baseUrl by mutableStateOf("")
    var apiKey by mutableStateOf("")
    var model by mutableStateOf("hermes-agent")
    var provider by mutableStateOf("")
    var relayUrl by mutableStateOf("")
    var firebaseProjectId by mutableStateOf("")
    var firebaseAppId by mutableStateOf("")
    var firebaseApiKey by mutableStateOf("")
    var firebaseSenderId by mutableStateOf("")
    var fcmToken by mutableStateOf("")
        private set
    var testResult by mutableStateOf("")
        private set
    var testing by mutableStateOf(false)
        private set
    var saved by mutableStateOf(false)

    init {
        viewModelScope.launch {
            val s = store.current()
            baseUrl = s.baseUrl
            apiKey = s.apiKey
            model = s.model
            provider = s.provider
            relayUrl = s.relayUrl
            firebaseProjectId = s.firebaseProjectId
            firebaseAppId = s.firebaseAppId
            firebaseApiKey = s.firebaseApiKey
            firebaseSenderId = s.firebaseSenderId
            fcmToken = s.fcmToken
        }
    }

    fun save() {
        viewModelScope.launch {
            store.save(
                ClientSettings(
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    model = model,
                    provider = provider,
                    relayUrl = relayUrl,
                    fcmToken = fcmToken,
                    firebaseProjectId = firebaseProjectId,
                    firebaseAppId = firebaseAppId,
                    firebaseApiKey = firebaseApiKey,
                    firebaseSenderId = firebaseSenderId,
                )
            )
            saved = true
        }
    }

    fun test() {
        viewModelScope.launch {
            testing = true
            testResult = "checking"
            try {
                val before = ClientSettings(
                    baseUrl = baseUrl, apiKey = apiKey, model = model, provider = provider,
                )
                if (!before.isConfigured) {
                    testResult = "Base URL and API key are required."
                    return@launch
                }
                val api = container.api()
                val healthy = api.checkHealth()
                val caps = try {
                    api.getCapabilities()
                } catch (e: Exception) {
                    testResult = "health=$healthy, capabilities failed: ${(e.message ?: "").take(120)}"
                    return@launch
                }
                val flags = listOf("run_status", "run_events_sse", "run_stop", "run_steer", "run_approval_response")
                    .joinToString(", ") { "$it=${caps.featureEnabled(it)}" }
                val fcm = if (draft().firebaseConfigured) {
                    FirebaseInit.ensureInitialized(container.appContext, draft())
                } else {
                    false
                }
                testResult = "health=$healthy\n$flags\nfcm_ready=$fcm"
            } catch (e: Exception) {
                testResult = (e.message ?: e.javaClass.simpleName).take(300)
            } finally {
                testing = false
            }
        }
    }

    private fun draft() = ClientSettings(
        baseUrl = baseUrl, apiKey = apiKey, model = model, provider = provider,
        relayUrl = relayUrl, fcmToken = fcmToken,
        firebaseProjectId = firebaseProjectId, firebaseAppId = firebaseAppId,
        firebaseApiKey = firebaseApiKey, firebaseSenderId = firebaseSenderId,
    )
}
