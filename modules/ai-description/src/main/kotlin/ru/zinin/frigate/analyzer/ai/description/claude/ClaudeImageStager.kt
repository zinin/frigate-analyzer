package ru.zinin.frigate.analyzer.ai.description.claude

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.TempFileWriter
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class ClaudeImageStager(
    private val tempWriter: TempFileWriter,
) {
    suspend fun stage(request: DescriptionRequest): List<Path> {
        val sorted = request.frames.sortedBy { it.frameIndex }
        val staged = mutableListOf<Path>()
        try {
            for (frame in sorted) {
                val prefix = "claude-${request.recordingId}-frame-${frame.frameIndex}"
                val path = tempWriter.createTempFile(prefix, ".jpg", frame.bytes)
                staged.add(path)
            }
            return staged
        } catch (e: Exception) {
            logger.warn(e) { "Failed to stage frames for ${request.recordingId}; cleaning up partial set" }
            // NonCancellable — stage может упасть при TimeoutCancellationException,
            // а suspend-вызов в отменённой корутине сразу бросит CancellationException.
            withContext(NonCancellable) { runCatching { tempWriter.deleteFiles(staged) } }
            throw e
        }
    }

    suspend fun cleanup(paths: List<Path>) {
        if (paths.isEmpty()) return
        // NonCancellable обязателен: cleanup() вызывается из finally в describe(),
        // куда выполнение часто попадает через TimeoutCancellationException.
        // Без этого suspend-вызов в отменённой корутине немедленно бросит
        // CancellationException, runCatching его проглотит, файлы останутся.
        withContext(NonCancellable) {
            runCatching { tempWriter.deleteFiles(paths) }
                .onFailure { logger.warn(it) { "Failed to delete staged Claude frames" } }
        }
    }
}
