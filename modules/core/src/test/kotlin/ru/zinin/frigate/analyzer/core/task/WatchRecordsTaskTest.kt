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

class WatchRecordsTaskTest {
    @Test
    fun `extractDateFromPath returns date for date directory`() {
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-15")
        assertEquals(LocalDate.of(2026, 2, 15), extractDateFromPath(path))
    }

    @Test
    fun `extractDateFromPath returns date for hour subdirectory`() {
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-15/09")
        assertEquals(LocalDate.of(2026, 2, 15), extractDateFromPath(path))
    }

    @Test
    fun `extractDateFromPath returns date for camera subdirectory`() {
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-15/09/cam1")
        assertEquals(LocalDate.of(2026, 2, 15), extractDateFromPath(path))
    }

    @Test
    fun `extractDateFromPath returns null for root recordings directory`() {
        val path = Path.of("/mnt/data/frigate/recordings")
        assertNull(extractDateFromPath(path))
    }

    @Test
    fun `isWithinWatchPeriod returns true for today`() {
        val clock = Clock.fixed(Instant.parse("2026-02-15T12:00:00Z"), ZoneOffset.UTC)
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-15")
        assertTrue(isWithinWatchPeriod(path, Duration.ofDays(1), clock))
    }

    @Test
    fun `isWithinWatchPeriod returns true for yesterday within 1 day period`() {
        val clock = Clock.fixed(Instant.parse("2026-02-15T12:00:00Z"), ZoneOffset.UTC)
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-14")
        assertTrue(isWithinWatchPeriod(path, Duration.ofDays(1), clock))
    }

    @Test
    fun `isWithinWatchPeriod returns false for old date`() {
        val clock = Clock.fixed(Instant.parse("2026-02-15T12:00:00Z"), ZoneOffset.UTC)
        val path = Path.of("/mnt/data/frigate/recordings/2026-01-01")
        assertFalse(isWithinWatchPeriod(path, Duration.ofDays(1), clock))
    }

    @Test
    fun `isWithinWatchPeriod returns true for root directory without date`() {
        val clock = Clock.fixed(Instant.parse("2026-02-15T12:00:00Z"), ZoneOffset.UTC)
        val path = Path.of("/mnt/data/frigate/recordings")
        assertTrue(isWithinWatchPeriod(path, Duration.ofDays(1), clock))
    }
}
