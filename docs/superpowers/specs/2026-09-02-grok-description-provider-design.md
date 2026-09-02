# Grok Build как второй провайдер AI-описаний

**Дата:** 2026-09-02
**Ветка:** `feature/grok-description-provider`
**Предыстория:** исследование Grok Build 1.0.13 и семь проб на grok-4.6 в сессии 2026-09-02. Факты, на которые опирается дизайн, собраны в приложении A.

## Задача

Модуль `ai-description` генерирует короткое и подробное описание детекций через Claude Code CLI и
правит их в уведомление Telegram. Нужен второй провайдер, `application.ai.description.provider=grok`,
который делает то же самое через бинарник Grok Build от xAI.

Ограничения:

- В проде модель grok-4.6 по OAuth-подписке SuperGrok. `XAI_API_KEY` отсутствует.
- Кастомные OpenAI-совместимые модели из `config.toml` Grok (BYOK) должны работать без логина xAI.
- Авторизация контейнера живёт без ручного перелогина столько, сколько позволяет Grok. Отказ
  авторизации становится виден владельцу сразу.
- Снаружи модуля ничего не меняется: тот же `DescriptionAgent`, тот же rate limiter, та же правка
  сообщения в Telegram.
- Один активный провайдер, выбранный через `provider`. Fallback-цепочки между провайдерами нет.

## Что меняется для владельца

| | Сейчас | После |
|---|---|---|
| Провайдеры | `claude` | `claude`, `grok` |
| Вход Grok в контейнере | нет | один раз `grok login --device-code`, дальше токен обновляется сам |
| Отказ авторизации провайдера | «Описание недоступно» в каждом уведомлении, причина в логах | то же плюс одно сообщение владельцу с командой для починки и сообщение о восстановлении |
| Кастомные модели | нет | `config.toml` на томе `GROK_HOME`, имя модели в `GROK_MODEL` |

## Принятые решения

**Headless CLI через `ProcessBuilder`, не ACP и не `read_file`.** На каждую запись пишется
`prompt.json` с текстом и inline base64-кадрами, запускается `grok --prompt-file … --json-schema …
--output-format json`, ответ читается из `structuredOutput`. Ноль новых зависимостей, один ход
модели, проверено пробами. ACP-SDK для Kotlin и Java pre-1.0, тянут kotlinx-serialization и kotlinx-io,
требуют управлять долгоживущим процессом и не дают аналога `--json-schema`. Путь через `read_file`
удваивает ходы и стоимость и всё равно нуждается в headless-вызове.

**Общее ядро и backend-ы, а не копия Claude-агента.** Семафор, таймауты и retry-политика живут в
одном провайдер-нейтральном агенте. Провайдер реализует только «одну попытку describe». Владелец
планирует добавлять провайдеров и дальше, поэтому дублировать сотню строк оркестрации и её тесты в
каждом из них нельзя.

**Отказ авторизации это отдельный тип ошибки, событие и сообщение владельцу.** Refresh-токен Grok
ротируется, абсолютный срок его жизни не задокументирован, и однажды он перестанет приниматься. Тогда
каждое уведомление уходит с «Описание недоступно», а причина видна только в логах. Владелец должен
узнать о проблеме из Telegram, один раз за эпизод, с готовой командой.

**BYOK через собственный `config.toml` на томе.** Приложение не знает про кастомные модели: оно
передаёт `-m <model>`, а `[model.<name>]` с `base_url` и ключом лежит в `GROK_HOME/config.toml`.
Генерация TOML из yaml дала бы свой формат поверх чужого и конфликт с ручными правками.

**Версия Grok в образе пиннится.** Мы зависим от конкретных флагов и формы JSON-ответа, поэтому
`Dockerfile` ставит заданную `ARG GROK_VERSION` версию, а не «последнюю».

**Приложение чистит `GROK_HOME` само.** Каждый headless-запуск сохраняет сессию с base64 кадров,
политики хранения у Grok нет, а поисковый индекс сессий растёт на ~9 КБ за запуск и при удалении
каталогов не сжимается. Sweeper раз в час под эксклюзивной блокировкой удаляет каталоги сессий,
индекс и логи. Приложение единственный пользователь этого `GROK_HOME`, `grok login` сессий не создаёт.

**`--effort low` по умолчанию.** Замер прошлой сессии: reasoning 1251 → 119 токенов, 8 с на вызов,
описание кадров не требует глубокого рассуждения. Флаг настраивается и опускается при пустом значении,
чтобы не ломать BYOK-модели без уровней reasoning.

