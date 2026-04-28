# Merged Design Review — Iteration 3

**Date:** 2026-04-28
**Documents reviewed:**
- Design: `docs/superpowers/specs/2026-04-27-notification-controls-and-object-tracking-design.md`
- Plan: `docs/superpowers/plans/2026-04-27-notification-controls-and-object-tracking.md`
- Telegram outbox follow-up: `docs/telegram-outbox.md`

**Agents executed:** 8
**Agents that produced output:** 6 (codex-executor, gemini-executor, ccs-executor profiles albb-glm, albb-qwen, albb-kimi, albb-minimax)
**Agents that failed (HTTP 429 quota):** 2 (ccs-executor profiles glm-direct, deepseek)

---

## codex-executor (gpt-5.5, xhigh)

> NOTE: Codex responded in English despite the Russian-only instruction in the prompt. Content is included verbatim.

### Critical Issues

1. `AppSettingsService` failures will not actually cause a recording retry. Task 19 evaluates settings after `saveProcessingResult` (`plan:3098-3103`), while current save marks `process_timestamp` (`RecordingEntityRepository.kt:56-63`), and the consumer only logs finalize failures (`FrameAnalyzerConsumer.kt:117-119`). So the plan's claim that settings exceptions "propagate so the pipeline can retry" (`plan:3143`, design `418`) is false. Move the settings-read preflight before marking the recording processed, or make save + decision part of one retry-safe boundary.

2. The second global check in `TelegramNotificationServiceImpl` can swallow settings failures. Task 13 adds `appSettings.getBoolean(...)` inside `sendRecordingNotification` (`plan:2055`), but current facade catches send failures and does not rethrow (`RecordingProcessingFacade.kt:59-69`). That contradicts the "settings read failures propagate" rule. Either remove the recording-path defense-in-depth read, pass the already-read decision into fan-out, or distinguish `AppSettings` failures from Telegram delivery failures.

3. Global OFF is not absolute when tracker fails. Task 10 implementation reads `globalEnabled`, then catches tracker errors and returns `NotificationDecision(true, TRACKER_ERROR)` unconditionally (`plan:1699-1732`). This violates the effective rule `global && user` in the design (`design:327`). Add a test for `global=false + tracker throws`; expected result should remain suppressed.

4. The service module build is missing dependencies required by the plan. Task 7/8/9 use `jakarta.validation`, `kotlinx.coroutines.sync.Mutex`, `kotlinx.coroutines.test`, and MockK (`plan:866-881`, `947-951`, `1157-1158`, `1424-1425`), but `modules/service/build.gradle.kts` currently lacks validation starter, coroutine bundle, MockK, and coroutine-test (`build.gradle.kts:14-24`). Also `ObjectTrackerServiceImplTest` mocks `TransactionalOperator` without stubbing `executeAndAwait` (`plan:976`, implementation uses it at `1200`), so the unit tests are likely to fail at runtime.

5. Corrupt boolean settings silently fall back to defaults. Task 9 explicitly tests `"weird" -> default` (`plan:1403-1407`) and implementation does `toBooleanStrictOrNull() ?: default` (`plan:1448-1449`). For global kill switches this can unexpectedly enable notifications. Invalid stored values should be logged and treated as configuration/data corruption, not defaulted.

### Concerns

- Callback wiring swallows `CancellationException` in Task 18 (`plan:3022-3053`), unlike existing bot routes that rethrow cancellation. Task 17 also declares `KotlinLogging.logger {}` without importing `KotlinLogging` (`plan:2915`), a direct compile blocker if copied.

- Test plan is incomplete versus the design. Design promises `NotificationsCommandHandlerTest`, repository integration tests, and facade integration coverage (`design:462-467`), but the task list omits command-handler tests and repository IT tasks. Task 13 tests signal-loss only, not signal-recovery filtering/global OFF (`plan:2155-2185`).

- Config docs are inconsistent: design still lists `NOTIFICATIONS_TRACK_CLEANUP_INTERVAL` and omits `NOTIFICATIONS_TRACK_CONFIDENCE_FLOOR` (`design:275-279`), while the plan uses `NOTIFICATIONS_TRACK_CLEANUP_INTERVAL_MS` and includes confidence floor (`plan:3177-3197`). Task 20 also targets root `.env.example` (`plan:3166-3168`), but this repo has `docker/deploy/.env.example`.

- Task 4 reintroduces the wrong coordinate contract: `RepresentativeBbox` says normalized `[0..1]` (`plan:429`), while design says current coordinates are pixels and not normalized (`design:102`).

