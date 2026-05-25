# Merged Design Review — Iteration 1

**Date:** 2026-05-25
**Topic:** status-telegram
**Design:** `docs/superpowers/specs/2026-05-25-status-telegram-design.md`
**Plan:** `docs/superpowers/plans/2026-05-25-status-command.md`

**Review agents (5):**
- codex-executor (gpt-5.5, xhigh reasoning)
- ccs-executor (glm-5.1)
- ollama-executor (ollama-kimi / kimi-k2.6:cloud)
- ollama-executor (ollama-minimax / minimax-m2.7:cloud)
- ollama-executor (ollama-deepseek / deepseek-v4-pro:cloud)

---

## codex-executor (gpt-5.5)

### Critical Issues

1. План нарушает модульные зависимости. `StatusCommandHandler` предлагается создать в `modules/telegram` и импортировать `ru.zinin.frigate.analyzer.core.service.StatusService` (`plan:1299`). Но `telegram` не зависит от `core`: наоборот, `core` зависит от `telegram` в `modules/core/build.gradle.kts:37`, а `telegram` зависит только от `common/model/service/ai-description` в `modules/telegram/build.gradle.kts:9`. Это не скомпилируется или потребует циклической зависимости. Лучше разместить `StatusCommandHandler` в `core`-модуле.

2. Реализация i18n для offline-строки сломана по индексам MessageFormat. Ключи объявлены как `status.cameras.line.offline=offline {2} (last {3})` (`plan:737`, `plan:766`), но formatter передаёт только три аргумента: `camId`, `duration`, `lastSeen` (`plan:1144-1150`). Индекс `{3}` отсутствует, а `{2}` станет временем, не duration. Тесты это не поймают, потому что мок `MessageResolver` не использует реальные properties.

3. HTML escaping неполный. Design требует экранировать все динамические `camId`/server `id` (`design:276`), но `appendByCamera()` кладёт `c.camId` в `<pre>` без escape (`plan:1095-1110`). Тест проверяет только `CameraStatusDto.camId`, но не `recordings.byCameras.camId`.

4. JSON serialization contract неверен. Design утверждает `Instant -> ISO-8601`, `Duration -> PT7M` (`design:207`), но текущий `ObjectMapper` в `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/JacksonConfiguration.kt:12` создаётся через `JsonMapper.builder().findAndAddModules().build()` без отключения timestamp-сериализации. Jackson будет писать числами. Нужно явно отключить `WRITE_DATES_AS_TIMESTAMPS` и `WRITE_DURATIONS_AS_TIMESTAMPS`, либо поменять контракт.

5. REST `/status` остаётся без авторизации. Принято "OWNER only", но план защищает только Telegram-команду через `requiredRole = OWNER`. REST-контроллер публичный, как `/statistics` (`modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/controller/StatisticsController.kt:21`). Механизма авторизации REST в проекте сейчас нет.

### Concerns

- `order = 6` конфликтует с существующим `/language` (`LanguageCommandHandler.kt:31`). `FrigateAnalyzerBot` сортирует по `order`, затем по `command` (`FrigateAnalyzerBot.kt:80`) — "сразу после `/version`" не гарантируется.
- `StatusControllerTest` не проверяет ISO-8601 для `Instant`/`Duration`, хотя design это обещает (`design:380`).
- `SignalLossMonitorTaskSnapshotTest` не проверяет defensive copy: нужно после snapshot вызвать `tick()` и убедиться, что старый snapshot не мутирует.
- `./gradlew compileKotlin` в Task 8 (`plan:814`) проверяет root task, а не все модули. Лучше явно `:frigate-analyzer-core:compileKotlin :frigate-analyzer-model:compileKotlin` или полный `build`.
- Тесты formatter слишком завязаны на мок `MessageResolver`. Есть готовый паттерн с реальным `ReloadableResourceBundleMessageSource` в `MessageResolverTest.kt:10`.
- Заявленные проверки ru/en, timezone, sorting, owner auth, generic error (`design:379-383`) покрыты лишь частично — например, `StatusCommandHandlerTest` проверяет только metadata.
- Поиск stale references только в `modules/` (`plan:797-800`) пропустит документацию, упоминающую `/statistics`, например `docs/incidents/2026-05-24-recovery.md:214`.

### Suggestions

