# TempFileHelper Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Create a centralized temp file management component and refactor DetectService to use it.

**Architecture:** Kotlin coroutine-based `@Component` in `core/helper/` package. All blocking I/O in `withContext(Dispatchers.IO)`. File reads/writes via `Flow<ByteArray>` for chunked processing. Periodic cleanup via `@Scheduled`.

**Tech Stack:** Kotlin coroutines, Spring `@Component`/`@Scheduled`, JUnit 5, `@TempDir`, `Clock.fixed()`

---

### Task 1: TempFileHelper — create empty temp file

**Files:**
- Create: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/helper/TempFileHelperTest.kt`
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/helper/TempFileHelper.kt`

**Step 1: Write the failing test**

```kotlin
package ru.zinin.frigate.analyzer.core.helper

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.zinin.frigate.analyzer.core.config.properties.ApplicationProperties
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class TempFileHelperTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var helper: TempFileHelper

    private val clock = Clock.fixed(Instant.parse("2026-02-17T10:30:45Z"), ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        val properties = ApplicationProperties(
            tempFolder = tempDir,
            ffmpegPath = Path.of("/usr/bin/ffmpeg"),
            connectionTimeout = Duration.ofSeconds(5),
            readTimeout = Duration.ofSeconds(5),
            writeTimeout = Duration.ofSeconds(5),
            responseTimeout = Duration.ofSeconds(5),
        )
        helper = TempFileHelper(properties, clock)
        helper.init()
    }

    @Test
    fun `createTempFile creates empty file with correct prefix`() = runTest {
        val path = helper.createTempFile("test-", ".txt")

        assertTrue(Files.exists(path))
        assertTrue(path.fileName.toString().startsWith("frigate-analyzer-tmp-2026-02-17-10-30-45-test-"))
        assertTrue(path.fileName.toString().endsWith(".txt"))
        assertTrue(path.startsWith(tempDir))
        assertTrue(Files.size(path) == 0L)
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :frigate-analyzer-core:test --tests "ru.zinin.frigate.analyzer.core.helper.TempFileHelperTest.createTempFile creates empty file with correct prefix"`
Expected: FAIL — class TempFileHelper does not exist

**Step 3: Write minimal implementation**

```kotlin
package ru.zinin.frigate.analyzer.core.helper

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.config.properties.ApplicationProperties
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

@Component
class TempFileHelper(
    private val applicationProperties: ApplicationProperties,
    private val clock: Clock,
) {
    private lateinit var tempDirPath: Path

    @PostConstruct
    fun init() {
        tempDirPath = applicationProperties.tempFolder
        if (Files.exists(tempDirPath)) {
            check(Files.isDirectory(tempDirPath)) { "tempFolder='$tempDirPath' is not a directory" }
            logger.debug { "tempFolder='$tempDirPath' exists" }
        } else {
            Files.createDirectories(tempDirPath)
            logger.debug { "tempFolder='$tempDirPath' created" }
        }
    }

    suspend fun createTempFile(prefix: String, suffix: String): Path =
        withContext(Dispatchers.IO) {
            val fullPrefix = PREFIX + DATE_FORMAT.format(LocalDateTime.now(clock)) + "-" + prefix
            Files.createTempFile(tempDirPath, fullPrefix, suffix)
        }

    companion object {
        private const val PREFIX = "frigate-analyzer-tmp-"
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")
    }
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :frigate-analyzer-core:test --tests "ru.zinin.frigate.analyzer.core.helper.TempFileHelperTest.createTempFile creates empty file with correct prefix"`
Expected: PASS

**Step 5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/helper/TempFileHelper.kt \
       modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/helper/TempFileHelperTest.kt
