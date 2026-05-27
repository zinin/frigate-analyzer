# Fresh Session Execution Prompt — Atomic State Snapshot Refactor

## TASK

Execute the implementation plan for the "Atomic snapshot for `@Volatile` runtime state" refactor in the Frigate Analyzer project.

Use `/superpowers:subagent-driven-development` skill for execution.

## DOCUMENTS

Read in this order:

1. **Plan:** `docs/superpowers/plans/2026-05-27-atomic-state-snapshot.md` — the implementation guide. 5 Tasks, 30+ steps with explicit code blocks and grep commands.
2. **Design:** `docs/superpowers/specs/2026-05-27-volatile-snapshot-design.md` — the architectural spec. Read §1 (rationale), §3 (common pattern), §4 (per-class spec), §5 (test ergonomics), §6 (migration order), §7 (error handling), §10 (known limitations).
3. **Review iter-1 finalization (background):** `docs/superpowers/specs/2026-05-27-volatile-snapshot-review-iter-1.md` — status table of 35 review issues with their decisions and commits. **Read this AFTER the plan** to understand why specific design choices were made (option A vs B/C/D for each disputed item).

## IMPORTANT: DO NOT START WORK YET

After reading the documents:
1. Confirm you have loaded all context (briefly summarize the 4 target classes + 5 tasks).
2. Wait for the user to explicitly tell you to start.

Do NOT begin implementation until the user explicitly tells you to start.

## SESSION CONTEXT

### Branch state at handoff

- **Working branch:** `refactor/atomic-state-snapshot` (NOT master).
- **Last commit:** `7da893d` — "docs: review iter 1 — decisions + log (volatile-snapshot)".
- **Production code touched:** NONE. All commits to date are design + plan + review documents only. Task 1 will be the first commit touching production Kotlin.
- **9 commits worth of design + plan refinement** completed in review iter 1 (commits `fe27d01`..`7da893d`). The plan you'll execute is the final post-iter-1 shape — do NOT consult any prior plan version.

### Target classes (in execution order — simple to complex)

| Order | Class | Module | Task | Approx LoC |
|-------|-------|--------|------|------------|
| 1 | `ServerState` | core | Task 1 | ~30-50 |
| 2 | `ActiveExportRegistry.Entry` | telegram | Task 2 | ~40-60 |
| 3 | `TelegramBotSupervisor` | telegram | Task 3 | ~100-130 |
| 4 | `WatchRecordsTask` | core | Task 4 | ~130-160 |
| — | Final build + PR prep | all | Task 5 | none |

Each task = independent commit; build + tests must pass after each.

### Key decisions from review iter 1 (DO NOT deviate without asking)

These were all DISPUTED items resolved via explicit user choice. The plan reflects the final shape, but be aware **why** the plan looks the way it does:

1. **CRITICAL-1 (option A — `BackoffResetResult`).** `maybeResetBackoffTransform` in `WatchRecordsTask` is now `internal` (was originally private) and returns `BackoffResetResult(state, didReset, nextSuccessesAtReset)`. The helper is FULLY PURE — no logging inside. The `logger.info { "Backoff reset after..." }` call lifts OUT of `state.updateAndGet` into the `onPollCompleted` caller, which uses captured-on-every-retry `var`s (`didResetBackoff`, `resetAfterSuccesses`) reset at the top of every CAS attempt. After `updateAndGet` settles, the caller logs ONCE based on the final-committed flag.

   **Why:** `AtomicReference.updateAndGet` may retry the transform under CAS contention; a side-effect inside the transform would produce duplicate log entries on retry. Spec §7 requires pure transforms.

