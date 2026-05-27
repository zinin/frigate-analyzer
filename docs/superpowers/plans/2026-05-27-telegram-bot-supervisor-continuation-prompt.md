## TASK

Execute the implementation plan for **Telegram Bot Supervisor — Resilient Long-Polling** (issue #34).

This plan has been through **one round of external review** (5 agents — codex/gpt-5.5, ccs/glm,
ollama-kimi/minimax/deepseek). Design + plan were updated with 12 AUTO-fixes and 8 DISPUTED
resolutions before this session.

Use `/superpowers:subagent-driven-development` skill for execution.

## CRITICAL: DO NOT START WORKING

**STOP. READ THIS CAREFULLY.**

After loading all context below, you MUST:
1. Read the documents and understand the context
2. Report what you understood (brief summary)
3. **WAIT for explicit user instructions** before taking ANY action

**DO NOT:**
- Start implementing tasks
- Make any code changes
- Run any commands (except reading documents)
- Assume what task to work on next

**The user will tell you exactly what to do.** Until then, only read and summarize.

## DOCUMENTS

- Design: `docs/superpowers/specs/2026-05-27-telegram-bot-supervisor-design.md`
- Plan: `docs/superpowers/plans/2026-05-27-telegram-bot-supervisor.md`
- Review log: `docs/superpowers/specs/2026-05-27-telegram-bot-supervisor-review-iter-1.md`
- Merged review (raw output of 5 agents): `docs/superpowers/specs/2026-05-27-telegram-bot-supervisor-review-merged-iter-1.md`

Read **design + plan + review log** at minimum. The merged review is reference material — open
only if you need to see the raw reviewer output for a specific finding.

## PROGRESS

**Completed:**
- [x] Design document drafted (commit `1ffa276`)
- [x] Plan document drafted (commit `3485ad3`)
- [x] External review iteration 1 (5 agents) — 25 findings classified and resolved (commits `9c6b3d3`, `ac12671`, `ed4a731`)
  - 12 AUTO-fixes applied (A1-A12) — most embedded in design/plan code blocks as `[A1]`/`[D2]` etc. markers
  - 8 DISPUTED resolved (D1-D8) — see review log for variant analysis and chosen options
  - 5 DISMISSED with rationale

**Remaining (plan execution):**
- [ ] Task 1: Refactor `FrigateAnalyzerBot` to expose methods (no behaviour change)
- [ ] Task 2: `TelegramBotSupervisor` scaffold + first test (branch-1 health)
- [ ] **Task 2.5 (NEW from review D2):** `TelegramLongPollingRunner` adapter (interface + impl)
- [ ] Task 3: `runSupervised` retry-loop with exponential backoff
- [ ] Task 4: `onAttemptEnded` — stable threshold + D3 fast-fail handling
- [ ] Task 5: Cancellation contract test
- [ ] Task 6: `computeHealth` — 7-branch state machine **(NEW branch ordering per D1)**
- [ ] Task 7: Lifecycle (`@PostConstruct` is a **NO-OP STUB** through this task — per D4)
- [ ] Task 8: `TelegramBotHealthIndicator`
- [ ] **Task 9 (RESTRUCTURED per D4):** Atomic cutover — populate supervisor's `start()` AND remove `FrigateAnalyzerBot.start()` in **ONE commit**
- [ ] Task 10: Documentation in `.claude/rules/telegram.md`
- [ ] Final 1-3: Build green, optional smoke, drop docs from `docs/superpowers/`, push PR

## SESSION CONTEXT

### What changed during review (high-level)

1. **Health state machine fixed [D1]:** branch "live stable polling → UP" moved to position 2
   (right after "supervisor not active → DOWN"). Adds invariant
   `lastPollingStartAt > lastFailureAt` to prevent stale-stamp UP. Companion fix [A5]:
   `lastPollingStartAt = null` at the start of each iteration.

2. **`TelegramLongPollingRunner` adapter introduced [D2]:** the supervisor no longer calls
   `bot.buildBehaviourWithLongPolling` directly. Instead it calls `runner.run { ... }` which
   returns `Throwable?` (null on clean exit). This was driven by reviewers flagging:
   - `Job.join()` doesn't propagate the cause — supervisor would see failures as successes
   - `buildBehaviourWithLongPolling` takes a separate `CoroutineScope` parameter — without
     passing our scope, the polling job is not a child of the supervisor (leak risk, conflicts
     on shutdown)
   - `mockkStatic` on a top-level Kotlin function is fragile (wrong class name in the original
     plan; wrong parameter count: 6 vs actual 3; `Job.completeExceptionally` doesn't exist on
     `Job`)
   The adapter solves all three. **A2 (mockkStatic teardown) and A4 (mockkStatic class/signature)
   are obsoleted by D2** — tests use a hand-rolled fake `TelegramLongPollingRunner` object.

3. **Silent failure detection [D3]:** `onAttemptEnded(success=true)` now branches on
   `duration >= STABLE_THRESHOLD`. Fast clean returns are treated as failures
   (`consecutiveFailures++`, `SilentPollingFailure` marker, no `lastStableAt` reset). This
   closes the "revoked token → infinite reconnect, never DOWN" hole from spec §10 risk.

4. **Atomic cutover [D4]:** `TelegramBotSupervisor.start()` is a **no-op stub** through Task 7.
   Tests launch `runSupervised()` directly via `supervisor.scope.launch { ... }` (scope visibility
   raised to `internal`). The real `start()` body is added in Task 9 Step 9.0a **in the same
   commit as removing `FrigateAnalyzerBot.start()`** — no window of two simultaneous pollers.

5. **`onOwnerActivated` keeps `event.chatId` [D5]:** the original plan's switch to
   `registerOwnerCommandsIfPossible()` (DB lookup) was rolled back. `event.chatId` is
   authoritative (event published after activating transaction). The new
   `registerOwnerCommandsIfPossible()` method is **only** called by the supervisor's reconnect
   loop, where no `chatId` is available.

6. **`CancellationException` rethrow added [A3]:** `registerDefaultCommands`,
   `registerOwnerCommands`, and the new `registerOwnerCommandsIfPossible` all need
   `catch (e: CancellationException) { throw e }` before `catch (e: Exception)`. Otherwise the
   `catch (Exception)` swallows cancellation, leading to unresponsive shutdown.

7. **Dispatcher changed [A6]:** `Dispatchers.Default` → `Dispatchers.IO.limitedParallelism(1)`
   for parity with `WatchRecordsTask` (polling is I/O-bound, supervisor is single-threaded).
   Requires `@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)`.

8. **Other small adjustments:**
   - `clock.instant()` → `Instant.now(clock)` everywhere [A9]
   - Log "polling started" on each successful reconnect [A11]
   - Log clean return with attempt duration [A10]
   - `shutdown()` uses `runCatching { join() }` + `isCompleted` check to avoid false
     "did not exit within Ns" log when cancel propagates [A7]
   - Removed `"registration failures don't trigger backoff"` from spec §8.1 — un-writeable [D7]
   - `@Volatile` snapshot documented as best-effort, parity with `WatchRecordsTask` [D8]

### Pre-implementation verification needed (CRITICAL)

**Verify the actual `buildBehaviourWithLongPolling` signature in ktgbotapi 33.1.0 BEFORE
starting Task 2.5.** This was raised by 4 of 5 reviewers. Concretely:
- What's the top-level Kotlin class name? Plan currently says
  `dev.inmo.tgbotapi.extensions.behaviour_builder.BuildBehaviourWithLongPollingKt` but reviewers
  suggest it may actually be `BehaviourBuildersKt`.
- How many parameters does the function take? Original plan assumed 6, MiniMax/Codex say 3
  (`scope`, `onFirstMetaInformation`, `block`).
- Does the function return `Job` or `Deferred<...>`? `Job.completeExceptionally` was used in
  the original plan but doesn't exist on `Job`.

The adapter [D2] doesn't make this verification optional — `KtgBotApiLongPollingRunner.run()`
calls `buildBehaviourWithLongPolling(this) { onUpdate() }` and needs the signature right.

### Decisions and rationale to remember

- **Three-component split** consistent with `WatchRecordsTask` + `WatchRecordsLoop` +
  `WatchRecordsTaskHealthIndicator`
- **Hardcoded thresholds** (no `application.yaml` config) — project policy
- **`onOwnerActivated` keeps own `eventScope`** — event-driven, not polling lifecycle
- **`@Profile("!test")` on `TelegramBotHealthIndicator`** — defensive (avoids breaking
  `actuatorHealth()` aggregation if anyone sets `telegram.enabled=true` in test profile)
- **No Micrometer metrics** — project doesn't use Micrometer
- **No auto-restart on DOWN** — operator-only (matches `WatchRecordsTask` policy)
- **No jitter in backoff** — single-instance bot
- **Adapter pattern intentional** — accepts +1 class for big test-quality + correctness gains

### Project warnings

- **NEVER run build/test commands directly** — always delegate to the `build-runner` agent.
  Project rule (see `CLAUDE.md` §"Planning Mode").
- **On ktlint failures**: run `./gradlew ktlintFormat`, then retry the build.
- **Before opening the PR**: `git rm` the spec + plan + review docs from
  `docs/superpowers/specs/` and `docs/superpowers/plans/` and commit. They must not appear in
  the PR diff. Task 10 Final 3 covers this. Note: the review files
  (`*-review-iter-1.md`, `*-review-merged-iter-1.md`) also need to be `git rm`'d.
- **`git add <file>` after every create/modify** — project Git workflow rule.

## PLAN QUALITY WARNING

This plan has been through external review, but **may still contain**:
- Errors or inaccuracies in implementation details that all 5 reviewers missed
- Inconsistencies between the new adapter [D2] and code blocks elsewhere in the plan
- Edge cases not covered by the review's lens
- Code-block fragments referring to the *old* mockkStatic approach that may still need cleanup

**If you notice any issues during implementation:**
1. STOP before proceeding with the problematic step
2. Clearly describe the problem you found
3. Explain why the plan doesn't work or seems incorrect
4. Ask the user how to proceed

Do NOT silently work around plan issues or make significant deviations without user approval.

## INSTRUCTIONS

1. Read the design document
2. Read the plan document (note the [A1]-[A12], [D1]-[D8] markers throughout)
3. Read the review iter-1 log for context on disputed decisions
4. Provide a brief summary of what you understood — especially:
   - The three-component split and how the new adapter [D2] fits in
   - The new branch ordering for `computeHealth` [D1]
   - The atomic-cutover sequencing [D4] between Task 7 and Task 9
   - The pre-implementation verification needed for ktgbotapi signature
5. **STOP and WAIT** — do NOT proceed with any implementation
6. Ask: "What would you like me to work on?"
