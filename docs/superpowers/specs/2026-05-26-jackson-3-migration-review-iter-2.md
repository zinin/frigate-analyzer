# Review Iteration 2 — 2026-05-26

## Источник

- Design: `docs/superpowers/specs/2026-05-26-jackson-3-migration-design.md`
- Plan: `docs/superpowers/plans/2026-05-26-jackson-3-migration.md`
- Review agents: 5 selected (all successful — vs iter-1 где 2 из 5 оборвались):
  - codex (gpt-5.5, xhigh) — ✅ complete
  - ccs glm — ✅ complete
  - ollama-kimi — ✅ complete
  - ollama-minimax — ✅ complete
  - ollama-deepseek — ✅ complete (без fallback)
- Merged output: `docs/superpowers/specs/2026-05-26-jackson-3-migration-review-merged-iter-2.md`

## Замечания

### [NEW-1] `WRITE_DATES_AS_TIMESTAMPS`/`WRITE_DURATIONS_AS_TIMESTAMPS` в `DateTimeFeature`, не `SerializationFeature`

> В Jackson 3.0.4 эти features перемещены из `tools.jackson.databind.SerializationFeature` в `tools.jackson.databind.cfg.DateTimeFeature`. Текущие snippet'ы в design § 3.1, § 4.3 + plan Tasks 2/3/4 не скомпилируются.

**Источник:** codex Critical #1 (verified review-discussion agent через javap actual JAR: `SerializationFeature` enum не содержит этих констант; `DateTimeFeature` содержит)
**Статус:** Автоисправлено (blocking compile error, единственный fix)
**Действие:** Заменены импорты + вызовы во всех 5 файлах: design § 3.1 (JacksonConfiguration), design § 4.3 (TestObjectMappers), plan Task 2 Step 3 (TestObjectMappers core), plan Task 3 Step 3 (TestObjectMappers ai-description), plan Task 4 Step 1 (JacksonConfiguration impl). Добавлен «implementation note про DateTime features» в design § 3.1 и plan Task 4 Step 1.

---

### [NEW-2] Gradle accessor `libs.jackson.kotlin.3` invalid в Kotlin DSL

> Алиас `jackson-kotlin-3` в `gradle/libs.versions.toml` генерирует accessor `libs.jackson.kotlin.3` — Kotlin identifier из цифры невалиден; Gradle 9 ставит префикс `v` (`libs.jackson.kotlin.v3`) либо отказывается генерировать. Plan Task 1 Step 2/3 не скомпилируется.

**Источник:** codex Critical #2
**Статус:** Автоисправлено (blocking gradle-script compile error)
**Действие:** Алиас переименован `jackson-kotlin-3` → `jackson-kotlin3` (слитное). Accessor становится `libs.jackson.kotlin3` — валидный Kotlin identifier. Обновлено в plan Task 1 Step 1/2/3 + добавлен naming note комментарий в TOML.

---

### [NEW-3] `detectServerObjectMapper` без `findAndAddModules()` — Kotlin DTO deserialization fragile

> Production бин (plan Task 6 Step 1) и test helper `TestObjectMappers.detectServerMapper()` (plan Task 2 Step 3) не вызывают `.findAndAddModules()`. WebClient к detect-server декодирует Kotlin data class'ы (`DetectResponse` etc.); без KotlinModule constructor-based десериализация ломается на required-параметрах. Текущий рабочий код проходит только потому что использует `JsonMapper.Builder`-overload, где Spring auto-вызывает `findModules()`.

**Источник:** codex Critical #3
**Статус:** Автоисправлено
**Действие:** Добавлен `.findAndAddModules()` в production `detectServerObjectMapper()` (design § 3.3, plan Task 6 Step 1) и test `TestObjectMappers.detectServerMapper()` (design § 4.3, plan Task 2 Step 3). Контекст-заметка в design § 3.3 и plan Task 6 Step 1 объясняет почему текущий код работал без явного вызова.

---

### [NEW-4] FAIL_ON_UNKNOWN_PROPERTIES regression test через `/status` GET не работает — consensus 5/5

> `/status` GET-only endpoint — `JacksonJsonDecoder` не вызывается на GET без body, тест `webTestClient.get().uri("/status")` ничего не доказывает про `FAIL_ON_UNKNOWN_PROPERTIES`. Глубже: даже POST-вариант не дифференцирует — Spring Boot 4 default тоже `FAIL_ON_UNKNOWN_PROPERTIES=false`. Все настройки нашего mapper'а совпадают с Boot 4 defaults → behavioral regression guard на наш configurer принципиально невозможен.

