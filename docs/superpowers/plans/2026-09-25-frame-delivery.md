# Frame Delivery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Дать пресету AI-описаний необязательный потолок длинной стороны кадра (`max-image-side`) и научить grok-backend распознавать ответ, который `grok` получил после молчаливого выброса картинок, отвергая его как `InvalidResponse`.

**Architecture:** Поле `maxImageSide` проходит путь `DescriptionProperties.Preset` → `DescriptionPreset` (каталог) → `VisionCallExecutor`, где итоговая сторона — меньшее из ненулевых значений фичи и пресета (`FrameDownscaler.effectiveMaxSide`). Новый `GrokImageStripDetector` после каждого запуска `grok` читает последний 1 МиБ `GROK_HOME/logs/unified.jsonl` (под тем же `GrokHomeGuard.shared`) и ищет событие `shell.turn.images_stripped` с `sid`, равным `sessionId` из stdout; `GrokBackend` превращает найденный выброс в `InvalidResponse`, а отсутствие записей сессии — в разовый WARN и fail-open.

**Tech Stack:** Kotlin 2.4.10, Spring Boot 4.1.0, Java 25, kotlinx-coroutines, Jackson 3 (`tools.jackson.*`), kotlin-logging, JUnit 5 + kotlin-test, MockK, logback `ListAppender` для проверок лога, ktlint.

**Spec:** `docs/superpowers/specs/2026-09-25-frame-delivery-design.md`

## Global Constraints

- Все команды Gradle (`./gradlew …`) в основной сессии запускаются **только** через агента `claude-forge:build-runner` (правило `CLAUDE.md`); субагент-исполнитель задачи запускает их сам. На ошибки ktlint: `./gradlew ktlintFormat`, затем повтор.
- Модуль: `:frigate-analyzer-ai-description`. Один класс: `./gradlew :frigate-analyzer-ai-description:test --tests '<FQCN>'`. Весь модуль: `./gradlew :frigate-analyzer-ai-description:test`. Линт: `./gradlew :frigate-analyzer-ai-description:ktlintCheck`.
- После создания или изменения файла — `git add <file>` (правило `CLAUDE.md`). В коммит попадают только файлы задачи; неотслеживаемые `.codex`, `docs/session-transfer-*`, `tmp/`, прочие `docs/superpowers/*` не трогать.
- Если системный промпт сессии даёт строки атрибуции (например `Claude-Session: <url>`), каждое сообщение коммита заканчивается ими отдельным `-m`; если не даёт — без хвоста. Id чужой сессии не копировать.
- Продакшен-код `core` и `telegram` не меняется. Новых переменных окружения и миграций БД нет.
- Новые параметры конструкторов — последними и со значением по умолчанию, где это указано; вызовы конструкторов — именованными аргументами.
- KDoc и комментарии — по-русски, как в модуле; тексты логов и исключений — по-английски.
- Точные значения (копировать дословно):
  - допустимый `max-image-side` пресета: `0` или `256..8192`;
  - текст проверки: `preset '$id': max-image-side must be 0 (no cap) or 256..8192, was $maxImageSide`;
  - суффикс подписи пресета в логе: `, max-image-side=N` (только при `N > 0`);
  - путь лога grok: `GROK_HOME/logs/unified.jsonl`; окно чтения `CAPTURE_TAIL_BYTES = 1 МиБ` (`1024L * 1024L`);
  - имя события: `shell.turn.images_stripped`; поля: `sid`, `msg`, `ctx.stripped`, `ctx.reason`;
  - detail исключения: `grok dropped $images of $frames frame(s) before the model call (reason=$reasons); if the endpoint limits request size, set max-image-side on the preset`;
  - WARN выброса: `Grok dropped $images of $frames frame(s) for $requestId (model=$model, reason=$reasons); rejecting the answer: <первые 300 символов ответа или "<no answer>">`;
  - WARN слепоты (один раз за процесс, дальше DEBUG): `Cannot verify frame delivery for grok runs: $reason; image drops by grok will go unnoticed`;
  - поле DEBUG-строки `Grok call …`: `strip=clean|stripped|blind`.
- Jackson 3: `JsonNode.isString` / `stringValue()`, `isIntegralNumber`, `canConvertToInt()`, `intValue()`, `isObject`; исключения разбора — `tools.jackson.core.JacksonException`.

## Review Focus

Пять входов, которые спека подразумевает, а прямые тесты легко пропускают; у каждого — тест в задаче-владельце:

1. **Окно хвоста начинается ровно с начала строки.** Строка на границе окна обязана сохраниться, иначе детектор слепнет по off-by-one — Task 3, тест `a line starting exactly at the window boundary is kept`.
2. **Многобайтный текст, разрезанный границей окна.** Кириллица из заметок о камерах в промпте судьи может попасть в лог grok; разрез двухбайтного символа не должен ни бросать, ни прятать наши строки — Task 3, тест `multi-byte text cut by the window boundary does not hide our lines`.
3. **`ctx.stripped` отсутствует или не целое.** Выброс всё равно засчитывается (за 1) и ответ отвергается — Task 3, тест `a strip without an integer count still counts as one dropped frame`.
4. **Выброс в запуске без ответа** (`stopReason=max_tokens`). Отчёт про выброс, а не про стоп-причину, и без NPE при логе фрагмента — Task 4, тест `a strip is reported even when grok returned no answer`.
5. **Потолок одного пресета не протекает в вызовы другого** того же executor-а (владелец переключил пресет в `/ai`) — Task 2, тест `a cap on one preset does not reach calls resolved to another`.

## File Structure

| Файл | Действие | Ответственность |
|---|---|---|
| `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/config/DescriptionProperties.kt` | Modify | `Preset.maxImageSide` + проверка |
| `…/ai/description/api/DescriptionPreset.kt` | Modify | поле `maxImageSide` в представлении пресета |
| `…/ai/description/core/DescriptionPresetCatalogBuilder.kt` | Modify | копирует поле в представление |
| `…/ai/description/core/PresetLogFormat.kt` | Modify | суффикс `, max-image-side=N` |
| `…/ai/description/core/FrameDownscaler.kt` | Modify | `effectiveMaxSide(featureCap, presetCap)` |
| `…/ai/description/core/VisionCallExecutor.kt` | Modify | уменьшение по потолку фичи и пресета |
| `…/ai/description/core/VisionLimits.kt` | Modify | KDoc поля `maxImageSide` |
| `…/ai/description/grok/GrokImageStripDetector.kt` | Create | `CapturedLog`, `StripCheck`, чтение и разбор хвоста `unified.jsonl` |
| `…/ai/description/grok/GrokBackend.kt` | Modify | захват хвоста под guard, проверка, отказ |
| `…/ai/description/grok/GrokBackendFactory.kt` | Modify | передаёт детектор в backend |
| `modules/ai-description/src/test/kotlin/…/grok/GrokUnifiedLogFixtures.kt` | Create | строки лога grok 1.0.41 для тестов |
| `…/test/…/grok/GrokImageStripDetectorTest.kt` | Create | детектор |
| `…/test/…/grok/GrokBackendEndToEndTest.kt` | Create | stub-`grok` через настоящий runner |
| тесты `DescriptionPresetsValidationTest`, `AiDescriptionAutoConfigurationTest`, `DescriptionPresetCatalogBuilderTest`, `FrameDownscalerTest`, `VisionCallExecutorTest`, `GrokBackendTest`, `GrokBackendFactoryTest`, `PartialSchemaRecoveryTest` | Modify | новые тесты и новые параметры конструкторов |
| `README.md`, `.claude/rules/ai-description.md`, `.claude/rules/configuration.md`, `docker/deploy/application-docker.yaml.example`, `docker/deploy/.env.example`, `CLAUDE.md` | Modify | документация |

`…` в путях = `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer` (main) и `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description` (test).

---

### Task 1: Поле `max-image-side` у пресета — объявление, проверка, каталог, лог

**Files:**
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/config/DescriptionProperties.kt` (класс `Preset`)
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/api/DescriptionPreset.kt`
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/core/DescriptionPresetCatalogBuilder.kt` (`entryOf`)
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/core/PresetLogFormat.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/config/DescriptionPresetsValidationTest.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/config/AiDescriptionAutoConfigurationTest.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/core/DescriptionPresetCatalogBuilderTest.kt`

**Interfaces:**
- Consumes: —
- Produces:
  - `DescriptionProperties.Preset(provider: String, model: String, effort: String = "", maxImageSide: Int = 0)`
  - `DescriptionPreset(id, provider, model, effectiveModel, effort, authScopeId, unavailableReason, slowEffort: Boolean = false, maxImageSide: Int = 0)`
  - `internal fun DescriptionPreset.logSignature(): String` → `provider/effectiveModel[/effort][, max-image-side=N]`

- [ ] **Step 1: Написать падающие тесты проверки**

В `DescriptionPresetsValidationTest` добавить в конец класса:

```kotlin
    @Test
    fun `a preset max-image-side below 256 is rejected with the preset id`() {
        val e =
            assertFailsWith<IllegalArgumentException> {
                props(mapOf("byok" to DescriptionProperties.Preset(provider = "grok", model = "m", maxImageSide = 100)))
            }
        assertTrue(e.message!!.contains("byok"), e.message)
        assertTrue(e.message!!.contains("max-image-side"), e.message)
    }

    @Test
    fun `a preset max-image-side above 8192 is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            props(mapOf("byok" to DescriptionProperties.Preset(provider = "grok", model = "m", maxImageSide = 9000)))
        }
    }

    /** Потолок не зависит от провайдера: claude за сторонним шлюзом нуждается в нём так же, как BYOK-grok. */
    @Test
    fun `a preset max-image-side of zero or within range is accepted for any provider`() {
        val parsed =
            props(
                mapOf(
                    "byok" to DescriptionProperties.Preset(provider = "grok", model = "m", maxImageSide = 1568),
                    "gateway" to DescriptionProperties.Preset(provider = "claude", model = "opus", maxImageSide = 256),
                    "plain" to DescriptionProperties.Preset(provider = "grok", model = "m"),
                ),
            )

        assertEquals(1568, parsed.presets.getValue("byok").maxImageSide)
        assertEquals(256, parsed.presets.getValue("gateway").maxImageSide)
        assertEquals(0, parsed.presets.getValue("plain").maxImageSide)
    }
