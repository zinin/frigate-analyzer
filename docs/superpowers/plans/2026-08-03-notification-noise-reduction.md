# Notification Noise Reduction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cut the reappearance-notification noise found in the v0.9.1 production run by adding a per-camera cooldown, a class allow-list for reappearances, and the absence data needed to tune both — every one of them a no-op unless explicitly configured.

**Architecture:** Three independently *configurable* changes across two existing services — but the
tasks that build them are **sequential, not parallel**: Task 2 consumes `ClassAbsence` and
`TrackerSummary` from Task 1, and Task 3 consumes `ProductionYamlBinder` from Task 2 (each task's
`Interfaces` block names what it takes). Dispatch them one after another; a controller that fans them
out concurrently will have two of the three fail to compile. `ObjectTrackerServiceImpl` gains a class filter on the reappearance branch and a richer debug summary extracted into an internal `TrackerSummary` value type. `NotificationDecisionServiceImpl` gains an in-memory per-camera cooldown on the `REAPPEARED` branch only, keyed and measured on `recording.recordTimestamp` rather than the wall clock. No schema change, no new module, no change to the gate order in `evaluate`.

**Tech Stack:** Kotlin 2.4.10, Spring Boot 4.1.0 (`@ConfigurationProperties` constructor binding), Coroutines, JUnit 5 + MockK + `kotlin.test`, ktlint.

## Global Constraints

- **Every new parameter is a no-op by default.** Behaviour with nothing configured must be identical to `v0.9.1`.
  - `application.notifications.cooldown.reappear` ← `NOTIFICATIONS_COOLDOWN_REAPPEAR`, `Duration`, default `PT0S` = disabled.
  - `application.notifications.tracker.reappear-classes` ← `NOTIFICATIONS_TRACK_REAPPEAR_CLASSES`, `List<String>`, default empty = all classes.
- **Do not change the gate order** in `NotificationDecisionServiceImpl.evaluate`: `NO_DETECTIONS` → `tracker.evaluate` → `NO_VALID_DETECTIONS` → `GLOBAL_OFF` → `OUT_OF_SCHEDULE` → `NEW_OBJECTS` → `REAPPEARED` → `ALL_REPEATED`. `tracker.evaluate` stays **before** all gates.
- **The cooldown gates only `REAPPEARED`.** `NEW_OBJECTS` is never suppressed by it.
- **Cooldown time base is `recording.recordTimestamp`, never `Instant.now()`.** The unprocessed queue drains newest-first with no floor on age, so a backlog is evaluated within seconds and a wall-clock cooldown would collapse it into one notification.
- **The stored cooldown anchor is the maximum notified `recordTimestamp` per camera**, because drain order makes `recordTimestamp` non-monotonic.
- **Suppression logs at DEBUG**, notifications at INFO — matching the existing branches.
- **Property validation fails fast in `init`**, in the style of `ObjectTrackerProperties`. Cooldown must be `>= PT0S`.
- **No database migrations.** Cooldown state is in-memory.
- **Do not narrow `DETECTION_FILTER_CLASSES`.**
- **Do not touch `WatchRecordsLoop.registerAllDirs`.**
- **Do not "fix" the documented trade-offs** in `ObjectTrackerServiceImpl`'s KDoc (interruption threshold == gap; residual drain-order case in `markWatched`; one-off burst after a long tracker-only outage).
- **Git:** `git add <file>` after creating or modifying any file.
- **The index is dirty with unrelated work.** `docs/deep-research-review-report.md`,
  `docs/telegram-rich-message-migration.md` and
  `docs/superpowers/plans/2026-08-03-watch-records-registration-continuation-prompt.md` were already
  staged before this branch started, and the tree carries a pile of untracked `docs/` files besides.
  A bare `git commit -m` would sweep them into a task commit. Every commit step below therefore has
  to be **path-scoped** — repeat the same paths after `git add`:
  `git commit <path> <path> … -m "…"`. Verify with `git show --stat HEAD` after each commit that
  nothing foreign got in.
