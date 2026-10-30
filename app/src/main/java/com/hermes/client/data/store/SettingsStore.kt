package com.hermes.client.data.store

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "hermes_settings")

/** App language: "system", "ja", "en", "zh" or "ko". Anything else falls back to system. */
const val LANGUAGE_SYSTEM = "system"
const val LANGUAGE_JA = "ja"
const val LANGUAGE_EN = "en"
const val LANGUAGE_ZH = "zh"
const val LANGUAGE_KO = "ko"

/** Supported in-app languages (codes). */
val SUPPORTED_LANGUAGES = listOf(LANGUAGE_SYSTEM, LANGUAGE_JA, LANGUAGE_EN, LANGUAGE_ZH, LANGUAGE_KO)

data class ClientSettings(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val provider: String = "",
    val relayUrl: String = "",
    val fcmToken: String = "",
    val firebaseProjectId: String = "",
    val firebaseAppId: String = "",
    val firebaseApiKey: String = "",
    val firebaseSenderId: String = "",
    val cfClientId: String = "",
    val cfClientSecret: String = "",
    val pinnedSessionIds: Set<String> = emptySet(),
    val language: String = LANGUAGE_SYSTEM,
    val showForeignSessions: Boolean = true,
    val enterToSend: Boolean = false,
    /** Master switch for FCM push notifications (completion and approval). */
    val notificationsEnabled: Boolean = true,
    /**
     * Session keys the app itself created or successfully ran on: explicit
     * proof of "made by this app", so the foreign-session filter never
     * hides them even if the server sends odd owner metadata.
     */
    val appOwnedSessionIds: Set<String> = emptySet(),
    /**
     * Session keys known to be owned by another surface (e.g. Discord):
     * recorded when a run is rejected with permission_denied, so history
     * can hide them when [showForeignSessions] is off.
     */
    val foreignSessionIds: Set<String> = emptySet(),
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
        val CF_ID = stringPreferencesKey("cf_client_id")
        val CF_SECRET = stringPreferencesKey("cf_client_secret")
        val PINNED = stringSetPreferencesKey("pinned_sessions")
        val LANGUAGE = stringPreferencesKey("language")
        val SHOW_FOREIGN = booleanPreferencesKey("show_foreign_sessions")
        val ENTER_TO_SEND = booleanPreferencesKey("enter_to_send")
        val NOTIF_ENABLED = booleanPreferencesKey("notifications_enabled")
        val APP_OWNED_IDS = stringSetPreferencesKey("app_owned_session_ids")
        val FOREIGN_IDS = stringSetPreferencesKey("foreign_session_ids")
    }

    val settings: Flow<ClientSettings> = context.settingsDataStore.data.map { p ->
        ClientSettings(
            baseUrl = p[Keys.BASE_URL] ?: "",
            apiKey = p[Keys.API_KEY] ?: "",
            model = p[Keys.MODEL] ?: "",
            provider = p[Keys.PROVIDER] ?: "",
            relayUrl = p[Keys.RELAY_URL] ?: "",
            fcmToken = p[Keys.FCM_TOKEN] ?: "",
            firebaseProjectId = p[Keys.FB_PROJECT] ?: "",
            firebaseAppId = p[Keys.FB_APP_ID] ?: "",
            firebaseApiKey = p[Keys.FB_API_KEY] ?: "",
            firebaseSenderId = p[Keys.FB_SENDER] ?: "",
            cfClientId = p[Keys.CF_ID] ?: "",
            cfClientSecret = p[Keys.CF_SECRET] ?: "",
            pinnedSessionIds = p[Keys.PINNED] ?: emptySet(),
            language = (p[Keys.LANGUAGE] ?: LANGUAGE_SYSTEM).takeIf { it in SUPPORTED_LANGUAGES }
                ?: LANGUAGE_SYSTEM,
            showForeignSessions = p[Keys.SHOW_FOREIGN] ?: true,
            enterToSend = p[Keys.ENTER_TO_SEND] ?: false,
            notificationsEnabled = p[Keys.NOTIF_ENABLED] ?: true,
            appOwnedSessionIds = p[Keys.APP_OWNED_IDS] ?: emptySet(),
            foreignSessionIds = p[Keys.FOREIGN_IDS] ?: emptySet(),
        )
    }

    suspend fun current(): ClientSettings = try {
        settings.first()
    } catch (_: Exception) {
        ClientSettings()
    }

    suspend fun save(value: ClientSettings) {
        context.settingsDataStore.edit { p ->
            p[Keys.BASE_URL] = value.baseUrl.trim().removeSuffix("/")
            p[Keys.API_KEY] = value.apiKey.trim()
            p[Keys.MODEL] = value.model.trim()
            p[Keys.PROVIDER] = value.provider.trim()
            p[Keys.RELAY_URL] = value.relayUrl.trim().removeSuffix("/")
            p[Keys.FCM_TOKEN] = value.fcmToken
            p[Keys.FB_PROJECT] = value.firebaseProjectId.trim()
            p[Keys.FB_APP_ID] = value.firebaseAppId.trim()
            p[Keys.FB_API_KEY] = value.firebaseApiKey.trim()
            p[Keys.FB_SENDER] = value.firebaseSenderId.trim()
            p[Keys.CF_ID] = value.cfClientId.trim()
            p[Keys.CF_SECRET] = value.cfClientSecret.trim()
            p[Keys.PINNED] = value.pinnedSessionIds
            p[Keys.LANGUAGE] = value.language.takeIf { it in SUPPORTED_LANGUAGES } ?: LANGUAGE_SYSTEM
            p[Keys.SHOW_FOREIGN] = value.showForeignSessions
            p[Keys.ENTER_TO_SEND] = value.enterToSend
            p[Keys.NOTIF_ENABLED] = value.notificationsEnabled
            p[Keys.APP_OWNED_IDS] = value.appOwnedSessionIds
            p[Keys.FOREIGN_IDS] = value.foreignSessionIds } }

    suspend fun saveFcmToken(token: String) { context.settingsDataStore.edit { p -> p[Keys.FCM_TOKEN] = token } }
}