2. **CRITICAL-2 (option A — single `getAndUpdate`).** Both `WatchRecordsTask.runSupervised` and `TelegramBotSupervisor.runSupervised` use ONE `state.getAndUpdate { it.copy(currentBackoff = nextBackoff(it.currentBackoff)) }.currentBackoff` per backoff cycle. The returned PRE-bump value drives the `delay(...)`; the post-bump value persists in state for the next iteration.

   **WatchRecordsTask quirk:** the `catch (e: Exception)` branch moves `logger.error` AFTER `onLoopFailure(e)` and uses the local `effectiveBackoff` from `getAndUpdate`. This is a behavioral cleanup — the log now reports the actual backoff used for the delay.

   **TelegramBotSupervisor quirk:** the `catch (e: Exception)` branch KEEPS the `logger.error` inside catch with a separate `state.get()` call. **Intentionally NOT consolidated** — this preserves pre-refactor log content (the message says "next backoff=Xms" where X is the PRE-`onAttemptEnded` value, not the post-bump value). `onAttemptEnded` may legitimately reset `currentBackoff` between the log and the delay — a pre-existing observable inconsistency, out of scope for this refactor.

3. **CRITICAL-3 (option A — `getAndUpdateHealth`).** `ServerState` gets a NEW method `fun getAndUpdateHealth(transform: (HealthSnapshot) -> HealthSnapshot): HealthSnapshot` returning the PRE-update snapshot. All 3 sites in `ServerHealthMonitor` (success callback, error callback, `markServerDead`) use it and decide whether to log "now ALIVE"/"now DEAD"/"marked as dead" based on `prev.alive`. This closes the check-then-act race against 3 concurrent writers (WebClient success, WebClient error, `markServerDead` from request-handler threads).

   `markServerDead` ALSO uses this pattern (a new addition vs. the original plan): only logs "marked as dead" on the actual alive→dead edge.

4. **CRITICAL-4 (option A — sticky semantics preserved).** `lastFailure` and `lastFailureAt` in both `SupervisorState` and `WatchTaskState` are **STICKY** — never cleared on success. This matches pre-refactor production behavior; existing tests assert sticky, so they should NOT need rewriting on this dimension. The plan's Task 5.3 verification step has been updated to NOT expect `consecutiveFailures == 0 → lastFailureAt == null`.

5. **CONCERN-3 (option B — regular class for ServerState).** `ServerState` is now `class ServerState`, NOT `data class`, with explicit `override fun equals/hashCode/toString` (`equals` by `id` only). Verified via grep that the codebase doesn't use `.copy()`, destructuring, or `componentN` on ServerState — only one constructor call exists (`DetectServerRegistry.kt:15`).

6. **CONCERN-5 (option D — enhanced KDoc).** `stateForTesting` keeps `internal var` visibility (no annotation, no separate test-source-set extension) but with a strong-worded KDoc: "DO NOT USE FROM PRODUCTION CODE — direct `state.set(...)` bypasses the CAS discipline". The project has no `@VisibleForTesting`/`@TestOnly` infrastructure (verified via grep — no Guava, no JetBrains annotations dep), so adding one for one var per class would be disproportionate.

### Additional auto-fixes integrated into the plan

- **GLM-Q2:** `onPollCompleted` extracted `applyEventsProcessedTransform(s, now): BackoffResetResult` helper alongside `maybeResetBackoffTransform`.
- **SUGGESTION-8:** inline comment in `onPollCompleted` documents the "wasted increment" quirk (when `eventsProcessed>0 && eventFailures>0`, `successesSinceLastFailure` is incremented then zeroed — pre-existing behavior preserved).
- **SUGGESTION-2:** deterministic transition tests with full snapshot-equality assertions added to plan Tasks 3.8 + 4.8 (4 onAttemptEnded cases + 8 onPollCompleted/registration/loop cases).
- **SUGGESTION-5:** plan Task 4.5b adds 3 parametrized tests for the now-pure `maybeResetBackoffTransform` (no-op / increment / reset).
- **SUGGESTION-10 + QUESTION-9:** every refactor task (1.1, 2.1, 3.1, 4.1) starts with explicit `git grep` commands to enumerate readers/writers + "STOP if production-writer found outside the target class" safety check.
- **GLM-Concern-7:** plan Task 2.2 explicitly says "we **update** the existing Entry comment at lines 33-36, NOT add a new block".
- **GLM-Suggestion-11:** commit-message bodies in plan Steps 1.6/2.6/3.11/4.11 say "Per the atomic snapshot refactor design (see branch history)" instead of "Spec §X" (spec/plan get `git rm`-ed before PR; file-paths would dangle).

