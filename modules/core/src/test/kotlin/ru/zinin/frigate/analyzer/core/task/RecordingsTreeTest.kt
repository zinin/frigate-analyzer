package ru.zinin.frigate.analyzer.core.task

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private val ROOT = Path.of("/mnt/data/frigate/recordings")
private val CLOCK = Clock.fixed(Instant.parse("2026-02-15T12:00:00Z"), ZoneOffset.UTC)

class RecordingsTreeTest {
    @Test
    fun `watchCutoff subtracts whole days in UTC`() {
        assertEquals(LocalDate.of(2026, 2, 14), watchCutoff(Duration.ofDays(1), CLOCK))
        assertEquals(LocalDate.of(2026, 2, 15), watchCutoff(Duration.ZERO, CLOCK))
        assertEquals(LocalDate.of(2026, 2, 8), watchCutoff(Duration.ofDays(7), CLOCK))
    }

    @Test
    fun `isPrunableDate returns false for a path without a date`() {
        assertFalse(isPrunableDate(ROOT, ROOT, LocalDate.of(2026, 2, 14)))
    }

    @Test
    fun `isPrunableDate returns false for a date on the cutoff`() {
        assertFalse(isPrunableDate(ROOT.resolve("2026-02-14"), ROOT, LocalDate.of(2026, 2, 14)))
    }

    @Test
    fun `isPrunableDate returns true for a date before the cutoff`() {
        assertTrue(isPrunableDate(ROOT.resolve("2026-02-13"), ROOT, LocalDate.of(2026, 2, 14)))
    }

    @Test
    fun `isPrunableDate inherits the date of an hour or camera directory`() {
        val cutoff = LocalDate.of(2026, 2, 14)
        assertTrue(isPrunableDate(ROOT.resolve("2026-02-13/09"), ROOT, cutoff))
        assertTrue(isPrunableDate(ROOT.resolve("2026-02-13/09/cam1"), ROOT, cutoff))
        assertFalse(isPrunableDate(ROOT.resolve("2026-02-15/09/cam1"), ROOT, cutoff))
    }

    @Test
    fun `isPrunableDate never prunes the root that isWithinWatchPeriod admits`() {
        val cutoff = watchCutoff(Duration.ofDays(1), CLOCK)
        assertTrue(isWithinWatchPeriod(ROOT, ROOT, Duration.ofDays(1), CLOCK))
        assertFalse(isPrunableDate(ROOT, ROOT, cutoff))
    }

    @Test
    fun `isPrunableDate is the exact complement of isWithinWatchPeriod for dated paths`() {
        // Wide, boundary-heavy list on purpose: a future refactor to the `!isWithinWatchPeriod`
        // form must fail this test for SOME date, whatever the cutoff arithmetic does.
        val cutoff = watchCutoff(Duration.ofDays(1), CLOCK)
        listOf(
            "2027-01-01",
            "2026-12-31",
            "2026-02-16",
            "2026-02-15",
            "2026-02-14",
            "2026-02-13",
            "2026-02-12",
            "2026-01-01",
            "2025-12-31",
            "2020-06-15",
        ).forEach { date ->
            val path = ROOT.resolve(date)
            assertEquals(
                !isWithinWatchPeriod(path, ROOT, Duration.ofDays(1), CLOCK),
                isPrunableDate(path, ROOT, cutoff),
                "mismatch for $date",
            )
        }
    }

    @Test
    fun `isDateAtUnexpectedDepth detects a root set one level too high`() {
        assertFalse(isDateAtUnexpectedDepth(ROOT, ROOT))
        assertFalse(isDateAtUnexpectedDepth(ROOT.resolve("2026-02-15"), ROOT))
        assertFalse(isDateAtUnexpectedDepth(ROOT.resolve("2026-02-15/09"), ROOT))
        assertFalse(isDateAtUnexpectedDepth(ROOT.resolve("2026-02-15/09/cam1"), ROOT))
        val highRoot = Path.of("/mnt/data/frigate")
        assertTrue(isDateAtUnexpectedDepth(highRoot.resolve("recordings/2026-02-15"), highRoot))
        assertFalse(isDateAtUnexpectedDepth(Path.of("/var/tmp/elsewhere/2026-02-15"), ROOT))
    }

