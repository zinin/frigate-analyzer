package ru.zinin.frigate.analyzer.telegram.bot.supervisor

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.bot.getMe
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.health.contributor.Health
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.bot.FrigateAnalyzerBot
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

// Visibility is `internal` (not file-private) so tests in this module can reference the
// same value via import rather than maintaining a hand-mirrored duplicate.
internal val INITIAL_BACKOFF: Duration = Duration.ofSeconds(5)
private val MAX_BACKOFF: Duration = Duration.ofSeconds(60)
private val STABLE_THRESHOLD: Duration = Duration.ofSeconds(60)
private val HEALTH_STALENESS: Duration = Duration.ofMinutes(5)
private val STARTUP_GRACE: Duration = Duration.ofMinutes(2)
private const val STARTUP_FAILURE_THRESHOLD: Long = 5L
private val SHUTDOWN_JOIN_TIMEOUT: Duration = Duration.ofSeconds(30)

// Telegram bot token format is "<numeric-id>:<base64-like>"; the URL form is "bot<id>:<token>".
// Ktor exception messages typically embed the full URL — sanitize before surfacing in
// /actuator/health (defense-in-depth even when management.endpoint.health.show-details=never).
private val BOT_TOKEN_PATTERN = Regex("""bot\d+:[\w-]+""")

