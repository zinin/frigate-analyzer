## TASK

Continue executing the implementation plan for AI description presets and the `/ai` switch.

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

- Design: `docs/superpowers/specs/2026-09-04-ai-description-presets-design.md`
- Plan:   `docs/superpowers/plans/2026-09-04-ai-description-presets.md`
- **Execution ledger: `.superpowers/sdd/2026-09-04-ai-description-presets/progress.md`** — read this
  third document too. It is not part of the original design work: it is the record of THIS
  execution, and it holds the 16 rulings that resolve the conflicts between the plan and the design,
  the requirements carried forward into the remaining tasks, and every deferred minor finding the
  final review has to triage. Nothing below repeats it in full.

Tasks 1–4 have been trimmed out of the plan (their bodies replaced with a commit reference) so you
do not spend context on finished work; the full original text is in git history. The plan is now
~1740 lines and still carries concrete code for every remaining step — read it properly, not just
the headings.

The per-task implementer reports and review reports live beside the ledger in
`.superpowers/sdd/2026-09-04-ai-description-presets/`. Do not read them up front; open one only if
you need the detail behind a specific decision.

## PROGRESS

**Completed tasks (9 commits on `feature/grok-description-provider`, base `1895d8f`):**

- [x] Task 1: Свойства пресетов, yaml и валидация — `4fcff86`, `1893439`
- [x] Task 2: `model` и `effort` как параметры вызова — `8a8e99a`, `775cb1e`
- [x] Task 3: Фабрики, каталог и проводка (executed as 3a + 3b) — `ab67789`, `c5a65d3`, `9870226`
- [x] Task 4: Рантайм-настройки и резолюция активного пресета — `f1556c6`, `0a991ff`

Every completed task went through an independent spec+quality review and, where findings were
raised, a fix round plus a scoped re-review. All modules were green at the pause:
ai-description 269 tests, core 359, telegram 349, ktlint clean.

**Remaining tasks:**

- [ ] Task 5: Состояние авторизации по области учётных данных
- [ ] Task 6: Хранение настроек в `app_settings` и рантайм-выключатель
- [ ] Task 7: Экран `/ai` — состояние, рендер и i18n
- [ ] Task 8: Команда `/ai`, коллбэки и подсказка в алерте авторизации
- [ ] Task 9: Деплой и документация
- [ ] Task 10: Полная сборка, ревью и живая проверка

## SESSION CONTEXT

### The single most important thing to know

**The design document was substantially rewritten in a review iteration; a number of the plan's code
snippets were never re-synced with it.** A pre-flight scan of the plan found twelve such conflicts
before any code was written, and each was resolved by a ruling recorded in the ledger. The design is
the binding authority; the plan is its argument. Where a snippet in Tasks 5–10 contradicts what is
described below or what is already in the tree, the tree and the design win — but say so out loud
rather than silently deviating.

### What was actually built (differs from the plan's text in Tasks 1–4)

- `api/DescriptionPreset.kt` — `DescriptionPreset(id, provider, model, effectiveModel, effort,
  authScopeId, unavailableReason)` plus `val available`. The plan's DTO snippet lacks
  `effectiveModel` and `authScopeId` and types the reason as `String?`.
- `api/UnavailableReason` (same file) — sealed: `NoToken`, `HomeUnwritable(path)`,
  `NoFactory(provider)`. `CliMissing` was deliberately **removed**: the design says a missing CLI is
  a WARN that does not make a preset unavailable, so the variant could never have a producer.
- `api/DescriptionPresets.kt` — `all(): List<DescriptionPreset>`.
- `api/ActiveDescriptionPreset.kt` — `suspend fun storedId(): String?` and
  `suspend fun effective(): DescriptionPreset`. There is no `activePresetId()`; the plan's snippet
  and its test both refer to one that was never defined.
- `api/DescriptionRuntimeSettings.kt` — the four suspend methods from the plan **plus an abstract
  `val sourceName: String`** so the storage names itself in the log line.
- `core/DescriptionBackendFactory.kt` — `providerId`, `availability(): Availability`,
  `effectiveModel(preset): String = preset.model` (defaulted), `authScopeId(preset): String` (no
  default), `create(preset)`. `Availability.Unavailable` carries an `UnavailableReason`, not a
  string.
