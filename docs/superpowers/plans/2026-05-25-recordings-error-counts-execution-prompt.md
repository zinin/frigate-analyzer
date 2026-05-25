## TASK

Execute the implementation plan for recordings error counts (#28) — adding `success` and `errors` counters to `/status`, computed via a single PostgreSQL `COUNT(*) FILTER (WHERE ...)` query, with updated Telegram `<pre>` layout.

Use `/superpowers:subagent-driven-development` skill for execution.

## DOCUMENTS

- Design: `docs/superpowers/specs/2026-05-25-recordings-error-counts-design.md`
- Plan: `docs/superpowers/plans/2026-05-25-recordings-error-counts.md`

Read both documents first.

## IMPORTANT: DO NOT START WORK YET

After reading the documents:
1. Confirm you have loaded all context.
2. Summarize your understanding briefly.
3. **WAIT for user instruction before taking any action.**

Do NOT begin implementation until the user explicitly tells you to start.

## SESSION CONTEXT

### Working branch
Already on `feat/recordings-error-counts` (created from `master` during brainstorming). Two docs commits land on it: spec (`2c48d43`) + plan + spec amendment (`fee688f`). Verify with `git log --oneline -3`.

### Key decisions and why (extracted from brainstorming Q1-Q7)

- **Q1 (counters):** chose option (b) — *additive*: keep `processed`/`unprocessed`, add `success` + `errors`. Rejected (a) "errors only" (user has to compute success themselves), (c) "reinterpret processed = analyzed" (silent breaking change), (d) "full breakdown with downloaded/pending" (bigger change, controversial semantics).
- **Field naming:** chose `success` (not `analyzed`, not `succeeded`, not `successful`) — user explicit choice.
- **Q2 (categorization):** *none* in this iteration — single `errors: Long`. Categorization (`detection`/`visualization`/`ai_description`) would require DB migration; deferred to a separate issue.
- **Q3 (retry-pending state):** not implemented; design intentionally does NOT enforce `success + errors == processed` as an invariant — leaves room for future retry semantics.
- **Q4 (Telegram layout):** chose layout C — 5 rows (Total / Success(%) / Errors(%) / Unprocessed / Rate). The redundant `Processed` row is dropped from Telegram only (REST contract retains `processed`).
- **Q5 (i18n):** new keys `label.success`, `label.errors`, shared `value.withPct={0} ({1}%)` template (DRY for both rows). Old `label.processed`/`value.processed` removed. RU labels: `Успешно`, `Ошибки`.
- **Q6 (drop `unprocessed`):** kept — independent meaning ("in queue"), removal would be breaking without payoff.
- **Q7 (SQL strategy):** chose *single `COUNT(*) FILTER (WHERE ...)`* query — atomic snapshot (closes iter-1 CONCERN-11) and 1 round-trip instead of 7. Rejected parallel `async { repo.count*() }` (doesn't solve atomicity, still 7 round-trips).

### Edge cases the plan handles explicitly

- `total == 0` → both percentage rows render `"0.0"` (div-by-zero guard mirrors current code).
- `errors == 0` → `Errors: 0 (0.0%)` row is still shown (operational signal).
- `success + errors == processed` is true *today* (every `markProcessedWithError` sets both `process_timestamp` and `error_message`) — but **no code asserts this invariant**; future retry-pending can break it in either direction.

### Discovered spec gaps (now amended)

The original spec was self-reviewed and committed before grep against the codebase. Two findings were folded back into the spec (commit `fee688f`, see § 9.3 update and § 9.4):

1. **`StatusMessageFormatterTest.kt` contains two test classes** in the same file: outer `StatusMessageFormatterTest` (mock-based, 4 `RecordingsStatistics(...)` construction sites) AND nested `StatusMessageFormatterI18nTest` (real ResourceBundle, has its own `sampleSnapshot()`). Both need compile-fix when `success`/`errors` become required non-null fields. The plan addresses this in Task 2 Step 5 (sites 5a-5d).
2. **`RecordingEntityRepositoryTest` (integration test against real PG via `IntegrationTestBase`)** has three existing tests — `should count {all,processed,unprocessed} recordings` — that use the methods being removed. Plan Task 6 Step 4 deletes those three tests and adds one combined `should return recording counts via FILTER aggregate` test seeding 4 recordings (1 success, 1 errors-finished, 2 unprocessed) and asserting the full `RecordingCountsDto`.

### Warning: helper signature uncertainty

Plan Task 6 Step 4c calls `createRecordingEntity(... errorMessage = "boom", ...)`. The `createRecordingEntity` helper in `RecordingEntityRepositoryTest.kt` may not yet accept `errorMessage` — the plan's note instructs to either extend the helper (`errorMessage: String? = null`, forward to `RecordingEntity.errorMessage`) or construct `RecordingEntity(...)` directly in that test only. **Verify the helper's current signature before implementing Step 4c.**

### Project-specific workflow rules (from `CLAUDE.md`)

- **Do NOT run `./gradlew build` directly during planning/implementation.** Use `/build` slash command or `build-runner` agent.
- **After implementation, run `superpowers:code-reviewer` agent FIRST**, fix every Critical finding, repeat until clean, **THEN** dispatch `build-runner`.
- **On ktlint failures:** `./gradlew ktlintFormat` then retry the build.
- **Git workflow:** `ALWAYS git add <file>` after creating or modifying any file (don't use `git add -A` / `git add .`).
- **Per global `~/.claude/CLAUDE.md`:** before opening PR, `git rm` all `docs/superpowers/` files in a separate commit so planning artefacts don't appear in the diff (plan Task 7 Step 5).

### Files that will be touched (10 total)

**Production (6):**
- `modules/model/.../dto/RecordingCountsDto.kt` (new)
- `modules/model/.../response/RecordingsStatistics.kt` (+2 fields)
- `modules/service/.../repository/RecordingEntityRepository.kt` (+1 method, later −3)
- `modules/core/.../service/StatusService.kt` (rewrite `buildRecordings()`)
- `modules/telegram/.../service/impl/StatusMessageFormatter.kt` (rewrite `appendRecordings()` + add `pct()` helper)
- `modules/telegram/src/main/resources/messages_{en,ru}.properties` (+3 keys each, later −2 each)

**Tests (4):**
- `core/.../service/StatusServiceTest.kt`
- `core/.../controller/StatusControllerTest.kt`
- `core/.../controller/StatusControllerTestConfig.kt` (compile-fix only)
- `core/.../repository/RecordingEntityRepositoryTest.kt` (replace 3 tests with 1)
- `telegram/.../service/impl/StatusMessageFormatterTest.kt` (compile-fix + 3 new tests, both nested classes)

## PLAN QUALITY WARNING

The plan was written for a large task and may contain:
- Errors or inaccuracies in implementation details
- Oversights about edge cases or dependencies
- Assumptions that don't match the actual codebase
- Missing steps or incomplete instructions

**If you notice any issues during implementation:**
1. STOP before proceeding with the problematic step.
2. Clearly describe the problem you found.
3. Explain why the plan doesn't work or seems incorrect.
4. Ask the user how to proceed.

Do NOT silently work around plan issues or make significant deviations without user approval.

Particular spots to watch:
- **Task 2 Step 5c** (line 223 of `StatusMessageFormatterTest.kt`): the current code uses *positional* `RecordingsStatistics(0, 0, 0, emptyList(), 0.0)` — the plan converts to named args. Confirm Kotlin accepts the rewrite cleanly.
- **Task 5 Step 1** test assertions rely on the identity-style mock that echoes parametric keys as `key[arg0,arg1]`. If the mock convention has changed in the file, adjust assertion strings accordingly (look at the test fixture at the top of the file).
- **Task 6 Step 4c**: helper signature for `errorMessage` parameter — see warning above.
- **Task 6 Step 5** runs `./gradlew :frigate-analyzer-core:test :frigate-analyzer-telegram:test` directly. Project rule prefers `build-runner`/`/build` — use those instead if you want to follow the rule strictly. The direct gradle invocation is faster for the per-module verification step, but Task 7 Step 2 still requires the full `build-runner` cycle.
