# Merged Design Review — Iteration 1

**Date:** 2026-04-25
**Topic:** description-rate-limit
**Reviewers:** codex (gpt-5.5), gemini (gemini-3.1-pro), ccs/glm (glm-5.1), ccs/albb-glm (glm-5), ccs/albb-qwen (qwen3.5-plus), ccs/albb-kimi (kimi-k2.5), ccs/albb-minimax (MiniMax-M2.5), ccs/deepseek (deepseek-v4-pro)

## codex-executor (gpt-5.5, xhigh reasoning)

### Critical Issues

- В design есть критическая ошибка в коде WARN-лога: snippet пишет WARN в любом `else` (design:139). Это противоречит тексту ниже, что WARN только при отказе лимитера (design:155). Plan исправляет это внутренним `if (descriptionSupplier != null)` (plan:592), но spec и plan сейчас расходятся.
- Plan неполно обновляет места создания `CommonSection`. В нём сказано, что все такие места в `modules/ai-description/src/test` (plan:136), но реально есть ещё `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/facade/RecordingProcessingFacadeTest.kt:127`. Это даст compile error после добавления обязательного `rateLimit`.
- Нужно обновить property-based тесты/ресурсы. `AiDescriptionAutoConfigurationTest.kt:40` задаёт `application.ai.description.common.*` вручную без нового `rate-limit.*`; `modules/core/src/test/resources/application.yaml:48` тоже не содержит блок. С новым non-null `RateLimit` binding будет падать.
- `DescriptionRateLimiter` добавляется в component scan ai-description и требует `Clock`. При `enabled=true` контексту неоткуда взять `Clock`, потому что common auto-config не импортируется в test'е. Нужно добавить `Clock` stub в test config или явно подключить common auto-config.
- Тест `cleanup keeps deque bounded` в плане написан неверно и будет падать. Он каждый раз ставит время в `base + 1h + i ms`, то есть последующие timestamps остаются внутри одного часа друг от друга. На `maxRequests=5` шестой вызов должен вернуть `false`, а тест ждёт `true`.
- Plan использует неверные Gradle project paths: `:modules:ai-description:test`, `:modules:telegram:test`. В репозитории зависимости указываются как `:frigate-analyzer-ai-description` (`modules/telegram/build.gradle.kts:12`); команды из плана не будут исполняться как написаны.
- После изменения конструктора `TelegramNotificationServiceImpl` plan явно добавляет provider только в `TelegramNotificationServiceImplTest`, но есть ещё `modules/telegram/src/test/kotlin/.../TelegramNotificationServiceImplSignalLossTest.kt:37`. Step 3.4 предлагает grep, но список файлов, git add и targeted test run его не покрывают.

### Concerns

- Boundary-семантика в самом алгоритме корректна: `timestamp <= cutoff` удаляется. Тесты `t+window-1ms`, `t+window`, `t+window+1ms` это покрывают правильно; сломан только cleanup-тест.
- Логика `(rateLimiterProvider.getIfAvailable()?.tryAcquire() != false)` покрывает null/true/false, но fail-open: если `descriptionSupplier != null`, а лимитер по какой-то причине не создан, Claude будет вызван без лимита. Это может скрыть misconfiguration.
- `tryAcquire()` как `suspend` под `Mutex` здесь нормален: вызов идёт из suspend-метода, внутри lock нет блокирующих/suspend-операций.
- Гонки между concurrent recordings по квоте не видно: `Mutex` сериализует. Но квота расходуется до `descriptionSupplier.invoke()`, и если supplier вернёт null/упадёт или enqueue позже сорвётся, слот уже сожжён.
- Design говорит про WARN "с recordingId и текущим состоянием окна" (design:45), но §5.1 уже намеренно не логирует `max/window` (design:286). Внутреннее противоречие.

### Suggestions

