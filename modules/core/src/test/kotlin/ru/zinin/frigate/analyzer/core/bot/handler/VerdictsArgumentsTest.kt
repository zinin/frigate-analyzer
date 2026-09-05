package ru.zinin.frigate.analyzer.core.bot.handler

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VerdictsArgumentsTest {
    @Test
    fun `no arguments means all cameras and ten rows`() = assertEquals(VerdictsArguments(null, 10), VerdictsArguments.parse("/verdicts"))

    @Test
    fun `camera only`() = assertEquals(VerdictsArguments("cam2", 10), VerdictsArguments.parse("/verdicts cam2"))

    @Test
    fun `camera and count`() = assertEquals(VerdictsArguments("cam2", 20), VerdictsArguments.parse("/verdicts   cam2  20"))

    @Test
    fun `count only`() = assertEquals(VerdictsArguments(null, 5), VerdictsArguments.parse("/verdicts 5"))

    @Test
    fun `bot mention suffix is tolerated`() =
        assertEquals(VerdictsArguments("cam2", 10), VerdictsArguments.parse("/verdicts@frigate_bot cam2"))

    @Test
    fun `count out of range or extra tokens is invalid`() {
        assertNull(VerdictsArguments.parse("/verdicts cam2 0"))
        assertNull(VerdictsArguments.parse("/verdicts cam2 31"))
        assertNull(VerdictsArguments.parse("/verdicts cam2 20 extra"))
    }
}
