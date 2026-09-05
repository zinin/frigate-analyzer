# LLM Notification Judge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Поставить между трекером и отправкой в Telegram третью ступень — LLM-судью на отдельном быстром пресете, который по кадрам с рамками и контексту из базы решает `PUBLISH`/`SUPPRESS`, гасит дубли snooze-ом, пишет каждый вердикт в `notification_verdicts` и показывает их владельцу в `/status`, `/verdicts` и `/ai`.

**Architecture:** Провайдерский SPI модуля `ai-description` становится задаче-нейтральным (`VisionBackend.complete(VisionRequest): String`), над ним живут две задачи — описания и судья — через общий `VisionCallExecutor` (семафор, таймауты, retry, auth-tracker) с собственными лимитами и резолверами пресетов. В `core` появляется `NotificationJudgeService`: per-camera очередь, snooze, сборка контекста (`JudgeContextBuilder`), вызов `JudgeAgent`, запись вердикта и fail-open отправка через существующий `TelegramNotificationService`. Поток описаний (плейсхолдер → правка Opus) не меняется.

**Tech Stack:** Kotlin 2.4.10, Spring Boot 4.1.0, Java 25, kotlinx-coroutines, R2DBC/PostgreSQL (`DatabaseClient` для агрегатных запросов), Liquibase, Jackson 3 (`tools.jackson`), ktgbotapi 36.1.0, MockK, kotlin-test JUnit5, AssertJ, Testcontainers (compose), ktlint.

**Spec:** `docs/superpowers/specs/2026-09-05-llm-notification-judge-design.md`

## Global Constraints

- Все команды Gradle (`./gradlew …`) запускаются **только** через агента `claude-forge:build-runner`, никогда напрямую в основной сессии (правило `CLAUDE.md`). На ошибки ktlint: `./gradlew ktlintFormat`, затем повтор.
- Тесты одного модуля: `./gradlew :frigate-analyzer-ai-description:test`, `:frigate-analyzer-service:test`, `:frigate-analyzer-core:test`, `:frigate-analyzer-telegram:test`. Один класс: `--tests <FQCN>`. Интеграционные тесты `core` (наследники `IntegrationTestBase`) поднимают Postgres через `docker/test-compose.yml` — нужен работающий Docker.
- После создания или изменения файла обязательно `git add <file>` (правило `CLAUDE.md`).
- Каждое сообщение коммита заканчивается отдельным `-m` со строкой `Claude-Session: <SESSION_URL>`. **`<SESSION_URL>` — URL сессии исполнителя**, не автора плана.
- Ветка: `feature/llm-notification-judge`. Файлы `docs/superpowers/**` перед PR удаляются из индекса отдельным коммитом (правило владельца), в PR-диффе их быть не должно.
- Конструкторы `@ConfigurationProperties`-классов вызываются только с именованными аргументами; новые параметры добавляются в конец списка со значениями по умолчанию.
- Идентификаторы провайдеров: `claude`, `grok`. Id пресета: `[a-z0-9][a-z0-9-]{0,31}`. Каталог пресетов один на описания и судью, ключ yaml `application.ai.description.presets` не меняется.
- Ключи `app_settings`: `ai.judge.preset.active` (строка), `ai.judge.enabled` (boolean, отсутствует = `true`). Существующие `ai.description.*` не меняются.
- Callback-данные `/ai`: префикс `aip:`, новые глаголы `aip:j:on`, `aip:j:off`, `aip:j:set:<id>`. Значения явные, никогда toggle. Длина payload ≤ 64 байт.
- Значения i18n не содержат апострофов (MessageFormat); каждый новый ключ добавляется **в оба** бандла `modules/telegram/src/main/resources/messages_{ru,en}.properties` (`MessageKeyParityTest` проверяет паритет).
- Секреты (токены, `auth.json`) не логируются, не попадают в `error` вердикта и в Telegram.
- Fail-open везде: любой сбой судьи (модель, лимит, контекст, запись вердикта) не теряет уведомление. `CancellationException` всегда пробрасывается.
- Хранение вердиктов вечное: чистки `notification_verdicts` нет.
- Kotlin allopen через `kotlin-spring` применён ко всем модулям: `@Bean`-методы в `@AutoConfiguration` не требуют `open`.
- **Task 1 и Task 2 — одна единица деплоя.** После Task 1 всё собирается и тесты проходят, но описания идут через переходный `DefaultDescriptionAgent`; выкатывать между ними нельзя.

---

## Структура файлов

### Модуль `ai-description` (`modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/`)

| Файл | Ответственность |
|---|---|
| `core/VisionBackend.kt` (create; заменяет `DescriptionBackend.kt`) | SPI провайдера: одна попытка, сырой текст |
| `core/VisionRequest.kt` (create) | `VisionRequest` + `VisionInstructions` |
| `core/VisionBackendFactory.kt` (create; заменяет `DescriptionBackendFactory.kt`) | Фабрика backend-ов на пресет |
| `core/DescriptionTask.kt` (create) | Тексты и схема задачи описаний |
| `core/DescriptionResponseParser.kt` (create; заменяет `claude/ClaudeResponseParser.kt`) | Провайдер-нейтральный разбор `short`/`detailed` |
| `core/VisionCallExecutor.kt` (create) | Семафор, таймауты, retry, downscale, auth-tracker — общий для двух задач |
| `core/VisionLimits.kt` (create) | Параметры executor-а |
| `core/DefaultDescriptionAgent.kt` (modify) | Тонкая обёртка: инструкции → executor → разбор |
| `core/ActivePresetResolver.kt` (modify) | `PresetChoiceSource`, явный `fallbackId`, метка задачи в логах |
| `core/DescriptionPresetResolver.kt`, `core/JudgePresetResolver.kt` (create) | Адаптеры резолвера под `ActiveDescriptionPreset` / `ActiveJudgePreset` |
| `core/InMemoryJudgeRuntimeSettings.kt` (create) | In-memory дефолт SPI судьи |
| `core/JudgeTask.kt` (create) | Инструкции судьи и JSON-схема |
| `core/JudgeResponseParser.kt` (create) | Разбор и валидация вердикта |
| `core/DefaultJudgeAgent.kt` (create) | `JudgeAgent` над executor-ом |
| `api/PresetChoiceSource.kt` (create) | Общая часть `DescriptionRuntimeSettings` и `JudgeRuntimeSettings` |
| `api/JudgeAgent.kt`, `api/JudgeRequest.kt`, `api/JudgeVerdict.kt`, `api/JudgeOutcome.kt`, `api/JudgeRuntimeSettings.kt`, `api/ActiveJudgePreset.kt` (create) | Публичный контракт судьи |
| `ratelimit/SlidingWindowRateLimiter.kt` (create), `ratelimit/DescriptionRateLimiter.kt` (modify), `ratelimit/JudgeRateLimiter.kt` (create) | Два независимых лимита |
| `config/JudgeProperties.kt` (create) | `application.ai.judge.*` |
| `config/AiDescriptionAutoConfiguration.kt` (modify) | Executor-ы, резолверы, бины судьи (`JudgeBeans`) |
| `config/JudgeAgentSanityChecker.kt` (create) | WARN, если судья включён, а агента нет |
| `claude/ClaudeBackend.kt`, `claude/ClaudePromptBuilder.kt`, `claude/ClaudeImageStager.kt`, `claude/ClaudeInvoker.kt`, `claude/DefaultClaudeInvoker.kt`, `claude/ClaudeAsyncClientFactory.kt`, `claude/ClaudeBackendFactory.kt` (modify) | Claude под `VisionRequest`, системный промпт через `appendSystemPrompt` |
| `grok/GrokBackend.kt`, `grok/GrokPromptFileWriter.kt`, `grok/GrokCommandBuilder.kt`, `grok/GrokOutputParser.kt`, `grok/GrokBackendFactory.kt` (modify); `grok/GrokPromptBuilder.kt` (delete) | Grok под `VisionRequest`, схема и системный промпт из запроса |

### Модуль `model` (`modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/`)

| Файл | Ответственность |
|---|---|
| `persistent/NotificationVerdictEntity.kt` (create) | Сущность `notification_verdicts` |
| `dto/VerdictEnums.kt` (create) | `VerdictStage`, `VerdictDecision`, `VerdictReason` |
| `dto/NewNotificationVerdict.kt` (create) | Данные для записи вердикта |
| `dto/JudgeStats.kt` (create) | `StaticScore`, `VerdictCountRow` |
| `response/StatusResponse.kt` (modify) | `JudgeSection`, `JudgeCounters`, `CameraSnoozeDto` |

### Модуль `service` (`modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/`)

| Файл | Ответственность |
|---|---|
| `repository/NotificationVerdictRepository.kt` (create) | CRUD + выборки истории и последних вердиктов |
| `repository/JudgeStatsRepository.kt` (create) | `DatabaseClient`: static score, записи в окне, счётчики |
| `NotificationVerdictService.kt`, `impl/NotificationVerdictServiceImpl.kt` (create) | Запись и чтение вердиктов |
| `AppSettingKeys.kt` (modify) | Ключи `ai.judge.*` |

### Модуль `core` (`modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/`)

| Файл | Ответственность |
|---|---|
| `judge/JudgeCandidate.kt` (create) | DTO кандидата |
| `judge/SnoozeRegistry.kt` (create) | Snooze по камерам, чистая логика |
| `judge/JudgeContext.kt` (create) | Data-классы блоков контекста |
| `judge/JudgeContextBuilder.kt` (create) | Сборка JSON с поблочной деградацией |
| `judge/JudgeZoneResolver.kt` (create) | env → зона владельца → JVM |
| `judge/NotificationJudgeService.kt` (create) | Оркестрация пяти шагов |
| `judge/JudgeCoroutineScope.kt` (create) | Scope с `@PreDestroy` |
| `judge/AiJudgeGuard.kt` (create) | Fail-fast при судье без описаний |
| `application/AppSettingsJudgeRuntimeSettings.kt` (create) | `JudgeRuntimeSettings` над `app_settings` |
| `facade/RecordingProcessingFacade.kt` (modify) | Передача кандидата судье |
| `service/StatusService.kt` (modify) | Секция `judge` |
| `bot/handler/VerdictsCommandHandler.kt`, `bot/handler/VerdictsMessageFormatter.kt` (create) | `/verdicts` |
| `src/main/resources/application.yaml` (modify) | Секция `application.ai.judge`, лимит описаний 30 |

### Модуль `telegram` (`modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/`)

| Файл | Ответственность |
|---|---|
| `dto/AiSettingsViewState.kt`, `bot/handler/aisettings/AiSettingsViewStateFactory.kt`, `AiSettingsMessageRenderer.kt`, `AiSettingsCallbackHandler.kt`, `AiSettingsCallbacks.kt` (modify) | Секция судьи в `/ai` |
| `service/impl/StatusMessageFormatter.kt` (modify) | Блок «⚖️ Judge» |
| `src/main/resources/messages_ru.properties`, `messages_en.properties` (modify) | Новые ключи |

### Прочее

| Файл | Ответственность |
|---|---|
| `docker/liquibase/migration/1.0.6.xml` (create), `master_frigate_analyzer.xml` (modify) | Таблица `notification_verdicts` |
| `docker/deploy/.env.example`, `docker/deploy/application-docker.yaml.example` (modify) | Переменные судьи, заметки камер |
| `.claude/rules/ai-description.md`, `configuration.md`, `database.md`, `pipeline.md`, `telegram.md`, `CLAUDE.md`, `README.md` (modify) | Документация |

---

## Ключевые контракты (единые для всех задач)

```kotlin
// ai-description, core/VisionRequest.kt
data class VisionInstructions(
    val systemPrompt: String,
    val preamble: String,
    val epilogue: String,
    val jsonSchema: String?,
)

data class VisionRequest(
    val requestId: UUID,
    val frames: List<DescriptionRequest.FrameImage>,
    val instructions: VisionInstructions,
)

// ai-description, core/VisionBackend.kt
interface VisionBackend {
    val providerId: String
    val authScopeId: String
    val authRecoveryHint: String
    suspend fun complete(request: VisionRequest): String
}

// ai-description, core/VisionCallExecutor.kt
data class VisionLimits(val queueTimeout: Duration, val timeout: Duration, val maxConcurrent: Int, val maxImageSide: Int)
data class VisionOutcome<T>(val value: T, val preset: DescriptionPreset, val elapsed: kotlin.time.Duration)
class VisionCallExecutor(resolver: ActivePresetResolver, authTracker: ProviderAuthTracker, limits: VisionLimits, label: String, timeSource: TimeSource = TimeSource.Monotonic) {
    suspend fun <T> execute(request: VisionRequest, parse: (String) -> T): VisionOutcome<T>
}

// ai-description, api
interface JudgeAgent { suspend fun judge(request: JudgeRequest): JudgeOutcome }
data class JudgeRequest(val recordingId: UUID, val camId: String, val frames: List<DescriptionRequest.FrameImage>, val contextJson: String, val language: String, val maxSnoozeMinutes: Int)
data class JudgeVerdict(val decision: Decision, val reason: Reason, val confidence: Double?, val summary: String, val snoozeMinutes: Int, val wanted: String)
data class JudgeOutcome(val verdict: JudgeVerdict, val presetId: String, val model: String, val latency: java.time.Duration)

// model, dto/VerdictEnums.kt
enum class VerdictStage { JUDGE, SNOOZE, FAILOVER, BYPASS }
enum class VerdictDecision { PUBLISH, SUPPRESS }
enum class VerdictReason { NEW_EVENT, CHANGED_SITUATION, FALSE_POSITIVE, STATIC_OBJECT, DUPLICATE, SNOOZED, JUDGE_OFF, TIMEOUT, RATE_LIMITED, UNAUTHORIZED, INVALID_RESPONSE, TRANSPORT, CONTEXT_ERROR }
```

---
### Task 1: Задаче-нейтральный SPI `VisionBackend` для Claude и Grok

**Files:**
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/core/VisionRequest.kt`
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/core/VisionBackend.kt`
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/core/VisionBackendFactory.kt`
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/core/DescriptionTask.kt`
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/core/DescriptionResponseParser.kt`
- Delete: `core/DescriptionBackend.kt`, `core/DescriptionBackendFactory.kt`, `claude/ClaudeResponseParser.kt`, `grok/GrokPromptBuilder.kt` (и их тесты `ClaudeResponseParserTest`, `GrokPromptBuilderTest`)
- Modify: `claude/ClaudeBackend.kt`, `claude/ClaudePromptBuilder.kt`, `claude/ClaudeImageStager.kt`, `claude/ClaudeInvoker.kt`, `claude/DefaultClaudeInvoker.kt`, `claude/ClaudeAsyncClientFactory.kt`, `claude/ClaudeBackendFactory.kt`
- Modify: `grok/GrokBackend.kt`, `grok/GrokPromptFileWriter.kt`, `grok/GrokCommandBuilder.kt`, `grok/GrokOutputParser.kt`, `grok/GrokBackendFactory.kt`
- Modify: `core/DefaultDescriptionAgent.kt`, `core/DescriptionPresetCatalog.kt`, `core/DescriptionPresetCatalogBuilder.kt`, `config/AiDescriptionAutoConfiguration.kt`
- Test (create): `core/DescriptionTaskTest.kt`, `core/DescriptionResponseParserTest.kt` (перенос `ClaudeResponseParserTest`)
- Test (modify): `claude/ClaudePromptBuilderTest.kt`, `claude/ClaudeBackendTest.kt`, `claude/ClaudeAsyncClientFactoryTest.kt`, `claude/ClaudeBackendFactoryTest.kt`, `claude/ClaudeBackendIntegrationTest.kt`, `grok/GrokBackendTest.kt`, `grok/GrokPromptFileWriterTest.kt`, `grok/GrokCommandBuilderTest.kt`, `grok/GrokOutputParserTest.kt`, `grok/GrokBackendFactoryTest.kt`, `core/DefaultDescriptionAgentTest.kt`, `core/ActivePresetResolverTest.kt`, `core/DescriptionPresetCatalogBuilderTest.kt`, `config/AiDescriptionAutoConfigurationTest.kt`

**Interfaces:**
- Consumes: существующие `DescriptionRequest`, `DescriptionResult`, `DescriptionException`, `TempFileWriter`, `JsonBlockExtractor`, `ResultNormalizer`, `LanguageNames`.
- Produces: `VisionInstructions`, `VisionRequest`, `VisionBackend`, `VisionBackendFactory` (тот же контракт, что был у `DescriptionBackendFactory`, но `create` возвращает `VisionBackend`), `DescriptionTask.instructions(DescriptionRequest): VisionInstructions`, `DescriptionTask.SYSTEM_PROMPT`, `DescriptionTask.JSON_SCHEMA`, `DescriptionResponseParser.parse(raw, shortMaxLength, detailedMaxLength): DescriptionResult`, `ClaudeInvoker.invoke(prompt, model, systemPrompt)`, `GrokCommandBuilder.build(promptFile, model, effort, structuredOutput, jsonSchema, systemPrompt)`, `GrokOutput.payload`.

- [x] **Step 1: Написать падающие тесты новых юнитов**

`modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/core/DescriptionTaskTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.core

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DescriptionTaskTest {
    private fun request(language: String = "en") =
        DescriptionRequest(
            recordingId = UUID.randomUUID(),
            frames = listOf(DescriptionRequest.FrameImage(0, ByteArray(1))),
            language = language,
            shortMaxLength = 150,
            detailedMaxLength = 800,
        )

    @Test
    fun `preamble names the language`() {
        assertTrue(DescriptionTask.instructions(request("ru")).preamble.contains("Write both descriptions in Russian."))
        assertTrue(DescriptionTask.instructions(request("en")).preamble.contains("Write both descriptions in English."))
    }

    @Test
    fun `epilogue carries the JSON shape and the numeric limits`() {
        val epilogue = DescriptionTask.instructions(request()).epilogue
        assertTrue(epilogue.contains("""{"short": "...", "detailed": "..."}"""))
        assertTrue(epilogue.contains("must not exceed 150 characters"))
        assertTrue(epilogue.contains("must not exceed 800 characters"))
    }

    @Test
    fun `system prompt and schema are the fixed description ones`() {
        val instructions = DescriptionTask.instructions(request())
        assertEquals(DescriptionTask.SYSTEM_PROMPT, instructions.systemPrompt)
        assertEquals(DescriptionTask.JSON_SCHEMA, instructions.jsonSchema)
    }

    @Test
    fun `rejects unknown language code`() {
        assertFailsWith<IllegalStateException> { DescriptionTask.instructions(request("de")) }
    }
}
```

`core/DescriptionResponseParserTest.kt` — перенести `claude/ClaudeResponseParserTest.kt` дословно, заменив класс на `DescriptionResponseParser(TestObjectMappers.internalMapper())` и пакет на `core`. Все двенадцать существующих проверок (валидный JSON, не-JSON, пропущенные ключи, объект в поле, число приводится к тексту, пустые значения, JSON в прозе, обрезка с многоточием, несколько блоков) остаются.

`claude/ClaudePromptBuilderTest.kt` заменить на:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.claude

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.core.VisionInstructions
import ru.zinin.frigate.analyzer.ai.description.core.VisionRequest
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClaudePromptBuilderTest {
    private val builder = ClaudePromptBuilder()
    private val instructions = VisionInstructions(systemPrompt = "sys", preamble = "PREAMBLE\n", epilogue = "EPILOGUE\n", jsonSchema = null)

    private fun request(frames: List<DescriptionRequest.FrameImage>) = VisionRequest(UUID.randomUUID(), frames, instructions)

    private val paths = listOf(Path.of("/tmp/a/frame-0.jpg"), Path.of("/tmp/a/frame-1.jpg"))

    @Test
    fun `assembles preamble, frame references and epilogue in that order`() {
        val prompt =
            builder.build(request(listOf(DescriptionRequest.FrameImage(0, ByteArray(1)), DescriptionRequest.FrameImage(1, ByteArray(1)))), paths)
        val expected =
            "PREAMBLE\n\nFrames (in chronological order):\n- Frame 0: @/tmp/a/frame-0.jpg\n- Frame 1: @/tmp/a/frame-1.jpg\n\nEPILOGUE"
        assertEquals(expected, prompt)
    }

    @Test
    fun `sorts unordered frames by frameIndex before zipping with the staged paths`() {
        val prompt =
            builder.build(request(listOf(DescriptionRequest.FrameImage(1, ByteArray(1)), DescriptionRequest.FrameImage(0, ByteArray(1)))), paths)
        assertTrue(prompt.indexOf("Frame 0: @/tmp/a/frame-0.jpg") < prompt.indexOf("Frame 1: @/tmp/a/frame-1.jpg"))
    }

    @Test
    fun `path count must match frame count`() {
        assertFailsWith<IllegalArgumentException> {
            builder.build(request(listOf(DescriptionRequest.FrameImage(0, ByteArray(1)))), paths)
        }
    }
}
```

`grok/GrokPromptFileWriterTest.kt` — заменить тест `blocks are intro, label+image per frame in frameIndex order, rules` на:

```kotlin
    private val instructions = VisionInstructions(systemPrompt = "sys", preamble = "INTRO", epilogue = "RULES", jsonSchema = null)
    private val request =
        VisionRequest(
            requestId = recordingId,
            frames = listOf(DescriptionRequest.FrameImage(2, byteArrayOf(1, 2)), DescriptionRequest.FrameImage(0, byteArrayOf(3, 4))),
            instructions = instructions,
        )

    @Test
    fun `blocks are preamble with frames header, label+image per frame in frameIndex order, epilogue`() {
        val blocks = writer.buildBlocks(request)
        assertEquals(6, blocks.size)
        assertEquals(mapOf("type" to "text", "text" to "INTRO\n\nFrames (in chronological order):"), blocks[0])
        assertEquals(mapOf("type" to "text", "text" to "Frame 0:"), blocks[1])
        assertEquals("image", blocks[2]["type"])
        assertEquals("image/jpeg", blocks[2]["mimeType"])
        assertEquals(Base64.getEncoder().encodeToString(byteArrayOf(3, 4)), blocks[2]["data"])
        assertEquals(mapOf("type" to "text", "text" to "Frame 2:"), blocks[3])
        assertEquals(Base64.getEncoder().encodeToString(byteArrayOf(1, 2)), blocks[4]["data"])
        assertEquals(mapOf("type" to "text", "text" to "RULES"), blocks[5])
    }
```

Остальные тесты файла (`write stores a json file…`, `delete …`) остаются, только `request` теперь `VisionRequest`. Конструктор writer-а: `GrokPromptFileWriter(tempWriter, mapper)` — без `GrokPromptBuilder`.

`grok/GrokCommandBuilderTest.kt` — в вызовах `build(...)` добавить именованные аргументы `jsonSchema = SCHEMA, systemPrompt = "SYS"`; тест `json schema requires exactly short and detailed` переименовать в `json schema flag carries the schema from the call` и проверять, что argv содержит пару `--json-schema`, `SCHEMA`; добавить тест `null schema drops the json-schema flag even when structured output is on`; в проверке argv `--system-prompt-override` идёт `"SYS"`, а не константа.

- [x] **Step 2: Запустить тесты и убедиться, что они не компилируются**

Run: `./gradlew :frigate-analyzer-ai-description:test --tests '*DescriptionTaskTest' --tests '*ClaudePromptBuilderTest'` (через build-runner)
Expected: FAIL — `Unresolved reference: VisionInstructions`, `DescriptionTask`, `DescriptionResponseParser`.

- [x] **Step 3: Создать SPI и задачу описаний**

