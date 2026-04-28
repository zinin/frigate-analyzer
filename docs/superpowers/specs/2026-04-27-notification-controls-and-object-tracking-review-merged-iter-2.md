# Merged Design Review — Iteration 2

## codex-executor (gpt-5.5)

### Critical Issues

1. Трекер обновляет состояние до фактической постановки Telegram-уведомления в очередь. Если `NotificationDecisionService` создаст/обновит track, а затем `sendRecordingNotification` упадёт, текущий `RecordingProcessingFacade` проглотит ошибку уведомления. Следующие сегменты уже будут считаться повтором и могут быть подавлены. Нужно либо делать enqueue/outbox частью атомарного решения, либо иметь rollback/compensation для newly-created tracks при сбое fan-out.

2. Out-of-order обработка описана как корректная, но это неверно. `GREATEST(last_seen_at, :T)` не защищает bbox: старый сегмент может перезаписать `bbox_*` трека из будущего и сломать последующие match-и.

3. Global gate для signal-loss находится слишком поздно. `SignalLossMonitorTask` уже меняет state до отправки. Если global OFF просто вернёт из Telegram service, state machine будет считать loss/recovery обработанным, и после включения global ON catch-up уведомления не будет.

4. Callback authorization не соответствует требованию дизайна. Плановая wiring-логика берёт пользователя по `message.chat.id`, а не по `callback.user`, и не использует `AuthorizationFilter`. Новый handler должен повторить pattern существующего `QuickExportHandler`, который проверяет callback sender.

5. В плане есть compile blockers после правок про `TransactionalOperator`: тест создаёт `ObjectTrackerServiceImpl(repo, uuid, clock, props)`, а реализация требует ещё `transactionalOperator`. Также используется `executeAndAwait`, но его extension-import не указан.

### Concerns

- `AppSettingsService.getBoolean()` может стать точкой fail-closed. В `NotificationDecisionServiceImpl` чтение global flag стоит до `try/catch`; в Telegram defense gate тоже нет fallback.
- Cache в `AppSettingsServiceImpl` гоняется без синхронизации: concurrent read может загрузить старое значение и записать его в cache после `setBoolean().cache.remove()`.
- `cleanupInterval` объявлен как env/config, но scheduled task его не использует: hard-coded `@Scheduled(fixedDelay = 3_600_000)`.
- Документы говорят, что bbox normalized `0..1`, но текущая визуализация работает с pixel coordinates. IoU scale-invariant, но документация может привести к неверным CHECK constraints или тестам.

### Suggestions

- Для out-of-order явно выбрать политику: per-camera chronological tracking, late-recording no-op для state mutation, либо хранить `max_processed_record_timestamp`/историю bbox.
- Для signal-loss перенести global decision в `SignalLossMonitorTask` до state transition или изменить `TelegramNotificationService` так, чтобы он возвращал `enqueued=true/false`.
- Для matching нескольких объектов одного класса лучше считать все IoU-пары и делать greedy assignment по IoU desc или Hungarian matching.
- Добавить тесты на: Telegram enqueue failure после new track, app_settings read failure, global OFF для signal-loss без потери будущего recovery/loss, callback от неавторизованного sender.

### Questions

- Должен ли tracker fail-open переопределять явный OWNER global OFF, или при global OFF уведомления не отправляются даже при сбое трекера?
- При включении global/user notifications обратно нужны catch-up события для текущего camera outage или уже стоящего объекта?
- Bbox contract окончательно pixel или normalized?

---

## gemini-executor

### Critical Issues

1. **Необработанные исключения в Flow для Telegram-коллбеков (Task 18)**
   В предложенном коде `onEach` для обработки `waitDataCallbackQuery` вызывает `editMessageText`. Если пользователь быстро нажмет кнопку дважды (или старое состояние случайно совпадет с новым), Telegram API выбросит ошибку `Bad Request: message is not modified`. Любое неперехваченное исключение внутри `onEach` приведет к крэшу всего `Flow`, и бот перестанет реагировать на inline-кнопки до перезапуска приложения. Обязательно добавьте `try/catch` вокруг логики редактирования сообщения.

2. **Рассинхронизация состояния кнопок (Toggle vs Explicit Set) (Task 17)**
   Коллбеки используют семантику переключения (`nfs:g:rec:tgl`), которая просто инвертирует текущее состояние в БД. Если у пользователя в истории чата осталось несколько старых сообщений `/notifications` или несколько OWNER меняют настройку, нажатие старой кнопки может выключить уже включенную опцию.
   Решение: передавать целевое состояние прямо в `callback_data`, например `nfs:g:rec:1` / `nfs:g:rec:0`.

