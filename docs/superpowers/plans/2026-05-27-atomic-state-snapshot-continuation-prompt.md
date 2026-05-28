# Fresh Session Continuation Prompt — Atomic State Snapshot Refactor (Tasks 4 + 5)

## TASK

Continue executing the implementation plan for the "Atomic snapshot for `@Volatile` runtime state" refactor in the Frigate Analyzer project. Tasks 1, 2, 3 are DONE and committed on branch `refactor/atomic-state-snapshot`. Tasks 4 (WatchRecordsTask) and 5 (final build + PR) remain.

Use `/superpowers:subagent-driven-development` for execution (or invoke via `/do-plan <threshold>` for the same orchestration with automatic context-threshold pause).

## CRITICAL: DO NOT START WORKING

**STOP. READ THIS CAREFULLY.**

After loading all context below, you MUST:
1. Read the documents and understand the context
2. Report what you understood (brief summary — the 4 target classes + 5 tasks + status)
3. **WAIT for explicit user instructions** before taking ANY action

**DO NOT:**
- Start implementing tasks
- Make any code changes
- Run any commands (except reading documents and confirming git state with `git log --oneline`)
- Assume what task to work on next

**The user will tell you exactly what to do.** Until then, only read and summarize.

## DOCUMENTS

Read in this order:

1. **Plan (trimmed):** `docs/superpowers/plans/2026-05-27-atomic-state-snapshot.md` — Tasks 1-3 reduced to ✅ markers + commit SHAs; Tasks 4 + 5 fully intact (~600 lines of remaining work).
2. **Design:** `docs/superpowers/specs/2026-05-27-volatile-snapshot-design.md` — architectural spec. Read §4.2 (WatchTaskState), §5 (test ergonomics), §7 (error handling — sticky semantics), §10 (known limitations).
3. **Review iter 1 finalization (background):** `docs/superpowers/specs/2026-05-27-volatile-snapshot-review-iter-1.md` — status table of 35 review issues with decisions + commits. Read this AFTER the plan to understand WHY specific design choices were made.

## PROGRESS

**Completed tasks (already committed):**

- [x] **Task 1: ServerState → AtomicReference HealthSnapshot**
  - Implementation: commit `28fdabc`
  - Review follow-up: commit `556c877` (KDoc + atomic toString)
  - Spec review ✅ / Code quality review ✅
  - 210 core-module tests pass
- [x] **Task 2: ActiveExportRegistry.Entry → AtomicReference EntryState**
  - Implementation: commit `73da93c`
  - Review follow-up: commit `1aada9b` (`snapshot()` KDoc disclosure)
  - Spec review ✅ / Code quality review ✅
  - 249 telegram-module tests pass
- [x] **Task 3: TelegramBotSupervisor → AtomicReference SupervisorState**
  - Implementation: commit `df9caf7`
  - Review follow-up: commit `8c82ced` (test comment polish)
  - Spec review ✅ / Code quality review ✅
  - 253 telegram-module tests pass (4 new deterministic transition tests added)
- [x] **Docs commit:** `421673d` — plan trimmed (Tasks 1-3 reduced to ✅ markers)

**Remaining tasks:**

- [ ] **Task 4: WatchRecordsTask → AtomicReference WatchTaskState** (the largest one — 11 fields)
- [ ] **Task 5: Full build + git rm spec/plan docs + PR open**

## SESSION CONTEXT (implicit knowledge from Tasks 1-3 — NOT in the plan)

### Build / tooling

- **Gradle module paths**: project names are `:frigate-analyzer-core:`, `:frigate-analyzer-telegram:` (NOT `:modules:core:` / `:modules:telegram:` as the plan still shows in places). The actual `settings.gradle.kts` renames each include to `frigate-analyzer-<module>`. Use the correct names when invoking gradle.
- **build-runner subagent is NOT dispatchable from inside another subagent**. Implementer subagents had to run `./gradlew` directly. This is fine — controller never ran build directly in the main session.
- **ktlint pattern**: run `:frigate-analyzer-<module>:ktlintFormat` (or `ktlintCheck`) before `:test`. Plan's mentions of `ktlintFormat` were correct.

### Refactor pattern established across Tasks 1-3 (use as template for Task 4)

