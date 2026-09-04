package ru.zinin.frigate.analyzer.ai.description.core

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionPreset
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionPresets

/** Неизменяемый список пресетов с готовыми backend-ами. Создаётся один раз на старте. */
class DescriptionPresetCatalog(
    private val entries: List<Entry>,
    val fallbackId: String,
) : DescriptionPresets {
    class Entry(
        val view: DescriptionPreset,
        /** null, если пресет недоступен: провайдер не настроен. */
        val backend: DescriptionBackend?,
    )

    private val index = entries.associateBy { it.view.id }

    init {
        require(entries.isNotEmpty()) { "preset catalog must not be empty" }
        require(index.containsKey(fallbackId)) { "fallback preset '$fallbackId' is not in the catalog" }
        require(index.getValue(fallbackId).backend != null) { "fallback preset '$fallbackId' is unavailable" }
    }

    override fun all(): List<DescriptionPreset> = entries.map { it.view }

    fun byId(id: String): Entry? = index[id]

    fun fallback(): Entry = index.getValue(fallbackId)
}
