package ru.zinin.frigate.analyzer.telegram.queue

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.requests.abstracts.Request
import dev.inmo.tgbotapi.requests.edit.caption.EditChatMessageCaption
import dev.inmo.tgbotapi.requests.edit.text.EditChatMessageText
import dev.inmo.tgbotapi.requests.send.SendTextMessage
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.MessageId
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.buttons.KeyboardMarkup
import dev.inmo.tgbotapi.types.message.abstracts.ContentMessage
import dev.inmo.tgbotapi.types.message.content.MediaGroupContent
import dev.inmo.tgbotapi.types.message.content.MediaGroupPartContent
import dev.inmo.tgbotapi.types.message.content.PhotoContent
import dev.inmo.tgbotapi.types.message.content.TextContent
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.support.ReloadableResourceBundleMessageSource
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.model.dto.VisualizedFrameData
import ru.zinin.frigate.analyzer.telegram.bot.handler.quickexport.QuickExportHandler
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.service.impl.DescriptionMessageFormatter
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TelegramNotificationSenderTest {
    private val bot = mockk<TelegramBot>()
    private val msg =
        MessageResolver(
            ReloadableResourceBundleMessageSource().apply {
                setBasename("classpath:messages")
                setDefaultEncoding("UTF-8")
                setFallbackToSystemLocale(false)
                setDefaultLocale(Locale.forLanguageTag("en"))
            },
        )
    private val quickExportHandler = mockk<QuickExportHandler>()
    private val formatterProvider = mockk<ObjectProvider<DescriptionMessageFormatter>>()
    private val runnerProvider = mockk<ObjectProvider<DescriptionEditJobRunner>>()
    private val formatter = DescriptionMessageFormatter(msg)

    // runner is built per-test from the runTest scope so its dispatcher shares the
    // TestCoroutineScheduler — required if any test ever introduces `delay(...)` in the edit job.
    private lateinit var runner: DescriptionEditJobRunner
    private val sender =
        TelegramNotificationSender(
            bot,
            quickExportHandler,
            msg,
            formatterProvider,
            runnerProvider,
        )

    private val recordingId = UUID.randomUUID()

    init {
        // Default mock for createExportKeyboard — returns a Russian-localized keyboard
        every { quickExportHandler.createExportKeyboard(any(), any()) } answers {
            val rid = firstArg<UUID>()
            InlineKeyboardMarkup(
                keyboard =
                    listOf(
                        listOf(
                            CallbackDataInlineKeyboardButton(
                                "📹 Оригинал",
                                "${QuickExportHandler.CALLBACK_PREFIX}$rid",
                            ),
                            CallbackDataInlineKeyboardButton(
                                "📹 С объектами",
                                "${QuickExportHandler.CALLBACK_PREFIX_ANNOTATED}$rid",
                            ),
                        ),
                    ),
            )
        }
        // By default, neither the formatter nor the runner is available — this mimics
        // application.ai.description.enabled=false (old plain-text path).
        every { formatterProvider.getIfAvailable() } returns null
        every { runnerProvider.getIfAvailable() } returns null
    }

    /**
     * Builds the runner backed by runTest's scheduler and flips the providers to "available".
     * Must be called inside a `runTest { ... }` block before [sender.send] for the description
     * path to be active.
     */
    private fun TestScope.enableDescriptionBeans() {
        runner =
            DescriptionEditJobRunner(
                bot = bot,
                formatter = formatter,
                scope =
                    DescriptionEditScope.forTest(
                        CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob()),
                    ),
            )
        every { formatterProvider.getIfAvailable() } returns formatter
        every { runnerProvider.getIfAvailable() } returns runner
    }

    private fun createTask(
        frames: List<VisualizedFrameData> = emptyList(),
        descriptionHandle: Deferred<Result<DescriptionResult>>? = null,
    ) = RecordingNotificationTask(
        id = UUID.randomUUID(),
        chatId = 12345L,
        message = "Test notification",
        visualizedFrames = frames,
        recordingId = recordingId,
        language = "ru",
        descriptionHandle = descriptionHandle,
    )

    private fun assertExportKeyboard(keyboard: InlineKeyboardMarkup) {
        assertEquals(1, keyboard.keyboard.size, "Should have one row")
        assertEquals(2, keyboard.keyboard[0].size, "Row should have two buttons")
        val originalButton = keyboard.keyboard[0][0]
        assertIs<CallbackDataInlineKeyboardButton>(originalButton)
        assertEquals("📹 Оригинал", originalButton.text)
        assertEquals("${QuickExportHandler.CALLBACK_PREFIX}$recordingId", originalButton.callbackData)
        val annotatedButton = keyboard.keyboard[0][1]
        assertIs<CallbackDataInlineKeyboardButton>(annotatedButton)
        assertEquals("📹 С объектами", annotatedButton.text)
        assertEquals("${QuickExportHandler.CALLBACK_PREFIX_ANNOTATED}$recordingId", annotatedButton.callbackData)
    }

    /**
     * Extracts replyMarkup from a captured request using reflection.
     * Needed because the photo request is wrapped in an internal CommonMultipartFileRequest,
     * so we first extract the inner `data` (SendPhotoData) then read its replyMarkup.
     *
     * Coupled to tgbotapi 32.0.0 internals — if the library changes its wrapping
     * mechanism, this method will fail at runtime. Review after version upgrades.
     */
    private fun extractReplyMarkup(request: Request<*>): KeyboardMarkup? {
        // For SendTextMessage, replyMarkup is directly accessible
        if (request is SendTextMessage) {
            return request.replyMarkup
        }
        // For multipart wrappers (e.g. CommonMultipartFileRequest), extract inner data via reflection
        try {
            val dataMethod =
                request::class.java.methods.find { it.name == "getData" }
                    ?: error("Request ${request::class} does not have getData() method")
            val innerData =
                dataMethod.invoke(request)
                    ?: error("getData() returned null for ${request::class}")
            val replyMarkupMethod =
                innerData::class.java.methods.find { it.name == "getReplyMarkup" }
                    ?: error("Inner data ${innerData::class} does not have getReplyMarkup() method")
            return replyMarkupMethod.invoke(innerData) as? KeyboardMarkup
        } catch (e: Exception) {
            throw AssertionError(
                "Failed to extract replyMarkup via reflection from ${request::class}. " +
                    "This is likely caused by a tgbotapi version change — review extractReplyMarkup().",
                e,
            )
        }
    }

    @Test
    fun `send with empty frames includes inline export keyboard in text message`() =
        runTest {
            val task = createTask(frames = emptyList())
            val textMessageResult = mockk<ContentMessage<TextContent>>()

            val requestSlot = slot<Request<*>>()
            coEvery { bot.execute(capture(requestSlot)) } returns textMessageResult

            sender.send(task)

            val request = requestSlot.captured
            assertIs<SendTextMessage>(request)
            val replyMarkup = request.replyMarkup
            assertNotNull(replyMarkup, "Text message should have replyMarkup")
            assertIs<InlineKeyboardMarkup>(replyMarkup)
            assertExportKeyboard(replyMarkup)
        }

    @Test
    fun `send with single frame includes inline export keyboard in photo message`() =
        runTest {
            val frame = VisualizedFrameData(frameIndex = 0, visualizedBytes = byteArrayOf(1, 2, 3), detectionsCount = 1)
            val task = createTask(frames = listOf(frame))
            val photoMessageResult = mockk<ContentMessage<PhotoContent>>()

            val requestSlot = slot<Request<*>>()
            coEvery { bot.execute(capture(requestSlot)) } returns photoMessageResult

            sender.send(task)

            val replyMarkup = extractReplyMarkup(requestSlot.captured)
            assertNotNull(replyMarkup, "SendPhoto should have replyMarkup")
            assertIs<InlineKeyboardMarkup>(replyMarkup)
            assertExportKeyboard(replyMarkup)
        }

    @Test
    fun `send with multiple frames sends export button after media group`() =
        runTest {
            val frames =
                listOf(
                    VisualizedFrameData(frameIndex = 0, visualizedBytes = byteArrayOf(1, 2, 3), detectionsCount = 1),
                    VisualizedFrameData(frameIndex = 1, visualizedBytes = byteArrayOf(4, 5, 6), detectionsCount = 2),
                )
            val task = createTask(frames = frames)

            // Media-group path: sender now reads `group.messageId` off the sendMediaGroup result,
            // so the return value must be a typed ContentMessage<MediaGroupContent<...>> rather than
            // a generic relaxed mock (which would fail a checkcast at runtime).
            val groupMsg =
                mockk<ContentMessage<MediaGroupContent<MediaGroupPartContent>>>(relaxed = true) {
                    every { messageId } returns MessageId(1L)
                }
            val capturedRequests = mutableListOf<Request<*>>()
            coEvery { bot.execute(capture(capturedRequests)) } coAnswers {
                val req = firstArg<Request<*>>()
                when {
                    req is SendTextMessage -> mockk<ContentMessage<TextContent>>(relaxed = true)
                    inner(req)?.contains("MediaGroup") == true -> groupMsg
                    else -> mockk(relaxed = true)
                }
            }

            sender.send(task)

            // At least 2 execute calls: media group dispatch(es) + export button text message
            assertTrue(capturedRequests.size >= 2, "Expected at least 2 execute() calls, got ${capturedRequests.size}")

            // The last request should be SendTextMessage with export keyboard
            val exportRequest = capturedRequests.last()
            assertIs<SendTextMessage>(exportRequest)
            assertEquals("👆 Нажмите для быстрого экспорта видео", exportRequest.text, "Export prompt text mismatch")
            val replyMarkup = exportRequest.replyMarkup
            assertNotNull(replyMarkup, "Export button message should have replyMarkup")
            assertIs<InlineKeyboardMarkup>(replyMarkup)
            assertExportKeyboard(replyMarkup)
        }

    @Test
    fun `send with frames exceeding MAX_MEDIA_GROUP_SIZE sends export button after all chunks`() =
        runTest {
            // 20 frames = 2 full chunks of 10, ensuring multiple sendMediaGroup calls
            val frames =
                (0..19).map { i ->
                    VisualizedFrameData(frameIndex = i, visualizedBytes = byteArrayOf(i.toByte()), detectionsCount = 1)
                }
            assertEquals(20, frames.size, "Test requires 20 frames to trigger 2-chunk sending")
            val task = createTask(frames = frames)

            // See companion test above — sender reads `group.messageId` and needs a typed mock.
            val groupMsg =
                mockk<ContentMessage<MediaGroupContent<MediaGroupPartContent>>>(relaxed = true) {
                    every { messageId } returns MessageId(1L)
                }
            val capturedRequests = mutableListOf<Request<*>>()
            coEvery { bot.execute(capture(capturedRequests)) } coAnswers {
                val req = firstArg<Request<*>>()
                when {
                    req is SendTextMessage -> mockk<ContentMessage<TextContent>>(relaxed = true)
                    inner(req)?.contains("MediaGroup") == true -> groupMsg
                    else -> mockk(relaxed = true)
                }
            }

            sender.send(task)

            // Should have at least 3 calls: 2 media group chunks + 1 export button
            assertTrue(capturedRequests.size >= 3, "Expected at least 3 execute() calls, got ${capturedRequests.size}")

            // The last request should be the export button message
            val exportRequest = capturedRequests.last()
            assertIs<SendTextMessage>(exportRequest)
            val replyMarkup = exportRequest.replyMarkup
            assertNotNull(replyMarkup, "Export button message should have replyMarkup")
            assertIs<InlineKeyboardMarkup>(replyMarkup)
            assertExportKeyboard(replyMarkup)
        }

    /**
     * Returns the simple name of the inner `SendXxxData` / `EditXxxData` class, unwrapping the
     * `CommonMultipartFileRequest` shell used by photo + media-group requests. Returns the
     * request's own simple name for unwrapped requests (SendTextMessage, EditChatMessageText,
     * EditChatMessageCaption).
     */
    private fun inner(request: Request<*>): String? {
        if (request is SendTextMessage || request is EditChatMessageText || request is EditChatMessageCaption) {
            return request::class.simpleName
        }
        return runCatching {
            val data =
                request::class.java.methods
                    .find { it.name == "getData" }
                    ?.invoke(request)
            data?.let { it::class.simpleName }
        }.getOrNull()
    }

    // --- NEW TESTS (Task 16): placeholder + edit flow ---

    @Test
    fun `disabled path with null descriptionHandle preserves current single-photo behavior`() =
        runTest {
            // Description beans absent (default setup) AND task.descriptionHandle = null.
            // Sender must emit only the photo — no edit call, no runner invocation.
            val frames =
                listOf(
                    VisualizedFrameData(frameIndex = 0, visualizedBytes = byteArrayOf(1), detectionsCount = 1),
                )
            val capturedRequests = mutableListOf<Request<*>>()
            coEvery { bot.execute(capture(capturedRequests)) } returns mockk<ContentMessage<PhotoContent>>(relaxed = true)

            sender.send(createTask(frames = frames))

            // Exactly one request: the photo send. No edit messages dispatched.
            assertEquals(1, capturedRequests.size, "Expected only the photo send, got ${capturedRequests.size}")
            assertTrue(
                capturedRequests.none { it is EditChatMessageCaption || it is EditChatMessageText },
                "No edit calls should happen when description is disabled",
            )
        }

    @Test
    fun `single photo with description handle sends placeholder then edits on success`() =
        runTest {
            enableDescriptionBeans()
            val frames =
                listOf(
                    VisualizedFrameData(frameIndex = 0, visualizedBytes = byteArrayOf(1), detectionsCount = 1),
                )
            val handle = CompletableDeferred<Result<DescriptionResult>>()
            handle.complete(Result.success(DescriptionResult("two cars", "two cars approaching gate")))

            val photoMsg =
                mockk<ContentMessage<PhotoContent>> {
                    every { messageId } returns MessageId(42L)
                }
            val textMsg =
                mockk<ContentMessage<TextContent>> {
                    every { messageId } returns MessageId(43L)
                }

            val capturedRequests = mutableListOf<Request<*>>()
            coEvery { bot.execute(capture(capturedRequests)) } coAnswers {
                val req = firstArg<Request<*>>()
                when {
                    req is SendTextMessage -> textMsg
                    req is EditChatMessageCaption || req is EditChatMessageText -> mockk(relaxed = true)
                    inner(req)?.contains("Photo") == true -> photoMsg
                    else -> mockk(relaxed = true)
                }
            }

            sender.send(createTask(frames = frames, descriptionHandle = handle))
            runner.lastLaunchedJobForTests()?.join()

            // Post-edit: both caption (photo) and text (reply) must be rewritten.
            assertTrue(
                capturedRequests.any { it is EditChatMessageCaption },
                "Expected EditChatMessageCaption for photo caption rewrite, " +
                    "got: ${capturedRequests.map { it::class.simpleName }}",
            )
            assertTrue(
                capturedRequests.any { it is EditChatMessageText },
                "Expected EditChatMessageText for details rewrite, " +
                    "got: ${capturedRequests.map { it::class.simpleName }}",
            )
        }

    @Test
    fun `single photo with description handle uses fallback on failure`() =
        runTest {
            enableDescriptionBeans()
            val frames = listOf(VisualizedFrameData(0, byteArrayOf(1), 1))
            val handle = CompletableDeferred<Result<DescriptionResult>>()
            handle.complete(Result.failure(RuntimeException("boom")))

            val photoMsg =
                mockk<ContentMessage<PhotoContent>> {
                    every { messageId } returns MessageId(42L)
                }
            val textMsg =
                mockk<ContentMessage<TextContent>> {
                    every { messageId } returns MessageId(43L)
                }

            val capturedRequests = mutableListOf<Request<*>>()
            coEvery { bot.execute(capture(capturedRequests)) } coAnswers {
                val req = firstArg<Request<*>>()
                when {
                    req is SendTextMessage -> textMsg
                    req is EditChatMessageCaption || req is EditChatMessageText -> mockk(relaxed = true)
                    inner(req)?.contains("Photo") == true -> photoMsg
                    else -> mockk(relaxed = true)
                }
            }

            sender.send(createTask(frames = frames, descriptionHandle = handle))
            runner.lastLaunchedJobForTests()?.join()

            val captionEdit = capturedRequests.filterIsInstance<EditChatMessageCaption>().lastOrNull()
            val detailsEdit = capturedRequests.filterIsInstance<EditChatMessageText>().lastOrNull()
            assertNotNull(captionEdit, "Expected EditChatMessageCaption for fallback caption")
            assertNotNull(detailsEdit, "Expected EditChatMessageText for fallback details")
            // The task's language = "ru" — only the Russian fallback ("Описание недоступно") is valid.
            assertTrue(
                captionEdit.text.contains("недоступно"),
                "Expected Russian fallback in caption, got: ${captionEdit.text}",
            )
            assertTrue(
                detailsEdit.text.contains("недоступно"),
                "Expected Russian fallback in details, got: ${detailsEdit.text}",
            )
        }

    @Test
    fun `media group with description handle sends albums and single edit on success`() =
        runTest {
            enableDescriptionBeans()
            val frames = (0..2).map { VisualizedFrameData(it, byteArrayOf(1), 1) }
            val handle = CompletableDeferred<Result<DescriptionResult>>()
            handle.complete(Result.success(DescriptionResult("two cars", "two cars approaching gate")))

            val groupMsg =
                mockk<ContentMessage<MediaGroupContent<MediaGroupPartContent>>> {
                    every { messageId } returns MessageId(50L)
                }
            val textMsg =
                mockk<ContentMessage<TextContent>> {
                    every { messageId } returns MessageId(51L)
                }

            val capturedRequests = mutableListOf<Request<*>>()
            coEvery { bot.execute(capture(capturedRequests)) } coAnswers {
                val req = firstArg<Request<*>>()
                when {
                    req is SendTextMessage -> textMsg
                    req is EditChatMessageCaption || req is EditChatMessageText -> mockk(relaxed = true)
                    inner(req)?.contains("MediaGroup") == true -> groupMsg
                    else -> mockk(relaxed = true)
                }
            }

            sender.send(createTask(frames = frames, descriptionHandle = handle))
            runner.lastLaunchedJobForTests()?.join()

            // Media group path: exactly one edit-text for the reply message, no caption edits.
            val captionEdits = capturedRequests.filterIsInstance<EditChatMessageCaption>()
            val textEdits = capturedRequests.filterIsInstance<EditChatMessageText>()
            assertEquals(
                0,
                captionEdits.size,
                "Media group path must not edit photo captions, got: ${captionEdits.size}",
            )
            assertEquals(
                1,
                textEdits.size,
                "Media group path must edit exactly one details message, got: ${textEdits.size}",
            )
        }

    @Test
    fun `empty frames with description handle skips edit job — no photo target to edit`() =
        runTest {
            enableDescriptionBeans()
            val handle = CompletableDeferred<Result<DescriptionResult>>()
            handle.complete(Result.success(DescriptionResult("s", "d")))

            val textMsg = mockk<ContentMessage<TextContent>>(relaxed = true)
            coEvery { bot.execute(any<Request<*>>()) } returns textMsg

            sender.send(createTask(frames = emptyList(), descriptionHandle = handle))

            // No edit job should have been launched — there's no photo/album to edit.
            assertNull(
                runner.lastLaunchedJobForTests(),
                "Empty-frames path must not launch an edit job even with description enabled",
            )
        }

    @Test
    fun `send dispatches SimpleTextNotificationTask to bot sendTextMessage`() =
        runTest {
            // sendTextMessage is a top-level extension that ultimately calls bot.execute(SendTextMessage(...)),
            // so we capture the request through bot.execute (matching the existing test convention) and assert
            // on the resulting SendTextMessage's chatId + text. This also implicitly verifies that no
            // SendPhoto / sendMediaGroup variant is dispatched (only one execute call, of type SendTextMessage).
            val task =
                SimpleTextNotificationTask(
                    id = UUID.randomUUID(),
                    chatId = 12345L,
                    text = "Camera \"front_door\" lost signal",
                )
            val capturedRequests = mutableListOf<Request<*>>()
            coEvery { bot.execute(capture(capturedRequests)) } returns mockk<ContentMessage<TextContent>>(relaxed = true)

            sender.send(task)

            assertEquals(1, capturedRequests.size, "Expected exactly one execute() call, got ${capturedRequests.size}")
            val request = capturedRequests.single()
            assertIs<SendTextMessage>(request)
            assertEquals("Camera \"front_door\" lost signal", request.text)
            val chatId = request.chatId
            assertIs<ChatId>(chatId)
            assertEquals(12345L, chatId.chatId.long)
        }
}
