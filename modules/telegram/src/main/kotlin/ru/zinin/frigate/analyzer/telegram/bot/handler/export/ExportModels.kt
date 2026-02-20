package ru.zinin.frigate.analyzer.telegram.bot.handler.export

import ru.zinin.frigate.analyzer.telegram.service.model.ExportMode
import ru.zinin.frigate.analyzer.telegram.service.model.VideoExportProgress.Stage
import java.time.Instant

sealed class ExportDialogOutcome {
    data class Success(
        val startInstant: Instant,
        val endInstant: Instant,
        val camId: String,
        val mode: ExportMode,
    ) : ExportDialogOutcome()

    data object Cancelled : ExportDialogOutcome()

    data object Timeout : ExportDialogOutcome()
}

internal const val EXPORT_DIALOG_TIMEOUT_MS = 600_000L
internal const val EXPORT_ORIGINAL_TIMEOUT_MS = 300_000L
internal const val EXPORT_ANNOTATED_TIMEOUT_MS = 1_200_000L
internal const val MAX_EXPORT_DURATION_MINUTES = 5L

internal fun renderProgress(
    stage: Stage,
    percent: Int? = null,
    mode: ExportMode = ExportMode.ORIGINAL,
    compressing: Boolean = false,
): String {
    val stages =
        buildList {
            add(Stage.PREPARING to "Подготовка")
            add(Stage.MERGING to "Склейка видео")
            if (compressing) add(Stage.COMPRESSING to "Сжатие видео")
            if (mode == ExportMode.ANNOTATED) add(Stage.ANNOTATING to "Аннотация видео")
            add(Stage.SENDING to "Отправка")
            add(Stage.DONE to "Готово")
        }

    val currentIndex = stages.indexOfFirst { it.first == stage }

    return buildString {
        for ((index, pair) in stages.withIndex()) {
            val (s, label) = pair
            when {
                s == stage && s == Stage.DONE -> appendLine("\u2705 $label")
                s == stage && s == Stage.ANNOTATING && percent != null -> appendLine("\uD83D\uDD04 $label: $percent%")
                s == stage -> appendLine("\uD83D\uDD04 $label...")
                currentIndex >= 0 && index < currentIndex -> appendLine("\u2705 $label")
                else -> appendLine("\u2B1C $label")
            }
        }
    }.trimEnd()
}
