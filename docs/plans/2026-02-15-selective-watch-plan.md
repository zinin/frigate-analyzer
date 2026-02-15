# Selective Watch Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Monitor only recent Frigate recording directories (configurable period) instead of all directories, reducing inotify watch count.

**Architecture:** Extract date from Frigate directory paths (`recordings/YYYY-MM-DD/HH/cam_id/`), filter directories by age at registration, periodically clean up expired watches. Group watch-related config into `records-watcher` section.

**Tech Stack:** Kotlin, Spring Boot ConfigurationProperties, Java NIO WatchService, java.time

**GitHub Issue:** https://github.com/zinin/frigate-analyzer/issues/2

---

### Task 1: Create RecordsWatcherProperties

**Files:**
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/RecordsWatcherProperties.kt`

**Step 1: Create the properties class**

```kotlin
package ru.zinin.frigate.analyzer.core.config.properties

import jakarta.validation.constraints.NotNull
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.nio.file.Path
import java.time.Duration

@ConfigurationProperties(prefix = "application.records-watcher")
@Validated
data class RecordsWatcherProperties(
    val disableFirstScan: Boolean = false,
    @field:NotNull
    val folder: Path,
    val watchPeriod: Duration = Duration.ofDays(1),
    val cleanupInterval: Duration = Duration.ofHours(1),
)
```

**Step 2: Register in EnableConfigurationProperties**

Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/FrigateAnalyzerApplication.kt`

Add `RecordsWatcherProperties::class` to `@EnableConfigurationProperties` and its import.

**Step 3: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/RecordsWatcherProperties.kt
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/FrigateAnalyzerApplication.kt
git commit -m "feat: add RecordsWatcherProperties config class"
```

---

### Task 2: Update application.yaml (main + test)

**Files:**
- Modify: `modules/core/src/main/resources/application.yaml`
- Modify: `modules/core/src/test/resources/application.yaml`

**Step 1: Update main application.yaml**

Replace lines 28-29:
```yaml
  disable-first-scan-task: ${DISABLE_FIRST_SCAN:false}
  frigate-records-folder: ${FRIGATE_RECORDS_FOLDER:/mnt/data/frigate/recordings}
```

With:
```yaml
  records-watcher:
    disable-first-scan: ${DISABLE_FIRST_SCAN:false}
    folder: ${FRIGATE_RECORDS_FOLDER:/mnt/data/frigate/recordings}
    watch-period: ${WATCH_PERIOD:P1D}
    cleanup-interval: ${WATCH_CLEANUP_INTERVAL:PT1H}
```

**Step 2: Update test application.yaml**

Replace lines 29-30:
```yaml
  disable-first-scan-task: true
  frigate-records-folder: /tmp/frigate/recordings/
```

With:
```yaml
  records-watcher:
    disable-first-scan: true
    folder: /tmp/frigate/recordings/
    watch-period: P1D
    cleanup-interval: PT1H
```

**Step 3: Commit**

```bash
git add modules/core/src/main/resources/application.yaml
git add modules/core/src/test/resources/application.yaml
git commit -m "feat: group watch settings into records-watcher section"
```

---

### Task 3: Remove old fields from ApplicationProperties, update all references

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/ApplicationProperties.kt`
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/application/ApplicationListener.kt`
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsTask.kt`
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/FirstTimeScanTask.kt`
- Modify: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/DetectServiceTest.kt`

**Step 1: Remove fields from ApplicationProperties**

Remove `disableFirstScanTask` and `frigateRecordsFolder` from `ApplicationProperties`. The class becomes:

```kotlin
data class ApplicationProperties(
    @field:NotNull
    val tempFolder: Path,
    @field:NotNull
    val ffmpegPath: Path,
    @field:NotNull
    val connectionTimeout: Duration,
    @field:NotNull
    val readTimeout: Duration,
    @field:NotNull
    val writeTimeout: Duration,
    @field:NotNull
    val responseTimeout: Duration,
    @field:NotEmpty
    val detectServers: Map<String, DetectServerProperties> = emptyMap(),
)
```

**Step 2: Update ApplicationListener**

Inject `RecordsWatcherProperties` instead of reading from `applicationProperties`:

```kotlin
class ApplicationListener(
    val gitProperties: GitProperties,
    val buildProperties: BuildProperties,
    val watchRecordsTask: WatchRecordsTask,
    val firstTimeScanTask: FirstTimeScanTask,
    val recordsWatcherProperties: RecordsWatcherProperties,
    val springProfileHelper: SpringProfileHelper,
)
```