**Grok запускается в изоляции от Claude Code и Cursor.** Grok читает `~/.claude/CLAUDE.md`, skills
и плагины Claude Code; на машине разработчика это 75 skills и втрое больше входных токенов. Env
`GROK_CLAUDE_*_ENABLED=0`, `GROK_CURSOR_*_ENABLED=0`, чистый `GROK_HOME` и пустой `--cwd` дают
минимальный системный промпт и в контейнере, и локально.

## Архитектура модуля `ai-description`

```
ai/description/
  api/      DescriptionAgent, DescriptionRequest, DescriptionResult, DescriptionException,
            TempFileWriter, DescriptionProviderAuthEvent          контракт наружу
  core/     DescriptionBackend (SPI внутрь), DefaultDescriptionAgent,
            ResultNormalizer, LanguageNames                        общее ядро
  claude/   ClaudeBackend и Claude-специфика                       provider=claude
  grok/     GrokBackend и Grok-специфика                           provider=grok
  config/   AiDescriptionAutoConfiguration, DescriptionProperties,
            ClaudeProperties, GrokProperties, DescriptionAgentSanityChecker
  ratelimit/ DescriptionRateLimiter                                без изменений
```

### `api/`

`DescriptionException` получает пятый тип, `Unauthorized(detail: String, cause: Throwable? = null)`:
провайдер отверг учётные данные. Тексты остальных типов становятся провайдер-нейтральными
(«Description provider returned an invalid response» вместо «Claude returned invalid JSON» и так далее).

`DescriptionProviderAuthEvent(provider: String, state: State, detail: String?, recoveryHint: String)`,
где `State` это `LOST` или `RESTORED`. Обычный Spring-event, публикуется ядром, слушается core-модулем.

### `core/`

```kotlin
interface DescriptionBackend {
    val providerId: String            // "claude", "grok"; попадает в события и логи
    val authRecoveryHint: String      // команда для починки авторизации, попадает в сообщение владельцу
    suspend fun describe(request: DescriptionRequest): DescriptionResult   // одна попытка
}
```

Backend не держит семафор и не повторяет вызовы. Он обязан бросать только `DescriptionException`
или `CancellationException`; всё остальное ядро оборачивает в `Transport`.

`DefaultDescriptionAgent(backend, descriptionProperties, eventPublisher, timeSource = Monotonic)`
реализует `DescriptionAgent` и наследует поведение сегодняшнего `ClaudeDescriptionAgent`:

- `Semaphore(maxConcurrent)`, ожидание слота не дольше `queueTimeout`, иначе `Timeout`.
- `withTimeout(timeout)` вокруг всей попытки с повторами, `TimeoutCancellationException` → `Timeout`.
- Повторы: один на `InvalidResponse` сразу, один на `Transport` через 5 с; перед повтором проверяется
  остаток бюджета (5 с и 10 с соответственно), при нехватке ошибка отдаётся как есть. `Timeout`,
  `RateLimited`, `Unauthorized` не повторяются.
- Состояние авторизации: `AtomicReference<AuthState>` со значениями `HEALTHY` и `LOST`, начальное
  `HEALTHY`. `Unauthorized` при `HEALTHY` переводит в `LOST` через `compareAndSet`, пишет ERROR с
  `authRecoveryHint` и публикует `LOST`. Успех при `LOST` переводит в `HEALTHY`, пишет INFO и
  публикует `RESTORED`. Прочие ошибки состояние не трогают. `compareAndSet` гарантирует одно событие
  на переход при параллельных вызовах. Вызовы при `LOST` не блокируются: процесс падает сразу и
  бесплатно, а первый успех после повторного входа и даёт `RESTORED`.
- Слот rate limiter при любой ошибке не возвращается, как сегодня.

`ResultNormalizer.normalize(short: String?, detailed: String?, shortMax: Int, detailedMax: Int)`:
пустое или отсутствующее поле → `InvalidResponse`; обрезка до лимита с «…» на конце без разрыва
суррогатной пары. Логика переезжает из `ClaudeResponseParser` без изменений.

`LanguageNames.of(code)`: `ru` → `Russian`, `en` → `English`, иначе `error(...)`, как сегодня в
`ClaudePromptBuilder`.

### `claude/`

