package ru.zinin.frigate.analyzer.core.task

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.health.contributor.Health
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.config.properties.RecordsWatcherProperties
import ru.zinin.frigate.analyzer.core.helper.SpringProfileHelper
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

// iter-2 CONCERN-3: ApplicationReadyEvent imports; removed unused PostConstruct.
// iter-2 CRITICAL-1: STARTUP_GRACE / STARTUP_FAILURE_THRESHOLD added for health-branches 2/3 (design §5.2).
// Visibility is `internal` (not file-private) so tests in this module can reference the
// same value via import rather than maintaining a hand-mirrored duplicate.
internal val INITIAL_BACKOFF: Duration = Duration.ofSeconds(5)
private val MAX_BACKOFF: Duration = Duration.ofSeconds(60)

// Visibility is `internal` (not file-private) so tests in this module can reference the
// same value via import rather than maintaining a hand-mirrored duplicate.
internal const val SUCCESSES_TO_RESET_BACKOFF: Int = 5
private val HEALTH_STALENESS: Duration = Duration.ofMinutes(2)
private val STARTUP_GRACE: Duration = Duration.ofMinutes(2)
private const val STARTUP_FAILURE_THRESHOLD: Long = 5L
private val SHUTDOWN_JOIN_TIMEOUT: Duration = Duration.ofSeconds(30)

