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
| `modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/impl/ObjectTrackerServiceImplTest.kt` | Modify | Class-filter behaviour at the delta level. |
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

Spec task 3. Pure observability: no behaviour changes, no new properties. Ships first because it is what the thresholds in tasks 2 and 3 get tuned from.

**Files:**
- Create: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/TrackerSummary.kt`
- Create: `modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/impl/TrackerSummaryTest.kt`
- Modify: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/ObjectTrackerServiceImpl.kt:165-249`

**Interfaces:**
- Produces: `internal data class ClassAbsence(val className: String, val absence: Duration)` with `fun render(): String`; `internal data class TrackerSummary(camId: String, recordingId: UUID, newCount: Int, matched: Int, reappeared: List<ClassAbsence>, classFiltered: List<ClassAbsence>, unobserved: Int, stale: Int, maxAbsence: Duration?)` with `val worthLogging: Boolean` and `fun render(): String`. Task 2 populates `classFiltered`; this task always passes `emptyList()`.
- Consumes: nothing from other tasks.

Kotlin's `internal` is visible from the module's own test source set (the Kotlin Gradle plugin wires the test compilation as a friend), so these types are unit-testable without widening visibility.

- [ ] **Step 1: Write the failing test**

Create `modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/impl/TrackerSummaryTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.service.impl

import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackerSummaryTest {
    private val recordingId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000ff")

    private fun summary(
        newCount: Int = 0,
        matched: Int = 0,
        reappeared: List<ClassAbsence> = emptyList(),
        classFiltered: List<ClassAbsence> = emptyList(),
        unobserved: Int = 0,
        stale: Int = 0,
        maxAbsence: Duration? = null,
    ) = TrackerSummary(
        camId = "cam2",
        recordingId = recordingId,
        newCount = newCount,
        matched = matched,
        reappeared = reappeared,
        classFiltered = classFiltered,
        unobserved = unobserved,
        stale = stale,
        maxAbsence = maxAbsence,
    )

    @Test
    fun `render carries every reappearance with its own absence`() {
        val rendered =
            summary(
                matched = 3,
                reappeared =
                    listOf(
                        ClassAbsence("person", Duration.ofHours(3).plusMinutes(12)),
                        ClassAbsence("bicycle", Duration.ofHours(7).plusMinutes(4)),
                    ),
                stale = 107,
                maxAbsence = Duration.ofHours(7).plusMinutes(4),
            ).render()

        assertEquals(
            "ObjectTracker: cam=cam2 new=0 matched=3 reappeared=[person:PT3H12M, bicycle:PT7H4M] " +
                "classFiltered=[] unobserved=0 stale=107 maxAbsence=PT7H4M " +
                "(recording=00000000-0000-0000-0000-0000000000ff)",
            rendered,
        )
    }

    @Test
    fun `maxAbsence renders as n slash a when nothing matched`() {
        // Every track was new, so no absence was measurable at all. Distinguishing this from
        // "matched, but with a zero-length absence" is the point of the nullable field.
        assertTrue(summary(newCount = 2).render().contains("maxAbsence=n/a"))
    }

    @Test
    fun `maxAbsence reports a below-threshold absence, which is what the threshold is tuned against`() {
        // The number the operator needs: the largest absence seen even when nothing reappeared.
        val rendered = summary(newCount = 1, matched = 4, maxAbsence = Duration.ofMinutes(40)).render()

        assertTrue(rendered.contains("maxAbsence=PT40M"), rendered)
    }

    @Test
    fun `an ordinary recording with nothing but matches is not worth logging`() {
        assertFalse(summary(matched = 12, stale = 95, maxAbsence = Duration.ofSeconds(40)).worthLogging)
    }

    @Test
    fun `a new track, a reappearance, an unobserved absence or a class-filtered one are each worth logging`() {
        assertTrue(summary(newCount = 1).worthLogging)
        assertTrue(summary(reappeared = listOf(ClassAbsence("person", Duration.ofHours(3)))).worthLogging)
        assertTrue(summary(unobserved = 1).worthLogging)
        // Without this disjunct a deployment filtering every reappearing class would log nothing
        // for exactly the recordings the operator needs to inspect.
        assertTrue(summary(classFiltered = listOf(ClassAbsence("cow", Duration.ofHours(8)))).worthLogging)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Dispatch the `claude-forge:build-runner` agent with:
`./gradlew :frigate-analyzer-service:test --tests '*TrackerSummaryTest*'`

Expected: FAIL — compilation error, `Unresolved reference: TrackerSummary`.

- [ ] **Step 3: Write the value types**

Create `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/TrackerSummary.kt`:

```kotlin
package ru.zinin.frigate.analyzer.service.impl

import java.time.Duration
import java.util.UUID

/** One matched track's absence before the current recording. */
internal data class ClassAbsence(
    val className: String,
    val absence: Duration,
) {
    fun render(): String = "$className:$absence"
}

/**
 * The tracker's per-recording debug line.
 *
 * Extracted from [ObjectTrackerServiceImpl] because it is the only place the reappearance
 * thresholds can be tuned from: `reappear-gap` is otherwise picked blind — the absences that did
 * not cross it are invisible, and they are exactly the ones that say where the boundary between
 * detector flakiness and a real return actually lies. A format operators grep is worth pinning in
 * a test rather than rediscovering in production logs.
 */
internal data class TrackerSummary(
    val camId: String,
    val recordingId: UUID,
    val newCount: Int,
    val matched: Int,
    val reappeared: List<ClassAbsence>,
    val classFiltered: List<ClassAbsence>,
    val unobserved: Int,
    val stale: Int,
    /** Largest absence among **all** matched tracks, including those below the gap. */
    val maxAbsence: Duration?,
) {
    /**
     * Keeps the line off the vast majority of recordings, where nothing but ordinary matches
     * happened.
     *
     * [classFiltered] joins the original three because without it a deployment that filters every
     * reappearing class would log nothing at all for those recordings — the one case where the
     * operator most needs to see that the filter, and not a missing reappearance, is what went
     * quiet. It only ever fires on an absence past the gap, so the line stays as rare as before.
     */
    val worthLogging: Boolean
        get() = newCount > 0 || reappeared.isNotEmpty() || classFiltered.isNotEmpty() || unobserved > 0

    fun render(): String =
        "ObjectTracker: cam=$camId new=$newCount matched=$matched " +
            "reappeared=${render(reappeared)} classFiltered=${render(classFiltered)} " +
            "unobserved=$unobserved stale=$stale maxAbsence=${maxAbsence ?: "n/a"} " +
            "(recording=$recordingId)"

    private fun render(absences: List<ClassAbsence>): String =
        absences.joinToString(prefix = "[", postfix = "]") { it.render() }
}
```

- [ ] **Step 4: Run the test to verify it passes**

`./gradlew :frigate-analyzer-service:test --tests '*TrackerSummaryTest*'` via `claude-forge:build-runner`.
Expected: PASS.

- [ ] **Step 5: Wire the summary into the tracker**

In `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/ObjectTrackerServiceImpl.kt`, replace the counter declarations at lines 165-168:

```kotlin
        var matched = 0
        var unobservedAbsences = 0
        val newClasses = mutableListOf<String>()
        val reappearedClasses = mutableListOf<String>()
```

with:

```kotlin
        var matched = 0
        var unobservedAbsences = 0
        // Tracked over every matched track, not just the ones past the gap: the sub-threshold
        // absences are what shows where the gap could be moved to.
        var maxAbsence: Duration? = null
        val newClasses = mutableListOf<String>()
        val reappeared = mutableListOf<ClassAbsence>()
```

Then, in the `if (match != null)` branch, replace line 208 (`matched++`) with:

```kotlin
                matched++
                // Negative for an out-of-order (older) recording: a later one already advanced the
                // track past this timestamp, which the KDoc at lines 191-193 spells out and
                // `out-of-order older recording never counts as reappeared` already exercises. That
                // is not an absence, and `?: absence` would take it unconditionally — a recording
                // whose only match is out-of-order would print `maxAbsence=PT-3H` and corrupt the
                // one number reappear-gap gets tuned against. Filtered here, not at render time, so
                // `maxAbsence` stays null and renders `n/a`: nothing measurable happened.
                if (absence != null && !absence.isNegative) {
                    maxAbsence = maxAbsence?.coerceAtLeast(absence) ?: absence
                }
```

and replace the reappearance registration at lines 218-222:

```kotlin
                    if (lastSeen.isBefore(watchedSince)) {
                        unobservedAbsences++
                    } else {
                        reappearedClasses += bbox.className
                    }
