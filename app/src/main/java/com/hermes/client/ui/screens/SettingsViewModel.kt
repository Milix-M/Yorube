package com.hermes.client.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.AppContainer
import com.hermes.client.R
import com.hermes.client.data.api.featureEnabled
import com.hermes.client.data.store.ClientSettings
import com.hermes.client.data.store.LANGUAGE_SYSTEM
import com.hermes.client.data.store.SettingsStore
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val store: SettingsStore get() = container.settingsStore

    var baseUrl by mutableStateOf("")
    var apiKey by mutableStateOf("")
    var model by mutableStateOf("")
    var provider by mutableStateOf("")
    var relayUrl by mutableStateOf("")
    var firebaseProjectId by mutableStateOf("")
    var firebaseAppId by mutableStateOf("")
    var firebaseApiKey by mutableStateOf("")
    var firebaseSenderId by mutableStateOf("")
    var cfClientId by mutableStateOf("")
    var cfClientSecret by mutableStateOf("")
    var language by mutableStateOf(LANGUAGE_SYSTEM)
    var showForeignSessions by mutableStateOf(true)
    var enterToSend by mutableStateOf(false)
    var notificationsEnabled by mutableStateOf(true)
    var fcmToken by mutableStateOf("")
        private set
    var testResult by mutableStateOf("")
        private set
    var testOk by mutableStateOf<Boolean?>(null)
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
            cfClientId = s.cfClientId
            cfClientSecret = s.cfClientSecret
            fcmToken = s.fcmToken
            language = s.language
            showForeignSessions = s.showForeignSessions
            enterToSend = s.enterToSend
            notificationsEnabled = s.notificationsEnabled
        }
    }

    fun save() {
        viewModelScope.launch {
            // Preserve the session marks: the screen never edits them.
            val stored = store.current()
            val keepForeign = stored.foreignSessionIds
            val keepAppOwned = stored.appOwnedSessionIds
            val newSettings = stored.copy(
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
                cfClientId = cfClientId,
                cfClientSecret = cfClientSecret,
                language = language,
                showForeignSessions = showForeignSessions,
                enterToSend = enterToSend,
                notificationsEnabled = notificationsEnabled,
                appOwnedSessionIds = keepAppOwned,
                foreignSessionIds = keepForeign,
            )
            if (container.runStreams.working.value &&
                (stored.baseUrl != newSettings.baseUrl.trim().removeSuffix("/") || stored.apiKey != newSettings.apiKey.trim() ||
                 stored.cfClientId != newSettings.cfClientId.trim() || stored.cfClientSecret != newSettings.cfClientSecret.trim())) {
                testResult = container.appContext.getString(R.string.settings_connection_busy)
                testOk = false
                return@launch
            }
            store.save(newSettings, preserveSessionState = true)
            saved = true
        }
    }

    fun test() {
        viewModelScope.launch {
            testing = true
            testOk = null
            val ctx = container.appContext
            testResult = ctx.getString(R.string.err_checking)
            try {
                val before = ClientSettings(
                    baseUrl = baseUrl, apiKey = apiKey, model = model, provider = provider,
                )
                if (!before.isConfigured) {
                    testResult = ctx.getString(R.string.err_health_key)
                    testOk = false
                    return@launch
                }
                val api = container.api(draft())
                val healthy = api.checkHealth()
                val caps = try {
                    api.getCapabilities()
                } catch (e: Exception) {
                    testResult = ctx.getString(
                        R.string.err_health_caps, healthy.toString(), (e.message ?: "").take(120)
                    )
                    testOk = false
                    return@launch
                }
                val flags = listOf("run_status", "run_events_sse", "run_stop", "run_steer", "run_approval_response")
                    .joinToString(", ") { "$it=${caps.featureEnabled(it)}" }
                testResult = "health=$healthy\n$flags"
                testOk = healthy
            } catch (e: Exception) {
                testResult = (e.message ?: e.javaClass.simpleName).take(300)
                testOk = false
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
        cfClientId = cfClientId, cfClientSecret = cfClientSecret,
        language = language, showForeignSessions = showForeignSessions,
        enterToSend = enterToSend, notificationsEnabled = notificationsEnabled,
    )
}
