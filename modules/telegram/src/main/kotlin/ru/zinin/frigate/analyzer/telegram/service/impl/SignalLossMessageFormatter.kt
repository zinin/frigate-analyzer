package ru.zinin.frigate.analyzer.telegram.service.impl

import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Component
class SignalLossMessageFormatter(
    private val msg: MessageResolver,
) {
    fun formatDuration(
        duration: Duration,
        language: String,
    ): String {
        val totalSeconds = duration.seconds.coerceAtLeast(0)
        return when {
            totalSeconds < SECONDS_PER_MINUTE -> {
                msg.get("signal.duration.seconds", language, totalSeconds)
            }

            totalSeconds < SECONDS_PER_HOUR -> {
                msg.get(
                    "signal.duration.minutes",
                    language,
                    totalSeconds / SECONDS_PER_MINUTE,
                )
            }

            totalSeconds < SECONDS_PER_DAY -> {
                val hours = totalSeconds / SECONDS_PER_HOUR
                val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
                msg.get("signal.duration.hours", language, hours, minutes)
            }

            else -> {
                val days = totalSeconds / SECONDS_PER_DAY
                val hours = (totalSeconds % SECONDS_PER_DAY) / SECONDS_PER_HOUR
                msg.get("signal.duration.days", language, days, hours)
            }
        }
    }

    fun buildLossMessage(
        camId: String,
        lastSeenAt: Instant,
        now: Instant,
        zone: ZoneId,
        language: String,
    ): String {
        // Negative gap = Frigate's record_timestamp is in the future relative to our `now` (clock skew).
        // Clamp to zero — the camera is in fact healthy from our perspective.
        val gap = Duration.between(lastSeenAt, now).coerceAtLeast(Duration.ZERO)
        val timeFormatter =
            DateTimeFormatter.ofPattern(TIME_PATTERN).withLocale(Locale.forLanguageTag(language))
        val lastSeenFormatted = lastSeenAt.atZone(zone).format(timeFormatter)
        val gapFormatted = formatDuration(gap, language)
        return buildString {
            appendLine(msg.get("notification.signal.loss.title", language, camId))
            appendLine(
                msg.get(
                    "notification.signal.loss.last_recording",
                    language,
                    lastSeenFormatted,
                    gapFormatted,
                ),
            )
        }
    }

    fun buildRecoveryMessage(
        camId: String,
        downtime: Duration,
        language: String,
    ): String {
        val downtimeFormatted = formatDuration(downtime, language)
        return buildString {
            appendLine(msg.get("notification.signal.recovery.title", language, camId))
            appendLine(msg.get("notification.signal.recovery.downtime", language, downtimeFormatted))
        }
    }

    companion object {
        private const val SECONDS_PER_MINUTE = 60L
        private const val SECONDS_PER_HOUR = 3600L
        private const val SECONDS_PER_DAY = 86400L

        // Explicit pattern (NOT FormatStyle.MEDIUM) to avoid container-locale surprises.
        private const val TIME_PATTERN = "HH:mm:ss"
    }
}
