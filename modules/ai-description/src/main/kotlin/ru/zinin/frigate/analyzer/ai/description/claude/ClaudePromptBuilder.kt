package ru.zinin.frigate.analyzer.ai.description.claude

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import java.nio.file.Path

@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class ClaudePromptBuilder {
    fun build(
        request: DescriptionRequest,
        framePaths: List<Path>,
    ): String {
        require(framePaths.size == request.frames.size) {
            "framePaths size (${framePaths.size}) must match request.frames size (${request.frames.size})"
        }
        val languageName = languageNameFor(request.language)
        // Сначала сортируем frames по frameIndex, потом zip со stagedPaths.
        // stager уже возвращает пути в отсортированном порядке, но request.frames
        // приходит из ConcurrentHashMap.values() без гарантий порядка.
        val sortedFrames = request.frames.sortedBy { it.frameIndex }
        val sortedPairs = sortedFrames.zip(framePaths)

        val framesBlock =
            sortedPairs
                .joinToString("\n") { (frame, path) ->
                    "- Frame ${frame.frameIndex}: @${path.toAbsolutePath().normalize()}"
                }

        return buildString {
            appendLine(
                "You are analyzing surveillance camera frames captured during an object detection event.",
            )
            appendLine("Write both descriptions in $languageName.")
            appendLine()
            appendLine("Frames (in chronological order):")
            appendLine(framesBlock)
            appendLine()
            appendLine("Return ONLY this JSON object (no prose around it):")
            appendLine("""{"short": "...", "detailed": "..."}""")
            appendLine()
            appendLine("Rules:")
            appendLine("- \"short\" must not exceed ${request.shortMaxLength} characters.")
            appendLine("- \"detailed\" must not exceed ${request.detailedMaxLength} characters.")
            appendLine("- No markdown, no explanations — just the JSON object.")
        }
    }

    private fun languageNameFor(code: String): String =
        when (code.lowercase()) {
            "ru" -> "Russian"

            "en" -> "English"

            // @Pattern на property-уровне уже отсеивает неверные коды.
            // Если сюда пришло что-то другое — это баг конфига/валидации.
            else -> error("Unsupported language code: '$code' (expected 'ru' or 'en')")
        }
}
