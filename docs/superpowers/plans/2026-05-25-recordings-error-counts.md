# Recordings error counts (#28) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `success` and `errors` counters to `/status` recordings section, computed by a single PostgreSQL `COUNT(*) FILTER (WHERE ...)` query, with updated Telegram `<pre>` layout.

**Architecture:** Replace three separate count queries with one FILTER aggregate returning a single DTO (`RecordingCountsDto`). Add two new fields to `RecordingsStatistics` (REST contract; additive — preserves existing `processed`/`unprocessed`). Telegram formatter switches to layout C (5 rows: Total / Success / Errors / Unprocessed / Rate); old `Processed` row and its i18n keys are removed.

**Tech Stack:** Kotlin 2.3.10, Spring Boot 4.0.3 + WebFlux, R2DBC (PostgreSQL), MockK, AssertJ, JUnit 5 (kotlin.test in formatter tests).

**Design reference:** `docs/superpowers/specs/2026-05-25-recordings-error-counts-design.md`

**Branch:** `feat/recordings-error-counts` (already created from master).

---

## File Structure

**New file:**
- `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/RecordingCountsDto.kt` — SQL-projection DTO, 5 `Long` fields with `@Column` annotations.

**Modified files (production):**
- `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/response/RecordingsStatistics.kt` — +2 fields (`success`, `errors`).
- `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/repository/RecordingEntityRepository.kt` — +`getRecordingCounts()`; later −`countAll`/`countProcessed`/`countUnprocessed`.
- `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/StatusService.kt` — `buildRecordings()` uses single new method.
- `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/StatusMessageFormatter.kt` — `appendRecordings()` rewritten to layout C + private `pct()` helper.
- `modules/telegram/src/main/resources/messages_en.properties` — +3 keys, later −2 keys.
- `modules/telegram/src/main/resources/messages_ru.properties` — +3 keys, later −2 keys.

**Modified files (test):**
- `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/StatusServiceTest.kt` — replace `count*` mocks with `getRecordingCounts()` mock; assert `success`/`errors`.
- `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/controller/StatusControllerTest.kt` — +2 jsonPath assertions.
- `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/controller/StatusControllerTestConfig.kt` — compile-fix: + `success`/`errors` args.
- `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/repository/RecordingEntityRepositoryTest.kt` — remove 3 count tests, add 1 FILTER aggregate test.
- `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/StatusMessageFormatterTest.kt` — compile-fix (4 sites) + layout C asserts + 2 edge-case tests. Same file also contains `StatusMessageFormatterI18nTest` whose `sampleSnapshot()` needs compile-fix.

---

## Task 1: Scaffold `RecordingCountsDto` and add `getRecordingCounts()` repository method

This is additive scaffolding. Old `count*` methods remain for now (removed in Task 6 after callers migrate). Project rule: do not run `./gradlew build` directly during planning; trust the compile in subsequent tasks where tests exercise the chain.

**Files:**
- Create: `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/RecordingCountsDto.kt`
- Modify: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/repository/RecordingEntityRepository.kt`

- [ ] **Step 1: Create `RecordingCountsDto`**

Create `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/RecordingCountsDto.kt`:

```kotlin
package ru.zinin.frigate.analyzer.model.dto

import org.springframework.data.relational.core.mapping.Column

data class RecordingCountsDto(
    @Column("total") val total: Long,
    @Column("processed") val processed: Long,
    @Column("unprocessed") val unprocessed: Long,
    @Column("success") val success: Long,
    @Column("errors") val errors: Long,
)
```

- [ ] **Step 2: Add `getRecordingCounts()` to `RecordingEntityRepository`**

In `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/repository/RecordingEntityRepository.kt`, add the import and the new method (place near the existing `count*` methods — between `countUnprocessed()` (line 108) and `getStatisticsByCameras()` (line 110)):

```kotlin
import ru.zinin.frigate.analyzer.model.dto.RecordingCountsDto
```

```kotlin
@Query(
    """
    SELECT
        COUNT(*)                                                AS total,
        COUNT(*) FILTER (WHERE process_timestamp IS NOT NULL)   AS processed,
        COUNT(*) FILTER (WHERE process_timestamp IS NULL)       AS unprocessed,
        COUNT(*) FILTER (WHERE process_timestamp IS NOT NULL
                           AND error_message IS NULL)           AS success,
        COUNT(*) FILTER (WHERE error_message IS NOT NULL)       AS errors
    FROM recordings
    """,
)
suspend fun getRecordingCounts(): RecordingCountsDto
```

- [ ] **Step 3: Stage and commit**

```bash
git add modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/RecordingCountsDto.kt \
        modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/repository/RecordingEntityRepository.kt
