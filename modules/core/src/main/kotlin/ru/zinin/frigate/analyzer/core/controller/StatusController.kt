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
import ru.zinin.frigate.analyzer.core.service.StatusService
import ru.zinin.frigate.analyzer.model.response.StatusResponse

private val logger = KotlinLogging.logger {}

@Tag(name = "StatusController", description = "API for retrieving system status")
@RequestMapping("/status")
@RestController
class StatusController(
    private val statusService: StatusService,
) {
    @Operation(summary = "Get system status", method = "GET")
    @ApiResponse(
        responseCode = "200",
        content = [Content(schema = Schema(implementation = StatusResponse::class))],
        description = "Recordings, cameras signal status, detect servers",
    )
    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun getStatus(): StatusResponse {
        logger.debug { "Fetching /status" }
        return statusService.collect()
    }
}