`ClaudeDescriptionAgent` превращается в `ClaudeBackend`: stage кадров во временные jpg, промпт со
ссылками `@/abs/path`, invoker, парсер, `ResultNormalizer`, удаление файлов в `finally` под
`NonCancellable`. Проверки в `init` (наличие токена, исполняемость CLI) остаются. `ClaudeResponseParser`
оставляет за собой извлечение JSON-блока и полей и делегирует обрезку normalizer-у. `ClaudeExceptionMapper`
получает ветку `Unauthorized`: сообщение с `authentication_error`, `invalid api key` или `oauth token`
(без учёта регистра). `authRecoveryHint` Claude: «set CLAUDE_CODE_OAUTH_TOKEN from `claude setup-token`
or ANTHROPIC_AUTH_TOKEN». Все `Claude*`-бины становятся условными на `enabled=true` и `provider=claude`.

### `grok/`

| Класс | Ответственность |
|---|---|
| `GrokPromptBuilder` | Текстовые части промпта и константный system prompt |
| `GrokPromptFileWriter` | Сборка ACP-блоков и запись `prompt.json` через `TempFileWriter`, удаление |
| `GrokCommandBuilder` | argv и env для запуска |
| `GrokProcessRunner`, `DefaultGrokProcessRunner` | Шов для тестов и реализация на `ProcessBuilder` |
| `GrokOutputParser` | Разбор JSON из stdout |
| `GrokExceptionMapper` | Классификация ошибок по exit-коду, error JSON и `stopReason` |
| `GrokHomeGuard` | Shared/exclusive блокировка `GROK_HOME` между запусками и sweeper-ом |
| `GrokHomeSweeper` | Очистка `sessions/` и `logs/` при старте и раз в час |
| `GrokBackend` | Оркестрация одной попытки, проверки в `init` |

**`GrokPromptFileWriter`.** Файл создаётся через `TempFileWriter.createTempFile("grok-<recordingId>",
".json", bytes)`. Суффикс `.json` обязателен: любое другое расширение Grok читает как обычный текст.
Кадры сортируются по `frameIndex`. Содержимое:

```json
[
  {"type":"text","text":"<вступление>\n\nFrames (in chronological order):"},
  {"type":"text","text":"Frame 12:"},
  {"type":"image","mimeType":"image/jpeg","data":"<base64>"},
  {"type":"text","text":"Frame 17:"},
  {"type":"image","mimeType":"image/jpeg","data":"<base64>"},
  {"type":"text","text":"<правила>"}
]
```

Base64 стандартный, без переносов. В логи попадают только текстовые блоки и размер файла.

**`GrokPromptBuilder`.** Вступление: «You are analyzing surveillance camera frames captured during an
object detection event. Write both descriptions in {Language}.» Правила: «Fill the structured output
fields "short" and "detailed". "short" must not exceed {N} characters. "detailed" must not exceed {M}
characters. No markdown, no explanations.» System prompt для `--system-prompt-override`, константа:
«You describe frames from a security camera for a notification message. Answer only through the
structured output. Do not call tools and do not ask questions.»

**`GrokCommandBuilder`.** argv:

```
<cli-path или grok>
  --prompt-file <абсолютный путь prompt.json>
  --json-schema {"type":"object","properties":{"short":{"type":"string"},"detailed":{"type":"string"}},"required":["short","detailed"],"additionalProperties":false}
  --output-format json
  -m <model>
  --effort <effort>                     только при непустом effort
  --max-turns 1
  --tools read_file
  --no-plan --no-subagents --disable-web-search
  --permission-mode bypassPermissions
  --no-auto-update
  --system-prompt-override <константа>
  --cwd <working-directory>
```

`--tools read_file` нужен как allowlist: он отключает инъекцию инструментов по умолчанию, а кадры
уже inline, и инструмент модели не нужен. Env поверх окружения JVM: `GROK_HOME=<home>`,
`GROK_DISABLE_AUTOUPDATER=1`, `GROK_MEMORY=0`, `GROK_SUBAGENTS=0`, `GROK_CLAUDE_AGENTS_ENABLED=0`,
`GROK_CLAUDE_HOOKS_ENABLED=0`, `GROK_CLAUDE_MCPS_ENABLED=0`, `GROK_CLAUDE_RULES_ENABLED=0`,
`GROK_CLAUDE_SKILLS_ENABLED=0`, те же пять для `GROK_CURSOR_*`, и `HTTP_PROXY`, `HTTPS_PROXY`,
`NO_PROXY` при непустых значениях в `grok.proxy`. `XAI_API_KEY` из окружения не вырезается: если
оператор его задал, Grok использует его как fallback.

