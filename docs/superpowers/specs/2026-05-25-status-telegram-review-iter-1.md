# Review Iteration 1 — 2026-05-25

## Источник

- Design: `docs/superpowers/specs/2026-05-25-status-telegram-design.md`
- Plan: `docs/superpowers/plans/2026-05-25-status-command.md`
- Review agents: codex-executor (gpt-5.5 xhigh), ccs-executor (glm-5.1), ollama-executor (kimi-k2.6:cloud), ollama-executor (minimax-m2.7:cloud), ollama-executor (deepseek-v4-pro:cloud)
- Merged output: `docs/superpowers/specs/2026-05-25-status-telegram-review-merged-iter-1.md`

## Замечания

### [CRITICAL-1] TelegramUserDto does not contain olsonCode

> `user?.olsonCode` referenced in handler, but DTO has no such field. Real handlers use `TelegramUserService.getUserZone(chatId)`.

**Источник:** ccs-glm, ollama-deepseek
**Статус:** Автоисправлено
**Ответ:** Inject `TelegramUserService` into `StatusCommandHandler`, call `getUserZone(message.chat.id.chatId.long)` — same pattern as `TimezoneCommandHandler`/`ExportCommandHandler`.
**Действие:** Design + Plan Task 10 updated; handler signature gained `userService` and `clock` params; removed `ZoneId.of(olsonCode)` logic.

---

### [CRITICAL-2] i18n templates have wrong placeholder indices

> `status.cameras.line.offline=offline {2} (last {3})` plus formatter passing 3 args starting from `{0}=camId` → `{2}` becomes lastSeen, `{3}` is missing. Same shift for `status.servers.line.alive`.

**Источник:** codex, ccs-glm, ollama-kimi, ollama-minimax, ollama-deepseek
**Статус:** Автоисправлено
**Ответ:** Renumber all placeholders starting at `{0}`; do NOT pass `camId`/`server.id` as MessageFormat args — they are rendered once in code via `escape(camPadded)` / `escape(idPadded)`. New keys: `status.cameras.line.online=online ({0} ago)`, `status.cameras.line.offline=offline {0} (last {1})`, `status.servers.line.alive=ALIVE  frame {0}/{1}  ext {2}/{3}  vis {4}/{5}  vvis {6}/{7}`, `status.servers.line.dead=DEAD`.
**Действие:** Design i18n section rewritten; Plan Task 7 + Task 9 formatter code updated; added real-bundle integration test (CONCERN-10 part) that catches this class of regressions.

---

### [CRITICAL-3] StatusCommandHandler module dependency violation

> Handler placed in `modules/telegram` but imports `core.service.StatusService`. `telegram` does NOT depend on `core` (reverse direction).

**Источник:** codex
**Статус:** Автоисправлено
**Ответ:** Move handler to `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/bot/handler/StatusCommandHandler.kt`. `StatusMessageFormatter` stays in `telegram` (depends only on `model` + i18n). `CommandHandler` interface imported from `telegram`.
**Действие:** Design file structure + handler location updated; Plan Task 10 file path + test path moved to `core` module.

---

### [CRITICAL-4] order = 6 conflicts with LanguageCommandHandler

> `LanguageCommandHandler.order = 6` already. Tie-breaker is lexicographical so `language` < `status`, not guaranteed "right after /version".

**Источник:** codex, ccs-glm, ollama-kimi
**Статус:** Автоисправлено
**Ответ:** Use `order = 8` — first unused slot after Notifications=7, before AddUser=10.
**Действие:** Design + Plan Task 10 updated.

---

### [CRITICAL-5] Jackson Duration/Instant serialization contract unverified

> Current `JacksonConfiguration.objectMapper()` does NOT explicitly disable `WRITE_DATES_AS_TIMESTAMPS` / `WRITE_DURATIONS_AS_TIMESTAMPS`. Without that, `Instant`/`Duration` serialize as numeric timestamps, contradicting design.

**Источник:** codex, ollama-kimi, ollama-deepseek
**Статус:** Автоисправлено
**Ответ:** Add `.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false).configure(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS, false)` to mapper builder. Add focused `JacksonConfigurationTest`.
**Действие:** Design REST section updated; Plan new Task 10b created with mapper update and test.

