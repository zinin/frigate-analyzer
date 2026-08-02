package ru.zinin.frigate.analyzer.core.task

import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

// Path arithmetic over Frigate's recordings tree: `recordings/YYYY-MM-DD/HH/camera/MM.SS.mp4`.
//
// Shared by WatchRecordsLoop (registers watch keys on directories) and FirstTimeScanTask
// (indexes the files themselves).

/**
 * Depth of a camera directory relative to the recordings root: 0 = root, 1 = date, 2 = hour,
 * 3 = camera. Below a camera directory there are only `.mp4` files, and a WatchService key is
 * registered on the camera directory — that is what delivers ENTRY_CREATE for new recordings.
 *
 * This is the only layout assumption in this file. It is not a new one: `RecordingFileHelper.parse`
 * already navigates `path.parent.parent.parent` and requires `nameCount >= 6`.
 */
internal const val CAMERA_DEPTH: Int = 3

private val DATE_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")

internal fun watchCutoff(
    watchPeriod: Duration,
    clock: Clock,
): LocalDate = LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(watchPeriod.toDays())

/**
 * Scans path segments FROM LEAF TO ROOT and returns the first date-like one. Inherited contract:
 * a date-like camera ID or a date-like directory below the date level yields the wrong answer —
 * change only together with `RecordingFileHelper.parse`. Date-like camera IDs are declared an
 * unsupported configuration.
 */
internal fun extractDateFromPath(
    path: Path,
    rootFolder: Path,
): LocalDate? {
    val relativePath = if (path.startsWith(rootFolder)) rootFolder.relativize(path) else path
    for (i in relativePath.nameCount - 1 downTo 0) {
        val name = relativePath.getName(i).toString()
        if (DATE_PATTERN.matches(name)) {
            return try {
                LocalDate.parse(name)
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
    return null
}

/**
 * Fail-OPEN: a path whose date cannot be extracted counts as "inside the period". The recordings
 * root itself and any non-standard directory always pass. See [isPrunableDate] for the mirror rule
 * used when deciding whether a subtree may be skipped.
 */
internal fun isWithinWatchPeriod(
    path: Path,
    rootFolder: Path,
    watchPeriod: Duration,
    clock: Clock,
): Boolean {
    val date = extractDateFromPath(path, rootFolder) ?: return true
    return !date.isBefore(watchCutoff(watchPeriod, clock))
}

/**
 * Fail-CLOSED mirror of [isWithinWatchPeriod]: a subtree may be pruned ONLY when its date was
 * successfully extracted AND falls strictly before [cutoff].
 *
 * Deliberately NOT written as `!isWithinWatchPeriod(...)`. The two are equivalent today, but the
 * negated form reads as "not in period -> cut", and the root's date never extracts. Cutting the
 * root would stop the watcher from ever noticing new date directories, and would leave
 * `registeredDirs` empty — health BRANCH 3.5 reports that as DOWN.
 */
internal fun isPrunableDate(
    path: Path,
    rootFolder: Path,
    cutoff: LocalDate,
): Boolean {
    val date = extractDateFromPath(path, rootFolder) ?: return false
    return date.isBefore(cutoff)
}

/**
 * Depth of [path] relative to [rootFolder]: 0 = root, 1 = date, 2 = hour, 3 = camera.
 * Returns -1 for paths outside the root, so depth-based rules simply do not apply to them and the
 * traversal degrades to a full walk — today's behaviour.
 *
 * NOTE: `rootFolder.relativize(rootFolder)` is the EMPTY path, and an empty [Path] reports
 * `nameCount == 1`, not 0. Without the `isEmpty()` guard the root is classified as a date directory.
 *
 * Symlinks: the walk runs without FOLLOW_LINKS, so symlinked directories arrive in `visitFile`
 * and depth rules never apply to them at all.
 */
internal fun depthFromRoot(
    path: Path,
    rootFolder: Path,
): Int {
    if (!path.startsWith(rootFolder)) return -1
    val rel = rootFolder.relativize(path)
    return if (rel.toString().isEmpty()) 0 else rel.nameCount
}

/**
 * Misplaced-root detector: a date extracts from [path], yet the FIRST segment under [rootFolder]
 * is not a date. In Frigate's layout the date is always the first segment under the root, so this
 * means `FRIGATE_RECORDS_FOLDER` points one level above the recordings root — the walk would then
 * stop at the hour level and never register camera directories, silently dropping ENTRY_CREATE.
 *
 * Implemented via [extractDateFromPath] rather than by matching DATE_PATTERN directly, so the
 * "is this segment a date" question has exactly one implementation.
 */
internal fun isDateAtUnexpectedDepth(
    path: Path,
    rootFolder: Path,
): Boolean {
    if (!path.startsWith(rootFolder) || path == rootFolder) return false
    val rel = rootFolder.relativize(path)
    val firstSegmentIsDate = extractDateFromPath(rootFolder.resolve(rel.getName(0)), rootFolder) != null
    return !firstSegmentIsDate && extractDateFromPath(path, rootFolder) != null
}
