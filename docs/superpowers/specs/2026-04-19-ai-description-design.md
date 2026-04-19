# AI-генерируемое описание обнаружений — Design

**Дата:** 2026-04-19
**Статус:** draft — под реализацию
**Ветка:** `feature/ai-description`

## 1. Постановка задачи

Добавить опциональную возможность: при завершении обработки записи с
обнаружениями Frigate Analyzer вызывает внешнего AI-агента (первой
реализацией — Claude Code CLI через Spring AI Community SDK), отдаёт ему
исходные кадры с камеры и получает два описания:

- **короткое** — приписывается к основному уведомлению;
- **подробное** — выводится в Telegram-клиенте через сворачиваемый блок
  (`expandable_blockquote`), раскрывается по клику.

Фича опциональная, включается через `application.yaml` + env, с расчётом на
добавление других AI-провайдеров (OpenAI, Gemini, локальные модели) без
переделки публичного API.

## 2. Решения, принятые в brainstorming

Ключевые ответы «какой вариант выбираем» — зафиксированы здесь для
отслеживания мотивации.

| Вопрос | Решение | Причина |
|---|---|---|
| Когда показывать описание? | Асинхронно: placeholder + edit после ответа Claude | Не блокируем уведомление; UX остаётся моментальным |
| Где держать placeholder/описание (single photo) | Каждое уведомление получает 2 сообщения: фото+caption и reply-text с expandable blockquote. Оба отправляются сразу с placeholder-ом, потом edit. | 1024-символьный лимит caption; expandable blockquote часто не влезает в caption; keyboard нельзя к media group |
| То же для media group (≥2 кадра) | Альбом + одно reply-сообщение с коротким + expandable подробным + кнопками. Edit-им только reply. | К `sendMediaGroup` нельзя прикрепить keyboard — и так идёт дополнительное сообщение для кнопок |
| Какие кадры отдаём Claude | Чистые оригинальные JPEG, все (до 10) | Пользовательский выбор |
| Контекст от YOLO в промпте | Не передаём | Пользовательский выбор; упрощает поток данных |
| Формат ответа Claude | Один вызов, JSON `{"short": "...", "detailed": "..."}` | Дёшево, надёжно, SDK легко парсит |
| Retry на невалидный JSON | 1 retry с той же инструкцией | Усиленная инструкция не обязательна |
| Язык ответа | Фиксированный из конфига (`common.language`) | Один вызов на запись, предсказуемо |
| При ошибке Claude | Явный fallback «⚠ Описание недоступно» в обоих местах | Отладка проще; UI не надо удалять |
| OAuth-токен для Claude | `CLAUDE_CODE_OAUTH_TOKEN` через env (с `application.yaml`-переопределением) | Тот же паттерн, что `TELEGRAM_BOT_TOKEN` |
| HTTP/HTTPS proxy для Claude | Отдельные env, пробрасываем через `CLIOptions.env(...)` | Без автонаследования: SDK whitelist-пропускает только HOME/PATH/… |
| Конфиг-структура | `application.ai.description.*` с `common` и `claude` подсекциями | Место под будущие провайдеры; меньше ручной валидации чем Map-of-providers |
| Имя модуля / префикса | `ai-description` / `application.ai.description` | «Описание от AI», короткое, оставляет место под `application.ai.summary` и т.п. |
| Реактивный API SDK | Используем `ClaudeAsyncClient` + `awaitSingle()` | Проект на WebFlux+coroutines, не блокируем IO-тред |
| Модель по умолчанию | `opus` (alias, без версии) | SDK/CLI резолвит в актуальную |
| Параллелизм вызовов | Semaphore, default `max-concurrent: 2` | Защита от перегрузки при всплесках детекций |
| Установка CLI в Docker | Native installer (`claude.ai/install.sh`), без Node.js | Официально рекомендованное, меньше образ, поддерживает Alpine/musl |

## 3. Архитектура модулей

Вводим **один новый Gradle-модуль** `ai-description` с внутренним
разделением пакетов. Мелкая модульная гранулярность (api/claude-impl как
отдельные модули) не нужна — проект компактный, пакетов достаточно.

