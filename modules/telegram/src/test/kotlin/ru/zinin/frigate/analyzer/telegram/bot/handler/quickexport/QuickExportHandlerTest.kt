package ru.zinin.frigate.analyzer.telegram.bot.handler.quickexport

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.requests.abstracts.Request
import dev.inmo.tgbotapi.requests.answers.AnswerCallbackQuery
import dev.inmo.tgbotapi.requests.edit.reply_markup.EditChatMessageReplyMarkup
import dev.inmo.tgbotapi.requests.send.SendTextMessage
import dev.inmo.tgbotapi.requests.send.media.SendVideoData
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.context.support.ReloadableResourceBundleMessageSource
import ru.zinin.frigate.analyzer.model.exception.DetectTimeoutException
import ru.zinin.frigate.analyzer.telegram.config.TelegramProperties
import ru.zinin.frigate.analyzer.telegram.filter.AuthorizationFilter
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.model.UserRole
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import ru.zinin.frigate.analyzer.telegram.service.VideoExportService
import ru.zinin.frigate.analyzer.telegram.service.model.ExportMode
import java.nio.file.Files
import java.time.Duration
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuickExportHandlerTest {
    private val msg =
        MessageResolver(
            ReloadableResourceBundleMessageSource().apply {
                setBasename("classpath:messages")
                setDefaultEncoding("UTF-8")
                setFallbackToSystemLocale(false)
                setDefaultLocale(Locale.forLanguageTag("en"))
            },
        )

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
        fun `returns UUID for valid annotated callback data`() {
            val recordingId = UUID.randomUUID()
            val data = "${QuickExportHandler.CALLBACK_PREFIX_ANNOTATED}$recordingId"

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
        fun `CALLBACK_PREFIX_ANNOTATED has correct value`() {
            assertEquals("qea:", QuickExportHandler.CALLBACK_PREFIX_ANNOTATED)
        }
    }

    @Nested
    inner class CreateExportKeyboardTest {
        private val bot = mockk<TelegramBot>()
        private val videoExportService = mockk<VideoExportService>()
        private val authorizationFilter = mockk<AuthorizationFilter>()
        private val userService = mockk<TelegramUserService>()
        private val properties =
            TelegramProperties(
                enabled = true,
                botToken = "test-token",
                owner = "testowner",
                sendVideoTimeout = Duration.ofMinutes(3),
            )
        private val handler =
            QuickExportHandler(
                bot,
                videoExportService,
                authorizationFilter,
                properties,
                msg,
                userService,
            )

        @Test
        fun `creates keyboard with single row and two buttons`() {
            val recordingId = UUID.randomUUID()

            val keyboard = handler.createExportKeyboard(recordingId, "ru")

            assertEquals(1, keyboard.keyboard.size, "Should have one row")
            assertEquals(2, keyboard.keyboard[0].size, "Row should have two buttons")
        }

        @Test
        fun `first button is original export`() {
            val recordingId = UUID.randomUUID()

            val keyboard = handler.createExportKeyboard(recordingId, "ru")
            val button = keyboard.keyboard[0][0]

            assertIs<CallbackDataInlineKeyboardButton>(button)
            assertEquals("📹 Оригинал", button.text)
            assertEquals("${QuickExportHandler.CALLBACK_PREFIX}$recordingId", button.callbackData)
        }

        @Test
        fun `second button is annotated export`() {
            val recordingId = UUID.randomUUID()

            val keyboard = handler.createExportKeyboard(recordingId, "ru")
            val button = keyboard.keyboard[0][1]

            assertIs<CallbackDataInlineKeyboardButton>(button)
            assertEquals("📹 С объектами", button.text)
            assertEquals("${QuickExportHandler.CALLBACK_PREFIX_ANNOTATED}$recordingId", button.callbackData)
        }
    }

    @Nested
    inner class CreateProcessingKeyboardTest {
        @Test
        fun `creates keyboard with single row and single button`() {
            val recordingId = UUID.randomUUID()

            val keyboard = QuickExportHandler.createProcessingKeyboard(recordingId, "⚙️ Exporting...")

            assertEquals(1, keyboard.keyboard.size, "Should have one row")
            assertEquals(1, keyboard.keyboard[0].size, "Row should have one button")
        }

        @Test
        fun `button has processing text`() {
            val recordingId = UUID.randomUUID()

            val keyboard = QuickExportHandler.createProcessingKeyboard(recordingId, "⚙️ Exporting...")
            val button = keyboard.keyboard[0][0]

            assertIs<CallbackDataInlineKeyboardButton>(button)
            assertEquals("⚙️ Exporting...", button.text)
        }

        @Test
        fun `button has correct callback data with prefix and recordingId`() {
            val recordingId = UUID.randomUUID()

            val keyboard = QuickExportHandler.createProcessingKeyboard(recordingId, "⚙️ Exporting...")
            val button = keyboard.keyboard[0][0]

            assertIs<CallbackDataInlineKeyboardButton>(button)
            assertEquals("${QuickExportHandler.CALLBACK_PREFIX}$recordingId", button.callbackData)
        }

        @Test
        fun `button uses annotated prefix when mode is ANNOTATED`() {
            val recordingId = UUID.randomUUID()

            val keyboard =
                QuickExportHandler.createProcessingKeyboard(recordingId, "⚙️ Exporting...", mode = ExportMode.ANNOTATED)
            val button = keyboard.keyboard[0][0]

            assertIs<CallbackDataInlineKeyboardButton>(button)
            assertEquals("${QuickExportHandler.CALLBACK_PREFIX_ANNOTATED}$recordingId", button.callbackData)
        }
    }

    @Nested
    inner class HandleTest {
        private val bot = mockk<TelegramBot>()
        private val videoExportService = mockk<VideoExportService>()
        private val authorizationFilter = mockk<AuthorizationFilter>()
        private val userService = mockk<TelegramUserService>()
        private val properties =
            TelegramProperties(
                enabled = true,
                botToken = "test-token",
                owner = "testowner",
                sendVideoTimeout = Duration.ofMinutes(3),
            )
        private val recordingId = UUID.randomUUID()

        init {
            coEvery { authorizationFilter.getRole(any<String>()) } returns UserRole.USER
            coEvery { userService.getUserLanguage(any()) } returns "ru"
        }

        private fun TestScope.createHandler() =
            QuickExportHandler(
                bot,
                videoExportService,
                authorizationFilter,
                properties,
                msg,
                userService,
                exportScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            )

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
                val handler = createHandler()
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
                coEvery { videoExportService.exportByRecordingId(eq(recordingId), any(), any(), any()) } returns tempFile
                coEvery { videoExportService.cleanupExportFile(tempFile) } returns Unit

                try {
                    // when
                    handler.handle(callback)?.join()
                } finally {
                    Files.deleteIfExists(tempFile)
                }

                // then

                // 1. Callback was answered (removes loading indicator)
                val answerRequests = capturedRequests.filterIsInstance<AnswerCallbackQuery>()
                assertTrue(answerRequests.isNotEmpty(), "Expected callback to be answered")

                // 2. Export was called with the correct recordingId
                coVerify { videoExportService.exportByRecordingId(eq(recordingId), any(), any(), any()) }

                // 3. Video was sent — tgbotapi's sendVideo() with a multipart file creates an
                //    internal CommonMultipartFileRequest wrapping SendVideoData. CommonMultipartFileRequest
                //    is Kotlin-internal so we verify by class name; SendVideoData is public so we
                //    verify the inner data via reflection for a stable type check.
                //    Known fragility: the class name check is tied to tgbotapi internals;
                //    review after library version upgrades.
                val videoSendRequests =
                    capturedRequests.filter {
                        it !is AnswerCallbackQuery && it !is EditChatMessageReplyMarkup && it !is SendTextMessage
                    }
                assertTrue(
                    videoSendRequests.isNotEmpty(),
                    "Expected a sendVideo request beyond answer/edit/text requests, " +
                        "but all ${capturedRequests.size} requests were of known types. " +
                        "Captured request types: ${capturedRequests.map { it::class.simpleName }}",
                )
                assertTrue(
                    videoSendRequests.any { it::class.simpleName == "CommonMultipartFileRequest" },
                    "Expected CommonMultipartFileRequest (tgbotapi's sendVideo multipart wrapper), " +
                        "but got: ${videoSendRequests.map { it::class.simpleName }}",
                )
                // Additionally verify the inner data is SendVideoData via reflection (public type,
                // stable across tgbotapi versions unlike the wrapper class name).
                val multipartRequest = videoSendRequests.first { it::class.simpleName == "CommonMultipartFileRequest" }
                val innerData =
                    multipartRequest::class.java.methods
                        .find { it.name == "getData" }
                        ?.invoke(multipartRequest)
                assertNotNull(innerData, "CommonMultipartFileRequest.getData() returned null")
                assertIs<SendVideoData>(innerData, "Expected inner data to be SendVideoData")

                // 4. Exported file was cleaned up
                coVerify { videoExportService.cleanupExportFile(tempFile) }

                // 5. Button was restored to export state after completion
                val editRequests = capturedRequests.filterIsInstance<EditChatMessageReplyMarkup>()
                assertTrue(editRequests.size >= 2, "Expected at least 2 edit calls (processing + restore)")
                val restoreMarkup = editRequests.last().replyMarkup
                assertNotNull(restoreMarkup)
                assertIs<InlineKeyboardMarkup>(restoreMarkup)
                assertEquals(
                    "📹 Оригинал",
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
        fun `handle with non-MessageDataCallbackQuery answers and returns early`() =
            runTest {
                val handler = createHandler()
                val callback =
                    mockk<DataCallbackQuery> {
                        every { data } returns "${QuickExportHandler.CALLBACK_PREFIX}${UUID.randomUUID()}"
                        every { user } returns mockk(relaxed = true)
                        every { id } returns CallbackQueryId("test-non-message-callback-id")
                    }

                val capturedRequests = mutableListOf<Request<*>>()
                coEvery { bot.execute(capture(capturedRequests)) } returns mockk(relaxed = true)

                handler.handle(callback)?.join()

                // Callback was answered (to dismiss loading indicator)
                val answerRequests = capturedRequests.filterIsInstance<AnswerCallbackQuery>()
                assertTrue(answerRequests.isNotEmpty(), "Expected callback to be answered before early return")

                // No export was attempted
                coVerify(exactly = 0) { videoExportService.exportByRecordingId(any(), any(), any(), any()) }
            }

        @Test
        fun `handle rejects user without username with set username message`() =
            runTest {
                val handler = createHandler()
                val callback = createMessageCallbackWithoutUsername()

                val capturedRequests = mutableListOf<Request<*>>()
                coEvery { bot.execute(capture(capturedRequests)) } returns mockk(relaxed = true)

                handler.handle(callback)?.join()

                // Verify answer was called with the correct "set username" message
                val answerRequests = capturedRequests.filterIsInstance<AnswerCallbackQuery>()
                val expectedText = msg.get("quickexport.error.username", "en")
                assertTrue(
                    answerRequests.any { it.text == expectedText },
                    "Expected AnswerCallbackQuery with 'set username' text, but got: ${answerRequests.map { it.text }}",
                )
                // Verify no export was attempted
                coVerify(exactly = 0) { videoExportService.exportByRecordingId(any(), any(), any(), any()) }
                // Verify authorizationFilter was never consulted (early return before auth check)
                coVerify(exactly = 0) { authorizationFilter.getRole(any<String>()) }
            }

        @Test
        fun `handle rejects unauthorized user with username`() =
            runTest {
                val handler = createHandler()
                val callback = createMessageCallback()

                coEvery { authorizationFilter.getRole("testuser") } returns null
                val capturedRequests = mutableListOf<Request<*>>()
                coEvery { bot.execute(capture(capturedRequests)) } returns mockk(relaxed = true)

                handler.handle(callback)?.join()

                // Verify no export was attempted
                coVerify(exactly = 0) { videoExportService.exportByRecordingId(any(), any(), any(), any()) }

                // Verify answer was called with unauthorized message from msg resolver
                val expectedText = msg.get("common.error.unauthorized", "en")
                val answerRequests = capturedRequests.filterIsInstance<AnswerCallbackQuery>()
                assertTrue(
                    answerRequests.any { it.text == expectedText },
                    "Expected AnswerCallbackQuery with text '$expectedText', but got: ${answerRequests.map {
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

                // Verify authorizationFilter was consulted
                coVerify { authorizationFilter.getRole("testuser") }
            }

        @Test
        fun `handle allows owner access even when userService returns null`() =
            runTest {
                val handler = createHandler()
                val callback = createOwnerCallback()
                val tempFile = Files.createTempFile("test-export", ".mp4")

                coEvery { authorizationFilter.getRole(properties.owner) } returns UserRole.OWNER
                coEvery { bot.execute(any<Request<*>>()) } returns mockk(relaxed = true)
                coEvery { videoExportService.exportByRecordingId(eq(recordingId), any(), any(), any()) } returns tempFile
                coEvery { videoExportService.cleanupExportFile(tempFile) } returns Unit

                try {
                    handler.handle(callback)?.join()
                } finally {
                    Files.deleteIfExists(tempFile)
                }

                // Verify export was called (owner is authorized via authorizationFilter)
                coVerify { videoExportService.exportByRecordingId(eq(recordingId), any(), any(), any()) }
            }

        @Test
        fun `handle allows active user access and performs export`() =
            runTest {
                val handler = createHandler()
                val callback = createMessageCallback()
                val tempFile = Files.createTempFile("test-export", ".mp4")

                coEvery { authorizationFilter.getRole("testuser") } returns UserRole.USER
                coEvery { bot.execute(any<Request<*>>()) } returns mockk(relaxed = true)
                coEvery { videoExportService.exportByRecordingId(eq(recordingId), any(), any(), any()) } returns tempFile
                coEvery { videoExportService.cleanupExportFile(tempFile) } returns Unit

                try {
                    handler.handle(callback)?.join()
                } finally {
                    Files.deleteIfExists(tempFile)
                }

                // Verify export was called (active user is authorized)
                coVerify { videoExportService.exportByRecordingId(eq(recordingId), any(), any(), any()) }
                // Verify authorizationFilter was consulted
                coVerify { authorizationFilter.getRole("testuser") }
            }

        @Test
        fun `handle switches button to processing then restores after export`() =
            runTest {
                val handler = createHandler()
                val callback = createMessageCallback()
                val tempFile = Files.createTempFile("test-export", ".mp4")

                val capturedRequests = mutableListOf<Request<*>>()
                coEvery { bot.execute(capture(capturedRequests)) } returns mockk(relaxed = true)
                coEvery { videoExportService.exportByRecordingId(eq(recordingId), any(), any(), any()) } returns tempFile
                coEvery { videoExportService.cleanupExportFile(tempFile) } returns Unit

                try {
                    handler.handle(callback)?.join()
                } finally {
                    Files.deleteIfExists(tempFile)
                }

                // Find all EditChatMessageReplyMarkup requests
                val editRequests = capturedRequests.filterIsInstance<EditChatMessageReplyMarkup>()
                assertTrue(editRequests.size >= 2, "Expected at least 2 editMessageReplyMarkup calls, got ${editRequests.size}")

                // First edit: processing keyboard with localized processing text
                val processingMarkup = editRequests[0].replyMarkup
                assertNotNull(processingMarkup, "Processing keyboard must not be null")
                assertIs<InlineKeyboardMarkup>(processingMarkup)
                val processingButton = processingMarkup.keyboard[0][0]
                assertIs<CallbackDataInlineKeyboardButton>(processingButton)
                assertEquals(msg.get("quickexport.button.processing", "ru"), processingButton.text)
                assertEquals("${QuickExportHandler.CALLBACK_PREFIX}$recordingId", processingButton.callbackData)

                // Last edit: restored export keyboard with localized original button text
                val restoreMarkup = editRequests.last().replyMarkup
                assertNotNull(restoreMarkup, "Restored keyboard must not be null")
                assertIs<InlineKeyboardMarkup>(restoreMarkup)
                val exportButton = restoreMarkup.keyboard[0][0]
                assertIs<CallbackDataInlineKeyboardButton>(exportButton)
                assertEquals("📹 Оригинал", exportButton.text)
                assertEquals("${QuickExportHandler.CALLBACK_PREFIX}$recordingId", exportButton.callbackData)
            }

        @Test
        fun `handle continues processing when editMessageReplyMarkup throws`() =
            runTest {
                val handler = createHandler()
                val callback = createMessageCallback()
                val tempFile = Files.createTempFile("test-export", ".mp4")

                coEvery { bot.execute(any<Request<*>>()) } coAnswers {
                    val request = firstArg<Request<*>>()
                    if (request is EditChatMessageReplyMarkup) {
                        throw RuntimeException("Telegram API error")
                    }
                    mockk(relaxed = true)
                }
                coEvery { videoExportService.exportByRecordingId(eq(recordingId), any(), any(), any()) } returns tempFile
                coEvery { videoExportService.cleanupExportFile(any()) } returns Unit

                try {
                    handler.handle(callback)?.join()
                } finally {
                    Files.deleteIfExists(tempFile)
                }

                // Despite editMessageReplyMarkup failing, export was still called
                coVerify { videoExportService.exportByRecordingId(eq(recordingId), any(), any(), any()) }
            }

        @Test
        fun `handle restores button after export error`() =
            runTest {
                val handler = createHandler()
                val callback = createMessageCallback()

                val capturedRequests = mutableListOf<Request<*>>()
                coEvery { bot.execute(capture(capturedRequests)) } returns mockk(relaxed = true)
                coEvery { videoExportService.exportByRecordingId(eq(recordingId), any(), any(), any()) } throws
                    IllegalArgumentException("Recording not found")

                handler.handle(callback)?.join()

                // Find all EditChatMessageReplyMarkup requests
                val editRequests = capturedRequests.filterIsInstance<EditChatMessageReplyMarkup>()
                assertTrue(editRequests.size >= 2, "Expected at least 2 editMessageReplyMarkup calls, got ${editRequests.size}")

                // First edit: processing keyboard
                val processingMarkup = editRequests[0].replyMarkup
                assertNotNull(processingMarkup)
                assertIs<InlineKeyboardMarkup>(processingMarkup)
                assertEquals(
                    msg.get("quickexport.button.processing", "ru"),
                    (processingMarkup.keyboard[0][0] as CallbackDataInlineKeyboardButton).text,
                )

                // Last edit: restored export keyboard
                val restoreMarkup = editRequests.last().replyMarkup
                assertNotNull(restoreMarkup)
                assertIs<InlineKeyboardMarkup>(restoreMarkup)
                assertEquals(
                    "📹 Оригинал",
                    (restoreMarkup.keyboard[0][0] as CallbackDataInlineKeyboardButton).text,
                )
            }

        @Test
        fun `handle calls exportByRecordingId and cleanupExportFile on successful export`() =
            runTest {
                val handler = createHandler()
                val callback = createMessageCallback()
                val tempFile = Files.createTempFile("test-export", ".mp4")

                coEvery { bot.execute(any<Request<*>>()) } returns mockk(relaxed = true)
                coEvery {
                    videoExportService.exportByRecordingId(eq(recordingId), any(), any(), any())
                } returns tempFile
                coEvery { videoExportService.cleanupExportFile(any()) } returns Unit

                try {
                    handler.handle(callback)?.join()
                } finally {
                    Files.deleteIfExists(tempFile)
                }

                // Verify exportByRecordingId was called with the correct recordingId
                coVerify { videoExportService.exportByRecordingId(eq(recordingId), any(), any(), any()) }

                // Verify cleanupExportFile was called with the exported file path
                coVerify { videoExportService.cleanupExportFile(tempFile) }
            }

        @Test
        fun `handle sends timeout message when export exceeds timeout`() =
            runTest {
                val handler = createHandler()
                val callback = createMessageCallback()

                val capturedRequests = mutableListOf<Request<*>>()
                coEvery { bot.execute(capture(capturedRequests)) } returns mockk(relaxed = true)
                coEvery {
                    videoExportService.exportByRecordingId(eq(recordingId), any(), any(), any())
                } coAnswers {
                    delay(400_000) // 400 seconds exceeds the 300-second (5 min) timeout
                    error("Should not reach here")
                }

                handler.handle(callback)?.join()

                // Verify timeout message was sent
                val expectedTimeoutMsg = msg.get("quickexport.error.timeout", "ru")
                val sendTextRequests = capturedRequests.filterIsInstance<SendTextMessage>()
                assertTrue(
                    sendTextRequests.any { it.text.contains(expectedTimeoutMsg) },
                    "Expected timeout message, but got text requests: ${sendTextRequests.map { it.text }}",
                )

                // Verify button was restored after timeout
                val editRequests = capturedRequests.filterIsInstance<EditChatMessageReplyMarkup>()
                assertTrue(editRequests.size >= 2, "Expected at least 2 edit calls (processing + restore)")

                val restoreMarkup = editRequests.last().replyMarkup
                assertNotNull(restoreMarkup)
                assertIs<InlineKeyboardMarkup>(restoreMarkup)
                assertEquals(
                    "📹 Оригинал",
                    (restoreMarkup.keyboard[0][0] as CallbackDataInlineKeyboardButton).text,
                )

                // Verify cleanupExportFile was NOT called (videoPath is null on timeout)
                coVerify(exactly = 0) { videoExportService.cleanupExportFile(any()) }
            }

        @Test
        fun `handle sends timeout message when sendVideo exceeds timeout`() =
            runTest {
                val handler = createHandler()
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
                coEvery { videoExportService.exportByRecordingId(eq(recordingId), any(), any(), any()) } coAnswers {
                    videoExportReturned = true
                    tempFile
                }
                coEvery { videoExportService.cleanupExportFile(tempFile) } returns Unit

                try {
                    handler.handle(callback)?.join()
                } finally {
                    Files.deleteIfExists(tempFile)
                }

                // Verify sendVideo timeout message was sent
                val expectedSendTimeoutMsg = msg.get("quickexport.error.send.timeout", "ru")
                val sendTextRequests = capturedRequests.filterIsInstance<SendTextMessage>()
                assertTrue(
                    sendTextRequests.any { it.text.contains(expectedSendTimeoutMsg) },
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
                    "📹 Оригинал",
                    (restoreMarkup.keyboard[0][0] as CallbackDataInlineKeyboardButton).text,
                )
            }

        @Test
        fun `handle sends error message for not found recording`() =
            runTest {
                val handler = createHandler()
                val callback = createMessageCallback()

                val capturedRequests = mutableListOf<Request<*>>()
                coEvery { bot.execute(capture(capturedRequests)) } returns mockk(relaxed = true)
                coEvery {
                    videoExportService.exportByRecordingId(eq(recordingId), any(), any(), any())
                } throws IllegalArgumentException("Recording not found")

                handler.handle(callback)?.join()

                // Verify "not found" error message
                val expectedNotFoundMsg = msg.get("quickexport.error.not.found", "ru")
                val sendTextRequests = capturedRequests.filterIsInstance<SendTextMessage>()
                assertTrue(
                    sendTextRequests.any { it.text.contains(expectedNotFoundMsg) },
                    "Expected 'not found' error message, but got: ${sendTextRequests.map { it.text }}",
                )
            }

        @Test
        fun `handle sends error message for missing files`() =
            runTest {
                val handler = createHandler()
                val callback = createMessageCallback()

                val capturedRequests = mutableListOf<Request<*>>()
                coEvery { bot.execute(capture(capturedRequests)) } returns mockk(relaxed = true)
                coEvery {
                    videoExportService.exportByRecordingId(eq(recordingId), any(), any(), any())
                } throws IllegalStateException("Recording files are missing")

                handler.handle(callback)?.join()

                // Verify "missing files" error message
                val expectedUnavailableMsg = msg.get("quickexport.error.unavailable", "ru")
                val sendTextRequests = capturedRequests.filterIsInstance<SendTextMessage>()
                assertTrue(
                    sendTextRequests.any { it.text.contains(expectedUnavailableMsg) },
                    "Expected 'missing files' error message, but got: ${sendTextRequests.map { it.text }}",
                )
            }

        @Test
        fun `handle sends annotation timeout message for DetectTimeoutException`() =
            runTest {
                val handler = createHandler()
                val callback = createMessageCallback()

                val capturedRequests = mutableListOf<Request<*>>()
                coEvery { bot.execute(capture(capturedRequests)) } returns mockk(relaxed = true)
                coEvery {
                    videoExportService.exportByRecordingId(eq(recordingId), any(), any(), any())
                } throws DetectTimeoutException("Video annotation timed out after 2700000ms")

                handler.handle(callback)?.join()

                val expectedTimeoutMsg = msg.get("quickexport.error.annotation.timeout", "ru")
                val sendTextRequests = capturedRequests.filterIsInstance<SendTextMessage>()
                assertTrue(
                    sendTextRequests.any { it.text.contains(expectedTimeoutMsg) },
                    "Expected annotation timeout message, but got: ${sendTextRequests.map { it.text }}",
                )
            }

        @Test
        fun `handle sends generic error message for unexpected exceptions`() =
            runTest {
                val handler = createHandler()
                val callback = createMessageCallback()

                val capturedRequests = mutableListOf<Request<*>>()
                coEvery { bot.execute(capture(capturedRequests)) } returns mockk(relaxed = true)
                coEvery {
                    videoExportService.exportByRecordingId(eq(recordingId), any(), any(), any())
                } throws RuntimeException("Unexpected error")

                handler.handle(callback)?.join()

                // Verify generic error message
                val expectedGenericMsg = msg.get("quickexport.error.generic", "ru")
                val sendTextRequests = capturedRequests.filterIsInstance<SendTextMessage>()
                assertTrue(
                    sendTextRequests.any { it.text.contains(expectedGenericMsg) },
                    "Expected generic error message, but got: ${sendTextRequests.map { it.text }}",
                )
            }

        @Test
        fun `handle propagates CancellationException without catching`() =
            runTest {
                val handler = createHandler()
                val callback = createMessageCallback()

                coEvery { bot.execute(any<Request<*>>()) } returns mockk(relaxed = true)
                coEvery {
                    videoExportService.exportByRecordingId(eq(recordingId), any(), any(), any())
                } throws CancellationException("Coroutine was cancelled")

                handler.handle(callback)?.join()

                // CancellationException propagates inside the export coroutine,
                // so no cleanup or button restore should happen
                coVerify(exactly = 0) { videoExportService.cleanupExportFile(any()) }
            }

        /**
         * Complements [handle sends generic error message for unexpected exceptions] which only
         * verifies the error message text. This test additionally asserts that no video was sent
         * and no cleanup was called — ensuring the error path has no side effects.
         */
        @Test
        fun `handle does not send video or cleanup on generic RuntimeException`() =
            runTest {
                val handler = createHandler()
                // given — generic RuntimeException triggers the "error" path
                val callback = createOwnerCallback()

                // Error path throws before reaching sendVideo, so a uniform relaxed mock is safe here
                // (unlike the happy-path test which needs type-specific coAnswers routing).
                val capturedRequests = mutableListOf<Request<*>>()
                coEvery { bot.execute(capture(capturedRequests)) } returns mockk(relaxed = true)
                coEvery {
                    videoExportService.exportByRecordingId(eq(recordingId), any(), any(), any())
                } throws RuntimeException("Unexpected internal error")

                // when
                handler.handle(callback)?.join()

                // then — generic error message was sent
                val expectedGenericMsg = msg.get("quickexport.error.generic", "ru")
                val sendTextRequests = capturedRequests.filterIsInstance<SendTextMessage>()
                assertTrue(
                    sendTextRequests.any { it.text.contains(expectedGenericMsg) },
                    "Expected generic error message containing '$expectedGenericMsg', but got: ${sendTextRequests.map { it.text }}",
                )

                // No video was sent — only AnswerCallbackQuery, EditChatMessageReplyMarkup, and SendTextMessage
                val videoRequests =
                    capturedRequests.filter {
                        it !is AnswerCallbackQuery && it !is EditChatMessageReplyMarkup && it !is SendTextMessage
                    }
                assertTrue(
                    videoRequests.isEmpty(),
                    "Expected no sendVideo requests on error, but found: ${videoRequests.map { it::class.simpleName }}",
                )

                // Cleanup was NOT called (no file was produced since export threw before returning)
                coVerify(exactly = 0) { videoExportService.cleanupExportFile(any()) }
            }

        @Test
        fun `handle sends not found message and restores button for not found recording`() =
            runTest {
                val handler = createHandler()
                val callback = createMessageCallback()

                val capturedRequests = mutableListOf<Request<*>>()
                coEvery { bot.execute(capture(capturedRequests)) } returns mockk(relaxed = true)
                coEvery {
                    videoExportService.exportByRecordingId(eq(recordingId), any(), any(), any())
                } throws IllegalArgumentException("Recording not found")

                handler.handle(callback)?.join()

                // Verify "not found" error message was sent
                val expectedNotFoundMsg = msg.get("quickexport.error.not.found", "ru")
                val sendTextRequests = capturedRequests.filterIsInstance<SendTextMessage>()
                assertTrue(
                    sendTextRequests.any { it.text.contains(expectedNotFoundMsg) },
                    "Expected 'not found' error message, but got: ${sendTextRequests.map { it.text }}",
                )

                // Verify button was restored after error
                val editRequests = capturedRequests.filterIsInstance<EditChatMessageReplyMarkup>()
                assertTrue(editRequests.size >= 2, "Expected at least 2 edit calls (processing + restore)")

                val restoreMarkup = editRequests.last().replyMarkup
                assertNotNull(restoreMarkup)
                assertIs<InlineKeyboardMarkup>(restoreMarkup)
                assertEquals(
                    "📹 Оригинал",
                    (restoreMarkup.keyboard[0][0] as CallbackDataInlineKeyboardButton).text,
                )
            }

        @Test
        fun `handle rejects duplicate export for same recordingId`() =
            runTest {
                val handler = createHandler()
                val callback = createMessageCallback()
                val tempFile = Files.createTempFile("test-export", ".mp4")

                val capturedRequests = mutableListOf<Request<*>>()
                coEvery { bot.execute(capture(capturedRequests)) } coAnswers {
                    val request = firstArg<Request<*>>()
                    if (request is AnswerCallbackQuery) true else mockk<ContentMessage<MessageContent>>(relaxed = true)
                }
                coEvery { videoExportService.exportByRecordingId(eq(recordingId), any(), any(), any()) } coAnswers {
                    delay(10_000) // simulate long export
                    tempFile
                }
                coEvery { videoExportService.cleanupExportFile(tempFile) } returns Unit

                try {
                    // First call starts export (will suspend on delay)
                    val firstJob = launch { handler.handle(callback)?.join() }
                    // Let the first call enter the export
                    yield()

                    // Second call with same recordingId should be rejected
                    val secondCallRequests = mutableListOf<Request<*>>()
                    coEvery { bot.execute(capture(secondCallRequests)) } coAnswers {
                        val request = firstArg<Request<*>>()
                        if (request is AnswerCallbackQuery) true else mockk<ContentMessage<MessageContent>>(relaxed = true)
                    }
                    handler.handle(callback)?.join()

                    // Verify second call got "already in progress" answer
                    val expectedConcurrentMsg = msg.get("quickexport.error.concurrent", "ru")
                    val answerRequests = secondCallRequests.filterIsInstance<AnswerCallbackQuery>()
                    assertTrue(
                        answerRequests.any { it.text?.contains(expectedConcurrentMsg) == true },
                        "Expected 'already in progress' answer, but got: ${answerRequests.map { it.text }}",
                    )

                    // Verify second call did NOT trigger export
                    coVerify(exactly = 1) { videoExportService.exportByRecordingId(eq(recordingId), any(), any(), any()) }

                    firstJob.cancel()
                } finally {
                    Files.deleteIfExists(tempFile)
                }
            }

        @Test
        fun `handle allows re-export after previous export completes`() =
            runTest {
                val handler = createHandler()
                val callback = createMessageCallback()
                val tempFile = Files.createTempFile("test-export", ".mp4")

                coEvery { bot.execute(any<Request<*>>()) } coAnswers {
                    val request = firstArg<Request<*>>()
                    if (request is AnswerCallbackQuery) true else mockk<ContentMessage<MessageContent>>(relaxed = true)
                }
                coEvery { videoExportService.exportByRecordingId(eq(recordingId), any(), any(), any()) } returns tempFile
                coEvery { videoExportService.cleanupExportFile(tempFile) } returns Unit

                try {
                    // First export completes
                    handler.handle(callback)?.join()
                    // Second export should work (guard released)
                    handler.handle(callback)?.join()
                } finally {
                    Files.deleteIfExists(tempFile)
                }

                // Both exports were executed
                coVerify(exactly = 2) { videoExportService.exportByRecordingId(eq(recordingId), any(), any(), any()) }
            }

        @Test
        fun `handle annotated callback calls exportByRecordingId with ANNOTATED mode`() =
            runTest {
                val handler = createHandler()
                val user =
                    CommonUser(
                        id = ChatId(RawChatId(1L)),
                        firstName = "Test",
                        username = Username("@testuser"),
                    )
                val realChat =
                    PrivateChatImpl(
                        id = ChatId(RawChatId(12345L)),
                        firstName = "TestChat",
                    )
                val mockMessage =
                    mockk<ContentMessage<MessageContent>>(relaxed = true) {
                        every { chat } returns realChat
                    }
                val callback =
                    MessageDataCallbackQuery(
                        id = CallbackQueryId("test-annotated-callback"),
                        from = user,
                        chatInstance = "test-instance",
                        message = mockMessage,
                        data = "${QuickExportHandler.CALLBACK_PREFIX_ANNOTATED}$recordingId",
                    )

                val tempFile = Files.createTempFile("test-export", ".mp4")

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
                coEvery {
                    videoExportService.exportByRecordingId(eq(recordingId), any(), eq(ExportMode.ANNOTATED), any())
                } returns tempFile
                coEvery { videoExportService.cleanupExportFile(tempFile) } returns Unit

                try {
                    handler.handle(callback)?.join()
                } finally {
                    Files.deleteIfExists(tempFile)
                }

                coVerify { videoExportService.exportByRecordingId(eq(recordingId), any(), eq(ExportMode.ANNOTATED), any()) }
            }
    }
}
