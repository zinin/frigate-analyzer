## TASK

Continue executing the implementation plan for the **notification schedule** feature (global
OWNER-configured daily window that suppresses recording-detection Telegram notifications outside
e.g. 00:00–07:00).

Tasks 1–9 are done, committed and reviewed. **Task 10 is partially done and the branch is
BLOCKED on one design decision plus a dirty working tree.**

## CRITICAL: DO NOT START WORKING

**STOP. READ THIS CAREFULLY.**

After loading all context below, you MUST:
1. Read the documents and understand the context
2. **Run `git log --oneline -5` and `git status --short`** — the tree state is unusual, see below
3. Report what you understood (brief summary)
4. **WAIT for explicit user instructions** before taking ANY action

**DO NOT:** implement, change code, commit, or run build commands until the user says to begin.

## ⚠️ FIRST THING TO KNOW — THE WORKING TREE IS DIRTY WITH PARTIAL, UNVERIFIED WORK

A fix agent working through ledger items **23, 24, 29, 7, 34, 39, 41** was still running when the
previous session hit its context limit and stopped. It **committed nothing**, wrote **no report**
(`.superpowers/sdd/ledger-fixes-report.md` does not exist), and was still editing files minutes
after the session took its last snapshot — so any file list written here would be stale by the time
you read it.

**Therefore: do not trust a snapshot. Establish the state yourself.**

```bash
git log --oneline -3          # expect b59778f at the tip; if there are commits past it, the agent lived longer
git status --short            # what is actually modified/staged right now
git diff --stat -- modules/   # unstaged work
git diff --cached --stat -- modules/   # staged work
```

What you can rely on:

- **Nothing from that agent was compiled, tested, or mutation-proven.** It never ran a single
  verification command. Treat every modification it left as an unverified draft to check or discard,
  not as work in progress you can build on.
- **Item 24 is the risky one.** It removes `= null` defaults from `NotificationsViewState`, which by
  design turns every construction site that omits those fields into a compile error. Whether the
  cascade was completed is UNKNOWN — start by compiling.
- **Item 23 must be mutation-proven** (hardcode `language = "en"` in the factory, confirm the new
  assertion fails, revert, confirm green). There is no evidence this was done.
- The ledger describes what each of the seven items requires, including the two that need a
  judgment call (29 and 7).

**A hazard this created:** the index may now mix that agent's work with the pre-existing staged
`docs/deep-research-review-report.md`, which is unrelated and predates this branch. A pathless
`git commit` would sweep it in. Keep using explicit pathspecs (see Git discipline below).

**A hazard this created:** the index now mixes those two test files with the pre-existing staged
`docs/deep-research-review-report.md`, which is unrelated work that predates this branch. A
pathless `git commit` would sweep it in. Keep using explicit pathspecs (see Git discipline below).

## DOCUMENTS

- Design (authoritative, 266 lines): `docs/superpowers/specs/2026-07-18-notification-schedule-design.md`
- Plan (Tasks 1–9 trimmed to done-markers; Task 10 full): `docs/superpowers/plans/2026-07-18-notification-schedule.md`
- **SDD progress ledger: `.superpowers/sdd/progress.md` — READ THIS FIRST.** Gitignored scratch in
  the working tree. It survives compaction and holds: the environment notes, the cross-task
  hand-off warnings, **47 numbered minor findings with the whole-branch review's triage of each**,
  the adjudication record of what must NOT be "fixed", and — most importantly — the two ktgbotapi
  source-research sections that settle the blocking question.
- Review decision log: `docs/superpowers/specs/2026-07-18-notification-schedule-review-iter-1.md` —
  50 замечаний со статусами. НЕ читать целиком; открывать точечно, когда шаг плана выглядит
  спорным — скорее всего это сознательное решение ревью.

## PROGRESS

**Completed (Tasks 1–9, all reviewed ✅):**

