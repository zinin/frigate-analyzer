package ru.zinin.frigate.analyzer.service.impl

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.common.helper.UUIDGeneratorHelper
import ru.zinin.frigate.analyzer.model.dto.NewNotificationVerdict
import ru.zinin.frigate.analyzer.model.dto.VerdictCountRow
import ru.zinin.frigate.analyzer.model.dto.VerdictDecision
import ru.zinin.frigate.analyzer.model.dto.VerdictReason
import ru.zinin.frigate.analyzer.model.dto.VerdictStage
import ru.zinin.frigate.analyzer.model.persistent.NotificationVerdictEntity
import ru.zinin.frigate.analyzer.service.repository.JudgeStatsRepository
import ru.zinin.frigate.analyzer.service.repository.NotificationVerdictRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

class NotificationVerdictServiceImplTest {
    private val repository = mockk<NotificationVerdictRepository>()
    private val stats = mockk<JudgeStatsRepository>()
    private val uuid = mockk<UUIDGeneratorHelper>()
    private val clock = Clock.fixed(Instant.parse("2026-09-05T10:00:00Z"), ZoneOffset.UTC)
    private val service = NotificationVerdictServiceImpl(repository, stats, uuid, clock)

    @Test
    fun `record maps the DTO onto the entity with a generated id and the clock time`() =
        runTest {
            val id = UUID.randomUUID()
            every { uuid.generateV1() } returns id
            val saved = slot<NotificationVerdictEntity>()
            coEvery { repository.save(capture(saved)) } answers { saved.captured }

            val verdict =
                NewNotificationVerdict(
                    recordingId = UUID.randomUUID(),
                    camId = "cam2",
                    recordTimestamp = Instant.parse("2026-09-05T09:59:00Z"),
                    stage = VerdictStage.JUDGE,
                    verdict = VerdictDecision.SUPPRESS,
                    reason = VerdictReason.STATIC_OBJECT,
                    trackerReason = "REAPPEARED",
                    classes = "car:1",
                    confidence = 0.8,
                    summary = "Парковка",
                    snoozeUntil = null,
                    presetId = "claude-sonnet",
                    model = "sonnet",
                    latencyMs = 1200,
                    contextJson = "{}",
                )
            service.record(verdict)

            assertEquals(id, saved.captured.id)
            assertEquals(clock.instant(), saved.captured.createdAt)
            assertEquals("JUDGE", saved.captured.stage)
            assertEquals("SUPPRESS", saved.captured.verdict)
            assertEquals("STATIC_OBJECT", saved.captured.reason)
            assertEquals(0.8f, saved.captured.confidence)
        }

    @Test
    fun `countersSince delegates to the stats repository`() =
        runTest {
            val since = Instant.parse("2026-09-04T10:00:00Z")
            coEvery { stats.verdictCounters(since) } returns listOf(VerdictCountRow("JUDGE", "PUBLISH", "NEW_EVENT", 3))
            assertEquals(3, service.countersSince(since).single().count)
        }
}