```

- [ ] **Step 2: Написать падающие тесты каталога**

В `DescriptionPresetCatalogBuilderTest` добавить в конец класса (хелперы `grok`, `claude`, `FakeFactory`, `build`, `catalogOf`, `logsFrom` уже есть):

```kotlin
    @Test
    fun `the preset cap reaches the catalog view`() {
        val catalog =
            catalogOf(
                build(
                    presets = linkedMapOf("byok" to grok.copy(maxImageSide = 1568), "grok-fast" to grok),
                    factories = listOf(FakeFactory("grok")),
                ),
            )

        assertEquals(1568, assertNotNull(catalog.byId("byok")).view.maxImageSide)
        assertEquals(0, assertNotNull(catalog.byId("grok-fast")).view.maxImageSide)
    }

    /**
     * Потолок входит в подпись пресета только там, где задан: у большинства пресетов его нет, и
     * пустой сегмент в каждой строке каталога был бы шумом. Строка сверяется целиком, как и
     * соседний тест стартовой строки.
     */
    @Test
    fun `the startup line names the cap only for a preset that sets it`() {
        val lines =
            logsFrom(Level.INFO) {
                build(
                    presets = linkedMapOf("byok" to grok.copy(maxImageSide = 1568), "claude-opus" to claude),
                    factories = listOf(FakeFactory("grok"), FakeFactory("claude")),
                    defaultPreset = "claude-opus",
                )
            }.filter { it.startsWith("Description presets:") }

        assertEquals(
            "Description presets: byok (grok/grok-4.6/low, max-image-side=1568), claude-opus (claude/opus); default 'claude-opus'",
            assertNotNull(lines.singleOrNull(), lines.toString()),
        )
    }
```

- [ ] **Step 3: Написать падающий тест связывания**

В `AiDescriptionAutoConfigurationTest` добавить после теста `two presets give two usable entries and the default preset wins` (хелперы `runner`, `properties`, `catalog` уже есть):

```kotlin
    /** Ключ пишется в yaml kebab-case; relaxed binding обязан довести его до поля пресета. */
    @Test
    fun `a preset max-image-side binds from the kebab-case key and reaches the catalog`() {
        runner
            .withPropertyValues(
                *properties(enabled = true, provider = "grok"),
                "application.ai.description.presets.byok.provider=grok",
                "application.ai.description.presets.byok.model=my-vision",
                "application.ai.description.presets.byok.effort=low",
                "application.ai.description.presets.byok.max-image-side=1568",
                "application.ai.description.presets.grok-fast.provider=grok",
                "application.ai.description.presets.grok-fast.model=grok-4.6",
            ).run { context ->
                val presets = catalog(context).all().associateBy { it.id }
                assertEquals(1568, presets.getValue("byok").maxImageSide)
                assertEquals(0, presets.getValue("grok-fast").maxImageSide)
            }
    }
```

- [ ] **Step 4: Запустить тесты и убедиться, что они не компилируются**

Run: `./gradlew :frigate-analyzer-ai-description:test --tests 'ru.zinin.frigate.analyzer.ai.description.config.DescriptionPresetsValidationTest'`
Expected: FAIL на компиляции тестов — `No parameter with name 'maxImageSide' found` / `Unresolved reference 'maxImageSide'`.

- [ ] **Step 5: Добавить поле и проверку в `DescriptionProperties.Preset`**

Заменить класс `Preset` целиком:

```kotlin
    data class Preset(
        val provider: String,
        val model: String,
        val effort: String = "",
        /**
         * Потолок длинной стороны кадра для вызовов через этот пресет; `0` — без потолка. Итог —
         * меньшее из ненулевых значений пресета и фичи ([CommonSection.maxImageSide] у описаний,
         * `application.ai.judge.max-image-side` у судьи): пресет выражает предел своей модели или
         * шлюза и может только ужесточить лимит фичи, но не поднять его.
         */
        val maxImageSide: Int = 0,
    ) {
        internal fun validate(id: String) {
            require(provider in KNOWN_PROVIDERS) {
                "preset '$id': provider '$provider' is unknown (known: ${KNOWN_PROVIDERS.joinToString()})"
            }
            require(model.isNotBlank()) { "preset '$id': model must not be blank" }
            require(effort.isBlank() || effort in EFFORTS) {
                "preset '$id': effort '$effort' must be empty or one of ${EFFORTS.joinToString()}"
            }
            require(effort.isBlank() || provider == "grok") {
                "preset '$id': effort is supported only by provider grok, not '$provider'"
            }
            // Границы те же, что у CommonSection.maxImageSide и JudgeProperties.maxImageSide.
            require(maxImageSide == 0 || maxImageSide in 256..8192) {
                "preset '$id': max-image-side must be 0 (no cap) or 256..8192, was $maxImageSide"
            }
        }
    }
