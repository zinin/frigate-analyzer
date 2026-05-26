# Review Iteration 1 — 2026-05-26

## Источник

- Design: `docs/superpowers/specs/2026-05-26-howitworks-diagram-refresh-design.md`
- Plan: `docs/superpowers/plans/2026-05-26-howitworks-diagram-refresh.md`
- Review agents: codex-executor (gpt-5.5, xhigh), ccs-executor (glm), ollama-executor (kimi, deepseek). Ollama-minimax dropped — stalled on README read.
- Merged output: `docs/superpowers/specs/2026-05-26-howitworks-diagram-refresh-review-merged-iter-1.md`

## Замечания

### [Plan/Commits] «exactly two commits ahead of master»

> Plan Task 2 Step 3 expected exactly two commits, but with the design + plan + future review-iter commits already in the branch the count is wrong (was 2 before Task 1; will be ≥4 after).

**Источник:** codex Con9, ccs Q15, kimi C8, deepseek Con7
**Статус:** Автоисправлено
**Ответ:** Reformulated step 3 to: "several commits ahead, exact count depends on review iterations" + check `git diff --stat master..HEAD` shows only README.md as net-changed
**Действие:** `plan.md` Task 2 Step 3 переписан

---

### [Plan/Verify] Mermaid render verification options

> Step 4 listed GitHub preview, VS Code, and mermaid.live as equally valid; reviewers (codex S9, ccs S11) noted mermaid.live and GitHub use different Mermaid versions.

**Источник:** codex S9, ccs S11
**Статус:** Автоисправлено
**Ответ:** GitHub-preview сделан primary (REQUIRED) шагом; добавлены явные проверки bidirectional arrows и subgraph borders; mermaid.live / VS Code — opt-in для быстрой итерации
**Действие:** `plan.md` Task 1 Step 4 переписан

---

### [Plan/Cleanup] `git rm` покрытие

> Task 2 Step 1 удалял только design и plan, но накопленные review-iter и merged файлы тоже под `docs/superpowers/`.

**Источник:** implicit от всех агентов (все упоминали "more than two commits")
**Статус:** Автоисправлено
**Ответ:** Команда расширена до `git rm` всех `2026-05-26-howitworks-diagram-refresh-*` + `review-*` файлов + safety net через `git ls-files docs/superpowers/`
**Действие:** `plan.md` Task 2 Step 1 переписан

---

### [DIAGRAM-REWRITE] Архитектурные ошибки в диаграмме

> 4 агента (codex, ccs, kimi, deepseek) независимо отметили: (1) порядок Visualize/Save/Tracker инвертирован относительно `RecordingProcessingFacade.processAndNotify`; (2) VIS не использует Vision API — фрейм-визуализация локальная (Java2D), Vision API трогает только Export; (3) AI Description триггерится из `telegramNotificationService`, а не из Object Tracker; (4) `<-. label .->` рискованный синтаксис; (5) Watcher не имеет прямого канала к Producers — общаются через DB.

**Источник:** codex C1/C2/C3, ccs C1/C2/C4, kimi C1/C2/C3/C4/C5, deepseek C1/C2
**Статус:** Обсуждено с пользователем
**Ответ:** Выбран Вариант B — Facade + OT отдельно (Recommended). 13 узлов: `A, B, V, P, Q, FAC, DB, OT, SL, BOT, U, EX, AI`. Watcher теперь пишет в DB; Producers полят DB. Facade-узел «Visualize (local) + Save to DB» сидит между Consumers и Object Tracker. OT — gatekeeper между save и Bot. AI-ветка перенесена на Bot. Двунаправленные пунктирные стрелки заменены на пары однонаправленных. Подписи Vision API теперь явно «3 стадии» (extract, detect, video annotate) — frame visualization для уведомлений локальная.
**Действие:** Spec — секция «Соответствие коду» переписана, «Ключевые потоки» переписаны (5 потоков пересмотрены), mermaid-код полностью заменён + добавлена секция «Принятые правки относительно первой версии». Plan — `new_string` в Task 1 Step 2 + Step 4 проверочный список (13 узлов, 7 dotted edges, специфика bidirectional) + commit message в Step 5.

---

