package ru.zinin.frigate.analyzer.ai.description.core

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import ru.zinin.frigate.analyzer.ai.description.api.ActiveDescriptionPreset
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionPreset
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRuntimeSettings
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/**
 * Активный пресет на каждый вызов: чтение дешёвое, потому что реализация настроек кэширует значение
 * на процесс и сбрасывает кэш только на собственной записи.
 *
 * Читает [DescriptionRuntimeSettings] fail-open: ключ `ai.description.*` — про удобство, а не про
 * безопасность, поэтому любой отказ чтения даёт предупреждение и пресет по умолчанию, но никогда не
 * исключение. Реализация поверх `app_settings` намеренно НЕ кэширует неудачные чтения, так что
 * отказ БД бил бы по каждой записи подряд, а сырое исключение R2DBC покинуло бы контракт
 * `DescriptionException`, который обещает агент.
 */
class ActivePresetResolver(
    private val catalog: DescriptionPresetCatalog,
    private val runtimeSettings: DescriptionRuntimeSettings,
) : ActiveDescriptionPreset {
    /** Последнее залогированное предупреждение: иначе каждая запись повторяла бы одну строку. */
    private val lastWarning = AtomicReference<String?>(null)

    /** Строка об источнике активного пресета печатается один раз за процесс. */
    private val sourceLogged = AtomicBoolean(false)

    /** Результат чтения: отказ и «ничего не выбрано» ведут себя одинаково, кроме INFO-строки. */
    private class StoredRead(
        val id: String?,
        val failed: Boolean,
    )

    suspend fun resolve(): DescriptionPresetCatalog.Entry {
        val read = readStoredId()
        if (read.id != null) {
            val stored = catalog.byId(read.id)
            if (stored?.backend != null) {
                logSourceOnce(stored.view, source = runtimeSettings.sourceName)
                return stored
            }
            warnOnce(
                if (stored == null) {
                    "Active description preset '${read.id}' is not configured; using '${catalog.fallbackId}'"
                } else {
                    "Active description preset '${read.id}' is unavailable " +
                        "(${stored.view.unavailableReason}); using '${catalog.fallbackId}'"
                },
            )
        }
        val fallback = catalog.fallback()
        // При отказе чтения источник неизвестен: предупреждение выше уже сказало правду, а INFO
        // «from default-preset» соврала бы про выбор владельца, который так и не был прочитан.
        if (!read.failed) logSourceOnce(fallback.view, source = DEFAULT_PRESET_SOURCE)
        return fallback
    }

    override suspend fun storedId(): String? = readStoredId().id

    override suspend fun effective(): DescriptionPreset = resolve().view

    /** Пустая строка — это «не выбрано», а не сломанный id: иначе на неё шло бы предупреждение. */
    private suspend fun readStoredId(): StoredRead =
        try {
            StoredRead(runtimeSettings.activePresetId()?.takeIf { it.isNotBlank() }, failed = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warnOnce("Cannot read the active description preset; using '${catalog.fallbackId}': ${e.message}")
            StoredRead(null, failed = true)
        }

    /**
     * Источник активного пресета — на INFO и один раз, лениво. Строка обязана назвать источник:
     * `default-preset` после первого выбора владельца перестаёт действовать, и оператор, поправивший
     * его в yaml и перезапустивший контейнер, иначе не получает никакого сигнала. На старте её
     * печатать нельзя: назвать источник — значит прочитать настройки, то есть сходить в БД из
     * контекста обновления Spring.
     */
    private fun logSourceOnce(
        preset: DescriptionPreset,
        source: String,
    ) {
        if (!sourceLogged.compareAndSet(false, true)) return
        val overridden =
            if (source != DEFAULT_PRESET_SOURCE && preset.id != catalog.fallbackId) {
                ", overriding default-preset='${catalog.fallbackId}'"
            } else {
                ""
            }
        logger.info { "Active description preset '${preset.id}' (${describe(preset)}) from $source$overridden" }
    }

    /** Модель именно эффективная: строка отвечает на вопрос «какая модель работает сейчас». */
    private fun describe(preset: DescriptionPreset): String =
        listOfNotNull(preset.provider, preset.effectiveModel, preset.effort.takeIf { it.isNotBlank() })
            .joinToString("/")

    private fun warnOnce(message: String) {
        if (lastWarning.getAndSet(message) != message) {
            logger.warn { message }
        }
    }

    private companion object {
        const val DEFAULT_PRESET_SOURCE = "default-preset"
    }
}
