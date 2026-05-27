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

private val logger = KotlinLogging.logger {}

private val INITIAL_BACKOFF: Duration = Duration.ofSeconds(5)
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

    @Volatile internal var startupAt: Instant? = null

    @Volatile internal var lastAttemptAt: Instant? = null

    @Volatile internal var lastPollingStartAt: Instant? = null

    @Volatile internal var lastStableAt: Instant? = null

    @Volatile internal var lastFailure: Throwable? = null

    @Volatile internal var lastFailureAt: Instant? = null

    @Volatile internal var consecutiveFailures: Long = 0

    @Volatile internal var currentBackoff: Duration = INITIAL_BACKOFF

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
        startupAt = Instant.now(clock)
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
        currentBackoff = INITIAL_BACKOFF
        while (currentCoroutineContext().isActive) {
            val attemptStart = Instant.now(clock)
            lastAttemptAt = attemptStart
            // Clear stale stamp so health branch 2 won't match on a previous (failed) attempt's
            // polling start.
            lastPollingStartAt = null
            try {
                bot.getMe()
                frigateAnalyzerBot.registerDefaultCommands()
                frigateAnalyzerBot.registerOwnerCommandsIfPossible()
                lastPollingStartAt = Instant.now(clock)
                logger.info { "Telegram bot polling started" }
                // Adapter returns Throwable? — null on clean exit, otherwise the cause from
                // structured-concurrency propagation.
                val cause = runner.run { frigateAnalyzerBot.registerRoutes(this) }
                // Clear lastPollingStartAt the instant runner.run exits. Otherwise the tail
                // delay(currentBackoff) window would let computeHealth read the stale "live
                // polling start" stamp and report UP for a poller that has already stopped.
                lastPollingStartAt = null
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
                logger.error(e) {
                    "Telegram bot bootstrap/polling failed; " +
                        "next backoff=${currentBackoff.toMillis()}ms"
                }
                onAttemptEnded(success = false, attemptStart = attemptStart, failure = e)
            }
            // Delay FIRST, then bump for next iteration. Preserves the documented
            // 5s→10s→20s→40s→60s progression: very first failure waits INITIAL_BACKOFF (5s);
            // after a stable-fail reset (onAttemptEnded sets currentBackoff=INITIAL) the next
            // delay is also INITIAL.
            delay(currentBackoff.toMillis())
            currentBackoff = nextBackoff(currentBackoff)
        }
    }

    private fun onAttemptEnded(
        success: Boolean,
        attemptStart: Instant,
        failure: Throwable?,
    ) {
        val duration = Duration.between(attemptStart, Instant.now(clock))
        if (success && duration >= STABLE_THRESHOLD) {
            // Clean polling exit AFTER a stable run — counts as success.
            consecutiveFailures = 0
            currentBackoff = INITIAL_BACKOFF
            lastStableAt = Instant.now(clock)
        } else if (success) {
            // Clean return faster than STABLE_THRESHOLD — library likely swallowed an error
            // (revoked token, etc.). Treat as fast-fail so consecutiveFailures grows and health
            // eventually crosses HEALTH_STALENESS into DOWN.
            lastFailure = SilentPollingFailure("clean return after $duration")
            lastFailureAt = Instant.now(clock)
            consecutiveFailures++
        } else {
            lastFailure = failure
            lastFailureAt = Instant.now(clock)
            if (duration >= STABLE_THRESHOLD) {
                // Worked long enough to count as a stable run.
                // lastStableAt = now (the moment of crash), not attemptStart: an attempt that
                // ran for an hour should leave a fresh stable timestamp so HEALTH_STALENESS
                // is measured from "just now", not from "1 hour ago".
                consecutiveFailures = 1
                currentBackoff = INITIAL_BACKOFF
                lastStableAt = Instant.now(clock)
            } else {
                consecutiveFailures++
            }
        }
    }

    private fun nextBackoff(current: Duration): Duration = minOf(current.multipliedBy(2), MAX_BACKOFF)

    fun computeHealth(now: Instant): Health {
        val builder = baseBuilder()

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
        val pollStart = lastPollingStartAt
        val failedAt = lastFailureAt
        if (pollStart != null &&
            (failedAt == null || pollStart.isAfter(failedAt)) &&
            Duration.between(pollStart, now) >= STABLE_THRESHOLD
        ) {
            return builder.up().withDetail("reason", "healthy").build()
        }

        val stable = lastStableAt
        val started = startupAt
        val sinceStartup = if (started != null) Duration.between(started, now) else Duration.ZERO

        // BRANCH 3: never stable, threshold OR grace expired → DOWN
        if (stable == null &&
            (consecutiveFailures >= STARTUP_FAILURE_THRESHOLD || sinceStartup > STARTUP_GRACE)
        ) {
            return builder
                .down()
                .withDetail(
                    "reason",
                    "startup failed: $consecutiveFailures attempts / $sinceStartup",
                ).build()
        }

        // BRANCH 4: never stable, still in grace → OUT_OF_SERVICE
        if (stable == null) {
            return builder
                .outOfService()
                .withDetail("reason", "connecting... attempts=$consecutiveFailures")
                .build()
        }

        // BRANCH 5: in backoff with stale stable point → DOWN
        if (consecutiveFailures > 0 && Duration.between(stable, now) > HEALTH_STALENESS) {
            return builder
                .down()
                .withDetail(
                    "reason",
                    "stale: failing for ${Duration.between(stable, now)} since lastStable=$stable",
                ).build()
        }

        // BRANCH 6: transient backoff (recent stable) → OUT_OF_SERVICE
        if (consecutiveFailures > 0) {
            return builder
                .outOfService()
                .withDetail(
                    "reason",
                    "in backoff (failures=$consecutiveFailures, backoff=${currentBackoff.toMillis()}ms)",
                ).build()
        }

        // BRANCH 7: just (re)connected, polling < STABLE_THRESHOLD → OUT_OF_SERVICE
        return builder
            .outOfService()
            .withDetail("reason", "connecting... (just (re)started, <STABLE_THRESHOLD)")
            .build()
    }

    private fun baseBuilder(): Health.Builder =
        Health
            .Builder()
            .withDetail("startupAt", startupAt?.toString() ?: "never")
            .withDetail("lastAttemptAt", lastAttemptAt?.toString() ?: "never")
            .withDetail("lastPollingStartAt", lastPollingStartAt?.toString() ?: "never")
            .withDetail("lastStableAt", lastStableAt?.toString() ?: "never")
            .withDetail("lastFailureAt", lastFailureAt?.toString() ?: "never")
            .withDetail("consecutiveFailures", consecutiveFailures)
            .withDetail("currentBackoffMs", currentBackoff.toMillis())
            .also { b ->
                lastFailure?.let {
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
