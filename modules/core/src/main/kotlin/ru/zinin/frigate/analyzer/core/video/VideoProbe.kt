package ru.zinin.frigate.analyzer.core.video

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.config.properties.ApplicationProperties
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.nio.file.Path
import java.time.Duration

private val logger = KotlinLogging.logger {}

/** Reads duration, frame size, frame rate and audio presence of a video file through ffprobe. */
@Component
class VideoProbe(
    private val applicationProperties: ApplicationProperties,
    private val processRunner: FfmpegProcessRunner,
    private val objectMapper: ObjectMapper,
) {
    /**
     * @throws RuntimeException when ffprobe fails, prints unreadable JSON, or reports no video
     *   stream, frame size or duration. Deliberately a plain RuntimeException: Quick Export maps
     *   IllegalStateException to "recording files unavailable", which would be misleading here.
     */
    suspend fun probe(path: Path): VideoInfo {
        val output = processRunner.run(buildCommand(path), PROBE_TIMEOUT)
        return parse(output.joinToString("\n"), path)
    }

    internal fun buildCommand(path: Path): List<String> =
        listOf(
            applicationProperties.ffprobePath.toString(),
            "-v",
            "error",
            "-show_entries",
            "stream=codec_type,width,height,avg_frame_rate,r_frame_rate:format=duration",
            "-of",
            "json",
            path.toString(),
        )

    internal fun parse(
        json: String,
        path: Path,
    ): VideoInfo {
        val root =
            try {
                objectMapper.readTree(json)
            } catch (e: Exception) {
                throw RuntimeException("ffprobe returned unreadable JSON for $path", e)
            }
        val streams = root.path("streams").childNodes()
        val video =
            streams.firstOrNull { it.path("codec_type").textOrNull() == "video" }
                ?: throw RuntimeException("ffprobe found no video stream in $path")
        val hasAudio = streams.any { it.path("codec_type").textOrNull() == "audio" }
        val width =
            video.path("width").intOrNull()?.takeIf { it > 0 }
                ?: throw RuntimeException("ffprobe reported no width for $path")
        val height =
            video.path("height").intOrNull()?.takeIf { it > 0 }
                ?: throw RuntimeException("ffprobe reported no height for $path")
        val duration =
            root
                .path("format")
                .path("duration")
                .textOrNull()
                ?.toDoubleOrNull()
                ?.takeIf { it > 0 }
                ?: throw RuntimeException("ffprobe reported no duration for $path")
        val fps =
            parseFrameRate(video.path("avg_frame_rate").textOrNull())
                ?: parseFrameRate(video.path("r_frame_rate").textOrNull())
                ?: DEFAULT_FPS.also { logger.warn { "ffprobe reported no usable frame rate for $path, assuming $it fps" } }
        return VideoInfo(durationSeconds = duration, width = width, height = height, fps = fps, hasAudio = hasAudio)
    }

    /** Parses an ffprobe rational such as `25/2` or `12.5`; null when absent, zero or malformed. */
    internal fun parseFrameRate(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split("/")
        val numerator = parts[0].trim().toDoubleOrNull() ?: return null
        val denominator =
            if (parts.size > 1) {
                parts[1].trim().toDoubleOrNull() ?: return null
            } else {
                1.0
            }
        if (numerator <= 0 || denominator <= 0) return null
        return numerator / denominator
    }

    private fun JsonNode.childNodes(): List<JsonNode> = (0 until size()).map { get(it) }

    private fun JsonNode.textOrNull(): String? = if (isString) asString() else null

    private fun JsonNode.intOrNull(): Int? = if (isIntegralNumber) intValue() else null

    companion object {
        val PROBE_TIMEOUT: Duration = Duration.ofSeconds(30)
        const val DEFAULT_FPS = 25.0
    }
}