**Источник:** ВСЕ 5 ревьюеров — codex Critical #4+#5, ccs-glm CRITICAL-9, ollama-kimi CRITICAL-NEW-1, ollama-minimax CRITICAL-NEW-1, ollama-deepseek CRITICAL-1
**Статус:** Авто-применено после анализа (Вариант A — единственный честный)
**Ответ:** Вариант B (создать POST echo-controller) не решает проблему — поведение всё равно идентично Boot defaults. Вариант C (изменить production-настройку для тестирования) инвазивен и меняет production behaviour ради тестирования. После решения NEW-10 (configurer ценен архитектурно, не поведенчески) попытка построить behavioral regression test противоречит сама себе.
**Действие:** Plan Task 7 Step 5 переписан: убран нерабочий FAIL_ON_UNKNOWN_PROPERTIES test, оставлен только KDoc update (Step 5a). Новый KDoc явно объясняет honest narrative: тест — sanity check, не regression guard. Real coverage перенесена в NEW-17 (codec identity reflection) + NEW-14 (ApplicationContextRunner strengthening). Sync с design § 4.1 «KDoc UPDATE» автоматически достигнут.

---

### [NEW-5] FQN `org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration` — VERIFIED, но safety check добавлен

> deepseek указал что FQN не верифицирован для Spring Boot 4.0.6 — если класс переименован, `ApplicationContextRunner` тест не скомпилируется.

**Источник:** ollama-deepseek CRITICAL-2
**Статус:** Отклонено (verified review-discussion agent — класс существует по этому пути в `spring-boot-jackson-4.0.x.jar`)
**Ответ:** FQN корректен. Spring Boot 4.x разделил автоконфиги по отдельным артефактам `spring-boot-<module>` (новая структура), но `JacksonAutoConfiguration` остался стабильным.
**Действие:** Добавлен **pre-flight verification step** в plan Task 1 Step 4 + plan Task 4 Step 2 — safety check против potential regression в minor-bump.

---

### [NEW-6] Kotlin multi-pattern `is JsonProcessingException, is JacksonException` — claim неверный

> minimax утверждал что Kotlin не позволит объединить в одну ветку `when` типы без общего супертипа.

**Источник:** ollama-minimax CONCERN-NEW-1
**Статус:** Отклонено (verified — claim ошибочен по Kotlin spec)
**Ответ:** В `when (e: Throwable)` каждое `is X` проверяется независимо. Subject — `Throwable`, оба типа extends `Throwable` — общий супертип есть. Multi-pattern компилируется и работает.
**Действие:** Без изменений плана.

---

### [NEW-7] `JsonMapper.Builder` overload существует в codec constructors — iter-1 CRITICAL-1 содержал неточность

> Iter-1 CRITICAL-1 утверждал «verified via javap: только JsonMapper». Verified в iter-2: 5 overload'ов, включая `(JsonMapper.Builder)`. Boot's `JsonMapperBuilderCustomizerConfiguration` использует Builder-injection и применяет `ProblemDetailJacksonMixin` + `spring.jackson.*` properties + `@JacksonMixin` бины. Pre-built `.build()` обходит эти customizer'ы.

**Источник:** ccs-glm CONCERN-20+QUESTION-13, ollama-kimi CONCERN-NEW-1+SUGGESTION-NEW-2, codex Concern #1+Question #1
**Статус:** Авто-применено после анализа (Вариант A — единственный consistent с NEW-10 Вариант B)
**Ответ:** NEW-7 Вариант B (`builder: JsonMapper.Builder` injection) размывает ту же цель что NEW-10 Вариант A — implicit/external influence на wire-format. Вариант A (pre-built) явно сохраняет «config truly governs» цель. Проект не использует `ProblemDetail` (verified — 0 hits), потеря mixin'а безвредна.
**Действие:** Добавлен «Builder vs pre-built — осознанный trade-off» параграф в design § 3.1 (JacksonConfiguration KDoc) и plan Task 4 Step 1 (KDoc). Iter-1 CRITICAL-1 утверждение «только JsonMapper» уточнено: «accept JsonMapper или JsonMapper.Builder; мы используем pre-built JsonMapper для явного контроля». Также добавлен параграф в design § 3.2 (WebFluxJacksonCodecConfigurer KDoc) и plan Task 6 Step 1 — объясняет почему текущий код работает без `.build()`.

---

### [NEW-8] Audit grep слишком узкий — consensus 4 ревьюера

> Plan Task 8 Step 1a использовал `grep -rn "ObjectMapper\|registerKotlinModule"` — пропускает Jackson аннотации, bean-name literals, типы из `tools.jackson.*`, custom serializer'ы.