`core/VisionRequest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.core

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import java.util.UUID

/**
 * Инструкции одной vision-задачи. Провайдер вставляет кадры между [preamble] и [epilogue] своим
 * способом (Claude — ссылками `@path`, Grok — inline-блоками) и ничего о задаче не знает.
 */
data class VisionInstructions(
    val systemPrompt: String,
    val preamble: String,
    val epilogue: String,
    /** JSON Schema ответа для провайдеров со structured output; null = только текстом в epilogue. */
    val jsonSchema: String?,
)

data class VisionRequest(
    /** Id записи: имена временных файлов и строки логов. */
    val requestId: UUID,
    val frames: List<DescriptionRequest.FrameImage>,
    val instructions: VisionInstructions,
)
```

`core/VisionBackend.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.core

/**
 * SPI провайдера: одна попытка без семафора, таймаутов и повторов — всё это делает
 * [VisionCallExecutor]. Возвращает сырой текст модели; разбор — дело задачи. Реализация обязана
 * бросать только `DescriptionException` или `CancellationException`; остальное executor оборачивает
 * в `Transport`.
 */
interface VisionBackend {
    /** `claude`, `grok`. */
    val providerId: String

    /** Область учётных данных: `claude`, `grok:<model>`. Ключ состояния в [ProviderAuthTracker]. */
    val authScopeId: String

    /** Команда, которой владелец чинит авторизацию. */
    val authRecoveryHint: String

    suspend fun complete(request: VisionRequest): String
}
```

`core/VisionBackendFactory.kt` — содержимое `DescriptionBackendFactory.kt` с переименованием интерфейса и `fun create(preset: DescriptionProperties.Preset): VisionBackend`; вложенный `Availability` без изменений.

`core/DescriptionTask.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.core

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest

/** Тексты задачи описаний. Единственное место, где живут формулировки для обоих провайдеров. */
object DescriptionTask {
    const val SYSTEM_PROMPT =
        "You describe frames from a security camera for a notification message. " +
            "Answer only with the requested JSON object. Do not call tools and do not ask questions."

    const val JSON_SCHEMA =
        """{"type":"object","properties":{"short":{"type":"string"},"detailed":{"type":"string"}},"required":["short","detailed"],"additionalProperties":false}"""

    fun instructions(request: DescriptionRequest): VisionInstructions {
        val languageName = LanguageNames.of(request.language)
        val preamble =
            buildString {
                appendLine("You are analyzing surveillance camera frames captured during an object detection event.")
                append("Write both descriptions in $languageName.")
            }
        val epilogue =
            buildString {
                appendLine("Return ONLY this JSON object (no prose around it):")
                appendLine("""{"short": "...", "detailed": "..."}""")
                appendLine()
                appendLine("Rules:")
                appendLine("- \"short\" must not exceed ${request.shortMaxLength} characters.")
                appendLine("- \"detailed\" must not exceed ${request.detailedMaxLength} characters.")
                append("- No markdown, no explanations — just the JSON object.")
            }
        return VisionInstructions(SYSTEM_PROMPT, preamble, epilogue, JSON_SCHEMA)
    }
}
```

`core/DescriptionResponseParser.kt` — тело `ClaudeResponseParser` без изменений (JsonBlockExtractor → readTree → `scalarOrNull` → `ResultNormalizer.normalize`), класс переименован, пакет `core`, аннотации `@Component` и `@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")` сохранены. Удалить `claude/ClaudeResponseParser.kt`.

- [x] **Step 4: Перевести Claude на `VisionRequest`**

`claude/ClaudeInvoker.kt`:

```kotlin
fun interface ClaudeInvoker {
    suspend fun invoke(
        prompt: String,
        model: String,
        systemPrompt: String,
    ): String
}
```

`claude/ClaudeAsyncClientFactory.kt`: `create(workTimeout, model, systemPrompt: String)` и `buildOptions(workTimeout, model, systemPrompt)`; в `buildOptions` после `.env(buildEnvMap())`:

```kotlin
        if (systemPrompt.isNotBlank()) {
            // append, а не replace: замена системного промпта CLI меняет обработку @-ссылок на кадры,
            // а нам нужно лишь добавить правило «только JSON, без инструментов».
            optionsBuilder.appendSystemPrompt(systemPrompt)
        }
```

`claude/DefaultClaudeInvoker.kt`: сигнатура `invoke(prompt, model, systemPrompt)`, `clientFactory.create(workTimeout, model, systemPrompt)`.

`claude/ClaudeImageStager.kt`: `stage(request: VisionRequest)`, префикс файла `"claude-${request.requestId}-frame-${frame.frameIndex}"`, лог `request.requestId`.

`claude/ClaudePromptBuilder.kt`:

```kotlin
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class ClaudePromptBuilder {
    fun build(
        request: VisionRequest,
        framePaths: List<Path>,
    ): String {
        require(framePaths.size == request.frames.size) {
            "framePaths size (${framePaths.size}) must match request.frames size (${request.frames.size})"
        }
        val sortedPairs = request.frames.sortedBy { it.frameIndex }.zip(framePaths)
        return buildString {
            appendLine(request.instructions.preamble.trimEnd())
            appendLine()
            appendLine("Frames (in chronological order):")
            sortedPairs.forEach { (frame, path) ->
                appendLine("- Frame ${frame.frameIndex}: @${path.toAbsolutePath().normalize()}")
            }
            appendLine()
            append(request.instructions.epilogue.trimEnd())
        }
    }
}
```

`claude/ClaudeBackend.kt` — реализует `VisionBackend`, без `responseParser`:

```kotlin
class ClaudeBackend(
    val model: String,
    override val authScopeId: String,
    private val promptBuilder: ClaudePromptBuilder,
    private val imageStager: ClaudeImageStager,
    private val invoker: ClaudeInvoker,
    private val exceptionMapper: ClaudeExceptionMapper,
) : VisionBackend {
    override val providerId: String = "claude"
    override val authRecoveryHint: String = AUTH_RECOVERY_HINT

    override suspend fun complete(request: VisionRequest): String {
        val stagedPaths = imageStager.stage(request)
        try {
            val prompt = promptBuilder.build(request, stagedPaths)
            return try {
                invoker.invoke(prompt, model, request.instructions.systemPrompt)
            } catch (e: Throwable) {
                throw exceptionMapper.map(e)
            }
        } finally {
            imageStager.cleanup(stagedPaths)
        }
    }

    companion object {
        const val AUTH_RECOVERY_HINT =
            "set CLAUDE_CODE_OAUTH_TOKEN from `claude setup-token` (or ANTHROPIC_AUTH_TOKEN) and restart"
    }
}
```

`claude/ClaudeBackendFactory.kt`: реализует `VisionBackendFactory`, убрать `responseParser` из конструктора и из `create`.

- [x] **Step 5: Перевести Grok на `VisionRequest`**

`grok/GrokOutputParser.kt`: поля `short`/`detailed` заменяются на `payload`:

```kotlin
data class GrokOutput(
    val stopReason: String?,
    val sessionId: String?,
    /** JSON structured output целиком или текст ответа; null = модель ничего не вернула. */
    val payload: String?,
    val usageSummary: String,
    val fromText: Boolean = false,
)

    fun parse(stdout: String): GrokOutput {
        val node =
            readObject(stdout)
                ?: throw DescriptionException.InvalidResponse(detail = "stdout is not a JSON object: ${stdout.take(200)}")
        val structured = node["structuredOutput"]?.takeIf { it.isObject }
        val text = node["text"]?.textOrNull()?.takeUnless { it.isBlank() }
        return GrokOutput(
            stopReason = node["stopReason"]?.textOrNull(),
            sessionId = node["sessionId"]?.textOrNull(),
            payload = structured?.let { objectMapper.writeValueAsString(it) } ?: text,
            usageSummary = usageSummary(node),
            fromText = structured == null && text != null,
        )
    }
```

`grok/GrokCommandBuilder.kt`: `build(promptFile, model, effort, structuredOutput: Boolean, jsonSchema: String?, systemPrompt: String)`; `--json-schema` добавляется только при `structuredOutput && jsonSchema != null`; `--system-prompt-override` получает `systemPrompt` (константа `JSON_SCHEMA` и ссылка на `GrokPromptBuilder.SYSTEM_PROMPT` удаляются).

`grok/GrokPromptFileWriter.kt`: конструктор `(tempFileWriter, objectMapper)`, `write(request: VisionRequest)`, `buildBlocks(request: VisionRequest)`:

```kotlin
    internal fun buildBlocks(request: VisionRequest): List<Map<String, String>> {
        val encoder = Base64.getEncoder()
        return buildList {
            add(text(request.instructions.preamble.trimEnd() + "\n\nFrames (in chronological order):"))
            request.frames.sortedBy { it.frameIndex }.forEach { frame ->
                add(text("Frame ${frame.frameIndex}:"))
                add(mapOf("type" to "image", "mimeType" to "image/jpeg", "data" to encoder.encodeToString(frame.bytes)))
            }
            add(text(request.instructions.epilogue.trimEnd()))
        }
    }
```

Имя временного файла `"grok-${request.requestId}"`. Удалить `grok/GrokPromptBuilder.kt` и `GrokPromptBuilderTest.kt`.

`grok/GrokBackend.kt` — реализует `VisionBackend`:

```kotlin
    override suspend fun complete(request: VisionRequest): String {
        var promptFile: Path? = null
        try {
            val file = promptFileWriter.write(request)
            promptFile = file
            val schema = request.instructions.jsonSchema
            val useSchema = schemaSupported && schema != null
            logger.debug {
                "Grok request ${request.requestId}: model=$model, effort=${effortForLog()}, " +
                    "json-schema=${if (useSchema) "on" else "off"}, frames=${request.frames.size}"
            }
            var result = runGrok(file, useSchema, schema, request.instructions.systemPrompt)
            var errorMessage = outputParser.errorMessage(result.stdout)
            if (errorMessage != null && useSchema && exceptionMapper.isStructuredOutputUnsupported(errorMessage)) {
                logger.warn { "Model $model does not accept --json-schema ($errorMessage); retrying without it" }
                schemaSupported = false
                result = runGrok(file, structuredOutput = false, schema, request.instructions.systemPrompt)
                errorMessage = outputParser.errorMessage(result.stdout)
            }
            if (errorMessage != null) throw exceptionMapper.fromFailure(result.exitCode, errorMessage, result.stderrTail)
            if (result.exitCode != 0) throw exceptionMapper.fromFailure(result.exitCode, null, result.stderrTail)
            val output = outputParser.parse(result.stdout)
            logger.debug {
                "Grok call ${request.requestId}: model=$model, effort=${effortForLog()}, " +
                    "payload=${if (output.fromText) "text" else "structuredOutput"}, ${output.usageSummary}, " +
                    "stopReason=${output.stopReason}, session=${output.sessionId}"
            }
            return output.payload?.takeUnless { it.isBlank() } ?: throw exceptionMapper.fromStopReason(output.stopReason)
        } finally {
            promptFile?.let { promptFileWriter.delete(it) }
        }
    }

    private suspend fun runGrok(
        promptFile: Path,
        structuredOutput: Boolean,
        jsonSchema: String?,
        systemPrompt: String,
    ): GrokProcessResult {
        val command = commandBuilder.build(promptFile, model, effort, structuredOutput, jsonSchema, systemPrompt)
        return guard.shared { runner.run(command) }
    }
```

`grok/GrokBackendFactory.kt`: реализует `VisionBackendFactory`.

- [x] **Step 6: Перевести каталог и агент на новый SPI**

`core/DescriptionPresetCatalog.kt`: `Entry(view, backend: VisionBackend?)`. `core/DescriptionPresetCatalogBuilder.kt`: `factories: List<VisionBackendFactory>`, типы `VisionBackendFactory.Availability`. `config/AiDescriptionAutoConfiguration.kt`: `factories: ObjectProvider<VisionBackendFactory>`; бин `descriptionAgent` получает `parser: DescriptionResponseParser`.

`core/DefaultDescriptionAgent.kt`: конструктор `(resolver, authTracker, descriptionProperties, parser: DescriptionResponseParser, timeSource = TimeSource.Monotonic)`; тип backend-а `VisionBackend`; метод `attempt`:

```kotlin
    private suspend fun attempt(
        backend: VisionBackend,
        request: DescriptionRequest,
    ): DescriptionResult =
        try {
            val raw = backend.complete(VisionRequest(request.recordingId, request.frames, DescriptionTask.instructions(request)))
            parser.parse(raw, request.shortMaxLength, request.detailedMaxLength)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DescriptionException) {
            throw e
        } catch (e: Throwable) {
            throw DescriptionException.Transport(e)
        }
```

- [x] **Step 7: Починить существующие тесты**

- `DefaultDescriptionAgentTest`: `FakeBackend : VisionBackend` с `complete(request) = handler(request)`, где handler возвращает строку `"""{"short":"s","detailed":"d"}"""`; в `build(...)` добавить `parser = DescriptionResponseParser(TestObjectMappers.internalMapper())`. Тест retry на `InvalidResponse` теперь имитируется backend-ом, возвращающим `"not json"` первым ответом.
- `ActivePresetResolverTest`, `DescriptionPresetCatalogBuilderTest`: анонимный backend реализует `VisionBackend` с `complete(...) = id`.
- `ClaudeBackendTest`: конструктор без `responseParser`; `ClaudeInvoker { _, _, _ -> ... }`; проверка результата — строка `"""{"short": "s", "detailed": "d"}"""` вместо `DescriptionResult`; тест на `InvalidResponse` из parse удаляется (парсер теперь снаружи, покрыт `DescriptionResponseParserTest`); `promptBuilder.build(any(), any())` остаётся.
- `ClaudeAsyncClientFactoryTest`: `buildOptions(timeout, model, systemPrompt = "")`; добавить тест `non-blank system prompt is appended`: `buildOptions(..., "SYS")` — у `CLIOptions` проверить поле `appendSystemPrompt` равно `"SYS"` (getter в SDK: `getAppendSystemPrompt()`; если геттер называется иначе, взять имя из `javap -cp <claude-code-sdk jar> org.springaicommunity.claude.agent.sdk.transport.CLIOptions`).
- `ClaudeBackendFactoryTest`, `GrokBackendFactoryTest`: тип фабрики `VisionBackendFactory`, `create(...)` возвращает `VisionBackend`.
- `ClaudeBackendIntegrationTest`: вызов `complete(VisionRequest(..., DescriptionTask.instructions(request)))` и разбор `DescriptionResponseParser`.
- `GrokBackendTest`: `request` — `VisionRequest` с `DescriptionTask.instructions(...)`; ожидания результата — строка payload (structured output сериализован как JSON); тест `missing structured output with max_tokens is InvalidResponse` остаётся (payload null → `fromStopReason`); `GrokPromptFileWriter` мок без `GrokPromptBuilder`.
- `GrokOutputParserTest`: проверять `payload` — для structured output это JSON-строка с `short`/`detailed`, для текста — сам текст; `fromText` как раньше.
- `AiDescriptionAutoConfigurationTest`: типы `ClaudeBackend`/`GrokBackend` остаются; добавить проверку, что бин `DescriptionResponseParser` есть при `enabled=true`.

- [x] **Step 8: Прогнать тесты модуля**

Run: `./gradlew :frigate-analyzer-ai-description:test` (через build-runner)
Expected: PASS.

- [x] **Step 9: Прогнать зависимые модули**

Run: `./gradlew :frigate-analyzer-core:test :frigate-analyzer-telegram:test` (через build-runner)
Expected: PASS — публичный API `DescriptionAgent`/`DescriptionRequest`/`DescriptionResult` не менялся.

- [x] **Step 10: Commit**

```bash
git add modules/ai-description docs/superpowers/plans/2026-09-05-llm-notification-judge.md
git commit -m "refactor(ai-description): task-neutral VisionBackend SPI for Claude and Grok" -m "Claude-Session: <SESSION_URL>"
```

---
### Task 2: `VisionCallExecutor`, резолверы с явным fallback и два лимита

**Files:**
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/core/VisionLimits.kt`
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/core/VisionCallExecutor.kt`
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/api/PresetChoiceSource.kt`
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/core/DescriptionPresetResolver.kt`
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/ratelimit/SlidingWindowRateLimiter.kt`
- Modify: `core/DefaultDescriptionAgent.kt`, `core/ActivePresetResolver.kt`, `api/DescriptionRuntimeSettings.kt`, `ratelimit/DescriptionRateLimiter.kt`, `config/AiDescriptionAutoConfiguration.kt`, `modules/core/src/main/resources/application.yaml` (лимит описаний 30)
- Test (create): `core/VisionCallExecutorTest.kt` (перенос `DefaultDescriptionAgentTest`), `ratelimit/SlidingWindowRateLimiterTest.kt` (перенос `DescriptionRateLimiterTest`)
- Test (modify): `core/DefaultDescriptionAgentTest.kt` (остаётся два теста на обёртку), `core/ActivePresetResolverTest.kt`, `config/AiDescriptionAutoConfigurationTest.kt`, `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/facade/RecordingProcessingFacadeTest.kt` (если конструирует `DescriptionProperties.RateLimit` — дефолт изменился), `modules/telegram/src/test/kotlin/.../aisettings/AiSettingsViewStateFactoryTest.kt` (тип `ActiveDescriptionPreset` не менялся — проверить компиляцию)

**Interfaces:**
- Consumes: `VisionBackend`, `VisionRequest`, `DescriptionTask`, `DescriptionResponseParser` из Task 1; `ProviderAuthTracker.onSuccess(scope, hint)` / `onUnauthorized(scope, e, hint)`; `FrameDownscaler.downscale(bytes, maxSide)`.
- Produces: `VisionLimits`, `VisionOutcome<T>`, `VisionCallExecutor.execute(request, parse): VisionOutcome<T>`, `PresetChoiceSource`, `ActivePresetResolver(catalog, source, fallbackId, label)` с `resolve()/storedId()/effective()`, `DescriptionPresetResolver : ActiveDescriptionPreset`, `SlidingWindowRateLimiter(name, rateLimit, clock).tryAcquire()`, `DescriptionRateLimiter : SlidingWindowRateLimiter`.

- [ ] **Step 1: Написать падающий тест executor-а**

`core/VisionCallExecutorTest.kt` — перенести `DefaultDescriptionAgentTest` целиком под новый класс. Каркас:

```kotlin
class VisionCallExecutorTest {
    private val limits = VisionLimits(queueTimeout = Duration.ofSeconds(30), timeout = Duration.ofSeconds(60), maxConcurrent = 2, maxImageSide = 0)
    private val instructions = VisionInstructions("sys", "pre", "epi", null)
    private val request = VisionRequest(UUID.randomUUID(), listOf(DescriptionRequest.FrameImage(0, ByteArray(1))), instructions)
    private val events = mutableListOf<Any>()
    private val publisher = ApplicationEventPublisher { event -> events.add(event) }

    private class FakeBackend(private val handler: suspend (VisionRequest) -> String) : VisionBackend {
        override val providerId = "fake"
        override val authScopeId = "fake:model"
        override val authRecoveryHint = "run fake-login"
        val calls = AtomicInteger()

        override suspend fun complete(request: VisionRequest): String {
            calls.incrementAndGet()
            return handler(request)
        }
    }

    private fun build(
        backend: FakeBackend,
        customLimits: VisionLimits = limits,
        timeSource: TimeSource = TimeSource.Monotonic,
        eventPublisher: ApplicationEventPublisher = publisher,
        extraPresets: List<Pair<String, VisionBackend>> = emptyList(),
        settings: PresetChoiceSource = InMemoryDescriptionRuntimeSettings(),
    ) = VisionCallExecutor(
        resolver = ActivePresetResolver(catalogOf("test" to backend, *extraPresets.toTypedArray()), settings, fallbackId = "test", label = "test"),
        authTracker = ProviderAuthTracker(eventPublisher),
        limits = customLimits,
        label = "test",
        timeSource = timeSource,
    )

    private suspend fun VisionCallExecutor.call(request: VisionRequest = this@VisionCallExecutorTest.request): String =
        execute(request) { raw -> if (raw == "invalid") throw DescriptionException.InvalidResponse(detail = "test") else raw }.value

    @Test
    fun `happy path returns the parsed value with the preset and the elapsed time`() =
        runTest {
            val backend = FakeBackend { "ok" }
            val outcome = build(backend).execute(request) { it.uppercase() }
            assertEquals("OK", outcome.value)
            assertEquals("test", outcome.preset.id)
            assertEquals(1, backend.calls.get())
        }

    @Test
    fun `InvalidResponse thrown by the parser is retried once`() =
        runTest {
            var first = true
            val backend = FakeBackend { if (first) { first = false; "invalid" } else "ok" }
            assertEquals("ok", build(backend).call())
            assertEquals(2, backend.calls.get())
        }
    // далее — все существующие сценарии DefaultDescriptionAgentTest: транспортный retry через 5 с,
    // исчерпание бюджета, Timeout/RateLimited/Unauthorized без повтора, семафор на два вызова,
    // queueTimeout, резолюция один раз на вызов, downscale при maxImageSide>0, события auth-трекера.
}
```

`catalogOf(...)` переносится из старого теста с `Entry(view, backend)`.

`ratelimit/SlidingWindowRateLimiterTest.kt` — перенос `DescriptionRateLimiterTest` с конструктором `SlidingWindowRateLimiter("test", DescriptionProperties.RateLimit(enabled = true, maxRequests = 3, window = Duration.ofSeconds(60)), clock)`; добавить:

```kotlin
    @Test
    fun `two limiters do not share the window`() =
        runTest {
            val a = SlidingWindowRateLimiter("a", DescriptionProperties.RateLimit(true, 1, Duration.ofMinutes(1)), clock)
            val b = SlidingWindowRateLimiter("b", DescriptionProperties.RateLimit(true, 1, Duration.ofMinutes(1)), clock)
            assertTrue(a.tryAcquire())
            assertTrue(b.tryAcquire())
            assertFalse(a.tryAcquire())
        }
```

- [ ] **Step 2: Убедиться, что тесты не компилируются**

Run: `./gradlew :frigate-analyzer-ai-description:test --tests '*VisionCallExecutorTest' --tests '*SlidingWindowRateLimiterTest'`
Expected: FAIL — `Unresolved reference: VisionCallExecutor`, `SlidingWindowRateLimiter`, `PresetChoiceSource`.

- [ ] **Step 3: Источник выбора пресета и резолвер**

`api/PresetChoiceSource.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.api

/** Откуда резолвер берёт выбранный владельцем пресет. Общая часть настроек описаний и судьи. */
interface PresetChoiceSource {
    val sourceName: String

    /** null = владелец ничего не выбирал. */
    suspend fun activePresetId(): String?
}
```

`api/DescriptionRuntimeSettings.kt`: `interface DescriptionRuntimeSettings : PresetChoiceSource` — объявления `sourceName` и `activePresetId()` из тела интерфейса убрать (наследуются), остальное без изменений.

`core/ActivePresetResolver.kt`: конструктор `(catalog: DescriptionPresetCatalog, source: PresetChoiceSource, val fallbackId: String, private val label: String)`; больше **не** реализует `ActiveDescriptionPreset`; `storedId()` и `effective()` остаются публичными методами; все обращения `catalog.fallbackId` заменить на `fallbackId`, `catalog.fallback()` — на `requireNotNull(catalog.byId(fallbackId))`; строки логов и WARN начинаются с `"Active $label preset ..."` вместо `"Active description preset ..."`. `init { require(catalog.byId(fallbackId)?.backend != null) { "fallback preset '$fallbackId' for $label is missing or unavailable" } }`.

`core/DescriptionPresetResolver.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.core

