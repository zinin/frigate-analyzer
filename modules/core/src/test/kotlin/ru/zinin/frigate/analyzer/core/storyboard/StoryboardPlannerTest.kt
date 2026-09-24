package ru.zinin.frigate.analyzer.core.storyboard

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoryboardPlannerTest {
    @Test
    fun `the window spans the detections plus the margins`() {
        assertEquals(TimeRange(0.0, 10.0), StoryboardPlanner.window(listOf(5.0), 5.0, 5.0, 12.0))
        assertEquals(TimeRange(-3.0, 16.0), StoryboardPlanner.window(listOf(11.0, 2.0), 5.0, 5.0, 12.0))
    }

    @Test
    fun `without detection times the window is the whole recording`() {
        val window = StoryboardPlanner.window(emptyList(), 5.0, 5.0, 12.0)

        assertEquals(TimeRange(0.0, 12.0), window)
        assertFalse(StoryboardPlanner.needsPrevious(window))
        assertFalse(StoryboardPlanner.needsNext(window, 12.0))
    }

    @Test
    fun `neighbours are needed only past the recording's edges`() {
        assertTrue(StoryboardPlanner.needsPrevious(TimeRange(-0.1, 5.0)))
        assertFalse(StoryboardPlanner.needsPrevious(TimeRange(0.0, 5.0)))
        assertTrue(StoryboardPlanner.needsNext(TimeRange(5.0, 12.1), 12.0))
        assertFalse(StoryboardPlanner.needsNext(TimeRange(5.0, 12.0), 12.0))
    }

    @Test
    fun `segments butt against each other within a second and a half`() {
        val base = Instant.parse("2026-09-23T10:00:00Z")

        assertTrue(StoryboardPlanner.contiguous(base, base.plusMillis(1400)))
        assertTrue(StoryboardPlanner.contiguous(base, base.minusMillis(1400)))
        assertFalse(StoryboardPlanner.contiguous(base, base.plusMillis(1600)))
    }

    @Test
    fun `footage is the window cut to what the neighbours cover`() {
        val window = TimeRange(-3.0, 16.0)

        assertEquals(Footage(TimeRange(-3.0, 16.0), false, false), StoryboardPlanner.footage(window, 12.0, 10.0, 10.0))
        assertEquals(Footage(TimeRange(0.0, 12.0), true, true), StoryboardPlanner.footage(window, 12.0, null, null))
        assertEquals(Footage(TimeRange(-2.0, 15.0), true, true), StoryboardPlanner.footage(window, 12.0, 2.0, 3.0))
        assertEquals(Footage(TimeRange(1.0, 11.0), false, false), StoryboardPlanner.footage(TimeRange(1.0, 11.0), 12.0, null, null))
    }

    @Test
    fun `a long enough footage gets the full number of tiles, spread evenly`() {
        val grid = assertNotNull(StoryboardPlanner.tileGrid(TimeRange(1.0, 11.0), 16))

        assertEquals(16, grid.moments.size)
        assertEquals(0.66, grid.stepSeconds, 1e-9)
        assertEquals(1.0, grid.moments.first(), 1e-9)
        assertEquals(10.9, grid.moments.last(), 1e-9)
    }

    /** В `double` `7.506 / (7.506 / 15)` равно `14.999999999999998`: деление обратно потеряло бы клетку. */
    @Test
    fun `the tile count does not lose a tile to floating point`() {
        assertEquals(16, assertNotNull(StoryboardPlanner.tileGrid(TimeRange(0.0, 7.606), 16)).moments.size)
    }

    @Test
    fun `a short footage keeps half a second between tiles`() {
        val grid = assertNotNull(StoryboardPlanner.tileGrid(TimeRange(0.0, 5.1), 16))

        assertEquals(11, grid.moments.size)
        assertEquals(0.5, grid.stepSeconds)
    }

    @Test
    fun `fewer than four tiles is no storyboard`() {
        assertEquals(4, assertNotNull(StoryboardPlanner.tileGrid(TimeRange(0.0, 1.6), 16)).moments.size)
        assertNull(StoryboardPlanner.tileGrid(TimeRange(0.0, 1.5), 16))
        assertNull(StoryboardPlanner.tileGrid(TimeRange(6.0, 6.0), 16))
    }

    @Test
    fun `a moment is found in the file that holds it`() {
        assertEquals(SegmentMoment(SegmentRole.PREVIOUS, 8.0), StoryboardPlanner.locate(-2.0, 12.0, 10.0))
        assertEquals(SegmentMoment(SegmentRole.CURRENT, 3.0), StoryboardPlanner.locate(3.0, 12.0, null))
        assertEquals(SegmentMoment(SegmentRole.NEXT, 0.0), StoryboardPlanner.locate(12.0, 12.0, null))
        assertEquals(SegmentMoment(SegmentRole.NEXT, 1.5), StoryboardPlanner.locate(13.5, 12.0, null))
        assertFailsWith<IllegalArgumentException> { StoryboardPlanner.locate(-1.0, 12.0, null) }
    }

    @Test
    fun `the tiles nearest to the detections are marked`() {
        val moments = listOf(0.0, 1.0, 2.0, 3.0)

        assertEquals(setOf(1, 3), StoryboardPlanner.markedTiles(moments, listOf(1.2, 2.9)))
        assertEquals(emptySet(), StoryboardPlanner.markedTiles(moments, emptyList()))
    }

    @Test
    fun `the grid is as square as the tile count allows`() {
        assertEquals(GridLayout(4, 4), StoryboardPlanner.layout(16))
        assertEquals(GridLayout(4, 3), StoryboardPlanner.layout(11))
        assertEquals(GridLayout(2, 2), StoryboardPlanner.layout(4))
        assertEquals(GridLayout(5, 5), StoryboardPlanner.layout(25))
        assertEquals(GridLayout(3, 2), StoryboardPlanner.layout(5))
    }

    @Test
    fun `tiles share the grid width and stay even`() {
        assertEquals(640, StoryboardPlanner.tileWidth(4))
        assertEquals(852, StoryboardPlanner.tileWidth(3))
        assertEquals(512, StoryboardPlanner.tileWidth(5))
    }

    @Test
    fun `a tile label is its time, plus a mark near a detection`() {
        assertEquals("5.0s", StoryboardPlanner.tileLabel(5.0, marked = false))
        assertEquals("12.3s • detection", StoryboardPlanner.tileLabel(12.34, marked = true))
    }
}
