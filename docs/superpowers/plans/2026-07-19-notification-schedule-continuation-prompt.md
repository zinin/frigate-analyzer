## TASK

Continue executing the implementation plan for the **notification schedule** feature
(global OWNER-configured daily window that suppresses recording-detection Telegram
notifications outside e.g. 00:00–07:00).

Tasks 1–8 are done, committed and reviewed. **Only Tasks 9 and 10 remain** — the bot wiring
plus docs, branch-wide review and the first full build.

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
- Plan (Tasks 1–8 trimmed to done-markers; Tasks 9–10 full): `docs/superpowers/plans/2026-07-18-notification-schedule.md`
- **SDD progress ledger: `.superpowers/sdd/progress.md` — READ THIS FIRST.** Gitignored
  scratch in the working tree; it survives compaction and holds the environment notes, the
  cross-task hand-off warnings, **41 numbered minor findings** with their resolutions, and
  the adjudication record of what must NOT be "fixed".
- Review decision log: `docs/superpowers/specs/2026-07-18-notification-schedule-review-iter-1.md` —
  50 замечаний со статусами. НЕ читать целиком; открывать точечно, когда шаг плана выглядит
  спорным — скорее всего это сознательное решение ревью. (Пример реального применения: строка
  548 прямо принимает англо-зависимость renderer-тестов как осознанную цену — ревьюер Task 6
  поднял её как находку и сам отозвал, когда я показал запись.)

Read design + the remaining plan tasks fully before summarizing.

## PROGRESS

**Completed (13 feature commits on `feature/notification-schedule`, all reviewed ✅):**

- [x] Task 1 — `ScheduleWindow` + `NotificationSchedule` model types — `6394584`
- [x] Task 2 — `AppSettingsService.getString/setString` + negative caching — `55350a5`, `d2448eb`, `a18ec03`
- [x] Task 3 — `AppSettingKeys` + `NotificationScheduleService` (fail-open) — `ac4172c`
- [x] Task 4 — `OUT_OF_SCHEDULE` suppression in the decision service — `2667433`, `17f6670`
- [x] Task 5 — `NotificationsViewStateFactory` + schedule fields — `a13d316`
- [x] Task 6 — status line + buttons on `/notifications` — `f2807f1`, `ef1f07e`
- [x] Task 7 — `ScheduleKeyboardRenderer` + shared `TimezonePresets` — `e9a6460`
- [x] Task 8 — `ScheduleCallbackHandler` dispatch — `70e0c8f`, `985b9fe`

**Remaining:**

- [ ] Task 9 — `ScheduleSettingsFlow` + bot wiring (includes a REQUIRED manual live-bot checklist)
- [ ] Task 10 — documentation + branch-wide code review + full build

Everything except the final wiring exists: the backend gate works, the state is assembled,
the screens render, the callbacks are decided. **Nothing is reachable by a user yet** — no
callback is routed to the schedule flow. Task 9 is the single wire that turns the feature on.

Test state at the checkpoint: `:frigate-analyzer-telegram:test` **294 tests green**, ktlint
clean on touched modules. `:frigate-analyzer-model:test` 24/24 and
`:frigate-analyzer-service:test` 66/66 as of Task 4. **The full `./gradlew build` has NEVER
been run on this branch** — Task 10 Step 3 is the first time.

## SESSION CONTEXT

### Execution method

`/claude-mesh:do-plan` → `superpowers:subagent-driven-development`: a fresh implementer
subagent per task, then a task reviewer (spec compliance + code quality), fix rounds until
both verdicts are ✅, then the ledger line. Dispatch model is **opus** — a user override of
config `runtime.dispatch_model: fable`; **re-state it when resuming**
(`/claude-mesh:do-plan dispatch model opus`) or it silently falls back to `fable`.
STOP threshold 250 000 tokens; this session paused at it after Task 8.

### Environment (costs iterations if forgotten)

**Gradle needs `JAVA_HOME=/usr/lib/jvm/zulu25-ca-amd64` prefixed on every `./gradlew`
invocation.** `~/.gradle/gradle.properties` sets `org.gradle.java.installations.auto-detect=false`
with no `installations.paths`, and PATH `java` is Zulu 21, so a bare `./gradlew` dies with
"Cannot find a Java installation … languageVersion=25" before compiling anything. Subagents
run gradle directly in their own context — the project rule about delegating builds targets
the main session, and `Bash(./gradlew:*)` is allow-listed.

Pre-existing test-output noise, not introduced by this branch and not to be chased: Byte
Buddy / `sun.misc.Unsafe` dynamic-agent warnings (JDK 25 + mockk), and ktgbotapi
`@RiskFeature` warnings.

### Git discipline (non-negotiable — the tree holds unrelated work)

