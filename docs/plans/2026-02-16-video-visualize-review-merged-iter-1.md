# Merged Design Review — Iteration 1

## codex-executor (gpt-5.3-codex)

### Critical Issues
- Нарушен ключевой инвариант по слотам: документ говорит «держим `VIDEO_VISUALIZE` слот до `completed/failed`», но одновременно требует `release` в `finally` даже при timeout/cancel. Это приводит к преждевременному освобождению слота, пока job ещё может выполняться на detect-сервере, и к перегрузке сервера следующими job. См. `docs/plans/2026-02-16-video-visualize-design.md:112`, `docs/plans/2026-02-16-video-visualize-design.md:170`, `docs/plans/2026-02-16-video-visualize-design.md:187`, `docs/plans/2026-02-16-video-visualize-plan.md:909`, `docs/plans/2026-02-16-video-visualize-plan.md:914`.
- Нереалистичная модель скачивания результата: `downloadJobResult` возвращает `ByteArray` (полная загрузка в память), при этом в проекте глобальные сетевые timeout'ы 30s. Для реального видео это высокий риск timeout/OOM. См. `docs/plans/2026-02-16-video-visualize-design.md:109`, `docs/plans/2026-02-16-video-visualize-plan.md:615`, `modules/core/src/main/resources/application.yaml:36`, `modules/core/src/main/resources/application.yaml:38`, `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/WebClientConfiguration.kt:45`, `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/WebClientConfiguration.kt:92`.
- Retry-политика слишком грубая: в плане polling и submit ретраят «любой `Exception`». Невосстановимые ошибки (например 4xx, `job not found`, `validation error`) будут крутиться до общего timeout; в polling это ещё и удерживает слот без пользы. См. `docs/plans/2026-02-16-video-visualize-design.md:185`, `docs/plans/2026-02-16-video-visualize-plan.md:886`, `docs/plans/2026-02-16-video-visualize-plan.md:945`.
- Потенциально ломающий rollout конфигурации: добавляется обязательное `videoVisualizeRequests` без стратегии миграции/дефолта для уже существующих конфигов detect-серверов. См. `docs/plans/2026-02-16-video-visualize-design.md:69`, `docs/plans/2026-02-16-video-visualize-design.md:203`, `docs/plans/2026-02-16-video-visualize-plan.md:197`, текущая обязательность полей в `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/DetectServerProperties.kt:22`.

### Concerns
- Несогласованность design vs plan: в design есть `detect-every` в YAML, в плане блок YAML его не добавляет. См. `docs/plans/2026-02-16-video-visualize-design.md:197`, `docs/plans/2026-02-16-video-visualize-plan.md:115`.
- Слабая типизация статуса job (`String`) и времени (`String`), плюс `processingTimeMs: Int`; это повышает риск тихих ошибок и невалидных значений. См. `docs/plans/2026-02-16-video-visualize-design.md:44`, `docs/plans/2026-02-16-video-visualize-design.md:46`, `docs/plans/2026-02-16-video-visualize-design.md:58`.
- Изменение `DetectServerStatistics` расширяет API-ответ статистики; в плане нет обсуждения обратной совместимости клиентов. См. `docs/plans/2026-02-16-video-visualize-design.md:73`, текущий контракт `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/response/StatisticsResponse.kt:23`.
- Тестовый план покрывает в основном happy-path и не проверяет ключевые сценарии продуктивной деградации (неизвестный status, 404 на job, ошибка download после `completed`, длительный сетевой лаг). См. `docs/plans/2026-02-16-video-visualize-plan.md:747`, `docs/plans/2026-02-16-video-visualize-plan.md:906`.
- Тесты строят кастомный `WebClient` без production timeout-конфига, поэтому могут давать ложное чувство надёжности. См. `docs/plans/2026-02-16-video-visualize-plan.md:765`, `docs/plans/2026-02-16-video-visualize-plan.md:781`.

