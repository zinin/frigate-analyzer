# Volatile Snapshot Refactor — Review Iteration 1 (finalized)

**Branch:** `refactor/atomic-state-snapshot`
**Date:** 2026-05-27 (raised) → 2026-05-28 (finalized)
**Scope:** 5-agent external review of design doc `docs/superpowers/specs/2026-05-27-volatile-snapshot-design.md` + implementation plan `docs/superpowers/plans/2026-05-27-atomic-state-snapshot.md`. Reviewers: codex-gpt-5.5, ccs-glm, ollama-kimi, ollama-minimax, ollama-deepseek.

**Outputs of this iteration:**
- `docs/superpowers/specs/2026-05-27-volatile-snapshot-review-merged-iter-1.md` — raw merged outputs from 5 reviewers.
- `docs/superpowers/specs/2026-05-27-volatile-snapshot-review-parsed-iter-1.md` — review-discussion agent parse + manual GLM addendum + classification.
- This file — final status table per issue + commits log.

**Status legend:**
- **AutoFixed** — applied without user input (clear-cut docs/typo fix or single viable option after analysis).
- **DiscussedWithUser** — option choice required user decision via AskUserQuestion.
- **AutoAppliedAfterAnalysis** — applied after structured analysis indicated only one viable option (or conditional auto-fix unblocked by a prior decision).
- **Dismissed** — declined with rationale documented in this file.
- **DismissedAfterAnalysis** — declined after analysis showed it became moot due to a prior decision.

---

## Status Table

