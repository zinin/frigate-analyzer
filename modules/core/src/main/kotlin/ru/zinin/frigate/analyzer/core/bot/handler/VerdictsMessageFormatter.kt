package ru.zinin.frigate.analyzer.core.bot.handler

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.model.dto.VerdictDecision
import ru.zinin.frigate.analyzer.model.dto.VerdictStage
import ru.zinin.frigate.analyzer.model.persistent.NotificationVerdictEntity
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.service.impl.escapeTelegramHtml
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class VerdictsMessageFormatter(
    private val msg: MessageResolver,
) {
    fun format(
        rows: List<NotificationVerdictEntity>,
        language: String,
        zone: ZoneId,
    ): String {
        val title = "<b>${escapeTelegramHtml(msg.get("verdicts.title", language))}</b>"
        if (rows.isEmpty()) {
            return "$title\n${escapeTelegramHtml(msg.get("verdicts.empty", language))}"
        }
        val fmt = DateTimeFormatter.ofPattern("HH:mm:ss")
        val truncated = escapeTelegramHtml(msg.get("verdicts.truncated", language))
        val sb = StringBuilder(title)
        for (row in rows) {
            val icon =
                when {
                    row.stage == VerdictStage.FAILOVER.name -> "⚠️"
                    row.verdict == VerdictDecision.PUBLISH.name -> "📨"
                    else -> "🔇"
                }
            val line =
                buildString {
                    append(icon).append(' ').append(row.recordTimestamp.atZone(zone).format(fmt)).append(' ')
                    append(escapeTelegramHtml(row.camId)).append(' ').append(row.stage).append(' ')
                    append(row.reason).append(' ')
                    append(escapeTelegramHtml(row.classes))
                    row.summary?.takeIf { it.isNotBlank() }?.let { append(" — ").append(escapeTelegramHtml(it)) }
                }
            if (sb.length + 1 + line.length + 1 + truncated.length > TELEGRAM_LIMIT) {
                sb.append('\n').append(truncated)
                return sb.toString()
            }
            sb.append('\n').append(line)
        }
        return sb.toString()
    }

    private companion object {
        const val TELEGRAM_LIMIT = 4096
    }
}
