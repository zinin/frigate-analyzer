package ru.zinin.frigate.analyzer.ai.description.api

/** Каталог пресетов только на чтение; порядок — порядок объявления в конфиге. */
interface DescriptionPresets {
    fun all(): List<DescriptionPreset>
}