```kotlin
internal data class XState(
    // all observability fields with defaults
)

/**
 * Single source of truth for runtime metrics. ALL writes MUST go through
 * [AtomicReference.updateAndGet] / [AtomicReference.getAndUpdate] on [state];
 * direct `state.set(...)` is reserved for test fixtures via [stateForTesting].
 * Reader code MUST do exactly one `state.get()` at the top of any method that
 * touches more than one field. This guarantees no reader observes a partial
 * snapshot ... even under concurrent writers.
 */
private val state = AtomicReference(XState())

/**
 * Test-fixture access ... **DO NOT USE FROM PRODUCTION CODE.** Direct
 * `state.set(...)` bypasses the CAS discipline ... Visibility is `internal`
 * to confine misuse to the test source set within this module; review
 * discipline enforces the rule (no runtime check).
 */
internal var stateForTesting: XState
    get() = state.get()
    set(value) { state.set(value) }
```

Plus convenience getters for read-heavy production sites that read ONE field (`val alive: Boolean get() = healthRef.get().alive`-style).

### Key design decisions confirmed during execution

- **CRITICAL-3 / multi-writer awareness**: applied as needed. For Task 4 (WatchRecordsTask) the runSupervised is effectively single-writer (one coroutine), so transition-detection via `getAndUpdate`-vs-`updateAndGet` is less critical, but the spec still calls for `getAndUpdate` in the tail backoff bump (CRITICAL-2).
- **CRITICAL-4 STICKY semantics**: `lastFailure` / `lastFailureAt` MUST NOT be cleared on success transitions. In Task 3 the success-stable branch's `.copy(...)` did NOT touch these fields — preserved automatically. The plan's Task 4 transition methods (`onPollCompleted`) currently DO clear `consecutiveFailures` on empty poll but DO NOT touch `lastFailure*` — sticky preserved. Make sure new test fixtures don't accidentally assert `lastFailure == null` after recovery from a failure.
- **CRITICAL-2 single getAndUpdate for tail bump**: in TelegramBotSupervisor the catch-Exception branch INTENTIONALLY kept a separate `state.get()` for the log message to preserve pre-refactor log content. **For WatchRecordsTask the plan says the OPPOSITE** — move `logger.error` AFTER `onLoopFailure(e)` and use the local `effectiveBackoff` from `getAndUpdate` for the log. This is a behavioral cleanup specific to Task 4 (see plan Task 4.4 / "WatchRecordsTask quirk" note in execution-prompt).
- **`onAttemptEnded` visibility bump in Task 3**: `private` → `internal` to enable the new deterministic transition tests. Task 4 likely needs the same for `onPollCompleted`, `onRegistrationSuccess/Failure`, `onLoopFailure`, AND for the new `maybeResetBackoffTransform` (latter was already planned `internal` per CRITICAL-1).
- **HealthIndicatorTest files in BOTH supervisors had ZERO direct field accesses**. The Task 3 `TelegramBotSupervisorHealthIndicatorTest.kt` (33 lines) was confirmed unedited. Likely same for `WatchRecordsTaskHealthIndicatorTest.kt` (33 lines per controller check). Verify before declaring done but don't expect to touch it.

### Test patterns established

- **Multi-field test setup** collapses cleanly: every consecutive `task.fieldX = ...; task.fieldY = ...` block becomes one `task.stateForTesting = WatchTaskState(...)` construction. Keep inline `// comments` on the corresponding `.copy(...)` lines.
- **Multi-field test reads** become `val s = task.stateForTesting; s.X; s.Y` (one snapshot read, then field access). Single-field reads stay `task.stateForTesting.X`.
- **Constants like `INITIAL_BACKOFF_MS` in test files** that mirror file-private production constants get a "// Mirrors X in WatchRecordsTask.kt — keep in sync" comment (added in Task 3 follow-up; same pattern for Task 4 if applicable).
- **Deterministic transition tests** use full snapshot equality: `assertEquals(initialFixture.copy(<delta>), task.stateForTesting)`. This catches accidental field mutations that a per-field assertion would miss.

### Tail-bump observable semantic change (BE AWARE for Task 4 tests)

Under the OLD `delay; bump` order, the persisted `currentBackoff` value DURING the tail delay was the PRE-bump value (e.g., 5s for the first failure). Under the NEW atomic `getAndUpdate; delay` order (CRITICAL-2 option A):
- The persisted value during delay is the POST-bump value (e.g., 10s = 2 × INITIAL).
- The local `effectiveBackoff` captured from `getAndUpdate`'s return drives `delay()` (still 5s for the first failure — observable wait time unchanged).

