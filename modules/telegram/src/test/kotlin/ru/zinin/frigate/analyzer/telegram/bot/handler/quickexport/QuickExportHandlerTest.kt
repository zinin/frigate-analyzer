package ru.zinin.frigate.analyzer.telegram.bot.handler.quickexport

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.requests.abstracts.Request
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.queries.callback.DataCallbackQuery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.telegram.config.TelegramProperties
import ru.zinin.frigate.analyzer.telegram.filter.AuthorizationFilter
import ru.zinin.frigate.analyzer.telegram.service.VideoExportService
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
    }
}
