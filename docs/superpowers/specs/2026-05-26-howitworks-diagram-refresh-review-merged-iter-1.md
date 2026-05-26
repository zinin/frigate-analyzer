# Merged Design Review — Iteration 1

**Дата:** 2026-05-26
**Дизайн:** `docs/superpowers/specs/2026-05-26-howitworks-diagram-refresh-design.md`
**План:** `docs/superpowers/plans/2026-05-26-howitworks-diagram-refresh.md`
**Агенты:** codex-executor (gpt-5.5, xhigh), ccs-executor (glm), ollama-executor (kimi, deepseek). Ollama-minimax dropped — stalled on README read, no findings produced.

---

## codex-executor (gpt-5.5)

### Critical Issues

1. **Диаграмма врёт про порядок «Consumers → Object Tracker → DB».**
   В коде (`RecordingProcessingFacade.processAndNotify`) последовательность другая:
   - `frameVisualizationService.visualizeFrames(...)` — визуализация ПЕРЕД сохранением;
   - `recordingEntityService.saveProcessingResult(request)` — сохранение в БД (recordings + detections);
   - `notificationDecisionService.evaluate(...)` — только тут вызывается `ObjectTrackerService` (через `NotificationDecisionServiceImpl.tracker.evaluate(...)`).
   То есть фактически: `Consumers → Facade → Visualize → DB save → Object Tracker → (decision) → Bot`.
   В диаграмме же показано `Q --> OT --> DB`, `OT --> VIS`, `VIS --> BOT`. Это инвертирует и Visualize→DB, и роль трекера.

2. **Стрелка `VIS <-. "annotate" .-> V` ложна.**
   Визуализация фреймов для уведомлений выполняется **локально** через `LocalVisualizationService` (Java2D), без вызова Vision API. Из четырёх обещанных стадий взаимодействия с Vision API — реально три (Producers extract, Consumers detect, Export annotate).

3. **AI Description триггерится не из Object Tracker.**
   `RecordingProcessingFacade` строит `descriptionSupplier` и передаёт его в `telegramNotificationService.sendRecordingNotification(...)`. Запуск AI-описания выполняет `DescriptionEditJobRunner` в telegram-модуле **после** фильтрации по подписчикам и проверки `DescriptionRateLimiter.tryAcquire()`. Узел AI должен входить со стороны `BOT`, а не от `OT`.

4. **Plan ссылается на «lines 7-21» — на нужной ветке нужно проверить.**
   На `feat/i18n-hardcoded-ru-strings` README.md действительно содержит mermaid-блок на строках 7-21. Однако реализатор должен выполнять задачу на ветке `docs/refresh-howitworks-diagram` от `master`. План об этом не предупреждает. Дополнительно: задача-нулевого-шага «создать/переключиться на правильную ветку» в плане отсутствует.

5. **Out-of-Scope явно фиксирует фразу под диаграммой «как есть» — а она устарела сильнее самой диаграммы.**
   Параграф под диаграммой упоминает только 3 функции Vision API и не отражает multi-server LB, two-stage detection, object tracking, signal-loss, AI description. README — главная точка входа.

### Concerns

1. Подпись «(polls DB)» рядом с `Signal-loss Monitor` соответствует коду, но `SignalLossMonitorTask` читает только `recordings`, не detections/object_tracks. Допустимое упрощение для overview.
2. `subgraph TG` содержит `EX["Export / Annotate jobs"]`, но `VideoExportServiceImpl` живёт в core, не в telegram. Включение `EX` в TG создаёт впечатление, что экспорт — часть telegram-модуля.
3. Mermaid: forward-reference `V` до объявления — формально допустимо, но анти-паттерн.
4. `<-. "label" .->` для bidirectional dotted edge: на текущем GitHub Mermaid v11 работает, но не самый предсказуемый. Шаг Task 1 Step 4 это и проверяет — ок.
5. `subgraph PIPE` с `direction LR` внутри top-level `graph TD` поддерживается, но при множестве внешних стрелок (`P <-> V`, `Q <-> V`, `OT --> VIS`, `OT --> AI`) велик риск перекрещивающихся линий.
6. «Visualize top frames» как отдельный узел — а в коде это шаг внутри Facade.
7. Plan Task 1 Step 4 предлагает «render via mermaid.live» — но mermaid.live использует другой рендерер, чем GitHub.
8. Self-Review не упоминает критерий «двунаправленных стрелок BOT↔U и BOT↔EX рендерятся корректно».
9. Plan Task 2 Step 3 ожидает «exactly two commits ahead of master», но фактически их будет три.