- `cleanupRetention` is documented as intentionally larger than TTL (`design:281`), but `ObjectTrackerProperties` has no validation tying `cleanupRetention >= ttl` (`plan:875-883`). A bad env setting can make active tracks disappear and re-enable notification floods.

### Suggestions

- Check global toggles before `getAuthorizedUsersWithZones()` in Telegram fan-out. It avoids loading users when the stream is globally OFF and reduces failure surface.

- In `/notifications`, non-OWNER rendering does not use global state (`plan:2500-2530`), but Task 16 reads both global settings for every user (`plan:2690-2699`). Avoid those reads for non-owners.

- Pick an explicit command order. Task 16 says "largest existing order + 1" but hardcodes `6` (`plan:2613-2680`); current `language` is already order 6 and owner commands go to 12. Use `7` if the intended position is after `/language`.

### Questions

- Should tracker failure while global recording notifications are OFF notify anyway, or should global OFF always win? I strongly recommend global OFF wins.

- For signal-loss/recovery, should `AppSettingsService` read failure consume the state transition, or should it preserve/retry the transition? Current monitor catches notification exceptions, so a settings DB failure can lose the alert.

- Should invalid `app_settings.setting_value` be considered fatal for known boolean keys? Current plan says no; operationally that looks unsafe.

---

## gemini-executor (gemini-3.1-pro-preview)

### Critical Issues

1. **Утечка AI-токенов при сбое БД и выключенном глобальном флаге (Task 10)**
   В `NotificationDecisionServiceImpl.kt` реализован механизм fail-open: если `tracker.evaluate` выбрасывает исключение, перехватчик `catch (e: Exception)` возвращает `NotificationDecision(true, TRACKER_ERROR)`. Однако, если глобальные уведомления отключены (`globalEnabled = false`), этот возврат `true` обходит глобальную блокировку. Фасад (`RecordingProcessingFacade`) увидит `shouldNotify = true`, **запросит генерацию AI-описания (потратив токены/деньги)** и передаст управление в Telegram-сервис, который в итоге дропнет отправку.
   *Решение:* В блоке `catch` нужно учитывать глобальный флаг: `NotificationDecision(globalEnabled, NotificationDecisionReason.TRACKER_ERROR)`.

2. **Нарушение структурированной конкурентности (swallowed `CancellationException`) в Task 18**
   В `FrigateAnalyzerBot.kt` обработчик `onDataCallbackQuery` обёрнут в `try { ... } catch (e: Exception) { ... }`. Поглощение базового `Exception` в Kotlin Coroutines перехватывает и гасит `CancellationException`, что ломает механизмы отмены (cancellation) и может привести к утечкам корутин.
   *Решение:* Перед общим `catch` необходимо добавить `catch (e: CancellationException) { throw e }` (как это корректно сделано в Task 10).

3. **Состояние гонки (грязное чтение) в in-memory кеше `AppSettingsServiceImpl` (Task 9)**
   Метод `setBoolean` помечен аннотацией `@Transactional`. Запись в кеш (`cacheMutex.withLock { cache[key] = v }`) происходит *до коммита* транзакции базой данных. Если транзакция откатится на этапе коммита (например, сетевая ошибка R2DBC), кеш останется с новым значением, а база — со старым, что приведет к рассинхронизации.
   *Решение:* Так как `repository.upsert` — это единичный атомарный SQL-запрос, аннотация `@Transactional` на `setBoolean` абсолютно избыточна. Её удаление решает проблему: кеш будет обновлен только после реального завершения запроса.

4. **Отсутствие откатов (`<rollback>`) для DDL миграций в Liquibase (Task 1)**
   Чейнджсет `1.0.4.xml` использует чистые SQL-запросы (`<sql>`) для создания таблиц и колонок. Liquibase не умеет автоматически генерировать откаты для кастомного SQL.
   *Решение:* Добавьте соответствующие теги `<rollback>` с `DROP TABLE object_tracks`, `DROP TABLE app_settings` и `ALTER TABLE telegram_users DROP COLUMN ...` для каждого `<changeSet>`.

### Concerns

1. **Неточный `NotificationDecisionReason` при игнорировании всех объектов:**
   Если все найденные объекты (detections) отфильтровываются по `confidenceFloor` в `BboxClusteringHelper`, трекер возвращает `DetectionDelta(new=0, ...)`. В `NotificationDecisionService` это попадет в ветку `else` и получит статус `ALL_REPEATED`. Технически уведомление будет подавлено (что правильно), но в дебаг-логе будет написано "Decision: suppress (all_repeated)", хотя правильнее было бы "no valid detections". Это может сбить с толку при отладке.

