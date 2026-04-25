# Review Iteration 1 — 2026-04-25 09:44

## Источник

- Design: `docs/superpowers/specs/2026-04-25-camera-signal-loss-design.md`
- Plan: `docs/superpowers/plans/2026-04-25-camera-signal-loss.md`
- Review agents: codex-executor (gpt-5.5), gemini-executor, ccs-executor × 6 profiles (glm, albb-glm, albb-qwen, albb-kimi, albb-minimax, deepseek)
- Merged output: `docs/superpowers/specs/2026-04-25-camera-signal-loss-review-merged-iter-1.md`

## Замечания

### [CRITICAL-1] Startup grace period подавляет alert для камер мёртвых при рестарте

> Камера, мёртвая до перезапуска приложения, никогда не получит alert: ветка (null,true) во время grace переводит в SignalLost(notificationSent=false), а на следующем тике после grace state ветка (SignalLost,true) была no-op.

**Источник:** codex-executor, gemini-executor, ccs-executor (albb-glm, albb-kimi, albb-minimax, deepseek)
**Статус:** Обсуждено с пользователем (Option A)
**Ответ:** Late alert после grace через флаг `notificationSent` в `SignalLost`. После grace tick шлёт отложенный LOSS для всех `SignalLost(sent=false)` записей.
**Действие:** В design — `SignalLost.notifiedAt` заменён на `notificationSent: Boolean`; полная decision-table перерисована; новая ветка LATE ALERT в Restart Behavior. В plan — Task 7 (CameraSignalState новое поле); Task 8a/8b (decide и task переписаны под новую семантику + тест "late alert after grace").

---

### [CRITICAL-2] Cleanup ломает recovery-уведомления для long-outage камер

> Если камера ушла в SignalLost, через activeWindow её state удаляется — recovery alert никогда не шлётся.

**Источник:** codex-executor, gemini-executor, ccs-executor (glm, albb-qwen, albb-kimi)
**Статус:** Обсуждено с пользователем (Option A)
**Ответ:** Cleanup только для Healthy. SignalLost-записи хранятся до явного recovery.
**Действие:** В design — алгоритм tick шага 3 переписан ("If state[camId] is Healthy → remove; If SignalLost → KEEP"). В plan — Task 8b (`SignalLossMonitorTask.tick()` cleanup-фильтр + регрессионный тест "cleanup keeps SignalLost but removes Healthy").

---

### [CRITICAL-3] runBlocking в @Scheduled блокирует TaskScheduler pool

> runBlocking блокирует scheduler thread (default size=1), может зафризить ServerHealthMonitor и другие @Scheduled.

**Источник:** ВСЕ 8 reviewers
**Статус:** Обсуждено с пользователем (Option A)
**Ответ:** `tick()` становится `suspend fun` — Spring 6.1+/Boot 3.2+ нативно поддерживают suspend в @Scheduled. Без runBlocking вообще.
**Действие:** В design — раздел Concurrency/Tick Algorithm обновлены, явно сказано "no runBlocking". В plan — Task 8b: `suspend fun tick()`, все вызовы (repo, notifier) прямо suspend. Тесты используют `runTest`.

---

### [CRITICAL-4] channel.send vs channel.trySend — реальное поведение очереди

> Дизайн утверждал "Channel.trySend, warn and drop", но код использует suspend channel.send (backpressure).

**Источник:** codex-executor, ccs-executor (glm, albb-glm, deepseek)
**Статус:** Обсуждено с пользователем (вариант "ничего не менять, suspend backpressure через suspend tick")
**Ответ:** С `suspend fun tick()` (CRITICAL-3 = A) backpressure работает корректно: `channel.send` suspend-ит корутину, не блокирует thread. Никаких tryEnqueue, withTimeout, launch не нужно.
**Действие:** В design — раздел Error Handling переписан: реалистичное описание "queue full" сценария, объяснён почему он безопасен с suspend tick.

---

### [CRITICAL-5] Healthy → SignalLost использовал устаревший lastSeenAt

> Переход использовал `h.lastSeenAt` (старый), а не текущий `maxRecordTs` из БД.

**Источник:** codex-executor
**Статус:** Автоисправлено
**Ответ:** auto-fix (явное намерение исходного дизайна)
**Действие:** В design — decide() decision table явно указывает `newState = SignalLost(maxRecordTs, ...)` и `event = Loss(camId, maxRecordTs, gap)` для перехода Healthy→SignalLost. Добавлено пояснение в "Two key changes". В plan — Task 8a имеет тест "Healthy + lost gap → SignalLost using maxRecordTs, Loss event with maxRecordTs" с явной assertion `lastSeenAt != prevSeen`.