```

with:

```kotlin
                    if (lastSeen.isBefore(watchedSince)) {
                        unobservedAbsences++
                    } else {
                        reappeared += ClassAbsence(bbox.className, absence)
                    }
```

Finally replace the summary block and the return at lines 242-257:

```kotlin
        val newCount = newClasses.size
        if (newCount > 0 || reappearedClasses.isNotEmpty() || unobservedAbsences > 0) {
            logger.debug {
                "ObjectTracker: cam=${recording.camId} new=$newCount matched=$matched " +
                    "reappeared=${reappearedClasses.size} unobserved=$unobservedAbsences " +
                    "stale=${active.size} (recording=${recording.id})"
            }
        }
        return DetectionDelta(
            newTracksCount = newCount,
            matchedTracksCount = matched,
            staleTracksCount = active.size,
            newClasses = newClasses,
            reappearedTracksCount = reappearedClasses.size,
            reappearedClasses = reappearedClasses,
        )
```

with:

```kotlin
        val summary =
            TrackerSummary(
                camId = recording.camId,
                recordingId = recording.id,
                newCount = newClasses.size,
                matched = matched,
                reappeared = reappeared,
                classFiltered = emptyList(),
                unobserved = unobservedAbsences,
                stale = active.size,
                maxAbsence = maxAbsence,
            )
        if (summary.worthLogging) {
            logger.debug { summary.render() }
        }
        return DetectionDelta(
            newTracksCount = newClasses.size,
            matchedTracksCount = matched,
            staleTracksCount = active.size,
            newClasses = newClasses,
            reappearedTracksCount = reappeared.size,
            reappearedClasses = reappeared.map { it.className },
        )
```

- [ ] **Step 6: Run the tracker's whole suite to verify nothing regressed**

`./gradlew :frigate-analyzer-service:test` via `claude-forge:build-runner`.
Expected: PASS — the existing `ObjectTrackerServiceImplTest` cases still hold, since `reappearedClasses` is now derived from the same list in the same order.

- [ ] **Step 7: Commit**

```bash
git add modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/TrackerSummary.kt \
        modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/impl/TrackerSummaryTest.kt \
        modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/ObjectTrackerServiceImpl.kt
# Path-scoped, as Global Constraints require: the index carries unrelated staged docs, and a bare
# `git commit -m` would sweep every one of them into this commit.
git commit -m "feat(tracker): log actual absence durations in the tracker summary

reappear-gap was picked blind: absences below the threshold never surfaced,
so nothing showed where the boundary between detector flakiness and a real
return actually lies. The debug line now carries maxAbsence over all matched
tracks and a per-class breakdown of the reappearances that fired." \
    -- modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/TrackerSummary.kt \
       modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/impl/TrackerSummaryTest.kt \
       modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/ObjectTrackerServiceImpl.kt
git show --stat HEAD   # must list exactly the three files above, nothing else
```

---

### Task 2: Reappearance class allow-list

Spec task 2. Removes group A (static objects re-found at dawn) without touching what gets detected or what notifies as new.

**Files:**
- Modify: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/config/ObjectTrackerProperties.kt`
- Modify: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/ObjectTrackerServiceImpl.kt`
- Modify: `modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/impl/ObjectTrackerServiceImplTest.kt`
- Modify: `modules/core/src/main/resources/application.yaml:55-67`
- Create: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/properties/ProductionYamlBinder.kt`
- Modify: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/properties/ObjectTrackerPropertiesBindingTest.kt`
- Modify: `docker/deploy/.env.example:73-83`

**Interfaces:**
- Consumes: `ClassAbsence`, `TrackerSummary` from Task 1.
- Produces: `ObjectTrackerProperties.reappearClasses: List<String>`, `ObjectTrackerProperties.reappearClassesNormalized: Set<String>`, `fun ObjectTrackerProperties.reappearAllows(className: String): Boolean`; `internal object ProductionYamlBinder` with `fun <T : Any> bind(prefix: String, type: Class<T>, env: Map<String, Any> = emptyMap(), properties: Map<String, Any> = emptyMap()): T`.

- [ ] **Step 1: Write the failing tests**

Append to `modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/impl/ObjectTrackerServiceImplTest.kt`, just before the closing brace of the class:

```kotlin
    /** The tuned prod shape plus the allow-list: only a person's *return* is an event. */
    private val personOnlyProps =
        ObjectTrackerProperties(
            ttl = Duration.ofHours(12),
            reappearGap = Duration.ofHours(1),
            cleanupRetention = Duration.ofHours(48),
            reappearClasses = listOf("person"),
        )

    @Test
    fun `a class outside reappear-classes does not produce a reappearance`() =
        runTest {
            // Group A of the production run: a cow standing in the same spot, lost by the detector
            // at night and found again at dawn. The absence is real and observed — only the class
            // says it is not an event.
            val svc = ObjectTrackerServiceImpl(repo, uuid, clock, personOnlyProps, transactionalOperator)
            val absentSince = fixedNow.minus(Duration.ofHours(8))
            svc.watchFrom(absentSince)
            val existing = track("cow", 0f, 0f, 0.5f, 0.5f, lastSeen = absentSince)
            coEvery { repo.findActive(any(), any(), any()) } returns listOf(existing)

            val delta = svc.evaluate(rec(), listOf(det("cow", 0.01f, 0.0f, 0.51f, 0.5f)))

            // Matched and updated as always — only the notification-worthy verdict changes, so the
            // decision service falls through to ALL_REPEATED on its own.
            assertEquals(1, delta.matchedTracksCount)
            assertEquals(0, delta.reappearedTracksCount)
            assertTrue(delta.reappearedClasses.isEmpty())
            coVerify(exactly = 1) { repo.updateOnMatch(existing.id!!, any(), any(), any(), any(), any(), recId) }
        }

    @Test
    fun `a class outside reappear-classes still notifies the first time it is seen`() =
        runTest {
            // The distinction the whole feature rests on: "a new cow" is an event, "the cow is
            // back" is not. Nothing here touches DETECTION_FILTER_CLASSES.
            val svc = ObjectTrackerServiceImpl(repo, uuid, clock, personOnlyProps, transactionalOperator)
            coEvery { repo.findActive(any(), any(), any()) } returns emptyList()
            coEvery { uuid.generateV1() } returns UUID.randomUUID()

            val delta = svc.evaluate(rec(), listOf(det("cow", 0f, 0f, 0.5f, 0.5f)))

            assertEquals(1, delta.newTracksCount)
            assertEquals(listOf("cow"), delta.newClasses)
        }

    @Test
    fun `a class inside reappear-classes reappears as before`() =
        runTest {
            val svc = ObjectTrackerServiceImpl(repo, uuid, clock, personOnlyProps, transactionalOperator)
            val absentSince = fixedNow.minus(Duration.ofHours(8))
            svc.watchFrom(absentSince)
            val existing = track("person", 0f, 0f, 0.5f, 0.5f, lastSeen = absentSince)
            coEvery { repo.findActive(any(), any(), any()) } returns listOf(existing)

            val delta = svc.evaluate(rec(), listOf(det("person", 0.01f, 0.0f, 0.51f, 0.5f)))

            assertEquals(1, delta.reappearedTracksCount)
            assertEquals(listOf("person"), delta.reappearedClasses)
        }

    @Test
    fun `an empty reappear-classes list lets every class reappear`() =
        runTest {
            // The documented default, pinned explicitly: longTtlProps leaves the list empty.
            val svc = ObjectTrackerServiceImpl(repo, uuid, clock, longTtlProps, transactionalOperator)
            val absentSince = fixedNow.minus(Duration.ofHours(8))
            svc.watchFrom(absentSince)
            val existing = track("cow", 0f, 0f, 0.5f, 0.5f, lastSeen = absentSince)
            coEvery { repo.findActive(any(), any(), any()) } returns listOf(existing)

            val delta = svc.evaluate(rec(), listOf(det("cow", 0.01f, 0.0f, 0.51f, 0.5f)))

            assertEquals(1, delta.reappearedTracksCount)
            assertEquals(listOf("cow"), delta.reappearedClasses)
        }

    @Test
    fun `reappear-classes matching ignores case, surrounding space and empty entries`() =
        runTest {
            // The value arrives from a comma-separated env variable, so all three are ordinary.
            val props =
                ObjectTrackerProperties(
                    ttl = Duration.ofHours(12),
                    reappearGap = Duration.ofHours(1),
                    cleanupRetention = Duration.ofHours(48),
                    reappearClasses = listOf("  PERSON  ", "", "   "),
                )
            val svc = ObjectTrackerServiceImpl(repo, uuid, clock, props, transactionalOperator)
            val absentSince = fixedNow.minus(Duration.ofHours(8))
            svc.watchFrom(absentSince)
            val existing = track("person", 0f, 0f, 0.5f, 0.5f, lastSeen = absentSince)
            coEvery { repo.findActive(any(), any(), any()) } returns listOf(existing)

            val delta = svc.evaluate(rec(), listOf(det("person", 0.01f, 0.0f, 0.51f, 0.5f)))

            assertEquals(1, delta.reappearedTracksCount)
        }

    @Test
    fun `a reappear-classes list of nothing but blanks is rejected at construction`() {
        // Normalizing it away would silently mean "all classes" — the exact opposite of the intent
        // behind setting the variable at all.
        assertFailsWith<IllegalArgumentException> {
            ObjectTrackerProperties(reappearClasses = listOf(" ", ""))
        }
    }

    @Test
    fun `an unobserved absence stays unobserved regardless of the class filter`() =
        runTest {
            // The watch-window guard is the more fundamental one and is checked first: an absence
            // nobody watched is not evidence of anything, filtered class or not.
            val svc = ObjectTrackerServiceImpl(repo, uuid, clock, personOnlyProps, transactionalOperator)
            val existing = track("cow", 0f, 0f, 0.5f, 0.5f, lastSeen = fixedNow.minus(Duration.ofHours(8)))
            coEvery { repo.findActive(any(), any(), any()) } returns listOf(existing)

            val delta = svc.evaluate(rec(), listOf(det("cow", 0.01f, 0.0f, 0.51f, 0.5f)))

            assertEquals(1, delta.matchedTracksCount)
            assertEquals(0, delta.reappearedTracksCount)
        }
