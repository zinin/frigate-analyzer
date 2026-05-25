## TASK

Execute the implementation plan for recordings error counts (#28) — adding `success` and `errors` counters to `/status`, computed via a single PostgreSQL `COUNT(*) FILTER (WHERE ...)` query, with updated Telegram `<pre>` layout.

Use `/superpowers:subagent-driven-development` skill for execution.

## CRITICAL: DO NOT START WORKING

**STOP. READ THIS CAREFULLY.**

After loading all context below, you MUST:
1. Read the design and plan documents and understand the context
2. Report what you understood (brief summary)
3. **WAIT for explicit user instructions** before taking ANY action

**DO NOT:**
- Start implementing tasks
- Make any code changes
- Run any commands (except reading documents)
- Assume what task to work on next

**The user will tell you exactly what to do.** Until then, only read and summarize.

## DOCUMENTS

- Design: `docs/superpowers/specs/2026-05-25-recordings-error-counts-design.md` (updated by iter-1)
- Plan: `docs/superpowers/plans/2026-05-25-recordings-error-counts.md` (updated by iter-1)
- Review iter-1 log: `docs/superpowers/specs/2026-05-25-recordings-error-counts-review-iter-1.md`
- Review iter-1 merged (raw outputs): `docs/superpowers/specs/2026-05-25-recordings-error-counts-review-merged-iter-1.md`

Read the design and plan first; the iter-1 log only if disputed decisions need verification.

## PROGRESS

**Completed:** brainstorming, design spec, implementation plan, external review iteration 1 (5 reviewers, 30 issues processed). No implementation tasks have started yet.

**Branch state (`feat/recordings-error-counts`, branched from master):**

```
5a4c861 docs: review iter 1 — decisions + log (recordings-error-counts)
375eb12 docs: review iter 1 — auto-fixes (recordings-error-counts)
89cce21 docs: add execution prompt for fresh session
fee688f docs: add implementation plan for recordings error counts (#28)
2c48d43 docs: add design for recordings error counts (#28)
```

**Implementation tasks (all pending):**
- [ ] Task 1: Scaffold `RecordingCountsDto` + `getRecordingCounts()` repository method
- [ ] Task 2: Backend slice — `+success/+errors` on `RecordingsStatistics`, wire `StatusService`, update `StatusServiceTest` + compile-fix 5 sites in `StatusMessageFormatterTest`
- [ ] Task 3: `StatusControllerTest` jsonPath assertions
- [ ] Task 4: New i18n keys (EN + RU)
- [ ] Task 5: Rewrite `StatusMessageFormatter.appendRecordings()` for layout C + 3 new tests + delete `formatRow()`
- [ ] Task 6: Cleanup — drop `countAll`/`countProcessed`/`countUnprocessed`, drop old i18n keys, replace 3 repo tests with 2 (FILTER aggregate + overlap edge-case), real-bundle i18n assertion
- [ ] Task 7: Code review (superpowers:code-reviewer) → fixes → build-runner → ktlint if needed → curl REST verify → `git rm docs/superpowers/...` → PR

## SESSION CONTEXT

### Brainstorming decisions (Q1-Q7) — unchanged

- **Q1 (counters):** *additive* — keep `processed`/`unprocessed`, add `success` + `errors`. Rejected: errors-only, reinterpret-`processed`, full-breakdown.
- **Field naming:** `success` / `errors` (user explicit choice — REJECTED renames to `successful`/`failed` from external reviewer SUGGESTION-1).
- **Q2 (categorization):** no error categorization in this iteration; single `errors: Long`.
- **Q3 (retry-pending):** not implemented; design **intentionally** does NOT enforce `success+errors=processed`. After iter-1: `errors` counts ALL `error_message IS NOT NULL` regardless of `process_timestamp` (Variant A KEEP — see CRITICAL-5 below).
- **Q4 (Telegram):** layout C — 5 rows (Total / Success(%) / Errors(%) / Unprocessed / Rate); old `Processed` row dropped from Telegram only (REST keeps `processed`).
- **Q5 (i18n):** new keys `label.success`, `label.errors`, shared `value.withPct={0} ({1}%)`; remove `label.processed`/`value.processed`. RU labels: `Успешно` / `Ошибки` (user explicit choice — REJECTED rename to `Успешные`).
- **Q6 (drop `unprocessed`):** kept — independent meaning.
- **Q7 (SQL strategy):** single `COUNT(*) FILTER (WHERE ...)` query — atomic snapshot, 1 round-trip.

### Iter-1 review summary

5 reviewers (codex-executor gpt-5.5 xhigh, ollama-kimi, ollama-deepseek, ollama-minimax; ccs-glm wrapper failed mid-flight). 30 issues processed: 22 auto-fixed, 1 disputed→discussed, 7 dismissed, 0 repeats. Both design and plan documents have been updated. Key decisions to remember:

- **CRITICAL-5 (errors filter semantics)** → **Variant A KEEP**. SQL remains `errors = COUNT(*) FILTER (WHERE error_message IS NOT NULL)` — independent of `process_timestamp`. Rationale: aligns with Q3 (no forced invariant); future acquire-failed/retry-pending errors will appear in `errors` automatically without code changes, preserving operator visibility (the core goal of #28). Design §6 rewritten to neutralize self-contradictory invariant phrasing.
- **CRITICAL-3 (Kimi "missing `RecordingCountsDto` import in `StatusService.kt`")** → DISMISSED. Kotlin type inference handles it; no import needed (same as how `CameraStatisticsDto` is consumed in current `buildRecordings()`).
- **CRITICAL-4 (Kimi "wrong line numbers in properties files")** → DISMISSED. Kimi reversed the line order; plan is correct.

### Plan changes from iter-1 (key spots to honor during implementation)

These ARE in the plan now — listed so subagent implementers immediately know what's intentional vs accidental:

1. **Task 2 Step 1:** `RecordingsStatistics` has a KDoc block on `val processed: Long` directing consumers to prefer `success`/`errors`. Keep the KDoc verbatim.
2. **Task 2 (top):** an interim-state warning callout — formatter tests will be RED at assertion between Task 2 and Task 5. This is expected.
3. **Task 2 Step 3d:** zero-errors test now asserts `processed` and `unprocessed` too.
4. **Task 5 Step 1:** THREE edge-case tests (errors=0, total=0, success+errors != processed). The third test documents the non-invariant explicitly.
5. **Task 5 Step 3:** `formatRow(...)` MUST be deleted explicitly — do not rely on ktlint.
6. **Task 6 Step 4a:** import for `RecordingCountsDto` is intentionally NOT added to the new test (type inferred).
7. **Task 6 Step 4c:** `createRecordingEntity(...)` helper extension is MANDATORY — add `errorMessage: String? = null` parameter (after `processAttempts`), forward in constructor body. Don't use `.copy()` or direct `RecordingEntity(...)` alternatives.
8. **Task 6 Step 4c:** there are now TWO tests in this step — primary FILTER-aggregate (4 seeded rows) + secondary overlap test `should count error-only recording in errors and unprocessed buckets` (seeds 1 row with `errorMessage="acquire failed"` and `process_timestamp=null`).
9. **Task 6 Step 4d (NEW):** real-bundle i18n assertion in `StatusMessageFormatterI18nTest` — two `@Test` blocks (EN + RU) checking localized labels render and raw keys do NOT leak.
10. **Task 7 Step 2b (NEW):** manual `curl ... | jq '.recordings | {total, processed, unprocessed, success, errors}'` REST verification.

### Project workflow rules (from `CLAUDE.md` — non-negotiable)

- **Do NOT run `./gradlew build` directly during implementation.** Use `/build` slash command or `build-runner` agent.
- **After implementation:** run `superpowers:code-reviewer` agent FIRST, fix every Critical finding, repeat until clean, **then** dispatch `build-runner`.
- **On ktlint failures:** `./gradlew ktlintFormat` → retry build.
- **Git:** ALWAYS `git add <file>` after creating/modifying any file (don't use `git add -A` / `git add .`).
- **Per global `~/.claude/CLAUDE.md`:** before opening PR, `git rm` all `docs/superpowers/` files in a SEPARATE commit so planning artefacts don't appear in the PR diff. The 4 docs to remove at PR time:
  - `docs/superpowers/specs/2026-05-25-recordings-error-counts-design.md`
  - `docs/superpowers/plans/2026-05-25-recordings-error-counts.md`
  - `docs/superpowers/specs/2026-05-25-recordings-error-counts-review-iter-1.md`
  - `docs/superpowers/specs/2026-05-25-recordings-error-counts-review-merged-iter-1.md`

### Files that will be touched (10 production + tests)

**Production (6):**
- `modules/model/.../dto/RecordingCountsDto.kt` (new)
- `modules/model/.../response/RecordingsStatistics.kt` (+2 fields + KDoc on `processed`)
- `modules/service/.../repository/RecordingEntityRepository.kt` (+1 method, later −3)
- `modules/core/.../service/StatusService.kt` (rewrite `buildRecordings()`)
- `modules/telegram/.../service/impl/StatusMessageFormatter.kt` (rewrite `appendRecordings()` + new `pct()` helper, delete `formatRow()`)
- `modules/telegram/src/main/resources/messages_{en,ru}.properties` (+3 keys each, later −2 each)

**Tests (5):**
- `core/.../service/StatusServiceTest.kt`
- `core/.../controller/StatusControllerTest.kt`
- `core/.../controller/StatusControllerTestConfig.kt` (compile-fix only)
- `core/.../repository/RecordingEntityRepositoryTest.kt` (helper extension + 2 new tests, replace 3 deleted)
- `telegram/.../service/impl/StatusMessageFormatterTest.kt` (5 compile-fix sites: 4 outer + 1 nested I18nTest; +3 layout tests in outer class; +2 real-bundle tests in nested I18nTest)

### Implementer subagent reminders

When dispatching the implementer subagent for each task, include in the prompt:
- The FULL TEXT of the task from the plan (don't make the subagent read the plan file).
- A note about the project rule «no direct gradle» — the subagent should use `/build` slash command if it needs to verify (or skip verification and let the controller dispatch `build-runner`).
- For Task 6 Step 4c specifically: emphasize «extend helper signature», do not use `.copy()` workaround.
- For Task 5: emphasize the layout-C identity-mock format `key[arg0,arg1]` (so the subagent doesn't try to use the real `MessageResolver` in those tests).

## PLAN QUALITY WARNING

The plan was reviewed externally in iter-1 (5 reviewers, 30 issues). Most line refs were verified, but some may have shifted after iter-1 edits. Particular spots to watch:

- **Task 6 Step 4c helper extension:** the line numbers reference `RecordingEntityRepositoryTest.kt:36-65` for the helper; confirmed correct (helper exists at lines 36-65, hardcoded `errorMessage = null` at line 63). If file changed since, re-locate by content.
- **Task 5 Step 1 identity-mock convention:** assertions use `status.recordings.value.withPct[85,85.0]`. If the mock convention in `StatusMessageFormatterTest.kt` has changed (look at the fixture at top of the file), adjust assertion strings to match.
- **`/build` vs direct gradle:** plan still mentions `./gradlew :module:test` in a few spots (Task 2 Step 6, Task 3 Step 2, Task 5 Step 2/4, Task 6 Step 5). Project rule overrides — prefer `/build` or `build-runner` for the FULL build cycle. Direct per-test gradle invocations are acceptable for quick subagent verification of a single Step, but the FINAL build (Task 7) must go through `build-runner`.

**If you notice any issues during implementation:**
1. STOP before proceeding with the problematic step.
2. Clearly describe the problem you found.
3. Explain why the plan doesn't work or seems incorrect.
4. Ask the user how to proceed.

Do NOT silently work around plan issues or make significant deviations without user approval.

## INSTRUCTIONS

1. Read both design and plan documents (and optionally the iter-1 log if a disputed decision needs verification).
2. Confirm you understand the current progress and session context.
3. Provide a brief summary of what you understood.
4. **STOP and WAIT** — do NOT proceed with any implementation.
5. Ask: «Запускаем `/superpowers:subagent-driven-development` для исполнения плана?»
