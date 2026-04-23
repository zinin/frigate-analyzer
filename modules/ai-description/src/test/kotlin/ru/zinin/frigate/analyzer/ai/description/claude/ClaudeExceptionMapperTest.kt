package ru.zinin.frigate.analyzer.ai.description.claude

import com.fasterxml.jackson.core.JsonParseException
import kotlinx.coroutines.CancellationException
import org.springaicommunity.claude.agent.sdk.exceptions.ClaudeSDKException
import org.springaicommunity.claude.agent.sdk.exceptions.TransportException
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ClaudeExceptionMapperTest {
    private val mapper = ClaudeExceptionMapper()

    @Test
    fun `TransportException maps to Transport`() {
        val e = mapper.map(TransportException("socket closed"))
        assertIs<DescriptionException.Transport>(e)
    }

    @Test
    fun `429 with http context maps to RateLimited`() {
        val e = mapper.map(ClaudeSDKException("HTTP 429 rate limit exceeded"))
        assertIs<DescriptionException.RateLimited>(e)
    }

    @Test
    fun `rate limit text maps to RateLimited`() {
        val e = mapper.map(ClaudeSDKException("request was rate limited"))
        assertIs<DescriptionException.RateLimited>(e)
    }

    @Test
    fun `bare 429 in unrelated text does NOT map to RateLimited`() {
        val e = mapper.map(ClaudeSDKException("process exited with code 429 unknown"))
        assertIs<DescriptionException.Transport>(e)
    }

    @Test
    fun `generic ClaudeSDKException maps to Transport`() {
        val e = mapper.map(ClaudeSDKException("process exited with code 1"))
        assertIs<DescriptionException.Transport>(e)
    }

    @Test
    fun `JsonParseException maps to InvalidResponse`() {
        val e = mapper.map(JsonParseException(null, "bad json"))
        assertIs<DescriptionException.InvalidResponse>(e)
    }

    @Test
    fun `unknown Throwable maps to Transport`() {
        val e = mapper.map(IllegalStateException("oops"))
        assertIs<DescriptionException.Transport>(e)
    }

    @Test
    fun `CancellationException is rethrown as-is (not wrapped)`() {
        val cancellation = CancellationException("cancelled by scope")
        val caught = assertFailsWith<CancellationException> { mapper.map(cancellation) }
        assert(caught === cancellation)
    }
}