- Переписать gate читабельнее через `when`, без `!= false`.
- Добавить обязательный тест на `descriptionSupplier == null`: provider не запрашивается, supplier не вызывается, WARN не пишется.
- Добавить binding-тест на `maxRequests=0` через `ApplicationContextRunner`.
- Сделать helper/factory для `DescriptionProperties.CommonSection` в тестах.
- Уточнить multi-photo поведение при `descriptionHandle=null`.

### Questions

- Должен ли `descriptionSupplier != null && rateLimiterProvider.getIfAvailable() == null` быть fail-open, или это startup/configuration error?
- Нужен ли в WARN текущий state окна (`used/max/window`)?
- Сервис гарантированно single-instance? Если несколько replicas — in-memory global станет per-instance.
- Слот расходуется при `tryAcquire()==true` даже если supplier вернул null, или только после старта `descriptionScope.async`?

---

## gemini-executor (gemini-3.1-pro)

### Critical Issues

- **Task 1.4 — неполный поиск `CommonSection(...)`.** План ищет обновления только в `modules/ai-description/src/test`, но конструктор также используется в `core/src/test/kotlin/.../RecordingProcessingFacadeTest.kt:127`. Task 5 (full build) гарантированно упадёт с compile error.

### Concerns

- **«Ложная» многопоточность в тесте `concurrent acquisitions never exceed limit`.** `coroutineScope { (1..50).map { async { ... } } }` внутри `runBlocking` без явного диспатчера выполняется кооперативно в одном потоке event-loop'а, не создавая реального data race. Тест прошёл бы даже без `Mutex`. Использовать `async(Dispatchers.Default) { limiter.tryAcquire() }`.
- **Незатронутые deploy-конфиги.** Task 4 обновляет `.claude/rules/configuration.md`, но игнорирует `docker/deploy/.env.example` и `docker/deploy/application-docker.yaml.example`.

### Suggestions

- **Читаемость null-safe логики.** `rateLimiterProvider.getIfAvailable()?.tryAcquire() != false` логически корректно, но идиоматичнее `?: true` (Elvis).

### Questions

Нет — boundary-семантика признана математически верной.

---

## ccs/glm (glm-5.1)

### Critical Issues

1. **`AiDescriptionAutoConfigurationTest` will break — `rate-limit` properties are missing.** `withPropertyValues(...)` blocks need 3 new rate-limit properties.
2. **`modules/core/src/test/resources/application.yaml` lacks the `rate-limit` block.**
3. **Step 1.4 grep does not cover `RecordingProcessingFacadeTest.kt`.**

### Concerns

4. Test scenario "AI disabled → rate limiter not queried" from design §6.2 is missing in plan.
5. Boundary-semantics test comment is misleading.
6. Mixed validation styles in `RateLimit`: Jakarta `@Min/@Max` + `init { require }`. Order: binding → init → validator, so users may get `IllegalArgumentException` instead of `ConstraintViolationException` for invalid `window`.
7. WARN log lacks current-window context.

### Suggestions

8. **Add default values to `RateLimit` data class** — auto-resolves Critical Issues #1 and #2 by removing fragility of every `CommonSection` consumer needing to remember `rateLimit`.
9. Consider `@ConditionalOnBean(DescriptionAgent::class)` instead of `@ConditionalOnProperty`.
10. Concurrency test with 50 coroutines may be unstable — consider `UnconfinedTestDispatcher`.

### Questions

1. Who updates `AiDescriptionAutoConfigurationTest`?
2. Should `DescriptionRateLimiter` creation be verified there, or is it covered by unit tests?
3. Semantics when `rate-limit.enabled=false` + `application.ai.description.enabled=true`: bean still created (always-true fast-path) or not registered?

---

## ccs/albb-glm (glm-5)

### Critical Issues

1. **Противоречие spec vs plan в WARN-логе** — Spec §3.2 пишет WARN всегда в else-ветке (включая `descriptionSupplier == null`), Plan Step 3.3 корректно делает вложенный guard.
2. **Тест `enabled=false` не проверяет что deque пуст** — without verification of internal state (regression risk).
3. **Не покрыт сценарий `rate-limit.enabled=false` в integration-тестах** `TelegramNotificationServiceImpl`.
4. **Устаревшие номера строк в Spec §3.2** — ссылки на `TelegramNotificationSender.kt:84/90-102/105-122/124-134/137-144` смещены на ±1-2 строки.