```

- [ ] **Step 6: Добавить поле в `DescriptionPreset`**

В `api/DescriptionPreset.kt` после параметра `slowEffort` добавить последним параметром:

```kotlin
    val slowEffort: Boolean = false,
    /**
     * Потолок длинной стороны кадра этого пресета; `0` — без потолка. Итоговая сторона — меньшее из
     * ненулевых значений пресета и вызывающей фичи (`VisionLimits.maxImageSide`), см.
     * `FrameDownscaler.effectiveMaxSide`.
     */
    val maxImageSide: Int = 0,
) {
```

(Строка `val slowEffort: Boolean = false,` и закрывающее `) {` уже есть — вставляется только блок KDoc и `val maxImageSide: Int = 0,` между ними.)

- [ ] **Step 7: Копировать поле в каталог**

В `core/DescriptionPresetCatalogBuilder.kt`, функция `entryOf`, в конструкторе `DescriptionPreset(...)` после строки `slowEffort = slowEffort,` добавить:

```kotlin
                maxImageSide = preset.maxImageSide,
```

- [ ] **Step 8: Дописать суффикс в подпись пресета**

Заменить содержимое `core/PresetLogFormat.kt` после `package`/`import` целиком:

```kotlin
/**
 * Одна форма записи пресета в логе — `provider/model/effort`, — на стартовую строку каталога и на
 * строку об источнике активного пресета. Две копии разъехались бы, а оператор сверяет эти строки
 * между собой: одна перечисляет объявленное, вторая называет работающее.
 *
 * Модель именно эффективная: `ANTHROPIC_MODEL` вытесняет объявленную, и печатать объявленную
 * значило бы называть запрос, которого не будет. Расхождение объявленной и эффективной отдельно
 * называет WARN `DescriptionPresetCatalogBuilder.warnAboutDisplacedModels`.
 *
 * Пустой `effort` опускается, а не печатается пустым сегментом: у claude его не бывает вовсе.
 * Потолок кадра дописывается через запятую и только если задан: у большинства пресетов его нет.
 */
internal fun DescriptionPreset.logSignature(): String =
    listOfNotNull(provider, effectiveModel, effort.takeIf { it.isNotBlank() }).joinToString("/") +
        if (maxImageSide > 0) ", max-image-side=$maxImageSide" else ""
```

(Импорт `DescriptionPreset` в файле уже есть — сохранить строки `package` и `import` как были.)

- [ ] **Step 9: Запустить тесты задачи — должны пройти**

Run: `./gradlew :frigate-analyzer-ai-description:test --tests 'ru.zinin.frigate.analyzer.ai.description.config.DescriptionPresetsValidationTest' --tests 'ru.zinin.frigate.analyzer.ai.description.config.AiDescriptionAutoConfigurationTest' --tests 'ru.zinin.frigate.analyzer.ai.description.core.DescriptionPresetCatalogBuilderTest'`
Expected: PASS.

- [ ] **Step 10: Прогнать модуль, компиляцию тестов telegram и линт**

Run: `./gradlew :frigate-analyzer-ai-description:test :frigate-analyzer-telegram:compileTestKotlin :frigate-analyzer-ai-description:ktlintCheck`
Expected: BUILD SUCCESSFUL (новое поле `DescriptionPreset` со значением по умолчанию не ломает конструкторы в тестах telegram; `ActivePresetResolverTest` с точными строками не меняется). На ошибку ktlint — `./gradlew ktlintFormat` и повтор.

- [ ] **Step 11: Commit**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/config/DescriptionProperties.kt \
  modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/api/DescriptionPreset.kt \
  modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/core/DescriptionPresetCatalogBuilder.kt \
  modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/core/PresetLogFormat.kt \
  modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/config/DescriptionPresetsValidationTest.kt \
  modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/config/AiDescriptionAutoConfigurationTest.kt \
  modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/core/DescriptionPresetCatalogBuilderTest.kt
git commit -m "feat(ai-description): let a preset declare its own max-image-side"
```

---

### Task 2: Итоговая сторона кадра — меньшее из потолков фичи и пресета

**Files:**
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/core/FrameDownscaler.kt`
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/core/VisionCallExecutor.kt`
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/core/VisionLimits.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/core/FrameDownscalerTest.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/core/VisionCallExecutorTest.kt`

**Interfaces:**
- Consumes: `DescriptionPreset.maxImageSide: Int` (Task 1).
- Produces: `internal fun FrameDownscaler.effectiveMaxSide(featureCap: Int, presetCap: Int): Int` — меньшее из ненулевых, `0` если оба нулевые.

- [ ] **Step 1: Написать падающий табличный тест `effectiveMaxSide`**

В `FrameDownscalerTest` добавить в конец класса:

```kotlin
    @Test
    fun `the effective side is the stricter of the non-zero caps`() {
        assertEquals(0, FrameDownscaler.effectiveMaxSide(featureCap = 0, presetCap = 0))
        assertEquals(1568, FrameDownscaler.effectiveMaxSide(featureCap = 0, presetCap = 1568))
        assertEquals(1280, FrameDownscaler.effectiveMaxSide(featureCap = 1280, presetCap = 0))
        assertEquals(1280, FrameDownscaler.effectiveMaxSide(featureCap = 1280, presetCap = 1568))
        assertEquals(1024, FrameDownscaler.effectiveMaxSide(featureCap = 1280, presetCap = 1024))
    }
```

- [ ] **Step 2: Дать тестовому каталогу executor-а потолок пресета**

В `VisionCallExecutorTest`:

1) в `build(...)` добавить последний параметр и передать его в `catalogOf`:

```kotlin
    private fun build(
        backend: FakeBackend,
        customLimits: VisionLimits = limits,
        timeSource: TimeSource = TimeSource.Monotonic,
        eventPublisher: ApplicationEventPublisher = publisher,
        extraPresets: List<Pair<String, VisionBackend>> = emptyList(),
        settings: PresetChoiceSource = InMemoryDescriptionRuntimeSettings(),
        presetCap: Int = 0,
    ) = VisionCallExecutor(
        resolver =
            ActivePresetResolver(
                catalogOf("test" to backend, *extraPresets.toTypedArray(), firstPresetCap = presetCap),
                settings,
                fallbackId = "test",
                label = "test",
            ),
        authTracker = ProviderAuthTracker(eventPublisher),
        limits = customLimits,
        label = "test",
        timeSource = timeSource,
    )
```

2) заменить `catalogOf` целиком:

```kotlin
    /**
     * По пресету на backend; первый объявленный — пресет по умолчанию, он же fallback каталога.
     * [firstPresetCap] — `max-image-side` первого пресета; у остальных потолка нет.
     */
    private fun catalogOf(
        vararg backends: Pair<String, VisionBackend>,
        firstPresetCap: Int = 0,
    ): DescriptionPresetCatalog =
        DescriptionPresetCatalog(
            backends.mapIndexed { index, (id, backend) ->
                DescriptionPresetCatalog.Entry(
                    DescriptionPreset(
                        id = id,
                        provider = backend.providerId,
                        model = "$id-model",
                        effectiveModel = "$id-model",
                        effort = "",
                        authScopeId = backend.authScopeId,
                        unavailableReason = null,
                        maxImageSide = if (index == 0) firstPresetCap else 0,
                    ),
                    backend,
                )
            },
            fallbackId = backends.first().first,
        )
```

3) рядом с классом `GatedSettings` добавить:

```kotlin
    /** Выбор владельца, закреплённый на одном пресете. */
    private class FixedChoice(
        private val id: String,
    ) : PresetChoiceSource {
        override val sourceName = "fixed choice"

        override suspend fun activePresetId(): String = id
    }
```

4) в импорты добавить `import kotlin.test.assertNotNull`.

- [ ] **Step 3: Написать падающие тесты executor-а**

В `VisionCallExecutorTest` после теста `frames are left alone when the limit is disabled` добавить:

```kotlin
    @Test
    fun `the preset cap applies when the task sets none`() =
        runTest {
            val big = jpeg(1920, 1080)
            var seen: ByteArray? = null
            val executor =
                build(
                    FakeBackend { request ->
                        seen = request.frames.single().bytes
                        "ok"
                    },
                    presetCap = 1568,
                )

            executor.call(request.copy(frames = listOf(DescriptionRequest.FrameImage(0, big))))

            assertEquals(1568, ImageIO.read(ByteArrayInputStream(assertNotNull(seen))).width)
        }

    @Test
    fun `the task cap wins when it is stricter than the preset cap`() =
        runTest {
            val big = jpeg(1920, 1080)
            var seen: ByteArray? = null
            val executor =
                build(
                    FakeBackend { request ->
                        seen = request.frames.single().bytes
                        "ok"
                    },
                    limits.copy(maxImageSide = 1280),
                    presetCap = 1568,
                )

            executor.call(request.copy(frames = listOf(DescriptionRequest.FrameImage(0, big))))

            assertEquals(1280, ImageIO.read(ByteArrayInputStream(assertNotNull(seen))).width)
        }

    @Test
    fun `the preset cap wins when it is stricter than the task cap`() =
        runTest {
            val big = jpeg(1920, 1080)
            var seen: ByteArray? = null
            val executor =
                build(
                    FakeBackend { request ->
                        seen = request.frames.single().bytes
                        "ok"
                    },
                    limits.copy(maxImageSide = 1280),
                    presetCap = 1024,
                )

            executor.call(request.copy(frames = listOf(DescriptionRequest.FrameImage(0, big))))

            assertEquals(1024, ImageIO.read(ByteArrayInputStream(assertNotNull(seen))).width)
        }

    /**
     * Review Focus 5: executor один на фичу, а пресет резолвится на каждый вызов. Потолок пресета
     * с лимитом не должен доставаться вызовам, которые владелец переключил на пресет без лимита.
     */
    @Test
    fun `a cap on one preset does not reach calls resolved to another`() =
        runTest {
            val big = jpeg(1920, 1080)
            var seenByOther: ByteArray? = null
            val other =
                FakeBackend { request ->
                    seenByOther = request.frames.single().bytes
                    "ok"
                }
            val executor =
                build(
                    FakeBackend { "capped" },
                    extraPresets = listOf("other" to other),
                    settings = FixedChoice("other"),
                    presetCap = 1568,
                )

            executor.call(request.copy(frames = listOf(DescriptionRequest.FrameImage(0, big))))

            assertSame(big, seenByOther)
        }
```

- [ ] **Step 4: Запустить тесты — должны упасть**

Run: `./gradlew :frigate-analyzer-ai-description:test --tests 'ru.zinin.frigate.analyzer.ai.description.core.FrameDownscalerTest' --tests 'ru.zinin.frigate.analyzer.ai.description.core.VisionCallExecutorTest'`
Expected: FAIL на компиляции — `Unresolved reference 'effectiveMaxSide'`.

- [ ] **Step 5: Добавить `effectiveMaxSide` в `FrameDownscaler`**

В `object FrameDownscaler` после константы `JPEG_QUALITY` добавить:

```kotlin
    /**
     * Итоговая длинная сторона из двух потолков — фичи и пресета: меньшее из ненулевых, `0` — ни
     * один не задан. Пресет выражает предел своей модели или шлюза и может только ужесточить лимит
     * фичи, но не поднять его.
     */
    internal fun effectiveMaxSide(
        featureCap: Int,
        presetCap: Int,
    ): Int = listOf(featureCap, presetCap).filter { it > 0 }.minOrNull() ?: 0
```

- [ ] **Step 6: Уменьшать кадры по потолку фичи и пресета в `VisionCallExecutor`**

1) в `execute` заменить вызов `downscaleFrames(request)` на:

```kotlin
                        downscaleFrames(request, entry.view.maxImageSide)
```

2) заменить функцию `downscaleFrames` целиком:

```kotlin
    /**
     * Один проход на запрос, до повторов: провайдер получает уже готовые кадры. Потолок — меньшее из
     * ненулевых значений фичи ([VisionLimits.maxImageSide]) и пресета, выбранного для этого вызова.
     */
    private suspend fun downscaleFrames(
        request: VisionRequest,
        presetCap: Int,
    ): VisionRequest {
        val maxSide = FrameDownscaler.effectiveMaxSide(limits.maxImageSide, presetCap)
        if (maxSide <= 0 || request.frames.isEmpty()) return request
        val before = request.frames.sumOf { it.bytes.size }
        val frames =
            withContext(Dispatchers.Default) {
                request.frames.map { frame -> frame.copy(bytes = FrameDownscaler.downscale(frame.bytes, maxSide)) }
            }
        val after = frames.sumOf { it.bytes.size }
        if (after != before) {
            logger.debug {
                "Downscaled ${frames.size} frames of ${request.requestId} to <=$maxSide px: " +
                    "$before -> $after bytes"
            }
        }
        return request.copy(frames = frames)
    }
```

3) в KDoc класса заменить фрагмент `downscale\n * кадров` на `downscale\n * кадров до меньшего из потолков фичи и пресета`, чтобы первая фраза KDoc читалась: «…retry по InvalidResponse и Transport с проверкой остатка бюджета, downscale кадров до меньшего из потолков фичи и пресета, отчёт в ProviderAuthTracker.»

- [ ] **Step 7: Обновить KDoc `VisionLimits.maxImageSide`**

В `core/VisionLimits.kt` заменить `/** 0 = кадры не уменьшаются. */` на:

```kotlin
    /** 0 = фича кадры не ограничивает; потолок пресета при этом всё равно действует. */
```

- [ ] **Step 8: Запустить тесты — должны пройти**

Run: `./gradlew :frigate-analyzer-ai-description:test --tests 'ru.zinin.frigate.analyzer.ai.description.core.FrameDownscalerTest' --tests 'ru.zinin.frigate.analyzer.ai.description.core.VisionCallExecutorTest'`
Expected: PASS (в том числе старые `frames are downscaled once before the backend sees them` и `frames are left alone when the limit is disabled`).

- [ ] **Step 9: Прогнать модуль и линт**

Run: `./gradlew :frigate-analyzer-ai-description:test :frigate-analyzer-ai-description:ktlintCheck`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Commit**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/core/FrameDownscaler.kt \
  modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/core/VisionCallExecutor.kt \
  modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/core/VisionLimits.kt \
  modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/core/FrameDownscalerTest.kt \
  modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/core/VisionCallExecutorTest.kt
git commit -m "feat(ai-description): downscale frames to the stricter of the feature and preset caps"
```

---

### Task 3: `GrokImageStripDetector` — чтение и разбор хвоста `unified.jsonl`

**Files:**
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokImageStripDetector.kt`
- Create: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokUnifiedLogFixtures.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokImageStripDetectorTest.kt`

**Interfaces:**
- Consumes: `GrokProperties.homePath: Path`; `tools.jackson.databind.ObjectMapper`.
- Produces:
  - `sealed interface CapturedLog { data class Lines(val lines: List<String>); data class Unavailable(val reason: String) }`
  - `sealed interface StripCheck { data object Clean; data class Stripped(val images: Int, val reasons: Set<String>); data class Blind(val reason: String) }`
  - `@Component class GrokImageStripDetector(properties: GrokProperties, objectMapper: ObjectMapper)` с `fun capture(): CapturedLog`, `fun inspect(captured: CapturedLog, sessionId: String?): StripCheck`, `fun reportBlind(reason: String)`, `companion: const val CAPTURE_TAIL_BYTES: Long`, `const val STRIPPED_EVENT: String`
  - тестовый `object GrokUnifiedLogFixtures` с `SID`, `OTHER`, `fun stripped(sid: String, count: Int, reason: String = "payload_heuristic"): String`, `fun inferenceDone(sid: String): String`

- [ ] **Step 1: Создать фикстуры лога**

`modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokUnifiedLogFixtures.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.grok

/**
 * Строки `GROK_HOME/logs/unified.jsonl`, снятые с grok 1.0.41 (2026-09-25; `ctx` сокращён). После
 * смены `ARG GROK_VERSION` имя события и поля нужно сверить с живым запуском и обновить здесь.
 */
object GrokUnifiedLogFixtures {
    const val SID = "01a0d95a-363c-7b41-9de8-9bb19b368c9c"
    const val OTHER = "01a0d918-9e8f-7121-9c44-cfa258d0f169"

    fun stripped(
        sid: String,
        count: Int,
        reason: String = "payload_heuristic",
    ): String =
        """{"ts":"2026-09-25T16:16:07.051Z","src":"shell","pid":901027,"ver":"1.0.41","lvl":"warn","sid":"$sid",""" +
            """"msg":"shell.turn.images_stripped","ctx":{"sampler_request_id":"8363536a-4bd0-4e39-9387-e0a4fec96a0d",""" +
            """"stripped":$count,"reason":"$reason","persist_deferred":false}}"""

    fun inferenceDone(sid: String): String =
        """{"ts":"2026-09-25T15:04:35.889Z","src":"shell","pid":639093,"ver":"1.0.41","lvl":"info","sid":"$sid",""" +
            """"msg":"shell.turn.inference_done","ctx":{"loop_index":1,"model_elapsed_ms":11295,"attempts":1,""" +
            """"prompt_tokens":4138,"completion_tokens":351}}"""
}
```

- [ ] **Step 2: Написать падающие тесты детектора**

`modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokImageStripDetectorTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.grok

import org.junit.jupiter.api.io.TempDir
import ru.zinin.frigate.analyzer.ai.description.config.GrokProperties
import ru.zinin.frigate.analyzer.ai.description.grok.GrokUnifiedLogFixtures.OTHER
import ru.zinin.frigate.analyzer.ai.description.grok.GrokUnifiedLogFixtures.SID
import ru.zinin.frigate.analyzer.ai.description.grok.GrokUnifiedLogFixtures.inferenceDone
import ru.zinin.frigate.analyzer.ai.description.grok.GrokUnifiedLogFixtures.stripped
import ru.zinin.frigate.analyzer.ai.description.testsupport.TestObjectMappers
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GrokImageStripDetectorTest {
    @TempDir
    lateinit var tempDir: Path

    private val home: Path get() = tempDir.resolve("home")
    private val log: Path get() = home.resolve("logs/unified.jsonl")
    private val window: Int = GrokImageStripDetector.CAPTURE_TAIL_BYTES.toInt()

    private fun detector() =
        GrokImageStripDetector(
            GrokProperties(
                cliPath = "",
                model = "grok-4.6",
                effort = "low",
                home = home.toString(),
                workingDirectory = tempDir.resolve("cwd").toString(),
                proxy = GrokProperties.ProxySection("", "", ""),
            ),
            TestObjectMappers.internalMapper(),
        )

    private fun writeLog(content: String) {
        Files.createDirectories(log.parent)
        log.writeText(content)
    }

    private fun lines(vararg entries: String): String = entries.joinToString("") { "$it\n" }

    private fun check(sessionId: String? = SID): StripCheck = detector().let { it.inspect(it.capture(), sessionId) }

    /** ASCII-строка чужой сессии ровно на [bytes] байт вместе с переводом строки. */
    private fun paddingLine(bytes: Int): String {
        val head = "{\"sid\":\"$OTHER\",\"msg\":\"pad\",\"ctx\":{\"p\":\""
        val tail = "\"}}"
        return head + "x".repeat(bytes - 1 - head.length - tail.length) + tail + "\n"
    }

    @Test
    fun `a strip event of our session is reported with its count and reason`() {
        writeLog(lines(inferenceDone(SID), stripped(SID, 10)))

        assertEquals(StripCheck.Stripped(10, setOf("payload_heuristic")), check())
    }

    @Test
    fun `strip events of our session are summed`() {
        writeLog(lines(stripped(SID, 3), stripped(SID, 2, reason = "image_error")))

        assertEquals(StripCheck.Stripped(5, setOf("payload_heuristic", "image_error")), check())
    }

    @Test
    fun `a strip event of another session leaves ours clean`() {
        writeLog(lines(stripped(OTHER, 10), inferenceDone(SID)))

        assertEquals(StripCheck.Clean, check())
    }

    @Test
    fun `our session id inside another session's context does not make the line ours`() {
        writeLog(
            lines(
                """{"sid":"$OTHER","msg":"shell.turn.images_stripped","ctx":{"stripped":4,"parent_session_id":"$SID"}}""",
                inferenceDone(SID),
            ),
        )

        assertEquals(StripCheck.Clean, check())
    }

    @Test
    fun `no entries of our session is blind`() {
        writeLog(lines(inferenceDone(OTHER)))

        assertIs<StripCheck.Blind>(check())
    }

    @Test
    fun `a missing session id is blind`() {
        writeLog(lines(stripped(SID, 10)))

        assertIs<StripCheck.Blind>(check(sessionId = null))
    }

    @Test
    fun `a missing log file is blind and says so`() {
        val result = assertIs<StripCheck.Blind>(check())

        assertTrue(result.reason.contains("not found"), result.reason)
    }

    @Test
    fun `an unterminated last line and a garbage line are skipped`() {
        // Последнюю строку без перевода строки мог дописывать другой запуск — её не читаем.
        writeLog(lines("not json at all", inferenceDone(SID)) + stripped(SID, 10))

        assertEquals(StripCheck.Clean, check())
    }

    /** Review Focus 3. */
    @Test
    fun `a strip without an integer count still counts as one dropped frame`() {
        writeLog(
            lines(
                """{"sid":"$SID","msg":"shell.turn.images_stripped","ctx":{"reason":"payload_heuristic"}}""",
                """{"sid":"$SID","msg":"shell.turn.images_stripped","ctx":{"stripped":"many"}}""",
            ),
        )

        assertEquals(StripCheck.Stripped(2, setOf("payload_heuristic")), check())
    }

    @Test
    fun `a large log is read from its tail`() {
        val filler = inferenceDone(OTHER) + "\n"
        writeLog(filler.repeat(window / filler.length + 100) + lines(stripped(SID, 10)))

        assertEquals(StripCheck.Stripped(10, setOf("payload_heuristic")), check())
    }

    /** Review Focus 1: окно начинается ровно с нашей строки — отбросить её как обрывок значило бы ослепнуть. */
    @Test
    fun `a line starting exactly at the window boundary is kept`() {
        val prefix = lines(inferenceDone(OTHER))
        val ours = lines(stripped(SID, 10))
        writeLog(prefix + ours + paddingLine(window - ours.length))

        assertEquals(window.toLong(), Files.size(log) - prefix.length)
        assertEquals(StripCheck.Stripped(10, setOf("payload_heuristic")), check())
    }

    /**
     * Review Focus 2: заметки о камерах в промпте судьи — кириллица, такой текст может оказаться в
     * логе grok, и граница окна рвёт двухбайтовый символ.
     */
    @Test
    fun `multi-byte text cut by the window boundary does not hide our lines`() {
        val cyrillic = lines("""{"sid":"$OTHER","msg":"title","ctx":{"t":"${"Двор дачи ".repeat(70_000)}"}}""")
        writeLog(cyrillic + lines(stripped(SID, 10)))

        assertTrue(Files.size(log) > window)
        assertEquals(StripCheck.Stripped(10, setOf("payload_heuristic")), check())
    }
}
```

- [ ] **Step 3: Запустить тесты — должны упасть**

Run: `./gradlew :frigate-analyzer-ai-description:test --tests 'ru.zinin.frigate.analyzer.ai.description.grok.GrokImageStripDetectorTest'`
Expected: FAIL на компиляции — `Unresolved reference 'GrokImageStripDetector'` / `'StripCheck'`.

- [ ] **Step 4: Реализовать детектор**

`modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokImageStripDetector.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.grok

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.config.GrokProperties
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