```
modules/
├── common
├── model
├── service
├── telegram
├── ai-description               ← НОВЫЙ
│   └── src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/
│       ├── api/                    ← интерфейс + DTO + исключения
│       ├── config/                 ← @ConfigurationProperties
│       └── claude/                 ← реализация для Claude Code CLI
└── core
```

**Зависимости модуля:**
```
ai-description → libs.spring-ai-claude-code-sdk
ai-description → libs.bundles.coroutines
ai-description → libs.spring-boot-starter-validation (для @Validated)
ai-description → libs.jackson-module-kotlin (уже транзитивно, но фиксируем)
```

Модуль НЕ зависит от `model` или `common`: использует стандартные типы
(`UUID`, `ByteArray`), своя иерархия DTO. Это даёт возможность в будущем
переместить его в отдельный репозиторий/артефакт без bundle-переделки.

**Правки в существующих модулях:**
- `core` — добавляет `implementation(project(":modules:ai-description"))`.
- `telegram` — то же (нужен тип `DescriptionResult` в форматтере
  Telegram-сообщений).

**Создание бина с условиями:**
```kotlin
@Configuration
@EnableConfigurationProperties(DescriptionProperties::class, ClaudeProperties::class)
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class ClaudeAgentConfig {
    @Bean
    @ConditionalOnProperty("application.ai.description.provider", havingValue = "claude")
    fun claudeDescriptionAgent(/*…*/): DescriptionAgent = ClaudeDescriptionAgent(/*…*/)
}
```

При `enabled=false` — бина нет. `ObjectProvider<DescriptionAgent>.getIfAvailable()` вернёт `null`, код facade работает как сейчас без ветвлений в hot-path.

## 4. Конфигурация

### `application.yaml`

```yaml
application:
  ai:
    description:
      enabled: ${APP_AI_DESCRIPTION_ENABLED:false}
      provider: ${APP_AI_DESCRIPTION_PROVIDER:claude}

      common:
        language: ${APP_AI_DESCRIPTION_LANGUAGE:en}          # ru | en
        short-max-length: ${APP_AI_DESCRIPTION_SHORT_MAX:200}
        detailed-max-length: ${APP_AI_DESCRIPTION_DETAILED_MAX:1500}
        timeout: ${APP_AI_DESCRIPTION_TIMEOUT:60s}
        max-concurrent: ${APP_AI_DESCRIPTION_MAX_CONCURRENT:2}

      claude:
        oauth-token: ${CLAUDE_CODE_OAUTH_TOKEN:}
        model: ${CLAUDE_MODEL:opus}
        cli-path: ${CLAUDE_CLI_PATH:}                        # пусто = SDK ищет в PATH
        startup-timeout: ${CLAUDE_STARTUP_TIMEOUT:10s}
        proxy:
          http: ${CLAUDE_HTTP_PROXY:}
          https: ${CLAUDE_HTTPS_PROXY:}
          no-proxy: ${CLAUDE_NO_PROXY:}
```

### `@ConfigurationProperties`-классы

Без kotlin-defaults — все дефолты в YAML через `${ENV:fallback}`.
Остаются только `jakarta.validation`-констрейнты для runtime-валидации.

```kotlin
@ConfigurationProperties(prefix = "application.ai.description")
@Validated
data class DescriptionProperties(
    val enabled: Boolean,
    val provider: String,
    val common: CommonSection,
) {
    data class CommonSection(
        val language: String,
        @field:Min(50) @field:Max(500)
        val shortMaxLength: Int,
        @field:Min(200) @field:Max(3500)
        val detailedMaxLength: Int,
        val timeout: Duration,
        @field:Min(1) @field:Max(10)
        val maxConcurrent: Int,
    )
}

@ConfigurationProperties(prefix = "application.ai.description.claude")
@Validated
data class ClaudeProperties(
    val oauthToken: String,
    val model: String,
    val cliPath: String,
    val startupTimeout: Duration,
    val proxy: ProxySection,
) {
    data class ProxySection(
        val http: String,
        val https: String,
        val noProxy: String,
    )
}
```

### Стартовая валидация в `ClaudeDescriptionAgent.@PostConstruct`

1. `oauthToken.isBlank()` → `IllegalStateException("CLAUDE_CODE_OAUTH_TOKEN must be set when application.ai.description.enabled=true")`. Приложение **не** стартует — чтобы избежать тихой нерабочей фичи.
2. `language ∉ {"ru", "en"}` → падение с подсказкой.
3. `Query.isCliInstalled() == false` → WARN, но приложение стартует (в dev-окружении CLI может отсутствовать). Первый вызов `describe()` вернёт fallback через обычный error-path.

