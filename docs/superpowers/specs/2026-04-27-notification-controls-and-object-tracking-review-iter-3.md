# Review Iteration 3 — 2026-04-28

## Источник

- Design: `docs/superpowers/specs/2026-04-27-notification-controls-and-object-tracking-design.md`
- Plan: `docs/superpowers/plans/2026-04-27-notification-controls-and-object-tracking.md`
- Telegram outbox follow-up: `docs/telegram-outbox.md`
- Review agents: codex-executor (gpt-5.5, xhigh), gemini-executor (gemini-3.1-pro-preview), ccs-executor (albb-glm/glm-5), ccs-executor (albb-qwen/qwen3.6-plus), ccs-executor (albb-kimi/kimi-k2.5), ccs-executor (albb-minimax/MiniMax-M2.5)
- Failed agents (HTTP 429, no usable output): ccs-executor (glm-direct), ccs-executor (deepseek)
- Merged output: `docs/superpowers/specs/2026-04-27-notification-controls-and-object-tracking-review-merged-iter-3.md`

## Замечания

### C1: Tracker fail-open bypasses global OFF (AI-token leak)

> When `tracker.evaluate(...)` throws AND `globalEnabled=false`, the catch returns `NotificationDecision(true, TRACKER_ERROR)` unconditionally. Facade then invokes the AI description supplier (spending tokens) only for the Telegram path to drop the notification anyway. Global OFF must win over tracker error.

**Источник:** codex-executor (Critical 3), gemini-executor (Critical 1)
**Статус:** Автоисправлено
**Ответ:** Catch-блок возвращает `NotificationDecision(globalEnabled, TRACKER_ERROR)`. При `globalEnabled=false` подавление сохраняется, AI supplier не вызывается, fan-out не вызывается. Логирование `WARN` дополнено `globalEnabled=$g, shouldNotify=$g`.
**Действие:** Design Error Handling: pseudocode + явное правило "Global OFF wins over tracker error". Plan Task 10: catch-блок, log message, новый тест `tracker exception with global OFF leads to TRACKER_ERROR and shouldNotify false`.

---

### C2: Дублирующий `appSettings.getBoolean(...)` в `sendRecordingNotification` "глотает" AppSettings exceptions

> Task 13 добавлял defense-in-depth global read в `sendRecordingNotification`, но `RecordingProcessingFacade` ловит ошибки fan-out без rethrow. Это противоречит правилу "AppSettings failures propagate" — AppSettings exception в этом месте будет молча подавлена. Decision service уже гейтит recording-путь.

**Источник:** codex-executor (Critical 2)
**Статус:** Автоисправлено
**Ответ:** Удалён defense-in-depth global read из `sendRecordingNotification`. Decision service остаётся единственным источником истины для recording-flow global gate. Signal-loss/recovery сохраняют свой собственный `appSettings.getBoolean(NOTIFICATIONS_SIGNAL_GLOBAL_ENABLED, true)` — это **единственный** gate для signal-flow и AppSettings exception ожидаемо пропагирует наружу до signal-monitor task.
**Действие:** Design Integration sections перешиты. Plan Task 13 Step 2 убрал глобальный read; Task 13 Step 5 добавил регрессионный тест `recording flow does not read global flag (decision service owns the gate)` который падает при возврате read'а.

---

### C3: AppSettings retry-boundary в фасаде недостоверна

> Task 19 размещает `decisionService.evaluate(...)` ПОСЛЕ `saveProcessingResult(...)`, который маркирует `process_timestamp != null`. Соответственно если decision throws AppSettings exception, recording уже считается обработанным; pipeline не сделает retry. Дизайн утверждал обратное.

**Источник:** codex-executor (Critical 1)
**Статус:** Обсуждено (auto-decided in Auto mode)
**Ответ:** Принято как documented limitation, аналогичная telegram-outbox accepted gap. Перемещение `evaluate(...)` до `save` — потенциально побочные эффекты (tracker трогает `last_recording_id`); шире, чем текущая итерация. Дизайн и Task 19 теперь явно говорят, что AppSettings failure для конкретного recording приводит к потере уведомления и оставляет это как known follow-up (вместе с telegram-outbox).
**Действие:** Design Error Handling: новый абзац "Recording retry boundary (accepted limitation)". Plan Task 19: блок-цитата с описанием ограничения.

