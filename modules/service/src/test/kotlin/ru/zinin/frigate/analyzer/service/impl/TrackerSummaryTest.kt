package ru.zinin.frigate.analyzer.service.impl

import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackerSummaryTest {
    private val recordingId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000ff")

    private fun summary(
        newCount: Int = 0,
        matched: Int = 0,
        reappeared: List<ClassAbsence> = emptyList(),
        classFiltered: List<ClassAbsence> = emptyList(),
        unobserved: Int = 0,
        stale: Int = 0,
        maxAbsence: Duration? = null,
    ) = TrackerSummary(
        camId = "cam2",
        recordingId = recordingId,
        newCount = newCount,
        matched = matched,
        reappeared = reappeared,
        classFiltered = classFiltered,
        unobserved = unobserved,
        stale = stale,
        maxAbsence = maxAbsence,
    )

    @Test
    fun `render carries every reappearance with its own absence`() {
        val rendered =
            summary(
                matched = 3,
                reappeared =
                    listOf(
                        ClassAbsence("person", Duration.ofHours(3).plusMinutes(12)),
                        ClassAbsence("bicycle", Duration.ofHours(7).plusMinutes(4)),
                    ),
                stale = 107,
                maxAbsence = Duration.ofHours(7).plusMinutes(4),
            ).render()

        assertEquals(
            "ObjectTracker: cam=cam2 new=0 matched=3 reappeared=[person:PT3H12M, bicycle:PT7H4M] " +
                "classFiltered=[] unobserved=0 stale=107 maxAbsence=PT7H4M " +
                "(recording=00000000-0000-0000-0000-0000000000ff)",
            rendered,
        )
    }

    @Test
    fun `maxAbsence renders as n slash a when nothing matched`() {
        // Every track was new, so no absence was measurable at all. Distinguishing this from
        // "matched, but with a zero-length absence" is the point of the nullable field.
        assertTrue(summary(newCount = 2).render().contains("maxAbsence=n/a"))
    }

    @Test
    fun `maxAbsence reports a below-threshold absence, which is what the threshold is tuned against`() {
        // The number the operator needs: the largest absence seen even when nothing reappeared.
        val rendered = summary(newCount = 1, matched = 4, maxAbsence = Duration.ofMinutes(40)).render()

        assertTrue(rendered.contains("maxAbsence=PT40M"), rendered)
    }

    @Test
    fun `an ordinary recording with nothing but matches is not worth logging`() {
        assertFalse(summary(matched = 12, stale = 95, maxAbsence = Duration.ofSeconds(40)).worthLogging)
    }

    @Test
    fun `a new track, a reappearance, an unobserved absence or a class-filtered one are each worth logging`() {
        assertTrue(summary(newCount = 1).worthLogging)
        assertTrue(summary(reappeared = listOf(ClassAbsence("person", Duration.ofHours(3)))).worthLogging)
        assertTrue(summary(unobserved = 1).worthLogging)
        // Without this disjunct a deployment filtering every reappearing class would log nothing
        // for exactly the recordings the operator needs to inspect.
        assertTrue(summary(classFiltered = listOf(ClassAbsence("cow", Duration.ofHours(8)))).worthLogging)
    }
}