- [x] Task 1 — `ScheduleWindow` + `NotificationSchedule` — `6394584`
- [x] Task 2 — `getString/setString` + negative caching — `55350a5`, `d2448eb`, `a18ec03`
- [x] Task 3 — `AppSettingKeys` + `NotificationScheduleService` — `ac4172c`
- [x] Task 4 — `OUT_OF_SCHEDULE` suppression — `2667433`, `17f6670`
- [x] Task 5 — `NotificationsViewStateFactory` — `a13d316`
- [x] Task 6 — status line + buttons — `f2807f1`, `ef1f07e`
- [x] Task 7 — `ScheduleKeyboardRenderer` + `TimezonePresets` — `e9a6460`
- [x] Task 8 — `ScheduleCallbackHandler` dispatch — `70e0c8f`, `985b9fe`
- [x] Task 9 — `ScheduleSettingsFlow` + bot wiring — `e802ef6`, `ed47e3b` (spec ✅ / quality ✅)

**Task 10 — partially done:**

- [x] Step 1 — docs + 2 KDoc fixes + 1 mutation-proven test — `854cfa6`, `48614cd`, `687d159`
- [x] Step 2 — branch-wide code review: **no Critical**, verdict "with fixes"
- [ ] Step 2 fixes — three Important, see below. **This is where the branch is blocked.**
- [ ] Step 3 — full `./gradlew build`. **NEVER RUN ON THIS BRANCH, not once.**
- [ ] Step 4 — commit
- [ ] Task 9 Step 5 — **REQUIRED manual live-bot checklist, never performed.** Merge gate.

## THE BLOCKING DECISION — read this before proposing anything

The whole-branch review found **no Critical issues**, but one Important blocks merge.

### Important 1 — the `zman` waiter freezes and then REPLAYS the owner's later `nfs:` clicks

ktgbotapi processes all callbacks of one registration for one user **strictly sequentially on an
unbounded queue**. `onDataCallbackQuery` defaults `markerFactory` to `ByUserCallbackQueryMarkerFactory`
(keyed on the user); `subscribeAsync` holds per marker a `Channel(Channel.UNLIMITED)` consumed by
exactly one coroutine. So while the 120-second manual-zone waiter is pending:

- `bot.answer(callback)` sits INSIDE the serialized body → later clicks are never acknowledged and
  the spinner hangs;
- the queue is unbounded, so those clicks are not dropped but **deferred and then executed** —
  minutes later, with `answer()` failing on expired ids while the mutations still land.

This is plan Task 9 Step 5 check #5, predicted to FAIL. The plan's own rule — *"if any check
fails, STOP and discuss, do not improvise around the waiter"* — is why nothing was changed.

### What the source research settled (this is the valuable part — do not re-derive it)

The reviewer reached the above from **decompiled bytecode**. Two agents then read the **actual
sources** — one pinned to `v33.1.0` (our version), one to `v35.1.0` (latest). Full citations are in
the ledger. Bottom line:

1. **The fix is ONE ARGUMENT, documented by the library.** `CallbackQueryTriggers.kt:89` declares
   `markerFactory` as **nullable**, and its KDoc at `:80-82` says verbatim: **"Pass null to handle
   requests fully parallel"**. `MainTrigger.kt:83-87` takes a fresh-coroutine-per-update branch when
   it is null, and exception logging is not lost there. Call site: add `markerFactory = null,` to
   `onDataCallbackQuery(...)`. The project passes `markerFactory` nowhere today — `grep` returns 0.
2. **Upgrading would NOT help and is not worth it.** The relevant files are **byte-identical**
   between v33.1.0 and v35.1.0 (verified by an empty `diff`), including MicroUtils
   `FlowSubscriptionAsync.kt` between 0.29.2 and 0.30.0. Nothing was added for waiters-from-callback.
   The upgrade would cross two majors and break 29 references to `CommonMessage<T>` (removed in
   34.0.0 for a sealed `ChatContentMessage<T>`) across every command-handler signature.
3. **⚠️ The one-line fix is only half a fix.** `subcontextUpdatesFilter` defaults to
   `CallbackQueryFilterByUser`, so under `markerFactory = null` two concurrent handlers of the same
   user get subcontexts fed by the same stream — two parallel `waitTextMessage()` calls would
   compete for one message. **Today's serialization is what masks this.** Both researchers reached
   this independently while reading different refs. So lifting serialization **REQUIRES** explicit
   isolation of the text wait (a `zman` dialog lock; `ActiveExportTracker` is the in-module
   precedent). The other sched actions stay safe under parallelism because every payload carries
   explicit values and is idempotent by design — `zman` is the sole stateful path.

