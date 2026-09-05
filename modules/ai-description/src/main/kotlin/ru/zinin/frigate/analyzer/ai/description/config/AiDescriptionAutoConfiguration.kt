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
import ru.zinin.frigate.analyzer.ai.description.api.JudgeAgent
import ru.zinin.frigate.analyzer.ai.description.api.JudgeRuntimeSettings
import ru.zinin.frigate.analyzer.ai.description.core.ActivePresetResolver
import ru.zinin.frigate.analyzer.ai.description.core.DefaultDescriptionAgent
import ru.zinin.frigate.analyzer.ai.description.core.DefaultJudgeAgent
import ru.zinin.frigate.analyzer.ai.description.core.DescriptionPresetCatalog
import ru.zinin.frigate.analyzer.ai.description.core.DescriptionPresetCatalogBuilder
import ru.zinin.frigate.analyzer.ai.description.core.DescriptionPresetResolver
import ru.zinin.frigate.analyzer.ai.description.core.DescriptionResponseParser
import ru.zinin.frigate.analyzer.ai.description.core.InMemoryDescriptionRuntimeSettings
import ru.zinin.frigate.analyzer.ai.description.core.InMemoryJudgeRuntimeSettings
import ru.zinin.frigate.analyzer.ai.description.core.JudgePresetResolver
import ru.zinin.frigate.analyzer.ai.description.core.JudgeResponseParser
import ru.zinin.frigate.analyzer.ai.description.core.ProviderAuthTracker
import ru.zinin.frigate.analyzer.ai.description.core.VisionBackendFactory
import ru.zinin.frigate.analyzer.ai.description.core.VisionCallExecutor
import ru.zinin.frigate.analyzer.ai.description.core.VisionLimits
import ru.zinin.frigate.analyzer.ai.description.ratelimit.JudgeRateLimiter
import java.time.Clock

private val logger = KotlinLogging.logger {}

@AutoConfiguration
@ComponentScan("ru.zinin.frigate.analyzer.ai.description")
@EnableConfigurationProperties(
    DescriptionProperties::class,
    ClaudeProperties::class,
    GrokProperties::class,
    JudgeProperties::class,
)
open class AiDescriptionAutoConfiguration {
    /**
     * Все бины фичи живут здесь, под ОДНИМ условием, и связаны обычными зависимостями — поэтому
     * порядок объявления `@Bean`-методов ни на что не влияет.
     *
     * Почему не `@ConditionalOnBean(DescriptionPresetCatalog::class)` на соседних методах:
     * прежний `@ConditionalOnBean(VisionBackend::class)` был надёжен потому, что backend
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
            factories: ObjectProvider<VisionBackendFactory>,
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
         * Адаптер описаний как `ActiveDescriptionPreset`. Сам [ActivePresetResolver] бином не
         * является: второй резолвер (судья) делит каталог, но не fallback, и два бина одного типа
         * ломают `ObjectProvider.getIfAvailable()` у экрана `/ai`.
         */
        @Bean
        fun descriptionPresetResolver(
            catalog: DescriptionPresetCatalog,
            runtimeSettings: DescriptionRuntimeSettings,
        ): DescriptionPresetResolver =
            DescriptionPresetResolver(ActivePresetResolver(catalog, runtimeSettings, catalog.fallbackId, label = "description"))

        /**
         * Виден и по конкретному типу — агенту, — и по `ProviderAuthStates`: экран `/ai` читает
         * состояние авторизации через интерфейс из `api`.
         */
        @Bean
        fun providerAuthTracker(eventPublisher: ApplicationEventPublisher): ProviderAuthTracker = ProviderAuthTracker(eventPublisher)

        @Bean
        fun descriptionVisionCallExecutor(
            resolver: DescriptionPresetResolver,
            authTracker: ProviderAuthTracker,
            descriptionProperties: DescriptionProperties,
        ): VisionCallExecutor {
            val common = descriptionProperties.common
            return VisionCallExecutor(
                resolver = resolver.resolver,
                authTracker = authTracker,
                limits = VisionLimits(common.queueTimeout, common.timeout, common.maxConcurrent, common.maxImageSide),
                label = "description",
            )
        }

        @Bean
        fun descriptionAgent(
            descriptionVisionCallExecutor: VisionCallExecutor,
            parser: DescriptionResponseParser,
        ): DescriptionAgent = DefaultDescriptionAgent(descriptionVisionCallExecutor, parser)

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
                // Нормализуем так же, как условие (trim + lowercase), но без фильтра по известным
                // провайдерам: опечатка вроде `gemini` тоже обязана остаться видимой, а
                // DescriptionPresetDeclarations.normalize отдаёт на ней null.
                val leftover = descriptionProperties.provider.trim().lowercase()
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

    /**
     * Бины судьи — отдельное условие поверх каталога описаний. Два [VisionCallExecutor] живут
     * рядом и различаются именем параметра (`descriptionVisionCallExecutor` /
     * `judgeVisionCallExecutor`); по типу их никто не инжектит.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
    @ConditionalOnProperty("application.ai.judge.enabled", havingValue = "true")
    @Conditional(DescriptionPresetsDeclaredCondition::class)
    open class JudgeBeans {
        @Bean
        @ConditionalOnMissingBean(JudgeRuntimeSettings::class)
        fun inMemoryJudgeRuntimeSettings(): JudgeRuntimeSettings = InMemoryJudgeRuntimeSettings()

        @Bean
        fun judgePresetResolver(
            catalog: DescriptionPresetCatalog,
            runtimeSettings: JudgeRuntimeSettings,
            judgeProperties: JudgeProperties,
        ): JudgePresetResolver {
            val fallbackId = judgeProperties.defaultPreset.ifBlank { catalog.fallbackId }
            val entry = catalog.byId(fallbackId)
            check(entry != null) {
                "application.ai.judge default-preset '$fallbackId' is not declared in application.ai.description.presets"
            }
            check(entry.backend != null) {
                "application.ai.judge default-preset '$fallbackId' is unavailable: ${entry.view.unavailableReason}"
            }
            return JudgePresetResolver(ActivePresetResolver(catalog, runtimeSettings, fallbackId, label = "judge"))
        }

        @Bean
        fun judgeVisionCallExecutor(
            resolver: JudgePresetResolver,
            authTracker: ProviderAuthTracker,
            judgeProperties: JudgeProperties,
        ): VisionCallExecutor =
            VisionCallExecutor(
                resolver = resolver.resolver,
                authTracker = authTracker,
                limits =
                    VisionLimits(
                        judgeProperties.queueTimeout,
                        judgeProperties.timeout,
                        judgeProperties.maxConcurrent,
                        judgeProperties.maxImageSide,
                    ),
                label = "judge",
            )

        @Bean
        fun judgeRateLimiter(
            clock: Clock,
            judgeProperties: JudgeProperties,
        ): JudgeRateLimiter = JudgeRateLimiter(clock, judgeProperties)

        @Bean
        fun judgeAgent(
            judgeVisionCallExecutor: VisionCallExecutor,
            parser: JudgeResponseParser,
        ): JudgeAgent = DefaultJudgeAgent(judgeVisionCallExecutor, parser)
    }
}
