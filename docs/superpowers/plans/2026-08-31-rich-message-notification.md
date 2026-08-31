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

Изолированная задача: версии меняются, код остаётся прежним. Смысл — убедиться, что мажорный апгрейд
не ломает бот вне зоны переделки (команды, экспорт, авторизация), до того как начнётся сама переделка.

**Files:**
- Modify: `gradle/libs.versions.toml:16-22`

**Interfaces:**
- Consumes: ничего.
- Produces: `dev.inmo:tgbotapi:36.1.0` в classpath — все последующие задачи опираются на типы `dev.inmo.tgbotapi.types.rich.*`.

- [ ] **Step 1: Поднять версии**

В `gradle/libs.versions.toml` заменить блок:

```toml
ktgbotapi = "36.1.0"
# Keep in lockstep with what ktgbotapi pulls in transitively: 36.1.0 -> ktor 3.5.2 and
# kotlinx-coroutines 1.11.0 (35.1.0 -> ktor 3.5.1). Mismatching them puts ktor out of step
# with the library compiled against it.
ktor = "3.5.2"
```

Строки про `coroutines = "1.11.0"` и комментарий о BOM Spring Boot оставить без изменений — 36.1.0
собран против той же версии корутин.

- [ ] **Step 2: Собрать проект целиком**

Через агент `claude-forge:build-runner`, команда: `./gradlew build`

Ожидание: BUILD SUCCESSFUL. Если компиляция падает вне модуля `telegram` — это и есть тот сюрприз,
ради которого задача отделена; чинить здесь же, до перехода к Task 2.

Известные ломающие изменения 36.0.0 проверены и наш код не задевают: позиционного создания
`Common*ContentMessageImpl` в репозитории нет, `ReplyParameters(message.metaInfo)` в
`StatusCommandHandler.kt:51` продолжает разрешаться (`metaInfo` по-прежнему `Triple`), новое поведение
`reply(to = …)` касается только ephemeral-сообщений.

- [ ] **Step 3: Коммит**

```bash
git add gradle/libs.versions.toml
git commit -m "build: upgrade ktgbotapi to 36.1.0 (Bot API 10.2) and ktor to 3.5.2"
```

---

### Task 2: Модель данных, состояние описания и рендерер HTML

Самодостаточная задача: новые классы пока никто не использует, поэтому тестируются в изоляции.
Старые i18n-ключи не трогаем — они ещё нужны работающему коду.

**Files:**
- Create: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/model/RecordingNotificationData.kt`
- Create: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/model/DescriptionState.kt`
- Create: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/RichNotificationRenderer.kt`
- Modify: `modules/telegram/src/main/resources/messages_ru.properties`
- Modify: `modules/telegram/src/main/resources/messages_en.properties`
- Test: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/RichNotificationRendererTest.kt`

**Interfaces:**
- Consumes: `MessageResolver.get(key: String, language: String, vararg args: Any): String`, `DescriptionResult(short: String, detailed: String)`.
- Produces:
  - `RecordingNotificationData(camId, fileName, detectionsCount, analyzedFramesCount, analyzeTimeSeconds, recordTimestamp, processTimestamp)` — все `String`, кроме трёх `Int`.
  - `DescriptionState` — `Absent` / `Pending` / `Ready(result: DescriptionResult)` / `Failed`.
  - `RichNotificationRenderer.render(data: RecordingNotificationData, description: DescriptionState, frameCount: Int, language: String): String`
  - `RichNotificationRenderer.mediaId(index: Int): String` и `RichNotificationRenderer.MAX_MEDIA: Int` — оба на `companion object`.

- [ ] **Step 1: Добавить i18n-ключи**

В `messages_ru.properties` рядом с блоком `notification.recording.*` дописать:

```properties
notification.recording.label.camera=Камера
notification.recording.label.file=Файл
notification.recording.label.detections=Обнаружений
notification.recording.label.frames=Кадров проанализировано
notification.recording.label.analyze.time=Время обработки, сек
notification.recording.label.record.timestamp=Запись
notification.recording.label.process.timestamp=Обработка
ai.description.details.summary=Подробное описание
```

