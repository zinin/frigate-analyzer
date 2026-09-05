package ru.zinin.frigate.analyzer.ai.description.config

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionAgent

private val logger = KotlinLogging.logger {}

@Component
class DescriptionAgentSanityChecker(
    private val descriptionProperties: DescriptionProperties,
    private val agentProvider: ObjectProvider<DescriptionAgent>,
) {
    @PostConstruct
    fun warnIfProviderMissing() {
        if (!descriptionProperties.enabled) return
        if (agentProvider.getIfAvailable() == null) {
            logger.warn {
                "application.ai.description.enabled=true but no DescriptionAgent registered: " +
                    "neither application.ai.description.presets nor a known " +
                    "application.ai.description.provider='${descriptionProperties.provider}' " +
                    "(known: ${DescriptionProperties.KNOWN_PROVIDERS.joinToString()}); all describe-calls will fall back."
            }
        }
    }
}