i18n-ключи захардкожены в коде (стабильные константы):
- `ai.description.placeholder.short`
- `ai.description.placeholder.detailed`
- `ai.description.fallback.unavailable`

## 5. Интерфейсы и компоненты

### Публичный API (`api/`)

```kotlin
interface DescriptionAgent {
    suspend fun describe(request: DescriptionRequest): DescriptionResult
}

data class DescriptionRequest(
    val recordingId: UUID,
    val frames: List<FrameImage>,      // ordered by frameIndex
    val language: String,               // "ru" | "en"
    val shortMaxLength: Int,
    val detailedMaxLength: Int,
) {
    data class FrameImage(val frameIndex: Int, val bytes: ByteArray)
}

data class DescriptionResult(
    val short: String,
    val detailed: String,
)

sealed class DescriptionException(message: String, cause: Throwable? = null)
    : RuntimeException(message, cause) {
    class Timeout(cause: Throwable? = null) : DescriptionException("Description timed out", cause)
    class InvalidResponse(cause: Throwable? = null) : DescriptionException("Claude returned invalid JSON", cause)
    class Transport(cause: Throwable? = null) : DescriptionException("Claude transport error", cause)
    class RateLimited(cause: Throwable? = null) : DescriptionException("Claude rate-limited (429)", cause)
    class Disabled : DescriptionException("Description agent is disabled")
}
```

### Реализация (`claude/`)

Ключевой класс:

```kotlin
@Component
@ConditionalOnProperty("application.ai.description.provider", havingValue = "claude")
class ClaudeDescriptionAgent(
    private val claudeProperties: ClaudeProperties,
    private val commonProperties: DescriptionProperties.CommonSection,
    private val promptBuilder: ClaudePromptBuilder,
    private val responseParser: ClaudeResponseParser,
    private val imageStager: ClaudeImageStager,
    private val clientFactory: ClaudeAsyncClientFactory,
) : DescriptionAgent {

    private val semaphore = Semaphore(commonProperties.maxConcurrent)

    override suspend fun describe(request: DescriptionRequest): DescriptionResult =
        semaphore.withPermit {
            withTimeout(commonProperties.timeout.toMillis()) {
                val stagedPaths = imageStager.stage(request)
                try {
                    val prompt = promptBuilder.build(request, stagedPaths)
                    executeWithRetry(prompt)
                } finally {
                    imageStager.cleanup(stagedPaths)
                }
            }
        }
}
```

Вспомогательные компоненты:

- **`ClaudePromptBuilder`** — system-инструкция на `language`, требование
  вернуть ровно `{"short": "...", "detailed": "..."}`, лимиты символов,
  перечисление файлов через `@/tmp/...`. Никакого YOLO-контекста.
- **`ClaudeResponseParser`** — парсит `String` через Jackson. Бросает
  `InvalidResponse` при отсутствии/пустоте полей, при prose-обёртке
  пытается извлечь JSON-блок регулярным выражением. Обрезает строки до
  `shortMaxLength`/`detailedMaxLength` с ellipsis `…`.
- **`ClaudeImageStager`** — обёртка над `TempFileHelper`.
  `stage(request)`: для каждого кадра вызывает
  `TempFileHelper.createTempFile("claude-${recordingId}-frame-${i}", ".jpg", bytes)`.
  `cleanup(paths)` → `TempFileHelper.deleteFiles(paths)` в `finally`.
  Cron-чистка «сирот» уже обеспечена PT1H-шедулером в `TempFileHelper`.
- **`ClaudeAsyncClientFactory`** — `@Component`, метод `create()`
  собирает `CLIOptions.builder().env(buildEnvMap()).model(...).timeout(...).build()`
  и возвращает `ClaudeClient.async(options).build()`. Клиент short-lived:
  один вызов — один клиент (SDK держит persistent CLI-subprocess
  для multi-turn, нам это не нужно). `buildEnvMap()` добавляет
  `CLAUDE_CODE_OAUTH_TOKEN`, `HTTP_PROXY`, `HTTPS_PROXY`, `NO_PROXY` —
  они не наследуются SDK автоматически.

