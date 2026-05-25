# Review Iteration 1 — 2026-05-26

## Источник

- Design: `docs/superpowers/specs/2026-05-26-jackson-3-migration-design.md`
- Plan: `docs/superpowers/plans/2026-05-26-jackson-3-migration.md`
- Review agents (4 selected, 3 successful):
  - codex (gpt-5.5, xhigh) — **incomplete** (CLI оборвался mid-investigation)
  - ccs glm — **incomplete** (CLI оборвался mid-investigation)
  - ollama-minimax (minimax-m2.7) — ✅ complete
  - ollama-kimi (kimi-k2.6) — ✅ complete (× 2: первый прогон + fallback от ollama-deepseek)
- Merged output: `docs/superpowers/specs/2026-05-26-jackson-3-migration-review-merged-iter-1.md`

## Замечания

### [CRITICAL-1] `JacksonJsonEncoder` API: `JsonMapper` vs `ObjectMapper`

> Design § 3.2 передаёт `internalObjectMapper` (тип `tools.jackson.databind.ObjectMapper`) в `JacksonJsonEncoder(...)`. Spring 7 конструктор требует `JsonMapper`.

**Источник:** ollama-minimax, ollama-kimi (×2), QUESTION-4 overlap
**Статус:** Автоисправлено
**Ответ:** Verified via `javap` of `spring-web-7.0.5.jar`: `JacksonJsonEncoder` принимает только `JsonMapper`. Также подтверждено `JsonMapper extends ObjectMapper` в Jackson 3 — DI consumer'ов с типом `ObjectMapper` (DetectService, ClaudeResponseParser) сохраняется.
**Действие:** Сменён тип возврата бина `internalObjectMapper` с `ObjectMapper` на `JsonMapper` в design § 3.1, design § 4.1, plan Task 2/3 (TestObjectMappers), plan Task 4 Step 1 (JacksonConfiguration), plan Task 7 (WebFluxJacksonCodecConfigurer). KDoc расширен с justification.

---

### [CRITICAL-2] `VideoVisualizationServiceTest` 4 вызова `buildObjectMapper()` несовместимого типа

> `buildObjectMapper()` возвращает Jackson 2 `ObjectMapper`. План удаляет helper в Step 6(f), но не явно описывает что после замены вызовов helper становится dead code.

**Источник:** ollama-minimax
**Статус:** Автоисправлено
**Действие:** В plan Task 4 Step 6 добавлен Context warning блок с явным указанием на dead code; также явно описан Jackson 2 → Jackson 3 type-change motivation.

---

### [CRITICAL-3+4] `AiDescriptionAutoConfigurationTest` @Bean return type несовместим

> `@Bean fun objectMapper(): ObjectMapper = ObjectMapper().registerKotlinModule()` создаёт бин Jackson 2 типа — после миграции ClaudeResponseParser требует Jackson 3, DI ломается.