private fun sanitizeFailureMessage(message: String?): String =
    (message ?: "<no-message>")
        .replace(BOT_TOKEN_PATTERN, "bot[REDACTED]")
        .take(500)

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class TelegramBotSupervisor(
    private val runner: TelegramLongPollingRunner,
    private val bot: TelegramBot,
    private val frigateAnalyzerBot: FrigateAnalyzerBot,
    private val clock: Clock,
    private val dispatcher: CoroutineDispatcher =
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        Dispatchers.IO.limitedParallelism(1),
) {
    internal val scope =
        CoroutineScope(SupervisorJob() + dispatcher + CoroutineName("telegram-bot-supervisor"))
    // Production default is Dispatchers.IO.limitedParallelism(1) for parity with WatchRecordsTask.
    // Long-polling is I/O-bound and the supervisor is single-threaded by design. Constructor takes
    // `dispatcher` for testability (StandardTestDispatcher).

    @Volatile internal var supervisorJob: Job? = null

    internal data class SupervisorState(
        val startupAt: Instant? = null,
        val lastAttemptAt: Instant? = null,
        val lastPollingStartAt: Instant? = null,
        val lastStableAt: Instant? = null,
        val lastFailure: Throwable? = null,
        val lastFailureAt: Instant? = null,
        val consecutiveFailures: Long = 0,
        val currentBackoff: Duration = INITIAL_BACKOFF,
    )

    /**
     * Single source of truth for runtime metrics. ALL writes MUST go through
     * [AtomicReference.updateAndGet] / [AtomicReference.getAndUpdate] on [state]; direct
     * `state.set(...)` is reserved for test fixtures via [stateForTesting]. Reader code MUST
     * do exactly one `state.get()` at the top of any method that touches more than one field.
     * This guarantees that no reader observes a partial snapshot — e.g., a new
     * `consecutiveFailures` paired with the old `currentBackoff` — even under concurrent writers.
     */
    private val state = AtomicReference(SupervisorState())

    /**
     * Test-fixture access for the supervisor's runtime state. **DO NOT USE FROM
     * PRODUCTION CODE.** Direct `state.set(...)` bypasses the CAS discipline maintained
     * by [AtomicReference.updateAndGet] / [AtomicReference.getAndUpdate] — production writers
     * MUST go through one of those two APIs. Visibility is `internal` to confine misuse to
     * the test source set within this module; review discipline enforces the rule (no
     * runtime check).
     */
    internal var stateForTesting: SupervisorState
        get() = state.get()
        set(value) {
            state.set(value)
        }

    /**
     * Launches the supervised polling loop.
     *
     * Idempotent: a second call (`supervisorJob != null`) is logged and ignored. Restart
     * after [shutdown] is NOT supported: the underlying [scope] is `cancel()`-ed in shutdown
     * and re-launching on a cancelled scope would silently noop. Spring's `@PostConstruct`
     * lifecycle guarantees single-threaded invocation from a fresh bean — TOCTOU on the
     * `supervisorJob != null` check is moot for the Spring use case. Mirrors `WatchRecordsTask`.
     */
    @PostConstruct
    fun start() {
        if (supervisorJob != null) {
            logger.warn { "TelegramBotSupervisor.start() invoked twice; ignoring duplicate." }
            return
        }
        logger.info { "Starting Telegram bot supervisor..." }
        // Hoist clock read out of the CAS lambda; @PostConstruct is single-threaded for the Spring
        // use case but the pattern stays consistent with the rest of the file.
        val now = Instant.now(clock)
        state.updateAndGet { it.copy(startupAt = now) }
        supervisorJob = scope.launch { runSupervised() }
    }

    @PreDestroy
    fun shutdown() {
        logger.info { "Shutting down Telegram bot supervisor..." }
        supervisorJob?.cancel()
        // Bound the join — `delay()` is cooperatively cancellable, so the common path returns
        // in milliseconds. The 30s budget covers the bad case where ktgbotapi / Ktor has
        // non-cancellable IO in flight (HTTP read on the long-poll socket). Spring's default
        // 30s shutdown-phase would interrupt us otherwise. After timeout, scope.cancel()
        // forces termination.
        runBlocking {
            withTimeoutOrNull(SHUTDOWN_JOIN_TIMEOUT.toMillis()) {
                // runCatching swallows a CancellationException that may propagate from
                // join() — otherwise withTimeoutOrNull would see the cancel and treat it
                // as a timeout, causing a false "did not exit within Ns" log.
                runCatching { supervisorJob?.join() }
                true
            }
        }
        val cleanShutdown = supervisorJob?.isCompleted == true
        if (!cleanShutdown) {
            logger.warn {
                "Supervisor did not exit within ${SHUTDOWN_JOIN_TIMEOUT.toSeconds()}s; forcing"
            }
        }
        scope.cancel()
        logger.info { "Telegram bot supervisor stopped" }
    }

    internal suspend fun stopAndJoin() {
        // Test helper mirroring shutdown() shape. Does NOT null `supervisorJob` because shutdown()
        // doesn't either — both leave the cancelled Job reference in place for diagnostic
        // inspection. Tests that need to re-start the supervisor in the same scope must reset
        // supervisorJob manually before calling start().
        supervisorJob?.cancel()
        supervisorJob?.join()
        scope.cancel()
    }

    internal suspend fun runSupervised() {
        state.updateAndGet { it.copy(currentBackoff = INITIAL_BACKOFF) }
        while (currentCoroutineContext().isActive) {
            val attemptStart = Instant.now(clock)
            // Stamp lastAttemptAt and clear stale lastPollingStartAt atomically in one snapshot.
            state.updateAndGet { it.copy(lastAttemptAt = attemptStart, lastPollingStartAt = null) }
            try {
                bot.getMe()
                frigateAnalyzerBot.registerDefaultCommands()
                frigateAnalyzerBot.registerOwnerCommandsIfPossible()
                val pollStart = Instant.now(clock)
                state.updateAndGet { it.copy(lastPollingStartAt = pollStart) }
                logger.info { "Telegram bot polling started" }
                // Adapter returns Throwable? — null on clean exit, otherwise the cause from
                // structured-concurrency propagation.
                val cause = runner.run { frigateAnalyzerBot.registerRoutes(this) }
                // Clear lastPollingStartAt the instant runner.run exits. Otherwise the tail
                // delay(currentBackoff) window would let computeHealth read the stale "live
                // polling start" stamp and report UP for a poller that has already stopped.
                state.updateAndGet { it.copy(lastPollingStartAt = null) }
                if (cause != null) {
                    throw cause
                }
                val attemptDuration = Duration.between(attemptStart, Instant.now(clock))
                logger.warn {
                    "long-polling runner returned cleanly after $attemptDuration; reconnecting"
                }
                onAttemptEnded(success = true, attemptStart = attemptStart, failure = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // INTENTIONAL: keep a separate state.get() here so the log message reports the
                // pre-onAttemptEnded backoff value (matches pre-refactor log content). The tail
                // getAndUpdate below uses its own pre-bump snapshot for the actual delay; this
                // log read may diverge from the eventual delay if onAttemptEnded resets the
                // backoff to INITIAL on the "failure && duration >= STABLE_THRESHOLD" branch.
                // That's a pre-existing observable property — out of scope for this refactor.
                val preFailureBackoff = state.get().currentBackoff
                logger.error(e) {
                    "Telegram bot bootstrap/polling failed; " +
                        "next backoff=${preFailureBackoff.toMillis()}ms"
                }
                onAttemptEnded(success = false, attemptStart = attemptStart, failure = e)
            }
            // CRITICAL-2 (per review iter-1 decision option A): single atomic RMW for the tail
            // bump. getAndUpdate returns the PRE-bump snapshot, whose currentBackoff drives the
            // upcoming delay; the bumped value persists for the next iteration. Replaces the
            // prior 3-op pattern (state.get() for delay + state.updateAndGet for bump + logger's
            // own state.get()).
            val effectiveBackoff =
                state
                    .getAndUpdate { it.copy(currentBackoff = nextBackoff(it.currentBackoff)) }
                    .currentBackoff
            delay(effectiveBackoff.toMillis())
        }
    }

    internal fun onAttemptEnded(
        success: Boolean,
        attemptStart: Instant,
        failure: Throwable?,
    ) {
        val now = Instant.now(clock)
        val duration = Duration.between(attemptStart, now)
        if (success && duration >= STABLE_THRESHOLD) {
            // Clean polling exit AFTER a stable run — counts as success. Sticky semantics:
            // lastFailure / lastFailureAt are NOT cleared here (preserves the most-recent
            // failure for diagnostics across recovery cycles — see review iter-1 CRITICAL-4).
            state.updateAndGet {
                it.copy(
                    consecutiveFailures = 0,
                    currentBackoff = INITIAL_BACKOFF,
                    lastStableAt = now,
                )
            }
        } else if (success) {
            // Clean return faster than STABLE_THRESHOLD — library likely swallowed an error
            // (revoked token, etc.). Treat as fast-fail so consecutiveFailures grows and health
            // eventually crosses HEALTH_STALENESS into DOWN.
            state.updateAndGet {
                it.copy(
                    lastFailure = SilentPollingFailure("clean return after $duration"),
                    lastFailureAt = now,
                    consecutiveFailures = it.consecutiveFailures + 1,
                )
            }
        } else {
            if (duration >= STABLE_THRESHOLD) {
                // Crash after a stable run — reset counters; lastStableAt = now (the moment of
                // crash) per original semantics so HEALTH_STALENESS is measured from "just now",
                // not from "1 hour ago".
                state.updateAndGet {
                    it.copy(
                        lastFailure = failure,
                        lastFailureAt = now,
                        consecutiveFailures = 1,
                        currentBackoff = INITIAL_BACKOFF,
                        lastStableAt = now,
                    )
                }
            } else {
                state.updateAndGet {
                    it.copy(
                        lastFailure = failure,
                        lastFailureAt = now,
                        consecutiveFailures = it.consecutiveFailures + 1,
                    )
                }
            }
        }
    }

    private fun nextBackoff(current: Duration): Duration = minOf(current.multipliedBy(2), MAX_BACKOFF)

    fun computeHealth(now: Instant): Health {
        val s = state.get()
        val builder = baseBuilder(s)

        // BRANCH 1: supervisor not active → DOWN
        val job = supervisorJob
        if (job == null || !job.isActive) {
            return builder.down().withDetail("reason", "supervisor not active").build()
        }

        // BRANCH 2: live stable polling → UP (checked FIRST after liveness so cold start AND
        // recovery-with-sticky-consecutiveFailures correctly report UP). Invariant
        // `lastPollingStartAt > lastFailureAt` guards against a stale stamp from a previously
        // crashed attempt (companion to the `lastPollingStartAt = null` writes at iteration
        // start and just after `runner.run` exits, both in `runSupervised`).
        val pollStart = s.lastPollingStartAt
        val failedAt = s.lastFailureAt
        if (pollStart != null &&
            (failedAt == null || pollStart.isAfter(failedAt)) &&
            Duration.between(pollStart, now) >= STABLE_THRESHOLD
        ) {
            return builder.up().withDetail("reason", "healthy").build()
        }

        val stable = s.lastStableAt
        val started = s.startupAt
        val sinceStartup = if (started != null) Duration.between(started, now) else Duration.ZERO

        // BRANCH 3: never stable, threshold OR grace expired → DOWN
        if (stable == null &&
            (s.consecutiveFailures >= STARTUP_FAILURE_THRESHOLD || sinceStartup > STARTUP_GRACE)
        ) {
            return builder
                .down()
                .withDetail(
                    "reason",
                    "startup failed: ${s.consecutiveFailures} attempts / $sinceStartup",
                ).build()
        }

        // BRANCH 4: never stable, still in grace → OUT_OF_SERVICE
        if (stable == null) {
            return builder
                .outOfService()
                .withDetail("reason", "connecting... attempts=${s.consecutiveFailures}")
                .build()
        }

        // BRANCH 5: in backoff with stale stable point → DOWN
        if (s.consecutiveFailures > 0 && Duration.between(stable, now) > HEALTH_STALENESS) {
            return builder
                .down()
                .withDetail(
                    "reason",
                    "stale: failing for ${Duration.between(stable, now)} since lastStable=$stable",
                ).build()
        }

        // BRANCH 6: transient backoff (recent stable) → OUT_OF_SERVICE
        if (s.consecutiveFailures > 0) {
            return builder
                .outOfService()
                .withDetail(
                    "reason",
                    "in backoff (failures=${s.consecutiveFailures}, backoff=${s.currentBackoff.toMillis()}ms)",
                ).build()
        }

        // BRANCH 7: just (re)connected, polling < STABLE_THRESHOLD → OUT_OF_SERVICE
        return builder
            .outOfService()
            .withDetail("reason", "connecting... (just (re)started, <STABLE_THRESHOLD)")
            .build()
    }

    private fun baseBuilder(s: SupervisorState): Health.Builder =
        Health
            .Builder()
            .withDetail("startupAt", s.startupAt?.toString() ?: "never")
            .withDetail("lastAttemptAt", s.lastAttemptAt?.toString() ?: "never")
            .withDetail("lastPollingStartAt", s.lastPollingStartAt?.toString() ?: "never")
            .withDetail("lastStableAt", s.lastStableAt?.toString() ?: "never")
            .withDetail("lastFailureAt", s.lastFailureAt?.toString() ?: "never")
            .withDetail("consecutiveFailures", s.consecutiveFailures)
            .withDetail("currentBackoffMs", s.currentBackoff.toMillis())
            .also { b ->
                s.lastFailure?.let {
                    b.withDetail(
                        "lastFailure",
                        "${it.javaClass.simpleName}: ${sanitizeFailureMessage(it.message)}",
                    )
                }
            }
}

// Marker exception written into `lastFailure` when the long-polling runner returns cleanly
// faster than STABLE_THRESHOLD. Distinguishable in health-details under
// `lastFailure: "SilentPollingFailure: clean return after PT30S"`, so an operator can tell a
// silent failure (revoked token, library swallowed error) from a real crash.
private class SilentPollingFailure(
    message: String,
) : RuntimeException(message)
