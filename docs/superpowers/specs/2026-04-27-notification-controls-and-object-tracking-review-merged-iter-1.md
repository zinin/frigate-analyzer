# Merged Design Review — Iteration 1

**Date:** 2026-04-27
**Agents:** codex-executor (gpt-5.5), gemini-executor, ccs-executor (glm/glm-5.1), ccs-executor (albb-glm/glm-5), ccs-executor (albb-qwen/qwen3.6-plus), ccs-executor (albb-kimi/kimi-k2.5), ccs-executor (albb-minimax/M2.5), ccs-executor (deepseek/V4)

---

## codex-executor (gpt-5.5, reasoning=xhigh)

### Critical Issues

1. **Compile blocker в плане.** `TelegramNotificationServiceImpl` должен импортировать `ru.zinin.frigate.analyzer.service.AppSettingsService`, но `modules/telegram/build.gradle.kts` не зависит от `:frigate-analyzer-service`. Надо либо добавить зависимость, либо вынести интерфейс/ключи настроек в модуль, доступный Telegram.

2. **Утверждение о корректности out-of-order обработки неверно.** Дизайн говорит, что `GREATEST(last_seen_at, :T)` делает match decision корректным при любом порядке, но pipeline реально обрабатывает записи параллельно и выбирает newest-first (`ORDER BY record_timestamp DESC`). Более новая запись может создать/обновить track, затем более старая запись сматчится в "будущее" и перезапишет bbox старым. Нужна явная стратегия: per-camera chronological processing, late-recording policy, или интервальная модель треков.

3. **Глобальный toggle signal-loss ломает state machine.** Дизайн оставляет global gate только в Telegram impl, но `SignalLossMonitorTask` уже переводит камеру в `SignalLost(notificationSent=true)` после вызова `sendCameraSignalLost`. Если Telegram service просто вернет "global off", состояние все равно будет считаться уведомленным. При включении global ON не будет late loss alert, а recovery может прийти без видимого loss. Gate должен быть до state transition либо notification service должен возвращать факт доставки/enqueue.

4. **Callback authorization недостаточно проработан.** Spec требует, чтобы `u:*` вызывал именно recipient, а `g:*` требовал OWNER. Плановая wiring-логика берет пользователя по `message.chat.id`, а не по `callback.user`, и не использует `AuthorizationFilter`. Надо делать по образцу `QuickExportHandler`: проверять callback sender, username/userId и роль.

5. **План местами сам себе противоречит и не пройдет собственные тесты.** В `ObjectTrackerServiceImplTest` тест `findActive uses now minus TTL` вызывает `service.evaluate(rec(), emptyList())` и ожидает `repo.findActive`, но реализация делает early return на empty detections.

### Concerns

- Fail-safe "tracker error = do not notify" может скрыть реальные события при временной проблеме БД. Fail-open с rate limit может быть безопаснее.
- Текущий IoU matching выбирает первый подходящий track, а не лучший IoU. При нескольких соседних объектах это даст перестановки треков и ложные suppress/notify.
- `visualizeFrames` вызывается до decision service — AI токены экономятся, но CPU/remote visualization работа для suppressed recordings все равно тратится.
- TTL semantics не равны "объект вошел в кадр". Если объект ушел на 90 секунд при TTL 120s и вернулся, уведомления не будет.
- `AppSettingsService` toggle через read-current / write-inverted подвержен гонке: два быстрых callback'а могут оба прочитать `true` и оба записать `false`.

### Suggestions

- Добавить per-camera ordering contract: либо процессить записи камеры строго по `record_timestamp ASC`, либо хранить `max_processed_record_timestamp` и для late recordings не менять bbox / не уведомлять.
- Для signal-loss вынести global gate в `SignalLossMonitorTask`/decider слой, чтобы состояние `notificationSent` отражало фактическую видимость события.
- Для tracker matching использовать best-match assignment: считать все IoU пары `(representative, activeTrack)` одного класса, сортировать по IoU desc и матчить без повторов.
- Передавать detections в `NotificationDecisionService` из `request.frames`, а не заново читать из БД.
- В план добавить явные изменения `modules/telegram/build.gradle.kts` и конкретную wiring-реализацию через существующий `onDataCallbackQuery`.

### Questions

