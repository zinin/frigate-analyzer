package ru.zinin.frigate.analyzer.core.config.properties

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@ConfigurationProperties(prefix = "application.signal-loss")
@Validated
data class SignalLossProperties(
    val enabled: Boolean = true,
    val threshold: Duration = Duration.ofMinutes(3),
    val pollInterval: Duration = Duration.ofSeconds(30),
    val activeWindow: Duration = Duration.ofHours(24),
    val startupGrace: Duration = Duration.ofMinutes(5),
) {
    // Cross-field invariants are checked imperatively in `init {}` (instead of as Bean Validation
    // annotations on individual fields) for two reasons:
    // 1. Bean Validation cannot concisely express `pollInterval < threshold` and
    //    `activeWindow > threshold`.
    // 2. Kotlin non-nullable types already prevent null binding, and unified imperative `check(...)`
    //    messages name the offending property in operator-readable form.
    //
    // Using `init {}` rather than `@PostConstruct` ensures `data class.copy(...)` re-validates,
    // preventing silent bypass.
    init {
        check(!threshold.isZero && !threshold.isNegative) {
            "application.signal-loss.threshold must be positive, got $threshold"
        }
        check(!pollInterval.isZero && !pollInterval.isNegative) {
            "application.signal-loss.pollInterval must be positive, got $pollInterval"
        }
        check(!activeWindow.isZero && !activeWindow.isNegative) {
            "application.signal-loss.activeWindow must be positive, got $activeWindow"
        }
        check(!startupGrace.isNegative) {
            "application.signal-loss.startupGrace must be non-negative, got $startupGrace"
        }
        check(pollInterval < threshold) {
            "application.signal-loss.pollInterval ($pollInterval) must be smaller than threshold ($threshold)"
        }
        check(activeWindow > threshold) {
            "application.signal-loss.activeWindow ($activeWindow) must be greater than threshold ($threshold)"
        }
    }
}
