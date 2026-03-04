package ru.zinin.frigate.analyzer.telegram.bot.handler.quickexport

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.requests.abstracts.Request
import dev.inmo.tgbotapi.requests.answers.AnswerCallbackQuery
import dev.inmo.tgbotapi.requests.edit.reply_markup.EditChatMessageReplyMarkup
import dev.inmo.tgbotapi.requests.send.SendTextMessage
import dev.inmo.tgbotapi.types.CallbackQueryId
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.RawChatId
import dev.inmo.tgbotapi.types.Username
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.chat.CommonUser
import dev.inmo.tgbotapi.types.chat.PrivateChatImpl
import dev.inmo.tgbotapi.types.message.abstracts.ContentMessage
import dev.inmo.tgbotapi.types.message.content.MessageContent
import dev.inmo.tgbotapi.types.queries.callback.DataCallbackQuery
import dev.inmo.tgbotapi.types.queries.callback.MessageDataCallbackQuery
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.telegram.config.TelegramProperties
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import ru.zinin.frigate.analyzer.telegram.service.VideoExportService
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuickExportHandlerTest {
    @Nested
    inner class ParseRecordingIdTest {
        @Test
        fun `returns UUID for valid callback data`() {
            val recordingId = UUID.randomUUID()
            val data = "${QuickExportHandler.CALLBACK_PREFIX}$recordingId"

            val result = QuickExportHandler.parseRecordingId(data)

            assertNotNull(result)
            assertEquals(recordingId, result)
        }

        @Test
        fun `returns null for invalid UUID`() {
            val data = "${QuickExportHandler.CALLBACK_PREFIX}invalid-uuid"

            val result = QuickExportHandler.parseRecordingId(data)

            assertNull(result)
        }

        @Test
        fun `returns null for empty string after prefix`() {
            val data = QuickExportHandler.CALLBACK_PREFIX

            val result = QuickExportHandler.parseRecordingId(data)

            assertNull(result)
        }

        @Test
        fun `returns null for data without prefix`() {
            val result = QuickExportHandler.parseRecordingId("not-a-uuid")

            assertNull(result)
        }

        @Test
        fun `returns null for partial UUID`() {
            val data = "${QuickExportHandler.CALLBACK_PREFIX}12345678-1234-1234-1234"

            val result = QuickExportHandler.parseRecordingId(data)

            assertNull(result)
        }
    }

    @Nested
    inner class CallbackPrefixTest {
        @Test
        fun `CALLBACK_PREFIX has correct value`() {
            assertEquals("qe:", QuickExportHandler.CALLBACK_PREFIX)
        }
    }

    @Nested
    inner class CreateExportKeyboardTest {
        @Test
        fun `creates keyboard with single row and single button`() {
            val recordingId = UUID.randomUUID()

            val keyboard = QuickExportHandler.createExportKeyboard(recordingId)

            assertEquals(1, keyboard.keyboard.size, "Should have one row")
            assertEquals(1, keyboard.keyboard[0].size, "Row should have one button")
        }

        @Test
        fun `button has correct text`() {
            val recordingId = UUID.randomUUID()

            val keyboard = QuickExportHandler.createExportKeyboard(recordingId)
            val button = keyboard.keyboard[0][0]

            assertIs<CallbackDataInlineKeyboardButton>(button)
            assertEquals("📹 Экспорт видео", button.text)
        }

        @Test
        fun `button has correct callback data with prefix and recordingId`() {
            val recordingId = UUID.randomUUID()

            val keyboard = QuickExportHandler.createExportKeyboard(recordingId)
            val button = keyboard.keyboard[0][0]

            assertIs<CallbackDataInlineKeyboardButton>(button)
            assertEquals("${QuickExportHandler.CALLBACK_PREFIX}$recordingId", button.callbackData)
        }
    }

    @Nested
    inner class CreateProcessingKeyboardTest {
        @Test
        fun `creates keyboard with single row and single button`() {
            val recordingId = UUID.randomUUID()

            val keyboard = QuickExportHandler.createProcessingKeyboard(recordingId)

            assertEquals(1, keyboard.keyboard.size, "Should have one row")
            assertEquals(1, keyboard.keyboard[0].size, "Row should have one button")
        }

        @Test
        fun `button has processing text`() {
            val recordingId = UUID.randomUUID()

            val keyboard = QuickExportHandler.createProcessingKeyboard(recordingId)
            val button = keyboard.keyboard[0][0]

            assertIs<CallbackDataInlineKeyboardButton>(button)
            assertEquals("⚙️ Экспорт...", button.text)
        }

        @Test
        fun `button has correct callback data with prefix and recordingId`() {
            val recordingId = UUID.randomUUID()

            val keyboard = QuickExportHandler.createProcessingKeyboard(recordingId)
            val button = keyboard.keyboard[0][0]

            assertIs<CallbackDataInlineKeyboardButton>(button)
            assertEquals("${QuickExportHandler.CALLBACK_PREFIX}$recordingId", button.callbackData)
        }
    }

    @Nested
    inner class HandleTest {
        private val bot = mockk<TelegramBot>()
        private val videoExportService = mockk<VideoExportService>()
        private val userService = mockk<TelegramUserService>()
        private val properties =
            TelegramProperties(
                enabled = true,
                botToken = "test-token",
                owner = "testowner",
                sendVideoTimeout = Duration.ofMinutes(3),
            )
        private val handler = QuickExportHandler(bot, videoExportService, userService, properties)

        private val recordingId = UUID.randomUUID()

        init {
            coEvery { userService.findActiveByUsername(any()) } returns mockk()
        }

        /**
         * Creates a [MessageDataCallbackQuery] with the given [user].
         *
         * Uses real [PrivateChatImpl] to avoid MockK issues with tgbotapi inline class
         * hierarchies (BusinessChatImpl ClassCastException on getId).
         */
        private fun createCallbackWithUser(
            user: CommonUser,
            callbackId: String = "test-callback-id",
        ): MessageDataCallbackQuery {
            val realChat =
                PrivateChatImpl(
                    id = ChatId(RawChatId(12345L)),
                    firstName = "TestChat",
                )
            val mockMessage =
                mockk<ContentMessage<MessageContent>>(relaxed = true) {
                    every { chat } returns realChat
                }
            return MessageDataCallbackQuery(
                id = CallbackQueryId(callbackId),
                from = user,
                chatInstance = "test-instance",
                message = mockMessage,
                data = "${QuickExportHandler.CALLBACK_PREFIX}$recordingId",
            )
        }

        private fun createMessageCallback() =
            createCallbackWithUser(
                user =
                    CommonUser(
                        id = ChatId(RawChatId(1L)),
                        firstName = "Test",
                        username = Username("@testuser"),
                    ),
            )

        /** Creates a [MessageDataCallbackQuery] from the bot owner (properties.owner). */
        private fun createOwnerCallback() =
            createCallbackWithUser(
                user =
                    CommonUser(
                        id = ChatId(RawChatId(3L)),
                        firstName = "Owner",
                        username = Username("@${properties.owner}"),
                    ),
                callbackId = "test-callback-owner",
            )

        /** Creates a [MessageDataCallbackQuery] with a user that has no username set. */
        private fun createMessageCallbackWithoutUsername() =
            createCallbackWithUser(
                user =
                    CommonUser(
                        id = ChatId(RawChatId(2L)),
                        firstName = "NoUsername",
                        username = null,
                    ),
                callbackId = "test-callback-no-username",
            )

        @Test
        fun `should export video for authorized user`() =
            runTest {
                // given
                val callback = createMessageCallback()
                val tempFile = Files.createTempFile("test-export", ".mp4")

                // Capture all bot requests and return type-appropriate values
                // (AnswerCallbackQuery expects Boolean, everything else expects ContentMessage)
                val capturedRequests = mutableListOf<Request<*>>()
                coEvery { bot.execute(any<Request<*>>()) } coAnswers {
                    val request = firstArg<Request<*>>()
                    capturedRequests.add(request)
                    if (request is AnswerCallbackQuery) {
                        true
                    } else {
                        mockk<ContentMessage<MessageContent>>(relaxed = true)
                    }
                }
                coEvery { videoExportService.exportByRecordingId(eq(recordingId), any(), any()) } returns tempFile
                coEvery { videoExportService.cleanupExportFile(tempFile) } returns Unit

                try {
                    // when
                    handler.handle(callback)
                } finally {
                    Files.deleteIfExists(tempFile)
                }

                // then

                // 1. Callback was answered (removes loading indicator)
                val answerRequests = capturedRequests.filterIsInstance<AnswerCallbackQuery>()
                assertTrue(answerRequests.isNotEmpty(), "Expected callback to be answered")

                // 2. Export was called with the correct recordingId
                coVerify { videoExportService.exportByRecordingId(eq(recordingId), any(), any()) }

                // 3. Video was sent (request is neither answer, edit, nor text message)
                val knownRequestTypes =
                    capturedRequests.count {
                        it is AnswerCallbackQuery || it is EditChatMessageReplyMarkup || it is SendTextMessage
                    }
                assertTrue(
                    capturedRequests.size > knownRequestTypes,
                    "Expected a sendVideo request beyond answer/edit/text requests, " +
                        "but all ${capturedRequests.size} requests were of known types",
                )

                // 4. Exported file was cleaned up
                coVerify { videoExportService.cleanupExportFile(tempFile) }

                // 5. Button was restored to export state after completion
                val editRequests = capturedRequests.filterIsInstance<EditChatMessageReplyMarkup>()
                assertTrue(editRequests.size >= 2, "Expected at least 2 edit calls (processing + restore)")
                val restoreMarkup = editRequests.last().replyMarkup
                assertNotNull(restoreMarkup)
                assertIs<InlineKeyboardMarkup>(restoreMarkup)
                assertEquals(
                    "📹 Экспорт видео",
                    (restoreMarkup.keyboard[0][0] as CallbackDataInlineKeyboardButton).text,
                )

                // 6. No error messages were sent (happy path)
                val sendTextRequests = capturedRequests.filterIsInstance<SendTextMessage>()
                assertTrue(
                    sendTextRequests.isEmpty(),
                    "Expected no error messages in happy path, but got: ${sendTextRequests.map { it.text }}",
                )
            }

        @Test
        fun `handle with non-MessageDataCallbackQuery returns early`() =
            runTest {
                val callback =
                    mockk<DataCallbackQuery> {
                        every { data } returns "${QuickExportHandler.CALLBACK_PREFIX}${UUID.randomUUID()}"
                        every { user } returns mockk(relaxed = true)
                    }

                handler.handle(callback)

                // No interaction with bot or services (early return since cast to MessageDataCallbackQuery fails)
                coVerify(exactly = 0) { bot.execute(any<Request<*>>()) }
                coVerify(exactly = 0) { videoExportService.exportByRecordingId(any(), any(), any()) }
            }

        @Test
        fun `handle rejects user without username with set username message`() =
            runTest {
                val callback = createMessageCallbackWithoutUsername()

                val capturedRequests = mutableListOf<Request<*>>()
                coEvery { bot.execute(capture(capturedRequests)) } returns mockk(relaxed = true)

                handler.handle(callback)

                // Verify answer was called with the correct "set username" message
                val answerRequests = capturedRequests.filterIsInstance<AnswerCallbackQuery>()
                assertTrue(
                    answerRequests.any { it.text == "Пожалуйста, установите username в настройках Telegram." },
                    "Expected AnswerCallbackQuery with 'set username' text, but got: ${answerRequests.map { it.text }}",
                )
                // Verify no export was attempted
                coVerify(exactly = 0) { videoExportService.exportByRecordingId(any(), any(), any()) }
                // Verify userService was never consulted (early return before auth check)
                coVerify(exactly = 0) { userService.findActiveByUsername(any()) }
            }

        @Test
        fun `handle rejects unauthorized user with username`() =
            runTest {
                val callback = createMessageCallback()

                coEvery { userService.findActiveByUsername("testuser") } returns null
                val capturedRequests = mutableListOf<Request<*>>()
                coEvery { bot.execute(capture(capturedRequests)) } returns mockk(relaxed = true)

                handler.handle(callback)

                // Verify no export was attempted
                coVerify(exactly = 0) { videoExportService.exportByRecordingId(any(), any(), any()) }

                // Verify answer was called with unauthorized message from properties
                val answerRequests = capturedRequests.filterIsInstance<AnswerCallbackQuery>()
                assertTrue(
                    answerRequests.any { it.text == properties.unauthorizedMessage },
                    "Expected AnswerCallbackQuery with text '${properties.unauthorizedMessage}', but got: ${answerRequests.map {
                        it.text
                    }}",
                )

                // Verify no video was sent (only AnswerCallbackQuery should have been executed)
                val nonAnswerRequests = capturedRequests.filter { it !is AnswerCallbackQuery }
                assertTrue(
                    nonAnswerRequests.isEmpty(),
                    "Expected only AnswerCallbackQuery requests, but also found: " +
                        nonAnswerRequests.map { it::class.simpleName },
                )

                // Verify userService was consulted for authorization check
                coVerify { userService.findActiveByUsername("testuser") }
            }

        @Test
        fun `handle allows owner access even when userService returns null`() =
            runTest {
                val callback = createOwnerCallback()
                val tempFile = Files.createTempFile("test-export", ".mp4")

                coEvery { userService.findActiveByUsername(properties.owner) } returns null
                coEvery { bot.execute(any<Request<*>>()) } returns mockk(relaxed = true)
                coEvery { videoExportService.exportByRecordingId(eq(recordingId), any(), any()) } returns tempFile
                coEvery { videoExportService.cleanupExportFile(tempFile) } returns Unit

                try {
                    handler.handle(callback)
                } finally {
                    Files.deleteIfExists(tempFile)
                }

                // Verify export was called (owner is authorized regardless of userService)
                coVerify { videoExportService.exportByRecordingId(eq(recordingId), any(), any()) }
            }

        @Test
        fun `handle allows active user access and performs export`() =
            runTest {
                val callback = createMessageCallback()
                val tempFile = Files.createTempFile("test-export", ".mp4")

                coEvery { userService.findActiveByUsername("testuser") } returns mockk()
                coEvery { bot.execute(any<Request<*>>()) } returns mockk(relaxed = true)
                coEvery { videoExportService.exportByRecordingId(eq(recordingId), any(), any()) } returns tempFile
                coEvery { videoExportService.cleanupExportFile(tempFile) } returns Unit

                try {
                    handler.handle(callback)
                } finally {
                    Files.deleteIfExists(tempFile)
                }

                // Verify export was called (active user is authorized)
                coVerify { videoExportService.exportByRecordingId(eq(recordingId), any(), any()) }
                // Verify userService was consulted
                coVerify { userService.findActiveByUsername("testuser") }
            }

        @Test
        fun `handle switches button to processing then restores after export`() =
            runTest {
                val callback = createMessageCallback()
                val tempFile = Files.createTempFile("test-export", ".mp4")

                val capturedRequests = mutableListOf<Request<*>>()
                coEvery { bot.execute(capture(capturedRequests)) } returns mockk(relaxed = true)
                coEvery { videoExportService.exportByRecordingId(recordingId) } returns tempFile
                coEvery { videoExportService.cleanupExportFile(tempFile) } returns Unit

                try {
                    handler.handle(callback)
                } finally {
                    Files.deleteIfExists(tempFile)
                }

                // Find all EditChatMessageReplyMarkup requests
                val editRequests = capturedRequests.filterIsInstance<EditChatMessageReplyMarkup>()
                assertTrue(editRequests.size >= 2, "Expected at least 2 editMessageReplyMarkup calls, got ${editRequests.size}")

                // First edit: processing keyboard with "⚙️ Экспорт..."
                val processingMarkup = editRequests[0].replyMarkup
                assertNotNull(processingMarkup, "Processing keyboard must not be null")
                assertIs<InlineKeyboardMarkup>(processingMarkup)
                val processingButton = processingMarkup.keyboard[0][0]
                assertIs<CallbackDataInlineKeyboardButton>(processingButton)
                assertEquals("⚙️ Экспорт...", processingButton.text)
                assertEquals("${QuickExportHandler.CALLBACK_PREFIX}$recordingId", processingButton.callbackData)

                // Last edit: restored export keyboard with "📹 Экспорт видео"
                val restoreMarkup = editRequests.last().replyMarkup
                assertNotNull(restoreMarkup, "Restored keyboard must not be null")
                assertIs<InlineKeyboardMarkup>(restoreMarkup)
                val exportButton = restoreMarkup.keyboard[0][0]
                assertIs<CallbackDataInlineKeyboardButton>(exportButton)
                assertEquals("📹 Экспорт видео", exportButton.text)
                assertEquals("${QuickExportHandler.CALLBACK_PREFIX}$recordingId", exportButton.callbackData)
            }

        @Test
        fun `handle continues processing when editMessageReplyMarkup throws`() =
            runTest {
                val callback = createMessageCallback()
                val tempFile = Files.createTempFile("test-export", ".mp4")

                coEvery { bot.execute(any<Request<*>>()) } coAnswers {
                    val request = firstArg<Request<*>>()
                    if (request is EditChatMessageReplyMarkup) {
                        throw RuntimeException("Telegram API error")
                    }
                    mockk(relaxed = true)
                }
                coEvery { videoExportService.exportByRecordingId(recordingId) } returns tempFile
                coEvery { videoExportService.cleanupExportFile(any()) } returns Unit

                try {
                    handler.handle(callback)
                } finally {
                    Files.deleteIfExists(tempFile)
                }

                // Despite editMessageReplyMarkup failing, export was still called
                coVerify { videoExportService.exportByRecordingId(eq(recordingId), any(), any()) }
            }

        @Test
        fun `handle restores button after export error`() =
            runTest {
                val callback = createMessageCallback()

                val capturedRequests = mutableListOf<Request<*>>()
                coEvery { bot.execute(capture(capturedRequests)) } returns mockk(relaxed = true)
                coEvery { videoExportService.exportByRecordingId(recordingId) } throws
                    IllegalArgumentException("Recording not found")

                handler.handle(callback)

                // Find all EditChatMessageReplyMarkup requests
                val editRequests = capturedRequests.filterIsInstance<EditChatMessageReplyMarkup>()
                assertTrue(editRequests.size >= 2, "Expected at least 2 editMessageReplyMarkup calls, got ${editRequests.size}")

                // First edit: processing keyboard
                val processingMarkup = editRequests[0].replyMarkup
                assertNotNull(processingMarkup)
                assertIs<InlineKeyboardMarkup>(processingMarkup)
                assertEquals("⚙️ Экспорт...", (processingMarkup.keyboard[0][0] as CallbackDataInlineKeyboardButton).text)

                // Last edit: restored export keyboard
                val restoreMarkup = editRequests.last().replyMarkup
                assertNotNull(restoreMarkup)
                assertIs<InlineKeyboardMarkup>(restoreMarkup)
                assertEquals(
                    "📹 Экспорт видео",
                    (restoreMarkup.keyboard[0][0] as CallbackDataInlineKeyboardButton).text,
                )
            }

        @Test
        fun `handle calls exportByRecordingId and cleanupExportFile on successful export`() =
            runTest {
                val callback = createMessageCallback()
                val tempFile = Files.createTempFile("test-export", ".mp4")

                coEvery { bot.execute(any<Request<*>>()) } returns mockk(relaxed = true)
                coEvery {
                    videoExportService.exportByRecordingId(eq(recordingId), any(), any())
                } returns tempFile
                coEvery { videoExportService.cleanupExportFile(any()) } returns Unit

                try {
                    handler.handle(callback)
                } finally {
                    Files.deleteIfExists(tempFile)
                }

                // Verify exportByRecordingId was called with the correct recordingId
                coVerify { videoExportService.exportByRecordingId(eq(recordingId), any(), any()) }

                // Verify cleanupExportFile was called with the exported file path
                coVerify { videoExportService.cleanupExportFile(tempFile) }
            }

        @Test
        fun `handle sends timeout message when export exceeds timeout`() =
            runTest {
                val callback = createMessageCallback()

                val capturedRequests = mutableListOf<Request<*>>()
                coEvery { bot.execute(capture(capturedRequests)) } returns mockk(relaxed = true)
                coEvery {
                    videoExportService.exportByRecordingId(eq(recordingId), any(), any())
                } coAnswers {
                    delay(400_000) // 400 seconds exceeds the 300-second (5 min) timeout
                    error("Should not reach here")
                }

                handler.handle(callback)

                // Verify timeout message was sent
                val sendTextRequests = capturedRequests.filterIsInstance<SendTextMessage>()
                assertTrue(
                    sendTextRequests.any { it.text.contains("Экспорт занял слишком много времени") },
                    "Expected timeout message, but got text requests: ${sendTextRequests.map { it.text }}",
                )

                // Verify button was restored after timeout
                val editRequests = capturedRequests.filterIsInstance<EditChatMessageReplyMarkup>()
                assertTrue(editRequests.size >= 2, "Expected at least 2 edit calls (processing + restore)")

                val restoreMarkup = editRequests.last().replyMarkup
                assertNotNull(restoreMarkup)
                assertIs<InlineKeyboardMarkup>(restoreMarkup)
                assertEquals(
                    "📹 Экспорт видео",
                    (restoreMarkup.keyboard[0][0] as CallbackDataInlineKeyboardButton).text,
                )

                // Verify cleanupExportFile was NOT called (videoPath is null on timeout)
                coVerify(exactly = 0) { videoExportService.cleanupExportFile(any()) }
            }

        @Test
        fun `handle sends timeout message when sendVideo exceeds timeout`() =
            runTest {
                val callback = createMessageCallback()
                val tempFile = Files.createTempFile("test-export", ".mp4")

                var videoExportReturned = false
                val capturedRequests = mutableListOf<Request<*>>()
                coEvery { bot.execute(capture(capturedRequests)) } coAnswers {
                    val req = firstArg<Request<*>>()
                    // After export completes, the next non-edit, non-text request is sendVideo — delay it past the timeout
                    if (videoExportReturned && req !is EditChatMessageReplyMarkup && req !is SendTextMessage) {
                        delay(Duration.ofMinutes(5).toMillis())
                    }
                    mockk(relaxed = true)
                }
                coEvery { videoExportService.exportByRecordingId(eq(recordingId), any(), any()) } coAnswers {
                    videoExportReturned = true
                    tempFile
                }
                coEvery { videoExportService.cleanupExportFile(tempFile) } returns Unit

                try {
                    handler.handle(callback)
                } finally {
                    Files.deleteIfExists(tempFile)
                }

                // Verify sendVideo timeout message was sent
                val sendTextRequests = capturedRequests.filterIsInstance<SendTextMessage>()
                assertTrue(
                    sendTextRequests.any { it.text.contains("Не удалось отправить видео") },
                    "Expected sendVideo timeout message, but got text requests: ${sendTextRequests.map { it.text }}",
                )

                // Verify cleanup was still called (in finally block)
                coVerify { videoExportService.cleanupExportFile(tempFile) }

                // Verify button was restored
                val editRequests = capturedRequests.filterIsInstance<EditChatMessageReplyMarkup>()
                assertTrue(editRequests.size >= 2, "Expected at least 2 edit calls (processing + restore)")
                val restoreMarkup = editRequests.last().replyMarkup
                assertNotNull(restoreMarkup)
                assertIs<InlineKeyboardMarkup>(restoreMarkup)
                assertEquals(
                    "📹 Экспорт видео",
                    (restoreMarkup.keyboard[0][0] as CallbackDataInlineKeyboardButton).text,
                )
            }

        @Test
        fun `handle sends error message for not found recording`() =
            runTest {
                val callback = createMessageCallback()

                val capturedRequests = mutableListOf<Request<*>>()
                coEvery { bot.execute(capture(capturedRequests)) } returns mockk(relaxed = true)
                coEvery {
                    videoExportService.exportByRecordingId(eq(recordingId), any(), any())
                } throws IllegalArgumentException("Recording not found")

                handler.handle(callback)

                // Verify "not found" error message
                val sendTextRequests = capturedRequests.filterIsInstance<SendTextMessage>()
                assertTrue(
                    sendTextRequests.any { it.text.contains("Запись не найдена") },
                    "Expected 'not found' error message, but got: ${sendTextRequests.map { it.text }}",
                )
            }

        @Test
        fun `handle sends error message for missing files`() =
            runTest {
                val callback = createMessageCallback()

                val capturedRequests = mutableListOf<Request<*>>()
                coEvery { bot.execute(capture(capturedRequests)) } returns mockk(relaxed = true)
                coEvery {
                    videoExportService.exportByRecordingId(eq(recordingId), any(), any())
                } throws IllegalStateException("Recording files are missing")

                handler.handle(callback)

                // Verify "missing files" error message
                val sendTextRequests = capturedRequests.filterIsInstance<SendTextMessage>()
                assertTrue(
                    sendTextRequests.any { it.text.contains("Файлы записи недоступны") },
                    "Expected 'missing files' error message, but got: ${sendTextRequests.map { it.text }}",
                )
            }

        @Test
        fun `handle sends generic error message for unexpected exceptions`() =
            runTest {
                val callback = createMessageCallback()

                val capturedRequests = mutableListOf<Request<*>>()
                coEvery { bot.execute(capture(capturedRequests)) } returns mockk(relaxed = true)
                coEvery {
                    videoExportService.exportByRecordingId(eq(recordingId), any(), any())
                } throws RuntimeException("Unexpected error")

                handler.handle(callback)

                // Verify generic error message
                val sendTextRequests = capturedRequests.filterIsInstance<SendTextMessage>()
                assertTrue(
                    sendTextRequests.any { it.text.contains("Ошибка экспорта") },
                    "Expected generic error message, but got: ${sendTextRequests.map { it.text }}",
                )
            }

        @Test
        fun `handle propagates CancellationException without catching`() =
            runTest {
                val callback = createMessageCallback()

                coEvery { bot.execute(any<Request<*>>()) } returns mockk(relaxed = true)
                coEvery {
                    videoExportService.exportByRecordingId(eq(recordingId), any(), any())
                } throws CancellationException("Coroutine was cancelled")

                assertFailsWith<CancellationException> {
                    handler.handle(callback)
                }

                // Verify button was NOT restored (CancellationException propagates immediately)
                coVerify(exactly = 0) { videoExportService.cleanupExportFile(any()) }
            }

        @Test
        fun `handle sends not found message and restores button for not found recording`() =
            runTest {
                val callback = createMessageCallback()

                val capturedRequests = mutableListOf<Request<*>>()
                coEvery { bot.execute(capture(capturedRequests)) } returns mockk(relaxed = true)
                coEvery {
                    videoExportService.exportByRecordingId(eq(recordingId), any(), any())
                } throws IllegalArgumentException("Recording not found")

                handler.handle(callback)

                // Verify "not found" error message was sent
                val sendTextRequests = capturedRequests.filterIsInstance<SendTextMessage>()
                assertTrue(
                    sendTextRequests.any { it.text.contains("Запись не найдена") },
                    "Expected 'not found' error message, but got: ${sendTextRequests.map { it.text }}",
                )

                // Verify button was restored after error
                val editRequests = capturedRequests.filterIsInstance<EditChatMessageReplyMarkup>()
                assertTrue(editRequests.size >= 2, "Expected at least 2 edit calls (processing + restore)")

                val restoreMarkup = editRequests.last().replyMarkup
                assertNotNull(restoreMarkup)
                assertIs<InlineKeyboardMarkup>(restoreMarkup)
                assertEquals(
                    "📹 Экспорт видео",
                    (restoreMarkup.keyboard[0][0] as CallbackDataInlineKeyboardButton).text,
                )
            }
    }
}