19 unrelated uncommitted entries are present and still byte-identical: **staged**
`docs/deep-research-review-report.md`; untracked `.taskmaster/`,
`docs/log-token-sanitization-issue.md`, `docs/reset-liquibase-checksums.sh`, eleven
`docs/superpowers/plans/*-prompt.md`, `tmp_diff_handler.txt`. Every commit so far used
**explicit pathspecs** (`git commit -m "…" -- <paths>`) precisely because a pathless commit
would sweep up that staged doc. Never `git add -A`, never `git commit -a`. Every implementer
was given this rule and every one of them honoured it — keep saying it.

### ⚠️ THE BIGGEST RISK IN TASK 9 — routing collision

Found by Task 8's reviewer, outside its diff. This is the one thing most likely to ship broken.

`NotificationsSettingsCallbackHandler.kt:33-39` splits callback data on `:` and matches
`parts.size == 4 && parts[0] == "nfs"`. **Six of the twelve sched payload forms produce
exactly 4 parts and DO match it**: `nfs:g:sched:on`, `:off`, `:cfg`, `:zone`, `:zman`,
`:home`. `parts[3]` is then neither `"1"` nor `"0"`, so lines 50-53 early-return
`DispatchOutcome.IGNORE` — before `when (parts[1] to parts[2])` is ever evaluated.

The multi-token payloads (`s:`, `e:`, `z:`) split into 5–6 parts and sail past. So if the bot
polls the legacy handler first, the failure is **PARTIAL**: the hour pickers keep working
while enable/disable/zone/back do nothing but write a debug line. That is the easiest
possible thing to miss in a manual smoke test.

This is exactly why the plan makes the interception order an invariant (Task 9 Step 3.3 —
intercept `ScheduleCallbackHandler.PREFIX` BEFORE the generic dispatch) and mandates the
IGNORE guard test (Step 3.4). **Do both, and verify the guard test actually discriminates.**

### Other Task 9 hand-off warnings

- **Exception propagation:** `ScheduleCallbackHandler.dispatch` deliberately does NOT catch
  failures from `scheduleService` — correct for a pure dispatch layer. Task 9 must catch
  them, or a settings failure leaves the callback unanswered and the button spinning.
- **Task 9 is deliberately WITHOUT unit tests** — a settled decision against mockkStatic
  acrobatics on ktgbotapi extension functions. Its Step 5 manual live-bot checklist is the
  merge gate, and the waiter runs from a callback context for the first time. **If it
  behaves unexpectedly, STOP rather than improvising around it.** Expect the reviewer to
  raise "no tests" as a finding; it is adjudicated, not open.
- `NotificationScheduleService`'s asymmetry matters here: `getRecordingSchedule()` swallows
  everything except `CancellationException`; `isEnabled`/`getWindow`/`getZone` PROPAGATE.
  The propagate half still has no test pinning it (ledger item 13) and the boundary is
  positional — widening either `try` by one line would silently make the dialog accessors
  fail-open with the whole suite green.
- Task 7's reviewer flagged that "Back" from the END picker returns to the main screen
  rather than to the start picker (both grids share one `hourGrid` helper). Plan-mandated
  and functionally a deliberate "cancel" — **confirm it as intended UX while wiring Task 9**
  rather than discovering it in production (ledger item 35).

### Methodology that repeatedly paid off — keep doing it

**Three separate reviews caught tests that passed against a broken implementation.** Two of
those gaps were in the PLAN's own supplied test sets, not in implementer work:

1. Task 6's ON-state test asserted only that the window and zone appear — which the
   OFF-configured line also satisfies. Swapping two `when` branches would have rendered an
   enabled schedule as "OFF" with the whole suite green.
2. Task 8, trap 3: an `action.takeLast(2)` parse passes all 18 of the brief's tests, yet
   breaks every single-digit start hour — half the picker.
3. Task 8, write-order: `coVerify(exactly = n)` is order-insensitive, so a mutant writing
   `setEnabled(true)` FIRST — precisely the state the invariant exists to prevent — left the
   suite BUILD SUCCESSFUL.

Consequence: **every fix round was required to prove the fix by mutation** — write the broken
implementation, confirm the test fails, revert, confirm green. That requirement is what
turned all three into caught defects instead of shipped ones. Ask for it again.

### Deviations from the plan's literal text (so no later reviewer is surprised)

- **Task 2:** `setBoolean` delegates to `setString` (`a18ec03`) — the plan implied two
  separate bodies. Behavior byte-identical, pinned by a strict-mockk test.
- **Task 8:** the zone-materialization block was extracted into
  `materializeZoneIfMissing(ownerChatId, updatedBy)` (`985b9fe`). Plan supplied it inline
  twice. **This one was escalated to the user, who approved it** — the same species as Task 2,
  and in both cases the iter-1 journal had decided only WHAT each path must do, never how
  many copies of the code should exist. Side effect: it closed a coverage finding without a
  new test, since one implementation means the existing test guards both paths.