В `messages_en.properties`:

```properties
notification.recording.label.camera=Camera
notification.recording.label.file=File
notification.recording.label.detections=Detections
notification.recording.label.frames=Frames analyzed
notification.recording.label.analyze.time=Processing time, sec
notification.recording.label.record.timestamp=Recording
notification.recording.label.process.timestamp=Processed
ai.description.details.summary=Detailed description
```

Единица измерения переехала в подпись, поэтому в ячейку значения попадает голое число.

- [ ] **Step 2: Написать падающий тест рендерера**

Создать `RichNotificationRendererTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.service.impl

import org.junit.jupiter.api.Test
import org.springframework.context.support.ReloadableResourceBundleMessageSource
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.service.model.DescriptionState
import ru.zinin.frigate.analyzer.telegram.service.model.RecordingNotificationData
import java.util.Locale
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RichNotificationRendererTest {
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

    private fun data(
        camId: String = "driveway",
        fileName: String = "2026-08-31_21-15-03.mp4",
    ) = RecordingNotificationData(
        camId = camId,
        fileName = fileName,
        detectionsCount = 3,
        analyzedFramesCount = 12,
        analyzeTimeSeconds = 4,
        recordTimestamp = "31 августа 2026 г., 21:15",
        processTimestamp = "31 августа 2026 г., 21:20",
    )

    @Test
    fun `renders heading and metadata table`() {
        val html = renderer.render(data(), DescriptionState.Absent, frameCount = 0, language = "ru")

        assertContains(html, "<h2>")
        assertContains(html, "<table bordered striped compact>")
        assertContains(html, "<tr><td>Камера</td><td>driveway</td></tr>")
        assertContains(html, "<tr><td>Время обработки, сек</td><td>4</td></tr>")
        assertContains(html, "<tr><td>Запись</td><td>31 августа 2026 г., 21:15</td></tr>")
    }

    @Test
    fun `escapes html special characters in values`() {
        val html = renderer.render(
            data(camId = "cam<1>&2", fileName = "a<b>.mp4"),
            DescriptionState.Absent,
            frameCount = 0,
            language = "ru",
        )

        assertContains(html, "cam&lt;1&gt;&amp;2")
        assertFalse(html.contains("cam<1>"), "raw angle brackets must not survive escaping")
    }

    @Test
    fun `single frame renders as plain img, two or more as collage`() {
        val one = renderer.render(data(), DescriptionState.Absent, frameCount = 1, language = "ru")
        assertContains(one, """<img src="tg://photo?id=f0"/>""")
        assertFalse(one.contains("<tg-collage>"), "one frame needs no collage")

        val three = renderer.render(data(), DescriptionState.Absent, frameCount = 3, language = "ru")
        assertContains(three, "<tg-collage>")
        assertContains(three, """<img src="tg://photo?id=f2"/>""")
    }

    @Test
    fun `zero frames renders no media at all`() {
        val html = renderer.render(data(), DescriptionState.Absent, frameCount = 0, language = "ru")

        assertFalse(html.contains("<img"), "no frames means no img tags")
        assertFalse(html.contains("<tg-collage>"), "no frames means no collage")
    }

    @Test
    fun `absent description renders neither paragraph nor details`() {
        val html = renderer.render(data(), DescriptionState.Absent, frameCount = 2, language = "ru")

        assertFalse(html.contains("<details>"), "disabled description must not render a details block")
        assertFalse(html.contains("Подробное описание"), "disabled description must not render a summary")
    }

    @Test
    fun `pending description renders placeholders`() {
        val html = renderer.render(data(), DescriptionState.Pending, frameCount = 2, language = "ru")

        // Плейсхолдеры бандла несут собственную разметку (<i>…</i>) и обязаны дойти неэкранированными.
        assertContains(html, msg.get("ai.description.placeholder.short", "ru"))
        assertContains(html, "<details><summary>Подробное описание</summary>")
        assertContains(html, msg.get("ai.description.placeholder.detailed", "ru"))
    }

    @Test
    fun `ready description renders model text`() {
        val html = renderer.render(
            data(),
            DescriptionState.Ready(DescriptionResult(short = "Человек у ворот", detailed = "Подробный текст")),
            frameCount = 2,
            language = "ru",
        )

        assertContains(html, "<p>Человек у ворот</p>")
        assertContains(html, "<details><summary>Подробное описание</summary>Подробный текст</details>")
    }

    @Test
    fun `failed description renders fallback in both slots`() {
        val fallback = msg.get("ai.description.fallback.unavailable", "ru")

        val html = renderer.render(data(), DescriptionState.Failed, frameCount = 2, language = "ru")

        assertEquals(2, html.split(fallback).size - 1, "fallback goes into both the paragraph and the details")
    }

    @Test
    fun `oversized detailed text is trimmed to the rich message limit`() {
        val html = renderer.render(
            data(),
            DescriptionState.Ready(DescriptionResult(short = "кратко", detailed = "д".repeat(40_000))),
            frameCount = 2,
            language = "ru",
        )

        assertTrue(html.length <= 32_768, "rendered message must fit the rich message limit, was ${html.length}")
        assertTrue(html.endsWith("</details>"), "trimming must not break the details block")
    }

    @Test
    fun `entity is never split by trimming`() {
        val html = renderer.render(
            data(),
            DescriptionState.Ready(DescriptionResult(short = "кратко", detailed = "<".repeat(40_000))),
            frameCount = 2,
            language = "ru",
        )

        val detailed = html.substringAfter("</summary>").substringBefore("</details>").removeSuffix("…")
        assertFalse(
            Regex("&[a-z]{1,4}$").containsMatchIn(detailed),
            "a trailing half-entity would break Telegram HTML, got tail: ${detailed.takeLast(8)}",
        )
    }

    @Test
    fun `media id is stable and zero based`() {
        assertEquals("f0", RichNotificationRenderer.mediaId(0))
        assertEquals("f9", RichNotificationRenderer.mediaId(9))
    }
}
```