---

### C4: Service-модуль не имеет необходимых зависимостей в build.gradle.kts

> Task 7/8/9 используют `jakarta.validation`, `kotlinx-coroutines-core`, `kotlinx-coroutines-test`, MockK; `modules/service/build.gradle.kts` может не содержать всех. Также тест мокает `TransactionalOperator` без stub'а для `executeAndAwait`.

**Источник:** codex-executor (Critical 4)
**Статус:** Автоисправлено
**Ответ:** Добавлен Step 0 в Task 7 с верификацией `build.gradle.kts` deps (validation, coroutines, MockK, coroutines-test) и инструкцией по добавлению только тех, что отсутствуют.
**Действие:** Plan Task 7 Step 0. Тест `empty detections...` ужесточён до `coVerify(exactly = 0) { transactionalOperator.executeAndAwait<Any>(any(), any()) }`, что также служит регрессионным тестом для early-return до mutex/транзакции (см. C7).

---

### C5: Corrupt boolean settings молча падают на default

> `getBoolean` делает `toBooleanStrictOrNull() ?: default` без логирования. Для kill-switch'ей это может неожиданно включить уведомления.

**Источник:** codex-executor (Critical 5)
**Статус:** Автоисправлено (WARN + default)
**Ответ:** При невалидном значении логируем `WARN { "AppSettings: invalid stored value for '$key'='$raw'; falling back to default=$default" }` и возвращаем default. Не fatal — корректно отдифференцирует data corruption от read-failure (последняя остаётся propagating).
**Действие:** Design Error Handling новый bullet про invalid stored values. Plan Task 9 implementation (`getBoolean`) использует `toBooleanStrictOrNull() ?: run { logger.warn(...); default }`. Тест переименован в `unparseable value logs WARN and falls back to default (recoverable corruption)` и проверяет fallback на оба default-значения.

---

### C6: `try { ... } catch (e: Exception) {}` в Task 18 поглощает `CancellationException` + missing `KotlinLogging` import в Task 17

> Существующие callback handlers пробрасывают `CancellationException`; Task 18 этого не делал. Task 17 объявлял `KotlinLogging.logger {}` без import — compile blocker.

**Источник:** codex-executor, gemini-executor (Critical 2), albb-qwen (C8), albb-glm (C5)
**Статус:** Автоисправлено
**Ответ:** В Task 18 добавлен `catch (e: CancellationException) { throw e }` ПЕРЕД общим `catch (e: Exception)` во всех trycatch блоках handler'а; добавлен `import kotlinx.coroutines.CancellationException` и весь набор импортов для extension-функций бота. В Task 17 добавлен `import io.github.oshai.kotlinlogging.KotlinLogging`.
**Действие:** Plan Task 17 Step 5 (импорт), Task 18 Step 2 (CancellationException + полный набор импортов).

---

### C7: `isEmpty()` проверка после mutex.withLock и executeAndAwait

> `evaluateLocked` проверяет `if (detections.isEmpty()) return ...` уже ВНУТРИ mutex и транзакции, бесполезно расходуя R2DBC connection и процесс-локальный lock на пустые входы.

**Источник:** albb-kimi (Critical 1)
**Статус:** Автоисправлено
**Ответ:** Early-return перенесён в `evaluate()` ДО `mutex.withLock` и ДО `transactionalOperator.executeAndAwait`. В `evaluateLocked` сохранён defensive guard на случай прямого вызова из тестов. Тест `empty detections...` усилен: `coVerify(exactly = 0)` для `executeAndAwait` и `findActive`.
**Действие:** Plan Task 8 implementation + тест.

---

### C8: AppSettings cache write до commit транзакции (race)

> `setBoolean` помечен `@Transactional`, но запись в кеш делается ДО фактического commit — при rollback кеш остаётся с новым значением, БД со старым.

**Источник:** gemini-executor (Critical 3), albb-kimi (M3), albb-qwen (C7)
**Статус:** Автоисправлено
**Ответ:** Удалён `@Transactional` с `setBoolean`. `repository.upsert(...)` — single atomic SQL, дополнительная транзакция была избыточна и расширяла окно гонки. Кеш обновляется только после успешного await — отражает закоммиченное состояние БД.
**Действие:** Plan Task 9 implementation: убран `@Transactional` + import; добавлен поясняющий комментарий "No @Transactional here: ...".

