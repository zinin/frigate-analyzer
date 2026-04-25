package ru.zinin.frigate.analyzer.core.task

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CancellationException
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.DependsOn
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.config.properties.SignalLossProperties
import ru.zinin.frigate.analyzer.service.repository.RecordingEntityRepository
import ru.zinin.frigate.analyzer.telegram.service.TelegramNotificationService
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Polls the database every `application.signal-loss.poll-interval` for the most recent recording
 * timestamp per active camera, runs each through the pure [decide] state machine, mutates an
 * in-memory state map, and dispatches Loss/Recovery transitions through [TelegramNotificationService].
 *
 * The state map is kept across ticks so that recovery alerts can fire even after the camera has
 * temporarily fallen out of `activeWindow` (cleanup intentionally retains [CameraSignalState.SignalLost]
 * entries while removing stale [CameraSignalState.Healthy] ones — see [tick] cleanup block).
 *
 * Cancellation is propagated unchanged; transient repository or notification failures are logged
 * at WARN and swallowed so a single bad tick (or transient queue-full condition) does not kill
 * the scheduler thread.
 *
 * The companion `SignalLossTelegramGuard` (in the telegram module) covers the conflict-fail
 * scenario where `signal-loss.enabled=true` is paired with `telegram.enabled=false`; this task
 * activates independently of the guard via the same `@ConditionalOnProperty` on
 * `application.signal-loss.enabled`.
 */
@Component
@ConditionalOnProperty(
    prefix = "application.signal-loss",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
@DependsOn("signalLossTelegramGuard")
class SignalLossMonitorTask(
    private val properties: SignalLossProperties,
    private val repository: RecordingEntityRepository,
    private val notificationService: TelegramNotificationService,
    private val clock: Clock,
) {
    // ConcurrentHashMap is intentionally defensive even though `@Scheduled fixedDelay` serializes
    // invocations — protects against future JMX/test access without a measurable cost.
    private val state = ConcurrentHashMap<String, CameraSignalState>()
    private lateinit var startedAt: Instant

    @PostConstruct
    fun init() {
        startedAt = Instant.now(clock)
        logger.info {
            "SignalLossMonitorTask started: threshold=${properties.threshold}, " +
                "pollInterval=${properties.pollInterval}, activeWindow=${properties.activeWindow}, " +
                "startupGrace=${properties.startupGrace}"
        }
    }

    @Scheduled(fixedDelayString = "\${application.signal-loss.poll-interval}")
    suspend fun tick() {
        try {
            val now = Instant.now(clock)
            val inGrace = now.isBefore(startedAt.plus(properties.startupGrace))
            val activeSince = now.minus(properties.activeWindow)

            val stats = repository.findLastRecordingPerCamera(activeSince)
            val seenCamIds = stats.mapTo(mutableSetOf()) { it.camId }
            val cfg = Config(threshold = properties.threshold, inGrace = inGrace)

            for (stat in stats) {
                val prev = state[stat.camId]
                val decision =
                    decide(
                        camId = stat.camId,
                        prev = prev,
                        obs = Observation(stat.lastRecordTimestamp, now),
                        cfg = cfg,
                    )
                state[stat.camId] = decision.newState

                when (val event = decision.event) {
                    is SignalLossEvent.Loss -> emitLoss(event, now)
                    is SignalLossEvent.Recovery -> emitRecovery(event)
                    null -> Unit
                }
            }

            // Cleanup: remove only Healthy entries that fell out of activeWindow.
            // SignalLost entries are KEPT so an eventual recovery can still be emitted —
            // this is the entire reason we hold state across ticks.
            val removed =
                state.entries
                    .filter { it.key !in seenCamIds && it.value is CameraSignalState.Healthy }
                    .map { it.key }
            removed.forEach { state.remove(it) }

            val currentlyLost = state.values.count { it is CameraSignalState.SignalLost }
            val healthy = state.values.count { it is CameraSignalState.Healthy }
            logger.debug {
                "Signal-loss tick (inGrace=$inGrace): scanned=${stats.size}, " +
                    "currentlyLost=$currentlyLost, healthy=$healthy, removed=${removed.size}"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Signal-loss tick failed" }
        }
    }

    private suspend fun emitLoss(
        event: SignalLossEvent.Loss,
        now: Instant,
    ) {
        // Per-emit try/catch keeps the tick's for-loop progressing for other cameras when a single dispatch fails.
        // `now` is propagated from `tick()` to avoid microsecond drift between the gap the decider used
        // and the "Xm Xs ago" the formatter computes.
        try {
            notificationService.sendCameraSignalLost(event.camId, event.lastSeenAt, now)
            logger.info {
                "Signal lost: camera=${event.camId}, lastSeen=${event.lastSeenAt}, gap=${event.gap}"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to dispatch signal-loss notification for camera ${event.camId}" }
        }
    }

    private suspend fun emitRecovery(event: SignalLossEvent.Recovery) {
        try {
            notificationService.sendCameraSignalRecovered(event.camId, event.downtime)
            logger.info { "Signal recovered: camera=${event.camId}, downtime=${event.downtime}" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to dispatch signal-recovery notification for camera ${event.camId}" }
        }
    }
}
