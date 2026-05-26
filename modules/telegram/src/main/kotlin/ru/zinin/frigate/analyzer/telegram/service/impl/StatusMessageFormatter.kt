package ru.zinin.frigate.analyzer.telegram.service.impl

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.model.dto.CameraState
import ru.zinin.frigate.analyzer.model.response.CameraStatistics
import ru.zinin.frigate.analyzer.model.response.CamerasSection
import ru.zinin.frigate.analyzer.model.response.DetectServerStatistics
import ru.zinin.frigate.analyzer.model.response.RecordingsStatistics
import ru.zinin.frigate.analyzer.model.response.ServerStatus
import ru.zinin.frigate.analyzer.model.response.StatusResponse
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class StatusMessageFormatter(
    private val msg: MessageResolver,
    private val duration: SignalLossMessageFormatter,
) {
    fun format(
        snapshot: StatusResponse,
        language: String,
        zone: ZoneId,
        now: Instant,
    ): String =
        buildString {
            appendLine("📊 <b>${escape(msg.get("status.title", language))}</b>")
            appendLine()
            appendRecordings(snapshot.recordings, language)
            appendLine()
            appendByCamera(snapshot.recordings.byCameras, language)
            appendLine()
            appendCameras(snapshot.cameras, language, zone, now)
            appendLine()
            appendServers(snapshot.detectServers, language)
        }.trimEnd()

    private fun StringBuilder.appendRecordings(
        r: RecordingsStatistics,
        language: String,
    ) {
        appendLine("📹 <b>${escape(msg.get("status.section.recordings", language))}</b>")
        val successPct = pct(r.success, r.total)
        val errorsPct = pct(r.errors, r.total)
        val rateFormatted = "%.1f".format(Locale.ROOT, r.processingRatePerMinute)
        val rows =
            listOf(
                msg.get("status.recordings.label.total", language) to r.total.toString(),
                msg.get("status.recordings.label.success", language) to
                    msg.get("status.recordings.value.withPct", language, r.success.toString(), successPct),
                msg.get("status.recordings.label.errors", language) to
                    msg.get("status.recordings.value.withPct", language, r.errors.toString(), errorsPct),
                msg.get("status.recordings.label.unprocessed", language) to r.unprocessed.toString(),
                msg.get("status.recordings.label.rate", language) to
                    msg.get("status.recordings.value.rate", language, rateFormatted),
            )
        val labelWidth = rows.maxOf { it.first.length }
        val valueWidth = rows.maxOf { it.second.length }
        appendPreBlock(
            rows.map { (l, v) ->
                "${l.padEnd(labelWidth + 1)} ${v.padStart(valueWidth)}"
            },
        )
    }

    private fun pct(
        part: Long,
        total: Long,
    ): String =
        if (total > 0) {
            "%.1f".format(Locale.ROOT, part.toDouble() * 100.0 / total.toDouble())
        } else {
            "0.0"
        }

    private fun StringBuilder.appendByCamera(
        cams: List<CameraStatistics>,
        language: String,
    ) {
        appendLine("📹 <b>${escape(msg.get("status.section.byCamera", language))}</b>")
        if (cams.isEmpty()) {
            appendPreBlock(listOf(escape(msg.get("status.empty", language))))
            return
        }
        val headers =
            listOf(
                msg.get("status.byCamera.header.cam", language),
                msg.get("status.byCamera.header.rec", language),
                msg.get("status.byCamera.header.proc", language),
                msg.get("status.byCamera.header.det", language),
            )
        val rows =
            cams.map { c ->
                listOf(
                    c.camId,
                    c.recordingsCount.toString(),
                    c.recordingsProcessed.toString(),
                    c.detectionsCount.toString(),
                )
            }
        // Compute column widths on RAW (pre-escape) lengths. In <pre>, `&lt;` renders as `<`
        // (1 visual char) but the escaped string is longer in char count — so padding must use
        // raw lengths and escape must be applied AFTER padding, not before.
        val widths =
            (0 until 4).map { col ->
                (rows.map { it[col].length } + headers[col].length).max()
            }
        val lines = mutableListOf<String>()
        // Pad first, then escape — escape after padding never breaks alignment because raw widths
        // were used. Header cells are i18n strings (currently HTML-safe) but escape defensively
        // in case future translations introduce `<`, `>`, `&` or `"`.
        val paddedHeaders =
            headers.mapIndexed { i, c ->
                if (i == 0) c.padEnd(widths[i]) else c.padStart(widths[i])
            }
        lines.add(paddedHeaders.map { escape(it) }.joinToString(" | "))
        rows.forEach { row ->
            val padded =
                row.mapIndexed { i, c ->
                    if (i == 0) c.padEnd(widths[i]) else c.padStart(widths[i])
                }
            // Only camId (column 0) is user-derived — escape it; numeric columns are safe.
            val cells = padded.mapIndexed { i, c -> if (i == 0) escape(c) else c }
            lines.add(cells.joinToString(" | "))
        }
        appendPreBlock(lines)
    }

    private fun StringBuilder.appendCameras(
        cameras: CamerasSection,
        language: String,
        zone: ZoneId,
        now: Instant,
    ) {
        appendLine("📷 <b>${escape(msg.get("status.section.cameras", language))}</b>")
        if (!cameras.monitoringEnabled) {
            appendPreBlock(listOf(escape(msg.get("status.cameras.disabled", language))))
            return
        }
        if (cameras.items.isEmpty()) {
            appendPreBlock(listOf(escape(msg.get("status.cameras.empty", language))))
            return
        }
        val locale = Locale.forLanguageTag(language)
        val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withLocale(locale)
        // When the camera has been offline for ≥ 24h, time alone (`09:53:00`) is ambiguous —
        // it could be today's or last week's. Prepend the date in those cases so the operator
        // can correctly interpret a long outage. SignalLoss notifications use HH:mm:ss only
        // because they arrive at the moment of loss (fresh context); /status is read on demand
        // and may surface week-old OFFLINE state.
        val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withLocale(locale)
        val longOfflineThreshold = Duration.ofHours(24)
        val camWidth = cameras.items.maxOf { it.camId.length }
        val lines =
            cameras.items.map { item ->
                val camPadded = item.camId.padEnd(camWidth)
                when (item.state) {
                    CameraState.HEALTHY -> {
                        val ago =
                            duration.formatDuration(
                                Duration.between(item.lastSeenAt, now).coerceAtLeast(Duration.ZERO),
                                language,
                            )
                        val line = msg.get("status.cameras.line.online", language, ago)
                        "🟢 ${escape(camPadded)}  $line"
                    }

                    CameraState.OFFLINE -> {
                        val offlineFor =
                            requireNotNull(item.offlineFor) {
                                "offlineFor must not be null for OFFLINE camera ${item.camId} " +
                                    "(StatusService.toDto contract violation)"
                            }.coerceAtLeast(Duration.ZERO)
                        val lastSeenFormatter =
                            if (offlineFor >= longOfflineThreshold) dateTimeFormatter else timeFormatter
                        val lastSeen = item.lastSeenAt.atZone(zone).format(lastSeenFormatter)
                        val line =
                            msg.get(
                                "status.cameras.line.offline",
                                language,
                                duration.formatDuration(offlineFor, language),
                                lastSeen,
                            )
                        "🔴 ${escape(camPadded)}  $line"
                    }
                }
            }
        appendPreBlock(lines)
    }

    private fun StringBuilder.appendServers(
        servers: List<DetectServerStatistics>,
        language: String,
    ) {
        appendLine("🖥️ <b>${escape(msg.get("status.section.servers", language))}</b>")
        if (servers.isEmpty()) {
            appendPreBlock(listOf(escape(msg.get("status.empty", language))))
            return
        }
        val idWidth = servers.maxOf { it.id.length }
        val lines =
            servers.map { s ->
                val idPadded = s.id.padEnd(idWidth)
                val tail =
                    when (s.status) {
                        ServerStatus.ALIVE -> {
                            msg.get(
                                "status.servers.line.alive",
                                language,
                                s.frameRequests.current,
                                s.frameRequests.maximum,
                                s.frameExtractionRequests.current,
                                s.frameExtractionRequests.maximum,
                                s.visualizeRequests.current,
                                s.visualizeRequests.maximum,
                                s.videoVisualizeRequests.current,
                                s.videoVisualizeRequests.maximum,
                            )
                        }

                        ServerStatus.DEAD -> {
                            msg.get("status.servers.line.dead", language)
                        }
                    }
                val marker = if (s.status == ServerStatus.ALIVE) "🟢" else "🔴"
                "$marker ${escape(idPadded)}  $tail"
            }
        appendPreBlock(lines)
    }

    private fun StringBuilder.appendPreBlock(lines: List<String>) {
        append("<pre>")
        append(lines.joinToString("\n"))
        appendLine("</pre>")
    }

    private fun escape(s: String): String =
        s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