@Component
class WatchRecordsTask(
    private val recordsWatcherProperties: RecordsWatcherProperties,
    private val loop: WatchRecordsLoop,
    private val clock: Clock,
    private val springProfileHelper: SpringProfileHelper,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    private val watchServiceFactory: () -> WatchService = { FileSystems.getDefault().newWatchService() },
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineName("watch-records"))

    @Volatile internal var supervisorJob: Job? = null

    private var watchService: WatchService? = null
    internal val registeredDirs: ConcurrentHashMap<Path, WatchKey> = ConcurrentHashMap()

    // iter-2 CRITICAL-1 (D2 Variant A): 11-field WatchTaskState per design §4.2.
    // Poll heartbeat updates on any non-throwing poll; event-processing on events>0 only;
    // registration tracking enables startup-failure detection via STARTUP_GRACE / STARTUP_FAILURE_THRESHOLD.
    internal data class WatchTaskState(
        val startupAt: Instant? = null,
        val lastSuccessfulPollAt: Instant? = null,
        val lastEventProcessedAt: Instant? = null,
        val lastSuccessfulRegistrationAt: Instant? = null,
        val consecutiveEventFailures: Long = 0,
        val consecutiveRegistrationFailures: Long = 0,
        val consecutiveFailures: Long = 0,
        val successesSinceLastFailure: Int = 0,
        val currentBackoff: Duration = INITIAL_BACKOFF,
        val lastFailure: Throwable? = null,
        val lastFailureAt: Instant? = null,
    )

    /**
     * Single source of truth for runtime metrics. ALL writes MUST go through
     * [AtomicReference.updateAndGet] / [AtomicReference.getAndUpdate] on [state]; direct
     * `state.set(...)` is reserved for test fixtures via [stateForTesting]. Reader code MUST
     * do exactly one `state.get()` at the top of any method that touches more than one field.
     * This guarantees that no reader observes a partial snapshot — e.g., a new
     * `consecutiveFailures` paired with the old `currentBackoff` — even under concurrent writers.
     */
    private val state = AtomicReference(WatchTaskState())

    /**
     * Test-fixture access for the task's runtime state. **DO NOT USE FROM PRODUCTION
     * CODE.** Direct `state.set(...)` bypasses the CAS discipline maintained by
     * [AtomicReference.updateAndGet] / [AtomicReference.getAndUpdate] — production writers
     * MUST go through one of those two APIs. Visibility is `internal` to confine misuse to
     * the test source set within this module; review discipline enforces the rule (no
     * runtime check).
     */
    internal var stateForTesting: WatchTaskState
        get() = state.get()
        set(value) {
            state.set(value)
        }

    // iter-1 D6 + iter-2 CRITICAL-3 Variant B: @EventListener(ApplicationReadyEvent::class) — consistent with
    // FirstTimeScanTask lifecycle. Concurrent start with FirstTimeScanTask accepted; DB unique constraint
    // on recordings.file_path mitigates duplicate-processing (per-event catch counts R2dbcException in
    // eventFailures). See design §4.1 D6 note for full rationale.
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (springProfileHelper.isTestProfile()) {
            logger.info { "Test profile detected. Watch records task skipped." }
            return
        }
        if (supervisorJob != null) {
            logger.warn { "WatchRecordsTask.start() invoked twice; ignoring duplicate." }
            return
        }
        logger.info { "Starting watch records in folder: ${recordsWatcherProperties.folder}" }
        // Hoist clock read out of the CAS lambda; @EventListener is single-threaded for the
        // Spring use case but the pattern stays consistent with the rest of the file.
        val now = Instant.now(clock)
        state.updateAndGet { it.copy(startupAt = now) }
        supervisorJob = scope.launch { runSupervised() }
    }

    @PreDestroy
    fun shutdown() {
        logger.info { "Shutting down watch records..." }
        supervisorJob?.cancel()
        // Bound the join — worst case loop is parked in delay(MAX_BACKOFF=60s) and won't observe cancel
        // until that wakes; Spring's default shutdown timeout (30s) would interrupt us first otherwise.
        // After timeout, scope.cancel() + closeWatchServiceQuietly() force the loop out via CWS.
        val joined =
            runBlocking {
                withTimeoutOrNull(SHUTDOWN_JOIN_TIMEOUT.toMillis()) {
                    supervisorJob?.join()
                    true
                }
            }
        if (joined == null) {
            logger.warn { "Supervisor did not exit within ${SHUTDOWN_JOIN_TIMEOUT.toSeconds()}s; forcing cleanup" }
        }
        // INVARIANT (iter-2 CONCERN-7): scope.cancel() must follow supervisorJob.join().
        // Future scope children must join individually before this line — scope.cancel() does not wait.
        scope.cancel()
        registeredDirs.values.forEach { it.cancel() }
        registeredDirs.clear()
        closeWatchServiceQuietly()
        logger.info { "Watch records shut down." }
    }

    // iter-2 CONCERN-1: suspend helper for tests (production uses shutdown() through @PreDestroy + runBlocking).
    // runBlocking on StandardTestDispatcher deadlocks the test scheduler; stopAndJoin() calls the same shape
    // from a suspend context (runTest) without runBlocking.
    internal suspend fun stopAndJoin() {
        supervisorJob?.cancel()
        supervisorJob?.join()
        scope.cancel()
        registeredDirs.values.forEach { it.cancel() }
        registeredDirs.clear()
        closeWatchServiceQuietly()
    }

    internal suspend fun runSupervised() {
        state.updateAndGet { it.copy(currentBackoff = INITIAL_BACKOFF) }
        var lastCleanup = Instant.now(clock)
        while (currentCoroutineContext().isActive) {
            try {
                ensureWatchService()
                val result = loop.runIteration(watchService!!, registeredDirs, lastCleanup)
                lastCleanup = result.lastCleanupAt
                // iter-2 CRITICAL-1 (D2): onPollCompleted splits poll-heartbeat from event-processing.
                // Empty poll (events=0, failures=0) updates ONLY lastSuccessfulPollAt — counters untouched.
                onPollCompleted(result.eventsProcessed, result.eventFailures)
                if (result.eventFailures > 0 && result.eventsProcessed == 0) {
                    // Backoff progression only when an iteration produced ZERO successes — partial
                    // success (some events processed + some failed) means the supervisor and
                    // WatchService are alive; BRANCH 5 already surfaces event-failures as transient,
                    // no need to also throttle the loop.
                    // CRITICAL-2 (per review iter-1 decision option A): single atomic RMW for the
                    // tail bump. getAndUpdate returns the PRE-bump snapshot, whose currentBackoff
                    // drives the upcoming delay; the bumped value persists for the next iteration.
                    val effectiveBackoff =
                        state
                            .getAndUpdate { it.copy(currentBackoff = nextBackoff(it.currentBackoff)) }
                            .currentBackoff
                    delay(effectiveBackoff.toMillis())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClosedWatchServiceException) {
                logger.warn { "WatchService closed; will recreate next iteration" }
                closeWatchServiceQuietly()
                registeredDirs.clear()
                // Two-write window: onLoopFailure updates failure counters; getAndUpdate below
                // bumps currentBackoff. A health read between the two writes sees the new
                // consecutiveFailures but the pre-bump currentBackoff — only the
                // `currentBackoffMs` detail can lag by one bump; BRANCH 6 ("in backoff after N
                // consecutive iteration failures") is the verdict either way.
                onLoopFailure(e)
                val effectiveBackoff =
                    state
                        .getAndUpdate { it.copy(currentBackoff = nextBackoff(it.currentBackoff)) }
                        .currentBackoff
                delay(effectiveBackoff.toMillis())
            } catch (e: Exception) {
                // Registration / unexpected loop failures (per-event ones are caught inside runIteration).
                // If ensureWatchService threw, it was a registration failure (see ensureWatchService body).
                // Otherwise — generic loop failure: increments both counters.
                onLoopFailure(e)
                val effectiveBackoff =
                    state
                        .getAndUpdate { it.copy(currentBackoff = nextBackoff(it.currentBackoff)) }
                        .currentBackoff
                logger.error(e) {
                    "WatchRecordsTask iteration failed; backing off for ${effectiveBackoff.toMillis()}ms"
                }
                delay(effectiveBackoff.toMillis())
            }
        }
    }

    private fun ensureWatchService() {
        if (watchService == null) {
            val ws =
                try {
                    watchServiceFactory()
                } catch (e: Exception) {
                    onRegistrationFailure(e)
                    throw e
                }
            try {
                registeredDirs.clear() // guarantee clean state
                loop.registerAllDirs(recordsWatcherProperties.folder, ws, registeredDirs)
            } catch (e: Exception) {
                runCatching { ws.close() }
                registeredDirs.clear()
                onRegistrationFailure(e)
                throw e // supervisor catches → backoff → retry from scratch
            }
            watchService = ws
            onRegistrationSuccess()
            logger.info { "Watch service created; registered ${registeredDirs.size} directories." }
        }
    }

    private fun closeWatchServiceQuietly() {
        runCatching { watchService?.close() }
        watchService = null
    }

    // iter-2 CRITICAL-1 (D2): 3 transition methods — separation of event/registration/general failure tracking.

    /**
     * Result of [maybeResetBackoffTransform]. Bundles the new state with a side-effect-free
     * indication of whether a reset actually happened, so that the caller can log **outside**
     * `updateAndGet` (the transform itself must remain pure — `AtomicReference.updateAndGet`
     * may retry the lambda under CAS contention, and a side-effect-in-transform pattern
     * would produce duplicate log entries on retry).
     */
    internal data class BackoffResetResult(
        val state: WatchTaskState,
        val didReset: Boolean = false,
        /** Successes count at the moment of reset — used by the caller for the log message. */
        val nextSuccessesAtReset: Int = 0,
    )

    internal fun onPollCompleted(
        eventsProcessed: Int,
        eventFailures: Int,
    ) {
        val now = Instant.now(clock)
        // Capture-on-every-retry: AtomicReference.updateAndGet may invoke the lambda multiple
        // times under contention. The only side effects in the transform are these two local
        // vars, which are unconditionally re-initialized at the top of each invocation, so
        // their final value reflects the winning CAS run. The logger.info call is hoisted
        // outside the CAS-loop and fires exactly once per onPollCompleted invocation.
        var didResetBackoff = false
        var resetAfterSuccesses = 0
        state.updateAndGet { s ->
            didResetBackoff = false
            resetAfterSuccesses = 0
            var n = s.copy(lastSuccessfulPollAt = now)
            if (eventsProcessed > 0) {
                val applied = applyEventsProcessedTransform(n, now)
                n = applied.state
                didResetBackoff = applied.didReset
                resetAfterSuccesses = applied.nextSuccessesAtReset
                // NOTE: maybeResetBackoffTransform increments successesSinceLastFailure.
                // When the same poll also brought event-failures (eventFailures > 0, handled
                // below), that increment is immediately zeroed by the failure branch — a
                // "wasted" increment. The didResetBackoff flag, however, is NOT cleared by
                // the failure branch: an actual backoff reset (currentBackoff -> INITIAL)
                // remains observable in the final state, so logging it is correct. This
                // is the same observable behavior the pre-refactor code produced.
            }
            if (eventFailures > 0) {
                n =
                    n.copy(
                        consecutiveEventFailures = n.consecutiveEventFailures + eventFailures,
                        consecutiveFailures = n.consecutiveFailures + 1,
                        successesSinceLastFailure = 0,
                    )
            } else if (eventsProcessed == 0) {
                // Empty poll with no failures — supervisor reached a clean iteration end (e.g. CWS
                // recovery followed by an idle period in a quiet deployment). Clear the general-
                // failure signal so BRANCH 6 doesn't latch until the next real event.
                n = n.copy(consecutiveFailures = 0)
            }
            n
        }
        // Side-effect log: fires exactly once per onPollCompleted invocation, AFTER the
        // CAS-loop has settled. Idempotent under retry: didResetBackoff is only true if
        // the committed transform did the reset.
        if (didResetBackoff) {
            logger.info { "Backoff reset after $resetAfterSuccesses consecutive successes" }
        }
    }

    /**
     * Pure transform applied when `eventsProcessed > 0`. Updates `lastEventProcessedAt`,
     * zeros `consecutiveEventFailures` and `consecutiveFailures`, then delegates to
     * [maybeResetBackoffTransform] for backoff-reset bookkeeping. Propagates the result
     * up so the caller can observe whether the backoff was reset.
     */
    private fun applyEventsProcessedTransform(
        s: WatchTaskState,
        now: Instant,
    ): BackoffResetResult {
        val withSuccess =
            s.copy(
                lastEventProcessedAt = now,
                consecutiveEventFailures = 0,
                // reset; subsequent ++ below handles mixed (some-processed + some-failed) iterations
                consecutiveFailures = 0,
            )
        return maybeResetBackoffTransform(withSuccess)
    }

    internal fun onRegistrationSuccess() {
        val now = Instant.now(clock)
        state.updateAndGet {
            it.copy(
                lastSuccessfulRegistrationAt = now,
                consecutiveRegistrationFailures = 0,
                // Registration is not a "full iteration success"; consecutiveFailures resets only on processed > 0.
            )
        }
    }

    internal fun onRegistrationFailure(t: Throwable) {
        val now = Instant.now(clock)
        state.updateAndGet {
            it.copy(
                consecutiveRegistrationFailures = it.consecutiveRegistrationFailures + 1,
                consecutiveFailures = it.consecutiveFailures + 1,
                successesSinceLastFailure = 0,
                lastFailure = t,
                lastFailureAt = now,
            )
        }
    }

    // Used for ClosedWatchServiceException and generic loop failures (not registration / not per-event).
    internal fun onLoopFailure(t: Throwable) {
        val now = Instant.now(clock)
        state.updateAndGet {
            it.copy(
                consecutiveFailures = it.consecutiveFailures + 1,
                successesSinceLastFailure = 0,
                lastFailure = t,
                lastFailureAt = now,
            )
        }
    }

    /**
     * Pure transform: returns a new state with `successesSinceLastFailure` incremented and
     * `currentBackoff` reset to [INITIAL_BACKOFF] if the consecutive-success threshold is reached.
     * If `currentBackoff` is already [INITIAL_BACKOFF] (or smaller), returns input unchanged with
     * `didReset=false`.
     *
     * **No side-effects.** The caller (typically [onPollCompleted]) is responsible for
     * emitting the "Backoff reset" log line after the enclosing `updateAndGet` has settled.
     */
    internal fun maybeResetBackoffTransform(s: WatchTaskState): BackoffResetResult {
        if (s.currentBackoff <= INITIAL_BACKOFF) return BackoffResetResult(state = s)
        val nextSuccesses = s.successesSinceLastFailure + 1
        return if (nextSuccesses >= SUCCESSES_TO_RESET_BACKOFF) {
            BackoffResetResult(
                state = s.copy(currentBackoff = INITIAL_BACKOFF, successesSinceLastFailure = 0),
                didReset = true,
                nextSuccessesAtReset = nextSuccesses,
            )
        } else {
            BackoffResetResult(state = s.copy(successesSinceLastFailure = nextSuccesses))
        }
    }

    private fun nextBackoff(current: Duration): Duration = minOf(current.multipliedBy(2), MAX_BACKOFF)

    // iter-2 CRITICAL-1 (D2): 8-branch health table per design §5.2 (priority-ordered, first-match wins).
    fun computeHealth(now: Instant): Health {
        val s = state.get()
        val builder = baseBuilder(s)

        // Health branches are priority-ordered, first match wins (see design §5.2).

        // BRANCH 1: supervisor not active
        val job = supervisorJob
        if (job == null || !job.isActive) {
            return builder.down().withDetail("reason", "supervisor not active").build()
        }

        val regAt = s.lastSuccessfulRegistrationAt
        val started = s.startupAt
        val sinceStartup = if (started != null) Duration.between(started, now) else Duration.ZERO

        // BRANCH 2: startup failure — never registered, threshold reached OR grace expired
        if (regAt == null &&
            (s.consecutiveRegistrationFailures >= STARTUP_FAILURE_THRESHOLD || sinceStartup > STARTUP_GRACE)
        ) {
            return builder
                .down()
                .withDetail(
                    "reason",
                    "startup failed: no successful registration after " +
                        "${s.consecutiveRegistrationFailures} attempts / $sinceStartup",
                ).build()
        }

        // BRANCH 3: never registered yet, but grace not expired — starting up
        if (regAt == null) {
            return builder
                .outOfService()
                .withDetail("reason", "registering... attempts=${s.consecutiveRegistrationFailures}")
                .build()
        }

        // BRANCH 3.5: registry collapsed after a successful start (root folder deleted, NFS mount
        // dropped, all WatchKeys invalidated). The supervisor keeps polling an empty key set —
        // BRANCH 7 "idle" would mask the real problem. Surface as DOWN so operator restarts the
        // process / re-mounts the volume.
        if (registeredDirs.isEmpty()) {
            return builder
                .down()
                .withDetail("reason", "registry empty after successful start (root folder gone?)")
                .build()
        }

        val lastEvent = s.lastEventProcessedAt

        // BRANCH 4: event-failures + stale → DOWN.
        // Staleness reference falls back to lastSuccessfulRegistrationAt when no event ever
        // succeeded — catches "parser is completely broken from the first event" (e.g. Frigate
        // file-naming convention changed and createRecording rejects everything). Without the
        // fallback, we'd stay in BRANCH 5 (transient) forever for such regressions.
        val staleReference = lastEvent ?: regAt
        if (s.consecutiveEventFailures > 0 &&
            Duration.between(staleReference, now) > HEALTH_STALENESS
        ) {
            val anchor = if (lastEvent != null) "last processed at $lastEvent" else "no event ever processed (registered at $regAt)"
            return builder
                .down()
                .withDetail(
                    "reason",
                    "stale: events failing for ${Duration.between(staleReference, now)} ($anchor)",
                ).build()
        }

        // BRANCH 5: event-failures, not stale → transient OUT_OF_SERVICE
        if (s.consecutiveEventFailures > 0) {
            return builder
                .outOfService()
                .withDetail(
                    "reason",
                    "transient event-processing failures (${s.consecutiveEventFailures} consecutive)",
                ).build()
        }

        // BRANCH 6: general backoff (e.g., ClosedWatchServiceException recovery cycle)
        if (s.consecutiveFailures > 0) {
            return builder
                .outOfService()
                .withDetail(
                    "reason",
                    "in backoff after ${s.consecutiveFailures} consecutive iteration failures",
                ).build()
        }

        // BRANCH 7: idle (camera off / no events) — UP with note
        if (lastEvent == null && sinceStartup > HEALTH_STALENESS.multipliedBy(2)) {
            return builder
                .up()
                .withDetail("reason", "idle: registered but no events yet (camera offline?)")
                .build()
        }

        // BRANCH 8: all good
        return builder.up().withDetail("reason", "healthy").build()
    }

    private fun baseBuilder(s: WatchTaskState): Health.Builder {
        val builder =
            Health
                .Builder()
                .withDetail("lastSuccessfulPollAt", s.lastSuccessfulPollAt?.toString() ?: "never")
                .withDetail("lastEventProcessedAt", s.lastEventProcessedAt?.toString() ?: "never")
                .withDetail("lastSuccessfulRegistrationAt", s.lastSuccessfulRegistrationAt?.toString() ?: "never")
                .withDetail("consecutiveEventFailures", s.consecutiveEventFailures)
                .withDetail("consecutiveRegistrationFailures", s.consecutiveRegistrationFailures)
                .withDetail("consecutiveFailures", s.consecutiveFailures)
                .withDetail("currentBackoffMs", s.currentBackoff.toMillis())
                .withDetail("registeredDirs", registeredDirs.size)
        s.lastFailure?.let {
            builder.withDetail(
                "lastFailure",
                "${it.javaClass.simpleName}: ${it.message?.take(500) ?: "<no-message>"}",
            )
        }
        s.lastFailureAt?.let { builder.withDetail("lastFailureAt", it.toString()) }
        return builder
    }
}
