package ru.zinin.frigate.analyzer.core.judge

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.springframework.beans.factory.ObjectProvider
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.api.JudgeAgent
import ru.zinin.frigate.analyzer.ai.description.api.JudgeOutcome
import ru.zinin.frigate.analyzer.ai.description.api.JudgeRequest
import ru.zinin.frigate.analyzer.ai.description.api.JudgeRuntimeSettings
import ru.zinin.frigate.analyzer.ai.description.api.JudgeVerdict
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import ru.zinin.frigate.analyzer.ai.description.config.JudgeProperties
import ru.zinin.frigate.analyzer.ai.description.ratelimit.JudgeRateLimiter
import ru.zinin.frigate.analyzer.model.dto.BboxCluster
import ru.zinin.frigate.analyzer.model.dto.NewNotificationVerdict
import ru.zinin.frigate.analyzer.model.dto.NotificationDecision
import ru.zinin.frigate.analyzer.model.dto.NotificationDecisionReason
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.dto.VerdictDecision
import ru.zinin.frigate.analyzer.model.dto.VerdictReason
import ru.zinin.frigate.analyzer.model.dto.VerdictStage
import ru.zinin.frigate.analyzer.model.dto.VisualizedFrameData
import ru.zinin.frigate.analyzer.model.persistent.DetectionEntity
import ru.zinin.frigate.analyzer.service.NotificationVerdictService
import ru.zinin.frigate.analyzer.service.config.ObjectTrackerProperties
import ru.zinin.frigate.analyzer.telegram.service.TelegramNotificationService
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationJudgeServiceTest {
    private val agent = mockk<JudgeAgent>()
    private val runtimeSettings = mockk<JudgeRuntimeSettings>()
    private val contextBuilder = mockk<JudgeContextBuilder>()
    private val zoneResolver = mockk<JudgeZoneResolver>()
    private val verdicts = mockk<NotificationVerdictService>()
    private val limiter = mockk<JudgeRateLimiter>()
    private val telegram = mockk<TelegramNotificationService>(relaxed = true)
    private val clock = Clock.fixed(Instant.parse("2026-09-05T10:00:00Z"), ZoneOffset.UTC)
    private val ts = Instant.parse("2026-09-05T09:59:00Z")
    private val recorded = mutableListOf<NewNotificationVerdict>()

    private fun recording(
        camId: String = "cam2",
        id: UUID = UUID.randomUUID(),
        at: Instant = ts,
    ) = RecordingDto(
        id,
        at,
        "/r/$camId/x.mp4",
        at,
        camId,
        LocalDate.of(2026, 9, 5),
        LocalTime.of(9, 59),
        at,
        at,
        at,
        1,
        1,
        5,
        2,
        null,
    )

    // x1 сдвигается на 500 px на каждый объект: одинаковые bbox BboxClusteringHelper склеил бы в один
    // объект, и тест эскалации «второй человек» проверял бы не то.
    private fun detection(
        recordingId: UUID,
        cls: String = "person",
        x1: Float = 10f,
    ) = DetectionEntity(
        UUID.randomUUID(),
        ts,
        recordingId,
        ts,
        0,
        "yolo26x.pt",
        0,
        cls,
        0.9f,
        x1,
        10f,
        x1 + 90f,
        200f,
    )

    private fun candidate(
        camId: String = "cam2",
        classes: List<String> = listOf("person"),
        at: Instant = ts,
    ): JudgeCandidate {
        val rec = recording(camId, at = at)
        return JudgeCandidate(
            rec,
            classes.mapIndexed { i, cls -> detection(rec.id, cls, x1 = 10f + i * 500f) },
            NotificationDecision(true, NotificationDecisionReason.NEW_OBJECTS),
            emptyList(),
            listOf(VisualizedFrameData(0, ByteArray(1), 1)),
            null,
        )
    }

    private fun outcome(
        decision: JudgeVerdict.Decision,
        reason: JudgeVerdict.Reason,
        snooze: Int = 0,
    ) = JudgeOutcome(
        JudgeVerdict(decision, reason, 0.9, "sum", snooze, ""),
        "claude-sonnet",
        "sonnet",
        Duration.ofSeconds(3),
    )

    private fun TestScope.service(): NotificationJudgeService {
        val agentProvider =
            mockk<ObjectProvider<JudgeAgent>>().also {
                every { it.getIfAvailable() } returns agent
            }
        val limiterProvider =
            mockk<ObjectProvider<JudgeRateLimiter>>().also {
                every { it.getIfAvailable() } returns limiter
            }
        coEvery { runtimeSettings.judgeEnabled() } returns true
        coEvery { limiter.tryAcquire() } returns true
        coEvery { zoneResolver.resolve() } returns ZoneId.of("UTC")
        coEvery { contextBuilder.build(any(), any(), any()) } returns JudgeContextResult("{}", emptyList())
        coEvery { verdicts.record(capture(recorded)) } answers { mockk() }
        return NotificationJudgeService(
            agentProvider,
            runtimeSettings,
            contextBuilder,
            zoneResolver,
            verdicts,
            limiterProvider,
            telegram,
            JudgeProperties(enabled = true),
            ObjectTrackerProperties(),
            DescriptionProperties(enabled = true, provider = "claude", common = commonSection()),
            JudgeCoroutineScope(CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())),
            clock,
        )
    }

    @Test
    fun `PUBLISH records a JUDGE verdict, sets the snooze and sends`() =
        runTest {
            coEvery { agent.judge(any()) } returns
                outcome(JudgeVerdict.Decision.PUBLISH, JudgeVerdict.Reason.NEW_EVENT, snooze = 15)
            val c = candidate()
            service().process(c)
            val v = recorded.single()
            assertEquals(VerdictStage.JUDGE, v.stage)
            assertEquals(VerdictDecision.PUBLISH, v.verdict)
            assertEquals(VerdictReason.NEW_EVENT, v.reason)
            assertEquals("person:1", v.classes)
            assertEquals(ts.plusSeconds(900), v.snoozeUntil)
            assertEquals("claude-sonnet", v.presetId)
            assertEquals(3000, v.latencyMs)
            coVerify(exactly = 1) { telegram.sendRecordingNotification(c.recording, c.visualizedFrames, null) }
        }

    @Test
    fun `the context builder gets the detections of each object, not of the whole class`() =
        runTest {
            coEvery { agent.judge(any()) } returns
                outcome(JudgeVerdict.Decision.PUBLISH, JudgeVerdict.Reason.NEW_EVENT)
            val s = service()
            val clusters = slot<List<BboxCluster>>()
            coEvery { contextBuilder.build(any(), capture(clusters), any()) } returns
                JudgeContextResult("{}", emptyList())

            s.process(candidate(classes = listOf("person", "person")))

            // Два человека в 500 px друг от друга — два кластера по одной детекции. Одинаковые
            // размеры здесь и означают, что членство доехало до билдера настоящим.
            assertEquals(listOf(1, 1), clusters.captured.map { it.detections.size })
        }

    @Test
    fun `SUPPRESS records and does not send`() =
        runTest {
            coEvery { agent.judge(any()) } returns
                outcome(JudgeVerdict.Decision.SUPPRESS, JudgeVerdict.Reason.STATIC_OBJECT)
            service().process(candidate())
            assertEquals(VerdictDecision.SUPPRESS, recorded.single().verdict)
            coVerify(exactly = 0) { telegram.sendRecordingNotification(any(), any(), any()) }
        }

    @Test
    fun `a snoozed candidate is suppressed without calling the agent, an escalation wakes it`() =
        runTest {
            coEvery { agent.judge(any()) } returns
                outcome(JudgeVerdict.Decision.PUBLISH, JudgeVerdict.Reason.NEW_EVENT, snooze = 15)
            val s = service()
            s.process(candidate())
            s.process(candidate(at = ts.plusSeconds(60)))
            assertEquals(VerdictStage.SNOOZE, recorded[1].stage)
            assertEquals(VerdictReason.SNOOZED, recorded[1].reason)
            coVerify(exactly = 1) { agent.judge(any()) }
            s.process(candidate(classes = listOf("person", "person"), at = ts.plusSeconds(120)))
            coVerify(exactly = 2) { agent.judge(any()) }
        }

    @Test
    fun `runtime switch off bypasses and sends`() =
        runTest {
            val s = service()
            coEvery { runtimeSettings.judgeEnabled() } returns false
            s.process(candidate())
            assertEquals(VerdictStage.BYPASS, recorded.single().stage)
            assertEquals(VerdictReason.JUDGE_OFF, recorded.single().reason)
            coVerify(exactly = 0) { agent.judge(any()) }
            coVerify(exactly = 1) { telegram.sendRecordingNotification(any(), any(), any()) }
        }

    @Test
    fun `every agent failure fails over to sending with its own reason`() =
        runTest {
            val cases =
                listOf(
                    DescriptionException.Timeout() to VerdictReason.TIMEOUT,
                    DescriptionException.RateLimited() to VerdictReason.RATE_LIMITED,
                    DescriptionException.Unauthorized("401") to VerdictReason.UNAUTHORIZED,
                    DescriptionException.InvalidResponse() to VerdictReason.INVALID_RESPONSE,
                    DescriptionException.Transport() to VerdictReason.TRANSPORT,
                    IllegalStateException("boom") to VerdictReason.TRANSPORT,
                )
            val s = service()
            for ((failure, reason) in cases) {
                recorded.clear()
                coEvery { agent.judge(any()) } throws failure
                s.process(candidate())
                assertEquals(VerdictStage.FAILOVER, recorded.single().stage)
                assertEquals(reason, recorded.single().reason)
                assertEquals(VerdictDecision.PUBLISH, recorded.single().verdict)
            }
            coVerify(exactly = cases.size) { telegram.sendRecordingNotification(any(), any(), any()) }
        }

    @Test
    fun `an exhausted limiter fails over as RATE_LIMITED without calling the agent`() =
        runTest {
            val s = service()
            coEvery { limiter.tryAcquire() } returns false
            s.process(candidate())
            assertEquals(VerdictReason.RATE_LIMITED, recorded.single().reason)
            coVerify(exactly = 0) { agent.judge(any()) }
        }

    @Test
    fun `a context builder failure fails over as CONTEXT_ERROR`() =
        runTest {
            val s = service()
            coEvery { contextBuilder.build(any(), any(), any()) } throws IllegalStateException("db down")
            s.process(candidate())
            assertEquals(VerdictReason.CONTEXT_ERROR, recorded.single().reason)
            coVerify(exactly = 1) { telegram.sendRecordingNotification(any(), any(), any()) }
        }

    @Test
    fun `an unexpected exception outside inner catches still sends and records FAILOVER TRANSPORT`() =
        runTest {
            val s = service()
            coEvery { limiter.tryAcquire() } throws IllegalStateException("limiter exploded")
            s.process(candidate())
            val v = recorded.single()
            assertEquals(VerdictStage.FAILOVER, v.stage)
            assertEquals(VerdictReason.TRANSPORT, v.reason)
            assertEquals(VerdictDecision.PUBLISH, v.verdict)
            coVerify(exactly = 0) { agent.judge(any()) }
            coVerify(exactly = 1) { telegram.sendRecordingNotification(any(), any(), any()) }
        }

    @Test
    fun `a failing verdict write does not lose the decision`() =
        runTest {
            coEvery { agent.judge(any()) } returns
                outcome(JudgeVerdict.Decision.PUBLISH, JudgeVerdict.Reason.NEW_EVENT)
            val s = service()
            coEvery { verdicts.record(any()) } throws IllegalStateException("db down")
            s.process(candidate())
            coVerify(exactly = 1) { telegram.sendRecordingNotification(any(), any(), any()) }
        }

    @Test
    fun `cancellation after submit still sends unjudged`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            coEvery { agent.judge(any()) } coAnswers {
                gate.await()
                outcome(JudgeVerdict.Decision.PUBLISH, JudgeVerdict.Reason.NEW_EVENT)
            }
            val s = service()
            val job = s.submit(candidate())
            runCurrent()
            job.cancel()
            job.join()
            val v = recorded.single()
            assertEquals(VerdictStage.FAILOVER, v.stage)
            assertEquals(VerdictReason.TRANSPORT, v.reason)
            assertEquals(VerdictDecision.PUBLISH, v.verdict)
            coVerify(exactly = 1) { telegram.sendRecordingNotification(any(), any(), any()) }
        }

    @Test
    fun `a camera queue deeper than 20 sends the overflow unjudged`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            coEvery { agent.judge(any()) } coAnswers {
                gate.await()
                outcome(JudgeVerdict.Decision.SUPPRESS, JudgeVerdict.Reason.DUPLICATE)
            }
            val s = service()
            val jobs = (1..21).map { i -> s.submit(candidate(at = ts.plusSeconds(i.toLong()))) }
            runCurrent()
            assertEquals(VerdictStage.FAILOVER, recorded.single().stage)
            assertEquals(VerdictReason.TRANSPORT, recorded.single().reason)
            coVerify(exactly = 1) { telegram.sendRecordingNotification(any(), any(), any()) }
            gate.complete(Unit)
            jobs.joinAll()
            coVerify(exactly = 1) { telegram.sendRecordingNotification(any(), any(), any()) }
        }

    @Test
    fun `candidates of one camera are judged in order, different cameras in parallel`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            coEvery { agent.judge(any()) } coAnswers {
                val req = firstArg<JudgeRequest>()
                order += req.camId
                if (req.camId == "cam2" && order.count { it == "cam2" } == 1) gate.await()
                outcome(JudgeVerdict.Decision.SUPPRESS, JudgeVerdict.Reason.DUPLICATE)
            }
            val s = service()
            val first = s.submit(candidate("cam2"))
            val second = s.submit(candidate("cam2", at = ts.plusSeconds(60)))
            val other = s.submit(candidate("cam3"))
            runCurrent()
            assertEquals(listOf("cam2", "cam3"), order) // second cam2 waits for the first, cam3 does not
            gate.complete(Unit)
            listOf(first, second, other).joinAll()
            assertEquals(listOf("cam2", "cam3", "cam2"), order)
        }

    @Test
    fun `the status snapshot keeps live snoozes and hides the expired ones`() =
        runTest {
            val s = service()
            coEvery { agent.judge(any()) } returns
                outcome(JudgeVerdict.Decision.PUBLISH, JudgeVerdict.Reason.NEW_EVENT, snooze = 1)
            s.process(candidate(camId = "cam2", at = ts.minusSeconds(600))) // until 09:49+1m, часы 10:00
            coEvery { agent.judge(any()) } returns
                outcome(JudgeVerdict.Decision.PUBLISH, JudgeVerdict.Reason.NEW_EVENT, snooze = 15)
            s.process(candidate(camId = "cam3")) // until 10:14
            // Реестр держит обе камеры — covers() меряет окно от времени записи и нужен для бэклога;
            // на экран уходит только та, что активна по стенным часам.
            assertEquals(listOf("cam3"), s.snapshotSnoozes().map { it.camId })
        }

    @Test
    fun `a cancellation escaping the send neither resends nor writes a second verdict`() =
        runTest {
            var sends = 0
            coEvery { agent.judge(any()) } returns
                outcome(JudgeVerdict.Decision.PUBLISH, JudgeVerdict.Reason.NEW_EVENT)
            // Рассылка идёт под NonCancellable, поэтому отмена снаружи её не прерывает; до обработчика
            // отмены доходит только та, что родилась ВНУТРИ рассылки — например, отменённая очередь.
            coEvery { telegram.sendRecordingNotification(any(), any(), any()) } coAnswers {
                sends++
                throw CancellationException("the telegram queue was cancelled")
            }
            val s = service()

            assertFailsWith<CancellationException> { s.process(candidate()) }

            // Получатели, принятые очередью до отмены, получили бы второе сообщение, а база — вторую
            // строку на ту же запись: и счётчики /status, и /verdicts считали бы её дважды.
            assertEquals(VerdictStage.JUDGE, recorded.single().stage)
            assertEquals(1, sends)
        }

    @Test
    fun `cancellation before the fan-out reaches the queue still delivers the notification`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            var enqueued = false
            coEvery { agent.judge(any()) } returns
                outcome(JudgeVerdict.Decision.PUBLISH, JudgeVerdict.Reason.NEW_EVENT)
            // Гейт стоит там же, где реальная рассылка приостанавливается ДО первого enqueue:
            // на чтении подписчиков из базы. Отмена, пойманная здесь, не должна терять запись —
            // фасад уже пометил её обработанной, и пайплайн её не повторит.
            coEvery { telegram.sendRecordingNotification(any(), any(), any()) } coAnswers {
                gate.await()
                enqueued = true
            }
            val s = service()
            val job = s.submit(candidate())
            runCurrent()
            job.cancel()
            gate.complete(Unit)
            job.join()
            assertTrue(enqueued, "the fan-out must finish once it has started")
            assertEquals(VerdictStage.JUDGE, recorded.single().stage)
            coVerify(exactly = 1) { telegram.sendRecordingNotification(any(), any(), any()) }
        }

    private fun commonSection() =
        DescriptionProperties.CommonSection(
            language = "ru",
            shortMaxLength = 200,
            detailedMaxLength = 1500,
            maxFrames = 10,
            queueTimeout = Duration.ofSeconds(30),
            timeout = Duration.ofSeconds(60),
            maxConcurrent = 2,
        )
}