```

Append to `modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/impl/TrackerSummaryTest.kt`, inside the class:

```kotlin
    @Test
    fun `render lists class-filtered reappearances separately from the ones that fired`() {
        val rendered =
            summary(
                matched = 2,
                reappeared = listOf(ClassAbsence("person", Duration.ofHours(3))),
                classFiltered = listOf(ClassAbsence("cow", Duration.ofHours(8))),
                maxAbsence = Duration.ofHours(8),
            ).render()

        assertTrue(rendered.contains("reappeared=[person:PT3H]"), rendered)
        assertTrue(rendered.contains("classFiltered=[cow:PT8H]"), rendered)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

`./gradlew :frigate-analyzer-service:test --tests '*ObjectTrackerServiceImplTest*' --tests '*TrackerSummaryTest*'` via `claude-forge:build-runner`.
Expected: FAIL — `No value passed for parameter` / `Unresolved reference: reappearClasses`.

- [ ] **Step 3: Add the property**

In `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/config/ObjectTrackerProperties.kt`, add a constructor parameter after `reappearGap` (line 36), keeping the trailing comma:

```kotlin
    /**
     * Classes allowed to produce a REAPPEARANCE notification. Empty — the default — means all of
     * them, which is what keeps this a no-op out of the box.
     *
     * Gates *returns* only. A class left out still notifies the first time it is seen: "a new cow"
     * is an event, "the cow is back" is not. Deliberately separate from
     * `application.detection-filter.allowed-classes`, which decides what is detected at all and
     * must not be narrowed to achieve this.
     *
     * Static objects are what it exists for. A bicycle the detector loses at dusk and finds again
     * at dawn shows an absence that no [reappearGap] can tell apart from a person who left in the
     * evening and came back in the morning — the durations are the same. The class can.
     */
    val reappearClasses: List<String> = emptyList(),
```

Then, **before** the existing `init` block (property initializers and `init` blocks run in declaration order, so this must come first), add:

```kotlin
    /** [reappearClasses] prepared for lookup: trimmed, lower-cased, blanks dropped. */
    val reappearClassesNormalized: Set<String> =
        reappearClasses.mapNotNull { it.trim().lowercase().ifEmpty { null } }.toSet()

    /** `true` when [className] may produce a reappearance. An empty list allows every class. */
    fun reappearAllows(className: String): Boolean =
        reappearClassesNormalized.isEmpty() || className.trim().lowercase() in reappearClassesNormalized
```

And inside the existing `init` block, append:

```kotlin
        require(reappearClasses.isEmpty() || reappearClassesNormalized.isNotEmpty()) {
            "application.notifications.tracker.reappear-classes was set but holds no usable class " +
                "name; an all-blank list would silently mean \"every class\", got $reappearClasses"
        }
```

- [ ] **Step 4: Filter reappearances in the tracker**

In `ObjectTrackerServiceImpl.evaluateLocked`, add the collector next to the others (introduced in Task 1):

```kotlin
        val classFiltered = mutableListOf<ClassAbsence>()
```

Replace the reappearance registration written in Task 1:

```kotlin
                    if (lastSeen.isBefore(watchedSince)) {
                        unobservedAbsences++
                    } else {
                        reappeared += ClassAbsence(bbox.className, absence)
                    }
```

with:

```kotlin
                    if (lastSeen.isBefore(watchedSince)) {
                        unobservedAbsences++
                    } else if (!properties.reappearAllows(bbox.className)) {
                        // Dropped here rather than in the decision service so reappearedTracksCount
                        // never grows and the decision falls through to ALL_REPEATED on its own.
                        // The track itself is matched and advanced exactly as before.
                        classFiltered += ClassAbsence(bbox.className, absence)
                    } else {
                        reappeared += ClassAbsence(bbox.className, absence)
                    }
```

and pass the list to the summary, replacing `classFiltered = emptyList(),` with:

```kotlin
                classFiltered = classFiltered,
```

- [ ] **Step 5: Run the tests to verify they pass**

`./gradlew :frigate-analyzer-service:test` via `claude-forge:build-runner`.
Expected: PASS.

- [ ] **Step 6: Add the yaml placeholder**

In `modules/core/src/main/resources/application.yaml`, after the `reappear-gap:` line (line 67), add:

```yaml
      # Classes allowed to notify when they come *back*. Empty (the default) means all of them,
      # which keeps this a no-op. Narrowing it never affects first-time appearances, and it is
      # unrelated to application.detection-filter.allowed-classes.
      reappear-classes: ${NOTIFICATIONS_TRACK_REAPPEAR_CLASSES:}
```

- [ ] **Step 7: Extract the shared binding harness**

Create `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/properties/ProductionYamlBinder.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.config.properties

import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.core.io.FileSystemResource
import java.io.File

/**
 * Binds a `@ConfigurationProperties` type out of the production `src/main/resources/application.yaml`.
 *
 * Nothing else reads that file. The test classpath carries its own `application.yaml`, which shadows
 * it — deliberately, since that is what keeps signal-loss inert in integration tests (see the
 * `SIGNAL_LOSS_ENABLED` note in `.claude/rules/configuration.md`) — so every placeholder in the
 * production file is otherwise evaluated for the first time when production starts. These tests are
 * where a defaulting mistake is caught instead.
 *
 * [env] is exposed as a [SystemEnvironmentPropertySource] on purpose: that type is what makes
 * `APPLICATION_NOTIFICATIONS_TRACKER_TTL` answer a lookup for
 * `application.notifications.tracker.ttl`, and a plain map would not. [properties] stands in for
 * anything that contributes the property under its canonical name — a profile yaml, a CLI argument,
 * a system property. Both outrank the yaml, as they do at startup.
 */
internal object ProductionYamlBinder {
    fun <T : Any> bind(
        prefix: String,
        type: Class<T>,
        env: Map<String, Any> = emptyMap(),
        properties: Map<String, Any> = emptyMap(),
    ): T {
        val environment = StandardEnvironment()
        // Hermetic: whatever this machine happens to export must not reach the assertions.
        environment.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
        environment.propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
        if (properties.isNotEmpty()) {
            environment.propertySources.addFirst(MapPropertySource("profile-yaml-or-cli", properties))
        }
        if (env.isNotEmpty()) {
            environment.propertySources.addFirst(
                SystemEnvironmentPropertySource(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    env,
                ),
            )
        }
        productionYaml().forEach { environment.propertySources.addLast(it) }
        return Binder.get(environment).bind(prefix, type).get()
    }

    /** Gradle runs tests with the module directory as the working directory. */
    private fun productionYaml() =
        File("src/main/resources/application.yaml")
            .also { check(it.isFile) { "Expected the production yaml at ${it.absolutePath}" } }
            .let { YamlPropertySourceLoader().load("production-application.yaml", FileSystemResource(it)) }
}
```

- [ ] **Step 8: Point the existing binding test at the shared harness and cover `reappear-classes`**

In `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/properties/ObjectTrackerPropertiesBindingTest.kt`:

Replace the class KDoc (lines 15-27) with the trimmed version — the harness explanation now lives on `ProductionYamlBinder`:

```kotlin
/**
 * Binds `application.notifications.tracker` out of the production yaml via [ProductionYamlBinder].
 *
 * What is pinned here is `reappear-gap` defaulting to `ttl`. Its default references the resolved
 * property, so it follows `ttl` from whichever source sets it; the `$NOTIFICATIONS_TRACK_TTL` form
 * it replaced only followed that one variable and silently fell back to PT30M otherwise — enabling
 * reappearance detection with a threshold nobody configured. `reappear-classes` is pinned for the
 * same class of mistake: it must arrive empty when unset, since a non-empty default would silence
 * reappearances nobody asked to silence.
 */
```

Replace the `bind` function and the companion object (lines 103-141) with:

```kotlin
    private fun bind(
        env: Map<String, Any> = emptyMap(),
        properties: Map<String, Any> = emptyMap(),
    ): ObjectTrackerProperties =
        ProductionYamlBinder.bind(PREFIX, ObjectTrackerProperties::class.java, env, properties)

    private companion object {
        const val PREFIX = "application.notifications.tracker"
    }
```

Remove the now-unused imports: `org.springframework.boot.context.properties.bind.Binder`, `org.springframework.boot.env.YamlPropertySourceLoader`, `org.springframework.core.env.MapPropertySource`, `org.springframework.core.env.StandardEnvironment`, `org.springframework.core.env.SystemEnvironmentPropertySource`, `org.springframework.core.io.FileSystemResource`, `java.io.File`.

Add these tests to the class:

```kotlin
    @Test
    fun `with nothing set, reappear-classes is empty and every class may reappear`() {
        val props = bind()

        assertThat(props.reappearClasses).isEmpty()
        assertThat(props.reappearAllows("cow")).isTrue()
    }

    @Test
    fun `reappear-classes binds a comma-separated variable and normalizes it`() {
        val props = bind(env = mapOf("NOTIFICATIONS_TRACK_REAPPEAR_CLASSES" to "person, Cow "))

        assertThat(props.reappearClassesNormalized).containsExactlyInAnyOrder("person", "cow")
        assertThat(props.reappearAllows("PERSON")).isTrue()
        assertThat(props.reappearAllows("bicycle")).isFalse()
    }

    @Test
    fun `an explicitly empty variable binds the same as an unset one`() {
        // A different path through the binder than the test above: docker compose and systemd both
        // export `NOTIFICATIONS_TRACK_REAPPEAR_CLASSES=` as a present-but-empty variable, where the
        // unset case never reaches the environment at all and resolves through the yaml default.
        // Both have to end at an empty list — anything else means the container fails to start on a
        // configuration that asked for nothing.
        val props = bind(env = mapOf("NOTIFICATIONS_TRACK_REAPPEAR_CLASSES" to ""))

        assertThat(props.reappearClasses).isEmpty()
        assertThat(props.reappearAllows("cow")).isTrue()
    }
```

- [ ] **Step 8a: Pin what the delimited converter does with an all-blank variable**

The `require` added in Step 3 rejects a list holding nothing usable, and
`a reappear-classes list of nothing but blanks is rejected at construction` proves that at the
**constructor** level. Whether an operator's typo can ever reach it is a different question, decided
by the delimited-string converter: if it trims and drops blank elements, `" , "` arrives as an empty
list, the `require` passes, and the deployment silently gets "every class" — the exact outcome the
fail-fast was written to prevent.

Add to `ObjectTrackerPropertiesBindingTest`:

```kotlin
    @Test
    fun `an all-blank variable is rejected rather than silently meaning every class`() {
        assertThatThrownBy { bind(env = mapOf("NOTIFICATIONS_TRACK_REAPPEAR_CLASSES" to " , ")) }
            .hasRootCauseInstanceOf(IllegalArgumentException::class.java)
    }
```

**Do not bend this test to match whatever the converter happens to do.** If it fails because the
converter swallows the blanks, the fail-fast is unreachable from the environment path: leave the
assertion as the statement of intent, report it to the user, and let them decide between moving the
check (e.g. rejecting a non-blank-but-unusable raw string) and accepting that only a programmatic
constructor call can trip it. Bending it would turn a real gap into a green test.

- [ ] **Step 9: Run the binding tests**

`./gradlew :frigate-analyzer-core:test --tests '*ObjectTrackerPropertiesBindingTest*'` via `claude-forge:build-runner`.
Expected: PASS.

- [ ] **Step 10: Document the variable**

In `docker/deploy/.env.example`, append to the tracker block (after the commented `NOTIFICATIONS_TRACK_REAPPEAR_GAP` line):

```bash

# Classes allowed to notify when they come *back*. Empty (the default) = all of them. Narrowing
# this leaves first-time appearances alone: "a new cow" still notifies, "the cow is back" stops.
# Unrelated to DETECTION_FILTER_CLASSES, which decides what is detected at all.
# NOTIFICATIONS_TRACK_REAPPEAR_CLASSES=person
```

In `.claude/rules/configuration.md`, add a row at the end of the Notifications table (after the `NOTIFICATIONS_TRACK_REAPPEAR_GAP` row):

```markdown
| `NOTIFICATIONS_TRACK_REAPPEAR_CLASSES` | (empty) | Comma-separated classes allowed to notify as `REAPPEARED`. Empty = all classes (no-op). Matching is case-insensitive and trims; blank entries are ignored, but a list of nothing but blanks fails at binding. Does **not** affect `NEW_OBJECTS` — a class left out still notifies the first time it is seen. Unrelated to `DETECTION_FILTER_CLASSES`. |
```

- [ ] **Step 11: Commit**

```bash
git add modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/config/ObjectTrackerProperties.kt \
        modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/ObjectTrackerServiceImpl.kt \
        modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/impl/ObjectTrackerServiceImplTest.kt \
        modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/impl/TrackerSummaryTest.kt \
        modules/core/src/main/resources/application.yaml \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/properties/ProductionYamlBinder.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/properties/ObjectTrackerPropertiesBindingTest.kt \
        docker/deploy/.env.example \
        .claude/rules/configuration.md
# Path-scoped, as Global Constraints require. Repeat the paths literally rather than via a shell
# variable: each command may be run as its own Bash call, and shell state does not survive between them.
git commit -m "feat(tracker): restrict reappearance notifications to chosen classes

A static object lost at dusk and found at dawn is indistinguishable, by
absence duration alone, from a visitor who left in the evening and returned
in the morning — no reappear-gap separates them. NOTIFICATIONS_TRACK_REAPPEAR_CLASSES
does, without touching what is detected or what notifies as new. Empty by
default, so nothing changes until it is set." \
    -- modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/config/ObjectTrackerProperties.kt \
       modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/ObjectTrackerServiceImpl.kt \
       modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/impl/ObjectTrackerServiceImplTest.kt \
       modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/impl/TrackerSummaryTest.kt \
       modules/core/src/main/resources/application.yaml \
       modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/properties/ProductionYamlBinder.kt \
       modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/properties/ObjectTrackerPropertiesBindingTest.kt \
       docker/deploy/.env.example \
       .claude/rules/configuration.md
git show --stat HEAD   # must list exactly the nine files above, nothing else
```

---

### Task 3: Per-camera cooldown on reappearance notifications

Spec task 1. Collapses group B — one pass through a frame saturated with stale tracks matching them one after another, four notifications in 24 seconds.

**Files:**
- Create: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/config/NotificationCooldownProperties.kt`
- Modify: `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/NotificationDecision.kt`
- Modify: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/NotificationDecisionServiceImpl.kt`
- Modify: `modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/impl/NotificationDecisionServiceImplTest.kt`
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/FrigateAnalyzerApplication.kt`
- Modify: `modules/core/src/main/resources/application.yaml`
- Create: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/properties/NotificationCooldownPropertiesBindingTest.kt`
- Modify: `docker/deploy/.env.example`
- Modify: `.claude/rules/configuration.md`, `.claude/rules/telegram-notifications.md`

**Interfaces:**
- Consumes: `ProductionYamlBinder.bind(prefix, type, env, properties)` from Task 2.
- Produces: `NotificationCooldownProperties(reappear: Duration = Duration.ZERO)` with `val reappearEnabled: Boolean`; `NotificationDecisionReason.COOLDOWN`; `NotificationDecisionServiceImpl(tracker, settings, scheduleService, cooldown)` — a fourth constructor parameter with no default.

- [ ] **Step 1: Write the failing tests**

In `modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/impl/NotificationDecisionServiceImplTest.kt`, add two imports — `ru.zinin.frigate.analyzer.service.config.NotificationCooldownProperties` and `java.time.Duration` (`Instant` and `UUID` are already imported) — then change the service construction on line 38 to:

```kotlin
    private val service =
        NotificationDecisionServiceImpl(tracker, settings, scheduleService, NotificationCooldownProperties())
```

Add these helpers next to the existing `recording` val:

```kotlin
    /** A second recording knob: the cooldown is keyed by camera and measured on recordTimestamp. */
    private fun rec(
        camId: String = "cam",
        at: Instant = now,
    ): RecordingDto = recording.copy(id = UUID.randomUUID(), camId = camId, recordTimestamp = at)

    private fun serviceWith(cooldown: Duration) =
        NotificationDecisionServiceImpl(
            tracker,
            settings,
            scheduleService,
            NotificationCooldownProperties(reappear = cooldown),
        )

    private fun reappearance() =
        DetectionDelta(0, 1, 0, emptyList(), reappearedTracksCount = 1, reappearedClasses = listOf("person"))
```

Then append these tests to the class:

```kotlin
    @Test
    fun `with the cooldown at its default every reappearance still notifies`() =
        runTest {
            // Acceptance criterion: unconfigured behaviour must be byte-identical to v0.9.1.
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { tracker.evaluate(any(), any()) } returns reappearance()

            val first = service.evaluate(rec(at = now), listOf(det()))
            val second = service.evaluate(rec(at = now.plusSeconds(2)), listOf(det()))

            assertEquals(NotificationDecisionReason.REAPPEARED, first.reason)
            assertEquals(NotificationDecisionReason.REAPPEARED, second.reason)
            assertTrue(second.shouldNotify)
        }

    @Test
    fun `the cooldown collapses a burst of reappearances on one camera`() =
        runTest {
            // Group B of the production run: one person walking past ~107 stale person tracks,
            // matching them one after another, four notifications in 24 seconds.
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { tracker.evaluate(any(), any()) } returns reappearance()
            val svc = serviceWith(Duration.ofMinutes(5))

            val first = svc.evaluate(rec(at = now), listOf(det()))
            val burst =
                listOf(2L, 13L, 24L).map { svc.evaluate(rec(at = now.plusSeconds(it)), listOf(det())) }

            assertTrue(first.shouldNotify)
            assertTrue(burst.all { !it.shouldNotify })
            assertTrue(burst.all { it.reason == NotificationDecisionReason.COOLDOWN })
        }

    @Test
    fun `a suppressed reappearance keeps the delta so the log line stays diagnosable`() =
        runTest {
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { tracker.evaluate(any(), any()) } returns reappearance()
            val svc = serviceWith(Duration.ofMinutes(5))

            svc.evaluate(rec(at = now), listOf(det()))
            val suppressed = svc.evaluate(rec(at = now.plusSeconds(2)), listOf(det()))

            assertEquals(listOf("person"), suppressed.delta?.reappearedClasses)
        }

    @Test
    fun `the cooldown expires and the next reappearance notifies again`() =
        runTest {
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { tracker.evaluate(any(), any()) } returns reappearance()
            val svc = serviceWith(Duration.ofMinutes(5))

            svc.evaluate(rec(at = now), listOf(det()))
            val later = svc.evaluate(rec(at = now.plus(Duration.ofMinutes(6))), listOf(det()))

            assertTrue(later.shouldNotify)
            assertEquals(NotificationDecisionReason.REAPPEARED, later.reason)
        }

    @Test
    fun `the cooldown window is measured from the last notification, not slid by suppressed ones`() =
        runTest {
            // A cooldown, not a debounce: a continuous stream of reappearances must not hold the
            // gate shut forever.
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { tracker.evaluate(any(), any()) } returns reappearance()
            val svc = serviceWith(Duration.ofMinutes(5))

            svc.evaluate(rec(at = now), listOf(det()))
            svc.evaluate(rec(at = now.plus(Duration.ofMinutes(4))), listOf(det()))
            val later = svc.evaluate(rec(at = now.plus(Duration.ofMinutes(6))), listOf(det()))

            assertTrue(later.shouldNotify)
        }

    @Test
    fun `the cooldown is per camera`() =
        runTest {
            // Group B spanned cam2 and cam3 within two seconds; both must still be announced.
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { tracker.evaluate(any(), any()) } returns reappearance()
            val svc = serviceWith(Duration.ofMinutes(5))

            val cam3 = svc.evaluate(rec(camId = "cam3", at = now), listOf(det()))
            val cam2 = svc.evaluate(rec(camId = "cam2", at = now.plusSeconds(2)), listOf(det()))

            assertTrue(cam3.shouldNotify)
            assertTrue(cam2.shouldNotify)
        }

    @Test
    fun `the cooldown never gates NEW_OBJECTS`() =
        runTest {
            // A genuinely new object must not be lost because something reappeared a moment ago.
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            val svc = serviceWith(Duration.ofMinutes(5))
            coEvery { tracker.evaluate(any(), any()) } returns reappearance()
            svc.evaluate(rec(at = now), listOf(det()))

            coEvery { tracker.evaluate(any(), any()) } returns DetectionDelta(1, 0, 0, listOf("car"))
            val fresh = svc.evaluate(rec(at = now.plusSeconds(2)), listOf(det()))

            assertTrue(fresh.shouldNotify)
            assertEquals(NotificationDecisionReason.NEW_OBJECTS, fresh.reason)
        }

    @Test
    fun `a NEW_OBJECTS notification does not open or close the reappear cooldown`() =
        runTest {
            // The gate order puts NEW_OBJECTS first, so that branch never touches the anchor.
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            val svc = serviceWith(Duration.ofMinutes(5))
            coEvery { tracker.evaluate(any(), any()) } returns DetectionDelta(1, 0, 0, listOf("car"))
            svc.evaluate(rec(at = now), listOf(det()))

            coEvery { tracker.evaluate(any(), any()) } returns reappearance()
            val reappeared = svc.evaluate(rec(at = now.plusSeconds(2)), listOf(det()))

            assertTrue(reappeared.shouldNotify)
            assertEquals(NotificationDecisionReason.REAPPEARED, reappeared.reason)
        }

    @Test
    fun `a backlog drained in seconds is not collapsed, because the clock never enters the decision`() =
        runTest {
            // The reason the cooldown is measured on recordTimestamp. After a restart the queue is
            // drained newest-first with no floor on age: an hour of recordings is evaluated within
            // seconds, and a wall-clock cooldown would announce one of them and swallow the rest.
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { tracker.evaluate(any(), any()) } returns reappearance()
            val svc = serviceWith(Duration.ofMinutes(5))

            val newest = svc.evaluate(rec(at = now), listOf(det()))
            val anHourBack = svc.evaluate(rec(at = now.minus(Duration.ofHours(1))), listOf(det()))
            val twoHoursBack = svc.evaluate(rec(at = now.minus(Duration.ofHours(2))), listOf(det()))

            assertTrue(newest.shouldNotify)
            assertTrue(anHourBack.shouldNotify)
            assertTrue(twoHoursBack.shouldNotify)
        }

    @Test
    fun `a burst drained newest-first collapses just as one drained oldest-first does`() =
        runTest {
            // Same 24-second burst, arriving in the other direction — which is the direction the
            // newest-first drain actually produces. Distance is what matters, not its sign.
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { tracker.evaluate(any(), any()) } returns reappearance()
            val svc = serviceWith(Duration.ofMinutes(5))

            val newest = svc.evaluate(rec(at = now.plusSeconds(24)), listOf(det()))
            val middle = svc.evaluate(rec(at = now.plusSeconds(13)), listOf(det()))
            val oldest = svc.evaluate(rec(at = now), listOf(det()))

            assertTrue(newest.shouldNotify)
            assertEquals(NotificationDecisionReason.COOLDOWN, middle.reason)
            assertEquals(NotificationDecisionReason.COOLDOWN, oldest.reason)
        }

    @Test
    fun `an out-of-order old recording cannot reopen the window for the live stream`() =
        runTest {
            // A stuck recording re-picked after its cooldown arrives with an hours-old timestamp.
            // It is far enough away to be its own event, but the anchor must stay at the newest
            // notified recording — otherwise the live stream's next match would notify again.
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { tracker.evaluate(any(), any()) } returns reappearance()
            val svc = serviceWith(Duration.ofMinutes(5))

            svc.evaluate(rec(at = now), listOf(det()))
            val stuck = svc.evaluate(rec(at = now.minus(Duration.ofHours(6))), listOf(det()))
            val live = svc.evaluate(rec(at = now.plusSeconds(30)), listOf(det()))

            assertTrue(stuck.shouldNotify)
            assertEquals(NotificationDecisionReason.COOLDOWN, live.reason)
        }

    @Test
    fun `a suppressed reappearance still ran the tracker, so the watch window kept advancing`() =
        runTest {
            // The tracker is called before every gate on purpose. Were the cooldown to skip it,
            // the window would stop being stamped and the suppressed notification would come back
            // later as a false reappearance.
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { tracker.evaluate(any(), any()) } returns reappearance()
            val svc = serviceWith(Duration.ofMinutes(5))
            val second = rec(at = now.plusSeconds(2))

            svc.evaluate(rec(at = now), listOf(det()))
            val suppressed = svc.evaluate(second, listOf(det()))

            assertEquals(NotificationDecisionReason.COOLDOWN, suppressed.reason)
            coVerify(exactly = 1) { tracker.evaluate(second, any()) }
        }

    @Test
    fun `a detection-less recording leaves the cooldown untouched`() =
        runTest {
            // NO_DETECTIONS short-circuits above every gate, so the anchor is neither read nor
            // written. Cheap insurance: were the cooldown ever hoisted above that branch, a camera's
            // own quiet stretch would start eating its next reappearance.
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { tracker.evaluate(any(), any()) } returns reappearance()
            val svc = serviceWith(Duration.ofMinutes(5))
            svc.evaluate(rec(at = now), listOf(det()))

            val quiet = rec(at = now.plusSeconds(2))
            justRun { tracker.markObserved(quiet) }
            val silent = svc.evaluate(quiet, emptyList())
            val within = svc.evaluate(rec(at = now.plusSeconds(4)), listOf(det()))

            assertEquals(NotificationDecisionReason.NO_DETECTIONS, silent.reason)
            // The anchor still sits where the first notification put it — untouched, not refreshed.
            assertEquals(NotificationDecisionReason.COOLDOWN, within.reason)
        }

    @Test
    fun `a reappearance suppressed by the global toggle does not arm the cooldown`() =
        runTest {
            // GLOBAL_OFF sits above REAPPEARED, so the branch that writes the anchor is never
            // reached. Once the toggle is back on, the next reappearance must go out at once —
            // otherwise switching notifications off would silently swallow the first one after.
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns false
            coEvery { tracker.evaluate(any(), any()) } returns reappearance()
            val svc = serviceWith(Duration.ofMinutes(5))

            val off = svc.evaluate(rec(at = now), listOf(det()))
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            val on = svc.evaluate(rec(at = now.plusSeconds(2)), listOf(det()))

            assertEquals(NotificationDecisionReason.GLOBAL_OFF, off.reason)
            assertTrue(on.shouldNotify)
            assertEquals(NotificationDecisionReason.REAPPEARED, on.reason)
        }

    @Test
    fun `a reappearance suppressed by the schedule does not arm the cooldown`() =
        runTest {
            // Same argument one gate down. Reuses the existing nightUtc / dayUtc fixtures.
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { tracker.evaluate(any(), any()) } returns reappearance()
            val svc = serviceWith(Duration.ofMinutes(5))

            coEvery { scheduleService.getRecordingSchedule() } returns nightUtc
            val closed = svc.evaluate(rec(at = now), listOf(det()))
            coEvery { scheduleService.getRecordingSchedule() } returns dayUtc
            val open = svc.evaluate(rec(at = now.plusSeconds(2)), listOf(det()))

            assertEquals(NotificationDecisionReason.OUT_OF_SCHEDULE, closed.reason)
            assertTrue(open.shouldNotify)
        }

    @Test
    fun `a negative cooldown is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> {
            NotificationCooldownProperties(reappear = Duration.ofSeconds(-1))
        }
    }