### Suggestions

1. **Исправить семантику основного потока.** Минимальный фикс: `Q --> SAVE["Save (DB)"] --> EVAL["Notification Decision (Object Tracker, IoU)"] --> VIS --> BOT`.
2. **Убрать `VIS <-. "annotate" .-> V`.** Подписать узел `VIS` как «Visualize (local, Java2D)».
3. **Перерисовать AI-ветку.** Корректно: `BOT -. async describe .-> AI`, `AI -. edit message .-> BOT`.
4. **Открыть рамки § Out of Scope для одного абзаца** — обновить параграф под диаграммой.
5. **Подумать об альтернативе «две мини-диаграммы»** для signal-loss + AI-description.
6. **Добавить в план нулевой шаг создания ветки** (с git switch -c / git worktree).
7. **Поправить Self-Review п.5** («two commits» → «three commits»).
8. **Стандартизовать ID узлов** (A → FR, B → WATCH для diff-читабельности).
9. **Сделать GitHub-preview обязательным шагом верификации**, остальные опциональными.

### Questions

1. Хотим ли показать, что Object Tracker записывает `object_tracks` в БД отдельной стрелкой?
2. Согласны ли, что Export — это не часть Telegram-модуля целиком? Выносить `EX` из subgraph TG?
3. Что делать с устаревшим параграфом под диаграммой?
4. Какой Mermaid-рендерер считаем эталонным?
5. «One screen» цель совместима ли с 13-узловой диаграммой на mobile GitHub?

---

## ccs-executor (glm-5.1)

### Critical Issues

1. **Невалидный Mermaid-синтаксис: `<-. "label" .->`** — не отрендерится. В Mermaid нет двунаправленных пунктирных стрелок с подписями. Доступные: `A -. "text" .-> B` (однонаправленная) или `A <--> B` (сплошная без подписи).
2. **Визуализация фреймов НЕ использует Vision API** — `FrameVisualizationService` использует `LocalVisualizationService` (Java2D). `VideoVisualizationService` (для Export) — единственный, кто зовёт Vision API для аннотации.
3. **`direction` внутри subgraph будет проигнорирован** — Mermaid Dagre игнорирует `direction` внутри subgraph если узлы связаны с узлами снаружи. Оба subgraph (PIPE, TG) имеют внешние связи.

### Concerns

4. **Object Tracker НЕ является «привратником» между Consumers и DB/notification.** Реальный поток оркестрируется `RecordingProcessingFacade`. Привратник — `NotificationDecisionService`, в котором tracker — лишь часть.
5. **Ребро `VIS --> BOT` семантически некорректно** — скрывает ключевое звено (NotificationDecision).
6. **Версия Mermaid на GitHub неизвестна** — поддержка `direction` в subgraph появилась в v10, bidirectional `<-->` в v10.9+.
7. **`<b>` HTML-теги в метках узлов хрупко** — работает, но Mermaid v11+ рекомендует Markdown `**bold**`.

### Suggestions

8. **Убрать `VIS` как отдельный узел** — это не сервис, а шаг внутри Facade.
9. **Добавить `ffmpeg` явно** — асимметрия с Vision API.
10. **Таблица `object_tracks` — проверить existence** (по `ObjectTrackEntity` это `object_tracks`, проверено).
11. **Рендерить на mermaid.live первым шагом** — показывает ошибки парсинга немедленно.

### Questions

12. Приоритет: точность архитектуры или читаемость?
13. Почему Signal-loss Monitor показан как `DB → SL → BOT`, а не отдельная ветка?
14. Проверка строк плана: 7-21 — совпадает с фактическим README.
15. Ветка содержит 2 коммита, после Task 1+2 будет 3 (план говорит «two» — неверно).

---

## ollama-executor (kimi-k2.6)

### Critical Issues

