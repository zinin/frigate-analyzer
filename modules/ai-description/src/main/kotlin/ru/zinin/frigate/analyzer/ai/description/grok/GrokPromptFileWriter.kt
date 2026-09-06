package ru.zinin.frigate.analyzer.ai.description.grok

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.TempFileWriter
import ru.zinin.frigate.analyzer.ai.description.core.VisionRequest
import tools.jackson.databind.ObjectMapper
import java.nio.file.Path
import java.util.Base64

private val logger = KotlinLogging.logger {}

/**
 * Пишет `prompt.json` для `grok --prompt-file`: массив ACP content blocks. Суффикс `.json`
 * обязателен, любое другое расширение Grok читает как обычный текст. Base64 кадров в логи не
 * попадает, только размер файла.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class GrokPromptFileWriter(
    private val tempFileWriter: TempFileWriter,
    private val objectMapper: ObjectMapper,
) {
    suspend fun write(request: VisionRequest): Path {
        val bytes = objectMapper.writeValueAsBytes(buildBlocks(request))
        val path = tempFileWriter.createTempFile("grok-${request.requestId}", ".json", bytes)
        logger.debug { "Grok prompt file $path: ${bytes.size} bytes, ${request.frames.size} frames" }
        return path
    }

    internal fun buildBlocks(request: VisionRequest): List<Map<String, String>> {
        val encoder = Base64.getEncoder()
        return buildList {
            add(text(request.instructions.preamble.trimEnd() + "\n\nFrames (in chronological order):"))
            request.frames.sortedBy { it.frameIndex }.forEach { frame ->
                add(text("Frame ${frame.frameIndex}:"))
                add(mapOf("type" to "image", "mimeType" to "image/jpeg", "data" to encoder.encodeToString(frame.bytes)))
            }
            add(text(request.instructions.epilogue.trimEnd()))
        }
    }

    /**
     * NonCancellable обязателен: вызывается из finally в GrokBackend.complete, куда выполнение
     * часто попадает через TimeoutCancellationException.
     */
    suspend fun delete(path: Path) {
        withContext(NonCancellable) {
            runCatching { tempFileWriter.deleteFiles(listOf(path)) }
                .onFailure { logger.warn(it) { "Failed to delete Grok prompt file $path" } }
        }
    }

    private fun text(value: String): Map<String, String> = mapOf("type" to "text", "text" to value)
}
