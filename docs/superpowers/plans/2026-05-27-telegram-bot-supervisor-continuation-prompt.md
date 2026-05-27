## TASK

Execute the implementation plan for **Telegram Bot Supervisor — Resilient Long-Polling** (issue #34).

The design + plan have been through **two rounds** of external review (each: codex/gpt-5.5,
ccs/glm, ollama-kimi/minimax/deepseek). All 25 findings from iter-1 and ~30 findings from iter-2
have been classified and resolved. Design and plan are now self-consistent and free of all
flagged plan-quality gaps.

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
- Iter-1 review log: `docs/superpowers/specs/2026-05-27-telegram-bot-supervisor-review-iter-1.md`
- Iter-2 review log: `docs/superpowers/specs/2026-05-27-telegram-bot-supervisor-review-iter-2.md`

Read **design + plan + iter-2 log** at minimum (iter-1 log is referenced via markers like
`[A1]`/`[D1]` in the design/plan code blocks). The merged review files are reference material —
open only if you need to see the raw reviewer output for a specific finding.

## PROGRESS

**Completed (review phase):**
- [x] Design document drafted (commit `1ffa276`)
- [x] Plan document drafted (commit `3485ad3`)
- [x] External review iteration 1 — 25 findings classified and resolved (commits `9c6b3d3`, `ac12671`, `ed4a731`)
  - 12 AUTO-fixes (A1-A12)
  - 8 DISPUTED resolved (D1-D8)
  - 5 DISMISSED
- [x] External review iteration 2 — ~30 findings classified and resolved (commits `e7c015d`, `c8529a8`, `7c804c5`, `4db2411`)
  - 21 AUTO-fixes applied (AUTO-1 through AUTO-21 — see iter-2 log)
  - 5 DISMISSED with rationale
  - 2 REPEAT (auto-answered from iter-1)
  - 0 DISPUTED (all candidates resolved to single-variant after analysis)

**Remaining (plan execution — none started):**
- [ ] Task 1: Refactor `FrigateAnalyzerBot` to expose methods (no behaviour change)
- [ ] Task 2: `TelegramBotSupervisor` scaffold + first test (branch-1 health)
- [ ] Task 2.5: `TelegramLongPollingRunner` adapter (interface + impl) — includes Step 2.5a (update constructor + helpers with `runner` per AUTO-2)
- [ ] Task 3: `runSupervised` retry-loop with exponential backoff (Step 3.0 now defines `tickingClock` helpers per AUTO-7)
- [ ] Task 4: `onAttemptEnded` — stable threshold + D3 fast-fail handling (Step 4.4 is now a no-op pointer per AUTO-7)
- [ ] Task 5: Cancellation contract test
- [ ] Task 6: `computeHealth` — 7-branch state machine (new branch ordering per D1 + AUTO-1 + AUTO-10/11 tests)
- [ ] Task 7: Lifecycle (`@PostConstruct` is a **NO-OP STUB** through this task — per D4)
- [ ] Task 8: `TelegramBotSupervisorHealthIndicator` (renamed from `TelegramBotHealthIndicator` per AUTO-21)
- [ ] Task 9: Atomic cutover — populate supervisor's `start()` AND remove `FrigateAnalyzerBot.start()` in ONE commit (per D4; git add now includes both files per AUTO-4)
- [ ] Task 10: Documentation in `.claude/rules/telegram.md` (now includes `TelegramLongPollingRunner` row per AUTO-14)
- [ ] Final 1-3: Build green, optional smoke, drop docs from `docs/superpowers/`, push PR

## SESSION CONTEXT

### High-level architecture (after iter-2)

Three production components + one test-only helper interface:

1. **`FrigateAnalyzerBot`** — Routes registrar. Public methods: `registerRoutes(ctx)`,
   `registerDefaultCommands()`, `registerOwnerCommandsIfPossible()`. Owns small `eventScope` for
   `onOwnerActivated` listener (event-driven, separate from polling lifecycle).
2. **`TelegramBotSupervisor`** — Polling lifecycle: `runSupervised` retry-loop with bounded
   exponential backoff (5s→60s), health state. Constructor takes `runner: TelegramLongPollingRunner`
   as FIRST parameter (per AUTO-2). `scope` is `internal val` (per AUTO-15) for test access.
3. **`TelegramLongPollingRunner`** — Thin adapter interface over ktgbotapi's
   `buildBehaviourWithLongPolling`. Production impl `KtgBotApiLongPollingRunner` uses
   `coroutineScope { ... }` + explicit try/catch (per AUTO-18). Returns `Throwable?` (null on
   clean exit). Adapter call uses **`scope = this` named-argument** (per AUTO-16, Context7-verified
   signature).
4. **`TelegramBotSupervisorHealthIndicator`** — `@Profile("!test")` Spring Actuator adapter.
   Renamed (per AUTO-21) so Spring exposes under key `telegramBotSupervisor` (was
   `telegramBot` if named `TelegramBotHealthIndicator`).

### ktgbotapi 33.1.0 signature (Context7-verified, in iter-2)

```kotlin
fun TelegramBot.buildBehaviourWithLongPolling(
    timeoutSeconds: Int = 30,
    autoDisableWebhooks: Boolean = true,
    mediaGroupsDebounceTimeMillis: Long = 1000L,
    scope: CoroutineScope = ...,
    ...,
    block: suspend BehaviourContext.() -> Unit
): Job
```

The named-argument call `bot.buildBehaviourWithLongPolling(scope = this) { ... }` is mandatory —
positional `this: CoroutineScope` would land on `timeoutSeconds: Int` and fail to compile.

### Critical iter-2 fixes (most likely to surprise during implementation)

1. **`computeHealth` branch ordering [D1+AUTO-1]:** branch 2 "live stable polling → UP" is
   CHECKED FIRST after liveness, BEFORE startup/backoff branches. Invariant `pollStart >
   lastFailureAt` guards against stale stamp from previously-crashed attempts.

2. **Backoff loop ordering [AUTO-19]:** `delay(currentBackoff)` runs BEFORE
   `currentBackoff = nextBackoff(currentBackoff)`. This preserves the documented 5→10→20→40→60
   progression. The previous iteration had bump-before-delay which made the first wait 10s.

3. **`lastPollingStartAt = null` after runner.run exits [AUTO-20]:** added immediately after
   `runner.run` returns, BEFORE the cause check. Otherwise a stable clean return would leave
   the stamp set during the post-poll delay, and branch 2 would report UP for a poller that
   has already stopped.

4. **`SilentPollingFailure` marker class [AUTO-5]:** Task 4 Step 4.3 now contains an explicit
   "declare `private class SilentPollingFailure(message: String) : RuntimeException(message)`"
   step at the bottom of the supervisor file. Earlier iteration referenced the class but never
   added it.

5. **`stopAndJoin()` does NOT null `supervisorJob` [AUTO-17]:** intentional, mirrors
   `shutdown()`. Tests re-using the same supervisor must reset the field manually.

6. **Test helpers defined in Task 3 Step 3.0 [AUTO-7]:** `tickingClock` +
   `newSupervisorWithTickingClock` introduced BEFORE Task 3 Step 3.1 uses them. Task 4 Step 4.4
   is now an empty pointer.

7. **Atomic cutover commit [D4+AUTO-4]:** Task 9 `git add` now lists BOTH
   `FrigateAnalyzerBot.kt` AND `TelegramBotSupervisor.kt` so the population of `start()` and
   removal of `FrigateAnalyzerBot.start()` land in a single commit.

### Decisions and rationale to remember

- **Three-component split** consistent with `WatchRecordsTask` + `WatchRecordsLoop` +
  `WatchRecordsTaskHealthIndicator`
- **Hardcoded thresholds** (no `application.yaml` config) — project policy
- **`onOwnerActivated` keeps own `eventScope` + `event.chatId`** [D5] — event-driven, not
  polling lifecycle
- **`@Profile("!test")` on indicator** — defensive (avoids breaking `actuatorHealth()`
  aggregation if anyone sets `telegram.enabled=true` in test profile)
- **No Micrometer metrics** — project doesn't use Micrometer
- **No auto-restart on DOWN** — operator-only (matches `WatchRecordsTask` policy)
- **No jitter in backoff** — single-instance bot
- **`@PostConstruct`** (not `ApplicationReadyEvent`) — `@ConditionalOnProperty(telegram.enabled=
  true)` already disables bean in test profile, so `isTestProfile()` guard would be redundant

### Project warnings

- **NEVER run build/test commands directly** — always delegate to the `build-runner` agent.
  Project rule (see `CLAUDE.md` §"Planning Mode").
- **On ktlint failures**: run `./gradlew ktlintFormat`, then retry the build.
- **Before opening the PR**: `git rm` the spec + plan + review docs from
  `docs/superpowers/specs/` and `docs/superpowers/plans/` and commit. They must not appear in
  the PR diff. Final 3 covers this. The review files (`*-review-iter-1.md`,
  `*-review-iter-2.md`, `*-review-merged-iter-1.md`, `*-review-merged-iter-2.md`,
  `*-continuation-prompt.md`) also need to be `git rm`'d.
- **`git add <file>` after every create/modify** — project Git workflow rule.

## PLAN QUALITY WARNING

The plan has been through two external review iterations, but **may still contain**:
- Subtle errors that all reviewers missed (low risk; covered by 10 reviewer-rounds total)
- Implementation details that don't match the actual ktgbotapi/Spring Boot 4.0.6 runtime
- Edge cases not covered by the review lens

**If you notice any issues during implementation:**
1. STOP before proceeding with the problematic step
2. Clearly describe the problem you found
3. Explain why the plan doesn't work or seems incorrect
4. Ask the user how to proceed

Do NOT silently work around plan issues or make significant deviations without user approval.

## INSTRUCTIONS

1. Read the design document
2. Read the plan document (note the [A1]-[A12], [D1]-[D8], [AUTO-1]-[AUTO-21] markers throughout)
3. Read the iter-2 review log for context on the most recent fixes
4. Provide a brief summary of what you understood — especially:
   - The four-component split (FrigateAnalyzerBot / TelegramBotSupervisor / TelegramLongPollingRunner / TelegramBotSupervisorHealthIndicator)
   - The new branch ordering for `computeHealth` (live polling → UP second)
   - The atomic-cutover sequencing [D4] between Task 7 and Task 9
   - The backoff ordering fix [AUTO-19] (delay before bump)
   - The Context7-verified `scope = this` named argument in the adapter
5. **STOP and WAIT** — do NOT proceed with any implementation
6. Ask: "What would you like me to work on?"

The user may also choose to run a third iteration of external review before execution — defer
to their decision.
