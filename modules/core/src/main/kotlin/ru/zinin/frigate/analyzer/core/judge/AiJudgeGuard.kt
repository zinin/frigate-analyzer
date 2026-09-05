package ru.zinin.frigate.analyzer.core.judge

import jakarta.annotation.PostConstruct
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import ru.zinin.frigate.analyzer.ai.description.config.JudgeProperties

@Component
@ConditionalOnProperty("application.ai.judge.enabled", havingValue = "true")
class AiJudgeGuard(
    private val descriptionProperties: DescriptionProperties,
    private val judgeProperties: JudgeProperties,
) {
    @PostConstruct
    fun validate() {
        if (!judgeProperties.enabled) return
        check(descriptionProperties.enabled) {
            "APP_AI_JUDGE_ENABLED=true requires APP_AI_DESCRIPTION_ENABLED=true: the judge runs on the AI preset " +
                "catalog that only exists with descriptions enabled. Enable descriptions or set APP_AI_JUDGE_ENABLED=false."
        }
    }
}