---

### C9: Liquibase 1.0.4 без `<rollback>` секций

> Чейнджсеты `1.0.4.xml` используют `<sql>` без `<rollback>` — Liquibase не сгенерирует автоматический rollback для custom SQL.

**Источник:** gemini-executor (Critical 4), albb-kimi (M6)
**Статус:** Автоисправлено
**Ответ:** Каждый changeset получил `<rollback>` с соответствующим `DROP INDEX` / `DROP TABLE` / `ALTER TABLE DROP COLUMN`. Дополнительно changeset для `telegram_users` получил `<preConditions onFail="HALT"><tableExists tableName="telegram_users"/></preConditions>` (закрывает заодно albb-glm S2).
**Действие:** Plan Task 1 Step 1 переработан, добавлено замечание про checksum reset (не требуется для нового файла).

---

### C10: `RepresentativeBbox` comment "normalized [0..1]" — регрессия iter-2 C10

> Комментарий в Task 4 утверждает "Coordinates are normalized [0..1] from YOLO output" — прямое противоречие iter-2 решению о pixel coordinates.

**Источник:** codex-executor (Concerns), gemini-executor (нет специфически), albb-glm (C3), albb-qwen (C1), albb-minimax (C1), albb-kimi (M4)
**Статус:** Автоисправлено
**Ответ:** Комментарий заменён на "Coordinates are pixel coordinates in the same coordinate space as DetectionEntity.x1..y2 (Float)." Это была чистая регрессия — design был корректен, plan rolled back.
**Действие:** Plan Task 4 Step 1 (комментарий KDoc).

---

### C11: callback wiring через `callback.user.id.chatId.long` некорректен; нужен username lookup

> Existing handlers (`QuickExportHandler`, `CancelExportHandler`) аутентифицируются по `callback.user.username`. Plan Task 18 пытался использовать `callback.user.id.chatId.long` + `findByUserIdAsDto` — repository метод не существует, типы ktgbotapi не гарантируют `.long`.

**Источник:** albb-glm (C1, C2), albb-qwen (C2), albb-kimi (C5), albb-minimax (нет)
**Статус:** Автоисправлено
**Ответ:** Task 17 Step 2 теперь добавляет `findByUsernameAsDto(username: String): TelegramUserDto?` (если ещё нет) вместо `findByUserIdAsDto`. Task 18 Step 2 авторизует по `callback.user.username ?: return@onDataCallbackQuery` и вызывает `userService.findByUsernameAsDto(...)`.
**Действие:** Plan Task 17 Step 2, Task 18 Step 2 (полный rewrite handler-блока с username pattern).

---

### C12: Constructor change в `TelegramUserServiceImpl` ломает существующие тесты, не отражено в плане

> Task 16 Step 2 добавляет `TelegramProperties` в primary constructor `TelegramUserServiceImpl`; план не показывал constructor modification и не перечислял тесты, требующие обновления.

**Источник:** albb-glm (C7), albb-qwen (C3), albb-kimi (C5)
**Статус:** Автоисправлено
**Ответ:** Task 16 Step 2 теперь явно показывает изменённый конструктор + grep команду для поиска всех существующих instantiations + список ожидаемых файлов тестов с шаблоном `mockk<TelegramProperties>().also { every { it.owner } returns "owner_username" }`.
**Действие:** Plan Task 16 Step 2 переработан.

---

### C13: `isOwner` не защищён от null/blank `username` или `telegramProperties.owner`

> `username.equals(...)` без проверки nullable / blank даёт ложные срабатывания если `application.telegram.owner` пустой/неконфигурированный или у пользователя нет username.

**Источник:** gemini-executor (Suggestion 1), albb-glm (C4)
**Статус:** Автоисправлено
**Ответ:** Сигнатура `isOwner(username: String?): Boolean` (nullable). Реализация: `if (username.isNullOrBlank() || configured.isNullOrBlank()) return false; return username.equals(configured, ignoreCase = true)`. Тест `isOwner` расширен (null username, blank username, null/blank configured owner, exact match, case-insensitive match).
**Действие:** Plan Task 16 Step 2.

---

### C14: `cleanupIntervalMs` default дублируется в трёх местах

