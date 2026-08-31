# Единое rich-сообщение для уведомления о детекции — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** одно событие детекции порождает одно сообщение в Telegram вместо двух.

**Architecture:** отправка переходит с `SendPhoto`/`sendMediaGroup`/`sendTextMessage` на единственный
`sendRichMessage` с HTML-телом, коллажем кадров и inline-клавиатурой. Метаданные едут в задаче структурой
и верстаются таблицей в новом `RichNotificationRenderer`. Правка после ответа модели становится одной
`editMessageText(rich_message)` вместо двух вызовов. Кадры грузит только первый получатель, остальные
ссылаются на его `file_id`.

**Tech Stack:** Kotlin 2.4.10, Spring Boot 4.1.0, ktgbotapi 36.1.0, Ktor 3.5.2, Coroutines 1.11.0,
JUnit 5 + MockK, Java 25.

**Spec:** `docs/superpowers/specs/2026-08-31-rich-message-notification-design.md`

## Global Constraints

- `ktgbotapi` = `36.1.0`, `ktor` = `3.5.2`, `coroutines` = `1.11.0` — версии держатся в lockstep, см. комментарии в `gradle/libs.versions.toml`.
- Конструкции Bot API 10.3 не отправляются: никаких `<tg-button>`, `<tg-document>`, `<blockquote expandable>` внутри rich-сообщения. Библиотека падает на разборе ответа с такой сущностью, а сообщение при этом доставляется.
- Лимиты rich-сообщения: 32768 символов текста, не более 50 медиа.
- Медиа в правке переобъявляются всегда. Без массива `media` Telegram отвечает `400 RICH_MESSAGE_PHOTO_INVALID`, даже если HTML ссылается на прежние `tg://photo?id=`.
- Идентификатор медиа формируется единственным местом — `RichNotificationRenderer.mediaId(index)`. HTML и массив `media` обязаны использовать одни и те же строки.
- TDD: сначала падающий тест, потом реализация.
- `./gradlew` напрямую не запускается — сборка через агент `claude-forge:build-runner` (правило проекта в `CLAUDE.md`). При ошибках ktlint: `./gradlew ktlintFormat`, затем повторная сборка.
- После создания или изменения любого файла — `git add <файл>`.

---

### Task 1: Апгрейд ktgbotapi до 36.1.0

✅ Done — see commit(s): `d3a1e35`

Итог: `ktgbotapi 36.1.0`, `ktor 3.5.2`, `coroutines 1.11.0` в lockstep; правок кода не потребовалось,
ломающие изменения 36.0.0 наш код не задели. BUILD SUCCESSFUL, 808 тестов.

---

### Task 2: Модель данных, состояние описания и рендерер HTML

✅ Done — see commit(s): `53b1be5`, `95a37ef`

Созданы `RecordingNotificationData`, `DescriptionState`, `RichNotificationRenderer`, восемь i18n-ключей
в обоих бандлах, двенадцать тестов рендерера.

**Interfaces (что Task 4 потребляет отсюда — сигнатуры фиксированы, не менять):**

- `RecordingNotificationData(camId, fileName, detectionsCount: Int, analyzedFramesCount: Int, analyzeTimeSeconds: Int, recordTimestamp: String, processTimestamp: String)` — пакет `telegram.service.model`
- `DescriptionState` — `Absent` / `Pending` / `Ready(result: DescriptionResult)` / `Failed`, пакет `telegram.service.model`
- `RichNotificationRenderer.render(data: RecordingNotificationData, description: DescriptionState, frameCount: Int, language: String): String`
- `RichNotificationRenderer.mediaId(index: Int): String` → `"f$index"`, и `RichNotificationRenderer.MAX_MEDIA = 50` — оба на companion
- Сверх исходного плана: `MAX_SHORT_LENGTH = 2_000` ограничивает текст модели `short`

**Что Task 4 обязан соблюсти:** рендерер клампит число кадров до `MAX_MEDIA` молча, поэтому отправитель
должен приложить ровно `min(frameCount, MAX_MEDIA)` элементов `media` — иначе `<img src="tg://photo?id=fN">`
и массив `media` разъедутся и Telegram отвергнет сообщение.

---

### Task 3: Держатель file_id, общий на запись