**So the realistic package is:** `markerFactory = null` + a `zman` dialog lock + rewriting the wrong
doc claim (Important 2). **But the plan requires settling waiter behaviour on a live bot first**,
and the user has not run Step 5 yet. Do not start coding this without their decision.

### Important 2 — a doc claim THIS BRANCH added is factually wrong

`.claude/rules/telegram-notifications.md:103-106` says concurrent waiters compete and a double
`zman` plausibly yields two saves. Under the CURRENT code two `zman` clicks cannot overlap — they
are serialized. Step 5 check #6 will pass, but not for the documented reason. The **cross-dialog**
claim at `:107-109` IS correct (`onCommand` is a separate registration with its own marker map, so
`zman` + `/timezone` genuinely race). Note the interaction: if Important 1 is fixed with
`markerFactory = null`, the claim becomes TRUE and the text must be rewritten again — so fix the
doc AFTER the waiter decision, not before. This is the third comment-contradicts-implementation
instance on this branch.

### Important 3 — Step 5's checklist is missing two items

Add before running it: (a) the settings-failure error reply — stop Postgres, click
`nfs:g:sched:off`, expect the ⚠️ reply (`ScheduleSettingsFlow.kt:92-95` has zero coverage of any
kind); (b) record the **observed** spinner/replay behaviour, so Important 2's doc fix is written
against reality rather than against a source reading.

## SESSION CONTEXT

### Execution method

`/claude-mesh:do-plan` → `superpowers:subagent-driven-development`: a fresh implementer subagent per
task, then a task reviewer (spec compliance + code quality), fix rounds until both verdicts are ✅,
then the ledger line. Dispatch model is **opus** — a user override of config
`runtime.dispatch_model: fable`; **re-state it when resuming** (`/claude-mesh:do-plan dispatch model
opus`) or it silently falls back to `fable`. STOP threshold 250 000 tokens.

### Environment (costs iterations if forgotten)

**Gradle needs `JAVA_HOME=/usr/lib/jvm/zulu25-ca-amd64` prefixed on every `./gradlew` invocation.**
`~/.gradle/gradle.properties` sets `org.gradle.java.installations.auto-detect=false` with no
`installations.paths`, and PATH `java` is Zulu 21, so a bare `./gradlew` dies with "Cannot find a
Java installation … languageVersion=25" before compiling anything.

Baselines at `687d159`: telegram **295** green, service **66**, model **24**, ktlint clean on all
three (verified with `--rerun-tasks`, not just an UP-TO-DATE run).

Do NOT run repo-wide `ktlintFormat` — it has no path scoping and would reformat the unrelated
uncommitted work. Fix ktlint findings by hand.

Pre-existing test-output noise, not from this branch and not to be chased: Byte Buddy /
`sun.misc.Unsafe` dynamic-agent warnings (JDK 25 + mockk), ktgbotapi `@RiskFeature` warnings, and a
kapt MapStruct `ConverterMapperProcessor` warning.

### Git discipline (non-negotiable — the tree holds unrelated work)

19 unrelated entries: **staged** `docs/deep-research-review-report.md`; untracked `.taskmaster/`,
`docs/log-token-sanitization-issue.md`, `docs/reset-liquibase-checksums.sh`, eleven
`docs/superpowers/plans/*-prompt.md`, `tmp_diff_handler.txt`. Every commit on this branch used
**explicit pathspecs** (`git commit -m "…" -- <paths>`) precisely because a pathless commit would
sweep up that staged doc — and now also the fix agent's partial work. Never `git add -A`, never
`git commit -a`. Verify each commit with `git show --stat HEAD`.

### Methodology that repeatedly paid off — keep doing it

**Four separate reviews caught tests that passed against a broken implementation**, two of them in
the plan's own supplied test sets. Consequence: **every fix must be proven by mutation** — write the
broken implementation, confirm the test fails, revert byte-for-byte, confirm green. That
requirement is what turned each into a caught defect instead of a shipped one. The most recent:
ledger 31's one-line fix in `687d159`, where a non-padded `"$hour:00"` renderer failed **exactly
one** test — the new assertion — while the other 294 passed. Ask for this again.

**And a second, newer lesson: read the sources, not the decompiled output.** The bytecode analysis
got the mechanism right but missed the documented one-argument opt-out, missed that marker maps are
per-registration (so our three `onDataCallbackQuery` calls already run concurrently), and missed
that `waitTextMessage()` is not marker-gated at all — which is what makes the waiter race real and
marker-independent. Two agents reading different refs converged on that last point.