### Suggestions
- Ввести явную state-machine для job (`QUEUED/PROCESSING/COMPLETED/FAILED/UNKNOWN`) и разделить retry на recoverable/non-recoverable (например, 5xx/timeout ретраить, 4xx и `job not found` завершать сразу).
- Для download перейти на streaming в файл/поток (не `ByteArray`) и добавить отдельные timeout'ы под видео-операции.
- Зафиксировать стратегию «orphan job»: что делать при timeout/cancel (если есть API cancel, вызывать его; если нет, вводить quarantine/TTL для слота или отдельный reconciliation-механизм).
- Сделать конфиг rollout-safe: временный fallback `videoVisualizeRequests` к `visualizeRequests` или дефолт с явным warning.
- Расширить тест-план интеграционными кейсами с реальными timeout'ами и ошибками после `completed`.

### Questions
- Есть ли у detect-server endpoint для отмены job? Если да, почему он не включён в дизайн timeout/cancel сценариев?
- Какой ожидаемый размер/длительность выходного видео (p95/p99)? Без этого трудно валидировать `ByteArray` и сетевые timeout'ы.
- Должны ли 4xx ошибки на submit/poll считаться финальными (без retry)?
- Что считается корректным поведением при `GET /jobs/{id}` = 404: мгновенный fail или долгий retry?
- Требуется ли обратная совместимость внешних клиентов `/statistics` при добавлении нового поля?

---

## gemini-executor

### Critical Issues

