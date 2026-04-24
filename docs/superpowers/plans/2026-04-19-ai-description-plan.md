# AI Description Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add optional AI-generated short + detailed descriptions of detection frames, delivered via Telegram with placeholder + edit flow and `expandable_blockquote` for the detailed part.

**Architecture:** New `ai-description` module with `DescriptionAgent` abstraction and first implementation via `spring-ai-community/claude-code-sdk` (native Claude Code CLI subprocess). Facade kicks off `describe()` asynchronously; Telegram sender posts placeholder-messages immediately, then edits them when description is ready. All behavior gated by `application.ai.description.enabled`.

**Tech Stack:** Kotlin 2.3.10, Spring Boot 4.0.3, `org.springaicommunity:claude-code-sdk:1.0.0`, Kotlin Coroutines, ktgbotapi 32.0.0, Jackson, MockK, `@TempDir`.

**Spec:** [`docs/superpowers/specs/2026-04-19-ai-description-design.md`](../specs/2026-04-19-ai-description-design.md). Always defer to the spec on unclear requirements.

**Working branch:** `feature/ai-description` (already checked out).

---

## Ground rules for every task

- **Build gate.** At end of every task, before committing, run the build via the `build` skill (dispatches `build-runner`). Do NOT run `./gradlew` directly in this session — project convention in `CLAUDE.md`. On ktlint errors: run `./gradlew ktlintFormat`, then rerun build.
- **Commits are small.** One task = one commit minimum. Use conventional-commit style: `feat(ai-description): ...`, `refactor(core): ...`, etc.
- **Don't modify unrelated files.** If a task introduces a change you didn't plan, stop and re-read the spec.
- **Files live at specific paths.** The Gradle project name includes the `frigate-analyzer-` prefix (e.g. `:frigate-analyzer-ai-description`) per `settings.gradle.kts`.

---

## Phase 1 — Foundation: module scaffold + libraries

### Task 1: Register the new module and SDK dependency

✅ Done — see commit(s): `f6758e6`

---

### Task 2: API contracts — DTOs, interface, exceptions, SPI

✅ Done — see commit(s): `e8a82b6`, `07dde62`

---

### Task 3: Configuration properties

✅ Done — see commit(s): `bc8caea`

---

### Task 3.5: AutoConfiguration — register module beans in Spring context

✅ Done — see commit(s): `2b4d5e3`, `07dde62`

---

### Task 4: ClaudePromptBuilder

✅ Done — see commit(s): `0122663`

---

### Task 5: ClaudeResponseParser

✅ Done — see commit(s): `3f14dce`

---

### Task 6: ClaudeImageStager

✅ Done — see commit(s): `5d07d34`

---

### Task 7: ClaudeAsyncClientFactory

✅ Done — see commit(s): `081ca4e`

---

### Task 8: Exception mapping helper

✅ Done — see commit(s): `e6d8761`, `07dde62`

---

### Task 9: ClaudeDescriptionAgent — skeleton + startup validation

✅ Done — see commit(s): `7c9cbb2`

---

### Task 10: ClaudeDescriptionAgent.describe() — retry, timeout, cleanup

✅ Done — see commit(s): `cbbcdca`, `07dde62`

---


### Task 11: DefaultClaudeInvoker (SDK call adapter)

✅ Done — see commit(s): `bb933f1`, `dc2afc2` (Critical NonCancellable wrap fix on close())

---

### Task 12: ~~Spring wiring — ClaudeAgentConfig~~ (УДАЛЕНА)

Этот task был удалён по результатам внешнего ревью (итерация 1). Причина: design §5 требует `@Component` на `ClaudeDescriptionAgent`/`DefaultClaudeInvoker` с `@ConditionalOnProperty`, но план пытался делать их через `@Bean`-фабрики в `@Configuration`-классе, причём с двойным `@ConditionalOnProperty` (на класс + на каждый бин), что создаёт пустой конфиг-класс при `provider != claude`.

Решение: `ClaudeDescriptionAgent` (Task 9/10) и `DefaultClaudeInvoker` (Task 11) — `@Component` с `@ConditionalOnProperty(enabled=true AND provider=claude)`. Все helpers (`ClaudePromptBuilder`, `ClaudeResponseParser`, `ClaudeImageStager`, `ClaudeAsyncClientFactory`, `ClaudeExceptionMapper`) — `@Component` с `@ConditionalOnProperty(enabled=true)` (переиспользуются будущими провайдерами). `AiDescriptionAutoConfiguration` (Task 3.5) делает `@ComponentScan` этого пакета.

Ничего создавать/коммитить в рамках этого task — пропускаем.

---

## Phase 4 — Core integration

### Task 13: DescriptionScopeConfig + TempFileWriter adapter

✅ Done — see commit(s): `1d91245` (scope + adapter + YAML), `7e9fa69` (Important fix: add `application.ai.description` block to `modules/core/src/test/resources/application.yaml` so `FrigateAnalyzerApplicationTests` Spring context starts; the test YAML shadows main YAML in tests). File renamed `DescriptionScopeConfig.kt → DescriptionCoroutineScope.kt` (ktlint `standard:filename` rule).

---

### Task 14: RecordingProcessingFacade — start describe-job via supplier

✅ Done — see commit(s): `9201107`. Notable: 4 plan bugs corrected during impl (FrameData ctor takes UUID first; RecordingDto has 15 required params not 8; test imports were split across two blocks; `TelegramNotificationServiceImplTest.service` field had to be re-typed to interface so Kotlin defaults resolve in existing 4 call sites). Test deviated from plan: real `FrameVisualizationService` + mocked `LocalVisualizationService` + real `LocalVisualizationProperties` (not `mockk<FrameVisualizationService>()`) — works around mockk's `$default`-bridge NPE on Kotlin default params; semantically neutral because test inputs filter to empty before `LocalVisualizationService` is touched.

---

## Phase 5 — Telegram integration

### Task 15: DescriptionMessageFormatter

✅ Done — see commit(s): `7c60363`. 9 tests passed (TDD red→green). `escapeAndTrim` algorithm tightened vs design draft: `cutoff = budget - 1` produces hard cap of `budget` chars (design suggested `budget` → could exceed by 1); also added `entityEnd < 0` guard for malformed entities. i18n placeholders use raw UTF-8 (`⏳`, `⚠`); existing keys in the same `.properties` files use `\uXXXX` form — both work because `setDefaultEncoding("UTF-8")` is configured. `captionPlaceholderOverhead(lang)` counts raw HTML chars (Telegram `<i>X</i>` counts as 7 toward 1024 limit per "after entities parsing", verified against Bot API docs).