### Project rules from `CLAUDE.md`

- **Git workflow:** ALWAYS `git add <file>` after creating or modifying.
- **Planning Mode:** do NOT run `./gradlew build` directly. After implementation: run `superpowers:code-reviewer` agent first; fix critical comments; then use `build-runner` agent. On ktlint errors: `./gradlew ktlintFormat`, retry build.
- **Build commands:** `/build` slash command preferred for automated build with error handling.
- **External review:** the iter 1 review is ALREADY complete (9 commits worth). Do NOT re-launch external review during execution — user can request iter 2 after Tasks 1-4 land if desired.

### Before opening the PR

Per global CLAUDE.md and project convention:
1. `git rm` ALL files under `docs/superpowers/specs/` and `docs/superpowers/plans/` that belong to this refactor. Specifically:
   - `docs/superpowers/specs/2026-05-27-volatile-snapshot-design.md`
   - `docs/superpowers/specs/2026-05-27-volatile-snapshot-review-merged-iter-1.md`
   - `docs/superpowers/specs/2026-05-27-volatile-snapshot-review-parsed-iter-1.md`
   - `docs/superpowers/specs/2026-05-27-volatile-snapshot-review-iter-1.md`
   - `docs/superpowers/plans/2026-05-27-atomic-state-snapshot.md`
   - `docs/superpowers/plans/2026-05-27-atomic-state-snapshot-execution-prompt.md` (this file)
2. Commit: `chore: drop plan + spec docs before opening PR`.
3. Leave `docs/health-volatile-snapshot-issue.md` alone (permanent context doc, not scratch).
4. Then `gh pr create` per plan Step 5.5.

## PLAN QUALITY WARNING

The plan was written for a multi-class refactor, then iteratively refined by 9 review commits. It may still contain:

- **Stale line numbers.** Several rounds of edits shifted lines in both production files (which haven't been touched yet) and the plan itself. When the plan says "lines 33-45 of ActiveExportRegistry.kt", VERIFY by reading the current file before applying — the lines may be off by a small offset.
- **Test file path assumptions.** The plan lists specific test files (e.g., `WatchRecordsTaskHealthIndicatorTest.kt`). Some may not exist — Step 3.9 / 4.9 say "if applicable". Don't fabricate test files; verify existence first.
- **Implementation detail vs. plan.** The plan was reviewed for design correctness, not line-by-line compilability. You may find that a snippet uses a constant or import that doesn't exist in the current production code (e.g., `STABLE_THRESHOLD` location). If so:
  1. STOP before changing it.
  2. Read the relevant production file to find the actual symbol.
  3. Adjust the plan's snippet to match — but flag it to the user if the discrepancy is large.
- **`BackoffResetResult` interactions.** This type is new (introduced for CRITICAL-1). It propagates through 3 places: `maybeResetBackoffTransform` (returns it), `applyEventsProcessedTransform` (returns it via delegation), `onPollCompleted` (captures `didReset`/`nextSuccessesAtReset` from it). Mis-wiring any of these breaks the "log fires once per actual reset" guarantee — be careful.
- **Captured-var-in-CAS-retry pattern.** The vars `didResetBackoff` and `resetAfterSuccesses` MUST be unconditionally re-assigned at the TOP of every CAS attempt (inside the lambda body). If you accidentally only assign them inside the `if (eventsProcessed > 0)` branch, a CAS retry might leave them with stale values from a prior attempt.
- **Single-writer assumption is wrong for ServerState/Entry.** Don't add a "single-writer optimization" anywhere — both classes have multiple writers (3 for ServerState, 2 for Entry). The CAS-based code handles this correctly.

**If you notice any issues during implementation:**
1. STOP before proceeding with the problematic step.
2. Clearly describe the problem you found.
3. Explain why the plan doesn't work or seems incorrect.
4. Ask the user how to proceed.

Do NOT silently work around plan issues or make significant deviations without user approval.
