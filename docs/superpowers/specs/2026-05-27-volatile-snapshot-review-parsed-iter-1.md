# Parsed Issues — Iteration 1 (checkpoint)

**Source:** review-discussion agent output (run before CCS GLM structured review arrived).
**Total issues:** 24 (4 Critical, 6 Concerns, 11 Suggestions, 3 standalone Questions — Q1/Q2/Q3 are resolved by their parent Critical/Concern).

**Note:** This parse used input from 4 agents (codex, ollama-kimi, ollama-minimax, ollama-deepseek). GLM final review arrived later — see addendum at bottom for GLM-only deltas to fold in.

---

## CRITICAL-1 — `maybeResetBackoffTransform` impure

`logger.info` inside `state.updateAndGet` lambda violates Spec §7 ("Transform-функции — pure"). `AtomicReference.updateAndGet` may retry the lambda under CAS contention → duplicate logs.

**Raised by:** codex-gpt-5.5, ollama-kimi, ollama-minimax, ollama-deepseek (4/4 structured reviewers — strongest signal).
**Status:** NEW.
**Options:**
1. **Recommended.** Return `Pair<WatchTaskState, Boolean>` or `BackoffResetResult(state, didReset)`; log outside `updateAndGet` based on flag.
2. Compare pre/post values after `updateAndGet` (`if (pre.currentBackoff > INITIAL && post.currentBackoff <= INITIAL) logger.info`).
3. Acknowledge impurity in KDoc with "safe under single-writer" — weaker, contradicts spec §7.

**Classification:** DISPUTED (multiple reasonable approaches; recommend option 1).

---

## CRITICAL-2 — `runSupervised` split read+update violates single-snapshot discipline

`val backoff = state.get().currentBackoff; delay(...); state.updateAndGet { ... nextBackoff(it.currentBackoff) }` is two snapshots. Also: `logger.error { ... state.get().currentBackoff ... }` in catch block adds extra `state.get()`.

**Raised by:** ollama-kimi, ollama-deepseek, ollama-minimax (partly), codex-gpt-5.5.
**Status:** NEW.
**Options:**
1. **Recommended.** `val effectiveBackoff = state.getAndUpdate { it.copy(currentBackoff = nextBackoff(it.currentBackoff)) }.currentBackoff; delay(effectiveBackoff.toMillis())`. Single atomic RMW. Capture into local for log.
2. Capture `val backoff = state.get().currentBackoff` once at top of iteration, use for both delay and logging — minimal refactor but still two-stage flow.
3. Document "two reads pattern is acceptable for single-writer" + inline comment — no code change, weakens discipline.

**Classification:** DISPUTED (clear best option but worth surfacing because impacts both supervisors).

---

## CRITICAL-3 — `ServerState` not single-writer; logging transition race

WebClient `subscribe` callbacks + `markServerDead` = 3 writer paths. `val wasAlive = server.alive; server.updateHealth { ... }` is check-then-act. For Entry: `attachCancellable` + `markCancelling` = 2 writer paths; spec §2 single-writer claim is wrong.

**Raised by:** codex-gpt-5.5, ollama-minimax.
**Status:** NEW.
**Options:**
1. **Recommended.** Add `getAndUpdateHealth` returning previous snapshot; rewrite as `val prev = server.getAndUpdateHealth { it.copy(alive = true, lastCheckTimestamp = now) }; if (!prev.alive) logger.info { ... }`. Eliminates race.
2. Capture `val now = clock.instant()` before `updateHealth`; keep `wasAlive = server.alive` with KDoc caveat — fixes timestamp staleness only.
3. Reformulate spec §2: remove "single-writer for all 4" blanket claim, document per-class writer counts — docs-only.

**Classification:** DISPUTED (CRITICAL-4 ServerState-related, CRITICAL-3 Entry-related — combine?).

---

## CRITICAL-4 — `lastFailure`/`lastFailureAt` semantics inconsistent

Verification step expects clearing on success; current code keeps sticky; tests assert sticky. Also: stable-success path doesn't clear, so `/actuator/health` shows UP with old failure detail.

