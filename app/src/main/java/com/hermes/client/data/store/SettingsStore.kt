package com.hermes.client.data.store

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "hermes_settings")

data class ClientSettings(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "hermes-agent",
    val provider: String = "",
    val relayUrl: String = "",
    val fcmToken: String = "",
    val firebaseProjectId: String = "",
    val firebaseAppId: String = "",
    val firebaseApiKey: String = "",
    val firebaseSenderId: String = "",
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank()
    val firebaseConfigured: Boolean get() =
        firebaseProjectId.isNotBlank() && firebaseAppId.isNotBlank() &&
            firebaseApiKey.isNotBlank() && firebaseSenderId.isNotBlank()
}

class SettingsStore(private val context: Context) {
    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val API_KEY = stringPreferencesKey("api_key")
        val MODEL = stringPreferencesKey("model")
        val PROVIDER = stringPreferencesKey("provider")
        val RELAY_URL = stringPreferencesKey("relay_url")
        val FCM_TOKEN = stringPreferencesKey("fcm_token")
        val FB_PROJECT = stringPreferencesKey("fb_project")
        val FB_APP_ID = stringPreferencesKey("fb_app_id")
        val FB_API_KEY = stringPreferencesKey("fb_api_key")
        val FB_SENDER = stringPreferencesKey("fb_sender")
    }

    val settings: Flow<ClientSettings> = context.settingsDataStore.data.map { p ->
        ClientSettings(
            baseUrl = p[Keys.BASE_URL] ?: "",
            apiKey = p[Keys.API_KEY] ?: "",
            model = p[Keys.MODEL] ?: "hermes-agent",
            provider = p[Keys.PROVIDER] ?: "",
            relayUrl = p[Keys.RELAY_URL] ?: "",
            fcmToken = p[Keys.FCM_TOKEN] ?: "",
            firebaseProjectId = p[Keys.FB_PROJECT] ?: "",
            firebaseAppId = p[Keys.FB_APP_ID] ?: "",
            firebaseApiKey = p[Keys.FB_API_KEY] ?: "",
            firebaseSenderId = p[Keys.FB_SENDER] ?: "",
        )
    }

    suspend fun current(): ClientSettings = settings.first()

    suspend fun save(value: ClientSettings) {
        context.settingsDataStore.edit { p ->
            p[Keys.BASE_URL] = value.baseUrl.trim().removeSuffix("/")
            p[Keys.API_KEY] = value.apiKey.trim()
            p[Keys.MODEL] = value.model.trim().ifBlank { "hermes-agent" }
            p[Keys.PROVIDER] = value.provider.trim()
            p[Keys.RELAY_URL] = value.relayUrl.trim().removeSuffix("/")
            p[Keys.FCM_TOKEN] = value.fcmToken
            p[Keys.FB_PROJECT] = value.firebaseProjectId.trim()
            p[Keys.FB_APP_ID] = value.firebaseAppId.trim()
            p[Keys.FB_API_KEY] = value.firebaseApiKey.trim()
            p[Keys.FB_SENDER] = value.firebaseSenderId.trim()
        }
    }

    suspend fun saveFcmToken(token: String) {
        context.settingsDataStore.edit { p -> p[Keys.FCM_TOKEN] = token }
    }
}
