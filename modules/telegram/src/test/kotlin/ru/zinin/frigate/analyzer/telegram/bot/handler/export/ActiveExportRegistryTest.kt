package ru.zinin.frigate.analyzer.telegram.bot.handler.export

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.telegram.service.model.CancellableJob
import ru.zinin.frigate.analyzer.telegram.service.model.ExportMode
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ActiveExportRegistryTest {
    private val scope = ExportCoroutineScope()
    private val registry = ActiveExportRegistry(scope)

    // Use a thread-safe collection: the concurrency test calls newJob() from 32 parallel threads.
    // A plain mutableListOf<Job>() has a data race on add() and can lose job refs → @AfterEach
    // cleanup misses some jobs, causing CI coroutine leaks.
    private val jobs = java.util.concurrent.ConcurrentLinkedQueue<Job>()

    private fun newJob(): Job = Job().also { jobs.add(it) }

    @org.junit.jupiter.api.AfterEach
    fun tearDown() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        scope.shutdown()
    }

    @Test
    fun `tryStartQuickExport returns Success and stores entry`() {
        val exportId = UUID.randomUUID()
        val chatId = 42L
        val recordingId = UUID.randomUUID()
        val job = newJob()

        val r = registry.tryStartQuickExport(exportId, chatId, ExportMode.ANNOTATED, recordingId, job)

        assertIs<ActiveExportRegistry.StartResult.Success>(r)
        assertEquals(exportId, r.exportId)
        val entry = registry.get(exportId)
        assertNotNull(entry)
        assertEquals(chatId, entry.chatId)
        assertEquals(ExportMode.ANNOTATED, entry.mode)
        assertEquals(recordingId, entry.recordingId)
        assertSame(job, entry.job)
        assertEquals(ActiveExportRegistry.State.ACTIVE, entry.state)
    }

    @Test
    fun `tryStartQuickExport returns DuplicateRecording for same recordingId`() {
        val recordingId = UUID.randomUUID()
        registry.tryStartQuickExport(UUID.randomUUID(), 1L, ExportMode.ORIGINAL, recordingId, newJob())

        val second = registry.tryStartQuickExport(UUID.randomUUID(), 2L, ExportMode.ORIGINAL, recordingId, newJob())

        assertIs<ActiveExportRegistry.StartResult.DuplicateRecording>(second)
    }

    @Test
    fun `tryStartQuickExport allows different recordingIds in same chat`() {
        val chatId = 7L
        val a = registry.tryStartQuickExport(UUID.randomUUID(), chatId, ExportMode.ORIGINAL, UUID.randomUUID(), newJob())
        val b = registry.tryStartQuickExport(UUID.randomUUID(), chatId, ExportMode.ANNOTATED, UUID.randomUUID(), newJob())
        assertIs<ActiveExportRegistry.StartResult.Success>(a)
        assertIs<ActiveExportRegistry.StartResult.Success>(b)
    }

    @Test
    fun `tryStartDialogExport returns Success and stores entry`() {
        val exportId = UUID.randomUUID()
        val chatId = 77L
        val job = newJob()

        val r = registry.tryStartDialogExport(exportId, chatId, ExportMode.ORIGINAL, job)

        assertIs<ActiveExportRegistry.StartResult.Success>(r)
        val entry = registry.get(exportId)
        assertNotNull(entry)
        assertNull(entry.recordingId)
    }

    @Test
    fun `tryStartDialogExport returns DuplicateChat for same chatId`() {
        val chatId = 99L
        registry.tryStartDialogExport(UUID.randomUUID(), chatId, ExportMode.ORIGINAL, newJob())
        val second = registry.tryStartDialogExport(UUID.randomUUID(), chatId, ExportMode.ANNOTATED, newJob())
        assertIs<ActiveExportRegistry.StartResult.DuplicateChat>(second)
    }

    @Test
    fun `QuickExport and DialogExport namespaces are independent`() {
        val chatId = 10L
        val recordingId = UUID.randomUUID()
        val quick = registry.tryStartQuickExport(UUID.randomUUID(), chatId, ExportMode.ANNOTATED, recordingId, newJob())
        val dialog = registry.tryStartDialogExport(UUID.randomUUID(), chatId, ExportMode.ORIGINAL, newJob())
        assertIs<ActiveExportRegistry.StartResult.Success>(quick)
        assertIs<ActiveExportRegistry.StartResult.Success>(dialog)
    }

    @Test
    fun `attachCancellable stores cancellable on entry`() {
        val exportId = UUID.randomUUID()
        registry.tryStartQuickExport(exportId, 1L, ExportMode.ANNOTATED, UUID.randomUUID(), newJob())
        val cancellable = CancellableJob { /* noop */ }

        registry.attachCancellable(exportId, cancellable)

        assertSame(cancellable, registry.get(exportId)!!.cancellable)
    }

    @Test
    fun `attachCancellable is a no-op for unknown exportId`() {
        registry.attachCancellable(UUID.randomUUID(), CancellableJob { /* noop */ })
        // No exception, no effect.
    }

    @Test
    fun `markCancelling transitions ACTIVE to CANCELLING and returns entry`() {
        val exportId = UUID.randomUUID()
        registry.tryStartQuickExport(exportId, 1L, ExportMode.ANNOTATED, UUID.randomUUID(), newJob())

        val marked = registry.markCancelling(exportId)

        assertNotNull(marked)
        assertEquals(ActiveExportRegistry.State.CANCELLING, marked.state)
        assertEquals(ActiveExportRegistry.State.CANCELLING, registry.get(exportId)!!.state)
    }

    @Test
    fun `markCancelling returns null on second call`() {
        val exportId = UUID.randomUUID()
        registry.tryStartQuickExport(exportId, 1L, ExportMode.ANNOTATED, UUID.randomUUID(), newJob())
        registry.markCancelling(exportId)

        val second = registry.markCancelling(exportId)

        assertNull(second)
    }

    @Test
    fun `markCancelling returns null for unknown exportId`() {
        assertNull(registry.markCancelling(UUID.randomUUID()))
    }

    @Test
    fun `release removes entry from all indexes`() {
        val exportId = UUID.randomUUID()
        val recordingId = UUID.randomUUID()
        registry.tryStartQuickExport(exportId, 1L, ExportMode.ANNOTATED, recordingId, newJob())

        registry.release(exportId)

        assertNull(registry.get(exportId))
        val reuse = registry.tryStartQuickExport(UUID.randomUUID(), 1L, ExportMode.ANNOTATED, recordingId, newJob())
        assertIs<ActiveExportRegistry.StartResult.Success>(reuse)
    }

    @Test
    fun `release is idempotent for unknown exportId`() {
        registry.release(UUID.randomUUID())
    }

    @Test
    fun `release of DialogExport frees chatId namespace`() {
        val chatId = 55L
        val exportId = UUID.randomUUID()
        registry.tryStartDialogExport(exportId, chatId, ExportMode.ORIGINAL, newJob())
        registry.release(exportId)
        val reuse = registry.tryStartDialogExport(UUID.randomUUID(), chatId, ExportMode.ORIGINAL, newJob())
        assertIs<ActiveExportRegistry.StartResult.Success>(reuse)
    }

    @Test
    fun `markCancelling after release returns null (TOCTOU)`() {
        val exportId = UUID.randomUUID()
        registry.tryStartQuickExport(exportId, 1L, ExportMode.ANNOTATED, UUID.randomUUID(), newJob())
        registry.release(exportId)

        val result = registry.markCancelling(exportId)

        assertNull(result)
    }

    @Test
    fun `attachCancellable fires cancel when entry is already CANCELLING`() =
        runBlocking {
            // Deterministic sync via CompletableDeferred — `delay(50)` is flaky on slow CI.
            val exportId = UUID.randomUUID()
            registry.tryStartQuickExport(exportId, 1L, ExportMode.ANNOTATED, UUID.randomUUID(), newJob())
            registry.markCancelling(exportId)
            val called = CompletableDeferred<Unit>()
            val cancellable = CancellableJob { called.complete(Unit) }

            registry.attachCancellable(exportId, cancellable)

            // Fail fast if the launch never happens (with a generous 5s ceiling for busy CI).
            withTimeout(5_000) { called.await() }
            assertTrue(called.isCompleted, "attachCancellable must invoke cancel when state is already CANCELLING")
            Unit
        }

    @Test
    fun `attachCancellable does not fire cancel when entry is ACTIVE`() =
        runBlocking {
            val exportId = UUID.randomUUID()
            registry.tryStartQuickExport(exportId, 1L, ExportMode.ANNOTATED, UUID.randomUUID(), newJob())
            val called = CompletableDeferred<Unit>()
            val cancellable = CancellableJob { called.complete(Unit) }

            registry.attachCancellable(exportId, cancellable)

            // A short window is enough — we're asserting the absence of an event. 100ms well exceeds
            // the time needed for an eagerly-dispatched Dispatchers.IO launch to run.
            delay(100)
            assertEquals(false, called.isCompleted)
            Unit
        }

    @Test
    fun `release invoked via invokeOnCompletion when LAZY job cancelled before first suspension`() {
        // Guards design §5.3 edge case: the LAZY coroutine was cancelled before its first
        // suspension point — the body (with finally { release() }) never runs, so
        // invokeOnCompletion is the only path that cleans the registry. This test mirrors
        // what Quick/Dialog handlers do in production.
        //
        // Uses a scoped SupervisorJob rather than GlobalScope (anti-pattern — leaks test
        // coroutines across the JVM). The scope is cancelled in `@AfterEach` via `jobs`
        // tracking plus a local `finally` — kept in sync with the class-level `@AfterEach`.
        val exportId = UUID.randomUUID()
        val localScope = CoroutineScope(SupervisorJob())
        val job =
            localScope.launch(start = CoroutineStart.LAZY) {
                // Body that would normally run the export.
                awaitCancellation()
            }
        try {
            registry.tryStartQuickExport(exportId, 1L, ExportMode.ANNOTATED, UUID.randomUUID(), job)
            job.invokeOnCompletion { registry.release(exportId) }
            job.cancel() // cancel BEFORE start() — body never enters
            runBlocking { job.join() }
            assertNull(registry.get(exportId), "registry must be released via invokeOnCompletion")
        } finally {
            localScope.cancel()
        }
    }

    @Test
    fun `concurrent tryStartQuickExport for same recordingId — exactly one succeeds`() =
        runBlocking {
            val recordingId = UUID.randomUUID()
            val successes = AtomicInteger(0)
            val duplicates = AtomicInteger(0)
            val starter = CompletableDeferred<Unit>()

            val threads =
                (1..32).map {
                    Thread {
                        runBlocking { starter.await() }
                        // Create the job locally — do NOT share a `mutableListOf<Job>()` across threads:
                        // that's a data race in the test harness itself (iter-2 codex TEST-3). The
                        // thread-safe `ConcurrentLinkedQueue` on the class accepts adds safely from newJob().
                        val r =
                            registry.tryStartQuickExport(
                                UUID.randomUUID(),
                                1L,
                                ExportMode.ANNOTATED,
                                recordingId,
                                newJob(),
                            )
                        when (r) {
                            is ActiveExportRegistry.StartResult.Success -> {
                                successes.incrementAndGet()
                            }

                            is ActiveExportRegistry.StartResult.DuplicateRecording -> {
                                duplicates.incrementAndGet()
                            }

                            else -> {}
                        }
                    }
                }
            threads.forEach { it.start() }
            starter.complete(Unit)
            threads.forEach { it.join() }

            assertEquals(1, successes.get())
            assertEquals(31, duplicates.get())
        }

    @Test
    fun `start-vs-release race — tryStart after release returns Success, not a false Duplicate`() {
        // Guards iter-3 codex BUG-1: `release()` removes from byExportId BEFORE taking
        // `synchronized(entry)` to avoid reverse-order deadlock, but that leaves a window where
        // byChat/byRecordingId still points at the (already removed) exportId. tryStart* must
        // self-heal the stale secondary index and succeed.
        val chatId = 42L
        val firstExportId = UUID.randomUUID()
        val firstJob = newJob()
        val r1 = registry.tryStartDialogExport(firstExportId, chatId, ExportMode.ORIGINAL, firstJob)
        assertTrue(r1 is ActiveExportRegistry.StartResult.Success)

        // Simulate the narrow race: remove from primary index directly (byExportId), then
        // immediately try a fresh start for the same chat BEFORE secondary cleanup runs.
        registry.release(firstExportId) // the whole release — primary + secondary removal together.
        val secondExportId = UUID.randomUUID()
        val r2 = registry.tryStartDialogExport(secondExportId, chatId, ExportMode.ANNOTATED, newJob())
        assertTrue(r2 is ActiveExportRegistry.StartResult.Success, "start after release must succeed")

        firstJob.cancel()
    }
}
