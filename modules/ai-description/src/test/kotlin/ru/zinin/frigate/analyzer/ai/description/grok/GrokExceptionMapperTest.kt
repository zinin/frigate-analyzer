package ru.zinin.frigate.analyzer.ai.description.grok

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GrokExceptionMapperTest {
    private val mapper = GrokExceptionMapper()

    @Test
    fun `not signed in is Unauthorized with the message as detail`() {
        val e = mapper.fromFailure(1, "Not signed in. To authenticate without a browser, run:\n  grok login --device-code", "")
        assertIs<DescriptionException.Unauthorized>(e)
        assertTrue(e.detail.startsWith("Not signed in"))
    }

    @Test
    fun `every auth marker is Unauthorized`() {
        listOf(
            "please run grok login",
            "Not authenticated",
            "401 Unauthorized",
            "token refresh failed: invalid_grant",
            "Refresh token rejected by auth.x.ai",
            "Authentication failed",
        ).forEach { message ->
            assertIs<DescriptionException.Unauthorized>(mapper.fromFailure(1, message, ""), message)
        }
    }

    @Test
    fun `rate limit texts are RateLimited`() {
        listOf("Rate limit exceeded", "Too Many Requests", "HTTP 429 from proxy").forEach { message ->
            assertIs<DescriptionException.RateLimited>(mapper.fromFailure(1, message, ""), message)
        }
    }

    @Test
    fun `auth wins over rate limit words`() {
        assertIs<DescriptionException.Unauthorized>(mapper.fromFailure(1, "Not signed in; rate limit unknown", ""))
    }

    @Test
    fun `bare 4290 does not match 429`() {
        assertIs<DescriptionException.Transport>(mapper.fromFailure(1, "session 4290 lost", ""))
    }

    @Test
    fun `other error message is Transport with exit code and message`() {
        val e = mapper.fromFailure(1, "Couldn't set model to nope", "")
        assertIs<DescriptionException.Transport>(e)
        assertEquals("Description provider transport error: exit 1: Couldn't set model to nope", e.message)
    }

    @Test
    fun `without error json the stderr tail becomes the detail`() {
        val e = mapper.fromFailure(143, null, "Terminated\n")
        assertIs<DescriptionException.Transport>(e)
        assertTrue(e.message!!.contains("exit 143: Terminated"))
    }

    @Test
    fun `without error json and stderr the exit code alone is the detail`() {
        val e = mapper.fromFailure(2, null, "   ")
        assertEquals("Description provider transport error: exit 2: grok exited with code 2", e.message)
    }

    @Test
    fun `stop reasons map per spec`() {
        assertIs<DescriptionException.InvalidResponse>(mapper.fromStopReason("max_tokens"))
        assertIs<DescriptionException.InvalidResponse>(mapper.fromStopReason("refusal"))
        assertIs<DescriptionException.InvalidResponse>(mapper.fromStopReason("max_turn_requests"))
        assertIs<DescriptionException.InvalidResponse>(mapper.fromStopReason(null))
        assertIs<DescriptionException.InvalidResponse>(mapper.fromStopReason("end_turn"))
        assertIs<DescriptionException.Transport>(mapper.fromStopReason("cancelled"))
    }
}
