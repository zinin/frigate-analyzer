package ru.zinin.frigate.analyzer.service.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import ru.zinin.frigate.analyzer.model.request.ExtractFramesRequest
import ru.zinin.frigate.analyzer.service.VideoService
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

@Service
class VideoServiceImpl : VideoService {
    override fun extractFramesLocal(request: ExtractFramesRequest): List<Path> {
        val workDir = request.tempFolder.resolve(request.recording.id.toString())
        Files.createDirectories(workDir)

        val outputPattern = workDir.resolve("frame_%03d.jpg").toString()

        val command =
            listOf(
                request.ffmpegPath,
                "-hide_banner",
                "-i",
                request.recording.filePath,
                "-vf",
                "select='eq(n,0)+(gt(scene,${request.threshold})*gte(t-prev_selected_t,1))+(gte(t,4)*eq(prev_selected_n,0))',showinfo",
                "-fps_mode",
                "vfr",
                "-q:v",
                "2",
                outputPattern,
            )

        val process =
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

        if (logger.isTraceEnabled()) {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach {
                    logger.trace { "ffmpeg: $it" }
                }
            }
        }

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            logger.error { "ffmpeg return exit code: $exitCode for recording: ${request.recording}" }
            return emptyList()
        }

        return Files.list(workDir).sorted().toList()
    }
}
