# Merged Design Review — Iteration 1

## codex-executor (gpt-5.3-codex)

### Критические замечания (5)

1. **Сценарий "Все камеры" не покрыт контрактом `VideoExportService`** — `exportVideo(..., camId: String)` не поддерживает кнопку "Все камеры" из дизайна.

2. **SQL-запросы не соответствуют требованию "включать все пересекающиеся фрагменты"** — `record_time >= :startTime AND record_time <= :endTime` пропустит фрагменты, начавшиеся ДО `startTime`, но пересекающие интервал.

3. **Waiter-flow не привязан к конкретному chatId/userId** — параллельные диалоги могут перехватить чужие callback/text сообщения.

4. **Блокирующие операции (ffmpeg, readBytes) внутри bot-flow** — `videoPath.toFile().readBytes()` загружает до 50MB в heap; ffmpeg блокирует обработку апдейтов.

5. **Отсутствует timeout/kill для процессов ffmpeg и обязательный try/finally** — зависший ffmpeg = утечка процессов и временных файлов.

### Важные замечания (6)

1. Нет индекса для новых запросов — нужен индекс на `(record_date, cam_id, record_time) WHERE file_path IS NOT NULL`.
2. CameraRecordingCountDto без `@Column` аннотаций.
3. Нет экранирования путей в ffmpeg concat-файле.
4. Cancel-кнопка описана в дизайне, но не раскрыта в плане на шагах ввода текста.
5. Проверка API waitDataCallbackQuery на последнем этапе — критическая развилка должна проверяться первой.
6. Нет тестов в плане.

### Незначительные замечания (3)

1. Именование расходится между дизайном и планом.
2. `Pair<LocalTime, LocalTime>` лучше заменить на value object `TimeRange`.
3. Пороги 45MB, 50MB, 10min разбросаны как литералы.

### Положительные стороны (4)

1. Размещение интерфейса в telegram, реализации в core — соответствует зависимостям.
2. UX-поток минимальный и логичный.
3. Компрессия только при необходимости.
4. Учтены операционные кейсы.

---

## gemini-executor (gemini-3-pro-preview)

### Критические замечания (2)

1. **Ошибка фильтрации событий (Concurrency Bug)** — `waitDataCallbackQuery` фильтр глобальный, не проверяет `chatId`/`userId`.
2. **Загрузка всего файла в память (OOM Risk)** — `readBytes()` загружает до 50MB.

### Важные замечания (3)

3. Отсутствие индекса БД на `(cam_id, record_date, record_time)`.
4. Ненадежная очистка временных файлов — `Files.deleteIfExists` вместо `TempFileHelper`.
5. Таймаут операции — один `withTimeoutOrNull` на диалог + процессинг.

### Незначительные замечания (2)

6. KDoc для VideoExportService интерфейса.
7. Отсутствие опции "Все камеры" — нужен TODO.

### Положительные стороны (3)

1. Waiter pattern упрощает код.
2. Четкие ограничения (5 мин, 50 МБ).
3. Выделение VideoMergeHelper.

---

## ccs-executor (glm-4.7)

### Критические замечания (3)

1. **VideoMergeHelper placement** — в core нельзя переиспользовать из service. Предложение: переместить в service.
2. **waitDataCallbackQuery API не проверен** — должен быть Task 1, не Task 7.
3. **Отсутствие @Column аннотаций в CameraRecordingCountDto** — R2DBC маппинг может сломаться.

### Важные замечания (6)

4. `answer(dateCallback)` синтаксис нуждается в проверке.
5. BehaviourContext import ordering issue.
6. sendVideo загружает весь файл в память (OOM risk).
7. Парсер `H:mm` не обрабатывает `HH:mm` (09:15).
8. Query не учитывает overlapping fragments.
9. Отсутствие валидации camId из callback data.

### Незначительные замечания (3)

10. parseTimeRange error messages.
11. Не нужен requirePathInTempDir — TempFileHelper handles it.
12. /export в DEFAULT_COMMANDS корректно, но не документировано.

### Положительные стороны (8)

Модульность, существующие паттерны, cleanup, constraint, compression, commits, cancel, timeout.