**Raised by:** codex-gpt-5.5, ollama-kimi.
**Status:** NEW.
**Options:**
1. **Recommended.** Decide "sticky": keep current behavior, remove `consecutiveFailures == 0 → lastFailureAt == null` expectation from Plan verification step. Add doc paragraph "lastFailure/lastFailureAt represent last observed failure, NOT current state".
2. Decide "current": clear in stable-success branches, update tests asserting sticky — invasive, changes health JSON.
3. Add separate `currentFailure`/`currentFailureAt` cleared on success — adds 2 fields, most accurate semantics.

**Classification:** DISPUTED (semantic decision needed; recommend sticky to minimize change).

---

## CONCERN-1 — Atomic snapshot narrower than claimed (Job, registeredDirs, counters outside snapshot)

`supervisorJob.isActive` + state snapshot, `registeredDirs.isEmpty()` + state snapshot, `canAcceptRequest` reads `alive` + counter — all are 2-source reads.

**Raised by:** codex-gpt-5.5, ollama-deepseek.
**Status:** NEW.
**Options:**
1. **Recommended.** Add "Known Limitations" section listing residual races (Job, registeredDirs, counter pairs in canAcceptRequest).
2. Move `supervisorJob.isActive` snapshot into State — overkill.
3. Narrow the claim wording globally — terminology fix.

**Classification:** AUTO-FIX (docs-only option 1).

---

## CONCERN-2 — Throwable JMM rationale inaccurate

`Throwable.cause`/`stackTrace` are mutable post-construction (`initCause`, `setStackTrace`). AtomicReference safe-publishes prior writes, doesn't make Throwable immutable.

**Raised by:** codex-gpt-5.5.
**Status:** NEW.
**Options:**
1. **Recommended.** Replace spec §7 wording with "effectively immutable post-catch in this codebase; we don't call initCause/setStackTrace on captured exceptions".
2. Introduce `FailureSnapshot(className, sanitizedMessage, at)` data class — invasive.
3. Keep current text — not recommended, will surface in next review.

**Classification:** AUTO-FIX (wording fix, option 1).

---

## CONCERN-3 — `ServerState` API/semantic changes more than plan acknowledges

`alive`/`lastCheckTimestamp` move from primary-constructor (in `data class copy/equals/hashCode`) to private `healthRef` (not in generated methods). `AtomicInteger` counters compared by reference identity — pre-existing footgun.

**Raised by:** codex-gpt-5.5, ollama-kimi.
**Status:** NEW.
**Options:**
1. **Recommended.** Add explicit note in spec §4.4: "API change: `alive`/`lastCheckTimestamp` no longer in `copy/equals/hashCode`; counters compared by reference identity is pre-existing — ServerState identified by `id` alone". Plus code comment.
2. Switch from `data class ServerState` to regular class with explicit equals/hashCode based on `id` only — eliminates footgun, removes auto-`copy/componentN/toString`.
3. Override equals/hashCode comparing counters by `.get()` — semantically tricky (mutable equality).

**Classification:** DISPUTED (option 1 docs; option 2 has merit — semantic clean-up matches the spirit of the refactor).

---

## CONCERN-4 — Convenience-getter policy inconsistent and underspecified

No unified rule. ranker reads `server.alive` (single-field); `computeHealth` uses `snapshot()`. For Entry, `entry.cancellable + entry.state` pair-consistency not guaranteed by individual getters.

**Raised by:** codex-gpt-5.5, ollama-kimi, ollama-minimax.
**Status:** NEW.
**Options:**
1. **Recommended.** Add explicit policy in spec §5.1: "Convenience getters allowed only for single-field reads. Multi-field consistency requires explicit snapshot (`server.snapshot()`, `entry.stateRef` via internal accessor, or `stateForTesting`)" + audit production call-sites.
2. Remove convenience getters everywhere — strict but breaks "preserve public API".
3. Keep as-is, no doc — leaves rule unstated.

**Classification:** AUTO-FIX (docs option 1).

---

## CONCERN-5 — `stateForTesting` setter no runtime protection

Setter does `state.set(...)` bypassing `updateAndGet`. No `@TestOnly`/`@VisibleForTesting` annotation. Risk of production-code copy.

