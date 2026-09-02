package ru.zinin.frigate.analyzer.ai.description.config

import org.springframework.util.unit.DataSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ClaudePropertiesTest {
    private fun props(maxBufferSize: DataSize) =
        ClaudeProperties(
            oauthToken = "token",
            model = "opus",
            cliPath = "",
            workingDirectory = "/tmp/frigate-analyzer",
            proxy = ClaudeProperties.ProxySection("", "", ""),
            maxBufferSize = maxBufferSize,
        )

    @Test
    fun `max buffer size defaults to 16 MiB`() {
        val props =
            ClaudeProperties(
                oauthToken = "token",
                model = "opus",
                cliPath = "",
                workingDirectory = "/tmp/frigate-analyzer",
                proxy = ClaudeProperties.ProxySection("", "", ""),
            )
        assertEquals(DataSize.ofMegabytes(16), props.maxBufferSize)
    }

    @Test
    fun `zero max buffer size is rejected`() {
        assertFailsWith<IllegalArgumentException> { props(DataSize.ofBytes(0)) }
    }

    @Test
    fun `max buffer size above the Int range is rejected`() {
        assertFailsWith<IllegalArgumentException> { props(DataSize.ofGigabytes(2)) }
    }
}
