package com.hermes.client

import android.content.Intent
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MainActivityTest {

    @Test
    fun createAndNewIntent() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull(activity)
        val m = MainActivity::class.java.getDeclaredMethod(
            "onNewIntent", android.content.Intent::class.java
        )
        m.isAccessible = true
        m.invoke(
            activity,
            android.content.Intent(activity, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SESSION_ID, "s9"),
        )
    }
}