**Raised by:** ollama-kimi.
**Status:** NEW.
**Options:**
1. **Recommended.** Add `@VisibleForTesting`-style annotation (project-local marker if guava not on classpath); mark setter `internal` + KDoc.
2. Move to separate test-source-set extension file — strongest protection, more setup.
3. Keep current — minimum effort.

**Classification:** DISPUTED (option choice depends on existing annotation infrastructure).

---

## CONCERN-6 — `Entry.snapshot()` not exposed; production readers undercounted

`ExportExecutor` (lines 115, 268) and `QuickExportHandler` (lines 186, 300) read `entry.state` — not in plan inventory.

**Raised by:** codex-gpt-5.5, ccs-glm (inventory).
**Status:** NEW.
**Options:**
1. **Recommended.** Add `internal fun snapshot(): EntryState = stateRef.get()` on Entry. Update spec §5.1 inventory to include `ExportExecutor` and `QuickExportHandler`.
2. Domain-specific accessors (e.g., `isReadyToCancel()`) — harder to anticipate.
3. Inventory-only update — leaves split-read risk.

**Classification:** AUTO-FIX (option 1).

---

## SUGGESTION-1 — Prefer domain-specific update methods over generic updateState

`entry.updateState(transform)` invites future side-effects. Domain-specific (`entry.markCancelling()`) constrains transitions.

**Raised by:** codex-gpt-5.5.
**Status:** NEW.
**Options:**
1. **Recommended.** Keep generic for now, add future-work note in spec §10.
2. Replace with domain-specific methods — more boilerplate.
3. Hybrid: generic internal + domain-specific public.

**Classification:** AUTO-FIX (note in §10, option 1).

---

## SUGGESTION-2 — Add deterministic transition tests for full state snapshots

Test each `onAttemptEnded` × 3 / `onPollCompleted` × 4 / `maybeResetBackoffTransform` threshold with full snapshot equality.

**Raised by:** codex-gpt-5.5, ollama-minimax, ollama-deepseek.
**Status:** NEW.
**Options:**
1. **Recommended.** Add per-transition snapshot-equality tests using `assertEquals(expected, sup.stateForTesting)`.
2. Decision table comment in spec only — no test code.
3. Skip — covered indirectly.

**Classification:** AUTO-FIX (add to plan as additional steps in Tasks 3-4).

---

## SUGGESTION-3 — Spec §4.2 typo: "декремент" → "инкремент"

`maybeResetBackoffTransform` increments `successesSinceLastFailure`, not decrements.

**Raised by:** codex-gpt-5.5.
**Status:** NEW.
**Classification:** AUTO-FIX (trivial typo, 1 word).

---

## SUGGESTION-4 — Update Entry doc comment about var mutation

Lines 33-36 of current `ActiveExportRegistry.kt`: "Entry holds vars whose values mutate concurrently". After refactor no longer accurate.

**Raised by:** ollama-kimi.
**Status:** NEW.
**Classification:** AUTO-FIX (update comment text in plan Task 2 Step 2.2).

---

## SUGGESTION-5 — Add unit test for `maybeResetBackoffTransform` as pure function

After extraction, trivially unit-testable. Parametrized cases (currentBackoff above/at/below INITIAL × successesSinceLastFailure below/at/above threshold).

**Raised by:** ollama-deepseek.
**Status:** NEW.
**Classification:** AUTO-FIX (add to plan Task 4 as new step).

---

## SUGGESTION-6 — Document `successesSinceLastFailure` visibility change

Was `private @Volatile`; becomes effectively `internal` via `WatchTaskState` (internal data class). Safe but should be flagged.

**Raised by:** ollama-deepseek.
**Status:** NEW.
**Classification:** AUTO-FIX (one-line note in spec §4.2 / plan Task 4).

---

## SUGGESTION-7 — Comment in runSupervised explaining single-writer atomicity

Becomes moot if CRITICAL-2 option 1 (single getAndUpdate) is adopted.

**Raised by:** ollama-minimax.
**Status:** NEW (conditional on CRITICAL-2 decision).
**Classification:** AUTO-FIX (apply after CRITICAL-2 resolved).

