package ru.zinin.frigate.analyzer.model.dto

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationScheduleTest {
    private val plain = ScheduleWindow(LocalTime.of(0, 0), LocalTime.of(7, 0))
    private val crossing = ScheduleWindow(LocalTime.of(23, 0), LocalTime.of(7, 0))

    // -- parse --

    @Test
    fun `parse accepts plain window`() {
        assertEquals(plain, ScheduleWindow.parse("00:00-07:00"))
    }

    @Test
    fun `parse accepts midnight-crossing window`() {
        assertEquals(crossing, ScheduleWindow.parse("23:00-07:00"))
    }

    @Test
    fun `parse rejects garbage`() {
        assertNull(ScheduleWindow.parse("garbage"))
    }

    @Test
    fun `parse rejects missing part`() {
        assertNull(ScheduleWindow.parse("00:00-"))
    }

    @Test
    fun `parse rejects single-digit hours`() {
        assertNull(ScheduleWindow.parse("0:00-7:00"))
    }

    @Test
    fun `parse rejects hour 24`() {
        // SMART resolver would silently turn "24:00" into 00:00 — STRICT must reject it.
        assertNull(ScheduleWindow.parse("24:00-07:00"))
    }

    @Test
    fun `parse rejects invalid minutes`() {
        assertNull(ScheduleWindow.parse("00:60-07:00"))
    }

    @Test
    fun `parse rejects equal start and end`() {
        assertNull(ScheduleWindow.parse("07:00-07:00"))
    }

    @Test
    fun `storage format round-trips through parse`() {
        assertEquals(crossing, ScheduleWindow.parse(crossing.storageFormat()))
        assertEquals("23:00-07:00", crossing.storageFormat())
    }

    @Test
    fun `display format uses en dash`() {
        assertEquals("00:00–07:00", plain.displayFormat())
    }

    @Test
    fun `ofHours builds whole-hour window`() {
        assertEquals(plain, ScheduleWindow.ofHours(0, 7))
    }

    @Test
    fun `equal start and end is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { ScheduleWindow.ofHours(7, 7) }
    }

    // -- contains: plain window --

    @Test
    fun `plain window includes start boundary`() {
        assertTrue(plain.contains(LocalTime.of(0, 0)))
    }

    @Test
    fun `plain window excludes end boundary`() {
        assertFalse(plain.contains(LocalTime.of(7, 0)))
    }

    @Test
    fun `plain window includes inner point`() {
        assertTrue(plain.contains(LocalTime.of(3, 30)))
    }

    @Test
    fun `plain window excludes daytime point`() {
        assertFalse(plain.contains(LocalTime.of(12, 0)))
    }

    // -- contains: midnight crossing --

    @Test
    fun `crossing window includes start boundary`() {
        assertTrue(crossing.contains(LocalTime.of(23, 0)))
    }

    @Test
    fun `crossing window includes late evening`() {
        assertTrue(crossing.contains(LocalTime.of(23, 30)))
    }

    @Test
    fun `crossing window includes early morning`() {
        assertTrue(crossing.contains(LocalTime.of(3, 0)))
    }

    @Test
    fun `crossing window includes midnight`() {
        assertTrue(crossing.contains(LocalTime.MIDNIGHT))
    }

    @Test
    fun `crossing window excludes end boundary`() {
        assertFalse(crossing.contains(LocalTime.of(7, 0)))
    }

    @Test
    fun `crossing window excludes daytime`() {
        assertFalse(crossing.contains(LocalTime.of(12, 0)))
    }

    // -- NotificationSchedule: zone-aware membership --

    @Test
    fun `instant is evaluated in the schedule zone`() {
        val schedule = NotificationSchedule(plain, ZoneId.of("Europe/Moscow"))
        // 23:30 UTC == 02:30 next day in Moscow (UTC+3) → inside 00:00–07:00
        assertTrue(schedule.contains(Instant.parse("2026-07-17T23:30:00Z")))
        // 12:00 UTC == 15:00 Moscow → outside
        assertFalse(schedule.contains(Instant.parse("2026-07-17T12:00:00Z")))
    }

    @Test
    fun `dst transition day evaluates without error`() {
        // Europe/Berlin switches to CEST on 2026-03-29 (02:00→03:00 local).
        val schedule = NotificationSchedule(plain, ZoneId.of("Europe/Berlin"))
        // 01:30 UTC == 03:30 CEST → inside 00:00–07:00
        assertTrue(schedule.contains(Instant.parse("2026-03-29T01:30:00Z")))
    }
}
