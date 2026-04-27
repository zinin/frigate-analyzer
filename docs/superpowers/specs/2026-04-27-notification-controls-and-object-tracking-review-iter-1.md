# Review Iteration 1 — 2026-04-27

## Источник

- Design: `docs/superpowers/specs/2026-04-27-notification-controls-and-object-tracking-design.md`
- Plan: `docs/superpowers/plans/2026-04-27-notification-controls-and-object-tracking.md`
- Review agents: codex-executor (gpt-5.5, xhigh), gemini-executor, ccs-executor (glm/glm-5.1), ccs-executor (albb-glm/glm-5), ccs-executor (albb-qwen/qwen3.6-plus), ccs-executor (albb-kimi/kimi-k2.5), ccs-executor (albb-minimax/M2.5), ccs-executor (deepseek/V4)
- Merged output: `docs/superpowers/specs/2026-04-27-notification-controls-and-object-tracking-review-merged-iter-1.md`

## Замечания

### C1: Greedy first-match → best-match IoU tracking

> Алгоритм в `ObjectTrackerServiceImpl.evaluateLocked` делает `active.firstOrNull { match }`. При нескольких объектах одного класса возможен cross-match (bbox1 захватывает track2, bbox2 захватывает track1). Оба считаются matched → ложное подавление.

**Источник:** codex-executor, gemini-executor, albb-qwen, deepseek
**Статус:** Автоисправлено
**Ответ:** Заменён `firstOrNull` на `maxByOrNull` с вычислением IoU: фильтруем по классу и порогу, берём трек с максимальным IoU. Комментарий о best-match добавлен.
**Действие:** Design: алгоритм в секции 3 заменён на best-match. Plan: Task 8 реализация заменена, старая `matches` extension function удалена.

---

### C2: `@Transactional` ДО `Mutex.withLock` — исчерпание connection pool

> Транзакция открывается Spring proxy ДО входа в `evaluate()`, затем корутина ждёт `mutex.withLock`. При N одновременных записей одной камеры все N соединений из пула держатся, пока ждут мьютекс.

**Источник:** gemini-executor, ccs-glm
**Статус:** Автоисправлено
**Ответ:** Убран `@Transactional` с `evaluate()`. Транзакция теперь открывается через `TransactionalOperator.executeAndAwait` ВНУТРИ `withLock`. Это гарантирует, что connection из пула берётся только после захвата мьютекса.
**Действие:** Design: секция Error Handling обновлена. Plan: Task 8 — добавлен `TransactionalOperator` в конструктор, логика обёрнута в `transactionalOperator.executeAndAwait`.

---

### C3: Fail-safe "do not notify" → "notify anyway" (fail-open)

> При ошибке трекера (БД недоступна) все уведомления молча подавляются. Для системы безопасности это недопустимо — пользователь не узнает о реальном нарушителе.

**Источник:** gemini-executor, minimax
**Статус:** Автоисправлено
**Ответ:** Изменено на fail-open: при `TRACKER_ERROR` возвращается `shouldNotify = true`. Система деградирует к pre-tracker поведению (каждая запись с детекциями уведомляет).
**Действие:** Design: секция Error Handling обновлена. Plan: Task 10 — реализация и тест обновлены (assertTrue вместо assertFalse).

---

### C4: Противоречивый тест в Task 8

> Тест `findActive uses now minus TTL` вызывает `service.evaluate(rec(), emptyList())` и ожидает вызова `repo.findActive`, но реализация делает early return на пустых детекциях.

**Источник:** codex-executor
**Статус:** Автоисправлено
**Ответ:** Тест исправлен — теперь передаёт одну детекцию (`det("car", ...)`) вместо `emptyList()`.
**Действие:** Plan: Task 8 — тест `findActive uses now minus TTL` обновлён.

---

### C5: `@Scheduled(fixedDelayString)` формат

> `@Scheduled(fixedDelayString = "${...:PT1H}")` с YAML значением `1h` — неоднозначный формат. Spring `@Scheduled` ожидает миллисекунды или ISO-8601 Duration.

**Источник:** ccs-glm
**Статус:** Автоисправлено
**Ответ:** Заменён на `@Scheduled(fixedDelay = 3_600_000)` (миллисекунды, константа) — убирает зависимость от парсинга property placeholder. YAML значения переведены на ISO-8601 формат (`PT2M`, `PT1H`) для `@ConfigurationProperties`.
**Действие:** Plan: Task 11 — `fixedDelayString` заменён на `fixedDelay`. Task 20 — YAML defaults в ISO-8601. `.env.example` обновлён.

---

### C6: Confidence floor и NaN-защита в `BboxClusteringHelper`

> Low-confidence детекции (0.05) расширяют union bbox кластера, потенциально сливая два разных объекта. Плюс при `weightSum = 0` деление даст NaN.

**Источник:** deepseek, ccs-glm
**Статус:** Автоисправлено
**Ответ:** Добавлен `confidenceFloor: Float = 0.3f` в `ObjectTrackerProperties`. `BboxClusteringHelper.cluster` фильтрует детекции по `confidence >= confidenceFloor`. Добавлен guard от NaN: если `weightSum <= 0f`, используется union bbox вместо weighted average.
**Действие:** Plan: Task 6 — `cluster()` принимает `confidenceFloor`. Task 7 — `ObjectTrackerProperties` с полем `confidenceFloor`. Task 8 — вызов `cluster` передаёт `properties.confidenceFloor`.

---

### C7: Telegram build.gradle.kts зависимость