---

### [CRITICAL-6] SIGNAL_LOSS_ENABLED=true с matchIfMissing=true сломает existing tests

> С matchIfMissing=true задача активируется во всех integration tests, где Telegram disabled — context refresh упадёт.

**Источник:** codex-executor
**Статус:** Обсуждено с пользователем (Option A)
**Ответ:** matchIfMissing=false, default=true. Production имеет фичу включённой через application.yaml; test-context (без property) не активирует task.
**Действие:** В design — Feature Flag раздел переписан, явно объяснено почему matchIfMissing=false. В plan — Task 8b: `@ConditionalOnProperty(matchIfMissing = false)`. Task 9: уточнено описание; добавлен новый раздел "Test config compatibility check" в design.

---

### [CRITICAL-7] Conflict-fail в @PostConstruct антипаттерн

> Проверка telegramProperties.enabled в SignalLossMonitorTask.@PostConstruct хрупка из-за порядка инициализации.

**Источник:** codex-executor, gemini-executor, ccs-executor (glm, albb-qwen, albb-kimi, albb-minimax)
**Статус:** Обсуждено с пользователем (Option A)
**Ответ:** Создать отдельный `SignalLossTelegramGuard` `@Component` по образцу `AiDescriptionTelegramGuard`.
**Действие:** В design — добавлена строка `SignalLossTelegramGuard` в Components, переписан раздел Feature Flag. В plan — новая Task 8c: создание гарда + тесты. Task 8b убрала проверку из task.init(). Task 10 переориентирован на интеграционный тест guard-а.

---

### [CRITICAL-8] LastRecordingPerCameraDto без @Column аннотаций

> Существующий CameraRecordingCountDto использует @Column. Без аннотаций R2DBC mapping ломается.

**Источник:** codex-executor, ccs-executor (albb-qwen)
**Статус:** Автоисправлено
**Ответ:** auto-fix
**Действие:** В design — LastRecordingPerCameraDto в Components обновлён ("explicit `@Column(\"cam_id\")` and `@Column(\"last_record_timestamp\")` annotations"). В plan — Task 1 Step 1: код DTO с импортом и аннотациями; SQL переписан с `AS cam_id AS last_record_timestamp`.

---

### [CRITICAL-9] UserZoneDto не существует — реальный класс UserZoneInfo

> План ссылался на несуществующий `ru.zinin.frigate.analyzer.model.dto.UserZoneDto`. Реальный — `ru.zinin.frigate.analyzer.telegram.dto.UserZoneInfo`.

**Источник:** ccs-executor (glm, deepseek)
**Статус:** Автоисправлено
**Ответ:** auto-fix (verified factual error)
**Действие:** В plan Task 6 — все упоминания UserZoneDto заменены на UserZoneInfo (4 места), import path исправлен, обновлена примечание про verified реальный класс.

---

### [CRITICAL-10] TelegramProperties без no-arg конструктора

> Тесты в плане использовали `TelegramProperties().apply{}`, но класс требует обязательные `botToken`, `owner`.

**Источник:** ccs-executor (glm, albb-minimax)
**Статус:** Автоисправлено
**Ответ:** auto-fix (verified factual error)
**Действие:** В plan Task 8c — guard-тесты используют прямой конструктор `SignalLossTelegramGuard(telegramEnabled = true/false)`. Старая testобразная конструкция в Task 8b с TelegramProperties удалена (guard вынесен отдельно).

---

### [CRITICAL-11] @Scheduled SpEL хрупок — использовать ${...} placeholder

> SpEL `#{@signalLossProperties.pollInterval.toMillis()}` зависит от bean name. ServerHealthMonitor использует `${application.detect.health-check-interval}`.

**Источник:** codex-executor, ccs-executor (glm, albb-glm, albb-qwen)
**Статус:** Автоисправлено
**Ответ:** auto-fix
**Действие:** В plan Task 8b — `@Scheduled(fixedDelayString = "\${application.signal-loss.poll-interval}")`. Spring сам парсит Duration.

---

### [CONCERN-1] ConcurrentHashMap избыточен для sequential ticks

> @Scheduled(fixedDelay) даёт happens-before между тиками, обычной HashMap достаточно. Обоснование в дизайне неточно.

