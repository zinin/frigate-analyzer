package ru.zinin.frigate.analyzer.core.task

import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.health.contributor.Status
import ru.zinin.frigate.analyzer.core.config.properties.RecordsWatcherProperties
import ru.zinin.frigate.analyzer.core.helper.SpringProfileHelper
import ru.zinin.frigate.analyzer.core.task.INITIAL_BACKOFF
import ru.zinin.frigate.analyzer.core.task.SUCCESSES_TO_RESET_BACKOFF
import ru.zinin.frigate.analyzer.core.task.WatchRecordsTask.BackoffResetResult
import ru.zinin.frigate.analyzer.core.task.WatchRecordsTask.WatchTaskState
import java.nio.file.ClosedWatchServiceException
import java.nio.file.Path
import java.nio.file.WatchService
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class WatchRecordsTaskTest {
    private val loop = mockk<WatchRecordsLoop>(relaxed = true)
    private val springProfileHelper =
        mockk<SpringProfileHelper> {
            every { isTestProfile() } returns false
        }
    private val properties =
        RecordsWatcherProperties(
            folder = Path.of("/tmp/wrt-test"),
            watchPeriod = Duration.ofDays(1),
            cleanupInterval = Duration.ofHours(1),
        )
    private val clock = Clock.fixed(Instant.parse("2026-05-23T12:00:00Z"), ZoneOffset.UTC)

    private fun newTask(
        watchServiceFactory: () -> WatchService = { mockk(relaxed = true) },
        dispatcher: CoroutineDispatcher = StandardTestDispatcher(),
    ) = WatchRecordsTask(
        recordsWatcherProperties = properties,
        loop = loop,
        clock = clock,
        springProfileHelper = springProfileHelper,
        dispatcher = dispatcher,
        watchServiceFactory = watchServiceFactory,
    )

    // JUnit 5 defaults to PER_METHOD instantiation, so mocks are fresh per test today.
    // This @BeforeEach is defensive — if a future maintainer ever adds @TestInstance(PER_CLASS)
    // for performance, mock state would otherwise leak across tests. clearMocks with
    // answers=false retains the springProfileHelper.isTestProfile() stub so we don't
    // re-stub it in every test.
    @BeforeEach
    fun setUp() {
        clearMocks(loop, springProfileHelper, answers = false)
    }

    // ----- Supervisor exception handling -----

    @Test
    fun `supervisor survives RuntimeException and continues looping`() =
        runTest {
            val task = newTask(dispatcher = StandardTestDispatcher(testScheduler))
            var iterations = 0
            coEvery { loop.runIteration(any(), any(), any()) } coAnswers {
                iterations++
                when (iterations) {
                    1 -> throw RuntimeException("boom1")
                    2 -> throw RuntimeException("boom2")
                    3 -> IterationResult(eventsProcessed = 1, eventFailures = 0, lastCleanupAt = Instant.now(clock))
                    else -> throw CancellationException("test done")
                }
            }

            val job = launch { task.runSupervised() }
            advanceUntilIdle()
            job.join()

            // After two RuntimeExceptions, then a success (events > 0), then cancel:
            //  - lastSuccessfulPollAt is set on the successful iteration
            //  - consecutiveFailures resets to 0 because eventsProcessed > 0
            //  - lastFailure remains the most recent RuntimeException (not cleared on success)
            val s = task.stateForTesting
            assertNotNull(s.lastSuccessfulPollAt, "Expected at least one successful poll")
            assertEquals(0, s.consecutiveFailures, "Counter should reset on success")
            assertTrue(s.lastFailure is RuntimeException)
        }

    @Test
    fun `ClosedWatchServiceException triggers watchService recreation`() =
        runTest {
            var factoryCallCount = 0
            val watchService = mockk<WatchService>(relaxed = true)
            val task =
                newTask(
                    watchServiceFactory = {
                        factoryCallCount++
                        watchService
                    },
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            var iterations = 0
            coEvery { loop.runIteration(any(), any(), any()) } coAnswers {
                iterations++
                when (iterations) {
                    1 -> throw ClosedWatchServiceException()
                    2 -> IterationResult(eventsProcessed = 1, eventFailures = 0, lastCleanupAt = Instant.now(clock))
                    else -> throw CancellationException("test done")
                }
            }

            val job = launch { task.runSupervised() }
            advanceUntilIdle()
            job.join()

            // Exactly 2 factory calls expected:
            //   iter 1 — factory(1) → runIteration throws CWS → closeWatchServiceQuietly sets watchService=null
            //   iter 2 — factory(2) → runIteration returns success → watchService remains set
            //   iter 3 — no factory call (watchService != null) → runIteration throws Cancellation → exit
            assertEquals(
                2,
                factoryCallCount,
                "WatchService should be recreated exactly once after ClosedWatchServiceException",
            )
            assertNotNull(task.stateForTesting.lastSuccessfulPollAt)
        }

    @Test
    fun `cancel exits loop cleanly without registering as failure`() =
        runTest {
            val task = newTask(dispatcher = StandardTestDispatcher(testScheduler))
            // Empty polls in the supervisor have no built-in delay, so the loop tight-spins on
            // mocked runIteration. We make the mock suspend via delay() so cancellation can
            // interrupt the coroutine at a known suspension point (matches production where
            // WatchService.poll(500ms) provides the natural pause).
            coEvery { loop.runIteration(any(), any(), any()) } coAnswers {
                delay(500)
                IterationResult(eventsProcessed = 0, eventFailures = 0, lastCleanupAt = Instant.now(clock))
            }

            val job = launch { task.runSupervised() }
            advanceTimeBy(2_000) // let the loop run a couple of empty polls
            job.cancel()
            advanceUntilIdle()

            assertEquals(0, task.stateForTesting.consecutiveFailures, "Cancel should NOT increment failures")
            assertTrue(job.isCancelled)
        }

    @Test
    fun `backoff resets after 5 consecutive successes after failures`() =
        runTest {
            val task = newTask(dispatcher = StandardTestDispatcher(testScheduler))
            // eventsProcessed = 1 is required: empty polls do NOT tick successesSinceLastFailure
            // (see onPollCompleted in WatchRecordsTask — maybeResetBackoffTransform is called only on events > 0).
            val successResult = IterationResult(eventsProcessed = 1, eventFailures = 0, lastCleanupAt = Instant.now(clock))
            var iterations = 0
            coEvery { loop.runIteration(any(), any(), any()) } coAnswers {
                iterations++
                when {
                    iterations <= 3 -> throw RuntimeException("e$iterations")
                    iterations <= 8 -> successResult
                    else -> throw CancellationException("test done")
                }
            }

            val job = launch { task.runSupervised() }
            advanceUntilIdle()
            job.join()

            val s = task.stateForTesting
            assertEquals(INITIAL_BACKOFF, s.currentBackoff, "Backoff should reset to INITIAL_BACKOFF=5s")
            assertEquals(0, s.consecutiveFailures)
        }

    // ----- 8-branch health table -----

    @Test
    fun `computeHealth branch 1 — returns DOWN when supervisor not started`() {
        val task = newTask()
        val now = Instant.parse("2026-05-23T12:05:00Z")
        val health = task.computeHealth(now)
        assertEquals(Status.DOWN, health.status)
        assertEquals("supervisor not active", health.details["reason"])
    }

    // Helper: build a task whose supervisor "looks active" without actually launching a coroutine.
    // The 11-field WatchTaskState is set via the `stateForTesting` setter — works because this
    // test file is in the same Kotlin module.
    private fun buildTask(
        state: WatchTaskState = WatchTaskState(startupAt = Instant.parse("2026-05-23T12:00:00Z")),
        registerDummyDir: Boolean = true,
    ): WatchRecordsTask {
        val task = newTask()
        task.supervisorJob = Job() // fresh Job is active until completed/cancelled
        task.stateForTesting = state
        // BRANCH 3.5 guard: a successful registration in real code always leaves at least one
        // entry in registeredDirs; tests that simulate "post-registration" state must populate
        // the registry, otherwise BRANCH 3.5 (empty registry → DOWN) fires before the branch
        // under test. Opt-out with registerDummyDir=false when explicitly testing BRANCH 3.5.
        if (registerDummyDir && state.lastSuccessfulRegistrationAt != null) {
            task.registeredDirs[Path.of("/tmp/wrt-test")] = mockk(relaxed = true)
        }
        return task
    }

    @Test
    fun `computeHealth branch 2 — startup failed by threshold returns DOWN`() {
        val task =
            buildTask(
                state =
                    WatchTaskState(
                        startupAt = Instant.parse("2026-05-23T12:00:00Z"),
                        lastSuccessfulRegistrationAt = null,
                        consecutiveRegistrationFailures = 5, // == STARTUP_FAILURE_THRESHOLD
                        consecutiveFailures = 5,
                    ),
            )
        val now = Instant.parse("2026-05-23T12:00:30Z") // 30s < grace=2m, but threshold reached
        val health = task.computeHealth(now)
        assertEquals(Status.DOWN, health.status)
        assertTrue(health.details["reason"].toString().contains("startup failed"))
    }

    @Test
    fun `computeHealth branch 2 — startup failed by grace expiry returns DOWN`() {
        val task =
            buildTask(
                state =
                    WatchTaskState(
                        startupAt = Instant.parse("2026-05-23T12:00:00Z"),
                        lastSuccessfulRegistrationAt = null,
                        consecutiveRegistrationFailures = 2,
                        consecutiveFailures = 2,
                    ),
            )
        val now = Instant.parse("2026-05-23T12:03:00Z") // 3m > grace=2m
        val health = task.computeHealth(now)
        assertEquals(Status.DOWN, health.status)
        assertTrue(health.details["reason"].toString().contains("startup failed"))
    }

    @Test
    fun `computeHealth branch 3 — registering during startup grace returns OUT_OF_SERVICE`() {
        val task =
            buildTask(
                state =
                    WatchTaskState(
                        startupAt = Instant.parse("2026-05-23T12:00:00Z"),
                        lastSuccessfulRegistrationAt = null,
                        consecutiveRegistrationFailures = 2,
                        consecutiveFailures = 2,
                    ),
            )
        val now = Instant.parse("2026-05-23T12:00:30Z") // 30s < grace=2m
        val health = task.computeHealth(now)
        assertEquals(Status.OUT_OF_SERVICE, health.status)
        assertTrue(health.details["reason"].toString().contains("registering"))
    }

    @Test
    fun `computeHealth branch 3_5 — registry empty after successful start returns DOWN`() {
        val task =
            buildTask(
                state =
                    WatchTaskState(
                        startupAt = Instant.parse("2026-05-23T11:00:00Z"),
                        lastSuccessfulPollAt = Instant.parse("2026-05-23T11:59:00Z"),
                        lastSuccessfulRegistrationAt = Instant.parse("2026-05-23T11:00:01Z"),
                        consecutiveEventFailures = 0,
                        consecutiveFailures = 0,
                    ),
                registerDummyDir = false, // simulate post-collapse: root folder gone, all WatchKeys invalidated
            )
        val now = Instant.parse("2026-05-23T12:00:00Z")
        val health = task.computeHealth(now)
        assertEquals(Status.DOWN, health.status)
        assertTrue(health.details["reason"].toString().contains("registry empty"))
    }

    @Test
    fun `computeHealth branch 4 — event failures stale returns DOWN`() {
        val task =
            buildTask(
                state =
                    WatchTaskState(
                        startupAt = Instant.parse("2026-05-23T10:00:00Z"),
                        lastSuccessfulRegistrationAt = Instant.parse("2026-05-23T10:00:01Z"),
                        lastEventProcessedAt = Instant.parse("2026-05-23T11:00:00Z"),
                        consecutiveEventFailures = 10,
                        lastFailure = RuntimeException("PG XX000"),
                    ),
            )
        val now = Instant.parse("2026-05-23T12:05:00Z") // 1h05m > staleness=2m
        val health = task.computeHealth(now)
        assertEquals(Status.DOWN, health.status)
        assertTrue(health.details["reason"].toString().contains("stale"))
    }

    @Test
    fun `computeHealth branch 5 — event failures transient returns OUT_OF_SERVICE`() {
        val task =
            buildTask(
                state =
                    WatchTaskState(
                        startupAt = Instant.parse("2026-05-23T12:00:00Z"),
                        lastSuccessfulRegistrationAt = Instant.parse("2026-05-23T12:00:01Z"),
                        lastEventProcessedAt = Instant.parse("2026-05-23T12:00:30Z"),
                        consecutiveEventFailures = 2,
                        lastFailure = RuntimeException("transient"),
                    ),
            )
        val now = Instant.parse("2026-05-23T12:01:00Z") // 30s < staleness
        val health = task.computeHealth(now)
        assertEquals(Status.OUT_OF_SERVICE, health.status)
        assertTrue(health.details["reason"].toString().contains("transient"))
    }

    @Test
    fun `computeHealth branch 6 — general backoff returns OUT_OF_SERVICE`() {
        val task =
            buildTask(
                state =
                    WatchTaskState(
                        startupAt = Instant.parse("2026-05-23T12:00:00Z"),
                        lastSuccessfulPollAt = Instant.parse("2026-05-23T12:00:05Z"),
                        lastSuccessfulRegistrationAt = Instant.parse("2026-05-23T12:00:01Z"),
                        lastEventProcessedAt = Instant.parse("2026-05-23T12:00:05Z"),
                        consecutiveEventFailures = 0,
                        consecutiveFailures = 1,
                        lastFailure = ClosedWatchServiceException(),
                    ),
            )
        val now = Instant.parse("2026-05-23T12:00:10Z")
        val health = task.computeHealth(now)
        assertEquals(Status.OUT_OF_SERVICE, health.status)
        assertTrue(health.details["reason"].toString().contains("backoff"))
    }

    @Test
    fun `computeHealth branch 7 — idle camera returns UP with note`() {
        val task =
            buildTask(
                state =
                    WatchTaskState(
                        startupAt = Instant.parse("2026-05-23T11:00:00Z"),
                        lastSuccessfulRegistrationAt = Instant.parse("2026-05-23T11:00:01Z"),
                        lastEventProcessedAt = null,
                        consecutiveEventFailures = 0,
                        consecutiveFailures = 0,
                    ),
            )
        val now = Instant.parse("2026-05-23T12:00:00Z") // 1h > 2 × HEALTH_STALENESS=4m
        val health = task.computeHealth(now)
        assertEquals(Status.UP, health.status)
        assertTrue(health.details["reason"].toString().contains("idle"))
    }

    @Test
    fun `computeHealth branch 8 — healthy returns UP`() {
        val task =
            buildTask(
                state =
                    WatchTaskState(
                        startupAt = Instant.parse("2026-05-23T12:00:00Z"),
                        lastSuccessfulPollAt = Instant.parse("2026-05-23T12:00:05Z"),
                        lastSuccessfulRegistrationAt = Instant.parse("2026-05-23T12:00:01Z"),
                        lastEventProcessedAt = Instant.parse("2026-05-23T12:00:05Z"),
                        consecutiveEventFailures = 0,
                        consecutiveFailures = 0,
                    ),
            )
        val now = Instant.parse("2026-05-23T12:00:10Z")
        val health = task.computeHealth(now)
        assertEquals(Status.UP, health.status)
        assertEquals("healthy", health.details["reason"])
    }

    // ----- Lifecycle: stopAndJoin -----

    @Test
    fun `stopAndJoin cancels supervisorJob closes watchService and clears registeredDirs`() =
        runTest {
            val watchService = mockk<WatchService>(relaxed = true)
            val task =
                newTask(
                    watchServiceFactory = { watchService },
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            // Park inside the loop until cancellation. awaitCancellation suspends indefinitely without
            // adding scheduled tasks (unlike delay(Long.MAX_VALUE) which DOES add a scheduled task and
            // causes advanceUntilIdle to spin advancing huge virtual times forever).
            coEvery { loop.runIteration(any(), any(), any()) } coAnswers {
                awaitCancellation()
            }
            task.registeredDirs[Path.of("/tmp/dir1")] = mockk(relaxed = true)
            task.registeredDirs[Path.of("/tmp/dir2")] = mockk(relaxed = true)

            task.start()
            yield()
            assertNotNull(task.supervisorJob, "start() should set supervisorJob")
            assertTrue(task.supervisorJob!!.isActive, "supervisorJob should be active after start()")

            task.stopAndJoin()

            assertEquals(0, task.registeredDirs.size)
            assertTrue(task.supervisorJob!!.isCancelled)
            verify { watchService.close() }
        }

    // ----- Follow-ups (CRITICAL-4 / 5 / 8) -----

    @Test
    fun `registerAllDirs failure leaves watchService null and bookkeeps registration failure`() =
        runTest {
            val task = newTask(dispatcher = StandardTestDispatcher(testScheduler))
            every { loop.registerAllDirs(any(), any(), any()) } throws java.io.IOException("disk gone")
            // runIteration is never reached because ensureWatchService throws first. With
            // advanceTimeBy(1) only the launch (scheduled at t=0) runs:
            //   - ensureWatchService.registerAllDirs throws IOException
            //   - onRegistrationFailure → consecutiveRegistrationFailures=1, consecutiveFailures=1
            //   - outer catch (Exception) → onLoopFailure → consecutiveFailures=2
            //   - delay(5000) suspends at t=0; advanceTimeBy(1) stops at t=1 (delay not fired).
            // Then cancel() + advanceUntilIdle() interrupts the delay and the loop exits cleanly.
            // Result is deterministic — counts are exact, not lower bounds.

            val job = launch { task.runSupervised() }
            advanceTimeBy(1)
            job.cancel()
            advanceUntilIdle()
            job.join()

            val s = task.stateForTesting
            assertEquals(1, s.consecutiveRegistrationFailures)
            assertEquals(2, s.consecutiveFailures)
            assertTrue(s.lastFailure is java.io.IOException)
        }

    @Test
    fun `5 consecutive registerAllDirs failures push health to DOWN via branch 2`() =
        runTest {
            val task = newTask(dispatcher = StandardTestDispatcher(testScheduler))
            task.stateForTesting = task.stateForTesting.copy(startupAt = Instant.parse("2026-05-23T12:00:00Z"))
            // computeHealth's Branch 1 fires when supervisorJob is null/inactive. We launch our own
            // job (not via task.start()), so we plant a fresh active Job in task.supervisorJob so
            // computeHealth proceeds to Branch 2 (the branch under test).
            task.supervisorJob = Job()
            every { loop.registerAllDirs(any(), any(), any()) } throws java.io.IOException("permission denied")
            // runIteration is unreachable — ensureWatchService throws every time. Each failure cycle:
            // onRegistrationFailure(++) → catch → onLoopFailure(++) → delay (5s, 10s, 20s, 40s, 60s, 60s...).
            // After ~200s of virtual time we'll have well over 5 failure cycles.

            val job = launch { task.runSupervised() }
            advanceTimeBy(300_000) // 5 minutes of virtual time — plenty for 5+ failure cycles
            job.cancel()
            advanceUntilIdle()
            job.join()

            // >= 5 (not == 5): supervisor may complete additional retries inside the 300s virtual
            // budget before the cancel-via-test fires. Backoff progression caps at 60s so cycles
            // accelerate; the upper bound is open. The minimum-5 guarantee is what gates branch 2.
            val s = task.stateForTesting
            assertTrue(
                s.consecutiveRegistrationFailures >= 5,
                "Got ${s.consecutiveRegistrationFailures}",
            )
            val health = task.computeHealth(Instant.parse("2026-05-23T12:00:30Z")) // 30s, within grace
            assertEquals(Status.DOWN, health.status)
            assertTrue(
                health.details["reason"].toString().contains("startup failed"),
                "Expected 'startup failed' reason, got: ${health.details["reason"]}",
            )
        }

    // ----- maybeResetBackoffTransform pure-helper unit tests (per Step 4.5b) -----
    // The helper is a pure function — no clock, no state mutation — so we call it directly
    // on a fresh task and assert the returned BackoffResetResult.

    @Test
    fun `maybeResetBackoffTransform — no-op when currentBackoff already at INITIAL`() {
        val task = buildTask()
        val input = WatchTaskState(currentBackoff = INITIAL_BACKOFF, successesSinceLastFailure = 99)
        val result = task.maybeResetBackoffTransform(input)
        assertEquals(BackoffResetResult(state = input), result)
    }

    @Test
    fun `maybeResetBackoffTransform — increments successes below threshold`() {
        val task = buildTask()
        val input =
            WatchTaskState(
                currentBackoff = Duration.ofSeconds(10),
                successesSinceLastFailure = SUCCESSES_TO_RESET_BACKOFF - 2,
            )
        val result = task.maybeResetBackoffTransform(input)
        assertEquals(
            BackoffResetResult(state = input.copy(successesSinceLastFailure = SUCCESSES_TO_RESET_BACKOFF - 1)),
            result,
        )
    }

    @Test
    fun `maybeResetBackoffTransform — resets backoff at threshold`() {
        val task = buildTask()
        val input =
            WatchTaskState(
                currentBackoff = Duration.ofSeconds(10),
                successesSinceLastFailure = SUCCESSES_TO_RESET_BACKOFF - 1,
            )
        val result = task.maybeResetBackoffTransform(input)
        assertEquals(
            BackoffResetResult(
                state = input.copy(currentBackoff = INITIAL_BACKOFF, successesSinceLastFailure = 0),
                didReset = true,
                nextSuccessesAtReset = SUCCESSES_TO_RESET_BACKOFF,
            ),
            result,
        )
    }

    // ----- Deterministic transition tests (per SUGGESTION-2) -----
    // Each test asserts the FULL post-transition snapshot in a single assertEquals — this catches
    // any accidental field modification on top of the expected ones. `clock` is fixed at
    // `2026-05-23T12:00:00Z`, so `Instant.now(clock) == fixedNow` inside each transition.

    private val fixedNow: Instant = Instant.parse("2026-05-23T12:00:00Z")

    @Test
    fun `onPollCompleted — events with no failures below threshold bumps successesSinceLastFailure`() {
        val task =
            buildTask(
                state =
                    WatchTaskState(
                        startupAt = fixedNow.minus(Duration.ofHours(1)),
                        lastSuccessfulRegistrationAt = fixedNow.minus(Duration.ofMinutes(10)),
                        consecutiveEventFailures = 3,
                        consecutiveFailures = 3,
                        successesSinceLastFailure = 1,
                        currentBackoff = Duration.ofSeconds(10),
                    ),
            )
        val before = task.stateForTesting
        task.onPollCompleted(eventsProcessed = 5, eventFailures = 0)
        assertEquals(
            before.copy(
                lastSuccessfulPollAt = fixedNow,
                lastEventProcessedAt = fixedNow,
                consecutiveEventFailures = 0,
                consecutiveFailures = 0,
                successesSinceLastFailure = 2, // bumped by maybeResetBackoffTransform; below threshold
                // currentBackoff unchanged (threshold not reached) — still 10s.
            ),
            task.stateForTesting,
        )
    }

    @Test
    fun `onPollCompleted — events with no failures at threshold resets backoff`() {
        val task =
            buildTask(
                state =
                    WatchTaskState(
                        startupAt = fixedNow.minus(Duration.ofHours(1)),
                        lastSuccessfulRegistrationAt = fixedNow.minus(Duration.ofMinutes(10)),
                        consecutiveEventFailures = 0,
                        consecutiveFailures = 0,
                        successesSinceLastFailure = SUCCESSES_TO_RESET_BACKOFF - 1,
                        currentBackoff = Duration.ofSeconds(20),
                    ),
            )
        val before = task.stateForTesting
        task.onPollCompleted(eventsProcessed = 5, eventFailures = 0)
        assertEquals(
            before.copy(
                lastSuccessfulPollAt = fixedNow,
                lastEventProcessedAt = fixedNow,
                // backoff reset; successesSinceLastFailure cleared.
                currentBackoff = INITIAL_BACKOFF,
                successesSinceLastFailure = 0,
            ),
            task.stateForTesting,
        )
    }

    @Test
    fun `onPollCompleted — empty poll only updates lastSuccessfulPollAt and clears consecutiveFailures`() {
        val task =
            buildTask(
                state =
                    WatchTaskState(
                        startupAt = fixedNow.minus(Duration.ofHours(1)),
                        lastSuccessfulRegistrationAt = fixedNow.minus(Duration.ofMinutes(10)),
                        lastEventProcessedAt = fixedNow.minus(Duration.ofMinutes(1)),
                        consecutiveEventFailures = 2, // not cleared by empty poll
                        consecutiveFailures = 1, // cleared by empty poll (per existing semantics)
                        successesSinceLastFailure = 0,
                        currentBackoff = Duration.ofSeconds(10),
                    ),
            )
        val before = task.stateForTesting
        task.onPollCompleted(eventsProcessed = 0, eventFailures = 0)
        assertEquals(
            before.copy(
                lastSuccessfulPollAt = fixedNow,
                consecutiveFailures = 0,
                // consecutiveEventFailures, successesSinceLastFailure, currentBackoff unchanged.
            ),
            task.stateForTesting,
        )
    }

    @Test
    fun `onPollCompleted — mixed (some events processed plus some failed) bumps event failures`() {
        val task =
            buildTask(
                state =
                    WatchTaskState(
                        startupAt = fixedNow.minus(Duration.ofHours(1)),
                        lastSuccessfulRegistrationAt = fixedNow.minus(Duration.ofMinutes(10)),
                        consecutiveEventFailures = 1,
                        consecutiveFailures = 0,
                        successesSinceLastFailure = 0,
                        currentBackoff = Duration.ofSeconds(10),
                    ),
            )
        val before = task.stateForTesting
        task.onPollCompleted(eventsProcessed = 5, eventFailures = 2)
        // Mixed iteration:
        //   1. events>0 branch: lastEventProcessedAt=now, consecutiveEventFailures=0, consecutiveFailures=0.
        //      maybeResetBackoffTransform increments successesSinceLastFailure 0 → 1.
        //   2. failures>0 branch: consecutiveEventFailures += 2 → 2; consecutiveFailures += 1 → 1;
        //      successesSinceLastFailure = 0 (zeroed — the "wasted" increment, per the NOTE in
        //      onPollCompleted production code).
        assertEquals(
            before.copy(
                lastSuccessfulPollAt = fixedNow,
                lastEventProcessedAt = fixedNow,
                consecutiveEventFailures = 2,
                consecutiveFailures = 1,
                successesSinceLastFailure = 0,
            ),
            task.stateForTesting,
        )
    }

    @Test
    fun `onPollCompleted — pure event failures with no successes`() {
        val task =
            buildTask(
                state =
                    WatchTaskState(
                        startupAt = fixedNow.minus(Duration.ofHours(1)),
                        lastSuccessfulRegistrationAt = fixedNow.minus(Duration.ofMinutes(10)),
                        lastEventProcessedAt = fixedNow.minus(Duration.ofMinutes(5)),
                        consecutiveEventFailures = 1,
                        consecutiveFailures = 0,
                        successesSinceLastFailure = 3,
                        currentBackoff = Duration.ofSeconds(10),
                    ),
            )
        val before = task.stateForTesting
        task.onPollCompleted(eventsProcessed = 0, eventFailures = 2)
        assertEquals(
            before.copy(
                lastSuccessfulPollAt = fixedNow,
                consecutiveEventFailures = 3, // 1 + 2
                consecutiveFailures = 1, // 0 + 1
                successesSinceLastFailure = 0,
            ),
            task.stateForTesting,
        )
    }

    @Test
    fun `onRegistrationSuccess — bumps lastSuccessfulRegistrationAt and zeros consecutiveRegistrationFailures`() {
        val task =
            buildTask(
                state =
                    WatchTaskState(
                        startupAt = fixedNow.minus(Duration.ofMinutes(1)),
                        consecutiveRegistrationFailures = 3,
                        consecutiveFailures = 3, // intentionally preserved (not cleared by registration success)
                        lastFailure = java.io.IOException("registration failure"),
                        lastFailureAt = fixedNow.minus(Duration.ofSeconds(30)),
                    ),
            )
        val before = task.stateForTesting
        task.onRegistrationSuccess()
        assertEquals(
            before.copy(
                lastSuccessfulRegistrationAt = fixedNow,
                consecutiveRegistrationFailures = 0,
                // consecutiveFailures, lastFailure, lastFailureAt preserved (sticky, per CRITICAL-4).
            ),
            task.stateForTesting,
        )
    }

    @Test
    fun `onRegistrationFailure — bumps registration and general counters, sets lastFailure`() {
        val task =
            buildTask(
                state =
                    WatchTaskState(
                        startupAt = fixedNow.minus(Duration.ofMinutes(1)),
                        consecutiveRegistrationFailures = 1,
                        consecutiveFailures = 1,
                        successesSinceLastFailure = 2,
                    ),
            )
        val before = task.stateForTesting
        val failure = java.io.IOException("permission denied")
        task.onRegistrationFailure(failure)
        assertEquals(
            before.copy(
                consecutiveRegistrationFailures = 2,
                consecutiveFailures = 2,
                successesSinceLastFailure = 0,
                lastFailure = failure,
                lastFailureAt = fixedNow,
            ),
            task.stateForTesting,
        )
    }

    @Test
    fun `onLoopFailure — bumps general counter and sets lastFailure, leaves registration counter alone`() {
        val task =
            buildTask(
                state =
                    WatchTaskState(
                        startupAt = fixedNow.minus(Duration.ofMinutes(1)),
                        consecutiveRegistrationFailures = 0,
                        consecutiveFailures = 2,
                        successesSinceLastFailure = 3,
                    ),
            )
        val before = task.stateForTesting
        val failure = RuntimeException("loop crashed")
        task.onLoopFailure(failure)
        assertEquals(
            before.copy(
                consecutiveFailures = 3,
                successesSinceLastFailure = 0,
                lastFailure = failure,
                lastFailureAt = fixedNow,
                // consecutiveRegistrationFailures preserved (loop failure is unrelated to registration).
            ),
            task.stateForTesting,
        )
    }
}