import ru.zinin.frigate.analyzer.ai.description.api.ActiveDescriptionPreset
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionPreset

/** Резолвер описаний как бин типа [ActiveDescriptionPreset]; сам [ActivePresetResolver] бином не является — их два. */
class DescriptionPresetResolver(
    val resolver: ActivePresetResolver,
) : ActiveDescriptionPreset {
    override suspend fun storedId(): String? = resolver.storedId()

    override suspend fun effective(): DescriptionPreset = resolver.effective()
}
```

- [ ] **Step 4: Executor**

`core/VisionLimits.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.core

import java.time.Duration

data class VisionLimits(
    val queueTimeout: Duration,
    val timeout: Duration,
    val maxConcurrent: Int,
    /** 0 = кадры не уменьшаются. */
    val maxImageSide: Int,
)
```

`core/VisionCallExecutor.kt` — тело `DefaultDescriptionAgent` (Task 1) с обобщением:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.core

data class VisionOutcome<T>(
    val value: T,
    val preset: DescriptionPreset,
    val elapsed: kotlin.time.Duration,
)

/**
 * Провайдер-нейтральное исполнение одной vision-задачи: резолюция пресета до семафора, семафор,
 * queueTimeout, timeout, retry по InvalidResponse и Transport с проверкой остатка бюджета, downscale
 * кадров, отчёт в ProviderAuthTracker. Разбор ответа ([parse]) выполняется внутри цикла повторов:
 * InvalidResponse из парсера повторяет вызов так же, как раньше повторял его backend.
 */
class VisionCallExecutor(
    private val resolver: ActivePresetResolver,
    private val authTracker: ProviderAuthTracker,
    private val limits: VisionLimits,
    private val label: String,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val semaphore = Semaphore(limits.maxConcurrent)

    suspend fun <T> execute(
        request: VisionRequest,
        parse: (String) -> T,
    ): VisionOutcome<T> {
        val entry = resolver.resolve()
        val backend = requireNotNull(entry.backend) { "resolved preset '${entry.view.id}' has no backend" }
        var acquired = false
        try {
            withTimeout(limits.queueTimeout.toMillis()) {
                semaphore.acquire()
                acquired = true
            }
        } catch (e: TimeoutCancellationException) {
            if (acquired) semaphore.release()
            throw DescriptionException.Timeout(cause = e)
        }
        val callStart = timeSource.markNow()
        try {
            val prepared = downscaleFrames(request)
            val value =
                try {
                    withTimeout(limits.timeout.toMillis()) { executeWithRetry(backend, prepared, parse) }
                } catch (e: TimeoutCancellationException) {
                    throw DescriptionException.Timeout(cause = e)
                }
            return VisionOutcome(value, entry.view, callStart.elapsedNow())
        } finally {
            logger.debug { "$label via preset '${entry.view.id}' completed in ${callStart.elapsedNow()} for ${request.requestId}" }
            if (acquired) semaphore.release()
        }
    }
    // downscaleFrames(request: VisionRequest) — как в DefaultDescriptionAgent, но maxSide = limits.maxImageSide
    // executeWithRetry(backend, request, parse) — как раньше; attempt(...) = parse(backend.complete(request))
    //   с той же обёрткой не-DescriptionException в Transport; бюджет = limits.timeout
}
```

`core/DefaultDescriptionAgent.kt` становится обёрткой:

```kotlin
class DefaultDescriptionAgent(
    private val executor: VisionCallExecutor,
    private val parser: DescriptionResponseParser,
) : DescriptionAgent {
    override suspend fun describe(request: DescriptionRequest): DescriptionResult {
        val vision = VisionRequest(request.recordingId, request.frames, DescriptionTask.instructions(request))
        return executor.execute(vision) { raw -> parser.parse(raw, request.shortMaxLength, request.detailedMaxLength) }.value
    }
}
```

`DefaultDescriptionAgentTest` сокращается до двух тестов: инструкции задачи описаний доходят до backend-а (`preamble` содержит язык) и результат парсера возвращается как есть.

- [ ] **Step 5: Лимитер**

`ratelimit/SlidingWindowRateLimiter.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.ratelimit

open class SlidingWindowRateLimiter(
    private val name: String,
    private val rateLimit: DescriptionProperties.RateLimit,
    private val clock: Clock,
) {
    private val mutex = Mutex()
    private val timestamps = ArrayDeque<Instant>(rateLimit.maxRequests)

    init {
        if (rateLimit.enabled) {
            logger.info { "$name rate limiter enabled: max=${rateLimit.maxRequests}, window=${rateLimit.window}" }
        } else {
            logger.info { "$name rate limiter disabled (rate-limit.enabled=false)" }
        }
    }

    suspend fun tryAcquire(): Boolean { /* тело прежнего DescriptionRateLimiter.tryAcquire без изменений */ }
}
```

`ratelimit/DescriptionRateLimiter.kt`:

```kotlin
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class DescriptionRateLimiter(
    clock: Clock,
    descriptionProperties: DescriptionProperties,
) : SlidingWindowRateLimiter("AI description", descriptionProperties.common.rateLimit, clock)
```

`DescriptionProperties.RateLimit.maxRequests` дефолт `10` → `30`; в `application.yaml` `max-requests: ${APP_AI_DESCRIPTION_RATE_LIMIT_MAX:30}`.

- [ ] **Step 6: Проводка**

`config/AiDescriptionAutoConfiguration.PresetBeans`:

```kotlin
        @Bean
        fun descriptionPresetResolver(
            catalog: DescriptionPresetCatalog,
            runtimeSettings: DescriptionRuntimeSettings,
        ): DescriptionPresetResolver =
            DescriptionPresetResolver(ActivePresetResolver(catalog, runtimeSettings, catalog.fallbackId, label = "description"))

        @Bean
        fun descriptionVisionCallExecutor(
            resolver: DescriptionPresetResolver,
            authTracker: ProviderAuthTracker,
            descriptionProperties: DescriptionProperties,
        ): VisionCallExecutor {
            val common = descriptionProperties.common
            return VisionCallExecutor(
                resolver = resolver.resolver,
                authTracker = authTracker,
                limits = VisionLimits(common.queueTimeout, common.timeout, common.maxConcurrent, common.maxImageSide),
                label = "description",
            )
        }

        @Bean
        fun descriptionAgent(
            descriptionVisionCallExecutor: VisionCallExecutor,
            parser: DescriptionResponseParser,
        ): DescriptionAgent = DefaultDescriptionAgent(descriptionVisionCallExecutor, parser)
```

Бин `activePresetResolver` удаляется; потребители `ActiveDescriptionPreset` (телеграм-фабрика экрана) получают `DescriptionPresetResolver` по интерфейсу. Тесты `ActivePresetResolverTest` и `AiDescriptionAutoConfigurationTest` адаптируются к новому конструктору (`fallbackId = "fast"`, `label = "description"`); в автоконфиг-тесте проверить `ctx.getBean(ActiveDescriptionPreset::class.java)` — единственный бин.

- [ ] **Step 7: Прогнать тесты**

Run: `./gradlew :frigate-analyzer-ai-description:test :frigate-analyzer-core:test :frigate-analyzer-telegram:test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add modules/ai-description modules/core/src/main/resources/application.yaml
git commit -m "refactor(ai-description): VisionCallExecutor and sliding-window limiter shared by two tasks" -m "Claude-Session: <SESSION_URL>"
```

---
### Task 3: Судья в `ai-description`: API, инструкции, разбор, свойства, агент, бины

**Files:**
- Create: `api/JudgeAgent.kt`, `api/JudgeRequest.kt`, `api/JudgeVerdict.kt`, `api/JudgeOutcome.kt`, `api/JudgeRuntimeSettings.kt`, `api/ActiveJudgePreset.kt`
- Create: `core/JudgeTask.kt`, `core/JudgeResponseParser.kt`, `core/DefaultJudgeAgent.kt`, `core/JudgePresetResolver.kt`, `core/InMemoryJudgeRuntimeSettings.kt`
- Create: `config/JudgeProperties.kt`, `config/JudgeAgentSanityChecker.kt`
- Create: `ratelimit/JudgeRateLimiter.kt`
- Modify: `config/AiDescriptionAutoConfiguration.kt` (`@EnableConfigurationProperties(JudgeProperties::class)`, вложенный `JudgeBeans`)
- Test (create): `core/JudgeResponseParserTest.kt`, `core/JudgeTaskTest.kt`, `core/DefaultJudgeAgentTest.kt`, `config/JudgePropertiesTest.kt`, `config/AiJudgeAutoConfigurationTest.kt`

(все пути — `modules/ai-description/src/{main,test}/kotlin/ru/zinin/frigate/analyzer/ai/description/`)

**Interfaces:**
- Consumes: `VisionCallExecutor`, `VisionLimits`, `ActivePresetResolver(catalog, source, fallbackId, label)`, `PresetChoiceSource`, `SlidingWindowRateLimiter`, `DescriptionPresetCatalog`, `DescriptionPresetsDeclaredCondition`, `JsonBlockExtractor`, `ResultNormalizer.truncate`.
- Produces: `JudgeAgent.judge(JudgeRequest): JudgeOutcome`; `JudgeRequest`; `JudgeVerdict` с `Decision`, `Reason`; `JudgeOutcome`; `JudgeRuntimeSettings`; `ActiveJudgePreset`; `JudgeProperties`; `JudgeRateLimiter`; бины при `application.ai.judge.enabled=true`.

- [ ] **Step 1: Падающие тесты**

`core/JudgeResponseParserTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.core

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.api.JudgeVerdict
import ru.zinin.frigate.analyzer.ai.description.testsupport.TestObjectMappers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class JudgeResponseParserTest {
    private val parser = JudgeResponseParser(TestObjectMappers.internalMapper())

    private fun parse(raw: String) = parser.parse(raw, maxSnoozeMinutes = 30)

    @Test
    fun `parses a full publish verdict`() {
        val verdict =
            parse("""{"verdict":"PUBLISH","reason":"NEW_EVENT","confidence":0.9,"summary":"Человек у калитки.","snooze_minutes":15,"wanted":""}""")
        assertEquals(JudgeVerdict.Decision.PUBLISH, verdict.decision)
        assertEquals(JudgeVerdict.Reason.NEW_EVENT, verdict.reason)
        assertEquals(0.9, verdict.confidence)
        assertEquals("Человек у калитки.", verdict.summary)
        assertEquals(15, verdict.snoozeMinutes)
        assertEquals("", verdict.wanted)
    }

    @Test
    fun `every suppress reason is accepted with SUPPRESS`() {
        for (reason in listOf("FALSE_POSITIVE", "STATIC_OBJECT", "DUPLICATE")) {
            assertEquals(JudgeVerdict.Reason.valueOf(reason), parse("""{"verdict":"SUPPRESS","reason":"$reason","summary":"x"}""").reason)
        }
    }

    @Test
    fun `publish with a suppress reason is InvalidResponse`() {
        assertFailsWith<DescriptionException.InvalidResponse> { parse("""{"verdict":"PUBLISH","reason":"DUPLICATE","summary":"x"}""") }
    }

    @Test
    fun `suppress with a publish reason is InvalidResponse`() {
        assertFailsWith<DescriptionException.InvalidResponse> { parse("""{"verdict":"SUPPRESS","reason":"NEW_EVENT","summary":"x"}""") }
    }

    @Test
    fun `unknown verdict, unknown reason and missing fields are InvalidResponse`() {
        assertFailsWith<DescriptionException.InvalidResponse> { parse("""{"verdict":"MAYBE","reason":"NEW_EVENT","summary":"x"}""") }
        assertFailsWith<DescriptionException.InvalidResponse> { parse("""{"verdict":"PUBLISH","reason":"WHATEVER","summary":"x"}""") }
        assertFailsWith<DescriptionException.InvalidResponse> { parse("""{"reason":"NEW_EVENT","summary":"x"}""") }
        assertFailsWith<DescriptionException.InvalidResponse> { parse("not json at all") }
    }

    @Test
    fun `snooze is clamped to the ceiling and negatives or garbage become zero`() {
        assertEquals(30, parse("""{"verdict":"SUPPRESS","reason":"DUPLICATE","summary":"x","snooze_minutes":720}""").snoozeMinutes)
        assertEquals(0, parse("""{"verdict":"SUPPRESS","reason":"DUPLICATE","summary":"x","snooze_minutes":-5}""").snoozeMinutes)
        assertEquals(0, parse("""{"verdict":"SUPPRESS","reason":"DUPLICATE","summary":"x","snooze_minutes":"soon"}""").snoozeMinutes)
        assertEquals(0, parse("""{"verdict":"SUPPRESS","reason":"DUPLICATE","summary":"x"}""").snoozeMinutes)
    }

    @Test
    fun `confidence outside 0..1 or non-numeric becomes null`() {
        assertNull(parse("""{"verdict":"SUPPRESS","reason":"DUPLICATE","summary":"x","confidence":1.7}""").confidence)
        assertNull(parse("""{"verdict":"SUPPRESS","reason":"DUPLICATE","summary":"x","confidence":"high"}""").confidence)
    }

    @Test
    fun `summary and wanted are truncated to 512 characters and default to empty`() {
        val long = "a".repeat(600)
        val verdict = parse("""{"verdict":"SUPPRESS","reason":"DUPLICATE","summary":"$long","wanted":"$long"}""")
        assertEquals(512, verdict.summary.length)
        assertEquals(512, verdict.wanted.length)
        assertEquals("", parse("""{"verdict":"SUPPRESS","reason":"DUPLICATE"}""").summary)
    }

    @Test
    fun `JSON embedded in prose is extracted`() {
        val verdict = parse("""Sure! {"verdict":"SUPPRESS","reason":"STATIC_OBJECT","summary":"Припаркованная машина."} Done.""")
        assertEquals(JudgeVerdict.Reason.STATIC_OBJECT, verdict.reason)
    }
}
```

`core/JudgeTaskTest.kt`:

```kotlin
class JudgeTaskTest {
    private val request =
        JudgeRequest(
            recordingId = UUID.randomUUID(),
            camId = "cam2",
            frames = listOf(DescriptionRequest.FrameImage(0, ByteArray(1))),
            contextJson = """{"recording":{"cam":"cam2"}}""",
            language = "ru",
            maxSnoozeMinutes = 30,
        )

    @Test
    fun `preamble names the camera and epilogue carries context, policy, snooze ceiling and language`() {
        val instructions = JudgeTask.instructions(request)
        assertTrue(instructions.preamble.contains("camera `cam2`"))
        assertTrue(instructions.epilogue.contains("""{"recording":{"cam":"cam2"}}"""))
        assertTrue(instructions.epilogue.contains("FALSE_POSITIVE"))
        assertTrue(instructions.epilogue.contains("When in doubt about a person, PUBLISH"))
        assertTrue(instructions.epilogue.contains("`snooze_minutes` (0–30)"))
        assertTrue(instructions.epilogue.contains("one sentence in Russian"))
        assertEquals(JudgeTask.SYSTEM_PROMPT, instructions.systemPrompt)
        assertEquals(JudgeTask.JSON_SCHEMA, instructions.jsonSchema)
    }

    @Test
    fun `is deterministic for the same input`() {
        assertEquals(JudgeTask.instructions(request), JudgeTask.instructions(request))
    }
}
```

`core/DefaultJudgeAgentTest.kt`:

```kotlin
class DefaultJudgeAgentTest {
    private val parser = JudgeResponseParser(TestObjectMappers.internalMapper())

    private class FakeBackend(private val answer: String) : VisionBackend {
        override val providerId = "fake"
        override val authScopeId = "fake:model"
        override val authRecoveryHint = "hint"
        var lastRequest: VisionRequest? = null

        override suspend fun complete(request: VisionRequest): String {
            lastRequest = request
            return answer
        }
    }

    private fun agent(backend: VisionBackend): DefaultJudgeAgent {
        val view = DescriptionPreset("judge", "fake", "m", "m-effective", "", "fake:model", null)
        val catalog = DescriptionPresetCatalog(listOf(DescriptionPresetCatalog.Entry(view, backend)), "judge")
        val resolver = ActivePresetResolver(catalog, InMemoryJudgeRuntimeSettings(), fallbackId = "judge", label = "judge")
        val executor =
            VisionCallExecutor(
                resolver,
                ProviderAuthTracker { },
                VisionLimits(Duration.ofSeconds(5), Duration.ofSeconds(10), 1, 0),
                label = "judge",
            )
        return DefaultJudgeAgent(executor, parser)
    }

    @Test
    fun `judge hands the task instructions to the backend and returns the verdict with preset and model`() =
        runTest {
            val backend = FakeBackend("""{"verdict":"SUPPRESS","reason":"STATIC_OBJECT","summary":"car","snooze_minutes":10}""")
            val request = JudgeRequest(UUID.randomUUID(), "cam2", listOf(DescriptionRequest.FrameImage(0, ByteArray(1))), "{}", "en", 30)

            val outcome = agent(backend).judge(request)

            assertEquals(JudgeVerdict.Decision.SUPPRESS, outcome.verdict.decision)
            assertEquals(10, outcome.verdict.snoozeMinutes)
            assertEquals("judge", outcome.presetId)
            assertEquals("m-effective", outcome.model)
            assertEquals(JudgeTask.SYSTEM_PROMPT, backend.lastRequest!!.instructions.systemPrompt)
            assertTrue(backend.lastRequest!!.instructions.preamble.contains("cam2"))
        }
}
```

`config/JudgePropertiesTest.kt` — по образцу `DescriptionPresetsValidationTest`: дефолты (`enabled=false`, `maxFrames=4`, `maxImageSide=1280`, `rateLimit.maxRequests=200`, `maxSnooze=PT30M`, `staticWindow=P7D`, `staticIou=0.4`, `historyWindow=PT6H`, `historyLimit=10`, `zone=""`, `cameras` пуста); отрицательный `timeout` и `staticIou=1.5` дают исключение при конструировании.

`config/AiJudgeAutoConfigurationTest.kt` — на `ApplicationContextRunner` с той же заглушечной конфигурацией и `properties(...)`, что в `AiDescriptionAutoConfigurationTest`, плюс `application.ai.judge.enabled=true` и `application.ai.judge.default-preset=claude`; проверить: бины `JudgeAgent`, `ActiveJudgePreset`, `JudgeRateLimiter` есть; `JudgeRuntimeSettings` — `InMemoryJudgeRuntimeSettings`; при `judge.enabled=false` их нет, а `JudgeProperties` есть; при `judge.default-preset=missing` контекст падает с сообщением, содержащим `judge default-preset 'missing'`.

- [ ] **Step 2: Убедиться, что не компилируется**

Run: `./gradlew :frigate-analyzer-ai-description:test --tests '*Judge*'`
Expected: FAIL — unresolved references.

- [ ] **Step 3: API**

`api/JudgeVerdict.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.api

data class JudgeVerdict(
    val decision: Decision,
    val reason: Reason,
    /** null = модель не дала число в [0, 1]. Только сохраняется. */
    val confidence: Double?,
    val summary: String,
    /** Уже обрезано парсером до потолка запроса; 0 = не усыплять. */
    val snoozeMinutes: Int,
    val wanted: String,
) {
    enum class Decision { PUBLISH, SUPPRESS }

    enum class Reason(
        val publishes: Boolean,
    ) {
        NEW_EVENT(true),
        CHANGED_SITUATION(true),
        FALSE_POSITIVE(false),
        STATIC_OBJECT(false),
        DUPLICATE(false),
    }
}
```

`api/JudgeRequest.kt`:

```kotlin
data class JudgeRequest(
    val recordingId: UUID,
    val camId: String,
    val frames: List<DescriptionRequest.FrameImage>,
    /** Контекст, собранный вызывающей стороной; модуль не знает, откуда он. */
    val contextJson: String,
    val language: String,
    val maxSnoozeMinutes: Int,
)
```

`api/JudgeOutcome.kt`:

```kotlin
data class JudgeOutcome(
    val verdict: JudgeVerdict,
    val presetId: String,
    val model: String,
    val latency: java.time.Duration,
)
```

`api/JudgeAgent.kt`: `interface JudgeAgent { suspend fun judge(request: JudgeRequest): JudgeOutcome }`.

`api/JudgeRuntimeSettings.kt`:

```kotlin
interface JudgeRuntimeSettings : PresetChoiceSource {
    suspend fun setActivePresetId(id: String, changedBy: String?)

    /** Отсутствие настройки означает «включён»; статический флаг фичи главнее. */
    suspend fun judgeEnabled(): Boolean

    suspend fun setJudgeEnabled(value: Boolean, changedBy: String?)
}
```

`api/ActiveJudgePreset.kt`: копия `ActiveDescriptionPreset` с тем же двумя методами `storedId()` / `effective()`.

- [ ] **Step 4: Задача, парсер, агент, in-memory настройки**

`core/JudgeTask.kt`:

```kotlin
object JudgeTask {
    const val SYSTEM_PROMPT =
        "You are the final gate of a home security camera notification system. " +
            "Answer only with the requested JSON object. Do not call tools and do not ask questions."

    const val JSON_SCHEMA =
        """{"type":"object","properties":{"verdict":{"type":"string","enum":["PUBLISH","SUPPRESS"]},""" +
            """"reason":{"type":"string","enum":["NEW_EVENT","CHANGED_SITUATION","FALSE_POSITIVE","STATIC_OBJECT","DUPLICATE"]},""" +
            """"confidence":{"type":"number"},"summary":{"type":"string"},"snooze_minutes":{"type":"integer"},"wanted":{"type":"string"}},""" +
            """"required":["verdict","reason","summary"],"additionalProperties":false}"""

    fun instructions(request: JudgeRequest): VisionInstructions {
        val language = LanguageNames.of(request.language)
        val preamble =
            "A YOLO detector flagged objects in a short recording from camera `${request.camId}`. Your job is to decide " +
                "whether the household should be notified about this recording. The frames below have the detector's boxes " +
                "drawn on them. Context assembled from the database follows the frames."
        val epilogue =
            buildString {
                appendLine("Context (JSON):")
                appendLine("```json")
                appendLine(request.contextJson)
                appendLine("```")
                appendLine()
                appendLine("Decide:")
                appendLine("- PUBLISH with reason NEW_EVENT when a real, new event is likely: a person, animal or vehicle that is not a known static object and has not been reported recently. Use CHANGED_SITUATION when an ongoing, already reported situation changed materially (another person, a vehicle arrived, someone approached the house).")
                appendLine("- SUPPRESS with FALSE_POSITIVE when the boxed region is not what the detector claims (glare, foliage, a woodpile, a shadow); with STATIC_OBJECT when the object is real but has been in this spot for a long time (high share of recordings across many days in `static`, identical box across frames); with DUPLICATE when the same situation was already reported (`recent_verdicts`, `last_published`) and nothing new happened.")
                appendLine("- When in doubt about a person, PUBLISH: missing a real person is worse than one extra message. For vehicles and objects with strong static evidence, lean to SUPPRESS. At night and in infrared be sceptical of odd shapes and glare, but still publish people.")
                appendLine("- `snooze_minutes` (0–${request.maxSnoozeMinutes}): if this situation will keep producing detections, how long we may skip asking you about this camera while the object classes stay the same and their count does not grow.")
                appendLine("- `summary`: one sentence in $language, at most 200 characters: what is in the frames and why this verdict.")
                appendLine("- `wanted`: what extra context would have made you confident, or an empty string.")
                appendLine()
                appendLine("Return ONLY this JSON object:")
                append("""{"verdict": "PUBLISH|SUPPRESS", "reason": "...", "confidence": 0.0, "summary": "...", "snooze_minutes": 0, "wanted": ""}""")
            }
        return VisionInstructions(SYSTEM_PROMPT, preamble, epilogue, JSON_SCHEMA)
    }
}
```

`core/JudgeResponseParser.kt`:

```kotlin
@Component
@ConditionalOnProperty("application.ai.judge.enabled", havingValue = "true")
class JudgeResponseParser(
    private val objectMapper: ObjectMapper,
) {
    fun parse(
        raw: String,
        maxSnoozeMinutes: Int,
    ): JudgeVerdict {
        val node: JsonNode =
            try {
                objectMapper.readTree(JsonBlockExtractor.extract(raw))
            } catch (e: Exception) {
                throw DescriptionException.InvalidResponse(e, detail = "judge answer is not JSON: ${raw.take(200)}")
            }
        val decision = enumOrInvalid<JudgeVerdict.Decision>(node["verdict"]?.scalarOrNull(), "verdict")
        val reason = enumOrInvalid<JudgeVerdict.Reason>(node["reason"]?.scalarOrNull(), "reason")
        if (reason.publishes != (decision == JudgeVerdict.Decision.PUBLISH)) {
            throw DescriptionException.InvalidResponse(detail = "reason $reason does not match verdict $decision")
        }
        val confidence = node["confidence"]?.takeIf { it.isNumber }?.doubleValue()?.takeIf { it in 0.0..1.0 }
        val snooze = node["snooze_minutes"]?.takeIf { it.isNumber }?.intValue()?.coerceIn(0, maxSnoozeMinutes) ?: 0
        return JudgeVerdict(
            decision = decision,
            reason = reason,
            confidence = confidence,
            summary = ResultNormalizer.truncate(node["summary"]?.scalarOrNull().orEmpty(), TEXT_MAX),
            snoozeMinutes = snooze,
            wanted = ResultNormalizer.truncate(node["wanted"]?.scalarOrNull().orEmpty(), TEXT_MAX),
        )
    }

    private inline fun <reified E : Enum<E>> enumOrInvalid(
        value: String?,
        field: String,
    ): E =
        enumValues<E>().firstOrNull { it.name == value }
            ?: throw DescriptionException.InvalidResponse(detail = "missing or unknown '$field': $value")

    private fun JsonNode.scalarOrNull(): String? = if (isValueNode && !isNull) asString() else null

    companion object {
        const val TEXT_MAX = 512
    }
}
```

`ResultNormalizer.truncate` сделать `fun` вместо `internal fun` (используется из того же модуля — оставить `internal`, парсер в том же модуле).

`core/DefaultJudgeAgent.kt`:

```kotlin
class DefaultJudgeAgent(
    private val executor: VisionCallExecutor,
    private val parser: JudgeResponseParser,
) : JudgeAgent {
    override suspend fun judge(request: JudgeRequest): JudgeOutcome {
        val vision = VisionRequest(request.recordingId, request.frames, JudgeTask.instructions(request))
        val outcome = executor.execute(vision) { raw -> parser.parse(raw, request.maxSnoozeMinutes) }
        return JudgeOutcome(outcome.value, outcome.preset.id, outcome.preset.effectiveModel, outcome.elapsed.toJavaDuration())
    }
}
```

`core/JudgePresetResolver.kt` — как `DescriptionPresetResolver`, но реализует `ActiveJudgePreset`.

`core/InMemoryJudgeRuntimeSettings.kt` — по образцу `InMemoryDescriptionRuntimeSettings`: `sourceName = "in-memory"`, `AtomicReference<String?>` для пресета, `AtomicBoolean(true)` для выключателя, INFO-строка в `init` о том, что выбор судьи не переживёт рестарт.

- [ ] **Step 5: Свойства, лимитер, sanity-checker, бины**

`config/JudgeProperties.kt`:

```kotlin
@ConfigurationProperties(prefix = "application.ai.judge")
@Validated
data class JudgeProperties(
    val enabled: Boolean = false,
    val defaultPreset: String = "",
    val queueTimeout: Duration = Duration.ofSeconds(30),
    val timeout: Duration = Duration.ofSeconds(60),
    @field:Min(1) @field:Max(10)
    val maxConcurrent: Int = 2,
    @field:Min(1) @field:Max(10)
    val maxFrames: Int = 4,
    @field:Min(0) @field:Max(8192)
    val maxImageSide: Int = 1280,
    @field:Valid
    val rateLimit: DescriptionProperties.RateLimit = DescriptionProperties.RateLimit(enabled = true, maxRequests = 200, window = Duration.ofHours(1)),
    val maxSnooze: Duration = Duration.ofMinutes(30),
    val staticWindow: Duration = Duration.ofDays(7),
    @field:DecimalMin("0.0") @field:DecimalMax("1.0")
    val staticIou: Double = 0.4,
    val historyWindow: Duration = Duration.ofHours(6),
    @field:Min(1) @field:Max(50)
    val historyLimit: Int = 10,
    /** Пусто = зона владельца из Telegram, затем зона JVM. */
    val zone: String = "",
    val cameras: Map<String, CameraSection> = emptyMap(),
) {
    data class CameraSection(
        val notes: String = "",
    )

    init {
        require(queueTimeout.toMillis() > 0) { "application.ai.judge.queue-timeout must be positive" }
        require(timeout.toMillis() > 0) { "application.ai.judge.timeout must be positive" }
        require(!maxSnooze.isNegative && !maxSnooze.isZero) { "application.ai.judge.max-snooze must be positive" }
        require(!staticWindow.isNegative && !staticWindow.isZero) { "application.ai.judge.static-window must be positive" }
        require(!historyWindow.isNegative && !historyWindow.isZero) { "application.ai.judge.history-window must be positive" }
        require(maxImageSide == 0 || maxImageSide >= 256) { "application.ai.judge.max-image-side must be 0 or at least 256" }
        require(zone.isBlank() || runCatching { java.time.ZoneId.of(zone) }.isSuccess) { "application.ai.judge.zone '$zone' is not a valid zone id" }
    }

    val maxSnoozeMinutes: Int get() = maxSnooze.toMinutes().toInt().coerceAtLeast(1)
}
```

`ratelimit/JudgeRateLimiter.kt`: `class JudgeRateLimiter(clock: Clock, judgeProperties: JudgeProperties) : SlidingWindowRateLimiter("AI judge", judgeProperties.rateLimit, clock)` — без `@Component`, бин создаётся в `JudgeBeans`.

`config/JudgeAgentSanityChecker.kt` — по образцу `DescriptionAgentSanityChecker`: `@Component`, `@PostConstruct`, WARN, если `judgeProperties.enabled` и `ObjectProvider<JudgeAgent>.getIfAvailable() == null` (текст: судья включён, но каталог пресетов не собран — проверить `application.ai.description.enabled` и `presets`).

`config/AiDescriptionAutoConfiguration.kt`: добавить `JudgeProperties::class` в `@EnableConfigurationProperties`; вложенный класс:

```kotlin
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
    @ConditionalOnProperty("application.ai.judge.enabled", havingValue = "true")
    @Conditional(DescriptionPresetsDeclaredCondition::class)
    open class JudgeBeans {
        @Bean
        @ConditionalOnMissingBean(JudgeRuntimeSettings::class)
        fun inMemoryJudgeRuntimeSettings(): JudgeRuntimeSettings = InMemoryJudgeRuntimeSettings()

        @Bean
        fun judgePresetResolver(
            catalog: DescriptionPresetCatalog,
            runtimeSettings: JudgeRuntimeSettings,
            judgeProperties: JudgeProperties,
        ): JudgePresetResolver {
            val fallbackId = judgeProperties.defaultPreset.ifBlank { catalog.fallbackId }
            val entry = catalog.byId(fallbackId)
            check(entry != null) { "application.ai.judge default-preset '$fallbackId' is not declared in application.ai.description.presets" }
            check(entry.backend != null) { "application.ai.judge default-preset '$fallbackId' is unavailable: ${entry.view.unavailableReason}" }
            return JudgePresetResolver(ActivePresetResolver(catalog, runtimeSettings, fallbackId, label = "judge"))
        }

        @Bean
        fun judgeVisionCallExecutor(
            resolver: JudgePresetResolver,
            authTracker: ProviderAuthTracker,
            judgeProperties: JudgeProperties,
        ): VisionCallExecutor =
            VisionCallExecutor(
                resolver = resolver.resolver,
                authTracker = authTracker,
                limits = VisionLimits(judgeProperties.queueTimeout, judgeProperties.timeout, judgeProperties.maxConcurrent, judgeProperties.maxImageSide),
                label = "judge",
            )

        @Bean
        fun judgeRateLimiter(
            clock: Clock,
            judgeProperties: JudgeProperties,
        ): JudgeRateLimiter = JudgeRateLimiter(clock, judgeProperties)

        @Bean
        fun judgeAgent(
            judgeVisionCallExecutor: VisionCallExecutor,
            parser: JudgeResponseParser,
        ): JudgeAgent = DefaultJudgeAgent(judgeVisionCallExecutor, parser)
    }
```

Два бина типа `VisionCallExecutor` — инъекция в `descriptionAgent`/`judgeAgent` идёт по имени параметра (`descriptionVisionCallExecutor`, `judgeVisionCallExecutor`), Spring разрешает по имени при неоднозначности типа. Никто другой `VisionCallExecutor` по типу не инжектит.

- [ ] **Step 6: Прогнать тесты**

Run: `./gradlew :frigate-analyzer-ai-description:test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add modules/ai-description
git commit -m "feat(ai-description): judge agent, task instructions, verdict parser and properties" -m "Claude-Session: <SESSION_URL>"
```

---
### Task 4: Таблица `notification_verdicts`, сущность, сервис и статистические запросы

**Files:**
- Create: `docker/liquibase/migration/1.0.6.xml`; Modify: `docker/liquibase/migration/master_frigate_analyzer.xml`
- Create: `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/VerdictEnums.kt`, `dto/NewNotificationVerdict.kt`, `dto/JudgeStats.kt`, `persistent/NotificationVerdictEntity.kt`
- Create: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/repository/NotificationVerdictRepository.kt`, `repository/JudgeStatsRepository.kt`, `NotificationVerdictService.kt`, `impl/NotificationVerdictServiceImpl.kt`
- Modify: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/AppSettingKeys.kt`
- Test (create): `modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/impl/NotificationVerdictServiceImplTest.kt`, `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/repository/NotificationVerdictRepositoryTest.kt`, `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/repository/JudgeStatsRepositoryTest.kt`

**Interfaces:**
- Consumes: `RecordingEntity`, `DetectionEntity`, `UUIDGeneratorHelper.generateV1()`, `Clock`, `IntegrationTestBase`.
- Produces: `VerdictStage`, `VerdictDecision`, `VerdictReason`; `NewNotificationVerdict`; `NotificationVerdictEntity`; `StaticScore(recordings, days, firstSeen, lastSeen)`, `VerdictCountRow(stage, verdict, reason, count)`; `NotificationVerdictService.record/recentForCamera/lastPublished/latest/countersSince`; `JudgeStatsRepository.staticScore/recordingsInWindow/verdictCounters`; ключи `AppSettingKeys.AI_JUDGE_PRESET_ACTIVE`, `AI_JUDGE_ENABLED`.

- [ ] **Step 1: Миграция**

`docker/liquibase/migration/1.0.6.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-5.0.xsd">
    <changeSet author="zinin" id="20260905-01-create-notification-verdicts">
        <comment>One row per notification candidate judged by the LLM gate (or bypassed / snoozed / failed over)</comment>
        <sql>
            CREATE TABLE notification_verdicts (
              id                UUID          PRIMARY KEY,
              created_at        TIMESTAMPTZ   NOT NULL,
              recording_id      UUID          NOT NULL,
              cam_id            VARCHAR(255)  NOT NULL,
              record_timestamp  TIMESTAMPTZ   NOT NULL,
              stage             VARCHAR(16)   NOT NULL,
              verdict           VARCHAR(8)    NOT NULL,
              reason            VARCHAR(32)   NOT NULL,
              tracker_reason    VARCHAR(32)   NOT NULL,
              classes           VARCHAR(255)  NOT NULL,
              confidence        REAL          NULL,
              summary           VARCHAR(512)  NULL,
              wanted            VARCHAR(512)  NULL,
              snooze_until      TIMESTAMPTZ   NULL,
              preset_id         VARCHAR(32)   NULL,
              model             VARCHAR(255)  NULL,
              latency_ms        INT           NULL,
              context_json      TEXT          NULL,
              error             VARCHAR(1024) NULL,
              CONSTRAINT fk_notification_verdicts_recording
                FOREIGN KEY (recording_id) REFERENCES recordings(id)
                ON DELETE CASCADE
            );
            CREATE INDEX idx_notification_verdicts_cam_record
              ON notification_verdicts (cam_id, record_timestamp DESC);
            CREATE INDEX idx_notification_verdicts_created
              ON notification_verdicts (created_at);
        </sql>
        <rollback>
            DROP INDEX IF EXISTS idx_notification_verdicts_created;
            DROP INDEX IF EXISTS idx_notification_verdicts_cam_record;
            DROP TABLE IF EXISTS notification_verdicts;
        </rollback>
    </changeSet>
</databaseChangeLog>
```

В `master_frigate_analyzer.xml` добавить `<include file="1.0.6.xml" relativeToChangelogFile="true"/>` после `1.0.5.xml`.

- [ ] **Step 2: Модель**

`model/dto/VerdictEnums.kt`:

```kotlin
package ru.zinin.frigate.analyzer.model.dto

enum class VerdictStage { JUDGE, SNOOZE, FAILOVER, BYPASS }

enum class VerdictDecision { PUBLISH, SUPPRESS }

enum class VerdictReason {
    NEW_EVENT,
    CHANGED_SITUATION,
    FALSE_POSITIVE,
    STATIC_OBJECT,
    DUPLICATE,
    SNOOZED,
    JUDGE_OFF,
    TIMEOUT,
    RATE_LIMITED,
    UNAUTHORIZED,
    INVALID_RESPONSE,
    TRANSPORT,
    CONTEXT_ERROR,
}
```

`model/dto/NewNotificationVerdict.kt`:

```kotlin
data class NewNotificationVerdict(
    val recordingId: UUID,
    val camId: String,
    val recordTimestamp: Instant,
    val stage: VerdictStage,
    val verdict: VerdictDecision,
    val reason: VerdictReason,
    val trackerReason: String,
    /** `person:1,car:1`, классы по алфавиту. */
    val classes: String,
    val confidence: Double? = null,
    val summary: String? = null,
    val wanted: String? = null,
    val snoozeUntil: Instant? = null,
    val presetId: String? = null,
    val model: String? = null,
    val latencyMs: Int? = null,
    val contextJson: String? = null,
    val error: String? = null,
)
```

`model/dto/JudgeStats.kt`:

```kotlin
data class StaticScore(
    val recordings: Long,
    val days: Long,
    val firstSeen: Instant?,
    val lastSeen: Instant?,
)

data class VerdictCountRow(
    val stage: String,
    val verdict: String,
    val reason: String,
    val count: Long,
)
```

`model/persistent/NotificationVerdictEntity.kt` — порядок полей фиксирован, на него опираются позиционные вызовы в тестах Task 10:

```kotlin
@Table(name = "notification_verdicts")
data class NotificationVerdictEntity(
    @JvmField @Id var id: UUID?,
    @Column("created_at") var createdAt: Instant,
    @Column("recording_id") var recordingId: UUID,
    @Column("cam_id") var camId: String,
    @Column("record_timestamp") var recordTimestamp: Instant,
    @Column("stage") var stage: String,
    @Column("verdict") var verdict: String,
    @Column("reason") var reason: String,
    @Column("tracker_reason") var trackerReason: String,
    @Column("classes") var classes: String,
    @Column("confidence") var confidence: Float?,
    @Column("summary") var summary: String?,
    @Column("wanted") var wanted: String?,
    @Column("snooze_until") var snoozeUntil: Instant?,
    @Column("preset_id") var presetId: String?,
    @Column("model") var model: String?,
    @Column("latency_ms") var latencyMs: Int?,
    @Column("context_json") var contextJson: String?,
    @Column("error") var error: String?,
) : Persistable<UUID> {
    override fun getId(): UUID? = id

    override fun isNew(): Boolean = true
}
```

`stage`/`verdict`/`reason` хранятся как имена enum (`String`), конвертация — в сервисе.

- [ ] **Step 3: Падающий тест сервиса**

`service/impl/NotificationVerdictServiceImplTest.kt` (MockK):

```kotlin
class NotificationVerdictServiceImplTest {
    private val repository = mockk<NotificationVerdictRepository>()
    private val stats = mockk<JudgeStatsRepository>()
    private val uuid = mockk<UUIDGeneratorHelper>()
    private val clock = Clock.fixed(Instant.parse("2026-09-05T10:00:00Z"), ZoneOffset.UTC)
    private val service = NotificationVerdictServiceImpl(repository, stats, uuid, clock)

    @Test
    fun `record maps the DTO onto the entity with a generated id and the clock time`() =
        runTest {
            val id = UUID.randomUUID()
            every { uuid.generateV1() } returns id
            val saved = slot<NotificationVerdictEntity>()
            coEvery { repository.save(capture(saved)) } answers { saved.captured }

            val verdict =
                NewNotificationVerdict(
                    recordingId = UUID.randomUUID(), camId = "cam2", recordTimestamp = Instant.parse("2026-09-05T09:59:00Z"),
                    stage = VerdictStage.JUDGE, verdict = VerdictDecision.SUPPRESS, reason = VerdictReason.STATIC_OBJECT,
                    trackerReason = "REAPPEARED", classes = "car:1", confidence = 0.8, summary = "Парковка", snoozeUntil = null,
                    presetId = "claude-sonnet", model = "sonnet", latencyMs = 1200, contextJson = "{}",
                )
            service.record(verdict)

            assertEquals(id, saved.captured.id)
            assertEquals(clock.instant(), saved.captured.createdAt)
            assertEquals("JUDGE", saved.captured.stage)
            assertEquals("SUPPRESS", saved.captured.verdict)
            assertEquals("STATIC_OBJECT", saved.captured.reason)
            assertEquals(0.8f, saved.captured.confidence)
        }

    @Test
    fun `countersSince delegates to the stats repository`() =
        runTest {
            val since = Instant.parse("2026-09-04T10:00:00Z")
            coEvery { stats.verdictCounters(since) } returns listOf(VerdictCountRow("JUDGE", "PUBLISH", "NEW_EVENT", 3))
            assertEquals(3, service.countersSince(since).single().count)
        }
}
```

- [ ] **Step 4: Репозитории и сервис**

`service/repository/NotificationVerdictRepository.kt`:

```kotlin
@Repository
interface NotificationVerdictRepository : CoroutineCrudRepository<NotificationVerdictEntity, UUID> {
    @Query(
        """
        SELECT * FROM notification_verdicts
        WHERE cam_id = :camId AND record_timestamp BETWEEN :from AND :to
        ORDER BY record_timestamp DESC
        LIMIT :limit
        """,
    )
    suspend fun findRecentForCamera(
        @Param("camId") camId: String,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
        @Param("limit") limit: Int,
    ): List<NotificationVerdictEntity>

    @Query(
        """
        SELECT * FROM notification_verdicts
        WHERE cam_id = :camId AND verdict = 'PUBLISH'
        ORDER BY record_timestamp DESC
        LIMIT 1
        """,
    )
    suspend fun findLastPublished(
        @Param("camId") camId: String,
    ): NotificationVerdictEntity?

    @Query("SELECT * FROM notification_verdicts ORDER BY record_timestamp DESC LIMIT :limit")
    suspend fun findLatest(
        @Param("limit") limit: Int,
    ): List<NotificationVerdictEntity>

    @Query("SELECT * FROM notification_verdicts WHERE cam_id = :camId ORDER BY record_timestamp DESC LIMIT :limit")
    suspend fun findLatestByCamera(
        @Param("camId") camId: String,
        @Param("limit") limit: Int,
    ): List<NotificationVerdictEntity>
}
```

`service/repository/JudgeStatsRepository.kt` (`DatabaseClient`, расширения `org.springframework.r2dbc.core.awaitSingle` / `flow`):

```kotlin
@Repository
class JudgeStatsRepository(
    private val databaseClient: DatabaseClient,
) {
    suspend fun staticScore(
        camId: String,
        className: String,
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
        from: Instant,
        to: Instant,
        excludeRecordingId: UUID,
        iou: Double,
        zone: String,
    ): StaticScore =
        databaseClient
            .sql(STATIC_SCORE_SQL)
            .bind("camId", camId)
            .bind("className", className)
            .bind("x1", x1).bind("y1", y1).bind("x2", x2).bind("y2", y2)
            .bind("from", from).bind("to", to)
            .bind("excludeRecordingId", excludeRecordingId)
            .bind("iou", iou)
            .bind("zone", zone)
            .map { row ->
                StaticScore(
                    recordings = row.get("recordings", java.lang.Long::class.java)?.toLong() ?: 0L,
                    days = row.get("days", java.lang.Long::class.java)?.toLong() ?: 0L,
                    firstSeen = row.get("first_seen", Instant::class.java),
                    lastSeen = row.get("last_seen", Instant::class.java),
                )
            }.awaitSingle()

    suspend fun recordingsInWindow(camId: String, from: Instant, to: Instant): Long =
        databaseClient
            .sql("SELECT count(*) AS cnt FROM recordings WHERE cam_id = :camId AND record_timestamp >= :from AND record_timestamp < :to")
            .bind("camId", camId).bind("from", from).bind("to", to)
            .map { row -> row.get("cnt", java.lang.Long::class.java)?.toLong() ?: 0L }
            .awaitSingle()

    suspend fun verdictCounters(since: Instant): List<VerdictCountRow> =
        databaseClient
            .sql("SELECT stage, verdict, reason, count(*) AS cnt FROM notification_verdicts WHERE created_at >= :since GROUP BY stage, verdict, reason")
            .bind("since", since)
            .map { row ->
                VerdictCountRow(
                    stage = row.get("stage", String::class.java)!!,
                    verdict = row.get("verdict", String::class.java)!!,
                    reason = row.get("reason", String::class.java)!!,
                    count = row.get("cnt", java.lang.Long::class.java)?.toLong() ?: 0L,
                )
            }.flow()
            .toList()

    companion object {
        /**
         * IoU в SQL: пересечение / (сумма площадей − пересечение). Индекс — idx_detections_detection_timestamp,
         * остальное фильтр; на проде ~160 мс на неделю cam2. Собственная запись исключена, чтобы
         * кандидат не считал сам себя доказательством статичности.
         */
        val STATIC_SCORE_SQL =
            """
            WITH inter AS (
              SELECT d.recording_id, d.detection_timestamp,
                     GREATEST(0, LEAST(d.x2, :x2) - GREATEST(d.x1, :x1)) * GREATEST(0, LEAST(d.y2, :y2) - GREATEST(d.y1, :y1)) AS i,
                     (d.x2 - d.x1) * (d.y2 - d.y1) + (:x2 - :x1) * (:y2 - :y1) AS sum_areas
              FROM detections d
              JOIN recordings r ON r.id = d.recording_id
              WHERE d.detection_timestamp >= :from AND d.detection_timestamp < :to
                AND r.cam_id = :camId
                AND d.class_name = :className
                AND d.recording_id <> :excludeRecordingId
            )
            SELECT count(DISTINCT recording_id) AS recordings,
                   count(DISTINCT (detection_timestamp AT TIME ZONE :zone)::date) AS days,
                   min(detection_timestamp) AS first_seen,
                   max(detection_timestamp) AS last_seen
            FROM inter
            WHERE sum_areas - i > 0 AND i / (sum_areas - i) >= :iou
            """.trimIndent()
    }
}
```

`service/NotificationVerdictService.kt`:

```kotlin
interface NotificationVerdictService {
    suspend fun record(verdict: NewNotificationVerdict): NotificationVerdictEntity

