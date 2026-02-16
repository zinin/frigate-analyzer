# Merged Design Review — Iteration 2

## codex-executor (gpt-5.3-codex)

### Critical Issues
- **[CI-1] Противоречивая схема управления слотом в документах.**
  В `design` одновременно описаны два разных алгоритма: единичный `acquire` на весь job (design:158) и повторный `acquire` внутри submit-retry (design:173). В `plan` это тоже смешано через `submitWithRetry` (plan:773, plan:823). Это риск неверной реализации (двойной acquire/release, рассинхрон счетчиков).
- **[CI-2] Retry submit неидемпотентен и может создавать дубликаты job.**
  Сценарий "submit error -> retry на другом сервере" (design:209) без idempotency key/клиентского request-id приводит к дублированию задач, если первая POST фактически принята сервером, но ответ потерян (plan:824). Это прямой риск скрытой перегрузки GPU и "невидимых" orphan jobs.
- **[CI-3] Утечка временных файлов при сбоях download/cancel.**
  В коде из плана temp-файл создаётся сразу (plan:632), но удаление при ошибке не предусмотрено, return только на happy-path (plan:658). При сетевых ошибках/таймаутах будут накапливаться partial `.mp4`.

### Concerns
- Глобальные таймауты клиента (`30s`) не согласованы с бизнес-таймаутом video visualize (`15m`): `application.yaml:36,38`, `WebClientConfiguration.kt:45`. Это может ломать длинные операции download/poll до истечения `videoVisualize.timeout`.
- План в текущем виде неисполняем: используется несуществующий gradle path `:modules-core:test` (plan:268), тогда как реальные project path задаются как `:core`, `:model`, ... (`settings.gradle.kts`).
- Вводится обязательное `videoVisualizeRequests`, но не обновлён docker-шаблон конфигурации: `docker/deploy/application-docker.yaml.example`.
- В design есть требование сохранить OpenAPI (design:245), но в плане нет отдельной задачи на это.
- `annotateVideo`/`submitVideoVisualize` принимают видео как `ByteArray`, что оставляет риск пиков памяти на больших файлах.
- Не определено поведение при ошибке `onProgress`: callback вызывается напрямую и его exception, вероятно, сорвёт весь job.

### Suggestions
- Зафиксировать один канонический алгоритм оркестрации (один `acquire`, один `release`, чёткие state flags) и удалить конфликтующие псевдокоды.
- Добавить идемпотентность submit (client request id / dedup token). Если API сервера не поддерживает — явно задокументировать accepted risk.
- Для download сделать fail-safe cleanup: удалять temp-файл в `catch/finally` при неполной записи.
- Разделить таймауты: отдельные для submit/poll/download (или отдельный `WebClient` для video endpoints).
- Исправить плановые команды (`:core:test`) и добавить задачу на обновление `docker/deploy/application-docker.yaml.example`.
- Добавить explicit шаг на сохранение/верификацию `docs/openapi/detect-server-openapi.json`.

### Questions
- Поддерживает ли detect-server idempotency key или client-supplied job/request id для POST `/detect/video/visualize`?
- Какой ожидаемый максимум входного/выходного видео (размер и длительность, p95/p99)?
- Ошибка в `onProgress` должна прерывать аннотацию или только логироваться?
- Нужно ли поддерживать форматы кроме MP4 на входе?

---

## gemini-executor

### Critical Issues

1. **Обработка 404 при поллинге (Job Lost)**
   В фазе Polling (Фаза 2), если сервер перезагрузился, job исчезнет из памяти, и сервер вернет `404 Not Found`. Текущий дизайн предлагает делать retry при ошибках. Если обрабатывать 404 как обычную ошибку сети и ретраить, сервис будет бессмысленно опрашивать сервер до истечения таймаута (15 минут).
   **Требование:** Явно обрабатывать `WebClientResponseException.NotFound` и выбрасывать терминальное исключение, прерывая цикл.

2. **Очистка временного файла при ошибке загрузки**
   Если в процессе загрузки произойдет разрыв соединения, метод выбросит исключение, но частично записанный файл останется на диске.
   **Требование:** Обернуть логику записи в `try-catch` и удалять `tempFile` в блоке `catch`.

### Concerns

1. **Дублирование логики Retry**
   Метод `DetectService.retryWithTimeout` является `private`. `VideoVisualizationService` вынужден будет полностью скопировать эту логику.

2. **Retry на 4xx ошибки (Bad Request)**
   Существующий паттерн `retryWithTimeout` ловит `Exception`, что включает `WebClientResponseException.BadRequest` (400). Бессмысленный retry на невосстановимые ошибки.

### Suggestions

1. **Выделение Retry-логики** — рассмотрите возможность сделать `retryWithTimeout` `internal` или вынести в утилитный класс.
2. **Fail-fast при недоступности серверов** — если серверов вообще нет в конфигурации, стоит упасть раньше.

### Questions
Нет вопросов.

---

## ccs-executor (glmt)

### Критические проблемы

### [C9] Противоречие в дизайне `submitWithRetry` — slot может быть утечён при retry
В дизайне (строки 168-181) если `acquireServer` успешно выполняется, но `submitVideoVisualize` выбрасывает исключение, слот не освобождается, потому что переменная `acquired` не инициализирована в блоке `catch`.

### [C10] Логирование orphan job выполняется всегда, даже при успешном завершении
В finally блоке orphan job логируется всегда, независимо от того, успешно завершился job или нет. Ложные positive предупреждения.

### [C11] Несоответствие имён параметров: `imgSize` vs `imgsz`
В плане используется `imgSize`, но в detection server API query parameter — `imgsz`. Несогласованность может привести к путанице.

### Заботы (Concerns)

- [N6] `downloadJobResult` — открывается новый `OutputStream` на каждый `DataBuffer`. Для большого видео это тысячи открытий/закрытий файла.
- [N7] Отсутствует валидация `detectEvery: Int?` — возможны отрицательные значения.
- [N8] `JobStatus` enum — если detection server добавит новые статусы, десериализация упадёт.
- [N9] `acquired` может быть `null` в `finally` если `submitWithRetry` бросит до присваивания.
- [N10] Временный файл не удаляется при ошибках downstream.

### Предложения (Suggestions)
- [S5] Вынести retry logic в отдельный метод для переиспользования.
- [S6] Добавить `jobId` в логи для tracing.
- [S7] Рассмотреть возможность ограничения размера видео.
- [S8] Добавить unit test для `submitWithRetry` retry logic.

### Вопросы (Questions)
- [Q5] Почему `onProgress` callback — suspend функция?
- [Q6] Обработка случая `progress > 100` или `< 0`.
- [Q7] Почему `jobId: String` вместо типобезопасной обёртки?