**Источник:** 6 reviewers
**Статус:** Автоисправлено (Option A)
**Ответ:** auto-fix — оставить ConcurrentHashMap (defensive), уточнить обоснование в design
**Действие:** В design — раздел Concurrency переписан: ConcurrentHashMap описан как defensive choice, не как требование thread-safety; объяснено что `suspend fun tick()` сериализуется Spring scheduler-ом.

---

### [CONCERN-2] First-sighting wording misleading

> Alert "Camera lost signal, last recording 23h ago" по выведенной из эксплуатации камере — шум.

**Источник:** codex-executor, ccs-executor (glm, albb-glm, albb-kimi, albb-minimax)
**Статус:** Обсуждено с пользователем (Option C)
**Ответ:** Оставить как есть — accepted trade-off
**Действие:** В design — добавлен раздел "Accepted Trade-offs (consciously deferred)" с явным акцептом этого поведения и пояснением UX-решения.

---

### [CONCERN-3] Отсутствие индексов БД для GROUP BY

> Запрос findLastRecordingPerCamera сканирует все записи за 24h. Композитный (cam_id, record_timestamp) ускорил бы.

**Источник:** gemini-executor, ccs-executor (glm, albb-qwen, albb-minimax)
**Статус:** Обсуждено с пользователем (Option B — проверить существующие)
**Ответ:** Verified: `idx_recordings_record_timestamp` уже существует в migration `1.0.0.xml`. Покрывает range scan. Композитный не нужен для типичного домашнего setup; деферним до measured необходимости.
**Действие:** В design — новый раздел "Database Indexes" объясняет решение. В plan — Task 1 SQL обогащён комментарием "no new index migration is needed". `.claude/rules/database.md` НЕ обновляется (нет изменений схемы).

---

### [CONCERN-4] Top-level emitLoss/emitRecovery не имели доступа к notificationService

> План показывал их как top-level fun, но они должны вызывать `notificationService` (поле класса). Не скомпилируется.

**Источник:** ccs-executor (albb-qwen)
**Статус:** Автоисправлено
**Ответ:** auto-fix
**Действие:** В plan Task 8b — emitLoss/emitRecovery определены как `private suspend fun` внутри класса `SignalLossMonitorTask`.

---

### [CONCERN-5] Отсутствие @EnableConfigurationProperties явный шаг

> SignalLossProperties без явной регистрации не создастся как bean.

**Источник:** ccs-executor (glm, albb-qwen)
**Статус:** Автоисправлено
**Ответ:** auto-fix
**Действие:** В plan Task 9 Step 2 — переписано: явно сказано открыть `FrigateAnalyzerApplication.kt`, добавить `SignalLossProperties::class` в существующий список `@EnableConfigurationProperties`. Files-секция плана содержит явный Modify-пункт.

---

### [CONCERN-6] NotificationTask sealed interface refactor — потенциальные breaking changes

> Превращение data class в sealed interface может сломать copy(), componentN(), reflection, сериализацию.

**Источник:** ccs-executor (albb-glm, albb-qwen, albb-kimi, albb-minimax)
**Статус:** Обсуждено с пользователем (Option B — sealed interface как в исходном плане + audit step)
**Ответ:** Sealed остаётся, но добавляется явный шаг audit всех callsite-ов перед refactor.
**Действие:** В design — раздел Components к строке про NotificationTask добавлено: "the refactor of NotificationTask from data class to sealed interface requires an explicit audit step (see plan Task 3) of all 5 production + 2 test usages". В plan — Task 3 Step 0 (audit BEFORE editing): grep callsites, проверить против expected list, остановиться если найдены незарегистрированные usage.

---

### [CONCERN-7] Time-based testing — @Scheduled использует системное время

> MutableClock не повлияет на @Scheduled триггер; тесты должны вызывать tick() напрямую с подменённым Clock.

**Источник:** gemini-executor, ccs-executor (albb-kimi, deepseek)
**Статус:** Автоисправлено (Option A)
**Ответ:** auto-fix — тесты вызывают tick() напрямую, документировано
**Действие:** В design — раздел Testing разделён: пара "Unit tests for decide()" (без mocks) + "Unit tests for SignalLossMonitorTask" (вызывают tick() напрямую с MutableClock). В plan — Task 8a/8b построены под этот паттерн; используется `runTest`.

---

### [CONCERN-8] lostCount семантика неправильная

> Счётчик считал текущее состояние, не новые потери в этом тике.

