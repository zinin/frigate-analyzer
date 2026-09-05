## TASK

Continue executing the implementation plan for the LLM notification judge.

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

- Design: `docs/superpowers/specs/2026-09-05-llm-notification-judge-design.md`
- Plan:   `docs/superpowers/plans/2026-09-05-llm-notification-judge.md`

Read both documents to understand the full picture.

## PROGRESS

Branch: `feature/llm-notification-judge`. HEAD at generation: current branch tip (trim commit `60a7d63` on top of review auto-decide `9a56cab`). Base: `master` (`ad6c3e5`).

**Completed tasks:**
- [x] Task 1: task-neutral `VisionBackend` SPI — `0ad2ec0`
- [x] Task 2: `VisionCallExecutor` and two rate limiters — `1f9ef47`
- [x] Task 3: judge agent, parser, properties — `7b3ab70`, `5eb8693`
- [x] Task 4: `notification_verdicts` table and services — `0b13d04`
- [x] Task 5: runtime settings, zone, guard, scope, snooze — `aa28675`
- [x] Task 6: `JudgeContextBuilder` — `ab7aec6`
- [x] Task 7: `NotificationJudgeService` — `9724e02`, plus later fail-open/queue/timeout commits below
- [x] Task 8: facade submits `JudgeCandidate` — `62b4e07`
- [x] Task 9: `/status` judge section — `1a485f8`
- [x] Task 10: `/verdicts` — `28f91e7`, `9a56cab`
- [x] Task 11: judge block in `/ai` — `6b531c7`
- [x] Task 12 steps 1–6: yaml, deploy examples, rules, build — `72e4f79`, `dc75296`
- [x] Mesh-review (`/claude-mesh:mesh-review default autodecide`, `BASE_BRANCH=master`) and auto-decide of disputed issues

**Remaining tasks:**
- [ ] Task 12 Step 7: owner live day on a fast preset (not an agent coding task)
- [ ] Before a PR: `git rm` all `docs/superpowers/` and commit — owner rule; they stay in branch history
- [ ] Optional follow-ups listed under SESSION CONTEXT (not in the original plan DAG)

## SESSION CONTEXT

This session did **not** implement the plan. The plan was already on the branch. This session ran the code review and applied review decisions.

**Mesh-review:** `AUTODECIDE=true`, `HOST=grok`, `BASE_BRANCH=master`. Reviewers kept: `native:grok-4.6`, `native:kimi-k3`, `native:deepseek-v4-pro`, `claude:opus` (REAL), `claude:fable` (REAL). **codex excluded** — ChatGPT usage limit, two re-dispatches, try again after 17:46 MSK on 2026-09-05. Do not treat Codex as having cross-validated.

**AUTO commit** `dc75296`: cache `recordingsInWindow` once per candidate; WARN once when camera queue crosses 20; log degraded context `errors`; `.env.example` default preset `grok-fast` (docker yaml has no `claude-sonnet`); `APP_AI_JUDGE_RATE_LIMIT_ENABLED` in the example; facade comment about JPEGs; `/ai` auth.note mentions judge; stale SPI comments.

**Autodecide (applied):**
- Cancel/shutdown: `process()` on `CancellationException` sends unjudged under `NonCancellable` then rethrows — `89fd1b5` (уверенно). Facade still marks the recording processed before `submit`.
- Queue cap 20: depth > 20 → `FAILOVER`/`TRANSPORT` + send, no model — `481703d` (под вопросом: the plan had called a cap optional).
- Context build: `withTimeout(10s)` around zone+build; `TimeoutCancellationException` → `CONTEXT_ERROR`, not the shutdown path — `5bcd53d` (под вопросом: 10s is not a setting). A hang test via MockK `coAnswers { delay/await }` does **not** suspend; do not re-add that test that way.
- `/verdicts 0` and `/verdicts 31` parse as invalid, not as `camId` — `9a56cab` (уверенно).

**Autodecide (не исправлять):**
- Claude SDK work-timeout is still `APP_AI_DESCRIPTION_TIMEOUT+5s` (`DefaultClaudeInvoker`), not the judge timeout. Safe when judge timeout ≤ description timeout. Correct fix is timeout on `VisionRequest`/`ClaudeInvoker.invoke` (SPI). Follow-up, not this plan.
- Snooze still arms on PUBLISH (prompt + existing test). Do not restrict to SUPPRESS without a product change.

**Intentional deviations from the original plan (still true):**
- Jackson 3: `snooze_minutes` via `doubleValue().toInt()` then `coerceIn` (`5eb8693`).
- `escapeTelegramHtml` is public (core `/verdicts`).
- `SnoozeRegistry` is a field of the orchestrator, not a `@Component`.
- Grok payload is `structured ?: text` (no merge of partial structured with text).
- `0269625`: outer fail-open around `judgeLocked`; vision semaphore `try/finally`; facade submits context frames with empty `frameBytes` (visualized JPEGs still travel; `descriptionSupplier` still closes over original frames).

**Ops / PR (not code in this diff):**
- `NOTIFICATIONS_TRACK_REAPPEAR_CLASSES=person` is an ops patch.
- First four commits on the branch are design/plan/execution-prompt only.
- Uncommitted/untracked `docs/superpowers/**` prompts from other features exist in the working tree — do not add them.
- Last full `./gradlew build` at review generation: SUCCESS, 1292 tests, 0 failures, 1 skipped. After review commits, only targeted tests were re-run (`NotificationJudgeServiceTest`, `JudgeContextBuilderTest`, `VerdictsArgumentsTest`, `ktlintCheck`).

**Global constraint drift:** plan still says `CancellationException` is always rethrown without send. After `89fd1b5` it is send-then-rethrow. Spec fail-open now covers shutdown cancel.

## PLAN QUALITY WARNING

The plan was written for a large task and may contain:
- Errors or inaccuracies in implementation details
- Oversights about edge cases or dependencies
- Assumptions that don't match the actual codebase
- Missing steps or incomplete instructions

**If you notice any issues during implementation:**
1. STOP before proceeding with the problematic step
2. Clearly describe the problem you found
3. Explain why the plan doesn't work or seems incorrect
4. Ask the user how to proceed

Do NOT silently work around plan issues or make significant deviations without user approval.

## INSTRUCTIONS

1. Read the documents listed above
2. Understand current progress and session context
3. Provide a brief summary of what you understood
4. **STOP and WAIT** — do NOT proceed with any implementation
5. Ask: "What would you like me to work on?"