**`DefaultGrokProcessRunner`.** `suspend fun run(argv, env, cwd): GrokProcessResult(exitCode, stdout,
stderrTail)`. `ProcessBuilder(argv).directory(cwd)`, env применяется к унаследованному окружению, stdin
закрывается сразу после старта. stdout читается целиком в UTF-8, от stderr хранится хвост в 8 КиБ.
Ожидание через `process.onExit().await()`. В `finally` при живом процессе `destroyForcibly()` и
ожидание завершения: так отмена корутины по таймауту агента не оставляет процесс сиротой. Любое
исключение запуска (нет бинарника, нет прав) → `Transport`.

**`GrokOutputParser`.** stdout разбирается как один JSON-объект: `text`, `stopReason`, `sessionId`,
`structuredOutput`, `usage`, `modelUsage`, `total_cost_usd`. Неразборный stdout при exit 0 →
`InvalidResponse`. `usage`, `modelUsage` и стоимость пишутся в лог на DEBUG одной строкой с
`recordingId`.

**`GrokExceptionMapper`.** Порядок проверок:

| Условие | Результат |
|---|---|
| exit 0, `structuredOutput` содержит непустые `short` и `detailed` | `ResultNormalizer.normalize(...)` |
| exit 0, `stopReason` ∈ {`max_tokens`, `refusal`, `max_turn_requests`} или `structuredOutput` неполный | `InvalidResponse` |
| exit 0, `stopReason = cancelled` | `Transport` |
| exit 1, stdout `{"type":"error","message":…}`, message содержит `not signed in`, `grok login`, `not authenticated`, `unauthorized`, `invalid_grant`, `refresh token`, `authentication failed` | `Unauthorized(message)` |
| exit 1, message содержит `rate limit`, `too many requests` или `\b429\b` | `RateLimited` |
| exit 1, любое другое message | `Transport(message)` |
| exit 130, 143, прочие, или stdout без error JSON | `Transport` с хвостом stderr |

Сопоставление подстрок без учёта регистра. Проверка на авторизацию идёт раньше проверки на
rate limit, обе раньше общего `Transport`.

**`GrokHomeGuard`.** Мини-RW-lock на корутинах: `shared { }` для запусков, `exclusive { }` для
sweeper-а. `exclusive` держит `Mutex`, чем блокирует новые запуски, и ждёт, пока счётчик `inFlight`
не станет нулём. `shared` берёт `Mutex` только на инкремент счётчика, поэтому запуски друг друга
не ждут. Ожидание `exclusive` ограничено сверху `common.timeout` плюс секунды.

**`GrokHomeSweeper`.** Раз в час (`@Scheduled(fixedDelayString = "PT1H")`, первый запуск через
минуту после старта) под `guard.exclusive` удаляет всё содержимое `<home>/sessions/` (каталоги сессий,
`session_search.sqlite` и его `-wal`/`-shm`) и файлы в `<home>/logs/`. Grok пересоздаёт индекс и логи
при следующем запуске. `auth.json`, `config.toml` и всё остальное в `GROK_HOME` sweeper не трогает.
Ошибки удаления пишутся на WARN и не прерывают обход. Отладочное окно: сессия последнего запуска
доступна на томе до следующего прохода.

**`GrokBackend`.** `providerId = "grok"`, `authRecoveryHint = "grok login --device-code (in Docker:
docker compose exec frigate-analyzer grok login --device-code)"`. `init`: создаёт `home` и
`working-directory` (`Files.createDirectories`, ошибка → `IllegalStateException`, приложение не
стартует); если `home` существует, но недоступен на запись, WARN про `chown` (дальше упадёт сам
`grok`, и это будет `Transport` в логе); проверяет бинарник: при пустом `cli-path` ищет `grok` по
`PATH`, при непустом проверяет `Files.isExecutable`, в обоих случаях при неудаче WARN, как у Claude;
при отсутствии `<home>/auth.json` WARN с командой входа и оговоркой про BYOK.
`describe`:

```
promptFile = promptFileWriter.write(request)
try {
    command = commandBuilder.build(promptFile)
    result = guard.shared { runner.run(command) }
    if (result.exitCode != 0) throw exceptionMapper.fromFailure(result)
    output = outputParser.parse(result.stdout)
    return exceptionMapper.toResult(output, request)      // normalize или исключение по таблице выше
} finally {
    withContext(NonCancellable) { promptFileWriter.delete(promptFile) }
}
```

### `config/`

`AiDescriptionAutoConfiguration` регистрирует `GrokProperties` наравне с `ClaudeProperties` и создаёт
агента `@Bean`-методом:

```kotlin
@Bean
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
@ConditionalOnBean(DescriptionBackend::class)
fun descriptionAgent(backend: DescriptionBackend, properties: DescriptionProperties,
                     publisher: ApplicationEventPublisher): DescriptionAgent = DefaultDescriptionAgent(...)
```

`@ConditionalOnBean` в `@AutoConfiguration`-классе видит бины, зарегистрированные его же
`@ComponentScan`, поэтому неизвестный `provider` даёт отсутствие backend-а, отсутствие агента и, как
сегодня, WARN от `DescriptionAgentSanityChecker` с `KNOWN_PROVIDERS = listOf("claude", "grok")`.
Backend-ы это `@Component` с `@ConditionalOnProperty` на `enabled=true` и на `provider=<id>`.
`RecordingProcessingFacade` и telegram-модуль продолжают получать агента через
`ObjectProvider<DescriptionAgent>`.

## Уведомление владельца об авторизации

Слушатель живёт в core-модуле рядом со `StartupTelegramNotifier` и повторяет его устройство:
`DescriptionAuthAlertNotifier`, `@Component`, условия `application.telegram.enabled=true` и
`application.ai.description.enabled=true`, собственный `CoroutineScope(SupervisorJob + IO)` с
`@PreDestroy`, `@EventListener(DescriptionProviderAuthEvent)`, запуск в scope с `withTimeout(5 s)` и
вызов `telegramNotificationService.sendOwnerMessage { lang -> messageResolver.get(...) }`. Ошибки
доставки пишутся на WARN.

Ключи в `messages_ru.properties` и `messages_en.properties`:

| Ключ | ru | en |
|---|---|---|
| `ai.description.auth.lost` | `🔴 AI-описания: провайдер {0} отверг авторизацию. Описания недоступны до повторного входа.\nКоманда для входа: {1}` | `🔴 AI descriptions: provider {0} rejected the credentials. Descriptions stay unavailable until you sign in again.\nSign-in command: {1}` |
| `ai.description.auth.restored` | `🟢 AI-описания: авторизация провайдера {0} восстановлена.` | `🟢 AI descriptions: provider {0} credentials work again.` |

К сообщению `LOST` через пустую строку добавляется `detail` провайдера, обрезанный до 300 символов,
без локализации. Аргументы: `{0}` это `providerId`, `{1}` это `authRecoveryHint`.

## Конфигурация

`GrokProperties`, префикс `application.ai.description.grok`, `@Validated`, биндится всегда.

| Свойство | Env | Default | Валидация |
|---|---|---|---|
| `cli-path` | `GROK_CLI_PATH` | пусто | пусто = `grok` из `PATH` |
| `model` | `GROK_MODEL` | `grok-4.6` | `@NotBlank` |
| `effort` | `GROK_EFFORT` | `low` | пусто = флаг не передаётся |
| `home` | `GROK_HOME` | `${application.temp-folder}/grok-home` | `@NotBlank`; путь нормализуется в коде |
| `working-directory` | `GROK_WORKING_DIR` | `${application.temp-folder}/grok-cwd` | `@NotBlank` |
| `proxy.http` | `GROK_HTTP_PROXY` | пусто | |
| `proxy.https` | `GROK_HTTPS_PROXY` | пусто | |
| `proxy.no-proxy` | `GROK_NO_PROXY` | пусто | |

Секция в `modules/core/src/main/resources/application.yaml` после `claude:`:

```yaml
      grok:
        cli-path: ${GROK_CLI_PATH:}
        model: ${GROK_MODEL:grok-4.6}
        # Пусто = флаг --effort не передаётся (BYOK-модели без уровней reasoning).
        effort: ${GROK_EFFORT:low}
        # Собственный каталог Grok: auth.json, config.toml, сессии. В контейнере это том.
        # Та же переменная ведёт ручной `grok login` внутри `docker compose exec`.
        home: ${GROK_HOME:${application.temp-folder}/grok-home}
        # Пустой каталог для --cwd: Grok читает из cwd AGENTS.md, CLAUDE.md, .claude/rules и .grok.
        working-directory: ${GROK_WORKING_DIR:${application.temp-folder}/grok-cwd}
        proxy:
          http: ${GROK_HTTP_PROXY:}
          https: ${GROK_HTTPS_PROXY:}
          no-proxy: ${GROK_NO_PROXY:}
```

