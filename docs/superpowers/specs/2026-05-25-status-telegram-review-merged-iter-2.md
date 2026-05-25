# Merged Design Review — Iteration 2

**Date:** 2026-05-25
**Topic:** `/status` REST + Telegram command
**Reviewers requested:** 5 (codex-executor, ccs-executor:glm, ollama-executor × 3)
**Reviewers responded:** 3 (codex, ollama-minimax, ollama-deepseek)
**Reviewers stalled:** 2 (ccs-glm, ollama-kimi — оба прекратили активность ~15:24–15:26, через 10+ минут без событий; пользователь решил продолжить с 3 готовыми отчётами)

---

## codex-executor (gpt-5.5, xhigh)

**Work dir:** `/home/zinin/.claude/codex-interaction/2026-05-25-15-16-06-90342-design-review-status-telegram-iter-2-codex/`

### Critical Issues

1. **Реальный i18n-тест форматтера в плане сейчас не является исполнимым.**
   В плане (`docs/superpowers/plans/2026-05-25-status-command.md:1117`) используется `assertThat`, но в `modules/telegram/build.gradle.kts:23` нет AssertJ / `spring-boot-starter-test`. Плюс RU-тест оставлен как "body omitted" в плане (`:1179`). Это ломает главный regression guard против i18n placeholder-сдвигов. Нужно либо добавить test dependency, либо переписать на `kotlin.test`, и дать полный код RU-теста.

2. **Неверная/несогласованная env var для disabled-сценария.**
   План добавляет текст `APPLICATION_SIGNAL_LOSS_ENABLED` в i18n (`:830`), но реальная конфигурация использует `SIGNAL_LOSS_ENABLED` в `modules/core/src/main/resources/application.yaml:49`. Manual check тоже противоречит сам себе: запускает `APPLICATION_SIGNAL_LOSS_ENABLED=false`, но ожидает старый текст `signal-loss.enabled=false` (`:1749`). Перед реализацией нужно выбрать один операторский контракт, лучше `SIGNAL_LOSS_ENABLED`, как уже в конфиге и guard-сообщении.

### Concerns

1. **Автотест контроллера не проверяет публичный URL с base-path.**
   В `application.yaml:11` задан `spring.webflux.base-path: /frigate-analyzer`, manual curl использует `/frigate-analyzer/status`, но `StatusControllerTest` ходит на `/status` в плане (`:660`). Даже если конфигурация `WebTestClient` это проглотит, тест не фиксирует реальный внешний контракт.

2. **План обещает edge case "monitor bean есть, snapshot пуст", но `StatusServiceTest` его не проверяет.**
   Дизайн явно различает `monitoringEnabled=false` и "мониторинг включен, камер ещё нет" в `design:441`. В тестах Task 5 есть absent-monitor и non-empty snapshot, но нет `snapshotStates() == emptyMap()` с `monitoringEnabled=true` (`:454`).

3. **Есть UI-контрактный разнобой по server rows.**
   Пример дизайна показывает серверы без emoji в `<pre>` (`design:329`), а реализация в плане добавляет `🟢/🔴` (`:1408`). Не баг компиляции, но нужно зафиксировать ожидаемый вид.

4. **Padding считается по raw id, а выводится escaped id.**
   Для `cam<&>` ширина считается до HTML escaping, потом строка становится `cam&lt;&amp;&gt;`, что ломает выравнивание в `<pre>`. Касается by-camera, camera status и server id (`:1302`, `:1335`, `:1383`). Лучше сначала escape, потом считать ширины.

5. **В документах остались stale-следы iter-1.**
   Design всё ещё говорит про `user.olsonCode` и тест handler-а в `telegram` в `design:447` и `design:457`, хотя план уже правильно использует `TelegramUserService.getUserZone()` и `core`.

### Suggestions

1. В Task 9 запускать оба тестовых класса явно, если i18n-тест будет sibling-классом: `--tests StatusMessageFormatterTest --tests StatusMessageFormatterI18nTest`. Сейчас фильтр в плане (`:1195`) может не запустить real-bundle test.

2. Если пример дизайна с `12 450` важен, добавить форматирование больших чисел. Плановая реализация сейчас делает plain `toString()` и не совпадает с примером readable mobile output.

3. Добавить отдельные formatter-тесты на escaping server id и `recordings.byCameras.camId`, не только `cameras.items.camId`.

### Questions

1. Серверные строки должны быть с emoji `🟢/🔴` или без, как в design example?

2. Disabled-текст должен ссылаться на реальный короткий env var `SIGNAL_LOSS_ENABLED` или команда хочет сознательно перейти на Spring-style override name?

---

## ccs-executor (glm)

