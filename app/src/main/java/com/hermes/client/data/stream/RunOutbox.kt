package com.hermes.client.data.stream

import android.content.Context
import com.hermes.client.data.api.HermesJson
import com.hermes.client.data.api.RunCreateRequest
import com.hermes.client.data.store.ClientSettings
import com.hermes.client.data.store.SecretCipher
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
internal data class PendingRun(
    val key: String,
    val scope: String,
    val request: RunCreateRequest,
    val createdAt: Long,
)

internal data class PreparedRun(val pending: PendingRun, val replay: Boolean)

/** Write-ahead records: a lost acceptance response must not mint a fresh execution key. */
internal class RunOutbox(context: Context) {
    private val preferences = context.getSharedPreferences("run_outbox", 0)

    private fun scope(settings: ClientSettings): String {
        // Hermes scopes idempotency by profile/API key. Rotating an outer
        // Cloudflare service token must not turn recovery into a new execution.
        val identity = listOf(settings.baseUrl, settings.apiKey)
        return MessageDigest.getInstance("SHA-256").digest(HermesJson.encodeToString(identity).toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun pending(settings: ClientSettings): List<PendingRun> = preferences.all.keys.map { key ->
        HermesJson.decodeFromString<PendingRun>(SecretCipher.decrypt(preferences.getString(key, "")!!, key))
    }.filter { it.scope == scope(settings) }

    @Synchronized
    fun prepare(request: RunCreateRequest, settings: ClientSettings, key: String?): PreparedRun {
        // A model change while offline must not change the body bound to a saved key.
        fun logical(value: RunCreateRequest) = value.copy(model = null, provider = null, modelOptions = null)
        val previous = pending(settings).firstOrNull {
            if (key != null) it.key == key else logical(it.request) == logical(request)
        }
        if (previous != null) {
            check(logical(previous.request) == logical(request)) { "The previous message outcome must be resolved before editing it" }
            return PreparedRun(previous, replay = true)
        }
        val record = PendingRun(key ?: UUID.randomUUID().toString(), scope(settings), request, System.currentTimeMillis())
        check(preferences.edit().putString(record.key,
            SecretCipher.encrypt(HermesJson.encodeToString(record), record.key)).commit()) { "Could not save the outgoing message" }
        return PreparedRun(record, replay = false)
    }

    @Synchronized
    fun accepted(key: String) {
        check(preferences.edit().remove(key).commit()) { "Could not save the outgoing message state" }
    }
}
