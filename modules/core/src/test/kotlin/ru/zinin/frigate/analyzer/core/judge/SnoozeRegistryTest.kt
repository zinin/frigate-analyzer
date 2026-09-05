package ru.zinin.frigate.analyzer.core.judge

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnoozeRegistryTest {
    private val registry = SnoozeRegistry()
    private val anchor = Instant.parse("2026-09-05T10:00:00Z")

    @Test
    fun `covers the same classes with equal or smaller counts inside the window in both directions`() {
        registry.set("cam2", anchor, minutes = 15, classes = mapOf("person" to 1))
        assertNotNull(registry.covers("cam2", anchor.plusSeconds(600), mapOf("person" to 1)))
        assertNotNull(registry.covers("cam2", anchor.minusSeconds(600), mapOf("person" to 1)))
        assertNull(registry.covers("cam2", anchor.plusSeconds(16 * 60), mapOf("person" to 1)))
    }

    @Test
    fun `a new class or a larger count wakes the judge`() {
        registry.set("cam2", anchor, minutes = 15, classes = mapOf("person" to 1))
        assertNull(registry.covers("cam2", anchor.plusSeconds(60), mapOf("person" to 2)))
        assertNull(registry.covers("cam2", anchor.plusSeconds(60), mapOf("person" to 1, "car" to 1)))
        assertNotNull(registry.covers("cam2", anchor.plusSeconds(60), mapOf("person" to 1)))
    }

    @Test
    fun `cameras are independent and a new set replaces the previous coverage`() {
        registry.set("cam2", anchor, 15, mapOf("person" to 1))
        assertNull(registry.covers("cam3", anchor, mapOf("person" to 1)))
        registry.set("cam2", anchor.plusSeconds(60), 15, mapOf("car" to 1))
        assertNull(registry.covers("cam2", anchor.plusSeconds(120), mapOf("person" to 1)))
        assertNotNull(registry.covers("cam2", anchor.plusSeconds(120), mapOf("car" to 1)))
    }

    @Test
    fun `zero minutes clears, snapshot lists active snoozes with until`() {
        registry.set("cam2", anchor, 15, mapOf("person" to 1))
        assertEquals(anchor.plusSeconds(900), registry.snapshot().single().until)
        registry.set("cam2", anchor, 0, mapOf("person" to 1))
        assertTrue(registry.snapshot().isEmpty())
        assertNull(registry.covers("cam2", anchor, mapOf("person" to 1)))
    }

    @Test
    fun `an older backlog anchor neither replaces nor clears a newer snooze`() {
        registry.set("cam2", anchor, 15, mapOf("person" to 1))
        // Бэклог разбирается от новых к старым: следующий кандидат старше и вне окна, поэтому идёт
        // к модели. Его вердикт не должен сдвигать окно назад — живые дубли остались бы без укрытия.
        registry.set("cam2", anchor.minusSeconds(3600), 30, mapOf("car" to 1))
        assertNotNull(registry.covers("cam2", anchor.plusSeconds(300), mapOf("person" to 1)))
        registry.set("cam2", anchor.minusSeconds(3600), 0, mapOf("car" to 1))
        assertNotNull(registry.covers("cam2", anchor.plusSeconds(300), mapOf("person" to 1)))
    }

    @Test
    fun `empty class map is never covered`() {
        registry.set("cam2", anchor, 15, mapOf("person" to 1))
        assertNull(registry.covers("cam2", anchor, emptyMap()))
    }
}