- Перенести `StatusCommandHandler` в `modules/core` (например `core/bot/handler/StatusCommandHandler.kt`). `StatusMessageFormatter` оставить в `telegram` — он зависит только от `model` и i18n.
- В formatter использовать реальные resource bundle tests хотя бы для одного RU и EN сценария с offline-камерой — это сразу поймает `{2}/{3}` ошибку.
- Добавить тесты: 404 на `/statistics` после удаления; HTML-escape для `byCameras.camId`; alphabetical sorting для двух OFFLINE cam id и двух DEAD server id; `monitoringEnabled=true` при пустом `snapshotStates()`.
- Для Telegram-лимита 4096 символов заранее добавить hard cap или split на несколько сообщений (`recordings.byCameras` может быть длиннее "20 камер").
- Сделать пользовательский текст точнее: `application.signal-loss.enabled=false` или `SIGNAL_LOSS_ENABLED=false`, а не `Monitoring disabled (signal-loss.enabled=false)`.

### Questions

- OWNER-only должен относиться только к Telegram `/status` или также к REST `/status`?
- REST-контракт точно отдаёт `offlineFor` как ISO duration (`PT7M`), или для скриптов удобнее `offlineForSeconds: Long?`?
- Нужно ли обновлять operational docs/scripts с `/statistics`, или исторические incident docs оставляем как есть?

---

## ccs-executor (glm-5.1)

### Critical Issues

1. **`TelegramUserDto` does not contain `olsonCode` — plan will not compile.** Design and plan reference `user.olsonCode` everywhere (in `StatusCommandHandler.handle()`, in `StatusMessageFormatter.format()`), but `TelegramUserDto` (`telegram/dto/TelegramUserDto.kt:7-21`) has no `olsonCode` field. Only `languageCode` exists. Timezone is stored in `TelegramUserEntity.olsonCode` and accessible via `TelegramUserService.getUserZone(chatId)` (separate DB query). **Consequence:** Task 10 (`StatusCommandHandler.kt`) won't compile. **Resolution options:** (A) Add `olsonCode: String?` to `TelegramUserDto` and populate via mapper; (B) Inject `TelegramUserService` into `StatusCommandHandler`, call `getUserZone(chatId)`; (C) Extend `AuthResult.Active` so `AuthorizationFilter` already resolves zone (breaks current interface).

2. **`order = 6` conflicts with `LanguageCommandHandler`.** Plan assigns `StatusCommandHandler.order = 6`, but `LanguageCommandHandler` already uses 6. Existing order map: Start=1, Help=2, Export=3, Timezone=4, Version=5, **Language=6**, Notifications=7, AddUser=10, RemoveUser=11, Users=12. Tie-breaker is lexicographical by `command` (`language` vs `status`), so not a bug, but unexplained duplication is a risk. Either set `order = 8` or document the intentional duplication.

3. **`StatusMessageFormatter` duplicates camId in camera lines.** In `appendCameras()`, MessageFormat receives `item.camId` as `{0}`, but template `status.cameras.line.online=online ({1} ago)` doesn't use `{0}`. For `offline` (`offline {2} (last {3})`) camId is passed but never used in template. Meanwhile `camPadded` is prepended outside MessageFormat. Hybrid of two approaches — inconsistent. Either remove `{0}` from `msg.get()` calls, or remove `camPadded` and let MessageFormat handle full string.

### Concerns

- `StatusService` in `core` transitively depends on `telegram` module through `SignalLossMonitorTask` → `TelegramNotificationService`. Works with current `core → telegram` dependency direction, but worth noting that REST `/status` becomes inactive if telegram module fails to bootstrap.
- `messages_ru.properties` encoding comment is misleading. Plan (Task 7) claims file is ISO-8859-1 with `\uXXXX` escapes. Actual file contains Cyrillic directly (UTF-8). No `\uXXXX` escapes needed.
- `SignalLossMonitorTaskSnapshotTest` is brittle — depends on default `activeWindow = 30m`.
- `coerceAtLeast(Duration.ZERO)` for offlineFor — reasonable for clock skew, but explicit documentation needed.
- HTML-escape applied to already MessageFormat-formatted result — `escape(line)` where `line` includes `ago` from `formatDuration()`. Risk of double-escape if `formatDuration` ever returns `&lt;`.
- Detect server sort test is incomplete — checks only one DEAD + one ALIVE. No coverage for two DEAD/ALIVE id ordering.

### Suggestions

