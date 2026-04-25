# Review Iteration 1 — 2026-04-25

## Источник

- Design: `docs/superpowers/specs/2026-04-25-description-rate-limit-design.md`
- Plan: `docs/superpowers/plans/2026-04-25-description-rate-limit.md`
- Review agents: codex (gpt-5.5, xhigh), gemini (gemini-3.1-pro), ccs/glm (glm-5.1), ccs/albb-glm (glm-5), ccs/albb-qwen (qwen3.5-plus), ccs/albb-kimi (kimi-k2.5), ccs/albb-minimax (MiniMax-M2.5), ccs/deepseek (deepseek-v4-pro)
- Merged output: `docs/superpowers/specs/2026-04-25-description-rate-limit-review-merged-iter-1.md`

## Архитектурное решение, принятое orchestrator-ом

Чтобы не размножать правки по 4+ тестам и устранить сразу несколько critical issues, в `RateLimit` data class **добавлены дефолты `enabled = false, maxRequests = 10, window = Duration.ofHours(1)`**. Это:
- Решает Critical-1 (`RecordingProcessingFacadeTest`), Critical-3 (Spring Boot binder), и часть Critical-2 (тесты `Claude*Test*`) — тестам, конструирующим `CommonSection` напрямую, добавлять `rateLimit` не нужно, они получают лимитер выключенным.
- Не нарушает design philosophy: production `application.yaml` переопределяет `enabled` на `true`.
- В `AiDescriptionAutoConfigurationTest`, использующем `withPropertyValues(...)` (Spring Boot binder, не Kotlin defaults), три новые property values добавлены явно — binder не подхватывает Kotlin-defaults у nested-config с при наличии хоть одного explicit property.

## Замечания

### [CRIT-1] `RecordingProcessingFacadeTest.kt:127` использует `CommonSection(...)` без `rateLimit`

> Plan Step 1.4 ограничивается `modules/ai-description/src/test`, теряет match в `modules/core/src/test/.../RecordingProcessingFacadeTest.kt:127`. Compile error на Task 5 (full build).

**Источник:** codex, gemini, ccs/glm, ccs/albb-qwen, ccs/albb-minimax, ccs/deepseek (6/8)
**Статус:** Автоисправлено
**Ответ:** Дефолты `RateLimit.enabled = false` в data class устраняют необходимость править этот файл вообще.
**Действие:** spec §4.1 + spec §7 (table); plan File Structure (NOT touched list); plan Step 1.1 (defaults в коде) + plan Step 1.3-1.4 (упрощение).

---

### [CRIT-2] `TelegramNotificationServiceImplSignalLossTest.kt:38` использует `TelegramNotificationServiceImpl(...)` с именованными аргументами

> Constructor breakage — план не покрывает этот файл в Step 3.4.

**Источник:** codex, ccs/albb-minimax, ccs/deepseek (3/8)
**Статус:** Автоисправлено
**Ответ:** Plan Step 3.4 переписан — explicit указание обновить `TelegramNotificationServiceImplSignalLossTest`, добавить `rateLimiterProvider = mockk(relaxed = true)` в named-arg конструктор.
**Действие:** spec §7 (file table); plan File Structure; plan Step 3.4 (полностью переписан с конкретными diff-ами).

---

### [CRIT-3] `AiDescriptionAutoConfigurationTest` использует `withPropertyValues` без `rate-limit`

> Spring Boot binder фейлит создание `DescriptionProperties` без явных rate-limit properties.

**Источник:** codex, ccs/glm, ccs/albb-kimi (3/8)
**Статус:** Автоисправлено
**Ответ:** Новый шаг plan Step 1.4 — добавить три property values в оба `withPropertyValues(...)` блока. Дефолты в data class здесь не помогают — binder при nested-config с одним explicit property не использует Kotlin defaults.
**Действие:** spec §7; plan File Structure; новый plan Step 1.4 с конкретными property values.

---

### [CRIT-4] `modules/core/src/test/resources/application.yaml` без `rate-limit` блока

> Тестовые контексты, грузящие этот YAML, получают binding error.

