package ru.zinin.frigate.analyzer.ai.description.core

import io.github.oshai.kotlinlogging.KotlinLogging
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionPreset
import ru.zinin.frigate.analyzer.ai.description.api.UnavailableReason
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Чистая сборка каталога: без Spring, чтобы правила «пустой список — нет каталога», «ни одного
 * годного — отказ старта» и выбор пресета по умолчанию проверялись обычным unit-тестом.
 */
object DescriptionPresetCatalogBuilder {
    /** Ниже этого таймаута медленный `effort` не оставляет бюджета на повтор. */
    private val RECOMMENDED_SLOW_EFFORT_TIMEOUT: Duration = Duration.ofSeconds(120)

    /** Уровни, на которых один вызов съедает почти весь таймаут по умолчанию. */
    private val SLOW_EFFORTS = setOf("xhigh", "max")

    /**
     * Три исхода в одной сигнатуре: «пресетов не объявлено» — это не ошибка (агента просто не
     * будет, как сегодня при неизвестном `provider`), а «объявлены, но ни один не годен» — ошибка,
     * решение о которой принимает вызывающий, а не билдер.
     */
    sealed interface Result {
        data class Catalog(
            val catalog: DescriptionPresetCatalog,
        ) : Result

        data object NoPresets : Result

        data class NoneUsable(
            val message: String,
        ) : Result
    }

    /**
     * @param timeout значение `application.ai.description.common.timeout`; нужно, чтобы предупредить
     *   о пресетах, которым его не хватит.
     */
    fun build(
        presets: Map<String, DescriptionProperties.Preset>,
        defaultPreset: String,
        factories: List<DescriptionBackendFactory>,
        timeout: Duration,
    ): Result {
        if (presets.isEmpty()) return Result.NoPresets
        warnAboutSlowEfforts(presets, timeout)

        val byProvider = factories.associateBy { it.providerId }
        val availability = availabilityOf(presets, byProvider)
        val entries = presets.map { (id, preset) -> entryOf(id, preset, byProvider, availability) }
        warnAboutDisplacedModels(entries)
        val usable = entries.filter { it.backend != null }
        if (usable.isEmpty()) {
            return Result.NoneUsable(
                "No usable description preset: " +
                    entries.joinToString { "${it.view.id} (${it.view.unavailableReason})" },
            )
        }

        val fallbackId = usable.firstOrNull { it.view.id == defaultPreset }?.view?.id ?: usable.first().view.id
        if (defaultPreset.isNotBlank() && fallbackId != defaultPreset) {
            logger.warn { "default-preset '$defaultPreset' is unavailable; falling back to '$fallbackId'" }
        }
        logger.info { "Description presets: ${entries.joinToString { it.view.id }}; default '$fallbackId'" }
        return Result.Catalog(DescriptionPresetCatalog(entries, fallbackId))
    }

    /**
     * Осмотр окружения живёт внутри `availability()`, поэтому спрашивать её у провайдера, которого
     * нет ни в одном пресете, нельзя: claude-деплой иначе создавал бы каталоги Grok и получал бы
     * чужие предупреждения. Один раз на провайдер, а не на пресет — повторять осмотр незачем.
     */
    private fun availabilityOf(
        presets: Map<String, DescriptionProperties.Preset>,
        byProvider: Map<String, DescriptionBackendFactory>,
    ): Map<String, DescriptionBackendFactory.Availability> =
        presets.values
            .mapTo(LinkedHashSet()) { it.provider }
            .mapNotNull { provider -> byProvider[provider]?.let { provider to it.availability() } }
            .toMap()

    private fun entryOf(
        id: String,
        preset: DescriptionProperties.Preset,
        byProvider: Map<String, DescriptionBackendFactory>,
        availability: Map<String, DescriptionBackendFactory.Availability>,
    ): DescriptionPresetCatalog.Entry {
        val factory = byProvider[preset.provider]
        val reason =
            if (factory == null) {
                UnavailableReason.NoFactory(preset.provider)
            } else {
                // getValue, а не [], хотя ключ заведомо есть: промах означал бы расхождение с
                // availabilityOf, а тихо счесть непроверенный провайдер годным хуже, чем упасть.
                (availability.getValue(preset.provider) as? DescriptionBackendFactory.Availability.Unavailable)?.reason
            }
        val view =
            DescriptionPreset(
                id = id,
                provider = preset.provider,
                model = preset.model,
                // Вытеснить модель умеет только сам провайдер; без фабрики вытеснять некому.
                effectiveModel = factory?.effectiveModel(preset) ?: preset.model,
                effort = preset.effort,
                authScopeId = factory?.authScopeId(preset) ?: preset.provider,
                unavailableReason = reason,
            )
        if (reason != null) {
            logger.warn { "Description preset '$id' (${preset.provider}/${preset.model}) is unavailable: $reason" }
        }
        return DescriptionPresetCatalog.Entry(view, factory?.takeIf { reason == null }?.create(preset))
    }

    /**
     * Настройка провайдера может вытеснить объявленную модель (`ANTHROPIC_MODEL` у claude): два
     * пресета, отличающиеся только моделью, тогда шлют одинаковые запросы, а на экране выглядят
     * разными. Предупреждение живёт здесь, а не в фабрике: только билдер видит все пресеты сразу и
     * может перечислить задетые — фабрика получает по одному. Формулировка провайдер-нейтральна:
     * ту же пару «объявленная → эффективная» оператор видит и на экране `/ai`.
     */
    private fun warnAboutDisplacedModels(entries: List<DescriptionPresetCatalog.Entry>) {
        val displaced = entries.map { it.view }.filter { it.effectiveModel != it.model }
        if (displaced.isEmpty()) return
        logger.warn {
            "provider configuration overrides the declared model: " +
                displaced.joinToString { "preset '${it.id}' (${it.model} -> ${it.effectiveModel})" } +
                "; presets that differ only by model will issue identical requests"
        }
    }

    /**
     * `grok-4.6 xhigh` съедает ~48 с из 60 с по умолчанию: транспортный повтор (10 с бюджета плюс
     * 5 с паузы) уже не успевает начаться, а повтор после невалидного ответа гибнет от внешнего
     * таймаута — честный `InvalidResponse` превращается в обманчивый `Timeout`. Рекомендация и
     * порог намеренно называют одно число: его же README советует ставить под такие пресеты.
     */
    private fun warnAboutSlowEfforts(
        presets: Map<String, DescriptionProperties.Preset>,
        timeout: Duration,
    ) {
        if (timeout >= RECOMMENDED_SLOW_EFFORT_TIMEOUT) return
        presets
            .filterValues { it.effort in SLOW_EFFORTS }
            .forEach { (id, preset) ->
                logger.warn {
                    "preset '$id': effort=${preset.effort} with timeout=${timeout.toSeconds()}s leaves no retry " +
                        "budget; consider APP_AI_DESCRIPTION_TIMEOUT=${RECOMMENDED_SLOW_EFFORT_TIMEOUT.toSeconds()}s"
                }
            }
    }
}