- При отказе tracker/DB система должна fail-open или fail-closed?
- Нужно ли отправлять signal-loss alert пользователю, который включил уведомления уже во время текущего outage?
- Какой интервал отсутствия объекта считается "покинул кадр и вернулся": TTL или другое правило?
- Нужна ли дедупликация только для Telegram, или это будущая доменная decision layer для других каналов тоже?
- Требуется ли сохранять историю треков для диагностики, или достаточно mutable `object_tracks` с последним bbox?

---

## gemini-executor

### Critical Issues

**1. Некорректный fail-safe механизм (Угроза безопасности)**
В `NotificationDecisionServiceImpl` описано, что в случае перехвата исключения (например, БД недоступна, таймаут соединения) метод возвращает `NotificationDecision(shouldNotify = false, reason = TRACKER_ERROR)`. Это означает, что любая техническая проблема с трекером приведет к **полному скрытию уведомлений**. Для системы безопасности (видеонаблюдения) это недопустимый паттерн.
*Решение:* При падении трекера система должна деградировать до базового поведения -- вернуть `shouldNotify = true` (пропустить уведомление), чтобы пользователь не пропустил реальную угрозу.

**2. Утечка пула соединений БД из-за порядка блокировок**
В `ObjectTrackerServiceImpl` аннотация `@Transactional` установлена на методе `evaluate`, внутри которого происходит захват in-memory блокировки: `mutex.withLock { evaluateLocked(...) }`.
Прокси Spring открывает транзакцию (и берет connection из пула) *до* входа в метод. Если несколько корутин одновременно обрабатывают события с одной камеры, все они захватят по одному соединению с БД и встанут в очередь на `mutex`, простаивая. При пиковой нагрузке это приведет к мгновенному исчерпанию connection pool.
*Решение:* Захватывать транзакцию нужно строго *внутри* критической секции. Следует перенести `@Transactional` на `evaluateLocked` и вызывать его через self-reference прокси (или вынести логику БД в отдельный транзакционный компонент).

### Concerns

1. **Холостой расход токенов AI и ресурсов на визуализацию:** `visualizeFrames(...)` вызывается перед `decision = notificationDecisionService.evaluate(...)`. Если `shouldNotify=true` но все пользователи отключили уведомления, система все равно запросит AI-описание впустую.
2. **Поворотные камеры (PTZ) и сдвиг кадра:** При PTZ весь фон сдвинется, алгоритм перестанет находить совпадения со старыми треками → шквал ложных уведомлений.
3. **Утечка памяти в ConcurrentHashMap:** `perCameraMutex` ключи никогда не удаляются.

### Suggestions

1. Пакетная вставка и обновление (`saveAll()` вместо N отдельных `save()`).
2. Max IoU вместо First Match для точного отслеживания в плотных сценах.
3. Внедрение базовых метрик (Micrometer) — счетчики за пару строк кода.

### Questions

1. Обработка старых событий при даунтайме — будет ли корректно работать отсев дубликатов?
2. Архивация треков vs Физическое удаление — не лучше ли Soft Delete для будущей аналитики?

---

## ccs-executor (glm / glm-5.1)

### Critical Issues

1. **Двойная агрегация обнаружений — расходящийся контракт.** Дизайн-документ показывает вызов `aggregateDetections` в `NotificationDecisionService`, а план — внутри `ObjectTrackerServiceImpl`. Нужно зафиксировать, что агрегация является внутренней деталью трекера.

2. **`@Transactional` + `Mutex` — потенциальная проблема с транзакциями.** Транзакция начинается до захвата мьютекса, что может исчерпать R2DBC connection pool. Решение: убрать `@Transactional` с `evaluate()` и управлять транзакцией внутри `evaluateLocked()` через `TransactionalOperator`.

3. **`@Scheduled(fixedDelayString)` не парсит Duration `1h`.** В плане указано `@Scheduled(fixedDelayString = "${application.notifications.tracker.cleanup-interval:PT1H}")`, но YAML значение `1h` не является валидным форматом для `fixedDelayString`. Нужно использовать значение в миллисекундах (`3600000`) или cron-выражение.

### Concerns

- Отсутствие таймаута на диалог `/notifications`
- `runBlocking` в `ObjectTracksCleanupTask` — технический долг
- `ConcurrentHashMap<String, Mutex>` растёт бесконечно без очистки
- `AppSettingsService` кэш без TTL инвалидации
- Двойная фильтрация получателей может привести к затратам на AI-описание вхолостую
- Callback `nfs:g:*` для non-owner молча возвращает `UNAUTHORIZED`

### Suggestions