> `3_600_000` повторяется в `ObjectTrackerProperties` (Task 7), `@Scheduled(fixedDelayString = "...:3600000")` (Task 11), `application.yaml` (Task 20). При изменении data-class default'а реальный `@Scheduled` использует value из YAML/placeholder — расхождение без compile-time проверки.

**Источник:** albb-qwen (C4)
**Статус:** Автоисправлено (документирование, без архитектурной перестройки)
**Ответ:** Spring placeholders в `@Scheduled` не позволяют ссылаться на data-class константы. Добавлен явный комментарий в Task 7 "If you change the default, update all three" с обозначением `application.yaml` как runtime source of truth, а data-class default и `@Scheduled` placeholder default — как fallback в test/no-env.
**Действие:** Plan Task 7 Step 1 (note блок).

---

### C15: Нет валидации `cleanupRetention >= ttl` в `ObjectTrackerProperties`

> Документация говорит "cleanup retention is intentionally larger than TTL", но в коде нет ни `require`, ни `@Validated`-constraint'а. Bad env может убить активные треки.

**Источник:** codex-executor (Concerns), albb-minimax (K6)
**Статус:** Автоисправлено
**Ответ:** В `init`-блоке `ObjectTrackerProperties` добавлены: `require(!ttl.isZero && !ttl.isNegative)`, `require(!cleanupRetention.isZero && !cleanupRetention.isNegative)`, `require(cleanupRetention >= ttl)`, `require(cleanupIntervalMs > 0)`. Валидируется при поднятии Spring контекста — fail-fast.
**Действие:** Plan Task 7 Step 1 + design env vars table описание про invariants.

---

### C16: Конфиг docs рассогласованы между design и plan

> Design env vars table содержала `NOTIFICATIONS_TRACK_CLEANUP_INTERVAL` без `_MS` и не упоминала `NOTIFICATIONS_TRACK_CONFIDENCE_FLOOR`; plan уже использовал `_MS` и confidence floor. Также Task 20 указывал `.env.example` в корне, а в репозитории файл лежит в `docker/deploy/.env.example`.

**Источник:** codex-executor (Concerns)
**Статус:** Автоисправлено
**Ответ:** Design table обновлён: имена с `_MS`, добавлен `CONFIDENCE_FLOOR`, defaults в ISO-8601 для Duration. Task 20 Step 2 указывает `docker/deploy/.env.example` (с инструкцией `ls -la` для верификации). File map в плане также скорректирован.
**Действие:** Design "Configurable parameters" + Plan File map + Task 20.

---

### C17: NO_VALID_DETECTIONS reason для confidence-filtered детекций

> Когда все детекции отфильтрованы по `confidenceFloor`, tracker возвращает `DetectionDelta(0,0,0,empty)`. Decision service маркировал это как `ALL_REPEATED`. В debug-логах "all_repeated" вводит в заблуждение.

**Источник:** gemini-executor (Concern 1)
**Статус:** Автоисправлено
**Ответ:** Добавлен новый reason `NotificationDecisionReason.NO_VALID_DETECTIONS`. В `NotificationDecisionServiceImpl.evaluate()` после tracker check: `if (delta.newTracksCount == 0 && delta.matchedTracksCount == 0 && delta.staleTracksCount == 0)` → возвращаем `NO_VALID_DETECTIONS`. Тест `tracker returns empty delta for confidence-filtered detections leads to NO_VALID_DETECTIONS` добавлен.
**Действие:** Plan Task 4 (enum), Task 10 implementation + тест. Design pseudocode + reason list + Logging table.

---

### C18: INFO log spam для каждого нового трека

> `ObjectTrackerServiceImpl` логирует `logger.info { "ObjectTracker: cam=... new=..." }` при каждом новом треке. Уличная камера = десятки тысяч записей в сутки.

**Источник:** gemini-executor (Concern 2)
**Статус:** Автоисправлено
**Ответ:** В design Logging table уровень понижен с INFO до DEBUG для `ObjectTracker: cam=... new=N` события. Сообщение остаётся условно (только при `new > 0`) для отладочной полезности.
**Действие:** Design Logging table.

---

### C19: "Bad Request: message is not modified" при двойных кликах

> Если пользователь кликает кнопку дважды быстро, второй `editMessageText` с identical content приведёт к ошибке Telegram API — попадает в общий WARN-лог.