git commit -m "feat(service): add getRecordingCounts() FILTER aggregate query

Single query replaces 3 separate count* methods. RecordingCountsDto
projects 5 atomic counts: total, processed, unprocessed, success, errors.

Refs #28"
```

---

## Task 2: Backend slice — add `success`/`errors` fields, wire StatusService, update StatusServiceTest

This is the core vertical slice. Adding REQUIRED non-null fields to `RecordingsStatistics` is a breaking change for every constructor call — those compile-fixes happen here so the build stays green at task end.

**Files:**
- Modify: `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/response/RecordingsStatistics.kt`
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/StatusService.kt`
- Modify: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/StatusServiceTest.kt`
- Modify: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/controller/StatusControllerTestConfig.kt`
- Modify: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/StatusMessageFormatterTest.kt` (4 construction sites, both nested classes)

- [ ] **Step 1: Add `success` and `errors` fields to `RecordingsStatistics`**

Replace the data class in `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/response/RecordingsStatistics.kt` (currently lines 3-9):

```kotlin
data class RecordingsStatistics(
    val total: Long,
    val processed: Long,
    val unprocessed: Long,
    val success: Long,
    val errors: Long,
    val byCameras: List<CameraStatistics>,
    val processingRatePerMinute: Double,
)
```

(`CameraStatistics` definition at lines 11-16 remains unchanged.)

- [ ] **Step 2: Update `StatusService.buildRecordings()` to use the new method**

In `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/StatusService.kt`, replace the entire `buildRecordings()` function (currently lines 42-68):

```kotlin
private suspend fun buildRecordings(): RecordingsStatistics {
    val counts = recordingRepository.getRecordingCounts()
    // Two near-identical types with the same positional fields: `CameraStatisticsDto`
    // (`model.dto`, SQL projection from RecordingEntityRepository) → `CameraStatistics`
    // (`model.response`, JSON contract). Mapping is mandatory to avoid leaking the
    // SQL/R2DBC layer into the response. The SQL query already orders by `cam_id ASC`
    // — relying on that invariant to keep the `byCameras` list stable.
    val byCameras =
        recordingRepository.getStatisticsByCameras().map { dto ->
            CameraStatistics(
                camId = dto.camId,
                recordingsCount = dto.recordingsCount,
                recordingsProcessed = dto.recordingsProcessed,
                detectionsCount = dto.detectionsCount,
            )
        }
    val rate = recordingRepository.getProcessingRatePerMinuteLast5Minutes()
    return RecordingsStatistics(
        total = counts.total,
        processed = counts.processed,
        unprocessed = counts.unprocessed,
        success = counts.success,
        errors = counts.errors,
        byCameras = byCameras,
        processingRatePerMinute = rate,
    )
}
```

- [ ] **Step 3: Update `StatusServiceTest` mock and assertions**

In `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/StatusServiceTest.kt`:

3a. Add the import for the new DTO (alongside existing imports near line 14):

```kotlin
import ru.zinin.frigate.analyzer.model.dto.RecordingCountsDto
```

3b. Replace the three `count*` mock stubs (currently lines 30-32) with one. The whole `recordings` mock block becomes:

```kotlin
private val recordings =
    mockk<RecordingEntityRepository>().apply {
        coEvery { getRecordingCounts() } returns
            RecordingCountsDto(
                total = 100L,
                processed = 90L,
                unprocessed = 10L,
                success = 85L,
                errors = 5L,
            )
        coEvery { getStatisticsByCameras() } returns
            listOf(
                CameraStatisticsDto("cam1", 50L, 50L, 5L),
                CameraStatisticsDto("cam2", 50L, 40L, 3L),
            )
        coEvery { getProcessingRatePerMinuteLast5Minutes() } returns 2.5
    }