---

### Task 16: TelegramNotificationSender — placeholder + edit flow

**Context:** Это ключевой рефактор. HTML parse mode активируется **только** когда `descriptionHandle != null`. При null — старый flow без изменений.

**Ключевые решения (результат итерации 1 ревью):**
- **`DescriptionEditJobRunner` — `@Component`** с собственным `CoroutineScope` + `@PreDestroy shutdown()` (не inline default-параметр). Паттерн по аналогии с `ExportCoroutineScope`.
- **ktgbotapi v32 корректный API** (проверено по sources библиотеки):
  - Используем suspend-extensions `bot.editMessageText(contentMessage, ...)` / `bot.editMessageCaption(contentMessage, ...)` из `dev.inmo.tgbotapi.extensions.api.edit.*` — совпадает с паттерном в `ExportExecutor.kt`. **НЕ** используем raw-классы `EditChatMessageText/Caption`.
  - `sendTextMessage(..., replyParameters = ReplyParameters(chatId, messageId), ...)` — **нет** `replyToMessageId`.
  - `bot.sendMediaGroup(...)` возвращает **один** `ContentMessage<MediaGroupContent<MediaGroupPartContent>>`, не `List`. Берём `group.messageId` напрямую.
  - `HTMLParseMode` импорт: `dev.inmo.tgbotapi.types.message.HTMLParseMode`.
  - `ContentMessage.messageId` — тип `MessageId` (value class). `MessageIdentifier` — deprecated typealias; не кастуем.
- **HTML-budget truncation** — `toCaption` вызывается на plain text ДО добавления HTML placeholder'а; бюджет уменьшается на `captionPlaceholderOverhead(lang)`.
- **Independent try/catch** — caption-edit и details-edit в `editOne` обёрнуты в отдельные `try/catch`, чтобы failure одного не блокировал второй.
- **MessageIsNotModifiedException** и `MessageToEditNotFoundException` из `dev.inmo.tgbotapi.bot.exceptions` ловятся специфично и логируются как DEBUG (ожидаемое поведение).
- **CancellationException** в каждом `catch (e: Exception)` внутри runner rethrow'ится (structured concurrency).

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/TelegramNotificationSender.kt`
- Create: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/DescriptionEditJobRunner.kt`
- Create: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/DescriptionEditScope.kt` (собственный `@Component` scope для runner'а)
- Modify: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/queue/TelegramNotificationSenderTest.kt`

- [ ] **Step 1: Write failing tests (add to existing file)**

Append these test cases to `TelegramNotificationSenderTest.kt`:

```kotlin
    // NB: тесты sender'а проверяют WIRING (что метод вызван с правильными аргументами).
    // Финальная сборка ktgbotapi вызовов тестируется в интеграционном тесте.
    // Мокаем suspend-extensions через MockKStatic — существующий TelegramNotificationSenderTest
    // уже использует этот паттерн, продолжаем его же.

    @Test
    fun `disabled path with null descriptionHandle preserves current single-photo behavior`() =
        runTest {
            val frames =
                listOf(
                    VisualizedFrameData(frameIndex = 0, visualizedBytes = ByteArray(1), detectionsCount = 1),
                )
            coEvery { bot.execute<ContentMessage<PhotoContent>>(any()) } returns mockk(relaxed = true)

            sender.send(createTask(frames = frames))

            // Ни один edit вызов не случился — потому что descriptionHandle=null.
            coVerify(exactly = 0) { anyConstructed<EditTarget>() }
        }

    @Test
    fun `single photo with description handle sends placeholder then edits on success`() =
        runTest {
            val frames =
                listOf(
                    VisualizedFrameData(frameIndex = 0, visualizedBytes = ByteArray(1), detectionsCount = 1),
                )
            val handle = CompletableDeferred<Result<DescriptionResult>>()
            handle.complete(Result.success(DescriptionResult("two cars", "two cars approaching gate")))

            val photoMsg = mockk<ContentMessage<PhotoContent>>()
            every { photoMsg.messageId } returns MessageId(42L)
            val textMsg = mockk<ContentMessage<TextContent>>()
            every { textMsg.messageId } returns MessageId(43L)

            coEvery { bot.execute<ContentMessage<PhotoContent>>(any()) } returns photoMsg
            coEvery {
                bot.sendTextMessage(
                    chatId = any(),
                    text = any<String>(),
                    parseMode = any(),
                    linkPreviewOptions = any(),
                    threadId = any(),
                    directMessageThreadId = any(),
                    businessConnectionId = any(),
                    disableNotification = any(),
                    protectContent = any(),
                    allowPaidBroadcast = any(),
                    effectId = any(),
                    suggestedPostParameters = any(),
                    replyParameters = any(),
                    replyMarkup = any(),
                )
            } returns textMsg

            // editMessageCaption / editMessageText — suspend extensions; мокаем аналогично как в ExportExecutorTest.
            coEvery {
                bot.editMessageCaption(
                    chatId = any(), messageId = any<MessageId>(),
                    text = any<String>(), parseMode = any(), replyMarkup = any(),
                )
            } returns mockk(relaxed = true)
            coEvery {
                bot.editMessageText(
                    chatId = any(), messageId = any<MessageId>(),
                    text = any<String>(), parseMode = any(), replyMarkup = any(),
                )
            } returns mockk(relaxed = true)

            sender.send(createTask(frames = frames, descriptionHandle = handle))
            runner.getEditJob()?.join()   // тестовый DescriptionEditJobRunner возвращает последний job

            coVerify { bot.editMessageCaption(any(), any<MessageId>(), any<String>(), any(), any()) }
            coVerify { bot.editMessageText(any(), any<MessageId>(), any<String>(), any(), any()) }
        }

    @Test
    fun `single photo with description handle uses fallback on failure`() =
        runTest {
            val frames = listOf(VisualizedFrameData(0, ByteArray(1), 1))
            val handle = CompletableDeferred<Result<DescriptionResult>>()
            handle.complete(Result.failure(RuntimeException("boom")))

            val photoMsg = mockk<ContentMessage<PhotoContent>> {
                every { messageId } returns MessageId(42L)
            }
            val textMsg = mockk<ContentMessage<TextContent>> {
                every { messageId } returns MessageId(43L)
            }
            coEvery { bot.execute<ContentMessage<PhotoContent>>(any()) } returns photoMsg
            coEvery {
                bot.sendTextMessage(any(), any<String>(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns textMsg

            val captionTextSlot = slot<String>()
            val detailsTextSlot = slot<String>()
            coEvery {
                bot.editMessageCaption(any(), any<MessageId>(), capture(captionTextSlot), any(), any())
            } returns mockk(relaxed = true)
            coEvery {
                bot.editMessageText(any(), any<MessageId>(), capture(detailsTextSlot), any(), any())
            } returns mockk(relaxed = true)

            sender.send(createTask(frames = frames, descriptionHandle = handle))
            runner.getEditJob()?.join()

            assertTrue(captionTextSlot.captured.contains("unavailable", ignoreCase = true))
            assertTrue(detailsTextSlot.captured.contains("unavailable", ignoreCase = true))
        }

    @Test
    fun `media group with description handle sends albums and single edit on success`() =
        runTest {
            val frames = (0..2).map { VisualizedFrameData(it, ByteArray(1), 1) }
            val handle = CompletableDeferred<Result<DescriptionResult>>()
            handle.complete(Result.success(DescriptionResult("two cars", "two cars approaching gate")))

            // sendMediaGroup возвращает ОДИН ContentMessage<MediaGroupContent<...>> (value class messageId).
            val groupMsg = mockk<ContentMessage<MediaGroupContent<MediaGroupPartContent>>> {
                every { messageId } returns MessageId(50L)
            }
            coEvery { bot.sendMediaGroup(any(), any<List<TelegramMediaPhoto>>()) } returns groupMsg

            val textMsg = mockk<ContentMessage<TextContent>> {
                every { messageId } returns MessageId(51L)
            }
            coEvery {
                bot.sendTextMessage(any(), any<String>(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns textMsg
            coEvery {
                bot.editMessageText(any(), any<MessageId>(), any<String>(), any(), any())
            } returns mockk(relaxed = true)

            sender.send(createTask(frames = frames, descriptionHandle = handle))
            runner.getEditJob()?.join()

            // Для media group только editMessageText; editMessageCaption НЕ вызывается.
            coVerify(exactly = 1) { bot.editMessageText(any(), any<MessageId>(), any<String>(), any(), any()) }
            coVerify(exactly = 0) { bot.editMessageCaption(any(), any<MessageId>(), any<String>(), any(), any()) }
        }
```