### Потребление в `core`

```kotlin
class RecordingProcessingFacade(
    // существующее
    private val descriptionAgentProvider: ObjectProvider<DescriptionAgent>,
    private val descriptionScope: CoroutineScope,
) {
    suspend fun processAndNotify(...) {
        val visualizedFrames = frameVisualizationService.visualizeFrames(request.frames)
        recordingEntityService.save(...)

        val claudeHandle: Deferred<Result<DescriptionResult>>? =
            descriptionAgentProvider.getIfAvailable()?.let { agent ->
                descriptionScope.async {
                    runCatching { agent.describe(buildDescriptionRequest(...)) }
                }
            }

        telegramNotificationService.sendRecordingNotification(
            recording, visualizedFrames, claudeHandle,
        )
    }
}
```

`Result<DescriptionResult>` вместо голого `Deferred<DescriptionResult>` —
чтобы `await()` никогда не бросал исключение в edit-coroutine Telegram-слоя.
Ошибки нормализуются в `Result.failure`, потребитель выбирает fallback.

`descriptionScope` — отдельный `CoroutineScope(Dispatchers.IO + SupervisorJob())`,
зарегистрированный `@Bean` в `core/config/DescriptionScopeConfig.kt` с
`@PreDestroy`-методом `scope.cancel()` на shutdown приложения
(аналогично существующему `ExportCoroutineScope`). Падение одного
describe-job не роняет остальные — SupervisorJob изолирует ошибки.

## 6. Data flow: placeholder → Claude → edit

**Parse mode — HTML** (не MarkdownV2). В caption/тексте будут эмоджи,
точки, скобки, дефисы — MarkdownV2 требует экранировать почти всё. В HTML
экранируются только `<`, `>`, `&`.

### Single photo (1 кадр)

```
t=0   facade стартует claudeHandle = descriptionScope.async { describe(...) }
      enqueue NotificationTask(claudeHandle)

t+ε   sender берёт task, для каждого получателя делает ПОСЛЕДОВАТЕЛЬНО:
      caption_initial = currentCaption + "\n\n⏳ <i>AI анализирует кадры…</i>"
      sendPhoto(caption_initial, parseMode=HTML, replyMarkup=exportKeyboard)
          → photoMsgId
      sendTextMessage(
          text="<blockquote expandable>⏳ <i>AI готовит подробное описание…</i></blockquote>",
          replyToMessageId=photoMsgId, parseMode=HTML)
          → detailsMsgId
      editTargets.add(EditTarget(chatId, photoMsgId, detailsMsgId, isMediaGroup=false))

t+Δ   ПОСЛЕ завершения рассылки ВСЕМ получателям (т.е. editTargets полностью заполнен):
      senderScope.launch {
          val outcome = claudeHandle.await()  // Result<DescriptionResult>
          editTargets.forEach { target -> editSingleTarget(target, outcome) }
      }
```

### Media group (2-10 кадров)

```
sendMediaGroup(photos with current captions) → [messages]
firstMsgId = messages[0].messageId

text_initial (HTML) =
    "👆 Нажмите для быстрого экспорта видео\n\n" +
    "⏳ <i>AI анализирует кадры…</i>\n\n" +
    "<blockquote expandable>⏳ <i>AI готовит подробное описание…</i></blockquote>"

sendTextMessage(chat, text_initial, parseMode=HTML,
    replyToMessageId=firstMsgId, replyMarkup=exportKeyboard)
    → detailsMsgId

editTargets.add(EditTarget(chatId, null, detailsMsgId, isMediaGroup=true))
```

### Таблица редактирования

| isMediaGroup | outcome | действия |
|---|---|---|
| false | success | `editMessageCaption(photoMsgId, caption_with_short, exportKeyboard)` + `editMessageText(detailsMsgId, "<blockquote expandable>detailed</blockquote>")` |
| false | failure | то же, но со строкой «⚠ Описание недоступно» вместо `short`/`detailed` |
| true | success | `editMessageText(detailsMsgId, combined_short + expandable_detailed, exportKeyboard)` — альбом не трогаем |
| true | failure | то же, fallback-строки |

Если `claudeHandle == null` (агент выключен) — sender не добавляет
placeholder, отправляет всё как сейчас, edit-job не запускает. UX идентичен
текущему. Код форматтера содержит ровно один `if (claudeHandle != null)`.