Тестовый `modules/core/src/test/resources/application.yaml` получает ту же секцию с литералами.
Списки свойств в `AiDescriptionAutoConfigurationTest` дополняются grok-ключами.

Env-переменная нарочно называется `GROK_HOME`: та же переменная в окружении контейнера ведёт и
ручной `grok login`. Дефолт `${application.temp-folder}/grok-home` использует ту же форму ссылки, что
`claude.working-directory`, и наследует её оговорку из `configuration.md`.

## Деплой

**`docker/deploy/Dockerfile`.** `ARG GROK_VERSION=1.0.13`. Под `appuser` после установки Claude:
`curl -fsSL https://x.ai/cli/install.sh | bash -s "$GROK_VERSION"`; бинарник статический, apk-пакетов
не добавляется, симлинк `~/.local/bin/grok` попадает в уже прописанный `PATH`. В блок `mkdir -p`
добавляется `/application/grok-home` с владельцем `appuser`.

**`docker/deploy/docker-compose.yml`.** Том `./grok-home:/application/grok-home` и
`GROK_HOME=/application/grok-home` в `environment`.

**`docker/deploy/docker-entrypoint.sh`.** Проверки при `APP_AI_DESCRIPTION_ENABLED=true` ветвятся
по `APP_AI_DESCRIPTION_PROVIDER` (дефолт `claude`). Ветка `claude` это сегодняшние проверки. Ветка
`grok`: бинарник по `GROK_CLI_PATH` или `PATH` с выводом версии; `GROK_HOME` существует и доступен
на запись, иначе WARN про `chown 1000:1000`; нет `GROK_HOME/auth.json` → WARN с командой
`docker compose exec frigate-analyzer grok login --device-code` и оговоркой про BYOK. Неизвестный
провайдер → WARN.

**`docker/deploy/.env.example`.** Блок `--- Grok-specific (when provider=grok) ---` с переменными из
таблицы, процедурой входа и примером BYOK.

**Процедура первого входа** (README):

```
mkdir -p grok-home && sudo chown 1000:1000 grok-home   # иначе compose создаст каталог от root
docker compose up -d
docker compose exec frigate-analyzer grok login --device-code
```

Дальше `auth.json` живёт на томе и обновляется сам. Копировать `auth.json` с хоста нельзя:
refresh-токен ротируется, из двух копий выживает одна.

**BYOK.** В `./grok-home/config.toml` секция `[model.<name>]` с `model`, `base_url` и `env_key`, ключ
в `.env`, `GROK_MODEL=<name>`, `GROK_EFFORT=` пустой.

**Локальная разработка.** `GROK_HOME=/tmp/frigate-analyzer/grok-home grok login --device-code` один
раз, затем `APP_AI_DESCRIPTION_PROVIDER=grok`. Собственный `~/.grok` разработчика подойдёт тоже, но
принесёт его `config.toml`, skills и rules.

## Тестирование

Все тесты без сети и без реального `grok`.

`ai-description`:

- `DefaultDescriptionAgentTest` с фейковым `DescriptionBackend`: сценарии сегодняшнего
  `ClaudeDescriptionAgentTest` (happy path, один повтор на `InvalidResponse`, один на `Transport` на
  виртуальном времени, исчерпание бюджета, оба таймаута, семафор на два слота, `RateLimited` без
  повтора) плюс `Unauthorized` без повтора, одно событие `LOST` при параллельных отказах, `RESTORED`
  после успеха, отсутствие событий при `HEALTHY` → успех.
- `ResultNormalizerTest`: пустые поля, обрезка, суррогатные пары; перенос из `ClaudeResponseParserTest`.
- `ClaudeBackendTest`: stage → промпт → invoker → парсер, удаление файлов при ошибке; проверки `init`
  из `ClaudeDescriptionAgentValidationTest`. `ClaudeExceptionMapperTest` с ветвью `Unauthorized`.