---

### [CRITICAL-6] StatusControllerTest needs @AutoConfigureWebTestClient

> `IntegrationTestBase` (`@SpringBootTest(webEnvironment = RANDOM_PORT)`) does not provide `WebTestClient` bean by default.

**Источник:** ccs-glm, ollama-kimi, ollama-deepseek
**Статус:** Обсуждено с пользователем (auto-decided — only Variant A had no fatal flaws)
**Ответ:** Variant A — annotate `StatusControllerTest` with `@AutoConfigureWebTestClient` locally. Variant B (annotate `IntegrationTestBase`) is premature for current scope (only one existing web-test). Variant C (manual `bindToApplicationContext`) is unnecessary boilerplate.
**Действие:** Plan Task 6 Step 1 already updated to include the annotation.

---

### [CRITICAL-7] messages_ru.properties encoding misstatement

> Plan said "ISO-8859-1 with \uXXXX escapes", actual file is UTF-8 with raw Cyrillic.

**Источник:** ccs-glm, ollama-kimi, ollama-minimax, ollama-deepseek
**Статус:** Автоисправлено
**Ответ:** Remove the misleading note; explicitly state "UTF-8 with raw Cyrillic — no \uXXXX escapes" in Plan Task 7.
**Действие:** Plan Task 7 note rewritten; Design i18n section updated.

---

### [CRITICAL-8] REST /status authorization scope

> Decision says "OWNER only", but plan only protects Telegram. REST `/status` was always public like `/statistics`.

**Источник:** codex
**Статус:** Автоисправлено
**Ответ:** Explicitly document in design that REST `/status` is public (matches existing `/statistics` behavior); OWNER-only applies only to Telegram command.
**Действие:** Design Scope + REST endpoint sections clarified.

---

### [CONCERN-1] StatusControllerTest does not verify ISO-8601 contract

> Test only asserts `isNumber`/`isString`; doesn't catch numeric timestamp regression.

**Источник:** codex, ollama-kimi, ollama-deepseek
**Статус:** Автоисправлено
**Ответ:** Add dedicated `JacksonConfigurationTest` (DB-independent) asserting `"PT7M"` and `"2026-04-25T10:00:00Z"` strings.
**Действие:** Plan Task 6 Step 1a added.

---

### [CONCERN-2] snapshotStates defensive-copy test incomplete

> Test only checks initial content, doesn't prove the returned map is detached from internal state.

**Источник:** codex, ccs-glm, ollama-deepseek
**Статус:** Автоисправлено
**Ответ:** Add Step 3a in Task 4: take snapshot → trigger second tick that mutates state → assert old snapshot unchanged; assert fresh snapshot reflects new state.
**Действие:** Plan Task 4 Step 3a added.

---

### [CONCERN-3] No error handling in StatusCommandHandler

> Other handlers catch errors; this one doesn't; design promised "exception → common.error.generic" test.

**Источник:** codex, ollama-kimi, ollama-minimax, ollama-deepseek
**Статус:** Автоисправлено
**Ответ:** `FrigateAnalyzerBot.registerRoutes()` wraps handler dispatch in try/catch at the router level (verified: VersionCommandHandler — most similar — has no local try/catch). Skip local handler try/catch. Remove the "exception → common.error.generic" test promise.
**Действие:** Design `StatusCommandHandler` section + Plan Task 10 Step 1 explicitly document router-level handling and no local error-path test.

---

### [CONCERN-4] Test coverage gaps vs design promises

> Missing: id-non-duplication test, OFFLINE-after-skew, empty `monitoringEnabled=true` items, 404 for old `/statistics`, language/timezone selection in handler test, two-DEAD / two-OFFLINE sort.

**Источник:** codex, ccs-glm, ollama-kimi, ollama-deepseek
**Статус:** Автоисправлено
**Ответ:** Add id-non-duplication test (Task 9); empty-items test already in plan via `format renders empty marker`; manual 404 check in Task 12; sort edge case (two-DEAD / two-OFFLINE) covered by real-bundle integration test in Task 9. language/timezone in handler test deferred (handler is thin wrapper, behaviour tested in formatter + service).
**Действие:** Plan Task 9 test list expanded (id-non-dup test, integration test).