Existing tests that assert `task.stateForTesting.currentBackoff == INITIAL_BACKOFF` while the task is parked in the tail delay will need to be updated to `2 * INITIAL_BACKOFF` (or the next bump-up value). In Task 3 ONE such test was updated and the reasoning was reviewed and confirmed correct (still discriminates "reset happened" vs "no reset"). Task 4 may have a similar case in `WatchRecordsTaskTest.kt` — flag and adjust similarly.

### Follow-up commit pattern after each task

Every task ended with a small KDoc/comment-only follow-up commit addressing the code reviewer's minor / important findings. Don't amend the implementer's main commit (per CLAUDE.md "ALWAYS create NEW commits rather than amending"). Plan to do the same for Task 4.

### Plan inaccuracies discovered during execution

- **Plan said the `markServerDead` writer makes 3 writers for ServerState**. Verified: the only `markServerDead` call site is currently commented out at `DetectService.kt:113`. Task 1 KDoc was updated to note this is "latent". Watch for similar "dormant writer" / "speculative API" disclosures needed in Task 4 (e.g., `WatchTaskState.snapshot()` may not have any production caller — mirror the Task 2 `Entry.snapshot()` disclosure).
- **Plan's gradle paths**: `:modules:core:` was wrong (actual: `:frigate-analyzer-core:`).

## TASK 4 SPECIFICS (heads-up before you start)

Per plan §Task 4 (full text in the trimmed plan file):