### Adjudicated — do NOT "fix" these

- **Ledger 46's premise is WRONG** — two `zman` clicks are serialized today, so do NOT add a lock
  citing item 46. (Its *conclusion* becomes right if Important 1 is fixed with `markerFactory = null`.)
- The surviving **two-site duplication of the edit-error policy** (`FrigateAnalyzerBot.kt:216-220`
  still string-matches; `ScheduleSettingsFlow` uses the typed exception). Human-adjudicated and
  re-confirmed by the whole-branch review: leave it. Pre-existing, unit-test-free code, log-level-only
  divergence. Follow-up issue, not a change on this branch.
- **`ScheduleSettingsFlow` ships without unit tests** — settled (mockkStatic acrobatics on ktgbotapi
  extension functions). Step 5 is its gate. Expect reviewers to raise it; it is adjudicated.
- Renderer tests asserting rendered **EN-locale text** — review iter-1 line 548 settled this as
  project convention.
- **"Back" from the END picker returns to the main screen**, not to the start picker — human-confirmed
  as intended "cancel" UX.
- **Time basis is `recording.recordTimestamp`** (event time), deliberately not "now".
- **Fail-open direction is a security decision:** corrupt settings produce extra notifications,
  never lost ones.
- **Reason precedence is normative** ("first tripped gate"): `NO_DETECTIONS` → `NO_VALID_DETECTIONS`
  → `GLOBAL_OFF` → `OUT_OF_SCHEDULE` → `NEW_OBJECTS`/`ALL_REPEATED`.
- **Write-order invariant:** window → zone → enabled LAST. Pinned by `coVerifyOrder`.
- Two warns per processed recording on a corrupt config — deliberate anomaly signal.
- Every user-visible string goes into BOTH `messages_en.properties` and `messages_ru.properties` or
  `MessageKeyParityTest` fails.

### Ledger triage already performed by the whole-branch review

- **Closed, do not re-raise:** 6 (measured — model module jacoco is 11.15% instruction coverage vs
  the 1% minimum, so its first-ever verification will pass), 19, 21, 22, 31, 38, 40.
- **Should fix:** 23, 24, 41 (drafted but unverified in the dirty tree), and 29, 7, 34, 39 (not
  started). Details for each are in the ledger.
- **Drop:** 2, 3, 4, 5, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 20, 25, 26, 27, 28, 30, 32, 33,
  35, 36, 37, 42, 44, 45, 47. Of these, 13/25 and 27 are worth revisiting if the area is touched
  again.

### Before the future PR

`git rm` everything under `docs/superpowers/` and commit — plan documents must not appear in the PR
diff. That is a finishing step, not part of execution.

## PLAN QUALITY WARNING

The plan was written for a large task and may contain errors, oversights about edge cases,
assumptions that don't match the codebase, or missing steps. Its supplied *test* sets in particular
have proven fallible more than once — treat plan-supplied assertions as a starting point, not as
evidence that a weakness was chosen deliberately.

**If you notice any issues:**
1. STOP before proceeding with the problematic step
2. Clearly describe the problem you found
3. Explain why the plan doesn't work or seems incorrect
4. Ask the user how to proceed

Do NOT silently work around plan issues or make significant deviations without user approval.
Перед «исправлением» странно выглядящего места — свериться с iter-1 журналом и с ledger: возможно,
это сознательное решение ревью или уже адъюдицированная находка.

## INSTRUCTIONS

1. Прочитать ledger `.superpowers/sdd/progress.md`, затем design и Task 10 плана.
2. Выполнить `git log --oneline -5` и `git status --short` — убедиться в реальном состоянии дерева.
3. Кратко изложить понимание: что сделано, что осталось, состояние грязного дерева, и суть
   блокирующего решения по waiter'у.
4. **STOP и ждать** явной команды пользователя.
5. Спросить: «С чего начать?» Наиболее вероятные варианты — (а) разобраться с недоделанной работой
   в дереве, (б) обсудить решение по Important 1 после того как пользователь прогонит Step 5,
   (в) доделать оставшиеся ledger-правки, (г) запустить первый полный build.