### Concerns

- Тест `invokes supplier exactly once below limit` не проверяет что handle одинаковый для всех получателей.
- `@field:Valid` на `rateLimit` поле должен присутствовать.
- MutableClock + `@Volatile` — OK для single-threaded boundary тестов.

---

## ccs/albb-qwen (qwen3.5-plus)

### Critical Issues

1. **`CommonSection(` в `RecordingProcessingFacadeTest` не обновляется.** Step 1.4 ограничивается `modules/ai-description/src/test`, теряет match в `core/src/test`.
2. **Гонка между `getIfAvailable()` и `tryAcquire()` — нет теста для `supplier != null && rateLimiter == null`.**
3. **Тест `concurrent acquisitions never exceed limit` с `Clock.fixed` не ловит реальные race-conditions** — все 50 корутин видят одинаковый `now`.

### Concerns

4. WARN-лог: `descriptionSupplier != null` guard — правильно, но запутанно.
5. Rate limiter вызывается даже когда `descriptionSupplier == null` (relies on Kotlin short-circuit).
6. **`RateLimit.init {}` валидация vs `@Min/@Max` — несогласованность покрытия:** план тестирует только `window`, не `maxRequests`.
7. `@ConditionalOnProperty` без `matchIfMissing` — корректно.
8. Design doc ссылается на `ClaudeDescriptionAgent.executeWithRetry()` — метод не существует. Retry-логика встроена в `describe()` через `ClaudeInvoker`.

### Suggestions

9. Альтернатива guard через Elvis (`?: true`).
10. Добавить метаданные в WARN-лог.
11. Тест `enabled=false` не проверяет что mutex не берётся.

---

## ccs/albb-kimi (kimi-k2.5)

### Critical Issues

1. **Некорректный тест `cleanup keeps deque bounded`** — сдвигает время на 1мс между итерациями. При `maxRequests=5` после 5 успешных вызовов deque заполнится, и 6-й вернёт `false`. Должен быть `window + 1мс`:
   ```kotlin
   clock.current = baseInstant.plus(Duration.ofHours(i.toLong() + 1)).plusMillis(1)
   ```
2. **Логика `getIfAvailable()?.tryAcquire() != false` может пропустить вызов** — если бин не создан при `descriptionSupplier != null`, supplier вызовется без rate limiting. Рекомендация: явная проверка через `if/else`.
3. **Отсутствие `@field:Min` для `window` в `RateLimit`** — Spring Validator не провалидирует `Duration` на binding-стадии. Нужен либо кастомный валидатор, либо `@DurationMin`.
4. **Plan не обновляет AiDescriptionAutoConfiguration.**

### Concerns

5. Boundary-semantics нетипична (timestamp == cutoff → drop). Это осознанный выбор.
6. Mutex под нагрузкой — приемлем для 10/час.
7. WARN при `enabled=false` — корректно (молчит).
8. Отсутствие метрик (out of scope).

### Suggestions

9. MutableClock с минимальными инкрементами для concurrency теста.
10. Добавить тест на "refund" при ошибке Claude.
11. `@RelaxedMockK` вместо `mockk(relaxed=true)`.
12. "Dry run" режим.

### Questions

13. Сообщение `@Min(1)` при `maxRequests=0`?
14. Поведение при изменении `window` во время runtime?
15. `AiDescriptionAutoConfiguration` корректно обработает `RateLimit`?

---

## ccs/albb-minimax (MiniMax-M2.5)

### Critical Issues

1. **Несоответствие WARN-лога в Design и фактической реализации в Plan.** Design (строки 143-148) пишет WARN при ЛЮБОМ null результате (включая `descriptionSupplier == null`). Plan корректно делает вложенный guard. Critical расхождение.
2. **Неполное покрытие тестов при обновлении конструктора.** Не учтены: `RecordingProcessingFacadeTest.kt` (`CommonSection`), `TelegramNotificationServiceImplSignalLossTest.kt:38` (`TelegramNotificationServiceImpl` constructor).

