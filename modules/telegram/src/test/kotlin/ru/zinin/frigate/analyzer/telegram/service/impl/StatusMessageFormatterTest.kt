package ru.zinin.frigate.analyzer.telegram.service.impl

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.context.support.ReloadableResourceBundleMessageSource
import ru.zinin.frigate.analyzer.model.dto.CameraState
import ru.zinin.frigate.analyzer.model.dto.CameraStatusDto
import ru.zinin.frigate.analyzer.model.response.CameraStatistics
import ru.zinin.frigate.analyzer.model.response.CamerasSection
import ru.zinin.frigate.analyzer.model.response.DetectServerStatistics
import ru.zinin.frigate.analyzer.model.response.RecordingsStatistics
import ru.zinin.frigate.analyzer.model.response.ServerLoad
import ru.zinin.frigate.analyzer.model.response.ServerStatus
import ru.zinin.frigate.analyzer.model.response.StatusResponse
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatusMessageFormatterTest {
    private val msg =
        mockk<MessageResolver>().apply {
            // Identity-style stubs — return key for plain keys, "{val}" for parametric.
            every { get(any(), "en") } answers { firstArg<String>() }
            every { get(any(), "en", *anyVararg()) } answers {
                val key = firstArg<String>()
                val args = thirdArg<Array<*>>().joinToString(",")
                "$key[$args]"
            }
            // Duration buckets used by SignalLossMessageFormatter
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
        }
    private val duration = SignalLossMessageFormatter(msg)
    private val formatter = StatusMessageFormatter(msg, duration)

    private val now = Instant.parse("2026-04-25T10:00:00Z")
    private val zone = ZoneId.of("UTC")

    private fun snapshot(
        camerasEnabled: Boolean = true,
        cameras: List<CameraStatusDto> = emptyList(),
    ): StatusResponse =
        StatusResponse(
            recordings =
                RecordingsStatistics(
                    total = 100L,
                    processed = 90L,
                    unprocessed = 10L,
                    success = 85L,
                    errors = 5L,
                    byCameras =
                        listOf(
                            CameraStatistics("cam1", 50, 50, 5),
                            CameraStatistics("cam2", 50, 40, 3),
                        ),
                    processingRatePerMinute = 2.5,
                ),
            cameras = CamerasSection(monitoringEnabled = camerasEnabled, items = cameras),
            detectServers =
                listOf(
                    DetectServerStatistics(
                        id = "srv-a",
                        status = ServerStatus.DEAD,
                        frameRequests = ServerLoad(0, 4),
                        frameExtractionRequests = ServerLoad(0, 2),
                        visualizeRequests = ServerLoad(0, 1),
                        videoVisualizeRequests = ServerLoad(0, 1),
                    ),
                ),
        )

    @Test
    fun `format escapes HTML special chars in camId`() {
        val items =
            listOf(
                CameraStatusDto(
                    camId = "cam<&>",
                    state = CameraState.HEALTHY,
                    lastSeenAt = now.minusSeconds(2),
                    offlineFor = null,
                ),
            )
        val out = formatter.format(snapshot(cameras = items), language = "en", zone = zone, now = now)
        assertTrue(out.contains("cam&lt;&amp;&gt;"), "expected escaped camId in: $out")
        assertFalse(out.contains("cam<&>"), "raw < & > leaked: $out")
    }

    @Test
    fun `format renders disabled monitoring marker when monitoringEnabled=false`() {
        val out = formatter.format(snapshot(camerasEnabled = false), language = "en", zone = zone, now = now)
        assertTrue(out.contains("status.cameras.disabled"), "missing disabled marker in: $out")
    }

    @Test
    fun `format renders empty marker when monitoringEnabled=true and items empty`() {
        val out = formatter.format(snapshot(camerasEnabled = true, cameras = emptyList()), language = "en", zone = zone, now = now)
        assertTrue(out.contains("status.cameras.empty"), "missing empty marker in: $out")
    }

    @Test
    fun `format produces non-empty HTML with all four section titles`() {
        val items =
            listOf(
                CameraStatusDto(
                    camId = "cam1",
                    state = CameraState.OFFLINE,
                    lastSeenAt = now.minusSeconds(600),
                    offlineFor = Duration.ofSeconds(600),
                ),
            )
        val out = formatter.format(snapshot(cameras = items), language = "en", zone = zone, now = now)
        assertTrue(out.contains("status.title"))
        assertTrue(out.contains("status.section.recordings"))
        assertTrue(out.contains("status.section.byCamera"))
        assertTrue(out.contains("status.section.cameras"))
        assertTrue(out.contains("status.section.servers"))
        assertTrue(out.contains("<pre>"))
    }

    @Test
    fun `format respects user timezone in offline last-seen rendering`() {
        val items =
            listOf(
                CameraStatusDto(
                    camId = "cam1",
                    state = CameraState.OFFLINE,
                    lastSeenAt = Instant.parse("2026-04-25T15:31:22Z"),
                    offlineFor = Duration.ofMinutes(7),
                ),
            )
        val moscow = ZoneId.of("Europe/Moscow") // UTC+3
        val out = formatter.format(snapshot(cameras = items), language = "en", zone = moscow, now = now)
        assertTrue(out.contains("18:31:22"), "expected zone-shifted time in: $out")
        assertFalse(out.contains("2026-04-25 18:31:22"), "did not expect date prefix for short offline: $out")
    }

    @Test
    fun `format prepends date to last-seen when offlineFor is at least 24h`() {
        val items =
            listOf(
                CameraStatusDto(
                    camId = "cam1",
                    state = CameraState.OFFLINE,
                    lastSeenAt = Instant.parse("2026-04-25T15:31:22Z"),
                    offlineFor = Duration.ofHours(48),
                ),
            )
        val moscow = ZoneId.of("Europe/Moscow") // UTC+3
        val out = formatter.format(snapshot(cameras = items), language = "en", zone = moscow, now = now)
        assertTrue(
            out.contains("2026-04-25 18:31:22"),
            "expected date-prefixed last-seen for long offline (>=24h) in: $out",
        )
    }

    @Test
    fun `format renders DEAD servers using dead line key`() {
        val out = formatter.format(snapshot(), language = "en", zone = zone, now = now)
        assertTrue(out.contains("status.servers.line.dead"), "missing dead line key in: $out")
        assertEquals(1, out.split("status.servers.line.dead").size - 1)
    }

    @Test
    fun `format does not duplicate camId or server id`() {
        val items =
            listOf(
                CameraStatusDto(
                    camId = "cam-unique",
                    state = CameraState.OFFLINE,
                    lastSeenAt = now.minusSeconds(120),
                    offlineFor = Duration.ofSeconds(120),
                ),
            )
        val out = formatter.format(snapshot(cameras = items), language = "en", zone = zone, now = now)
        // camId should appear exactly once (no MessageFormat duplication)
        assertEquals(1, out.split("cam-unique").size - 1, "camId duplicated in: $out")
        // server id "srv-a" (DEAD in snapshot()) should appear exactly once
        assertEquals(1, out.split("srv-a").size - 1, "server id duplicated in: $out")
    }

    @Test
    fun `format escapes HTML special chars in byCameras camId`() {
        val snap =
            StatusResponse(
                recordings =
                    RecordingsStatistics(
                        total = 1,
                        processed = 1,
                        unprocessed = 0,
                        success = 1,
                        errors = 0,
                        byCameras =
                            listOf(
                                CameraStatistics(camId = "cam<&>", recordingsCount = 1, recordingsProcessed = 1, detectionsCount = 0),
                            ),
                        processingRatePerMinute = 0.0,
                    ),
                cameras = CamerasSection(monitoringEnabled = false, items = emptyList()),
                detectServers = emptyList(),
            )
        val out = formatter.format(snap, language = "en", zone = zone, now = now)
        assertTrue(out.contains("cam&lt;&amp;&gt;"), "expected escaped camId in byCameras: $out")
        assertFalse(out.contains("cam<&>"), "raw < & > leaked in byCameras: $out")
    }

    @Test
    fun `format escapes HTML special chars in server id`() {
        val snap =
            StatusResponse(
                recordings =
                    RecordingsStatistics(
                        total = 0,
                        processed = 0,
                        unprocessed = 0,
                        success = 0,
                        errors = 0,
                        byCameras = emptyList(),
                        processingRatePerMinute = 0.0,
                    ),
                cameras = CamerasSection(monitoringEnabled = false, items = emptyList()),
                detectServers =
                    listOf(
                        DetectServerStatistics(
                            id = "srv<a>",
                            status = ServerStatus.ALIVE,
                            frameRequests = ServerLoad(1, 4),
                            frameExtractionRequests = ServerLoad(0, 2),
                            visualizeRequests = ServerLoad(0, 1),
                            videoVisualizeRequests = ServerLoad(0, 1),
                        ),
                    ),
            )
        val out = formatter.format(snap, language = "en", zone = zone, now = now)
        assertTrue(out.contains("srv&lt;a&gt;"), "expected escaped server id: $out")
        assertFalse(out.contains("srv<a>"), "raw < > leaked in server id: $out")
    }

    @Test
    fun `format renders layout C with success and errors rows including percentages`() {
        val out = formatter.format(snapshot(), language = "en", zone = zone, now = now)
        // Success row: label key present, value template rendered with success count and pct of total.
        // snapshot() has total=100, success=85, errors=5 → success%=85.0, errors%=5.0
        assertTrue(out.contains("status.recordings.label.success"), "missing success label in: $out")
        assertTrue(
            out.contains("status.recordings.value.withPct[85,85.0]"),
            "missing success value with pct in: $out",
        )
        assertTrue(out.contains("status.recordings.label.errors"), "missing errors label in: $out")
        assertTrue(
            out.contains("status.recordings.value.withPct[5,5.0]"),
            "missing errors value with pct in: $out",
        )
        // Old Processed row must be gone.
        assertFalse(
            out.contains("status.recordings.label.processed"),
            "obsolete processed label still rendered: $out",
        )
    }

    @Test
    fun `format renders errors row with zero count and zero percent`() {
        val zeroErrors =
            StatusResponse(
                recordings =
                    RecordingsStatistics(
                        total = 50L,
                        processed = 50L,
                        unprocessed = 0L,
                        success = 50L,
                        errors = 0L,
                        byCameras = emptyList(),
                        processingRatePerMinute = 1.0,
                    ),
                cameras = CamerasSection(monitoringEnabled = false, items = emptyList()),
                detectServers = emptyList(),
            )
        val out = formatter.format(zeroErrors, language = "en", zone = zone, now = now)
        assertTrue(
            out.contains("status.recordings.value.withPct[0,0.0]"),
            "expected errors row '0 (0.0%)' even when errors=0: $out",
        )
    }

    @Test
    fun `format keeps layout C when success plus errors does not equal processed`() {
        // Documents that the design intentionally does NOT enforce
        // `success + errors == processed`; layout C must still render cleanly
        // when a future retry-pending state breaks the invariant.
        val divergent =
            StatusResponse(
                recordings =
                    RecordingsStatistics(
                        total = 100L,
                        processed = 100L,
                        unprocessed = 0L,
                        success = 80L,
                        errors = 10L,
                        byCameras = emptyList(),
                        processingRatePerMinute = 0.0,
                    ),
                cameras = CamerasSection(monitoringEnabled = false, items = emptyList()),
                detectServers = emptyList(),
            )
        val out = formatter.format(divergent, language = "en", zone = zone, now = now)
        assertTrue(
            out.contains("status.recordings.value.withPct[80,80.0]"),
            "missing success row 80 (80.0%) in: $out",
        )
        assertTrue(
            out.contains("status.recordings.value.withPct[10,10.0]"),
            "missing errors row 10 (10.0%) in: $out",
        )
    }

    @Test
    fun `format renders zero percent when total is zero`() {
        val empty =
            StatusResponse(
                recordings =
                    RecordingsStatistics(
                        total = 0L,
                        processed = 0L,
                        unprocessed = 0L,
                        success = 0L,
                        errors = 0L,
                        byCameras = emptyList(),
                        processingRatePerMinute = 0.0,
                    ),
                cameras = CamerasSection(monitoringEnabled = false, items = emptyList()),
                detectServers = emptyList(),
            )
        val out = formatter.format(empty, language = "en", zone = zone, now = now)
        // Both success and errors rows should render with '0.0' percent (div-by-zero guard).
        assertEquals(
            2,
            out.split("status.recordings.value.withPct[0,0.0]").size - 1,
            "expected two '0 (0.0%)' rows (success + errors) when total=0: $out",
        )
    }
}

