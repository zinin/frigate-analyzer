# TempFileHelper Design

## Purpose

Centralized component for temporary file management: creation, reading, writing, deletion, and periodic cleanup of stale files. Replaces scattered temp file logic in DetectService and provides reusable utility for FA-18 and future features.

## Module & Location

- **Module:** `core`
- **Package:** `ru.zinin.frigate.analyzer.core.helper`
- **File:** `TempFileHelper.kt`

## Dependencies

- `ApplicationProperties` — provides `tempFolder` path
- `Clock` — testable time source for file timestamps

## File Naming Convention

```
frigate-analyzer-tmp-{yyyy-MM-dd-HH-mm-ss}-{prefix}{random}{suffix}
```

Timestamp embedded in filename enables age-based cleanup without filesystem metadata queries.

## API

```kotlin
@Component
class TempFileHelper(
    private val applicationProperties: ApplicationProperties,
    private val clock: Clock,
) {
    companion object {
        private const val PREFIX = "frigate-analyzer-tmp-"
        private const val BUFFER_SIZE = 32 * 1024
        private const val MAX_AGE_DAYS = 7L
    }

    @PostConstruct
    fun init()

    suspend fun createTempFile(prefix: String, suffix: String): Path

    suspend fun createTempFile(prefix: String, suffix: String, content: ByteArray): Path

    suspend fun createTempFile(prefix: String, suffix: String, content: Flow<ByteArray>): Path

    fun readFile(path: Path, bufferSize: Int = BUFFER_SIZE): Flow<ByteArray>

    suspend fun deleteFiles(files: List<Path>): Int

    suspend fun deleteIfExists(path: Path): Boolean

    suspend fun findOldFiles(): List<Path>

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1H")
    fun cleanOldFiles()
}
```

## Implementation Details

### Initialization (@PostConstruct)

Verify `applicationProperties.tempFolder` exists and is a directory. Create if missing. No fallback to `java.io.tmpdir` — property is `@NotNull` validated.

### File Operations

All blocking I/O wrapped in `withContext(Dispatchers.IO)`:

- **createTempFile(prefix, suffix):** Creates empty temp file via `Files.createTempFile(tempFolder, fullPrefix, suffix)`
- **createTempFile(prefix, suffix, content: ByteArray):** Creates file, writes content in one call. On write failure, deletes the created file before re-throwing.
- **createTempFile(prefix, suffix, content: Flow<ByteArray>):** Creates file, writes chunked content from Flow — no full file in memory. On write failure, deletes the created file before re-throwing.
- **readFile(path, bufferSize):** Returns `Flow<ByteArray>` — reads chunks of `bufferSize`, back-pressure via Flow's pull-based nature. Validates `bufferSize > 0`. Validates path is inside `tempFolder`. Uses `.flowOn(Dispatchers.IO)` for context safety.
- **deleteFiles(files):** Deletes list of files (regular files only, skips directories), returns count of successfully deleted. Validates paths are inside `tempFolder`.
- **deleteIfExists(path):** Deletes single file (regular file only), returns true if existed. Validates path is inside `tempFolder`.

### Periodic Cleanup

`@Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1H")` — runs hourly.

Scans `tempFolder` for files matching `frigate-analyzer-tmp-{timestamp}-*` pattern, parses timestamp, deletes files older than 7 days. Handles `DateTimeParseException` per-file (log + skip) to avoid one corrupted filename breaking the entire cleanup.

Uses `runBlocking(Dispatchers.IO)` inside `@Scheduled` method since Spring scheduling doesn't support coroutines natively.

### Path Validation

Methods that accept `Path` (`readFile`, `deleteIfExists`, `deleteFiles`) validate that `path.normalize().startsWith(tempDirPath)`. Throws `IllegalArgumentException` if path is outside `tempFolder`.

### Error Handling

- I/O errors logged via kotlin-logging, re-thrown to caller
- `createTempFile` with content: on write failure, deletes created file before re-throwing
- Cleanup job logs errors per-file but continues processing remaining files

## Refactoring

### DetectService.downloadJobResult()

Before:
```kotlin
val tempFile = withContext(Dispatchers.IO) {
    Files.createTempFile(applicationProperties.tempFolder, "video-annotated-", ".mp4")
}
// ...
withContext(Dispatchers.IO) { Files.deleteIfExists(tempFile) }
```

After:
```kotlin
val tempFile = tempFileHelper.createTempFile("video-annotated-", ".mp4")
// ...
tempFileHelper.deleteIfExists(tempFile)
```

`DataBufferUtils.write(flux, tempFile)` remains unchanged — it writes from WebClient stream.

### VideoServiceImpl — NOT refactored

Uses `Files.createDirectories()` for ffmpeg work directory, not temp files. Different pattern, different module (`service` has no dependency on `core`).

## Testing

Unit tests with JUnit 5:

- `@TempDir` for isolated filesystem
- `Clock.fixed()` for deterministic timestamps
- Test cases: create/read/write files, findOldFiles with aged timestamps, cleanOldFiles deletes stale files
- Negative tests: malformed filename in findOldFiles (skip + continue), path validation rejection, write failure cleanup
