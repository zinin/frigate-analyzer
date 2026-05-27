## TASK

Continue review iteration 1 for the "atomic snapshot for @Volatile runtime state" refactor in Frigate Analyzer. The previous session ran 5 external review agents, parsed 24+ issues, classified them, and applied the first batch of 6 auto-fixes before hitting context budget.

## CRITICAL: DO NOT START WORKING

**STOP. READ THIS CAREFULLY.**

After loading all context below, you MUST:
1. Read the documents and understand the context
2. Report what you understood (brief summary, max 8 lines)
3. **WAIT for explicit user instructions** before taking ANY action

**DO NOT:**
- Start applying remaining auto-fixes
- Start discussing disputed items
- Make any code changes
- Run any commands (except reading documents)
- Re-run review agents (already done — outputs cached in `/home/zinin/.claude/`)

**The user will tell you exactly what to do.** Until then, only read and summarize.

## DOCUMENTS

Read in this order:

1. **Resume instructions + classified issues:** `docs/superpowers/specs/2026-05-27-volatile-snapshot-review-parsed-iter-1.md` — this is the primary document for this session. It contains 24 parsed issues + GLM addendum + classification (AUTO-FIX / DISPUTED / DISMISSED) + recommended options per item + resume instructions at the bottom.

2. **Merged 5-agent review:** `docs/superpowers/specs/2026-05-27-volatile-snapshot-review-merged-iter-1.md` — full raw outputs from codex-gpt-5.5, ollama-kimi, ollama-minimax, ollama-deepseek, ccs-glm. Skim only — issues are already parsed in document #1.

3. **Design:** `docs/superpowers/specs/2026-05-27-volatile-snapshot-design.md` — being updated by this iteration. Six fixes already applied in commit `fe27d01`.

4. **Plan:** `docs/superpowers/plans/2026-05-27-atomic-state-snapshot.md` — has not been edited yet in this iteration; several auto-fixes target plan documents.

## CURRENT STATE

- Working branch: `refactor/atomic-state-snapshot` (already checked out).
- Last commit: `fe27d01` — "docs: review iter 1 — partial auto-fixes batch 1".
- Last 3 commits: `fe27d01` (auto-fixes batch 1), `1c09a08` (review checkpoint with parsed + merged files), `081e20a` (execution prompt for the plan itself).

## PROGRESS — REVIEW ITERATION 1

**Completed in previous session:**
- [x] Launched 5 review agents in parallel (codex-gpt-5.5, ccs-glm, ollama-kimi, ollama-minimax, ollama-deepseek).
- [x] Merged all 5 outputs into `docs/superpowers/specs/2026-05-27-volatile-snapshot-review-merged-iter-1.md`.
- [x] Parsed via `review-discussion` agent and manually classified into 35 issues (24 from initial pass + 5 GLM additions + 6 cross-references).
- [x] Classified into 6 DISPUTED / 24 AUTO-FIX / 5 DISMISSED buckets.
- [x] Applied 6 of 24 AUTO-FIX items to design spec (commit `fe27d01`): typo fix, expanded §1 rationale, §10 onLoopFailure clarification, §5.1 convenience-getter unified rule, §4.3 `Entry.snapshot()`, §7 Throwable wording.
- [x] Intermediate commits for safety.

**Remaining — 18 AUTO-FIX (skill rule: apply ALL before starting disputed phase, then ONE commit):**
- CONCERN-1: Add "Known Limitations" section to spec (residual races: Job, registeredDirs, canAcceptRequest counter pair).
- SUGGESTION-1: Add future-work note about domain-specific update methods in spec §10.
- SUGGESTION-6: One-line note about `successesSinceLastFailure` visibility change (private → effectively internal) in spec §4.2.
- QUESTION-5: Add `AtomicReferenceFieldUpdater` rejection paragraph in spec §10.
- QUESTION-6: Add `ReadWriteLock`/`StampedLock` rejection paragraph in spec §10.
- QUESTION-7: Document atomic guarantee per JLS §17.4 in spec §5.3.
- QUESTION-10: Add comment near State field declaration about updateAndGet for single-field consistency.
- SUGGESTION-2: Add deterministic transition tests as new steps in plan Tasks 3-4 (per-transition snapshot equality).
- SUGGESTION-4: Update Entry doc-comment text in plan Task 2 production code block (no more "Entry holds vars" — refers to AtomicReference now).
- SUGGESTION-8: Inline comment about "wasted increment" quirk in plan Task 4.5 (onPollCompleted when eventsProcessed>0 && eventFailures>0).
- SUGGESTION-9: Fix `markCancelling` KDoc in plan Task 2.4 (returns Entry, not EntryState).
- SUGGESTION-10: Embed grep inventory steps into plan Tasks 1.1, 2.1, 3.1, 4.1 using GLM-provided data.
- QUESTION-9: Add explicit grep step to plan Task 1.1 for `.alive` call-sites.
- GLM-Concern-7: In plan Task 2.2, update the EXISTING Entry comment (lines 33-36 of ActiveExportRegistry.kt) instead of duplicating a new one.
- GLM-Suggestion-11: Replace "Spec §X" references in commit-message bodies (Steps 1.6/2.6/3.11/4.11 of plan) with "(per the refactor design)" — since spec/plan are git-rm'ed before PR.
- GLM-Q2: Extract `onPollCompleted` transform branching into a named pure helper in plan Task 4.5 (mirroring `maybeResetBackoffTransform` pattern).
- SUGGESTION-5 (conditional on CRITICAL-1 outcome): Add unit test for the extracted `maybeResetBackoffTransform` pure function in plan Task 4.
- SUGGESTION-7 (conditional on CRITICAL-2 outcome): Comment in runSupervised about single-writer atomicity — moot if CRITICAL-2 option 1 (single `getAndUpdate`) is adopted.