class StatusMessageFormatterI18nTest {
    private val messageSource =
        ReloadableResourceBundleMessageSource().apply {
            setBasename("classpath:messages")
            setDefaultEncoding("UTF-8")
            setFallbackToSystemLocale(false)
            setDefaultLocale(Locale.forLanguageTag("en"))
        }
    private val realMessageResolver = MessageResolver(messageSource)
    private val duration = SignalLossMessageFormatter(realMessageResolver)
    private val formatter = StatusMessageFormatter(realMessageResolver, duration)

    private val now = Instant.parse("2026-04-25T10:00:00Z")
    private val zone = ZoneOffset.UTC

    private fun sampleSnapshot(): StatusResponse =
        StatusResponse(
            recordings =
                RecordingsStatistics(
                    total = 0,
                    processed = 0,
                    unprocessed = 0,
                    success = 0,
                    errors = 0,
                    byCameras = emptyList(),
                    processingRatePerMinute = 0.0,
                ),
            cameras =
                CamerasSection(
                    monitoringEnabled = true,
                    items =
                        listOf(
                            CameraStatusDto(
                                camId = "cam1",
                                state = CameraState.OFFLINE,
                                lastSeenAt = Instant.parse("2026-04-25T09:53:00Z"),
                                offlineFor = Duration.ofMinutes(7),
                            ),
                        ),
                ),
            detectServers =
                listOf(
                    DetectServerStatistics(
                        id = "srv-a",
                        status = ServerStatus.ALIVE,
                        frameRequests = ServerLoad(1, 4),
                        frameExtractionRequests = ServerLoad(0, 2),
                        visualizeRequests = ServerLoad(0, 1),
                        videoVisualizeRequests = ServerLoad(0, 1),
                    ),
                ),
        )

