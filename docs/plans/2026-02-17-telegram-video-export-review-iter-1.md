# Review Iteration 1 — 2026-02-17 21:20

## Источник

- Design: `docs/plans/2026-02-17-telegram-video-export-design.md`
- Plan: `docs/plans/2026-02-17-telegram-video-export-plan.md`
- Review agents: codex-executor (gpt-5.3-codex), gemini-executor (gemini-3-pro-preview), ccs-executor (glm-4.7)
- Merged output: `docs/plans/2026-02-17-telegram-video-export-review-merged-iter-1.md`

## Замечания

### #1 Фильтрация waiters не привязана к chatId/userId

> Параллельные `/export` могут перехватить чужие callback/text сообщения. `waitDataCallbackQuery(filter = { it.data.startsWith("export:") })` — глобальный фильтр.

**Источник:** Codex, Gemini, CCS
**Статус:** Новое
**Ответ:** Добавить chatId в фильтр — `it.message?.chat?.id == chatId` во все waitDataCallbackQuery/waitText
**Действие:** Обновить Task 6 — добавить chatId фильтрацию во все waiters

---

### #2 SQL не учитывает overlapping фрагменты

> `record_time >= :startTime AND record_time <= :endTime` пропустит фрагменты, начавшиеся до startTime, но пересекающие интервал. RecordingEntity не имеет поля duration.

**Источник:** Codex, CCS
**Статус:** Новое
**Ответ:** Расширить SQL на 10 сек назад — `record_time >= startTime - INTERVAL '10 seconds'`
**Действие:** Обновить Task 2 — изменить SQL запрос

---

### #3 OOM: readBytes() грузит до 50MB в память

> `videoPath.toFile().readBytes()` загружает весь файл в heap.

**Источник:** Codex, Gemini, CCS
**Статус:** Новое
**Ответ:** Ложное срабатывание — осознанное решение из брейнсторма: "acceptable for v1, note as tech debt"
**Действие:** Нет изменений

---

### #4 "Все камеры" не покрыт контрактом

> `exportVideo(..., camId: String)` не поддерживает сценарий "Все камеры".

**Источник:** Codex, Gemini
**Статус:** Новое
**Ответ:** Ложное срабатывание — session context: "NOT included in v1 for simplicity"
**Действие:** Нет изменений

---

### #5 waitDataCallbackQuery API не проверен — должен быть Task 1

> Критическая развилка (waiter vs state machine) проверяется на Task 7 (последняя). Должна быть первой.

**Источник:** CCS, Codex
**Статус:** Новое
**Ответ:** Да, перенести проверку API на позицию Task 1
**Действие:** Переупорядочить задачи: Task 7 → Task 1, остальные сдвигаются

---

### #6 ffmpeg timeout/kill отсутствует

> `process.waitFor()` без timeout — зависший ffmpeg = утечка процессов. Существующий VideoServiceImpl тоже не имеет timeout.

**Источник:** Codex
**Статус:** Новое
**Ответ:** Добавить timeout для нового кода — `process.waitFor(timeout, TimeUnit.SECONDS)` + `destroyForcibly()`
**Действие:** Обновить Task 4 (VideoMergeHelper) — добавить process timeout

---

### #7 @Column аннотации отсутствуют на CameraRecordingCountDto

> CameraStatisticsDto использует @Column, новый DTO должен следовать паттерну.

**Источник:** CCS, Codex
**Статус:** Новое
**Ответ:** Да, добавить @Column("cam_id") и @Column("recordings_count")
**Действие:** Обновить Task 1 (CameraRecordingCountDto) — добавить аннотации

---

### #8 VideoMergeHelper в core вместо service

> Нельзя переиспользовать из service модуля.

**Источник:** CCS
**Статус:** Новое
**Ответ:** Ложное срабатывание — helper зависит от TempFileHelper и ApplicationProperties (оба в core), перенос невозможен без рефакторинга
**Действие:** Нет изменений

---

### #9 Отсутствие индекса БД

> Нет индекса на (cam_id, record_date, record_time) для новых запросов.

**Источник:** Codex, Gemini
**Статус:** Новое
**Ответ:** Отложить — таблица пока небольшая
**Действие:** Нет изменений

