## TASK

Continue the **notification schedule** feature (global OWNER-configured daily window that
suppresses recording-detection Telegram notifications outside e.g. 00:00–07:00), branch
`feature/notification-schedule`.

**ALL plan tasks (1–10) are COMPLETE, reviewed, and the branch builds green. A 7-reviewer
mesh-review (2026-07-19) additionally found ZERO Critical and produced NO code changes.**
The branch is blocked ONLY on two human gates: the Task 9 Step 5 live-bot checklist and the
Important-1 waiter decision. Expect this session to start from the results of one or both.

## CRITICAL: DO NOT START WORKING

**STOP. READ THIS CAREFULLY.**

After loading all context below, you MUST:
1. Read the documents and understand the context
2. Run `git log --oneline -6` and `git status --short` — the tip must be a
   `docs: refresh continuation prompt …` commit whose PARENT chain is `ead0555`
   (previous continuation prompt) → `41fbff2`; `modules/` content equals `8486a65`
   (verify: `git log --oneline 8486a65..HEAD -- modules/` prints NOTHING). The tree holds
   ONLY the known unrelated entries listed under Git discipline below.
3. Report what you understood (brief summary)
4. **WAIT for explicit user instructions** before taking ANY action

**DO NOT:** implement, change code, commit, or run build commands until the user says to begin.

## DOCUMENTS

- **SDD progress ledger: `.superpowers/sdd/progress.md` — READ THIS FIRST.** Gitignored scratch
  in the working tree. Holds the whole state: environment notes, the two ktgbotapi
  source-research sections that settle the waiter question, the "LEDGER-FIXES WAVE LANDED"
  record, the "MESH-REVIEW LANDED (2026-07-19)" record, every adjudication, and the
  closed/dropped finding triage.
- Design (authoritative, 266 lines): `docs/superpowers/specs/2026-07-18-notification-schedule-design.md`
- Plan (fully trimmed to done-markers): `docs/superpowers/plans/2026-07-18-notification-schedule.md`
- Fix-wave artifacts: `.superpowers/sdd/ledger-fixes-brief.md` (requirements incl. the
  adjudicated item-29 semantics table) and `.superpowers/sdd/ledger-fixes-report.md`
  (mutation evidence).
- Review decision log (point lookups ONLY, do not read whole):
  `docs/superpowers/specs/2026-07-18-notification-schedule-review-iter-1.md`

## PROGRESS

