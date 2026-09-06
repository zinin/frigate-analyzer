package ru.zinin.frigate.analyzer.core.service

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.springframework.beans.factory.ObjectProvider
import ru.zinin.frigate.analyzer.ai.description.api.ActiveJudgePreset
import ru.zinin.frigate.analyzer.ai.description.api.JudgeRuntimeSettings
import ru.zinin.frigate.analyzer.core.judge.CameraSnooze
import ru.zinin.frigate.analyzer.core.judge.NotificationJudgeService
import ru.zinin.frigate.analyzer.model.dto.VerdictCountRow
import ru.zinin.frigate.analyzer.model.response.CameraSnoozeDto
import ru.zinin.frigate.analyzer.model.response.JudgeSection
import ru.zinin.frigate.analyzer.service.NotificationVerdictService
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatusServiceJudgeTest {
    private val judgeService = mockk<NotificationJudgeService>()
    private val verdicts = mockk<NotificationVerdictService>()
    private val runtime = mockk<JudgeRuntimeSettings>()
    private val activePreset = mockk<ActiveJudgePreset>()
    private val clock = Clock.fixed(Instant.parse("2026-09-05T10:00:00Z"), ZoneOffset.UTC)

    private fun service(judgePresent: Boolean) =
        StatusService(
            recordingRepository = mockk(relaxed = true),
            detectServerLoadBalancer = mockk(relaxed = true),
            signalLossMonitorTask = provider(null),
            clock = clock,
            judgeService = provider(if (judgePresent) judgeService else null),
            verdictService = verdicts,
            judgeRuntimeSettings = provider(if (judgePresent) runtime else null),
            activeJudgePreset = provider(if (judgePresent) activePreset else null),
        )

    @Test
    fun `without the judge the section is disabled`() =
        runTest {
            assertEquals(JudgeSection.disabled(), service(judgePresent = false).collect().judge)
        }

    @Test
    fun `with the judge counters are folded by stage and reason and snoozes are listed`() =
        runTest {
            coEvery { runtime.judgeEnabled() } returns true
            coEvery { activePreset.effective() } returns mockk { every { id } returns "claude-sonnet" }
            coEvery { verdicts.countersSince(clock.instant().minus(Duration.ofHours(24))) } returns
                listOf(
                    VerdictCountRow("JUDGE", "PUBLISH", "NEW_EVENT", 3),
                    VerdictCountRow("JUDGE", "SUPPRESS", "STATIC_OBJECT", 30),
                    VerdictCountRow("JUDGE", "SUPPRESS", "DUPLICATE", 15),
                    VerdictCountRow("SNOOZE", "SUPPRESS", "SNOOZED", 20),
                    VerdictCountRow("FAILOVER", "PUBLISH", "TIMEOUT", 1),
                    VerdictCountRow("BYPASS", "PUBLISH", "JUDGE_OFF", 2),
                )
            every { judgeService.snapshotSnoozes() } returns
                listOf(
                    CameraSnooze(
                        "cam2",
                        clock.instant(),
                        clock.instant().plusSeconds(900),
                        mapOf("person" to 1),
                    ),
                )

            val judge = service(judgePresent = true).collect().judge

            assertTrue(judge.enabled)
            assertTrue(judge.runtimeEnabled)
            assertEquals("claude-sonnet", judge.presetId)
            assertEquals(6, judge.last24h.published) // JUDGE PUBLISH + FAILOVER + BYPASS
            assertEquals(mapOf("STATIC_OBJECT" to 30L, "DUPLICATE" to 15L), judge.last24h.suppressedByReason)
            assertEquals(1, judge.last24h.failover)
            assertEquals(20, judge.last24h.snoozed)
            assertEquals(
                CameraSnoozeDto("cam2", clock.instant().plusSeconds(900), "person:1"),
                judge.snoozes.single(),
            )
        }

    @Test
    fun `settings and preset reads fail soft`() =
        runTest {
            coEvery { runtime.judgeEnabled() } throws IllegalStateException("db")
            coEvery { activePreset.effective() } throws IllegalStateException("db")
            coEvery { verdicts.countersSince(any()) } returns emptyList()
            every { judgeService.snapshotSnoozes() } returns emptyList()
            val judge = service(judgePresent = true).collect().judge
            assertTrue(judge.runtimeEnabled)
            assertNull(judge.presetId)
        }

    private fun <T : Any> provider(value: T?): ObjectProvider<T> =
        mockk {
            every { getIfAvailable() } returns value
            every { ifAvailable } returns value
        }
}