Ошибки `editMessage*` (`message is not modified`, `message to edit not
found`) — WARN в лог и пропускаем. Retry на edit не делаем.

## 7. Error handling и retry

### Retry-логика внутри `ClaudeDescriptionAgent.executeWithRetry()`

```kotlin
private suspend fun executeWithRetry(prompt: String): DescriptionResult {
    var jsonRetries = 0
    var transportRetries = 0
    while (true) {
        try {
            val rawJson = callClaudeOnce(prompt)  // Mono.awaitSingle()
            return responseParser.parse(rawJson)
        } catch (e: DescriptionException.InvalidResponse) {
            if (jsonRetries++ >= 1) throw e
            logger.warn(e) { "Invalid JSON from Claude, retrying (attempt ${jsonRetries + 1})" }
        } catch (e: DescriptionException.Transport) {
            if (transportRetries++ >= 1) throw e
            logger.warn(e) { "Claude transport error, retrying in 5s" }
            delay(5.seconds)
        }
        // RateLimited и Disabled — без retry, наружу
    }
}
```

`withTimeout(commonProperties.timeout.toMillis())` в `describe()` — общий
потолок 60 сек. Если retry затянул — `TimeoutCancellationException` →
`Timeout`.

### Нормализация SDK-исключений → `DescriptionException`

Выполняется внутри `callClaudeOnce()`.

| SDK/рантайм | Тип |
|---|---|
| `TransportException` (SDK) | `Transport` |
| HTTP 429 (по тексту или коду SDK) | `RateLimited` |
| `TimeoutCancellationException` | `Timeout` (пробрасывается как есть) |
| `JsonProcessingException` / пустые поля | `InvalidResponse` |
| Прочие `ClaudeSDKException` | `Transport` (консервативно retryable) |
| Любой `Throwable` | Оборачиваем в `Transport`, лог WARN с оригинальным cause |

### Failure matrix в Telegram-слое

| Что случилось | UI-результат |
|---|---|
| `Timeout`, `Transport` (после retry), `RateLimited`, `InvalidResponse` (после retry), `Disabled` | `caption` + «⚠ <i>Описание недоступно</i>», expandable → «⚠ <i>Описание недоступно</i>». Нейтральный тон, без упоминания Claude. |
| Edit → `message is not modified` / `message to edit not found` | WARN, игнор |
| Edit-coroutine упала (OOM/cancel) | ERROR, placeholder остаётся — допустимая деградация |
| CLI не найден при старте (`Query.isCliInstalled() == false`) | WARN на старте: «CLI not found in PATH, all descriptions will return fallback». Приложение стартует, каждый вызов отдаёт fallback. |

### Claude OAuth expired

SDK не выделяет отдельный тип. Получаем `Transport` с текстом от CLI
(например, `Authentication failed`). Поведение — стандартный `Transport`:
retry → fallback. В логе — оригинальный текст, по нему владелец понимает,
что надо обновить `CLAUDE_CODE_OAUTH_TOKEN`.

### Уровни логирования

- **WARN** — первый failure любого типа, edit-retry-errors, CLI missing, OAuth expired.
- **ERROR** — не используем для описаний. Все ошибки ожидаемы и покрыты fallback.
- **DEBUG** — prompt (без байтов), raw Claude response (обрезанный), timing.

### Что явно НЕ делаем

- **Circuit breaker** — добавляет комплексности, откладываем до появления проблем в production.
- **Health-indicator** `/actuator/health` — избыточно для MVP, логов достаточно.
- **Metrics/Micrometer** — если в проекте уже есть prometheus-endpoint, добавим latency/success/failure counters; если нет — не вводим ради этой фичи.

## 8. Docker и окружение

### Dockerfile (`docker/deploy/Dockerfile`)

