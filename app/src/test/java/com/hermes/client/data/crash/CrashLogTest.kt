package com.hermes.client.data.crash

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CrashLogTest {

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    private var prevHandler: Thread.UncaughtExceptionHandler? = null

    @After
    fun restoreHandler() {
        Thread.setDefaultUncaughtExceptionHandler(prevHandler)
        CrashLog.clear(app())
    }

    @Test
    fun format_includesThreadAndCauses() {
        val cause = IllegalStateException("root")
        val error = RuntimeException("boom", cause)
        val text = CrashLog.format(Thread.currentThread(), error)
        assertTrue(text.contains("boom"))
        assertTrue(text.contains("root"))
        assertTrue(text.contains(Thread.currentThread().name))
        assertTrue(text.contains("Caused by"))
    }

    @Test
    fun writeReadClear_roundTrip() {
        assertEquals("", CrashLog.read(app()))
        CrashLog.writeReport(app(), "first")
        CrashLog.writeReport(app(), "second")
        val all = CrashLog.read(app())
        assertTrue(all.contains("first"))
        assertTrue(all.contains("second"))
        // Newest first.
        assertTrue(all.indexOf("second") < all.indexOf("first"))
        CrashLog.clear(app())
        assertEquals("", CrashLog.read(app()))
    }

    @Test
    fun writeReport_truncatesHuge() {
        CrashLog.writeReport(app(), "x".repeat(CrashLog.MAX_BYTES + 100))
        assertTrue(CrashLog.read(app()).length <= CrashLog.MAX_BYTES)
    }

    @Test
    fun install_recordsAndDelegates() {
        prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        val seen = AtomicReference<Throwable?>()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> seen.set(e) }
        CrashLog.install(app())
        val failure = RuntimeException("crash-test")
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), failure)
        assertEquals(failure, seen.get())
        assertTrue(CrashLog.read(app()).contains("crash-test"))
    }
}