- [x] Tasks 1–10 — done and reviewed (commits in the plan's done-markers); fix wave
      `72e1f81..8486a65` (seven triaged items, mutation-proven); **first full build GREEN
      2026-07-19** — BUILD SUCCESSFUL 5m04s, 692 tests / 0 failures / 1 pre-existing skip
      (ai-description, untouched by branch — verified), model jacoco first-ever run passed
      (11.15% ≥ 1%); plan status `e63df58`, plan trim `41fbff2`.
- [x] **Mesh-review (2026-07-19, `/claude-mesh:mesh-review default`)** — 7 reviewers: builtin
      claude, codex CLI, ext-claude × 5 (zai/glm, alibaba/qwen, deepseek/v4-pro, ollama/kimi,
      ollama/minimax); all six wrappers mechanically verified REAL (verify-delegation.sh, no
      flips). **Zero Critical across all seven**; verdicts ready-YES ×3 (claude, glm, qwen),
      with-fixes ×4 (codex, deepseek, kimi, minimax). Processing outcome: **AUTO 0 /
      DISPUTED 0 / NO commits** — every finding dismissed against code+adjudications or a
      REPEAT of the three gated clusters (waiter lock → Important 1; bot-level routing test →
      ledger 42; ScheduleSettingsFlow tests → Step 5 gate). Codex independently re-ran the
      FULL suite + ktlint under zulu25: BUILD SUCCESSFUL (second green-build confirmation).
      Full record + verified-facts list: ledger §"MESH-REVIEW LANDED".
- [ ] **Task 9 Step 5 — live-bot checklist (merge gate, the HUMAN runs it).** 8 items below.
- [ ] **Important 1 decision (HUMAN)** → then possibly the waiter fix package + the Important 2
      doc rewrite + incremental re-build. The mesh-review added ONE new input to this package —
      the "command swallowing" item below.
- [ ] Finishing: `git rm` docs/superpowers/ + PR (superpowers:finishing-a-development-branch).

## THE TWO HUMAN GATES

### Step 5 checklist (8 items, live bot; original 6 + two added by review Important 3)

1. `zman` → valid zone (`Europe/Berlin`) → saved message + main screen re-rendered.
2. `zman` → garbage input → error message, flow exits (one-shot).
3. `zman` → `/cancel` → cancelled message.
4. `zman` → silence 120 s → timeout message.
5. `zman`, then immediately another `nfs:` click — **predicted FAIL**: spinner hangs until the
   waiter ends AND the deferred clicks then EXECUTE late. Expected — record the picture, don't
   abort the checklist over it.
6. Double `zman` click → no duplicate saves/messages — predicted to pass, but BECAUSE of the
   library's serialization, not for the reason currently documented.
7. (Important 3a) Stop Postgres → click `nfs:g:sched:off` → expect the ⚠️ error reply
   (`ScheduleSettingsFlow.kt:92-95`; that reply has zero automated coverage by design).
8. (Important 3b) Record the OBSERVED spinner/replay behaviour from item 5 — the Important 2
   doc rewrite must be written against reality, not against a source reading.

Plan rule: if a check other than the predicted #5 fails — STOP and discuss, do not improvise
around the waiter.

Optional extra observation (settles the mesh-review's new finding empirically): while a `zman`
waiter pends, send `/notifications` — prediction: the command EXECUTES **and** the waiter also
consumes the text ("unknown timezone" reply).

### Important 1 — the decision package (source research DONE — do not re-derive)

ktgbotapi processes all callbacks of ONE registration for ONE user strictly sequentially on an
unbounded queue → the 120 s `zman` waiter freezes later `nfs:` clicks and then REPLAYS them.
Settled by reading v33.1.0 sources (full citations in the ledger):

- The fix is ONE ARGUMENT: `markerFactory = null` in the `onDataCallbackQuery` registration —
  library-documented ("Pass null to handle requests fully parallel"); exception logging is NOT
  lost on the null path. The project passes `markerFactory` nowhere today.
- **The one-liner is HALF the fix.** It removes the accidental double-click protection, and the
  waiter race is real and marker-independent (`subcontextUpdatesFilter` is per-user; two live
  waiters see the SAME text message) — currently MASKED by serialization. Lifting it REQUIRES a
  `zman` dialog lock (`ActiveExportTracker` is the in-module precedent). All other sched
  actions are payload-explicit and idempotent; `zman` is the sole stateful path.
- Upgrading ktgbotapi does NOT help: relevant files byte-identical 33.1.0 ↔ 35.1.0 (verified by
  empty diff); migration would break 29 `CommonMessage<T>` references. Do not propose it.
- Important 2: the waiter doc claim at `.claude/rules/telegram-notifications.md` ("concurrent
  waiters compete → double `zman` plausibly yields two saves") is WRONG under current code
  (serialized) and becomes TRUE under `markerFactory = null`. Rewrite it ONLY AFTER the
  decision, using the observed Step 5 behaviour. The cross-dialog claim (`zman` + `/timezone`
  race) is correct and stays.
- **NEW input from the mesh-review: the waiter also swallows COMMANDS.** While `zman` pends,
  the owner's `/status` or `/notifications` EXECUTES (its own `onCommand` registration
  receives the update independently) AND the waiter consumes the same text → "unknown
  timezone" reply. Mechanism confirmed against the ledger's source research (waiters subscribe
  to the subcontext's allUpdatesFlow; markers gate only handler ENTRY). glm's version of the
  mechanism ("command not executed") is WRONG — builtin claude's is right. The ops-notes
  waiter section does not mention command-swallowing: it MUST enter the Important-2 rewrite,
  and a `startsWith("/")` guard (treat-as-cancel, symmetric with `/timezone`) is an OPTION
  inside the Important-1 package. Raised by glm (Imp), deepseek (Imp), builtin claude (Minor).
- Alternative on the table: accept freeze-and-replay as-is and document it honestly (no code
  change).

If the human approves the package: one fix subagent (implement `markerFactory = null` + zman
dialog lock + doc rewrite + whatever tests are mockable) → task review → incremental build.
This touches `FrigateAnalyzerBot` registration and `ScheduleSettingsFlow` — areas frozen so
far by adjudication; that freeze lifts only with this decision.

## SESSION CONTEXT

### Execution method

`/claude-mesh:do-plan` → `superpowers:subagent-driven-development`; fix waves = one fixer
subagent with a brief file + task reviewer until Spec ✅ / Approved. Dispatch model is **opus**
— a user override of config `runtime.dispatch_model: fable`; **re-state it when resuming**
(`/claude-mesh:do-plan dispatch model opus`) or it silently falls back to `fable`. STOP
threshold 250 000 tokens.

### Environment (costs iterations if forgotten)

**Gradle needs `JAVA_HOME=/usr/lib/jvm/zulu25-ca-amd64` prefixed on every `./gradlew`** — PATH
java is Zulu 21 and toolchain auto-detect is off; bare `./gradlew` dies before compiling.
Gradle runs go through the `build-runner` agent; recorded precedent: read-only `ktlintCheck`
may run direct if delegation stalls twice. Do NOT run repo-wide `ktlintFormat` (no path
scoping; unrelated uncommitted work in the tree) — fix findings by hand.

Baselines (verified; tree at `8486a65` module-side): telegram **299**, service 66, model 24,
core 229, ai-description 74 (1 pre-existing skip); full build GREEN in ~5 min (independently
re-confirmed by the mesh-review's codex run). Pre-existing noise to ignore: Byte Buddy /
`sun.misc.Unsafe` dynamic-agent warnings, ktgbotapi `@RiskFeature`, kapt MapStruct
`ConverterMapperProcessor`, JDK-25 native-access/CDS-sharing lines.

### Git discipline (non-negotiable — the tree holds unrelated work)

**Staged** `docs/deep-research-review-report.md` (496 lines, predates the branch — must stay
uncommitted); untracked `.taskmaster/`, `docs/log-token-sanitization-issue.md`,
`docs/reset-liquibase-checksums.sh`, fourteen `docs/superpowers/plans/*-prompt.md` from OTHER
features, `tmp_diff_handler.txt`. Every commit on this branch uses **explicit pathspecs**
(`git commit -m "…" -- <paths>`); never `git add -A`, never `git commit -a`, never a pathless
commit. Verify every commit with `git show --stat HEAD`.

### Mesh-review verified facts — do NOT re-derive, do NOT "fix"

- `getUserZone` returns non-null `ZoneId` (UTC fallback) — minimax's "null reaches setZone"
  is type-impossible.
- `model/build.gradle.kts` branch diff is test-scope only (kotlin-test-junit5 +
  junit-platform-launcher) — scope is correct.
- `cacheMutex` + withLock structure predates the branch (present at `e5ab9b5`) — the
  mutex-held-across-DB-read note is pre-existing and theoretical (steady state fully cached).
- The `callbackMsg as ContentMessage<TextContent>` cast in `FrigateAnalyzerBot` (flagged by
  kimi as Important, qwen as Minor) is NOT a crash path: `bot.answer` runs FIRST (no spinner
  hang), `as? MessageDataCallbackQuery ?: return` at :183 pre-filters inaccessible callbacks,
  type erasure makes the raw cast tolerant of any ContentMessage, `nfs:` keyboards ride only
  on bot-authored TEXT messages, the outer catch is the backstop — and the identical cast has
  lived on the RERENDER path since before the branch (verified at `e5ab9b5:217`). Do not
  "fix" it pre-Step-5.

### Methodology that keeps paying — demand it again

Every behavior fix is **proven by mutation**: break the implementation, confirm EXACTLY the
new assertion fails (name it, count the rest), revert byte-for-byte (sha256 before/after),
confirm green. The fix wave recorded four such proofs; across the branch, reviews caught five
tests that passed against broken implementations. Second lesson: read library SOURCES, not
decompiled bytecode — the bytecode pass missed the documented opt-out, per-registration marker
maps, and that `waitTextMessage()` is not marker-gated.

### Adjudicated — do NOT "fix" (carried forward)

- Ledger 46's PREMISE is wrong (two `zman` clicks are serialized TODAY); its lock conclusion
  becomes required only under `markerFactory = null`.
- Two-site edit-error-policy duplication (`FrigateAnalyzerBot.kt:216-220` string-match vs
  `ScheduleSettingsFlow` typed catch) — leave; follow-up issue, not this branch.
- `ScheduleSettingsFlow` ships without unit tests — settled (mockkStatic acrobatics on
  ktgbotapi extensions); Step 5 is its gate. Re-raised by mesh-review (kimi, codex, builtin) —
  still settled.
- Bot-level routing-order test absence — ledger 42, defensible (a reorder degrades to DEAD
  BUTTONS, never data corruption; no bot-level tests exist in the project at all). Re-raised
  by FIVE mesh reviewers — still settled; a reusable BehaviourContext test harness is a
  follow-up idea, not this branch.
- Renderer tests assert rendered EN-locale text — project convention (iter-1 line 548).
- "Back" from the END picker returns to the main screen (cancel UX) — intended.
- Time basis is `recording.recordTimestamp`; fail-open direction is a security decision;
  reason precedence is normative ("first tripped gate"); write-order invariant window → zone →
  enabled LAST (pinned by `coVerifyOrder`); two warns per corrupt-config recording; ru/en
  message parity — all normative, do not touch.
- Item 7 CLOSED comment-only: the redaction policy is coherent (success-path values at debug
  only; corrupt-path raw values in warns deliberate). Item 29 CLOSED with the semantics table
  in `ledger-fixes-brief.md` (corrupt "window + no zone" → misconfigured regardless of
  enabled; zone-only preconfiguration → plain OFF). Re-raised by minimax as "asymmetric
  classification" — DISMISSED again; builtin claude independently endorsed the current shape.
- Manual-zone validation deliberately WIDER than `/timezone` (offset ids allowed) — design-
  documented; relaxing `/timezone` is a follow-up outside this branch (re-raised by glm).
- Dropped-findings list unchanged (see ledger); 13/25 and 27 worth revisiting only if the area
  is touched again.

### Before the future PR

`git rm` everything under `docs/superpowers/` and commit — plan documents must NOT appear in
the PR diff. Finishing step, not execution.

## PLAN QUALITY WARNING

The plan and this hand-off may contain errors, oversights, or assumptions that don't match the
codebase. If you notice an issue: STOP before the problematic step, describe the problem,
explain why it seems wrong, ask the user how to proceed. Do NOT silently work around issues.
Перед «исправлением» странно выглядящего места — свериться с iter-1 журналом и с ledger:
скорее всего это сознательное решение ревью или уже адъюдицированная находка.

## INSTRUCTIONS

1. Прочитать ledger `.superpowers/sdd/progress.md` (включая секцию MESH-REVIEW LANDED), затем
   design; план — только done-маркеры.
2. Выполнить `git log --oneline -6` и `git status --short` — убедиться в состоянии дерева.
3. Кратко изложить понимание: всё выполнено, ветка зелёная, mesh-review дал 0 Critical и 0
   правок, остались два человеческих гейта.
4. **STOP и ждать** явной команды пользователя.
5. Спросить: «С чего начать?» Вероятные варианты: (а) принять результаты прогона Step 5 и
   решение по Important 1; (б) реализовать пакет `markerFactory = null` + zman-lock + doc
   rewrite (если решение принято; в doc rewrite войдёт и command-swallowing); (в) принять
   текущее поведение → честно переписать doc (включая command-swallowing) → finishing branch
   (PR); (г) разобрать неожиданности, которые показал прогон.
