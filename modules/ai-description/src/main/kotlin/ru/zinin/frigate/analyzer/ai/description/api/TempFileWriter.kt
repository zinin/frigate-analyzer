package ru.zinin.frigate.analyzer.ai.description.api

import java.nio.file.Path

/**
 * SPI — реализуется в core-модуле адаптером над TempFileHelper.
 * Живёт в api/ пакете: это модульный контракт, не claude-специфика.
 * Будущие провайдеры (OpenAI/Gemini) используют ту же абстракцию.
 */
interface TempFileWriter {
    suspend fun createTempFile(
        prefix: String,
        suffix: String,
        content: ByteArray,
    ): Path

    suspend fun deleteFiles(files: List<Path>): Int
}