**Источник:** gemini-executor (Concern 3)
**Статус:** Автоисправлено
**Ответ:** В Task 18 callback handler ловит exception вокруг `editMessageText`, проверяет `e.message?.contains("message is not modified", ignoreCase = true)` — если да, downgrade до DEBUG. Остальные ошибки остаются WARN. Перед общим catch добавлен `catch (e: CancellationException) { throw e }`.
**Действие:** Plan Task 18 Step 2 (handler-блок).

---

### C20: `recording.recordTimestamp` потенциально nullable → NPE

> В `evaluateLocked` использовался `recording.recordTimestamp` напрямую без null-check.

**Источник:** albb-kimi (M1)
**Статус:** Автоисправлено
**Ответ:** Используется `requireNotNull(recording.recordTimestamp) { "RecordingDto.recordTimestamp is null for recording=${recording.id}" }`. В текущей схеме recording всегда имеет timestamp; явный require ловит programming error и даёт чёткое сообщение.
**Действие:** Plan Task 8 implementation.

---

### C21: Misleading variable name `enabled` (фактически — target state)

> В `NotificationsSettingsCallbackHandler.dispatch` переменная `val enabled = when (parts[3]) { "1" -> true; "0" -> false; ... }` фактически содержит **target state** post-click, не current state. Имя вводит в заблуждение.

**Источник:** albb-kimi (C3)
**Статус:** Автоисправлено
**Ответ:** Переименовано в `targetEnabled` с поясняющим комментарием "the desired post-click state, not the current DB state". Логика без изменений.
**Действие:** Plan Task 17 Step 5.

---

### C22: `currentUser.username` может быть null для `setBoolean.updatedBy`

> `appSettings.setBoolean(..., updatedBy = currentUser.username)` — Telegram users могут не иметь username; в БД попадёт null или TypeError.

**Источник:** albb-glm (C4)
**Статус:** Автоисправлено
**Ответ:** Используется `currentUser.username ?: "<unknown>"` в обоих global-callback'ах (`g:rec`, `g:sig`). audit trail в `app_settings.updated_by` сохраняется (`<unknown>` отличает запись бота от migration-seeded NULL).
**Действие:** Plan Task 17 Step 5 (handler implementation + note).

---

### C23: NotificationsCommandHandler читает globals для USER без пользы + не логирует показ диалога

> Task 16 handler читал оба `appSettings.getBoolean(...)` для **каждого** пользователя; non-OWNER UI не показывает global state. Также не было INFO/DEBUG log при показе `/notifications`.

**Источник:** codex-executor (Suggestion), albb-qwen (C9)
**Статус:** Автоисправлено
**Ответ:** Globals читаются только при `isOwner == true` (`if (isOwner) appSettings.getBoolean(...) else null`). Снижает нагрузку на cache и failure surface. Также добавлен `logger.debug { "/notifications opened by chatId=$chatId username=${user.username} isOwner=$isOwner" }`.
**Действие:** Plan Task 16 Step 3 implementation. `NotificationsViewState.recordingGlobalEnabled`/`signalGlobalEnabled` стали nullable; renderer `requireNotNull(...)` для OWNER ветки.

---

### C24: `NotificationsViewState` лежит в файле renderer, нужен общий DTO

> При импорте `NotificationsViewState` в command handler (`/handler/notifications/`) и bot-wiring (`/bot/`) возникает зависимость на renderer файл.

**Источник:** albb-qwen (S1)
**Статус:** Автоисправлено
**Ответ:** `NotificationsViewState` вынесен в `modules/telegram/src/main/kotlin/.../telegram/dto/NotificationsViewState.kt`. Глобальные флаги стали nullable. `RenderedNotifications` остался внутри renderer (нет внешних потребителей).
**Действие:** Plan File map + Task 15 Step 1 + Task 16/18 импорты.

---

### C25: Order для `/notifications` команды должен быть 7, не 6

> Plan указывал hardcoded `order = 6`, но `/language` уже занимает 6, OWNER-команды стартуют с 12. Дизайн говорил "after /language", значит 7.

**Источник:** codex-executor (Suggestion)
**Статус:** Автоисправлено
**Ответ:** `override val order: Int = 7`. Task 16 Step 1 теперь явно ссылается на текущее состояние `/language = 6` и owner = 12.
**Действие:** Plan Task 16 Step 1 + handler snippet.

