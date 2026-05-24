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

private val logger = KotlinLogging.logger {}

// iter-2 CONCERN-3: ApplicationReadyEvent imports; removed unused PostConstruct.
// iter-2 CRITICAL-1: STARTUP_GRACE / STARTUP_FAILURE_THRESHOLD added for health-branches 2/3 (design §5.2).
private val INITIAL_BACKOFF: Duration = Duration.ofSeconds(5)
private val MAX_BACKOFF: Duration = Duration.ofSeconds(60)
private const val SUCCESSES_TO_RESET_BACKOFF: Int = 5
private val HEALTH_STALENESS: Duration = Duration.ofMinutes(2)
private val STARTUP_GRACE: Duration = Duration.ofMinutes(2)
private const val STARTUP_FAILURE_THRESHOLD: Long = 5L

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

    // iter-2 CRITICAL-1 (D2 Variant A): 10-field SupervisorState per design §4.1.
    // Poll heartbeat updates on any non-throwing poll; event-processing on events>0 only;
    // registration tracking enables startup-failure detection via STARTUP_GRACE / STARTUP_FAILURE_THRESHOLD.
    @Volatile internal var lastSuccessfulPollAt: Instant? = null

    @Volatile internal var lastEventProcessedAt: Instant? = null

    @Volatile internal var lastSuccessfulRegistrationAt: Instant? = null

    @Volatile internal var consecutiveEventFailures: Long = 0

    @Volatile internal var consecutiveRegistrationFailures: Long = 0

    @Volatile internal var consecutiveFailures: Long = 0

    @Volatile private var successesSinceLastFailure: Int = 0

    @Volatile internal var currentBackoff: Duration = INITIAL_BACKOFF

    @Volatile internal var lastFailure: Throwable? = null

    @Volatile internal var startupAt: Instant? = null

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
        startupAt = Instant.now(clock)
        supervisorJob = scope.launch { runSupervised() }
    }

    @PreDestroy
    fun shutdown() {
        logger.info { "Shutting down watch records..." }
        supervisorJob?.cancel()
        runBlocking { supervisorJob?.join() }
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
        currentBackoff = INITIAL_BACKOFF
        var lastCleanup = Instant.now(clock)
        while (currentCoroutineContext().isActive) {
            try {
                ensureWatchService()
                val result = loop.runIteration(watchService!!, registeredDirs, lastCleanup)
                lastCleanup = result.lastCleanupAt
                // iter-2 CRITICAL-1 (D2): onPollCompleted splits poll-heartbeat from event-processing.
                // Empty poll (events=0, failures=0) updates ONLY lastSuccessfulPollAt — counters untouched.
                onPollCompleted(result.eventsProcessed, result.eventFailures)
                if (result.eventFailures > 0) {
                    // Backoff progression on per-event failures — supervisor "pinged" even when
                    // ClosedWatchServiceException didn't fire but events keep failing.
                    delay(currentBackoff.toMillis())
                    currentBackoff = nextBackoff(currentBackoff)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClosedWatchServiceException) {
                logger.warn { "WatchService closed; will recreate next iteration" }
                closeWatchServiceQuietly()
                registeredDirs.clear()
                onLoopFailure(e)
                delay(currentBackoff.toMillis())
                currentBackoff = nextBackoff(currentBackoff)
            } catch (e: Exception) {
                // Registration / unexpected loop failures (per-event ones are caught inside runIteration).
                logger.error(e) { "WatchRecordsTask iteration failed; backing off for ${currentBackoff.toMillis()}ms" }
                // If ensureWatchService threw, it was a registration failure (see ensureWatchService body).
                // Otherwise — generic loop failure: increments both counters.
                onLoopFailure(e)
                delay(currentBackoff.toMillis())
                currentBackoff = nextBackoff(currentBackoff)
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

    private fun onPollCompleted(
        eventsProcessed: Int,
        eventFailures: Int,
    ) {
        lastSuccessfulPollAt = Instant.now(clock)
        if (eventsProcessed > 0) {
            lastEventProcessedAt = Instant.now(clock)
            consecutiveEventFailures = 0
            consecutiveFailures = 0 // reset; subsequent ++ below handles mixed (some-processed + some-failed) iterations
            maybeResetBackoff()
        }
        if (eventFailures > 0) {
            consecutiveEventFailures += eventFailures
            consecutiveFailures++
            successesSinceLastFailure = 0
        }
        // empty poll (events=0, failures=0): only lastSuccessfulPollAt updated — counters NOT touched
    }

    private fun onRegistrationSuccess() {
        lastSuccessfulRegistrationAt = Instant.now(clock)
        consecutiveRegistrationFailures = 0
        // Registration is not a "full iteration success"; consecutiveFailures resets only on processed > 0.
    }

    private fun onRegistrationFailure(t: Throwable) {
        consecutiveRegistrationFailures++
        consecutiveFailures++
        successesSinceLastFailure = 0
        lastFailure = t
    }

    // Used for ClosedWatchServiceException and generic loop failures (not registration / not per-event).
    private fun onLoopFailure(t: Throwable) {
        consecutiveFailures++
        successesSinceLastFailure = 0
        lastFailure = t
    }

    private fun maybeResetBackoff() {
        if (currentBackoff > INITIAL_BACKOFF) {
            successesSinceLastFailure++
            if (successesSinceLastFailure >= SUCCESSES_TO_RESET_BACKOFF) {
                logger.info { "Backoff reset after $successesSinceLastFailure consecutive successes" }
                currentBackoff = INITIAL_BACKOFF
                successesSinceLastFailure = 0
            }
        }
    }

    private fun nextBackoff(current: Duration): Duration = minOf(current.multipliedBy(2), MAX_BACKOFF)

    // iter-2 CRITICAL-1 (D2): 8-branch health table per design §5.2 (priority-ordered, first-match wins).
    fun computeHealth(now: Instant): Health {
        val builder =
            Health
                .Builder()
                .withDetail("lastSuccessfulPollAt", lastSuccessfulPollAt?.toString() ?: "never")
                .withDetail("lastEventProcessedAt", lastEventProcessedAt?.toString() ?: "never")
                .withDetail("lastSuccessfulRegistrationAt", lastSuccessfulRegistrationAt?.toString() ?: "never")
                .withDetail("consecutiveEventFailures", consecutiveEventFailures)
                .withDetail("consecutiveRegistrationFailures", consecutiveRegistrationFailures)
                .withDetail("consecutiveFailures", consecutiveFailures)
                .withDetail("currentBackoffMs", currentBackoff.toMillis())
                .withDetail("registeredDirs", registeredDirs.size)
        lastFailure?.let {
            builder.withDetail(
                "lastFailure",
                "${it.javaClass.simpleName}: ${it.message?.take(500) ?: "<no-message>"}",
            )
        }

        // Health branches are priority-ordered, first match wins (see design §5.2).

        // BRANCH 1: supervisor not active
        val job = supervisorJob
        if (job == null || !job.isActive) {
            return builder.down().withDetail("reason", "supervisor not active").build()
        }

        val regAt = lastSuccessfulRegistrationAt
        val started = startupAt
        val sinceStartup = if (started != null) Duration.between(started, now) else Duration.ZERO

        // BRANCH 2: startup failure — never registered, threshold reached OR grace expired
        if (regAt == null &&
            (consecutiveRegistrationFailures >= STARTUP_FAILURE_THRESHOLD || sinceStartup > STARTUP_GRACE)
        ) {
            return builder
                .down()
                .withDetail(
                    "reason",
                    "startup failed: no successful registration after " +
                        "$consecutiveRegistrationFailures attempts / $sinceStartup",
                ).build()
        }

        // BRANCH 3: never registered yet, but grace not expired — starting up
        if (regAt == null) {
            return builder
                .outOfService()
                .withDetail("reason", "registering... attempts=$consecutiveRegistrationFailures")
                .build()
        }

        val lastEvent = lastEventProcessedAt

        // BRANCH 4: event-failures + stale processed event → DOWN
        // Guard: lastEvent != null because staleness is measured against the last successful event;
        // if no events have ever been processed (lastEvent == null), fall through to BRANCH 5 (transient).
        if (consecutiveEventFailures > 0 &&
            lastEvent != null &&
            Duration.between(lastEvent, now) > HEALTH_STALENESS
        ) {
            return builder
                .down()
                .withDetail(
                    "reason",
                    "stale: events failing for ${Duration.between(lastEvent, now)} " +
                        "(last processed at $lastEvent)",
                ).build()
        }

        // BRANCH 5: event-failures, not stale → transient OUT_OF_SERVICE
        if (consecutiveEventFailures > 0) {
            return builder
                .outOfService()
                .withDetail(
                    "reason",
                    "transient event-processing failures ($consecutiveEventFailures consecutive)",
                ).build()
        }

        // BRANCH 6: general backoff (e.g., ClosedWatchServiceException recovery cycle)
        if (consecutiveFailures > 0) {
            return builder
                .outOfService()
                .withDetail(
                    "reason",
                    "in backoff after $consecutiveFailures consecutive iteration failures",
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
}