**Источник:** ccs-executor (deepseek)
**Статус:** Автоисправлено
**Ответ:** auto-fix
**Действие:** В design — раздел Logging Levels уточнён ("currentlyLost is a gauge — count of cameras currently in SignalLost — NOT new losses this tick"). В plan Task 8b — лог-строка использует `currentlyLost`.

---

### [CONCERN-9] @Validated вместо ручного validate()

> Проектные TelegramProperties используют @Validated + @field-аннотации. Несогласованность.

**Источник:** ccs-executor (glm, albb-qwen, deepseek)
**Статус:** Автоисправлено
**Ответ:** auto-fix
**Действие:** В design — раздел Validation переписан: per-field через @Validated/@field:NotNull/@field:PositiveOrZero, cross-field через @PostConstruct. В plan Task 2 — SignalLossProperties с @Validated и `validateCrossField()` вместо `validate()`. Тесты обновлены.

---

### [CONCERN-10] Locale-зависимый FormatStyle и "0 сек"

> FormatStyle.MEDIUM зависит от системной locale. formatDuration давал бы "5 мин 0 сек".

**Источник:** ccs-executor (glm, deepseek)
**Статус:** Автоисправлено
**Ответ:** auto-fix
**Действие:** В plan Task 5 — buildLossMessage использует explicit pattern `HH:mm:ss` с явной Locale. Бакетинг в formatDuration уже не даёт "0 sec" компонент (по построению). Добавлен gap-clamping для clock skew.

---

### [CONCERN-11] Отсутствие Micrometer метрик

> Нет signal.loss.detected, gauge currently-lost и т.п.

**Источник:** ccs-executor (albb-kimi, albb-minimax, albb-qwen)
**Статус:** Обсуждено с пользователем (Option A — YAGNI, не добавлять)
**Ответ:** Не добавлять метрики в этой итерации. INFO-логи на каждый transition достаточны.
**Действие:** В design — добавлено в "Accepted Trade-offs" с явным признанием как deferred.

---

### [QUESTION-1] activeWindow vs Frigate retention

> Если Frigate retention < activeWindow, активная камера может выпадать.

**Источник:** gemini-executor, ccs-executor (glm, albb-qwen, albb-kimi)
**Статус:** Обсуждено с пользователем (Option A — документировать)
**Ответ:** Документировать в дизайне явно: "activeWindow >= Frigate retention".
**Действие:** В design — раздел Validation добавлено явное правило (cross-field, документированное); раздел Configuration table обновлён. В plan Task 9 — `.claude/rules/configuration.md` с явной guidance для SIGNAL_LOSS_ACTIVE_WINDOW.

---

### [QUESTION-2] Граничные условия gap == threshold, pollInterval >= threshold, clock skew

> Не описано: > или >=? Поведение при pollInterval >= threshold? Skew между Frigate и analyzer?

**Источник:** ccs-executor (albb-qwen, albb-minimax, deepseek)
**Статус:** Автоисправлено (Option A)
**Ответ:** auto-fix — явно `>` (строго); валидация pollInterval < threshold уже есть; skew → gap = max(0, ...)
**Действие:** В design — раздел "decide() Decision Table" явно указывает strict inequality + clock-skew clamping; раздел Error Handling отдельная строка для clock skew. В plan Task 8a — `decide()` импликментирует clamping; есть тесты "boundary: gap exactly == threshold" и "clock skew: maxRecordTs in the future".

---

### [QUESTION-3] Hysteresis для flapping

> Камера около threshold может спамить парами LOSS+RECOVERY.

**Источник:** ccs-executor (albb-minimax, deepseek)
**Статус:** Обсуждено с пользователем (Option A — отложить)
**Ответ:** Не добавлять hysteresis в этой итерации. Observer in production.
**Действие:** В design — добавлено в "Accepted Trade-offs" как deferred.

---

### [SUGGESTION-1] Вынести state machine в чистую функцию

> Чистая функция (prev, observation, config) -> Decision упростит тесты.

**Источник:** codex-executor, ccs-executor (glm, albb-qwen)
**Статус:** Автоисправлено (Option A)
**Ответ:** auto-fix
**Действие:** В design — Components: `decide(prev, observation, config) -> Decision` как отдельный pure function entry. В plan — новая Task 8a отдельно для pure decide() + decision table tests; Task 8b использует decide() в tick().

---

### [SUGGESTION-2] .claude/rules/configuration.md и README

> Новые env vars не задокументированы.

