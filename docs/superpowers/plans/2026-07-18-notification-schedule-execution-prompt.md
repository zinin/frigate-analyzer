## TASK

Execute the implementation plan for the **notification schedule** feature (global OWNER-configured daily window that suppresses recording-detection Telegram notifications outside e.g. 00:00–07:00).

Use `/superpowers:subagent-driven-development` skill for execution.

## DOCUMENTS

- Design: `docs/superpowers/specs/2026-07-18-notification-schedule-design.md`
- Plan: `docs/superpowers/plans/2026-07-18-notification-schedule.md`

Read both documents first.

## IMPORTANT: DO NOT START WORK YET

After reading the documents:
1. Confirm you have loaded all context
2. Summarize your understanding briefly
3. **WAIT for user instruction before taking any action**

Do NOT begin implementation until the user explicitly tells you to start.

## SESSION CONTEXT

Decisions made during brainstorming (with rationale), beyond what the spec records:

- **Rejected alternatives:** per-user schedules (global OWNER-only chosen); applying the schedule
  to signal-loss alerts (they stay always-on — system health); silent delivery
  (`disable_notification`) and digest of suppressed notifications (dropped entirely — daytime
  detections are pure noise, data stays in DB); free-text window input and a separate
  `/schedule` command (hour picker inside `/notifications` chosen); `answerCallbackQuery` toast
  for `end == start` (impossible — the bot answers every `nfs:` callback immediately to clear
  the spinner, a callback cannot be answered twice; inline warning in the screen text instead).
- **Time basis is `recording.recordTimestamp`** (event time), deliberately NOT "now": during
  backlog catch-up a night detection processed in the morning must still be delivered, a
  daytime detection must never be. Do not "simplify" to `Instant.now()`.
- **`evaluate()` signature intentionally unchanged.** The facade prefetches the global flag
  before `saveProcessingResult` so settings failures keep recordings retryable; the schedule is
  instead read inside `evaluate()` via `getRecordingSchedule()` which NEVER throws (fail-open
  null + warn). Do not "improve" this by prefetching the schedule in the facade or letting
  reads propagate.
- **Fail-open direction is a security decision:** corrupt/unreadable schedule settings must
  produce extra notifications, never lost ones.

Git state and repo conventions:

- Work on branch **`feature/notification-schedule`** (already created; spec, plan and this
  prompt are committed there). Verify with `git branch --show-current` before starting.
- The working tree contains **unrelated uncommitted files** (staged
  `docs/deep-research-review-report.md`; untracked `.taskmaster/`, `docs/log-token-sanitization-issue.md`,
  `docs/reset-liquibase-checksums.sh`, `tmp_diff_handler.txt`). Do NOT commit or delete them.
  `git add` only files you create/modify (project rule), and prefer `git commit -- <paths>` if
  anything unrelated is staged.
- Gradle module names carry the `frigate-analyzer-` prefix (`:frigate-analyzer-model`, etc.).
- Builds/tests go through the `build-runner` agent, never `./gradlew` directly in the main
  session. On ktlint errors: `./gradlew ktlintFormat`, retry.
- Before any future PR: `git rm` everything under `docs/superpowers/` and commit (plan docs
  must not appear in the PR diff) — but that is a finishing step, not part of this execution.

Implementation warnings discovered during planning:

- **Task 4:** the `DetectionDelta(...)` argument lists in the new decision-service tests are
  placeholders by design — copy the literal constructor call from the existing NEW_OBJECTS
  test in the same file (`NotificationDecisionServiceImplTest`); the constructor was not
  visible at plan time.
- **Task 6:** `NotificationsMessageRendererTest` was only partially inspected; besides the
  rewritten row-count test, ANY other test constructing a state with `isOwner = true` must add
  `scheduleEnabled = false` (step 2 of the task covers this — do not skip the file-wide search).
- **Task 1** adds test infrastructure to the `model` module (it had none); root build already
  applies `useJUnitPlatform()` globally.
- **Task 9:** `ScheduleSettingsFlow.manualZoneInput` uses the ktgbotapi waiter
  (`waitTextMessage`) inside a callback-handler context — the pattern is copied from
  `TimezoneCommandHandler` (120 s timeout, `/cancel`), but this is its first use from a
  callback flow. If the waiter misbehaves there, STOP and discuss rather than improvising.
- **Task 5 bot refactor:** before removing `appSettings` from `FrigateAnalyzerBot`, grep the
  file to confirm its only usages are the two `getBoolean` calls in the RERENDER block.
- Keyboard row indices in Task 6 tests assume owner rows: [0] rec user, [1] sig user,
  [2] rec global, [3] sig global, [4] sched toggle, [5] window+zone, [6] close.

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