### Concerns

1. **Холостая работа трекера при отключенных глобальных уведомлениях (Task 10)**
   Трекер вызывается даже при `globalEnabled == false`, чтобы сохранить когерентность состояния. Это нагрузка во время длительного OFF, но это осознанное решение дизайна.

2. **Race condition в кэше `AppSettingsServiceImpl` (Task 9)**
   Concurrent cache miss может записать устаревшее значение в cache после `setBoolean`. Рекомендуется `Mutex` на уровне ключа или другой синхронизированный populate.

3. **Откат координат при out-of-order записях (Task 8 & 2)**
   `last_seen_at = GREATEST(...)` защищает TTL, но `bbox_*` и `last_recording_id` перезаписываются безусловно. Нужно использовать `CASE WHEN :lastSeenAt >= last_seen_at THEN ... ELSE ... END`.

### Suggestions

1. Добавить явные `<rollback>` в Liquibase SQL changesets.
2. Задокументировать, что быстрый объект внутри одного recording может дать несколько inner clusters и временных ghost tracks до TTL.
3. Добавить unit-тесты для `TelegramUserService.isOwner`.

### Questions

1. Стоит ли использовать строки `"true"` / `"false"` в `app_settings`, если future schedules могут потребовать JSON?
2. Что делать, если `updateNotifications...` вернул `false`: silently rerender или показать callback alert об ошибке?

---

## ccs-executor output A

### Критические проблемы

1. Отсутствует зависимость `telegram → service`. В `modules/telegram/build.gradle.kts` нет `implementation(project(":frigate-analyzer-service"))`; без неё не скомпилируются импорты `AppSettingsService`/`AppSettingKeys`.

2. `DetectionEntityService` не имеет `findByRecordingId`. План предусматривает добавление метода, но это стоит сделать явно и не как побочный эффект.

3. `ObjectTrackerProperties` не зарегистрирован в `@EnableConfigurationProperties`. В core свойства регистрируются явным перечислением, поэтому нужно явно добавить `ObjectTrackerProperties::class` или scan.

4. Нужен тест на случай, когда все детекции ниже `confidenceFloor`, кластеризация возвращает пустой список, tracker возвращает нулевой delta, а decision service трактует это как suppression.

### Замечания и предложения

- Проверить наличие `TransactionalOperator` bean.
- Greedy first-match в inner clustering — limitation, можно задокументировать.
- Callback handler должен использовать `callback.from`, а не `message.chat.id`, если применимо.
- Добавить `NOTIFICATIONS_TRACK_CONFIDENCE_FLOOR` в `application.yaml` и `.env.example`.
- Рассмотреть индекс `(cam_id, class_name, last_seen_at DESC)`.
- `cleanupInterval` задан в properties, но не используется `@Scheduled`.

---

## ccs-executor output B

### Critical Issues

1. `@ConfigurationPropertiesScan` не найден в core-модуле. Без явной регистрации `ObjectTrackerProperties` приложение упадёт при старте.

2. `cleanupInterval` — мёртвая конфигурация. Свойство документируется, но task использует hard-coded `@Scheduled(fixedDelay = 3_600_000)`.

3. Callback-регистрация не соответствует паттернам кодовой базы: план предлагает `waitDataCallbackQuery().filter{}.onEach{}.launchIn(this)`, а существующий `FrigateAnalyzerBot.registerRoutes()` использует `onDataCallbackQuery(initialFilter = {...})`.

4. `DetectionEntityService`/`DetectionEntityRepository` не имеют `findByRecordingId`; план упоминает это, но без теста.

### Concerns

- Detections читаются вне per-camera lock; порядок нельзя менять без понимания.
- `@Transactional(readOnly = true)` на `AppSettingsServiceImpl.getBoolean` избыточен при cache hit.
- Нет тестов для `RecordingProcessingFacade`: suppression, non-suppression, отсутствие AI supplier при suppression.
- Signal-loss asymmetry стоит явно документировать.

### Suggestions

- Использовать `onDataCallbackQuery` pattern для callback-регистрации.
- Убрать `@Transactional` с cache-hit path.
- Починить `cleanupInterval` или убрать его.
- Указать точный файл и метод для Task 18: `FrigateAnalyzerBot.kt`, `registerRoutes()`.
- Добавить facade-тесты.