---

### [CONCERN-5] No protection against 4096-char Telegram limit

> No truncation/splitting; overflow possible.

**Источник:** codex, ccs-glm, ollama-minimax
**Статус:** Обсуждено с пользователем (auto-decided — Variant A wins)
**Ответ:** Variant A — out of scope. Router-level try/catch catches `RequestException`. Project has ≤10 cameras + 1–3 servers — realistic message ≈1500 chars. YAGNI; if/when needed, add hard cap.
**Действие:** Design Out-of-scope section explicitly lists this.

---

### [CONCERN-6] HTML double-escape risk

> `escape(line)` applied to MessageFormat output; if i18n ever contains `&` or `<`, double-escape.

**Источник:** ccs-glm
**Статус:** Обсуждено с пользователем (auto-decided — Variant B canonical "escape at boundary")
**Ответ:** Variant B — remove `escape(line)`; keep only `escape(camPadded)` / `escape(idPadded)` for user-derived values. i18n templates, `formatDuration()`, and `DateTimeFormatter("HH:mm:ss")` are trusted sources (digits + technical units like "min"/"мин"); they cannot produce HTML special chars.
**Действие:** Plan Task 9 formatter code updated — `appendCameras` and `appendServers` no longer wrap their `line`/`tail` in escape().

---

### [CONCERN-7] now drift between StatusService and StatusCommandHandler

> Service uses `Instant.now(clock)`, handler uses bare `Instant.now()`.

**Источник:** ollama-kimi, ollama-deepseek
**Статус:** Автоисправлено
**Ответ:** Inject `Clock` into `StatusCommandHandler`, call `Instant.now(clock)`.
**Действие:** Design + Plan Task 10 updated.

---

### [CONCERN-8] `./gradlew compileKotlin` in Task 8 too narrow

> Root-task compileKotlin doesn't trigger submodule compileKotlin.

**Источник:** codex
**Статус:** Автоисправлено
**Ответ:** Replace with explicit list `:frigate-analyzer-model:compileKotlin :frigate-analyzer-core:compileKotlin :frigate-analyzer-telegram:compileKotlin`.
**Действие:** Plan Task 8 Step 3 updated.

---

### [CONCERN-9] Stale references grep scope too narrow

> Only `modules/` scanned; misses `*.md`, `*.java`, `*.sh`, `*.yml`, `*.json`.

**Источник:** codex, ollama-minimax
**Статус:** Автоисправлено
**Ответ:** Expand grep to include `*.md *.java *.sh *.yml *.json *.yaml *.kt *.kts *.properties`, repo-root scope, exclude `build/` and `.gradle/`.
**Действие:** Plan Task 8 Step 1 updated.

---

### [CONCERN-10] StatusMessageFormatterTest too coupled to mocks

> Mocked `MessageResolver` returns identity stubs, hides i18n placeholder bugs (the very class CRITICAL-2 was missed by).

**Источник:** codex, ollama-kimi, ollama-deepseek
**Статус:** Обсуждено с пользователем (auto-decided — Variant B layered defence)
**Ответ:** Variant B — keep mocked unit tests for structural assertions (escape, padding, sections); add ONE real-bundle integration test (`StatusMessageFormatterI18nTest`) that renders a representative snapshot with `ReloadableResourceBundleMessageSource` against real properties. Catches placeholder shifts and missing-arg renders.
**Действие:** Plan Task 9 test code extended with integration test snippet.

---

### [CONCERN-11] N+1 / no read isolation in StatusService.collect()

> 5 separate SQL queries; possible inconsistency between `total` and `processed`.

**Источник:** ccs-glm, ollama-minimax
**Статус:** Автоисправлено (accepted as inherited behavior)
**Ответ:** Inherited from `/statistics`. Document as out-of-scope; the small inconsistency window is acceptable for a diagnostic endpoint.
**Действие:** Design Out-of-scope section updated.

---

### [CONCERN-12] CameraSignalState boundary concerns

> Internal to `core/task/`; would leak if service moved.

**Источник:** ollama-minimax
**Статус:** Автоисправлено
**Ответ:** Resolved by CRITICAL-3 — handler is in `core`, so `CameraSignalState` import is intra-module. No code action needed.
**Действие:** No-op; covered by CRITICAL-3 architectural decision.

