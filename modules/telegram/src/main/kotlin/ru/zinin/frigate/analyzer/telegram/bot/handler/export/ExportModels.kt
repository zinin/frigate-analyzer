package ru.zinin.frigate.analyzer.telegram.bot.handler.export

import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
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

// Must exceed application.detect.video-visualize.timeout (default 45m) so the inner
// annotation timeout surfaces a real failure instead of being masked by this outer one.
internal const val EXPORT_ANNOTATED_TIMEOUT_MS = 3_000_000L
internal const val MAX_EXPORT_DURATION_MINUTES = 5L

internal fun renderProgress(
    stage: Stage,
    percent: Int? = null,
    mode: ExportMode = ExportMode.ORIGINAL,
    compressing: Boolean = false,
    msg: MessageResolver,
    lang: String,
): String {
    val stages =
        buildList {
            add(Stage.PREPARING to msg.get("export.progress.preparing", lang))
            add(Stage.MERGING to msg.get("export.progress.merging", lang))
            if (compressing) add(Stage.COMPRESSING to msg.get("export.progress.compressing", lang))
            if (mode == ExportMode.ANNOTATED) add(Stage.ANNOTATING to msg.get("export.progress.annotating", lang))
            add(Stage.SENDING to msg.get("export.progress.sending", lang))
            add(Stage.DONE to msg.get("export.progress.done", lang))
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
