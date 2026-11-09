package ru.zinin.frigate.analyzer.core.storyboard

import java.time.Duration

/**
 * Чем кончилась подготовка раскадровки события ([RecordingStoryboard]). [Status.AVAILABLE] — сетка [built]
 * построена; [Status.UNAVAILABLE] — builder обошёлся без неё (мало видео или кадров, сбой ffprobe или ffmpeg —
 * причина в его WARN); [Status.TIMEOUT] — истёк собственный бюджет подготовки, и сборку отменили. Сетка есть
 * только у AVAILABLE.
 *
 * [elapsed] — сколько шла подготовка по монотонным часам: от захвата mutex до конца сборки или бюджета, со всеми
 * ожиданиями builder-а — permits, соседи, ffprobe, ffmpeg. У запомненного результата это время той первой сборки.
 */
data class StoryboardPreparation(
    val status: Status,
    val built: BuiltStoryboard?,
    val elapsed: Duration,
) {
    init {
        require((status == Status.AVAILABLE) == (built != null)) {
            "Storyboard preparation $status ${if (built == null) "without" else "with"} a grid"
        }
    }

    enum class Status { AVAILABLE, UNAVAILABLE, TIMEOUT }
}