- `GrokPromptFileWriterTest`: порядок кадров, структура блоков, суффикс `.json`, base64, удаление.
- `GrokCommandBuilderTest`: точный argv, отсутствие `--effort` при пустом значении, env изоляции и
  прокси.
- `GrokOutputParserTest`, `GrokExceptionMapperTest`: таблица классификации целиком.
- `GrokBackendTest` с фейковым runner-ом: удаление prompt-файла при успехе, ошибке и отмене; `init`
  создаёт каталоги и предупреждает об отсутствии бинарника и `auth.json`.
- `GrokHomeGuardTest`: `exclusive` ждёт `inFlight`, новые `shared` ждут `exclusive`.
- `GrokHomeSweeperTest` на `@TempDir`: удаляет содержимое `sessions/` и `logs/`, не трогает
  `auth.json` и `config.toml`.
- `DefaultGrokProcessRunnerTest` со stub-скриптом `grok` на sh, `@EnabledOnOs(LINUX, MAC)`: exit 0 с
  JSON, exit 1 с error JSON, хвост stderr, отмена убивает `sleep`-процесс.
- `AiDescriptionAutoConfigurationTest`: `provider=claude` даёт `ClaudeBackend` и агента; `provider=grok`
  даёт `GrokBackend` и агента без Claude-бинов; неизвестный provider без агента и без падения;
  `enabled=false` без бинов.

`core`: `DescriptionAuthAlertNotifierTest` с моком `TelegramNotificationService` (`LOST` → текст с
hint и detail, `RESTORED` → текст, обрезка detail), `GrokPropertiesBindingTest` поверх
`ProductionYamlBinder` (дефолты, пустой `GROK_EFFORT`, переопределение `GROK_HOME`).

`telegram`: новые ключи присутствуют в обоих бандлах.

**Живая проверка вне CI**, последняя задача плана: сборка образа, вход по device code, одна запись
с реальными кадрами, DEBUG-строка с токенами и стоимостью, `grok inspect` из `working-directory` с
env приложения показывает 0 skills и 0 rules, проход sweeper-а, переименование `auth.json` даёт
сообщение владельцу и «Описание недоступно», повторный вход даёт `RESTORED`.

## Документация

- `.claude/rules/ai-description.md`: таблица слоёв `api/core/claude/grok/config`, событие авторизации
  и уведомление, guard и sweeper, форма вызова Grok и его ответов.
- `.claude/rules/configuration.md`: значения `APP_AI_DESCRIPTION_PROVIDER`, переменные Grok.
- `README.md`, раздел «AI description»: два провайдера, процедура входа, BYOK.
- `CLAUDE.md`: строка модуля `ai-description` и ключевой паттерн «AI description» упоминают оба
  провайдера.
- `docker/deploy/.env.example`.

## Риски и открытые вопросы

- **Размер `prompt.json` на реальных кадрах.** Пробы шли на 320×240. Десять кадров по 300–800 КБ
  дают файл в 4–11 МБ; лимиты API xAI на картинки в запросе не проверены. Проверяется вживую;
  ручка на случай отказа: `APP_AI_DESCRIPTION_MAX_FRAMES`.
- **Срок жизни refresh-токена.** Не задокументирован; эмпирически сессия пережила четыре дня простоя.
  Именно поэтому есть уведомление владельцу.
- **`active_sessions.json` в `GROK_HOME`.** Sweeper удаляет каталоги сессий, на которые может ссылаться
  реестр активных сессий. Ожидается, что Grok переживает это молча; проверяется вживую по stderr.
- **Формулировки ошибок Grok.** Паттерны `Unauthorized` и `RateLimited` покрывают известные тексты;
  неизвестная формулировка уйдёт в `Transport` с полным сообщением в логе, откуда её можно добавить.

## Вне рамок

- Fallback-цепочка провайдеров, одновременная работа двух провайдеров.
- ACP и долгоживущий `grok agent`.
- Генерация `config.toml` приложением, управление ключами BYOK.
- Состояние провайдера в `/actuator/health`.
- Изменения поведения Claude-провайдера, кроме маппинга ошибок авторизации в `Unauthorized`.

## Приложение A. Факты о Grok Build 1.0.13

Установка: `curl -fsSL https://x.ai/cli/install.sh | bash -s <version>`, бинарник `~/.grok/bin/grok`,
симлинк `~/.local/bin/grok`, static-pie, на Alpine работает без библиотек.

