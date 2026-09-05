package ru.zinin.frigate.analyzer.core.judge

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import ru.zinin.frigate.analyzer.ai.description.config.JudgeProperties
import ru.zinin.frigate.analyzer.core.testsupport.TestObjectMappers
import ru.zinin.frigate.analyzer.model.dto.DetectionDelta
import ru.zinin.frigate.analyzer.model.dto.FrameData
import ru.zinin.frigate.analyzer.model.dto.NotificationDecision
import ru.zinin.frigate.analyzer.model.dto.NotificationDecisionReason
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.dto.RepresentativeBbox
import ru.zinin.frigate.analyzer.model.dto.StaticScore
import ru.zinin.frigate.analyzer.model.persistent.DetectionEntity
import ru.zinin.frigate.analyzer.model.persistent.ObjectTrackEntity
import ru.zinin.frigate.analyzer.model.response.BBox
import ru.zinin.frigate.analyzer.model.response.DetectResponse
import ru.zinin.frigate.analyzer.model.response.Detection
import ru.zinin.frigate.analyzer.model.response.ImageSize
import ru.zinin.frigate.analyzer.service.NotificationVerdictService
import ru.zinin.frigate.analyzer.service.config.ObjectTrackerProperties
import ru.zinin.frigate.analyzer.service.repository.JudgeStatsRepository
import ru.zinin.frigate.analyzer.service.repository.ObjectTrackRepository
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JudgeContextBuilderTest {
    private val stats = mockk<JudgeStatsRepository>()
    private val tracks = mockk<ObjectTrackRepository>()
    private val verdicts = mockk<NotificationVerdictService>()
    private val mapper = TestObjectMappers.internalMapper()
    private val properties = JudgeProperties(cameras = mapOf("cam4" to JudgeProperties.CameraSection(notes = "Огород за домом")))
    private val trackerProperties =
        ObjectTrackerProperties(ttl = Duration.ofHours(12), cleanupRetention = Duration.ofHours(24))
    private val builder = JudgeContextBuilder(stats, tracks, verdicts, properties, trackerProperties, mapper)

    private val recordingId = UUID.randomUUID()
    private val ts = Instant.parse("2026-09-04T07:22:48Z")
    private val zone = ZoneId.of("Europe/Moscow")
    private val recording =
        RecordingDto(
            id = recordingId,
            creationTimestamp = ts,
            filePath = "/r/cam4/22.48.mp4",
            fileCreationTimestamp = ts,
            camId = "cam4",
            recordDate = LocalDate.of(2026, 9, 4),
            recordTime = LocalTime.of(7, 22, 48),
            recordTimestamp = ts,
            startProcessingTimestamp = ts,
            processTimestamp = ts.plusSeconds(51),
            processAttempts = 1,
            detectionsCount = 2,
            analyzeTime = 5,
            analyzedFramesCount = 2,
            errorMessage = null,
        )
    private val frame =
        FrameData(
            recordingId,
            0,
            ByteArray(1),
            DetectResponse(
                listOf(Detection(3, "motorcycle", 0.628, BBox(151.0, 1387.0, 441.0, 1651.0))),
                0,
                ImageSize(2560, 1920),
                "yolo26x.pt",
            ),
        )
    private val objects = listOf(RepresentativeBbox("motorcycle", 151f, 1387f, 441f, 1651f))
    private val decision =
        NotificationDecision(
            true,
            NotificationDecisionReason.NEW_OBJECTS,
            DetectionDelta(newTracksCount = 1, matchedTracksCount = 0, staleTracksCount = 0, newClasses = listOf("motorcycle")),
        )
    private val candidate = JudgeCandidate(recording, listOf(detection("motorcycle", 0.628f)), decision, listOf(frame), emptyList(), null)

    private fun detection(
        cls: String,
        conf: Float,
    ) = DetectionEntity(UUID.randomUUID(), ts, recordingId, ts, 0, "yolo26x.pt", 3, cls, conf, 151f, 1387f, 441f, 1651f)

    private fun happyStubs() {
        coEvery {
            stats.staticScore(
                "cam4",
                "motorcycle",
                151.0,
                1387.0,
                441.0,
                1651.0,
                ts.minus(Duration.ofDays(7)),
                ts,
                recordingId,
                0.4,
                "Europe/Moscow",
            )
        } returns StaticScore(18, 7, Instant.parse("2026-08-28T09:04:10Z"), Instant.parse("2026-09-03T13:54:36Z"))
        coEvery { stats.recordingsInWindow("cam4", ts.minus(Duration.ofDays(7)), ts) } returns 60412
        coEvery { tracks.findActive("cam4", ts.minus(Duration.ofHours(12)), ts.plus(Duration.ofHours(12))) } returns emptyList()
        coEvery { verdicts.recentForCamera("cam4", ts.minus(Duration.ofHours(6)), ts.plus(Duration.ofHours(6)), 10) } returns emptyList()
        coEvery { verdicts.lastPublished("cam4") } returns null
    }

    @Test
    fun `builds every block with snake_case keys and local times in the given zone`() =
        runTest {
            happyStubs()
            val result = builder.build(candidate, objects, zone)
            val root = mapper.readTree(result.json)
            assertTrue(result.errors.isEmpty())
            assertEquals("cam4", root["recording"]["cam"].asString())
            assertEquals("2026-09-04T10:22:48+03:00", root["recording"]["time"].asString())
            assertEquals("Europe/Moscow", root["recording"]["zone"].asString())
            assertEquals(51, root["recording"]["processing_lag_seconds"].asInt())
            assertEquals(2560, root["frames"][0]["width"].asInt())
            assertEquals("motorcycle", root["frames"][0]["detections"][0]["class"].asString())
            assertEquals(18, root["objects"][0]["static"]["recordings"].asInt())
            assertEquals(60412, root["objects"][0]["static"]["recordings_in_window"].asInt())
            assertEquals("NEW_OBJECTS", root["tracker"]["reason"].asString())
            assertEquals("motorcycle", root["tracker"]["new_classes"][0].asString())
            assertTrue(root["active_tracks"].isEmpty)
            assertTrue(root["recent_verdicts"].isEmpty)
            assertTrue(root["last_published"].isNull)
            assertEquals("Огород за домом", root["camera_notes"].asString())
        }

    @Test
    fun `recordingsInWindow is queried once for the camera not once per object`() =
        runTest {
            happyStubs()
            coEvery {
                stats.staticScore(
                    "cam4",
                    "person",
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns StaticScore(1, 1, ts, ts)
            val two =
                listOf(
                    RepresentativeBbox("motorcycle", 151f, 1387f, 441f, 1651f),
                    RepresentativeBbox("person", 10f, 10f, 20f, 20f),
                )
            builder.build(candidate, two, zone)
            coVerify(exactly = 1) { stats.recordingsInWindow("cam4", ts.minus(Duration.ofDays(7)), ts) }
        }

    @Test
    fun `a failing provider degrades to an error marker without failing the build`() =
        runTest {
            happyStubs()
            coEvery { stats.staticScore(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                IllegalStateException("db down")
            val result = builder.build(candidate, objects, zone)
            val root = mapper.readTree(result.json)
            assertEquals("IllegalStateException", root["objects"][0]["static"]["error"].asString())
            assertEquals(listOf("objects.static"), result.errors)
        }

    @Test
    fun `unknown camera has empty notes and absent tracks are marked as unmatched`() =
        runTest {
            happyStubs()
            coEvery { tracks.findActive(any(), any(), any()) } returns
                listOf(
                    ObjectTrackEntity(
                        UUID.randomUUID(),
                        ts.minusSeconds(3600),
                        "cam4",
                        "person",
                        204f,
                        1408f,
                        460f,
                        1652f,
                        ts.minusSeconds(60),
                        UUID.randomUUID(),
                    ),
                )
            val root = mapper.readTree(builder.build(candidate.copy(recording = recording.copy(camId = "cam9")), objects, zone).json)
            assertEquals("", root["camera_notes"].asString())
            assertEquals(false, root["active_tracks"][0]["matched_now"].asBoolean())
        }
}