| Issue | Severity | Source(s) | Status | Decision / Rationale | Commit |
|-------|----------|-----------|--------|----------------------|--------|
| CRITICAL-1 (`maybeResetBackoffTransform` impure) | Critical | 4/4 structured reviewers | DiscussedWithUser | Option A — `BackoffResetResult(state, didReset, nextSuccessesAtReset)`; helper now fully pure, log lifted to caller via captured-on-every-retry vars. `maybeResetBackoffTransform` becomes `internal` to enable direct unit testing. | `245d6df` |
| CRITICAL-2 (`runSupervised` split read+update) | Critical | 4 reviewers | DiscussedWithUser | Option A — single `getAndUpdate` whose return value supplies pre-bump backoff for delay; both WatchRecordsTask and TelegramBotSupervisor restructured. SUGGESTION-7 becomes moot. | `b2281b5` |
| CRITICAL-3 (ServerState/Entry NOT single-writer) | Critical | codex-gpt-5.5, ollama-minimax | DiscussedWithUser | Option A — new `getAndUpdateHealth(transform): HealthSnapshot` on ServerState; all 3 sites in `ServerHealthMonitor` use prev snapshot for transition-detection log. Entry handled via existing CAS semantics (no code change, docs only). Spec §1 rewritten to acknowledge 3-writer model for ServerState + 2-writer for Entry. | `1907f4f` |
| CRITICAL-4 (lastFailure sticky vs current) | Critical | codex-gpt-5.5, ollama-kimi | DiscussedWithUser | Option A — preserve sticky semantics (this is a refactor, not feature change). Plan Task 5.3 verification rewritten: do NOT expect `consecutiveFailures == 0 → lastFailureAt == null`. Spec §7 gains "Sticky failure semantics" paragraph; future-work option (clear-on-success or separate `currentFailure*`) flagged in §10.5. | `160d1ea` |
| CONCERN-1 (atomic snapshot narrower than claimed) | Concern | codex-gpt-5.5, ollama-deepseek | AutoFixed | Spec §10.2 "Residual non-atomic reads" added — lists Job/registeredDirs/canAcceptRequest-counter pairs explicitly as acceptable. | `62557e5` |
| CONCERN-2 (Throwable JMM rationale inaccurate) | Concern | codex-gpt-5.5 | AutoFixed | Spec §7 wording corrected: «Throwable формально НЕ строго-immutable; в codebase мы не вызываем initCause/setStackTrace на пойманных exceptions». | `fe27d01` (batch 1) |
| CONCERN-3 (ServerState data class equals/hashCode) | Concern | codex-gpt-5.5, ollama-kimi | DiscussedWithUser | Option B — switch `data class ServerState` → regular `class` with explicit `equals/hashCode` by `id`. Verified safe via grep (no `.copy()`, no componentN usage). Spec §4.4 explains 3 reasons. | `5f0f302` |
| CONCERN-4 (convenience-getter policy underspecified) | Concern | codex-gpt-5.5, ollama-kimi, ollama-minimax | AutoFixed | Spec §5.1 unified rule added: convenience getter allowed ONLY for single-field reads; multi-field consistency via `server.snapshot()` / `entry.snapshot()` / `stateForTesting`. Production audit included. | `fe27d01` (batch 1) |
| CONCERN-5 (`stateForTesting` no runtime protection) | Concern | ollama-kimi | DiscussedWithUser | Option D — enhanced KDoc with strong-worded "DO NOT USE FROM PRODUCTION CODE" warning on the setter in both supervisor classes; spec §3.1 documents the protection layers (internal + naming + KDoc + review). Annotation infrastructure not added (no Guava/JetBrains deps; disproportionate cost). | `8e4ae42` |
| CONCERN-6 (`Entry.snapshot()` not exposed; readers undercounted) | Concern | codex-gpt-5.5, ccs-glm | AutoFixed | Spec §4.3 + plan Task 2.2: `internal fun snapshot(): EntryState = stateRef.get()` added; production reader inventory updated to include ExportExecutor and QuickExportHandler. | `fe27d01` (batch 1) |
| SUGGESTION-1 (domain-specific update methods) | Suggestion | codex-gpt-5.5 | AutoFixed | Spec §10.5 "Future work" — note about possible evolution from generic `updateState` to domain-specific methods. Not applied now (refactor already invasive). | `62557e5` |
| SUGGESTION-2 (deterministic transition tests) | Suggestion | codex-gpt-5.5, ollama-minimax, ollama-deepseek | AutoFixed | Plan Tasks 3.8 + 4.8 extended: per-transition snapshot-equality tests (`assertEquals(expected, sup.stateForTesting)`) listed for 4 onAttemptEnded cases + 5 onPollCompleted cases + 3 registration/loop transitions. | `62557e5` |
| SUGGESTION-3 (Spec §4.2 typo) | Suggestion | codex-gpt-5.5 | AutoFixed | "декремент" → "инкремент". | `fe27d01` (batch 1) |
| SUGGESTION-4 (Entry doc comment about var mutation) | Suggestion | ollama-kimi | AutoFixed | Plan Task 2.2 instructed to **update existing comment** at lines 33-36 (not duplicate); new wording removes "Entry holds vars whose values mutate" since AtomicReference now covers it. | `62557e5` |
| SUGGESTION-5 (unit test for pure `maybeResetBackoffTransform`) | Suggestion | ollama-deepseek | AutoAppliedAfterAnalysis | Conditional on CRITICAL-1 outcome — unblocked by option A which makes the helper truly pure (returns `BackoffResetResult`, no side-effects). Plan Step 4.5b added with 3 parametrized tests (no-op / increment / reset). | `245d6df` |
| SUGGESTION-6 (`successesSinceLastFailure` visibility change) | Suggestion | ollama-deepseek | AutoFixed | Spec §4.2 visibility note: field moves from `private @Volatile var` to part of `internal data class` → effective widening from private to internal. Intentional, no production reader outside the task. | `62557e5` |
| SUGGESTION-7 (single-writer atomicity comment in runSupervised) | Suggestion | ollama-minimax | DismissedAfterAnalysis | Moot after CRITICAL-2 option A — the new `getAndUpdate` shape doesn't need a "single-writer comment" because the atomic RMW closes the race regardless of writer count. | `b2281b5` (implicitly dismissed) |
| SUGGESTION-8 (`onPollCompleted` "wasted increment" quirk) | Suggestion | ollama-deepseek | AutoFixed | Plan Task 4.5 inline comment in `onPollCompleted` documents: when `eventsProcessed > 0 && eventFailures > 0`, `successesSinceLastFailure` is incremented then zeroed — pre-existing behavior preserved. | `62557e5` |
| SUGGESTION-9 (`markCancelling` KDoc wrong return type) | Suggestion | ollama-kimi | AutoFixed | Plan Task 2.4: `@return` wording corrected — returns `Entry` (the live registry object), not `EntryState`. | `62557e5` |
| SUGGESTION-10 (grep inventory steps in plan) | Suggestion | ollama-minimax, ccs-glm | AutoFixed | Plan Tasks 1.1, 2.1, 3.1, 4.1: explicit grep commands enumerating readers/writers for each refactor target; expected results listed; "STOP if production-writer found outside the target class" safety check. | `62557e5` |
| SUGGESTION-11 (`BRANCH 3.5` numbering style) | Suggestion | ollama-deepseek | Dismissed | Style nit; `BRANCH 3.5` intentionally preserves git-blame mapping to pre-refactor branch numbering. No change needed. | — |
| QUESTION-4 (Why not Option D — document as acceptable) | Question | ollama-minimax | AutoFixed | Spec §1 expanded with 4-pillar "Why refactor" rationale: (a) real race in WatchRecordsTask counters, (b) JMM-comment tech-debt, (c) unified discipline, (d) cheap insurance. | `fe27d01` (batch 1) |
| QUESTION-5 (`AtomicReferenceFieldUpdater` rejected?) | Question | ollama-kimi | AutoFixed | Spec §10.4 "Alternatives considered and rejected" — ARFU rejection rationale: allocation savings negligible at our rates; reflection-based setup; loss of `data class.copy()` ergonomics. | `62557e5` |
| QUESTION-6 (`ReadWriteLock`/`StampedLock` rejected?) | Question | ollama-kimi | AutoFixed | Spec §10.4 — RWLock/StampedLock rejection rationale: AtomicReference lock-free, no CAS contention at our rates, explicit locks introduce interleaving risk. | `62557e5` |
| QUESTION-7 (Verifying reader/writer atomicity post-refactor) | Question | ollama-minimax | AutoFixed | Spec §5.3 formal justification paragraph added: atomicity guaranteed by `AtomicReference.get()`/`updateAndGet` per JLS §17.4 happens-before chain. No stress test added — formal guarantee suffices. | `62557e5` |
| QUESTION-8 (`onLoopFailure` and `currentBackoff` — spec §2 unclear) | Question | ollama-minimax | AutoFixed | Spec §10.3 (formerly §10 bullet) replaced ambiguous "(если применимо)" with explicit list: currentBackoff is written ONLY in `runSupervised` tail bump + `maybeResetBackoffTransform`; `onLoopFailure` does NOT touch it. | `fe27d01` (batch 1) |
| QUESTION-9 (`.alive` call-site grep verification) | Question | ollama-deepseek | AutoFixed | Plan Task 1.1: explicit `git grep -nP '\.alive\b'` command added; expected reader-sites enumerated (DetectServerLoadBalancer:86,121, ServerSelectionStrategy:15). | `62557e5` |
| QUESTION-10 (Why `updateAndGet` for single-field `startupAt`?) | Question | ollama-deepseek | AutoFixed | Spec §3.1 paragraph added explaining unified-discipline rationale + protection against future second writer + transform extensibility. | `62557e5` |