- **Production target**: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsTask.kt` (391 lines pre-refactor)
- **Test targets**: `WatchRecordsTaskTest.kt` (471 lines), `WatchRecordsTaskHealthIndicatorTest.kt` (33 lines — likely no field access)
- **11 fields** to fold into `WatchTaskState`: startupAt, lastSuccessfulPollAt, lastEventProcessedAt, lastSuccessfulRegistrationAt, consecutiveEventFailures, consecutiveRegistrationFailures, consecutiveFailures, successesSinceLastFailure, currentBackoff, lastFailure, lastFailureAt.
- **New type**: `internal data class BackoffResetResult(val state, val didReset, val nextSuccessesAtReset)` — bundles state with side-effect-free reset signal.
- **`maybeResetBackoffTransform` becomes `internal`+pure**: returns `BackoffResetResult` (no logging inside). The `logger.info { "Backoff reset after..." }` lifts OUT of the `updateAndGet` transform into the `onPollCompleted` caller, captured via vars (`didResetBackoff`, `resetAfterSuccesses`) that are **unconditionally reset at the TOP of every CAS attempt** (defensive against CAS retry — the lambda may run multiple times under contention).
- **New helper `applyEventsProcessedTransform(s, now): BackoffResetResult`** mirrors maybeResetBackoffTransform pattern for the `eventsProcessed > 0` branch.
- **runSupervised has 3 catch branches** — each uses a single `getAndUpdate { it.copy(currentBackoff = nextBackoff(it.currentBackoff)) }.currentBackoff` for the backoff bump. The `catch (Exception)` branch ALSO moves `logger.error` AFTER `onLoopFailure(e)` and uses the local `effectiveBackoff` for the log message (CRITICAL-2 quirk specific to Task 4 — see plan).
- **`computeHealth`** has 8 branches including BRANCH 3.5 — preserve the numbering (intentional per SUGGESTION-11 in review iter 1 to keep git-blame mapping).
- **`baseBuilder(s: WatchTaskState)`** helper extracted from inline `computeHealth` (similar to Task 3's baseBuilder).
- **Test helper `buildTask(...)`** in WatchRecordsTaskTest.kt currently takes 9 keyword params — collapse to ONE `state: WatchTaskState = WatchTaskState(...)` param.
- **3 unit tests for `maybeResetBackoffTransform`** (no-op / increment / reset) — directly testable now that it's pure.
- **8 deterministic transition tests** with full snapshot equality (per spec): 5 cases for onPollCompleted + 1 for onRegistrationSuccess + 1 for onRegistrationFailure + 1 for onLoopFailure.
- **Note on "wasted increment" quirk**: when `eventsProcessed > 0 && eventFailures > 0`, `successesSinceLastFailure` is incremented (in maybeResetBackoffTransform's flow) and immediately zeroed by the failure branch — pre-existing behavior, preserved. `didResetBackoff` flag is NOT cleared (an actual reset remains observable). Add inline comment per SUGGESTION-8.

## PLAN QUALITY WARNING

- Line numbers in the plan body may be stale after Tasks 1-3 production edits to neighboring files. Always grep before applying — locate by surrounding context.
- `gradle` paths in the plan say `:modules:core:` — use `:frigate-analyzer-core:` instead.
- `STABLE_THRESHOLD` constant exists in TelegramBotSupervisor only — WatchRecordsTask uses different constants (`HEALTH_STALENESS`, `STARTUP_GRACE`, `STARTUP_FAILURE_THRESHOLD`, `SUCCESSES_TO_RESET_BACKOFF`). The plan's branch numbering in computeHealth refers to WatchRecordsTask's own 8-branch table; do not confuse with TelegramBotSupervisor's 7-branch table.
- The `BackoffResetResult` type is NEW — it propagates through 3 places (`maybeResetBackoffTransform` returns it; `applyEventsProcessedTransform` returns it via delegation; `onPollCompleted` captures `didReset` and `nextSuccessesAtReset` from it). Mis-wiring breaks the "log fires once per actual reset" guarantee.
- **Captured-var-in-CAS-retry pattern**: `didResetBackoff` and `resetAfterSuccesses` MUST be unconditionally re-assigned at the TOP of every CAS attempt (inside the lambda body). If you only assign them inside the `if (eventsProcessed > 0)` branch, a CAS retry might leave stale values from a prior attempt.
- **Single-writer assumption is wrong for ServerState/Entry but correct for WatchRecordsTask/TelegramBotSupervisor** (one supervisor coroutine = one writer). Don't add a "single-writer optimization" — the CAS-based code handles concurrent reads (from health indicator) correctly regardless.

**If you notice any issues during implementation:**
1. STOP before proceeding with the problematic step.
2. Clearly describe the problem you found.
3. Explain why the plan doesn't work or seems incorrect.
4. Ask the user how to proceed.

Do NOT silently work around plan issues or make significant deviations without user approval.

## TASK 5 SPECIFICS

Per plan §Task 5:
- Full project build (`./gradlew build`) + test (`./gradlew test`).
- Per global CLAUDE.md: before opening PR, `git rm` ALL files under `docs/superpowers/specs/` and `docs/superpowers/plans/` that belong to this refactor:
  - `docs/superpowers/specs/2026-05-27-volatile-snapshot-design.md`
  - `docs/superpowers/specs/2026-05-27-volatile-snapshot-review-merged-iter-1.md`
  - `docs/superpowers/specs/2026-05-27-volatile-snapshot-review-parsed-iter-1.md`
  - `docs/superpowers/specs/2026-05-27-volatile-snapshot-review-iter-1.md`
  - `docs/superpowers/plans/2026-05-27-atomic-state-snapshot.md`
  - `docs/superpowers/plans/2026-05-27-atomic-state-snapshot-execution-prompt.md`
  - `docs/superpowers/plans/2026-05-27-atomic-state-snapshot-continuation-prompt.md` (this file)
- Commit: `chore: drop plan + spec docs before opening PR`
- Leave `docs/health-volatile-snapshot-issue.md` (permanent context doc).
- Then `gh pr create` per plan Step 5.5.

## SESSION STATE AT HANDOFF

- **Working branch**: `refactor/atomic-state-snapshot`
- **HEAD**: `421673d` (the plan-trim commit)
- **Last 8 commits on the branch**:
  ```
  421673d docs: trim completed tasks 1-3 from atomic-snapshot plan
  8c82ced review: TelegramBotSupervisorTest comment polish (Task 3 follow-up)
  df9caf7 refactor(telegram): atomic SupervisorState for TelegramBotSupervisor
  1aada9b review: Entry.snapshot() KDoc disclosure (Task 2 follow-up)
  73da93c refactor(telegram): atomic EntryState for ActiveExportRegistry.Entry
  556c877 review: ServerState KDoc + atomic toString (Task 1 follow-up)
  28fdabc refactor(loadbalancer): atomic HealthSnapshot for ServerState
  d846e75 docs: execution prompt for atomic snapshot refactor (post-iter-1)
  ```
- **Base for full-implementation diff**: `d846e75` (last doc-only commit before any production code touched).
- **Working tree**: clean (no uncommitted changes).

## INSTRUCTIONS

1. Read the documents listed above (plan, design, review iter-1 finalization).
2. Confirm the current git state with `git log --oneline d846e75..HEAD` (should show 9 commits).
3. Provide a brief summary of what you understood — the 4 target classes, the 5 tasks, current progress.
4. **STOP and WAIT** — do NOT proceed with any implementation.
5. Ask: "What would you like me to work on?"

The expected next action is `/do-plan <threshold>` (or `/do-plan` for the default 250k threshold) to resume `subagent-driven-development` execution starting with Task 4.