```dockerfile
# ... существующие слои runtime-stage (apk add ffmpeg fontconfig curl) ...

# Зависимости для Claude Code CLI native (musl + ripgrep per official docs)
RUN apk add --no-cache bash libgcc libstdc++ ripgrep

# Устанавливаем Claude Code под appuser в ~/.local/bin
USER appuser
RUN curl -fsSL https://claude.ai/install.sh | bash

# Настройки CLI: отключаем встроенный ripgrep и авто-апдейт
RUN mkdir -p /home/appuser/.claude \
 && printf '%s\n' '{' \
                  '  "env": {' \
                  '    "USE_BUILTIN_RIPGREP": "0",' \
                  '    "DISABLE_AUTOUPDATER": "1"' \
                  '  }' \
                  '}' > /home/appuser/.claude/settings.json

USER root
ENV PATH="/home/appuser/.local/bin:${PATH}"
USER appuser

# ... остальное (ENTRYPOINT) ...
```

**Почему native а не npm:** официально рекомендован Anthropic; самодостаточный native-бинарь, Node.js в runtime не нужен; поддерживает Alpine/musl автоматически; образ меньше на ~110 MB.

**Почему `DISABLE_AUTOUPDATER=1`:** контейнер детерминированный — версия CLI = версия образа. `docker pull` — единственный способ обновить.

**Архитектуры:** x86_64 и ARM64 поддерживаются из коробки.

### docker-entrypoint.sh

```bash
if [ "${APP_AI_DESCRIPTION_ENABLED:-false}" = "true" ]; then
    if [ -z "${CLAUDE_CODE_OAUTH_TOKEN:-}" ]; then
        echo "WARN: APP_AI_DESCRIPTION_ENABLED=true but CLAUDE_CODE_OAUTH_TOKEN is empty; AI descriptions will return fallback." >&2
    elif ! command -v claude >/dev/null 2>&1; then
        echo "WARN: claude CLI not found in PATH; AI descriptions will return fallback." >&2
    else
        echo "INFO: claude CLI detected: $(claude --version 2>/dev/null || echo 'unknown')"
    fi
fi

exec java ...  # как сейчас
```

### `.env.example`

Добавляем в конец, в стиле существующих комментариев:

```bash
# --- AI descriptions (optional) ---
# Enables Claude-generated short + detailed descriptions of detection frames.
# APP_AI_DESCRIPTION_ENABLED=true
# APP_AI_DESCRIPTION_PROVIDER=claude
# APP_AI_DESCRIPTION_LANGUAGE=en             # ru | en
# APP_AI_DESCRIPTION_SHORT_MAX=200
# APP_AI_DESCRIPTION_DETAILED_MAX=1500
# APP_AI_DESCRIPTION_TIMEOUT=60s
# APP_AI_DESCRIPTION_MAX_CONCURRENT=2

# --- Claude-specific (when provider=claude) ---
# Obtain the token ONCE on the host: `claude setup-token`,
# copy the value here. Long-lived OAuth token (works against your Claude subscription).
# CLAUDE_CODE_OAUTH_TOKEN=
# CLAUDE_MODEL=opus                          # opus | sonnet | haiku (alias)
# CLAUDE_CLI_PATH=                           # empty = SDK uses PATH
# CLAUDE_STARTUP_TIMEOUT=10s

# --- Optional proxy for Claude API calls ---
# CLAUDE_HTTP_PROXY=http://proxy:8080
# CLAUDE_HTTPS_PROXY=http://proxy:8080
# CLAUDE_NO_PROXY=localhost,127.0.0.1
```

### Отвергнутые варианты

| Вариант | Почему нет |
|---|---|
| `npm i -g @anthropic-ai/claude-code` | +110 MB Node.js в runtime без пользы |
| Sidecar-контейнер с HTTP-мостом | Ломает простоту home-deployment, добавляет network layer |
| Bind-mount хостового CLI | UID/GID конфликты, привязка к машине |
| `autoUpdatesChannel=stable` вместо `DISABLE_AUTOUPDATER=1` | Обновление всё равно случится и рассинхронизирует рестарты |

## 9. Тесты

### Unit-тесты в `ai-description`

**`ClaudePromptBuilderTest`:** language ru/en в инструкции, числовые
лимиты в тексте, пути `@...` в правильном порядке по `frameIndex`,
детерминированность.

**`ClaudeResponseParserTest`:** валидный JSON → `DescriptionResult`;
невалидный/пустые поля → `InvalidResponse`; prose+JSON → extract
регуляркой; overlong строки → обрезание с `…`.

**`ClaudeImageStagerTest`:** вызывает `TempFileHelper.createTempFile` для
каждого кадра, порядок по `frameIndex`, cleanup удаляет ровно переданные
пути. `@TempDir` + реальный `TempFileHelper`.