1. **Forward reference `B --> P` перед определением `P`** — в оригинале стрелка шла на subgraph (`B --> C`), а не внутрь. Безопасный паттерн: `B --> PIPE`.
2. **Двунаправленные пунктирные стрелки с меткой `<-. "label" .->`** — синтаксис рискован.
3. **Архитектурная ошибка: `OT --> VIS`** — порядок в коде обратный (visualize ПЕРЕД OT).
4. **Архитектурная ошибка: `OT -. "async" .-> AI`** — AI запускается из TelegramNotificationSender, а не OT.
5. **Отсутствует `RecordingProcessingFacade` как оркестратор.**

### Concerns

6. Один узел `DB[("PostgreSQL")]` скрывает разные таблицы (recordings/detections vs object_tracks).
7. Export jobs «video annotate» корректна только для Annotated-режима. Original не ходит в Vision API.
8. План говорит «two commits ahead», но будет 4 (учитывая 2 уже сделанных коммита design + plan, минус 2 удалённых через `git rm`).
9. `FirstTimeScanTask` отсутствует на диаграмме как точка входа.

### Suggestions

A. Добавить узел-оркестратор `FAC["Process & Notify (Facade)"]` между Q и остальными.
B. AI Description привязать к Telegram-уведомлению: `BOT -. "async AI edit" .-> AI`, `AI -. "edit message" .-> BOT`.
C. Безопасный forward-ref: `B --> PIPE`.
D. Заменить двунаправленные `<-. "label" .->` на две однонаправленные.
E. Упомянуть `FirstTimeScanTask` в тексте под диаграммой.

### Questions

1. Проверялся ли Mermaid-код на GitHub preview?
2. Почему AI Description привязан к Object Tracker?
3. Стоит ли добавить `RecordingProcessingFacade` на диаграмму?
4. Нужен ли отдельный узел `NotificationDecision`?

---

## ollama-executor (deepseek-v4-pro)

### Critical Issues

1. **Порядок потока не соответствует коду: `Q → OT → VIS` перепутан.** В коде: визуализация → DB → ObjectTracker → отправка.
2. **`B --> P` — ложная прямая связь Watcher → Producers.** Watcher пишет в БД, Producer независимо опрашивает БД. Корректнее: `B --> DB` и `DB --> P`.
3. **`OT --> DB` неоднозначность таблиц** — ObjectTracker пишет `object_tracks`, а основной поток — `recordings`+`detections`. Единый узел `DB` скрывает различие.

### Concerns

4. Mermaid: `<-."label".->` — риск для GitHub. Документированная форма — `A <-.-> B` без лейбла.
5. Множественные ссылки на `BOT` извне subgraph — стрелки будут пересекать границу, визуальный шум.
6. Декларация узлов после использования (`V` и `BOT` объявлены после использования).
7. Plan: неверный ожидаемый результат `git log` (будет 4 коммита, не 2).

### Suggestions

8. Рассмотреть `flowchart` вместо `graph` — современная замена.
9. Явная связь Watcher → DB → Producers: `A --> B --> DB --> P`.
10. Разорвать `OT → VIS`, заменить на поток через Facade.

### Questions

11. Нужен ли `AiDescriptionTelegramGuard` на диаграмме?
12. Стоит ли показывать `CancelExportHandler`?

---

## Сводка пересечений (issues, замеченные несколькими агентами)

| Issue | codex | ccs | kimi | deepseek |
|-------|-------|-----|------|----------|
| Порядок Visualize/Save/Tracker неверный | C1 | C4 | C3 | C1 |
| VIS не использует Vision API | C2 | C2 | — | — |
| AI триггерится не из OT | C3 | — | C4 | — |
| `<-. "label" .->` синтаксис | Con4 | C1 | C2 | Con4 |
| Forward-ref V/B/P | Con3 | — | C1 | Con6 |
| `direction` в subgraph игнорируется при внешних связях | Con5 | C3 | — | Con5 |
| План: неверное число коммитов | Con9 | Q15 | C8 | Con7 |
| Параграф под диаграммой устарел | C5 | — | — | — |
| Нужен FAC/NotificationDecision узел | S1 | C4 | C5,A | S10 |
| `B --> P` без БД-промежутка | — | — | — | C2 |
| Один DB узел — компромисс | Con1 | — | C6 | C3 |
| ffmpeg не показан | — | S9 | — | — |
| EX в TG subgraph — спорно | Con2 | — | — | — |
