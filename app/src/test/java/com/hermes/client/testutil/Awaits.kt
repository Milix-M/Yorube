package com.hermes.client.testutil

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockWebServer

/**
 * Polls [cond] until true. Each round pumps the test scheduler (so Main-bound
 * continuations run on the test thread with proper visibility) and then yields
 * real time for IO-backed work such as DataStore and MockWebServer.
 */
suspend fun TestScope.awaitCondition(
    timeoutMs: Long = 15_000,
    cond: suspend () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        testScheduler.advanceUntilIdle()
        if (cond()) return
        if (System.currentTimeMillis() >= deadline) error("timed out waiting")
        withContext(Dispatchers.IO) { Thread.sleep(50) }
    }
}

/** Runs a ViewModel test with Main bound to the test scheduler. */
fun runVmTest(block: suspend TestScope.() -> Unit) = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
        block()
    } finally {
        Dispatchers.resetMain()
    }
}

/**
 * Shuts [server] down even when a test deliberately leaves a stream
 * hanging. MockWebServer.shutdown() (OkHttp 4.12) closes only the
 * listening socket and then waits at most 5 s per task queue, throwing
 * IOException("Gave up waiting for queue to shut down") if a connection
 * thread is still busy — and a thread parked in a long response delay
 * (the 10–30 s headers delays used to hold an SSE stream open) is just
 * Thread.sleep-ing: closing sockets cannot wake it, and shutdown()
 * never interrupts it. Retrying shutdown() is safe (the socket close is
 * idempotent and the queue await simply re-runs), so keep retrying
 * until the server's own threads have drained; only give up — with the
 * original exception — once [timeoutMs] has passed.
 */
fun shutdownServer(server: MockWebServer, timeoutMs: Long = 60_000) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        try {
            server.shutdown()
            return
        } catch (e: IOException) {
            if (System.currentTimeMillis() >= deadline) throw e
            Thread.sleep(250)
        }
    }
}