/** Хвост `unified.jsonl`, снятый сразу после выхода процесса `grok`. */
sealed interface CapturedLog {
    /** Только полные строки: обрывки на краях окна отброшены. */
    data class Lines(
        val lines: List<String>,
    ) : CapturedLog

    data class Unavailable(
        val reason: String,
    ) : CapturedLog
}

/** Дошли ли кадры до модели — по записям `grok` о своей сессии. */
sealed interface StripCheck {
    /** Записи сессии есть, выброса среди них нет. */
    data object Clean : StripCheck

    /** `grok` выкинул [images] картинок и повторил запрос без них: ответ получен вслепую. */
    data class Stripped(
        val images: Int,
        val reasons: Set<String>,
    ) : StripCheck

    /** Записей сессии нет — проверить нечем, ответ принимается как раньше. */
    data class Blind(
        val reason: String,
    ) : StripCheck
}

/**
 * Узнаёт, что `grok` выкинул картинки из запроса. grok 1.0.41 на ошибку эндпоинта, которую относит
 * к картинкам (`413 Payload Too Large`, `reason=payload_heuristic`), убирает их, вставляет вместо них
 * текст «The server could not process an image…» и повторяет запрос: процесс выходит с кодом 0, а
 * stdout несёт обычный ответ. Единственный машинный след — строка
 * `"msg":"shell.turn.images_stripped"` с `sid` сессии в `GROK_HOME/logs/unified.jsonl`.
 *
 * Читается хвост файла после запуска, а не смещение, запомненное до него: grok сам урезает
 * `unified.jsonl`, перечитывая и переписывая файл, и смещение после этого указало бы в чужую строку.
 * Записи только что завершившегося запуска — самые свежие и переживают урезание.
 *
 * Файл внутренний, не публичный контракт grok: при смене `GROK_VERSION` формат нужно перепроверить.
 * Пропавший лог или сменившийся формат дают [StripCheck.Blind] и WARN, а не ложный отказ.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class GrokImageStripDetector(
    properties: GrokProperties,
    private val objectMapper: ObjectMapper,
) {
    private val logPath: Path = properties.homePath.resolve(LOG_RELATIVE_PATH)
    private val blindReported = AtomicBoolean(false)

    /**
     * Вызывается сразу после выхода процесса и под тем же `GrokHomeGuard.shared`, что и запуск: иначе
     * ежечасная уборка могла бы удалить файл между выходом и чтением. Блокирующий ввод-вывод —
     * вызывающий уводит его на `Dispatchers.IO`.
     */
    fun capture(): CapturedLog =
        try {
            FileChannel.open(logPath, StandardOpenOption.READ).use { channel ->
                val size = channel.size()
                // Байт перед окном читается тоже: если там '\n', первая строка окна целая и
                // отбрасывать её нельзя; иначе отбрасывается только её обрывок.
                val start = if (size > CAPTURE_TAIL_BYTES) size - CAPTURE_TAIL_BYTES - 1 else 0L
                val text = String(readFrom(channel, start, (size - start).toInt()), Charsets.UTF_8)
                CapturedLog.Lines(completeLines(text, fromFileStart = start == 0L))
            }
        } catch (e: NoSuchFileException) {
            CapturedLog.Unavailable("$logPath not found")
        } catch (e: IOException) {
            CapturedLog.Unavailable("cannot read $logPath: ${e.message}")
        }

    /** Чистая функция: никакого ввода-вывода, только разбор захваченного по [sessionId] из stdout. */
    fun inspect(
        captured: CapturedLog,
        sessionId: String?,
    ): StripCheck {
        if (sessionId.isNullOrBlank()) return StripCheck.Blind("no sessionId in grok output")
        val lines =
            when (captured) {
                is CapturedLog.Unavailable -> return StripCheck.Blind(captured.reason)
                is CapturedLog.Lines -> captured.lines
            }
        var seen = false
        var images = 0
        val reasons = linkedSetOf<String>()
        lines
            .asSequence()
            // Подстрока отсеивает чужие строки до разбора JSON; равенство sid решает окончательно.
            .filter { it.contains(sessionId) }
            .mapNotNull(::parseObject)
            .filter { it.stringField("sid") == sessionId }
            .forEach { entry ->
                seen = true
                if (entry.stringField("msg") == STRIPPED_EVENT) {
                    val ctx = entry["ctx"]
                    // Каждое событие выкидывает картинки, ещё остававшиеся в запросе, поэтому сумма.
                    images += ctx?.get("stripped")?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.intValue() ?: 1
                    ctx?.stringField("reason")?.let(reasons::add)
                }
            }
        return when {
            images > 0 -> StripCheck.Stripped(images, reasons)
            seen -> StripCheck.Clean
            else -> StripCheck.Blind("no entries for session $sessionId in the last $CAPTURE_TAIL_BYTES bytes of $logPath")
        }
    }

    /** WARN при первом вызове за процесс, дальше DEBUG: слепота обычно постоянна, и WARN на каждый вызов был бы шумом. */
    fun reportBlind(reason: String) {
        if (blindReported.compareAndSet(false, true)) {
            logger.warn { "Cannot verify frame delivery for grok runs: $reason; image drops by grok will go unnoticed" }
        } else {
            logger.debug { "Cannot verify frame delivery for grok run: $reason" }
        }
    }

    private fun parseObject(line: String): JsonNode? =
        try {
            objectMapper.readTree(line).takeIf { it.isObject }
        } catch (e: JacksonException) {
            null
        }

    private fun JsonNode.stringField(name: String): String? = this[name]?.takeIf { it.isString }?.stringValue()

    companion object {
        /** Запуск пишет десятки килобайт; в мегабайт помещаются записи нескольких параллельных запусков. */
        const val CAPTURE_TAIL_BYTES: Long = 1024L * 1024L

        const val STRIPPED_EVENT = "shell.turn.images_stripped"

        private const val LOG_RELATIVE_PATH = "logs/unified.jsonl"

        /**
         * Полные строки текста. Последний элемент разбиения — пустая строка после завершающего `\n`
         * или недописанная строка другого запуска: отбрасывается всегда. Первый — обрывок строки,
         * начавшейся до окна, если чтение началось не с начала файла.
         */
        internal fun completeLines(
            text: String,
            fromFileStart: Boolean,
        ): List<String> {
            val parts = text.split('\n').dropLast(1)
            return (if (fromFileStart) parts else parts.drop(1)).filter { it.isNotBlank() }
        }

        private fun readFrom(
            channel: FileChannel,
            start: Long,
            length: Int,
        ): ByteArray {
            val buffer = ByteBuffer.allocate(length)
            var position = start
            while (buffer.hasRemaining()) {
                val read = channel.read(buffer, position)
                if (read < 0) break
                position += read
            }
            return buffer.array().copyOf(buffer.position())
        }
    }
}
```

- [ ] **Step 5: Запустить тесты — должны пройти**

Run: `./gradlew :frigate-analyzer-ai-description:test --tests 'ru.zinin.frigate.analyzer.ai.description.grok.GrokImageStripDetectorTest'`
Expected: PASS, 12 тестов.

- [ ] **Step 6: Прогнать модуль и линт**

Run: `./gradlew :frigate-analyzer-ai-description:test :frigate-analyzer-ai-description:ktlintCheck`
Expected: BUILD SUCCESSFUL (новый `@Component` подхватывается `@ComponentScan("ru.zinin.frigate.analyzer.ai.description")` автоконфигурации; его зависимости `GrokProperties` и `ObjectMapper` в контекстах `AiDescriptionAutoConfigurationTest` есть). Длинные строки, если ktlint их отметит, — через `./gradlew ktlintFormat` или перенос выражения.

- [ ] **Step 7: Commit**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokImageStripDetector.kt \
  modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokUnifiedLogFixtures.kt \
  modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokImageStripDetectorTest.kt
git commit -m "feat(ai-description): detect grok image drops from the tail of unified.jsonl"
```

