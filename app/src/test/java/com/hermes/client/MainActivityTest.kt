package com.hermes.client

import android.content.Intent
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class MainActivityTest {

    @Test
    fun createAndNewIntent() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull(activity)
        activity.onNewIntent(
            Intent(activity, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SESSION_ID, "s9")
        )
    }
}
