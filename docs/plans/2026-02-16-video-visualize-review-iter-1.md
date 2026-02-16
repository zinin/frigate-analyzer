# Review Iteration 1 — 2026-02-16

## Источник

- Design: `docs/plans/2026-02-16-video-visualize-design.md`
- Plan: `docs/plans/2026-02-16-video-visualize-plan.md`
- Review agents: codex-executor (gpt-5.3-codex), gemini-executor, ccs-executor (glmt)
- Merged output: `docs/plans/2026-02-16-video-visualize-review-merged-iter-1.md`

## Замечания

### [C1] Паттерн управления слотами (Resource Leak Risk)

> submitVideoVisualize возвращает AcquiredServer наружу — риск утечки слотов, двойного release, нарушение инкапсуляции. Предлагают HOF с лямбдой.

**Источник:** codex, gemini, ccs
**Статус:** Новое
**Ответ:** Вариант B — HTTP-методы остаются в DetectService, но принимают AcquiredServer как параметр (не acquire'ят внутри). VideoVisualizationService управляет lifecycle слотов (acquire/release в try/finally).
**Действие:** Обновить design и plan: submitVideoVisualize принимает AcquiredServer, не делает acquireServer/releaseServer. Оркестратор управляет слотами.

---

### [C2] ByteArray download — OOM/timeout риск

> downloadJobResult возвращает ByteArray (полная загрузка в память), глобальные сетевые timeout 30s слишком короткие для видео.

**Источник:** codex, ccs
**Статус:** Новое
**Ответ:** Streaming в файл — downloadJobResult пишет во временный файл и возвращает Path.
**Действие:** Обновить design и plan: downloadJobResult возвращает Path, использует streaming запись.

---

### [C3] Retry policy слишком грубая

> Ретраит все Exception включая невосстановимые (4xx). Удерживает слот без пользы.

**Источник:** codex, ccs
**Статус:** Новое
**Ответ:** Сделать как в остальных методах DetectService по аналогии.
**Действие:** При реализации использовать существующий паттерн retry из DetectService.

---

### [C4] Ломающий rollout конфигурации

> videoVisualizeRequests обязательное поле без дефолта для существующих конфигов.

**Источник:** codex
**Статус:** Новое
**Ответ:** Оставить обязательным. Конфиг внутренний, внешних пользователей нет.
**Действие:** Без изменений.

---

### [N1] Отмена job на сервере при cancel/timeout

> При cancel/timeout слот освобождается, но сервер продолжает обработку (orphan job).

**Источник:** gemini, ccs
**Статус:** Новое
**Ответ:** Логировать orphan job (WARN). Detection server API v2.2.0 не поддерживает DELETE /jobs/{id}.
**Действие:** Добавить логирование в finally блок VideoVisualizationService.

---

### [N2] Slot starvation

> Все серверы заняты долгими видео, новые запросы ждут.

**Источник:** codex, gemini, ccs
**Статус:** Новое
**Ответ:** Принять как есть. simultaneousCount=1, withTimeout ограничивает ожидание.
**Действие:** Без изменений.

---

### [N3] Poll без exponential backoff

> Фиксированный pollInterval = 3s.

**Источник:** ccs
**Статус:** Новое
**Ответ:** Оставить фиксированный. При 1-2 серверах нагрузка минимальна.
**Действие:** Без изменений.

---

### [N4] Design vs plan несогласованность (detect-every)

> detect-every есть в design YAML, но отсутствует в plan YAML.

**Источник:** codex
**Статус:** Новое
**Ответ:** Добавить в plan YAML.
**Действие:** Обновить plan: добавить detect-every в YAML блок.

---

### [N5] Слабая типизация status (String)

> Статус job как String — риск тихих ошибок.

**Источник:** codex, ccs
**Статус:** Новое
**Ответ:** Ввести enum JobStatus.
**Действие:** Обновить design и plan: добавить enum JobStatus, использовать в JobStatusResponse.

---

### [N6] HTTP response validation

> Не описано поведение при non-2xx, malformed JSON.

**Источник:** ccs
**Статус:** Новое
**Ответ:** Принять как есть. WebClient сам бросает исключения. Аналогично существующим методам.
**Действие:** Без изменений.

---

### [N7/N8] retryDelay общий + test coverage

> Общий retryDelay, недостаточный test coverage.

**Источник:** ccs, codex
**Статус:** Новое
**Ответ:** Принять как есть для MVP.
**Действие:** Без изменений.

---

### [S3/S4] Micrometer metrics и Job ID в MDC

> Добавить метрики и MDC для observability.

**Источник:** gemini, ccs
**Статус:** Новое
**Ответ:** Out of scope. Отдельная задача.
**Действие:** Без изменений.

---

### [Q3/Q4] Pipeline интеграция / Destination видео

> Куда идёт видео после скачивания? Pipeline или standalone?

**Источник:** ccs
**Статус:** Новое
**Ответ:** Standalone → Telegram. Видео отправляется пользователю, временный файл удаляется.
**Действие:** Добавить уточнение в design.

---

## Изменения в документах

| Файл | Изменение |
|------|-----------|
| design.md | C1: submitVideoVisualize принимает AcquiredServer |
| design.md | C2: downloadJobResult возвращает Path (streaming) |
| design.md | N1: добавить логирование orphan job |
| design.md | N5: добавить enum JobStatus |
| design.md | Q3/Q4: добавить note о destination |
| plan.md | N4: добавить detect-every в YAML |
| plan.md | Все изменения из design отражены |

## Статистика

- Всего замечаний: 13
- Новых: 13
- Повторов (автоответ): 0
- Пользователь сказал "стоп": Нет
- Агенты: codex-executor (gpt-5.3-codex), gemini-executor, ccs-executor (glmt)
