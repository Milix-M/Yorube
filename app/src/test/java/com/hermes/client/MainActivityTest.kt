package com.hermes.client

import android.content.Intent
import android.media.AudioManager
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [MainActivityTest.Api33AudioManagerShadow::class])
class MainActivityTest {

    // Let Compose manage frame advancement and dispose the activity after the test.
    @get:Rule
    val compose = createEmptyComposeRule()

    // Robolectric 4.17's API 33 audio-service proxy resolves BluetoothAddress,
    // which does not exist on API 33. Model navigation sounds as disabled so
    // ViewRootImpl can attach the activity without instantiating that proxy.
    // Keep this scoped to the activity test; retain its API 33 coverage.
    @Implements(AudioManager::class)
    class Api33AudioManagerShadow : ShadowAudioManager() {
        @Implementation(minSdk = 33, maxSdk = 33)
        protected fun areNavigationRepeatSoundEffectsEnabled(): Boolean = false
    }

    @Test
    fun createAndNewIntent() {
        // Manage the controller directly: onNewIntent recreates the activity,
        // which can leave ActivityScenario's invoker holding the old instance.
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            compose.runOnUiThread { controller.setup() }
            val activity = controller.get()
            assertNotNull(activity)
            compose.runOnUiThread {
                controller.newIntent(
                    Intent(activity, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_SESSION_ID, "s9"),
                )
                assertEquals("s9", activity.intent.getStringExtra(MainActivity.EXTRA_SESSION_ID))
            }
            compose.waitForIdle()
        }
    }
}