**Files:**
- Create: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/SharedFrameIds.kt`
- Test: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/queue/SharedFrameIdsTest.kt`

**Interfaces:**
- Consumes: `dev.inmo.tgbotapi.requests.abstracts.FileId`.
- Produces: `SharedFrameIds` с методами `get(): List<FileId>?`, `putIfAbsent(ids: List<FileId>): Boolean`, `invalidate()`.

- [ ] **Step 1: Написать падающий тест**

```kotlin
package ru.zinin.frigate.analyzer.telegram.queue

import dev.inmo.tgbotapi.requests.abstracts.FileId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedFrameIdsTest {
    @Test
    fun `starts empty`() {
        assertNull(SharedFrameIds().get())
    }

    @Test
    fun `first writer wins and later writers are ignored`() {
        val shared = SharedFrameIds()

        assertTrue(shared.putIfAbsent(listOf(FileId("first"))))
        assertFalse(shared.putIfAbsent(listOf(FileId("second"))))

        assertEquals(listOf(FileId("first")), shared.get())
    }

    @Test
    fun `invalidate clears the cache so the next sender uploads again`() {
        val shared = SharedFrameIds()
        shared.putIfAbsent(listOf(FileId("first")))

        shared.invalidate()

        assertNull(shared.get())
        assertTrue(shared.putIfAbsent(listOf(FileId("second"))))
    }

    @Test
    fun `empty list is not cached`() {
        val shared = SharedFrameIds()

        assertFalse(shared.putIfAbsent(emptyList()), "an empty result must not poison the cache")
        assertNull(shared.get())
    }
}
```

- [ ] **Step 2: Убедиться, что тест не компилируется**

`./gradlew :telegram:compileTestKotlin` → FAILED, `Unresolved reference: SharedFrameIds`.

- [ ] **Step 3: Реализовать**

```kotlin
package ru.zinin.frigate.analyzer.telegram.queue

import dev.inmo.tgbotapi.requests.abstracts.FileId
import java.util.concurrent.atomic.AtomicReference

/**
 * Идентификаторы загруженных кадров одной записи, общие для всех её получателей.
 * Первый отправитель грузит байты и кладёт сюда `file_id`, остальные ссылаются на них.
 *
 * Очередь уведомлений разбирается одним потребителем, поэтому «первый» определяется порядком
 * задач и гонки нет. [AtomicReference] защищает не от неё, а от возможного распараллеливания
 * очереди в будущем: худшее, что тогда случится — лишняя загрузка, а не рассинхронизация.
 */
class SharedFrameIds {
    private val ref = AtomicReference<List<FileId>?>(null)

    fun get(): List<FileId>? = ref.get()

    /** Пустой список не кэшируется: иначе получатели без кадров отравили бы кэш остальным. */
    fun putIfAbsent(ids: List<FileId>): Boolean {
        if (ids.isEmpty()) return false
        return ref.compareAndSet(null, ids)
    }

    /** Сбрасывает кэш после отказа отправки по `file_id`, чтобы кадры ушли байтами. */
    fun invalidate() {
        ref.set(null)
    }
}
```

- [ ] **Step 4: Прогнать тест**

`./gradlew :telegram:test --tests "*SharedFrameIdsTest"` → PASS.

- [ ] **Step 5: Коммит**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/SharedFrameIds.kt \
        modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/queue/SharedFrameIdsTest.kt