```

3c. Extend the existing `collect populates recordings counters and rate` test (currently lines 128-139). Replace its body so it asserts the new fields too:

```kotlin
@Test
fun `collect populates recordings counters and rate`() =
    runBlocking {
        val service = StatusService(recordings, lb, monitorProvider(null), clock)
        val resp = service.collect()
        assertThat(resp.recordings.total).isEqualTo(100L)
        assertThat(resp.recordings.processed).isEqualTo(90L)
        assertThat(resp.recordings.unprocessed).isEqualTo(10L)
        assertThat(resp.recordings.success).isEqualTo(85L)
        assertThat(resp.recordings.errors).isEqualTo(5L)
        assertThat(resp.recordings.processingRatePerMinute).isEqualTo(2.5)
        assertThat(resp.recordings.byCameras.map { it.camId }).containsExactly("cam1", "cam2")
        Unit
    }
```

3d. Add a new test for the all-success scenario (append at end of class, before the closing brace):

```kotlin
@Test
fun `collect populates recordings counters with zero errors`() =
    runBlocking {
        val emptyRecordings =
            mockk<RecordingEntityRepository>().apply {
                coEvery { getRecordingCounts() } returns
                    RecordingCountsDto(total = 0L, processed = 0L, unprocessed = 0L, success = 0L, errors = 0L)
                coEvery { getStatisticsByCameras() } returns emptyList()
                coEvery { getProcessingRatePerMinuteLast5Minutes() } returns 0.0
            }
        val service = StatusService(emptyRecordings, lb, monitorProvider(null), clock)
        val resp = service.collect()
        assertThat(resp.recordings.total).isEqualTo(0L)
        assertThat(resp.recordings.success).isEqualTo(0L)
        assertThat(resp.recordings.errors).isEqualTo(0L)
        assertThat(resp.recordings.byCameras).isEmpty()
        Unit
    }
```

- [ ] **Step 4: Compile-fix `StatusControllerTestConfig`**

In `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/controller/StatusControllerTestConfig.kt`, update the `RecordingsStatistics(...)` construction (currently lines 36-42) to include the two new fields:

```kotlin
recordings =
    RecordingsStatistics(
        total = 0L,
        processed = 0L,
        unprocessed = 0L,
        success = 0L,
        errors = 0L,
        byCameras = emptyList(),
        processingRatePerMinute = 0.0,
    ),
```

- [ ] **Step 5: Compile-fix `StatusMessageFormatterTest` (4 construction sites)**

In `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/StatusMessageFormatterTest.kt`, update each `RecordingsStatistics(...)` construction. Use values that match the snapshot's narrative (90 processed includes 85 success + 5 errors); all-zero in degenerate snapshots.

5a. `snapshot()` helper (currently lines 62-72). Replace with:

```kotlin
recordings =
    RecordingsStatistics(
        total = 100L,
        processed = 90L,
        unprocessed = 10L,
        success = 85L,
        errors = 5L,
        byCameras =
            listOf(
                CameraStatistics("cam1", 50, 50, 5),
                CameraStatistics("cam2", 50, 40, 3),
            ),
        processingRatePerMinute = 2.5,
    ),
```

5b. `format escapes HTML special chars in byCameras camId` (currently lines 201-210). Replace with:

```kotlin
recordings =
    RecordingsStatistics(
        total = 1,
        processed = 1,
        unprocessed = 0,
        success = 1,
        errors = 0,
        byCameras =
            listOf(
                CameraStatistics(camId = "cam<&>", recordingsCount = 1, recordingsProcessed = 1, detectionsCount = 0),
            ),
        processingRatePerMinute = 0.0,
    ),