**Work dir:** `/home/zinin/.claude/ccs-interaction/2026-05-25-15-16-09-90427-design-review-status-telegram-iter-2-ccs-glm/`

**Статус:** ⚠️ Reviewer не вернул отчёт. Прочитал 56 файлов проекта, raw.jsonl стоит без новых событий с 15:24:52 (через ~21 минуту от старта). Финальный `output.txt` не сгенерирован. Считается **stalled**; пропущен по решению пользователя.

---

## ollama-executor (ollama-kimi, K2.6 cloud)

**Work dir:** `/home/zinin/.claude/ollama-interaction/2026-05-25-15-16-24-90841-design-review-status-telegram-iter-2-ollama-kimi/`

**Статус:** ⚠️ Reviewer не вернул отчёт. Модель оставалась в tool-use фазе (Grep / Read), не дойдя до финальной генерации. Watchdog last alive 15:26:58 (10+ мин назад) — сам watchdog тоже прекратил пинги. Считается **stalled**; пропущен по решению пользователя.

---

## ollama-executor (ollama-minimax, MiniMax M2.7 cloud)

**Work dir:** `/home/zinin/.claude/ollama-interaction/2026-05-25-15-16-54-91690-design-review-status-telegram-iter-2-ollama-minimax/`

Reviewer проверил репозиторий и подтвердил следующие observations:

- `SignalLossMonitorTask.kt` — метод `snapshotStates()` действительно отсутствует (должен быть добавлен).
- `StatisticsResponse.kt` — содержит все типы, которые план предлагает расщепить.
- `CameraStatisticsDto.kt` — существующий DTO репозитория (не путать с `CameraStatistics` response-моделью).
- `SignalLossProperties.kt` — конструктор совпадает с ожидаемым в плане.
- `DetectServerLoadBalancer.kt` — `getAllServersStatistics()` уже существует.
- `settings.gradle.kts` — модули именуются как `frigate-analyzer-$module`.
- `messages_en.properties` — `signal.duration.*` уже есть, новые `status.*` не конфликтуют.
- `JacksonConfiguration.kt` — `WRITE_DATES_AS_TIMESTAMPS`/`WRITE_DURATIONS_AS_TIMESTAMPS` не отключены (CRITICAL-5 из iter-1, должно быть исправлено).
- `CameraSignalState.kt` — структура соответствует плану.
- `TelegramUserService.getUserZone()` — возвращает `ZoneId`, подтверждено.

### Critical Issues

Нет новых критических замечаний.

### Concerns

1. **Weakly-consistent snapshot** — неочевидное поведение при конкурентном `tick()`. `ConcurrentHashMap.toMap()` даёт weakly-consistent snapshot: возможен несогласованный набор данных. Документация в KDoc достаточна, не блокирует.

2. **Timeliness `snapshotStates()` при пустом state** — если `GET /status` вызывается до первого `tick()`, вернётся пустая map → `status.cameras.empty`. Поведение согласовано с дизайном.

3. **`SignalLossMessageFormatter.formatDuration`** — секундная гранулярность ("59 sec", "2s ago") визуально шумна для status-экрана, но консистентна с существующими notification-форматами. YAGNI, наследуется.

### Suggestions

1. **Документировать invariant сортировки `byCameras`** — текущий SQL даёт ASC по `cam_id`; добавить комментарий в `StatusService.buildRecordings()` чтобы будущий рефакторинг не сломал.

2. **Ограничение количества камер в output** — при 30+ камерах с длинными `camId` риск приблизиться к 4096-char limit. План уже предусматривает truncate fallback `… +N more`.

### Questions

1. **Минимальный `pollInterval` для `SignalLossProperties`** — есть ли upper-bound check (например `<= 1m`) или любой positive Duration допустим?

2. **`StatusService.collect()` логирование ошибок** — нужно ли явное try/catch с логированием на уровне service, или router-level достаточно?

3. **Тест на dynamic toggle `signal-loss` в рантайме** — нетипичный сценарий dev-env, out-of-scope?

### Итог

Дизайн и план итерации 2 полностью согласуются с iter-1 решениями. Новых критических замечаний нет. Concern-level замечания — уточняющие, не блокирующие. Реализация выполнима.

---

## ollama-executor (ollama-deepseek, DeepSeek-V4 Pro cloud)

**Work dir:** `/home/zinin/.claude/ollama-interaction/2026-05-25-15-17-10-92181-design-review-status-telegram-iter-2-ollama-deepseek/`

### Critical Issues