git commit -m "feat(telegram): share uploaded frame ids across recipients"
```

---

### Task 4: Перевод отправки и правки на rich-сообщение

Самая крупная задача, и она атомарна: смена типа `RecordingNotificationTask.message` и формы
`EditTarget` ломает компиляцию сразу в четырёх файлах, разделить их нельзя.

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/NotificationTask.kt:15-32`
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImpl.kt:105-162`
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/TelegramNotificationSender.kt` (целиком)
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/DescriptionEditJobRunner.kt:26-150`
- Test: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/queue/TelegramNotificationSenderTest.kt` (переписывается)
- Test: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImplTest.kt:115` (правится)

**Interfaces:**
- Consumes: `RichNotificationRenderer.render(...)`, `RichNotificationRenderer.mediaId(index)`, `RichNotificationRenderer.MAX_MEDIA`, `SharedFrameIds`, `RecordingNotificationData`, `DescriptionState`.
- Produces:
  - `RecordingNotificationTask(id, chatId, data: RecordingNotificationData, visualizedFrames, recordingId, frameIds: SharedFrameIds, language, descriptionHandle, createdAt)`
  - `EditTarget(chatId: ChatIdentifier, messageId: MessageId, data: RecordingNotificationData, fileIds: List<FileId>, exportKeyboard: InlineKeyboardMarkup, language: String)`
  - `TelegramNotificationSender(bot, quickExportHandler, renderer, editJobRunner)` — параметры `msg` и `descriptionFormatter` уходят.
  - `DescriptionEditJobRunner(bot, renderer, scope)`.

- [ ] **Step 1: Переписать тест отправителя**

Полностью заменить `TelegramNotificationSenderTest.kt`. Прежние кейсы «пусто / одно фото / альбом»
и рефлексивный хелпер `extractReplyMarkup` уходят: `SendRichMessage` приходит в `bot.execute`
напрямую, без multipart-обёртки, поэтому поля читаются как обычные свойства.

```kotlin
package ru.zinin.frigate.analyzer.telegram.queue

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.requests.abstracts.FileId
import dev.inmo.tgbotapi.requests.abstracts.Request
import dev.inmo.tgbotapi.requests.edit.text.EditChatMessageText
import dev.inmo.tgbotapi.requests.send.SendRichMessage
import dev.inmo.tgbotapi.types.MessageId
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
                RichBlockPhoto(
                    photo = listOf(mockk<PhotoSize> { every { fileId } returns FileId("file-$i") }),
                    hasSpoiler = null,
                    caption = null,
                )
            }
        val info = mockk<RichTextInfo> { every { this@mockk.blocks } returns blocks }
        val content = mockk<RichMessageContent> { every { richMessage } returns info }
        return mockk<ChatContentMessage<RichMessageContent>> {
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
            coEvery { bot.execute(any()) } returns richResult(count = 0)

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
    fun `simple text task still goes out as a plain message`() =
        runTest {
            val slot = slot<Request<*>>()
            coEvery { bot.execute(capture(slot)) } returns mockk<PrivateContentMessage<*>>(relaxed = true)

            sender.send(
                SimpleTextNotificationTask(id = UUID.randomUUID(), chatId = 12345L, text = "signal lost"),
            )

            assertTrue(slot.captured !is SendRichMessage, "signal-loss alerts stay plain text")
        }
}
```

- [ ] **Step 2: Убедиться, что тест не компилируется**

`./gradlew :telegram:compileTestKotlin`

Ожидание: FAILED — конструктор `TelegramNotificationSender` не принимает `renderer`, у
`RecordingNotificationTask` нет параметров `data` и `frameIds`.

- [ ] **Step 3: Сменить полезную нагрузку задачи**

В `NotificationTask.kt` заменить в `RecordingNotificationTask` поле `message` и добавить `frameIds`:

```kotlin
data class RecordingNotificationTask(
    override val id: UUID,
    override val chatId: Long,
    val data: RecordingNotificationData,
    val visualizedFrames: List<VisualizedFrameData>,
    /** ID of the recording, used for callback data in inline export buttons. */
    val recordingId: UUID,
    /**
     * Идентификаторы кадров, общие для всех получателей одной записи: первый отправитель
     * грузит байты, остальные ссылаются на его `file_id`.
     */
    val frameIds: SharedFrameIds,
    val language: String? = null,
    /**
     * Shared Deferred across all recipients of the same recording — one AI request
     * fans out to N edits (one per recipient). Started in
     * TelegramNotificationServiceImpl.sendRecordingNotification AFTER subscriber
     * filtering, before enqueue of each task.
     * null — feature disabled / no frames / no subscribers.
     */
    val descriptionHandle: Deferred<Result<DescriptionResult>>? = null,
    override val createdAt: Instant = Instant.now(),
) : NotificationTask
```

Добавить импорт `ru.zinin.frigate.analyzer.telegram.service.model.RecordingNotificationData`.

- [ ] **Step 4: Переписать сборку данных в сервисе**

В `TelegramNotificationServiceImpl.kt` заменить `formatRecordingMessage` на `buildNotificationData`:

```kotlin
    private fun buildNotificationData(
        recording: RecordingDto,
        zone: ZoneId,
        language: String,
    ): RecordingNotificationData {
        val formatter =
            DateTimeFormatter
                .ofLocalizedDateTime(FormatStyle.LONG)
                .withLocale(Locale.forLanguageTag(language))

        return RecordingNotificationData(
            camId = recording.camId,
            fileName = recording.filePath.substringAfterLast("/"),
            detectionsCount = recording.detectionsCount,
            analyzedFramesCount = recording.analyzedFramesCount,
            analyzeTimeSeconds = recording.analyzeTime,
            recordTimestamp = recording.recordTimestamp.atZone(zone).format(formatter),
            processTimestamp = recording.processTimestamp?.atZone(zone)?.format(formatter) ?: "N/A",
        )
    }