```

5c. `format escapes HTML special chars in server id` (currently line 223 — positional construction):

```kotlin
recordings = RecordingsStatistics(
    total = 0,
    processed = 0,
    unprocessed = 0,
    success = 0,
    errors = 0,
    byCameras = emptyList(),
    processingRatePerMinute = 0.0,
),
```

5d. `StatusMessageFormatterI18nTest.sampleSnapshot()` (currently lines 261-267). Replace with:

```kotlin
recordings =
    RecordingsStatistics(
        total = 0,
        processed = 0,
        unprocessed = 0,
        success = 0,
        errors = 0,
        byCameras = emptyList(),
        processingRatePerMinute = 0.0,
    ),
```

- [ ] **Step 6: Run service tests to verify**

```bash
./gradlew :frigate-analyzer-core:test --tests "ru.zinin.frigate.analyzer.core.service.StatusServiceTest"
```

(Per project rule, prefer `/build` or the `build-runner` agent over a direct gradle invocation. This direct command is only an option if you want a fast check of just StatusServiceTest before the broader build.)

Expected: PASS — `collect populates recordings counters and rate` now asserts new fields; new `collect populates recordings counters with zero errors` also passes.

- [ ] **Step 7: Stage and commit**

```bash
git add modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/response/RecordingsStatistics.kt \
        modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/StatusService.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/StatusServiceTest.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/controller/StatusControllerTestConfig.kt \
        modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/StatusMessageFormatterTest.kt
git commit -m "feat(model,core): expose success/errors in /status recordings

RecordingsStatistics gains two non-null fields; StatusService now calls
the single getRecordingCounts() FILTER aggregate (3 SQL queries instead
of 5). All existing RecordingsStatistics(...) construction sites updated
for compile compatibility.

Refs #28"
```

---

## Task 3: Update `StatusControllerTest` JSON-path assertions

Additive assertions; JSON serialization auto-includes the new fields once `RecordingsStatistics` has them (Task 2).

**Files:**
- Modify: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/controller/StatusControllerTest.kt`

- [ ] **Step 1: Add `success`/`errors` jsonPath assertions**

In the `GET status returns 200 with expected structure` test (currently lines 31-60), insert two jsonPath checks after the `unprocessed` assertion (between lines 49 and 50):

```kotlin
.jsonPath("$.recordings.success")
.isNumber
.jsonPath("$.recordings.errors")
.isNumber
```

- [ ] **Step 2: Run the controller test**

```bash
./gradlew :frigate-analyzer-core:test --tests "ru.zinin.frigate.analyzer.core.controller.StatusControllerTest"
```

Expected: PASS — `StatusControllerTestConfig` (updated in Task 2) returns a `RecordingsStatistics` with `success=0, errors=0`; JSON contains both fields.

- [ ] **Step 3: Stage and commit**

```bash
git add modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/controller/StatusControllerTest.kt
git commit -m "test(core): assert success/errors in /status JSON contract

Refs #28"
```

---

## Task 4: Add new i18n keys (EN + RU)

Pure additions; the formatter still uses the old keys until Task 5.

**Files:**
- Modify: `modules/telegram/src/main/resources/messages_en.properties`
- Modify: `modules/telegram/src/main/resources/messages_ru.properties`

- [ ] **Step 1: Add EN keys**

In `modules/telegram/src/main/resources/messages_en.properties`, add three lines immediately after the existing `status.recordings.value.rate=...` line (currently line 207):

```properties
status.recordings.label.success=Success
status.recordings.label.errors=Errors
status.recordings.value.withPct={0} ({1}%)
```

- [ ] **Step 2: Add RU keys**

In `modules/telegram/src/main/resources/messages_ru.properties`, add three lines at the same logical position (after `status.recordings.value.rate=...` at line 207):

```properties
status.recordings.label.success=Успешно
status.recordings.label.errors=Ошибки
status.recordings.value.withPct={0} ({1}%)
```

- [ ] **Step 3: Stage and commit**

```bash
git add modules/telegram/src/main/resources/messages_en.properties \
        modules/telegram/src/main/resources/messages_ru.properties
git commit -m "i18n(telegram): add status.recordings success/errors keys

EN: Success, Errors. RU: Успешно, Ошибки.
Shared value.withPct template '{0} ({1}%)' for both rows.

Refs #28"
```

---

## Task 5: Rewrite `StatusMessageFormatter.appendRecordings()` for layout C + add tests

