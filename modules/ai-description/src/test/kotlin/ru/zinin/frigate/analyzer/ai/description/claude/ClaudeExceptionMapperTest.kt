package ru.zinin.frigate.analyzer.ai.description.claude

import com.fasterxml.jackson.core.JsonParseException
import kotlinx.coroutines.CancellationException
import org.assertj.core.api.Assertions.assertThat
import org.springaicommunity.claude.agent.sdk.exceptions.ClaudeSDKException
import org.springaicommunity.claude.agent.sdk.exceptions.TransportException
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import tools.jackson.core.exc.StreamReadException
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
    fun `TransportException carrying rate-limit text maps to RateLimited`() {
        // CLI-side 429 errors arrive from the SDK as TransportException (subclass of
        // ClaudeSDKException). The mapper must recognise rate-limit regardless of which
        // concrete subclass the SDK picked.
        val e = mapper.map(TransportException("Anthropic API error 429: rate limit exceeded"))
        assertIs<DescriptionException.RateLimited>(e)
    }

    @Test
    fun `429 with http context maps to RateLimited`() {
        val e = mapper.map(ClaudeSDKException("HTTP 429 rate limit exceeded"))
        assertIs<DescriptionException.RateLimited>(e)
    }

    @Test
    fun `429 with anthropic-api context maps to RateLimited`() {
        val e = mapper.map(ClaudeSDKException("Anthropic API returned 429"))
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
    fun `authentication_error maps to Unauthorized`() {
        val e = mapper.map(ClaudeSDKException("API Error: 401 {\"type\":\"authentication_error\"}"))
        assertIs<DescriptionException.Unauthorized>(e)
        assert(e.detail.contains("authentication_error"))
    }

    @Test
    fun `invalid api key maps to Unauthorized`() {
        assertIs<DescriptionException.Unauthorized>(mapper.map(ClaudeSDKException("Invalid API key provided")))
    }

    @Test
    fun `expired oauth token maps to Unauthorized`() {
        assertIs<DescriptionException.Unauthorized>(mapper.map(ClaudeSDKException("OAuth token has expired")))
    }

    @Test
    fun `a transport failure that merely mentions the oauth token stays Transport`() {
        // Unauthorized не повторяется и шлёт владельцу требование перелогиниться: упоминание токена
        // в тексте сетевого сбоя не повод для такого сообщения.
        assertIs<DescriptionException.Transport>(
            mapper.map(ClaudeSDKException("Failed to refresh OAuth token: connection reset by peer")),
        )
    }

    @Test
    fun `Unauthorized wins over rate-limit words in the same message`() {
        val e = mapper.map(ClaudeSDKException("authentication_error while checking rate limit"))
        assertIs<DescriptionException.Unauthorized>(e)
    }

    @Test
    fun `JsonParseException maps to InvalidResponse`() {
        val e = mapper.map(JsonParseException(null, "bad json"))
        assertIs<DescriptionException.InvalidResponse>(e)
    }

    @Test
    fun `map wraps tools_jackson JacksonException as InvalidResponse`() {
        val cause = StreamReadException(null, "boom")
        val result = mapper.map(cause)
        assertThat(result).isInstanceOf(DescriptionException.InvalidResponse::class.java)
        assertThat(result.cause).isSameAs(cause)
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