```

`justRun` and `verify` are what the existing `empty detections short-circuit to NO_DETECTIONS` test
uses for `markObserved`, so it is not a suspend function — do not reach for `coJustRun` here.
`nightUtc` and `dayUtc` are existing fixtures in the same class.

- [ ] **Step 2: Run the tests to verify they fail**

`./gradlew :frigate-analyzer-service:test --tests '*NotificationDecisionServiceImplTest*'` via `claude-forge:build-runner`.
Expected: FAIL — `Unresolved reference: NotificationCooldownProperties`.

- [ ] **Step 3: Add the properties class**

Create `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/config/NotificationCooldownProperties.kt`:

```kotlin
package ru.zinin.frigate.analyzer.service.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/**
 * Rate limits applied to notifications that already survived every other gate.
 *
 * Kept apart from [ObjectTrackerProperties] deliberately: the tracker decides *what happened*, this
 * decides *how often that may be announced*. Nothing here changes tracker bookkeeping — a
 * suppressed notification still leaves every track advanced and the watch window stamped.
 */
@ConfigurationProperties(prefix = "application.notifications.cooldown")
@Validated
data class NotificationCooldownProperties(
    /**
     * Minimum distance between two REAPPEARED notifications for one camera.
     *
     * Measured on `RecordingDto.recordTimestamp`, never on the wall clock. The unprocessed queue is
     * drained newest-first with no floor on age, so after a restart or a stalled pipeline an hour
     * of recordings is evaluated within seconds; a wall-clock cooldown would announce one of them
     * and swallow every real event behind it.
     *
     * Exists for the burst a single pass produces under a long `ttl`: the frame accumulates stale
     * tracks of the same class along the walkway, a person matches them one after another, and each
     * of those tracks — untouched for hours — contributes its own reappearance. Pick a value above
     * the burst length (tens of seconds) and below the shortest interval between two visits worth
     * telling apart.
     *
     * Defaults to [Duration.ZERO], which disables the gate entirely.
     */
    val reappear: Duration = Duration.ZERO,
) {
    init {
        require(!reappear.isNegative) {
            "application.notifications.cooldown.reappear must be >= PT0S (PT0S disables it), got $reappear"
        }
    }

    /** `false` for the default [Duration.ZERO]: the gate is opt-in. */
    val reappearEnabled: Boolean
        get() = !reappear.isZero
}
```

- [ ] **Step 4: Add the decision reason**

In `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/NotificationDecision.kt`, insert after `REAPPEARED,`:

```kotlin

    /**
     * A reappearance, suppressed because this camera announced one less than
     * `NotificationCooldownProperties.reappear` ago in *recording* time. Collapses the burst one
     * pass produces when it matches several long-untouched tracks in a row. `NEW_OBJECTS` is never
     * gated by it, and the tracker has already run — only the announcement is dropped.
     */
    COOLDOWN,
