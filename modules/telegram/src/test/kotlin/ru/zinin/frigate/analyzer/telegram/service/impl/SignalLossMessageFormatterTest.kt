package ru.zinin.frigate.analyzer.telegram.service.impl

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.function.Executable
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SignalLossMessageFormatterTest {
    private val msg =
        mockk<MessageResolver>().apply {
            // Duration buckets — answers reflect the {N} placeholder values
            every { get("signal.duration.seconds", "en", any()) } answers {
                "${arg<Array<*>>(2)[0]} sec"
            }
            every { get("signal.duration.minutes", "en", any()) } answers {
                "${arg<Array<*>>(2)[0]} min"
            }
            every { get("signal.duration.hours", "en", any(), any()) } answers {
                "${arg<Array<*>>(2)[0]} h ${arg<Array<*>>(2)[1]} min"
            }
            every { get("signal.duration.days", "en", any(), any()) } answers {
                "${arg<Array<*>>(2)[0]} d ${arg<Array<*>>(2)[1]} h"
            }
            // Loss message
            every { get("notification.signal.loss.title", "en", any()) } answers {
                "Camera \"${arg<Array<*>>(2)[0]}\" lost signal"
            }
            every { get("notification.signal.loss.last_recording", "en", any(), any()) } answers {
                "Last recording: ${arg<Array<*>>(2)[0]} (${arg<Array<*>>(2)[1]} ago)"
            }
            // Recovery message
            every { get("notification.signal.recovery.title", "en", any()) } answers {
                "Camera \"${arg<Array<*>>(2)[0]}\" is back online"
            }
            every { get("notification.signal.recovery.downtime", "en", any()) } answers {
                "Downtime: ${arg<Array<*>>(2)[0]}"
            }
        }
    private val formatter = SignalLossMessageFormatter(msg)

    @Test
    fun `formatDuration buckets seconds, minutes, hours, days`() {
        // (input seconds, expected output) — exhaustive bucket boundaries
        val cases =
            listOf(
                0L to "0 sec",
                1L to "1 sec",
                59L to "59 sec",
                60L to "1 min",
                119L to "1 min",
                120L to "2 min",
                3599L to "59 min",
                3600L to "1 h 0 min",
                3660L to "1 h 1 min",
                7320L to "2 h 2 min",
                86399L to "23 h 59 min",
                86400L to "1 d 0 h",
                90000L to "1 d 1 h",
            )
        // assertAll reports every failing row in a single test run rather than stopping at the first.
        assertAll(
            cases.map { (seconds, expected) ->
                Executable {
                    assertEquals(
                        expected,
                        formatter.formatDuration(Duration.ofSeconds(seconds), language = "en"),
                        "input=${seconds}s",
                    )
                }
            },
        )
    }

    @Test
    fun `formatDuration treats negative input as zero`() {
        assertEquals(
            "0 sec",
            formatter.formatDuration(Duration.ofSeconds(-10), language = "en"),
        )
    }

    @Test
    fun `buildLossMessage includes title, last-seen time and gap`() {
        val zone = ZoneId.of("UTC")
        val lastSeen = Instant.parse("2026-04-25T14:32:18Z")
        val now = Instant.parse("2026-04-25T14:35:32Z") // gap ~ 3 min 14 s

        val result =
            formatter.buildLossMessage(
                camId = "front_door",
                lastSeenAt = lastSeen,
                now = now,
                zone = zone,
                language = "en",
            )

        assertTrue(
            result.contains("Camera \"front_door\" lost signal"),
            "missing title in: $result",
        )
        // gap rounds down to 3 minutes (194 s -> minutes bucket -> "3 min")
        assertTrue(
            result.contains("Last recording: 14:32:18 (3 min ago)"),
            "missing last-recording line in: $result",
        )
    }

    @Test
    fun `buildLossMessage clamps negative gap (clock skew) to zero`() {
        val zone = ZoneId.of("UTC")
        val lastSeen = Instant.parse("2026-04-25T14:35:32Z")
        val now = Instant.parse("2026-04-25T14:32:18Z") // now < lastSeen — skew

        val result =
            formatter.buildLossMessage(
                camId = "front_door",
                lastSeenAt = lastSeen,
                now = now,
                zone = zone,
                language = "en",
            )

        assertTrue(result.contains("(0 sec ago)"), "expected clamped gap in: $result")
    }

    @Test
    fun `buildRecoveryMessage includes title and downtime`() {
        val downtime = Duration.ofMinutes(12).plusSeconds(48) // 12 min, falls in minutes bucket

        val result =
            formatter.buildRecoveryMessage(
                camId = "front_door",
                downtime = downtime,
                language = "en",
            )

        assertTrue(
            result.contains("Camera \"front_door\" is back online"),
            "missing title in: $result",
        )
        assertTrue(result.contains("Downtime: 12 min"), "missing downtime in: $result")
    }
}