    @Test
    fun `depthFromRoot returns 0 for the root itself`() {
        // Regression guard: rootFolder.relativize(rootFolder) is the EMPTY path, whose nameCount
        // is 1, not 0. Without the guard the root is classified as a date directory.
        assertEquals(0, depthFromRoot(ROOT, ROOT))
    }

    @Test
    fun `depthFromRoot returns 1 2 3 for date hour and camera`() {
        assertEquals(1, depthFromRoot(ROOT.resolve("2026-02-15"), ROOT))
        assertEquals(2, depthFromRoot(ROOT.resolve("2026-02-15/09"), ROOT))
        assertEquals(3, depthFromRoot(ROOT.resolve("2026-02-15/09/cam1"), ROOT))
    }

    @Test
    fun `depthFromRoot returns -1 for a path outside the root`() {
        assertEquals(-1, depthFromRoot(Path.of("/var/tmp/elsewhere"), ROOT))
    }

    @Test
    fun `CAMERA_DEPTH equals the depth of a camera directory`() {
        assertEquals(CAMERA_DEPTH, depthFromRoot(ROOT.resolve("2026-02-15/09/cam1"), ROOT))
    }

    // --- Pure-function tests migrated from WatchRecordsTaskTest ---

    @Test
    fun `extractDateFromPath returns date for date directory`() {
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-15")
        assertEquals(LocalDate.of(2026, 2, 15), extractDateFromPath(path, ROOT))
    }

    @Test
    fun `extractDateFromPath returns date for hour subdirectory`() {
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-15/09")
        assertEquals(LocalDate.of(2026, 2, 15), extractDateFromPath(path, ROOT))
    }

    @Test
    fun `extractDateFromPath returns date for camera subdirectory`() {
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-15/09/cam1")
        assertEquals(LocalDate.of(2026, 2, 15), extractDateFromPath(path, ROOT))
    }

    @Test
    fun `extractDateFromPath returns null for root recordings directory`() {
        assertNull(extractDateFromPath(ROOT, ROOT))
    }

    @Test
    fun `extractDateFromPath returns null for invalid date like 2026-02-30`() {
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-30")
        assertNull(extractDateFromPath(path, ROOT))
    }

    @Test
    fun `extractDateFromPath ignores date-like segments in root path`() {
        val rootWithDate = Path.of("/data/2024-01-15/frigate/recordings")
        val path = Path.of("/data/2024-01-15/frigate/recordings/2026-02-15/09/cam1")
        assertEquals(LocalDate.of(2026, 2, 15), extractDateFromPath(path, rootWithDate))
    }

    @Test
    fun `isWithinWatchPeriod returns true for today`() {
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-15")
        assertTrue(isWithinWatchPeriod(path, ROOT, Duration.ofDays(1), CLOCK))
    }

    @Test
    fun `isWithinWatchPeriod returns true for yesterday within 1 day period`() {
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-14")
        assertTrue(isWithinWatchPeriod(path, ROOT, Duration.ofDays(1), CLOCK))
    }

    @Test
    fun `isWithinWatchPeriod returns false for old date`() {
        val path = Path.of("/mnt/data/frigate/recordings/2026-01-01")
        assertFalse(isWithinWatchPeriod(path, ROOT, Duration.ofDays(1), CLOCK))
    }

    @Test
    fun `isWithinWatchPeriod returns true for root directory without date`() {
        assertTrue(isWithinWatchPeriod(ROOT, ROOT, Duration.ofDays(1), CLOCK))
    }

    @Test
    fun `isWithinWatchPeriod returns true for exact cutoff date`() {
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-14")
        assertTrue(isWithinWatchPeriod(path, ROOT, Duration.ofDays(1), CLOCK))
    }

    @Test
    fun `isWithinWatchPeriod returns false for one day before cutoff`() {
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-13")
        assertFalse(isWithinWatchPeriod(path, ROOT, Duration.ofDays(1), CLOCK))
    }
}
