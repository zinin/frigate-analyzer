package ru.zinin.frigate.analyzer.ai.description.claude

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Клиент строится до первого обращения к SDK, поэтому фабрика бросает: аргументы до неё уже дошли,
 * а дальше в тесте делать нечего — реальный `ClaudeAsyncClient` тут не поднять.
 */
class DefaultClaudeInvokerTest {
    private val clientFactory = mockk<ClaudeAsyncClientFactory>()

    @Test
    fun `the SDK work timeout follows the budget of the call, not the description timeout`() =
        runTest {
            every { clientFactory.create(any(), any(), any()) } throws IllegalStateException("stop at the factory")
            val invoker = DefaultClaudeInvoker(clientFactory)

            assertFailsWith<IllegalStateException> {
                invoker.invoke("prompt", "opus", "system", Duration.ofSeconds(120))
            }

            // Бюджет вызова плюс запас в 5 с: таймаут корутины обязан сработать первым, а SDK
            // остаётся страховкой. Судья с APP_AI_JUDGE_TIMEOUT больше APP_AI_DESCRIPTION_TIMEOUT
            // иначе обрывался бы по чужой настройке и приходил бы как Transport.
            verify(exactly = 1) { clientFactory.create(Duration.ofSeconds(125), "opus", "system") }
        }
}
