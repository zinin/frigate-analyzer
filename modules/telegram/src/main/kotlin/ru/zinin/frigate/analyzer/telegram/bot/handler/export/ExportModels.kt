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

// Covers the worst case of fitting the merged file into the Telegram limit: ffprobe (30 s) plus
// two libx264 encodes of VideoMergeHelper.FFMPEG_TIMEOUT_SECONDS (300 s) each, plus the merge.
// With a smaller budget the retry could never finish and the user saw the processing timeout
// instead of the size message; the export stays cancellable throughout.
internal const val EXPORT_ORIGINAL_TIMEOUT_MS = 720_000L

// 75 minutes. Must exceed application.detect.video-visualize.timeout (default 45m) plus
// everything the export does around the annotation, so the inner annotation timeout surfaces a
// real failure instead of being masked by this outer one: the merge (300 s) and two fitting
// passes of ffprobe (30 s) plus two libx264 encodes of 300 s each.
internal const val EXPORT_ANNOTATED_TIMEOUT_MS = 4_500_000L
internal const val MAX_EXPORT_DURATION_MINUTES = 5L

internal fun renderProgress(
    stage: Stage,
    percent: Int? = null,
    mode: ExportMode = ExportMode.ORIGINAL,
    compressing: Boolean = false,
    compressingResult: Boolean = false,
    msg: MessageResolver,
    lang: String,
): String {
    val stages =
        buildList {
            add(Stage.PREPARING to msg.get("export.progress.preparing", lang))
            add(Stage.MERGING to msg.get("export.progress.merging", lang))
            if (compressing) add(Stage.COMPRESSING to msg.get("export.progress.compressing", lang))
            if (mode == ExportMode.ANNOTATED) add(Stage.ANNOTATING to msg.get("export.progress.annotating", lang))
            if (compressingResult) add(Stage.COMPRESSING_RESULT to msg.get("export.progress.compressing.result", lang))
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
