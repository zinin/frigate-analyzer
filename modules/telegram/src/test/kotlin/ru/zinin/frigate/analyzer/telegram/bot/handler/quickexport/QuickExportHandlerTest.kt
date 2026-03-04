package ru.zinin.frigate.analyzer.telegram.bot.handler.quickexport

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.requests.abstracts.Request
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
import ru.zinin.frigate.analyzer.telegram.filter.AuthorizationFilter
import ru.zinin.frigate.analyzer.telegram.model.UserRole
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

        @Test
        fun `CALLBACK_PREFIX matches TelegramNotificationSender prefix`() {
            assertEquals(
                ru.zinin.frigate.analyzer.telegram.queue.TelegramNotificationSender.CALLBACK_PREFIX,
                QuickExportHandler.CALLBACK_PREFIX,
            )
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
        private val authorizationFilter = mockk<AuthorizationFilter>()
        private val properties =
            TelegramProperties(
                enabled = true,
                botToken = "test-token",
                owner = "testowner",
                sendVideoTimeout = Duration.ofMinutes(3),
            )
        private val handler = QuickExportHandler(bot, videoExportService, authorizationFilter, properties)

        private val recordingId = UUID.randomUUID()

        init {
            coEvery { authorizationFilter.getRole(any<String>()) } returns UserRole.USER
        }

        /**
         * Creates a real [MessageDataCallbackQuery] with real data class instances
         * and only interface mocks (ContentMessage).
         *
         * Uses real PrivateChatImpl to avoid MockK issues with tgbotapi inline class
         * hierarchies (BusinessChatImpl ClassCastException on getId).
         */
        private fun createMessageCallback(): MessageDataCallbackQuery {
            val realChat =
                PrivateChatImpl(
                    id = ChatId(RawChatId(12345L)),
                    firstName = "TestChat",
                )
            val mockMessage =
                mockk<ContentMessage<MessageContent>>(relaxed = true) {
                    every { chat } returns realChat
                }
            val user =
                CommonUser(
                    id = ChatId(RawChatId(1L)),
                    firstName = "Test",
                    username = Username("@testuser"),
                )
            return MessageDataCallbackQuery(
                id = CallbackQueryId("test-callback-id"),
                from = user,
                chatInstance = "test-instance",
                message = mockMessage,
                data = "${QuickExportHandler.CALLBACK_PREFIX}$recordingId",
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
        fun `handle rejects unauthorized user with username`() =
            runTest {
                val callback = createMessageCallback()

                coEvery { authorizationFilter.getRole("testuser") } returns null
                every { authorizationFilter.getUnauthorizedMessage() } returns "Unauthorized"
                coEvery { bot.execute(any<Request<*>>()) } returns mockk(relaxed = true)

                handler.handle(callback)

                // Verify answer was called with unauthorized message (no export attempted)
                coVerify(exactly = 0) { videoExportService.exportByRecordingId(any(), any(), any()) }
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
