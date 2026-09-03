package ru.zinin.frigate.analyzer.ai.description.config

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionAgent
import ru.zinin.frigate.analyzer.ai.description.core.DefaultDescriptionAgent
import ru.zinin.frigate.analyzer.ai.description.core.DescriptionBackend

@AutoConfiguration
@ComponentScan("ru.zinin.frigate.analyzer.ai.description")
@EnableConfigurationProperties(DescriptionProperties::class, ClaudeProperties::class)
open class AiDescriptionAutoConfiguration {
    /**
     * Агент существует только вместе с backend-ом выбранного провайдера. `@ConditionalOnBean`
     * надёжен здесь потому, что это `@AutoConfiguration`: `@Bean`-методы читаются после того, как
     * `@ComponentScan` этого же класса зарегистрировал backend-ы. Неизвестный `provider` даёт
     * отсутствие агента и WARN от [DescriptionAgentSanityChecker], как раньше.
     */
    @Bean
    @ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
    @ConditionalOnBean(DescriptionBackend::class)
    fun descriptionAgent(
        backend: DescriptionBackend,
        descriptionProperties: DescriptionProperties,
        eventPublisher: ApplicationEventPublisher,
    ): DescriptionAgent = DefaultDescriptionAgent(backend, descriptionProperties, eventPublisher)
}
