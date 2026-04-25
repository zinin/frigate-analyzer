# Merged Design Review — Iteration 1

**Documents reviewed:**
- Design: `docs/superpowers/specs/2026-04-25-camera-signal-loss-design.md`
- Plan: `docs/superpowers/plans/2026-04-25-camera-signal-loss.md`

**Reviewers (8):** codex-executor (gpt-5.5), gemini-executor, ccs-executor × 6 profiles (glm, albb-glm, albb-qwen, albb-kimi, albb-minimax, deepseek)

---

## codex-executor (gpt-5.5)

### Critical Issues

- Startup grace описан противоречиво. В design одновременно сказано, что камера, уже мёртвая на рестарте, будет уведомлена после grace, но тестовый раздел говорит, что `SignalLost` из grace не шлёт поздний alert. Плановый код тоже переводит `(null, true)` в `SignalLost` во время grace и потом попадает в no-op. Нужно выбрать семантику и явно смоделировать `PendingLoss`/`suppressed` состояние.

- Cleanup ломает требование recovery-уведомлений. Если камера ушла в `SignalLost`, через `activeWindow` её state удаляется, а при возвращении она попадёт в `(null, false)` и recovery не отправится. Для уже уведомлённых потерь `SignalLost` лучше хранить дольше active-window или вообще до восстановления.

- Поведение "queue full" в design не соответствует коду. Design говорит про `Channel.trySend`, но текущая очередь делает `channel.send` в `TelegramNotificationQueue.kt:40`. Значит `runBlocking` в scheduled task может зависнуть на заполненной очереди, а не "warn and drop".

- Переход `Healthy -> SignalLost` использует `h.lastSeenAt`, а не текущий `maxRecordTs`. Если между тиками появилась новая запись, но к моменту тика она уже старше threshold, alert и downtime будут посчитаны от устаревшего времени.

- Default `SIGNAL_LOSS_ENABLED=true` сломает существующие integration tests: в `modules/core/src/test/resources/application.yaml:40` Telegram выключен. С `matchIfMissing=true` signal-loss task начнёт падать на старте всех контекстных тестов, если явно не добавить `application.signal-loss.enabled=false` в test config.

- Conflict-fail лучше не класть в `SignalLossMonitorTask.@PostConstruct`. В проекте уже есть более подходящий паттерн: `AiDescriptionTelegramGuard`. Для signal-loss нужен отдельный guard.

- DTO в плане не зеркалит существующий стиль R2DBC projections. `CameraRecordingCountDto` использует `@Column("cam_id")`; новый `LastRecordingPerCameraDto` тоже должен явно аннотировать колонки.

### Concerns

- `ConcurrentHashMap` не настоящий механизм корректности state machine.
- `runBlocking` в `@Scheduled` — лучше один общий `runBlocking { tickSuspend() }`, не отдельные блокировки.
- "First sighting lost" может отправлять alerts по камерам, которые последний раз писали 23 часа назад и уже выведены из эксплуатации.
- "camera lost signal" — слишком уверенно; фактически детектируется "analyzer stopped seeing recordings".
- Нет тестов на "latest timestamp advanced but still over threshold", recovery after `activeWindow`, partial recipient failure, non-blocking enqueue.
- `@Scheduled(fixedDelayString = "#{@signalLossProperties.pollInterval.toMillis()}")` — лучше `${application.signal-loss.poll-interval}` (как в `ServerHealthMonitor`).
- `.claude/rules/configuration.md` не покрывает README/env docs.

### Suggestions

- Вынести state machine в чистую функцию: `(prev, now, latestRecord, inGrace) -> (newState, event?)`.
- Ввести `LossPendingDuringGrace` или поле `notificationSent=false`.
- Добавить `TelegramNotificationQueue.tryEnqueue(...)` — non-blocking путь для системных alerts.
- Не удалять `SignalLost` по `activeWindow`; cleanup только для `Healthy`.
- Использовать отдельный `SignalLossTelegramGuard`.

### Questions

- Recovery, если outage > `activeWindow`, но loss-alert уже был?
- После рестарта: камера уже лежащая до старта получает loss-alert после grace или подавляется?
- "camera signal lost" vs "recordings stopped arriving"?
- Исключать камеры близкие к концу active-window?
- `SIGNAL_LOSS_ENABLED=true` по умолчанию правильно для dev/test?

---

## gemini-executor

### Critical Issues

1. **Блокировка пула потоков TaskScheduler (`runBlocking` внутри `@Scheduled`)** — в Spring Boot по умолчанию `TaskScheduler` имеет размер пула 1. `runBlocking` заблокирует поток на время I/O, заморозит остальные `@Scheduled` задачи (`ServerHealthMonitor`). Spring 3.2+ поддерживает корутины в планировщике — `tick()` должен быть `suspend fun`.

2. **Критическая логическая ошибка в State Machine (потеря уведомлений из-за Grace Period)** — ветка `prev == null && overThreshold` во время startupGrace переводит в `SignalLost` но подавляет alert. Следующий тик попадает в `(SignalLost, true)` no-op. Камера, мёртвая до перезапуска, никогда не получит alert. **Это противоречит дизайну "Restart Behavior"**.