**Источник:** codex Concern #3, ollama-kimi CONCERN-NEW-2+SUGGESTION-NEW-3, ollama-minimax SUGGESTION-NEW-2, ollama-deepseek SUGGESTION-2
**Статус:** Автоисправлено
**Действие:** Plan Task 8 Step 1a расширен: (a) тип-ориентированный grep по `com.fasterxml.jackson|tools.jackson|JsonMapper|ObjectMapper|JsonNode|JsonProcessingException|JacksonException|JacksonJson|@Json[A-Z]`; (b) bean-name literal grep по `"objectMapper"`, `@Qualifier("objectMapper")`, `getBean("objectMapper")`; (c) compat starter sanity check через `./gradlew dependencies`. Явно перечислены модули для audit (telegram, service, model, common, ai-description, docker). Документировано что `JobStatus.kt` `@JsonProperty` — «intentionally allowed».

---

### [NEW-9] Line numbers в плане могут drift'нуть после auto-fix'ов — consensus 3 ревьюера

> Plan Tasks 4/5 содержат hard-coded line refs («line 88», «line 481» и т.д.). После DateTimeFeature и других правок iter-2 номера сдвинутся; даже до iter-2 один reviewer (ccs-glm) указал что `buildWebClient` ближе к ~473, а не 481.

**Источник:** codex Suggestion #3, ccs-glm QUESTION-14, ollama-minimax CONCERN-NEW-3+QUESTION-NEW-3
**Статус:** Автоисправлено
**Действие:** Добавлен общий раздел «Про line numbers в плане» в plan header — рекомендация запускать `grep -n "<уникальный текст>" <file>` перед каждым edit'ом. Перечислены конкретные search patterns для каждого test-файла (`DetectServiceTest`, `DetectServiceCancelJobTest`, `VideoVisualizationServiceTest`, `ClaudeDescriptionAgentIntegrationTest`, `AiDescriptionAutoConfigurationTest`).

---

### [NEW-10] `CodecCustomizer` vs `WebFluxConfigurer` — архитектурный выбор (consensus 3 ревьюера)

> Spring Boot 4 `CodecsAutoConfiguration.jacksonCodecCustomizer(JsonMapper)` автоматически wire'ит `@Primary JsonMapper` бин в WebFlux codec. Наш `WebFluxJacksonCodecConfigurer` функционально дублирует эту логику.