Обнови `createTask` и сетап:

```kotlin
    // Instead of passing raw runner instance, используем ObjectProvider — как в production.
    private val formatterProvider = mockk<ObjectProvider<DescriptionMessageFormatter>>()
    private val runnerProvider = mockk<ObjectProvider<DescriptionEditJobRunner>>()
    private val formatter = mockk<DescriptionMessageFormatter>(relaxed = true)

    // helper-runner для тестов — выставляет синхронный scope и отдаёт Job через getEditJob()
    private val runner = TestDescriptionEditJobRunner(bot, formatter)

    init {
        every { formatterProvider.getIfAvailable() } returns formatter
        every { runnerProvider.getIfAvailable() } returns runner
        every { formatter.captionPlaceholderOverhead(any()) } returns 0
        // Никаких захардкоженных возвратов для captionSuccess/Fallback/etc — пусть реальный htmlEscape работает:
        // but relaxed mock is OK since тесты не проверяют экранирование (это отдельный formatter-test).
    }

    private fun createTask(
        frames: List<VisualizedFrameData> = emptyList(),
        descriptionHandle: Deferred<Result<DescriptionResult>>? = null,
    ) = NotificationTask(
        id = UUID.randomUUID(),
        chatId = 12345L,
        message = "Test notification",
        visualizedFrames = frames,
        recordingId = recordingId,
        language = "ru",
        descriptionHandle = descriptionHandle,
    )
```

Add imports at the top of the test file:

```kotlin
import dev.inmo.tgbotapi.extensions.api.edit.caption.editMessageCaption
import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
import dev.inmo.tgbotapi.extensions.api.send.media.sendMediaGroup
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.types.MessageId
import dev.inmo.tgbotapi.types.ReplyParameters
import dev.inmo.tgbotapi.types.media.MediaGroupContent
import dev.inmo.tgbotapi.types.media.MediaGroupPartContent
import dev.inmo.tgbotapi.types.message.HTMLParseMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.test.runTest
import org.springframework.beans.factory.ObjectProvider
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.telegram.service.impl.DescriptionMessageFormatter
```

**Реализация `TestDescriptionEditJobRunner`** (положить в тот же test-файл, перед классом `TelegramNotificationSenderTest`, либо в отдельный helper):

```kotlin
/**
 * Test double для DescriptionEditJobRunner. Использует UnconfinedTestDispatcher —
 * корутины исполняются сразу, `getEditJob()?.join()` возвращается моментально.
 * Паттерн скопирован с существующего TestExportExecutor (см. ExportExecutorTest.kt).
 */
private class TestDescriptionEditJobRunner(
    bot: TelegramBot,
    formatter: DescriptionMessageFormatter,
) : DescriptionEditJobRunner(
    bot = bot,
    formatter = formatter,
    scope = DescriptionEditScope.forTest(CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())),
) {
    @Volatile
    private var lastJob: Job? = null

    override fun launchEditJob(
        targets: List<EditTarget>,
        handleOutcome: suspend () -> Result<DescriptionResult>,
    ): Job {
        val job = super.launchEditJob(targets, handleOutcome)
        lastJob = job
        return job
    }

    fun getEditJob(): Job? = lastJob
}
```

Для поддержки test-scope в `DescriptionEditScope` добавлен фабричный метод `companion object { fun forTest(scope: CoroutineScope) = DescriptionEditScope(scope) }` — см. Step 3a. Альтернатива — сделать `DescriptionEditScope` `open` и подкласс с override-нутым `delegate`.

- [ ] **Step 2: Run test — expect failures**

Dispatch `build-runner` with `./gradlew :frigate-analyzer-telegram:test --tests TelegramNotificationSenderTest`.
Expected: FAIL — new cases missing implementation.

- [ ] **Step 3a: Create DescriptionEditScope**

Create `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/DescriptionEditScope.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.queue

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * Managed scope для edit-job'ов описаний в Telegram-слое. Аналог ExportCoroutineScope.
 *
 * Условен на enabled=true (без описаний edit-job'ы не запускаются вообще).
 * Отделён от DescriptionCoroutineScope (describe живёт в core, edit — в telegram).
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
open class DescriptionEditScope internal constructor(
    delegate: CoroutineScope,
) : CoroutineScope by delegate {
    constructor() : this(CoroutineScope(Dispatchers.IO + SupervisorJob()))

    @PreDestroy
    open fun shutdown() {
        val job = coroutineContext[Job] ?: return
        runBlocking {
            try {
                withTimeout(SHUTDOWN_TIMEOUT_MS) { job.cancelAndJoin() }
            } catch (_: TimeoutCancellationException) {
                logger.warn {
                    "Description edit coroutines did not finish within ${SHUTDOWN_TIMEOUT_MS}ms; forcing shutdown"
                }
            }
        }
    }

    companion object {
        const val SHUTDOWN_TIMEOUT_MS = 10_000L
    }
}
```

