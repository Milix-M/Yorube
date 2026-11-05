package com.hermes.client

import android.app.Application
import com.hermes.client.data.api.HermesApi
import com.hermes.client.data.crash.CrashLog
import com.hermes.client.data.fcm.WatchRegistrar
import com.hermes.client.data.stream.RunStreamEngine
import com.hermes.client.data.store.ClientSettings
import com.hermes.client.data.store.SettingsStore

class AppContainer(val appContext: android.content.Context) {
    val settingsStore = SettingsStore(appContext)
    val watchRegistrar = WatchRegistrar()

    /**
     * App-scoped owner of the active run's SSE stream, so the stream
     * survives the chat screen (and, via RunStreamService, the app being
     * backgrounded). Lazy: the engine's scope binds Dispatchers.Main at
     * first use, and nothing may stream before the first run starts.
     */
    val runStreams: RunStreamEngine by lazy { RunStreamEngine(this) }

    suspend fun api(): HermesApi {
        val s: ClientSettings = settingsStore.current()
        val extra = buildMap {
            if (s.cfClientId.isNotBlank() && s.cfClientSecret.isNotBlank()) {
                put("CF-Access-Client-Id", s.cfClientId)
                put("CF-Access-Client-Secret", s.cfClientSecret)
            }
        }
        return HermesApi(
            baseUrl = s.baseUrl,
            apiKey = s.apiKey,
            extraHeaders = extra,
        )
    }
}

class HermesApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        container = AppContainer(this)
    }
}