---

### [CONCERN-13] Snapshot weakly-consistent semantics undocumented

> `ConcurrentHashMap.toMap()` may capture intermediate state; should document.

**Источник:** ccs-glm, ollama-deepseek
**Статус:** Автоисправлено
**Ответ:** Expand `snapshotStates()` KDoc to explicitly call out weakly-consistent semantics, immutability of returned map, and acceptable use for diagnostic snapshots.
**Действие:** Plan Task 4 Step 3 KDoc rewritten.

---

### [SUGGESTION-1] Better user-facing text for disabled marker

> `Monitoring disabled (signal-loss.enabled=false)` — env-var form is more actionable.

**Источник:** codex
**Статус:** Обсуждено с пользователем (auto-decided — env-var form aligns with project config style)
**Ответ:** Variant 1 — env-var form. EN: `Monitoring disabled (set APPLICATION_SIGNAL_LOSS_ENABLED=true to enable)`. RU: `Мониторинг отключён (установите APPLICATION_SIGNAL_LOSS_ENABLED=true)`.
**Действие:** Design + Plan Task 7 i18n keys updated.

---

### [SUGGESTION-2] Add lastUpdatedAt: Instant to StatusResponse

> JSON consumers may want snapshot timestamp for staleness detection.

**Источник:** ollama-minimax
**Статус:** Обсуждено с пользователем (auto-decided — Variant A YAGNI)
**Ответ:** Variant A — do not add. Single-deployment project, no monitoring scripts consume `/status`. HTTP `Date` header suffices if needed later. Easy to add backward-compatibly later.
**Действие:** No change.

---

### [SUGGESTION-3] Hardcoded "(none)" in formatter

> Use i18n key for empty placeholder.

**Источник:** ollama-kimi
**Статус:** Автоисправлено
**Ответ:** Add `status.empty=(none)` / `status.empty=(нет)` i18n keys; use them in `appendByCamera` and `appendServers` for empty case.
**Действие:** Plan Task 7 + Task 9 formatter updated.

---

### [SUGGESTION-4] coerceAtLeast(Duration.ZERO) in formatter fallback

> Service applies it but formatter's `item.offlineFor ?: Duration.between(...)` fallback path doesn't.

**Источник:** ollama-minimax, ccs-glm
**Статус:** Автоисправлено
**Ответ:** Apply `.coerceAtLeast(Duration.ZERO)` in both HEALTHY's `ago` computation and OFFLINE's fallback `offlineFor` computation.
**Действие:** Plan Task 9 formatter code updated.

---

### [SUGGESTION-5] Sort recordings.byCameras OFFLINE-first

> Coherent UX, problematic cameras at top.

**Источник:** ollama-deepseek
**Статус:** Отклонено
**Ответ:** Intentionally kept alphabetical: aligns with existing SQL `ORDER BY cam_id`; OFFLINE-first would require cross-section state, increasing coupling.
**Действие:** No change.

---

### [SUGGESTION-6] Test that id is not duplicated

> Regression guard for CRITICAL-2-style bugs.

**Источник:** ollama-deepseek
**Статус:** Автоисправлено
**Ответ:** Add `format does not duplicate camId or server id` test asserting `out.split("cam-unique").size - 1 == 1` and same for server id.
**Действие:** Plan Task 9 test added.

---

### [SUGGESTION-7] ObjectProvider test pattern

> Could use `SimpleObjectProvider` instead of mockk.

**Источник:** ollama-kimi
**Статус:** Отклонено
**Ответ:** Current mockk pattern is simple and readable; no behavioural benefit from change.
**Действие:** No change.

---

### [SUGGESTION-8] Reuse SignalLossMessageFormatter.buildLossMessage()

> Code reuse opportunity.

**Источник:** ccs-glm
**Статус:** Отклонено
**Ответ:** Different output formats — `buildLossMessage()` is full notification (emoji, header, buttons); status line is compact table row. Reuse would require breaking the existing API.
**Действие:** No change.

---

### [SUGGESTION-9] Verify reply() signature vs sendTextMessage()