```

В цикле по получателям создать один `SharedFrameIds` **до** цикла и передать его в каждую задачу:

```kotlin
        val frameIds = SharedFrameIds()
        recipients.forEach { userZone ->
            val lang = userZone.language ?: "en"
            val task =
                RecordingNotificationTask(
                    id = uuidGeneratorHelper.generateV1(),
                    chatId = userZone.chatId,
                    data = buildNotificationData(recording, userZone.zone, lang),
                    visualizedFrames = visualizedFrames,
                    recordingId = recording.id,
                    frameIds = frameIds,
                    language = userZone.language,
                    descriptionHandle = descriptionHandle,
                )
            notificationQueue.enqueue(task)
        }
```

Цикл уже существует в `TelegramNotificationServiceImpl.kt:105-119`; в нём меняются ровно две вещи — `message = message` превращается в `data = buildNotificationData(...)`, и добавляется `frameIds = frameIds`. Объявление `val frameIds = SharedFrameIds()` ставится перед циклом, чтобы все получатели одной записи делили один экземпляр.

- [ ] **Step 5: Переписать отправитель**

Заменить в `TelegramNotificationSender.kt` конструктор и всю ветку записи:

```kotlin
@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class TelegramNotificationSender(
    private val bot: TelegramBot,
    private val quickExportHandler: QuickExportHandler,
    private val renderer: RichNotificationRenderer,
    // ObjectProvider — бин правок существует только при application.ai.description.enabled=true.
    private val editJobRunner: ObjectProvider<DescriptionEditJobRunner>,
) {
    suspend fun send(task: NotificationTask) {
        when (task) {
            is RecordingNotificationTask -> sendRecording(task)
            is SimpleTextNotificationTask -> sendSimpleText(task)
        }
    }

    private suspend fun sendSimpleText(task: SimpleTextNotificationTask) {
        val chatIdObj = ChatId(RawChatId(task.chatId))
        RetryHelper.retryIndefinitely("Send simple text", task.chatId) {
            bot.sendTextMessage(chatId = chatIdObj, text = task.text)
        }
    }

    private suspend fun sendRecording(task: RecordingNotificationTask) {
        val chatIdObj = ChatId(RawChatId(task.chatId))
        val lang = task.language ?: "en"
        val exportKeyboard = quickExportHandler.createExportKeyboard(task.recordingId, lang)
        val frames = task.visualizedFrames.take(RichNotificationRenderer.MAX_MEDIA)

        // Описание существует только когда есть что описывать и включена сама фича.
        val describing = task.descriptionHandle != null && frames.isNotEmpty()
        val state = if (describing) DescriptionState.Pending else DescriptionState.Absent
        val html = renderer.render(task.data, state, frames.size, lang)

        val sent = sendRich(chatIdObj, task, frames, html, exportKeyboard)
        val fileIds = sent.content.richMessage.blocks.filterIsInstance<RichBlockPhoto>().mapNotNull { it.photo.lastOrNull()?.fileId }
        task.frameIds.putIfAbsent(fileIds)

        if (!describing) {
            // Плейсхолдера в сообщении нет, редактировать нечего — не жжём токены модели.
            task.descriptionHandle?.cancel()
            return
        }
        val runner = editJobRunner.getIfAvailable() ?: return
        val handle = requireNotNull(task.descriptionHandle) { "describing == true implies descriptionHandle != null" }
        val target =
            EditTarget(
                chatId = chatIdObj,
                messageId = sent.messageId,
                data = task.data,
                fileIds = fileIds,
                exportKeyboard = exportKeyboard,
                language = lang,
            )
        runner.launchEditJob(listOf(target)) { handle.await() }
    }

    /**
     * Отправка с переиспользованием `file_id`, если их уже получил предыдущий получатель.
     *
     * Попытка по `file_id` делается ровно одна и БЕЗ бесконечного ретрая: иначе отказ
     * (устаревший или неприменимый идентификатор) крутился бы вечно и до загрузки байтов
     * дело бы не дошло. Загрузка байтами — уже с обычным `retryIndefinitely`.
     */
    private suspend fun sendRich(
        chatIdObj: ChatId,
        task: RecordingNotificationTask,
        frames: List<VisualizedFrameData>,
        html: String,
        exportKeyboard: InlineKeyboardMarkup,
    ): ChatContentMessage<RichMessageContent> {
        val cached = task.frameIds.get()
        if (cached != null && cached.size == frames.size && frames.isNotEmpty()) {
            val media = cached.mapIndexed { i, id -> InputRichMessageMedia(RichNotificationRenderer.mediaId(i), TelegramMediaPhoto(id)) }
            try {
                return deliver(chatIdObj, html, media, exportKeyboard)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Cached frame file_id rejected for chat=${task.chatId}; falling back to upload" }
                task.frameIds.invalidate()
            }
        }
        val media =
            frames.mapIndexed { i, frame ->
                InputRichMessageMedia(
                    RichNotificationRenderer.mediaId(i),
                    TelegramMediaPhoto(frame.visualizedBytes.asMultipartFile("frame_${frame.frameIndex}.jpg")),
                )
            }
        return RetryHelper.retryIndefinitely("Send rich notification", task.chatId) {
            deliver(chatIdObj, html, media, exportKeyboard)
        }
    }

    private suspend fun deliver(
        chatIdObj: ChatId,
        html: String,
        media: List<InputRichMessageMedia>,
        exportKeyboard: InlineKeyboardMarkup,
    ): ChatContentMessage<RichMessageContent> =
        bot.sendRichMessage(
            chatIdObj,
            InputRichMessageHTML(html, media = media.ifEmpty { null }),
            replyMarkup = exportKeyboard,
        )
}
```

Импорты, которые уходят: `sendMediaGroup`, `SendPhoto`, `ReplyParameters`, `HTMLParseMode`, `ParseMode`,
`MessageId`, `MessageResolver`, `DescriptionMessageFormatter`. `TelegramMediaPhoto` остаётся — теперь он
оборачивает и загружаемые байты, и `file_id`.
Импорты, которые появляются: `dev.inmo.tgbotapi.extensions.api.send.sendRichMessage`,
`dev.inmo.tgbotapi.types.rich.InputRichMessageHTML`, `InputRichMessageMedia`, `RichBlockPhoto`,
`dev.inmo.tgbotapi.types.message.abstracts.ChatContentMessage`,
`dev.inmo.tgbotapi.types.message.content.RichMessageContent`,
`kotlinx.coroutines.CancellationException`, `RichNotificationRenderer`, `DescriptionState`.

Удалить `companion object` с `MAX_MEDIA_GROUP_SIZE`/`MAX_CAPTION_LENGTH` и приватный `String.toCaption`.

- [ ] **Step 6: Переписать цель и тело правки**

В `DescriptionEditJobRunner.kt` заменить `EditTarget` и три метода правки одним:

```kotlin
/**
 * Цель правки: сообщение, ушедшее с плейсхолдерами, и всё, что нужно, чтобы собрать его заново.
 *
 * `fileIds` обязательны: rich-сообщение при правке переобъявляет медиа целиком, иначе Telegram
 * отвечает `RICH_MESSAGE_PHOTO_INVALID`.
 */