2. **Лог-спам на уровне INFO (Task 8):**
   В `ObjectTrackerServiceImpl` появление новых треков логируется как `logger.info { "ObjectTracker: cam=... new=..." }`. Для уличной камеры каждое проехавшее авто или прошедший человек будет генерировать строку в лог, что может привести к десяткам тысяч записей в сутки.
   *Решение:* Понизить уровень до `logger.debug`.

3. **Лишние вызовы Telegram API при двойных кликах (Task 18):**
   Если пользователь быстро нажимает на одну и ту же кнопку, `dispatch` может вернуть `RERENDER`, но итоговый `RenderedNotifications.text` и `keyboard` будут полностью идентичны текущему сообщению. Вызов `bot.editMessageText` с неизмененным контентом приведет к исключению от Telegram API (`Message is not modified`), которое упадет в общий лог как `WARN`. Стоит обрабатывать/игнорировать конкретно эту ошибку API, чтобы не засорять логи.

### Suggestions

1. **Явный null-check в `isOwner` (Task 16):**
   Метод `isOwner(username: String)` сравнивает строки. В Telegram у пользователя может не быть юзернейма. Хотя текущий DTO `TelegramUserDto` описывает `username` как non-nullable `String` (вероятно, под капотом есть fallback на ID или first name), стоит убедиться, что логика `isOwner` не даст ложных срабатываний, если `telegramProperties.owner` случайно не настроен или пуст.

2. **Использование XML DSL для Liquibase (Task 1):**
   Вместо тегов `<sql>` лучше использовать встроенные теги Liquibase (`<createTable>`, `<addColumn>`). Это автоматически решит проблему с написанием роллбеков и сделает миграцию более "чистой" в рамках инструмента.

### Questions

Нет, план получился весьма исчерпывающим и очень детально покрывает как корнер-кейсы домена (например, сохранение координат, IoU, защита от ретроспективных апдейтов времени), так и интеграцию с ботом. Архитектурный выбор (mutex на уровне инстанса + Sliding TTL) отлично ложится в текущие ограничения проекта (single-instance, отсутствие outbox на данном этапе).

---

## ccs-executor (albb-glm / glm-5)

### Critical Issues

**C1: Plan Task 18 использует неправильный подход для user lookup (compile/runtime blocker)**

```kotlin
val senderUserId = callback.user.id.chatId.long
val current = userService.findByUserIdAsDto(senderUserId) ?: return@onDataCallbackQuery
```

- `callback.user.id` в ktgbotapi — это Telegram user ID (внутренний ID аккаунта), не chat ID.
- `TelegramUserRepository` НЕ имеет derived method `findByUserId(Long)`.
- Plan Task 17 Step 2 говорит реализовать `findByUserIdAsDto` через `repository.findByUserId(userId)`, но repository method отсутствует.
- Existing handlers (QuickExportHandler, CancelExportHandler) используют username для auth: `callback.user.username?.withoutAt`.

**Fix:** добавить `findByUserId(userId: Long): TelegramUserEntity?` или использовать username lookup.

**C2: Plan Task 17 Step 2 говорит `repository.findByUserId(userId)` но repository не имеет этого метода.**

`TelegramUserRepository.kt` line 53: имеет `findByChatId(chatId: Long)`, но НЕ `findByUserId(userId: Long)`.

**C3: Plan Task 4 RepresentativeBbox.kt comment stale (normalized → pixel)**

```kotlin
/**
 * Coordinates are normalized [0..1] from YOLO output, stored as Float to match DetectionEntity.
 */
```

Iter-2 C10 решила: pixel coordinates. Comment должен быть обновлён.

### Concerns

**C4: Plan Task 17 handler использует `currentUser.username` для setBoolean updatedBy — может быть null.** Telegram позволяет users без username; нужно `currentUser.username ?: "unknown"` или гарантировать, что OWNER всегда имеет username.

**C5: Plan Task 17 handler missing KotlinLogging import.**

**C6: Plan Task 18 Step 2 missing imports** (`NotificationsViewState`, `NotificationsSettingsCallbackHandler.DispatchOutcome`, `AppSettingKeys`, `NotificationsMessageRenderer`).

**C7: Plan Task 16 inject TelegramProperties не присутствует в current TelegramUserServiceImpl.**