    @Test
    fun `real bundle EN renders offline line with both duration and last-seen`() {
        val out = formatter.format(sampleSnapshot(), language = "en", zone = zone, now = now)
        assertTrue(out.contains("offline 7 min"), "expected `offline 7 min` in: $out")
        assertTrue(out.contains("(last 09:53:00)"), "expected `(last 09:53:00)` in: $out")
        assertTrue(out.contains("frame 1/4"), "expected `frame 1/4` in: $out")
        assertFalse(out.contains("frame srv-a"), "server id leaked into ALIVE placeholders: $out")
    }

    @Test
    fun `real bundle RU renders offline line with duration and last-seen`() {
        val out = formatter.format(sampleSnapshot(), language = "ru", zone = zone, now = now)
        assertTrue(out.contains("оффлайн 7 мин"), "expected `оффлайн 7 мин` in: $out")
        assertTrue(out.contains("(последняя 09:53:00)"), "expected `(последняя 09:53:00)` in: $out")
        assertTrue(out.contains("frame 1/4"), "expected `frame 1/4` in: $out")
        assertFalse(out.contains("frame srv-a"), "server id leaked into ALIVE placeholders: $out")
        assertTrue(out.contains("Статус Frigate Analyzer"), "expected RU title in: $out")
    }

    @Test
    fun `format renders EN recordings rows with localized labels and no raw keys`() {
        val out = formatter.format(sampleSnapshot(), language = "en", zone = zone, now = now)
        assertTrue(out.contains("Success"), "EN: missing localized 'Success' label: $out")
        assertTrue(out.contains("Errors"), "EN: missing localized 'Errors' label: $out")
        assertFalse(
            out.contains("status.recordings.label.success") ||
                out.contains("status.recordings.label.errors") ||
                out.contains("status.recordings.value.withPct"),
            "EN: raw i18n key leaked through MessageResolver fallback: $out",
        )
    }