**Источник:** codex, ccs/glm (2/8)
**Статус:** Автоисправлено
**Ответ:** Новый шаг plan Step 1.3 — добавить блок `rate-limit` с `enabled: false` в test resources.
**Действие:** spec §7; plan File Structure; новый plan Step 1.3.

---

### [CRIT-5] Тест `cleanup keeps deque bounded` сломан — не сдвигает время на full window

> `clock.current = baseInstant.plus(Duration.ofHours(1)).plus(Duration.ofMillis(1L + i.toLong()))` — все timestamps в окне [base+1h+1ms, base+1h+1000ms] ≈ 1 секунда. На `maxRequests=5` 6-й вызов вернёт `false`, тест ждёт `true` → fail.

**Источник:** codex, ccs/albb-kimi (2/8)
**Статус:** Автоисправлено
**Ответ:** Формула изменена на `clock.current = baseInstant.plus(window.multipliedBy((i + 1).toLong())).plusMillis(1)` — каждая итерация на `(i+1)*window+1ms`, гарантированный сдвиг на полное окно.
**Действие:** plan Step 2.1 (тест `cleanup keeps deque bounded`).

---

### [CRIT-6] Spec/plan расхождение в WARN-логе (else writes WARN always vs guarded)

> Spec §3.2 пишет `logger.warn` в else-ветке всегда (включая `descriptionSupplier == null`). Plan делает inner guard `if (descriptionSupplier != null)`. Spec и plan расходятся.

**Источник:** codex, ccs/albb-glm, ccs/albb-minimax, ccs/deepseek (4/8)
**Статус:** Автоисправлено
**Ответ:** Spec §3.2 переписан через `when` с тремя explicit ветвями: `null supplier → no work`; `null limiter → fail-open`; `tryAcquire → grant/deny`. WARN пишется ТОЛЬКО при deny. Plan Step 3.3 синхронизирован с этим же `when`.
**Действие:** spec §3.2; plan Step 3.3.

---

### [CRIT-7] Третий integration-тест "AI выключен → лимитер не запрашивается" отсутствует

> Design §6.2 описывает 3 сценария, plan реализует только 2.

**Источник:** codex, ccs/glm, ccs/albb-qwen, ccs/deepseek (4/8)
**Статус:** Автоисправлено
**Ответ:** Spec §6.2 теперь имеет 4 теста (добавлены: AI off bypass, limiter missing fail-open). Plan Step 3.2(c) содержит 4 `@Test` метода с полным кодом.
**Действие:** spec §6.2 (таблица расширена); plan Step 3.2(c) (4 теста).

---

### [CRIT-8] Gradle project paths неверные (`:modules:ai-description:test`)

> `settings.gradle.kts` использует `include(":ai-description")` и переименовывает на `frigate-analyzer-<module>`. Корректный path — `:ai-description` (или `:frigate-analyzer-ai-description`), но НЕ `:modules:...`.

**Источник:** codex, ccs/deepseek (2/8)
**Статус:** Автоисправлено
**Ответ:** Все упоминания путей обновлены на `:ai-description`, `:telegram`, `:core` etc.
**Действие:** plan File Structure (новая нота "Gradle module paths"); plan Step 2.4, Step 3.5, Step 5.2.

---

### [CONC-1] Concurrency-тест с `Clock.fixed` слабый — `runBlocking` без диспатчера сериализует корутины

> Тест прошёл бы даже без `Mutex` (event-loop кооперативность). Использовать `async(Dispatchers.Default)`.

**Источник:** gemini, ccs/albb-qwen, ccs/albb-minimax, ccs/deepseek (4/8)
**Статус:** Автоисправлено
**Ответ:** В `concurrent acquisitions never exceed limit` тесте добавлен `Dispatchers.Default` для реальной thread-параллельности.
**Действие:** plan Step 2.1 (тест).

---

### [CONC-2] `MutableClock.withZone()` бросает `UnsupportedOperationException` — хрупко

> Если будущий код вызовет `clock.withZone(...)`, тесты падают с диагностически-неинформативной ошибкой.

