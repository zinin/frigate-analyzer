package ru.zinin.frigate.analyzer.telegram.service.model

data class VideoExportProgress(
    val stage: Stage,
    val percent: Int? = null,
) {
    enum class Stage { PREPARING, MERGING, COMPRESSING, ANNOTATING, SENDING, DONE }
}