- [ ] **Step 3b: Create DescriptionEditJobRunner**

Create `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/DescriptionEditJobRunner.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.queue

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.bot.exceptions.MessageIsNotModifiedException
import dev.inmo.tgbotapi.bot.exceptions.MessageToEditNotFoundException
import dev.inmo.tgbotapi.extensions.api.edit.caption.editMessageCaption
import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
import dev.inmo.tgbotapi.types.ChatIdentifier
import dev.inmo.tgbotapi.types.MessageId
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.message.HTMLParseMode
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.telegram.service.impl.DescriptionMessageFormatter

private val logger = KotlinLogging.logger {}

data class EditTarget(
    val chatId: ChatIdentifier,
    val captionMessageId: MessageId?,
    val detailsMessageId: MessageId,
    val baseText: String,               // raw text — formatter сам сделает escape+trim
    val captionBudget: Int,             // budget под итоговый HTML-caption (1024 − short overhead)
    val exportKeyboard: InlineKeyboardMarkup,
    val language: String,
    val isMediaGroup: Boolean,
)

@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class DescriptionEditJobRunner(
    private val bot: TelegramBot,
    private val formatter: DescriptionMessageFormatter,
    private val scope: DescriptionEditScope,
) {
    fun launchEditJob(
        targets: List<EditTarget>,
        handleOutcome: suspend () -> Result<DescriptionResult>,
    ): Job =
        scope.launch {
            val outcome = handleOutcome()
            targets.forEach { target ->
                editOne(target, outcome)
            }
        }

    private suspend fun editOne(
        target: EditTarget,
        outcome: Result<DescriptionResult>,
    ) {
        if (target.isMediaGroup) {
            editMediaGroup(target, outcome)
        } else {
            // Два независимых try/catch: если caption edit упал, details всё равно обновится.
            editSinglePhotoCaption(target, outcome)
            editSinglePhotoDetails(target, outcome)
        }
    }

    private suspend fun editMediaGroup(target: EditTarget, outcome: Result<DescriptionResult>) {
        // Для media group: caption в фото не трогаем, только reply-text. Бюджет здесь не caption-лимит (1024),
        // а Telegram text-message лимит (4096) — short+expandable детально умещаются. Используем captionBudget
        // как прокси "разумного размера" для верхнего куска, expandable идёт следом без сжатия.
        val newText = outcome.fold(
            onSuccess = { result ->
                val short = formatter.captionSuccess(target.baseText, result, target.language, target.captionBudget)
                "$short\n\n${formatter.expandableBlockquoteSuccess(result, target.language)}"
            },
            onFailure = {
                val short = formatter.captionFallback(target.baseText, target.language, target.captionBudget)
                "$short\n\n${formatter.expandableBlockquoteFallback(target.language)}"
            },
        )
        runEdit("media group details", target) {
            bot.editMessageText(
                chatId = target.chatId,
                messageId = target.detailsMessageId,
                text = newText,
                parseMode = HTMLParseMode,
                replyMarkup = target.exportKeyboard,
            )
        }
    }

    private suspend fun editSinglePhotoCaption(target: EditTarget, outcome: Result<DescriptionResult>) {
        val captionText = outcome.fold(
            onSuccess = { formatter.captionSuccess(target.baseText, it, target.language, target.captionBudget) },
            onFailure = { formatter.captionFallback(target.baseText, target.language, target.captionBudget) },
        )
        runEdit("single-photo caption", target) {
            bot.editMessageCaption(
                chatId = target.chatId,
                messageId = target.captionMessageId!!,
                text = captionText,
                parseMode = HTMLParseMode,
                replyMarkup = target.exportKeyboard,
            )
        }
    }

    private suspend fun editSinglePhotoDetails(target: EditTarget, outcome: Result<DescriptionResult>) {
        val detailsText = outcome.fold(
            onSuccess = { formatter.expandableBlockquoteSuccess(it, target.language) },
            onFailure = { formatter.expandableBlockquoteFallback(target.language) },
        )
        runEdit("single-photo details", target) {
            bot.editMessageText(
                chatId = target.chatId,
                messageId = target.detailsMessageId,
                text = detailsText,
                parseMode = HTMLParseMode,
            )
        }
    }

    private suspend fun runEdit(
        label: String,
        target: EditTarget,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e  // structured concurrency — не глотаем отмену
        } catch (e: MessageIsNotModifiedException) {
            logger.debug { "Edit skipped for $label (chat=${target.chatId}): message is not modified" }
        } catch (e: MessageToEditNotFoundException) {
            logger.debug { "Edit skipped for $label (chat=${target.chatId}): message not found" }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to edit $label for chat=${target.chatId}; continuing" }
        }
    }
}
```

- [ ] **Step 4: Update TelegramNotificationSender**