### Concerns

1. **Производительность БД (Отсутствие индексов для группировки)** — нужен композитный индекс `(record_timestamp, cam_id)`.
2. **Многопоточность и `ConcurrentHashMap`** — обоснование в документе говорит о небольшом недопонимании модели памяти Java. `fixedDelay` гарантирует *happens-before* между ticks.
3. **Отсутствие recovery-алерта для камер, выпавших за пределы Active Window**.

### Suggestions

1. **Использование `TestDispatcher` для виртуального времени в тестах** — `runTest { advanceTimeBy(...) }` вместо `MutableClock`.
2. **Редизайн проверки конфликта в `@PostConstruct`** — `@ConditionalOnBean(TelegramNotificationQueue::class)` чище.

### Questions

1. Согласованность `activeWindow` и Grace Period?
2. Зачем NoOp если контекст падает при отсутствии Telegram?

---

## ccs-executor (glm)

### Critical Issues

1. **`@Scheduled(fixedDelayString)` — SpEL vs `${...}`** — в проекте паттерн `${application.detect.health-check-interval}` (ServerHealthMonitor) или `PT1H` literal (TempFileHelper). SpEL хрупок (зависит от bean name `signalLossProperties`).
2. **`TelegramProperties()` не имеет no-arg конструктора** — реальный класс требует `botToken` и `owner` без default. Тест не скомпилируется.
3. **`UserZoneDto` не существует** — реальный класс `ru.zinin.frigate.analyzer.telegram.dto.UserZoneInfo`.
4. **Некорректное описание сценария "queue full"** — `channel.send()` suspend-блокирует, не `trySend`.
5. **Множественные `runBlocking` в одном tick** — каждый emit создаёт новый scope.

### Concerns

6. `SignalLossProperties.validate()` вместо JSR-303 `@Validated` — несогласованность.
7. Cleanup камеры из `SignalLost` — потеря recovery-уведомления.
8. Отсутствие `@EnableConfigurationProperties(SignalLossProperties::class)` в явном шаге.
9. Конфликт-fail через `@PostConstruct` — race condition?
10. Integration test (Task 10) — `SpringApplication(StubApp::class)` не сработает.
11. Отсутствие обработки `CancellationException` в tick.

### Suggestions

12. Вынести state-machine в отдельную чистую функцию.
13. Рассмотреть `initialDelayString` вместо startup grace.
14. Альтернативно `@ConditionalOnBean(TelegramNotificationService.class)`.
15. `formatDuration` — убрать `0 сек`.

### Questions

16. Огромная таблица `recordings` — нужен индекс?
17. Почему `activeWindow` = 24h, а не "время с последней записи"?
18. Recovery после длительного сбоя БД?

---

## ccs-executor (albb-glm)

### Critical Issues

1. **`ServerHealthMonitor` не существует** — grep не нашёл. Дизайн ссылается на несуществующий класс.
2. **`channel.send` vs `channel.trySend`** — описание в design не соответствует коду.
3. **`runBlocking` внутри `@Scheduled` — потенциальный deadlock** — может exhaust thread pool.

### Concerns

4. ConcurrentHashMap visibility — ConcurrentHashMap не нужен для sequential ticks.
5. "First sighting lost" — wording может быть misleading.
6. Cleanup logic — UX inconsistent для long downtime.
7. NotificationTask sealed interface refactor — потенциальные breaking changes.
8. Test plan gaps — нет проверки state после tick.

### Suggestions

9. **Альтернатива: `@Async` с self-managed loop** (как `WatchRecordsTask`).
10. Добавить `@PreDestroy` shutdown.
11. Duration formatting — рассмотреть ICU4J.

### Questions

12. `TelegramProperties.enabled` — default value?
13. `signalLossFormatter` — `@Component`?
14. SpEL в `@Scheduled` — работает ли?

---

## ccs-executor (albb-qwen)

### Critical Issues

1. **Channel type compatibility при рефакторинге `NotificationTask`** — потеря уведомления на уровне queue не означает retry на стороне монитора (документированный trade-off).
2. **`runBlocking` внутри `@Scheduled` — истощение пула TaskScheduler** — нужен `spring.task.scheduling.pool.size` ≥ 2.
3. **Конфликт-fail в `@PostConstruct` — порядок инициализации** — лучше `ApplicationListener<ApplicationReadyEvent>` или `SmartInitializingSingleton`.

### Concerns

4. SpEL vs placeholder — несогласованность стиля.
5. `LastRecordingPerCameraDto` — отсутствие `@Column` аннотаций.
6. Состояние `(null, true)` — может вводить в заблуждение (gap=23h).
7. Cleanup: камеры выпавшие из activeWindow — потеря recovery alert.
8. Рефакторинг NotificationTask — охват изменений.

### Suggestions

S1. **`emitLoss`/`emitRecovery` — сделать членами класса** (план не компилируется, top-level функция не имеет доступа к `notificationService`).
S2. Добавить `SignalLossProperties` в `@EnableConfigurationProperties`.
S3. Использовать `runTest` вместо `runBlocking` в тестах.
S4. Добавить метрику last tick status.
S5. Рассмотреть `Flow` для будущей миграции.

