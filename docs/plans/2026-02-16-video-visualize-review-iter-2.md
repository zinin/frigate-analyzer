# Review Iteration 2 — 2026-02-16

## Источник

- Design: `docs/plans/2026-02-16-video-visualize-design.md`
- Plan: `docs/plans/2026-02-16-video-visualize-plan.md`
- Review agents: codex-executor (gpt-5.3-codex), gemini-executor, ccs-executor (glmt)
- Merged output: `docs/plans/2026-02-16-video-visualize-review-merged-iter-2.md`

## Замечания

### [C5] Противоречивый pseudocode управления слотами в design

> Design одновременно описывает два алгоритма: единичный acquire на весь job (строки 157-166) и acquire ВНУТРИ retry loop (строки 168-181). Plan повторяет это в submitWithRetry. Риск двойного acquire/release.

**Источник:** codex (CI-1), ccs (C9)
**Статус:** Новое
**Ответ:** Исправить pseudocode. Acquire внутри retry loop, release на failure, keep на success.
**Действие:** Обновлены обе секции в design. Slot management показывает финальный try/finally с completed flag. Фаза 1 показывает корректный acquire → try submit → catch release retry.

---

### [C6] Утечка temp-файлов при ошибке download

> downloadJobResult создаёт temp file, но не удаляет при ошибке streaming. Partial .mp4 накапливаются.

