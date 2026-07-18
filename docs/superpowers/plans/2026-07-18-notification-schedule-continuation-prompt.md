## TASK

Continue executing the implementation plan for the **notification schedule** feature
(global OWNER-configured daily window that suppresses recording-detection Telegram
notifications outside e.g. 00:00–07:00).

Tasks 1–4 are done, committed and reviewed. Tasks 5–10 remain — the whole Telegram UI plus
docs and the final build.

## CRITICAL: DO NOT START WORKING

**STOP. READ THIS CAREFULLY.**

After loading all context below, you MUST:
1. Read the documents and understand the context
2. Report what you understood (brief summary)
3. **WAIT for explicit user instructions** before taking ANY action

**DO NOT:** implement tasks, change code, or run commands (beyond reading) until the user
explicitly says to begin.

## DOCUMENTS

- Design (final, review iter 1 applied): `docs/superpowers/specs/2026-07-18-notification-schedule-design.md`
- Plan (Tasks 1–4 trimmed to done-markers; Tasks 5–10 full): `docs/superpowers/plans/2026-07-18-notification-schedule.md`
- **SDD progress ledger: `.superpowers/sdd/progress.md` — READ THIS FIRST.** It is
  gitignored scratch in the working tree, it survives context compaction, and it holds the
  environment notes, the cross-task hand-off warnings, and all 22 rolled-up minor findings
  that the final whole-branch review must triage.
- Review decision log: `docs/superpowers/specs/2026-07-18-notification-schedule-review-iter-1.md` —
  50 замечаний со статусами. НЕ читать целиком; открывать точечно, когда шаг плана выглядит
  спорным — скорее всего это сознательное решение ревью.

Read design + the remaining plan tasks fully before summarizing.

## PROGRESS

**Completed (7 feature commits on `feature/notification-schedule`, all reviewed ✅):**

- [x] Task 1 — `ScheduleWindow` + `NotificationSchedule` model types — `6394584`
- [x] Task 2 — `AppSettingsService.getString/setString` + negative caching of absent keys — `55350a5`, `d2448eb`, `a18ec03`
- [x] Task 3 — `AppSettingKeys` + `NotificationScheduleService` (fail-open, never throws) — `ac4172c`
- [x] Task 4 — `OUT_OF_SCHEDULE` suppression in `NotificationDecisionServiceImpl` — `2667433`, `17f6670`

**Remaining:**

- [ ] Task 5 — `NotificationsViewState` schedule fields + `NotificationsViewStateFactory`
- [ ] Task 6 — schedule status line and buttons on the `/notifications` main screen
- [ ] Task 7 — `ScheduleKeyboardRenderer` (hour pickers, zone screen) + shared `TimezonePresets`
- [ ] Task 8 — `ScheduleCallbackHandler` pure dispatch for `nfs:g:sched:*`
- [ ] Task 9 — `ScheduleSettingsFlow` + bot wiring (includes a REQUIRED manual live-bot checklist)
- [ ] Task 10 — documentation + branch-wide code review + full build

The backend is functionally complete: the schedule is read and the gate is applied. Nothing
has changed in practice yet — all three settings keys are absent, so the schedule resolves
to `null` and notifications flow exactly as before. The UI (Tasks 5–9) is what makes the
feature reachable.

Test state at the checkpoint: `:frigate-analyzer-model:test` 24/24,
`:frigate-analyzer-service:test` 66/66, `:frigate-analyzer-telegram:compileTestKotlin` and
`:frigate-analyzer-core:compileTestKotlin` both green, ktlint clean on the touched modules.
The full `./gradlew build` has NOT been run yet — Task 10 does that.

## SESSION CONTEXT

### Execution method

