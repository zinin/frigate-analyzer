package ru.zinin.frigate.analyzer.ai.description.core

import java.util.UUID

/**
 * Инструкции одной vision-задачи. Провайдер печатает [preamble], затем [imagesHeader], затем картинки
 * своим способом (Claude — ссылками `@path`, Grok — inline-блоками), каждую с подписью задачи, и в
 * конце [epilogue]. О задаче провайдер ничего не знает.
 */
data class VisionInstructions(
    val systemPrompt: String,
    val preamble: String,
    /** Строка перед картинками. Формулирует задача, провайдер печатает как есть. */
    val imagesHeader: String,
    val epilogue: String,
    /** JSON Schema ответа для провайдеров со structured output; null = только текстом в epilogue. */
    val jsonSchema: String?,
)

/**
 * Одна картинка vision-вызова: байты и подпись, которой задача её представляет. Порядок картинок в
 * [VisionRequest.images] — порядок, в котором их увидит модель; провайдер его не меняет.
 */
data class VisionImage(
    val bytes: ByteArray,
    val caption: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VisionImage) return false
        return caption == other.caption && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * caption.hashCode() + bytes.contentHashCode()

    override fun toString(): String = "VisionImage(caption=$caption, bytes=${bytes.size}B)"
}

data class VisionRequest(
    /** Id записи: имена временных файлов и строки логов. */
    val requestId: UUID,
    val images: List<VisionImage>,
    val instructions: VisionInstructions,
)