Change line 42 from:
```kotlin
} else if (!applicationProperties.disableFirstScanTask) {
```
To:
```kotlin
} else if (!recordsWatcherProperties.disableFirstScan) {
```

Remove `applicationProperties` field and its import. Add import for `RecordsWatcherProperties`.

**Step 3: Update WatchRecordsTask**

Inject `RecordsWatcherProperties` instead of `ApplicationProperties`:

```kotlin
class WatchRecordsTask(
    val recordsWatcherProperties: RecordsWatcherProperties,
    val recordingEntityHelper: RecordingEntityHelper,
    val recordingFileHelper: RecordingFileHelper,
)
```

Change line 40 from:
```kotlin
val folderPath = applicationProperties.frigateRecordsFolder
```
To:
```kotlin
val folderPath = recordsWatcherProperties.folder
```

Remove `ApplicationProperties` import, add `RecordsWatcherProperties` import.

**Step 4: Update FirstTimeScanTask**

Same pattern — inject `RecordsWatcherProperties`:

```kotlin
class FirstTimeScanTask(
    val recordsWatcherProperties: RecordsWatcherProperties,
    val recordingEntityHelper: RecordingEntityHelper,
    val recordingFileHelper: RecordingFileHelper,
)
```

Change line 39 from:
```kotlin
.walk(applicationProperties.frigateRecordsFolder)
```
To:
```kotlin
.walk(recordsWatcherProperties.folder)
```

**Step 5: Update DetectServiceTest**

Remove `disableFirstScanTask` and `frigateRecordsFolder` from the `applicationProperties()` helper (lines 340-342):

```kotlin
private fun applicationProperties(serverProps: DetectServerProperties): ApplicationProperties {
    val dummyPath = Path.of(".")
    val dummyDuration = Duration.ofSeconds(1)

    return ApplicationProperties(
        tempFolder = dummyPath,
        ffmpegPath = dummyPath,
        connectionTimeout = dummyDuration,
        readTimeout = dummyDuration,
        writeTimeout = dummyDuration,
        responseTimeout = dummyDuration,
        detectServers = mapOf("test" to serverProps),
    )
}
```

**Step 6: Build to verify compilation**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

**Step 7: Run tests**

Run: `./gradlew test`
Expected: All tests pass

