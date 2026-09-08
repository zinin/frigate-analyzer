package ru.zinin.frigate.analyzer.ai.description.claude

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.springaicommunity.claude.agent.sdk.ClaudeAsyncClient
import org.springaicommunity.claude.agent.sdk.types.AssistantMessage
import org.springaicommunity.claude.agent.sdk.types.ContentBlock
import org.springaicommunity.claude.agent.sdk.types.Message
import org.springaicommunity.claude.agent.sdk.types.TextBlock
import org.springaicommunity.claude.agent.sdk.types.ToolUseBlock
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
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
                invoker.invoke("prompt", "opus", "system", Duration.ofSeconds(120), 1)
            }

            // Бюджет вызова плюс запас в 5 с: таймаут корутины обязан сработать первым, а SDK
            // остаётся страховкой. Судья с APP_AI_JUDGE_TIMEOUT больше APP_AI_DESCRIPTION_TIMEOUT
            // иначе обрывался бы по чужой настройке и приходил бы как Transport.
            verify(exactly = 1) { clientFactory.create(Duration.ofSeconds(125), "opus", "system") }
        }

    /**
     * Кадры доезжают до Claude только через Read: ответ без единого вызова модель написала, не видя
     * картинки. Иногда это проза, и её ловит парсер, а иногда — валидный JSON про недоступное
     * изображение, который ушёл бы получателям вместо описания, не оставив в логах ничего.
     */
    @Test
    fun `an answer produced without a single Read is rejected`() =
        runTest {
            val invoker = invokerReturning(assistant(TextBlock("""{"short":"s"}""")))

            assertFailsWith<DescriptionException.InvalidResponse> {
                invoker.invoke("prompt", "opus", "system", Duration.ofSeconds(60), 1)
            }
        }

    @Test
    fun `an answer that follows a Read call passes through`() =
        runTest {
            val invoker =
                invokerReturning(
                    assistant(ToolUseBlock("t1", "Read", mapOf("file_path" to "/tmp/f.jpg"))),
                    assistant(TextBlock("""{"short":"s"}""")),
                )

            assertEquals(
                """{"short":"s"}""",
                invoker.invoke("prompt", "opus", "system", Duration.ofSeconds(60), 1),
            )
        }

    /**
     * Прочитаны не все кадры: ответ всё равно опирается на картинку, а повтор был бы платой зря.
     * Ветку легко «ужесточить», прочитав условие `reads < framesToRead` как повод для отказа.
     */
    @Test
    fun `an answer that read some of the frames is not rejected`() =
        runTest {
            val invoker =
                invokerReturning(
                    assistant(ToolUseBlock("t1", "Read", mapOf("file_path" to "/tmp/f1.jpg"))),
                    assistant(TextBlock("""{"short":"s"}""")),
                )

            assertEquals(
                """{"short":"s"}""",
                invoker.invoke("prompt", "opus", "system", Duration.ofSeconds(60), 3),
            )
        }

    /** Запрос без кадров читать нечего — требование Read к нему не относится. */
    @Test
    fun `an answer to a frameless request is not required to read anything`() =
        runTest {
            val invoker = invokerReturning(assistant(TextBlock("plain")))

            assertEquals("plain", invoker.invoke("prompt", "opus", "system", Duration.ofSeconds(60), 0))
        }

    private fun assistant(vararg blocks: ContentBlock): Message = AssistantMessage.of(blocks.toList())

    private fun invokerReturning(vararg messages: Message): DefaultClaudeInvoker {
        val turn = mockk<ClaudeAsyncClient.TurnSpec>()
        every { turn.messages() } returns Flux.fromIterable(messages.toList())
        val client = mockk<ClaudeAsyncClient>()
        every { client.connect(any<String>()) } returns turn
        every { client.close() } returns Mono.empty()
        every { clientFactory.create(any(), any(), any()) } returns client
        return DefaultClaudeInvoker(clientFactory)
    }
}
