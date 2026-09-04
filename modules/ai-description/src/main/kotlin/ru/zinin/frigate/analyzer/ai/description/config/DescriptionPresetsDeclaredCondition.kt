package ru.zinin.frigate.analyzer.ai.description.config

import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.core.type.AnnotatedTypeMetadata

/**
 * Бины фичи существуют, только когда есть что класть в каталог: либо объявлена карта `presets`,
 * либо legacy-`provider` называет известного провайдера. Иначе бинов нет, агента нет и
 * [DescriptionAgentSanityChecker] пишет WARN — то же поведение, что сегодня даёт опечатка в
 * `APP_AI_DESCRIPTION_PROVIDER`.
 */
class DescriptionPresetsDeclaredCondition : Condition {
    override fun matches(
        context: ConditionContext,
        metadata: AnnotatedTypeMetadata,
    ): Boolean = DescriptionPresetDeclarations.anyDeclared(context.environment)
}
