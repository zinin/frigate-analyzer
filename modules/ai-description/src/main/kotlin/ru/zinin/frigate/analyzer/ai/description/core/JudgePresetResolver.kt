package ru.zinin.frigate.analyzer.ai.description.core

import ru.zinin.frigate.analyzer.ai.description.api.ActiveJudgePreset
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionPreset

/** Резолвер судьи как бин типа [ActiveJudgePreset]; сам [ActivePresetResolver] бином не является — их два. */
class JudgePresetResolver(
    val resolver: ActivePresetResolver,
) : ActiveJudgePreset {
    override suspend fun storedId(): String? = resolver.storedId()

    override suspend fun effective(): DescriptionPreset = resolver.effective()
}