---

### C26: Test plan не покрывает design promises

> Design обещал `NotificationsCommandHandlerTest`, repository ITs (`ObjectTrackRepositoryIT`, `AppSettingsRepositoryIT`), facade integration coverage. Plan task list пропускал command handler test и не имел репозиторных IT-задач. Task 13 покрывал только signal-loss, не signal-recovery + global OFF.

**Источник:** codex-executor (Concerns), albb-qwen (C5)
**Статус:** Автоисправлено
**Ответ:** Task 16 получил Step 4 — `NotificationsCommandHandlerTest` со списком тестов (USER vs OWNER рендеринг, отсутствие чтения `appSettings` для USER, propagation для OWNER при ошибке settings). Task 13 Step 6 расширен до 5 тестов (signal-loss + signal-recovery × global OFF + per-user filter; AppSettings failure propagates). Task 11 получил `ObjectTracksCleanupTaskTest` (success + exception swallowed). File map обновлён.
**Действие:** Plan File map, Task 11, Task 13, Task 16.

---

### C27: ktgbotapi typing для `UserId.chatId.long`

> Без проверки исходников ktgbotapi v31.x не гарантировано, что `UserId.chatId` возвращает `ChatId` (с `.long`) или `Long` (без `.long`). Вторая ветка — compile error.

**Источник:** albb-qwen (Q1, C2)
**Статус:** Автоисправлено (через C11 — переход на username lookup)
**Ответ:** Снято полностью переходом на `callback.user.username` lookup, который имеет стабильный тип `String?` через все версии ktgbotapi. `findByUserId` не добавляется.
**Действие:** Покрыто C11.

---

### C28: Task 18 не вызывает `bot.answer(callback)` сразу

> Telegram callback timeout ~30 сек; если handler делает медленную DB-операцию до `answer`, спинер на кнопке зависает у пользователя.

**Источник:** albb-minimax (K3)
**Статус:** Автоисправлено
**Ответ:** В Task 18 Step 2 `bot.answer(callback)` вызывается **первой** операцией (внутри собственного try/catch с `CancellationException`-rethrow), до любого DB lookup или dispatch. Отдельный try/catch не падает основной flow.
**Действие:** Plan Task 18 Step 2.

---

### C29: Audit `nfs:*` callback strings против design

> Дизайн зафиксировал `nfs:u:rec:1/0`, `nfs:u:sig:1/0`, `nfs:g:rec:1/0`, `nfs:g:sig:1/0`, `nfs:close`. Task 16/17 нужно проверить, что используется ровно эти строки.

**Источник:** albb-minimax (C4)
**Статус:** Автоисправлено
**Ответ:** Добавлен Step 3 в Task 18 — финальный audit `grep -rn "\"nfs:" modules/telegram/src/main/kotlin/` и список ожидаемых совпадений.
**Действие:** Plan Task 18 Step 3.

---

### C30: Task 18 не документирует зависимости на Task 9 / 16 / 17

> Если параллельные агенты (subagent-driven-development) попытаются выполнить Task 18 раньше Task 9 (`AppSettingsService`) — будет compile error.

**Источник:** albb-minimax (C3)
**Статус:** Автоисправлено
**Ответ:** В Task 18 добавлена секция "**Depends on:** Task 9, Task 16, Task 17. Task 18 must run after these." Sequential plan делает это implicit, но для парапаллельного выполнения теперь explicit.
**Действие:** Plan Task 18 (секция Depends on).

---

### C31: Out-of-order recording test missing

> Iter-2 добавил guard `CASE WHEN :T >= last_seen_at THEN ... ELSE existing END` для bbox/last_recording_id, но в Task 8 не было unit-теста проверяющего, что `updateOnMatch` вызывается с older `:lastSeenAt` корректно.

**Источник:** albb-minimax (K1)
**Статус:** Автоисправлено
**Ответ:** Добавлен тест `out-of-order older recording matches existing track via updateOnMatch with original lastSeenAt` — moqk'ает existing track с newer `last_seen_at`, evaluate с older recording timestamp, verify что `updateOnMatch` вызвано с правильным `:lastSeenAt = recording.recordTimestamp`. SQL CASE WHEN покрывается интеграционным тестом `ObjectTrackRepositoryIT`.
**Действие:** Plan Task 8 test step.

