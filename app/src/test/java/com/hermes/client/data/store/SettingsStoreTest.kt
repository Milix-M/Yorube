package com.hermes.client.data.store

import android.app.Application
import androidx.datastore.preferences.PreferencesProto
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.HermesApp
import com.hermes.client.testutil.runVmTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SettingsStoreTest {

    /**
     * Drops the process-wide DataStore singleton so the next access binds a
     * fresh instance to this test's application. Without this, current()
     * would serve state cached from other tests and never read our file.
     */
    private fun resetDataStoreSingleton() {
        val delegateField = Class.forName("com.hermes.client.data.store.SettingsStoreKt")
            .getDeclaredField("settingsDataStore\$delegate")
        delegateField.isAccessible = true
        val delegate = delegateField.get(null)
        val instanceField = delegate.javaClass.getDeclaredField("INSTANCE")
        instanceField.isAccessible = true
        instanceField.set(delegate, null)
    }

    /**
     * A readable preferences file whose value type disagrees with the key
     * (Int stored under a String key) must not crash current(): the
     * ClassCastException from the unchecked Preferences.get is rescued by
     * the catch and reported as default (empty) settings.
     */
    @Test
    fun current_wrongTypedValue_returnsDefault() = runVmTest {
        val app = ApplicationProvider.getApplicationContext<Application>() as HermesApp
        resetDataStoreSingleton()
        val storeFile = java.io.File(app.filesDir, "datastore/hermes_settings.preferences_pb")
        try {
            storeFile.parentFile?.mkdirs()
            val poisoned = PreferencesProto.PreferenceMap.newBuilder()
                .putPreferences(
                    "base_url",
                    PreferencesProto.Value.newBuilder().setInteger(5).build(),
                )
                .build()
            storeFile.writeBytes(poisoned.toByteArray())
            val store = SettingsStore(app)
            assertEquals(ClientSettings(), store.current())
        } finally {
            storeFile.delete()
            SettingsStore(app).save(ClientSettings(baseUrl = "https://example.com", apiKey = "k"))
            resetDataStoreSingleton()
        }
    }
}