- Защита от `NaN` в `BboxClusteringHelper` при `weightSum == 0f`
- Greedy IoU matching вместо Hungarian algorithm — допустимо, но стоит отметить
- Можно извлечь detections из `SaveProcessingResultRequest` вместо дополнительного запроса к БД
- Использовать `recording.detectionsCount` для early return

### Questions

- Как обрабатывается out-of-order обработка записей одной камеры?
- Что при повторной обработке (reprocess) записи?
- Где именно wire-up Telegram callbacks?

---

## ccs-executor (albb-glm / glm-5)

### Critical Issues

1. **Leak памяти в ConcurrentHashMap с Mutex:** Mutex создаются через `computeIfAbsent` и никогда не удаляются.
2. **Несоответствие между `isNew()` и методом сохранения:** `AppSettingEntity.isNew() = true` заставит `save()` всегда пытаться INSERT.
3. **Отсутствует обработка bbox > 1.0 (malformed YOLO output):** Нет validation для normalized coordinates.
4. **Greedy clustering — O(n²) сложность:** При 500+ detections одного класса может замедлить pipeline.
5. **`notifications_signal_enabled` — только global toggle, без tracker:** Нет fail-safe если `AppSettingsService` падает при чтении global toggle.

### Concerns

1. TTL=120s может быть слишком коротким (detector blink вызывает новый notification)
2. Bbox drift при медленном смещении stationary объекта
3. Nullable `id` в `ObjectTrackEntity` — runtime risk
4. Отсутствует тест для `staleTracksCount` semantics
5. Не указано **где** detections извлекаются для вызова `NotificationDecisionService`
6. Defense-in-depth для recording global toggle избыточен

### Suggestions

1. Добавить Micrometer metrics
2. Expose `DetectionDelta` в DEBUG logging
3. Пересмотреть полезность `last_recording_id` (ON DELETE SET NULL)
4. UI feedback при global toggle — кто затронут
5. Timezone-aware schedules в будущем

### Questions

1. Expected behaviour при restart с pending recordings?
2. Должен ли `NotificationDecisionService` быть в `service` или `core`?
3. Что происходит при manual reprocessing?
4. Test coverage для cleanup task race с active matching?
5. YOLO class_name case sensitivity?

---

## ccs-executor (albb-qwen / qwen3.6-plus)

### Critical Issues

1. **Трекер НЕ работает при выключенном глобальном тогле — противоречие спецификации.** Псевдокод делает ранний `return` до вызова `objectTracker.evaluate`, хотя spec говорит "tracker still runs". Fix: вызывать трекер безусловно.

2. **Greedy matching без учёта IoU-скоринга.** Алгоритм перебирает `representativeBboxes` в HashMap-порядке. При нескольких треках одного класса возможны субоптимальные матчи. Fix: сортировать bbox по убыванию IoU с лучшим треком.

3. **`isNew() = true` всегда в `ObjectTrackEntity` и `AppSettingEntity`.** Стандартный `repository.save()` всегда делает INSERT — будет `UNIQUE VIOLATION`.

4. **`ObjectTrackerProperties` объявлен, но не сконфигурирован в плане.** Нет task для его создания или привязки к `application.yaml`.

### Concerns

5. Callback-данные без идентификатора текущего состояния — лишняя DB-операция и race condition.
6. `ObjectTrackEntity` с nullable полями — null-safety overhead.
7. Индекс `idx_object_tracks_cam_lastseen` не покрывает cleanup-запрос (нет `cam_id` в WHERE).
8. План не покрывает тест `NotificationsCommandHandlerTest`.
9. Out-of-order recordings: `GREATEST` спасает timestamp но не bbox — рассинхрон.

### Suggestions

10. Добавить Micrometer-метрики (3 счётчика).
11. `DetectionDelta.newClasses` логировать вместо `newTracksCount`.
12. Задокументировать почему не используется `ON CONFLICT` upsert для `object_tracks`.

### Questions

13. Как `ObjectTrackerProperties` маппится на env vars (duration strings)?
14. Содержит ли `SaveProcessingResultRequest.frames` `DetectionEntity`?
15. Signal-loss/recovery: глобальный тогл — только defense-in-depth или единственный гейт?

---

## ccs-executor (albb-kimi / kimi-k2.5)

### Critical Issues

1. `ObjectTrackEntity.isNew()` always returns `true` — breaks Spring Data `save()` semantics.
2. Global toggle logic asymmetry — recording notifications check twice, signal-loss only once.
3. Memory leak in `perCameraMutex` — `ConcurrentHashMap<String, Mutex>` grows unbounded.
4. `runBlocking` in `@Scheduled` — blocks the scheduler thread.