**After auto-fixes — 6 DISPUTED (skill rule: process ONE AT A TIME, structured analysis → auto-apply if only one option viable, else AskUserQuestion):**
1. **CRITICAL-1** — `maybeResetBackoffTransform` impure (4/4 reviewers). Options: (1) Pair<State, Boolean> return + log outside, (2) compare pre/post, (3) acknowledge impurity. Recommended: option 1.
2. **CRITICAL-2** — `runSupervised` split read+update. Options: (1) single `getAndUpdate`, (2) capture local at iteration top, (3) document acceptable. Recommended: option 1.
3. **CRITICAL-3** — ServerState/Entry NOT single-writer; check-then-act race. Options: (1) `getAndUpdateHealth` returning prev snapshot, (2) capture timestamp before update, (3) docs-only reformulation. Recommended: option 1.
4. **CRITICAL-4** — `lastFailure`/`lastFailureAt` sticky vs current. Options: (1) keep sticky + update plan verification, (2) clear on success + update tests, (3) add separate currentFailure fields. Recommended: option 1.
5. **CONCERN-3** — ServerState `data class` equals/hashCode change. Options: (1) docs-only note, (2) switch to regular class with id-only equals, (3) override equals comparing counter values. Recommended: TBD — option 1 vs option 2 has real merit, may need user input.
6. **CONCERN-5** — `stateForTesting` no runtime protection. Options: (1) `@VisibleForTesting`-style annotation, (2) test-source-set extension, (3) keep as-is. Recommended: depends on existing annotation infrastructure in project.

**After all decisions:**
- Generate iter-1 finalization file at `docs/superpowers/specs/2026-05-27-volatile-snapshot-review-iter-1.md` (status table per issue: AutoFixed / AutoAppliedAfterAnalysis / DiscussedWithUser / Dismissed / Repeated).
- Commit decisions + log: `docs: review iter 1 — decisions + log (volatile-snapshot)`.
- Ask user via AskUserQuestion: "Новая итерация (fresh session)" vs "Остановиться и начать работу (fresh session)".

## SESSION CONTEXT (implicit knowledge from previous session)

- **GLM specifics:** The CCS GLM agent first returned only a test/production-site inventory (no structured Critical/Concerns), then on a second pass returned a proper structured review. Both blocks are in the merged file. The initial parse via `review-discussion` agent was done BEFORE GLM's structured review arrived — the parsed file has a "GLM ADDENDUM" section at the bottom with deltas. When generating the iter-1 finalization file, include all 35 issues (parsed + addendum).
- **Skill rule discipline:** Per the review-design-external-iterative skill (Iron Rules), all auto-fixes must be applied and committed BEFORE starting disputed discussion. The auto-fixes already committed in `fe27d01` are an intermediate batch; the next session should apply remaining 18 auto-fixes and either commit them as "auto-fixes batch 2" or amend approach if user prefers.
- **CRITICAL-1 reach:** Of all 35 issues, the impure `maybeResetBackoffTransform` was independently flagged by 4 of 4 structured reviewers (codex-gpt-5.5, ollama-kimi, ollama-minimax, ollama-deepseek). It is the strongest signal in the review. Codex specifically called it out as needing a CAS-loop or moved logging.
- **CRITICAL-3 nuance:** The "not single-writer" finding applies differently to ServerState (real multi-callback concurrency from WebClient.subscribe + markServerDead) and Entry (attachCancellable from export coroutine + markCancelling from cancel coroutine). They might warrant separate decisions or unified `getAndUpdate` pattern.
- **CRITICAL-4 design impact:** If user picks "sticky" (recommended), the plan's verification step in Task 5 that checks `consecutiveFailures == 0 → lastFailureAt == null` must be removed/rewritten. If user picks "current" (clear on success), it changes observable health JSON behavior and requires updating multiple existing tests that currently assert sticky.
- **CONCERN-3 + GLM-Concern-7 conflict:** GLM-Concern-7 says plan adds a duplicate Entry comment; the right fix is to update the EXISTING comment at lines 33-36, not add a new block. CONCERN-3 (ServerState equals/hashCode) is a more fundamental design question — option 2 (regular class) removes the footgun entirely; could be auto-applied if user agrees with the rationale.
- **Conditional auto-fixes** (SUGGESTION-5 and SUGGESTION-7) depend on the CRITICAL-1 and CRITICAL-2 outcomes — defer until those decisions are made, then apply if applicable.
- **No production code has been touched** in this branch yet. Only design + plan + review docs.

## PLAN QUALITY WARNING

The classified issues file (`parsed-iter-1.md`) was written manually after the formal `review-discussion` agent run and may contain:
- Incorrect classification (issue marked AUTO-FIX that actually has trade-offs needing user input).
- Incorrect recommendation (option 1 not always best — verify reasoning against the merged review for context).
- Stale references to line numbers in design/plan (the partial auto-fixes batch already shifted lines).

**If you notice any issues during continuation:**
1. STOP before proceeding with the problematic step
2. Clearly describe the problem you found
3. Explain why the classification or recommendation seems wrong
4. Ask the user how to proceed

Do NOT silently re-classify or skip items without user approval.

## INSTRUCTIONS

1. Read the four documents listed above (in the order given).
2. Understand current progress and remaining work.
3. Provide a brief summary (max 8 lines) covering: how many auto-fixes remain, the 6 disputed items by name, current branch state.
4. **STOP and WAIT** — do NOT proceed with any implementation.
5. Ask: "Применять оставшиеся 18 AUTO-FIX или сначала обсудить какой-то disputed item?"