    @Test
    fun `format renders RU recordings rows with localized labels and no raw keys`() {
        val out = formatter.format(sampleSnapshot(), language = "ru", zone = zone, now = now)
        assertTrue(out.contains("Успешно"), "RU: missing localized 'Успешно' label: $out")
        assertTrue(out.contains("Ошибки"), "RU: missing localized 'Ошибки' label: $out")
        assertFalse(
            out.contains("status.recordings.label.success") ||
                out.contains("status.recordings.label.errors") ||
                out.contains("status.recordings.value.withPct"),
            "RU: raw i18n key leaked through MessageResolver fallback: $out",
        )
    }

    @Test
    fun `RU locale renders non-zero percentages with dot separator (Locale ROOT guard)`() {
        val snapshot =
            sampleSnapshot().copy(
                recordings =
                    RecordingsStatistics(
                        total = 100,
                        processed = 90,
                        unprocessed = 10,
                        success = 85,
                        errors = 5,
                        byCameras = emptyList(),
                        processingRatePerMinute = 1.5,
                    ),
            )

        val out = formatter.format(snapshot, language = "ru", zone = zone, now = now)

        assertTrue(out.contains("85.0"), "expected `85.0` (dot decimal) in RU output: $out")
        assertTrue(out.contains("5.0"), "expected `5.0` (dot decimal) in RU output: $out")
        assertFalse(
            Regex("""\b\d+,\d+%""").containsMatchIn(out),
            "RU locale leaked into pct format (found `N,N%` instead of `N.N%`): $out",
        )
    }
}