    suspend fun recentForCamera(camId: String, from: Instant, to: Instant, limit: Int): List<NotificationVerdictEntity>

    suspend fun lastPublished(camId: String): NotificationVerdictEntity?

    /** null camId = все камеры. */
    suspend fun latest(camId: String?, limit: Int): List<NotificationVerdictEntity>

    suspend fun countersSince(since: Instant): List<VerdictCountRow>
}
```

`service/impl/NotificationVerdictServiceImpl.kt` — `@Service`, конструктор `(repository, stats, uuid: UUIDGeneratorHelper, clock: Clock)`; `record` строит сущность (`id = uuid.generateV1()`, `createdAt = Instant.now(clock)`, enum → `name`, `confidence?.toFloat()`) и `repository.save`; остальные методы делегируют.

`AppSettingKeys`: `const val AI_JUDGE_PRESET_ACTIVE = "ai.judge.preset.active"`, `const val AI_JUDGE_ENABLED = "ai.judge.enabled"`.

- [ ] **Step 5: Интеграционные тесты репозиториев (core, Testcontainers)**

`core/repository/NotificationVerdictRepositoryTest.kt` — наследник `IntegrationTestBase` по образцу `ObjectTrackRepositoryTest`: `@BeforeEach` чистит `notification_verdicts` и `recordings`; хелпер `recording(camId, ts)` сохраняет `RecordingEntity`; хелпер `verdict(camId, ts, verdict = "SUPPRESS", stage = "JUDGE", reason = "DUPLICATE")`. Тесты:
- `findRecentForCamera respects camera, window on both sides and limit` — 4 строки cam2 (ts−7h, ts−1h, ts+1h, ts+7h) и одна cam3; окно ±6h, limit 10 → две строки, новые первыми.
- `findLastPublished returns the newest PUBLISH row of the camera` — PUBLISH старая, PUBLISH новая, SUPPRESS ещё новее → возвращается новая PUBLISH.
- `findLatest and findLatestByCamera order by record_timestamp desc`.
- `deleting a recording cascades to its verdicts`.

`core/repository/JudgeStatsRepositoryTest.kt` — наследник `IntegrationTestBase`; сеет `RecordingEntity` (cam2) и `DetectionEntity` (`detectionTimestamp` = времени записи):
- `staticScore counts recordings and days with IoU at or above the threshold` — три записи в разные дни с `car` bbox `[100,100,200,200]` (IoU 1.0), одна с `[150,150,250,250]` (IoU ≈ 0.14 → мимо), одна другого класса, одна вне окна, плюс сама «кандидатская» запись (исключается по id) → `recordings = 3`, `days = 3`, `firstSeen`/`lastSeen` = крайние из трёх.
- `recordingsInWindow counts only this camera inside the window`.
- `verdictCounters groups by stage, verdict and reason since the instant` — сеет через `NotificationVerdictRepository` и проверяет строки.

- [ ] **Step 6: Прогнать тесты**

Run: `./gradlew :frigate-analyzer-service:test :frigate-analyzer-core:test --tests '*NotificationVerdict*' --tests '*JudgeStats*'`
Expected: PASS (интеграционные поднимают compose с liquibase — миграция `1.0.6.xml` применяется).

- [ ] **Step 7: Commit**

```bash
git add docker/liquibase/migration/1.0.6.xml docker/liquibase/migration/master_frigate_analyzer.xml modules/model modules/service modules/core/src/test
git commit -m "feat(service): notification_verdicts table, verdict service and judge statistics queries" -m "Claude-Session: <SESSION_URL>"
```

---
### Task 5: Проводка судьи в `core`: настройки над `app_settings`, зона, guard, scope, snooze

**Files:**
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/application/AppSettingsJudgeRuntimeSettings.kt`
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/judge/JudgeZoneResolver.kt`, `judge/AiJudgeGuard.kt`, `judge/JudgeCoroutineScope.kt`, `judge/SnoozeRegistry.kt`, `judge/JudgeCandidate.kt`
- Test (create): `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/application/JudgeRuntimeSettingsWiringTest.kt`, `core/judge/JudgeZoneResolverTest.kt`, `core/judge/AiJudgeGuardTest.kt`, `core/judge/SnoozeRegistryTest.kt`

**Interfaces:**
- Consumes: `JudgeRuntimeSettings`, `JudgeProperties`, `AppSettingsService`, `AppSettingKeys.AI_JUDGE_*`, `TelegramUserService.findByUsernameIgnoreCase/getUserZone`, `TelegramProperties.owner`, `DescriptionProperties.enabled`, `NotificationDecision`, `RecordingDto`, `DetectionEntity`, `FrameData`, `VisualizedFrameData`, `DescriptionResult`.
- Produces: `AppSettingsJudgeRuntimeSettings : JudgeRuntimeSettings`; `JudgeZoneResolver.resolve(): ZoneId`; `JudgeCoroutineScope`; `SnoozeRegistry` (`covers(camId, recordTimestamp, classes): CameraSnooze?`, `set(camId, anchor, minutes, classes)`, `clear(camId)`, `snapshot(): List<CameraSnooze>`), `CameraSnooze(camId, anchor, until, covered: Map<String, Int>)`; `JudgeCandidate`.

- [ ] **Step 1: Падающие тесты**

`core/judge/SnoozeRegistryTest.kt`:

```kotlin
class SnoozeRegistryTest {
    private val registry = SnoozeRegistry()
    private val anchor = Instant.parse("2026-09-05T10:00:00Z")

    @Test
    fun `covers the same classes with equal or smaller counts inside the window in both directions`() {
        registry.set("cam2", anchor, minutes = 15, classes = mapOf("person" to 1))
        assertNotNull(registry.covers("cam2", anchor.plusSeconds(600), mapOf("person" to 1)))
        assertNotNull(registry.covers("cam2", anchor.minusSeconds(600), mapOf("person" to 1)))
        assertNull(registry.covers("cam2", anchor.plusSeconds(16 * 60), mapOf("person" to 1)))
    }

    @Test
    fun `a new class or a larger count wakes the judge`() {
        registry.set("cam2", anchor, minutes = 15, classes = mapOf("person" to 1))
        assertNull(registry.covers("cam2", anchor.plusSeconds(60), mapOf("person" to 2)))
        assertNull(registry.covers("cam2", anchor.plusSeconds(60), mapOf("person" to 1, "car" to 1)))
        assertNotNull(registry.covers("cam2", anchor.plusSeconds(60), mapOf("person" to 1)))
    }

    @Test
    fun `cameras are independent and a new set replaces the previous coverage`() {
        registry.set("cam2", anchor, 15, mapOf("person" to 1))
        assertNull(registry.covers("cam3", anchor, mapOf("person" to 1)))
        registry.set("cam2", anchor.plusSeconds(60), 15, mapOf("car" to 1))
        assertNull(registry.covers("cam2", anchor.plusSeconds(120), mapOf("person" to 1)))
        assertNotNull(registry.covers("cam2", anchor.plusSeconds(120), mapOf("car" to 1)))
    }

    @Test
    fun `zero minutes clears, snapshot lists active snoozes with until`() {
        registry.set("cam2", anchor, 15, mapOf("person" to 1))
        assertEquals(anchor.plusSeconds(900), registry.snapshot().single().until)
        registry.set("cam2", anchor, 0, mapOf("person" to 1))
        assertTrue(registry.snapshot().isEmpty())
        assertNull(registry.covers("cam2", anchor, mapOf("person" to 1)))
    }

    @Test
    fun `empty class map is never covered`() {
        registry.set("cam2", anchor, 15, mapOf("person" to 1))
        assertNull(registry.covers("cam2", anchor, emptyMap()))
    }
}
```

`core/judge/JudgeZoneResolverTest.kt`:

```kotlin
class JudgeZoneResolverTest {
    private val userService = mockk<TelegramUserService>()
    private val telegramProperties = TelegramProperties(enabled = true, botToken = "t", owner = "owner")

    private fun resolver(zone: String) = JudgeZoneResolver(JudgeProperties(zone = zone), userService, telegramProperties)

    @Test
    fun `explicit zone wins`() = runTest { assertEquals(ZoneId.of("Asia/Tomsk"), resolver("Asia/Tomsk").resolve()) }

    @Test
    fun `falls back to the owner zone`() =
        runTest {
            coEvery { userService.findByUsernameIgnoreCase("owner") } returns mockk { every { chatId } returns 42L }
            coEvery { userService.getUserZone(42L) } returns ZoneId.of("Europe/Moscow")
            assertEquals(ZoneId.of("Europe/Moscow"), resolver("").resolve())
        }

    @Test
    fun `owner without chat or a failing lookup falls back to the JVM zone`() =
        runTest {
            coEvery { userService.findByUsernameIgnoreCase("owner") } returns null
            assertEquals(ZoneId.systemDefault(), resolver("").resolve())
            coEvery { userService.findByUsernameIgnoreCase("owner") } throws IllegalStateException("db down")
            assertEquals(ZoneId.systemDefault(), resolver("").resolve())
        }
}
```

`core/judge/AiJudgeGuardTest.kt`: `AiJudgeGuard(DescriptionProperties(enabled = false, provider = "claude", common = <любая валидная секция>), JudgeProperties(enabled = true)).validate()` бросает `IllegalStateException` с текстом, содержащим `APP_AI_JUDGE_ENABLED` и `APP_AI_DESCRIPTION_ENABLED`; при `enabled = true` не бросает.

`core/application/JudgeRuntimeSettingsWiringTest.kt` — по образцу `DescriptionRuntimeSettingsWiringTest`: `ApplicationContextRunner` с `AiDescriptionAutoConfiguration`, `AppSettingsDescriptionRuntimeSettings`, `AppSettingsJudgeRuntimeSettings`, свойствами описаний из того теста плюс `application.ai.judge.enabled=true`; проверяет, что бин `JudgeRuntimeSettings` — `AppSettingsJudgeRuntimeSettings` (не in-memory), а `judgeEnabled()` читает `AppSettingKeys.AI_JUDGE_ENABLED` с дефолтом `true` (мок `AppSettingsService` отвечает `false` → `false`).

- [ ] **Step 2: Убедиться, что не компилируется**

Run: `./gradlew :frigate-analyzer-core:test --tests '*SnoozeRegistryTest' --tests '*JudgeZoneResolverTest'`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Реализация**

`core/judge/SnoozeRegistry.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.judge

data class CameraSnooze(
    val camId: String,
    val anchor: Instant,
    val until: Instant,
    /** Класс → сколько объектов этого класса было в оценённой записи. */
    val covered: Map<String, Int>,
) {
    val minutes: Long get() = Duration.between(anchor, until).toMinutes()
}

/**
 * Snooze по камерам, только память процесса. Покрытие считается по модулю разницы времени записи и
 * якоря — бэклог разбирается от новых к старым, тот же приём, что у cooldown REAPPEARED.
 */
class SnoozeRegistry {
    private val byCamera = ConcurrentHashMap<String, CameraSnooze>()

    fun covers(
        camId: String,
        recordTimestamp: Instant,
        classes: Map<String, Int>,
    ): CameraSnooze? {
        if (classes.isEmpty()) return null
        val snooze = byCamera[camId] ?: return null
        val window = Duration.between(snooze.anchor, snooze.until)
        if (Duration.between(snooze.anchor, recordTimestamp).abs() > window) return null
        val escalated = classes.any { (cls, count) -> count > (snooze.covered[cls] ?: 0) }
        return if (escalated) null else snooze
    }

    fun set(
        camId: String,
        anchor: Instant,
        minutes: Int,
        classes: Map<String, Int>,
    ) {
        if (minutes <= 0 || classes.isEmpty()) {
            byCamera.remove(camId)
            return
        }
        byCamera[camId] = CameraSnooze(camId, anchor, anchor.plus(Duration.ofMinutes(minutes.toLong())), classes.toMap())
    }

    fun clear(camId: String) {
        byCamera.remove(camId)
    }