---

### C32: Liquibase checksum reset clarification

> Albb-minimax спросил, требуется ли reset checksums после добавления 1.0.4. Это не нужно для нового файла (reset нужен только для модификации существующих changesets).

**Источник:** albb-minimax (Q1)
**Статус:** Автоисправлено (документирование)
**Ответ:** В Task 1 добавлено замечание: "Adding new changesets to `1.0.4.xml` does **not** require a Liquibase checksum reset."
**Действие:** Plan Task 1 (note блок).

---

### C33: `/notifications` dialog timeout

> `LanguageCommandHandler` имеет `LANGUAGE_DIALOG_TIMEOUT_MS = 60_000L`; `/notifications` явного таймаута не имел. Задан вопрос: нужен?

**Источник:** albb-minimax (Q2)
**Статус:** Отклонено (auto-decided)
**Ответ:** `/notifications` — toggle-style команда, не conversation. Callback всегда осмысленный, можно нажимать в любой момент. Stale messages защищены через explicit target state в callback data (iter-2 C5). Таймаут не нужен.
**Действие:** Без изменений.

---

### Q1: Cache invalidation на старте приложения

**Источник:** albb-qwen (Q3)
**Статус:** Отклонено (auto-decided)
**Ответ:** Кеш создаётся пустым в `init`; lazy populate на первом read. Invalidate-on-start = no-op.
**Действие:** Без изменений.

---

### Repeat: `findByRecordingId` в DetectionEntityService

**Источник:** albb-kimi (Critical 2)
**Статус:** Повтор (iter-2 C12)
**Ответ:** Уже применено в iter-2 — Task 19 Step 3 содержит explicit `findByRecordingId` interface/impl/repository steps.
**Действие:** Без изменений (verified via `grep -n findByRecordingId` в плане).

---

### Repeat: `@EnableConfigurationProperties(ObjectTrackerProperties::class)`

**Источник:** albb-minimax (C2)
**Статус:** Повтор (iter-2 C11)
**Ответ:** Уже применено в iter-2 — Task 20 Step 3 содержит explicit instructions для core configuration class.
**Действие:** Без изменений (verified).

---

### Repeat: `runBlocking` в cleanup task — accepted tech debt

**Источник:** albb-kimi (M5)
**Статус:** Повтор (iter-1 C11, iter-2 C17)
**Ответ:** Решение accepted, теперь явно задокументировано в KDoc для `cleanup()` метода: "single suspending DELETE wrapped in runBlocking ... acceptable for short hourly DELETE; if cleanup grows, schedule on coroutine scope and consume SmartLifecycle for graceful shutdown."
**Действие:** Plan Task 11 Step 1 (KDoc на `cleanup()`).

---

### Repeat: ConcurrentHashMap leak / Sliding TTL

**Источник:** albb-minimax (K4, K5)
**Статус:** Повтор (iter-1 C10, iter-2 C15)
**Ответ:** Принято и задокументировано ранее. Без изменений.
**Действие:** Без изменений.

---

### Repeat: Batch insert/update

**Источник:** albb-qwen (S2)
**Статус:** Повтор (iter-2 C14)
**Ответ:** Per-row `save()` accepted на текущей итерации; batch — будущая оптимизация.
**Действие:** Без изменений.

---

### Repeat: Static `BboxClusteringHelper`

**Источник:** albb-kimi (S3, S5)
**Статус:** Повтор (iter-2 C18)
**Ответ:** Pure deterministic logic с прямыми unit-тестами; DI бы добавил шум без пользы.
**Действие:** Без изменений.

---

### Repeat: Signal-loss path не проходит через NotificationDecisionService

**Источник:** albb-minimax (K2)
**Статус:** Повтор (informational)
**Ответ:** Документировано в design Integration section как намеренное решение.
**Действие:** Без изменений.

---

### Codex Question 2: signal-loss + AppSettings failure

> Should AppSettings read failure consume signal state transition or preserve it?

**Источник:** codex-executor (Question)
**Статус:** Обсуждено (auto-decided)
**Ответ:** AppSettings exception пропагирует наружу из `sendCameraSignalLost/Recovered`. Signal-monitor task ловит/логирует notification failures и попробует transition снова на следующем тике (sigloss state machine идемпотентна). Не loose — событие восстановится при следующем мониторинге. Документировано в design Error Handling.
**Действие:** Design Error Handling + Plan Task 13 (note + новый тест `signal-loss propagates AppSettings read failure (no silent fallback)`).

