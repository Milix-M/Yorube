package com.hermes.client.data.fcm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.data.store.ClientSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class FirebaseInitTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun unconfigured_returnsFalse() {
        assertFalse(FirebaseInit.ensureInitialized(ctx(), ClientSettings()))
        assertFalse(
            FirebaseInit.ensureInitialized(
                ctx(), ClientSettings(firebaseProjectId = "only-this")
            )
        )
    }

    @Test
    fun configured_isStableAcrossCalls() {
        val settings = ClientSettings(
            firebaseProjectId = "p",
            firebaseAppId = "a",
            firebaseApiKey = "k",
            firebaseSenderId = "1",
        )
        val first = FirebaseInit.ensureInitialized(ctx(), settings)
        val second = FirebaseInit.ensureInitialized(ctx(), settings)
        assertEquals(first, second)
    }
}
