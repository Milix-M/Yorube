package com.hermes.client.data.store

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(
    name = "hermes_settings",
    produceMigrations = { listOf(SecretPreferencesMigration()) },
)

private fun encryptedKey(name: String) = stringPreferencesKey("$name.encrypted.v1")

/** DataStore commits all encrypted replacements before removing the legacy plaintext. */
internal class SecretPreferencesMigration : DataMigration<Preferences> {
    private val names = listOf("api_key", "cf_client_id", "cf_client_secret", "fb_api_key", "fcm_token")
    override suspend fun shouldMigrate(currentData: Preferences) =
        names.any { stringPreferencesKey(it) in currentData }

    override suspend fun migrate(currentData: Preferences): Preferences = currentData.toMutablePreferences().apply {
        names.forEach { name ->
            val legacy = stringPreferencesKey(name)
            currentData[legacy]?.let { value ->
                if (encryptedKey(name) !in this) this[encryptedKey(name)] = SecretCipher.encrypt(value, name)
            }
            remove(legacy)
        }
    }

    override suspend fun cleanUp() = Unit
}

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
    val reasoningEffort: String = "",
    val serviceTier: String = "",
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
    val modelOptions: Map<String, String>? get() = buildMap {
        if (reasoningEffort.isNotBlank()) put("reasoning_effort", reasoningEffort)
        if (serviceTier.isNotBlank()) put("service_tier", serviceTier)
    }.takeIf { it.isNotEmpty() }
    val firebaseConfigured: Boolean get() =
        firebaseProjectId.isNotBlank() && firebaseAppId.isNotBlank() &&
            firebaseApiKey.isNotBlank() && firebaseSenderId.isNotBlank()
}