---

## Изменения в документах

| Файл | Изменение |
|------|-----------|
| `docs/superpowers/specs/2026-04-27-notification-controls-and-object-tracking-design.md` | Env vars table + invariants; pseudocode для decision (NO_VALID_DETECTIONS, globalEnabled в catch); Error Handling переписан (corrupt boolean, retry-boundary, signal-loss propagate); Integration section переписана (recording — нет defense-in-depth read; signal — единственный gate); Logging table (INFO→DEBUG для tracker, fail-open WARN с globalEnabled, corrupt setting WARN); Tests table расширена |
| `docs/superpowers/plans/2026-04-27-notification-controls-and-object-tracking.md` File map | Добавлены NotificationsViewState DTO, NotificationsCommandHandlerTest, ObjectTracksCleanupTaskTest; путь `.env.example` исправлен на `docker/deploy/.env.example` |
| Task 1 | Liquibase rollback + preCondition + checksum-reset note |
| Task 4 | RepresentativeBbox комментарий: pixel coordinates (regression iter-2 C10) |
| Task 4 | NotificationDecisionReason: новое значение `NO_VALID_DETECTIONS` |
| Task 7 | Step 0 (build.gradle.kts deps); init-block с require-валидациями (`ttl > 0`, `cleanupRetention >= ttl`, `cleanupIntervalMs > 0`); комментарий о triple-default |
| Task 8 | Early-return перенесён до `withLock` и `executeAndAwait`; defensive guard в `evaluateLocked`; `requireNotNull(recordTimestamp)`; новый тест out-of-order; усиленный empty-detections тест |
| Task 9 | `@Transactional` снят с `setBoolean`; `import org.springframework.transaction.annotation.Transactional` удалён; corrupt-value WARN log; тест `unparseable value` усилен |
| Task 10 | KDoc обновлён; `globalEnabled` в catch; новый бранч `NO_VALID_DETECTIONS`; новые тесты (global OFF + tracker error; NO_VALID_DETECTIONS) |
| Task 11 | Описание `cleanup()` метода с tech-debt note; новый `ObjectTracksCleanupTaskTest` (success + exception swallowed) |
| Task 13 | Recording-flow read удалён; signal-flow gate — единственный gate; полные тесты для signal-recovery + global OFF + per-user filter + AppSettings failure |
| Task 15 | NotificationsViewState вынесен в dto/; nullable global flags; `requireNotNull(...)` в renderer; новый тест `owner variant requires non-null global flags`; обновлённые тестовые данные (null для USER) |
| Task 16 | order = 7; `isOwner(username: String?)` defensive; constructor change в `TelegramUserServiceImpl` явный; список существующих тестов для обновления; handler читает globals только для OWNER; debug log; новый Step `NotificationsCommandHandlerTest` |
| Task 17 | username lookup pattern (`findByUsernameAsDto`); `KotlinLogging` import; `targetEnabled` rename; debug log на unknown/malformed; `username ?: "<unknown>"` fallback |
| Task 18 | Depends-on note; полный rewrite handler-блока: answer-first, username auth, CancellationException-rethrow, "message is not modified" handling, OWNER-only globals, audit step |
| Task 19 | Block-quote с accepted retry-boundary limitation; ссылка на telegram-outbox |
| Task 20 | `.env.example` → `docker/deploy/.env.example`; init-block fail-fast note |

## Статистика

- Всего замечаний: 33 (C1–C33) + 7 повторов + 2 questions
- Автоисправлено: 28
- Обсуждено (auto-decided per Auto mode): 3 (C3, signal-loss propagate, /notifications timeout)
- Отклонено: 2 (Q1 cache invalidate-on-start, C33 dialog timeout)
- Повторов (автоответ из iter-1/iter-2): 7
- Пользователь сказал "стоп": Нет
- Агенты: codex-executor, gemini-executor, ccs-executor (4 успешных профиля: albb-glm, albb-qwen, albb-kimi, albb-minimax)
- Failed agents: ccs-executor (glm-direct), ccs-executor (deepseek) — оба HTTP 429