> ktgbotapi version-specific.

**Источник:** ollama-kimi, ollama-minimax, ollama-deepseek
**Статус:** Отклонено
**Ответ:** Plan already documents the fallback in Task 10 Step 4. To be resolved at implementation time by checking actual ktgbotapi version.
**Действие:** No change — fallback already in plan.

---

### [SUGGESTION-10] OFFLINE camera lifecycle (upper bound)

> Decommissioned cameras stay forever; consider cleanup.

**Источник:** ollama-kimi
**Статус:** Отклонено
**Ответ:** Separate issue in `SignalLossMonitorTask` cleanup logic, not part of `/status` feature scope. `/status` correctly reflects current monitor contract.
**Действие:** No change.

---

### [QUESTION-1] /statistics removal — deprecation vs hard 404

**Источник:** codex, ollama-kimi, ollama-deepseek
**Статус:** Отклонено (covered by CONCERN-9)
**Ответ:** Hard 404 per design; CONCERN-9's expanded grep will surface any active references — addressed via Task 8 Step 1.
**Действие:** No change beyond CONCERN-9.

---

### [QUESTION-2] offlineFor format

**Источник:** codex, ollama-deepseek
**Статус:** Отклонено (covered by CRITICAL-5)
**Ответ:** Keep ISO-8601 (`PT7M`) as design specified. Once CRITICAL-5 fix lands (mapper config + test), the contract is properly verified.
**Действие:** No change.

---

### [QUESTION-3] /help OWNER without active chatId

**Источник:** ollama-minimax
**Статус:** Отклонено (already in plan)
**Ответ:** Manual sanity check Task 12 Step 4 verifies `/help` shows `/status` for OWNER.
**Действие:** No change.

---

### [QUESTION-4] SQL already sorts byCameras

**Источник:** ollama-minimax, ollama-deepseek
**Статус:** Отклонено
**Ответ:** No redundant sort in plan — `StatusService.buildRecordings()` uses repository result as-is.
**Действие:** No change.

---

### [QUESTION-5] @ConditionalOnProperty correct?

**Источник:** ollama-deepseek
**Статус:** Отклонено (confirmation only)
**Ответ:** Confirmed correct — formatter only used by Telegram handler, both gated by same `application.telegram.enabled=true` property.
**Действие:** No change.

---

## Изменения в документах

| Файл | Изменение |
|------|-----------|
| `docs/superpowers/specs/2026-05-25-status-telegram-design.md` | Scope clarification (REST public, OWNER only Telegram); module structure note (handler in core); StatusCommandHandler signature with `userService` + `clock`; i18n keys renumbered + status.empty; REST section with explicit Jackson config; CamerasSection KDoc weak-consistency mention; Out-of-scope expanded (4096-char limit, REST auth, N+1) |
| `docs/superpowers/plans/2026-05-25-status-command.md` | File Structure table updated (handler in core, new Task 10b); Task 4 defensive-copy mutation test; Task 6 `@AutoConfigureWebTestClient` + Step 1a JacksonConfigurationTest; Task 7 placeholders renumbered + UTF-8 note + status.empty + env-var form; Task 8 broad grep + per-module compileKotlin; Task 9 formatter rewritten for renumbered keys, escape-only-user-derived, coerceAtLeast, status.empty, id-non-dup test, real-bundle integration test; Task 10 handler moved to core/bot/handler with userService + clock + order=8; new Task 10b Jackson mapper config; Self-Review updated |
| `docs/superpowers/specs/2026-05-25-status-telegram-review-merged-iter-1.md` | New — merged output from 5 review agents |
| `docs/superpowers/specs/2026-05-25-status-telegram-review-iter-1.md` | New — this file |

## Статистика

- Всего замечаний: 36
- Автоисправлено (без обсуждения): 22
- Авто-применено после анализа: 6
- Обсуждено с пользователем: 0 (все спорные решились через анализ без эскалации)
- Отклонено: 8
- Повторов (автоответ): 0
- Пользователь сказал "стоп": Нет
- Агенты: codex-executor (gpt-5.5 xhigh), ccs-executor (glm-5.1), ollama-executor × 3 (kimi-k2.6, minimax-m2.7, deepseek-v4-pro)
