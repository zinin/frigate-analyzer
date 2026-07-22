package ru.zinin.frigate.analyzer.telegram.bot.handler.notifications

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IsCancelInputTest {
    @Test
    fun `explicit cancel is a cancel`() {
        assertTrue(isCancelInput("/cancel"))
    }

    // Any other command is a cancel too: the waiter receives commands independently of onCommand,
    // so without this the command would run AND draw a bogus "unknown timezone" reply.
    @Test
    fun `unrelated command is a cancel`() {
        assertTrue(isCancelInput("/status"))
    }

    @Test
    fun `notifications command is a cancel`() {
        assertTrue(isCancelInput("/notifications"))
    }

    @Test
    fun `zone id with a slash inside is not a cancel`() {
        assertFalse(isCancelInput("Europe/Moscow"))
    }

    @Test
    fun `plain zone id is not a cancel`() {
        assertFalse(isCancelInput("UTC"))
    }

    @Test
    fun `empty input is not a cancel`() {
        assertFalse(isCancelInput(""))
    }
}