- [ ] **Step 3: Убедиться, что тест не компилируется**

Через `claude-forge:build-runner`: `./gradlew :telegram:compileTestKotlin`

Ожидание: FAILED — `Unresolved reference: RichNotificationRenderer`, `RecordingNotificationData`, `DescriptionState`.

- [ ] **Step 4: Создать модель данных**

`service/model/RecordingNotificationData.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.service.model

/**
 * Метаданные обработанной записи для уведомления. Даты приходят уже отформатированными
 * в зоне и локали получателя — форматирование остаётся в `TelegramNotificationServiceImpl`,
 * который единственный знает про `UserZone`.
 */
data class RecordingNotificationData(
    val camId: String,
    val fileName: String,
    val detectionsCount: Int,
    val analyzedFramesCount: Int,
    val analyzeTimeSeconds: Int,
    val recordTimestamp: String,
    /** "N/A", когда запись ещё не обработана — как и в прежнем текстовом формате, без локализации. */
    val processTimestamp: String,
)
```

`service/model/DescriptionState.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.service.model

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult

/**
 * Состояние AI-описания на момент рендера сообщения. Заменяет прежний признак
 * «`formatter == null` значит описание выключено».
 */
sealed interface DescriptionState {
    /** Описание выключено настройкой или кадров нет — блоков описания в сообщении не будет. */
    data object Absent : DescriptionState

    /** Запрос к модели в полёте — рендерятся плейсхолдеры, которые перепишет правка. */
    data object Pending : DescriptionState

    data class Ready(val result: DescriptionResult) : DescriptionState

    /** Модель не ответила — в оба блока идёт текст fallback. */
    data object Failed : DescriptionState
}
```

- [ ] **Step 5: Реализовать рендерер**