**Step 8: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/ApplicationProperties.kt
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/application/ApplicationListener.kt
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsTask.kt
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/FirstTimeScanTask.kt
git add modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/DetectServiceTest.kt
git commit -m "refactor: migrate watch settings to RecordsWatcherProperties"
```

---

### Task 4: Add date extraction and filtering to WatchRecordsTask

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsTask.kt`
- Create: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsTaskTest.kt`

**Step 1: Write unit test for date extraction**

```kotlin
package ru.zinin.frigate.analyzer.core.task

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class WatchRecordsTaskTest {
    @Test
    fun `extractDateFromPath returns date for date directory`() {
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-15")
        assertEquals(LocalDate.of(2026, 2, 15), extractDateFromPath(path))
    }

    @Test
    fun `extractDateFromPath returns date for hour subdirectory`() {
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-15/09")
        assertEquals(LocalDate.of(2026, 2, 15), extractDateFromPath(path))
    }

    @Test
    fun `extractDateFromPath returns date for camera subdirectory`() {
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-15/09/cam1")
        assertEquals(LocalDate.of(2026, 2, 15), extractDateFromPath(path))
    }

    @Test
    fun `extractDateFromPath returns null for root recordings directory`() {
        val path = Path.of("/mnt/data/frigate/recordings")
        assertNull(extractDateFromPath(path))
    }

    @Test
    fun `isWithinWatchPeriod returns true for today`() {
        val clock = Clock.fixed(Instant.parse("2026-02-15T12:00:00Z"), ZoneOffset.UTC)
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-15")
        assertTrue(isWithinWatchPeriod(path, Duration.ofDays(1), clock))
    }

    @Test
    fun `isWithinWatchPeriod returns true for yesterday within 1 day period`() {
        val clock = Clock.fixed(Instant.parse("2026-02-15T12:00:00Z"), ZoneOffset.UTC)
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-14")
        assertTrue(isWithinWatchPeriod(path, Duration.ofDays(1), clock))
    }

    @Test
    fun `isWithinWatchPeriod returns false for old date`() {
        val clock = Clock.fixed(Instant.parse("2026-02-15T12:00:00Z"), ZoneOffset.UTC)
        val path = Path.of("/mnt/data/frigate/recordings/2026-01-01")
        assertFalse(isWithinWatchPeriod(path, Duration.ofDays(1), clock))
    }

    @Test
    fun `isWithinWatchPeriod returns true for root directory without date`() {
        val clock = Clock.fixed(Instant.parse("2026-02-15T12:00:00Z"), ZoneOffset.UTC)
        val path = Path.of("/mnt/data/frigate/recordings")
        assertTrue(isWithinWatchPeriod(path, Duration.ofDays(1), clock))
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "*.WatchRecordsTaskTest"`
Expected: FAIL — functions `extractDateFromPath` and `isWithinWatchPeriod` not found

**Step 3: Implement date extraction as package-level functions**

Add to `WatchRecordsTask.kt` (as package-level functions, accessible from test):

```kotlin
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeParseException

private val DATE_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")

internal fun extractDateFromPath(path: Path): LocalDate? {
    for (i in path.nameCount - 1 downTo 0) {
        val name = path.getName(i).toString()
        if (DATE_PATTERN.matches(name)) {
            return try {
                LocalDate.parse(name)
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
    return null
}

internal fun isWithinWatchPeriod(
    path: Path,
    watchPeriod: Duration,
    clock: Clock,
): Boolean {
    val date = extractDateFromPath(path) ?: return true
    val cutoff = LocalDate.now(clock).minusDays(watchPeriod.toDays())
    return !date.isBefore(cutoff)
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "*.WatchRecordsTaskTest"`
Expected: PASS

**Step 5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsTask.kt
git add modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsTaskTest.kt
git commit -m "feat: add date extraction and watch period filtering"
```

---

### Task 5: Integrate filtering and cleanup into WatchRecordsTask

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsTask.kt`

**Step 1: Inject Clock, add filtering to registerAllDirs, add cleanup logic**

Full updated `WatchRecordsTask`:

```kotlin
package ru.zinin.frigate.analyzer.core.task

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.config.properties.RecordsWatcherProperties
import ru.zinin.frigate.analyzer.model.request.CreateRecordingRequest
import ru.zinin.frigate.analyzer.service.helper.RecordingEntityHelper
import ru.zinin.frigate.analyzer.service.helper.RecordingFileHelper
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.absolutePathString

private val logger = KotlinLogging.logger {}

private const val POLL_PERIOD = 500L

private val DATE_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")

internal fun extractDateFromPath(path: Path): LocalDate? {
    for (i in path.nameCount - 1 downTo 0) {
        val name = path.getName(i).toString()
        if (DATE_PATTERN.matches(name)) {
            return try {
                LocalDate.parse(name)
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
    return null
}

internal fun isWithinWatchPeriod(
    path: Path,
    watchPeriod: Duration,
    clock: Clock,
): Boolean {
    val date = extractDateFromPath(path) ?: return true
    val cutoff = LocalDate.now(clock).minusDays(watchPeriod.toDays())
    return !date.isBefore(cutoff)
}

@Component
class WatchRecordsTask(
    val recordsWatcherProperties: RecordsWatcherProperties,
    val recordingEntityHelper: RecordingEntityHelper,
    val recordingFileHelper: RecordingFileHelper,
    val clock: Clock,
) {
    private val stopped = AtomicBoolean(false)

    private val registeredDirs = ConcurrentHashMap<Path, WatchKey>()

    @Async
    fun run() {
        val folderPath = recordsWatcherProperties.folder
        logger.info { "Starting watch records in folder: $folderPath" }

        val watchService = FileSystems.getDefault().newWatchService()
        registerAllDirs(folderPath, watchService)

        logger.info { "Watch records started. Registered ${registeredDirs.size} directories." }

        var lastCleanup = Instant.now(clock)

        while (!stopped.get()) {
            val key = watchService.poll(POLL_PERIOD, TimeUnit.MILLISECONDS)

            if (key != null) {
                val dir = key.watchable() as Path

                for (event in key.pollEvents()) {
                    val kind = event.kind()
                    if (kind != ENTRY_CREATE) continue

                    val ev = event as WatchEvent<Path>
                    val fullPath = dir.resolve(ev.context())
                    logger.info { "New file created: $fullPath" }

                    if (Files.isDirectory(fullPath)) {
                        if (isWithinWatchPeriod(fullPath, recordsWatcherProperties.watchPeriod, clock)) {
                            registerAllDirs(fullPath, watchService)
                        } else {
                            logger.info { "Skipping old directory: $fullPath" }
                        }
                    } else {
                        val attrs = Files.readAttributes(fullPath, BasicFileAttributes::class.java)

                        val recordingFile = recordingFileHelper.parse(fullPath)

                        val recordingId =
                            runBlocking {
                                recordingEntityHelper.createRecording(
                                    CreateRecordingRequest(
                                        fullPath.absolutePathString(),
                                        attrs.creationTime().toInstant(),
                                        recordingFile.camId,
                                        recordingFile.date,
                                        recordingFile.time,
                                        recordingFile.timestamp,
                                    ),
                                )
                            }
                        logger.info { "Recording id: $recordingId" }
                    }
                }

                if (!key.reset()) {
                    registeredDirs.remove(dir)
                }
            }

            // Periodic cleanup of expired watches
            val now = Instant.now(clock)
            if (Duration.between(lastCleanup, now) >= recordsWatcherProperties.cleanupInterval) {
                cleanupExpiredDirs()
                lastCleanup = now
            }
        }

        watchService.close()
        logger.info { "Finished watching records." }
    }

    fun shutdown() {
        stopped.set(true)
        logger.info { "Shutting down watch records..." }

        registeredDirs.values.forEach { it.cancel() }
        registeredDirs.clear()
    }

    private fun registerAllDirs(
        start: Path,
        watchService: WatchService,
    ) {
        var registered = 0
        var skipped = 0

        Files
            .walk(start)
            .filter { Files.isDirectory(it) }
            .forEach { dir ->
                if (isWithinWatchPeriod(dir, recordsWatcherProperties.watchPeriod, clock)) {
                    registeredDirs.computeIfAbsent(dir) {
                        val key = dir.register(watchService, ENTRY_CREATE)
                        registered++
                        key
                    }
                } else {
                    skipped++
                }
            }

        if (skipped > 0) {
            logger.info { "Registered $registered directories, skipped $skipped old directories." }
        }
    }

    private fun cleanupExpiredDirs() {
        var removed = 0

        registeredDirs.entries.removeIf { (dir, watchKey) ->
            if (!isWithinWatchPeriod(dir, recordsWatcherProperties.watchPeriod, clock)) {
                watchKey.cancel()
                removed++
                true
            } else {
                false
            }
        }

        if (removed > 0) {
            logger.info { "Cleanup: removed $removed expired watch keys. Active watches: ${registeredDirs.size}" }
        }
    }
}
```

**Step 2: Run all tests**

Run: `./gradlew test`
Expected: All tests pass

**Step 3: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsTask.kt
git commit -m "feat: integrate date filtering and periodic cleanup into WatchRecordsTask"
```

---

### Task 6: Update documentation

**Files:**
- Modify: `.claude/rules/configuration.md` — add WATCH_PERIOD, WATCH_CLEANUP_INTERVAL; update DISABLE_FIRST_SCAN and FRIGATE_RECORDS_FOLDER to reflect new yaml path
- Modify: `.claude/rules/pipeline.md` — update WatchRecordsTask description to mention selective watching

**Step 1: Update configuration.md**

In "Core Settings" table, move `FRIGATE_RECORDS_FOLDER` and `DISABLE_FIRST_SCAN` to a new "Records Watcher" section and add:

| Variable | Default | Purpose |
|----------|---------|---------|
| `FRIGATE_RECORDS_FOLDER` | /mnt/data/frigate/recordings/ | Frigate recordings path |
| `DISABLE_FIRST_SCAN` | false | Skip initial scan on startup |
| `WATCH_PERIOD` | P1D | ISO-8601 duration, how far back to watch directories |
| `WATCH_CLEANUP_INTERVAL` | PT1H | How often to clean up expired watch keys |

**Step 2: Update pipeline.md**

Update "File Watching" section to mention selective watching behavior.

**Step 3: Commit**

```bash
git add .claude/rules/configuration.md .claude/rules/pipeline.md
git commit -m "docs: update rules for selective watch configuration"
```

---

### Task 7: Final verification

**Step 1: Run full build with tests**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

**Step 2: Run ktlint**

Run: `./gradlew ktlintCheck`
Expected: No violations (or fix with `./gradlew ktlintFormat`)

**Step 3: Final commit if needed after formatting**