**Источник:** codex (CI-3), gemini (Critical #2), ccs (N10)
**Статус:** Новое
**Ответ:** Добавить cleanup — try-catch в downloadJobResult, удалять temp file в catch.
**Действие:** Обновлён design (описание downloadJobResult) и plan (Task 7: single OutputStream + try-catch с Files.deleteIfExists).

---

### [C7] 404 при polling — job потерян после рестарта сервера

> Если detection server перезагрузился, job исчезает, GET /jobs/{id} вернёт 404. Текущий дизайн ретраит ошибки poll бесконечно до timeout (15 мин).

**Источник:** gemini (Critical #1)
**Статус:** Новое
**Ответ:** 404 = терминальная ошибка. Бросать VideoAnnotationFailedException, не ретраить.
**Действие:** Обновлён design (добавлена секция обработки 404 + строка в таблице сценариев). Обновлён plan (Task 8: catch WebClientResponseException.NotFound).

---

### [C8] Orphan job WARN логируется всегда, даже при успехе

> В finally блоке orphan job логируется безусловно, создавая ложные positive предупреждения.

**Источник:** ccs (C10)
**Статус:** Новое
**Ответ:** Добавить флаг completed. Логировать orphan только если !completed && jobId != null.
**Действие:** Обновлён design (pseudocode с var completed = false) и plan (Task 8: completed flag).

---

### [N9] Idempotency: retry submit может создать дубликаты job

> Retry submit на другом сервере без idempotency key — если POST принят, но ответ потерян, второй POST создаст duplicate orphan job.

**Источник:** codex (CI-2)
**Статус:** Новое
**Ответ:** Принять риск. API не поддерживает idempotency key. Риск минимален (1 сервер, редкий сценарий). Orphan jobs логируются.
**Действие:** Добавлен accepted risk note в design (после таблицы сценариев).

---

### [N10] WebClient глобальный timeout (30s) vs бизнес-timeout (15m)

> response-timeout=30s, read-timeout=30s, write-timeout=30s — все глобальные. Для video запросов (upload/download минуты) 30s может не хватить.

**Источник:** codex (concern)
**Статус:** Новое
**Ответ:** Проверить при реализации. При необходимости переопределить timeout на уровне отдельных запросов. Пользователь отметил, что responseTimeout может быть не нужен вообще — отдельная задача.
**Действие:** Без изменений в документах. Проверить при реализации Task 5 и 7.

---

### [N11] File I/O: открытие OutputStream на каждый DataBuffer

> В downloadJobResult открывается новый OutputStream на каждый DataBuffer chunk. Для большого видео — тысячи open/close.

**Источник:** ccs (N6)
**Статус:** Новое
**Ответ:** Открыть OutputStream один раз перед collect, закрыть после.
**Действие:** Обновлён plan (Task 7: Files.newOutputStream вне collect, внутри — только write).

---

### [N12] onProgress exception убьёт job

> Exception в onProgress callback не перехватывается — прерывает весь job.

**Источник:** codex (concern), ccs (Q5)
**Статус:** Новое
**Ответ:** Оборачивать в try-catch. Ошибка callback не должна убивать job.
**Действие:** Обновлён design (pseudocode Фазы 2) и plan (Task 8: try { onProgress } catch { logger.warn }).

---

### [N13] Неверный gradle path в плане

> `:modules-core:test` не существует, правильно `:core:test`.

**Источник:** codex (concern)
**Статус:** Новое
**Ответ:** Исправить.
**Действие:** Заменены все `:modules-core:test` → `:core:test` в plan.

---

### [N14] Docker template не обновлён

> `docker/deploy/application-docker.yaml.example` не содержит video-visualize-requests.

**Источник:** codex (concern)
**Статус:** Новое
**Ответ:** Добавить шаг в Task 3.
**Действие:** Добавлен Step 8 в Task 3: обновить docker template.

---

### [N15] Retry на 4xx (400 Bad Request)

> Существующий retryWithTimeout ловит все Exception включая 400. Бессмысленный retry на невосстановимые ошибки.

**Источник:** gemini (concern #2)
**Статус:** Новое (расширяет C3 из iter-1)
**Ответ:** Следовать существующему паттерну. Как решено в C3 iter-1.
**Действие:** Без изменений.

---

### [N16] JobStatus enum brittleness

> Если detection server добавит новые статусы, десериализация упадёт.

**Источник:** ccs (N8)
**Статус:** Новое
**Ответ:** Принять как есть. API v2.2.0 фиксирован.
**Действие:** Без изменений.

---

### [N17] OpenAPI save task

> В design пункт 8 о сохранении openapi.json, но в плане нет задачи.

**Источник:** codex (concern)
**Статус:** Новое
**Ответ:** Убрать из design. Не нужно для MVP.
**Действие:** Удалён пункт 8 из design.

---

### [N18] ByteArray → Path для input видео

> annotateVideo/submitVideoVisualize принимают ByteArray. Видео собирается из файлов Frigate на диске — лучше Path для streaming upload.

**Источник:** codex (concern) + уточнение от пользователя
**Статус:** Новое
**Ответ:** Path. Streaming upload из файла без загрузки в память.
**Действие:** Обновлены design (сигнатуры submitVideoVisualize и annotateVideo) и plan (Task 5, 8, 9: videoPath вместо bytes/filePath, FileSystemResource вместо ByteArrayResource).

---

### [N19] detectEvery validation

> detectEvery: Int? без валидации — может быть 0 или отрицательным.

**Источник:** ccs (N7)
**Статус:** Новое
**Ответ:** Добавить @Min(1).
**Действие:** Обновлены design и plan (VideoVisualizeConfig: @field:Min(1) для detectEvery).

---

### [N20] imgSize naming inconsistency

> Параметр метода imgSize, query param imgsz.

**Источник:** ccs (C11)
**Статус:** Новое
**Ответ:** Следовать существующей конвенции DetectService.
**Действие:** Без изменений.

---

### [S9] Retry DRY

> retryWithTimeout в DetectService — private. VideoVisualizationService копирует логику.

**Источник:** gemini (concern #1), ccs (S5)
**Статус:** Новое
**Ответ:** Принять для MVP. Рефакторить потом.
**Действие:** Без изменений.

---

### [Q7] jobId: String vs typed wrapper

> Стоит ли ввести value class JobId?

**Источник:** ccs (Q7)
**Статус:** Новое
**Ответ:** Оставить String.
**Действие:** Без изменений.

---

### [S8] Submit retry test

> Нет теста на retry при transient failure на фазе SUBMIT.

**Источник:** ccs (S8)
**Статус:** Новое
**Ответ:** Добавить тест в Task 9.
**Действие:** Добавлен Step 3 в Task 9: тест submit retry.

---

### [Q6] progress bounds

> Как обрабатывать progress > 100 или < 0?

**Источник:** ccs (Q6)
**Статус:** Новое
**Ответ:** Доверять серверу, не валидировать.
**Действие:** Без изменений.

---

### [S10] Fail-fast при отсутствии серверов

> Если серверов с VIDEO_VISUALIZE нет в конфиге, ждать 15мин бессмысленно.

**Источник:** gemini (suggestion #2)
**Статус:** Новое
**Ответ:** LB уже бросает DetectServerUnavailableException сразу.
**Действие:** Без изменений.

---

### [S6] jobId в логах

> Добавить jobId во все логи VideoVisualizationService для tracing.

**Источник:** ccs (S6)
**Статус:** Новое
**Ответ:** Добавить при реализации.
**Действие:** Без изменений в документах. Учесть при реализации Task 8.

---

## Изменения в документах

| Файл | Изменение |
|------|-----------|
| design.md | C5: Исправлен pseudocode slot management (корректный retry pattern) |
| design.md | C6: Добавлено описание temp file cleanup в downloadJobResult |
| design.md | C7: Добавлена обработка 404 при polling + строка в таблице сценариев |
| design.md | C8: Pseudocode с completed flag для orphan logging |
| design.md | N9: Добавлен accepted risk note (idempotency) |
| design.md | N17: Удалён пункт 8 (OpenAPI spec) |
| design.md | N18: ByteArray → Path для submitVideoVisualize и annotateVideo |
| design.md | N19: @Min(1) для detectEvery |
| plan.md | N13: Все `:modules-core:test` → `:core:test` |
| plan.md | N14: Добавлен Step 8 в Task 3 (docker template) |
| plan.md | N11: Task 7 — single OutputStream + try-catch cleanup |
| plan.md | N18: Task 5, 8, 9 — videoPath вместо bytes/filePath |
| plan.md | C7, C8, N12: Task 8 — 404 handling, completed flag, onProgress wrapping |
| plan.md | N19: Task 2 — @Min(1) для detectEvery |
| plan.md | S8: Task 9 — добавлен submit retry test |

## Статистика

- Всего замечаний: 22
- Новых: 22
- Повторов (автоответ): 0
- Пользователь сказал "стоп": Нет
- Агенты: codex-executor (gpt-5.3-codex), gemini-executor, ccs-executor (glmt)
