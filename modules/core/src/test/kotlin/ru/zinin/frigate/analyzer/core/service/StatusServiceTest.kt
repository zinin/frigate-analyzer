package ru.zinin.frigate.analyzer.core.service

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import ru.zinin.frigate.analyzer.core.loadbalancer.DetectServerLoadBalancer
import ru.zinin.frigate.analyzer.core.task.CameraSignalState
import ru.zinin.frigate.analyzer.core.task.SignalLossMonitorTask
import ru.zinin.frigate.analyzer.model.dto.CameraState
import ru.zinin.frigate.analyzer.model.dto.CameraStatisticsDto
import ru.zinin.frigate.analyzer.model.response.DetectServerStatistics
import ru.zinin.frigate.analyzer.model.response.ServerLoad
import ru.zinin.frigate.analyzer.model.response.ServerStatus
import ru.zinin.frigate.analyzer.service.repository.RecordingEntityRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class StatusServiceTest {
    private val now = Instant.parse("2026-04-25T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private val recordings =
        mockk<RecordingEntityRepository>().apply {
            coEvery { countAll() } returns 100L
            coEvery { countProcessed() } returns 90L
            coEvery { countUnprocessed() } returns 10L
            coEvery { getStatisticsByCameras() } returns
                listOf(
                    CameraStatisticsDto("cam1", 50L, 50L, 5L),
                    CameraStatisticsDto("cam2", 50L, 40L, 3L),
                )
            coEvery { getProcessingRatePerMinuteLast5Minutes() } returns 2.5
        }

    private val lb =
        mockk<DetectServerLoadBalancer>().apply {
            every { getAllServersStatistics() } returns
                listOf(
                    DetectServerStatistics(
                        id = "srv-b",
                        status = ServerStatus.ALIVE,
                        frameRequests = ServerLoad(1, 4),
                        frameExtractionRequests = ServerLoad(0, 2),
                        visualizeRequests = ServerLoad(0, 1),
                        videoVisualizeRequests = ServerLoad(0, 1),
                    ),
                    DetectServerStatistics(
                        id = "srv-a",
                        status = ServerStatus.DEAD,
                        frameRequests = ServerLoad(0, 4),
                        frameExtractionRequests = ServerLoad(0, 2),
                        visualizeRequests = ServerLoad(0, 1),
                        videoVisualizeRequests = ServerLoad(0, 1),
                    ),
                )
        }

    private fun monitorProvider(monitor: SignalLossMonitorTask?): ObjectProvider<SignalLossMonitorTask> =
        mockk<ObjectProvider<SignalLossMonitorTask>>().apply {
            every { ifAvailable } returns monitor
        }

    @Test
    fun `collect returns monitoringEnabled=false when monitor bean absent`() =
        runBlocking {
            val service = StatusService(recordings, lb, monitorProvider(null), clock)
            val resp = service.collect()
            assertThat(resp.cameras.monitoringEnabled).isFalse()
            assertThat(resp.cameras.items).isEmpty()
            Unit
        }

    @Test
    fun `collect returns monitoringEnabled=true with empty items when snapshot empty`() =
        runBlocking {
            val monitor =
                mockk<SignalLossMonitorTask>().apply {
                    every { snapshotStates() } returns emptyMap()
                }
            val service = StatusService(recordings, lb, monitorProvider(monitor), clock)
            val resp = service.collect()
            assertThat(resp.cameras.monitoringEnabled).isTrue()
            assertThat(resp.cameras.items).isEmpty()
            Unit
        }

    @Test
    fun `collect maps Healthy and SignalLost to HEALTHY and OFFLINE`() =
        runBlocking {
            val monitor =
                mockk<SignalLossMonitorTask>().apply {
                    every { snapshotStates() } returns
                        mapOf(
                            "cam1" to CameraSignalState.Healthy(now.minusSeconds(5)),
                            "cam2" to CameraSignalState.SignalLost(now.minusSeconds(600), notificationSent = true),
                        )
                }
            val service = StatusService(recordings, lb, monitorProvider(monitor), clock)

            val resp = service.collect()

            assertThat(resp.cameras.monitoringEnabled).isTrue()
            assertThat(resp.cameras.items.map { it.camId }).containsExactly("cam2", "cam1")
            val cam2 = resp.cameras.items[0]
            assertThat(cam2.state).isEqualTo(CameraState.OFFLINE)
            assertThat(cam2.offlineFor).isEqualTo(Duration.ofSeconds(600))
            val cam1 = resp.cameras.items[1]
            assertThat(cam1.state).isEqualTo(CameraState.HEALTHY)
            assertThat(cam1.offlineFor).isNull()
            Unit
        }

    @Test
    fun `collect sorts detect servers DEAD first then alphabetical`() =
        runBlocking {
            val service = StatusService(recordings, lb, monitorProvider(null), clock)
            val resp = service.collect()
            assertThat(resp.detectServers.map { it.id }).containsExactly("srv-a", "srv-b")
            Unit
        }

    @Test
    fun `collect populates recordings counters and rate`() =
        runBlocking {
            val service = StatusService(recordings, lb, monitorProvider(null), clock)
            val resp = service.collect()
            assertThat(resp.recordings.total).isEqualTo(100L)
            assertThat(resp.recordings.processed).isEqualTo(90L)
            assertThat(resp.recordings.unprocessed).isEqualTo(10L)
            assertThat(resp.recordings.processingRatePerMinute).isEqualTo(2.5)
            assertThat(resp.recordings.byCameras.map { it.camId }).containsExactly("cam1", "cam2")
            Unit
        }
}