- **Never run `./gradlew build` directly** — use the `claude-forge:build-runner` agent or `/build`. On ktlint errors run `./gradlew ktlintFormat` and retry.

## File Structure

| File | Status | Responsibility |
|---|---|---|
| `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/TrackerSummary.kt` | Create | Internal value types `ClassAbsence` + `TrackerSummary`: the tracker's per-recording debug line and the predicate deciding whether it is worth emitting. |
| `modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/impl/TrackerSummaryTest.kt` | Create | Pins the rendered format operators grep and tune from. |
| `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/ObjectTrackerServiceImpl.kt` | Modify | Collect absences, filter reappearances by class, emit `TrackerSummary`. |
| `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/DetectionDelta.kt` | Modify | Diagnostic `maxAbsence` field, so the tracker's accumulation is reachable from a test. |
| `modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/impl/ObjectTrackerServiceImplTest.kt` | Modify | `maxAbsence` accumulation (Task 1) and class-filter behaviour at the delta level (Task 2). |
| `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/config/ObjectTrackerProperties.kt` | Modify | `reappearClasses` + its normalized lookup set + `reappearAllows`. |
| `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/config/NotificationCooldownProperties.kt` | Create | `application.notifications.cooldown` binding + validation. |
| `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/NotificationDecision.kt` | Modify | New `COOLDOWN` reason. |
| `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/NotificationDecisionServiceImpl.kt` | Modify | Per-camera reappear cooldown on the `REAPPEARED` branch. |
| `modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/impl/NotificationDecisionServiceImplTest.kt` | Modify | Cooldown behaviour, including the backlog and out-of-order cases. |
| `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/FrigateAnalyzerApplication.kt` | Modify | Register `NotificationCooldownProperties`. |
| `modules/core/src/main/resources/application.yaml` | Modify | Two new placeholders. |
| `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/properties/ProductionYamlBinder.kt` | Create | Shared harness that binds a properties type out of the *production* yaml. |
| `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/properties/ObjectTrackerPropertiesBindingTest.kt` | Modify | Delegate to the shared harness; add `reappear-classes` cases. |
| `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/properties/NotificationCooldownPropertiesBindingTest.kt` | Create | Pins the `PT0S` default coming out of the production yaml. |
| `.claude/rules/configuration.md` | Modify | New variables + the tuning narrative tying all three knobs together. |
| `.claude/rules/telegram-notifications.md` | Modify | One bullet under "Consumers" for the cooldown. |
| `docker/deploy/.env.example` | Modify | Commented entries for both new variables. |

## Deviations from the spec — read before starting

Three deliberate departures; do not "simplify" them away during review.

1. **`classFiltered=[...]` in the tracker debug line, and `classFiltered.isNotEmpty()` added to the
   line's emit condition.** The spec asks to preserve the existing condition
   (`newCount > 0 || reappearedClasses.isNotEmpty() || unobservedAbsences > 0`). Kept literally, a
   deployment that filters every reappearing class would log *nothing* for exactly the recordings
   the operator needs to inspect: `reappearedClasses` is now empty by construction, so the line
   disappears together with the notification. The added disjunct fires only when a class filter
   actually suppressed a reappearance — a rare event, so the line still stays off ordinary
   recordings, which is what the spec's constraint protects.
2. **The cooldown compares `|sinceLast|`, not the signed value.** The anchor is the maximum notified
   `recordTimestamp` as the spec requires, so a recording older than the anchor yields a negative
   distance. A burst can be drained in either direction (the queue is newest-first, so the four
   recordings of one pass may well arrive descending), and both directions must collapse. Absolute
   distance also keeps the backlog case intact: a recording hours away from the anchor is a
   different event on either side and still notifies — which is the whole reason the spec forbids a
   wall-clock cooldown.