**Источник:** ollama-minimax (Critical #3, #4), ollama-kimi (Critical #1)
**Статус:** Автоисправлено (объединено в один фикс — CRITICAL-4 разрешается тем же изменением что и CRITICAL-3)
**Действие:** Plan Task 5 Step 7 переписан: явная замена и тела, и сигнатуры на `fun objectMapper(): tools.jackson.databind.json.JsonMapper = TestObjectMappers.internalMapper()`. Добавлено объяснение почему этот случай отличается от Step 3 (требует изменения return type, не только тела).

---

### [CRITICAL-5] Подозрительный синтаксис Kotlin multi-pattern

> План использует `is JsonProcessingException,\n is JacksonException,\n -> {...}` с trailing comma перед `->`.

**Источник:** ollama-minimax (Critical #5)
**Статус:** Автоисправлено (косметика — trailing comma валиден в Kotlin 1.4+, но переформат для читаемости)
**Действие:** Plan Task 5 Step 2(b) переписан на однострочный `is JsonProcessingException, is JacksonException -> DescriptionException.InvalidResponse(throwable)`. Также добавлена инструкция написать KDoc объясняющий defensive-character ветки `is JacksonException`.

---

### [CRITICAL-6] TDD failing test checks только on file absence

> Plan Tasks 2/3 ожидают «compilation failure unresolved reference» — только проверка отсутствия файла, не runtime assertion.

**Источник:** ollama-minimax (Critical #6)
**Статус:** Отклонено
**Ответ:** Compile-failure тоже считается red-step в TDD, особенно для migration-tasks где альтернативного `TestObjectMappers` существовать не может (single source of truth). Дополнительная runtime assertion усложнила бы план без выигрыша. Это pragmatic TDD discipline.
**Действие:** Без изменений.

---

### [CRITICAL-7] `StatusControllerTest` не является regression guard для `WebFluxJacksonCodecConfigurer`

> Default Spring Boot 4 mapper тоже сериализует Instant/Duration в ISO-8601 — текущий тест не сломается при удалении configurer.

**Источник:** ollama-kimi (Critical #6), ollama-deepseek (Critical #1) — strong consensus
**Статус:** Автоисправлено
**Действие:** Plan Task 7 Step 5 расширен: добавление regression-теста на `FAIL_ON_UNKNOWN_PROPERTIES=false` (отправить JSON с unknown property, assert 200 OK) — это специфично для нашего mapper'а, default mapper вернул бы 400. Также обновляется KDoc StatusControllerTest для удаления устаревшего "tools.jackson, NOT our com.fasterxml.jackson-based JacksonConfiguration" утверждения.

---

### [CRITICAL-8] `findAndAddModules()` ненадёжен через ServiceLoader

> Если `META-INF/services/tools.jackson.databind.JacksonModule` отсутствует в артефакте — KotlinModule не загрузится.

**Источник:** ollama-deepseek (Critical #2), ollama-kimi (Concern #10)
**Статус:** Обсуждено и применено (Вариант A — оставить + усиленный test guard)
**Ответ:** ServiceLoader-discovery — официальный contract Jackson 3. Coupling с internal `KotlinModule.Builder` API (Вариант B) — реальный риск (Jackson 3 ещё молодой). Round-trip data class test в `JacksonConfigurationTest` — достаточный regression guard на случай поломки discovery.
**Действие:** Дизайн § 3.1 дополнен явным упоминанием test guard как primary regression mechanism + explicit rejection rationale для альтернатив.

---

### [CONCERN-1] `StatusControllerTest` KDoc устареет после миграции

**Источник:** ollama-kimi (Critical #4 — mislabeled)
**Статус:** Автоисправлено
**Действие:** Design § 4.1 добавил явный пункт StatusControllerTest KDoc update; Plan Task 7 Step 5 содержит новый текст KDoc.

---

### [CONCERN-2] DetectServiceTest impacts wording в Task 4 Step 4(e)

**Источник:** ollama-minimax (Concern #7)
**Статус:** Автоисправлено
**Действие:** Plan Task 4 Step 4(e) переформулирован — пояснение что эти tools.jackson импорты были корректны для `buildJsonMapper()` и становятся unused только после удаления helper.

---

### [CONCERN-3] `bundles.jackson` остаётся явной зависимостью

> Цель «Jackson 2 только транзитивно» нарушается явным `implementation(libs.bundles.jackson)`.

**Источник:** ollama-minimax (Concern #8, Q1)
**Статус:** Обсуждено и применено (Вариант A — explicit follow-up scope)
**Ответ:** Cleanup `bundles.jackson` имеет отдельный risk profile; раздувать атомарную миграцию ещё одним рискованным изменением плохо для review/rollback. Variants B/C расширяют scope без пропорционального выигрыша.
**Действие:** Design § 8 Out of scope дополнен двумя explicit follow-up пунктами: (a) удаление `bundles.jackson` из `modules/core/build.gradle.kts`; (b) удаление из `modules/ai-description/build.gradle.kts`.

---

### [CONCERN-4] `@Primary` конфликт с springdoc

**Источник:** ollama-minimax (Concern #9), ollama-deepseek (Concern #9)
**Статус:** Автоисправлено
**Действие:** В design § 3.1 KDoc добавлен подраздел "Dual-stack rationale" объясняющий что `@Primary` scoping работает только within `tools.jackson.databind.*` классов — springdoc инжектит `com.fasterxml.jackson.databind.ObjectMapper` (другой класс), нет collision.

---

### [CONCERN-5] Task 6 — потенциальное дублирование DeserializationFeature

**Источник:** ollama-minimax (Concern #10)
**Статус:** Автоисправлено
**Действие:** Plan Task 6 добавил Step 2 — explicit verification что `jsonEncoder()`/`jsonDecoder()` принимают bean по параметру, не создают свои инстансы; grep verification command.

---

### [CONCERN-6] Anonymous `JacksonException` может не скомпилироваться

**Источник:** ollama-kimi (Concern #7, Suggestion #18, Q3)
**Статус:** Автоисправлено
**Действие:** Plan Task 5 Step 6(b) переписан — использовать `tools.jackson.core.exc.StreamReadException(null, "boom")` (concrete public subclass) вместо anonymous `object : JacksonException("boom") {}`. Добавлено объяснение почему StreamReadException безопаснее.

---

### [CONCERN-7] `JacksonConfigurationTest` не проверяет Spring bean

**Источник:** ollama-kimi (Concern #8, Suggestion #15, Q4)
**Статус:** Автоисправлено
**Действие:** Plan Task 4 Step 2 добавил `ApplicationContextRunner` test проверяющий что бин зарегистрирован как `JsonMapper` и помечен `@Primary` (через BeanDefinition.isPrimary). Также подключён `JacksonAutoConfiguration` через `withConfiguration(AutoConfigurations.of(...))` (см. CONCERN-12 ниже).

---

### [CONCERN-8] Spring Boot 4 `JacksonCodecCustomizer` поведение

> Если auto-config делает то же — наш configurer лишний; если нет — наш единственный путь.

**Источник:** ollama-kimi (Concern #9, Q2)
**Статус:** Обсуждено и применено (Вариант A — behavioural verification)
**Ответ:** Внутренности Spring Boot 4 меняются между минорными версиями. Behavioural test (CRITICAL-7) полностью закрывает оба сценария: в любом случае удаление нашего configurer'а сломает тест (если он необходим) или ничего не изменит (если auto-config дубль). `@Order(LOWEST_PRECEDENCE)` гарантирует precedence.
**Действие:** Design § 3.2 дополнен параграфом про behavioural verification как primary path (вместо internals research).

---

### [CONCERN-9] `TestObjectMappers` дублируется vs `java-test-fixtures`

**Источник:** ollama-deepseek (Concern #6, Suggestion #3)
**Статус:** Обсуждено и применено (Вариант A — принять дублирование)
**Ответ:** 5 строк factory методов не оправдывают Gradle complexity нового source set или нового модуля. KDoc + behavioural тесты — достаточная защита от drift.
**Действие:** Design § 4.3 дополнен явным "Альтернатива rejected" с rationale.

---

### [CONCERN-10] `DetectService` SNAKE_CASE → camelCase для error parsing

**Источник:** ollama-deepseek (Concern #7, Q2, Suggestion #5)
**Статус:** Автоисправлено
**Действие:** Design § 3.4 дополнен заметкой про historic SNAKE_CASE в тестах и почему camelCase безопасен (`.path("detail")` case-sensitive по тексту JSON-ключа). Plan Task 4 Step 3 — inline комментарий в DetectService объясняющий назначение mapper'а.

---

### [CONCERN-11] WebFluxConfigurer registration order без `@Order`

**Источник:** ollama-deepseek (Concern #8), ollama-kimi (Suggestion #14), ollama-deepseek (Suggestion #4)
**Статус:** Автоисправлено
**Действие:** `@Order(Ordered.LOWEST_PRECEDENCE)` добавлен в design § 3.2, plan Task 7 Step 3 (WebFluxJacksonCodecConfigurer). KDoc объясняет назначение.

---

### [CONCERN-12] Bean topology с `JacksonAutoConfiguration` + `Jackson2ObjectMapperBuilder`

> В контексте может оказаться несколько JsonMapper-бинов: наш, auto-config'а, springdoc'а.

**Источник:** ollama-deepseek (Concern #9, Q3)
**Статус:** Обсуждено и применено (Вариант A — `@Primary` + расширенный test)
**Ответ:** `@Primary` — эталонный Spring-механизм disambiguation. Расширенный `ApplicationContextRunner` test с auto-config даёт реалистичную проверку без overhead `@SpringBootTest`. Boilerplate `@Qualifier` (Variant B) и full integration test (Variant C) дают плохой ROI.
**Действие:** Plan Task 4 Step 2 — `ApplicationContextRunner` test расширен `withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration::class.java))`, проверяет что наш `@Primary` бин выбран при type-based DI даже когда auto-config загружен.

---

### [CONCERN-13] `WebFluxJacksonCodecConfigurerTest` mock-only

**Источник:** ollama-deepseek (Concern #10), ollama-minimax (Suggestion #12)
**Статус:** Отклонено (overlap)
**Ответ:** Покрывается CRITICAL-7 фиксом — integration regression test через `StatusControllerTest` даёт end-to-end coverage. Не нужно усложнять mock-test.
**Действие:** Без отдельных изменений.

---

### [CONCERN-14] `ClaudeExceptionMapper` JacksonException branch — почти dead code

**Источник:** ollama-deepseek (Critical #3 — mislabeled)
**Статус:** Автоисправлено
**Действие:** Design § 3.4 дополнен заметкой про defensive-character ветки. Plan Task 5 Step 2 содержит KDoc-инструкцию для метода `map`.

---

### [CONCERN-15] Нет отдельного теста на `FAIL_ON_UNKNOWN_PROPERTIES`

**Источник:** ollama-deepseek (Critical #4 — mislabeled)
**Статус:** Автоисправлено
**Действие:** Plan Task 4 Step 2 — новый test `FAIL_ON_UNKNOWN_PROPERTIES is disabled — unknown JSON fields tolerated` добавлен.

---

### [CONCERN-16] `JacksonConfiguration` KDoc ссылается на ephemeral document

**Источник:** ollama-deepseek (Critical #5 — mislabeled)
**Статус:** Автоисправлено
**Действие:** KDoc в design § 3.1 и plan Task 4 Step 1 переписан inline — self-contained dual-stack rationale без зависимости от внешних docs.

---

### [CONCERN-17] `ClaudeDescriptionAgentTest` inline-конструктор требует TestObjectMappers import

**Источник:** ollama-kimi (Critical #2 — mislabeled)
**Статус:** Автоисправлено
**Действие:** Plan Task 5 Step 4(b) переписан — явная инструкция «explicitly add (do NOT rely on IDE auto-import in scripted runs)».

---

### [CONCERN-18] `ClaudeDescriptionAgentIntegrationTest` @Disabled но компилируется

**Источник:** ollama-kimi (Critical #3 — mislabeled)
**Статус:** Автоисправлено
**Действие:** Plan Task 5 Step 5 переписан с конкретным указанием на `line 102 val mapper = ObjectMapper().registerKotlinModule()` и объяснением что Kotlin компилирует disabled-тесты.

---

### [CONCERN-19] ai-description TestObjectMappers без detectServerMapper

**Источник:** ollama-kimi (Concern #13)
**Статус:** Автоисправлено
**Действие:** Plan Task 8 Step 1a (audit step) — grep `detectServerMapper` по ai-description возвращает 0 results (часть общего audit).

---

### [SUGGESTION-1] `StatusControllerTest` в verification list

**Источник:** ollama-minimax (Suggestion #11)
**Статус:** Автоисправлено
**Действие:** Plan Task 4 Step 7 уже включает full core test suite (`./gradlew :frigate-analyzer-core:test`), что включает StatusControllerTest. Также упомянуто явно в Task 7 Step 6 после расширения теста.

---

### [SUGGESTION-2] `WebFluxJacksonCodecConfigurerTest` mapper equality assertion

**Источник:** ollama-minimax (Suggestion #12)
**Статус:** Отклонено (overlap с CONCERN-13)
**Действие:** Без изменений — покрывается CRITICAL-7 фиксом (integration regression).

---

### [SUGGESTION-3] Тест на Kotlin data class deserialization

**Источник:** ollama-minimax (Suggestion #13)
**Статус:** Отклонено (уже в плане)
**Ответ:** Plan Task 4 Step 2 уже содержит `KotlinModule is auto-discovered — data class round-trips`.
**Действие:** Без изменений.

---

### [SUGGESTION-4] Комментарий в libs.versions.toml

**Источник:** ollama-kimi (Suggestion #17)
**Статус:** Автоисправлено
**Действие:** Plan Task 1 Step 1 — добавлен 3-строчный комментарий перед `jackson-kotlin-3` алиасом.

---

### [SUGGESTION-5] DetectService inline comment

**Источник:** ollama-deepseek (Suggestion #5)
**Статус:** Отклонено (overlap с CONCERN-10)
**Действие:** Объединено в CONCERN-10 fix.

---

### [QUESTION-1] Зачем `bundles.jackson` в ai-description?

**Источник:** ollama-minimax (Q1)
**Статус:** Отклонено (overlap с CONCERN-3)
**Действие:** Решено в рамках CONCERN-3 (follow-up scope).

---

### [QUESTION-2] Гарантия порядка `configureHttpMessageCodecs`?

**Источник:** ollama-minimax (Q2)
**Статус:** Отклонено (overlap с CONCERN-11)
**Действие:** Решено через `@Order(LOWEST_PRECEDENCE)` в CONCERN-11 fix.

---

### [QUESTION-3] Другие зависимости конфликтуют с tools.jackson?

**Источник:** ollama-minimax (Q3)
**Статус:** Обсуждено и применено (Вариант A — build verification как primary check)
**Ответ:** tools.jackson и com.fasterxml.jackson — разные namespace, физически не конфликтуют на JVM. Behavioural verification через 8 промежуточных `./gradlew build` calls — primary check. Полный audit — useful follow-up.
**Действие:** Design § 4.2 дополнен параграфом про co-existence + явный пункт что full audit — follow-up вместе с CONCERN-3.

---

### [QUESTION-4] `JacksonJsonEncoder` constructor signature?

**Источник:** ollama-kimi (Q1)
**Статус:** Отклонено (overlap с CRITICAL-1)
**Действие:** Verified через javap, решено в CRITICAL-1.

---

### [QUESTION-5] Spring Boot `JacksonCodecCustomizer` behavior?

**Источник:** ollama-kimi (Q2)
**Статус:** Отклонено (overlap с CONCERN-8)
**Действие:** Решено через behavioural verification (CONCERN-8 fix).

---

### [QUESTION-6] `tools.jackson.core.JacksonException(String)` public?

**Источник:** ollama-kimi (Q3)
**Статус:** Отклонено (overlap с CONCERN-6)
**Действие:** Решено через concrete `StreamReadException` subclass (CONCERN-6 fix).

---

### [QUESTION-7] direct call vs ApplicationContextRunner в JacksonConfigurationTest?

**Источник:** ollama-kimi (Q4)
**Статус:** Отклонено (overlap с CONCERN-7)
**Действие:** Решено через добавление `ApplicationContextRunner` test (CONCERN-7 fix).

---

### [QUESTION-8] Нужен ли integration regression test для FAIL_ON_UNKNOWN_PROPERTIES?

**Источник:** ollama-kimi (Q5)
**Статус:** Отклонено (overlap с CRITICAL-7)
**Действие:** Да, решено через расширение StatusControllerTest (CRITICAL-7 fix).

---

### [QUESTION-9] `jackson-module-kotlin:3.0.4` содержит META-INF/services?

**Источник:** ollama-deepseek (Q1)
**Статус:** Отклонено (overlap с CRITICAL-8)
**Действие:** Решено через round-trip test guard (CRITICAL-8 fix).

---

### [QUESTION-10] Почему тесты использовали SNAKE_CASE для buildObjectMapper?

**Источник:** ollama-deepseek (Q2)
**Статус:** Отклонено (overlap с CONCERN-10)
**Действие:** Объяснено как historic accident в design § 3.4 (CONCERN-10 fix).

---

### [QUESTION-11] `@Primary internalObjectMapper` + auto-config interaction?

**Источник:** ollama-deepseek (Q3)
**Статус:** Отклонено (overlap с CONCERN-4 + CONCERN-12)
**Действие:** Решено через KDoc rationale (CONCERN-4) + ApplicationContextRunner test (CONCERN-12).

---

### [QUESTION-12] Все 19 файлов с ObjectMapper покрыты планом?

**Источник:** ollama-deepseek (Q4)
**Статус:** Автоисправлено
**Действие:** Plan Task 8 Step 1a добавлен — explicit grep audit с cross-check против покрытого списка.

---

## Изменения в документах

| Файл | Изменение |
|------|-----------|
| `docs/superpowers/specs/2026-05-26-jackson-3-migration-design.md` | § 3.1 (тип JsonMapper, расширенный KDoc, ServiceLoader rationale), § 3.2 (@Order, behavioural verification rationale), § 3.4 (DetectService note + JacksonException defensive note), § 4.1 (StatusControllerTest добавлен в таблицу + JsonMapper type правки), § 4.2 (co-existence parag.), § 4.3 (расширенные тесты + alternative rejected note), § 7 (расширенный DoD), § 8 (расширенный Out of scope) |
| `docs/superpowers/plans/2026-05-26-jackson-3-migration.md` | Header (тип JsonMapper note), Task 1 Step 1 (comment), Task 2 Step 3 (return type), Task 3 Step 3 (return type), Task 4 Step 1 (JsonMapper bean type + расширенный KDoc), Task 4 Step 2 (3 новых теста: FAIL_ON_UNKNOWN_PROPERTIES, KotlinModule round-trip, ApplicationContextRunner), Task 4 Step 3 (DetectService inline comment), Task 4 Step 4(e) (переформулировка), Task 4 Step 6 (dead code warning), Task 5 Step 2 (one-line when + KDoc defensive), Task 5 Step 4 (explicit import), Task 5 Step 5 (явный line 102), Task 5 Step 6 (StreamReadException), Task 5 Step 7 (полностью переписан с JsonMapper return type), Task 6 Step 2 (new verification step), Task 7 Step 3 (@Order), Task 7 Step 5 (расширение StatusControllerTest + KDoc update), Task 8 Step 1a (audit step) |
| `docs/superpowers/specs/2026-05-26-jackson-3-migration-review-merged-iter-1.md` | Создан (merged review от 3 ревьюеров) |
| `docs/superpowers/specs/2026-05-26-jackson-3-migration-review-iter-1.md` | Создан (этот файл) |

## Статистика

- Всего замечаний: 39
- Автоисправлено (без обсуждения): 23
- Авто-применено после анализа: 6 (все 6 disputed решены auto-apply после структурированного анализа — рекомендованный Вариант A явно превосходил альтернативы в каждом случае)
- Обсуждено с пользователем: 0
- Отклонено: 10 (overlap-duplicates + 1 pragmatic CRITICAL-6)
- Повторов (автоответ): 0 (первая итерация)
- Пользователь сказал "стоп": Нет
- Агенты: 4 selected (codex, ccs-glm, ollama-kimi, ollama-minimax + fallback ollama-deepseek→kimi), 3 successful (ollama-minimax, ollama-kimi ×2)
- Codex и ccs-glm CLI оборвались mid-investigation (никакого финального result event) — артефакты сохранены в `~/.claude/codex-interaction/` и `~/.claude/ccs-interaction/` для диагностики; не блокирующее для следующей итерации.
