package com.hermes.client.data.fcm

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class WatchRequest(
    val run_id: String,
    val session_id: String,
    val fcm_token: String,
    val title: String = "Yorube",
)

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/**
 * Best-effort registration with the FCM relay (server/fcm-relay).
 * The relay streams the run to its terminal event, then pushes one FCM
 * data message. Failures never block the chat.
 */
class WatchRegistrar(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun registerWatch(
        relayBaseUrl: String,
        runId: String,
        sessionId: String,
        fcmToken: String,
    ): Boolean = withContext(Dispatchers.IO) {
        if (relayBaseUrl.isBlank() || fcmToken.isBlank()) return@withContext false
        try {
            val payload = Json.encodeToString(
                WatchRequest(runId, sessionId, fcmToken)
            )
            val request = Request.Builder()
                .url(relayBaseUrl.removeSuffix("/") + "/watch")
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }
}