3. **A `reappear-classes` list holding nothing but blanks fails at binding instead of being ignored.**
   The spec (§2, "пустые элементы списка игнорировать") read literally makes `[" ", ""]` normalize to
   nothing and therefore mean *every class* — a no-op. That is the opposite of what an operator who
   set the variable wanted, and it fails silently: no error, no log, reappearances simply keep coming.
   The `require` in `ObjectTrackerProperties.init` turns it into a startup failure instead, matching
   how the class already treats every other broken invariant. Blank entries inside an otherwise usable
   list are still ignored exactly as the spec asks — only the all-blank list is rejected. The no-op
   default is untouched: an unconfigured variable resolves to an empty string, binds to an empty list,
   and passes.

**Not injecting `Clock` into `NotificationDecisionServiceImpl`.** The spec allows for it "where
wall-clock is needed at all"; the finished logic needs none, so the constructor stays free of an
unused dependency.

---


### Task 1: Tracker debug summary — `maxAbsence` and the reappearance breakdown

✅ Done — see commit `ef147c0`. Spec ✅, quality Approved, 0 critical / 0 important.

Additive deviation accepted: one extra test, `the largest absence wins even when a shorter one is
measured after it`. The pinned `maxAbsence takes the largest absence across every matched track`
supplies detections in ascending-absence order, so a plain `maxAbsence = absence` assignment passes
it — proved by mutation. The pinned test was left untouched.

---

### Task 2: Reappearance class allow-list

✅ Done — see commit `f69b751`. Spec ✅, quality Approved, 0 critical / 0 important.

Step 8a's open question is **answered**: the fail-fast IS reachable from the environment path.
Spring's delimited-string converter splits `" , "` on the comma and keeps both blank elements, so
`reappearClassesNormalized` comes out empty and the `require` fires. The test passes unbent.

Accepted deviation: Step 8a's assertion was *tightened* — it keeps
`hasRootCauseInstanceOf(IllegalArgumentException)` and adds a root-cause message assertion pinning
`"reappear-classes was set but holds no usable class name"`, because seven `require`s in that
constructor throw the same type.

---

### Task 3: Per-camera cooldown on reappearance notifications

✅ Done — see commit `8204f55`. Spec ✅, quality Approved, 0 critical / 0 important.

The concurrency gate was reviewed line by line and cleared on all six questions: all three `compute`
return paths correct, the suppressed path returns the identical `last` reference, the captured-local
write is sound (`compute` invokes the remapper exactly once, same thread, no retry), `maxOf` keeps
the anchor on the newest announced recording, `PT0S` short-circuits before the map is touched, and
the lambda is arithmetic-only with no suspension point under the bin lock. Equal timestamps yield
`Duration.ZERO`, which suppresses rather than being conflated with the "not suppressed" null.

---

### Task 4: Tuning guide

✅ Done — see commits `cec1956`, `d66d56b`, `5d7559c`. Review needed one fix round; clean after it.

**Two defects in this plan's own pinned markdown were found and corrected — do not reintroduce them:**

1. The `maxAbsence` bullet said the sub-threshold data shows what **raising** `REAPPEAR_GAP` would
   start catching. The gap is a strict lower bound (`absence > reappearGap`), so raising it catches
   strictly *less*; the sub-threshold absences are what **lowering** would catch — and this plan's
   own text 30 lines further down said exactly that, so the pinned markdown contradicted itself.
2. `NOTIFICATIONS_COOLDOWN_REAPPEAR=PT5M` shipped under "set the two noise knobs from what they
   show", but the lines the guide tells the operator to collect cannot produce that value: neither
   logger prints a `recordTimestamp`, and `sinceLast=` only appears on the
   `Decision: suppress (cooldown)` line, which cannot fire until the cooldown is already on. A
   paragraph naming the quantity (burst span) and a followable path was added.

Scope was also widened beyond the plan's single file, deliberately: `docker/deploy/.env.example` and
`.claude/rules/telegram-notifications.md` carried cooldown statements that overstated what the code
does, and the `NOTIFICATIONS_COOLDOWN_REAPPEAR` table row contradicted a bullet three lines below it.

---

## Verification

