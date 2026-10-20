package com.hermes.client

import android.app.Application
import com.hermes.client.data.api.HermesApi
import com.hermes.client.data.fcm.WatchRegistrar
import com.hermes.client.data.store.ClientSettings
import com.hermes.client.data.store.SettingsStore

class AppContainer(val appContext: android.content.Context) {
    val settingsStore = SettingsStore(appContext)
    val watchRegistrar = WatchRegistrar()

    suspend fun api(): HermesApi {
        val s: ClientSettings = settingsStore.current()
        return HermesApi(
            baseUrl = s.baseUrl,
            apiKey = s.apiKey,
            allowInsecureHosts = BuildConfig.DEBUG,
        )
    }
}

class HermesApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
