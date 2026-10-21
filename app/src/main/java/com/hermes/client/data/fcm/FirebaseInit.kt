package com.hermes.client.data.fcm

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.hermes.client.data.store.ClientSettings

/**
 * Manual Firebase init. The build does not apply the google-services plugin,
 * so the app works without google-services.json. FCM stays disabled until
 * the user fills the four Firebase fields in Settings.
 */
object FirebaseInit {

    /**
     * Test seam: replaces the real FirebaseApp init. Null (default) uses the
     * real SDK. Tests set a fake to cover success and failure deterministically.
     */
    internal var initializer: ((Context, FirebaseOptions) -> Unit)? = null

    @Synchronized
    fun ensureInitialized(context: Context, settings: ClientSettings): Boolean {
        if (!settings.firebaseConfigured) return false
        return try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setProjectId(settings.firebaseProjectId)
                    .setApplicationId(settings.firebaseAppId)
                    .setApiKey(settings.firebaseApiKey)
                    .setGcmSenderId(settings.firebaseSenderId)
                    .build()
                val init = initializer ?: { ctx: Context, opts: FirebaseOptions ->
                    FirebaseApp.initializeApp(ctx, opts)
                    Unit
                }
                init(context, options)
            }
            FirebaseApp.getApps(context).isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }
}
