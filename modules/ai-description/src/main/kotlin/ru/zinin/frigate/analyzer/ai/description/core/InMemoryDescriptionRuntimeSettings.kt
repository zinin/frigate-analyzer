package ru.zinin.frigate.analyzer.ai.description.core

import io.github.oshai.kotlinlogging.KotlinLogging
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRuntimeSettings
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/** Дефолт на случай отсутствия реализации из `core`: выбор живёт до перезапуска процесса. */
class InMemoryDescriptionRuntimeSettings : DescriptionRuntimeSettings {
    private val presetId = AtomicReference<String?>(null)
    private val enabled = AtomicBoolean(true)

    override val sourceName = "in-memory settings"

    init {
        // Строка при создании: без неё in-memory-дефолт может незаметно оказаться в проде (бин
        // `core` не зарегистрировался, опечатка в пакете после рефакторинга) — приложение стартует,
        // `/ai` работает, а выбор владельца молча пропадает на каждом рестарте.
        logger.info { "Description runtime settings: in-memory (the choice does not survive a restart)" }
    }

    override suspend fun activePresetId(): String? = presetId.get()

    override suspend fun setActivePresetId(
        id: String,
        changedBy: String?,
    ) {
        presetId.set(id)
    }

    override suspend fun descriptionsEnabled(): Boolean = enabled.get()

    override suspend fun setDescriptionsEnabled(
        value: Boolean,
        changedBy: String?,
    ) {
        enabled.set(value)
    }
}
