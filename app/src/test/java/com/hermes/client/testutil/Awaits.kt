package com.hermes.client.testutil

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withContext

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