`service/impl/RichNotificationRenderer.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.service.impl

import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.service.model.DescriptionState
import ru.zinin.frigate.analyzer.telegram.service.model.RecordingNotificationData

/**
 * Собирает HTML rich-сообщения уведомления. Единственное место, где верстается уведомление:
 * и первичная отправка, и правка после ответа модели зовут один и тот же [render].
 *
 * Безусловный компонент — сообщение строится всегда, а AI-описание лишь одно из его состояний.
 *
 * **Граница доверия.** Строки из бандла сообщений (заголовок, подписи ячеек, плейсхолдеры, fallback,
 * заголовок раскрывашки) — наша собственная разметка и вставляются как есть: `ai.description.placeholder.short`
 * это `⏳ <i>AI анализирует кадры…</i>`, и экранирование превратило бы курсив в литеральные `&lt;i&gt;`.
 * Всё, что пришло извне — поля [RecordingNotificationData] и оба текста модели, — экранируется.
 */
@Component
class RichNotificationRenderer(
    private val msg: MessageResolver,
) {
    fun render(
        data: RecordingNotificationData,
        description: DescriptionState,
        frameCount: Int,
        language: String,
    ): String {
        val head =
            buildString {
                append("<h2>").append(msg.get(KEY_TITLE, language)).append("</h2>")
                append("<table bordered striped compact>")
                row(KEY_LABEL_CAMERA, data.camId, language)
                row(KEY_LABEL_FILE, data.fileName, language)
                row(KEY_LABEL_DETECTIONS, data.detectionsCount.toString(), language)
                row(KEY_LABEL_FRAMES, data.analyzedFramesCount.toString(), language)
                row(KEY_LABEL_ANALYZE_TIME, data.analyzeTimeSeconds.toString(), language)
                row(KEY_LABEL_RECORD_TS, data.recordTimestamp, language)
                row(KEY_LABEL_PROCESS_TS, data.processTimestamp, language)
                append("</table>")
                append(shortHtml(description, language))
                append(framesHtml(frameCount))
            }

        if (description == DescriptionState.Absent) return head
        val open = "<details><summary>${msg.get(KEY_DETAILS_SUMMARY, language)}</summary>"
        val budget = MAX_LENGTH - head.length - open.length - DETAILS_CLOSE.length
        val detailed =
            when (description) {
                // Тексты бандла — наша собственная разметка, они уходят как есть и заведомо коротки.
                DescriptionState.Pending -> msg.get(KEY_PLACEHOLDER_DETAILED, language)
                DescriptionState.Failed -> msg.get(KEY_FALLBACK, language)
                // Текст модели — чужой ввод: экранируется и режется по бюджету.
                is DescriptionState.Ready -> escapeAndTrim(description.result.detailed, budget)
                DescriptionState.Absent -> return head
            }
        return head + open + detailed + DETAILS_CLOSE
    }

    private fun StringBuilder.row(
        labelKey: String,
        value: String,
        language: String,
    ) {
        append("<tr><td>")
            .append(msg.get(labelKey, language))
            .append("</td><td>")
            .append(escape(value))
            .append("</td></tr>")
    }

    private fun shortHtml(
        description: DescriptionState,
        language: String,
    ): String =
        when (description) {
            DescriptionState.Absent -> ""
            DescriptionState.Pending -> paragraph(msg.get(KEY_PLACEHOLDER_SHORT, language))
            DescriptionState.Failed -> paragraph(msg.get(KEY_FALLBACK, language))
            is DescriptionState.Ready -> paragraph(escape(description.result.short))
        }

    /** Принимает УЖЕ подготовленный HTML: экранирование — забота вызывающего, см. границу доверия. */
    private fun paragraph(inner: String): String = "<p>$inner</p>"

    private fun framesHtml(frameCount: Int): String {
        val count = frameCount.coerceAtMost(MAX_MEDIA)
        return when {
            count == 0 -> ""
            count == 1 -> img(0)
            else -> (0 until count).joinToString(separator = "", prefix = "<tg-collage>", postfix = "</tg-collage>") { img(it) }
        }
    }

    private fun img(index: Int): String = """<img src="tg://photo?id=${mediaId(index)}"/>"""

    /**
     * Экранирование для Telegram HTML. Кавычки не экранируются: значения никогда не попадают
     * в атрибуты — единственные атрибуты, которые мы строим, это наши же `tg://photo?id=fN`.
     */
    private fun escape(s: String): String =
        s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    /**
     * Экранирует и, если не влезает в бюджет, обрезает так, чтобы не разорвать HTML-сущность
     * и не расколоть суррогатную пару.
     */
    private fun escapeAndTrim(
        text: String,
        budget: Int,
    ): String {
        if (budget <= 0) return ""
        val escaped = escape(text)
        if (escaped.length <= budget) return escaped
        var cutoff = budget - 1 // место под многоточие
        val lastAmp = escaped.lastIndexOf('&', startIndex = (cutoff - 1).coerceAtLeast(0))
        if (lastAmp >= 0) {
            val entityEnd = escaped.indexOf(';', startIndex = lastAmp)
            if (entityEnd < 0 || entityEnd >= cutoff) {
                cutoff = lastAmp
            }
        }
        if (cutoff > 0 && escaped[cutoff - 1].isHighSurrogate()) {
            cutoff -= 1
        }
        return escaped.substring(0, cutoff.coerceAtLeast(0)) + "…"
    }

    companion object {
        /** Лимит текста rich-сообщения по документации Bot API. */
        const val MAX_LENGTH = 32_768

        /** Потолок медиа в rich-сообщении. Наш собственный максимум кадров — 10. */
        const val MAX_MEDIA = 50

        /**
         * Идентификатор кадра. Одна и та же строка обязана попасть и в `<img src="tg://photo?id=…">`,
         * и в `InputRichMessageMedia.id`, иначе Telegram отвергнет сообщение.
         */
        fun mediaId(index: Int): String = "f$index"

        private const val DETAILS_CLOSE = "</details>"

        private const val KEY_TITLE = "notification.recording.title"
        private const val KEY_LABEL_CAMERA = "notification.recording.label.camera"
        private const val KEY_LABEL_FILE = "notification.recording.label.file"
        private const val KEY_LABEL_DETECTIONS = "notification.recording.label.detections"
        private const val KEY_LABEL_FRAMES = "notification.recording.label.frames"
        private const val KEY_LABEL_ANALYZE_TIME = "notification.recording.label.analyze.time"
        private const val KEY_LABEL_RECORD_TS = "notification.recording.label.record.timestamp"
        private const val KEY_LABEL_PROCESS_TS = "notification.recording.label.process.timestamp"
        private const val KEY_DETAILS_SUMMARY = "ai.description.details.summary"
        private const val KEY_PLACEHOLDER_SHORT = "ai.description.placeholder.short"
        private const val KEY_PLACEHOLDER_DETAILED = "ai.description.placeholder.detailed"
        private const val KEY_FALLBACK = "ai.description.fallback.unavailable"
    }
}
```

- [ ] **Step 6: Прогнать тесты рендерера**

Через `claude-forge:build-runner`: `./gradlew :telegram:test --tests "*RichNotificationRendererTest"`

Ожидание: PASS, все одиннадцать тестов.

Если падает `renders heading and metadata table` из-за эмодзи в заголовке — проверить, что
`notification.recording.title` берётся как есть и экранируется: эмодзи проходят экранирование без изменений.

- [ ] **Step 7: Коммит**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/model/RecordingNotificationData.kt \
        modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/model/DescriptionState.kt \
        modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/RichNotificationRenderer.kt \
        modules/telegram/src/main/resources/messages_ru.properties \
        modules/telegram/src/main/resources/messages_en.properties \
        modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/RichNotificationRendererTest.kt
git commit -m "feat(telegram): render notification as rich message HTML"
```

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

- [ ] **Step 3: Пометить исследование реализованным**

В шапке `docs/telegram-rich-message-migration.md` заменить строку статуса на:

```markdown
**Статус:** реализовано в `feature/rich-message-notification`; документ сохранён как история
исследования API и живой проверки.
```

- [ ] **Step 4: Коммит**

```bash
git add .claude/rules/telegram.md .claude/rules/ai-description.md docs/telegram-rich-message-migration.md
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