Текущий конструктор не имеет `TelegramProperties`. Plan Task 16 Step 2 говорит inject `TelegramProperties` для `isOwner`, но не показывает constructor modification. Все существующие тесты, которые конструируют `TelegramUserServiceImpl`, сломаются.

### Suggestions

**S1: Plan Task 18 should follow existing callback auth pattern** (username lookup, как в `QuickExportHandler`/`CancelExportHandler`).

**S2: Add preconditions check в Liquibase migration** для `telegram_users` table.

### Questions

**Q1: Should `findByUserId` lookup be needed?** Existing handlers используют username. Зачем Plan Task 18 needs userId lookup?

---

## ccs-executor (albb-kimi / kimi-k2.5)

### Critical Issues

**C1: isEmpty() check после mutex.withLock и executeAndAwait в `ObjectTrackerServiceImpl.evaluateLocked()`**

При пустом списке детекций всё равно берётся mutex и открывается транзакция. Только потом проверяется `isEmpty()`. Проверка должна быть до `withLock` и до `executeAndAwait`.

**C2: Отсутствие `findByRecordingId` в `DetectionEntityService` — критический блокер для Task 19**

В Task 19, шаг 2, план предполагает `detectionEntityService.findByRecordingId(recordingId)`. Но в текущем коде `DetectionEntityService` имеет только `createDetection`. Необходимо добавить `findByRecordingId` в interface, реализацию и repository — это не отражено как explicit task.

**C3: Misleading variable naming `enabled` vs `targetEnabled` в `NotificationsSettingsCallbackHandler.dispatch`** (логика верна, но имя переменной вводит в заблуждение).

**C4: Проблема с Spring Data R2DBC и `@Transactional` на suspend-функции в `AppSettingsServiceImpl`** (Task 9, строка 1452). Несоответствие подходов: `ObjectTrackerServiceImpl` использует `TransactionalOperator`, `AppSettingsServiceImpl` — `@Transactional`.

**C5: Отсутствие метода `isOwner` в `TelegramUserService` (Task 16) + breaking constructor change.**

### Medium Issues

**M1: `recording.recordTimestamp` может быть nullable → NPE в `evaluateLocked`.** Нужна проверка или fallback на `creationTimestamp`.

**M2: `TransactionalOperator` не используется в текущем codebase — необходимо проверить bean availability и правильный импорт.**

**M3: `AppSettingsServiceImpl` — race condition в кеше / exception handling в `loadAndCache`.**

**M4: Комментарий `RepresentativeBbox` противоречит дизайну (normalized vs pixel).**

**M5: `ObjectTracksCleanupTask` использует `runBlocking` — accepted tech debt, но нужен код-комментарий.**

**M6: Нет rollback-стратегии для миграции 1.0.4.**

### Suggestions

- S1: Интеграционный тест для end-to-end flow.
- S2: Метрики (`object_tracks.active`, `notifications.suppressed`).
- S3: Упростить `BboxClusteringHelper` (брать bbox с максимальным confidence вместо weighted average).
- S4: Логирование при shutdown числа активных треков.
- S5: Унифицировать подход к транзакциям (`TransactionalOperator` везде).
- S6: Валидация callback data + проверка ACTIVE статуса.

### Questions

- Q1: Поведение при `recordTimestamp = null`?
- Q2: Сколько камер ожидается? Нужен ли eviction?
- Q3: Конфликт concurrent update одного трека?
- Q4: Каскадный DELETE при удалении recording?
- Q5: Runtime изменение настроек напрямую в БД?

---

## ccs-executor (albb-qwen / qwen3.6-plus)

### Critical Issues

**C1. `RepresentativeBbox` comment противоречит pixel coordinates decision (Plan Task 4)**

Комментарий «Coordinates are normalized [0..1]» противоречит iter-2 C10. Заменить на «Pixel coordinates, same space as DetectionEntity.x1..y2.»

**C2. `callback.user.id.chatId.long` — непроверенная цепочка типов ktgbotapi (Plan Task 18)**

Если `UserId.chatId: Long`, то `.long` — лишний вызов и компиляция упадёт. Если `UserId.chatId: ChatId` — корректно. Нужно проверить исходники ktgbotapi v31.2.0.

**C3. Добавление `TelegramProperties` в конструктор `TelegramUserServiceImpl` сломает существующие тесты (Plan Task 16, Step 2).**

План говорит «add cases for `isOwner`», но не перечисляет тестовые файлы, требующие обновления.

