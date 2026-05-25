package ru.zinin.frigate.analyzer.telegram.filter

import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.RawChatId
import dev.inmo.tgbotapi.types.Username
import dev.inmo.tgbotapi.types.chat.CommonUser
import dev.inmo.tgbotapi.types.message.abstracts.PrivateContentMessage
import dev.inmo.tgbotapi.types.message.content.MessageContent
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto
import ru.zinin.frigate.analyzer.telegram.model.UserRole
import ru.zinin.frigate.analyzer.telegram.model.UserStatus
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class AuthorizationFilterTest {
    private val userService = mockk<TelegramUserService>()
    private val filter = AuthorizationFilter(userService)

    @BeforeEach
    fun setupOwnerCheck() {
        // userService.isOwner делает case-insensitive сравнение с properties.owner ("ownerUser").
        // Мокаем тут единожды; per-test тест с другим регистром переопределит.
        every { userService.isOwner(any()) } answers {
            val u = firstArg<String?>()
            u != null && u.equals("ownerUser", ignoreCase = true)
        }
    }

    private fun makeUser(
        username: String,
        status: UserStatus,
    ): TelegramUserDto =
        TelegramUserDto(
            id = UUID.randomUUID(),
            username = username,
            chatId = 12345L,
            userId = 67890L,
            firstName = "First",
            lastName = "Last",
            status = status,
            creationTimestamp = Instant.now(),
            activationTimestamp = if (status == UserStatus.ACTIVE) Instant.now() else null,
            languageCode = "en",
            notificationsRecordingEnabled = true,
            notificationsSignalEnabled = true,
        )

    @Test
    fun `authorize(username) returns Active(OWNER) for active owner record`() =
        runTest {
            val owner = makeUser("ownerUser", UserStatus.ACTIVE)
            coEvery { userService.findByUsername("ownerUser") } returns owner

            val result = filter.authorize("ownerUser")

            assertEquals(AuthResult.Active(UserRole.OWNER, owner), result)
        }

    @Test
    fun `authorize(username) returns Active(USER) for active non-owner record`() =
        runTest {
            val user = makeUser("alice", UserStatus.ACTIVE)
            coEvery { userService.findByUsername("alice") } returns user

            val result = filter.authorize("alice")

            assertEquals(AuthResult.Active(UserRole.USER, user), result)
        }

    @Test
    fun `authorize(username) returns NeedsActivation for INVITED owner record`() =
        runTest {
            val owner = makeUser("ownerUser", UserStatus.INVITED)
            coEvery { userService.findByUsername("ownerUser") } returns owner

            val result = filter.authorize("ownerUser")

            assertEquals(AuthResult.NeedsActivation, result)
        }

    @Test
    fun `authorize(username) returns NeedsActivation for INVITED non-owner record`() =
        runTest {
            val user = makeUser("alice", UserStatus.INVITED)
            coEvery { userService.findByUsername("alice") } returns user

            val result = filter.authorize("alice")

            assertEquals(AuthResult.NeedsActivation, result)
        }

    @Test
    fun `authorize(username) returns NeedsActivation for owner without DB record`() =
        runTest {
            coEvery { userService.findByUsername("ownerUser") } returns null

            val result = filter.authorize("ownerUser")

            assertEquals(AuthResult.NeedsActivation, result)
        }

    @Test
    fun `authorize(username) returns Unauthorized for non-owner without DB record`() =
        runTest {
            coEvery { userService.findByUsername("stranger") } returns null

            val result = filter.authorize("stranger")

            assertEquals(AuthResult.Unauthorized, result)
        }

    @Test
    fun `authorize(message) returns Unauthorized when PrivateContentMessage has null username`() =
        runTest {
            // Реальный PrivateContentMessage с user.username == null → extractUsername вернёт null.
            // Используем реальный CommonUser, т.к. MockK 1.14.x не умеет стабильно мокать inline
            // value class Username и её аксессоры (компилируются в static -impl методы).
            val realUser =
                CommonUser(
                    id = ChatId(RawChatId(1L)),
                    firstName = "NoUsername",
                    username = null,
                )
            val message =
                mockk<PrivateContentMessage<MessageContent>>(relaxed = true) {
                    every { user } returns realUser
                }

            val result = filter.authorize(message)

            assertEquals(AuthResult.Unauthorized, result)
        }

    @Test
    fun `authorize(message) returns Active(USER) for PrivateContentMessage with active user`() =
        runTest {
            val activeUser = makeUser("alice", UserStatus.ACTIVE)
            coEvery { userService.findByUsername("alice") } returns activeUser

            // Реальный CommonUser с Username("@alice") — .withoutAt вернёт "alice".
            // MockK 1.14.x не может стабильно мокать inline value class Username.
            val realUser =
                CommonUser(
                    id = ChatId(RawChatId(1L)),
                    firstName = "Test",
                    username = Username("@alice"),
                )
            val message =
                mockk<PrivateContentMessage<MessageContent>>(relaxed = true) {
                    every { user } returns realUser
                }

            val result = filter.authorize(message)

            assertEquals(AuthResult.Active(UserRole.USER, activeUser), result)
        }

    @Test
    fun `authorize(username) returns Active(OWNER) for owner with different case`() =
        runTest {
            // env-конфиг: "ownerUser". Telegram отдаёт: "OWNERUSER". Должно работать благодаря
            // userService.isOwner(case-insensitive). Запись в БД сохранена в том регистре, как
            // прислал Telegram при /start (т.е. "OWNERUSER").
            val owner = makeUser("OWNERUSER", UserStatus.ACTIVE)
            coEvery { userService.findByUsername("OWNERUSER") } returns owner

            val result = filter.authorize("OWNERUSER")

            assertEquals(AuthResult.Active(UserRole.OWNER, owner), result)
        }
}