data class EditTarget(
    val chatId: ChatIdentifier,
    val messageId: MessageId,
    val data: RecordingNotificationData,
    val fileIds: List<FileId>,
    val exportKeyboard: InlineKeyboardMarkup,
    val language: String,
)
```

Конструктор класса: `formatter: DescriptionMessageFormatter` → `renderer: RichNotificationRenderer`.

```kotlin
    private suspend fun editOne(
        target: EditTarget,
        outcome: Result<DescriptionResult>,
    ) {
        val state =
            outcome.fold(
                onSuccess = { DescriptionState.Ready(it) },
                onFailure = { DescriptionState.Failed },
            )
        val html = renderer.render(target.data, state, target.fileIds.size, target.language)
        val media =
            target.fileIds.mapIndexed { i, id ->
                InputRichMessageMedia(RichNotificationRenderer.mediaId(i), TelegramMediaPhoto(id))
            }
        runEdit("rich notification", target) {
            bot.execute(
                EditChatMessageRichText(
                    target.chatId,
                    target.messageId,
                    InputRichMessageHTML(html, media = media.ifEmpty { null }),
                    replyMarkup = target.exportKeyboard,
                ),
            )
        }
    }
```

`runEdit`, обработка `MessageIsNotModifiedException`/`MessageToEditNotFoundException`, `editBackoffMs`
и `launchEditJob` остаются без изменений.

- [ ] **Step 7: Обновить тест сервиса уведомлений**

`TelegramNotificationServiceImplTest.kt:115` проверяет исчезнувшее поле:

```kotlin
assertTrue(taskSlot.captured.message.contains("camera1"), "message should contain camera ID")
```

Заменить на проверку структуры:

```kotlin
assertEquals("camera1", taskSlot.captured.data.camId, "task must carry the camera id as data")
```

И добавить тест на общий держатель идентификаторов — он опирается на существующий в этом файле
кейс `sendRecordingNotification sends to all subscribers with correct recordingId`, откуда берётся
готовая обвязка с двумя подписчиками:

```kotlin
    @Test
    fun `all recipients of one recording share a single frame id holder`() =
        runTest {
            val tasks = mutableListOf<RecordingNotificationTask>()
            coEvery { notificationQueue.enqueue(capture(tasks)) } returns Unit

            // Обвязку с двумя авторизованными подписчиками взять из теста
            // `sendRecordingNotification sends to all subscribers with correct recordingId`.
            service.sendRecordingNotification(recording, visualizedFrames, descriptionSupplier = null)

            assertEquals(2, tasks.size, "both subscribers must be enqueued")
            assertSame(
                tasks[0].frameIds,
                tasks[1].frameIds,
                "one upload must serve every recipient of the same recording",
            )
        }
