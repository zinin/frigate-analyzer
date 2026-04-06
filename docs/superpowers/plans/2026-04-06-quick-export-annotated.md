# Quick Export: вторая кнопка с аннотациями + прогресс — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a second quick export button for annotated video and show export progress in both buttons.

**Architecture:** Extend `QuickExportHandler` with a new callback prefix `qea:`, pass `ExportMode` through the handler, add `mode` parameter to `VideoExportService.exportByRecordingId()`, and update the inline keyboard from one button to two buttons in one row. Progress is displayed by updating the inline keyboard button text during export.

**Tech Stack:** Kotlin, ktgbotapi, Spring Boot, MockK

---

### Task 1: Add `mode` parameter to `VideoExportService.exportByRecordingId()`

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/VideoExportService.kt:36-41`
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/VideoExportServiceImpl.kt:144-177`
- Modify: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/VideoExportServiceImplTest.kt`

- [ ] **Step 1: Write the failing test — `exportByRecordingId` with ANNOTATED mode**

Add test to `VideoExportServiceImplTest.kt` after the existing `exportByRecordingId` tests (after line 685):

```kotlin
@Test
fun `exportByRecordingId with ANNOTATED mode calls exportVideo with ANNOTATED`() =
    runTest {
        val recording = recordingWithTimestamp()
        val recordingFile = createTempFile("recording1.mp4")
        val mergedFile = createTempFile("merged.mp4")
        val annotatedFile = createTempFile("annotated.mp4")

        val expectedStart = recordTimestamp.minus(exportDuration)
        val expectedEnd = recordTimestamp.plus(exportDuration)

        coEvery { recordingRepository.findById(recordingId) } returns recording
        coEvery { recordingRepository.findByCamIdAndInstantRange("front", expectedStart, expectedEnd) } returns
            listOf(recording(recordingFile.toString()))
        coEvery { videoMergeHelper.mergeVideos(any()) } returns mergedFile
        coEvery { tempFileHelper.deleteIfExists(mergedFile) } returns true
        stubAnnotateVideo(annotatedFile)

        val result =
            service.exportByRecordingId(
                recordingId = recordingId,
                duration = exportDuration,
                mode = ExportMode.ANNOTATED,
            )

        assertEquals(annotatedFile, result)
        assertAnnotateCalledWith(mergedFile, "person,car", "yolo26x.pt")
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "*.VideoExportServiceImplTest.exportByRecordingId with ANNOTATED mode*" -x ktlintCheck`
Expected: Compilation error — `mode` parameter does not exist on `exportByRecordingId`

- [ ] **Step 3: Add `mode` parameter to interface and implementation**

In `VideoExportService.kt`, change the `exportByRecordingId` signature (lines 36-41):

```kotlin
suspend fun exportByRecordingId(
    recordingId: UUID,
    duration: Duration = Duration.ofMinutes(1),
    mode: ExportMode = ExportMode.ORIGINAL,
    onProgress: suspend (VideoExportProgress) -> Unit = {},
): Path
```

In `VideoExportServiceImpl.kt`, update the override (lines 144-177). Change the signature:

```kotlin
override suspend fun exportByRecordingId(
    recordingId: UUID,
    duration: Duration,
    mode: ExportMode,
    onProgress: suspend (VideoExportProgress) -> Unit,
): Path {
```

And change the `exportVideo` call (line 170-176) to pass `mode`:

```kotlin
return exportVideo(
    startInstant = startInstant,
    endInstant = endInstant,
    camId = camId,
    mode = mode,
    onProgress = onProgress,
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "*.VideoExportServiceImplTest" -x ktlintCheck`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/VideoExportService.kt \
       modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/VideoExportServiceImpl.kt \
       modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/VideoExportServiceImplTest.kt
git commit -m "feat(export): add mode parameter to exportByRecordingId"
```

---

### Task 2: Update inline keyboard — two buttons in one row

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandler.kt:155-186` (companion object)
- Modify: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandlerTest.kt`

- [ ] **Step 1: Update tests for the new keyboard layout**

In `QuickExportHandlerTest.kt`, replace the `CreateExportKeyboardTest` inner class (lines 103-136) with:

```kotlin
@Nested
inner class CreateExportKeyboardTest {
    @Test
    fun `creates keyboard with single row and two buttons`() {
        val recordingId = UUID.randomUUID()

        val keyboard = QuickExportHandler.createExportKeyboard(recordingId)

        assertEquals(1, keyboard.keyboard.size, "Should have one row")
        assertEquals(2, keyboard.keyboard[0].size, "Row should have two buttons")
    }

    @Test
    fun `first button is original export`() {
        val recordingId = UUID.randomUUID()

        val keyboard = QuickExportHandler.createExportKeyboard(recordingId)
        val button = keyboard.keyboard[0][0]

        assertIs<CallbackDataInlineKeyboardButton>(button)
        assertEquals("📹 Оригинал", button.text)
        assertEquals("${QuickExportHandler.CALLBACK_PREFIX}$recordingId", button.callbackData)
    }

    @Test
    fun `second button is annotated export`() {
        val recordingId = UUID.randomUUID()

        val keyboard = QuickExportHandler.createExportKeyboard(recordingId)
        val button = keyboard.keyboard[0][1]

        assertIs<CallbackDataInlineKeyboardButton>(button)
        assertEquals("📹 С объектами", button.text)
        assertEquals("${QuickExportHandler.CALLBACK_PREFIX_ANNOTATED}$recordingId", button.callbackData)
    }
}
```

Also update `CallbackPrefixTest` (lines 95-101) to add the annotated prefix check:

```kotlin
@Nested
inner class CallbackPrefixTest {
    @Test
    fun `CALLBACK_PREFIX has correct value`() {
        assertEquals("qe:", QuickExportHandler.CALLBACK_PREFIX)
    }

    @Test
    fun `CALLBACK_PREFIX_ANNOTATED has correct value`() {
        assertEquals("qea:", QuickExportHandler.CALLBACK_PREFIX_ANNOTATED)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :telegram:test --tests "*.QuickExportHandlerTest.CreateExportKeyboardTest" --tests "*.QuickExportHandlerTest.CallbackPrefixTest" -x ktlintCheck`
Expected: Compilation error — `CALLBACK_PREFIX_ANNOTATED` does not exist, keyboard has wrong button count

- [ ] **Step 3: Update companion object in QuickExportHandler**

In `QuickExportHandler.kt`, update the companion object (lines 155-186):

```kotlin
companion object {
    const val CALLBACK_PREFIX = "qe:"
    const val CALLBACK_PREFIX_ANNOTATED = "qea:"
    private const val QUICK_EXPORT_ORIGINAL_TIMEOUT_MS = 300_000L // 5 minutes
    private const val QUICK_EXPORT_ANNOTATED_TIMEOUT_MS = 1_200_000L // 20 minutes

    internal fun parseRecordingId(callbackData: String): UUID? {
        val recordingIdStr =
            callbackData
                .removePrefix(CALLBACK_PREFIX_ANNOTATED)
                .removePrefix(CALLBACK_PREFIX)
        return try {
            UUID.fromString(recordingIdStr)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun createExportKeyboard(recordingId: UUID): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            keyboard =
                matrix {
                    row {
                        +CallbackDataInlineKeyboardButton("📹 Оригинал", "$CALLBACK_PREFIX$recordingId")
                        +CallbackDataInlineKeyboardButton("📹 С объектами", "$CALLBACK_PREFIX_ANNOTATED$recordingId")
                    }
                },
        )

    fun createProcessingKeyboard(
        recordingId: UUID,
        text: String = "⚙️ Экспорт...",
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            keyboard =
                matrix {
                    row {
                        +CallbackDataInlineKeyboardButton(text, "$CALLBACK_PREFIX$recordingId")
                    }
                },
        )
}
```

Note: `parseRecordingId` now strips `qea:` first (longer prefix), then `qe:`. This ensures both prefixes are handled. `QUICK_EXPORT_TIMEOUT_MS` is replaced with two constants. `createProcessingKeyboard` now accepts a `text` parameter for progress updates.

- [ ] **Step 4: Fix existing tests that reference old button text "📹 Экспорт видео"**

In `QuickExportHandlerTest.kt`, find all assertions checking for `"📹 Экспорт видео"` in the restored button text. In the `HandleTest` class, update the assertion in `should export video for authorized user` (around line 335):

```kotlin
// Old:
assertEquals(
    "📹 Экспорт видео",
    (restoreMarkup.keyboard[0][0] as CallbackDataInlineKeyboardButton).text,
)

// New:
assertEquals(
    "📹 Оригинал",
    (restoreMarkup.keyboard[0][0] as CallbackDataInlineKeyboardButton).text,
)
```

Search for any other test assertions with `"📹 Экспорт видео"` and update them to `"📹 Оригинал"`.

Also update `CreateProcessingKeyboardTest` (lines 138-171). The `button has processing text` test remains valid. The `button has correct callback data` test remains valid.

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :telegram:test --tests "*.QuickExportHandlerTest" -x ktlintCheck`
Expected: All tests PASS

- [ ] **Step 6: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandler.kt \
       modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandlerTest.kt
git commit -m "feat(export): two quick export buttons — original and annotated"
```

---

### Task 3: Handle annotated callback and add progress display

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandler.kt:39-138` (handle method)
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBot.kt:131-141`
- Modify: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandlerTest.kt`

- [ ] **Step 1: Write test for annotated export callback handling**

Add test in `HandleTest` class in `QuickExportHandlerTest.kt`:

```kotlin
@Test
fun `handle annotated callback calls exportByRecordingId with ANNOTATED mode`() =
    runTest {
        val user = CommonUser(
            id = ChatId(RawChatId(1L)),
            firstName = "Test",
            username = Username("@testuser"),
        )
        val realChat = PrivateChatImpl(
            id = ChatId(RawChatId(12345L)),
            firstName = "TestChat",
        )
        val mockMessage = mockk<ContentMessage<MessageContent>>(relaxed = true) {
            every { chat } returns realChat
        }
        val callback = MessageDataCallbackQuery(
            id = CallbackQueryId("test-annotated-callback"),
            from = user,
            chatInstance = "test-instance",
            message = mockMessage,
            data = "${QuickExportHandler.CALLBACK_PREFIX_ANNOTATED}$recordingId",
        )

        val tempFile = Files.createTempFile("test-export", ".mp4")

        val capturedRequests = mutableListOf<Request<*>>()
        coEvery { bot.execute(any<Request<*>>()) } coAnswers {
            val request = firstArg<Request<*>>()
            capturedRequests.add(request)
            if (request is AnswerCallbackQuery) true
            else mockk<ContentMessage<MessageContent>>(relaxed = true)
        }
        coEvery {
            videoExportService.exportByRecordingId(eq(recordingId), any(), eq(ExportMode.ANNOTATED), any())
        } returns tempFile
        coEvery { videoExportService.cleanupExportFile(tempFile) } returns Unit

        try {
            handler.handle(callback)
        } finally {
            Files.deleteIfExists(tempFile)
        }

        coVerify { videoExportService.exportByRecordingId(eq(recordingId), any(), eq(ExportMode.ANNOTATED), any()) }
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :telegram:test --tests "*.QuickExportHandlerTest.HandleTest.handle annotated callback*" -x ktlintCheck`
Expected: FAIL — handler does not pass mode to `exportByRecordingId`

- [ ] **Step 3: Update `handle()` to determine mode from callback data and add progress**

Rewrite the `handle()` method in `QuickExportHandler.kt`. The key changes:
1. Determine `ExportMode` from callback prefix
2. Choose timeout based on mode
3. Pass `mode` to `exportByRecordingId`
4. Pass `onProgress` callback that updates the button text
5. Update button text for SENDING stage after export completes

Replace the `handle` method (lines 39-138):

```kotlin
suspend fun handle(callback: DataCallbackQuery) {
    val messageCallback = callback as? MessageDataCallbackQuery
    val message = messageCallback?.message
    if (message == null) {
        bot.answer(callback)
        return
    }
    val chatId = message.chat.id

    val callbackData = callback.data
    val mode =
        if (callbackData.startsWith(CALLBACK_PREFIX_ANNOTATED)) ExportMode.ANNOTATED else ExportMode.ORIGINAL

    // Parse recordingId from callback data
    val recordingId = parseRecordingId(callbackData)
    if (recordingId == null) {
        logger.warn { "Invalid recordingId in callback: $callbackData" }
        bot.answer(callback, "Ошибка: неверный формат данных")
        return
    }

    // Check username presence
    val user = callback.user
    val username = user.username?.withoutAt
    if (username == null) {
        bot.answer(callback, "Пожалуйста, установите username в настройках Telegram.")
        return
    }

    // Check authorization
    if (authorizationFilter.getRole(username) == null) {
        bot.answer(callback, properties.unauthorizedMessage)
        return
    }

    // Prevent duplicate exports
    if (!activeExports.add(recordingId)) {
        bot.answer(callback, "Экспорт уже выполняется.")
        return
    }

    // Answer callback immediately
    bot.answer(callback)

    // Switch button to processing state
    try {
        bot.editMessageReplyMarkup(
            message,
            replyMarkup = createProcessingKeyboard(recordingId),
        )
    } catch (e: Exception) {
        logger.warn(e) { "Failed to update button to processing state" }
    }

    try {
        val timeout =
            if (mode == ExportMode.ANNOTATED) QUICK_EXPORT_ANNOTATED_TIMEOUT_MS else QUICK_EXPORT_ORIGINAL_TIMEOUT_MS

        var lastRenderedStage: VideoExportProgress.Stage? = null
        var lastRenderedPercent: Int? = null

        val onProgress: suspend (VideoExportProgress) -> Unit = { progress ->
            val shouldUpdate =
                when {
                    progress.stage != lastRenderedStage -> true
                    progress.stage == VideoExportProgress.Stage.ANNOTATING && progress.percent != null -> {
                        val lastPct = lastRenderedPercent ?: -1
                        (progress.percent - lastPct) >= 5
                    }
                    else -> false
                }

            if (shouldUpdate) {
                lastRenderedStage = progress.stage
                lastRenderedPercent = progress.percent
                val text = renderProgressButton(progress.stage, progress.percent)
                try {
                    bot.editMessageReplyMarkup(
                        message,
                        replyMarkup = createProcessingKeyboard(recordingId, text),
                    )
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to update progress button" }
                }
            }
        }

        val videoPath =
            withTimeoutOrNull(timeout) {
                videoExportService.exportByRecordingId(recordingId, mode = mode, onProgress = onProgress)
            }

        if (videoPath == null) {
            bot.sendTextMessage(chatId, "Экспорт занял слишком много времени. Попробуйте позже.")
            restoreButton(message, recordingId)
            return
        }

        try {
            try {
                bot.editMessageReplyMarkup(
                    message,
                    replyMarkup = createProcessingKeyboard(recordingId, "⚙️ Отправка..."),
                )
            } catch (e: Exception) {
                logger.warn(e) { "Failed to update progress button to sending" }
            }

            val sent =
                withTimeoutOrNull(properties.sendVideoTimeout.toMillis()) {
                    bot.sendVideo(
                        chatId,
                        videoPath.toFile().asMultipartFile().copy(filename = "quick_export_$recordingId.mp4"),
                    )
                }

            if (sent == null) {
                bot.sendTextMessage(chatId, "Не удалось отправить видео: превышено время ожидания.")
            }
        } finally {
            try {
                videoExportService.cleanupExportFile(videoPath)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to cleanup: $videoPath" }
            }
        }

        // Restore buttons
        restoreButton(message, recordingId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error(e) { "Quick export failed for recording $recordingId" }
        val errorMsg =
            when (e) {
                is IllegalArgumentException -> "Запись не найдена."
                is IllegalStateException -> "Файлы записи недоступны."
                else -> "Ошибка экспорта. Попробуйте позже."
            }
        bot.sendTextMessage(chatId, errorMsg)
        restoreButton(message, recordingId)
    } finally {
        activeExports.remove(recordingId)
    }
}
```

Add the `renderProgressButton` helper as a private function in the companion object:

```kotlin
private fun renderProgressButton(
    stage: VideoExportProgress.Stage,
    percent: Int? = null,
): String =
    when (stage) {
        VideoExportProgress.Stage.PREPARING -> "⚙️ Подготовка..."
        VideoExportProgress.Stage.MERGING -> "⚙️ Склейка видео..."
        VideoExportProgress.Stage.COMPRESSING -> "⚙️ Сжатие видео..."
        VideoExportProgress.Stage.ANNOTATING ->
            if (percent != null) "⚙️ Аннотация $percent%..." else "⚙️ Аннотация..."
        VideoExportProgress.Stage.SENDING -> "⚙️ Отправка..."
        VideoExportProgress.Stage.DONE -> "✅ Готово"
    }
```

Add the necessary import to `QuickExportHandler.kt`:

```kotlin
import ru.zinin.frigate.analyzer.telegram.service.model.ExportMode
import ru.zinin.frigate.analyzer.telegram.service.model.VideoExportProgress
```

- [ ] **Step 4: Update bot callback routing**

In `FrigateAnalyzerBot.kt`, update the `onDataCallbackQuery` filter (lines 131-141) to match both prefixes:

```kotlin
onDataCallbackQuery(
    initialFilter = {
        it.data.startsWith(QuickExportHandler.CALLBACK_PREFIX_ANNOTATED) ||
            it.data.startsWith(QuickExportHandler.CALLBACK_PREFIX)
    },
) { callback ->
    try {
        quickExportHandler.handle(callback)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error(e) { "Error handling quick export callback: ${callback.data}" }
    }
}
```

Note: `"qea:xxx".startsWith("qe:")` is actually `FALSE` (colon at different positions: `qe:` vs `qea:`), so both prefixes are distinct and order doesn't matter. Both checks are included for clarity.

- [ ] **Step 5: Update existing tests that call `exportByRecordingId` with old signature**

In `QuickExportHandlerTest.kt`, existing mock stubs use `exportByRecordingId(eq(recordingId), any(), any())` (3 args). Now the method has 4 parameters. Update all `coEvery`/`coVerify` calls:

```kotlin
// Old (3 args):
coEvery { videoExportService.exportByRecordingId(eq(recordingId), any(), any()) } returns tempFile
coVerify { videoExportService.exportByRecordingId(eq(recordingId), any(), any()) }

// New (4 args):
coEvery { videoExportService.exportByRecordingId(eq(recordingId), any(), any(), any()) } returns tempFile
coVerify { videoExportService.exportByRecordingId(eq(recordingId), any(), any(), any()) }
```

Also update `coVerify(exactly = 0)` calls:

```kotlin
// Old:
coVerify(exactly = 0) { videoExportService.exportByRecordingId(any(), any(), any()) }

// New:
coVerify(exactly = 0) { videoExportService.exportByRecordingId(any(), any(), any(), any()) }
```

- [ ] **Step 6: Run all tests**

Run: `./gradlew :telegram:test --tests "*.QuickExportHandlerTest" -x ktlintCheck`
Expected: All tests PASS

- [ ] **Step 7: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandler.kt \
       modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBot.kt \
       modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandlerTest.kt
git commit -m "feat(export): handle annotated callback and show progress in button"
```

---

### Task 4: Full build and format

**Files:** All modified files

- [ ] **Step 1: Run ktlintFormat**

Run: `./gradlew ktlintFormat`

- [ ] **Step 2: Run full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit formatting fixes if any**

```bash
git add -u
git commit -m "style: ktlint format"
```

Only commit if there are changes. Skip if `git status` shows clean tree.
