package ru.zinin.frigate.analyzer.ai.description.grok

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GrokHomeGuardTest {
    @Test
    fun `exclusive skips immediately if a shared block is in flight`() =
        runTest {
            val guard = GrokHomeGuard()
            val gate = CompletableDeferred<Unit>()
            var exclusiveRan = false

            launch { guard.shared { gate.await() } }
            advanceUntilIdle()
            assertFailsWith<GrokHomeGuard.ExclusiveBusyException> {
                guard.exclusive { exclusiveRan = true }
            }
            assertFalse(exclusiveRan)
            gate.complete(Unit)
            advanceUntilIdle()
        }

    @Test
    fun `shared waits while exclusive runs`() =
        runTest {
            val guard = GrokHomeGuard()
            val gate = CompletableDeferred<Unit>()
            var sharedRan = false

            launch { guard.exclusive { gate.await() } }
            advanceUntilIdle()
            launch { guard.shared { sharedRan = true } }
            advanceTimeBy(5_000)
            assertFalse(sharedRan, "shared must wait while exclusive holds the guard")

            gate.complete(Unit)
            advanceUntilIdle()
            assertTrue(sharedRan)
        }

    @Test
    fun `shared blocks run concurrently with each other`() =
        runTest {
            val guard = GrokHomeGuard()
            val gate = CompletableDeferred<Unit>()
            var entered = 0

            repeat(3) {
                launch {
                    guard.shared {
                        entered++
                        gate.await()
                    }
                }
            }
            advanceUntilIdle()
            assertEquals(3, entered)
            gate.complete(Unit)
            advanceUntilIdle()
        }

    @Test
    fun `block results and exceptions propagate`() =
        runTest {
            val guard = GrokHomeGuard()
            assertEquals(42, guard.shared { 42 })
            assertEquals("x", guard.exclusive { "x" })
            var failed = false
            try {
                guard.shared<Unit> { throw IllegalStateException("boom") }
            } catch (e: IllegalStateException) {
                failed = true
            }
            assertTrue(failed)
            assertEquals(1, guard.exclusive { 1 }, "a failed shared block must release its slot")
        }
}
