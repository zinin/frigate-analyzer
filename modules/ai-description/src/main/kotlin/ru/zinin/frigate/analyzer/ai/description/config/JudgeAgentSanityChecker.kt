package ru.zinin.frigate.analyzer.ai.description.config

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.JudgeAgent

private val logger = KotlinLogging.logger {}

@Component
class JudgeAgentSanityChecker(
    private val judgeProperties: JudgeProperties,
    private val agentProvider: ObjectProvider<JudgeAgent>,
) {
    @PostConstruct
    fun warnIfAgentMissing() {
        if (!judgeProperties.enabled) return
        if (agentProvider.getIfAvailable() == null) {
            logger.warn {
                "application.ai.judge.enabled=true but no JudgeAgent registered: " +
                    "the description preset catalog was not built — check " +
                    "application.ai.description.enabled and application.ai.description.presets"
            }
        }
    }
}