> `TelegramNotificationServiceImpl` импортирует `AppSettingsService` из `service/` модуля. Нужно убедиться, что `telegram/build.gradle.kts` содержит `implementation(project(":frigate-analyzer-service"))`.

**Источник:** codex-executor
**Статус:** Автоисправлено
**Ответ:** Добавлен Step 0 в Task 13 — верификация зависимости перед реализацией. Модульная цепочка `core → telegram → service` подразумевает, что зависимость уже есть.
**Действие:** Plan: Task 13 — добавлен Step 0.

---

### C8: Global OFF псевдокод — порядок вызовов

> Спек говорит "tracker still runs to keep state coherent", но псевдокод в секции 4 делает `return` ДО вызова трекера при `globalEnabled=false`.

**Источник:** albb-qwen, deepseek
**Статус:** Автоисправлено
**Ответ:** Псевдокод в плане (Task 10) исправлен: трекер вызывается безусловно ПЕРЕД проверкой `globalEnabled`. Комментарий добавлен.
**Действие:** Plan: Task 10 — порядок `tracker.evaluate()` перед `when { !globalEnabled -> ... }`.

---

### C9: `isNew() = true` в entity

> `ObjectTrackEntity.isNew()` и `AppSettingEntity.isNew()` всегда возвращают `true`. Spring Data `save()` будет делать INSERT → `UNIQUE VIOLATION` по PK.

**Источник:** albb-kimi, albb-qwen, albb-glm
**Статус:** Отклонено
**Ответ:** Это стандартный паттерн проекта для всех Persistable-entity. `save()` используется только для новых записей в `ObjectTrackerServiceImpl` (с предварительно сгенерированным UUID). `AppSettingEntity` использует кастомный `upsert()`, а не `save()`. Добавлен коммент в `AppSettingEntity`.
**Действие:** Без изменений.

---

### C10: `ConcurrentHashMap<String, Mutex>` без эвикции

> Мапа perCameraMutex растёт неограниченно — ключи никогда не удаляются.

**Источник:** gemini-executor, albb-glm, albb-kimi, deepseek
**Статус:** Отклонено (с документированием)
**Ответ:** Количество камер фиксировано (2-10). Это не утечка памяти на практике. Добавлен комментарий в коде. Если проект перейдёт на динамические камеры — нужно будет добавить эвикцию.
**Действие:** Plan: Task 8 — добавлен документирующий комментарий.

---

### C11: `runBlocking` в `@Scheduled`

> `runBlocking { tracker.cleanupExpired() }` блокирует поток scheduled-pool.

**Источник:** albb-kimi, albb-glm, deepseek
**Статус:** Отклонено (accept tech debt)
**Ответ:** Для простого DELETE это безопасно (выполняется <100ms раз в час). При усложнении cleanup в будущем — перейти на `CoroutineScope`. Задокументировано как known limitation.
**Действие:** Без изменений в коде. Добавлен комментарий "acceptable for single-DELETE, revisit if cleanup becomes complex".

---

### S1: IoU=0.3 для мелких объектов

> Для удалённых объектов (5% кадра) тот же абсолютный сдвиг даёт IoU ≈ 0 — новый трек при каждом сдвиге.

**Источник:** deepseek, albb-kimi
**Статус:** Задокументировано
**Ответ:** Это known limitation текущего подхода. Адаптивный порог (scale-aware IoU) — потенциальное улучшение в будущем. Добавлена заметка в дизайн.
**Действие:** Без изменений.

---

### S2: `ObjectTrackerProperties` не в плане

> `ObjectTrackerProperties` объявлен в File Map, но нет task для его создания.

**Источник:** albb-qwen
**Статус:** Отклонено (false positive)
**Ответ:** Task 7 называется "ObjectTrackerProperties (Spring config)" и содержит полный код. Рецензент пропустил этот task.
**Действие:** Без изменений.

---

## Изменения в документах

| Файл | Изменение |
|------|-----------|
| `design.md` | Секция 3 (Algorithm): best-match вместо firstOrNull |
| `design.md` | Секция Error Handling: fail-closed → fail-open, @Transactional уточнение |
| `plan.md` Task 8 | @Transactional → TransactionalOperator внутри withLock |
| `plan.md` Task 8 | best-match алгоритм, удалена matches() extension |
| `plan.md` Task 8 | Добавлен импорт TransactionalOperator |
| `plan.md` Task 8 | Исправлен противоречивый тест (emptyList → det) |
| `plan.md` Task 10 | fail-open: shouldNotify=true на TRACKER_ERROR |
| `plan.md` Task 10 | Тест обновлён: assertTrue вместо assertFalse |
| `plan.md` Task 10 | Порядок: tracker.evaluate() перед проверкой globalEnabled |
| `plan.md` Task 11 | @Scheduled fixedDelayString → fixedDelay |
| `plan.md` Task 7 | Добавлен confidenceFloor в ObjectTrackerProperties |
| `plan.md` Task 6 | confidenceFloor фильтр, NaN guard |
| `plan.md` Task 8 | cluster() вызов с confidenceFloor |
| `plan.md` Task 13 | Step 0: проверка build.gradle.kts зависимости |
| `plan.md` Task 20 | YAML: 120s→PT2M, 1h→PT1H |
| `plan.md` Task 20 | .env.example: ISO-8601 формат |

## Статистика

- Всего замечаний: 12 (сгруппированы из ~27 исходных от 8 агентов)
- Автоисправлено: 8
- Отклонено (false positive / known pattern): 3
- Задокументировано без изменений кода: 1
- Агенты: codex-executor, gemini-executor, ccs-executor (6 profiles)