---

### Task 4: `GrokBackend` отвергает ответ после выброса картинок

**Files:**
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokBackend.kt`
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokBackendFactory.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokBackendTest.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokBackendFactoryTest.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/core/DescriptionPresetCatalogBuilderTest.kt` (`realGrokFactory`)
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/core/PartialSchemaRecoveryTest.kt`
- Create test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokBackendEndToEndTest.kt`

**Interfaces:**
- Consumes: `GrokImageStripDetector.capture()/inspect()/reportBlind()`, `CapturedLog`, `StripCheck` (Task 3); фикстуры `GrokUnifiedLogFixtures` (Task 3).
- Produces: `GrokBackend(..., guard: GrokHomeGuard, stripDetector: GrokImageStripDetector)`; `GrokBackendFactory(..., guard: GrokHomeGuard, stripDetector: GrokImageStripDetector)`.

- [ ] **Step 1: Подготовить `GrokBackendTest` — детектор в хелпере и запись лога**

1) импорты добавить:

```kotlin
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import ru.zinin.frigate.analyzer.ai.description.grok.GrokUnifiedLogFixtures.OTHER
import ru.zinin.frigate.analyzer.ai.description.grok.GrokUnifiedLogFixtures.SID
import ru.zinin.frigate.analyzer.ai.description.grok.GrokUnifiedLogFixtures.inferenceDone
import ru.zinin.frigate.analyzer.ai.description.grok.GrokUnifiedLogFixtures.stripped
import java.nio.file.Files
import java.nio.file.StandardOpenOption
```

2) в `backend(...)` после `guard = GrokHomeGuard(),` добавить:

```kotlin
            stripDetector = GrokImageStripDetector(properties, TestObjectMappers.internalMapper()),
```

3) после функции `result(...)` добавить хелперы:

```kotlin
    /** Что grok дописал бы в `unified.jsonl` за время запуска: фейк-runner зовёт это из `run`. */
    private fun appendToGrokLog(vararg entries: String) {
        val log = tempDir.resolve("home/logs/unified.jsonl")
        Files.createDirectories(log.parent)
        Files.writeString(log, entries.joinToString("") { "$it\n" }, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private fun answerOf(sessionId: String) =
        """{"stopReason":"end_turn","sessionId":"$sessionId","structuredOutput":{"short":"Car","detailed":"A car."}}"""

    private suspend fun warningsDuring(block: suspend () -> Unit): List<String> {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            block()
        } finally {
            root.detachAppender(appender)
            appender.stop()
        }
        return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
    }
```

- [ ] **Step 2: Написать падающие тесты backend-а**

В `GrokBackendTest` перед тестом `identifies itself as grok with a device-code hint` добавить:

```kotlin
    @Test
    fun `an answer produced after grok dropped the frames is rejected`() =
        runTest {
            val backend =
                backend(
                    GrokProcessRunner {
                        appendToGrokLog(inferenceDone(SID), stripped(SID, 1))
                        result(0, answerOf(SID))
                    },
                )

            val e = assertFailsWith<DescriptionException.InvalidResponse> { backend.complete(request, budget) }

            assertTrue(e.message!!.contains("dropped 1 of 1"), e.message)
            assertTrue(e.message!!.contains("max-image-side"), e.message)
            coVerify(exactly = 1) { promptFileWriter.delete(promptFile) }
        }

    @Test
    fun `a run whose session has no strip returns the answer`() =
        runTest {
            val backend =
                backend(
                    GrokProcessRunner {
                        appendToGrokLog(stripped(OTHER, 10), inferenceDone(SID))
                        result(0, answerOf(SID))
                    },
                )

            assertEquals("""{"short":"Car","detailed":"A car."}""", backend.complete(request, budget).primary)
        }

    @Test
    fun `an unverifiable run returns the answer and warns once per process`() =
        runTest {
            val backend = backend(GrokProcessRunner { result(0, answerOf(SID)) })

            val warnings =
                warningsDuring {
                    backend.complete(request, budget)
                    backend.complete(request, budget)
                }

            assertEquals(1, warnings.count { it.startsWith("Cannot verify frame delivery") }, warnings.toString())
        }

    /** Review Focus 4: ответа нет вовсе, но отчёт обязан назвать выброс, а не стоп-причину. */
    @Test
    fun `a strip is reported even when grok returned no answer`() =
        runTest {
            val backend =
                backend(
                    GrokProcessRunner {
                        appendToGrokLog(stripped(SID, 1))
                        result(0, """{"stopReason":"max_tokens","sessionId":"$SID"}""")
                    },
                )

            val e = assertFailsWith<DescriptionException.InvalidResponse> { backend.complete(request, budget) }

            assertTrue(e.message!!.contains("dropped 1 of 1"), e.message)
        }

    @Test
    fun `after a schema retry the run whose answer is used is the one inspected`() =
        runTest {
            val schemaError = """{"type":"error","message":"litellm.BadRequestError: failed to parse grammar"}"""
            val textAnswer = """{"stopReason":"end_turn","sessionId":"$SID","text":"{\"short\":\"a\",\"detailed\":\"b\"}"}"""
            val backend =
                backend(
                    GrokProcessRunner { command ->
                        if (command.argv.contains("--json-schema")) {
                            result(1, schemaError)
                        } else {
                            // Записи сессии появляются только во втором запуске: хвост, снятый после
                            // первого, их бы не содержал, и выброс прошёл бы незамеченным.
                            appendToGrokLog(inferenceDone(SID), stripped(SID, 1))
                            result(0, textAnswer)
                        }
                    },
                )

            assertFailsWith<DescriptionException.InvalidResponse> { backend.complete(request, budget) }
        }

    @Test
    fun `a request without frames is not checked for dropped frames`() =
        runTest {
            val backend =
                backend(
                    GrokProcessRunner {
                        appendToGrokLog(stripped(SID, 1))
                        result(0, answerOf(SID))
                    },
                )

            val response = backend.complete(request.copy(frames = emptyList()), budget)

            assertEquals("""{"short":"Car","detailed":"A car."}""", response.primary)
        }
```