- **Task 6:** ktlint 1.8 reformatted the plan's supplied `when` block (bracing + blank lines
  only); the plan's `val offLabel` local was dropped because `renderText()` already resolves
  that exact key into an in-scope `off`.
- **Task 7:** the plan's Step 4 code references an undefined `ZONE_PRESETS`; the plan itself
  resolves it to `TimezonePresets.CITIES` in the next sentence. Applied as the plan intended.
- **Tasks 7 and 8** added a handful of tests beyond the brief, each closing a documented
  invariant that had no coverage. All additive; no specified value changed.
- `catch (_: …)` is used for unused exception parameters — verified codebase convention
  across 8 files.

### Adjudicated — do NOT "fix" these

- Renderer tests asserting rendered **EN-locale text**, which break when wording changes.
  Review iter-1 line 548 settled this as project convention.
- Two warns per processed recording on a corrupt config — the design's deliberate anomaly
  signal; the two messages are not redundant.
- Task 9 without unit tests (above).
- **Time basis is `recording.recordTimestamp`** (event time), deliberately not "now".
- **`evaluate()`'s signature stays unchanged**; the schedule is read inside it.
- **Fail-open direction is a security decision:** corrupt settings produce extra
  notifications, never lost ones.
- **Reason precedence is normative** ("first tripped gate"): `NO_DETECTIONS` →
  `NO_VALID_DETECTIONS` → `GLOBAL_OFF` → `OUT_OF_SCHEDULE` → `NEW_OBJECTS`/`ALL_REPEATED`.
- **Write-order invariant:** window → zone → enabled LAST. Now pinned by `coVerifyOrder`.
- **Manual zone input is deliberately wider than `/timezone`:** accepts `UTC` and offset
  zones. Do NOT copy `contains('/')`.
- **`OUT_OF_SCHEDULE` logs at debug** by design — don't raise it, don't add metrics.
- Every user-visible string goes into BOTH `messages_en.properties` and
  `messages_ru.properties` or `MessageKeyParityTest` fails.

### Task 10 specifics

- **Broaden the ops line about caching:** negative caching now applies to EVERY `app_settings`
  key, not just the schedule triple. Before Task 2 an absent key was never cached, so an
  operator who `INSERT`ed e.g. `notifications.recording.global_enabled` by SQL saw it on the
  next read; now it stays absent for the process lifetime.
- **Two documentation errors worth fixing:** the schedule read is missing from
  `NotificationDecisionService`'s execution-order KDoc list (ledger 21), and
  `NotificationDecision.kt:14` still claims `NO_VALID_DETECTIONS` means filtering happened
  "before tracker", which the implementation contradicts (ledger 22).
- **41 minor findings await triage** in the ledger. Ledger item **31 is the strongest
  one-line candidate**: `ScheduleKeyboardRendererTest` never pins the end-picker title's
  zero-padding, so a `"$hour:00"` implementation passes all 7 tests and ships "Window starts
  at 5:00" to the user. Same defect class as the three above.
- `/timezone` has **zero handler tests**; Task 7's refactor of it was verified only by
  review (the reviewer mechanically diffed all 8 key→olson pairs — IDENTICAL — and
  reconciled the insertion budget to prove no other line changed). Worth knowing before the
  branch-wide review re-derives it.
- Before the future PR: `git rm` everything under `docs/superpowers/` and commit — plan docs
  must not appear in the PR diff. That is a finishing step, not part of execution.

## PLAN QUALITY WARNING

The plan was written for a large task and may contain:
- Errors or inaccuracies in implementation details
- Oversights about edge cases or dependencies
- Assumptions that don't match the actual codebase
- Missing steps or incomplete instructions

Its supplied *test* sets in particular have already proven fallible twice (see Methodology
above) — treat plan-supplied assertions as a starting point, not as evidence that the
weakness was chosen.

**If you notice any issues during implementation:**
1. STOP before proceeding with the problematic step
2. Clearly describe the problem you found
3. Explain why the plan doesn't work or seems incorrect
4. Ask the user how to proceed

Do NOT silently work around plan issues or make significant deviations without user
approval. Перед «исправлением» странно выглядящего места — свериться с iter-1 журналом и с
ledger: возможно, это сознательное решение ревью или уже адъюдицированная находка.

## INSTRUCTIONS

1. Прочитать ledger `.superpowers/sdd/progress.md`, затем design и оставшиеся задачи плана.
2. Кратко изложить понимание: что уже сделано, что осталось, ключевые предупреждения —
   в первую очередь routing-коллизию Task 9.
3. **STOP и ждать** явной команды пользователя.
4. Спросить: «С чего начать?» (ожидаемо: Task 9 через `/claude-mesh:do-plan dispatch model
   opus`, который продолжит цикл subagent-driven-development с того же места).