Replace the full content of `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/TelegramNotificationSender.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.queue

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.send.media.sendMediaGroup
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.requests.abstracts.asMultipartFile
import dev.inmo.tgbotapi.requests.send.media.SendPhoto
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.MessageId
import dev.inmo.tgbotapi.types.RawChatId
import dev.inmo.tgbotapi.types.ReplyParameters
import dev.inmo.tgbotapi.types.media.TelegramMediaPhoto
import dev.inmo.tgbotapi.types.message.HTMLParseMode
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Deferred
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.telegram.bot.handler.quickexport.QuickExportHandler
import ru.zinin.frigate.analyzer.telegram.helper.RetryHelper
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.queue.DescriptionEditJobRunner
import ru.zinin.frigate.analyzer.telegram.service.impl.DescriptionMessageFormatter

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class TelegramNotificationSender(
    private val bot: TelegramBot,
    private val quickExportHandler: QuickExportHandler,
    private val msg: MessageResolver,
    // При enabled=false у description beanов нет — используем ObjectProvider чтобы sender
    // компилировался и работал без описаний.
    private val descriptionFormatter: ObjectProvider<DescriptionMessageFormatter>,
    private val editJobRunner: ObjectProvider<DescriptionEditJobRunner>,
) {
    suspend fun send(task: NotificationTask) {
        val chatIdObj = ChatId(RawChatId(task.chatId))
        val frames = task.visualizedFrames
        val lang = task.language ?: "en"
        val exportKeyboard = quickExportHandler.createExportKeyboard(task.recordingId, lang)
        val formatter = descriptionFormatter.getIfAvailable()
        val withDescription = task.descriptionHandle != null && formatter != null

        // HTML-budget: formatter'у даём raw text + budget; он сам сделает HTML-aware escape+truncate
        // (см. DescriptionMessageFormatter.escapeAndTrim). Это гарантирует что финальный HTML-caption
        // укладывается в 1024 символа даже при наличии &<> в camId/filePath.
        val parseMode = if (withDescription) HTMLParseMode else null
        val captionInitial =
            if (withDescription) formatter!!.captionInitialPlaceholder(task.message, lang, MAX_CAPTION_LENGTH)
            else task.message.toCaption(MAX_CAPTION_LENGTH)

        // Для edit case: передаём raw text, formatter сам ужмёт под оставшийся бюджет после short-хвоста.
        // SHORT_MAX_LENGTH — pessimistic worst-case (@Max(500) из CommonSection).
        val editBaseBudget = if (withDescription) MAX_CAPTION_LENGTH - SHORT_MAX_LENGTH - "\n\n".length else MAX_CAPTION_LENGTH

        val targets = mutableListOf<EditTarget>()

        when {
            frames.isEmpty() -> {
                // Нет кадров — не прикрепляем placeholder/edit (некуда reply'ить).
                RetryHelper.retryIndefinitely("Send text message", task.chatId) {
                    bot.sendTextMessage(
                        chatId = chatIdObj,
                        text = captionInitial,
                        parseMode = parseMode,
                        replyMarkup = exportKeyboard,
                    )
                }
            }

            frames.size == 1 -> {
                val frame = frames.first()
                val photoMsg =
                    RetryHelper.retryIndefinitely("Send photo message", task.chatId) {
                        bot.execute(
                            SendPhoto(
                                chatId = chatIdObj,
                                photo = frame.visualizedBytes.asMultipartFile("frame_${frame.frameIndex}.jpg"),
                                text = captionInitial,
                                parseMode = parseMode,
                                replyMarkup = exportKeyboard,
                            ),
                        )
                    }
                if (withDescription) {
                    val detailsMsg =
                        RetryHelper.retryIndefinitely("Send details placeholder", task.chatId) {
                            bot.sendTextMessage(
                                chatId = chatIdObj,
                                text = formatter!!.placeholderDetailedExpandable(lang),
                                parseMode = HTMLParseMode,
                                replyParameters = ReplyParameters(chatIdObj, photoMsg.messageId),
                            )
                        }
                    targets.add(
                        EditTarget(
                            chatId = chatIdObj,
                            captionMessageId = photoMsg.messageId,
                            detailsMessageId = detailsMsg.messageId,
                            baseText = task.message,
                            captionBudget = editBaseBudget,
                            exportKeyboard = exportKeyboard,
                            language = lang,
                            isMediaGroup = false,
                        ),
                    )
                }
            }

            else -> {
                var firstAlbumMessageId: MessageId? = null
                frames.chunked(MAX_MEDIA_GROUP_SIZE).forEachIndexed { chunkIndex, chunk ->
                    val group =
                        RetryHelper.retryIndefinitely("Send media group", task.chatId) {
                            val media =
                                chunk.mapIndexed { idx, frame ->
                                    TelegramMediaPhoto(
                                        file = frame.visualizedBytes.asMultipartFile("frame_${frame.frameIndex}.jpg"),
                                        // first frame carries caption; formatter сам escape'ит если withDescription
                                        text =
                                            if (chunkIndex == 0 && idx == 0) {
                                                if (withDescription) {
                                                    formatter!!.captionInitialPlaceholder(task.message, lang, MAX_CAPTION_LENGTH)
                                                } else {
                                                    task.message.toCaption(MAX_CAPTION_LENGTH)
                                                }
                                            } else null,
                                    )
                                }
                            @Suppress("OPT_IN_USAGE")
                            bot.sendMediaGroup(chatIdObj, media)
                        }
                    if (chunkIndex == 0) {
                        // sendMediaGroup возвращает ContentMessage (не List) — messageId это id первого сообщения альбома.
                        firstAlbumMessageId = group.messageId
                    }
                }
                val albumBaseText = msg.get("notification.recording.export.prompt", lang)
                val promptInitial =
                    if (withDescription) {
                        albumBaseText +
                            "\n\n" +
                            formatter!!.placeholderShort(lang) +
                            "\n\n" +
                            formatter.placeholderDetailedExpandable(lang)
                    } else {
                        albumBaseText
                    }
                val detailsMsg =
                    RetryHelper.retryIndefinitely("Send export button", task.chatId) {
                        bot.sendTextMessage(
                            chatId = chatIdObj,
                            text = promptInitial,
                            parseMode = if (withDescription) HTMLParseMode else null,
                            replyParameters = firstAlbumMessageId?.let { ReplyParameters(chatIdObj, it) },
                            replyMarkup = exportKeyboard,
                        )
                    }
                if (withDescription) {
                    targets.add(
                        EditTarget(
                            chatId = chatIdObj,
                            captionMessageId = null,
                            detailsMessageId = detailsMsg.messageId,
                            baseText = albumBaseText,
                            captionBudget = editBaseBudget,
                            exportKeyboard = exportKeyboard,
                            language = lang,
                            isMediaGroup = true,
                        ),
                    )
                }
            }
        }

        if (withDescription && targets.isNotEmpty()) {
            val runner = editJobRunner.getIfAvailable()
            if (runner != null) {
                runner.launchEditJob(targets) { task.descriptionHandle!!.await() }
            }
        }
    }

    companion object {
        private const val MAX_MEDIA_GROUP_SIZE = 10
        private const val MAX_CAPTION_LENGTH = 1024
        // Pessimistic worst-case: @Max(500) из DescriptionProperties.CommonSection.
        // Используем upper bound для edit budget — гарантирует 1024 даже при shortMaxLength=500.
        // При реальном дефолте 200 caption space останется на ~300 символов недоиспользованным —
        // приемлемый компромисс между простотой (без проброса реального значения в sender) и безопасностью.
        private const val SHORT_MAX_LENGTH = 500
    }

    private fun String.toCaption(maxLength: Int): String {
        // Plain-text truncation (без HTML parseMode). Для HTML-сценариев используется
        // DescriptionMessageFormatter.escapeAndTrim внутри formatter-методов.
        if (length <= maxLength) return this
        logger.warn { "Truncating caption from $length to $maxLength characters to satisfy Telegram limits" }
        return substring(0, maxLength)
    }
}
```