Headless включается `-p`, `--prompt-file` или `--prompt-json`. `--prompt-file` с расширением `.json`
читается как ACP content blocks (`text`, `image` с `mimeType` и base64 `data`), любое другое
расширение как текст. Одна строка argv в Linux ограничена 128 КиБ, поэтому `--prompt-json` для
кадров непригоден.

`--output-format json`: один объект `{text, stopReason, sessionId, requestId, thought?, usage{input_tokens,
cache_read_input_tokens, cache_creation_input_tokens, output_tokens, reasoning_tokens, total_tokens},
modelUsage{<model>{inputTokens, outputTokens, modelCalls, costUSD}}, total_cost_usd, total_cost_usd_ticks,
structuredOutput}`. `stopReason` ∈ {`end_turn`, `max_tokens`, `max_turn_requests`, `refusal`, `cancelled`}.
`--json-schema '<schema>'` кладёт разобранный объект в `structuredOutput` (camelCase). Ошибка: exit 1 и
на stdout `{"type":"error","message":"…"}`, тот же текст на stderr. Exit 130 и 143 по сигналам.
Стоимость приходит и по подписке.

Без авторизации (пустой `GROK_HOME`) headless отвечает exit 1 и
`{"type":"error","message":"Not signed in. To authenticate without a browser, run:\n  grok login --device-code\n\nAlternatively, set the XAI_API_KEY environment variable or run `grok login` on a machine with a browser."}`.
Первый запуск в пустом `GROK_HOME` создаёт `config.toml`, `README.md`, `docs/`, `logs/unified.jsonl`,
`sessions/session_search.sqlite`, `active_sessions.json` и служебные файлы.

Flags: `--cwd`, `--tools` (allowlist, отключает инъекцию по умолчанию), `--disallowed-tools`,
`--max-turns`, `--no-plan`, `--no-subagents`, `--disable-web-search`, `--permission-mode bypassPermissions`
(= `--always-approve` = `--yolo`), `--no-auto-update` плюс env `GROK_DISABLE_AUTOUPDATER=1`,
`--effort none|minimal|low|medium|high|xhigh|max` (у grok-4.6 по умолчанию high, `max` не принимает),
`--system-prompt-override`, `--rules`, `--sandbox`, `-m`, `--verbatim`. `RUST_LOG` в headless по
умолчанию off.

Grok читает из cwd и `HOME`: `AGENTS.md`, `CLAUDE.md`, `.claude/rules/*.md`, `~/.claude/CLAUDE.md`,
`~/.claude/skills`, плагины и `~/.grok/rules`. Отключается env `GROK_CLAUDE_{AGENTS,HOOKS,MCPS,RULES,SKILLS}_ENABLED=0`
и `GROK_CURSOR_{…}_ENABLED=0`, память `GROK_MEMORY=0`, субагенты `GROK_SUBAGENTS=0`.

Замеры на grok-4.6, тестовый jpg 320×240: HOME разработчика с 75 skills 18 874 входных токена и
$0.0070; с `--system-prompt-override` и `--tools read_file` 9 307 и $0.0045; плюс `--effort low`
9 177 токенов, 8.2 с, $0.0033; чистый `GROK_HOME` только с `auth.json` 3 048 токенов, 11.3 с, $0.0013.

Авторизация: OIDC на `auth.x.ai`, scope `offline_access grok-cli:access`, хранится в
`$GROK_HOME/auth.json` (0600). Access-токен живёт 6 часов, обновляется за 5 минут до истечения
(`GROK_AUTH_EARLY_INVALIDATION_SECS`) или по 401. Refresh-токен ротируется; параллельные процессы с
одним `auth.json` согласуются через диск; изменения файла подхватываются на лету. `grok login
--device-code` даёт такую же самообновляющуюся сессию без браузера. Аналога `claude setup-token` нет.
Приоритет credentials: per-model `api_key`/`env_key` из `[model.<name>]` → session token из
`auth.json` → `XAI_API_KEY`.

Сессии: `$GROK_HOME/sessions/<url-encoded cwd>/<uuid>/` с `chat_history.jsonl` и `updates.jsonl`, оба
содержат base64 кадров; ~130 КБ на пробу с картинкой 320×240. `sessions/session_search.sqlite` растёт
~9 КБ на сессию и не хранит base64. Политики хранения нет, есть `grok sessions delete <id>`. Read-only
`GROK_HOME` не подходит: не сохранится обновлённый `auth.json`.
