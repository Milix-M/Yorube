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
                FirebaseApp.initializeApp(context, options)
            }
            FirebaseApp.getApps(context).isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }
}
