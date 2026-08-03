package ru.zinin.frigate.analyzer.service.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/**
 * Rate limits applied to notifications that already survived every other gate.
 *
 * Kept apart from [ObjectTrackerProperties] deliberately: the tracker decides *what happened*, this
 * decides *how often that may be announced*. Nothing here changes tracker bookkeeping — a
 * suppressed notification still leaves every track advanced and the watch window stamped.
 */
@ConfigurationProperties(prefix = "application.notifications.cooldown")
@Validated
data class NotificationCooldownProperties(
    /**
     * Minimum distance between two REAPPEARED notifications for one camera.
     *
     * Measured on `RecordingDto.recordTimestamp`, never on the wall clock. The unprocessed queue is
     * drained newest-first with no floor on age, so after a restart or a stalled pipeline an hour
     * of recordings is evaluated within seconds; a wall-clock cooldown would announce one of them
     * and swallow every real event behind it.
     *
     * Exists for the burst a single pass produces under a long `ttl`: the frame accumulates stale
     * tracks of the same class along the walkway, a person matches them one after another, and each
     * of those tracks — untouched for hours — contributes its own reappearance. Pick a value above
     * the burst length (tens of seconds) and below the shortest interval between two visits worth
     * telling apart.
     *
     * Keyed by camera id alone, deliberately class-agnostic: whichever class reaches `REAPPEARED`
     * first arms the window for every class on that camera, so a flickering bicycle can mute a
     * person's return for the length of the cooldown. Meant to be set together with
     * `ObjectTrackerProperties.reappearClasses`, which narrows what can reach this gate at all.
     *
     * Defaults to [Duration.ZERO], which disables the gate entirely.
     */
    val reappear: Duration = Duration.ZERO,
) {
    init {
        require(!reappear.isNegative) {
            "application.notifications.cooldown.reappear must be >= PT0S (PT0S disables it), got $reappear"
        }
    }

    /** `false` for the default [Duration.ZERO]: the gate is opt-in. */
    val reappearEnabled: Boolean
        get() = !reappear.isZero
}