**C4. Дублирование значения по умолчанию `cleanupIntervalMs` в трёх местах (Plan Tasks 7, 11, 20).**

`3600000` появляется в `ObjectTrackerProperties`, в `@Scheduled(fixedDelayString = "...:3600000")` и в `application.yaml`. Если разработчик изменит дефолт в `ObjectTrackerProperties`, реальный `@Scheduled` не изменится.

### Concerns

**C5. Нет теста для `ObjectTracksCleanupTask` (Plan Task 11).**

**C6. `NotificationsCommandHandler`: проверить, что `message.chat.id` (тип `ChatId`) корректно передаётся в `sendTextMessage` (Plan Task 16, Step 3).**

**C7. `AppSettingsServiceImpl.setBoolean`: cache write внутри `@Transactional` — write до commit.**

**C8. Отсутствует импорт `KotlinLogging` в сниппете `NotificationsSettingsCallbackHandler` (Plan Task 17, Step 5).**

**C9. `NotificationsCommandHandler` не логирует результат показа диалога.**

### Suggestions

- S1: Вынести `NotificationsViewState` в отдельный DTO-файл.
- S2: Пакетная вставка/обновление треков в `evaluateLocked` (`saveAll`, batch UPDATE).
- S3: `logger.debug` для неизвестных/мальформированных callback data.
- S4: В `NotificationsCommandHandler` явно обработать ошибку `appSettings.getBoolean`.

### Questions

- Q1: Тип `UserId.chatId` в ktgbotapi v31.2.0?
- Q2: Нужен ли `@ConditionalOnProperty` на `ObjectTracksCleanupTask`?
- Q3: Нужна ли инвалидация кэша `AppSettingsService` при старте приложения?

---

## ccs-executor (albb-minimax / MiniMax-M2.5)

### Critical Issues

**C1. Противоречие в системе координат — план противоречит дизайну (Task 4 vs design).**

Плановый комментарий `RepresentativeBbox` «normalized [0..1]» противоречит дизайну (pixel coordinates) и iter-2 C10. Прямое противоречие.

**C2. Отсутствует `@EnableConfigurationProperties` для `ObjectTrackerProperties` (Plan Task 7).**

В плане нет указания, как активируется `@ConfigurationProperties`. Нужно `@EnableConfigurationProperties(ObjectTrackerProperties::class)` в `@Configuration`-классе или scan.

**C3. Предполагаемый порядок задач Telegram integration — потенциальная проблема с порядком зависимостей (Plan Task 18 → 9).**

В Task 18 вызывается `notificationsSettingsCallbackHandler`, который зависит от `AppSettingsService` (Task 9). Если параллельные агенты выполнят Task 18 раньше, будет compile error. Нужно явно документировать зависимости.

**C4. Несоответствие в callback данных между design и plan.**

Дизайн: `nfs:u:rec:1/0`, `nfs:u:sig:1/0`, `nfs:g:rec:1/0`, `nfs:g:sig:1/0`. Нужно убедиться, что Task 16/17 используют exactly эти строки.

### Concerns

- **K1.** Missing test for out-of-order recordings with stale tracks (Task 8).
- **K2.** Signal-loss path не проходит через `NotificationDecisionService` — приемлемо, но при будущих schedules придётся менять `TelegramNotificationServiceImpl`.
- **K3.** Callback query filter pattern: `initialFilter = { it.data.startsWith("nfs:") }` корректен, но нужно убедиться, что `answer(callback)` вызывается до обработки.
- **K4.** Потенциальная утечка памяти в `ConcurrentHashMap` (accepted, но напоминание).
- **K5.** Sliding TTL — потенциально бесконечные треки (documented edge case).
- **K6.** Missing validation `cleanupIntervalMs > 0` / `ttl > 0` в `ObjectTrackerProperties`.

### Suggestions

(Включены в Concerns).

### Questions

- **Q1.** Требуется ли финальный Liquibase checksum reset после добавления 1.0.4?
- **Q2.** Как обрабатывается случай, когда пользователь нажимает кнопку после истечения таймаута сообщения? (В `LanguageCommandHandler` есть `LANGUAGE_DIALOG_TIMEOUT_MS = 60_000L`; в `/notifications` явного таймаута нет.)

---

## Failed agents

- **ccs-executor (glm)** — HTTP 429 rate-limit at start; no usable output.
- **ccs-executor (deepseek)** — quota exhausted (`You've hit your limit · resets 11am Europe/Moscow`); no usable output.

These two profiles will be retried in a future iteration if needed.
