package ru.zinin.frigate.analyzer.ai.description.config

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionAgent
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRuntimeSettings
import ru.zinin.frigate.analyzer.ai.description.core.ActivePresetResolver
import ru.zinin.frigate.analyzer.ai.description.core.DefaultDescriptionAgent
import ru.zinin.frigate.analyzer.ai.description.core.DescriptionBackendFactory
import ru.zinin.frigate.analyzer.ai.description.core.DescriptionPresetCatalog
import ru.zinin.frigate.analyzer.ai.description.core.DescriptionPresetCatalogBuilder
import ru.zinin.frigate.analyzer.ai.description.core.InMemoryDescriptionRuntimeSettings
import ru.zinin.frigate.analyzer.ai.description.core.ProviderAuthTracker

private val logger = KotlinLogging.logger {}

@AutoConfiguration
@ComponentScan("ru.zinin.frigate.analyzer.ai.description")
@EnableConfigurationProperties(DescriptionProperties::class, ClaudeProperties::class, GrokProperties::class)
open class AiDescriptionAutoConfiguration {
    /**
     * Все бины фичи живут здесь, под ОДНИМ условием, и связаны обычными зависимостями — поэтому
     * порядок объявления `@Bean`-методов ни на что не влияет.
     *
     * Почему не `@ConditionalOnBean(DescriptionPresetCatalog::class)` на соседних методах:
     * прежний `@ConditionalOnBean(DescriptionBackend::class)` был надёжен потому, что backend
     * приходил из `@ComponentScan` — из другой фазы, гарантированно раньше. Для sibling-`@Bean`
     * того же класса такой гарантии нет: Spring Boot не обещает, что он виден `OnBeanCondition`,
     * а порядок методов в байткоде Kotlin может разойтись с порядком в файле. Цена ошибки
     * несимметрична — каталог есть, агента нет, `/ai` рисует пресеты, а описания молча никогда
     * не вызываются.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
    @Conditional(DescriptionPresetsDeclaredCondition::class)
    open class PresetBeans {
        @Bean
        fun descriptionPresetCatalog(
            descriptionProperties: DescriptionProperties,
            claudeProperties: ClaudeProperties,
            grokProperties: GrokProperties,
            factories: ObjectProvider<DescriptionBackendFactory>,
        ): DescriptionPresetCatalog {
            val result =
                DescriptionPresetCatalogBuilder.build(
                    presets = declaredPresets(descriptionProperties, claudeProperties, grokProperties),
                    defaultPreset = descriptionProperties.defaultPreset,
                    // ObjectProvider, а не List<…>: при нуле кандидатов Spring бросает
                    // NoSuchBeanDefinitionException вместо подстановки пустого списка.
                    factories = factories.orderedStream().toList(),
                    timeout = descriptionProperties.common.timeout,
                )
            // NoPresets здесь недостижим: условие бина спрашивает о том же самом у того же
            // DescriptionPresetDeclarations. Если он всё-таки пришёл — условие и билдер разошлись,
            // и старт обязан упасть: молча остаться без описаний хуже.
            return when (result) {
                is DescriptionPresetCatalogBuilder.Result.Catalog -> result.catalog
                is DescriptionPresetCatalogBuilder.Result.NoneUsable -> error(result.message)
                DescriptionPresetCatalogBuilder.Result.NoPresets -> error("no description preset resolved though the condition matched")
            }
        }

        /**
         * Дефолт, уступающий реализации из `core`: там выбор владельца ложится в `app_settings` и
         * переживает рестарт. Условие именно `@ConditionalOnMissingBean` — автоконфигурация
         * обрабатывается после пользовательских бинов, поэтому в проде побеждает `core`.
         */
        @Bean
        @ConditionalOnMissingBean(DescriptionRuntimeSettings::class)
        fun inMemoryDescriptionRuntimeSettings(): DescriptionRuntimeSettings = InMemoryDescriptionRuntimeSettings()

        /**
         * Резолвер, а не каталог: он же отдаёт `telegram` реализацию `ActiveDescriptionPreset` —
         * бин виден и по конкретному типу, и по интерфейсу из `api`.
         */
        @Bean
        fun activePresetResolver(
            catalog: DescriptionPresetCatalog,
            runtimeSettings: DescriptionRuntimeSettings,
        ): ActivePresetResolver = ActivePresetResolver(catalog, runtimeSettings)

        /**
         * Виден и по конкретному типу — агенту, — и по `ProviderAuthStates`: экран `/ai` читает
         * состояние авторизации через интерфейс из `api`.
         */
        @Bean
        fun providerAuthTracker(eventPublisher: ApplicationEventPublisher): ProviderAuthTracker = ProviderAuthTracker(eventPublisher)

        @Bean
        fun descriptionAgent(
            resolver: ActivePresetResolver,
            authTracker: ProviderAuthTracker,
            descriptionProperties: DescriptionProperties,
        ): DescriptionAgent = DefaultDescriptionAgent(resolver, authTracker, descriptionProperties)

        /**
         * Пустая карта означает деплой, настроенный старым способом: один пресет из `provider` и
         * секции этого провайдера. Неизвестный `provider` даёт пустую карту — тогда сюда не
         * доходит даже условие. Значение нормализуется тем же кодом, что и в условии.
         */
        private fun declaredPresets(
            descriptionProperties: DescriptionProperties,
            claudeProperties: ClaudeProperties,
            grokProperties: GrokProperties,
        ): Map<String, DescriptionProperties.Preset> {
            if (descriptionProperties.presets.isNotEmpty()) {
                // yaml-дефолт `claude` биндится всегда, даже если оператор переменную не ставил.
                // WARN только на leftover, который не этот дефолт: опечатка остаётся видимой,
                // а штатный деплой с картой пресетов не учит игнорировать WARN.
                val leftover = DescriptionPresetDeclarations.normalize(descriptionProperties.provider)
                if (leftover.isNotEmpty() && leftover != YAML_DEFAULT_PROVIDER) {
                    logger.warn {
                        "${DescriptionPresetDeclarations.PROVIDER_PROPERTY}='${descriptionProperties.provider}' " +
                            "ignored: presets are declared"
                    }
                }
                return descriptionProperties.presets
            }
            return when (DescriptionPresetDeclarations.normalize(descriptionProperties.provider)) {
                "claude" -> mapOf("claude" to legacyClaudePreset(claudeProperties))
                "grok" -> mapOf("grok" to legacyGrokPreset(grokProperties))
                else -> emptyMap()
            }
        }

        private fun legacyClaudePreset(claudeProperties: ClaudeProperties): DescriptionProperties.Preset =
            DescriptionProperties.Preset(provider = "claude", model = claudeProperties.model)

        private fun legacyGrokPreset(grokProperties: GrokProperties): DescriptionProperties.Preset =
            DescriptionProperties.Preset(
                provider = "grok",
                model = grokProperties.model,
                effort = grokProperties.effort,
            )

        private companion object {
            /** Совпадает с дефолтом `application.ai.description.provider` в application.yaml. */
            const val YAML_DEFAULT_PROVIDER = "claude"
        }
    }
}
