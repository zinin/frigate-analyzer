package ru.zinin.frigate.analyzer.core.task

import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

/** A single observation pulled from the database for one camera. */
data class Observation(
    val maxRecordTs: Instant,
    val now: Instant,
)

/** Static configuration the decider needs to evaluate one observation. */
data class Config(
    val threshold: Duration,
    val inGrace: Boolean,
)

/** Domain events emitted on user-visible state transitions. */
sealed class SignalLossEvent {
    data class Loss(
        val camId: String,
        val lastSeenAt: Instant,
        val gap: Duration,
    ) : SignalLossEvent()

    data class Recovery(
        val camId: String,
        val downtime: Duration,
    ) : SignalLossEvent()
}

/** Pair of `(newState, optionally an event to emit)` produced by [decide]. */
data class Decision(
    val newState: CameraSignalState,
    val event: SignalLossEvent?,
)

/**
 * Pure state-machine decision. No I/O, no mutation, no clock — `obs.now` and `cfg.inGrace`
 * are passed in.
 *
 * See spec section "decide() Decision Table" for the full transition matrix.
 *
 * Clock-skew handling: if `maxRecordTs > now`, the gap is clamped to zero. A debug-level
 * line is logged per detection (operators can raise the log level for [SignalLossDecider]
 * when investigating clock-skew incidents). The camera is then treated as healthy.
 */
fun decide(
    camId: String,
    prev: CameraSignalState?,
    obs: Observation,
    cfg: Config,
): Decision {
    val rawGap = Duration.between(obs.maxRecordTs, obs.now)
    val gap =
        if (rawGap.isNegative) {
            logger.debug {
                "Clock skew for camera $camId: maxRecordTs=${obs.maxRecordTs} > now=${obs.now}; treating gap as 0"
            }
            Duration.ZERO
        } else {
            rawGap
        }
    val overThreshold = gap > cfg.threshold

    return when (prev) {
        null -> {
            when {
                !overThreshold -> {
                    Decision(
                        newState = CameraSignalState.Healthy(obs.maxRecordTs),
                        event = null,
                    )
                }

                cfg.inGrace -> {
                    Decision(
                        newState = CameraSignalState.SignalLost(obs.maxRecordTs, notificationSent = false),
                        event = null,
                    )
                }

                else -> {
                    Decision(
                        newState = CameraSignalState.SignalLost(obs.maxRecordTs, notificationSent = true),
                        event = SignalLossEvent.Loss(camId, obs.maxRecordTs, gap),
                    )
                }
            }
        }

        is CameraSignalState.Healthy -> {
            when {
                !overThreshold -> {
                    Decision(
                        newState = CameraSignalState.Healthy(obs.maxRecordTs),
                        event = null,
                    )
                }

                cfg.inGrace -> {
                    Decision(
                        newState = CameraSignalState.SignalLost(obs.maxRecordTs, notificationSent = false),
                        event = null,
                    )
                }

                else -> {
                    Decision(
                        newState = CameraSignalState.SignalLost(obs.maxRecordTs, notificationSent = true),
                        event = SignalLossEvent.Loss(camId, obs.maxRecordTs, gap),
                    )
                }
            }
        }

        is CameraSignalState.SignalLost -> {
            when {
                !overThreshold -> {
                    // Suppress Recovery if the prior Loss was never user-visible (still in grace,
                    // or grace ended without ever producing a late alert): a "back online" message
                    // without a preceding "lost signal" is confusing UX. Spec "Restart Behavior" §4.
                    Decision(
                        newState = CameraSignalState.Healthy(obs.maxRecordTs),
                        event =
                            if (prev.notificationSent) {
                                SignalLossEvent.Recovery(
                                    camId,
                                    Duration.between(prev.lastSeenAt, obs.maxRecordTs),
                                )
                            } else {
                                null
                            },
                    )
                }

                prev.notificationSent -> {
                    Decision(
                        // Already notified — no spam.
                        newState = prev,
                        event = null,
                    )
                }

                cfg.inGrace -> {
                    Decision(
                        // Still in grace, keep deferred.
                        newState = CameraSignalState.SignalLost(prev.lastSeenAt, notificationSent = false),
                        event = null,
                    )
                }

                else -> {
                    Decision(
                        // LATE ALERT: deferred during grace, fires on first tick after grace ends.
                        newState = CameraSignalState.SignalLost(prev.lastSeenAt, notificationSent = true),
                        event = SignalLossEvent.Loss(camId, prev.lastSeenAt, gap),
                    )
                }
            }
        }
    }
}
