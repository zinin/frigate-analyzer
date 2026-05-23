# Промпт для новой сессии: архитектурный фикс `WatchRecordsTask` (supervisor + health + alert)

> **Как использовать:** скопируй блок ниже целиком в новую сессию Claude Code в корне репозитория `frigate-analyzer`. Промпт самодостаточный, новая сессия сама прочитает нужные файлы.

---

## Промпт

Привет. Нужно закрыть **открытый со времён двух инцидентов** архитектурный bug в `WatchRecordsTask` (`modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsTask.kt`). Bug стрельнул дважды с одинаковыми последствиями (см. ниже), сейчас контейнер остановлен — есть окно сделать это правильно, а не "по-быстрому".

### Контекст — обязательно прочитай в начале

1. `docs/incidents/2026-05-17-postgres-corruption.md` — первый инцидент, ~30 ч простоя, открытый bug помечен как «нужно зафиксить отдельным коммитом».
2. `docs/incidents/2026-05-23-sata-cable-corruption.md` — второе срабатывание ровно того же сценария, ~7 ч простоя. Корневая причина оказалась глубже (битый SATA-кабель → btrfs corruption → PG corruption), но **усилитель последствий — этот код**.
3. `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsTask.kt` — текущая реализация.
4. `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsTaskTest.kt` — существующие тесты.
5. `CLAUDE.md` — `Planning Mode` секция: после имплементации **сначала `superpowers:code-reviewer` agent**, потом `build-runner`. На ktlint ошибках — `./gradlew ktlintFormat` и перезапустить build.

### Проблема одной строкой

`WatchRecordsTask.run()` под `@Async` — голый `while (!stopped.get())` без try/catch. Первое же необработанное исключение из тела (например, `R2dbcException`, `IOException` из `Files.readAttributes`, `IllegalArgumentException` из `recordingFileHelper.parse`) ловит `SimpleAsyncUncaughtExceptionHandler`, **поток `task-1` навсегда умирает**, и Spring `@Async` его **не воскрешает**. Никакого retry, никакого alert, никакого health-сигнала. Pipeline после этого работает в пустоту: `FrameExtractorProducer` находит 0 unprocessed (БД не пополняется) → нет уведомлений. Внешне всё «healthy» — actuator зелёный, docker healthcheck зелёный.

Реальный стектрейс из инцидента 2026-05-23 (по [docs/incidents/2026-05-23-sata-cable-corruption.md](../incidents/2026-05-23-sata-cable-corruption.md)):

```
2026-05-23T12:14:27,154 ERROR [task-1] o.s.a.i.SimpleAsyncUncaughtExceptionHandler :40 -
  Unexpected exception occurred invoking async method: WatchRecordsTask.run()
org.springframework.dao.DataAccessResourceFailureException: executeMany;
  SQL [INSERT INTO "recordings" (...) VALUES (...)];
  [XX000] could not open file "base/25697/34076.1" (target block 1503595372)
```

### Acceptance criteria

Фикс закрыт, когда выполнены **ВСЕ** пункты:

1. **Supervision.** Тело цикла обёрнуто так, что любое **non-cancellation** исключение **не убивает поток**: логируется ERROR'ом, делается экспоненциальный backoff (start `5s`, max `60s`, reset при N успешных итерациях подряд), цикл продолжает работать. `CancellationException` и `InterruptedException` пробрасываются без подавления.
2. **Recreation of WatchService.** Если `WatchService` сам сломался (`ClosedWatchServiceException` / `IOException`) — он **пересоздаётся** в next iteration, `registeredDirs` чистится и регистрируется заново. Не оставляем мёртвый сервис в поле.
3. **Health indicator.** Зарегистрирован Spring `HealthIndicator` с компонентом `watchRecordsTask`, статус которого:
   - `UP` если последняя успешная итерация была < `staleness threshold` назад (по умолчанию `5 * POLL_PERIOD + 30s`, конфигурируемо через `app.records-watcher.health.staleness`),
   - `DOWN` если поток мёртв (например, через `stopped == false && lastTickInstant > threshold ago`),
   - `OUT_OF_SERVICE` если задача в backoff'e после N подряд провалов.

   `/actuator/health` отдаёт реальное состояние writer-а, docker healthcheck начинает падать при смерти задачи (можно завязать healthcheck на `/actuator/health/liveness` если group настроен — оставь решение на читающего, но опиши в commit-message выбранный путь).
