package ru.zinin.frigate.analyzer.telegram.queue

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.bot.exceptions.MessageIsNotModifiedException
import dev.inmo.tgbotapi.bot.exceptions.MessageToEditNotFoundException
import dev.inmo.tgbotapi.requests.abstracts.FileId
import dev.inmo.tgbotapi.requests.abstracts.Request
import dev.inmo.tgbotapi.requests.edit.text.EditChatMessageText
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.MessageId
import dev.inmo.tgbotapi.types.RawChatId
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.context.support.ReloadableResourceBundleMessageSource
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.service.impl.RichNotificationRenderer
import ru.zinin.frigate.analyzer.telegram.service.model.RecordingNotificationData
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Покрывает [DescriptionEditJobRunner.runEdit] — путь, который до сих пор не выполнялся ни одним
 * тестом, хотя дизайн-док утверждал, что «тесты на исключения и бэкофф остаются нетронутыми»
 * (оставлять было нечего). Зависимость от него выросла: правка теперь одна вместо двух, и её
 * молчаливый отказ оставляет пользователя с плейсхолдером «⏳» навсегда.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DescriptionEditJobRunnerTest {
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
    private val renderer = RichNotificationRenderer(msg)

    private fun TestScope.runner() =
        DescriptionEditJobRunner(
            bot = bot,
            renderer = renderer,
            scope = DescriptionEditScope.forTest(CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())),
        )

    private fun target(
        fileIds: List<FileId> = listOf(FileId("f-0"), FileId("f-1")),
        keyboard: () -> InlineKeyboardMarkup = { InlineKeyboardMarkup(keyboard = emptyList()) },
    ) = EditTarget(
        chatId = ChatId(RawChatId(42)),
        messageId = MessageId(7),
        data =
            RecordingNotificationData(
                camId = "driveway",
                fileName = "clip.mp4",
                detectionsCount = 3,
                analyzedFramesCount = 12,
                analyzeTimeSeconds = 4,
                recordTimestamp = "31 августа 2026 г., 21:15",
                processTimestamp = "31 августа 2026 г., 21:20",
            ),
        fileIds = fileIds,
        keyboard = keyboard,
        language = "ru",
    )

    private val ready = Result.success(DescriptionResult(short = "Человек у ворот", detailed = "Подробности"))

    @Test
    fun `a not-modified answer is terminal and never retried`() =
        runTest {
            coEvery { bot.execute<Any>(any()) } throws mockk<MessageIsNotModifiedException>(relaxed = true)

            runner().launchEditJob(target()) { ready }.join()

            // Сообщение уже несёт этот текст — повтор ничего не изменит.
            coVerify(exactly = 1) { bot.execute<Any>(any()) }
        }

    @Test
    fun `a deleted message is terminal and never retried`() =
        runTest {
            coEvery { bot.execute<Any>(any()) } throws mockk<MessageToEditNotFoundException>(relaxed = true)

            runner().launchEditJob(target()) { ready }.join()

            // Пользователь удалил сообщение — править нечего.
            coVerify(exactly = 1) { bot.execute<Any>(any()) }
        }

    @Test
    fun `a transient failure is retried to the bound and then given up`() =
        runTest {
            coEvery { bot.execute<Any>(any()) } throws RuntimeException("429 Too Many Requests")

            // Завершение здесь важно не меньше числа попыток: неограниченный ретрай удержал бы scope
            // и сорвал бы остановку по @PreDestroy.
            runner().launchEditJob(target()) { ready }.join()

            // EDIT_MAX_ATTEMPTS — best-effort правка сдаётся, а не крутится вечно.
            coVerify(exactly = 5) { bot.execute<Any>(any()) }
        }

    @Test
    fun `the keyboard is resolved when the edit runs, not when the job is launched`() =
        runTest {
            val choice =
                InlineKeyboardMarkup(keyboard = listOf(listOf(CallbackDataInlineKeyboardButton("📹", "qe:1"))))
            val progress =
                InlineKeyboardMarkup(keyboard = listOf(listOf(CallbackDataInlineKeyboardButton("Отмена", "xc:1"))))
            var now = choice
            val captured = slot<Request<Any>>()
            coEvery { bot.execute<Any>(capture(captured)) } returns mockk<Any>(relaxed = true)

            // Пока модель думает, пользователь успевает нажать экспорт — на сообщении уже «Отмена».
            runner()
                .launchEditJob(target(keyboard = { now })) {
                    now = progress
                    ready
                }.join()

            val edit = assertIs<EditChatMessageText>(captured.captured)
            assertEquals(
                progress,
                edit.replyMarkup,
                "a keyboard remembered at send time would put the export choice back and take the only Cancel button away",
            )
        }

    @Test
    fun `an oversized id list is clamped so html and media keep declaring the same slots`() =
        runTest {
            val ids = (0..RichNotificationRenderer.MAX_MEDIA).map { FileId("f-$it") }
            val captured = slot<Request<Any>>()
            coEvery { bot.execute<Any>(capture(captured)) } returns mockk<Any>(relaxed = true)

            runner().launchEditJob(target(fileIds = ids)) { ready }.join()

            val rich = assertIs<EditChatMessageText>(captured.captured).richMessage!!
            assertEquals(RichNotificationRenderer.MAX_MEDIA, rich.media!!.size, "media is capped")
            val overflow = RichNotificationRenderer.mediaId(RichNotificationRenderer.MAX_MEDIA)
            assertFalse(
                rich.html!!.contains("""tg://photo?id=$overflow"""),
                "html must not declare a slot the media array does not carry — Telegram rejects the edit outright",
            )
        }

    @Test
    fun `edit backoff follows the documented schedule`() {
        // 15s, 30s, 60s, 120s, дальше потолок — суммарно ~3.75 минуты до отказа.
        assertEquals(15_000L, DescriptionEditJobRunner.editBackoffMs(1))
        assertEquals(30_000L, DescriptionEditJobRunner.editBackoffMs(2))
        assertEquals(60_000L, DescriptionEditJobRunner.editBackoffMs(3))
        assertEquals(120_000L, DescriptionEditJobRunner.editBackoffMs(4))
        assertEquals(120_000L, DescriptionEditJobRunner.editBackoffMs(9), "capped, never grows without bound")
    }
}
