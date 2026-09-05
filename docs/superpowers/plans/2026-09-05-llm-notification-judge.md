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
✅ Done — see commit(s): `0ad2ec0`

---
### Task 2: `VisionCallExecutor`, резолверы с явным fallback и два лимита
✅ Done — see commit(s): `1f9ef47`

---
### Task 3: Судья в `ai-description`: API, инструкции, разбор, свойства, агент, бины
✅ Done — see commit(s): `7b3ab70`, `5eb8693`

---
### Task 4: Таблица `notification_verdicts`, сущность, сервис и статистические запросы
✅ Done — see commit(s): `0b13d04`

---
### Task 5: Проводка судьи в `core`: настройки над `app_settings`, зона, guard, scope, snooze
✅ Done — see commit(s): `aa28675`

---
### Task 6: `JudgeContextBuilder`
✅ Done — see commit(s): `ab7aec6`

---
### Task 7: `NotificationJudgeService` — оркестрация пяти шагов
✅ Done — see commit(s): `9724e02`, `0269625`, `89fd1b5`, `481703d`, `5bcd53d`

---
### Task 8: Фасад передаёт кандидата судье
✅ Done — see commit(s): `62b4e07`

---
### Task 9: Секция судьи в `/status` (REST и Telegram)
✅ Done — see commit(s): `1a485f8`

---
### Task 10: Owner-команда `/verdicts`
✅ Done — see commit(s): `28f91e7`, `9a56cab`

---
### Task 11: Секция судьи в `/ai`
✅ Done — see commit(s): `6b531c7`

---
### Task 12: Конфигурация, деплой-примеры, документация, полная сборка
✅ Steps 1–6 Done — see commit(s): `72e4f79`, `dc75296`

- [ ] **Step 7: Живая проверка на стенде (после мержа, руками владельца)**

1. На проде до выката: `NOTIFICATIONS_TRACK_REAPPEAR_CLASSES=person` в `.env`, рестарт — заплатка от возвратов машины и велосипедов, независимая от судьи.
2. Деплой образа; контейнер liquibase применяет `1.0.6.xml`.
3. `.env`: `APP_AI_JUDGE_ENABLED=true`, `APP_AI_JUDGE_DEFAULT_PRESET=grok-fast` (id должен быть в yaml-каталоге; `claude-sonnet` в docker-примере нет); при желании `cameras.<cam>.notes` в `application-docker.yaml`.
4. В Telegram: `/ai` показывает блок судьи с ⚖️-кнопками; `/status` показывает секцию судьи; `/verdicts` пуст.
5. Через сутки: `/verdicts cam2 30` — статичные машина и велосипеды должны быть `STATIC_OBJECT`, засветы `FALSE_POSITIVE`, серия с человеком — один `PUBLISH`, дальше `SNOOZED`/`DUPLICATE`.

---

## Self-review

**Spec coverage.** Раздел 4 (поток и компоненты) → Task 5, 7, 8. Раздел 5 (контекст, static score, кадры, зона) → Task 4 (SQL), 6 (билдер), 5 (зона), 7 (кадры судье: первые `max-frames` визуализированных в хронологическом порядке). Раздел 6 (промпт и схема ответа) → Task 3 (`JudgeTask`, `JudgeResponseParser`). Раздел 7 (snooze) → Task 5 (`SnoozeRegistry`) и Task 7 (только `stage = JUDGE` меняет snooze; `FAILOVER`/`BYPASS` его не трогают — в `judgeLocked` `snoozes.set` вызывается только на ветке успешного вердикта). Раздел 8 (таблица) → Task 4. Раздел 9 (`/status`, `/verdicts`) → Task 9, 10. Раздел 10 (`VisionBackend`, executor, резолверы, лимиты, переименования, условия включения) → Task 1, 2, 3. Раздел 11 (`/ai`) → Task 11. Раздел 12 (ошибки и конкурентность) → Task 7 (per-camera mutex, WARN на очереди > 20, fail-open по типам, устойчивость к сбою записи). Раздел 13 (конфигурация) → Task 3 (`JudgeProperties`), Task 12 (yaml, env). Раздел 14 (тесты) → каждая задача несёт свои; интеграционные — Task 4. Раздел 15 (развёртывание) и 16 (документация) → Task 12.

**Placeholder scan.** «TBD/TODO» нет. Шаги с кодом содержат код; шаги-переносы (`DescriptionResponseParserTest`, `VisionCallExecutorTest`) называют исходный файл и точные изменения. `<SESSION_URL>` — намеренный плейсхолдер команд коммита, объявлен в Global Constraints.

**Type consistency.** `VisionCallExecutor.execute(request, parse): VisionOutcome<T>` одинаков в Task 2 и 3. `ActivePresetResolver(catalog, source, fallbackId, label)` — Task 2 и 3. `JudgeOutcome(verdict, presetId, model, latency: java.time.Duration)` — Task 3 и 7 (`outcome.latency.toMillis()`). `NewNotificationVerdict` — Task 4 и 7 (`copy(...)` по полям с дефолтами). `JudgeContextResult(json, errors)` — Task 6 и 7. `SnoozeRegistry.set(camId, anchor, minutes, classes)` / `covers(...)`: `CameraSnooze?` — Task 5 и 7. `CameraSnooze(camId, anchor, until, covered)` — Task 5 и 9. `NotificationVerdictService.latest(camId: String?, limit)` — Task 4 и 10. `JudgeProperties.maxSnoozeMinutes` — Task 3 и 7. Конструктор `StatusService` с четырьмя новыми параметрами в конце — Task 9 (тест и реализация). `AiSettingsCallbackHandler(presets, settings, judgeSettings)` — Task 11.
