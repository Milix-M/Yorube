package com.hermes.client.ui.screens

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.AppContainer
import com.hermes.client.data.store.ClientSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test: every field edited on the Settings screen must survive
 * save() into the store. A missing field here (e.g. Cloudflare tokens)
 * silently breaks auth at runtime.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SettingsViewModelTest {

    @Test
    fun save_persistsAllFields() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val container = AppContainer(app)
            val vm = SettingsViewModel(container)

            vm.baseUrl = "https://example.com:8642"
            vm.apiKey = "k"
            vm.model = "m"
            vm.provider = "p"
            vm.relayUrl = "https://relay:8090"
            vm.firebaseProjectId = "proj"
            vm.firebaseAppId = "appid"
            vm.firebaseApiKey = "fbkey"
            vm.firebaseSenderId = "sender"
            vm.cfClientId = "cid"
            vm.cfClientSecret = "csec"
            vm.save()

            val saved = awaitStored(container)
            assertEquals("https://example.com:8642", saved.baseUrl)
            assertEquals("k", saved.apiKey)
            assertEquals("m", saved.model)
            assertEquals("p", saved.provider)
            assertEquals("https://relay:8090", saved.relayUrl)
            assertEquals("proj", saved.firebaseProjectId)
            assertEquals("appid", saved.firebaseAppId)
            assertEquals("fbkey", saved.firebaseApiKey)
            assertEquals("sender", saved.firebaseSenderId)
            assertEquals("cid", saved.cfClientId)
            assertEquals("csec", saved.cfClientSecret)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private suspend fun awaitStored(container: AppContainer): ClientSettings {
        repeat(100) {
            val cur = container.settingsStore.current()
            if (cur.baseUrl.isNotEmpty()) return cur
            withContext(Dispatchers.IO) { Thread.sleep(100) }
        }
        return container.settingsStore.current()
    }
}
