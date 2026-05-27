## TASK

Execute the implementation plan for the atomic snapshot refactor of `@Volatile` runtime state across 4 classes: `TelegramBotSupervisor`, `WatchRecordsTask`, `ActiveExportRegistry.Entry`, `ServerState`.

Use `/superpowers:subagent-driven-development` skill for execution.

## DOCUMENTS

- Design: `docs/superpowers/specs/2026-05-27-volatile-snapshot-design.md`
- Plan: `docs/superpowers/plans/2026-05-27-atomic-state-snapshot.md`
- Context (background incident report): `docs/health-volatile-snapshot-issue.md`

Read all three documents first. The plan is detailed (1200+ lines) — read it fully end-to-end, not just headers.

## CURRENT STATE

- Working branch: `refactor/atomic-state-snapshot` (already checked out).
- Spec and plan are already committed in this branch (commits `bb82ba9`, `4b676c1`).
- Master branch contains only the merged `fix/telegram-bot-supervisor` work (PR #36) up to commit `81de41a`. This refactor is the direct follow-up to that PR's external-review feedback.

## IMPORTANT: DO NOT START WORK YET

After reading the documents:
1. Confirm you have loaded all context (design, plan, context document).
2. Summarize your understanding briefly — confirm scope (4 classes), approach (`AtomicReference<XState>` snapshot, NOT Mutex, NOT atomicfu), task ordering (simplest first: ServerState → Entry → TelegramBotSupervisor → WatchRecordsTask), and the convenience-getter hybrid policy (removed in supervisor classes, kept in Entry/ServerState).
3. **WAIT for user instruction before taking any action.**

Do NOT begin implementation until the user explicitly tells you to start.

## SESSION CONTEXT (decisions and rationale from brainstorm)

### Key decisions

- **Scope: all 4 classes** in a single PR (user explicitly chose this over the smaller "supervisor-only" scope). Single unified pattern.
- **Approach: `AtomicReference<XState>` snapshot.** Rejected: Mutex/`synchronized` (blocks Spring Actuator thread, contradicts non-blocking @Volatile philosophy), local snapshot without atomicity (does not solve the race), documenting-as-acceptable (reviewers will flag again), `kotlinx.atomicfu` (overkill for a JVM-only project).
- **Single writer pattern is the reality** for all 4 sites, but `AtomicReference` chosen over `@Volatile var state: XState` for explicit CAS-via-`updateAndGet` semantics and to guard against future multi-writer regressions.
- **`supervisorJob: Job?` stays OUTSIDE State** — it is a lifecycle handle with its own internal mutable state (`isActive`, `isCancelled`), not a value. Copying its reference into `data class.copy()` is semantically odd. Single writer in `start()`, no race.
- **`startupAt` IS in State** — even though single-writer in `start()`, including it keeps the "all value-fields in snapshot" invariant uniform.
- **`successesSinceLastFailure` IS in State** (WatchRecordsTask) — even though private writer-only and not in computeHealth, including it makes the "all mutable runtime state in State" rule uniform.
- **Convenience getter hybrid:**
  - TelegramBotSupervisor, WatchRecordsTask: **NO** convenience getters. Direct fields disappear with the State refactor. Tests use `stateForTesting`. This forces consistent snapshot reads in tests.
  - ActiveExportRegistry.Entry: **KEEP** convenience getters `cancellable` / `state`. Production call-sites already use them (`registry.get(...)!!.cancellable`, `entry.state == CANCELLING`).
  - ServerState: **KEEP** convenience getters `alive` / `lastCheckTimestamp`. Used by `canAcceptRequest`, ranker, log lines.
- **No concurrency smoke tests** — StandardTestDispatcher is single-threaded; race-tests would not reliably reproduce the issue. Existing test suite plus structural guarantee from AtomicReference is sufficient.

### Rejected alternatives

- Variant B (Mutex/synchronized writer + reader): blocks Spring Actuator thread, contradicts the non-blocking `@Volatile` philosophy.
- Variant C (local snapshot reads without atomicity): doesn't solve the race; just masks it.
- Variant D (document as acceptable): reviewers (4 of 6 on the previous PR) will flag again on the next review.
- `kotlinx.atomicfu`: cross-platform abstraction, adds compiler-plugin dependency, overkill for JVM-only.
- Scope "only TelegramBotSupervisor": user rejected — WatchRecordsTask has even more `@Volatile` fields (12 vs 9) and harder counter arithmetic; would leave the bigger problem behind.

### Edge cases and warnings

- **`Throwable` safe-publication.** `Throwable` fields (`message`, `cause`, `stackTrace`) are final-after-construction; copying by reference into `XState.copy()` is safe.
- **`Entry` stays a plain `class`, not `data class`.** Reason already documented in current code (a `data class` synthesizing `equals/hashCode` over an AtomicReference is a latent footgun). Plan preserves this.
- **`synchronized(entry)` blocks in `markCancelling` / `release` MUST be preserved.** They are NOT for state mutation (the AtomicReference handles that atomically) — they are for the **lock-ordering invariant with `release`** documented in `.claude/rules/telegram-export.md` §lock-ordering. Do not remove them as part of this refactor.
- **The long JMM comment in `ActiveExportRegistry.attachCancellable` (lines 114-128) MUST be removed** as part of Task 2. AtomicReference's happens-before makes the workaround unnecessary.
- **`registeredDirs: ConcurrentHashMap` in `WatchRecordsTask` is NOT in State.** It is already thread-safe; reader only uses `.size`. Same for `watchService: WatchService?` (single-thread access by supervisor).
- **`AtomicInteger` counters in `ServerState` are NOT in `HealthSnapshot`.** They are already atomic; only `alive` and `lastCheckTimestamp` go into the snapshot.
- **Production impact of the current bug is ≈ 0.** This is a tech-debt cleanup, not an active bug. Don't over-engineer; don't add features beyond what the plan specifies.
- **Plan line-number references may drift.** Some "lines X-Y" references in the plan correspond to the current state of files. If `ktlintFormat` shifts lines, navigate by symbol name and surrounding context, not by line number.
- **In `WatchRecordsTask.runSupervised`**, the existing initial line `currentBackoff = INITIAL_BACKOFF` becomes `state.updateAndGet { it.copy(currentBackoff = INITIAL_BACKOFF) }` even though `WatchTaskState`'s default constructor already sets it — this preserves the original "reset on each loop entry" semantics (which matters if `runSupervised` is somehow re-entered after backoff progression, though structurally it shouldn't be).
- **Test fixture style.** TelegramBotSupervisorTest has ~30 direct field writes (`sup.startupAt = ...`, etc.). These collapse to ~10 `sup.stateForTesting = SupervisorState(...)` calls. Don't try to merge or split fixtures beyond that — keep tests recognizable.
- **WatchRecordsTaskTest helper.** The current `buildTask(...)` helper takes ~9 explicit parameters and writes them one-by-one. Collapse the signature to `buildTask(state: WatchTaskState = WatchTaskState(...defaults...), registerDummyDir: Boolean = true)` and update call sites to construct the `WatchTaskState` inline.