- `core/DescriptionPresetCatalogBuilder.build(presets, defaultPreset, factories, timeout): Result` —
  a sealed `Result` = `Catalog | NoPresets | NoneUsable(message)`, never null and never throwing for
  those three outcomes. **Note the fourth parameter**, which the plan does not have. The builder
  also emits the startup diagnostics: the preset list, the slow-effort timeout WARN, and the
  "a provider's configuration displaces this preset's model" WARN.
- `core/ActivePresetResolver` implements `ActiveDescriptionPreset` and adds
  `suspend fun resolve(): DescriptionPresetCatalog.Entry`. Every settings read goes through one
  bounded helper (5 s), is fail-open to `catalog.fallback()`, warns once per distinct message, and
  catches `TimeoutCancellationException` **before** `CancellationException` (the former is a
  subclass of the latter, and genuine caller cancellation must still propagate).
- Both factories are **strictly passive in their constructors**: all environment inspection
  (creating `GROK_HOME` and the working dir, probing the CLI, warning about `auth.json`) happens in
  `availability()` behind `by lazy`, and the builder only asks providers that appear in a declared
  preset. A failed `Files.createDirectories` returns `Unavailable(HomeUnwritable(path))` instead of
  throwing, because throwing kills the Spring context before the catalog exists.
- `GrokHomeSweeper` takes an `ObjectProvider<DescriptionPresets>` and returns early when no declared
  preset uses grok.

### Requirements carried into the remaining tasks (not in the plan's text)

**Task 5** — the plan's snippets are the pre-review text and are wrong in two ways its own tests
already contradict:
- Auth state is keyed by **credential scope**, not provider: `ProviderAuthStates.byScope()`,
  `ProviderAuthTracker.onSuccess/onUnauthorized(authScopeId, …)`. `DescriptionBackend` gains
  `val authScopeId: String`, supplied by the factory — `claude` → `"claude"`,
  `grok` → `"grok:<model>"`. The design devotes a section to why per-provider is a lie: a working
  BYOK key publishes RESTORED and paints all of grok healthy while the OAuth `auth.json` is still
  broken, and it offers `grok login` to a stale BYOK key that the command cannot fix.
- Rename `DescriptionProviderAuthEvent.provider` → `authScopeId` and update
  `DescriptionAuthAlertNotifier` (5 references) and its tests. The design's other sentence, that the
  notifier "does not change", is about behaviour — it forwards the string verbatim into `{0}`.
- Task 4 left `authScopeId` computed but consumed by nobody, and the agent still has a single
  agent-wide `authState`. That is a **parked review finding** whose owner is Task 5: once more than
  one backend is reachable per call, a LOST on preset A can be cleared by a success on preset B.
- The two multithreaded tests move out of `DefaultDescriptionAgentTest` **alive**, with their real
  threads and `CountDownLatch` — they are the only tests that prove the lock exists for a reason.

**Task 6** — two hard obligations:
- `AppSettingsDescriptionRuntimeSettings` must `override val sourceName = "app_settings"`. The SPI
  gained that member in Task 4; forgetting it is a compile error, not a silent bug.
- The facade must inject `DescriptionRuntimeSettings` through `ObjectProvider`. `PresetBeans` is
  gated on `enabled=true` **and** presets-declared, so with the feature off the bean does not exist
  and a required injection would break a descriptions-disabled start.

**Task 7** — three things:
- The view state keeps **both** ids (`storedPresetId` and `effectivePresetId`) and derives
  `hasMismatch`; the renderer marks `✅` against the effective one. The plan's own renderer snippet
  and test refer to a single `activePresetId` the DTO does not have.
- The renderer must **branch on the sealed `UnavailableReason`** and localize it, never interpolate
  its `toString()` — that is the whole reason the reason became a typed code. Expect to add
  `ai.settings.reason.*` keys to both bundles. (The `NoneUsable` startup message may interpolate:
  it goes to a log, not to Telegram.)
- Known design gap to decide deliberately: `storedId()` is fail-open, so during a settings outage
  `/ai` renders a `✅` on the yaml default as though it were the owner's choice. There is no third
  "could not read" state. The owner's likely reaction (re-picking) is idempotent, so nothing is at
  risk beyond a false assertion on screen.