TDD-style: extend test first (layout assertion) → run → fail → update formatter → run → pass.

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/StatusMessageFormatter.kt`
- Modify: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/StatusMessageFormatterTest.kt`

- [ ] **Step 1: Add layout-C tests to `StatusMessageFormatterTest`**

Append the following tests to the `StatusMessageFormatterTest` class (before its closing brace at line 241). They use the existing identity-style mock `msg` (which echoes the key for plain keys and `key[arg0,arg1]` for parametric), so assertions check for both the i18n key presence and the rendered values.

```kotlin
@Test
fun `format renders layout C with success and errors rows including percentages`() {
    val out = formatter.format(snapshot(), language = "en", zone = zone, now = now)
    // Success row: label key present, value template rendered with success count and pct of total.
    // snapshot() has total=100, success=85, errors=5 → success%=85.0, errors%=5.0
    assertTrue(out.contains("status.recordings.label.success"), "missing success label in: $out")
    assertTrue(
        out.contains("status.recordings.value.withPct[85,85.0]"),
        "missing success value with pct in: $out",
    )
    assertTrue(out.contains("status.recordings.label.errors"), "missing errors label in: $out")
    assertTrue(
        out.contains("status.recordings.value.withPct[5,5.0]"),
        "missing errors value with pct in: $out",
    )
    // Old Processed row must be gone.
    assertFalse(
        out.contains("status.recordings.label.processed"),
        "obsolete processed label still rendered: $out",
    )
}

@Test
fun `format renders errors row with zero count and zero percent`() {
    val zeroErrors =
        StatusResponse(
            recordings =
                RecordingsStatistics(
                    total = 50L,
                    processed = 50L,
                    unprocessed = 0L,
                    success = 50L,
                    errors = 0L,
                    byCameras = emptyList(),
                    processingRatePerMinute = 1.0,
                ),
            cameras = CamerasSection(monitoringEnabled = false, items = emptyList()),
            detectServers = emptyList(),
        )
    val out = formatter.format(zeroErrors, language = "en", zone = zone, now = now)
    assertTrue(
        out.contains("status.recordings.value.withPct[0,0.0]"),
        "expected errors row '0 (0.0%)' even when errors=0: $out",
    )
}

@Test
fun `format renders zero percent when total is zero`() {
    val empty =
        StatusResponse(
            recordings =
                RecordingsStatistics(
                    total = 0L,
                    processed = 0L,
                    unprocessed = 0L,
                    success = 0L,
                    errors = 0L,
                    byCameras = emptyList(),
                    processingRatePerMinute = 0.0,
                ),
            cameras = CamerasSection(monitoringEnabled = false, items = emptyList()),
            detectServers = emptyList(),
        )
    val out = formatter.format(empty, language = "en", zone = zone, now = now)
    // Both success and errors rows should render with '0.0' percent (div-by-zero guard).
    assertEquals(
        2,
        out.split("status.recordings.value.withPct[0,0.0]").size - 1,
        "expected two '0 (0.0%)' rows (success + errors) when total=0: $out",
    )
}
```

- [ ] **Step 2: Run the new tests to verify they fail**

```bash
./gradlew :frigate-analyzer-telegram:test --tests "ru.zinin.frigate.analyzer.telegram.service.impl.StatusMessageFormatterTest"
```

Expected: the three new tests FAIL (formatter still emits old layout with `status.recordings.label.processed`).

- [ ] **Step 3: Rewrite `appendRecordings()` and add `pct()` helper**

In `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/StatusMessageFormatter.kt`, replace the existing `appendRecordings()` (currently lines 43-71) with:

```kotlin
private fun StringBuilder.appendRecordings(
    r: RecordingsStatistics,
    language: String,
) {
    appendLine("📹 <b>${escape(msg.get("status.section.recordings", language))}</b>")
    val successPct = pct(r.success, r.total)
    val errorsPct = pct(r.errors, r.total)
    val rateFormatted = "%.1f".format(Locale.ROOT, r.processingRatePerMinute)
    val rows =
        listOf(
            msg.get("status.recordings.label.total", language) to r.total.toString(),
            msg.get("status.recordings.label.success", language) to
                msg.get("status.recordings.value.withPct", language, r.success.toString(), successPct),
            msg.get("status.recordings.label.errors", language) to
                msg.get("status.recordings.value.withPct", language, r.errors.toString(), errorsPct),
            msg.get("status.recordings.label.unprocessed", language) to r.unprocessed.toString(),
            msg.get("status.recordings.label.rate", language) to
                msg.get("status.recordings.value.rate", language, rateFormatted),
        )
    val labelWidth = rows.maxOf { it.first.length }
    val valueWidth = rows.maxOf { it.second.length }
    appendPreBlock(
        rows.map { (l, v) ->
            "${l.padEnd(labelWidth + 1)} ${v.padStart(valueWidth)}"
        },
    )
}

private fun pct(part: Long, total: Long): String =
    if (total > 0) {
        "%.1f".format(Locale.ROOT, part.toDouble() * 100.0 / total.toDouble())
    } else {
        "0.0"
    }
```

The unused private helper `formatRow(...)` (currently lines 234-241) is dead code from earlier iterations of the file; leave it for now if untouched by ktlint, or remove it if ktlint flags unused-private — it is not exercised by any caller.

- [ ] **Step 4: Run tests to verify pass**

```bash
./gradlew :frigate-analyzer-telegram:test --tests "ru.zinin.frigate.analyzer.telegram.service.impl.StatusMessageFormatterTest"
```

Expected: all `StatusMessageFormatterTest` tests pass, including the three new layout-C tests. The nested `StatusMessageFormatterI18nTest` also runs (cameras/servers EN+RU assertions, recordings not involved) — should remain green from Task 2 compile-fix.

- [ ] **Step 5: Stage and commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/StatusMessageFormatter.kt \
        modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/StatusMessageFormatterTest.kt
git commit -m "feat(telegram): switch /status recordings to layout C

5 rows: Total / Success (%) / Errors (%) / Unprocessed / Rate.
Old Processed row removed (now redundant — Success + Errors decompose it).
Percentages relative to total; 0.0 when total=0 (div-by-zero guard).
New pct() private helper extracted for reuse across rows.

Refs #28"
```

---

## Task 6: Cleanup — remove unused `count*` methods, unused i18n keys, update RecordingEntityRepositoryTest

All callers were migrated in Tasks 2 and 5. This task removes the dead code and replaces the now-stale repository integration tests with a single FILTER aggregate test.

**Files:**
- Modify: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/repository/RecordingEntityRepository.kt`
- Modify: `modules/telegram/src/main/resources/messages_en.properties`
- Modify: `modules/telegram/src/main/resources/messages_ru.properties`
- Modify: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/repository/RecordingEntityRepositoryTest.kt`

- [ ] **Step 1: Verify no remaining callers**

```bash
grep -rn "countAll\|countProcessed\|countUnprocessed" modules --include="*.kt"
grep -rn "status\.recordings\.label\.processed\|status\.recordings\.value\.processed" modules --include="*.kt" --include="*.properties"
```

Expected: only the definitions themselves (repository interface, properties files, the repository-test cases that will be replaced in Step 4). If anything else surfaces, stop and triage before removal.

- [ ] **Step 2: Remove the three `count*` methods from the repository**

In `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/repository/RecordingEntityRepository.kt`, delete lines 101-108 (the `countAll`, `countProcessed`, `countUnprocessed` block — the three `@Query`-annotated `suspend fun`s with their docstrings/blank lines).

- [ ] **Step 3: Remove the two obsolete i18n keys from both bundles**

In `modules/telegram/src/main/resources/messages_en.properties`, delete:

```properties
status.recordings.label.processed=Processed
status.recordings.value.processed={0} ({1}%)
```

In `modules/telegram/src/main/resources/messages_ru.properties`, delete:

```properties
status.recordings.label.processed=Обработано
status.recordings.value.processed={0} ({1}%)
```

- [ ] **Step 4: Update `RecordingEntityRepositoryTest`**

In `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/repository/RecordingEntityRepositoryTest.kt`:

4a. Add import for the new DTO (alongside existing model imports):

```kotlin
import ru.zinin.frigate.analyzer.model.dto.RecordingCountsDto
```

4b. Delete the three tests: `should count all recordings` (currently lines 437-451), `should count processed recordings` (lines 453-477), `should count unprocessed recordings` (lines 479-498).

4c. In place of the three deleted tests (still inside the `// region Counters` block), add a single integration test that seeds four recordings covering each FILTER bucket and asserts the full DTO:

```kotlin
@Test
fun `should return recording counts via FILTER aggregate`() {
    runBlocking {
        // unprocessed: no process_timestamp
        repository.save(createRecordingEntity(filePath = "/recordings/pending.mp4"))
        // success: processed, no error
        repository.save(
            createRecordingEntity(
                filePath = "/recordings/ok.mp4",
                processTimestamp = Instant.now(),
            ),
        )
        // errors finished: both process_timestamp and error_message
        repository.save(
            createRecordingEntity(
                filePath = "/recordings/failed.mp4",
                processTimestamp = Instant.now(),
                errorMessage = "boom",
            ),
        )
        // another unprocessed baseline (so unprocessed=2, total=4)
        repository.save(createRecordingEntity(filePath = "/recordings/pending2.mp4"))

        // when
        val counts = repository.getRecordingCounts()

        // then
        assertEquals(4L, counts.total)
        assertEquals(2L, counts.processed)
        assertEquals(2L, counts.unprocessed)
        assertEquals(1L, counts.success)
        assertEquals(1L, counts.errors)
    }
}
```

**Note:** the test fixture helper `createRecordingEntity(...)` must support an `errorMessage` parameter. Verify by looking near the top of the file for its definition (or its earlier overload usage in failure-state tests in the same file). If the helper does not accept `errorMessage`, either extend it with `errorMessage: String? = null` (and forward to `RecordingEntity.errorMessage`), or construct the entity directly via `RecordingEntity(...)` in this test only — match whichever pattern the file already uses elsewhere for tests touching `error_message`.

- [ ] **Step 5: Run full test suites for both touched modules**

```bash
./gradlew :frigate-analyzer-core:test :frigate-analyzer-telegram:test
```

Expected: PASS — repository integration test exercises the real FILTER aggregate query against PostgreSQL via `IntegrationTestBase`; all `StatusServiceTest`, `StatusControllerTest`, `StatusMessageFormatterTest` (and nested `I18nTest`) green.

If the test for `errorMessage` helper extension breaks anything, narrow the fix to that test file only — do not rewrite the broader fixture.

- [ ] **Step 6: Stage and commit**

```bash
git add modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/repository/RecordingEntityRepository.kt \
        modules/telegram/src/main/resources/messages_en.properties \
        modules/telegram/src/main/resources/messages_ru.properties \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/repository/RecordingEntityRepositoryTest.kt
git commit -m "refactor: drop unused recordings count queries and i18n keys

- Repository: remove countAll/countProcessed/countUnprocessed
  (superseded by getRecordingCounts FILTER aggregate).
- i18n: remove status.recordings.label.processed and value.processed
  (Telegram layout C no longer renders the Processed row).
- Repository test: replace 3 single-count tests with 1 combined
  FILTER aggregate test seeding all 5 bucket variants.

Refs #28"
```

---

## Task 7: Code review + build verification (project workflow)

Per `CLAUDE.md` § Planning Mode: run `superpowers:code-reviewer` agent FIRST, fix critical comments, repeat until clean; then use `build-runner` agent for the full build. On ktlint errors run `./gradlew ktlintFormat` and retry the build.

- [ ] **Step 1: Internal code review**

Invoke `superpowers:code-reviewer` against the diff between `master` and `feat/recordings-error-counts` (post-Task 6). Address every comment flagged Critical. For non-Critical, decide deliberately (apply or document why declined). Loop until the reviewer surfaces no Critical items.

- [ ] **Step 2: Full build**

Dispatch the `build-runner` agent with `./gradlew build` (or run `/build`). Expected: BUILD SUCCESSFUL with all module tests green.

- [ ] **Step 3: Fix ktlint if needed**

If ktlint fails, run:

```bash
./gradlew ktlintFormat
```