**`ClaudeDescriptionAgentTest`** (MockK-heavy):
- Успех → parse → result; semaphore acquire+release.
- `InvalidResponse` → retry 1 раз, второй fail пробрасывается.
- `Transport` → retry с `delay(5s)` (через `runTest` + virtual time).
- `RateLimited` → без retry.
- Превышен timeout → `Timeout` (через `withTimeout` + `runTest`).
- `cleanup` в `finally` независимо от исхода.
- Concurrency: 3 вызова при `maxConcurrent=2`, третий ждёт slot.

**`DescriptionExceptionMappingTest`:** mapping SDK-исключений → наших
типов; 429 по тексту → `RateLimited`.

### Изменения в существующих тестах

**`RecordingProcessingFacadeTest`** — 3 новых сценария:
- agent disabled → telegram получает `claudeHandle = null`, регрессия.
- agent enabled success → Deferred не null, telegram получает handle.
- describe бросает исключение → facade не падает (поймано `runCatching`),
  handle содержит `Result.failure`.

**`TelegramNotificationSenderTest`** — 4 новых сценария:
1. Single-photo + описание success → sendPhoto, sendTextMessage (reply),
   editMessageCaption, editMessageText.
2. Single-photo + описание fail → те же edit, но fallback-строки.
3. Media-group + описание success → sendMediaGroup, sendTextMessage (reply
   на первое фото), editMessageText. editMessageCaption **не** вызывается.
4. `claudeHandle == null` → регрессия, никаких placeholder/edit.

HTML-escape: вход `"car <2> & person"` → проверяем `&lt;2&gt; &amp;` в
выходном caption/expandable.

### Интеграционный тест (opt-in)

`ClaudeDescriptionAgentIntegrationTest` — `@EnabledIfEnvironmentVariable(named="INTEGRATION_CLAUDE", matches="stub")`.
Создаёт bash-скрипт в `@TempDir`, имитирующий `claude`-CLI (читает
stdin-prompt, echoes pre-canned stream-json, exit 0). Конфигурирует
`CLAUDE_CLI_PATH` на этот скрипт. Проверяет end-to-end prompt → subprocess
→ parse → `DescriptionResult` без полёта к Anthropic.

### i18n

`messages_ru.properties`:
```properties
ai.description.placeholder.short=⏳ AI анализирует кадры…
ai.description.placeholder.detailed=⏳ AI готовит подробное описание…
ai.description.fallback.unavailable=⚠ Описание недоступно
```

`messages_en.properties` — перевод тех же ключей. При наличии
`MessageSourceTest` (проверка полноты ключей) — добавить покрытие.

### Что осознанно НЕ тестируем

- Latency Claude (нестабильно).
- Качество описаний (свойство модели, не кода).
- `@Scheduled`-чистка `TempFileHelper` (покрыта существующими тестами).
- Docker image (проверяется вручную при релизе).

## 10. Список изменений

### Новые файлы

**Модуль `ai-description`:**
```
modules/ai-description/build.gradle.kts
modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/
├── api/
│   ├── DescriptionAgent.kt
│   ├── DescriptionRequest.kt
│   ├── DescriptionResult.kt
│   └── DescriptionException.kt
├── config/
│   ├── DescriptionProperties.kt
│   └── ClaudeProperties.kt
└── claude/
    ├── ClaudeAgentConfig.kt
    ├── ClaudeAsyncClientFactory.kt
    ├── ClaudeDescriptionAgent.kt
    ├── ClaudeImageStager.kt
    ├── ClaudePromptBuilder.kt
    └── ClaudeResponseParser.kt

modules/ai-description/src/test/kotlin/.../claude/
├── ClaudeDescriptionAgentTest.kt
├── ClaudeImageStagerTest.kt
├── ClaudePromptBuilderTest.kt
├── ClaudeResponseParserTest.kt
├── DescriptionExceptionMappingTest.kt
└── ClaudeDescriptionAgentIntegrationTest.kt   (stub-CLI, @EnabledIfEnv)
```

**В `core`:**
```
modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/DescriptionScopeConfig.kt
```

**В `telegram`:**
```
modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/DescriptionMessageFormatter.kt
```