class SettingsStore(private val context: Context) {
    @Volatile var snapshot = ClientSettings()
        private set
    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val API_KEY = encryptedKey("api_key")
        val MODEL = stringPreferencesKey("model")
        val REASONING = stringPreferencesKey("reasoning_effort")
        val SERVICE_TIER = stringPreferencesKey("service_tier")
        val PROVIDER = stringPreferencesKey("provider")
        val RELAY_URL = stringPreferencesKey("relay_url")
        val FCM_TOKEN = encryptedKey("fcm_token")
        val FB_PROJECT = stringPreferencesKey("fb_project")
        val FB_APP_ID = stringPreferencesKey("fb_app_id")
        val FB_API_KEY = encryptedKey("fb_api_key")
        val FB_SENDER = stringPreferencesKey("fb_sender")
        val CF_ID = encryptedKey("cf_client_id")
        val CF_SECRET = encryptedKey("cf_client_secret")
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
            apiKey = SecretCipher.decrypt(p[Keys.API_KEY].orEmpty(), "api_key"),
            model = p[Keys.MODEL] ?: "",
            provider = p[Keys.PROVIDER] ?: "",
            reasoningEffort = p[Keys.REASONING] ?: "",
            serviceTier = p[Keys.SERVICE_TIER] ?: "",
            relayUrl = p[Keys.RELAY_URL] ?: "",
            fcmToken = SecretCipher.decrypt(p[Keys.FCM_TOKEN].orEmpty(), "fcm_token"),
            firebaseProjectId = p[Keys.FB_PROJECT] ?: "",
            firebaseAppId = p[Keys.FB_APP_ID] ?: "",
            firebaseApiKey = SecretCipher.decrypt(p[Keys.FB_API_KEY].orEmpty(), "fb_api_key"),
            firebaseSenderId = p[Keys.FB_SENDER] ?: "",
            cfClientId = SecretCipher.decrypt(p[Keys.CF_ID].orEmpty(), "cf_client_id"),
            cfClientSecret = SecretCipher.decrypt(p[Keys.CF_SECRET].orEmpty(), "cf_client_secret"),
            pinnedSessionIds = p[Keys.PINNED] ?: emptySet(),
            language = (p[Keys.LANGUAGE] ?: LANGUAGE_SYSTEM).takeIf { it in SUPPORTED_LANGUAGES }
                ?: LANGUAGE_SYSTEM,
            showForeignSessions = p[Keys.SHOW_FOREIGN] ?: true,
            enterToSend = p[Keys.ENTER_TO_SEND] ?: false,
            notificationsEnabled = p[Keys.NOTIF_ENABLED] ?: true,
            appOwnedSessionIds = p[Keys.APP_OWNED_IDS] ?: emptySet(),
            foreignSessionIds = p[Keys.FOREIGN_IDS] ?: emptySet(),
        ).also { snapshot = it }
    }

    suspend fun current(): ClientSettings = try {
        settings.first()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        ClientSettings()
    }

    suspend fun save(value: ClientSettings, preserveSessionState: Boolean = false) {
        context.settingsDataStore.edit { p ->
            val pins = p[Keys.PINNED].orEmpty()
            val owned = p[Keys.APP_OWNED_IDS].orEmpty()
            val foreign = p[Keys.FOREIGN_IDS].orEmpty()
            p[Keys.BASE_URL] = value.baseUrl.trim().removeSuffix("/")
            p[Keys.API_KEY] = SecretCipher.encrypt(value.apiKey.trim(), "api_key")
            p[Keys.MODEL] = value.model.trim()
            p[Keys.PROVIDER] = value.provider.trim()
            p[Keys.REASONING] = value.reasoningEffort
            p[Keys.SERVICE_TIER] = value.serviceTier
            p[Keys.RELAY_URL] = value.relayUrl.trim().removeSuffix("/")
            p[Keys.FCM_TOKEN] = SecretCipher.encrypt(value.fcmToken, "fcm_token")
            p[Keys.FB_PROJECT] = value.firebaseProjectId.trim()
            p[Keys.FB_APP_ID] = value.firebaseAppId.trim()
            p[Keys.FB_API_KEY] = SecretCipher.encrypt(value.firebaseApiKey.trim(), "fb_api_key")
            p[Keys.FB_SENDER] = value.firebaseSenderId.trim()
            p[Keys.CF_ID] = SecretCipher.encrypt(value.cfClientId.trim(), "cf_client_id")
            p[Keys.CF_SECRET] = SecretCipher.encrypt(value.cfClientSecret.trim(), "cf_client_secret")
            p[Keys.PINNED] = value.pinnedSessionIds
            p[Keys.LANGUAGE] = value.language.takeIf { it in SUPPORTED_LANGUAGES } ?: LANGUAGE_SYSTEM
            p[Keys.SHOW_FOREIGN] = value.showForeignSessions
            p[Keys.ENTER_TO_SEND] = value.enterToSend
            p[Keys.NOTIF_ENABLED] = value.notificationsEnabled
            p[Keys.APP_OWNED_IDS] = value.appOwnedSessionIds
            p[Keys.FOREIGN_IDS] = value.foreignSessionIds
            if (preserveSessionState) {
                p[Keys.PINNED] = pins
                p[Keys.APP_OWNED_IDS] = owned
                p[Keys.FOREIGN_IDS] = foreign
            }
        }
        current()
    }
    suspend fun setPinned(id: String, pinned: Boolean? = null): Set<String> {
        val updated = context.settingsDataStore.edit { p ->
            val current = p[Keys.PINNED].orEmpty()
            val shouldPin = pinned ?: (id !in current)
            p[Keys.PINNED] = if (shouldPin) current + id else current - id
        }
        return updated[Keys.PINNED].orEmpty()
    }

    suspend fun setRunOption(key: String, value: String) { context.settingsDataStore.edit { p ->
        p[if (key == "reasoning_effort") Keys.REASONING else Keys.SERVICE_TIER] = value
    } }
    suspend fun setModel(model: String, provider: String?) { context.settingsDataStore.edit { p ->
        p[Keys.MODEL] = model
        if (provider != null) p[Keys.PROVIDER] = provider
    } }


    suspend fun markAppOwned(id: String) { context.settingsDataStore.edit { p ->
        p[Keys.APP_OWNED_IDS] = p[Keys.APP_OWNED_IDS].orEmpty() + id
    } }
    suspend fun markForeign(id: String) { context.settingsDataStore.edit { p ->
        p[Keys.FOREIGN_IDS] = p[Keys.FOREIGN_IDS].orEmpty() + id
    } }

    suspend fun saveFcmToken(token: String) { context.settingsDataStore.edit { p ->
        p[Keys.FCM_TOKEN] = SecretCipher.encrypt(token, "fcm_token")
    } }
}