---

## SUGGESTION-8 — Document `onPollCompleted` "wasted increment" quirk

When `eventsProcessed>0 && eventFailures>0`, `successesSinceLastFailure` is incremented then zeroed two lines later. Pre-existing.

**Raised by:** ollama-deepseek.
**Status:** NEW.
**Options:**
1. **Recommended.** Inline comment documenting quirk.
2. Reorder transform — changes observable behavior.

**Classification:** AUTO-FIX (option 1 comment).

---

## SUGGESTION-9 — markCancelling KDoc says wrong return type

KDoc claims "return entry snapshot" but returns `Entry`, not `EntryState`. Trivial KDoc fix.

**Raised by:** ollama-kimi.
**Status:** NEW.
**Classification:** AUTO-FIX (KDoc wording).

---

## SUGGESTION-10 — Add inventory grep steps to plan

Plan should include explicit `grep` commands before each refactor task.

**Raised by:** ollama-minimax, ccs-glm (inventory provided).
**Status:** NEW.
**Classification:** AUTO-FIX (add inventory data into plan Task 1.1, 2.1, 3.1, 4.1 from GLM grep output).

---

## SUGGESTION-11 — Branch numbering style in computeHealth

`BRANCH 3.5` exists. Style nit, not blocking.

**Raised by:** ollama-deepseek.
**Status:** NEW.
**Classification:** DISMISSED (intentionally preserved git-blame mapping).

---

## QUESTION-4 — Why not Option D (document as acceptable)?

Production impact ≈ 0; "4 of 6 reviewers" is sociological. Rationale stronger?

**Raised by:** ollama-minimax.
**Status:** NEW.
**Options:**
1. **Recommended.** Add "Why refactor" paragraph in spec §1: (a) WatchRecordsTask has 3-counter cross-snapshot race, (b) removing JMM-comment tech-debt, (c) unified discipline, (d) cheap insurance.
2. Drop ServerState from scope — answers ServerState-specific concern.
3. Accept as rhetorical.

**Classification:** AUTO-FIX (rationale paragraph, option 1).

---

## QUESTION-5 — `AtomicReferenceFieldUpdater` considered?

Saves ~100 bytes/update, no wrapper alloc. Why rejected?

**Raised by:** ollama-kimi.
**Status:** NEW.
**Classification:** AUTO-FIX (rejection paragraph in spec §10).

---

## QUESTION-6 — `ReadWriteLock`/`StampedLock` for ServerState?

Many readers, one writer.

**Raised by:** ollama-kimi.
**Status:** NEW.
**Classification:** AUTO-FIX (rejection paragraph in spec §10).

---

## QUESTION-7 — Verifying reader/writer atomicity post-refactor?

No test demonstrates atomic guarantee under contention.

**Raised by:** ollama-minimax.
**Status:** NEW.
**Options:**
1. Add multi-threaded stress test — empirical.
2. **Recommended.** Document in spec §5.3 "guaranteed by AtomicReference per JLS §17.4" — formal justification, no test.

**Classification:** AUTO-FIX (option 2 doc).

---

## QUESTION-8 — `onLoopFailure` and `currentBackoff` — spec §2 unclear

Spec says "onLoopFailure (если применимо)" but it doesn't touch currentBackoff.

**Raised by:** ollama-minimax.
**Status:** NEW.
**Classification:** AUTO-FIX (replace ambiguous text with explicit list of writers).

---

## QUESTION-9 — `.alive` call-site grep verification

`DetectServerLoadBalancer.kt:86,121` reads `server.alive`. Plan should include grep step.

**Raised by:** ollama-deepseek.
**Status:** NEW.
**Classification:** AUTO-FIX (add grep step to plan Task 1.1).

---

## QUESTION-10 — Why `updateAndGet` for single-field `startupAt`?

Document intent in code comment.

**Raised by:** ollama-deepseek.
**Status:** NEW.
**Classification:** AUTO-FIX (add comment near State field declaration).

---

# GLM ADDENDUM (deltas from CCS GLM late structured review)