**Критические изменения vs исходного плана:**
- `descriptionFormatter` и `editJobRunner` — `ObjectProvider` вместо прямого инжекта, т.к. при `enabled=false` их бинов нет, но sender существует (условен только на `telegram.enabled`).
- `MessageIdentifier` → `MessageId` (cast `as MessageIdentifier` удалён — typealias deprecated).
- `HTMLParseMode` импорт из `dev.inmo.tgbotapi.types.message`.
- `replyToMessageId` → `replyParameters = ReplyParameters(chatId, msgId)` — ktgbotapi v32 API.
- `sendMediaGroup(...)` возвращает **один** `ContentMessage<MediaGroupContent<...>>`, `group.messageId` — id первого сообщения альбома. `.firstOrNull()?.messageId` убрано.
- HTML-aware truncation перенесён в `DescriptionMessageFormatter.escapeAndTrim` (escape-first, не разрывает entity). Sender передаёт raw baseText + budget — formatter сам escape'ит и трим'ит.
- `EditTarget.baseCaption` → `baseText` + `captionBudget` — передаём сырой текст с бюджетом, а не pre-trimmed.
- Импорты `kotlinx.coroutines.Deferred`, `ObjectProvider`, `DescriptionResult`, `DescriptionEditJobRunner` добавлены в шапку файла.

- [ ] **Step 5: Run test to verify it passes**

Dispatch `build-runner` with `./gradlew :frigate-analyzer-telegram:test --tests TelegramNotificationSenderTest`.
Expected: PASS (all existing + 4 new).

- [ ] **Step 6: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/DescriptionEditJobRunner.kt modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/TelegramNotificationSender.kt modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/queue/TelegramNotificationSenderTest.kt
git commit -m "feat(telegram): add placeholder+edit flow for AI descriptions in notification sender"
```

---

## Phase 6 — ~~YAML configuration~~ (ПЕРЕНЕСЕНО В TASK 13)

### Task 17: ~~application.yaml — AI description section~~ (УДАЛЕНА)

YAML-секция добавляется в Task 13 Step 4.5 (перенесено по результатам итерации 1 ревью — иначе между Task 13 и Task 17 приложение не могло стартовать из-за отсутствия default-значений).

---

## Phase 7 — Docker

### Task 18: Dockerfile — native Claude CLI install

**Files:**
- Modify: `docker/deploy/Dockerfile`

- [ ] **Step 1: Add Claude CLI installation**

Edit `docker/deploy/Dockerfile`. **Не добавляй отдельный `RUN apk add`** — существующую `RUN apk add --no-cache ffmpeg curl fontconfig ttf-dejavu` нужно расширить **в той же строке**, чтобы не дублировать apk-слой:

```dockerfile
# Runtime deps (ffmpeg/fontconfig existing + bash/libgcc/libstdc++/ripgrep for Claude CLI per Anthropic docs)
RUN apk add --no-cache ffmpeg curl fontconfig ttf-dejavu bash libgcc libstdc++ ripgrep
```

Затем, после `USER appuser` line near the end, add (before `EXPOSE 8080`):

```dockerfile
# Install Claude Code CLI under appuser (~/.local/bin)
RUN curl -fsSL https://claude.ai/install.sh | bash
# Disable built-in ripgrep on musl + disable auto-updater (deterministic image)
RUN mkdir -p /home/appuser/.claude \
 && printf '%s\n' '{' \
                  '  "env": {' \
                  '    "USE_BUILTIN_RIPGREP": "0",' \
                  '    "DISABLE_AUTOUPDATER": "1"' \
                  '  }' \
                  '}' > /home/appuser/.claude/settings.json
```

Then, still as root, insert **before** the `USER appuser` line a `USER root` block (if not already root) so the subsequent `USER appuser` + native install runs under appuser context. The final ordering should be:

1. `RUN apk add --no-cache ffmpeg curl fontconfig ttf-dejavu bash libgcc libstdc++ ripgrep`  (as root — существующий, расширенный)
2. (existing) setup of directories, group, user
3. existing COPY --from=builder lines
4. existing AOT cache build (as root) — keep
5. existing `COPY docker/deploy/docker-entrypoint.sh` + `chmod +x` + `chown`
6. `USER appuser`
7. NEW: `RUN curl -fsSL https://claude.ai/install.sh | bash`
8. NEW: `RUN mkdir -p /home/appuser/.claude && printf ...`
9. `USER root` + `ENV PATH="/home/appuser/.local/bin:${PATH}"` + `USER appuser`
10. `EXPOSE 8080`
11. `ENTRYPOINT ...`

Concrete final Dockerfile block for reference — **copy exactly** over the current runtime-stage below the FROM line:

```dockerfile
FROM azul/zulu-openjdk-alpine:25
WORKDIR /application

RUN apk add --no-cache ffmpeg curl fontconfig ttf-dejavu bash libgcc libstdc++ ripgrep

RUN mkdir -p /tmp/frigate-analyzer /application/logs /application/config && \
    addgroup -g 1000 appgroup && \
    adduser -u 1000 -G appgroup -D appuser && \
    chown -R appuser:appgroup /application /tmp/frigate-analyzer

COPY --from=builder /builder/extracted/dependencies/ ./
COPY --from=builder /builder/extracted/spring-boot-loader/ ./
COPY --from=builder /builder/extracted/snapshot-dependencies/ ./
COPY --from=builder /builder/extracted/application/ ./

RUN java --enable-native-access=ALL-UNNAMED \
         -XX:AOTCacheOutput=application.aot \
         -Dspring.context.exit=onRefresh \
         -jar application.jar || true

COPY docker/deploy/docker-entrypoint.sh /application/docker-entrypoint.sh
RUN chmod +x /application/docker-entrypoint.sh && \
    chown -R appuser:appgroup /application

USER appuser
RUN curl -fsSL https://claude.ai/install.sh | bash && \
    mkdir -p /home/appuser/.claude && \
    printf '%s\n' '{' \
                  '  "env": {' \
                  '    "USE_BUILTIN_RIPGREP": "0",' \
                  '    "DISABLE_AUTOUPDATER": "1"' \
                  '  }' \
                  '}' > /home/appuser/.claude/settings.json

USER root
ENV PATH="/home/appuser/.local/bin:${PATH}"
USER appuser

EXPOSE 8080

ENTRYPOINT ["/application/docker-entrypoint.sh"]
```