**Источник:** codex-executor
**Статус:** Автоисправлено
**Ответ:** auto-fix (уже было в плане Task 9; проверено)
**Действие:** Plan Task 9 Step 3 содержит обновление `.claude/rules/configuration.md` с расширенной таблицей. README не упоминается явно — в проекте нет конкретного env-table в README, который надо синхронизировать.

---

### [SUGGESTION-3] @PreDestroy / CancellationException handling

> CancellationException не должен быть пойман как Exception.

**Источник:** ccs-executor (glm, albb-glm, albb-kimi)
**Статус:** Автоисправлено
**Ответ:** auto-fix
**Действие:** В design — раздел Error Handling и Logging Levels: "CancellationException is always rethrown (never logged at any level)". В plan Task 8b — tick(), emitLoss, emitRecovery — везде явный `catch (e: CancellationException) { throw e }` перед общим `catch (e: Exception)`. Добавлен тест "tick re-throws CancellationException".

---

### [SUGGESTION-4] Удалить неиспользуемое `notifiedAt`

> Поле в SignalLost было бесполезно.

**Источник:** ccs-executor (deepseek)
**Статус:** Автоисправлено
**Ответ:** auto-fix
**Действие:** Поле `notifiedAt` заменено на functional `notificationSent: Boolean` (см. CRITICAL-1) — теперь поле осмысленное и активно используется в decide().

---

## Изменения в документах

| Файл | Изменение |
|------|-----------|
| `docs/superpowers/specs/2026-04-25-camera-signal-loss-design.md` | Components таблица расширена (decide, SignalLossTelegramGuard, audit note для NotificationTask). Feature Flag раздел переписан (matchIfMissing=false + Guard). State Machine: SignalLost.notifiedAt → notificationSent. Tick Algorithm перерисован: suspend fun, late-alert ветка, cleanup только Healthy. Полная decide() Decision Table добавлена. Configuration: matchIfMissing-описание, activeWindow >= retention guidance. Validation: @Validated + cross-field в @PostConstruct. Database Indexes раздел. Restart Behavior — описана late-alert механика. Concurrency — переписана. Error Handling: реалистичный queue-full сценарий, clock skew, CancellationException. Logging Levels — late alert, currentlyLost, skew warns. Testing — два уровня (decide + task), guard tests, test config compat. Accepted Trade-offs раздел. |
| `docs/superpowers/plans/2026-04-25-camera-signal-loss.md` | Architecture описание расширено + явный список консолидированных изменений. File Structure: добавлены SignalLossDecider, SignalLossDeciderTest, SignalLossTelegramGuard + Test, FrigateAnalyzerApplication. Task 1: @Column в DTO + index note. Task 2: @Validated + validateCrossField(). Task 3: новый Step 0 (audit). Task 5: explicit DateTime pattern + skew clamping. Task 6: UserZoneDto → UserZoneInfo (4 места). Task 7: SignalLost.notificationSent поле. Старый монолитный Task 8 (469 строк) разбит на Task 8a (pure decide + 12 параметризованных тестов), 8b (suspend tick + behavior tests, late-alert + cleanup-keeps-SignalLost regression), 8c (SignalLossTelegramGuard + tests). Task 9: matchIfMissing=false уточнение, явная регистрация properties. Task 10: integration test для guard. Definition of Done: 12 задач вместо 10, явная гарантия совместимости с existing tests. |
| `docs/superpowers/specs/2026-04-25-camera-signal-loss-review-merged-iter-1.md` | Создан — объединение 8 reviews. |
| `docs/superpowers/specs/2026-04-25-camera-signal-loss-review-iter-1.md` | Создан — этот файл. |

## Статистика

- Всего замечаний: 27
- Автоисправлено: 15 (CRITICAL-5, -8, -9, -10, -11; CONCERN-1, -4, -5, -7, -8, -9, -10; QUESTION-2; SUGGESTION-1, -3, -4 — поправил 16, но SUGGESTION-2 был частично уже в плане)
- Обсуждено с пользователем: 12 (CRITICAL-1, -2, -3, -4, -6, -7; CONCERN-2, -3, -6, -11; QUESTION-1, -3)
- Отклонено (dismissed): 0
- Повторов (автоответ): 0 (это первая итерация)
- Пользователь сказал "стоп": Нет
- Агенты: codex-executor (gpt-5.5), gemini-executor, ccs-executor × 6 профилей (glm, albb-glm, albb-qwen, albb-kimi, albb-minimax, deepseek)