git commit -m "feat: add TempFileHelper with createTempFile (FA-18)"
```

---

### Task 2: TempFileHelper — write byte array to temp file

**Files:**
- Modify: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/helper/TempFileHelperTest.kt`
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/helper/TempFileHelper.kt`

**Step 1: Write the failing test**

Add to `TempFileHelperTest`:

```kotlin
@Test
fun `createTempFile with byte array writes content`() = runTest {
    val content = "Hello, world!".toByteArray()
    val path = helper.createTempFile("data-", ".bin", content)

    assertTrue(Files.exists(path))
    assertTrue(path.fileName.toString().startsWith("frigate-analyzer-tmp-"))
    assertArrayEquals(content, Files.readAllBytes(path))
}
```

Add import: `import org.junit.jupiter.api.Assertions.assertArrayEquals`

**Step 2: Run test to verify it fails**

Run: `./gradlew :frigate-analyzer-core:test --tests "ru.zinin.frigate.analyzer.core.helper.TempFileHelperTest.createTempFile with byte array writes content"`
Expected: FAIL — no matching overload

**Step 3: Write minimal implementation**

Add to `TempFileHelper`:

```kotlin
suspend fun createTempFile(prefix: String, suffix: String, content: ByteArray): Path {
    val path = createTempFile(prefix, suffix)
    try {
        withContext(Dispatchers.IO) {
            Files.write(path, content)
        }
    } catch (e: Exception) {
        withContext(Dispatchers.IO) { Files.deleteIfExists(path) }
        throw e
    }
    return path
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :frigate-analyzer-core:test --tests "ru.zinin.frigate.analyzer.core.helper.TempFileHelperTest.createTempFile with byte array writes content"`
Expected: PASS

**Step 5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/helper/TempFileHelper.kt \
       modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/helper/TempFileHelperTest.kt
git commit -m "feat: add TempFileHelper.createTempFile with ByteArray (FA-18)"
```

---

### Task 3: TempFileHelper — write Flow<ByteArray> to temp file

**Files:**
- Modify: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/helper/TempFileHelperTest.kt`
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/helper/TempFileHelper.kt`

**Step 1: Write the failing test**

Add to `TempFileHelperTest`:

```kotlin
@Test
fun `createTempFile with flow writes chunked content`() = runTest {
    val chunk1 = "Hello, ".toByteArray()
    val chunk2 = "world!".toByteArray()
    val content = flowOf(chunk1, chunk2)

    val path = helper.createTempFile("flow-", ".bin", content)

    assertTrue(Files.exists(path))
    assertArrayEquals("Hello, world!".toByteArray(), Files.readAllBytes(path))
}
```

Add import: `import kotlinx.coroutines.flow.flowOf`

**Step 2: Run test to verify it fails**

Run: `./gradlew :frigate-analyzer-core:test --tests "ru.zinin.frigate.analyzer.core.helper.TempFileHelperTest.createTempFile with flow writes chunked content"`
Expected: FAIL — no matching overload for Flow

**Step 3: Write minimal implementation**

Add to `TempFileHelper`:

```kotlin
suspend fun createTempFile(prefix: String, suffix: String, content: Flow<ByteArray>): Path {
    val path = createTempFile(prefix, suffix)
    try {
        withContext(Dispatchers.IO) {
            path.outputStream().buffered().use { out ->
                content.collect { chunk -> out.write(chunk) }
            }
        }
    } catch (e: Exception) {
        withContext(Dispatchers.IO) { Files.deleteIfExists(path) }
        throw e
    }
    return path
}
```

Add import: `import kotlinx.coroutines.flow.Flow`

**Step 4: Run test to verify it passes**

Run: `./gradlew :frigate-analyzer-core:test --tests "ru.zinin.frigate.analyzer.core.helper.TempFileHelperTest.createTempFile with flow writes chunked content"`
Expected: PASS

**Step 5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/helper/TempFileHelper.kt \
       modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/helper/TempFileHelperTest.kt
git commit -m "feat: add TempFileHelper.createTempFile with Flow<ByteArray> (FA-18)"
```

---

### Task 4: TempFileHelper — read file as Flow<ByteArray>

**Files:**
- Modify: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/helper/TempFileHelperTest.kt`
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/helper/TempFileHelper.kt`

**Step 1: Write the failing tests**

Add to `TempFileHelperTest`:

```kotlin
@Test
fun `readFile returns file content in chunks`() = runTest {
    val content = ByteArray(100_000) { (it % 256).toByte() }
    val path = helper.createTempFile("read-", ".bin", content)

    val result = ByteArrayOutputStream()
    helper.readFile(path, bufferSize = 1024).collect { chunk ->
        result.write(chunk)
    }

    assertArrayEquals(content, result.toByteArray())
}

@Test
fun `readFile returns empty flow for empty file`() = runTest {
    val path = helper.createTempFile("empty-", ".bin")

    val chunks = mutableListOf<ByteArray>()
    helper.readFile(path).collect { chunks.add(it) }

    assertTrue(chunks.isEmpty())
}
```

Add imports:
```kotlin
import java.io.ByteArrayOutputStream
```

**Step 2: Run tests to verify they fail**

Run: `./gradlew :frigate-analyzer-core:test --tests "ru.zinin.frigate.analyzer.core.helper.TempFileHelperTest.readFile*"`
Expected: FAIL — readFile method does not exist

**Step 3: Write minimal implementation**

Add to `TempFileHelper`:

```kotlin
fun readFile(path: Path, bufferSize: Int = BUFFER_SIZE): Flow<ByteArray> {
    require(bufferSize > 0) { "bufferSize must be positive" }
    requirePathInTempDir(path)
    return flow {
        path.inputStream().buffered().use { stream ->
            val buffer = ByteArray(bufferSize)
            while (true) {
                val bytesRead = stream.read(buffer)
                if (bytesRead == -1) break
                emit(buffer.copyOf(bytesRead))
            }
        }
    }.flowOn(Dispatchers.IO)
}
```

Add imports:
```kotlin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
```

Add to companion object:
```kotlin
private const val BUFFER_SIZE = 32 * 1024
```

Add private helper method:
```kotlin
private fun requirePathInTempDir(path: Path) {
    require(path.normalize().startsWith(tempDirPath)) {
        "Path '$path' is outside tempFolder '$tempDirPath'"
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew :frigate-analyzer-core:test --tests "ru.zinin.frigate.analyzer.core.helper.TempFileHelperTest.readFile*"`
Expected: PASS

**Step 5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/helper/TempFileHelper.kt \
       modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/helper/TempFileHelperTest.kt
git commit -m "feat: add TempFileHelper.readFile with Flow<ByteArray> (FA-18)"
```

---

### Task 5: TempFileHelper — delete files

**Files:**
- Modify: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/helper/TempFileHelperTest.kt`
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/helper/TempFileHelper.kt`

**Step 1: Write the failing tests**

Add to `TempFileHelperTest`:

```kotlin
@Test
fun `deleteIfExists deletes file and returns true`() = runTest {
    val path = helper.createTempFile("del-", ".tmp")
    assertTrue(Files.exists(path))

    val result = helper.deleteIfExists(path)

    assertTrue(result)
    assertTrue(Files.notExists(path))
}

@Test
fun `deleteIfExists returns false for non-existent file`() = runTest {
    val path = tempDir.resolve("non-existent.tmp")

    val result = helper.deleteIfExists(path)

    assertFalse(result)
}

@Test
fun `deleteFiles deletes multiple files and returns count`() = runTest {
    val path1 = helper.createTempFile("multi1-", ".tmp")
    val path2 = helper.createTempFile("multi2-", ".tmp")
    val nonExistent = tempDir.resolve("gone.tmp")

    val count = helper.deleteFiles(listOf(path1, path2, nonExistent))

    assertEquals(2, count)
    assertTrue(Files.notExists(path1))
    assertTrue(Files.notExists(path2))
}
```

Add imports:
```kotlin
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
```

**Step 2: Run tests to verify they fail**

Run: `./gradlew :frigate-analyzer-core:test --tests "ru.zinin.frigate.analyzer.core.helper.TempFileHelperTest.delete*"`
Expected: FAIL — methods do not exist

**Step 3: Write minimal implementation**

Add to `TempFileHelper`:

```kotlin
suspend fun deleteIfExists(path: Path): Boolean {
    requirePathInTempDir(path)
    return withContext(Dispatchers.IO) {
        if (Files.isDirectory(path)) {
            logger.warn { "Refusing to delete directory: $path" }
            return@withContext false
        }
        Files.deleteIfExists(path)
    }
}

suspend fun deleteFiles(files: List<Path>): Int =
    withContext(Dispatchers.IO) {
        var count = 0
        for (file in files) {
            try {
                requirePathInTempDir(file)
                if (Files.isDirectory(file)) {
                    logger.warn { "Skipping directory: $file" }
                    continue
                }
                if (Files.deleteIfExists(file)) {
                    count++
                }
            } catch (e: IOException) {
                logger.error(e) { "Error deleting file: $file" }
            }
        }
        count
    }
```

Add import: `import java.io.IOException`

**Step 4: Run tests to verify they pass**

Run: `./gradlew :frigate-analyzer-core:test --tests "ru.zinin.frigate.analyzer.core.helper.TempFileHelperTest.delete*"`
Expected: PASS

**Step 5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/helper/TempFileHelper.kt \
       modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/helper/TempFileHelperTest.kt
git commit -m "feat: add TempFileHelper.deleteIfExists and deleteFiles (FA-18)"
```

---

### Task 6: TempFileHelper — findOldFiles and cleanOldFiles

**Files:**
- Modify: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/helper/TempFileHelperTest.kt`
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/helper/TempFileHelper.kt`

**Step 1: Write the failing tests**

Add to `TempFileHelperTest`:

```kotlin
@Test
fun `findOldFiles returns files older than MAX_AGE_DAYS`() = runTest {
    // clock is fixed at 2026-02-17T10:30:45Z
    // Create a file with timestamp 8 days ago (older than 7 days)
    val oldPrefix = "frigate-analyzer-tmp-2026-02-09-10-30-45-old-"
    Files.createTempFile(tempDir, oldPrefix, ".tmp")

    // Create a file with timestamp today (fresh)
    val freshPrefix = "frigate-analyzer-tmp-2026-02-17-10-30-45-fresh-"
    Files.createTempFile(tempDir, freshPrefix, ".tmp")

    // Create a non-matching file
    Files.createTempFile(tempDir, "unrelated-", ".tmp")

    val oldFiles = helper.findOldFiles()

    assertEquals(1, oldFiles.size)
    assertTrue(oldFiles[0].fileName.toString().contains("old-"))
}

@Test
fun `cleanOldFiles deletes stale files`() = runTest {
    val oldPrefix = "frigate-analyzer-tmp-2026-02-09-10-30-45-stale-"
    val oldFile = Files.createTempFile(tempDir, oldPrefix, ".tmp")

    val freshPrefix = "frigate-analyzer-tmp-2026-02-17-10-30-45-keep-"
    val freshFile = Files.createTempFile(tempDir, freshPrefix, ".tmp")

    helper.cleanOldFiles()

    assertTrue(Files.notExists(oldFile))
    assertTrue(Files.exists(freshFile))
}

@Test
fun `findOldFiles skips files with malformed timestamp`() = runTest {
    // Valid old file
    val oldPrefix = "frigate-analyzer-tmp-2026-02-09-10-30-45-old-"
    Files.createTempFile(tempDir, oldPrefix, ".tmp")

    // Malformed timestamp (99 month)
    val malformedPrefix = "frigate-analyzer-tmp-2026-99-09-10-30-45-bad-"
    Files.createTempFile(tempDir, malformedPrefix, ".tmp")

    val oldFiles = helper.findOldFiles()

    assertEquals(1, oldFiles.size)
    assertTrue(oldFiles[0].fileName.toString().contains("old-"))
}
```

**Step 2: Run tests to verify they fail**

Run: `./gradlew :frigate-analyzer-core:test --tests "ru.zinin.frigate.analyzer.core.helper.TempFileHelperTest.findOldFiles*" --tests "ru.zinin.frigate.analyzer.core.helper.TempFileHelperTest.cleanOldFiles*"`
Expected: FAIL — methods do not exist

**Step 3: Write minimal implementation**

Add to `TempFileHelper`:

```kotlin
suspend fun findOldFiles(): List<Path> =
    withContext(Dispatchers.IO) {
        Files.list(tempDirPath).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().startsWith(PREFIX) }
                .filter { path ->
                    val filename = path.fileName.toString()
                    val matcher = PATTERN.matcher(filename)
                    if (matcher.matches()) {
                        try {
                            val timestamp = LocalDateTime.parse(matcher.group(1), DATE_FORMAT)
                            timestamp.isBefore(LocalDateTime.now(clock).minusDays(MAX_AGE_DAYS))
                        } catch (e: java.time.format.DateTimeParseException) {
                            logger.warn(e) { "Cannot parse timestamp from filename: $filename" }
                            false
                        }
                    } else {
                        false
                    }
                }
                .toList()
        }
    }

@Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1H")
fun cleanOldFiles() {
    runBlocking(Dispatchers.IO) {
        logger.debug { "Cleaning old temp files..." }
        val oldFiles = findOldFiles()
        val count = deleteFiles(oldFiles)
        logger.debug { "Cleaned $count old temp files" }
    }
}
```

Add imports:
```kotlin
import kotlinx.coroutines.runBlocking
import org.springframework.scheduling.annotation.Scheduled
import java.util.regex.Pattern
```

Add to companion object:
```kotlin
private const val MAX_AGE_DAYS = 7L
private val PATTERN = Pattern.compile("""frigate-analyzer-tmp-(\d{4}-\d{2}-\d{2}-\d{2}-\d{2}-\d{2})-.+""")
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew :frigate-analyzer-core:test --tests "ru.zinin.frigate.analyzer.core.helper.TempFileHelperTest"`
Expected: ALL PASS

**Step 5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/helper/TempFileHelper.kt \
       modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/helper/TempFileHelperTest.kt
git commit -m "feat: add TempFileHelper.findOldFiles and @Scheduled cleanOldFiles (FA-18)"
```

---

### Task 7: Refactor DetectService to use TempFileHelper

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/DetectService.kt`
- Modify: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/DetectServiceTest.kt`
- Modify: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/VideoVisualizationServiceTest.kt`

**Step 1: Modify DetectService constructor**

Add `TempFileHelper` dependency, remove `ApplicationProperties` (only used for `tempFolder`):

In `DetectService.kt`, change constructor:

```kotlin
@Service
class DetectService(
    private val webClient: WebClient,
    private val detectServerLoadBalancer: DetectServerLoadBalancer,
    private val detectProperties: DetectProperties,
    private val tempFileHelper: TempFileHelper,
) {
```

Remove import: `import ru.zinin.frigate.analyzer.core.config.properties.ApplicationProperties`
Add import: `import ru.zinin.frigate.analyzer.core.helper.TempFileHelper`

**Step 2: Refactor downloadJobResult**

Replace lines 359-392 of `DetectService.kt`:

```kotlin
suspend fun downloadJobResult(
    acquired: AcquiredServer,
    jobId: String,
): Path {
    val tempFile = tempFileHelper.createTempFile("video-annotated-", ".mp4")

    try {
        val flux =
            webClient
                .get()
                .uri { uriBuilder ->
                    uriBuilder
                        .scheme(acquired.schema)
                        .host(acquired.host)
                        .port(acquired.port)
                        .path("/jobs/{jobId}/download")
                        .build(jobId)
                }.retrieve()
                .bodyToFlux<DataBuffer>()

        DataBufferUtils
            .write(flux, tempFile, StandardOpenOption.WRITE)
            .then()
            .awaitSingleOrNull()
    } catch (e: Exception) {
        tempFileHelper.deleteIfExists(tempFile)
        throw e
    }

    return tempFile
}
```

Remove unused imports from `DetectService.kt`:
- `import java.nio.file.Files`
- `import kotlinx.coroutines.Dispatchers`
- `import kotlinx.coroutines.withContext` (check if used elsewhere — `withContext` is still used in `retryWithTimeout` via `withTimeout`, but `Dispatchers` and `withContext` are NOT directly used outside `downloadJobResult`)

Actually, check carefully: `Dispatchers` and `withContext` are NOT used in other methods of DetectService. Only `withTimeout` and `delay` are used. So these imports can be removed.

**Step 3: Update DetectServiceTest**

In `DetectServiceTest.kt`, the constructor of `DetectService` will need updating. Read the test file to see how `DetectService` is instantiated and update to pass `TempFileHelper` instead of `ApplicationProperties`.

The test currently passes `ApplicationProperties` to `DetectService`. Note: `ApplicationProperties` is still needed for `DetectServerLoadBalancer` — only `DetectService` stops using it directly.

Replace `DetectService` instantiation with `TempFileHelper`:

```kotlin
// In setUp or wherever DetectService is created:
// applicationProperties is still created for DetectServerLoadBalancer (unchanged)
val tempFileHelper = TempFileHelper(applicationProperties(serverProps), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
tempFileHelper.init()
val detectService = DetectService(webClient, loadBalancer, detectProperties, tempFileHelper)
```

**Step 3b: Update VideoVisualizationServiceTest**

Same change — `VideoVisualizationServiceTest` also creates `DetectService(...)` directly (line 82). Update to pass `TempFileHelper` instead of `ApplicationProperties`:

```kotlin
val tempFileHelper = TempFileHelper(applicationProperties(serverProps), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
tempFileHelper.init()
val detectService = DetectService(webClient, loadBalancer, detectProperties, tempFileHelper)
```

**Step 4: Run all tests**

Run: `./gradlew :frigate-analyzer-core:test`
Expected: ALL PASS

**Step 5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/DetectService.kt \
       modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/DetectServiceTest.kt \
       modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/VideoVisualizationServiceTest.kt
git commit -m "refactor: use TempFileHelper in DetectService (FA-18)"
```

---

### Task 8: Full build verification

**Step 1: Run ktlint**

Run: `./gradlew ktlintCheck`
If errors: `./gradlew ktlintFormat`, then re-check.

**Step 2: Run full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

**Step 3: Commit any formatting fixes**

```bash
git add -u
git commit -m "style: fix ktlint formatting (FA-18)"
```