- [x] **Step 1: Full review before the build** — whole-branch review over `2b293a8..5234be5`:
      ✅ ready to merge, 0 critical, 0 important, 8 minor. No-op guarantee traced end to end through
      all three features. Feature layering confirmed correct: the allow-list drops the reappearance
      in the *tracker*, before the cooldown can arm its anchor, so a filtered cow never eats the
      camera's cooldown budget. `reappearSuppressedBy` runs after `tracker.evaluate` returns, so it
      is outside the per-camera mutex and outside the R2DBC transaction — no lock nesting at all.
      Spec §6 prohibitions all verified intact.

- [x] **Step 1a: Final fix wave** — commit `5234be5` closed six of the eight minor findings, each of
      the three test-quality ones proved by mutation. The two left open both need a Log4j2
      log-capture harness the project does not have; that is infrastructure for another branch.

- [x] **Step 2: Full build** at `5d7559c` — BUILD SUCCESSFUL, **766 tests, 0 failures, 0 errors,
      1 skipped** (the pre-existing `ClaudeDescriptionAgentIntegrationTest` stub-CLI case), ktlint
      clean, zero compilation errors. Cross-module risk closed properly: Gradle had `telegram`,
      `ai-description` and `common` marked UP-TO-DATE from a stale cache, so a forced `--rerun-tasks`
      recompile of exactly those three modules was run against branch code — clean.

- [x] **Step 2a: Re-run the build after the fix wave** at `5234be5` — BUILD SUCCESSFUL, **767 tests,
      0 failures, 0 errors, 1 skipped**, ktlint clean, 0 compilation errors and no warning in any
      file the wave touched. Run as `clean build --console=plain`, 108 actionable tasks all executed
      with nothing from cache, so every test task genuinely ran. 767 rather than 769 is correct, not
      a shortfall: the wave added exactly one new `@Test` (the cooldown boundary case) and modified
      two existing tests in place, so `service` went 118 → 119 and no other module moved.

- [ ] **Step 2b: Scoped re-review of the fix wave.** Never ran. The package is already on disk:
      `.superpowers/sdd/2026-08-03-notification-noise-reduction/review-5d7559c..5234be5.diff`.
      Use `re-review-prompt.md` with the six findings, which are listed in the ledger.

- [x] **Step 3: Confirm the acceptance criteria** — walked against spec §5 by the whole-branch
      reviewer. All three parameters no-op by default (traced through `reappearAllows` returning
      `true` on an empty set, `reappearEnabled` false at `PT0S` short-circuiting before the map, and
      the emit condition's single new disjunct being permanently false). All six listed test cases
      covered. `configuration.md` and `.env.example` updated. On that night's data: group B's four
      notifications collapse to two (one per camera) and group A vanishes entirely under
      `reappear-classes=person` — 9 notifications become 3.

- [ ] **Step 4: Remove the plan documents before the PR** — **user-gated, do not run without an
      explicit instruction.** Note the list has grown since this plan was written: it now also covers
      the review artefacts and the prompts this branch accumulated. Check
      `git status --short -- docs/` before composing the command, and remember that five unrelated
      `docs/` files were already staged before this branch started and must NOT be swept in.

```bash
git rm docs/superpowers/plans/2026-08-03-notification-noise-reduction.md \
       docs/superpowers/plans/2026-08-03-notification-noise-reduction-continuation-prompt-v2.md \
       docs/superpowers/specs/2026-08-03-notification-noise-reduction-review-merged-iter-1.md \
       docs/superpowers/specs/2026-08-03-notification-noise-reduction-review-iter-1.md
git commit -m "chore: drop the plan and review documents from the branch" \
    -- docs/superpowers/plans/2026-08-03-notification-noise-reduction.md \
       docs/superpowers/plans/2026-08-03-notification-noise-reduction-continuation-prompt-v2.md \
       docs/superpowers/specs/2026-08-03-notification-noise-reduction-review-merged-iter-1.md \
       docs/superpowers/specs/2026-08-03-notification-noise-reduction-review-iter-1.md

# Three dots on purpose — a PR shows the net diff against the merge base, so a file added and then
# removed inside the branch is absent from it. This must print nothing.
git diff master...HEAD --name-only -- docs/superpowers/
```

- [ ] **Step 5: `superpowers:finishing-a-development-branch`** — not run.