```

- [ ] **Step 5: Implement the gate**

In `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/NotificationDecisionServiceImpl.kt`, add the imports:

```kotlin
import ru.zinin.frigate.analyzer.service.config.NotificationCooldownProperties
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
```

Add the constructor parameter and the state:

```kotlin
class NotificationDecisionServiceImpl(
    private val tracker: ObjectTrackerService,
    private val settings: AppSettingsService,
    private val scheduleService: NotificationScheduleService,
    private val cooldown: NotificationCooldownProperties,
) : NotificationDecisionService {
    /**
     * Newest `recordTimestamp` a REAPPEARED notification has gone out for, per camera.
     *
     * In-memory on purpose: the horizon is minutes, the camera set is small, and losing the map on
     * restart costs at most one extra notification. Written only when this service decides to
     * notify — updating it on suppression too would turn the cooldown into a debounce that a
     * continuous stream of reappearances could hold shut forever.
     *
     * Never evicted. One entry per camera id ever seen, so a renamed or retired camera leaves a
     * stale entry behind; with a camera set in the single digits that is a few hundred bytes for the
     * process lifetime, and any eviction policy would cost more than it saves.
     *
     * "Decides to notify" is not "delivered": [ru.zinin.frigate.analyzer.core.facade.RecordingProcessingFacade]
     * sends after `evaluate` returns and swallows a send failure with a log line, by which point the
     * anchor is already advanced. A failed send therefore also mutes the camera's reappearances for
     * the length of the cooldown — the one fail-closed spot in a subsystem that is otherwise
     * uniformly fail-open. Accepted: sending is an enqueue onto the bot's own queue, so the window is
     * both rare and short. Confirming delivery back into the decision would invert the dependency
     * between the two layers, which is well outside what a cooldown is worth.
     */
    private val lastReappearNotified = ConcurrentHashMap<String, Instant>()
```

Replace the `delta.reappearedTracksCount > 0` branch (lines 75-81) with:

```kotlin
                delta.reappearedTracksCount > 0 -> {
                    val sinceLast = reappearCooldownGap(recording)
                    if (sinceLast != null) {
                        logger.debug {
                            "Decision: suppress (cooldown): cam=${recording.camId} " +
                                "sinceLast=$sinceLast recording=${recording.id}"
                        }
                        NotificationDecision(false, NotificationDecisionReason.COOLDOWN, delta)
                    } else {
                        rememberReappearNotified(recording)
                        logger.info {
                            "Decision: notify (reappeared): cam=${recording.camId} " +
                                "reappearedClasses=${delta.reappearedClasses} recording=${recording.id}"
                        }
                        NotificationDecision(true, NotificationDecisionReason.REAPPEARED, delta)
                    }
                }
```

Add both helpers below `evaluate`, above `isRecordingNotificationsGloballyEnabled`:

```kotlin
    /**
     * Distance from this camera's last announced reappearance to [recording] while the cooldown
     * still covers it; `null` when the notification may go out.
     *
     * Signed for the log line, compared by absolute value. A burst is drained in whichever
     * direction the queue hands it over — newest-first is the normal case — and both directions
     * describe the same 24 seconds of one person walking past. Distance is also what keeps a
     * backlog intact: a recording hours from the anchor is a separate event on either side of it,
     * which is precisely what a wall-clock cooldown could not express.
     */
    private fun reappearCooldownGap(recording: RecordingDto): Duration? {
        if (!cooldown.reappearEnabled) return null
        val last = lastReappearNotified[recording.camId] ?: return null
        val sinceLast = Duration.between(last, recording.recordTimestamp)
        return sinceLast.takeIf { it.abs() < cooldown.reappear }
    }

    /**
     * Anchors the next window at the newest announced recording rather than the latest one seen: a
     * stuck recording re-picked with an hours-old timestamp is far enough away to be its own event,
     * but it must not drag the anchor backwards and let the live stream notify again immediately.
     */
    private fun rememberReappearNotified(recording: RecordingDto) {
        if (!cooldown.reappearEnabled) return
        lastReappearNotified.merge(recording.camId, recording.recordTimestamp) { old, new -> maxOf(old, new) }
    }
```

- [ ] **Step 6: Run the tests to verify they pass**

`./gradlew :frigate-analyzer-service:test` via `claude-forge:build-runner`.
Expected: PASS.

- [ ] **Step 7: Register the properties bean and the yaml placeholder**

In `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/FrigateAnalyzerApplication.kt`, add the import `import ru.zinin.frigate.analyzer.service.config.NotificationCooldownProperties` and the entry `NotificationCooldownProperties::class,` to `@EnableConfigurationProperties`, after `ObjectTrackerProperties::class,`.

In `modules/core/src/main/resources/application.yaml`, insert between `  notifications:` (line 54) and `    tracker:` (line 55):

```yaml
    cooldown:
      # Minimum distance between two REAPPEARED notifications for one camera, measured on the
      # recording timestamp rather than the wall clock — a backlog drained newest-first would
      # otherwise collapse into a single notification. PT0S (the default) disables the gate.
      reappear: ${NOTIFICATIONS_COOLDOWN_REAPPEAR:PT0S}
```

- [ ] **Step 8: Write the binding test**

Create `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/properties/NotificationCooldownPropertiesBindingTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.config.properties

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.service.config.NotificationCooldownProperties
import java.time.Duration

/**
 * Binds `application.notifications.cooldown` out of the production yaml via [ProductionYamlBinder].
 *
 * The property this pins is the disabled default. The gate silences notifications, so a placeholder
 * that ever resolved to something other than `PT0S` would drop real events on a deployment that
 * never asked for a cooldown — and the production yaml is otherwise first evaluated in production.
 */
class NotificationCooldownPropertiesBindingTest {
    @Test
    fun `with nothing set the reappear cooldown is disabled`() {
        val props = bind()

        assertThat(props.reappear).isEqualTo(Duration.ZERO)
        assertThat(props.reappearEnabled).isFalse()
    }

    @Test
    fun `NOTIFICATIONS_COOLDOWN_REAPPEAR enables it`() {
        val props = bind(env = mapOf("NOTIFICATIONS_COOLDOWN_REAPPEAR" to "PT5M"))

        assertThat(props.reappear).isEqualTo(Duration.ofMinutes(5))
        assertThat(props.reappearEnabled).isTrue()
    }

    @Test
    fun `the relaxed variable name works too`() {
        val props = bind(env = mapOf("APPLICATION_NOTIFICATIONS_COOLDOWN_REAPPEAR" to "PT5M"))

        assertThat(props.reappear).isEqualTo(Duration.ofMinutes(5))
    }

    private fun bind(env: Map<String, Any> = emptyMap()): NotificationCooldownProperties =
        ProductionYamlBinder.bind(
            "application.notifications.cooldown",
            NotificationCooldownProperties::class.java,
            env,
        )
}
```

- [ ] **Step 9: Run the core tests**

`./gradlew :frigate-analyzer-core:test` via `claude-forge:build-runner`.
Expected: PASS.

- [ ] **Step 10: Document the variable**

In `docker/deploy/.env.example`, append after the tracker block:

```bash

# --- Notification cooldown ---
# Minimum distance between two REAPPEARED notifications for one camera, measured on recording
# time (not the wall clock, so a backlog is never collapsed). PT0S (the default) disables it.
# One pass through a frame full of stale tracks matches several of them in a row and each match
# is its own reappearance; this collapses that burst into one notification per camera.
# NOTIFICATIONS_COOLDOWN_REAPPEAR=PT5M
```

In `.claude/rules/configuration.md`, add a row at the end of the Notifications table:

```markdown
| `NOTIFICATIONS_COOLDOWN_REAPPEAR` | PT0S | Minimum distance between two `REAPPEARED` notifications for one camera; extra ones are suppressed with reason `COOLDOWN`. `PT0S` (default) disables it. Measured on `recordTimestamp`, so a backlog drained newest-first is not collapsed. Does not gate `NEW_OBJECTS`, and never skips the tracker. |

Two things about that row surprise operators the first time and are worth stating outright:

- **`sinceLast` in the suppress line can be negative** (`sinceLast=PT-11S`). The distance is compared
  by absolute value, and the queue drains newest-first, so the recording being judged is often *older*
  than the one that anchored the window. A minus sign there is normal, not a bug.
- **A recording far older than the anchor notifies again.** The anchor holds the newest announced
  `recordTimestamp` per camera, and anything outside the window on either side counts as its own
  event. While a large backlog is being drained this can produce two notifications in quick
  succession — that is the deliberate trade-off which keeps a backlog from collapsing into a single
  notification, which is what a wall-clock cooldown would do.
```

In `.claude/rules/telegram-notifications.md`, add to the "Consumers" list after the "Detection schedule" bullet:

```markdown
- **Reappearance cooldown** — `NotificationDecisionServiceImpl` suppresses a `REAPPEARED`
  notification with reason `COOLDOWN` when the same camera announced one less than
  `NOTIFICATIONS_COOLDOWN_REAPPEAR` ago, measured on `recording.recordTimestamp` rather than the
  wall clock (a backlog drained newest-first would otherwise collapse into one notification). The
  anchor is the newest announced recording per camera, held in memory only. `NEW_OBJECTS` is never
  gated by it, and the tracker has already run by the time the gate is reached — suppression drops
  the announcement, never the bookkeeping. Disabled by default.
```

- [ ] **Step 11: Commit**

```bash
git add modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/config/NotificationCooldownProperties.kt \
        modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/NotificationDecision.kt \
        modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/NotificationDecisionServiceImpl.kt \
        modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/impl/NotificationDecisionServiceImplTest.kt \
        modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/FrigateAnalyzerApplication.kt \
        modules/core/src/main/resources/application.yaml \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/properties/NotificationCooldownPropertiesBindingTest.kt \
        docker/deploy/.env.example \
        .claude/rules/configuration.md \
        .claude/rules/telegram-notifications.md
# Path-scoped, as Global Constraints require.
git commit -m "feat(notifications): cool down repeated reappearance notifications per camera

One person walking past a frame saturated with stale tracks matches them one
after another, and each of those tracks — untouched for hours — is its own
reappearance: four notifications in 24 seconds during the production run.
NOTIFICATIONS_COOLDOWN_REAPPEAR collapses the burst. Measured on
recordTimestamp so a backlog drained newest-first is not collapsed with it,
and applied to REAPPEARED only. PT0S by default." \
    -- modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/config/NotificationCooldownProperties.kt \
       modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/NotificationDecision.kt \
       modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/NotificationDecisionServiceImpl.kt \
       modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/impl/NotificationDecisionServiceImplTest.kt \
       modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/FrigateAnalyzerApplication.kt \
       modules/core/src/main/resources/application.yaml \
       modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/properties/NotificationCooldownPropertiesBindingTest.kt \
       docker/deploy/.env.example \
       .claude/rules/configuration.md \
       .claude/rules/telegram-notifications.md
git show --stat HEAD   # must list exactly the ten files above, nothing else
```

---

### Task 4: Tuning guide

The three knobs are only useful together, and the narrative that ties them to the data in the debug line does not belong to any one of them.

**Files:**
- Modify: `.claude/rules/configuration.md` — the "Tuning REAPPEAR_GAP under a long TTL" section.

- [ ] **Step 1: Extend the tuning section**

In `.claude/rules/configuration.md`, after the existing `### Tuning REAPPEAR_GAP under a long TTL`
section. Note that the section does **not** end at the three-variable code block: a paragraph about
per-user and global notification toggles follows it and belongs to the enclosing `## Notifications`
section, not to the tuning narrative. Insert **after that paragraph**, at the end of the file —
inserting straight after the code block would file the toggles paragraph under the new
`### Reading the tracker's debug line` heading, where it makes no sense.

Add:

````markdown
### When REAPPEAR_GAP alone cannot help

A production run under `TTL=PT12H`, `REAPPEAR_GAP=PT1H` across three cameras produced 9
reappearance notifications overnight against 5156 suppressions. Only one was worth sending. The
other eight came from two mechanisms the gap cannot separate:

- **Static objects flickering.** A bicycle, a parked car, a cow standing still. The detector loses
  them at night and finds them again at dawn; the absence crosses any reasonable gap. Raising the
  threshold far enough to cover a whole night (~`PT10H`) makes it meet `TTL`, at which point the
  feature is off by construction. An 8-hour absence of a motionless bicycle is *the same duration*
  as a person who left in the evening and came back in the morning — no single threshold splits
  them. `NOTIFICATIONS_TRACK_REAPPEAR_CLASSES` splits them by class instead.
- **Bursts from one pass.** Under a long TTL the frame accumulates stale tracks of the same class
  along a walkway; a person crossing it matches them one after another, and each of those tracks
  has been untouched for hours, so each produces its own reappearance. Every one of them clears any
  threshold, so the gap has no effect at all. `NOTIFICATIONS_COOLDOWN_REAPPEAR` collapses them.

Raising `NOTIFICATIONS_TRACK_CONFIDENCE_FLOOR` against the flicker makes things **worse**: the weak
night-time detections it discards are what kept the static object's absences short, so dropping
them lengthens every absence and produces more reappearances, not fewer.

### Reading the tracker's debug line

`ObjectTrackerServiceImpl` logs one line per interesting recording at DEBUG:

```
ObjectTracker: cam=cam2 new=0 matched=3 reappeared=[person:PT3H12M] classFiltered=[cow:PT8H2M] unobserved=0 stale=107 maxAbsence=PT8H2M (recording=<uuid>)
```

The format changed in this release and **breaks existing greps**: `reappeared=` used to carry a count
(`reappeared=1`), so a pattern like `reappeared=[1-9]` now matches nothing. `maxAbsence` is new.

- `maxAbsence` — the largest absence among **all** matched tracks, including those that stayed
  below `REAPPEAR_GAP`. This is the number to tune the gap against: it shows where the boundary
  currently runs and what raising the threshold would start catching. `n/a` when nothing matched.
- `reappeared=[class:duration]` — the reappearances that fired, each with its own absence.
- `classFiltered=[class:duration]` — absences past the gap that `REAPPEAR_CLASSES` kept quiet. The
  line is emitted for these too, so a filtered deployment still shows what it is suppressing.
- `unobserved=N` — absences discarded because the tracker was not watching when they began.

The line stays off ordinary recordings: it is emitted only when something new appeared, something
reappeared, something was filtered, or something was unobserved.

None of it is visible at the default level. `log4j2.yaml.example` ships `ru.zinin` at `info`, so a
deployment built from the example configuration logs nothing of the above. Turn the two classes up
explicitly — `application-docker.yaml` is gitignored, so this has to be done per host and cannot be
assumed to be in place:

```yaml
logging:
  level:
    ru.zinin.frigate.analyzer.service.impl.ObjectTrackerServiceImpl: DEBUG
    ru.zinin.frigate.analyzer.service.impl.NotificationDecisionServiceImpl: DEBUG
```

Keep it to those two loggers. `ru.zinin: DEBUG` across the board buries the tracker line under
per-frame detection output, which is the opposite of what the tuning pass needs.

Collect a night of these lines before choosing values, then set the two noise knobs from what they
show:

```
NOTIFICATIONS_TRACK_REAPPEAR_CLASSES=person
NOTIFICATIONS_COOLDOWN_REAPPEAR=PT5M
```
````

- [ ] **Step 1a: Fix the `DETECTION_FILTER_CLASSES` row while in the file**

The Detection Filter table lists the default as
`person,car,motorcycle,truck,bicycle,cat,dog,bird,backpack,umbrella`. `application.yaml:102` actually
ships `person,car,motorcycle,truck,bicycle,cat,dog,bird,backpack,horse,sheep,cow,bear,elephant,zebra,giraffe`
— `umbrella` is not in it and the seven animal classes are missing from the docs. That row is
load-bearing for this task: the whole reappearance-class argument rests on cows being detected and
notified as new, and the documentation currently says they are not detected at all. Correct it to
match the yaml.

- [ ] **Step 2: Commit**

```bash
git add .claude/rules/configuration.md
git commit -m "docs: explain what REAPPEAR_GAP cannot fix and how to read the tracker line" \
    -- .claude/rules/configuration.md
git show --stat HEAD   # must list only .claude/rules/configuration.md
```

---

## Verification

- [ ] **Step 1: Full review before the build**

Dispatch the `superpowers:code-reviewer` agent over the branch diff (`git diff master...HEAD`). Fix every critical finding and re-run the agent until clean. Per `CLAUDE.md` this comes **before** the build.

- [ ] **Step 2: Full build**

Dispatch `claude-forge:build-runner` with `./gradlew build`. On ktlint failures run `./gradlew ktlintFormat`, `git add` the reformatted files, and retry.

Expected: BUILD SUCCESSFUL, ktlint clean, test count `726 + new tests`, 0 failures, 1 skipped.

- [ ] **Step 3: Confirm the acceptance criteria**

Walk the spec's section 5 explicitly and record the evidence for each:

- [ ] All three parameters are no-ops by default — pinned by `with the cooldown at its default every reappearance still notifies`, `an empty reappear-classes list lets every class reappear`, `with nothing set, reappear-classes is empty and every class may reappear`, `with nothing set the reappear cooldown is disabled`, and the untouched pre-existing suites.
- [ ] Build green, ktlint clean, no test failures.
- [ ] The listed cases are covered: cooldown off by default; cooldown suppresses the second notification and lets the next through after expiry; cooldown does not touch `NEW_OBJECTS`; cooldown measured on `recordTimestamp` so a backlog is not collapsed; empty class list means all classes; a class outside the list cannot reappear but still notifies as new.
- [ ] `.claude/rules/configuration.md` and `docker/deploy/.env.example` updated.
- [ ] Against that night's data: `PT5M` reduces group B from 4 notifications to 2 (one per camera) — `the cooldown collapses a burst of reappearances on one camera` plus `the cooldown is per camera`; `reappear-classes=person` removes group A entirely — `a class outside reappear-classes does not produce a reappearance`, covering the bicycle/cow/car cases.

- [ ] **Step 4: Remove the plan documents before the PR**

Per the global workflow rules, `docs/superpowers/` must not appear in the PR diff. That covers the
plan **and** the review artefacts this branch accumulated under `docs/superpowers/specs/`.

One `git rm` per file, not two. `git rm --cached` followed by a plain `git rm` on the same path fails:
the first leaves the file untracked, and the second then dies on `pathspec did not match any files`.

```bash
git rm docs/superpowers/plans/2026-08-03-notification-noise-reduction.md \
       docs/superpowers/specs/2026-08-03-notification-noise-reduction-review-merged-iter-1.md \
       docs/superpowers/specs/2026-08-03-notification-noise-reduction-review-iter-1.md
git commit -m "chore: drop the plan and review documents from the branch" \
    -- docs/superpowers/plans/2026-08-03-notification-noise-reduction.md \
       docs/superpowers/specs/2026-08-03-notification-noise-reduction-review-merged-iter-1.md \
       docs/superpowers/specs/2026-08-03-notification-noise-reduction-review-iter-1.md

# The rule is now checked, not assumed: this must print nothing.
# Three dots on purpose — a PR shows the net diff against the merge base, so a file added and then
# removed inside the branch is absent from it. `git log --stat master..HEAD` would instead list the
# commit that added the plan (f351ea4) and report a leak that is not there.
git diff master...HEAD --name-only -- docs/superpowers/
```

The documents stay reachable in branch history. Adjust the `specs/` paths to whatever iterations
actually exist — a later review round adds `-iter-2` and so on.
