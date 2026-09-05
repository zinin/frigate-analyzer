package ru.zinin.frigate.analyzer.core.judge

import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import ru.zinin.frigate.analyzer.ai.description.config.JudgeProperties
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AiJudgeGuardTest {
    private val common =
        DescriptionProperties.CommonSection(
            language = "en",
            shortMaxLength = 200,
            detailedMaxLength = 1500,
            maxFrames = 10,
            queueTimeout = Duration.ofSeconds(30),
            timeout = Duration.ofSeconds(60),
            maxConcurrent = 2,
        )

    @Test
    fun `judge without descriptions fails fast naming both flags`() {
        val e =
            assertFailsWith<IllegalStateException> {
                AiJudgeGuard(
                    DescriptionProperties(enabled = false, provider = "claude", common = common),
                    JudgeProperties(enabled = true),
                ).validate()
            }
        assertTrue(e.message!!.contains("APP_AI_JUDGE_ENABLED"), e.message)
        assertTrue(e.message!!.contains("APP_AI_DESCRIPTION_ENABLED"), e.message)
    }

    @Test
    fun `judge with descriptions enabled does not throw`() {
        AiJudgeGuard(
            DescriptionProperties(enabled = true, provider = "claude", common = common),
            JudgeProperties(enabled = true),
        ).validate()
    }
}