```

Импорт: `kotlin.test.assertSame`.

- [ ] **Step 8: Прогнать тесты модуля**

`./gradlew :telegram:test`

Ожидание: PASS. Ожидаемые места отказов и что с ними делать:
- `richResult` не отдаёт `content` — проверить, что мок построен на `PrivateContentMessage<RichMessageContent>`, а не на `ContentMessage<*>`.
- Тест фолбэка зацикливается — значит попытка по `file_id` попала под `retryIndefinitely`; она обязана быть без ретрая.
- Ошибки ktlint — `./gradlew ktlintFormat`, затем повтор.

- [ ] **Step 9: Коммит**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/NotificationTask.kt \
        modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/TelegramNotificationSender.kt \
        modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/DescriptionEditJobRunner.kt \
        modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImpl.kt \
        modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/queue/TelegramNotificationSenderTest.kt \
        modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImplTest.kt
git commit -m "feat(telegram): send one rich message per recording instead of two"
```

---

### Task 5: Удаление форматтера и мёртвых ключей

**Files:**
- Delete: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/DescriptionMessageFormatter.kt`
- Delete: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/DescriptionMessageFormatterTest.kt`
- Modify: `modules/telegram/src/main/resources/messages_ru.properties`
- Modify: `modules/telegram/src/main/resources/messages_en.properties`

**Interfaces:**
- Consumes: ничего нового.
- Produces: ничего — задача снимает мёртвый код.

- [ ] **Step 1: Убедиться, что форматтер больше никто не зовёт**

```bash
grep -rn "DescriptionMessageFormatter" --include="*.kt" modules/
```

Ожидание: только два удаляемых файла. Любая другая ссылка означает, что Task 4 не доведён.

- [ ] **Step 2: Удалить форматтер и его тест**

```bash
git rm modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/DescriptionMessageFormatter.kt \
       modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/DescriptionMessageFormatterTest.kt
```

- [ ] **Step 3: Удалить неиспользуемые ключи**

