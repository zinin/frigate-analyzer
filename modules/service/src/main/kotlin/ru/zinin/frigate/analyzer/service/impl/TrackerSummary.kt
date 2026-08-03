package ru.zinin.frigate.analyzer.service.impl

import java.time.Duration
import java.util.UUID

/** One matched track's absence before the current recording. */
internal data class ClassAbsence(
    val className: String,
    val absence: Duration,
) {
    fun render(): String = "$className:$absence"
}

/**
 * The tracker's per-recording debug line.
 *
 * Extracted from [ObjectTrackerServiceImpl] because it is the only place the reappearance
 * thresholds can be tuned from: `reappear-gap` is otherwise picked blind — the absences that did
 * not cross it are invisible, and they are exactly the ones that say where the boundary between
 * detector flakiness and a real return actually lies. A format operators grep is worth pinning in
 * a test rather than rediscovering in production logs.
 */
internal data class TrackerSummary(
    val camId: String,
    val recordingId: UUID,
    val newCount: Int,
    val matched: Int,
    val reappeared: List<ClassAbsence>,
    val classFiltered: List<ClassAbsence>,
    val unobserved: Int,
    val stale: Int,
    /** Largest absence among **all** matched tracks, including those below the gap. */
    val maxAbsence: Duration?,
) {
    /**
     * Keeps the line off the vast majority of recordings, where nothing but ordinary matches
     * happened.
     *
     * [classFiltered] joins the original three because without it a deployment that filters every
     * reappearing class would log nothing at all for those recordings — the one case where the
     * operator most needs to see that the filter, and not a missing reappearance, is what went
     * quiet. It only ever fires on an absence past the gap, so the line stays as rare as before.
     */
    val worthLogging: Boolean
        get() = newCount > 0 || reappeared.isNotEmpty() || classFiltered.isNotEmpty() || unobserved > 0

    fun render(): String =
        "ObjectTracker: cam=$camId new=$newCount matched=$matched " +
            "reappeared=${render(reappeared)} classFiltered=${render(classFiltered)} " +
            "unobserved=$unobserved stale=$stale maxAbsence=${maxAbsence ?: "n/a"} " +
            "(recording=$recordingId)"

    private fun render(absences: List<ClassAbsence>): String = absences.joinToString(prefix = "[", postfix = "]") { it.render() }
}