4. **Telegram alert.** При **первом** переходе задачи в backoff (после первой ошибки) и при каждом следующем переходе **после `M` успешных итераций между ними** — отправлять алёрт владельцу бота через существующий механизм (см. `modules/telegram/`). **Не спамить** на каждой итерации backoff'а — только событийные переходы. Шаблон сообщения должен содержать: имя задачи, exception class + message (первые ~500 символов), таймштамп, признак "будет переоткрыто автоматически" / "требует ручного вмешательства".
5. **Конфигурация.** Все таймауты/пороги вынесены в `RecordsWatcherProperties` с дефолтами:
   - `health.staleness` = `2m`
   - `supervisor.initialBackoff` = `5s`
   - `supervisor.maxBackoff` = `60s`
   - `supervisor.successesToResetBackoff` = `5`
   - `supervisor.alertCooldownIterations` = `100` (или `alertCooldown` как Duration, на твоё усмотрение, главное — описать)

   Соответствующие env vars в `docker/deploy/.env.example` (`RECORDS_WATCHER_*`) и в `application.yaml`.
6. **Тесты (TDD).** Использовать `superpowers:test-driven-development`. До любой строки кода:
   - **Failing test #1:** имитирует выброс `RuntimeException` из mocked `recordingEntityHelper.createRecording(...)`; ожидание: поток жив через 2 backoff-итерации, ошибка залогирована, health indicator переключился DOWN→UP→DOWN корректно.
   - **Failing test #2:** имитирует `ClosedWatchServiceException` из `watchService.poll(...)`; ожидание: WatchService пересоздаётся, регистрации восстанавливаются, новые ENTRY_CREATE снова доходят до `createRecording`.
   - **Failing test #3:** имитирует `CancellationException`; ожидание: цикл выходит (т.е. отмена работает корректно, не подавляется supervisor'ом).
   - **Failing test #4:** имитирует 5 подряд успешных итераций после 3 ошибок подряд — `successesToResetBackoff` срабатывает, backoff возвращается к `initialBackoff`.
   - **Failing test #5:** проверяет, что Telegram alert вызывается ровно один раз на серию из 50 backoff-итераций (cooldown работает).
   - Все тесты — **сперва красные**, потом имплементация, потом зелёные. Не объединять.
7. **Никакого "while I'm here" рефакторинга.** Только supervisor + health + alert + конфиг + тесты + строго необходимые правки. Любое расширение scope — обсудить отдельно.
8. **Документация.** Обновить `.claude/rules/pipeline.md` (раздел `## File Watching`) — описать supervision, health, alert. Обновить `CLAUDE.md` если меняется команда / env-var.
9. **Git hygiene.** `git add` после каждого изменения (CLAUDE.md явно требует). Branch отдельный (например `fix/watch-records-supervisor`). Коммит по conventional commits, ссылка на оба incident report'а в body.
10. **Code review.** После имплементации — запустить `superpowers:code-reviewer` agent. Фиксить критичные комментарии до чистоты, затем `build-runner`. На ktlint ошибках — `./gradlew ktlintFormat`, retry.

### Чего НЕ нужно делать в этом коммите

- Не лезть в `pg_checksums`, `btrfs scrub`, REINDEX — это сторона hardware/PG, отдельный план в инцидент-репорте.
- Не убирать `@Async`-аннотацию совсем без обсуждения архитектуры (это уже соседний рефакторинг). Допустимо обернуть выполнение в `runCatching` + `Thread.sleep`, либо переписать на `@Scheduled(fixedDelay)` с явным `TaskScheduler` — выбор обоснуй в commit-message.
- Не менять контракт `WatchRecordsTask.shutdown()`.
- Не трогать `FirstTimeScanTask` (отдельная задача, ловит свои ошибки иначе).

### Финальная проверка перед PR

- `./gradlew test` зелёный во всех модулях (используй `superpowers:verification-before-completion`).
- `./gradlew ktlintCheck` зелёный.
- Manual sanity на локальном Docker: имитируй ошибку (например, временно отвали PG / сделай invalid filename) — убедись по логам, что поток жив и продолжает работать после восстановления.
- `superpowers:requesting-code-review` перед merge.

### Опциональные улучшения (если останется время)

- Метрика `watch_records_task_iterations_total` / `watch_records_task_errors_total` / `watch_records_task_backoff_seconds` через Micrometer.
- Метрика lag: `watch_records_task_last_success_age_seconds` (gauge).
- Docker healthcheck в `docker/deploy/docker-compose.yml` поменять на `/actuator/health/liveness` если liveness group настроен только из тех индикаторов, которые реально означают "приложение мертво". (если не уверен — оставь как есть, не ломай.)

---

**Главное:** этот bug **уже стрелял дважды**. Третий раз он стрельнёт через неделю-месяц независимо от того, починим ли SATA-кабель. Цель — чтобы next transient PG/IO ошибка вызвала 1 строчку в логе + 1 алёрт в Telegram, а не многочасовой outage.