---

### #10 Очистка temp файлов через Files.deleteIfExists вместо TempFileHelper

> План использует raw Files.deleteIfExists, игнорируя абстракцию TempFileHelper.

**Источник:** Gemini
**Статус:** Новое
**Ответ:** Да, использовать TempFileHelper.deleteIfExists
**Действие:** Обновить Task 6 — заменить Files.deleteIfExists на TempFileHelper

---

### #11 Один timeout на диалог + процессинг

> `withTimeoutOrNull(10 мин)` оборачивает и диалог, и ffmpeg обработку.

**Источник:** Gemini
**Статус:** Новое
**Ответ:** Разделить на два отдельных timeout
**Действие:** Обновить Task 6 — separate dialog timeout + processing timeout

---

### #12 Cancel на шагах текстового ввода

> При вводе даты/времени текстом нет возможности отмены кроме timeout.

**Источник:** Codex
**Статус:** Новое
**Ответ:** Добавить обработку /cancel в waitText
**Действие:** Обновить Task 6 — проверять текст на /cancel и "отмена"

---

### #13 Нет тестов в плане

> Отсутствуют unit/integration тесты.

**Источник:** Codex
**Статус:** Новое
**Ответ:** TDD в процессе subagent-driven development, отдельная задача не нужна
**Действие:** Нет изменений

---

### #14 Парсинг H:mm не обрабатывает HH:mm

> DateTimeFormatter.ofPattern("H:mm") якобы не принимает 09:15.

**Источник:** CCS
**Статус:** Новое
**Ответ:** Ложное срабатывание — `H:mm` в Java DateTimeFormatter принимает и `9:15`, и `09:15`
**Действие:** Нет изменений

---

### #15 Валидация camId из callback data

> Нет проверки формата camId после removePrefix.

**Источник:** CCS
**Статус:** Новое
**Ответ:** Не нужно — Telegram гарантирует целостность callback_data
**Действие:** Нет изменений

---

### #16 answer(dateCallback) синтаксис

> Сигнатура answer() нуждается в проверке.

**Источник:** CCS
**Статус:** Новое
**Ответ:** Будет проверено при проверке API (Task 7→1)
**Действие:** Включено в переупорядоченную Task 1 (проверка API)

---

### #17 Escape путей в ffmpeg concat файле

> Спецсимволы в путях могут сломать concat.

**Источник:** Codex
**Статус:** Новое
**Ответ:** Да, добавить экранирование
**Действие:** Обновить Task 4 (VideoMergeHelper) — escape путей в concat файле

---

### #18-#22 Незначительные замечания

- Naming inconsistency (дизайн vs план) — нормализуется при имплементации
- Pair → TimeRange — отложить, не критично для v1
- Пороги в конфигурацию — константы в companion object достаточно
- KDoc для интерфейса — добавить при имплементации
- TODO для "Все камеры" — добавить при имплементации

**Действие:** Нет изменений в документах, учтётся при имплементации

---

## Изменения в документах

| Файл | Изменение |
|------|-----------|
| plan.md | Переупорядочить: Task 7 (проверка API) → Task 1 |
| plan.md, Task 1 | Добавить @Column аннотации к CameraRecordingCountDto |
| plan.md, Task 2 | SQL: `record_time >= :startTime - INTERVAL '10 seconds'` |
| plan.md, Task 4 | Добавить process.waitFor(timeout) + destroyForcibly() |
| plan.md, Task 4 | Добавить escape путей в concat файле |
| plan.md, Task 6 | Добавить chatId фильтрацию во все waiters |
| plan.md, Task 6 | Разделить timeout на dialog + processing |
| plan.md, Task 6 | Добавить обработку /cancel в текстовом вводе |
| plan.md, Task 6 | Заменить Files.deleteIfExists на TempFileHelper |

## Статистика

- Всего замечаний: 22
- Новых: 22
- Повторов (автоответ): 0
- Пользователь сказал "стоп": Нет
- Агенты: codex-executor (gpt-5.3-codex), gemini-executor (gemini-3-pro-preview), ccs-executor (glm-4.7)
