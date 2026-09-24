package ru.zinin.frigate.analyzer.ai.description.claude

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.TempFileWriter
import ru.zinin.frigate.analyzer.ai.description.core.VisionRequest
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class ClaudeImageStager(
    private val tempWriter: TempFileWriter,
) {
    /**
     * Пишет картинки запроса во временные файлы через [TempFileWriter] в том порядке, в каком их
     * выложила задача, и возвращает пути в том же порядке: `ClaudePromptBuilder` сшивает подписи с
     * путями по позиции.
     */
    suspend fun stage(request: VisionRequest): List<Path> {
        val staged = mutableListOf<Path>()
        try {
            request.images.forEachIndexed { index, image ->
                val prefix = "claude-${request.requestId}-image-$index"
                staged.add(tempWriter.createTempFile(prefix, ".jpg", image.bytes))
            }
            return staged
        } catch (e: Exception) {
            logger.warn(e) { "Failed to stage images for ${request.requestId}; cleaning up partial set" }
            // NonCancellable — stage может упасть при TimeoutCancellationException,
            // а suspend-вызов в отменённой корутине сразу бросит CancellationException.
            withContext(NonCancellable) {
                runCatching { tempWriter.deleteFiles(staged) }
                    .exceptionOrNull()
                    ?.let { e.addSuppressed(it) }
            }
            throw e
        }
    }

    suspend fun cleanup(paths: List<Path>) {
        if (paths.isEmpty()) return
        // NonCancellable обязателен: cleanup() вызывается из finally в complete(),
        // куда выполнение часто попадает через TimeoutCancellationException.
        // Без этого suspend-вызов в отменённой корутине немедленно бросит
        // CancellationException, runCatching его проглотит, файлы останутся.
        withContext(NonCancellable) {
            runCatching { tempWriter.deleteFiles(paths) }
                .onFailure { logger.warn(it) { "Failed to delete staged Claude images" } }
        }
    }
}
