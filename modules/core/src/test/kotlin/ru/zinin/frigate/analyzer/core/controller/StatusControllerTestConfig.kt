package ru.zinin.frigate.analyzer.core.controller

import io.mockk.coEvery
import io.mockk.mockk
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import ru.zinin.frigate.analyzer.core.service.StatusService
import ru.zinin.frigate.analyzer.model.dto.CameraState
import ru.zinin.frigate.analyzer.model.dto.CameraStatusDto
import ru.zinin.frigate.analyzer.model.response.CamerasSection
import ru.zinin.frigate.analyzer.model.response.RecordingsStatistics
import ru.zinin.frigate.analyzer.model.response.StatusResponse
import java.time.Duration
import java.time.Instant

/**
 * Mocks [StatusService] (not `SignalLossMonitorTask`) so the controller returns a fixed
 * snapshot with concrete `Instant`/`Duration` values — letting wire-format assertions
 * verify the actual JSON output of Spring Boot 4's WebFlux Jackson codec stack.
 *
 * `StatusService` has no `@Scheduled` / `@PostConstruct` methods, so the mockk proxy
 * survives Spring lifecycle processing. Mocking `SignalLossMonitorTask` directly does
 * not work — `ScheduledAnnotationBeanPostProcessor` walks the bean's declared methods
 * and fails on the mockk-generated `tick()` proxy that lacks a real implementation.
 */
@TestConfiguration
class StatusControllerTestConfig {
    @Bean
    @Primary
    fun statusService(): StatusService {
        val mock = mockk<StatusService>()
        coEvery { mock.collect() } returns
            StatusResponse(
                recordings =
                    RecordingsStatistics(
                        total = 0L,
                        processed = 0L,
                        unprocessed = 0L,
                        byCameras = emptyList(),
                        processingRatePerMinute = 0.0,
                    ),
                cameras =
                    CamerasSection(
                        monitoringEnabled = true,
                        items =
                            listOf(
                                CameraStatusDto(
                                    camId = "cam-wire-test",
                                    state = CameraState.OFFLINE,
                                    lastSeenAt = Instant.parse("2026-04-25T10:00:00Z"),
                                    offlineFor = Duration.ofMinutes(7),
                                ),
                            ),
                    ),
                detectServers = emptyList(),
            )
        return mock
    }
}
