package ru.zinin.frigate.analyzer.ai.description.claude

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.core.VisionRequest
import java.nio.file.Path

@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class ClaudePromptBuilder {
    fun build(
        request: VisionRequest,
        imagePaths: List<Path>,
    ): String {
        require(imagePaths.size == request.images.size) {
            "imagePaths size (${imagePaths.size}) must match request.images size (${request.images.size})"
        }
        return buildString {
            appendLine(request.instructions.preamble.trimEnd())
            appendLine()
            appendLine(request.instructions.imagesHeader)
            request.images.zip(imagePaths).forEach { (image, path) ->
                appendLine("- ${image.caption}: @${path.toAbsolutePath().normalize()}")
            }
            appendLine()
            append(request.instructions.epilogue.trimEnd())
        }
    }
}
