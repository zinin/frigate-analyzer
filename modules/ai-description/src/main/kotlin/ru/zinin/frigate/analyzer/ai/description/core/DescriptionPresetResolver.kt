package ru.zinin.frigate.analyzer.ai.description.core

import ru.zinin.frigate.analyzer.ai.description.api.ActiveDescriptionPreset
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionPreset

/** Резолвер описаний как бин типа [ActiveDescriptionPreset]; сам [ActivePresetResolver] бином не является — их два. */
class DescriptionPresetResolver(
    val resolver: ActivePresetResolver,
) : ActiveDescriptionPreset {
    override suspend fun storedId(): String? = resolver.storedId()

    override suspend fun effective(): DescriptionPreset = resolver.effective()
}