### Questions

Q1. `activeWindow = 24h`, если retention Frigate < 24h?
Q2. Сколько камер ожидаем? Индекс на `record_timestamp`?
Q3. Почему `gap > threshold` (строго), а не `>=`?
Q4. Jakarta Validation для `SignalLossProperties`?

---

## ccs-executor (albb-kimi)

### Critical Issues

1. **ConcurrentHashMap visibility ошибка** — `state` обновляется без синхронизации с `seenCamIds`.
2. **runBlocking внутри @Scheduled — блокирует поток планировщика**.
3. **Неправильное размещение проверки конфликта** — `@PostConstruct` плох; лучше `@ConditionalOnExpression` или `@Bean` factory.
4. **Некорректная семантика перехода "first sighting lost"** — "Last recording: X (3 min ago)" вводит в заблуждение.
5. **Cleanup удаляет состояние без уведомления** — recovery без предшествующего loss возможен.
6. **NotificationTask sealed interface refactor — скрытые зависимости** (сериализаторы, deserializers, copy(), componentN(), reflection).

### Concerns

7. activeWindow для сезонных камер.
8. Нет retry для failed notifications.
9. `Duration.toMillis()` в SpEL — неявное поведение.
10. Отсутствие метрик (`camera.signal.lost.count`, `signal.loss.detection.lag.seconds`).

### Suggestions

11. Event-driven через `ApplicationEventPublisher`.
12. Circuit breaker (Resilience4j).
13. `@ConditionalOnExpression` вместо `@PostConstruct`.
14. Per-camera threshold в архитектуре.
15. Caffeine с expiration вместо `ConcurrentHashMap`.

### Questions

16. Семантика `findLastRecordingPerCamera` при `cam_id IS NULL`?
17. Почему `notifiedAt = Instant.now()`, а не `maxRecordTs + threshold`?
18. Обработка `CancellationException` в `emitLoss`?
19. Тестирование time-based логики с `Clock` — Spring `@Scheduled` использует системное время.
20. `SignalLost.lastSeenAt` для downtime — recovery не будет если cleanup.

---

## ccs-executor (albb-minimax)

### Critical Issues

1. **`runBlocking` внутри `@Scheduled` — блокировка потока планировщика** — рекомендация `CoroutineScope(Dispatchers.IO).launch`.
2. **`TelegramProperties` не имеет конструктора по умолчанию** — нужно `TelegramProperties(enabled=true, botToken="test", owner="test")`.
3. **Неполная логика состояния при первом запуске после grace period** — `(SignalLost, true)` no-op, уведомление НЕ отправляется.
4. **Тесты не покрывают конфликтную проверку при старте** — нужен `@SpringBootTest`.

### Concerns

5. ConcurrentHashMap — обоснование неточное.
6. NotificationTask sealed interface refactor — breaking change для external references.
7. "First sighting lost" — семантика странная.
8. Отсутствует обработка "частичного восстановления" (flapping).

### Suggestions

9. **Добавить метрики Micrometer сразу** (`signal.loss.detected`, `signal.loss.recovered`, `signal.loss.tick.duration`, `signal.loss.cameras.monitoring`).
10. Grace period — лог "END OF GRACE PERIOD".
11. Retry для отправки уведомлений (3 attempts, exponential backoff).

### Questions

12. 100+ камер — pagination/batching?
13. Изменение threshold в runtime?
14. Тесты для `gap == threshold` граница?

---

## ccs-executor (deepseek)

### Critical Issues

1. **Расхождение `send()` vs `trySend()`** — описано в дизайне `Channel.trySend`, реальный код использует suspend `channel.send()`.
2. **`UserZoneDto` не существует** — реальный класс `UserZoneInfo`.
3. **`runBlocking` без Dispatcher** — паттерн в проекте `runBlocking(Dispatchers.IO)`.
4. **Семантика `lostCount` в ветке `(SignalLost, true)`** — счётчик считает текущее состояние, а не новые потери.
5. **Двойной `runBlocking` на каждый emit** — N последовательных блокировок scheduler-потока.

### Concerns

- Избыточность `ConcurrentHashMap` при `fixedDelay`.
- `lateinit startedAt` race.
- "Recovery без loss" в grace period.
- Cleanup на каждом тике.
- Breaking change в конструкторе `TelegramNotificationServiceImpl`.
- Locale-зависимый `FormatStyle.MEDIUM`.
- Неконсистентность `clearMocks`.
- Избыточная валидация `Duration`.

### Suggestions

- Аккумуляция emit-ов в один runBlocking.
- Упрощение `MutableClock`.
- Тест на состояние map.
- Удаление неиспользуемого `notifiedAt`.
- Уточнение семантики `lastSeenAt` vs `maxRecordTs`.

### Questions

- `matchIfMissing=true` на @ConditionalOnProperty — точно?
- Поведение при `pollInterval >= threshold`?
- Экранирование Markdown в `SimpleTextNotificationTask`?
- Ситуация `maxRecordTs > now` (clock skew)?
- Hysteresis/cooldown для flapping?