### Изменённые файлы

| Файл | Изменение |
|---|---|
| `settings.gradle.kts` | `include(":modules:ai-description")` |
| `gradle/libs.versions.toml` | + `spring-ai-claude-code-sdk = "1.0.0"` + alias |
| `docker/deploy/Dockerfile` | + native install Claude CLI под appuser |
| `docker/deploy/docker-entrypoint.sh` | + WARN-блок при `APP_AI_DESCRIPTION_ENABLED=true` |
| `docker/deploy/.env.example` | + секция AI-description + Claude + proxy |
| `modules/telegram/build.gradle.kts` | + `project(":modules:ai-description")` |
| `modules/telegram/.../queue/NotificationTask.kt` | + поле `descriptionHandle: Deferred<Result<DescriptionResult>>? = null` |
| `modules/telegram/.../service/TelegramNotificationService.kt` | + параметр `descriptionHandle` в `sendRecordingNotification(...)` |
| `modules/telegram/.../service/impl/TelegramNotificationServiceImpl.kt` | Проброс в NotificationTask |
| `modules/telegram/.../queue/TelegramNotificationSender.kt` | Build placeholder, захват messageId, запуск edit-job, HTML parse mode при наличии handle |
| `modules/telegram/.../i18n/messages_ru.properties` | + 3 ключа |
| `modules/telegram/.../i18n/messages_en.properties` | + 3 ключа |
| `modules/core/build.gradle.kts` | + `project(":modules:ai-description")` |
| `modules/core/.../facade/RecordingProcessingFacade.kt` | `ObjectProvider<DescriptionAgent>` + `descriptionScope`; запуск describe-job; проброс handle |
| `modules/core/src/main/resources/application.yaml` | + секция `application.ai.description.*` |
| `modules/core/.../facade/RecordingProcessingFacadeTest.kt` | + 3 сценария |
| `modules/telegram/.../queue/TelegramNotificationSenderTest.kt` | + 4 сценария |

### Порядок реализации (dependency-граф)

1. Модуль `ai-description` с API (интерфейсы, DTO, exceptions). `settings.gradle.kts`, `libs.versions.toml`, пустой `build.gradle.kts` модуля.
2. Конфигурация: properties-классы, YAML-секция, `@ConditionalOnProperty`.
3. Claude-компоненты: prompt builder, response parser, image stager, async client factory + их юнит-тесты.
4. Полная реализация `ClaudeDescriptionAgent` — retry, timeout, semaphore + тесты.
5. Facade: ObjectProvider, descriptionScope, запуск describe-job + тесты.
6. Telegram: форматтер, изменения sender-а, i18n + тесты.
7. Docker: Dockerfile, entrypoint, .env.example.
8. Opt-in integration-test со stub-CLI.

На каждом шаге build зелёный.

## 11. Риски и открытые моменты (не блокеры)

- **`editMessageCaption` в ktgbotapi v32.0.0.** В текущем коде не
  используется (только `editMessageText`/`editMessageReplyMarkup`), но
  это стандартный Bot API. Уверенность высокая, на шаге реализации
  Telegram-слоя подтверждается явным импортом. Если вдруг отсутствует —
  fallback: `editMessageText` для single-photo case (подробное описание
  эволюционирует только в reply-сообщении, caption остаётся статичным).
- **Формат SDK output.** `ClaudeAsyncClient.text()` возвращает collected
  text. Предполагаем, что внутри — ровно наш JSON. Если SDK добавляет
  envelope — правим парсер в рамках того же PR без изменения публичного
  API.
- **`libgcc`/`libstdc++`/`ripgrep` версии в Alpine.** Документация
  Anthropic требует их, но не фиксирует минимальные версии. Ставим
  `apk add --no-cache` без пина. Если CI на базовом образе `azul/zulu-openjdk-alpine:25`
  тянет несовместимые — пинуем версию явно.
- **Изоляция от `model`/`common`.** Сознательно не делаем модуль
  зависимым от внутренних DTO проекта — конверсия `FrameData → FrameImage`
  происходит в `core`-слое (facade). Если в будущем нужна будет
  дополнительная информация из `FrameData` в промпте (метаданные кадра,
  timestamp) — добавим поле в `DescriptionRequest.FrameImage` явно,
  без втягивания `model` как зависимости.