1.  **Нарушение инкапсуляции управления ресурсами (Resource Leaks Risk)**
    Метод `submitVideoVisualize` в `DetectService` возвращает `AcquiredServer` и перекладывает ответственность за `releaseServer` на вызывающий код. Это нарушает паттерн, используемый в остальных методах `DetectService` (где acquire/release скрыты), и создает высокий риск утечки слотов (deadlock load balancer'а), если вызывающий код забудет вызвать release или упадет с исключением *до* входа в блок finally (например, при сохранении `acquired` в переменную).
    **Решение:** Вместо `submitVideoVisualize` реализовать метод высшего порядка, который гарантирует освобождение слота.
    ```kotlin
    suspend fun <T> runVideoVisualizeJob(
        bytes: ByteArray,
        // ... params ...
        block: suspend (AcquiredServer, JobCreatedResponse) -> T
    ): T {
        val acquired = loadBalancer.acquireServer(VIDEO_VISUALIZE)
        try {
            val job = submit(acquired, ...) // internal logic
            return block(acquired, job)
        } finally {
            loadBalancer.releaseServer(acquired.id, VIDEO_VISUALIZE)
        }
    }
    ```
    `VideoVisualizationService` тогда будет просто передавать логику поллинга и скачивания в этот блок.

2.  **Отсутствие отмены Job на сервере (Resource Waste)**
    Если корутина в `VideoVisualizationService` будет отменена (например, пользователь закрыл соединение/браузер), сработает `finally` и слот освободится, но сервер продолжит обрабатывать "осиротевшее" видео, тратя GPU/CPU ресурсы.
    **Решение:** В `finally` блоке `VideoVisualizationService` (или в предложенном выше wrapper'е) нужно проверять причину завершения. Если это `CancellationException`, следует отправить запрос на отмену задачи: `DELETE /jobs/{job_id}` (если API это поддерживает) или хотя бы залогировать необходимость этого для будущих версий API.

### Concerns

1.  **Обработка "мертвых" серверов (Failure Handling)**
    В текущем дизайне при ошибке HTTP во время поллинга (`getJobStatus`) происходит повтор (retry). Однако, если сервер действительно упал (connection refused), бесконечный retry может быть неоптимален.
    В текущих методах `DetectService` есть закомментированный код `detectServerLoadBalancer.markServerDead(acquired.id)`. Стоит решить, нужно ли маркировать сервер как `dead` при ошибках поллинга, чтобы LoadBalancer перестал выдавать его другим клиентам.

2.  **Долгое удержание слота (Load Balancer Impact)**
    Слот `VIDEO_VISUALIZE` удерживается на всё время процессинга (минуты). Если количество серверов невелико (1-2), один пользователь может занять все ресурсы.
    Стоит убедиться, что `simultaneous-count` для `VIDEO_VISUALIZE` в конфигах по умолчанию настроен консервативно (например, 1 на сервер), чтобы не заблокировать `FRAME` запросы (хотя они разделены по типам, но GPU общий).

3.  **Дублирование конфигурации**
    В `DetectProperties` добавляется `VideoVisualizeConfig`, который во многом дублирует параметры `VisualizeConfig` (lineWidth, showLabels и т.д.). Возможно, стоит переиспользовать существующие классы конфигурации или наследовать их, чтобы не плодить сущности.

### Suggestions

1.  **Безопасный API в DetectService**
    Переделать API `DetectService` на использование лямбды:
    ```kotlin
    suspend fun <T> processVideoVisualization(
        bytes: ByteArray,
        filePath: String,
        // ... options
        processor: suspend (server: AcquiredServer, jobId: String) -> T
    ): T
    ```

2.  **Job ID в логах**
    Пробросить `job_id` в MDC для трассировки.

3.  **Metrics**
    Добавить Micrometer метрики на длительность и количество активных задач.

### Questions

1.  Поддерживает ли Detection Server API v2.2.0 отмену задач (`DELETE /jobs/{id}`)? Если да, это критически важно добавить в дизайн для сценария Cancellation.
2.  Нужен ли механизм восстановления ("подхвата") задач после рестарта Analyzer? (Судя по "Scope: async job API", скорее всего нет, но стоит уточнить, что потерянные джобы — это допустимое поведение).

---

## ccs-executor (glmt)

### Critical Issues

### 1. **Race condition при release в submitVideoVisualize**

**Проблема:** В дизайне `submitVideoVisualize` делает release сервера в catch-блоке:

```kotlin
} catch (e: Exception) {
    detectServerLoadBalancer.releaseServer(acquired.id, RequestType.VIDEO_VISUALIZE)
    throw e
}
```

Это создаёт race condition: если caller получил `Pair<AcquiredServer, JobCreatedResponse>` и затем вызвал `releaseServer`, произойдёт **двойной release** — счётчик уйдёт в отрицательное значение.

**Решение:** Использовать паттерн "leasing" с выделенным типом `Lease<T>`, или изменить контракт — `submitVideoVisualize` ВСЕГДА release, а успешный job_id хранить где-то отдельно.

### 2. **Отсутствие валидации response от detection server**

**Проблема:** Дизайн не предусматривает валидацию HTTP status codes и response body от detection server. Что если:
- POST `/detect/video/visualize` вернёт HTTP 500 вместо 202?
- Response JSON не содержит `job_id`?
- GET `/jobs/{job_id}` вернёт 404 (job был удалён/истёк)?

### 3. **DetectProperties.retryDelay используется без объявления**

**Проблема:** В дизайне `VideoVisualizeConfig` НЕ содержит поля `retryDelay`, но в реализации `VideoVisualizationService.submitWithRetry` используется `detectProperties.retryDelay`, которое является общим retry delay для frame/visualize операций.

## Concerns

### 4. **Удерживание слота на протяжении всей обработки — потенциальный deadlock**

Если все серверы заняты долгими видео-заданиями, новые запросы будут бесконечно ждать в `acquireServer`.

### 5. **Монолитный ByteArray для скачивания видео**

`downloadJobResult` возвращает `ByteArray`, который полностью загружается в память. Высокий риск OOM при параллельной обработке.

### 6. **Poll loop без exponential backoff**

Фиксированный `pollInterval = 3s` создаёт лишнюю нагрузку на detection server при долгих job'ах.

### 7. **Отсутствие обработки zombie jobs**

Если Frigate Analyzer крашнулся после submit, detection server тратит ресурсы на job, который никто уже не опрашивает.

### 8. **detectEvery и classes параметры не имеют аналогов в VisualizeConfig**

В `submitVideoVisualize` есть параметры `detectEvery` и `classes`, которых нет в существующей `VisualizeConfig`.

## Suggestions

### 9. **Использовать sealed class для RequestResult вместо Pair**

### 10. **Вынести retry логику в DetectService универсально**

### 11. **Добавить metrics/observability**

### 12. **Детализировать тестовые сценарии** (concurrent requests, server starvation, recovery, large file)

## Questions

### 13. **Какова ожидаемая нагрузка?**

### 14. **Почему именно THREE HTTP метода в DetectService?**

Альтернатива: четыре метода (submit, poll, download, cancel) — что если в будущем понадобится отмена job?

### 15. **Совместимость с pipeline архитектурой**

Планируется ли интеграция с pipeline или это standalone сервис?

### 16. **Где будет храниться аннотированное видео после скачивания?**