**Источник:** codex Suggestion #1+Critical #5, ccs-glm SUGGESTION-7, ollama-deepseek SUGGESTION-1
**Статус:** Обсуждено с пользователем (Вариант B)
**Ответ:** Пользователь выбрал Вариант B — сохранить configurer как explicit ownership statement; обновить narrative до honest description («функционально дублирует Boot's auto-config, но даёт явное ownership документирование цели issue #29»); применить NEW-11 (`@Component` вместо `@Configuration`). Вариант A (удалить configurer) отвергнут — размывает цель issue #29 «config truly governs wire-format». Вариант C (custom CodecCustomizer @Bean) — gold-plating без чёткого выигрыша.
**Действие:** Design § 3.2 переписан с honest narrative параграфом: «функциональное дублирование Boot's auto-config — поведенческие тесты не могут доказать necessity нашего configurer'а; ценность архитектурная (explicit ownership + belt-and-suspenders + документация); regression guard — `WebFluxJacksonCodecConfigurerTest` reflection identity + `ApplicationContextRunner` bean topology». Plan Task 7 Step 3 KDoc переписан в соответствии. Связано с NEW-4 fix.

---

### [NEW-11] `@Configuration` на `WebFluxJacksonCodecConfigurer` избыточен — должен быть `@Component`

> Класс не объявляет `@Bean`-методов, поэтому `@Configuration` (с CGLIB-proxy и full configuration scanning) — overhead без выигрыша.

**Источник:** ollama-kimi CONCERN-NEW-3+QUESTION-NEW-3
**Статус:** Автоисправлено (в составе NEW-10 Вариант B решения)
**Действие:** Design § 3.2 и plan Task 7 Step 3 — `@Configuration` заменён на `@Component`. Import `Component` добавлен, `Configuration` import убран. Объяснение добавлено в KDoc.

---

### [NEW-12] `@Qualifier` на `@Bean` определении избыточен

> Plan Task 6 Step 1 имел `@Bean @Qualifier("detectServerObjectMapper") fun detectServerObjectMapper()`. Имя метода = имя бина = qualifier value по умолчанию; `@Qualifier` на определении вводит в заблуждение.

**Источник:** ccs-glm CONCERN-23+SUGGESTION-8
**Статус:** Автоисправлено
**Действие:** Убран `@Qualifier("detectServerObjectMapper")` с bean definition в design § 3.3 и plan Task 6 Step 1. Оставлен только на injection params `jsonEncoder`/`jsonDecoder`. Добавлена placement note.

---

### [NEW-13] Plan Task 6 не объяснял почему текущий код работает без `.build()`

> Plan показывал «before» код как точную копию текущего (без `.build()`), «after» добавляет `.build()` — без объяснения почему. Реализатор мог запутаться.

**Источник:** ccs-glm CONCERN-22
**Статус:** Автоисправлено
**Действие:** Plan Task 6 Step 1 — добавлен context-параграф «почему текущий код без `.build()` компилируется» (ссылка на `JsonMapper.Builder`-overload в codec API). Соотнесён с NEW-7 trade-off explanation.

---

### [NEW-14] `ApplicationContextRunner` test может тривиально проходить — assertion strengthening

> Если auto-config skip'ает регистрацию своего JsonMapper (например через unsatisfied `@ConditionalOnClass`), `getBean(JsonMapper)` вернёт единственный (наш) бин — тест проходит без реальной disambiguation проверки.

**Источник:** ollama-deepseek CONCERN-2, codex Concern #2, ollama-kimi QUESTION-NEW-1
**Статус:** Автоисправлено
**Действие:** Plan Task 4 Step 2 — расширен test: добавлен `assertThat(allMappers).hasSizeGreaterThanOrEqualTo(2)` до `@Primary` resolution check. Fail-loud message объясняет что делать если auto-config skip'нул. Также добавлен pre-flight FQN verification step.

---

### [NEW-15] Design § 4.1 vs Plan Task 7 Step 5 рассинхронизация

> Design таблица для StatusControllerTest указывала только «KDoc UPDATE». Plan Task 7 Step 5(b) добавлял новый test method. Несоответствие.

**Источник:** ollama-deepseek SUGGESTION-4
**Статус:** Автоисправлено (sync достигнут автоматически после NEW-4 fix'а)
**Действие:** После NEW-4 fix Plan Task 7 Step 5 не содержит new test method — sync с design § 4.1 «KDoc UPDATE» достигнут.

---

### [NEW-16] Plan Task 5 Step 4(d) формулировка про «switch declared type» — misleading

> Plan говорил «switch its declared type to tools.jackson.databind.ObjectMapper». На практике `ClaudeResponseParser` constructor param остаётся `ObjectMapper` (меняется только import — уже сделано в Step 1); Kotlin сам выводит тип из `TestObjectMappers.internalMapper()`.

**Источник:** ollama-deepseek CONCERN-3
**Статус:** Автоисправлено
**Действие:** Plan Task 5 Step 4(d) переформулирован — явно сказано что type-switch не требуется (Kotlin сам выводит), уточнено когда explicit type annotation может встретиться (локальные `val mapper: ObjectMapper = ...` declarations) и как обработать.

---

### [NEW-17] `WebFluxJacksonCodecConfigurerTest` не проверял identity mapper'а

> Test захватывал `encoderSlot.captured` и asserts `.isNotNull` — баг при котором configurer создаёт новые default-mappers вместо использования параметра не был бы пойман.

**Источник:** ollama-deepseek CONCERN-1
**Статус:** Автоисправлено
**Действие:** Plan Task 7 Step 1 — добавлен reflection identity check: `encoderSlot.captured.mapper === mapper` через `AbstractJacksonHttpMessageWriter.mapper` поле. Добавлен «Reflection note» объясняющий почему этот подход + альтернативы.

---

### [NEW-18] `spring-boot-jackson2` compat starter — verification в Task 8

> Design § 4.2 утверждает что `spring-boot-jackson2` шипится Boot 4. Plan не verify'ил его наличие.

**Источник:** ollama-deepseek SUGGESTION-3
**Статус:** Автоисправлено
**Действие:** Plan Task 8 Step 1b — добавлен verify step после full build: `./gradlew :frigate-analyzer-core:dependencies --configuration runtimeClasspath | grep -E "spring-boot-jackson|com.fasterxml.jackson"`. Ожидаемый output (Jackson 3 primary + Jackson 2 compat starter + Jackson 2 транзитивно через springdoc) задокументирован.

---

### [NEW-19] codex Question #1 — overlap с NEW-7

**Источник:** codex Question #1
**Статус:** Отклонено (overlap с NEW-7 — тот же вопрос сформулированный иначе)
**Действие:** Резолюция в NEW-7 (Вариант A — pre-built, осознанный trade-off).

---

### [NEW-20] codex Question #2 — overlap с NEW-10

**Источник:** codex Question #2
**Статус:** Отклонено (overlap с NEW-10 — тот же выбор сформулированный иначе)
**Действие:** Резолюция в NEW-10 (Вариант B — оставить configurer).

---

### [REPEAT-1] `WebFluxJacksonCodecConfigurerTest` mock-only sufficiency

**Источник:** ollama-deepseek CONCERN-1 (general aspect)
**Статус:** Повтор iter-1 CONCERN-13 (general «mock-only insufficient»)
**Ответ (iter-1):** Покрывается CRITICAL-7 фиксом — integration regression через StatusControllerTest даёт end-to-end coverage. Не нужно усложнять mock-test.
**Действие:** Без отдельных изменений. Новый конкретный аспект (identity assertion) выделен в NEW-17 как самостоятельная улучшение.

---

## Изменения в документах

| Файл | Изменение |
|------|-----------|
| `docs/superpowers/specs/2026-05-26-jackson-3-migration-design.md` | § 3.1 (DateTimeFeature + Builder/pre-built trade-off параграф + import-list уточнение), § 3.2 (Honest narrative параграф про функциональное дублирование Boot auto-config + `@Component` вместо `@Configuration` + Builder overload note + regression strategy перечисление), § 3.3 (`.findAndAddModules()` в detectServerObjectMapper + `@Qualifier` placement note + объяснение почему текущий код без `.build()`), § 4.3 (DateTimeFeature + `.findAndAddModules()` в detectServerMapper) |
| `docs/superpowers/plans/2026-05-26-jackson-3-migration.md` | Header (line numbers drift note + search patterns), Task 1 Step 1 (алиас `jackson-kotlin3` + naming note), Task 1 Step 2/3 (`libs.jackson.kotlin3`), Task 1 Step 4 (FQN pre-flight check), Task 2 Step 3 (DateTimeFeature + `.findAndAddModules()` в detectServerMapper + Jackson 3 note KDoc), Task 3 Step 3 (DateTimeFeature + Jackson 3 note KDoc), Task 4 Step 1 (DateTimeFeature import + implementation note + Builder/pre-built trade-off параграф в KDoc), Task 4 Step 2 (ApplicationContextRunner strengthening assertion + FQN pre-flight verification), Task 5 Step 4(d) (переформулировка — type-switch не нужен), Task 6 Step 1 (context-параграф про `.build()` + `.findAndAddModules()` в detectServerObjectMapper + `@Qualifier` placement note), Task 7 Step 1 (reflection identity guard в test + reflection note), Task 7 Step 3 (`@Component` вместо `@Configuration` + KDoc переписан под honest narrative), Task 7 Step 5 (убран нерабочий FAIL_ON_UNKNOWN test, оставлен только KDoc update — sync с design § 4.1), Task 8 Step 1a (расширенный multi-pattern grep audit + bean-name literal grep + явные модули), Task 8 Step 1b (spring-boot-jackson2 + Jackson 2 transitive verification step) |
| `docs/superpowers/specs/2026-05-26-jackson-3-migration-review-merged-iter-2.md` | Создан (merged review от 5 ревьюеров) |
| `docs/superpowers/specs/2026-05-26-jackson-3-migration-review-iter-2.md` | Создан (этот файл) |

## Статистика

- Всего замечаний: 20 (включая 2 question-overlap'а и 1 REPEAT)
- Автоисправлено (без обсуждения): 11 (NEW-1, NEW-2, NEW-3, NEW-8, NEW-9, NEW-11 в составе NEW-10, NEW-12, NEW-13, NEW-14, NEW-15 sync, NEW-16, NEW-17, NEW-18)
- Авто-применено после анализа: 2 (NEW-4 — Вариант A единственный consistent; NEW-7 — Вариант A единственный consistent с NEW-10 решением)
- Обсуждено с пользователем: 1 (NEW-10 — architectural choice; пользователь выбрал Вариант B)
- Отклонено: 4 (NEW-5 verified ok safety check added; NEW-6 verified Kotlin claim wrong; NEW-19 overlap с NEW-7; NEW-20 overlap с NEW-10)
- Повторов (автоответ): 1 (REPEAT-1, general aspect — новый аспект выделен в NEW-17)
- Пользователь сказал "стоп": Нет
- Агенты: 5 selected (codex/ccs-glm/ollama-kimi/ollama-minimax/ollama-deepseek), 5 successful (vs iter-1 — codex и ccs-glm оборвались, в iter-2 ВСЕ 5 завершились без abort'ов)