---

## ccs-executor output C

### Критические проблемы

1. Несоответствие API ktgbotapi для callback-обработки: проект использует `onDataCallbackQuery(initialFilter = {...})`, а план предлагает `waitDataCallbackQuery().filter{}.collect{}`. Нужно переписать Task 18 под `onDataCallbackQuery` в `FrigateAnalyzerBot.registerRoutes()`.

2. `DetectionEntityRepository.findByRecordingId` не существует; метод должен быть явно объявлен.

3. `NotificationsSettingsCallbackHandler` не реализует bot-wiring: `dispatch()` не делает Telegram calls, а `editMessageText`/`answer` остаются в псевдокоде. Нужно либо перенести Telegram calls в handler как в QuickExportHandler, либо сделать Task 18 полноценным wiring с тестами.

### Вопросы и сомнения

- Race между global toggle и Telegram defense layer не баг, но его нужно задокументировать.
- Task 12 должен явно grep-нуть все тестовые `UserZoneInfo` usages.
- `@Transactional` на `AppSettingsServiceImpl.getBoolean` создаёт overhead на cache hit.

### Предложения

- Добавить тест confidenceFloor в Task 8.
- Добавить интеграционный/facade тест для full flow.
- Проверить/описать `TransactionalOperator` wiring.

---

## ccs-executor output D

### Critical Issues

1. Нет batch INSERT для новых треков: `repository.save()` вызывается в цикле. Предложено `saveAll` или batch insert.

2. `BboxClusteringHelper` как статический `object` не инжектируется, что снижает изолируемость unit-тестов.

3. `AppSettingsServiceImpl` cache имеет race на populate: два concurrent cache-miss вызова пойдут в БД.

### Concerns

- `ObjectTrackerService.evaluate` принимает полный `RecordingDto`, хотя использует только `id`, `camId`, `recordTimestamp`.
- Signal-loss не проходит через `NotificationDecisionService`, что создаёт асимметрию.
- `ObjectTracksCleanupTask` без условного property guard.
- Task 17 уже содержит тесты, но стоит убедиться, что они покрывают Telegram-side behavior.

### Suggestions

- Задокументировать invariant `NotificationDecision.delta`: non-null iff tracker was invoked.
- Добавить integration test для цепочки `RecordingProcessingFacade` → `NotificationDecisionService` → `ObjectTrackerService` → Telegram queue.
- Задокументировать `ON DELETE SET NULL` поведение `last_recording_id`.

---

## ccs-executor output E

### Critical Issues

1. Транзакция внутри Mutex: output ошибочно утверждает, что план не использует `TransactionalOperator`, но актуальный план Task 8 уже включает `TransactionalOperator.executeAndAwait` внутри `withLock`. Это повтор/false positive относительно Iteration 1.

2. Неэффективный индекс для cleanup: `DELETE WHERE last_seen_at < :threshold` не использует `(cam_id, last_seen_at DESC)` без `cam_id`; нужен отдельный индекс `(last_seen_at)`.

3. Асимметричная защита signal-loss path: только Telegram impl gate.

4. Graceful shutdown для cleanup task предложен, но для Spring `@Scheduled` single DELETE это, вероятно, overkill.

5. Batch operations atomicity не покрыта integration test.

### Concerns / Suggestions

- First-time burst уведомлений при пустом tracker state.
- NULL bbox handling; в текущей модели координаты, вероятно, non-null, нужно проверить.
- Debug/reset mechanism для tracker.
- Observability/metrics как follow-up.

---

## ccs-executor output F

### Critical Issues

1. Signal-loss/recovery не проходит через `NotificationDecisionService`; это осознанная асимметрия, но требует документирования.

2. Matched tracks могут жить бесконечно, если объект стоит 30 дней. Это функционально корректно, но нужно явно описать sliding TTL semantics.

3. Out-of-order обработка: `GREATEST(last_seen_at, :T)` не защищает `bbox_*` и `last_recording_id`; нужен timestamp guard.

4. Callback handler не потокобезопасен для concurrent edits; возможны double-click races.

### Concerns / Suggestions

- IoU=0.3 для PTZ/scale changes.
- Нет метрик observability.
- Inner clustering может терять мелкие объекты.
- Нужен timeout вокруг cleanup, если он усложнится.
- Рассмотреть `(cam_id, class_name, last_seen_at)` индекс.
- Валидация `TTL <= cleanupRetention`.
- Rate limiting/debounce на callback'и.