- [ ] **Step 3: Написать падающий сквозной тест**

`modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokBackendEndToEndTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.grok

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.TempFileWriter
import ru.zinin.frigate.analyzer.ai.description.config.GrokProperties
import ru.zinin.frigate.analyzer.ai.description.core.VisionInstructions
import ru.zinin.frigate.analyzer.ai.description.core.VisionRequest
import ru.zinin.frigate.analyzer.ai.description.grok.GrokUnifiedLogFixtures.SID
import ru.zinin.frigate.analyzer.ai.description.grok.GrokUnifiedLogFixtures.stripped
import ru.zinin.frigate.analyzer.ai.description.testsupport.TestObjectMappers
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.UUID
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `GrokBackend` с настоящими runner-ом, командой и детектором: stub-скрипт `grok` пишет строку
 * выброса в `$GROK_HOME/logs/unified.jsonl`, как это делает grok 1.0.41. Тесты на фейках этого не
 * ловят: там путь лога задаёт сам тест, а здесь — env процесса, собранный `GrokCommandBuilder`, и
 * детектор обязан читать тот же каталог.
 */
@EnabledOnOs(OS.LINUX, OS.MAC)
class GrokBackendEndToEndTest {
    @TempDir
    lateinit var tempDir: Path

    private val tempFileWriter =
        object : TempFileWriter {
            override suspend fun createTempFile(
                prefix: String,
                suffix: String,
                content: ByteArray,
            ): Path = Files.createTempFile(tempDir, prefix, suffix).also { Files.write(it, content) }

            override suspend fun deleteFiles(files: List<Path>): Int = files.count { Files.deleteIfExists(it) }
        }

    @Test
    fun `a strip written by the grok process is caught through the real runner and paths`() =
        runBlocking {
            val stripLine = tempDir.resolve("strip-line.jsonl")
            stripLine.writeText(stripped(SID, 1) + "\n")
            val stdout = """{"stopReason":"end_turn","sessionId":"$SID","structuredOutput":{"short":"a","detailed":"b"}}"""
            val binary = tempDir.resolve("grok")
            binary.writeText(
                """
                #!/bin/sh
                mkdir -p "${'$'}GROK_HOME/logs"
                cat '$stripLine' >> "${'$'}GROK_HOME/logs/unified.jsonl"
                printf '%s' '$stdout'
                """.trimIndent() + "\n",
            )
            Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"))
            val properties =
                GrokProperties(
                    cliPath = binary.toString(),
                    model = "byok-vision",
                    effort = "",
                    home = tempDir.resolve("home").toString(),
                    workingDirectory = Files.createDirectories(tempDir.resolve("cwd")).toString(),
                    proxy = GrokProperties.ProxySection("", "", ""),
                )
            val mapper = TestObjectMappers.internalMapper()
            val backend =
                GrokBackend(
                    model = properties.model,
                    effort = properties.effort,
                    authScopeId = "grok:${properties.model}",
                    promptFileWriter = GrokPromptFileWriter(tempFileWriter, mapper),
                    commandBuilder = GrokCommandBuilder(properties),
                    runner = DefaultGrokProcessRunner(tempFileWriter),
                    outputParser = GrokOutputParser(mapper),
                    exceptionMapper = GrokExceptionMapper(),
                    guard = GrokHomeGuard(),
                    stripDetector = GrokImageStripDetector(properties, mapper),
                )
            val request =
                VisionRequest(
                    UUID.randomUUID(),
                    listOf(DescriptionRequest.FrameImage(0, byteArrayOf(1, 2, 3))),
                    VisionInstructions("sys", "pre", "epi", null),
                )

            val e =
                assertFailsWith<DescriptionException.InvalidResponse> {
                    backend.complete(request, Duration.ofSeconds(30))
                }

            assertTrue(e.message!!.contains("dropped 1 of 1"), e.message)
        }
}
```

- [ ] **Step 4: Запустить тесты — должны упасть**

Run: `./gradlew :frigate-analyzer-ai-description:test --tests 'ru.zinin.frigate.analyzer.ai.description.grok.GrokBackendTest' --tests 'ru.zinin.frigate.analyzer.ai.description.grok.GrokBackendEndToEndTest'`
Expected: FAIL на компиляции — `No parameter with name 'stripDetector' found`.

- [ ] **Step 5: Встроить детектор в `GrokBackend`**

Заменить `GrokBackend.kt` целиком:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.grok

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.core.VisionBackend
import ru.zinin.frigate.analyzer.ai.description.core.VisionRequest
import ru.zinin.frigate.analyzer.ai.description.core.VisionResponse
import java.nio.file.Path
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Одна попытка через headless Grok Build: `prompt.json` → процесс → сырой текст модели.
 * Семафор, таймауты, повторы и разбор ответа живут в `VisionCallExecutor`; отмена корутины
 * по таймауту убивает процесс в runner-е, а prompt-файл удаляется в `finally` под NonCancellable.
 *
 * Не бин: экземпляр создаёт [GrokBackendFactory] на каждый grok-пресет, поэтому [model], [effort]
 * и [authScopeId] приходят из пресета, а осмотр окружения остаётся в фабрике — один раз на провайдер.
 *
 * После каждого запуска [stripDetector] проверяет, дошли ли кадры до модели: grok молча выкидывает
 * картинки, если эндпоинт их отверг, и такой ответ отвергается как `InvalidResponse`.
 */
class GrokBackend(
    val model: String,
    val effort: String,
    override val authScopeId: String,
    private val promptFileWriter: GrokPromptFileWriter,
    private val commandBuilder: GrokCommandBuilder,
    private val runner: GrokProcessRunner,
    private val outputParser: GrokOutputParser,
    private val exceptionMapper: GrokExceptionMapper,
    private val guard: GrokHomeGuard,
    private val stripDetector: GrokImageStripDetector,
) : VisionBackend {
    override val providerId: String = PROVIDER_ID
    override val authRecoveryHint: String = AUTH_RECOVERY_HINT

    /**
     * Схема поддержана, пока эндпоинт не доказал обратное. Первый отказ по `response_format`/grammar
     * переводит на текстовый JSON до конца жизни процесса. Поле экземпляра, то есть флаг живёт на
     * пресет — ровно правильная область: модель зафиксирована пресетом, и второй пресет с другой
     * моделью не должен наследовать чужой отказ.
     */
    @Volatile
    private var schemaSupported: Boolean = true

    /**
     * [timeout] не используется: у процесса grok нет собственного таймаута, его снимает отмена
     * корутины из `withTimeout` executor-а, и runner убивает процесс.
     */
    override suspend fun complete(
        request: VisionRequest,
        timeout: Duration,
    ): VisionResponse {
        var promptFile: Path? = null
        try {
            val file = promptFileWriter.write(request)
            promptFile = file
            val schema = request.instructions.jsonSchema
            val useSchema = schemaSupported && schema != null
            val systemPrompt = "${request.instructions.systemPrompt} $TOOL_RULE"
            logger.debug {
                "Grok request ${request.requestId}: model=$model, effort=${effortForLog()}, " +
                    "json-schema=${if (useSchema) "on" else "off"}, frames=${request.frames.size}"
            }
            var run = runGrok(file, useSchema, schema, systemPrompt)
            var errorMessage = outputParser.errorMessage(run.result.stdout)
            if (errorMessage != null && useSchema && exceptionMapper.isStructuredOutputUnsupported(errorMessage)) {
                logger.warn { "Model $model does not accept --json-schema ($errorMessage); retrying without it" }
                schemaSupported = false
                run = runGrok(file, structuredOutput = false, schema, systemPrompt)
                errorMessage = outputParser.errorMessage(run.result.stdout)
            }
            val result = run.result
            if (errorMessage != null) throw exceptionMapper.fromFailure(result.exitCode, errorMessage, result.stderrTail)
            if (result.exitCode != 0) throw exceptionMapper.fromFailure(result.exitCode, null, result.stderrTail)
            val output = outputParser.parse(result.stdout)
            // Проверяется запуск, чей ответ используется: при повторе без схемы это второй.
            val strip = if (request.frames.isEmpty()) StripCheck.Clean else stripDetector.inspect(run.log, output.sessionId)
            logger.debug {
                "Grok call ${request.requestId}: model=$model, effort=${effortForLog()}, " +
                    "primary=${if (output.fromText) "text" else "structuredOutput"}, " +
                    "fallback=${if (output.fallback != null) "text" else "none"}, ${output.usageSummary}, " +
                    "stopReason=${output.stopReason}, session=${output.sessionId}, strip=${strip.logName()}"
            }
            when (strip) {
                is StripCheck.Stripped -> throw rejectStripped(request, output, strip)
                is StripCheck.Blind -> stripDetector.reportBlind(strip.reason)
                StripCheck.Clean -> Unit
            }
            val payload =
                output.payload?.takeUnless { it.isBlank() }
                    ?: throw exceptionMapper.fromStopReason(output.stopReason)
            return VisionResponse(payload, output.fallback?.takeUnless { it.isBlank() })
        } finally {
            promptFile?.let { promptFileWriter.delete(it) }
        }
    }

    /**
     * Ответ получен после того, как grok выкинул картинки: модель их не видела. Смысл тот же, что у
     * гейта Claude («ответил, не прочитав кадр»), поэтому и тип тот же — `InvalidResponse`, который
     * executor повторяет один раз. Фрагмент ответа уходит в лог: по нему отличают вежливое «кадры
     * недоступны» от выдумки. Подсказка в тексте общая — id пресета backend не знает.
     */
    private fun rejectStripped(
        request: VisionRequest,
        output: GrokOutput,
        strip: StripCheck.Stripped,
    ): DescriptionException.InvalidResponse {
        val frames = request.frames.size
        val reasons = strip.reasons.joinToString().ifEmpty { "unknown" }
        logger.warn {
            "Grok dropped ${strip.images} of $frames frame(s) for ${request.requestId} " +
                "(model=$model, reason=$reasons); rejecting the answer: " +
                (output.payload?.take(ANSWER_LOG_MAX) ?: "<no answer>")
        }
        return DescriptionException.InvalidResponse(
            detail =
                "grok dropped ${strip.images} of $frames frame(s) before the model call (reason=$reasons); " +
                    "if the endpoint limits request size, set max-image-side on the preset",
        )
    }

    private suspend fun runGrok(
        promptFile: Path,
        structuredOutput: Boolean,
        jsonSchema: String?,
        systemPrompt: String,
    ): GrokRun {
        val command = commandBuilder.build(promptFile, model, effort, structuredOutput, jsonSchema, systemPrompt)
        // Хвост лога снимается под тем же shared, что и запуск: иначе ежечасная уборка могла бы
        // удалить unified.jsonl между выходом процесса и чтением.
        return guard.shared {
            val result = runner.run(command)
            GrokRun(result, withContext(Dispatchers.IO) { stripDetector.capture() })
        }
    }

    private fun effortForLog(): String = effort.ifBlank { "<none>" }

    private fun StripCheck.logName(): String =
        when (this) {
            StripCheck.Clean -> "clean"
            is StripCheck.Stripped -> "stripped"
            is StripCheck.Blind -> "blind"
        }

    /** Результат процесса и хвост `unified.jsonl`, снятый, пока запуск ещё держит `GrokHomeGuard.shared`. */
    private data class GrokRun(
        val result: GrokProcessResult,
        val log: CapturedLog,
    )

    companion object {
        const val PROVIDER_ID = "grok"

        /**
         * Кадры Grok получает image-блоками в prompt-файле, читать ему нечего: `read_file` и так
         * снят `--disallowed-tools`. Запрет живёт здесь, потому что общий текст задачи его больше
         * не несёт — Claude там же требует обратного, вызова Read.
         */
        const val TOOL_RULE = "Do not call tools."

        const val AUTH_RECOVERY_HINT =
            "grok login --device-code (in Docker: docker compose exec frigate-analyzer grok login --device-code)"

        /** Сколько символов отвергнутого ответа уходит в лог — столько же, сколько у гейта Claude. */
        private const val ANSWER_LOG_MAX = 300
    }
}
```

- [ ] **Step 6: Передавать детектор из фабрики**

В `GrokBackendFactory.kt`:

1) в конструкторе после `private val guard: GrokHomeGuard,` добавить:

```kotlin
    private val stripDetector: GrokImageStripDetector,