then re-dispatch the `build-runner` agent to retry the build. Commit any whitespace/import-order changes as:

```bash
git add -u
git commit -m "style: apply ktlint formatting"
```

- [ ] **Step 4: (Optional) External design review of the implementation**

If desired, invoke `/external-code-review default` against the branch — this runs Codex/CCS/Gemini/Ollama reviewers in parallel. Use `superpowers:review-discussion` to triage and either apply or document each finding.

- [ ] **Step 5: Pre-PR cleanup of planning artefacts**

Per global CLAUDE.md `Superpowers workflow` rule: planning docs must NOT appear in the final PR diff. Remove them in a single commit:

```bash
git rm docs/superpowers/specs/2026-05-25-recordings-error-counts-design.md \
       docs/superpowers/plans/2026-05-25-recordings-error-counts.md
git commit -m "chore: remove planning docs before PR"
```

The documents remain available in branch history (`git log --all --diff-filter=D -- docs/superpowers/specs/2026-05-25-recordings-error-counts-design.md`) if needed later.

- [ ] **Step 6: Open the PR**

```bash
gh pr create --title "feat: expose success/errors counters in /status (#28)" --body "$(cat <<'EOF'
## Summary

- Adds `success` and `errors` counters to `RecordingsStatistics` (REST and Telegram `/status`); existing `processed`/`unprocessed` semantics preserved.
- Replaces 3 serial count queries with a single PostgreSQL `COUNT(*) FILTER (WHERE ...)` aggregate (atomic snapshot, fewer round-trips).
- Telegram layout C: 5 rows (Total / Success (%) / Errors (%) / Unprocessed / Rate); old redundant `Processed` row and its i18n keys dropped.

Resolves #28.

## Test plan

- [ ] `:frigate-analyzer-core:test` — StatusServiceTest, StatusControllerTest, RecordingEntityRepositoryTest (real PG via IntegrationTestBase)
- [ ] `:frigate-analyzer-telegram:test` — StatusMessageFormatterTest (layout C, edge cases), StatusMessageFormatterI18nTest
- [ ] Manual `/status` in Telegram (OWNER chat) — verify new layout renders correctly with real data
- [ ] Manual `GET /frigate-analyzer/status` — verify JSON contains success/errors
EOF
)"
```

---

## Self-Review

**1. Spec coverage:**
- Spec § 1 Problem → addressed by Tasks 1+2+5 (new fields + service wiring + Telegram surface).
- Spec § 5 Data layer → Task 1 (DTO + method) + Task 6 (old methods removed + repo integration test).
- Spec § 6 Response model → Task 2 Step 1.
- Spec § 7 StatusService → Task 2 Step 2.
- Spec § 8 Telegram layout/i18n → Tasks 4 (keys) + 5 (formatter + tests) + 6 (key removal).
- Spec § 9 Tests → Task 2 (StatusServiceTest, compile-fix sites), Task 3 (StatusControllerTest), Task 5 (StatusMessageFormatterTest), Task 6 (RecordingEntityRepositoryTest).
- Spec § 10 Backward compat → preserved (additive REST fields; no DB migration).
- Spec § 11 Risks → grep checks in Task 6 Step 1; FILTER aggregate exercised by Task 6 Step 5 integration test.
- Spec § 12 Out of scope → no tasks for categorization, retry-pending, per-camera errors, DB migration.
- Spec § 13 Affected files → covered file-by-file in tasks.

**2. Placeholder scan:** no TBD/TODO/"implement later"; every code-changing step has a complete code block. The only deferred decision is the `createRecordingEntity` helper signature in Task 6 Step 4c — handled with a contextual instruction ("verify or extend") rather than a placeholder, because the helper's current signature was not in our context window.

**3. Type consistency:**
- `RecordingCountsDto` field names (`total`, `processed`, `unprocessed`, `success`, `errors`) consistent across Task 1, 2, 5, 6.
- `pct(part, total)` signature in formatter matches its only caller pattern.
- `RecordingsStatistics` 7-arg constructor used identically in all touched sites (named args throughout).
- `getRecordingCounts()` signature (suspend, no params, returns `RecordingCountsDto`) consistent.

No drift detected.
