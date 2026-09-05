package ru.zinin.frigate.analyzer.ai.description.config

import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.Environment

/**
 * Единственная точка истины о том, что объявлено. Ею пользуются и условие включения бинов фичи, и
 * автоконфигурация, поэтому разойтись им негде.
 */
object DescriptionPresetDeclarations {
    const val PRESETS_PREFIX = "application.ai.description.presets"
    const val PROVIDER_PROPERTY = "application.ai.description.provider"

    /**
     * Есть ли что класть в каталог. Читается через `Binder` — тот же механизм, которым Spring
     * биндит [DescriptionProperties], поэтому видны все источники свойств, relaxed binding из
     * окружения (`APP_AI_DESCRIPTION_PRESETS_…`), bracket-форма `presets[id]` и плейсхолдеры.
     * Сканирование имён `EnumerablePropertySource` этого не умеет: карта связалась бы, условие
     * сказало бы «пресетов нет», и получилось бы молчаливое «описания не работают».
     */
    fun anyDeclared(environment: Environment): Boolean = boundPresetKeys(environment).isNotEmpty() || legacyProvider(environment) != null

    fun boundPresetKeys(environment: Environment): Set<String> =
        Binder
            .get(environment)
            .bind(PRESETS_PREFIX, Bindable.mapOf(String::class.java, Any::class.java))
            .orElseGet(::emptyMap)
            .keys

    /**
     * Нормализованный legacy-провайдер или null, если он пуст либо неизвестен.
     *
     * `trim().lowercase()` обязателен: сегодняшний `@ConditionalOnProperty(havingValue = "claude")`
     * сравнивает без учёта регистра, поэтому работающий деплой с
     * `APP_AI_DESCRIPTION_PROVIDER=CLAUDE` активирует Claude. Регистрозависимая проверка тихо
     * оставила бы такой деплой без агента и нарушила бы обещание обратной совместимости.
     */
    fun legacyProvider(environment: Environment): String? = normalize(environment.getProperty(PROVIDER_PROPERTY, ""))

    fun normalize(raw: String): String? = raw.trim().lowercase().takeIf { it in DescriptionProperties.KNOWN_PROVIDERS }
}