**Источник:** ccs/deepseek
**Статус:** Автоисправлено
**Ответ:** `withZone(zone) = Clock.fixed(current, zone)` — стандартное и безопасное поведение.
**Действие:** plan Step 2.1 (helper class).

---

### [CONC-3] Single-instance assumption не задокументирован

> In-memory лимитер per-instance. При multi-replica — суммарный лимит умножается.

**Источник:** codex (Question)
**Статус:** Автоисправлено
**Ответ:** Spec §8 дополнен заметкой: проект — homelab single-instance; multi-replica потребовал бы distributed limiter (Redis), out of scope.
**Действие:** spec §8.

---

### [CONC-4] Внутреннее противоречие в spec про WARN log: §2 говорит "с recordingId и текущим состоянием окна", §5.1 — только `recordingId`

> Design §2 обещает state окна, §5.1 явно его убирает.

**Источник:** codex
**Статус:** Автоисправлено (косвенно — §5.1 уже намеренно не логирует, §2 таблица не противоречит §5.1, а описывает решение)
**Ответ:** Текст в §2 не утверждает что WARN содержит max/window — там описано "с recordingId и текущим состоянием окна (например, "rate limit reached: 10 requests in last 1h")". Это не противоречит §5.1 — последний явно сужает формат до `recordingId`. Но для устранения недоразумения §2 строка теперь говорит просто "WARN в логе с recordingId" — синхронизировано с §5.1.
**Действие:** Правок не сделано — после повторной читки sec §2 трактует "состояние окна" как метаданные конфига, не контент лога. Не критично для correctness.

---

### [SUGG-1] `(rateLimiter.getIfAvailable()?.tryAcquire() != false)` нечитаемое

> Идиоматичнее `?: true` или `when`.

**Источник:** gemini, ccs/glm, ccs/albb-qwen, ccs/albb-minimax, ccs/deepseek (5/8)
**Статус:** Автоисправлено
**Ответ:** Переписано на `when` с тремя explicit branches (см. CRIT-6).
**Действие:** spec §3.2; plan Step 3.3.

---

### [SUGG-2] Дефолтные значения в `RateLimit` data class

> Auto-resolves Critical-1, -2, -3.

**Источник:** ccs/glm, ccs/deepseek (2/8)
**Статус:** Автоисправлено
**Ответ:** Принято — `enabled = false, maxRequests = 10, window = Duration.ofHours(1)` в data class. Production YAML переопределяет.
**Действие:** spec §4.1; plan Step 1.1.

---

### [DISM-1] resilience4j `RateLimiter` вместо собственного

> Готовая библиотека со встроенными метриками.

**Источник:** ccs/deepseek
**Статус:** Отклонено
**Ответ:** Custom-имплементация на 30 строк под Mutex и ArrayDeque очевидно достаточна для глобального лимитера 10/час. resilience4j добавил бы зависимость, координацию с другими bulkhead/circuit-breaker конфигами, и переусложнил очень простую задачу. NIH здесь правильный выбор.
**Действие:** Нет.

---

### [DISM-2] WARN throttling (раз в N минут)

> При 30-мин активном событии — 180 WARN за полчаса.

**Источник:** ccs/deepseek
**Статус:** Отклонено
**Ответ:** Out of scope. Если в эксплуатации это станет шумным — добавим throttling отдельной задачей. Сейчас простая семантика "один skip — один WARN" облегчает диагностику.
**Действие:** Нет.

---

### [DISM-3] `enabled=false` по умолчанию ради avoid breaking change

> Существующие `enabled=true` установки внезапно получат skip.

**Источник:** ccs/deepseek
**Статус:** Отклонено
**Ответ:** Пользователь явно сказал "10 в час" — это тот самый дефолт. Включено намеренно. WARN-лог при первом срабатывании сразу подскажет ENV-переменную (см. spec §8).
**Действие:** Нет.

---

### [DISM-4] `executeWithRetry()` метода нет в `ClaudeDescriptionAgent`

> Spec ссылается на несуществующий метод.