- `StatusControllerTest` (Task 6) — `@AutoConfigureWebTestClient` missing.
- URI `/status` without context path — app has context path `/frigate-analyzer`. Check how `StatisticsController` is tested today.
- `SignalLossMonitorTaskSnapshotTest` doesn't verify defensive copy — get snapshot → call `tick()` again → verify first snapshot unchanged.
- No concurrency test for `snapshotStates()` and `tick()`.
- No 4096-char Telegram limit protection.
- Potential N+1 query problem in `StatusService` (5 separate SQL queries inherited).

### Questions

1. **How will user timezone be obtained?** `TelegramUserDto` lacks `olsonCode`.
2. **Update `TelegramUserDto`?**
3. **Handler order:** intentional collision or unique value?
4. **Why doesn't `StatusMessageFormatter` reuse `SignalLossMessageFormatter.buildLossMessage()`?**

---

## ollama-executor (ollama-kimi)

### Critical Issues

1. **Ошибка в i18n-шаблоне `status.cameras.line.offline`** — ключ `offline {2} (last {3})`, но в форматтере передаётся только 3 аргумента (`item.camId`, `formatDuration`, `lastSeen`). Индексы 0-based: `{2}` попадает на `lastSeen`, `{3}` отсутствует. Результат: `offline 18:31:22 (last )`. **Нужно исправить на `offline {1} (last {2})`.**

2. **`StatusControllerTest` не скомпилируется без `@AutoConfigureWebTestClient`** — `IntegrationTestBase` не имеет этой аннотации, и в проекте `WebTestClient` создаётся вручную через `bindToApplicationContext` (см. `FrigateAnalyzerApplicationTests.kt:16`).

3. **Коллизия `order = 6`** — `LanguageCommandHandler` уже имеет `order = 6` (`LanguageCommandHandler.kt:33`).

4. **Несоответствие теста и реализации: `exception → common.error.generic`** — в разделе Testing дизайна заявлено, но реализация `StatusCommandHandler` в плане не содержит `try/catch`.

### Concerns

- Смешение `kotlin.test` и AssertJ — проект на JUnit 5 + AssertJ, не `kotlin.test`.
- Переусложнённый мок в `StatusMessageFormatterTest` — лезет во внутренности `SignalLossMessageFormatter` через `MessageResolver` вместо прямого мока.
- `Duration` в JSON: риск сериализации — зависит от наличия `JavaTimeModule`. Тест должен явно проверять `"PT600S"`, а не `isString`.
- Микро-дрейф `now` между `StatusService` и `StatusMessageFormatter`.
- Неверное примечание о кодировке `messages_ru.properties` — файл содержит сырые UTF-8 кириллические символы, не `\uXXXX`.
- Хардкод `"(none)"` в форматтере вместо i18n-ключа.
- Неиспользуемая переменная `port` в `StatusControllerTest`.

### Suggestions

- Используйте `ObjectProvider` корректно в тестах.
- Проверьте `CameraSignalState` в cleanup-логике (OFFLINE камера висит вечно — upper bound?).
- Унифицируйте сигнатуру `reply` для ktgbotapi.
- `StatusControllerTest` слишком поверхностный (только типы полей, не сортировка).

### Questions

1. Есть ли глобальный обработчик исключений в `FrigateAnalyzerBot`?
2. Используется ли `/statistics` внешними скриптами?
3. Какой точный формат сериализации `Duration` в Jackson в этом проекте?

---

## ollama-executor (ollama-minimax)

### Critical Issues

1. **Несоответствие количества плейсхолдеров в ключе `status.servers.line.alive`** — ключ имеет `{1}`–`{8}`, но код передаёт 9 аргументов начиная с `s.id`. `{0}` (`s.id`) полностью неиспользованный, значения сдвигаются: `{1}` получает `s.id` (`"srv-a"`), а `{2}` получает `frameRequests.current`. Вывод будет `ALIVE  frame srv-a/1` вместо `ALIVE  frame 1/4`. **Исправление:** изменить ключ на корректный (с `{0}`=id, `{1}`=frame current...) или изменить код, чтобы не передавать `s.id`.

### Concerns

- Отсутствие `@Transactional` или read-isolation в `StatusService.collect()` — 5 отдельных вызовов; возможна лёгкая несогласованность между `total` и `processed`.
- `ObjectProvider.ifAvailable` — Spring timing нюанс, стоит явно упомянуть в спеке.
- Лимит Telegram 4096 символов — soft acknowledge. При 50 камерах + длинных именах может переполнить. Стоит добавить `…` truncate.
- `CameraSignalState` — internal type в `core/task/`: импорт в `StatusService` (внутри того же модуля) работает, но при будущем переносе сервиса в `telegram` цепочка зависимостей сломается.
- Двойной try/catch в `StatusCommandHandler` — внешний catch в `FrigateAnalyzerBot.registerRoutes()` уже ловит. Внутренний redundant.

