package ru.zinin.frigate.analyzer.service.helper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class RecordingFileHelperTest {
    private val helper = RecordingFileHelper()

    @Test
    fun `parse valid frigate recording path`() {
        // given
        val path =
            Path.of(
                "/mnt/data/frigate/recordings/2025-12-28/09/cam1/01.25.mp4",
            )

        // when
        val result = helper.parse(path)

        // then
        assertEquals("/mnt/data/frigate/recordings", result.basePath)
        assertEquals("cam1", result.camId)
        assertEquals(LocalDate.of(2025, 12, 28), result.date)
        assertEquals(LocalTime.of(9, 1, 25), result.time)

        val expectedInstant = Instant.parse("2025-12-28T09:01:25Z")
        assertEquals(expectedInstant, result.timestamp)
    }

    @Test
    fun `invalid filename should throw exception`() {
        val path =
            Path.of(
                "/mnt/data/frigate/recordings/2025-12-28/09/cam1/01-25.mp4",
            )

        assertThrows(IllegalStateException::class.java) {
            helper.parse(path)
        }
    }

    @Test
    fun `invalid path structure should throw exception`() {
        val path = Path.of("/mnt/data/frigate/recordings/01.25.mp4")

        assertThrows(IllegalArgumentException::class.java) {
            helper.parse(path)
        }
    }
}