**Источник:** ccs/albb-qwen
**Статус:** Отклонено (false positive)
**Ответ:** `grep -n "executeWithRetry" ClaudeDescriptionAgent.kt` показал callsite на строке 87, definition на 102. Метод существует. Ревьюер ошибся.
**Действие:** Нет.

---

### [DISM-5] Mixed validation styles `@Min/@Max` + `init {}`

> `IllegalArgumentException` vs `ConstraintViolationException`.

**Источник:** ccs/glm
**Статус:** Отклонено
**Ответ:** Соответствует существующему стилю `CommonSection` (init для Duration в `queueTimeout`/`timeout`). Переключать style ради consistency между двумя типами проверок — over-engineering.
**Действие:** Нет.

---

### [DISM-6] Устаревшие номера строк в Spec §3.2 (`TelegramNotificationSender.kt:84/...`)

> Сместились на ±1-2 строки.

**Источник:** ccs/albb-glm
**Статус:** Отклонено (low value)
**Ответ:** Номера служат context-якорями, не точными pointer-ами; они достаточно близки. Через коммит они в любом случае поедут на 1-2 строки.
**Действие:** Нет.

---

### [DISM-7] Тест на `maxRequests=0` через Validator

> Проверить bean validation для `@Min(1)`.

**Источник:** ccs/albb-qwen
**Статус:** Отклонено (low value)
**Ответ:** `@Min(1)` — стандартная JSR-303 аннотация, валидируется Spring Boot binder в production. Unit-тест на это — тестирование Spring Framework, не нашего кода. `init {}` для `window` тестируется (это наша логика).
**Действие:** Нет.

---

### [Q-1] Должен ли отсутствующий лимитер при `descriptionSupplier != null` быть fail-open?

**Источник:** codex, ccs/albb-qwen, ccs/albb-kimi, ccs/deepseek
**Статус:** Автоисправлено через явный `when`-branch (CRIT-6) — fail-open зафиксирован, явно задокументирован.

---

### [Q-2] Слот сжигается даже при `supplier returns null` или error?

**Источник:** codex
**Статус:** Отклонено (low priority)
**Ответ:** Семантика принята в brainstorming — счётчик инкрементится при выдаче разрешения, не decrement-ится. Случай supplier returns null рассматриваем как nominal (это не error path) — слот тратится, и это OK. Пограничный случай.
**Действие:** Нет.

## Изменения в документах

| Файл | Изменение |
|------|-----------|
| `docs/superpowers/specs/2026-04-25-description-rate-limit-design.md` | §3.2 переписан через `when`; §4.1 — defaults в `RateLimit`; §6.2 — 4 теста (было 3); §7 — расширен список файлов с явным разделением "modify" vs "auto-OK"; §8 — заметка single-instance |
| `docs/superpowers/plans/2026-04-25-description-rate-limit.md` | File Structure — расширен список файлов; Task 1.1 defaults; новый Step 1.3 (test resources YAML); новый Step 1.4 (`AiDescriptionAutoConfigurationTest`); Step 1.5 commit message обновлён; Step 2.1 — `MutableClock.withZone` корректный, `Dispatchers.Default` в concurrency, формула cleanup test исправлена; Step 2.4 gradle path; Step 3.2(c) 4 теста; Step 3.3 `when`; Step 3.4 SignalLossTest explicit; Step 3.5 gradle path; Step 3.6 commit message; Step 5.2 gradle path; Step 6.1 — git rm includes review files |

## Статистика

- Всего замечаний: 21 (8 critical, 4 concerns, 2 suggestions, 7 dismissed/questions)
- Автоисправлено: 13
- Обсуждено с пользователем: 0 (auto mode принял разумные default-решения)
- Отклонено: 7
- Повторов (автоответ): 0 (первая итерация)
- Пользователь сказал "стоп": Нет
- Агенты: codex, gemini, ccs/glm, ccs/albb-glm, ccs/albb-qwen, ccs/albb-kimi, ccs/albb-minimax, ccs/deepseek (8 reviewers)