- Auth lines are per **scope**, not per provider (see Task 5). The design's screen mock predates
  that decision and shows one line per provider.

**Task 8** — the callback handler splits into `classify(data, isOwner): Dispatched` (pure, no I/O)
and `apply(data, changedBy)` (the write). The registration answers the callback from `classify`,
then calls `apply`, then re-renders. The plan's "Interfaces" section already mandates this split;
only its snippets still show a single `dispatch` that writes before answering. This matters because
the default `markerFactory` serializes one user's callbacks, so a handler stuck on a slow database
blocks the owner's *next* click, and because the design's "answer first, write second" is why there
is no success toast at all.

**Task 9** — two things that are easy to lose:
- `.claude/rules/ai-description.md` is now **factually wrong** — it still documents backends as
  `@Component`s gated on `provider=<id>` with `@ConditionalOnBean(DescriptionBackend)`. It
  auto-loads for anyone working under `modules/ai-description/**`, so trust the code over it until
  this task rewrites it.
- The startup catalog INFO line currently names only preset ids; Step 3a wants
  `id (provider/model/effort)` **with the values**.
- Both "смежная правка в Task 1" notes inside Task 9 are **already implemented**: the `default-preset`
  WARN for an empty map (Task 1) and the slow-effort retry-budget WARN, which lives in
  `DescriptionPresetCatalogBuilder` rather than in `DescriptionProperties.init` because the builder
  is the only place that sees the legacy-synthesized preset too. Do not implement them twice.
- The "where the active preset came from" INFO line (Step 3a) is **already implemented** in
  `ActivePresetResolver`, emitted lazily on the first resolution rather than at startup — naming the
  source requires a suspend R2DBC read that would block context refresh. Step 3a is documentation
  only now.

**Task 10** — unchanged from the original prompt: the rollout is two steps, both before the merge.
First deploy with an EMPTY presets map, to prove the legacy path behaves exactly as today (that is
the feature's own recorded constraint); only then declare presets on the stand and run the rest of
the checks. Then `git rm` everything under `docs/superpowers/` so the plan documents stay out of the
PR diff, and note that `.superpowers/` is git-ignored scratch that never enters the PR at all.

### Deferred minor findings

Roughly two dozen Minor findings were recorded rather than fixed, each as a `minor (deferred)` line
in the ledger. Task 10's review is explicitly supposed to triage them and decide which must be fixed
before the merge. They are not lost, and they are not to be fixed opportunistically along the way.

### Environment and process

- **Gradle needs `JAVA_HOME=/usr/lib/jvm/zulu25`** on this machine: `~/.gradle/gradle.properties`
  sets `auto-detect=false` and `JAVA_HOME` is unset in the shell. Nothing in the repo needs changing.
- The repository lives on a VMware HGFS share, so builds are slow: a single test class takes ~30 s
  and a module suite several minutes. Budget for it; a full three-module run is not a quick check.
- Two `core` runs failed once with `WebTestClient` 5 s timeouts under machine load (context startup
  134 s versus 1.5–16 s normally). It reproduced on a clean baseline worktree and is unrelated to
  this feature — if you see it, re-run on a quiet machine rather than chasing it.
- An unrelated documentation file may be **staged but uncommitted** on this branch. Commit with
  explicit pathspecs; never `git add -A` or `git add .`.
- Every commit message ends with a separate `-m` argument carrying `Claude-Session: <URL of YOUR
  session>`. Do not copy the URL from an earlier commit — that would attribute your work to a
  previous session.
- `CLAUDE.md` forbids running Gradle directly in the main session. During this execution the rule
  was applied as: implementer subagents run Gradle themselves in their own context (which is what
  the rule protects — the controller's context), while the controller never does and would use the
  `claude-forge:build-runner` agent if it needed a build.

## PLAN QUALITY WARNING

The plan was written for a large task and may contain:
- Errors or inaccuracies in implementation details
- Oversights about edge cases or dependencies
- Assumptions that don't match the actual codebase
- Missing steps or incomplete instructions

This is not hypothetical here: twelve concrete conflicts were already found and ruled on before any
code was written, and two more surfaced during implementation (an unbounded settings read that could
hang the pipeline, and a backward-compatibility path with no assertion covering it). Expect more in
Tasks 5–10, and expect the stale snippets to look plausible.

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