1. **CRITICAL-1: `reply()` with `parseMode` API not verified** — Plan Task 10 Step 3 uses `reply(message, text, parseMode = HTMLParseMode)`, но ни один существующий handler в проекте так не делает. Verified: `VersionCommandHandler.kt:49`, `ExportCommandHandler.kt:41` используют `reply(message, text)` без parseMode; `TimezoneCommandHandler` использует `sendTextMessage`; `TelegramNotificationSender.kt:190-194` использует `bot.sendTextMessage(..., parseMode = HTMLParseMode, replyParameters = ...)`. План подтверждает неопределённость fallback'ом на `sendTextMessage`, но это нужно verify ДО реализации. Рекомендуется проверенный паттерн `sendTextMessage(message.chat, text, parseMode = HTMLParseMode, replyParameters = ReplyParameters(message.metaInfo))`.

2. **CRITICAL-2: Emoji-marker inconsistency — design vs plan для servers** — Design (lines 330-332) показывает серверы БЕЗ emoji (`srv-a ALIVE  frame 2/4 ...`); Plan Task 9 Step 3 line 1408 префиксует их `🟢`/`🔴`. Cameras консистентны в обоих документах. Servers — несогласованны. Требует явного решения.

### Concerns

1. **CONCERN-1: `StatusControllerTest` requires Docker** — Plan Task 6 Step 1 line 654 расширяет `IntegrationTestBase`, который поднимает Docker Compose (PostgreSQL + Liquibase) в `companion object init {}`. Чистый JSON-path тест не требует реальной БД; альтернатива `@WebFluxTest(StatusController::class) + @Import(StatusService::class)` с моками, но это пропускает реальный ObjectMapper round-trip. Компромисс: оставить `IntegrationTestBase`, но задокументировать Docker dependency.

2. **CONCERN-2: `offlineFor` computed в двух местах** — Service (Task 5 Step 3 line 610) вычисляет `Duration.between(state.lastSeenAt, now).coerceAtLeast(Duration.ZERO)`; Formatter (Task 9 Step 3 line 1356) перерасчёт через `item.offlineFor ?: Duration.between(item.lastSeenAt, now)`. Два источника правды. Если `offlineFor == null` для OFFLINE камеры — это баг `StatusService.toDto()`, форматтер не должен молча накрывать его.

3. **CONCERN-3: `StatusMessageFormatterI18nTest` missing `setFallbackToSystemLocale(false)`** — Plan Task 9 Step 1 line 1124 настраивает `ReloadableResourceBundleMessageSource` только с `basename` и `defaultEncoding`. Существующий `MessageResolverTest.kt:12-16` дополнительно ставит `setFallbackToSystemLocale(false)` + `setDefaultLocale(Locale.forLanguageTag("en"))`. Без них тест нестабилен на non-English dev машинах.

4. **CONCERN-4: `CameraStatistics` и `CameraStatisticsDto` имеют идентичные positional fields, разные пакеты** — Обе имеют `camId: String, recordingsCount: Long, recordingsProcessed: Long, detectionsCount: Long`. DTO — `model.dto` с `@Column` (SQL результат); model — `model.response` (JSON ответ). `StatusService.buildRecordings()` маппит DTO → model (lines 561-566). План корректен, но легко перепутать — обе принимают идентичные positional args. Предложен комментарий в `buildRecordings()`.

### Suggestions

1. **SUGGESTION-1: Use `sendTextMessage` instead of `reply()`** (linked to CRITICAL-1) — Так как `StatusCommandHandler` работает внутри `BehaviourContext.handle()`, использовать либо `sendTextMessage(message.chat, text, parseMode = HTMLParseMode)` (как `TimezoneCommandHandler`), либо verify `reply(message, text, parseMode = HTMLParseMode)` в текущей версии ktgbotapi. Рекомендация: `sendTextMessage` — известно-рабочий в проекте.

2. **SUGGESTION-2: Drop the `offlineFor` fallback in formatter** — Заменить `(item.offlineFor ?: Duration.between(item.lastSeenAt, now)).coerceAtLeast(Duration.ZERO)` на `requireNotNull(item.offlineFor) { "offlineFor must not be null for OFFLINE camera ${item.camId}" }.coerceAtLeast(Duration.ZERO)`.

3. **SUGGESTION-3: Add `setFallbackToSystemLocale(false)` + `setDefaultLocale(...)` to `StatusMessageFormatterI18nTest`** — отразить `MessageResolverTest.kt:12-16`.

### Questions

1. **QUESTION-1: Should server lines have emoji markers?** (linked to CRITICAL-2) — Design говорит нет; план добавляет `🟢/🔴`. Требует явного решения до того, как реализация разойдётся со spec.

### Summary table

| Category | Count |
|---|---|
| Critical | 2 |
| Concerns | 4 |
| Suggestions | 3 |
| Questions | 1 |

Все findings заявлены reviewer'ом как NEW против iter-1 resolved list.