Из `messages_ru.properties` и `messages_en.properties` убрать восемь строк:
`notification.recording.camera`, `notification.recording.file`, `notification.recording.detections`,
`notification.recording.frames`, `notification.recording.processing.time`,
`notification.recording.timestamp`, `notification.recording.processed`,
`notification.recording.export.prompt`.

Оставить `notification.recording.title` — он стал заголовком rich-сообщения.

- [ ] **Step 4: Проверить, что ни один удалённый ключ не остался в коде**

```bash
grep -rn "notification.recording.export.prompt\|notification.recording.camera\|notification.recording.processed" --include="*.kt" modules/
```

Ожидание: пусто.

- [ ] **Step 5: Полная сборка**

Через `claude-forge:build-runner`: `./gradlew build`

Ожидание: BUILD SUCCESSFUL.

- [ ] **Step 6: Коммит**

```bash
git add modules/telegram/src/main/resources/messages_ru.properties modules/telegram/src/main/resources/messages_en.properties
git commit -m "refactor(telegram): drop the caption formatter and its dead i18n keys"
```

---

### Task 6: Документация

**Files:**
- Modify: `.claude/rules/telegram.md`
- Modify: `.claude/rules/ai-description.md`
- Modify: `docs/telegram-rich-message-migration.md`
- Modify: `CLAUDE.md:5`

**Interfaces:**
- Consumes: поведение, зафиксированное задачами 1–5.
- Produces: ничего исполняемого.

- [ ] **Step 1: Описать новую схему в правилах телеграма**

В `.claude/rules/telegram.md` заменить описание отправки уведомлений: одно rich-сообщение на запись,
таблица метаданных, коллаж кадров от двух штук, `<details>` для подробного описания, клавиатура
экспорта на самом сообщении, `file_id` переиспользуются между получателями.

Добавить предупреждение: конструкции Bot API 10.3 (`<tg-button>`, `<tg-document>`) не отправлять —
ktgbotapi 36.1.0 падает на разборе ответа, при том что сообщение доставляется, и `retryIndefinitely`
отправит его повторно.

- [ ] **Step 2: Обновить правила AI-описания**

В `.claude/rules/ai-description.md`: правка одна вместо двух, цель правки несёт `file_id` кадров,
медиа переобъявляются при каждой правке, состояние описания передаётся типом `DescriptionState`.

- [ ] **Step 3: Обновить версию библиотеки в двух местах документации**

После Task 1 обе строки утверждают неверное:

- `CLAUDE.md:5` — в строке `**Stack:**` заменить `ktgbotapi 35.1.0` на `ktgbotapi 36.1.0`.
- `.claude/rules/telegram.md:140` — заголовок `## ktgbotapi Waiter API (v35.1.0)` → `(v36.1.0)`.

Проверить, что других упоминаний не осталось:

```bash
grep -rn "35\.1\.0" --include="*.md" . | grep -v docs/superpowers | grep -v telegram-rich-message-migration
```

Ожидание: пусто (историческое упоминание в `gradle/libs.versions.toml:18` — намеренное, его не трогаем).

- [ ] **Step 4: Пометить исследование реализованным**

В шапке `docs/telegram-rich-message-migration.md` заменить строку статуса на:

```markdown
**Статус:** реализовано в `feature/rich-message-notification`; документ сохранён как история
исследования API и живой проверки.
```

- [ ] **Step 5: Коммит**

```bash
git add .claude/rules/telegram.md .claude/rules/ai-description.md docs/telegram-rich-message-migration.md CLAUDE.md
git commit -m "docs: describe the single rich message notification flow"
```

---

## Проверка после реализации

Ручная, на живом боте — автоматикой не закрывается:

1. Уведомление с несколькими кадрами: одно сообщение, коллаж, таблица, клавиатура экспорта.
2. Появление описания: текст заменяет плейсхолдеры, кадры остаются на месте.
3. Двое подписчиков: второму кадры уходят без повторной загрузки (в логах нет второй загрузки, сообщение приходит целым). **Это единственная проверка кросс-чатового `file_id`** — предположение, которое не удалось проверить в исследовании.
4. Вид на телефоне: таблица на узком экране.
5. Запись без кадров: одно сообщение без медиа, описание не запрашивается.
