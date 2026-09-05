package ru.zinin.frigate.analyzer.ai.description.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DescriptionExceptionTest {
    @Test
    fun `Unauthorized carries detail in message and property`() {
        val e = DescriptionException.Unauthorized("Not signed in")
        assertEquals("Not signed in", e.detail)
        assertEquals("Description provider rejected the credentials: Not signed in", e.message)
    }

    @Test
    fun `Transport without detail keeps the neutral base message`() {
        assertEquals("Description provider transport error", DescriptionException.Transport().message)
    }

    @Test
    fun `Transport with detail appends it`() {
        val e = DescriptionException.Transport(detail = "exit 1: boom")
        assertEquals("Description provider transport error: exit 1: boom", e.message)
    }

    @Test
    fun `InvalidResponse and RateLimited accept a detail`() {
        assertEquals(
            "Description provider returned an invalid response: no structured output",
            DescriptionException.InvalidResponse(detail = "no structured output").message,
        )
        assertEquals(
            "Description provider rate-limited the request: 429",
            DescriptionException.RateLimited(detail = "429").message,
        )
    }

    @Test
    fun `messages never name a provider`() {
        listOf(
            DescriptionException.Timeout(),
            DescriptionException.InvalidResponse(),
            DescriptionException.Transport(),
            DescriptionException.RateLimited(),
            DescriptionException.Unauthorized("x"),
        ).forEach { e ->
            assertFalse(e.message!!.contains("Claude"), "message must be provider-neutral: ${e.message}")
        }
    }

    @Test
    fun `event exposes the auth scope, state, detail and hint`() {
        val event =
            DescriptionProviderAuthEvent(
                authScopeId = "grok:grok-4.6",
                state = DescriptionProviderAuthEvent.State.LOST,
                detail = "Not signed in",
                recoveryHint = "grok login --device-code",
            )
        assertEquals("grok:grok-4.6", event.authScopeId)
        assertEquals(DescriptionProviderAuthEvent.State.LOST, event.state)
    }
}