    fun snapshot(): List<CameraSnooze> = byCamera.values.sortedBy { it.camId }
}
```

`core/judge/JudgeCandidate.kt`:

```kotlin
data class JudgeCandidate(
    val recording: RecordingDto,
    val detections: List<DetectionEntity>,
    val decision: NotificationDecision,
    /** Сырые кадры с ответами детектора: размеры и детекции по кадрам. */
    val frames: List<FrameData>,
    /** Кадры с рамками в порядке ранжирования визуализации — те же, что уйдут в Telegram. */
    val visualizedFrames: List<VisualizedFrameData>,
    val descriptionSupplier: (() -> Deferred<Result<DescriptionResult>>)?,
)
```

`core/judge/JudgeCoroutineScope.kt` — копия `DescriptionCoroutineScope` с именем `JudgeCoroutineScope`, `@Component`, `@ConditionalOnProperty("application.ai.judge.enabled", havingValue = "true")`, текст WARN про «Judge coroutines».

`core/judge/JudgeZoneResolver.kt`:

```kotlin
@Component
@ConditionalOnProperty("application.ai.judge.enabled", havingValue = "true")
class JudgeZoneResolver(
    private val judgeProperties: JudgeProperties,
    private val userService: TelegramUserService,
    private val telegramProperties: TelegramProperties,
) {
    suspend fun resolve(): ZoneId {
        if (judgeProperties.zone.isNotBlank()) return ZoneId.of(judgeProperties.zone)
        return try {
            val chatId = userService.findByUsernameIgnoreCase(telegramProperties.owner)?.chatId
            if (chatId == null) ZoneId.systemDefault() else userService.getUserZone(chatId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Cannot resolve the owner's timezone for the judge context; using ${ZoneId.systemDefault()}" }
            ZoneId.systemDefault()
        }
    }
}
```

`core/judge/AiJudgeGuard.kt`:

```kotlin
@Component
@ConditionalOnProperty("application.ai.judge.enabled", havingValue = "true")
class AiJudgeGuard(
    private val descriptionProperties: DescriptionProperties,
    private val judgeProperties: JudgeProperties,
) {
    @PostConstruct
    fun validate() {
        if (!judgeProperties.enabled) return
        check(descriptionProperties.enabled) {
            "APP_AI_JUDGE_ENABLED=true requires APP_AI_DESCRIPTION_ENABLED=true: the judge runs on the AI preset " +
                "catalog that only exists with descriptions enabled. Enable descriptions or set APP_AI_JUDGE_ENABLED=false."
        }
    }
}
```

`core/application/AppSettingsJudgeRuntimeSettings.kt` — копия `AppSettingsDescriptionRuntimeSettings` над ключами `AI_JUDGE_PRESET_ACTIVE` / `AI_JUDGE_ENABLED`, условие `application.ai.judge.enabled=true`, `sourceName = "app_settings"`, INFO в `init`: `"Judge runtime settings: app_settings (the choice survives a restart)"`.

- [ ] **Step 4: Прогнать тесты**

Run: `./gradlew :frigate-analyzer-core:test --tests '*judge*' --tests '*JudgeRuntimeSettingsWiringTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/core
git commit -m "feat(core): judge runtime settings, zone resolver, guard, scope and snooze registry" -m "Claude-Session: <SESSION_URL>"
```

---

### Task 6: `JudgeContextBuilder`

**Files:**
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/judge/JudgeContext.kt`, `judge/JudgeContextBuilder.kt`
- Test (create): `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/judge/JudgeContextBuilderTest.kt`

**Interfaces:**
- Consumes: `JudgeCandidate`, `RepresentativeBbox`, `JudgeStatsRepository.staticScore/recordingsInWindow`, `ObjectTrackRepository.findActive(camId, min, max)`, `ObjectTrackerProperties.ttl`, `NotificationVerdictService.recentForCamera/lastPublished`, `JudgeProperties` (`staticWindow`, `staticIou`, `historyWindow`, `historyLimit`, `cameras`), `JsonMapper` (бин `internalObjectMapper`).
- Produces: `JudgeContextBuilder.build(candidate, objects, zone): JudgeContextResult(json: String, errors: List<String>)`.

- [ ] **Step 1: Падающий тест**

`core/judge/JudgeContextBuilderTest.kt` (MockK; мапper — `TestObjectMappers.internalMapper()` из `core/testsupport`):

```kotlin
class JudgeContextBuilderTest {
    private val stats = mockk<JudgeStatsRepository>()
    private val tracks = mockk<ObjectTrackRepository>()
    private val verdicts = mockk<NotificationVerdictService>()
    private val mapper = TestObjectMappers.internalMapper()
    private val properties = JudgeProperties(cameras = mapOf("cam4" to JudgeProperties.CameraSection(notes = "Огород за домом")))
    private val trackerProperties = ObjectTrackerProperties(ttl = Duration.ofHours(12))
    private val builder = JudgeContextBuilder(stats, tracks, verdicts, properties, trackerProperties, mapper)

    private val recordingId = UUID.randomUUID()
    private val ts = Instant.parse("2026-09-04T07:22:48Z")
    private val zone = ZoneId.of("Europe/Moscow")
    private val recording = RecordingDto(id = recordingId, creationTimestamp = ts, filePath = "/r/cam4/22.48.mp4", fileCreationTimestamp = ts, camId = "cam4",
        recordDate = LocalDate.of(2026, 9, 4), recordTime = LocalTime.of(7, 22, 48), recordTimestamp = ts, startProcessingTimestamp = ts,
        processTimestamp = ts.plusSeconds(51), processAttempts = 1, detectionsCount = 2, analyzeTime = 5, analyzedFramesCount = 2, errorMessage = null)
    private val frame = FrameData(recordingId, 0, ByteArray(1),
        DetectResponse(listOf(Detection(3, "motorcycle", 0.628, BBox(151.0, 1387.0, 441.0, 1651.0))), 0, ImageSize(2560, 1920), "yolo26x.pt"))
    private val objects = listOf(RepresentativeBbox("motorcycle", 151f, 1387f, 441f, 1651f))
    private val decision = NotificationDecision(true, NotificationDecisionReason.NEW_OBJECTS,
        DetectionDelta(newTracksCount = 1, matchedTracksCount = 0, staleTracksCount = 0, newClasses = listOf("motorcycle")))
    private val candidate = JudgeCandidate(recording, listOf(detection("motorcycle", 0.628f)), decision, listOf(frame), emptyList(), null)

    private fun detection(cls: String, conf: Float) = DetectionEntity(UUID.randomUUID(), ts, recordingId, ts, 0, "yolo26x.pt", 3, cls, conf, 151f, 1387f, 441f, 1651f)

    private fun happyStubs() {
        coEvery { stats.staticScore("cam4", "motorcycle", 151.0, 1387.0, 441.0, 1651.0, ts.minus(Duration.ofDays(7)), ts, recordingId, 0.4, "Europe/Moscow") } returns
            StaticScore(18, 7, Instant.parse("2026-08-28T09:04:10Z"), Instant.parse("2026-09-03T13:54:36Z"))
        coEvery { stats.recordingsInWindow("cam4", ts.minus(Duration.ofDays(7)), ts) } returns 60412
        coEvery { tracks.findActive("cam4", ts.minus(Duration.ofHours(12)), ts.plus(Duration.ofHours(12))) } returns emptyList()
        coEvery { verdicts.recentForCamera("cam4", ts.minus(Duration.ofHours(6)), ts.plus(Duration.ofHours(6)), 10) } returns emptyList()
        coEvery { verdicts.lastPublished("cam4") } returns null
    }

    @Test
    fun `builds every block with snake_case keys and local times in the given zone`() =
        runTest {
            happyStubs()
            val result = builder.build(candidate, objects, zone)
            val root = mapper.readTree(result.json)
            assertTrue(result.errors.isEmpty())
            assertEquals("cam4", root["recording"]["cam"].asString())
            assertEquals("2026-09-04T10:22:48+03:00", root["recording"]["time"].asString())
            assertEquals("Europe/Moscow", root["recording"]["zone"].asString())
            assertEquals(51, root["recording"]["processing_lag_seconds"].asInt())
            assertEquals(2560, root["frames"][0]["width"].asInt())
            assertEquals("motorcycle", root["frames"][0]["detections"][0]["class"].asString())
            assertEquals(18, root["objects"][0]["static"]["recordings"].asInt())
            assertEquals(60412, root["objects"][0]["static"]["recordings_in_window"].asInt())
            assertEquals("NEW_OBJECTS", root["tracker"]["reason"].asString())
            assertEquals("motorcycle", root["tracker"]["new_classes"][0].asString())
            assertTrue(root["active_tracks"].isEmpty)
            assertTrue(root["recent_verdicts"].isEmpty)
            assertTrue(root["last_published"].isNull)
            assertEquals("Огород за домом", root["camera_notes"].asString())
        }

    @Test
    fun `a failing provider degrades to an error marker without failing the build`() =
        runTest {
            happyStubs()
            coEvery { stats.staticScore(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws IllegalStateException("db down")
            val result = builder.build(candidate, objects, zone)
            val root = mapper.readTree(result.json)
            assertEquals("IllegalStateException", root["objects"][0]["static"]["error"].asString())
            assertEquals(listOf("objects.static"), result.errors)
        }

    @Test
    fun `unknown camera has empty notes and absent tracks are marked as unmatched`() =
        runTest {
            happyStubs()
            coEvery { tracks.findActive(any(), any(), any()) } returns
                listOf(ObjectTrackEntity(UUID.randomUUID(), ts.minusSeconds(3600), "cam4", "person", 204f, 1408f, 460f, 1652f, ts.minusSeconds(60), UUID.randomUUID()))
            val root = mapper.readTree(builder.build(candidate.copy(recording = recording.copy(camId = "cam9")), objects, zone).json)
            assertEquals("", root["camera_notes"].asString())
            assertEquals(false, root["active_tracks"][0]["matched_now"].asBoolean())
        }
}
```

- [ ] **Step 2: Убедиться, что не компилируется**

Run: `./gradlew :frigate-analyzer-core:test --tests '*JudgeContextBuilderTest'`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Реализация**

`core/judge/JudgeContext.kt` — data-классы блоков, все с `@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)` (`tools.jackson.databind.annotation.JsonNaming`, `tools.jackson.databind.PropertyNamingStrategies`), времена уже строками ISO-8601 со смещением:

```kotlin
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class RecordingBlock(val cam: String, val time: String, val zone: String, val processingLagSeconds: Long?)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class FrameDetectionBlock(@JsonProperty("class") val className: String, val confidence: Double, val bbox: List<Int>)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class FrameBlock(val index: Int, val width: Int, val height: Int, val detections: List<FrameDetectionBlock>)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class StaticBlock(val recordings: Long, val days: Long, val firstSeen: String?, val lastSeen: String?, val recordingsInWindow: Long)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ObjectBlock(@JsonProperty("class") val className: String, val confidence: Double, val bbox: List<Int>, val framesSeen: Int, val static: Any?)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class TrackerBlock(val reason: String, val newClasses: List<String>, val reappearedClasses: List<String>, val maxAbsence: String?)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ActiveTrackBlock(@JsonProperty("class") val className: String, val bbox: List<Int>, val firstSeen: String?, val lastSeen: String?, val matchedNow: Boolean)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class VerdictBlock(val time: String, val stage: String, val verdict: String, val reason: String, val classes: String, val summary: String?)

data class ErrorBlock(val error: String)

data class JudgeContextResult(val json: String, val errors: List<String>)
```

`core/judge/JudgeContextBuilder.kt`:

```kotlin
@Component
@ConditionalOnProperty("application.ai.judge.enabled", havingValue = "true")
class JudgeContextBuilder(
    private val stats: JudgeStatsRepository,
    private val tracks: ObjectTrackRepository,
    private val verdicts: NotificationVerdictService,
    private val properties: JudgeProperties,
    private val trackerProperties: ObjectTrackerProperties,
    private val mapper: JsonMapper,
) {
    suspend fun build(
        candidate: JudgeCandidate,
        objects: List<RepresentativeBbox>,
        zone: ZoneId,
    ): JudgeContextResult {
        val errors = mutableListOf<String>()
        val recording = candidate.recording
        val ts = recording.recordTimestamp
        val root = mapper.createObjectNode()
        root.set<JsonNode>("recording", mapper.valueToTree(RecordingBlock(recording.camId, format(ts, zone), zone.id,
            recording.processTimestamp?.let { Duration.between(ts, it).seconds })))
        root.set<JsonNode>("frames", mapper.valueToTree(framesBlock(candidate.frames)))
        root.set<JsonNode>("objects", mapper.valueToTree(objects.map { objectBlock(candidate, it, zone, errors) }))
        root.set<JsonNode>("tracker", mapper.valueToTree(trackerBlock(candidate.decision)))
        root.set<JsonNode>("active_tracks", block("active_tracks", errors) { activeTracks(candidate, zone) })
        root.set<JsonNode>("recent_verdicts", block("recent_verdicts", errors) { recentVerdicts(recording, zone) })
        root.set<JsonNode>("last_published", block("last_published", errors) { verdicts.lastPublished(recording.camId)?.let { verdictBlock(it, zone) } })
        root.put("camera_notes", properties.cameras[recording.camId]?.notes.orEmpty())
        return JudgeContextResult(mapper.writeValueAsString(root), errors)
    }

    private suspend fun objectBlock(candidate: JudgeCandidate, obj: RepresentativeBbox, zone: ZoneId, errors: MutableList<String>): ObjectBlock {
        val ts = candidate.recording.recordTimestamp
        val ofClass = candidate.detections.filter { it.className == obj.className }
        val confidence = ofClass.maxOfOrNull { it.confidence.toDouble() } ?: 0.0
        val framesSeen = ofClass.map { it.frameIndex }.distinct().size
        val static =
            try {
                val from = ts.minus(properties.staticWindow)
                val score = stats.staticScore(candidate.recording.camId, obj.className, obj.x1.toDouble(), obj.y1.toDouble(), obj.x2.toDouble(), obj.y2.toDouble(),
                    from, ts, candidate.recording.id, properties.staticIou, zone.id)
                StaticBlock(score.recordings, score.days, score.firstSeen?.let { format(it, zone) }, score.lastSeen?.let { format(it, zone) },
                    stats.recordingsInWindow(candidate.recording.camId, from, ts))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Judge context: static score failed for ${candidate.recording.id}" }
                errors += "objects.static"
                ErrorBlock(e::class.simpleName ?: "Exception")
            }
        return ObjectBlock(obj.className, confidence, bbox(obj.x1, obj.y1, obj.x2, obj.y2), framesSeen, static)
    }
    // framesBlock: FrameBlock на каждый кадр с detectResponse != null, отсортированный по frameIndex; bbox из BBox(Double) округляется в Int.
    // trackerBlock: reason.name, delta?.newClasses ?: [], delta?.reappearedClasses ?: [], delta?.maxAbsence?.toString().
    // activeTracks: tracks.findActive(cam, ts - ttl, ts + ttl) → ActiveTrackBlock(matchedNow = lastRecordingId == recording.id).
    // recentVerdicts: verdicts.recentForCamera(cam, ts - historyWindow, ts + historyWindow, historyLimit) → VerdictBlock.
    // block(name, errors) { … }: valueToTree(result), для null — NullNode.instance (tools.jackson.databind.node.NullNode),
    //   при исключении valueToTree(ErrorBlock(e::class.simpleName)) + errors += name; CancellationException пробрасывается.
    // format(instant, zone) = instant.atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME).
}
```

- [ ] **Step 4: Прогнать тесты**

Run: `./gradlew :frigate-analyzer-core:test --tests '*JudgeContextBuilderTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/core
git commit -m "feat(core): judge context builder with per-block degradation" -m "Claude-Session: <SESSION_URL>"
```

---
### Task 7: `NotificationJudgeService` — оркестрация пяти шагов

**Files:**
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/judge/NotificationJudgeService.kt`
- Test (create): `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/judge/NotificationJudgeServiceTest.kt`

**Interfaces:**
- Consumes: `JudgeAgent`, `JudgeRequest`, `JudgeOutcome`, `JudgeVerdict`, `JudgeRuntimeSettings`, `JudgeRateLimiter`, `JudgeProperties`, `JudgeContextBuilder`, `JudgeZoneResolver`, `SnoozeRegistry`, `CameraSnooze`, `JudgeCandidate`, `JudgeCoroutineScope`, `NotificationVerdictService.record`, `NewNotificationVerdict`, `VerdictStage/VerdictDecision/VerdictReason`, `BboxClusteringHelper.cluster`, `ObjectTrackerProperties.innerIou/confidenceFloor`, `TelegramNotificationService.sendRecordingNotification`, `DescriptionProperties.common.language`, `Clock`.
- Produces: `NotificationJudgeService.submit(candidate): Job`, `suspend fun process(candidate)` (internal, для тестов), `fun snapshotSnoozes(): List<CameraSnooze>`.

- [ ] **Step 1: Падающие тесты**

`core/judge/NotificationJudgeServiceTest.kt`:

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationJudgeServiceTest {
    private val agent = mockk<JudgeAgent>()
    private val runtimeSettings = mockk<JudgeRuntimeSettings>()
    private val contextBuilder = mockk<JudgeContextBuilder>()
    private val zoneResolver = mockk<JudgeZoneResolver>()
    private val verdicts = mockk<NotificationVerdictService>()
    private val limiter = mockk<JudgeRateLimiter>()
    private val telegram = mockk<TelegramNotificationService>(relaxed = true)
    private val clock = Clock.fixed(Instant.parse("2026-09-05T10:00:00Z"), ZoneOffset.UTC)
    private val ts = Instant.parse("2026-09-05T09:59:00Z")
    private val recorded = mutableListOf<NewNotificationVerdict>()

    private fun recording(camId: String = "cam2", id: UUID = UUID.randomUUID(), at: Instant = ts) =
        RecordingDto(id, at, "/r/$camId/x.mp4", at, camId, LocalDate.of(2026, 9, 5), LocalTime.of(9, 59), at, at, at, 1, 1, 5, 2, null)

    // x1 сдвигается на 500 px на каждый объект: одинаковые bbox BboxClusteringHelper склеил бы в один
    // объект, и тест эскалации «второй человек» проверял бы не то.
    private fun detection(recordingId: UUID, cls: String = "person", x1: Float = 10f) =
        DetectionEntity(UUID.randomUUID(), ts, recordingId, ts, 0, "yolo26x.pt", 0, cls, 0.9f, x1, 10f, x1 + 90f, 200f)

    private fun candidate(camId: String = "cam2", classes: List<String> = listOf("person"), at: Instant = ts): JudgeCandidate {
        val rec = recording(camId, at = at)
        return JudgeCandidate(rec, classes.mapIndexed { i, cls -> detection(rec.id, cls, x1 = 10f + i * 500f) },
            NotificationDecision(true, NotificationDecisionReason.NEW_OBJECTS),
            emptyList(), listOf(VisualizedFrameData(0, ByteArray(1), 1)), null)
    }

    private fun outcome(decision: JudgeVerdict.Decision, reason: JudgeVerdict.Reason, snooze: Int = 0) =
        JudgeOutcome(JudgeVerdict(decision, reason, 0.9, "sum", snooze, ""), "claude-sonnet", "sonnet", Duration.ofSeconds(3))

    private fun TestScope.service(): NotificationJudgeService {
        val agentProvider = mockk<ObjectProvider<JudgeAgent>>().also { every { it.getIfAvailable() } returns agent }
        val limiterProvider = mockk<ObjectProvider<JudgeRateLimiter>>().also { every { it.getIfAvailable() } returns limiter }
        coEvery { runtimeSettings.judgeEnabled() } returns true
        coEvery { limiter.tryAcquire() } returns true
        coEvery { zoneResolver.resolve() } returns ZoneId.of("UTC")
        coEvery { contextBuilder.build(any(), any(), any()) } returns JudgeContextResult("{}", emptyList())
        coEvery { verdicts.record(capture(recorded)) } answers { mockk() }
        return NotificationJudgeService(
            agentProvider, runtimeSettings, contextBuilder, zoneResolver, verdicts, limiterProvider, telegram,
            JudgeProperties(enabled = true), ObjectTrackerProperties(), DescriptionProperties(true, "claude", commonSection()),
            JudgeCoroutineScope(CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())), clock,
        )
    }

    @Test
    fun `PUBLISH records a JUDGE verdict, sets the snooze and sends`() =
        runTest {
            coEvery { agent.judge(any()) } returns outcome(JudgeVerdict.Decision.PUBLISH, JudgeVerdict.Reason.NEW_EVENT, snooze = 15)
            val c = candidate()
            service().process(c)
            val v = recorded.single()
            assertEquals(VerdictStage.JUDGE, v.stage); assertEquals(VerdictDecision.PUBLISH, v.verdict); assertEquals(VerdictReason.NEW_EVENT, v.reason)
            assertEquals("person:1", v.classes); assertEquals(ts.plusSeconds(900), v.snoozeUntil); assertEquals("claude-sonnet", v.presetId); assertEquals(3000, v.latencyMs)
            coVerify(exactly = 1) { telegram.sendRecordingNotification(c.recording, c.visualizedFrames, null) }
        }

    @Test
    fun `SUPPRESS records and does not send`() =
        runTest {
            coEvery { agent.judge(any()) } returns outcome(JudgeVerdict.Decision.SUPPRESS, JudgeVerdict.Reason.STATIC_OBJECT)
            service().process(candidate())
            assertEquals(VerdictDecision.SUPPRESS, recorded.single().verdict)
            coVerify(exactly = 0) { telegram.sendRecordingNotification(any(), any(), any()) }
        }

    @Test
    fun `a snoozed candidate is suppressed without calling the agent, an escalation wakes it`() =
        runTest {
            coEvery { agent.judge(any()) } returns outcome(JudgeVerdict.Decision.PUBLISH, JudgeVerdict.Reason.NEW_EVENT, snooze = 15)
            val s = service()
            s.process(candidate())
            s.process(candidate(at = ts.plusSeconds(60)))
            assertEquals(VerdictStage.SNOOZE, recorded[1].stage); assertEquals(VerdictReason.SNOOZED, recorded[1].reason)
            coVerify(exactly = 1) { agent.judge(any()) }
            s.process(candidate(classes = listOf("person", "person"), at = ts.plusSeconds(120)))
            coVerify(exactly = 2) { agent.judge(any()) }
        }

    @Test
    fun `runtime switch off bypasses and sends`() =
        runTest {
            val s = service()
            coEvery { runtimeSettings.judgeEnabled() } returns false
            s.process(candidate())
            assertEquals(VerdictStage.BYPASS, recorded.single().stage); assertEquals(VerdictReason.JUDGE_OFF, recorded.single().reason)
            coVerify(exactly = 0) { agent.judge(any()) }
            coVerify(exactly = 1) { telegram.sendRecordingNotification(any(), any(), any()) }
        }

    @Test
    fun `every agent failure fails over to sending with its own reason`() =
        runTest {
            val cases = listOf(
                DescriptionException.Timeout() to VerdictReason.TIMEOUT,
                DescriptionException.RateLimited() to VerdictReason.RATE_LIMITED,
                DescriptionException.Unauthorized("401") to VerdictReason.UNAUTHORIZED,
                DescriptionException.InvalidResponse() to VerdictReason.INVALID_RESPONSE,
                DescriptionException.Transport() to VerdictReason.TRANSPORT,
                IllegalStateException("boom") to VerdictReason.TRANSPORT,
            )
            val s = service()
            for ((failure, reason) in cases) {
                recorded.clear()
                coEvery { agent.judge(any()) } throws failure
                s.process(candidate())
                assertEquals(VerdictStage.FAILOVER, recorded.single().stage); assertEquals(reason, recorded.single().reason)
                assertEquals(VerdictDecision.PUBLISH, recorded.single().verdict)
            }
            coVerify(exactly = cases.size) { telegram.sendRecordingNotification(any(), any(), any()) }
        }

    @Test
    fun `an exhausted limiter fails over as RATE_LIMITED without calling the agent`() =
        runTest {
            val s = service()
            coEvery { limiter.tryAcquire() } returns false
            s.process(candidate())
            assertEquals(VerdictReason.RATE_LIMITED, recorded.single().reason)
            coVerify(exactly = 0) { agent.judge(any()) }
        }

    @Test
    fun `a context builder failure fails over as CONTEXT_ERROR`() =
        runTest {
            val s = service()
            coEvery { contextBuilder.build(any(), any(), any()) } throws IllegalStateException("db down")
            s.process(candidate())
            assertEquals(VerdictReason.CONTEXT_ERROR, recorded.single().reason)
            coVerify(exactly = 1) { telegram.sendRecordingNotification(any(), any(), any()) }
        }

    @Test
    fun `a failing verdict write does not lose the decision`() =
        runTest {
            coEvery { agent.judge(any()) } returns outcome(JudgeVerdict.Decision.PUBLISH, JudgeVerdict.Reason.NEW_EVENT)
            val s = service()
            coEvery { verdicts.record(any()) } throws IllegalStateException("db down")
            s.process(candidate())
            coVerify(exactly = 1) { telegram.sendRecordingNotification(any(), any(), any()) }
        }

    @Test
    fun `candidates of one camera are judged in order, different cameras in parallel`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            coEvery { agent.judge(any()) } coAnswers {
                val req = firstArg<JudgeRequest>()
                order += req.camId
                if (req.camId == "cam2" && order.count { it == "cam2" } == 1) gate.await()
                outcome(JudgeVerdict.Decision.SUPPRESS, JudgeVerdict.Reason.DUPLICATE)
            }
            val s = service()
            val first = s.submit(candidate("cam2"))
            val second = s.submit(candidate("cam2", at = ts.plusSeconds(60)))
            val other = s.submit(candidate("cam3"))
            runCurrent()
            assertEquals(listOf("cam2", "cam3"), order) // second cam2 waits for the first, cam3 does not
            gate.complete(Unit)
            listOf(first, second, other).joinAll()
            assertEquals(listOf("cam2", "cam3", "cam2"), order)
        }

    private fun commonSection() = DescriptionProperties.CommonSection(language = "ru", shortMaxLength = 200, detailedMaxLength = 1500, maxFrames = 10,
        queueTimeout = Duration.ofSeconds(30), timeout = Duration.ofSeconds(60), maxConcurrent = 2)
}
```

- [ ] **Step 2: Убедиться, что не компилируется**

Run: `./gradlew :frigate-analyzer-core:test --tests '*NotificationJudgeServiceTest'`
Expected: FAIL — `Unresolved reference: NotificationJudgeService`.

- [ ] **Step 3: Реализация**

`core/judge/NotificationJudgeService.kt`:

```kotlin
@Component
@ConditionalOnProperty("application.ai.judge.enabled", havingValue = "true")
class NotificationJudgeService(
    private val agentProvider: ObjectProvider<JudgeAgent>,
    private val runtimeSettings: JudgeRuntimeSettings,
    private val contextBuilder: JudgeContextBuilder,
    private val zoneResolver: JudgeZoneResolver,
    private val verdicts: NotificationVerdictService,
    private val rateLimiterProvider: ObjectProvider<JudgeRateLimiter>,
    private val telegram: TelegramNotificationService,
    private val properties: JudgeProperties,
    private val trackerProperties: ObjectTrackerProperties,
    private val descriptionProperties: DescriptionProperties,
    private val scope: JudgeCoroutineScope,
    private val clock: Clock,
) {
    private val snoozes = SnoozeRegistry()
    private val perCameraMutex = ConcurrentHashMap<String, Mutex>()
    private val queued = ConcurrentHashMap<String, AtomicInteger>()

    /** Точка входа фасада: возвращается сразу, работа идёт в [JudgeCoroutineScope]. */
    fun submit(candidate: JudgeCandidate): Job = scope.launch { process(candidate) }

    fun snapshotSnoozes(): List<CameraSnooze> = snoozes.snapshot()

    internal suspend fun process(candidate: JudgeCandidate) {
        val camId = candidate.recording.camId
        val waiting = queued.computeIfAbsent(camId) { AtomicInteger() }
        if (waiting.incrementAndGet() > QUEUE_WARN_THRESHOLD) {
            logger.warn { "Judge queue for cam=$camId holds ${waiting.get()} candidates; the model is slower than the camera" }
        }
        try {
            perCameraMutex.computeIfAbsent(camId) { Mutex() }.withLock { judgeLocked(candidate) }
        } finally {
            waiting.decrementAndGet()
        }
    }

    private suspend fun judgeLocked(candidate: JudgeCandidate) {
        val recording = candidate.recording
        val objects = BboxClusteringHelper.cluster(candidate.detections, trackerProperties.innerIou, trackerProperties.confidenceFloor)
        val classCounts = objects.groupingBy { it.className }.eachCount().toSortedMap()
        val classes = classCounts.entries.joinToString(",") { "${it.key}:${it.value}" }
        val base = { stage: VerdictStage, decision: VerdictDecision, reason: VerdictReason ->
            NewNotificationVerdict(recording.id, recording.camId, recording.recordTimestamp, stage, decision, reason,
                candidate.decision.reason.name, classes)
        }

        if (!judgeEnabled(recording.id)) {
            record(base(VerdictStage.BYPASS, VerdictDecision.PUBLISH, VerdictReason.JUDGE_OFF))
            send(candidate)
            return
        }
        val snooze = snoozes.covers(recording.camId, recording.recordTimestamp, classCounts)
        if (snooze != null) {
            record(base(VerdictStage.SNOOZE, VerdictDecision.SUPPRESS, VerdictReason.SNOOZED).copy(snoozeUntil = snooze.until))
            return
        }
        val context =
            try {
                contextBuilder.build(candidate, objects, zoneResolver.resolve())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Judge context failed for recording=${recording.id}; sending without a verdict" }
                record(base(VerdictStage.FAILOVER, VerdictDecision.PUBLISH, VerdictReason.CONTEXT_ERROR).copy(error = e.describe()))
                send(candidate)
                return
            }
        val limiter = rateLimiterProvider.getIfAvailable()
        if (limiter != null && !limiter.tryAcquire()) {
            logger.warn { "Judge rate limit reached; sending recording=${recording.id} (cam=${recording.camId}) unjudged" }
            record(base(VerdictStage.FAILOVER, VerdictDecision.PUBLISH, VerdictReason.RATE_LIMITED).copy(contextJson = context.json, error = "local rate limit"))
            send(candidate)
            return
        }
        val agent = agentProvider.getIfAvailable()
        val outcome =
            try {
                checkNotNull(agent) { "no JudgeAgent bean: the AI preset catalog is not available" }
                agent.judge(judgeRequest(candidate, context))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = failoverReason(e)
                logger.warn(e) { "Judge failed for recording=${recording.id} (cam=${recording.camId}) reason=$reason; sending unjudged" }
                record(base(VerdictStage.FAILOVER, VerdictDecision.PUBLISH, reason).copy(contextJson = context.json, error = e.describe()))
                send(candidate)
                return
            }
        val verdict = outcome.verdict
        val decision = if (verdict.decision == JudgeVerdict.Decision.PUBLISH) VerdictDecision.PUBLISH else VerdictDecision.SUPPRESS
        snoozes.set(recording.camId, recording.recordTimestamp, verdict.snoozeMinutes, classCounts)
        val until = snoozes.covers(recording.camId, recording.recordTimestamp, classCounts)?.until
        logger.info {
            "Judge: cam=${recording.camId} verdict=${verdict.decision} reason=${verdict.reason} snooze=${verdict.snoozeMinutes}m " +
                "latency=${outcome.latency.toMillis()}ms preset=${outcome.presetId} recording=${recording.id}"
        }
        record(
            base(VerdictStage.JUDGE, decision, VerdictReason.valueOf(verdict.reason.name)).copy(
                confidence = verdict.confidence, summary = verdict.summary.ifBlank { null }, wanted = verdict.wanted.ifBlank { null },
                snoozeUntil = until, presetId = outcome.presetId, model = outcome.model,
                latencyMs = outcome.latency.toMillis().coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), contextJson = context.json,
            ),
        )
        if (decision == VerdictDecision.PUBLISH) send(candidate)
    }

    private fun judgeRequest(candidate: JudgeCandidate, context: JudgeContextResult): JudgeRequest =
        JudgeRequest(
            recordingId = candidate.recording.id,
            camId = candidate.recording.camId,
            frames = candidate.visualizedFrames.take(properties.maxFrames).sortedBy { it.frameIndex }
                .map { DescriptionRequest.FrameImage(it.frameIndex, it.visualizedBytes) },
            contextJson = context.json,
            language = descriptionProperties.common.language,
            maxSnoozeMinutes = properties.maxSnoozeMinutes,
        )

    private suspend fun judgeEnabled(recordingId: UUID): Boolean =
        try {
            withTimeout(SETTINGS_READ_TIMEOUT) { runtimeSettings.judgeEnabled() }
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) { logger.warn { "Reading the judge switch for $recordingId timed out; failing open" }; true } else throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to read the judge switch for $recordingId; failing open" }
            true
        }

    private suspend fun record(verdict: NewNotificationVerdict) {
        try {
            verdicts.record(verdict)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Failed to store the judge verdict for recording=${verdict.recordingId}; the decision is applied anyway" }
        }
    }

    private suspend fun send(candidate: JudgeCandidate) {
        try {
            telegram.sendRecordingNotification(candidate.recording, candidate.visualizedFrames, candidate.descriptionSupplier)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Failed to send telegram notification for recording ${candidate.recording.id}" }
        }
    }

    private fun failoverReason(e: Exception): VerdictReason =
        when (e) {
            is DescriptionException.Timeout -> VerdictReason.TIMEOUT
            is DescriptionException.RateLimited -> VerdictReason.RATE_LIMITED
            is DescriptionException.Unauthorized -> VerdictReason.UNAUTHORIZED
            is DescriptionException.InvalidResponse -> VerdictReason.INVALID_RESPONSE
            else -> VerdictReason.TRANSPORT
        }

    /** Класс и сообщение без стека и без чужих строк — в колонке 1024 символа и никаких секретов. */
    private fun Throwable.describe(): String = "${this::class.simpleName}: ${message.orEmpty()}".take(ERROR_MAX)

    private companion object {
        val SETTINGS_READ_TIMEOUT = 5.seconds
        const val QUEUE_WARN_THRESHOLD = 20
        const val ERROR_MAX = 1024
    }
}
```

Замечание про `judgeEnabled`: `TimeoutCancellationException` — наследник `CancellationException`, поэтому ветка с таймаутом обязана стоять внутри `catch (e: CancellationException)` (как показано) или отдельным `catch` выше него; иначе таймаут пробросится как отмена.

- [ ] **Step 4: Прогнать тесты**

Run: `./gradlew :frigate-analyzer-core:test --tests '*NotificationJudgeServiceTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/core
git commit -m "feat(core): NotificationJudgeService with per-camera ordering, snooze and fail-open" -m "Claude-Session: <SESSION_URL>"
```

---
### Task 8: Фасад передаёт кандидата судье

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/facade/RecordingProcessingFacade.kt`
- Test (modify): `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/facade/RecordingProcessingFacadeTest.kt`

**Interfaces:**
- Consumes: `NotificationJudgeService.submit(JudgeCandidate)`, `JudgeCandidate`.
- Produces: фасад с параметром `judgeProvider: ObjectProvider<NotificationJudgeService>` (последний в конструкторе).

- [ ] **Step 1: Падающие тесты**

В `RecordingProcessingFacadeTest` хелпер `facade(...)` получает параметр `judge: NotificationJudgeService? = null` и передаёт `judgeProvider` (`ObjectProvider`, `getIfAvailable()` возвращает `judge`). Новые тесты:

```kotlin
    @Test
    fun `with a judge present the facade hands the candidate over and does not send itself`() =
        runTest {
            val judge = mockk<NotificationJudgeService>()
            val captured = slot<JudgeCandidate>()
            every { judge.submit(capture(captured)) } returns Job()
            val (facade, request) = facade(agent = null, judge = judge)

            facade.processAndNotify(request)

            assertEquals(recording, captured.captured.recording)
            assertEquals(NotificationDecisionReason.NEW_OBJECTS, captured.captured.decision.reason)
            assertEquals(request.frames, captured.captured.frames)
            assertEquals(1, captured.captured.visualizedFrames.size)
            coVerify(exactly = 0) { telegramNotificationService.sendRecordingNotification(any(), any(), any()) }
        }

    @Test
    fun `with a judge present the description supplier travels with the candidate`() =
        runTest {
            val judge = mockk<NotificationJudgeService>()
            val captured = slot<JudgeCandidate>()
            every { judge.submit(capture(captured)) } returns Job()
            val agent = mockk<DescriptionAgent>()
            val (facade, request) = facade(agent = agent, judge = judge)

            facade.processAndNotify(request)

            assertNotNull(captured.captured.descriptionSupplier)
        }

    @Test
    fun `a suppressed decision never reaches the judge`() =
        runTest {
            val judge = mockk<NotificationJudgeService>()
            coEvery { notificationDecisionService.evaluate(any(), any(), any()) } returns
                NotificationDecision(shouldNotify = false, reason = NotificationDecisionReason.ALL_REPEATED)
            val (facade, request) = facade(agent = null, judge = judge)

            facade.processAndNotify(request)

            verify(exactly = 0) { judge.submit(any()) }
        }
```

Существующие тесты не меняются: без судьи (`judge = null`) поведение прежнее.

- [ ] **Step 2: Убедиться, что не компилируется**

Run: `./gradlew :frigate-analyzer-core:test --tests '*RecordingProcessingFacadeTest'`
Expected: FAIL — нет параметра `judgeProvider`.

- [ ] **Step 3: Реализация**

В конструктор `RecordingProcessingFacade` добавить последним параметром:

```kotlin
    // ObjectProvider: бина нет при application.ai.judge.enabled=false, и фасад тогда отправляет сам.
    private val judgeProvider: ObjectProvider<NotificationJudgeService>,
```

В `processAndNotify` после построения `descriptionSupplier` и до `try { telegramNotificationService.sendRecordingNotification(...) }`:

```kotlin
        val judge = judgeProvider.getIfAvailable()
        if (judge != null) {
            // Судья держит кандидата на время ответа модели, поэтому уходит в свой scope: consumer
            // pipeline возвращается к кадрам сразу. Отправка при PUBLISH — внутри судьи, тем же
            // supplier-ом описания, что построен выше.
            judge.submit(
                JudgeCandidate(
                    recording = recording,
                    detections = savedResult.detections,
                    decision = decision,
                    frames = request.frames,
                    visualizedFrames = visualizedFrames,
                    descriptionSupplier = descriptionSupplier,
                ),
            )
            return
        }
```

- [ ] **Step 4: Прогнать тесты**

Run: `./gradlew :frigate-analyzer-core:test --tests '*RecordingProcessingFacadeTest'`
Expected: PASS, включая все прежние тесты фасада.

- [ ] **Step 5: Commit**

```bash
git add modules/core
git commit -m "feat(core): route notification candidates through the judge when it is enabled" -m "Claude-Session: <SESSION_URL>"
```

---

### Task 9: Секция судьи в `/status` (REST и Telegram)

**Files:**
- Modify: `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/response/StatusResponse.kt`
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/StatusService.kt`
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/StatusMessageFormatter.kt`
- Modify: `modules/telegram/src/main/resources/messages_ru.properties`, `messages_en.properties`
- Test (modify): `modules/telegram/src/test/kotlin/.../service/impl/StatusMessageFormatterTest.kt`, `modules/core/src/test/kotlin/.../controller/StatusControllerTest.kt`; Test (create): `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/StatusServiceJudgeTest.kt`

**Interfaces:**
- Consumes: `NotificationJudgeService.snapshotSnoozes()`, `CameraSnooze`, `NotificationVerdictService.countersSince`, `VerdictCountRow`, `JudgeRuntimeSettings.judgeEnabled()`, `ActiveJudgePreset.effective()`, `JudgeProperties.enabled`.
- Produces: `StatusResponse.judge: JudgeSection`; `JudgeSection(enabled, runtimeEnabled, presetId, last24h: JudgeCounters, snoozes: List<CameraSnoozeDto>)`; `JudgeCounters(published, suppressedByReason: Map<String, Long>, failover, snoozed)`; `CameraSnoozeDto(camId, until: Instant, classes: String)`; ключи i18n `status.section.judge`, `status.judge.*`.

- [ ] **Step 1: Модель**

В `StatusResponse.kt`:

```kotlin
data class StatusResponse(
    val recordings: RecordingsStatistics,
    val cameras: CamerasSection,
    val detectServers: List<DetectServerStatistics>,
    val judge: JudgeSection = JudgeSection.disabled(),
)

data class JudgeSection(
    val enabled: Boolean,
    val runtimeEnabled: Boolean,
    val presetId: String?,
    val last24h: JudgeCounters,
    val snoozes: List<CameraSnoozeDto>,
) {
    companion object {
        fun disabled() = JudgeSection(false, false, null, JudgeCounters(0, emptyMap(), 0, 0), emptyList())
    }
}

data class JudgeCounters(
    val published: Long,
    val suppressedByReason: Map<String, Long>,
    val failover: Long,
    val snoozed: Long,
)

data class CameraSnoozeDto(
    val camId: String,
    val until: Instant,
    val classes: String,
)
```

- [ ] **Step 2: Падающие тесты**

`core/service/StatusServiceJudgeTest.kt` (MockK, только секция судьи; репозиторий записей и балансировщик — relaxed-моки):

```kotlin
class StatusServiceJudgeTest {
    private val judgeService = mockk<NotificationJudgeService>()
    private val verdicts = mockk<NotificationVerdictService>()
    private val runtime = mockk<JudgeRuntimeSettings>()
    private val activePreset = mockk<ActiveJudgePreset>()
    private val clock = Clock.fixed(Instant.parse("2026-09-05T10:00:00Z"), ZoneOffset.UTC)

    private fun service(judgePresent: Boolean) =
        StatusService(
            recordingRepository = mockk(relaxed = true),
            detectServerLoadBalancer = mockk(relaxed = true),
            signalLossMonitorTask = provider(null),
            clock = clock,
            judgeService = provider(if (judgePresent) judgeService else null),
            verdictService = verdicts,
            judgeRuntimeSettings = provider(if (judgePresent) runtime else null),
            activeJudgePreset = provider(if (judgePresent) activePreset else null),
        )

    @Test
    fun `without the judge the section is disabled`() =
        runTest { assertEquals(JudgeSection.disabled(), service(judgePresent = false).collect().judge) }

    @Test
    fun `with the judge counters are folded by stage and reason and snoozes are listed`() =
        runTest {
            coEvery { runtime.judgeEnabled() } returns true
            coEvery { activePreset.effective() } returns mockk { every { id } returns "claude-sonnet" }
            coEvery { verdicts.countersSince(clock.instant().minus(Duration.ofHours(24))) } returns
                listOf(
                    VerdictCountRow("JUDGE", "PUBLISH", "NEW_EVENT", 3),
                    VerdictCountRow("JUDGE", "SUPPRESS", "STATIC_OBJECT", 30),
                    VerdictCountRow("JUDGE", "SUPPRESS", "DUPLICATE", 15),
                    VerdictCountRow("SNOOZE", "SUPPRESS", "SNOOZED", 20),
                    VerdictCountRow("FAILOVER", "PUBLISH", "TIMEOUT", 1),
                    VerdictCountRow("BYPASS", "PUBLISH", "JUDGE_OFF", 2),
                )
            every { judgeService.snapshotSnoozes() } returns
                listOf(CameraSnooze("cam2", clock.instant(), clock.instant().plusSeconds(900), mapOf("person" to 1)))

            val judge = service(judgePresent = true).collect().judge

            assertTrue(judge.enabled); assertTrue(judge.runtimeEnabled); assertEquals("claude-sonnet", judge.presetId)
            assertEquals(6, judge.last24h.published)          // JUDGE PUBLISH + FAILOVER + BYPASS
            assertEquals(mapOf("STATIC_OBJECT" to 30L, "DUPLICATE" to 15L), judge.last24h.suppressedByReason)
            assertEquals(1, judge.last24h.failover)
            assertEquals(20, judge.last24h.snoozed)
            assertEquals(CameraSnoozeDto("cam2", clock.instant().plusSeconds(900), "person:1"), judge.snoozes.single())
        }

    @Test
    fun `settings and preset reads fail soft`() =
        runTest {
            coEvery { runtime.judgeEnabled() } throws IllegalStateException("db")
            coEvery { activePreset.effective() } throws IllegalStateException("db")
            coEvery { verdicts.countersSince(any()) } returns emptyList()
            every { judgeService.snapshotSnoozes() } returns emptyList()
            val judge = service(judgePresent = true).collect().judge
            assertTrue(judge.runtimeEnabled); assertNull(judge.presetId)
        }

    private fun <T> provider(value: T?): ObjectProvider<T> = mockk { every { getIfAvailable() } returns value; every { ifAvailable } returns value }
}
```

`StatusMessageFormatterTest`: в `snapshot(...)` добавить параметр `judge: JudgeSection = JudgeSection.disabled()`; тесты:
- `judge section says disabled when the feature is off` — вывод содержит `status.judge.disabled`.
- `judge section lists counters and snoozes` — при `JudgeSection(true, true, "claude-sonnet", JudgeCounters(6, mapOf("STATIC_OBJECT" to 30), 1, 20), listOf(CameraSnoozeDto("cam2", now.plusSeconds(900), "person:1")))` вывод содержит `status.judge.preset[claude-sonnet]`, `status.judge.published[6]`, `STATIC_OBJECT 30`, `status.judge.failover[1]`, `status.judge.snoozed[20]`, `status.judge.snooze.line[cam2,10:15:00,person:1]`.

`StatusControllerTest`: добавить `.jsonPath("$.judge.enabled").isEqualTo(false)` (в тестовом контексте судья выключен).

- [ ] **Step 3: Реализация**

`StatusService`: конструктор дополняется `judgeService: ObjectProvider<NotificationJudgeService>`, `verdictService: NotificationVerdictService`, `judgeRuntimeSettings: ObjectProvider<JudgeRuntimeSettings>`, `activeJudgePreset: ObjectProvider<ActiveJudgePreset>`; `collect()` добавляет `judge = buildJudge(now)`:

```kotlin
    private suspend fun buildJudge(now: Instant): JudgeSection {
        val judge = judgeService.ifAvailable ?: return JudgeSection.disabled()
        val rows = verdictService.countersSince(now.minus(Duration.ofHours(24)))
        val published = rows.filter { it.verdict == VerdictDecision.PUBLISH.name }.sumOf { it.count }
        val suppressed = rows.filter { it.stage == VerdictStage.JUDGE.name && it.verdict == VerdictDecision.SUPPRESS.name }
            .groupBy { it.reason }.mapValues { (_, r) -> r.sumOf { it.count } }
        val failover = rows.filter { it.stage == VerdictStage.FAILOVER.name }.sumOf { it.count }
        val snoozed = rows.filter { it.stage == VerdictStage.SNOOZE.name }.sumOf { it.count }
        return JudgeSection(
            enabled = true,
            runtimeEnabled = failSoft(true) { judgeRuntimeSettings.ifAvailable?.judgeEnabled() ?: true },
            presetId = failSoft(null) { activeJudgePreset.ifAvailable?.effective()?.id },
            last24h = JudgeCounters(published, suppressed, failover, snoozed),
            snoozes = judge.snapshotSnoozes().map { CameraSnoozeDto(it.camId, it.until, it.covered.entries.sortedBy { e -> e.key }.joinToString(",") { e -> "${e.key}:${e.value}" }) },
        )
    }

    private suspend fun <T> failSoft(fallback: T, read: suspend () -> T): T =
        try { read() } catch (e: CancellationException) { throw e } catch (e: Exception) { logger.warn(e) { "Judge status read failed; using fallback" }; fallback }
```

`StatusMessageFormatter.format(...)`: после серверов `appendLine(); appendJudge(snapshot.judge, language, zone)`:

```kotlin
    private fun StringBuilder.appendJudge(judge: JudgeSection, language: String, zone: ZoneId) {
        appendLine("⚖️ <b>${escape(msg.get("status.section.judge", language))}</b>")
        if (!judge.enabled) { appendPreBlock(listOf(escape(msg.get("status.judge.disabled", language)))); return }
        val lines = mutableListOf<String>()
        lines += escape(msg.get(if (judge.runtimeEnabled) "status.judge.state.on" else "status.judge.state.off", language))
        lines += escape(msg.get("status.judge.preset", language, judge.presetId ?: "-"))
        lines += escape(msg.get("status.judge.published", language, judge.last24h.published))
        lines += escape(msg.get("status.judge.suppressed", language, judge.last24h.suppressedByReason.values.sum()))
        judge.last24h.suppressedByReason.entries.sortedByDescending { it.value }.forEach { (reason, count) -> lines += "  ${escape(reason)} $count" }
        lines += escape(msg.get("status.judge.failover", language, judge.last24h.failover))
        lines += escape(msg.get("status.judge.snoozed", language, judge.last24h.snoozed))
        if (judge.snoozes.isEmpty()) {
            lines += escape(msg.get("status.judge.snooze.none", language))
        } else {
            val fmt = DateTimeFormatter.ofPattern("HH:mm:ss")
            judge.snoozes.forEach { s -> lines += escape(msg.get("status.judge.snooze.line", language, s.camId, s.until.atZone(zone).format(fmt), s.classes)) }
        }
        appendPreBlock(lines)
    }
```

i18n (ru / en):

```
status.section.judge=Судья уведомлений / Notification judge
status.judge.disabled=Судья выключен (APP_AI_JUDGE_ENABLED=true включает) / Judge disabled (set APP_AI_JUDGE_ENABLED=true)
status.judge.state.on=Состояние: включён / State: on
status.judge.state.off=Состояние: выключен в /ai / State: switched off in /ai
status.judge.preset=Пресет: {0} / Preset: {0}
status.judge.published=За 24 ч отправлено: {0} / Published in 24h: {0}
status.judge.suppressed=За 24 ч подавлено: {0} / Suppressed in 24h: {0}
status.judge.failover=Сбоев судьи: {0} / Judge failures: {0}
status.judge.snoozed=Погашено snooze: {0} / Snoozed: {0}
status.judge.snooze.none=Активных snooze нет / No active snoozes
status.judge.snooze.line={0} до {1}: {2} / {0} until {1}: {2}
```

Формат чисел в `msg.get(...)`: передавать `Long`/`Int` как аргументы `MessageFormat` — как в существующем `status.servers.line.alive`.

- [ ] **Step 4: Прогнать тесты**

Run: `./gradlew :frigate-analyzer-telegram:test :frigate-analyzer-core:test --tests '*Status*'`
Expected: PASS (включая `MessageKeyParityTest`).

- [ ] **Step 5: Commit**

```bash
git add modules/model modules/core modules/telegram
git commit -m "feat(status): judge section with 24h counters and active snoozes" -m "Claude-Session: <SESSION_URL>"
```

---
### Task 10: Owner-команда `/verdicts`

**Files:**
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/bot/handler/VerdictsCommandHandler.kt`, `bot/handler/VerdictsMessageFormatter.kt`, `bot/handler/VerdictsArguments.kt`
- Modify: `modules/telegram/src/main/resources/messages_ru.properties`, `messages_en.properties`
- Test (create): `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/bot/handler/VerdictsArgumentsTest.kt`, `bot/handler/VerdictsMessageFormatterTest.kt`

**Interfaces:**
- Consumes: `CommandHandler`, `NotificationVerdictService.latest(camId, limit)`, `NotificationVerdictEntity`, `TelegramUserService.getUserZone`, `MessageResolver`, `escapeTelegramHtml` (`telegram/service/impl/TelegramHtml.kt`).
- Produces: `VerdictsArguments.parse(text): VerdictsArguments?` (`camId: String?`, `limit: Int`), `VerdictsMessageFormatter.format(rows, language, zone): String`, команда `verdicts` (`ownerOnly = true`, `requiredRole = OWNER`, `order = 10`), ключи `command.verdicts.description`, `verdicts.*`.

- [ ] **Step 1: Падающие тесты**

`VerdictsArgumentsTest`:

```kotlin
class VerdictsArgumentsTest {
    @Test fun `no arguments means all cameras and ten rows`() = assertEquals(VerdictsArguments(null, 10), VerdictsArguments.parse("/verdicts"))
    @Test fun `camera only`() = assertEquals(VerdictsArguments("cam2", 10), VerdictsArguments.parse("/verdicts cam2"))
    @Test fun `camera and count`() = assertEquals(VerdictsArguments("cam2", 20), VerdictsArguments.parse("/verdicts   cam2  20"))
    @Test fun `count only`() = assertEquals(VerdictsArguments(null, 5), VerdictsArguments.parse("/verdicts 5"))
    @Test fun `bot mention suffix is tolerated`() = assertEquals(VerdictsArguments("cam2", 10), VerdictsArguments.parse("/verdicts@frigate_bot cam2"))
    @Test fun `count out of range or extra tokens is invalid`() {
        assertNull(VerdictsArguments.parse("/verdicts cam2 0"))
        assertNull(VerdictsArguments.parse("/verdicts cam2 31"))
        assertNull(VerdictsArguments.parse("/verdicts cam2 20 extra"))
    }
}
```

`VerdictsMessageFormatterTest` (мок `MessageResolver` как в `StatusMessageFormatterTest`):

```kotlin
class VerdictsMessageFormatterTest {
    private val msg = mockk<MessageResolver>().apply {
        every { get(any(), "en") } answers { firstArg<String>() }
        every { get(any(), "en", *anyVararg()) } answers { "${firstArg<String>()}[${thirdArg<Array<*>>().joinToString(",")}]" }
    }
    private val formatter = VerdictsMessageFormatter(msg)
    private val zone = ZoneId.of("Europe/Moscow")

    private fun row(stage: String, verdict: String, reason: String, summary: String? = "sum") =
        NotificationVerdictEntity(UUID.randomUUID(), Instant.parse("2026-09-05T07:00:00Z"), UUID.randomUUID(), "cam2",
            Instant.parse("2026-09-05T06:59:30Z"), stage, verdict, reason, "NEW_OBJECTS", "person:1", 0.9f, summary, null, null,
            "claude-sonnet", "sonnet", 1200, null, null)

    @Test
    fun `renders one line per verdict with icon, local time, camera, stage, reason, classes and summary`() {
        val text = formatter.format(listOf(row("JUDGE", "PUBLISH", "NEW_EVENT"), row("JUDGE", "SUPPRESS", "STATIC_OBJECT"), row("FAILOVER", "PUBLISH", "TIMEOUT", null)), "en", zone)
        assertTrue(text.startsWith("verdicts.title"))
        assertTrue(text.contains("📨 09:59:30 cam2 JUDGE NEW_EVENT person:1 — sum"))
        assertTrue(text.contains("🔇 09:59:30 cam2 JUDGE STATIC_OBJECT person:1 — sum"))
        assertTrue(text.contains("⚠️ 09:59:30 cam2 FAILOVER TIMEOUT person:1"))
    }

    @Test
    fun `empty list renders the empty message`() = assertTrue(formatter.format(emptyList(), "en", zone).contains("verdicts.empty"))

    @Test
    fun `output is cut at the Telegram limit with a marker`() {
        val rows = List(30) { row("JUDGE", "SUPPRESS", "DUPLICATE", "x".repeat(400)) }
        val text = formatter.format(rows, "en", zone)
        assertTrue(text.length <= 4096)
        assertTrue(text.endsWith("verdicts.truncated"))
    }

    @Test
    fun `html is escaped in summaries`() {
        assertTrue(formatter.format(listOf(row("JUDGE", "SUPPRESS", "DUPLICATE", "<b>x</b>")), "en", zone).contains("&lt;b&gt;x&lt;/b&gt;"))
    }
}
```

- [ ] **Step 2: Убедиться, что не компилируется**

Run: `./gradlew :frigate-analyzer-core:test --tests '*Verdicts*'`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Реализация**

`bot/handler/VerdictsArguments.kt`:

```kotlin
data class VerdictsArguments(
    val camId: String?,
    val limit: Int,
) {
    companion object {
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 30

        /** `/verdicts [cam] [n]`; null = аргументы не разобрать. */
        fun parse(text: String): VerdictsArguments? {
            val tokens = text.trim().split(Regex("\\s+")).drop(1)
            return when (tokens.size) {
                0 -> VerdictsArguments(null, DEFAULT_LIMIT)
                1 -> tokens[0].toIntOrNull()?.let { limit(it)?.let { n -> VerdictsArguments(null, n) } } ?: VerdictsArguments(tokens[0], DEFAULT_LIMIT)
                2 -> tokens[1].toIntOrNull()?.let(::limit)?.let { VerdictsArguments(tokens[0], it) }
                else -> null
            }
        }

        private fun limit(n: Int): Int? = n.takeIf { it in 1..MAX_LIMIT }
    }
}
```

`bot/handler/VerdictsMessageFormatter.kt`:

```kotlin
@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class VerdictsMessageFormatter(
    private val msg: MessageResolver,
) {
    fun format(rows: List<NotificationVerdictEntity>, language: String, zone: ZoneId): String {
        val title = "<b>${escapeTelegramHtml(msg.get("verdicts.title", language))}</b>"
        if (rows.isEmpty()) return "$title\n${escapeTelegramHtml(msg.get("verdicts.empty", language))}"
        val fmt = DateTimeFormatter.ofPattern("HH:mm:ss")
        val truncated = escapeTelegramHtml(msg.get("verdicts.truncated", language))
        val sb = StringBuilder(title)
        for (row in rows) {
            val icon = when {
                row.stage == VerdictStage.FAILOVER.name -> "⚠️"
                row.verdict == VerdictDecision.PUBLISH.name -> "📨"
                else -> "🔇"
            }
            val line = buildString {
                append(icon).append(' ').append(row.recordTimestamp.atZone(zone).format(fmt)).append(' ')
                append(escapeTelegramHtml(row.camId)).append(' ').append(row.stage).append(' ').append(row.reason).append(' ')
                append(escapeTelegramHtml(row.classes))
                row.summary?.takeIf { it.isNotBlank() }?.let { append(" — ").append(escapeTelegramHtml(it)) }
            }
            if (sb.length + 1 + line.length + 1 + truncated.length > TELEGRAM_LIMIT) {
                sb.append('\n').append(truncated)
                return sb.toString()
            }
            sb.append('\n').append(line)
        }
        return sb.toString()
    }

    private companion object {
        const val TELEGRAM_LIMIT = 4096
    }
}
```

`bot/handler/VerdictsCommandHandler.kt` — по образцу `StatusCommandHandler` (тот же пакет `core/bot/handler`, `@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")`):

```kotlin
class VerdictsCommandHandler(
    private val verdictService: NotificationVerdictService,
    private val formatter: VerdictsMessageFormatter,
    private val userService: TelegramUserService,
    private val msg: MessageResolver,
) : CommandHandler {
    override val command: String = "verdicts"
    override val requiredRole: UserRole = UserRole.OWNER
    override val ownerOnly: Boolean = true
    override val order: Int = 10

    override suspend fun BehaviourContext.handle(message: ChatContentMessage<TextContent>, user: TelegramUserDto?) {
        val language = user?.languageCode ?: "en"
        val args = VerdictsArguments.parse(message.content.text)
        if (args == null) {
            reply(message, msg.get("verdicts.usage", language))
            return
        }
        val rows = verdictService.latest(args.camId, args.limit)
        val zone = userService.getUserZone(message.chat.id.chatId.long)
        sendTextMessage(message.chat, formatter.format(rows, language, zone), parseMode = HTMLParseMode, replyParameters = ReplyParameters(message.metaInfo))
    }
}
```

Команда регистрируется автоматически: `FrigateAnalyzerBot` собирает все `CommandHandler`-бины; `ownerOnly = true` держит её вне общего меню и `/help`.

i18n (ru / en):

```
command.verdicts.description=Последние вердикты судьи / Latest judge verdicts
verdicts.title=⚖️ Вердикты судьи / ⚖️ Judge verdicts
verdicts.empty=Вердиктов пока нет / No verdicts yet
verdicts.usage=Использование: /verdicts [камера] [число 1–30] / Usage: /verdicts [camera] [count 1–30]
verdicts.truncated=… список обрезан по лимиту Telegram / … list cut at the Telegram limit
```

- [ ] **Step 4: Прогнать тесты**

Run: `./gradlew :frigate-analyzer-core:test --tests '*Verdicts*' :frigate-analyzer-telegram:test --tests '*MessageKeyParityTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/core modules/telegram/src/main/resources
git commit -m "feat(telegram): owner command /verdicts listing the latest judge verdicts" -m "Claude-Session: <SESSION_URL>"
```

---

### Task 11: Секция судьи в `/ai`

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/dto/AiSettingsViewState.kt`
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/aisettings/AiSettingsCallbacks.kt`, `AiSettingsCallbackHandler.kt`, `AiSettingsViewStateFactory.kt`, `AiSettingsMessageRenderer.kt`
- Modify: `messages_ru.properties`, `messages_en.properties`
- Test (modify): `AiSettingsCallbackHandlerTest.kt`, `AiSettingsMessageRendererTest.kt`, `AiSettingsViewStateFactoryTest.kt`, `telegram/i18n/AiSettingsMessagesTest.kt`

**Interfaces:**
- Consumes: `JudgeRuntimeSettings` (`judgeEnabled/setJudgeEnabled/setActivePresetId`), `ActiveJudgePreset` (`storedId/effective`), `DescriptionPresets`.
- Produces: `AiSettingsCallbacks.JUDGE_ON = "aip:j:on"`, `JUDGE_OFF = "aip:j:off"`, `JUDGE_SET_PREFIX = "aip:j:set:"`; `AiSettingsViewState.judgeAvailable/judgeEnabled/judgeStoredPresetId/judgeEffectivePresetId`; ключи `ai.settings.judge.*`.

- [ ] **Step 1: Падающие тесты**

`AiSettingsCallbackHandlerTest`: конструктор `AiSettingsCallbackHandler(provider(presets), provider(settings), provider(judgeSettings))`, где `judgeSettings = mockk<JudgeRuntimeSettings>(relaxed = true)`. Новые тесты:

```kotlin
    @Test
    fun `the owner switches the judge preset`() =
        runTest {
            val dispatched = handle("aip:j:set:grok-fast")
            assertEquals(DispatchOutcome.RERENDER, dispatched.outcome)
            coVerify(exactly = 1) { judgeSettings.setActivePresetId("grok-fast", "owner") }
            coVerify(exactly = 0) { settings.setActivePresetId(any(), any()) }
        }

    @Test
    fun `the owner turns the judge off and on`() =
        runTest {
            handle("aip:j:off"); handle("aip:j:on")
            coVerifyOrder { judgeSettings.setJudgeEnabled(false, "owner"); judgeSettings.setJudgeEnabled(true, "owner") }
        }

    @Test
    fun `an unavailable preset is refused for the judge with the same alert`() =
        runTest {
            val dispatched = handle("aip:j:set:claude-opus") // claude-opus в CATALOG недоступен (NoToken)
            assertEquals(DispatchOutcome.ALERT, dispatched.outcome)
            coVerify(exactly = 0) { judgeSettings.setActivePresetId(any(), any()) }
        }

    @Test
    fun `judge callbacks without the judge bean change nothing and still answer`() =
        runTest {
            val handler = AiSettingsCallbackHandler(provider(presets), provider(settings), provider(null))
            val dispatched = handler.handle("aip:j:on", true, "owner") { answers += it }
            assertEquals(DispatchOutcome.RERENDER, dispatched.outcome)
            assertEquals(1, answers.size)
        }
```

`AiSettingsMessageRendererTest`: состояние с `judgeAvailable = true, judgeEnabled = true, judgeStoredPresetId = "grok-fast", judgeEffectivePresetId = "grok-fast"`:
- текст содержит `ai.settings.judge.title`, `ai.settings.judge.state`, `ai.settings.judge.active`;
- клавиатура содержит кнопки с данными `aip:j:set:grok-fast`, `aip:j:set:byok-luna`, `aip:j:set:claude-opus` и кнопку `aip:j:off`; подписи кнопок судьи начинаются с `⚖️ `, подписи описаний — с `📝 `;
- при `judgeAvailable = false` ни текста судьи, ни `aip:j:` кнопок нет (существующие тесты рендера клавиатуры описаний адаптировать под префикс `📝 `).

`AiSettingsViewStateFactoryTest`: с `ObjectProvider<JudgeRuntimeSettings>` и `ObjectProvider<ActiveJudgePreset>`, дающими бины, состояние получает `judgeAvailable = true` и id пресетов судьи; без бинов — `judgeAvailable = false`; падение `judgeEnabled()` даёт `true` (fail-open).

`AiSettingsMessagesTest`: добавить новые ключи в карту «ключ → число аргументов».

- [ ] **Step 2: Убедиться, что не компилируется**

Run: `./gradlew :frigate-analyzer-telegram:test --tests '*AiSettings*'`
Expected: FAIL.

- [ ] **Step 3: Реализация**

`AiSettingsViewState` — новые поля в конце с дефолтами:

```kotlin
    val judgeAvailable: Boolean = false,
    val judgeEnabled: Boolean = true,
    val judgeStoredPresetId: String? = null,
    val judgeEffectivePresetId: String? = null,
) {
    val hasMismatch: Boolean get() = storedPresetId != null && effectivePresetId != null && storedPresetId != effectivePresetId
    val hasJudgeMismatch: Boolean get() = judgeStoredPresetId != null && judgeEffectivePresetId != null && judgeStoredPresetId != judgeEffectivePresetId
```

`AiSettingsCallbacks`: `const val JUDGE_ON = PREFIX + "j:on"`, `JUDGE_OFF = PREFIX + "j:off"`, `JUDGE_SET_PREFIX = PREFIX + "j:set:"`.

`AiSettingsCallbackHandler`: третий параметр `judgeSettingsProvider: ObjectProvider<JudgeRuntimeSettings>`; `Action` получает `JudgeSwitch(enabled)` и `JudgeSelect(id)`; `parse` разбирает новые payload-ы **до** проверки `SET_PREFIX` (префиксы не пересекаются, но порядок явный); `classify`: `JudgeSwitch → RERENDER`, `JudgeSelect → classifySelect(id)` (та же проверка каталога); `apply`: `JudgeSwitch → judgeSettings.setJudgeEnabled(...)`, `JudgeSelect → judgeSettings.setActivePresetId(...)`, при отсутствии бина — WARN и ничего.

`AiSettingsViewStateFactory`: параметры `judgeRuntimeSettingsProvider: ObjectProvider<JudgeRuntimeSettings>`, `activeJudgePresetProvider: ObjectProvider<ActiveJudgePreset>`; `build` заполняет `judgeAvailable = judgeRuntimeSettingsProvider.getIfAvailable() != null`, `judgeEnabled` через тот же fail-open helper с таймаутом 5 с, `judgeStoredPresetId = activeJudge?.storedId()`, `judgeEffectivePresetId = if (presets.isEmpty()) null else activeJudge?.effective()?.id`.

`AiSettingsMessageRenderer.renderText` — после блока описаний, если `state.judgeAvailable`:

```kotlin
            appendLine()
            appendLine(msg.get("ai.settings.judge.title", lang))
            appendLine(msg.get("ai.settings.judge.state", lang, msg.get(if (state.judgeEnabled) "ai.settings.state.on" else "ai.settings.state.off", lang)))
            val judgeActive = state.presets.firstOrNull { it.id == state.judgeEffectivePresetId }
            if (judgeActive == null) appendLine(msg.get("ai.settings.judge.active.none", lang))
            else appendLine(msg.get("ai.settings.judge.active", lang, judgeActive.id, judgeActive.provider, judgeActive.effectiveModel, effortLabel(judgeActive)))
            if (state.hasJudgeMismatch) appendLine(msg.get("ai.settings.judge.mismatch", lang, state.judgeStoredPresetId.orEmpty(), state.judgeEffectivePresetId.orEmpty()))
```

`renderKeyboard`: подписи описаний — `"📝 " + presetLabel(preset, state.effectivePresetId)`; затем, если `state.judgeAvailable`, по ряду на пресет с `"⚖️ " + presetLabel(preset, state.judgeEffectivePresetId)` и данными `JUDGE_SET_PREFIX + preset.id`, ряд с кнопкой `ai.settings.judge.button.disable`/`enable` (`JUDGE_OFF`/`JUDGE_ON`); кнопка «Закрыть» остаётся последней.

i18n (ru / en):

```
ai.settings.judge.title=⚖️ Судья уведомлений / ⚖️ Notification judge
ai.settings.judge.state=Судья: {0} / Judge: {0}
ai.settings.judge.active=Пресет судьи: {0} ({1} / {2} / {3}) / Judge preset: {0} ({1} / {2} / {3})
ai.settings.judge.active.none=Пресет судьи не выбран / No judge preset
ai.settings.judge.mismatch=⚠️ Для судьи выбран {0} — работает {1} / ⚠️ Judge preset {0} selected — running {1}
ai.settings.judge.button.enable=Включить судью / Enable judge
ai.settings.judge.button.disable=Выключить судью / Disable judge
```

- [ ] **Step 4: Прогнать тесты**

Run: `./gradlew :frigate-analyzer-telegram:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/telegram
git commit -m "feat(telegram): judge preset and switch in the /ai dialog" -m "Claude-Session: <SESSION_URL>"
```

---
### Task 12: Конфигурация, деплой-примеры, документация, полная сборка

**Files:**
- Modify: `modules/core/src/main/resources/application.yaml`
- Modify: `docker/deploy/.env.example`, `docker/deploy/application-docker.yaml.example`
- Modify: `.claude/rules/ai-description.md`, `.claude/rules/configuration.md`, `.claude/rules/database.md`, `.claude/rules/pipeline.md`, `.claude/rules/telegram.md`, `CLAUDE.md`, `README.md`

**Interfaces:**
- Consumes: всё, что сделано в Task 1–11.
- Produces: секция `application.ai.judge` в yaml, документация, зелёная полная сборка.

- [ ] **Step 1: `application.yaml`**

После секции `application.ai.description` (на том же уровне, что `description`) добавить:

```yaml
    # LLM-судья уведомлений: третья ступень между трекером и отправкой в Telegram. Работает на
    # общем каталоге пресетов описаний (application.ai.description.presets), но со своим пресетом,
    # лимитами и выключателем в /ai. Требует application.ai.description.enabled=true.
    judge:
      enabled: ${APP_AI_JUDGE_ENABLED:false}
      # Пресет судьи до первого выбора владельца в /ai; пусто = default-preset описаний.
      default-preset: ${APP_AI_JUDGE_DEFAULT_PRESET:}
      queue-timeout: ${APP_AI_JUDGE_QUEUE_TIMEOUT:30s}
      timeout: ${APP_AI_JUDGE_TIMEOUT:60s}
      max-concurrent: ${APP_AI_JUDGE_MAX_CONCURRENT:2}
      # Кадры с рамками, первые N по ранжированию визуализации, в хронологическом порядке.
      max-frames: ${APP_AI_JUDGE_MAX_FRAMES:4}
      max-image-side: ${APP_AI_JUDGE_MAX_IMAGE_SIDE:1280}
      rate-limit:
        enabled: ${APP_AI_JUDGE_RATE_LIMIT_ENABLED:true}
        max-requests: ${APP_AI_JUDGE_RATE_LIMIT_MAX:200}
        window: ${APP_AI_JUDGE_RATE_LIMIT_WINDOW:1h}
      # Потолок snooze_minutes из ответа модели.
      max-snooze: ${APP_AI_JUDGE_MAX_SNOOZE:PT30M}
      # Static score: сколько записей за окно содержали тот же класс в том же месте (IoU >= порога).
      static-window: ${APP_AI_JUDGE_STATIC_WINDOW:P7D}
      static-iou: ${APP_AI_JUDGE_STATIC_IOU:0.4}
      # История вердиктов камеры в контексте: ± окно вокруг записи и число строк.
      history-window: ${APP_AI_JUDGE_HISTORY_WINDOW:PT6H}
      history-limit: ${APP_AI_JUDGE_HISTORY_LIMIT:10}
      # Зона локальных времён в промпте; пусто = зона владельца из /timezone, затем зона JVM.
      zone: ${APP_AI_JUDGE_ZONE:}
      # Заметки владельца о сценах камер, только yaml (application-docker.yaml):
      # cameras:
      #   cam4: { notes: "Огород за домом: грядки под сеткой, куча земли, поленница слева внизу." }
      cameras: {}
```

Проверить, что `rate-limit.max-requests` описаний уже `30` (Task 2).

- [ ] **Step 2: Примеры деплоя**

`docker/deploy/.env.example` — после блока описаний добавить:

```
# --- LLM notification judge (optional, requires APP_AI_DESCRIPTION_ENABLED=true) ---
# Third gate between the object tracker and Telegram: a fast model looks at the annotated frames
# and the database context and decides PUBLISH/SUPPRESS. Every verdict lands in notification_verdicts
# and is visible in /status and /verdicts; the owner switches the judge preset and turns it off in /ai.
# APP_AI_JUDGE_ENABLED=true
# Declared preset id used until the owner picks one in /ai; empty = the descriptions default preset.
# A fast, cheap model is the point: claude-sonnet or grok-fast, not opus.
# APP_AI_JUDGE_DEFAULT_PRESET=claude-sonnet
# APP_AI_JUDGE_TIMEOUT=60s
# APP_AI_JUDGE_QUEUE_TIMEOUT=30s
# APP_AI_JUDGE_MAX_CONCURRENT=2
# APP_AI_JUDGE_MAX_FRAMES=4
# APP_AI_JUDGE_MAX_IMAGE_SIDE=1280
# Protective ceiling on judge calls; beyond it candidates are sent unjudged (FAILOVER RATE_LIMITED).
# APP_AI_JUDGE_RATE_LIMIT_MAX=200
# APP_AI_JUDGE_RATE_LIMIT_WINDOW=1h
# APP_AI_JUDGE_MAX_SNOOZE=PT30M
# APP_AI_JUDGE_STATIC_WINDOW=P7D
# APP_AI_JUDGE_STATIC_IOU=0.4
# APP_AI_JUDGE_HISTORY_WINDOW=PT6H
# APP_AI_JUDGE_HISTORY_LIMIT=10
# Local-time zone of the prompt context; empty = the owner's /timezone, then the JVM zone (UTC in the container).
# APP_AI_JUDGE_ZONE=
```

Строку `# APP_AI_DESCRIPTION_RATE_LIMIT_MAX` (если она есть) привести к новому дефолту `30`, иначе добавить с комментарием.

`docker/deploy/application-docker.yaml.example` — в закомментированный блок `ai:` добавить:

```yaml
  #   judge:
  #     # Заметки владельца о сцене каждой камеры попадают в контекст судьи как есть.
  #     cameras:
  #       cam2: { notes: "Двор дачи: у ворот обычно стоит машина хозяев, велосипеды лежат на траве." }
  #       cam4: { notes: "Огород за домом: грядки под сеткой, куча земли, поленница слева внизу." }
```

- [ ] **Step 3: Документация**

- `.claude/rules/ai-description.md`: `paths:` дополнить `**/judge/**`, `**/Verdicts*`; таблица слоёв — `VisionBackend`, `VisionBackendFactory`, `VisionRequest`/`VisionInstructions`, `VisionCallExecutor`, `DescriptionTask`, `JudgeTask`, `DescriptionResponseParser`, `JudgeResponseParser`, `DefaultJudgeAgent`, `JudgePresetResolver`, `SlidingWindowRateLimiter` + два наследника, `JudgeProperties`, `JudgeAgentSanityChecker`; новый раздел «Judge» (пять шагов оркестратора, snooze, fail-open, таблица причин по `stage`, интеграция с фасадом, `/ai` с двумя блоками и глаголами `aip:j:*`, ключи `app_settings`); в разделе «Rate Limiting» — два лимита и новый дефолт 30.
- `.claude/rules/configuration.md`: новый раздел «AI Judge» с таблицей переменных из Step 1 и заметкой про `APP_AI_JUDGE_ZONE`; в таблице описаний `APP_AI_DESCRIPTION_RATE_LIMIT_MAX` → `30`.
- `.claude/rules/database.md`: таблица `notification_verdicts` (колонки, индексы, «без чистки»), ключи `ai.judge.preset.active` / `ai.judge.enabled` в разделе `app_settings`.
- `.claude/rules/pipeline.md`: в `RecordingProcessingFacade` — «при включённом судье передаёт кандидата `NotificationJudgeService` и не отправляет сам»; строка про `JudgeCoroutineScope`.
- `.claude/rules/telegram.md`: строки `/verdicts` (OWNER, handler в `core`) и обновлённое описание `/ai`; `/status` — блок судьи.
- `CLAUDE.md`: строка модуля `ai-description` → «AI descriptions and the LLM notification judge over a shared preset catalog»; в Key Patterns добавить «**LLM judge:** third gate after the tracker; annotated frames + DB context → PUBLISH/SUPPRESS, snooze against duplicates, every verdict in `notification_verdicts`, fail-open»; в таблице Database — `notification_verdicts`; в таблице модульной документации — обновлённые `paths` для `ai-description.md`.
- `README.md`: команды `/verdicts` и обновлённый `/ai`; переменные `APP_AI_JUDGE_*` в таблице конфигурации; абзац про судью рядом с описаниями.

- [ ] **Step 4: Полная сборка**

Run: `./gradlew build` (через build-runner; на ошибки ktlint — `./gradlew ktlintFormat` и повтор)
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Ревью**

Запустить `superpowers:code-reviewer` (правило `CLAUDE.md`) на диффе ветки против `master`; критические замечания исправить, повторить до чистого прохода.

- [ ] **Step 6: Commit**

```bash
git add modules/core/src/main/resources/application.yaml docker/deploy/.env.example docker/deploy/application-docker.yaml.example .claude/rules CLAUDE.md README.md
git commit -m "docs(judge): configuration, deploy examples and rules for the LLM notification judge" -m "Claude-Session: <SESSION_URL>"
```

- [ ] **Step 7: Живая проверка на стенде (после мержа, руками владельца)**

1. На проде до выката: `NOTIFICATIONS_TRACK_REAPPEAR_CLASSES=person` в `.env`, рестарт — заплатка от возвратов машины и велосипедов, независимая от судьи.
2. Деплой образа; контейнер liquibase применяет `1.0.6.xml`.
3. `.env`: `APP_AI_JUDGE_ENABLED=true`, `APP_AI_JUDGE_DEFAULT_PRESET=claude-sonnet` (или `grok-fast`); при желании `cameras.<cam>.notes` в `application-docker.yaml`.
4. В Telegram: `/ai` показывает блок судьи с ⚖️-кнопками; `/status` показывает секцию судьи; `/verdicts` пуст.
5. Через сутки: `/verdicts cam2 30` — статичные машина и велосипеды должны быть `STATIC_OBJECT`, засветы `FALSE_POSITIVE`, серия с человеком — один `PUBLISH`, дальше `SNOOZED`/`DUPLICATE`.

---

## Self-review

**Spec coverage.** Раздел 4 (поток и компоненты) → Task 5, 7, 8. Раздел 5 (контекст, static score, кадры, зона) → Task 4 (SQL), 6 (билдер), 5 (зона), 7 (кадры судье: первые `max-frames` визуализированных в хронологическом порядке). Раздел 6 (промпт и схема ответа) → Task 3 (`JudgeTask`, `JudgeResponseParser`). Раздел 7 (snooze) → Task 5 (`SnoozeRegistry`) и Task 7 (только `stage = JUDGE` меняет snooze; `FAILOVER`/`BYPASS` его не трогают — в `judgeLocked` `snoozes.set` вызывается только на ветке успешного вердикта). Раздел 8 (таблица) → Task 4. Раздел 9 (`/status`, `/verdicts`) → Task 9, 10. Раздел 10 (`VisionBackend`, executor, резолверы, лимиты, переименования, условия включения) → Task 1, 2, 3. Раздел 11 (`/ai`) → Task 11. Раздел 12 (ошибки и конкурентность) → Task 7 (per-camera mutex, WARN на очереди > 20, fail-open по типам, устойчивость к сбою записи). Раздел 13 (конфигурация) → Task 3 (`JudgeProperties`), Task 12 (yaml, env). Раздел 14 (тесты) → каждая задача несёт свои; интеграционные — Task 4. Раздел 15 (развёртывание) и 16 (документация) → Task 12.

**Placeholder scan.** «TBD/TODO» нет. Шаги с кодом содержат код; шаги-переносы (`DescriptionResponseParserTest`, `VisionCallExecutorTest`) называют исходный файл и точные изменения. `<SESSION_URL>` — намеренный плейсхолдер команд коммита, объявлен в Global Constraints.

**Type consistency.** `VisionCallExecutor.execute(request, parse): VisionOutcome<T>` одинаков в Task 2 и 3. `ActivePresetResolver(catalog, source, fallbackId, label)` — Task 2 и 3. `JudgeOutcome(verdict, presetId, model, latency: java.time.Duration)` — Task 3 и 7 (`outcome.latency.toMillis()`). `NewNotificationVerdict` — Task 4 и 7 (`copy(...)` по полям с дефолтами). `JudgeContextResult(json, errors)` — Task 6 и 7. `SnoozeRegistry.set(camId, anchor, minutes, classes)` / `covers(...)`: `CameraSnooze?` — Task 5 и 7. `CameraSnooze(camId, anchor, until, covered)` — Task 5 и 9. `NotificationVerdictService.latest(camId: String?, limit)` — Task 4 и 10. `JudgeProperties.maxSnoozeMinutes` — Task 3 и 7. Конструктор `StatusService` с четырьмя новыми параметрами в конце — Task 9 (тест и реализация). `AiSettingsCallbackHandler(presets, settings, judgeSettings)` — Task 11.