---

## GLM Addendum (deltas from CCS GLM late structured review)

| Issue | Status | Decision / Rationale | Commit |
|-------|--------|----------------------|--------|
| GLM-Critical 2 (== CONCERN-3) | DiscussedWithUser | Same as CONCERN-3 (regular class + equals by id). | `5f0f302` |
| GLM-Concern 6 (`onAttemptEnded` 4 separate updateAndGet) | Dismissed | Verified: tests use `sup.field = ...` + immediate `computeHealth()`, never observe intermediate state. The 4-step composition is structurally correct (each step writes a coherent subset of fields). No problem in practice. | — |
| GLM-Concern 7 (duplicate Entry comment) | AutoFixed | Plan Task 2.2 updated: the rewritten Entry comment **updates** the existing one at lines 33-36 (not duplicates). New wording references AtomicReference rationale. | `62557e5` |
| GLM-Suggestion 10 (extension function refactor of `baseBuilder`) | Dismissed | Style preference (`private fun SupervisorState.toHealthBuilder()` vs `private fun baseBuilder(s: SupervisorState)`); no clear benefit; would diverge from existing project style. | — |
| GLM-Suggestion 11 (commit messages reference Spec §X) | AutoFixed | Plan Steps 1.6/2.6/3.11/4.11: commit-message bodies now say "Per the atomic snapshot refactor design (see branch history)" instead of "Spec §X" (spec/plan get `git rm`-ed before PR, so file-paths would dangle). | `62557e5` |
| GLM-Suggestion 12 (`lastCheckTimestamp` not read by any reader) | Dismissed with note | Verified incorrect — `lastCheckTimestamp` IS read in `DetectServerLoadBalancer.kt:121` log line. GLM missed it in grep; no action needed. | — |
| GLM-Q5 (separate read-only `val state: SupervisorState` for tests + separate test-only writer) | Dismissed | The current `stateForTesting` (internal getter + setter) already provides exactly this; GLM likely misread the pattern. | — |
| GLM-Q2 (extract `onPollCompleted` transform branching into named helper) | AutoFixed | Plan Task 4.5: `applyEventsProcessedTransform(s, now): BackoffResetResult` extracted; mirrors `maybeResetBackoffTransform` pattern. | `62557e5`, refined to `BackoffResetResult` in `245d6df` |