The first parsing pass did NOT include GLM. GLM also raised:

- **GLM-Critical 2 = CONCERN-3 above** (ServerState API change) — already covered, marked DISPUTED.
- **GLM-Concern 6**: `onAttemptEnded` — 4 separate `updateAndGet` calls. Verification: tests use `sup.field = ...` + immediate `computeHealth()`, never observe intermediate state. No problem in practice — dismiss as covered by tests.
- **GLM-Concern 7 (NEW)**: Plan adds a duplicate `Entry plain-class` comment at lines 219-222; existing comment at lines 33-36. → AUTO-FIX: update existing comment instead of duplicating.
- **GLM-Suggestion 10 (NEW)**: Refactor `baseBuilder(s: SupervisorState)` as extension function `private fun SupervisorState.toHealthBuilder()`. → DISMISSED (style preference, no clear benefit; preserves current shape of plan).
- **GLM-Suggestion 11 (NEW)**: Commit messages reference spec §X but spec/plan get `git rm`-ed before PR. → AUTO-FIX: replace `Spec §X` references in commit-message bodies with `(see design doc in branch history at <commit-sha>)` OR `(per the refactor design)` to remove file-path coupling.
- **GLM-Suggestion 12 (NEW)**: `ServerState.lastCheckTimestamp` not read by any production reader. → DISMISSED with note: it IS read by `DetectServerLoadBalancer.kt:121` log line; possibly missed in grep. Verify before acting.
- **GLM-Q5 (NEW)**: Separate read-only `val state: SupervisorState get() = state.get()` for tests + separate test-only writer. → DISMISSED (current `stateForTesting` already does this via internal var; GLM may have misread the pattern).
- **GLM-Q2 (NEW)**: Extract `onPollCompleted` transform branching into named private function (mirroring `maybeResetBackoffTransform`). → SUGGESTION-12: AUTO-FIX, extract into pure helper for readability.

---

# Summary

- **DISPUTED (need user decision or careful analysis):** CRITICAL-1, CRITICAL-2, CRITICAL-3, CRITICAL-4, CONCERN-3, CONCERN-5 (6 items).
- **AUTO-FIX (apply directly):** CONCERN-1, CONCERN-2, CONCERN-4, CONCERN-6, SUGGESTION-1, SUGGESTION-2, SUGGESTION-3, SUGGESTION-4, SUGGESTION-5, SUGGESTION-6, SUGGESTION-7 (conditional), SUGGESTION-8, SUGGESTION-9, SUGGESTION-10, QUESTION-4, QUESTION-5, QUESTION-6, QUESTION-7, QUESTION-8, QUESTION-9, QUESTION-10, GLM-Concern-7, GLM-Suggestion-11, GLM-Q2 (24 items).
- **DISMISSED:** SUGGESTION-11 (style nit), GLM-Concern-6 (covered by tests), GLM-Suggestion-10 (style), GLM-Suggestion-12 (likely misread by GLM), GLM-Q5 (misread) (5 items).
- **REPEATED:** none (iter 1).

**Total NEW issues:** 35 (after GLM addendum).

# Resume instructions for next session

This is a checkpoint after parsing. Steps still TO DO:

1. (Optional) Re-run review-discussion agent with the updated merged file that now includes GLM's structured review (or trust this manual addendum).
2. Apply all AUTO-FIX items to design (`docs/superpowers/specs/2026-05-27-volatile-snapshot-design.md`) and plan (`docs/superpowers/plans/2026-05-27-atomic-state-snapshot.md`).
3. Commit auto-fixes: `docs: review iter 1 — auto-fixes (volatile-snapshot)`.
4. Process DISPUTED items one-at-a-time per skill rules (structured analysis → auto-apply if only one option viable, else AskUserQuestion).
5. Generate iter-1 file at `docs/superpowers/specs/2026-05-27-volatile-snapshot-review-iter-1.md`.
6. Commit decisions + log: `docs: review iter 1 — decisions + log (volatile-snapshot)`.
7. Ask user via AskUserQuestion: "Новая итерация (fresh session)" / "Остановиться и начать работу (fresh session)".