- [ ] **Step 2: Build the image locally (manual verification)**

Run from the repo root:

```bash
./gradlew :frigate-analyzer-core:bootJar
docker build -f docker/deploy/Dockerfile -t frigate-analyzer:ai-test .
# --entrypoint обязательно — образ имеет ENTRYPOINT=docker-entrypoint.sh,
# просто аргументы "claude --version" уйдут в Java-приложение и не проверят CLI.
docker run --rm --entrypoint claude frigate-analyzer:ai-test --version
```

Expected: version string prints, no errors.

If `claude --version` fails with missing shared libraries, re-read Anthropic Alpine docs and adjust `apk add` list.

- [ ] **Step 3: Commit**

```bash
git add docker/deploy/Dockerfile
git commit -m "chore(docker): install Claude Code CLI natively in image"
```

---

### Task 19: docker-entrypoint.sh — warn block

**Files:**
- Modify: `docker/deploy/docker-entrypoint.sh`

- [ ] **Step 1: Add WARN block**

Replace the content of `docker/deploy/docker-entrypoint.sh` with:

```bash
#!/bin/sh
set -- --enable-native-access=ALL-UNNAMED -XX:MaxRAMPercentage=75.0 -XX:AOTCache=application.aot

if [ -f /application/config/log4j2.yaml ]; then
  echo "Using external log4j2 config: /application/config/log4j2.yaml"
  set -- "$@" -Dlogging.config=/application/config/log4j2.yaml
else
  echo "Using built-in log4j2 config (console only)"
fi

if [ "${APP_AI_DESCRIPTION_ENABLED:-false}" = "true" ]; then
    if [ -z "${CLAUDE_CODE_OAUTH_TOKEN:-}" ]; then
        echo "WARN: APP_AI_DESCRIPTION_ENABLED=true but CLAUDE_CODE_OAUTH_TOKEN is empty; AI descriptions will return fallback." >&2
    elif [ -n "${CLAUDE_CLI_PATH:-}" ]; then
        # Explicit path: проверяем его, а не PATH — иначе WARN будет ложным при кастомном location.
        if [ -x "${CLAUDE_CLI_PATH}" ]; then
            echo "INFO: claude CLI detected at ${CLAUDE_CLI_PATH}: $(${CLAUDE_CLI_PATH} --version 2>/dev/null || echo 'unknown')"
        else
            echo "WARN: explicit CLAUDE_CLI_PATH=${CLAUDE_CLI_PATH} not found or not executable; AI descriptions will return fallback." >&2
        fi
    elif ! command -v claude >/dev/null 2>&1; then
        echo "WARN: claude CLI not found in PATH (CLAUDE_CLI_PATH is empty); AI descriptions will return fallback." >&2
    else
        echo "INFO: claude CLI detected: $(claude --version 2>/dev/null || echo 'unknown')"
    fi
fi

exec java "$@" -jar application.jar
```

- [ ] **Step 2: Commit**

```bash
git add docker/deploy/docker-entrypoint.sh
git commit -m "chore(docker): warn on misconfigured AI description in entrypoint"
```

---

### Task 20: .env.example update

**Files:**
- Modify: `docker/deploy/.env.example`

- [ ] **Step 1: Append AI description section**

Append to `docker/deploy/.env.example`:

```bash

# --- AI descriptions (optional) ---
# Enables Claude-generated short + detailed descriptions of detection frames.
# APP_AI_DESCRIPTION_ENABLED=true
# APP_AI_DESCRIPTION_PROVIDER=claude
# APP_AI_DESCRIPTION_LANGUAGE=en             # ru | en (validated at startup via @Pattern)
# APP_AI_DESCRIPTION_SHORT_MAX=200
# APP_AI_DESCRIPTION_DETAILED_MAX=1500
# APP_AI_DESCRIPTION_MAX_FRAMES=10           # top-N frames by frameIndex sent to Claude
# APP_AI_DESCRIPTION_QUEUE_TIMEOUT=30s       # waiting for a semaphore permit
# APP_AI_DESCRIPTION_TIMEOUT=60s             # actual describe + retry
# APP_AI_DESCRIPTION_MAX_CONCURRENT=2

# --- Claude-specific (when provider=claude) ---
# Obtain the token ONCE on the host: `claude setup-token`,
# copy the value here. Long-lived OAuth token (works against your Claude subscription).
# CLAUDE_CODE_OAUTH_TOKEN=
# CLAUDE_MODEL=opus                          # opus | sonnet | haiku (alias)
# CLAUDE_CLI_PATH=                           # empty = SDK resolves via `which claude`
# CLAUDE_WORKING_DIR=                        # empty = uses application.temp-folder (required for SDK)

# --- Optional proxy for Claude API calls ---
# CLAUDE_HTTP_PROXY=http://proxy:8080
# CLAUDE_HTTPS_PROXY=http://proxy:8080
# CLAUDE_NO_PROXY=localhost,127.0.0.1
```

- [ ] **Step 2: Commit**

```bash
git add docker/deploy/.env.example
git commit -m "docs(docker): document AI description env variables in .env.example"
```

---

## Phase 8 — Final build + code review

### Task 21: Full project build + ktlint

**Files:** none

- [ ] **Step 1: Run ktlint format**

Dispatch `build-runner` with `./gradlew ktlintFormat`.
Expected: BUILD SUCCESSFUL. Any formatting fixes auto-applied.

- [ ] **Step 2: Run full build with tests**

Dispatch `build-runner` with `./gradlew build`.
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 3: If any files changed during ktlintFormat, commit**

```bash
git status --short
# If there are unstaged changes from ktlint:
git add -u
git commit -m "style: apply ktlint formatting"
```

---

### Task 22: Superpowers code-reviewer pass

**Files:** none (review-only)

- [ ] **Step 1: Run superpowers code-reviewer**

In the current session, dispatch `Agent` with `subagent_type: superpowers:code-reviewer` and this prompt:

> Review the ai-description feature implementation on branch `feature/ai-description`. The design doc is at `docs/superpowers/specs/2026-04-19-ai-description-design.md`. Compare the implementation to the spec. Focus on:
>
> 1. Does the code match the 8 sections of the spec (modules, config, API, data flow, errors, Docker, tests, changes)?
> 2. Any places where `// TODO`, placeholder text, or unfinished code slipped in?
> 3. Any thread-safety / coroutine lifecycle issues around `descriptionScope`, `DescriptionEditJobRunner.scope`, semaphore, or `Deferred<Result<…>>.await()`?
> 4. Any Telegram edit-path regression risk (`message is not modified`, lost messageId, absence of `parseMode` when HTML is used)?
> 5. Test coverage: are the four sender scenarios present (single-success/fail, group-success, disabled-regression)? Facade 3 scenarios?
>
> Report findings in priority order.

