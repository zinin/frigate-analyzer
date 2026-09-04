package ru.zinin.frigate.analyzer.telegram.bot.handler.aisettings

import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.RawChatId
import dev.inmo.tgbotapi.types.Username
import dev.inmo.tgbotapi.types.chat.PrivateChatImpl
import dev.inmo.tgbotapi.types.message.abstracts.PrivateContentMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import dev.inmo.tgbotapi.utils.RiskFeature
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.type.filter.AssignableTypeFilter
import ru.zinin.frigate.analyzer.telegram.bot.handler.CommandHandler
import ru.zinin.frigate.analyzer.telegram.dto.AiSettingsViewState
import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto
import ru.zinin.frigate.analyzer.telegram.model.UserRole
import ru.zinin.frigate.analyzer.telegram.model.UserStatus
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiSettingsCommandHandlerTest {
    private val viewStateFactory = mockk<AiSettingsViewStateFactory>()
    private val renderer = mockk<AiSettingsMessageRenderer>()
    private val handler = AiSettingsCommandHandler(viewStateFactory, renderer)

    /**
     * `ownerOnly` — не дубль `requiredRole`: видимость команды определяет именно он.
     * `registerDefaultCommands()` и общий раздел `/help` берут всё, что `filterNot { ownerOnly }`,
     * поэтому без этого флага `/ai` попала бы в меню каждого пользователя и отбивалась бы на клике.
     */
    @Test
    fun `the command is owner-only and takes the free order`() {
        assertEquals("ai", handler.command)
        assertEquals(UserRole.OWNER, handler.requiredRole)
        assertTrue(handler.ownerOnly)
        assertEquals(ORDERS_BY_COMMAND.getValue("ai"), handler.order)
    }

    @Test
    fun `the order layout has no duplicates`() {
        val duplicates =
            ORDERS_BY_COMMAND.entries
                .groupBy({ it.value }, { it.key })
                .filterValues { it.size > 1 }

        assertTrue(duplicates.isEmpty(), "commands sharing one order: $duplicates")
    }

    /**
     * Страховка карты выше: новый `CommandHandler` в модуле обязан появиться и в ней. Сверяется
     * количество, а не имена, потому что имя класса до команды не восстанавливается
     * (`AiSettingsCommandHandler` — это `/ai`), а инстанцировать хендлеры без контекста Spring
     * нельзя: `order` инициализируется в конструкторе.
     */
    @Test
    fun `every command handler of this module is accounted for in the order layout`() {
        val found = scanCommandHandlers(telegram = true, aiDescription = true)

        // `/status` живёт в modules/core и с classpath этого модуля не виден.
        assertEquals(ORDERS_BY_COMMAND.size - 1, found.size, "handlers found: $found")
        assertTrue(found.contains(AiSettingsCommandHandler::class.java.name), "handlers found: $found")
    }

    /** При выключенной фиче команды нет вовсе — значит, нет и пункта в меню владельца. */
    @Test
    fun `the ai command disappears together with the feature`() {
        val found = scanCommandHandlers(telegram = true, aiDescription = false)

        assertFalse(found.contains(AiSettingsCommandHandler::class.java.name), "handlers found: $found")
        assertEquals(ORDERS_BY_COMMAND.size - 2, found.size, "handlers found: $found")
    }

    /** Кандидаты сканируются с условиями: `@ConditionalOnProperty` проверяется по этому окружению. */
    private fun scanCommandHandlers(
        telegram: Boolean,
        aiDescription: Boolean,
    ): List<String> {
        val environment =
            StandardEnvironment().apply {
                propertySources.addFirst(
                    MapPropertySource(
                        "aiSettingsCommandHandlerTest",
                        mapOf(
                            "application.telegram.enabled" to telegram.toString(),
                            "application.ai.description.enabled" to aiDescription.toString(),
                        ),
                    ),
                )
            }
        val scanner = ClassPathScanningCandidateComponentProvider(false, environment)
        scanner.addIncludeFilter(AssignableTypeFilter(CommandHandler::class.java))
        return scanner
            .findCandidateComponents("ru.zinin.frigate.analyzer.telegram")
            .mapNotNull(BeanDefinition::getBeanClassName)
            .sorted()
    }

    @Test
    fun `the screen is built for the language of the owner`() =
        runTest {
            val state = mockk<AiSettingsViewState>()
            coEvery { viewStateFactory.build("ru") } returns state
            every { renderer.render(state) } returns RenderedAiSettings("screen", mockk(relaxed = true))

            val ctx = mockk<BehaviourContext>(relaxed = true)
            with(ctx) { with(handler) { handle(message(), user(languageCode = "ru")) } }

            coVerify(exactly = 1) { viewStateFactory.build("ru") }
            coVerify(exactly = 1) { renderer.render(state) }
        }

    @Test
    fun `a user without a language falls back to en`() =
        runTest {
            val state = mockk<AiSettingsViewState>()
            coEvery { viewStateFactory.build("en") } returns state
            every { renderer.render(state) } returns RenderedAiSettings("screen", mockk(relaxed = true))

            val ctx = mockk<BehaviourContext>(relaxed = true)
            with(ctx) { with(handler) { handle(message(), user(languageCode = null)) } }

            coVerify(exactly = 1) { viewStateFactory.build("en") }
        }

    @Test
    fun `a null user opens nothing`() =
        runTest {
            val ctx = mockk<BehaviourContext>(relaxed = true)
            with(ctx) { with(handler) { handle(message(), null) } }

            coVerify(exactly = 0) { viewStateFactory.build(any()) }
            coVerify(exactly = 0) { renderer.render(any()) }
        }

    /** `PrivateChatImpl` помечен в библиотеке как рискованный; тесту нужен только chatId. */
    @OptIn(RiskFeature::class)
    private fun message() =
        mockk<PrivateContentMessage<TextContent>>(relaxed = true).also {
            every { it.chat } returns PrivateChatImpl(id = ChatId(RawChatId(123L)), username = Username("@owner"))
        }

    private fun user(languageCode: String?) =
        TelegramUserDto(
            id = UUID.randomUUID(),
            username = "owner",
            chatId = 123L,
            userId = 100L,
            firstName = "Owner",
            lastName = null,
            status = UserStatus.ACTIVE,
            creationTimestamp = Instant.now(),
            activationTimestamp = Instant.now(),
            languageCode = languageCode,
        )

    private companion object {
        /**
         * Раскладка `order` целиком, включая `/status` из `modules/core`. Карта ведётся руками:
         * значения инициализируются в конструкторах, а конструкторы требуют бинов, поэтому собрать
         * их рефлексией в модульном тесте нельзя.
         */
        val ORDERS_BY_COMMAND =
            mapOf(
                "start" to 1,
                "help" to 2,
                "export" to 3,
                "timezone" to 4,
                "version" to 5,
                "language" to 6,
                "notifications" to 7,
                "status" to 8,
                "ai" to 9,
                "adduser" to 10,
                "removeuser" to 11,
                "users" to 12,
            )
    }
}
