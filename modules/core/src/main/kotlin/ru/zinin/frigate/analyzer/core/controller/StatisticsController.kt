package ru.zinin.frigate.analyzer.core.controller

import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.zinin.frigate.analyzer.core.loadbalancer.DetectServerLoadBalancer
import ru.zinin.frigate.analyzer.model.response.CameraStatistics
import ru.zinin.frigate.analyzer.model.response.RecordingsStatistics
import ru.zinin.frigate.analyzer.model.response.StatisticsResponse
import ru.zinin.frigate.analyzer.service.repository.RecordingEntityRepository

private val logger = KotlinLogging.logger {}

@Tag(name = "StatisticsController", description = "API for retrieving system statistics")
@RequestMapping("/statistics")
@RestController
class StatisticsController(
    private val recordingRepository: RecordingEntityRepository,
    private val detectServerLoadBalancer: DetectServerLoadBalancer,
) {
    @Operation(summary = "Get system statistics", method = "GET")
    @ApiResponse(
        responseCode = "200",
        content = [Content(schema = Schema(implementation = StatisticsResponse::class))],
        description = "Statistics for recordings and detect servers",
    )
    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun getStatistics(): StatisticsResponse {
        logger.debug { "Fetching statistics" }

        // Fetch recording statistics
        val total = recordingRepository.countAll()
        val processed = recordingRepository.countProcessed()
        val unprocessed = recordingRepository.countUnprocessed()
        val camerasStats = recordingRepository.getStatisticsByCameras()
        val processingRate = recordingRepository.getProcessingRatePerMinuteLast5Minutes()

        val recordingsStatistics =
            RecordingsStatistics(
                total = total,
                processed = processed,
                unprocessed = unprocessed,
                byCameras =
                    camerasStats.map { dto ->
                        CameraStatistics(
                            camId = dto.camId,
                            recordingsCount = dto.recordingsCount,
                            recordingsProcessed = dto.recordingsProcessed,
                            detectionsCount = dto.detectionsCount,
                        )
                    },
                processingRatePerMinute = processingRate,
            )

        // Fetch detect server statistics
        val serverStatistics = detectServerLoadBalancer.getAllServersStatistics()

        val response =
            StatisticsResponse(
                recordings = recordingsStatistics,
                detectServers = serverStatistics,
            )

        logger.debug {
            "Statistics: total=$total, processed=$processed, unprocessed=$unprocessed, rate=$processingRate rec/min, servers=${serverStatistics.size}"
        }

        return response
    }
}