### Project rules to remember

- **Project CLAUDE.md:** ALWAYS `git add <file>` after creating/modifying. After implementation: run `superpowers:code-reviewer` agent FIRST, fix critical comments, then `build-runner` for build. On ktlint errors: `./gradlew ktlintFormat`, then retry. Do NOT run `./gradlew build` directly.
- **Global CLAUDE.md:** Plan and spec must be `git rm`-ed before opening the PR (`docs/superpowers/specs/...`, `docs/superpowers/plans/...`). The context document `docs/health-volatile-snapshot-issue.md` stays (it is not in `docs/superpowers/`).
- **Commit style.** Conventional commits (`refactor(scope): subject`, `docs: ...`). HEREDOC for multi-line bodies.
- **Each task in the plan = one commit** (5 commits total: 4 refactor + 1 review-cleanup if needed). Do NOT batch all tasks into one commit.
- **Branch:** `refactor/atomic-state-snapshot` (already checked out).

## PLAN QUALITY WARNING

The plan was written for a large task and may contain:
- Errors or inaccuracies in implementation details (e.g., wrong line numbers if files drift, wrong method signatures if some method's shape was inferred rather than read).
- Oversights about edge cases or dependencies (e.g., a test file not mentioned in the plan that also reads/writes target fields).
- Assumptions that don't match the actual codebase (e.g., default values, helper signatures).
- Missing steps or incomplete instructions.

**If you notice any issues during implementation:**
1. STOP before proceeding with the problematic step.
2. Clearly describe the problem you found.
3. Explain why the plan doesn't work or seems incorrect.
4. Ask the user how to proceed.

Do NOT silently work around plan issues or make significant deviations without user approval.

### Specific spots prone to drift

- `DetectServiceTest.kt` has ~12 `server.alive = true` writes; grep before editing to confirm exact occurrence count and locations.
- `TelegramBotSupervisorHealthIndicatorTest.kt` and `WatchRecordsTaskHealthIndicatorTest.kt` — the plan's optional steps (3.9, 4.9) say "if applicable"; if you find them empty / no direct field access, skip the step but record this fact in the corresponding task's final review.
- The "lines NN-MM" markers in the plan are written against the current file state; if a prior step in the same task has already edited the file, recompute by symbol name.
