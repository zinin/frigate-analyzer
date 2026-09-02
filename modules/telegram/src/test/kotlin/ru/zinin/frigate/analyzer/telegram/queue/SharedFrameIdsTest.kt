package ru.zinin.frigate.analyzer.telegram.queue

import dev.inmo.tgbotapi.requests.abstracts.FileId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedFrameIdsTest {
    @Test
    fun `starts empty`() {
        assertNull(SharedFrameIds().get())
    }

    @Test
    fun `first writer wins and later writers are ignored`() {
        val shared = SharedFrameIds()

        assertTrue(shared.putIfAbsent(listOf(FileId("first"))))
        assertFalse(shared.putIfAbsent(listOf(FileId("second"))))

        assertEquals(listOf(FileId("first")), shared.get())
    }

    @Test
    fun `invalidate clears the cache so the next sender uploads again`() {
        val shared = SharedFrameIds()
        shared.putIfAbsent(listOf(FileId("first")))

        shared.invalidate()

        assertNull(shared.get())
        assertTrue(shared.putIfAbsent(listOf(FileId("second"))))
    }

    @Test
    fun `empty list is not cached`() {
        val shared = SharedFrameIds()

        assertFalse(shared.putIfAbsent(emptyList()), "an empty result must not poison the cache")
        assertNull(shared.get())
    }
}
