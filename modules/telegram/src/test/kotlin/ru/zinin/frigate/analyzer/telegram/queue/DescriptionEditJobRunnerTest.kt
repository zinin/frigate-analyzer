package ru.zinin.frigate.analyzer.telegram.queue

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.bot.exceptions.MessageIsNotModifiedException
import dev.inmo.tgbotapi.bot.exceptions.MessageToEditNotFoundException
import dev.inmo.tgbotapi.requests.abstracts.FileId
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.MessageId
import dev.inmo.tgbotapi.types.RawChatId
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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

    private fun target() =
        EditTarget(
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
            fileIds = listOf(FileId("f-0"), FileId("f-1")),
            exportKeyboard = InlineKeyboardMarkup(keyboard = emptyList()),
            language = "ru",
        )

    private val ready = Result.success(DescriptionResult(short = "Человек у ворот", detailed = "Подробности"))

    @Test
    fun `a not-modified answer is terminal and never retried`() =
        runTest {
            coEvery { bot.execute<Any>(any()) } throws mockk<MessageIsNotModifiedException>(relaxed = true)

            runner().launchEditJob(listOf(target())) { ready }.join()

            // Сообщение уже несёт этот текст — повтор ничего не изменит.
            coVerify(exactly = 1) { bot.execute<Any>(any()) }
        }

    @Test
    fun `a deleted message is terminal and never retried`() =
        runTest {
            coEvery { bot.execute<Any>(any()) } throws mockk<MessageToEditNotFoundException>(relaxed = true)

            runner().launchEditJob(listOf(target())) { ready }.join()

            // Пользователь удалил сообщение — править нечего.
            coVerify(exactly = 1) { bot.execute<Any>(any()) }
        }

    @Test
    fun `a transient failure is retried to the bound and then given up`() =
        runTest {
            coEvery { bot.execute<Any>(any()) } throws RuntimeException("429 Too Many Requests")

            // Завершение здесь важно не меньше числа попыток: неограниченный ретрай удержал бы scope
            // и сорвал бы остановку по @PreDestroy.
            runner().launchEditJob(listOf(target())) { ready }.join()

            // EDIT_MAX_ATTEMPTS — best-effort правка сдаётся, а не крутится вечно.
            coVerify(exactly = 5) { bot.execute<Any>(any()) }
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
