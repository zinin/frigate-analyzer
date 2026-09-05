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
        framePaths: List<Path>,
    ): String {
        require(framePaths.size == request.frames.size) {
            "framePaths size (${framePaths.size}) must match request.frames size (${request.frames.size})"
        }
        val sortedPairs = request.frames.sortedBy { it.frameIndex }.zip(framePaths)
        return buildString {
            appendLine(request.instructions.preamble.trimEnd())
            appendLine()
            appendLine("Frames (in chronological order):")
            sortedPairs.forEach { (frame, path) ->
                appendLine("- Frame ${frame.frameIndex}: @${path.toAbsolutePath().normalize()}")
            }
            appendLine()
            append(request.instructions.epilogue.trimEnd())
        }
    }
}