### Concerns

3. Thread-safety тест с `Clock.fixed` — нереалистичный сценарий.
4. Формулировка boundary semantics в Design и Plan различаются.
5. Отсутствие теста на "AI включён, но `rate-limit.enabled=false`".

### Suggestions

6. Упрощение `(getIfAvailable()?.tryAcquire() != false)` через `?: true`.
7. Тест на "rateLimiter = null".
8. `MutableClock` с `@Volatile` — shared state между тестами; рекомендация: новый экземпляр на каждый @Test.

### Questions

9. Случай "AI включён, но нет DescriptionRateLimiter бина" — задокументировать в коде.
10. Почему не используется `@ConditionalOnProperty` для `rate-limit.enabled`?

---

## ccs/deepseek (deepseek-v4-pro)

### Critical Issues

1. **План пропускает `TelegramNotificationServiceImplSignalLossTest`** (`TelegramNotificationServiceImpl` constructor breakage).
2. **План пропускает `RecordingProcessingFacadeTest`** (`CommonSection` constructor breakage).
3. **Отсутствует третий integration-тест "AI выключен — лимитер не запрашивается"** (Design §6.2 описывает 3 сценария, plan реализует 2).

### Concerns

4. WARN-лог в else-ветке покрывает два семантически разных случая (rate-limit reached vs limiter missing).
5. `descriptionSupplier` лямбда строится до проверки лимитера — waste при отказе.
6. `MutableClock.withZone()` throws — хрупко.
7. AI включён + лимит по умолчанию 10/час — breaking change для существующих установок.
8. План не указывает корректные пути в Gradle проекте.
9. Boundary-тест concurrency не тестирует interleaved timing.

### Suggestions

10. Дефолтное значение `rateLimit` в `CommonSection` (e.g., `enabled=false` в коде, `enabled=true` в YAML).
11. Выделить WARN-лог в явную ветку через `when` для читаемости.
12. `availableSlots()` метод для будущих метрик.
13. Заменить `MutableClock` на `Clock.offset(baseClock, offset)`.

### Questions

14. Почему `RateLimit` — sibling с `CommonSection`, а не nested внутри?
15. WARN на каждый skip или throttling раз в N минут?
16. Рассматривался ли `resilience4j RateLimiter`?

---

# Summary of Critical Issues by Frequency

| Issue | Mentioned by |
|---|---|
| `RecordingProcessingFacadeTest.kt:127` (`CommonSection`) не покрыт | codex, gemini, ccs-glm, ccs-albb-qwen, ccs-albb-minimax, ccs-deepseek (6/8) |
| `TelegramNotificationServiceImplSignalLossTest.kt` (`TelegramNotificationServiceImpl` constructor) не покрыт | codex, ccs-albb-minimax, ccs-deepseek (3/8) |
| `AiDescriptionAutoConfigurationTest` `withPropertyValues` без `rate-limit` | codex, ccs-glm, ccs-albb-kimi (3/8) |
| `modules/core/src/test/resources/application.yaml` без `rate-limit` блока | codex, ccs-glm (2/8) |
| Тест `cleanup keeps deque bounded` сломан (1ms vs window) | codex, ccs-albb-kimi (2/8) |
| Spec/plan расхождение в WARN-логе (else writes WARN always vs guarded) | codex, ccs-albb-glm, ccs-albb-minimax (3/8) |
| Третий integration-тест ("AI off → лимитер не спрашивается") отсутствует | codex (suggestion), ccs-glm, ccs-albb-qwen, ccs-deepseek (4/8) |
| Concurrency test с `Clock.fixed` слабый | gemini, ccs-albb-qwen, ccs-albb-minimax, ccs-deepseek (4/8) |
| Gradle project paths возможно неверные | codex, ccs-deepseek (2/8) |