### Concerns

1. IoU=0.3 threshold too liberal for cross-recording matching.
2. No object size consideration in IoU matching.
3. Cleanup interval vs TTL race condition after downtime.
4. Missing composite index on `(cam_id, class_name, last_seen_at)`.

### Suggestions

1. Add minimal metrics.
2. Make IoU threshold configurable per-camera.
3. Add `trackIds` to `DetectionDelta`.
4. Consider PostgreSQL advisory locks for multi-instance.
5. Add `max_tracks_per_camera` limit with FIFO eviction.

### Questions

1. Handling of low-confidence detections in tracker?
2. Whether `descriptionSupplier` lambda is truly not invoked when `shouldNotify=false`?
3. Timezone handling for `last_seen_at` comparison?
4. Liquibase migration rollback strategy?
5. Race condition on simultaneous global toggle clicks?

---

## ccs-executor (albb-minimax / M2.5)

### Critical Issues

1. **IoU tracking for parked cars:** PTZ cameras, partial occlusion, different bbox aspect ratios not addressed.
2. **Concurrency Mutex description incomplete:** Which Mutex implementation? How does it integrate with R2DBC transactions?
3. **Global OFF behavior inconsistent:** Tracker runs and updates state → unnecessary DB load and potential race condition.

### Concerns

4. Fail-safe = "do not notify" — silently suppresses all notifications on tracker errors.
5. `app_settings` as key-value strings — no compile-time type safety.
6. Cleanup timing — runs once per hour with 1-hour retention, but TTL is 120 seconds. Stale tracks live up to 1 hour post-expiry.
7. `GREATEST(last_seen_at, :T)` safety — semantic meaning breaks under replay scenarios.

### Suggestions

8. Simplify by storing global flags differently.
9. Redis as alternative to PostgreSQL for tracking state.
10. Add Micrometer metrics in initial scope.
11. Event-driven decoupling (`RecordingAnalyzed` events).

### Questions

- Q1: Frigate camera resets/restarts — camera ID changes?
- Q2: Expected DB load under realistic camera counts?
- Q3: Rationale for different IoU thresholds (0.3 vs 0.5)?
- Q4: Multi-camera object dedup planned?
- Q5: Parallel-processing race condition tested end-to-end?

---

## ccs-executor (deepseek / V4)

### Critical Issues

**1. Greedy track matching с `firstOrNull` даёт алиасинг при близких треках одного класса**
Алгоритм делает `active.firstOrNull { it.matches(bbox, threshold) }` — жадный захват первого активного трека. При двух близко припаркованных машинах возможен cross-match. Нужно заменить на **best-match** (максимальный IoU).

**2. Трекер работает даже когда `global = OFF` и все пользователи отключены**
При global OFF на часы/дни трекер продолжает CPU на кластеризацию и IO на DB round-trips вхолостую. Предложение: если global OFF, трекер не вызывать; при включении обратно — одноразовый spike уведомлений, приемлемый компромисс.

**3. Отсутствие confidence floor в кластеризации**
`BboxClusteringHelper.cluster` не фильтрует детекции по confidence. Low-confidence detection расширяет union bbox кластера, потенциально объединяя два разных объекта. Добавить `confidence >= minConfidence` фильтр.

### Concerns

4. `runBlocking` в `@Scheduled` задаче — антипаттерн в реактивном приложении.
5. `ConcurrentHashMap<String, Mutex>` без эвикции — медленная утечка памяти.
6. Нет теста на реалистичный multi-frame сценарий (10 frames parked car with jitter).
7. `NotificationDecision` не экспонируется наружу — structured logging дал бы видимость.
8. IoU порог 0.3 для мелких объектов — при том же абсолютном сдвиге IoU падает до 0.

### Suggestions

9. Best-match вместо first-match.
10. Учтена subtle race `GREATEST` vs `recordingTimestamp`.
11. Добавить `confidenceFloor` в `ObjectTrackerProperties`.
12. Логировать suppressed уведомления при отключённом Telegram.
13. Неиспользуемые импорты в тестах (`Runs`, `just`).

### Questions

- Что с INVITED пользователями? Должны ли видеть `/notifications`?
- Что при удалении камеры из Frigate?
- Риск O(n²) кластеризации при большом количестве детекций?
- Обработка `chatId = null` при вызове `updateNotificationsRecordingEnabled`?
