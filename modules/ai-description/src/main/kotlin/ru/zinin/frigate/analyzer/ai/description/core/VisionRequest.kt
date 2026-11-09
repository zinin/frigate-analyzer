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
 * Одна картинка vision-вызова: байты, подпись, которой задача её представляет, и роль. Порядок картинок
 * в [VisionRequest.images] — порядок, в котором их увидит модель; провайдер его не меняет.
 */
data class VisionImage(
    val bytes: ByteArray,
    val caption: String,
    /**
     * По роли [VisionCallExecutor] выбирает потолок длинной стороны: у сетки он может быть свой. Модели
     * роль не печатается — что перед ней, говорит подпись.
     */
    val role: Role = Role.FRAME,
) {
    /** [FRAME] — обычный кадр события, [STORYBOARD] — раскадровка: сетка кадров с подписями времени. */
    enum class Role { FRAME, STORYBOARD }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VisionImage) return false
        return caption == other.caption && role == other.role && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * (31 * caption.hashCode() + role.hashCode()) + bytes.contentHashCode()

    override fun toString(): String = "VisionImage(caption=$caption, role=$role, bytes=${bytes.size}B)"
}

data class VisionRequest(
    /** Id записи: имена временных файлов и строки логов. */
    val requestId: UUID,
    val images: List<VisionImage>,
    val instructions: VisionInstructions,
)
