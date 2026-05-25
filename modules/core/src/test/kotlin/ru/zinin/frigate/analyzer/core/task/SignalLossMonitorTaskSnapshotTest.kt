package ru.zinin.frigate.analyzer.core.task

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.core.config.properties.SignalLossProperties
import ru.zinin.frigate.analyzer.model.dto.LastRecordingPerCameraDto
import ru.zinin.frigate.analyzer.service.repository.RecordingEntityRepository
import ru.zinin.frigate.analyzer.telegram.service.TelegramNotificationService
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class SignalLossMonitorTaskSnapshotTest {
    @Test
    fun `snapshotStates returns defensive copy after tick`() {
        runBlocking {
            val now = Instant.parse("2026-04-25T10:00:00Z")
            val fixedClock = Clock.fixed(now, ZoneOffset.UTC)
            val repo = mockk<RecordingEntityRepository>()
            val notify = mockk<TelegramNotificationService>(relaxed = true)
            val props =
                SignalLossProperties(
                    enabled = true,
                    threshold = Duration.ofMinutes(3),
                    pollInterval = Duration.ofSeconds(30),
                    activeWindow = Duration.ofMinutes(30),
                    startupGrace = Duration.ZERO,
                )
            coEvery { repo.findLastRecordingPerCamera(any()) } returns
                listOf(
                    LastRecordingPerCameraDto("cam1", now.minusSeconds(10)),
                    LastRecordingPerCameraDto("cam2", now.minusSeconds(600)),
                )

            val task = SignalLossMonitorTask(props, repo, notify, fixedClock)
            task.init()
            task.tick()

            val snapshot = task.snapshotStates()

            assertThat(snapshot.keys).containsExactlyInAnyOrder("cam1", "cam2")
            assertThat(snapshot["cam1"]).isInstanceOf(CameraSignalState.Healthy::class.java)
            assertThat(snapshot["cam2"]).isInstanceOf(CameraSignalState.SignalLost::class.java)
        }
    }

    @Test
    fun `snapshotStates is decoupled from subsequent state mutations`() {
        runBlocking {
            val now = Instant.parse("2026-04-25T10:00:00Z")
            val fixedClock = Clock.fixed(now, ZoneOffset.UTC)
            val repo = mockk<RecordingEntityRepository>()
            val notify = mockk<TelegramNotificationService>(relaxed = true)
            val props =
                SignalLossProperties(
                    enabled = true,
                    threshold = Duration.ofMinutes(3),
                    pollInterval = Duration.ofSeconds(30),
                    activeWindow = Duration.ofMinutes(30),
                    startupGrace = Duration.ZERO,
                )
            coEvery { repo.findLastRecordingPerCamera(any()) } returnsMany
                listOf(
                    listOf(LastRecordingPerCameraDto("cam1", now.minusSeconds(10))),
                    listOf(LastRecordingPerCameraDto("cam1", now.minusSeconds(600))),
                )

            val task = SignalLossMonitorTask(props, repo, notify, fixedClock)
            task.init()
            task.tick()
            val firstSnapshot = task.snapshotStates()
            assertThat(firstSnapshot["cam1"]).isInstanceOf(CameraSignalState.Healthy::class.java)

            task.tick()
            // The first snapshot must NOT observe the second-tick transition to SignalLost.
            assertThat(firstSnapshot["cam1"]).isInstanceOf(CameraSignalState.Healthy::class.java)
            // But a fresh snapshot reflects the new state.
            assertThat(task.snapshotStates()["cam1"]).isInstanceOf(CameraSignalState.SignalLost::class.java)
        }
    }
}
