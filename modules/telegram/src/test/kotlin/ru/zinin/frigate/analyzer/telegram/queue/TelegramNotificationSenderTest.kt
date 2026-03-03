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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

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
        assertEquals(1, keyboard.keyboard[0].size, "Row should have one button")
        val button = keyboard.keyboard[0][0]
        assertIs<CallbackDataInlineKeyboardButton>(button)
        assertEquals("\uD83D\uDCF9 Экспорт видео", button.text)
        assertEquals("qe:$recordingId", button.callbackData)
    }

    /**
     * Extracts replyMarkup from a captured request using reflection.
     * Needed because the photo request is wrapped in an internal CommonMultipartFileRequest,
     * so we first extract the inner `data` (SendPhotoData) then read its replyMarkup.
     */
    private fun extractReplyMarkup(request: Request<*>): KeyboardMarkup? {
        // For SendTextMessage, replyMarkup is directly accessible
        if (request is SendTextMessage) {
            return request.replyMarkup
        }
        // For multipart wrappers (e.g. CommonMultipartFileRequest), extract inner data via reflection
        val dataMethod =
            request::class.java.methods.find { it.name == "getData" }
                ?: error("Request ${request::class} does not have getData() method")
        val innerData = dataMethod.invoke(request)
        val replyMarkupMethod =
            innerData!!::class.java.methods.find { it.name == "getReplyMarkup" }
                ?: error("Inner data ${innerData::class} does not have getReplyMarkup() method")
        return replyMarkupMethod.invoke(innerData) as? KeyboardMarkup
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

            // Should have 2 execute calls: 1 for media group + 1 for export button text message
            assertEquals(2, capturedRequests.size, "Should have 2 execute() calls")

            // The last request should be SendTextMessage with export keyboard
            val exportRequest = capturedRequests.last()
            assertIs<SendTextMessage>(exportRequest)
            assertEquals("👆 Нажмите для быстрого экспорта видео", exportRequest.text)
            val replyMarkup = exportRequest.replyMarkup
            assertNotNull(replyMarkup, "Export button message should have replyMarkup")
            assertIs<InlineKeyboardMarkup>(replyMarkup)
            assertExportKeyboard(replyMarkup)
        }
}
