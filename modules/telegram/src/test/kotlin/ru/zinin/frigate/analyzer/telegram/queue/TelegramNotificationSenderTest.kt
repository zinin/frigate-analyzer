package ru.zinin.frigate.analyzer.telegram.queue

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.requests.abstracts.FileId
import dev.inmo.tgbotapi.requests.abstracts.Request
import dev.inmo.tgbotapi.requests.edit.text.EditChatMessageText
import dev.inmo.tgbotapi.requests.send.SendRichMessage
import dev.inmo.tgbotapi.requests.send.SendTextMessage
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.MessageId
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.files.PhotoFile
import dev.inmo.tgbotapi.types.files.PhotoSize
import dev.inmo.tgbotapi.types.message.abstracts.ChatContentMessage
import dev.inmo.tgbotapi.types.message.abstracts.PrivateContentMessage
import dev.inmo.tgbotapi.types.message.content.RichMessageContent
import dev.inmo.tgbotapi.types.rich.RichBlockPhoto
import dev.inmo.tgbotapi.types.rich.RichTextInfo
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
import ru.zinin.frigate.analyzer.telegram.service.impl.RichNotificationRenderer
import ru.zinin.frigate.analyzer.telegram.service.model.RecordingNotificationData
import java.util.Locale
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    private val renderer = RichNotificationRenderer(msg)
    private val quickExportHandler = mockk<QuickExportHandler>()
    private val runnerProvider = mockk<ObjectProvider<DescriptionEditJobRunner>>()
    private val sender = TelegramNotificationSender(bot, quickExportHandler, renderer, runnerProvider)

    // Раннер строится внутри runTest, чтобы его диспетчер делил планировщик с тестом.
    private lateinit var runner: DescriptionEditJobRunner

    private val recordingId = UUID.randomUUID()

    init {
        every { quickExportHandler.createExportKeyboard(any(), any()) } answers {
            InlineKeyboardMarkup(
                keyboard = listOf(listOf(CallbackDataInlineKeyboardButton("📹 Оригинал", "qe:${firstArg<UUID>()}"))),
            )
        }
        every { runnerProvider.getIfAvailable() } returns null
    }

    /** Включает путь с AI-описанием — по умолчанию бин раннера отсутствует. */
    private fun TestScope.enableDescriptionBeans() {
        runner =
            DescriptionEditJobRunner(
                bot = bot,
                renderer = renderer,
                scope =
                    DescriptionEditScope.forTest(
                        CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob()),
                    ),
            )
        every { runnerProvider.getIfAvailable() } returns runner
    }

    private fun data() =
        RecordingNotificationData(
            camId = "driveway",
            fileName = "clip.mp4",
            detectionsCount = 3,
            analyzedFramesCount = 12,
            analyzeTimeSeconds = 4,
            recordTimestamp = "31 августа 2026 г., 21:15",
            processTimestamp = "31 августа 2026 г., 21:20",
        )

    private fun frames(count: Int) =
        (0 until count).map {
            VisualizedFrameData(frameIndex = it, visualizedBytes = byteArrayOf(1, 2, 3), detectionsCount = 1)
        }

    private fun createTask(
        frameCount: Int = 2,
        frameIds: SharedFrameIds = SharedFrameIds(),
        descriptionHandle: Deferred<Result<DescriptionResult>>? = null,
    ) = RecordingNotificationTask(
        id = UUID.randomUUID(),
        chatId = 12345L,
        data = data(),
        visualizedFrames = frames(frameCount),
        recordingId = recordingId,
        frameIds = frameIds,
        language = "ru",
        descriptionHandle = descriptionHandle,
    )

    /** Ответ Telegram: rich-сообщение с [count] фото-блоками, из которых берутся file_id. */
    private fun richResult(
        count: Int,
        messageId: Long = 1L,
    ): ChatContentMessage<RichMessageContent> {
        val blocks =
            (0 until count).map { i ->
                // photo — это value-класс PhotoFile поверх List<PhotoSize>, голый список не подходит.
                RichBlockPhoto(
                    photo = PhotoFile(listOf(mockk<PhotoSize> { every { fileId } returns FileId("file-$i") })),
                    hasSpoiler = null,
                    caption = null,
                )
            }
        val info = mockk<RichTextInfo> { every { this@mockk.blocks } returns blocks }
        val content = mockk<RichMessageContent> { every { richMessage } returns info }
        // ChatContentMessage — sealed, MockK его не проксирует; PrivateContentMessage это его подтип.
        return mockk<PrivateContentMessage<RichMessageContent>> {
            every { this@mockk.content } returns content
            every { this@mockk.messageId } returns MessageId(messageId)
        }
    }

    @Test
    fun `sends exactly one rich message carrying the export keyboard`() =
        runTest {
            val requests = mutableListOf<Request<*>>()
            coEvery { bot.execute(capture(requests)) } returns richResult(count = 2)

            sender.send(createTask(frameCount = 2))

            assertEquals(1, requests.size, "one recording must produce exactly one message")
            val request = requests.single()
            assertIs<SendRichMessage>(request)
            val keyboard = request.replyMarkup
            assertNotNull(keyboard, "rich message must carry the export keyboard")
            assertIs<InlineKeyboardMarkup>(keyboard)
        }

    @Test
    fun `first recipient uploads frame bytes`() =
        runTest {
            val slot = slot<Request<*>>()
            coEvery { bot.execute(capture(slot)) } returns richResult(count = 2)

            sender.send(createTask(frameCount = 2))

            val request = assertIs<SendRichMessage>(slot.captured)
            assertEquals(2, request.mediaMap.size, "fresh frames go out as multipart uploads")
            assertContains(request.richMessage.html!!, """<img src="tg://photo?id=f0"/>""")
            assertEquals(listOf("f0", "f1"), request.richMessage.media!!.map { it.id })
        }

    @Test
    fun `second recipient reuses file ids and uploads nothing`() =
        runTest {
            val shared = SharedFrameIds()
            val requests = mutableListOf<Request<*>>()
            coEvery { bot.execute(capture(requests)) } returns richResult(count = 2)

            sender.send(createTask(frameCount = 2, frameIds = shared))
            sender.send(createTask(frameCount = 2, frameIds = shared))

            val second = assertIs<SendRichMessage>(requests.last())
            assertTrue(second.mediaMap.isEmpty(), "the second recipient must not re-upload bytes")
            assertEquals(listOf("f0", "f1"), second.richMessage.media!!.map { it.id })
        }

    @Test
    fun `rejected file id falls back to uploading the bytes once`() =
        runTest {
            val shared = SharedFrameIds()
            shared.putIfAbsent(listOf(FileId("stale-0"), FileId("stale-1")))
            val requests = mutableListOf<Request<*>>()
            coEvery { bot.execute(capture(requests)) } answers {
                val request = requests.last() as SendRichMessage
                if (request.mediaMap.isEmpty()) error("Bad Request: wrong file identifier") else richResult(count = 2)
            }

            sender.send(createTask(frameCount = 2, frameIds = shared))

            assertEquals(2, requests.size, "one rejected attempt, then one upload")
            assertTrue((requests.last() as SendRichMessage).mediaMap.isNotEmpty(), "fallback must upload bytes")
        }

    @Test
    fun `a partial answer neither poisons the id cache nor schedules an edit`() =
        runTest {
            enableDescriptionBeans()
            val shared = SharedFrameIds()
            val handle = CompletableDeferred<Result<DescriptionResult>>()
            val requests = mutableListOf<Request<*>>()
            // Ответ вернул один фото-блок на два отправленных кадра.
            coEvery { bot.execute(capture(requests)) } returns richResult(count = 1)

            sender.send(createTask(frameCount = 2, frameIds = shared, descriptionHandle = handle))

            assertEquals(1, requests.size, "the message itself is delivered, only the follow-up is skipped")
            assertNull(shared.get(), "a short id list must never reach the cache")
            assertNull(runner.lastLaunchedJobForTests(), "an edit would strip the frames off the sent message")
            assertTrue(handle.isCancelled, "nothing will consume the model answer")
        }

    @Test
    fun `without the edit runner the placeholder is not rendered at all`() =
        runTest {
            // runnerProvider отдаёт null по умолчанию — бина правки в контексте нет.
            val handle = CompletableDeferred<Result<DescriptionResult>>()
            val slot = slot<Request<*>>()
            coEvery { bot.execute(capture(slot)) } returns richResult(count = 2)

            sender.send(createTask(frameCount = 2, descriptionHandle = handle))

            val request = assertIs<SendRichMessage>(slot.captured)
            assertFalse(
                request.richMessage.html!!.contains(msg.get("ai.description.placeholder.short", "ru")),
                "a placeholder nobody can rewrite would hang forever",
            )
            assertTrue(handle.isCancelled, "no one would apply the model answer")
        }

    @Test
    fun `no frames still produces one message without media`() =
        runTest {
            val slot = slot<Request<*>>()
            coEvery { bot.execute(capture(slot)) } returns richResult(count = 0)

            sender.send(createTask(frameCount = 0))

            val request = assertIs<SendRichMessage>(slot.captured)
            assertTrue(request.mediaMap.isEmpty(), "no frames means no uploads")
            assertTrue(request.richMessage.media.isNullOrEmpty(), "no frames means no media declarations")
        }

    @Test
    fun `description handle is cancelled when there are no frames`() =
        runTest {
            val handle = CompletableDeferred<Result<DescriptionResult>>()
            coEvery { bot.execute(any<Request<*>>()) } returns richResult(count = 0)

            sender.send(createTask(frameCount = 0, descriptionHandle = handle))

            assertTrue(handle.isCancelled, "nothing to describe without frames, and nothing to edit later")
        }

    @Test
    fun `placeholder goes out first and the edit replaces it with the model text`() =
        runTest {
            enableDescriptionBeans()
            val handle = CompletableDeferred<Result<DescriptionResult>>()
            val requests = mutableListOf<Request<*>>()
            coEvery { bot.execute(capture(requests)) } returns richResult(count = 2)

            sender.send(createTask(frameCount = 2, descriptionHandle = handle))
            handle.complete(Result.success(DescriptionResult(short = "Человек у ворот", detailed = "Подробности")))
            runner.lastLaunchedJobForTests()?.join()

            val sent = assertIs<SendRichMessage>(requests.first())
            assertContains(sent.richMessage.html!!, msg.get("ai.description.placeholder.short", "ru"))

            val edit = assertIs<EditChatMessageText>(requests.last())
            val rich = assertNotNull(edit.richMessage, "edit must carry a rich message")
            assertContains(rich.html!!, "Человек у ворот")
            assertContains(rich.html!!, "Подробности")
            assertEquals(
                listOf("f0", "f1"),
                rich.media!!.map { it.id },
                "media must be re-declared on every edit or Telegram answers RICH_MESSAGE_PHOTO_INVALID",
            )
        }

    @Test
    fun `failed description is replaced by the fallback text`() =
        runTest {
            enableDescriptionBeans()
            val handle = CompletableDeferred<Result<DescriptionResult>>()
            val requests = mutableListOf<Request<*>>()
            coEvery { bot.execute(capture(requests)) } returns richResult(count = 2)

            sender.send(createTask(frameCount = 2, descriptionHandle = handle))
            handle.complete(Result.failure(IllegalStateException("model unavailable")))
            runner.lastLaunchedJobForTests()?.join()

            val edit = assertIs<EditChatMessageText>(requests.last())
            val rich = assertNotNull(edit.richMessage, "edit must carry a rich message")
            assertContains(rich.html!!, msg.get("ai.description.fallback.unavailable", "ru"))
        }

    @Test
    fun `frames beyond the media limit are dropped from html and media alike`() =
        runTest {
            val slot = slot<Request<*>>()
            coEvery { bot.execute(capture(slot)) } returns richResult(count = RichNotificationRenderer.MAX_MEDIA)

            sender.send(createTask(frameCount = RichNotificationRenderer.MAX_MEDIA + 1))

            val request = assertIs<SendRichMessage>(slot.captured)
            assertEquals(RichNotificationRenderer.MAX_MEDIA, request.richMessage.media!!.size, "media is capped")
            assertEquals(RichNotificationRenderer.MAX_MEDIA, request.mediaMap.size, "a dropped frame is not uploaded")
            val overflowId = RichNotificationRenderer.mediaId(RichNotificationRenderer.MAX_MEDIA)
            assertFalse(
                request.richMessage.html!!.contains("""tg://photo?id=$overflowId"""),
                "html must not reference a frame the media array does not declare",
            )
        }

    @Test
    fun `simple text task still goes out as a plain message`() =
        runTest {
            val slot = slot<Request<*>>()
            coEvery { bot.execute(capture(slot)) } returns mockk<PrivateContentMessage<*>>(relaxed = true)

            sender.send(
                SimpleTextNotificationTask(id = UUID.randomUUID(), chatId = 12345L, text = "signal lost"),
            )

            val request = assertIs<SendTextMessage>(slot.captured)
            assertEquals("signal lost", request.text)
            val chatId = assertIs<ChatId>(request.chatId)
            assertEquals(12345L, chatId.chatId.long)
        }
}
