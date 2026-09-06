package ru.zinin.frigate.analyzer.core.bot.handler

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.model.persistent.NotificationVerdictEntity
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertTrue

class VerdictsMessageFormatterTest {
    private val msg =
        mockk<MessageResolver>().apply {
            every { get(any(), "en", *anyVararg()) } answers {
                val key = firstArg<String>()
                val args = thirdArg<Array<*>>()
                if (args.isEmpty()) key else "$key[${args.joinToString(",")}]"
            }
        }
    private val formatter = VerdictsMessageFormatter(msg)
    private val zone = ZoneId.of("Europe/Moscow")

    private fun row(
        stage: String,
        verdict: String,
        reason: String,
        summary: String? = "sum",
    ) = NotificationVerdictEntity(
        UUID.randomUUID(),
        Instant.parse("2026-09-05T07:00:00Z"),
        UUID.randomUUID(),
        "cam2",
        Instant.parse("2026-09-05T06:59:30Z"),
        stage,
        verdict,
        reason,
        "NEW_OBJECTS",
        "person:1",
        0.9f,
        summary,
        null,
        null,
        "claude-sonnet",
        "sonnet",
        1200,
        null,
        null,
    )

    @Test
    fun `renders one line per verdict with icon, local time, camera, stage, reason, classes and summary`() {
        val text =
            formatter.format(
                listOf(
                    row("JUDGE", "PUBLISH", "NEW_EVENT"),
                    row("JUDGE", "SUPPRESS", "STATIC_OBJECT"),
                    row("FAILOVER", "PUBLISH", "TIMEOUT", null),
                ),
                "en",
                zone,
            )
        assertTrue(text.startsWith("<b>verdicts.title</b>"))
        assertTrue(text.contains("📨 09:59:30 cam2 JUDGE NEW_EVENT person:1 — sum"))
        assertTrue(text.contains("🔇 09:59:30 cam2 JUDGE STATIC_OBJECT person:1 — sum"))
        assertTrue(text.contains("⚠️ 09:59:30 cam2 FAILOVER TIMEOUT person:1"))
    }

    @Test
    fun `empty list renders the empty message`() =
        assertTrue(formatter.format(emptyList<NotificationVerdictEntity>(), "en", zone).contains("verdicts.empty"))

    @Test
    fun `output is cut at the Telegram limit with a marker`() {
        val rows = List(30) { row("JUDGE", "SUPPRESS", "DUPLICATE", "x".repeat(400)) }
        val text = formatter.format(rows, "en", zone)
        assertTrue(text.length <= 4096)
        assertTrue(text.endsWith("verdicts.truncated"))
    }

    @Test
    fun `html is escaped in summaries`() {
        assertTrue(
            formatter
                .format(listOf(row("JUDGE", "SUPPRESS", "DUPLICATE", "<b>x</b>")), "en", zone)
                .contains("&lt;b&gt;x&lt;/b&gt;"),
        )
    }
}
