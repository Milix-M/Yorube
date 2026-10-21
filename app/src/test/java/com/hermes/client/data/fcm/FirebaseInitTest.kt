package com.hermes.client.data.fcm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.data.store.ClientSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class FirebaseInitTest {

    @Before
    fun setUp() {
        FirebaseInit.initializer = null
    }

    @After
    fun tearDown() {
        FirebaseInit.initializer = null
    }

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    private fun configured() = ClientSettings(
        firebaseProjectId = "p",
        firebaseAppId = "a",
        firebaseApiKey = "k",
        firebaseSenderId = "1",
    )

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
    fun fakeNoop_returnsFalseWithoutRegistration() {
        FirebaseInit.initializer = { _, _ -> Unit }
        // The fake registers nothing, so getApps() stays empty and the
        // result stays false. Covers the init-call and result lines.
        assertFalse(FirebaseInit.ensureInitialized(ctx(), configured()))
    }

    @Test
    fun fakeThrow_returnsFalse() {
        FirebaseInit.initializer = { _, _ -> throw RuntimeException("nope") }
        assertFalse(FirebaseInit.ensureInitialized(ctx(), configured()))
    }

    @Test
    fun realInit_isStable() {
        val first = FirebaseInit.ensureInitialized(ctx(), configured())
        val second = FirebaseInit.ensureInitialized(ctx(), configured())
        assertEquals(first, second)
    }
}
