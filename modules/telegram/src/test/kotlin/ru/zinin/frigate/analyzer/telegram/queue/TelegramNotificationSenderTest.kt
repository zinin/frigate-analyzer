package ru.zinin.frigate.analyzer.telegram.queue

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.requests.abstracts.Request
import dev.inmo.tgbotapi.requests.send.SendTextMessage
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.buttons.KeyboardMarkup
import dev.inmo.tgbotapi.types.message.abstracts.ContentMessage
import dev.inmo.tgbotapi.types.message.content.PhotoContent
import dev.inmo.tgbotapi.types.message.content.TextContent
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.model.dto.VisualizedFrameData
import ru.zinin.frigate.analyzer.telegram.bot.handler.quickexport.QuickExportHandler
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TelegramNotificationSenderTest {
    private val bot = mockk<TelegramBot>()
    private val sender = TelegramNotificationSender(bot)

    private val recordingId = UUID.randomUUID()

    private fun createTask(frames: List<VisualizedFrameData> = emptyList()) =
        NotificationTask(
            id = UUID.randomUUID(),
            chatId = 12345L,
            message = "Test notification",
            visualizedFrames = frames,
            recordingId = recordingId,
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
     * Coupled to tgbotapi 30.0.2 internals — if the library changes its wrapping
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

            val capturedRequests = mutableListOf<Request<*>>()
            coEvery { bot.execute(capture(capturedRequests)) } returns mockk(relaxed = true)

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

            val capturedRequests = mutableListOf<Request<*>>()
            coEvery { bot.execute(capture(capturedRequests)) } returns mockk(relaxed = true)

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
}