### [SCOPE-PARAGRAPH] Устаревший параграф под диаграммой

> Codex C5: параграф «Frame extraction, object detection, and video annotation are performed by an external vision-api-server» не отражает multi-instance LB, two-stage detection, object tracking, signal-loss, AI description; концептуально некогерентно замораживать его в Out of Scope.

**Источник:** codex C5, S4
**Статус:** Обсуждено с пользователем
**Ответ:** Вариант A — оставить как есть. Параграф фактически верен: Vision API именно эти три функции и выполняет. Балансировка/two-stage/tracking — фичи frigate-analyzer, а не Vision API. Codex смешал «что делает Vision API» с «что добавилось в frigate-analyzer». Детали LB в подписи узла на диаграмме и в § Detection Servers — этого достаточно.
**Действие:** Никаких правок (no-op).

---

### Отклонённые замечания (DISMISSED, без действий)

| Замечание | Источник | Причина отклонения |
|---|---|---|
| `<b>` HTML-теги заменить на `**bold**` | ccs Con7 | Работает на GitHub Mermaid v11, не критично |
| `flowchart` вместо `graph TD` | deepseek S8 | Синонимы, нет эффекта на рендер |
| Добавить AiDescriptionTelegramGuard / FirstTimeScanTask / CancelExportHandler | kimi C9, deepseek Q11/Q12 | Слишком низкий уровень для overview-диаграммы |
| Разбить узел DB на recordings/detections vs object_tracks | codex Con1, kimi C6, deepseek C3 | Допустимое упрощение; разбиение перегрузит схему |
| Добавить ffmpeg как отдельный узел | ccs S9 | Покрывается подписью «ffmpeg merge» на стрелке от EX |
| Object Tracker → DB отдельной стрелкой (object_tracks) | codex Q1 | Деталь, покрывается подписью узла OT |
| Standardize IDs: A→FR, B→WATCH | codex S8 | Стилистика, не улучшает clarity |
| Две мини-диаграммы | codex S5 | Отвергнуто в брейнсторминге |
| Вынести EX из subgraph TG | codex Con2 | Handlers в telegram/, исполнитель в core — группировка по handlers разумна |

## Изменения в документах

| Файл | Изменение |
|------|-----------|
| `plan.md` (Task 1 Step 2) | mermaid `new_string` полностью переписан под Вариант B (13 узлов, новая структура потока, dotted edges заменены) |
| `plan.md` (Task 1 Step 4) | GitHub-preview сделан REQUIRED; checklist переписан под актуальный набор узлов + проверки bidirectional `<-->` и dotted edges |
| `plan.md` (Task 1 Step 5) | Commit message переписан, отражает все архитектурные правки |
| `plan.md` (Task 2 Step 1) | `git rm` расширен на review-iter / merged файлы + safety net |
| `plan.md` (Task 2 Step 3) | Ожидаемый результат `git log` переформулирован; добавлен `git diff --stat` для верификации single-file net change |
| `design.md` (§ Соответствие коду) | Таблица узлов пересмотрена под Вариант B; добавлены пояснения куда триггерится AI, как работает Facade |
| `design.md` (§ Ключевые потоки) | 5 потоков переписаны под реальный порядок кода (Watcher→DB→Producers, Facade=visualize+save, OT как gatekeeper, AI из BOT, Vision API в 3 стадиях не 4) |
| `design.md` (§ Mermaid-код) | Mermaid-блок полностью переписан под Вариант B |
| `design.md` | Добавлена секция «Принятые правки относительно первой версии» с переcheckpoint |

## Статистика

- Всего замечаний: ~28 (от 4 ревьюеров)
- Автоисправлено (без обсуждения): 3 (план-правки)
- Авто-применено после анализа: 0
- Обсуждено с пользователем: 2 (DIAGRAM-REWRITE, SCOPE-PARAGRAPH)
- Отклонено: 9 (см. таблицу выше)
- Повторов (автоответ): 0 (первая итерация)
- Пользователь сказал «стоп»: Нет
- Агенты: codex-executor (gpt-5.5, xhigh), ccs-executor (glm), ollama-executor (kimi, deepseek). Ollama-minimax dropped — stalled.