Tasks 1–4 ran under `/claude-mesh:do-plan` → `superpowers:subagent-driven-development`:
a fresh implementer subagent per task, then a task reviewer (spec compliance + code
quality), fix rounds until both verdicts are ✅, then the ledger line. Dispatch model was
**opus** — a user override of config `runtime.dispatch_model: fable`; re-state it when
resuming (`/claude-mesh:do-plan dispatch_model бери opus`) or it falls back to `fable`.
STOP threshold 250 000 tokens; this session paused at it after Task 4.

### Environment (this bit cost the first agent several iterations)

**Gradle needs `JAVA_HOME=/usr/lib/jvm/zulu25-ca-amd64` prefixed on every `./gradlew`
invocation.** `~/.gradle/gradle.properties` sets
`org.gradle.java.installations.auto-detect=false` with no `installations.paths`, and PATH
`java` is Zulu 21, so a bare `./gradlew` dies with "Cannot find a Java installation …
languageVersion=25" before compiling anything. Subagents ran gradle directly in their own
isolated context — the project rule about delegating builds targets the main session, and
`Bash(./gradlew:*)` is allow-listed.

Byte Buddy dynamic-agent warnings in `system-err` are a repo-wide JDK-25/mockk artifact
predating this branch. Not noise any task introduced; don't chase it.

### Git discipline (non-negotiable — the tree holds unrelated work)

19 unrelated uncommitted entries were present at session start and are still byte-identical:
staged `docs/deep-research-review-report.md`; untracked `.taskmaster/`,
`docs/log-token-sanitization-issue.md`, `docs/reset-liquibase-checksums.sh`, eleven
`docs/superpowers/plans/*-prompt.md`, `tmp_diff_handler.txt`. Every commit so far used
**explicit pathspecs** (`git commit -m "…" -- <paths>`) precisely because a pathless commit
would sweep up that staged doc. Never `git add -A`, never `git commit -a`.

### The one production deviation from the plan's literal text

Task 2's plan text said "`setBoolean` keeps its shape" and told the implementer to apply the
same two logging changes to both setters. The reviewer flagged the resulting ~10-line
verbatim duplication as Important. I checked the iter-1 journal first: its CONCERN-13 had
decided only *what* both setters log, never how many copies of the write path should exist —
so the duplication was a gap in the plan's wording, not a deliberated decision.
**`setBoolean` now delegates to `setString`** (`a18ec03`), behavior byte-identical (proven by
a strict-mockk test that pins the exact upsert arguments). If a later task or the final
review expects two separate bodies, this is why there is one.

### Hand-off warnings for the remaining tasks

- **Task 8:** `ScheduleWindow.ofHours` has TWO failure modes with **incompatible exception
  types** — `ofHours(7, 7)` throws `IllegalArgumentException` (the `require`), but
  `ofHours(24, 7)` throws `java.time.DateTimeException` (`LocalTime.of`), and
  `DateTimeException` does NOT extend `IllegalArgumentException`. The plan's dispatch already
  guards both range and equality before calling `ofHours` — verify it stays that way; a
  `catch (e: IllegalArgumentException)` would let an out-of-range hour escape.
  Also: `ScheduleWindow.parse` returns null on `start == end` while the constructor throws —
  asymmetry by design.
- **Tasks 5 and 9** read `NotificationScheduleService`'s KDoc to know which methods throw.
  The asymmetry is real and implemented correctly (`getRecordingSchedule()` swallows
  everything except `CancellationException`; `isEnabled`/`getWindow`/`getZone` propagate),
  but the *propagate* half has no test pinning it — the boundary is positional, so widening
  either `try` by one line would silently make the dialog accessors fail-open with the whole
  suite still green. Ledger item 13.
- **Task 5** tells you to remove `appSettings` from `FrigateAnalyzerBot`'s constructor — grep
  first that its only uses really are the two `getBoolean` calls in the RERENDER block.
- **Task 6** cites renderer-test lines 128/146 for the `isOwner = true` constructions
  (verified 2026-07-18); if the file has drifted, search for `isOwner = true` instead.