### Suggestions

- `coerceAtLeast(Duration.ZERO)` в `StatusMessageFormatter.appendCameras()` для fallback-пути.
- Добавить `lastUpdatedAt: Instant` в `StatusResponse` для скриптов-консьюмеров.
- Полнота grep при удалении `StatisticsController`/`StatisticsResponse`: проверить также OpenAPI/Swagger `@Schema(implementation = StatisticsResponse::class)` в `*.java`.
- i18n alignment: `messages_ru.properties` использует `зап/мин` (короче), padding должен корректно компенсировать.

### Questions

1. Что происходит, если `StatusService.collect()` бросает исключение из репозитория?
2. Поведение `HelpCommandHandler` для OWNER без активного chatId?
3. `RecordingEntityRepository.getStatisticsByCameras()` уже сортирует по `camId` — сортировка избыточна (no-op).
4. Точная сигнатура `reply()` в ktgbotapi для этой версии — не верифицировано.
5. ISO-8859-1 кодировка `messages_ru.properties` — соответствует существующей конвенции? (фактически: нет, файл UTF-8)

---

## ollama-executor (ollama-deepseek)

### Critical Issues

1. **`TelegramUserDto` не содержит поле `olsonCode`** — Design line 104, Plan Task 10 Step 3 используют `user?.olsonCode`. Поле отсутствует в DTO. Реальные handlers используют `userService.getUserZone(chatId.chatId.long)` (см. `TimezoneCommandHandler:47`, `ExportCommandHandler`). План не указывает `TelegramUserService` как зависимость — не скомпилируется.

2. **Duplicate identifiers in formatter output — systematic i18n key bug** — `camId` и `server.id` печатаются дважды: один раз через `escape(camPadded)` / `escape(idPadded)`, второй раз как `{0}` аргумент. `status.cameras.line.offline=offline {2} (last {3})` — `{3}` это missing argument. Все четыре ключа (online/offline/alive/dead) требуют argument-numbering shift после удаления id.

3. **`messages_ru.properties` encoding claim incorrect** — план говорит "ISO-8859-1 encoded with `\uXXXX` escapes", файл фактически UTF-8 с прямой кириллицей (`common.cancel=Отмена`). Misleading comment.

4. **`IntegrationTestBase` does not provide `WebTestClient`** — Plan Task 6 Step 1 autowires `WebTestClient`. `IntegrationTestBase` это `@SpringBootTest(webEnvironment = RANDOM_PORT)` через Testcontainers — нет `WebTestClient` bean. `@AutoConfigureWebTestClient` упомянут как optional, фактически required.

### Concerns

- No error handling в `StatusCommandHandler.handle()` — все существующие handlers ловят errors (e.g. `StartCommandHandler`, `ExportCommandHandler`).
- `now` mismatch между `StatusService` (`Instant.now(clock)`) и `StatusCommandHandler` (отдельный `Instant.now()` без `clock`) — архитектурно неконсистентно.
- `reply()` + `HTMLParseMode` API compatibility risk — нет существующих handlers с этой комбинацией; только `TelegramNotificationSender` использует `bot.sendTextMessage(...)` с HTML.
- Возможная snapshot inconsistency при concurrent `tick()` — `ConcurrentHashMap.toMap()` может захватить intermediate state. Безопасно, но стоит документировать.
- `StatusService` не re-sorts `recordings.byCameras` — нужно verify SQL `ORDER BY cam_id` в `getStatisticsByCameras()`.

### Suggestions

- Добавить тест, что server id не дублируется в выходе.
- Добавить тест error path в `StatusCommandHandlerTest`.
- `SignalLossMonitorTask.snapshotStates()` — документировать thread-safety в KDoc.
- Рассмотреть сортировку `byCameras` OFFLINE-first для consistency.
- Plan Task 9 использует `kotlin.test.assertEquals` — стандарт проекта это AssertJ; унифицировать.

### Questions

1. Should `@AutoConfigureWebTestClient` go on `IntegrationTestBase` for future tests?
2. `/statistics` → 404 без deprecation — есть ли monitoring/Prometheus/health-checks?
3. Format of `offlineFor` in REST: ISO-8601 (`PT7M`) vs raw seconds?
4. `@ConditionalOnProperty(application.telegram.enabled=true)` on `StatusMessageFormatter` — confirmed correct.