```

2) в `create(...)` после `guard = guard,` добавить:

```kotlin
            stripDetector = stripDetector,
```

- [ ] **Step 7: Обновить остальные конструкторы в тестах**

1) `GrokBackendFactoryTest.factory(...)` — после `guard = mockk(relaxed = true),` добавить `stripDetector = mockk(relaxed = true),`.
2) `DescriptionPresetCatalogBuilderTest.realGrokFactory()` — после `guard = mockk(relaxed = true),` добавить `stripDetector = mockk(relaxed = true),`.
3) `PartialSchemaRecoveryTest.executorOver(...)` — после `guard = GrokHomeGuard(),` добавить `stripDetector = GrokImageStripDetector(properties, mapper),` (реальный детектор: его `home` указывает в несуществующий каталог, проверка вернёт `Blind`, и цепочка идёт как раньше).

Relaxed-мок детектора годится только там, где `complete()` не вызывается: его `inspect()` вернул бы мок `StripCheck`, не совпадающий ни с одной веткой `when`, — `NoWhenBranchMatchedException`.

- [ ] **Step 8: Запустить тесты — должны пройти**

Run: `./gradlew :frigate-analyzer-ai-description:test --tests 'ru.zinin.frigate.analyzer.ai.description.grok.GrokBackendTest' --tests 'ru.zinin.frigate.analyzer.ai.description.grok.GrokBackendEndToEndTest' --tests 'ru.zinin.frigate.analyzer.ai.description.grok.GrokBackendFactoryTest' --tests 'ru.zinin.frigate.analyzer.ai.description.core.DescriptionPresetCatalogBuilderTest' --tests 'ru.zinin.frigate.analyzer.ai.description.core.PartialSchemaRecoveryTest'`
Expected: PASS — и новые тесты, и старые (`success returns normalized structured output…`, `missing structured output with max_tokens is InvalidResponse` и прочие идут через `Blind`, потому что их runner лог не пишет).

- [ ] **Step 9: Прогнать модуль и линт**

Run: `./gradlew :frigate-analyzer-ai-description:test :frigate-analyzer-ai-description:ktlintCheck`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Commit**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokBackend.kt \
  modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokBackendFactory.kt \
  modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokBackendTest.kt \
  modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokBackendEndToEndTest.kt \
  modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokBackendFactoryTest.kt \
  modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/core/DescriptionPresetCatalogBuilderTest.kt \
  modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/core/PartialSchemaRecoveryTest.kt
git commit -m "feat(ai-description): reject a grok answer produced after the frames were dropped"
```

---

### Task 5: Документация

**Files:**
- Modify: `README.md`
- Modify: `.claude/rules/ai-description.md`
- Modify: `.claude/rules/configuration.md`
- Modify: `docker/deploy/application-docker.yaml.example`
- Modify: `docker/deploy/.env.example`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: имена и тексты из Tasks 1–4 (`max-image-side`, `effectiveMaxSide`, `GrokImageStripDetector`, `shell.turn.images_stripped`, `Cannot verify frame delivery…`, `strip=clean|stripped|blind`).
- Produces: —

- [ ] **Step 1: `README.md` — строка таблицы описаний**

В таблице раздела `### AI description (optional)` после строки `APP_AI_DESCRIPTION_TIMEOUT` вставить:

```markdown
| `APP_AI_DESCRIPTION_MAX_IMAGE_SIDE` | `0` | Longest frame side before the model call; `0` = camera resolution. A preset may declare a stricter cap of its own — see "Frame size per preset" below |
```

- [ ] **Step 2: `README.md` — абзац о потолке пресета**

После абзаца, который заканчивается на ``— `/ai` shows the model that will actually be used.``, вставить:

````markdown
**Frame size per preset.** Some endpoints limit the request size or the image resolution; others do
not. A preset may carry its own `max-image-side`, and frames sent through it are downscaled to the
stricter of that value and the feature's own setting — `APP_AI_DESCRIPTION_MAX_IMAGE_SIDE` for
descriptions, `APP_AI_JUDGE_MAX_IMAGE_SIDE` for the judge — where `0` means no cap:

```yaml
        byok-vision: { provider: grok, model: my-vision, effort: low, max-image-side: 1568 }
```

A preset without the key keeps the feature's setting, so a model with no such limit still receives
frames at the full resolution the feature allows. The value appears in the startup catalog line —
`byok-vision (grok/my-vision/low, max-image-side=1568)`.
````

- [ ] **Step 3: `README.md` — абзац BYOK**

В конец абзаца **Custom models (BYOK).** (после ``naming it `GROK_MY_GATEWAY_KEY` works without the list.``) дописать:

```markdown
If the endpoint rejects the images — a gateway answering `413 Payload Too Large`, for example —
`grok` drops them, repeats the request without them and returns an answer about frames the model
never saw. The application reads that from `grok-home/logs/unified.jsonl` and rejects the answer:
the notification shows "⚠ Описание недоступно", the judge sends it unjudged, and the log WARN
suggests `max-image-side` on the preset.
```

- [ ] **Step 4: `.claude/rules/ai-description.md` — таблица слоёв**

1) в строке `DescriptionPreset` заменить ``(`id`, `provider`, `model`, `effectiveModel`, `effort`, `authScopeId`, `unavailableReason`, `slowEffort`)`` на ``(`id`, `provider`, `model`, `effectiveModel`, `effort`, `authScopeId`, `unavailableReason`, `slowEffort`, `maxImageSide`)``;
2) в строке `FrameDownscaler` заменить ``Optional resize to `max-image-side` (ImageIO, bilinear, JPEG q0.85)`` на ``Optional resize to the stricter of the feature's and the preset's `max-image-side` (`effectiveMaxSide`; ImageIO, bilinear, JPEG q0.85)``;
3) после строки `GrokHomeGuard`, `GrokHomeSweeper` вставить:

```markdown
| Grok | `GrokImageStripDetector` | `grok/` | Reads the last 1 MiB of `GROK_HOME/logs/unified.jsonl` after each run; a `shell.turn.images_stripped` entry of the run's `sessionId` makes `GrokBackend` reject the answer as `InvalidResponse` |
```

- [ ] **Step 5: `.claude/rules/ai-description.md` — Declaration**

В абзаце **Declaration.**:
1) заменить ``is a map `id → {provider, model, effort}`;`` на ``is a map `id → {provider, model, effort, max-image-side}`;``;
2) после предложения ``a non-empty `effort` on a claude preset fails startup.`` вставить:

```markdown
`max-image-side` is optional for any provider, `0` (no cap) or `256..8192`: frames of calls through
the preset are downscaled to the stricter of the non-zero values of the preset and the calling
feature (`common.max-image-side` for descriptions, `judge.max-image-side` for the judge). A preset
states the limit of its model or gateway, so it can tighten the feature's value but never lift it;
`logSignature()` appends `, max-image-side=N` when it is set.
```

- [ ] **Step 6: `.claude/rules/ai-description.md` — Dropped images и GROK_HOME hygiene**

1) перед абзацем **GROK_HOME hygiene.** вставить:

```markdown
**Dropped images.** When an endpoint answers a request carrying images with an error grok attributes
to the images (`413 Payload Too Large` from nginx in front of vLLM, `reason=payload_heuristic`), grok
1.0.41 removes the images, puts "The server could not process an image, so it was left out of this
request." in their place and repeats the request. The process exits 0 with an ordinary answer —
typically a valid JSON saying the frames are unavailable — and the only machine-readable trace is a
line in `GROK_HOME/logs/unified.jsonl`: `"sid":"<sessionId>","msg":"shell.turn.images_stripped",
"ctx":{"stripped":N,"reason":…}`. `GrokImageStripDetector.capture()` reads the last 1 MiB of that
file right after the process exits, inside the same `GrokHomeGuard.shared` as the run, so the hourly
sweep cannot delete it in between. The tail, not an offset remembered before the run: grok trims the
file itself by rewriting it, which would leave an old offset pointing into a foreign line, while the
newest entries — the run's own — survive the trim. `inspect()` keeps the lines whose `sid` equals the
`sessionId` from stdout: a strip event → `Stripped`, and `GrokBackend` logs a WARN with the first 300
characters of the answer and throws `InvalidResponse` (the executor retries it once, like any
unusable answer); lines of the session without the event → `Clean`; no lines of the session at all →
`Blind` — a WARN once per process and the answer is accepted as before, because a missing log or a
changed format must not turn into refusals. Any strip rejects the answer, partial ones included: the
count is exact, and the inserted notice ends up paraphrased in the description. The DEBUG line
`Grok call …` carries `strip=clean|stripped|blind`. The file is grok-internal: re-check the event
name and fields when `ARG GROK_VERSION` changes, as with `grok inspect`.
```

2) в конец абзаца **GROK_HOME hygiene.** дописать:

```markdown
`logs/unified.jsonl` is also read after every run by `GrokImageStripDetector`; the hourly deletion
is harmless — grok recreates the file, and the read happens under the same shared lock as the run.
```

- [ ] **Step 7: `.claude/rules/ai-description.md` — Testing**

В конец раздела `## Testing` дописать:

```markdown
`GrokImageStripDetectorTest` pins the tail read (the window boundary, multi-byte text cut by it, an
unterminated last line) and the three outcomes on fixture lines captured from grok 1.0.41
(`GrokUnifiedLogFixtures` — refresh them when `ARG GROK_VERSION` changes); `GrokBackendEndToEndTest`
(POSIX) runs a stub `grok` through the real runner and checks that the detector reads the same
`GROK_HOME` the process got. `VisionCallExecutorTest` pins the stricter-cap rule and that a cap on
one preset never reaches calls resolved to another.
```

- [ ] **Step 8: `.claude/rules/configuration.md`**

1) в конец ячейки описания строки `APP_AI_DESCRIPTION_MAX_IMAGE_SIDE` (перед закрывающим ` |`) дописать: `` A preset may declare its own `max-image-side`; calls through it use the stricter of the two non-zero values, so this can stay `0` while one BYOK preset is capped.``
2) в конец ячейки описания строки `APP_AI_JUDGE_MAX_IMAGE_SIDE` дописать: `` A preset's own `max-image-side` can only tighten it: the judge uses the stricter non-zero value.``

- [ ] **Step 9: `docker/deploy/application-docker.yaml.example`**

После строки `  #       claude-opus: { provider: claude, model: opus }` вставить:

```yaml
  #       # BYOK-модель за шлюзом с лимитом на размер запроса: кадры этого пресета уменьшаются до
  #       # меньшего из max-image-side пресета и настройки фичи. Без ключа пресет шлёт кадры как фича.
  #       byok-vision: { provider: grok,   model: my-vision, effort: low, max-image-side: 1568 }
```

- [ ] **Step 10: `docker/deploy/.env.example`**

После строки `# own limit (1568 px for the LiteLLM one), after which the model reports an unavailable frame.` вставить:

```text
# A preset can carry its own max-image-side in application-docker.yaml; calls through it use the
# stricter of the two, so this can stay 0 while one BYOK preset is capped.
```

- [ ] **Step 11: `CLAUDE.md`**

В строке `- **Description presets:** yaml declares named presets (provider + model + effort), one backend per preset; …` заменить `(provider + model + effort)` на `(provider + model + effort + optional max-image-side)`.

- [ ] **Step 12: Проверить отсутствие расхождений и закоммитить**

Run: `git diff --stat` и перечитать изменённые абзацы: имена (`max-image-side`, `GrokImageStripDetector`, `shell.turn.images_stripped`, `effectiveMaxSide`, `strip=clean|stripped|blind`) совпадают с кодом Tasks 1–4.

```bash
git add README.md .claude/rules/ai-description.md .claude/rules/configuration.md \
  docker/deploy/application-docker.yaml.example docker/deploy/.env.example CLAUDE.md
git commit -m "docs(ai-description): document the per-preset frame cap and grok image-drop detection"
```

---

### Task 6: Ревью ветки, полная сборка, ручная проверка

**Files:** — (только исправления по замечаниям ревью)

**Interfaces:** —

- [ ] **Step 1: Ревью ветки**

По `CLAUDE.md` проекта: сначала агент `superpowers:code-reviewer` на весь diff ветки против `master` (`git diff master...HEAD`) со ссылкой на спеку. Критичные и важные замечания исправить (тест + код), коммит на каждое исправление; повторять ревью, пока критичных не останется.

- [ ] **Step 2: Полная сборка**

Run (через `claude-forge:build-runner`): `./gradlew build`
Expected: BUILD SUCCESSFUL. На ошибки ktlint — `./gradlew ktlintFormat`, коммит форматирования, повтор сборки.

- [ ] **Step 3: Ручная проверка на живой модели — только с явного согласия владельца**

Проверка тратит несколько вызовов BYOK-модели за шлюзом с лимитом (замер 2026-09-25: GLM-5.3-Flash через LANIT, около 50 тыс. входных токенов). Без согласия — пропустить и сказать об этом в отчёте.

1) Подготовить отдельный `GROK_HOME` с одним BYOK-блоком (ключ не печатать, каталог потом удалить):

```bash
S=$(mktemp -d) && mkdir -p "$S/home" && python3 - "$S/home/config.toml" <<'EOF'
import re, sys, os
raw = open(os.path.expanduser('~/.grok/config.toml')).read()
start = raw.index('[model.glm-5-3-flash]\n')
m = re.compile(r'^\[model\.(?!glm-5-3-flash[\].])', re.M).search(raw, start + 1)
open(sys.argv[1], 'w').write(raw[start:m.start() if m else len(raw)])
os.chmod(sys.argv[1], 0o600)
EOF
echo "$S"
```

2) Создать **временный, не коммитить** тест `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokLiveFrameDeliveryCheck.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.grok

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationEventPublisher
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionPreset
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.TempFileWriter
import ru.zinin.frigate.analyzer.ai.description.config.GrokProperties
import ru.zinin.frigate.analyzer.ai.description.core.ActivePresetResolver
import ru.zinin.frigate.analyzer.ai.description.core.DescriptionPresetCatalog
import ru.zinin.frigate.analyzer.ai.description.core.DescriptionTask
import ru.zinin.frigate.analyzer.ai.description.core.InMemoryDescriptionRuntimeSettings
import ru.zinin.frigate.analyzer.ai.description.core.ProviderAuthTracker
import ru.zinin.frigate.analyzer.ai.description.core.VisionCallExecutor
import ru.zinin.frigate.analyzer.ai.description.core.VisionLimits
import ru.zinin.frigate.analyzer.ai.description.core.VisionRequest
import ru.zinin.frigate.analyzer.ai.description.testsupport.TestObjectMappers
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** ВРЕМЕННАЯ ручная проверка: не коммитить, удалить после запуска. */
@EnabledIfEnvironmentVariable(named = "GROK_LIVE_HOME", matches = ".+")
class GrokLiveFrameDeliveryCheck {
    @TempDir
    lateinit var tempDir: Path

    private val tempFileWriter =
        object : TempFileWriter {
            override suspend fun createTempFile(
                prefix: String,
                suffix: String,
                content: ByteArray,
            ): Path = Files.createTempFile(tempDir, prefix, suffix).also { Files.write(it, content) }

            override suspend fun deleteFiles(files: List<Path>): Int = files.count { Files.deleteIfExists(it) }
        }

    @Test
    fun `full frames are rejected and capped frames are described`() =
        runBlocking {
            val model = System.getenv("GROK_LIVE_MODEL")
            val frame = Files.readAllBytes(Path.of(System.getenv("GROK_LIVE_FRAME")))
            val properties =
                GrokProperties(
                    cliPath = "",
                    model = model,
                    effort = "low",
                    home = System.getenv("GROK_LIVE_HOME"),
                    workingDirectory = Files.createDirectories(tempDir.resolve("cwd")).toString(),
                    proxy = GrokProperties.ProxySection("", "", ""),
                )
            val mapper = TestObjectMappers.internalMapper()
            val backend =
                GrokBackend(
                    model = model,
                    effort = "low",
                    authScopeId = "grok:$model",
                    promptFileWriter = GrokPromptFileWriter(tempFileWriter, mapper),
                    commandBuilder = GrokCommandBuilder(properties),
                    runner = DefaultGrokProcessRunner(tempFileWriter),
                    outputParser = GrokOutputParser(mapper),
                    exceptionMapper = GrokExceptionMapper(),
                    guard = GrokHomeGuard(),
                    stripDetector = GrokImageStripDetector(properties, mapper),
                )
            val frames = (0 until 10).map { DescriptionRequest.FrameImage(it, frame) }
            val instructions = DescriptionTask.instructions(DescriptionRequest(UUID.randomUUID(), frames, "ru", 200, 1500))
            val request = VisionRequest(UUID.randomUUID(), frames, instructions)

            // 1. Без потолка: шлюз отвечает 413, grok выкидывает кадры, backend отвергает ответ.
            val rejected = assertFailsWith<DescriptionException.InvalidResponse> { backend.complete(request, Duration.ofMinutes(3)) }
            println("REJECTED: ${rejected.message}")

            // 2. Потолок 1568 на пресете: executor уменьшает кадры, ответ проходит.
            val preset =
                DescriptionPreset(
                    id = "capped",
                    provider = "grok",
                    model = model,
                    effectiveModel = model,
                    effort = "low",
                    authScopeId = "grok:$model",
                    unavailableReason = null,
                    maxImageSide = 1568,
                )
            val catalog = DescriptionPresetCatalog(listOf(DescriptionPresetCatalog.Entry(preset, backend)), fallbackId = "capped")
            val executor =
                VisionCallExecutor(
                    resolver = ActivePresetResolver(catalog, InMemoryDescriptionRuntimeSettings(), fallbackId = "capped", label = "live"),
                    authTracker = ProviderAuthTracker(ApplicationEventPublisher { }),
                    limits = VisionLimits(Duration.ofSeconds(30), Duration.ofMinutes(3), 1, 0),
                    label = "live",
                )
            println("DESCRIBED: " + executor.execute(request) { it }.value)
        }
}
```

3) Run (через `claude-forge:build-runner`, с переменными окружения): `GROK_LIVE_HOME=<S>/home GROK_LIVE_MODEL=glm-5-3-flash GROK_LIVE_FRAME=tmp/photo_2026-05-16_17-01-06.jpg ./gradlew :frigate-analyzer-ai-description:test --tests 'ru.zinin.frigate.analyzer.ai.description.grok.GrokLiveFrameDeliveryCheck' -i`
Expected: PASS; в выводе `REJECTED: … grok dropped 10 of 10 frame(s) … (reason=payload_heuristic) …` и `DESCRIBED: {"short": …}` с описанием кадра.

4) Удалить временный тест и каталог: `rm modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokLiveFrameDeliveryCheck.kt && rm -rf "$S"`. Убедиться, что `git status` его не показывает.

- [ ] **Step 4: Перед созданием PR**

По глобальному `CLAUDE.md` владельца документы superpowers не попадают в diff PR:

```bash
git rm docs/superpowers/specs/2026-09-25-frame-delivery-design.md docs/superpowers/plans/2026-09-25-frame-delivery.md
git commit -m "docs: drop the superpowers documents before the PR"
```

Сами документы остаются в истории ветки.