- **Task 9** is deliberately WITHOUT unit tests (a decision against mockkStatic acrobatics on
  ktgbotapi extension functions). Its Step 5 manual live-bot checklist is the merge gate, and
  the waiter runs from a callback context for the first time — if it behaves unexpectedly,
  STOP rather than improvising around it.
- **Task 10** docs: broaden the ops line about caching. Negative caching now applies to EVERY
  `app_settings` key, not just the schedule triple — before Task 2 an absent key was never
  cached, so an operator who `INSERT`ed e.g. `notifications.recording.global_enabled` by SQL
  saw it on the next read; now it stays absent for the process lifetime.

### What the reviews established, so you needn't re-derive it

- No exhaustive `when` over `NotificationDecisionReason` exists anywhere in `modules/`, so
  the additive `OUT_OF_SCHEDULE` entry cannot break a downstream compile; `decision.delta`
  has zero consumers in `core`/`telegram`.
- `NotificationDecisionServiceImpl` has exactly one construction site repo-wide (its test
  fixture); production wiring is Spring constructor injection.
- The `service` module's `@ComponentScan("ru.zinin.frigate.analyzer.service")` covers the
  `.impl` subpackage, so the new `@Service` bean wires the same way `AppSettingsServiceImpl`
  already does. First real context startup is still ahead — Task 10's full build.
- All four completed tasks used the plan's supplied code **verbatim**; no supplied test ever
  contradicted its supplied implementation. That is a good prior for the remaining code
  blocks, not a guarantee.
- 22 minor findings are rolled up in the ledger for the final whole-branch review to triage.
  Two of them are documentation errors worth fixing during Task 10: the schedule read is
  missing from `NotificationDecisionService`'s execution-order KDoc list, and
  `NotificationDecision.kt:14` still claims `NO_VALID_DETECTIONS` means filtering happened
  "before tracker", which the implementation contradicts.

### Decisions from brainstorming and review iter 1 that must NOT be "improved"

- **Time basis is `recording.recordTimestamp`** (event time), deliberately not "now": during
  backlog catch-up a night detection processed in the morning must still be delivered, a
  daytime one never.
- **`evaluate()`'s signature stays unchanged**; the schedule is read inside it via the
  never-throwing `getRecordingSchedule()`. Do not prefetch it in the facade.
- **Fail-open direction is a security decision:** corrupt/unreadable schedule settings must
  produce extra notifications, never lost ones.
- **Reason precedence is normative** ("first tripped gate"): `NO_DETECTIONS` →
  `NO_VALID_DETECTIONS` → `GLOBAL_OFF` → `OUT_OF_SCHEDULE` → `NEW_OBJECTS`/`ALL_REPEATED`.
- **Status line has three states + "misconfigured"**; the ON branch requires both window and
  zone (smart cast).
- **Manual zone input is deliberately wider than `/timezone`:** accepts `UTC` (the feature's
  own materialization fallback) and offset zones. Do NOT copy `contains('/')`.
- **`OUT_OF_SCHEDULE` logs at debug** by design — don't raise it, don't add metrics.
- Every user-visible string goes into BOTH `messages_en.properties` and
  `messages_ru.properties` or `MessageKeyParityTest` fails.
- Before the future PR: `git rm` everything under `docs/superpowers/` and commit — plan docs
  must not appear in the PR diff. That is a finishing step, not part of execution.

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

Do NOT silently work around plan issues or make significant deviations without user
approval. Перед «исправлением» странно выглядящего места — свериться с iter-1 журналом:
возможно, это сознательное решение ревью.

## INSTRUCTIONS

1. Прочитать ledger `.superpowers/sdd/progress.md`, затем design и оставшиеся задачи плана.
2. Кратко изложить понимание: что уже сделано, что осталось, ключевые предупреждения.
3. **STOP и ждать** явной команды пользователя.
4. Спросить: «С чего начать?» (ожидаемо: Task 5 через `/claude-mesh:do-plan dispatch_model
   бери opus`, который продолжит цикл subagent-driven-development с того же места).