---

## Commits log (review iter 1)

1. `fe27d01` — `docs: review iter 1 — partial auto-fixes batch 1`
   Items: CONCERN-2, CONCERN-4, CONCERN-6, SUGGESTION-3, QUESTION-4, QUESTION-8.
2. `62557e5` — `docs: review iter 1 — auto-fixes batch 2 (volatile-snapshot)`
   Items: CONCERN-1, SUGGESTION-1, SUGGESTION-2, SUGGESTION-4, SUGGESTION-6, SUGGESTION-8, SUGGESTION-9, SUGGESTION-10, QUESTION-5, QUESTION-6, QUESTION-7, QUESTION-9, QUESTION-10, GLM-Concern-7, GLM-Suggestion-11, GLM-Q2.
3. `245d6df` — `docs: review iter 1 — CRITICAL-1 + conditional SUGGESTION-5 (volatile-snapshot)`
   Items: CRITICAL-1, SUGGESTION-5.
4. `b2281b5` — `docs: review iter 1 — CRITICAL-2 (single getAndUpdate in runSupervised)`
   Items: CRITICAL-2, SUGGESTION-7 (dismissed-after-analysis).
5. `1907f4f` — `docs: review iter 1 — CRITICAL-3 (getAndUpdateHealth + multi-writer docs)`
   Items: CRITICAL-3.
6. `160d1ea` — `docs: review iter 1 — CRITICAL-4 (sticky lastFailure semantics)`
   Items: CRITICAL-4.
7. `5f0f302` — `docs: review iter 1 — CONCERN-3 (ServerState: data class -> regular class)`
   Items: CONCERN-3.
8. `8e4ae42` — `docs: review iter 1 — CONCERN-5 (stateForTesting KDoc reinforcement)`
   Items: CONCERN-5.

Plus this file's commit (`docs: review iter 1 — decisions + log (volatile-snapshot)`).

---

## Summary

- **Total NEW issues raised in iter 1:** 35 (28 from primary parse + 7 unique from GLM addendum).
- **AutoFixed:** 22.
- **DiscussedWithUser:** 6 (all CRITICAL-1..4, CONCERN-3, CONCERN-5).
- **AutoAppliedAfterAnalysis:** 1 (SUGGESTION-5 — conditional, applied after CRITICAL-1 decision).
- **Dismissed:** 5 (SUGGESTION-11, GLM-Concern-6, GLM-Suggestion-10, GLM-Suggestion-12, GLM-Q5).
- **DismissedAfterAnalysis:** 1 (SUGGESTION-7 — moot after CRITICAL-2 option A).
- **Repeated from prior iteration:** 0 (this is iter 1).

**Production code touched in this iteration:** none — all changes are to design + plan documents. The implementation work (writing production code per the plan) is a separate session.

## Next steps

- **Option 1 (iter 2):** Send updated design + plan back through external review agents for a second iteration. Expect smaller delta; primarily verification that critical fixes resolved the concerns.
- **Option 2 (proceed to implementation):** Start executing the plan via `superpowers:subagent-driven-development` or `superpowers:executing-plans`. The 8 commits above + design updates provide a self-contained implementation guide.