- [ ] **Step 2: Fix any critical issues reported**

For each critical finding from the reviewer: create a commit fixing it. Minor nits can be addressed in a separate pass or noted for a follow-up PR.

- [ ] **Step 3: Re-run build**

Dispatch `build-runner` with `./gradlew build`.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit any fixes**

```bash
git add -A
git commit -m "fix(ai-description): address code-reviewer findings"
```

---

### Task 23: (Optional) Integration test with stub CLI

**Files:**
- Create: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeDescriptionAgentIntegrationTest.kt`

This test is opt-in via env var `INTEGRATION_CLAUDE=stub`, so normal CI is unaffected.

- [ ] **Step 1: Write integration test**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.claude

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.config.ClaudeProperties
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals

@EnabledIfEnvironmentVariable(named = "INTEGRATION_CLAUDE", matches = "stub")
class ClaudeDescriptionAgentIntegrationTest {
    @Test
    fun `end-to-end prompt to parsed result with stub CLI`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        // 1. Create stub that echoes stream-json with our canned JSON inside AssistantMessage text
        val stubClaude = tempDir.resolve("claude")
        stubClaude.writeText(
            """#!/bin/sh
cat <<'JSON'
{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"{\"short\": \"stub-s\", \"detailed\": \"stub-d\"}"}]}}
{"type":"result","subtype":"success","duration_ms":1,"duration_api_ms":1,"is_error":false,"num_turns":1,"result":"ok","session_id":"test","total_cost_usd":0,"usage":{"input_tokens":0,"output_tokens":0}}
JSON
""",
        )
        Files.setPosixFilePermissions(
            stubClaude,
            setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
            ),
        )

        val claudeProps =
            ClaudeProperties(
                oauthToken = "fake",
                model = "opus",
                cliPath = stubClaude.absolutePathString(),
                workingDirectory = tempDir.absolutePathString(),
                proxy = ClaudeProperties.ProxySection("", "", ""),
            )
        val common =
            DescriptionProperties.CommonSection(
                language = "en",
                shortMaxLength = 200,
                detailedMaxLength = 1500,
                maxFrames = 10,
                queueTimeout = Duration.ofSeconds(30),
                timeout = Duration.ofSeconds(30),
                maxConcurrent = 1,
            )

        val factory = ClaudeAsyncClientFactory(claudeProps)
        val invoker = DefaultClaudeInvoker(
            factory,
            DescriptionProperties(enabled = true, provider = "claude", common = common),
        )

        val mapper = ObjectMapper().registerKotlinModule()
        val stager =
            ClaudeImageStager(
                object : ru.zinin.frigate.analyzer.ai.description.api.TempFileWriter {
                    override suspend fun createTempFile(
                        prefix: String,
                        suffix: String,
                        content: ByteArray,
                    ): Path {
                        val p = tempDir.resolve("$prefix$suffix")
                        p.parent.createDirectories()
                        Files.write(p, content)
                        return p
                    }

                    override suspend fun deleteFiles(files: List<Path>): Int = files.count { Files.deleteIfExists(it) }
                },
            )

        val agent =
            ClaudeDescriptionAgent(
                claudeProperties = claudeProps,
                descriptionProperties = DescriptionProperties(
                    enabled = true,
                    provider = "claude",
                    common = common,
                ),
                promptBuilder = ClaudePromptBuilder(),
                responseParser = ClaudeResponseParser(mapper),
                imageStager = stager,
                invoker = invoker,
                exceptionMapper = ClaudeExceptionMapper(),
            )

        val request =
            DescriptionRequest(
                recordingId = UUID.randomUUID(),
                frames = listOf(DescriptionRequest.FrameImage(0, byteArrayOf(1))),
                language = "en",
                shortMaxLength = 200,
                detailedMaxLength = 1500,
            )

        val result = agent.describe(request)
        assertEquals("stub-s", result.short)
        assertEquals("stub-d", result.detailed)
    }
}
```

- [ ] **Step 2: Run the test explicitly**

```bash
INTEGRATION_CLAUDE=stub ./gradlew :frigate-analyzer-ai-description:test --tests ClaudeDescriptionAgentIntegrationTest
```

Expected: PASS (if SDK wire-format of stub matches the real SDK — adjust stub JSON as needed to match `MessageParser` expectations).

**Note:** If this integration test proves fragile against SDK internals, treat it as optional — it's a nice-to-have, not a gate.

- [ ] **Step 3: Commit**

```bash
git add modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeDescriptionAgentIntegrationTest.kt
git commit -m "test(ai-description): add opt-in integration test with stub CLI"
```

---

### Task 24: Clean up design/plan docs before PR

Per user CLAUDE.md: design and plan docs must not appear in the final PR diff.

**Files:**
- Delete: `docs/superpowers/specs/2026-04-19-ai-description-design.md`
- Delete: `docs/superpowers/plans/2026-04-19-ai-description-plan.md`

- [ ] **Step 1: Remove docs and commit**

```bash
git rm docs/superpowers/specs/2026-04-19-ai-description-design.md
git rm docs/superpowers/plans/2026-04-19-ai-description-plan.md
git commit -m "chore: remove brainstorming docs before PR"
```

Docs stay available in branch history via `git log feature/ai-description` if needed.

---

## Post-plan: verification checklist

Before opening the PR:

- [ ] `./gradlew build` is green (via build-runner).
- [ ] `./gradlew ktlintCheck` is green.
- [ ] Manual test: build docker image, set `CLAUDE_CODE_OAUTH_TOKEN`, trigger a test recording, observe placeholder → edit in Telegram.
- [ ] Manual test: disable feature (`APP_AI_DESCRIPTION_ENABLED=false` — default), confirm notification flow unchanged AND application starts without NoSuchBeanDefinitionException.
- [ ] Manual test: set `APP_AI_DESCRIPTION_ENABLED=true` BUT `APP_AI_DESCRIPTION_PROVIDER=foo` (не зарегистрирован); startup должен выдать WARN "no agent registered" и не ломать приложение.
- [ ] Manual test: camera с именем, содержащим `<` или `&` — Telegram уведомление должно успешно уходить и отображаться (проверяет HTML-escape baseText).
- [ ] Manual test: recording с >10 кадрами — в Claude уходит ровно 10.
- [ ] PR description references the spec sections (reviewers will want it).
