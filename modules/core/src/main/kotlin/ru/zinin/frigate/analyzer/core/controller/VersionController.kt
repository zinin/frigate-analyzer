package ru.zinin.frigate.analyzer.core.controller

import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.boot.info.BuildProperties
import org.springframework.boot.info.GitProperties
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val logger = KotlinLogging.logger {}

@Tag(name = "VersionController", description = "API for retrieving application version")
@RequestMapping("/version")
@RestController
class VersionController(
    val buildProperties: BuildProperties,
    val gitProperties: GitProperties,
) {
    @Operation(summary = "Get service version", method = "GET")
    @ApiResponse(
        responseCode = "200",
        content = [Content(schema = Schema(implementation = String::class))],
        description = "Response with the service version",
    )
    @GetMapping(produces = [MediaType.TEXT_PLAIN_VALUE])
    suspend fun version(): String {
        val sb = StringBuilder()

        withContext(Dispatchers.IO) {
            sb.append("Git version: ").append(gitProperties.commitId).append("\r\n")
            sb.append("Git commit time: ").append(gitProperties.commitTime).append("\r\n")
            sb.append("Build version: ").append(buildProperties.version).append("\r\n")
            sb.append("Build time: ").append(buildProperties.time).append("\r\n")
        }

        logger.info { sb.toString() }

        return sb.toString()
    }
}
