# WatchService Registration Performance — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Сократить регистрацию каталогов в WatchService с 9 минут 9 секунд до миллисекунд, отсекая поддеревья дат вне окна наблюдения и не спускаясь ниже каталога камеры.

**Architecture:** `Files.walk` (плоский неуправляемый стрим по всем ~3.2 млн файлов) заменяется на `Files.walkFileTree` с visitor'ом, который в `preVisitDirectory` возвращает `SKIP_SUBTREE` для дат вне окна и для каталогов камер. Арифметика путей (дата, окно, prune-предикат, глубина) выносится в отдельный файл `RecordingsTree.kt` и переиспользуется в `FirstTimeScanTask`, который получает собственное окно `FIRST_SCAN_PERIOD`.

**Tech Stack:** Kotlin 2.4.10, Spring Boot 4.1.0, Java 25 NIO (`walkFileTree`), Coroutines/Flow, JUnit 5, mockk, AssertJ, ktlint.

**Spec:** `docs/superpowers/specs/2026-08-02-watch-records-registration-design.md`

## Global Constraints

- Ветка работы — `perf/watch-records-registration`. Она уже создана и содержит коммит со спекой.
- **НИКОГДА не запускать `./gradlew` напрямую.** Любая сборка, тест или линт — через агент `claude-forge:build-runner` (или команду `/build`). Это правило из `CLAUDE.md`.
- После реализации каждой задачи: сначала агент код-ревью (`superpowers:requesting-code-review`), чинить критичные замечания до чистоты, только потом сборка.
- На ошибках ktlint: `./gradlew ktlintFormat` (через build-runner), затем повторить.
- **Всегда `git add <file>` после создания или изменения файла.** Правило из `CLAUDE.md`.
- Gradle-путь модуля — `:frigate-analyzer-core` (имена проектов переименованы в `settings.gradle.kts`). Тесты модуля: `./gradlew :frigate-analyzer-core:test`.
- Gradle запускает тесты с рабочим каталогом = каталог модуля (`modules/core`). Это уже используется в `ObjectTrackerPropertiesBindingTest`.
- Весь код задач живёт в пакете `ru.zinin.frigate.analyzer.core.task`. Функции с видимостью `internal` в одном пакете доступны без импортов — ни один импорт при переезде между файлами пакета не меняется.
- Тесты создают деревья через `Files.createTempDirectory` и убирают в `finally { root.toFile().deleteRecursively() }` — конвенция существующего `WatchRecordsLoopTest`.
- Продовый `modules/core/src/main/resources/application.yaml` **затеняется** тестовым `modules/core/src/test/resources/application.yaml` на тестовом classpath. Проверять продовый yaml можно только через `FileSystemResource("src/main/resources/application.yaml")` — как это делает `ObjectTrackerPropertiesBindingTest`.
- Перед созданием PR: `git rm` всех файлов из `docs/superpowers/` и коммит — планы и спеки не должны попадать в диф PR. Правило из глобального `CLAUDE.md`.

---

### Task 1: `RecordingsTree.kt` — арифметика путей по дереву записей

Выделяет из `WatchRecordsLoop.kt` работу с путями и добавляет три функции, на которых держится prune. Поведения не меняет — это фундамент для Task 2.

**Files:**
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/RecordingsTree.kt`
- Create: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/RecordingsTreeTest.kt`
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsLoop.kt:21-23` (импорты), `:180-210` (переезжающие функции)
- Modify: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsLoopTest.kt:10-13` (импорты), `:36` (ROOT), `:225-304` (переезжающие тесты)

**Interfaces:**
- Consumes: ничего (первая задача)
- Produces:
  - `internal const val CAMERA_DEPTH: Int = 3`
  - `internal fun watchCutoff(watchPeriod: Duration, clock: Clock): LocalDate`
  - `internal fun extractDateFromPath(path: Path, rootFolder: Path): LocalDate?`
  - `internal fun isWithinWatchPeriod(path: Path, rootFolder: Path, watchPeriod: Duration, clock: Clock): Boolean`
  - `internal fun isPrunableDate(path: Path, rootFolder: Path, cutoff: LocalDate): Boolean`
  - `internal fun depthFromRoot(path: Path, rootFolder: Path): Int`

- [ ] **Step 1: Написать падающий тест на новые функции**

Создать `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/RecordingsTreeTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.task

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private val ROOT = Path.of("/mnt/data/frigate/recordings")
private val CLOCK = Clock.fixed(Instant.parse("2026-02-15T12:00:00Z"), ZoneOffset.UTC)

class RecordingsTreeTest {
    @Test
    fun `watchCutoff subtracts whole days in UTC`() {
        assertEquals(LocalDate.of(2026, 2, 14), watchCutoff(Duration.ofDays(1), CLOCK))
        assertEquals(LocalDate.of(2026, 2, 15), watchCutoff(Duration.ZERO, CLOCK))
        assertEquals(LocalDate.of(2026, 2, 8), watchCutoff(Duration.ofDays(7), CLOCK))
    }

    @Test
    fun `isPrunableDate returns false for a path without a date`() {
        assertFalse(isPrunableDate(ROOT, ROOT, LocalDate.of(2026, 2, 14)))
    }

    @Test
    fun `isPrunableDate returns false for a date on the cutoff`() {
        assertFalse(isPrunableDate(ROOT.resolve("2026-02-14"), ROOT, LocalDate.of(2026, 2, 14)))
    }

    @Test
    fun `isPrunableDate returns true for a date before the cutoff`() {
        assertTrue(isPrunableDate(ROOT.resolve("2026-02-13"), ROOT, LocalDate.of(2026, 2, 14)))
    }

    @Test
    fun `isPrunableDate inherits the date of an hour or camera directory`() {
        val cutoff = LocalDate.of(2026, 2, 14)
        assertTrue(isPrunableDate(ROOT.resolve("2026-02-13/09"), ROOT, cutoff))
        assertTrue(isPrunableDate(ROOT.resolve("2026-02-13/09/cam1"), ROOT, cutoff))
        assertFalse(isPrunableDate(ROOT.resolve("2026-02-15/09/cam1"), ROOT, cutoff))
    }

    @Test
    fun `isPrunableDate never prunes the root that isWithinWatchPeriod admits`() {
        val cutoff = watchCutoff(Duration.ofDays(1), CLOCK)
        assertTrue(isWithinWatchPeriod(ROOT, ROOT, Duration.ofDays(1), CLOCK))
        assertFalse(isPrunableDate(ROOT, ROOT, cutoff))
    }

    @Test
    fun `isPrunableDate is the exact complement of isWithinWatchPeriod for dated paths`() {
        val cutoff = watchCutoff(Duration.ofDays(1), CLOCK)
        listOf("2026-02-16", "2026-02-15", "2026-02-14", "2026-02-13", "2025-12-31").forEach { date ->
            val path = ROOT.resolve(date)
            assertEquals(
                !isWithinWatchPeriod(path, ROOT, Duration.ofDays(1), CLOCK),
                isPrunableDate(path, ROOT, cutoff),
                "mismatch for $date",
            )
        }
    }

    @Test
    fun `depthFromRoot returns 0 for the root itself`() {
        // Regression guard: rootFolder.relativize(rootFolder) is the EMPTY path, whose nameCount
        // is 1, not 0. Without the guard the root is classified as a date directory.
        assertEquals(0, depthFromRoot(ROOT, ROOT))
    }

    @Test
    fun `depthFromRoot returns 1 2 3 for date hour and camera`() {
        assertEquals(1, depthFromRoot(ROOT.resolve("2026-02-15"), ROOT))
        assertEquals(2, depthFromRoot(ROOT.resolve("2026-02-15/09"), ROOT))
        assertEquals(3, depthFromRoot(ROOT.resolve("2026-02-15/09/cam1"), ROOT))
    }

    @Test
    fun `depthFromRoot returns -1 for a path outside the root`() {
        assertEquals(-1, depthFromRoot(Path.of("/var/tmp/elsewhere"), ROOT))
    }

    @Test
    fun `CAMERA_DEPTH equals the depth of a camera directory`() {
        assertEquals(CAMERA_DEPTH, depthFromRoot(ROOT.resolve("2026-02-15/09/cam1"), ROOT))
    }
}
```

- [ ] **Step 2: Запустить тест и убедиться, что он падает**

Через агент `claude-forge:build-runner`: `./gradlew :frigate-analyzer-core:test --tests 'ru.zinin.frigate.analyzer.core.task.RecordingsTreeTest'`

Ожидается: компиляция теста падает с unresolved reference на `watchCutoff`, `isPrunableDate`, `depthFromRoot`, `CAMERA_DEPTH`.

- [ ] **Step 3: Создать `RecordingsTree.kt` с новыми функциями**

`extractDateFromPath` и `isWithinWatchPeriod` пока остаются в `WatchRecordsLoop.kt` — они в том же пакете, поэтому вызываются отсюда без импорта. Переедут на шаге 6.

```kotlin
package ru.zinin.frigate.analyzer.core.task

import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Path arithmetic over Frigate's recordings tree: `recordings/YYYY-MM-DD/HH/camera/MM.SS.mp4`.
 *
 * Shared by [WatchRecordsLoop] (registers watch keys on directories) and [FirstTimeScanTask]
 * (indexes the files themselves).
 */

/**
 * Depth of a camera directory relative to the recordings root: 0 = root, 1 = date, 2 = hour,
 * 3 = camera. Below a camera directory there are only `.mp4` files, and a WatchService key is
 * registered on the camera directory — that is what delivers ENTRY_CREATE for new recordings.
 *
 * This is the only layout assumption in this file. It is not a new one: `RecordingFileHelper.parse`
 * already navigates `path.parent.parent.parent` and requires `nameCount >= 6`.
 */
internal const val CAMERA_DEPTH: Int = 3

internal fun watchCutoff(
    watchPeriod: Duration,
    clock: Clock,
): LocalDate = LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(watchPeriod.toDays())

/**
 * Fail-CLOSED mirror of [isWithinWatchPeriod]: a subtree may be pruned ONLY when its date was
 * successfully extracted AND falls strictly before [cutoff].
 *
 * Deliberately NOT written as `!isWithinWatchPeriod(...)`. The two are equivalent today, but the
 * negated form reads as "not in period -> cut", and the root's date never extracts. Cutting the
 * root would stop the watcher from ever noticing new date directories, and would leave
 * `registeredDirs` empty — health BRANCH 3.5 reports that as DOWN.
 */
internal fun isPrunableDate(
    path: Path,
    rootFolder: Path,
    cutoff: LocalDate,
): Boolean {
    val date = extractDateFromPath(path, rootFolder) ?: return false
    return date.isBefore(cutoff)
}

/**
 * Depth of [path] relative to [rootFolder]: 0 = root, 1 = date, 2 = hour, 3 = camera.
 * Returns -1 for paths outside the root, so depth-based rules simply do not apply to them and the
 * traversal degrades to a full walk — today's behaviour.
 *
 * NOTE: `rootFolder.relativize(rootFolder)` is the EMPTY path, and an empty [Path] reports
 * `nameCount == 1`, not 0. Without the `isEmpty()` guard the root is classified as a date directory.
 */
internal fun depthFromRoot(
    path: Path,
    rootFolder: Path,
): Int {
    if (!path.startsWith(rootFolder)) return -1
    val rel = rootFolder.relativize(path)
    return if (rel.toString().isEmpty()) 0 else rel.nameCount
}
```

- [ ] **Step 4: Запустить тест и убедиться, что он проходит**

Через `claude-forge:build-runner`: `./gradlew :frigate-analyzer-core:test --tests 'ru.zinin.frigate.analyzer.core.task.RecordingsTreeTest'`

Ожидается: PASS, 11 тестов.

- [ ] **Step 5: Коммит**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/RecordingsTree.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/RecordingsTreeTest.kt
git commit -m "feat: add prune predicate and depth helper for the recordings tree"
```

- [ ] **Step 6: Перенести `extractDateFromPath` и `isWithinWatchPeriod` в `RecordingsTree.kt`**

Удалить из `WatchRecordsLoop.kt` весь блок начиная с пустой строки после закрывающей скобки класса (строка 180) до конца файла — то есть `private val DATE_PATTERN`, `extractDateFromPath`, `isWithinWatchPeriod`. Файл должен заканчиваться закрывающей скобкой класса `WatchRecordsLoop`.

Удалить из импортов `WatchRecordsLoop.kt` три ставшие неиспользуемыми строки:

```kotlin
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
```

Остальные импорты `java.time.*` (`Clock`, `Duration`, `Instant`) остаются — они используются в `runIteration`.

Добавить в `RecordingsTree.kt` импорт `java.time.format.DateTimeParseException` и перенесённый код. `isWithinWatchPeriod` при переносе переписывается на `watchCutoff`, чтобы граница вычислялась в одном месте:

```kotlin
private val DATE_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")

internal fun extractDateFromPath(
    path: Path,
    rootFolder: Path,
): LocalDate? {
    val relativePath = if (path.startsWith(rootFolder)) rootFolder.relativize(path) else path
    for (i in relativePath.nameCount - 1 downTo 0) {
        val name = relativePath.getName(i).toString()
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

/**
 * Fail-OPEN: a path whose date cannot be extracted counts as "inside the period". The recordings
 * root itself and any non-standard directory always pass. See [isPrunableDate] for the mirror rule
 * used when deciding whether a subtree may be skipped.
 */
internal fun isWithinWatchPeriod(
    path: Path,
    rootFolder: Path,
    watchPeriod: Duration,
    clock: Clock,
): Boolean {
    val date = extractDateFromPath(path, rootFolder) ?: return true
    return !date.isBefore(watchCutoff(watchPeriod, clock))
}
```

Порядок объявлений в файле: `CAMERA_DEPTH`, `DATE_PATTERN`, `watchCutoff`, `extractDateFromPath`, `isWithinWatchPeriod`, `isPrunableDate`, `depthFromRoot`.

- [ ] **Step 7: Перенести тесты чистых функций в `RecordingsTreeTest.kt`**

Удалить из `WatchRecordsLoopTest.kt`:
- строку `private val ROOT = Path.of("/mnt/data/frigate/recordings")` (строка 36) и следующую за ней пустую строку;
- весь блок от комментария `// --- Pure-function tests migrated from WatchRecordsTaskTest ---` до последнего теста файла (`extractDateFromPath ignores date-like segments in root path`), включая комментарий;
- ставшие неиспользуемыми импорты `org.junit.jupiter.api.Assertions.assertFalse`, `org.junit.jupiter.api.Assertions.assertNull`, `org.junit.jupiter.api.Assertions.assertTrue`.

Импорты `assertEquals` и `assertNotEquals` остаются — они используются в тестах `runIteration`. Импорты `LocalDate`, `ZoneOffset` остаются: `LocalDate` используется в `RecordingFileDto`, `ZoneOffset` — в объявлении `clock`.

Добавить перенесённые тесты в `RecordingsTreeTest.kt` **дословно**, но заменив локальные объявления часов на общий `CLOCK` там, где они совпадают по значению (`Clock.fixed(Instant.parse("2026-02-15T12:00:00Z"), ZoneOffset.UTC)`), и добавив импорт `org.junit.jupiter.api.Assertions.assertNull`:

```kotlin
    @Test
    fun `extractDateFromPath returns date for date directory`() {
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-15")
        assertEquals(LocalDate.of(2026, 2, 15), extractDateFromPath(path, ROOT))
    }

    @Test
    fun `extractDateFromPath returns date for hour subdirectory`() {
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-15/09")
        assertEquals(LocalDate.of(2026, 2, 15), extractDateFromPath(path, ROOT))
    }

    @Test
    fun `extractDateFromPath returns date for camera subdirectory`() {
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-15/09/cam1")
        assertEquals(LocalDate.of(2026, 2, 15), extractDateFromPath(path, ROOT))
    }

    @Test
    fun `extractDateFromPath returns null for root recordings directory`() {
        assertNull(extractDateFromPath(ROOT, ROOT))
    }

    @Test
    fun `extractDateFromPath returns null for invalid date like 2026-02-30`() {
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-30")
        assertNull(extractDateFromPath(path, ROOT))
    }

    @Test
    fun `extractDateFromPath ignores date-like segments in root path`() {
        val rootWithDate = Path.of("/data/2024-01-15/frigate/recordings")
        val path = Path.of("/data/2024-01-15/frigate/recordings/2026-02-15/09/cam1")
        assertEquals(LocalDate.of(2026, 2, 15), extractDateFromPath(path, rootWithDate))
    }

    @Test
    fun `isWithinWatchPeriod returns true for today`() {
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-15")
        assertTrue(isWithinWatchPeriod(path, ROOT, Duration.ofDays(1), CLOCK))
    }

    @Test
    fun `isWithinWatchPeriod returns true for yesterday within 1 day period`() {
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-14")
        assertTrue(isWithinWatchPeriod(path, ROOT, Duration.ofDays(1), CLOCK))
    }

    @Test
    fun `isWithinWatchPeriod returns false for old date`() {
        val path = Path.of("/mnt/data/frigate/recordings/2026-01-01")
        assertFalse(isWithinWatchPeriod(path, ROOT, Duration.ofDays(1), CLOCK))
    }

    @Test
    fun `isWithinWatchPeriod returns true for root directory without date`() {
        assertTrue(isWithinWatchPeriod(ROOT, ROOT, Duration.ofDays(1), CLOCK))
    }

    @Test
    fun `isWithinWatchPeriod returns true for exact cutoff date`() {
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-14")
        assertTrue(isWithinWatchPeriod(path, ROOT, Duration.ofDays(1), CLOCK))
    }

    @Test
    fun `isWithinWatchPeriod returns false for one day before cutoff`() {
        val path = Path.of("/mnt/data/frigate/recordings/2026-02-13")
        assertFalse(isWithinWatchPeriod(path, ROOT, Duration.ofDays(1), CLOCK))
    }
```

- [ ] **Step 8: Запустить весь модуль и убедиться, что всё зелёное**

Через `claude-forge:build-runner`: `./gradlew :frigate-analyzer-core:test`

Ожидается: PASS. Общее число тестов не изменилось — 12 переехали, 11 добавились ранее.

- [ ] **Step 9: Коммит**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/RecordingsTree.kt \
        modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsLoop.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/RecordingsTreeTest.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsLoopTest.kt
git commit -m "refactor: move recordings-tree path helpers out of WatchRecordsLoop"
```

---

### Task 2: `registerAllDirs` — обход с отсечением поддеревьев

Ядро задачи. Заменяет `Files.walk` на управляемый `walkFileTree` и возвращает счётчики вместо `Int`.

**Files:**
- Create: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/RecordingsTreeFixture.kt`
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsLoop.kt` (импорты, новый `RegistrationResult`, тело `registerAllDirs`)
- Modify: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsLoopTest.kt` (новая группа тестов)
- Modify: `.claude/rules/pipeline.md` (раздел «Selective watching»)

**Interfaces:**
- Consumes: `CAMERA_DEPTH`, `watchCutoff(Duration, Clock): LocalDate`, `isPrunableDate(Path, Path, LocalDate): Boolean`, `depthFromRoot(Path, Path): Int` из Task 1; `buildCanonicalTree(Path)` из шага 1 этой задачи
- Produces:
  - `data class RegistrationResult(val registered: Int, val prunedSubtrees: Int, val visitedEntries: Int)`
  - `fun WatchRecordsLoop.registerAllDirs(start: Path, watchService: WatchService, registeredDirs: ConcurrentMap<Path, WatchKey>): RegistrationResult` — тип возврата изменился с `Int`
  - `internal fun buildCanonicalTree(root: Path)` — общая фикстура для тестов Task 2 и Task 4

- [ ] **Step 1: Создать общую тестовую фикстуру**

Создать `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/RecordingsTreeFixture.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.task

import java.nio.file.Files
import java.nio.file.Path

/**
 * The canonical fixture shared by registerAllDirs and first-scan tests.
 *
 * Assumes a clock fixed at 2026-05-23T12:00:00Z and a watch period of P1D, which puts the cutoff
 * at 2026-05-22: today and the cutoff day itself are inside the window, the other two dates are not.
 *
 * Counts it pins:
 *  - directories in window  = 2 dates + 4 hours + 8 cameras           = 14, plus the root = 15
 *  - directories out of window that the walk still touches            = 2 (pruned at date level)
 *  - `.mp4` files in window = 2 x 2 x 2 x 3                           = 24
 *  - `.mp4` files out of window                                       = 24
 */
internal val CANONICAL_DATES_IN_WINDOW = listOf("2026-05-23", "2026-05-22")
internal val CANONICAL_DATES_OUT_OF_WINDOW = listOf("2026-05-21", "2025-01-01")
internal val CANONICAL_HOURS = listOf("00", "01")
internal val CANONICAL_CAMERAS = listOf("cam1", "cam2")
internal val CANONICAL_FILES = listOf("00.10.mp4", "00.20.mp4", "00.30.mp4")

internal fun buildCanonicalTree(root: Path) {
    (CANONICAL_DATES_IN_WINDOW + CANONICAL_DATES_OUT_OF_WINDOW).forEach { date ->
        CANONICAL_HOURS.forEach { hour ->
            CANONICAL_CAMERAS.forEach { camera ->
                val leaf = root.resolve(date).resolve(hour).resolve(camera)
                Files.createDirectories(leaf)
                CANONICAL_FILES.forEach { name -> Files.createFile(leaf.resolve(name)) }
            }
        }
    }
}

/** Every directory the walk is expected to register when started from [root]. */
internal fun canonicalRegisteredDirs(root: Path): Set<Path> =
    buildSet {
        add(root)
        CANONICAL_DATES_IN_WINDOW.forEach { date ->
            add(root.resolve(date))
            CANONICAL_HOURS.forEach { hour ->
                add(root.resolve(date).resolve(hour))
                CANONICAL_CAMERAS.forEach { camera ->
                    add(root.resolve(date).resolve(hour).resolve(camera))
                }
            }
        }
    }
```

- [ ] **Step 2: Написать падающие тесты на `registerAllDirs`**

Добавить в `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsLoopTest.kt` новые импорты:

```kotlin
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
```

`assertFalse` и `assertTrue` были удалены из этого файла в Task 1 Step 7 — там они остались без пользователей после переезда тестов, и ktlint-правило `no-unused-imports` не позволило бы оставить их «на будущее». Здесь они возвращаются вместе с новыми тестами.

и приватный хелпер плюс группу тестов внутрь класса `WatchRecordsLoopTest`:

```kotlin
    private fun loopFor(
        root: Path,
        watchPeriod: Duration = Duration.ofDays(1),
    ) = WatchRecordsLoop(
        recordsWatcherProperties =
            RecordsWatcherProperties(
                folder = root,
                watchPeriod = watchPeriod,
                cleanupInterval = Duration.ofHours(1),
            ),
        recordingEntityHelper = recordingEntityHelper,
        recordingFileHelper = recordingFileHelper,
        clock = clock,
    )

    // --- registerAllDirs ---

    @Test
    fun `registerAllDirs registers root in-window dates hours and cameras`() {
        val root = Files.createTempDirectory("rad-registers")
        val watchService = FileSystems.getDefault().newWatchService()
        try {
            buildCanonicalTree(root)
            val dirs = ConcurrentHashMap<Path, WatchKey>()

            val result = loopFor(root).registerAllDirs(root, watchService, dirs)

            assertEquals(15, result.registered)
            assertEquals(canonicalRegisteredDirs(root), dirs.keys.toSet())
        } finally {
            watchService.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `registerAllDirs never enumerates recording files`() {
        val root = Files.createTempDirectory("rad-no-files")
        val watchService = FileSystems.getDefault().newWatchService()
        try {
            buildCanonicalTree(root)
            val dirs = ConcurrentHashMap<Path, WatchKey>()

            val result = loopFor(root).registerAllDirs(root, watchService, dirs)

            // 48 .mp4 files exist on disk. If any of them were visited, visitedEntries would
            // exceed registered + pruned — that relation is the whole point of the change.
            assertEquals(17, result.visitedEntries)
            assertEquals(
                result.registered + result.prunedSubtrees,
                result.visitedEntries,
                "visitedEntries must equal directories + pruned subtrees; any excess means files were enumerated",
            )
        } finally {
            watchService.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `registerAllDirs prunes out-of-window date subtrees`() {
        val root = Files.createTempDirectory("rad-prunes")
        val watchService = FileSystems.getDefault().newWatchService()
        try {
            buildCanonicalTree(root)
            val dirs = ConcurrentHashMap<Path, WatchKey>()

            val result = loopFor(root).registerAllDirs(root, watchService, dirs)

            assertEquals(2, result.prunedSubtrees)
            assertTrue(
                dirs.keys.none { key -> CANONICAL_DATES_OUT_OF_WINDOW.any { key.toString().contains(it) } },
                "no directory under an out-of-window date may be registered",
            )
        } finally {
            watchService.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `registerAllDirs does not descend below the camera level`() {
        val root = Files.createTempDirectory("rad-camera-depth")
        val watchService = FileSystems.getDefault().newWatchService()
        try {
            buildCanonicalTree(root)
            val nested = root.resolve("2026-05-23/00/cam1/nested")
            Files.createDirectories(nested)
            val dirs = ConcurrentHashMap<Path, WatchKey>()

            val result = loopFor(root).registerAllDirs(root, watchService, dirs)

            assertFalse(dirs.containsKey(nested))
            assertEquals(17, result.visitedEntries, "the camera directory must not be opened")
        } finally {
            watchService.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `registerAllDirs registers the root even though it carries no date`() {
        val root = Files.createTempDirectory("rad-root")
        val watchService = FileSystems.getDefault().newWatchService()
        try {
            buildCanonicalTree(root)
            val dirs = ConcurrentHashMap<Path, WatchKey>()

            loopFor(root).registerAllDirs(root, watchService, dirs)

            assertTrue(dirs.containsKey(root), "the root must stay watched so new date dirs are noticed")
        } finally {
            watchService.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `registerAllDirs handles a start below the root`() {
        val root = Files.createTempDirectory("rad-substart")
        val watchService = FileSystems.getDefault().newWatchService()
        try {
            buildCanonicalTree(root)
            val dirs = ConcurrentHashMap<Path, WatchKey>()

            // This is the runIteration call-site: a freshly created date directory.
            val result = loopFor(root).registerAllDirs(root.resolve("2026-05-23"), watchService, dirs)

            assertEquals(7, result.registered)
            assertEquals(0, result.prunedSubtrees)
            assertEquals(7, result.visitedEntries)
            assertFalse(dirs.containsKey(root))
        } finally {
            watchService.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `registerAllDirs is idempotent`() {
        val root = Files.createTempDirectory("rad-idempotent")
        val watchService = FileSystems.getDefault().newWatchService()
        try {
            buildCanonicalTree(root)
            val dirs = ConcurrentHashMap<Path, WatchKey>()
            val loop = loopFor(root)

            loop.registerAllDirs(root, watchService, dirs)
            val second = loop.registerAllDirs(root, watchService, dirs)

            assertEquals(0, second.registered)
            assertEquals(2, second.prunedSubtrees)
            assertEquals(17, second.visitedEntries)
            assertEquals(15, dirs.size)
        } finally {
            watchService.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `registerAllDirs skips an unreadable directory instead of aborting`() {
        val root = Files.createTempDirectory("rad-unreadable")
        val watchService = FileSystems.getDefault().newWatchService()
        val locked = root.resolve("2026-05-23/00")
        try {
            buildCanonicalTree(root)
            Files.setPosixFilePermissions(locked, emptySet<PosixFilePermission>())
            assumeTrue(
                runCatching { Files.newDirectoryStream(locked).use { it.iterator().hasNext() } }.isFailure,
                "chmod 000 does not restrict this user (running as root?) — cannot simulate an unreadable directory",
            )
            val dirs = ConcurrentHashMap<Path, WatchKey>()

            val result = loopFor(root).registerAllDirs(root, watchService, dirs)

            // 15 expected minus the locked hour directory and its two cameras.
            assertEquals(12, result.registered)
            assertFalse(dirs.containsKey(locked))
            assertTrue(dirs.containsKey(root.resolve("2026-05-23/01/cam1")))
            assertTrue(dirs.containsKey(root.resolve("2026-05-22/01/cam2")))
        } finally {
            runCatching {
                Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"))
            }
            watchService.close()
            root.toFile().deleteRecursively()
        }
    }
```

- [ ] **Step 3: Запустить тесты и убедиться, что они падают**

Через `claude-forge:build-runner`: `./gradlew :frigate-analyzer-core:test --tests 'ru.zinin.frigate.analyzer.core.task.WatchRecordsLoopTest'`

Ожидается: компиляция падает — `result.registered` / `result.prunedSubtrees` / `result.visitedEntries` не существуют, `registerAllDirs` возвращает `Int`.

- [ ] **Step 4: Реализовать `RegistrationResult` и новый обход**

В `WatchRecordsLoop.kt` добавить импорты:

```kotlin
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.SimpleFileVisitor
```

Добавить data-класс рядом с существующим `IterationResult` (перед объявлением класса `WatchRecordsLoop`):

```kotlin
/**
 * Outcome of one [WatchRecordsLoop.registerAllDirs] traversal.
 *
 * [visitedEntries] is the load-bearing number: it counts every filesystem entry the walk touched.
 * For a healthy prune it equals [registered] + [prunedSubtrees]; anything above that means `.mp4`
 * files were enumerated, which is exactly the regression this traversal exists to prevent.
 */
data class RegistrationResult(
    val registered: Int,
    val prunedSubtrees: Int,
    val visitedEntries: Int,
)
```

Заменить тело `registerAllDirs` целиком:

```kotlin
    fun registerAllDirs(
        start: Path,
        watchService: WatchService,
        registeredDirs: ConcurrentMap<Path, WatchKey>,
    ): RegistrationResult {
        val root = recordsWatcherProperties.folder
        // Computed once for the whole walk: a traversal that crosses midnight would otherwise
        // apply two different cutoffs to different branches of the same tree.
        val cutoff = watchCutoff(recordsWatcherProperties.watchPeriod, clock)
        var registered = 0
        var pruned = 0
        var visited = 0
        val startedAt = System.nanoTime()

        Files.walkFileTree(
            start,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    visited++
                    // The date check runs at EVERY level, not just at depth 1: it is pure string
                    // work, and that keeps correctness independent of where dates actually sit.
                    // Depth is then the single lever, and it only controls descent.
                    if (isPrunableDate(dir, root, cutoff)) {
                        pruned++
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    registeredDirs.computeIfAbsent(dir) {
                        val k = dir.register(watchService, ENTRY_CREATE)
                        registered++
                        k
                    }
                    return if (depthFromRoot(dir, root) >= CAMERA_DEPTH) {
                        FileVisitResult.SKIP_SUBTREE
                    } else {
                        FileVisitResult.CONTINUE
                    }
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    visited++
                    return FileVisitResult.CONTINUE
                }

                // SimpleFileVisitor rethrows by default, which would abort the whole registration
                // because of a single unreadable directory. The walk opens a directory BEFORE
                // preVisitDirectory, so an unreadable one arrives here, not there.
                override fun visitFileFailed(
                    file: Path,
                    exc: IOException,
                ): FileVisitResult {
                    visited++
                    logger.warn(exc) { "Registration: skipping unreadable entry $file" }
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    dir: Path,
                    exc: IOException?,
                ): FileVisitResult {
                    if (exc != null) logger.warn(exc) { "Registration: error after visiting $dir" }
                    return FileVisitResult.CONTINUE
                }
            },
        )

        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        logger.info {
            "Registered $registered dirs, pruned $pruned date subtrees, " +
                "visited $visited entries in ${elapsedMs}ms"
        }
        return RegistrationResult(registered, pruned, visited)
    }
```

Оба call-site (`WatchRecordsTask.kt:244` и `WatchRecordsLoop.kt:83`) игнорируют возвращаемое значение — менять их не нужно.

- [ ] **Step 5: Запустить тесты и убедиться, что они проходят**

Через `claude-forge:build-runner`: `./gradlew :frigate-analyzer-core:test`

Ожидается: PASS. Если `WatchRecordsTaskTest` не компилируется — найти стаб вида `every { loop.registerAllDirs(...) } returns <Int>` и заменить возвращаемое значение на `RegistrationResult(0, 0, 0)`. На момент написания плана таких стабов нет: строки 436 и 467 используют `throws`.

- [ ] **Step 6: Обновить `.claude/rules/pipeline.md`**

Заменить блок «### Selective watching» на:

```markdown
### Selective watching

WatchRecordsLoop uses selective watching to limit monitored directories:
- Only directories within `WATCH_PERIOD` are monitored (date extracted from Frigate's `YYYY-MM-DD` structure)
- The root recordings directory is always watched to catch new date directories
- A periodic cleanup removes expired watch keys based on `WATCH_CLEANUP_INTERVAL`

`registerAllDirs` walks with `Files.walkFileTree` and prunes as it goes, rather than filtering after
the fact. Two rules, both in `preVisitDirectory`:
- `isPrunableDate` (fail-CLOSED, `RecordingsTree.kt`) returns `SKIP_SUBTREE` for a date outside the
  window. It is deliberately not `!isWithinWatchPeriod` (fail-OPEN) — the root's date never
  extracts, and pruning the root would blind the watcher to new date directories.
- `depthFromRoot(dir) >= CAMERA_DEPTH` returns `SKIP_SUBTREE` at the camera directory. Below it
  there are only `.mp4` files, and the watch key sits on the camera directory anyway. **No recording
  file is ever enumerated during registration.**

The cutoff is computed once per traversal so a walk crossing midnight cannot apply two different
windows. `RegistrationResult.visitedEntries` counts every filesystem entry touched; when the prune
is healthy it equals `registered + prunedSubtrees`, and tests assert exactly that.

An unreadable directory reaches `visitFileFailed` (the walk opens a directory before
`preVisitDirectory` runs) and is logged and skipped. It no longer aborts the whole registration.

WatchRecordsLoop parses `.mp4` filenames to extract camera ID, date, time, timestamp.
```

- [ ] **Step 7: Коммит**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsLoop.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/WatchRecordsLoopTest.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/RecordingsTreeFixture.kt \
        .claude/rules/pipeline.md
git commit -m "perf: prune out-of-window subtrees when registering watch directories"
```

---

### Task 3: конфигурация — `FIRST_SCAN_PERIOD` и детали health

Две строки в `application.yaml`, свойство, документация и биндинг-тест продового yaml.

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/RecordsWatcherProperties.kt`
- Modify: `modules/core/src/main/resources/application.yaml:28-33` (records-watcher) и конец файла (блок `management`)
- Modify: `docker/deploy/.env.example`
- Modify: `.claude/rules/configuration.md`
- Create: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/properties/RecordsWatcherPropertiesBindingTest.kt`

**Interfaces:**
- Consumes: ничего из предыдущих задач
- Produces: `RecordsWatcherProperties.firstScanPeriod: Duration` (дефолт `= watchPeriod`) — потребляется Task 4

- [ ] **Step 1: Написать падающий биндинг-тест**

Создать `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/properties/RecordsWatcherPropertiesBindingTest.kt`. Форма повторяет соседний `ObjectTrackerPropertiesBindingTest`:

```kotlin
package ru.zinin.frigate.analyzer.core.config.properties

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.core.io.FileSystemResource
import java.io.File
import java.time.Duration

/**
 * Binds `application.records-watcher` out of the production `src/main/resources/application.yaml`,
 * the same way [ObjectTrackerPropertiesBindingTest] does — the test classpath carries its own
 * `application.yaml`, which shadows the production one, so its placeholders are otherwise evaluated
 * for the first time when production starts.
 *
 * What is pinned here is `first-scan-period` defaulting to `watch-period`. Its default references
 * the resolved property, not `$WATCH_PERIOD`, so it follows the watch period from whichever source
 * sets it. The nested-variable form `${FIRST_SCAN_PERIOD:${WATCH_PERIOD:P1D}}` would silently fall
 * back to P1D whenever the watch period is set by anything other than that one env var.
 */
class RecordsWatcherPropertiesBindingTest {
    @Test
    fun `with nothing set, first-scan-period equals watch-period and both keep the documented default`() {
        val props = bind()

        assertThat(props.watchPeriod).isEqualTo(Duration.ofDays(1))
        assertThat(props.firstScanPeriod).isEqualTo(props.watchPeriod)
    }

    @Test
    fun `first-scan-period follows a watch-period set through WATCH_PERIOD`() {
        val props = bind(env = mapOf("WATCH_PERIOD" to "P3D"))

        assertThat(props.watchPeriod).isEqualTo(Duration.ofDays(3))
        assertThat(props.firstScanPeriod).isEqualTo(Duration.ofDays(3))
    }

    @Test
    fun `first-scan-period follows a watch-period set as a property rather than as that one variable`() {
        // A docker profile yaml, a CLI argument or a system property lands this way.
        // This is the case the nested-variable form would get wrong.
        val props = bind(properties = mapOf("$PREFIX.watch-period" to "P3D"))

        assertThat(props.watchPeriod).isEqualTo(Duration.ofDays(3))
        assertThat(props.firstScanPeriod).isEqualTo(Duration.ofDays(3))
    }

    @Test
    fun `first-scan-period follows a watch-period set through the relaxed variable name`() {
        val props = bind(env = mapOf("APPLICATION_RECORDSWATCHER_WATCHPERIOD" to "P3D"))

        assertThat(props.watchPeriod).isEqualTo(Duration.ofDays(3))
        assertThat(props.firstScanPeriod).isEqualTo(Duration.ofDays(3))
    }

    @Test
    fun `an explicitly set FIRST_SCAN_PERIOD wins over the watch-period default`() {
        val props = bind(env = mapOf("WATCH_PERIOD" to "P3D", "FIRST_SCAN_PERIOD" to "P0D"))

        assertThat(props.watchPeriod).isEqualTo(Duration.ofDays(3))
        assertThat(props.firstScanPeriod).isEqualTo(Duration.ZERO)
    }

    @Test
    fun `health details are exposed by default and can be switched off`() {
        assertThat(environmentWith().getProperty("management.endpoint.health.show-details"))
            .isEqualTo("always")
        assertThat(
            environmentWith(env = mapOf("HEALTH_SHOW_DETAILS" to "never"))
                .getProperty("management.endpoint.health.show-details"),
        ).isEqualTo("never")
    }

    private fun bind(
        env: Map<String, Any> = emptyMap(),
        properties: Map<String, Any> = emptyMap(),
    ): RecordsWatcherProperties =
        Binder
            .get(environmentWith(env, properties))
            .bind(PREFIX, RecordsWatcherProperties::class.java)
            .get()

    /**
     * [env] is exposed as a [SystemEnvironmentPropertySource] on purpose: that type is what makes
     * `APPLICATION_RECORDSWATCHER_WATCHPERIOD` answer a lookup for
     * `application.records-watcher.watch-period`, and a plain map would not.
     */
    private fun environmentWith(
        env: Map<String, Any> = emptyMap(),
        properties: Map<String, Any> = emptyMap(),
    ): StandardEnvironment {
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
        return environment
    }

    private companion object {
        const val PREFIX = "application.records-watcher"

        /** Gradle runs tests with the module directory as the working directory. */
        fun productionYaml() =
            File("src/main/resources/application.yaml")
                .also { check(it.isFile) { "Expected the production yaml at ${it.absolutePath}" } }
                .let { YamlPropertySourceLoader().load("production-application.yaml", FileSystemResource(it)) }
    }
}
```

- [ ] **Step 2: Запустить тест и убедиться, что он падает**

Через `claude-forge:build-runner`: `./gradlew :frigate-analyzer-core:test --tests 'ru.zinin.frigate.analyzer.core.config.properties.RecordsWatcherPropertiesBindingTest'`

Ожидается: компиляция падает — у `RecordsWatcherProperties` нет `firstScanPeriod`.

- [ ] **Step 3: Добавить свойство**

В `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/RecordsWatcherProperties.kt` добавить поле **после** `watchPeriod` (Kotlin-дефолт ссылается на предыдущий параметр конструктора — тот же приём, что `reappearGap: Duration = ttl` в `ObjectTrackerProperties`) и строку валидации:

```kotlin
    @field:NotNull
    val watchPeriod: Duration = Duration.ofDays(1),
    /**
     * How far back the one-off startup scan indexes files. Defaults to [watchPeriod]: the backfill
     * covers exactly the window the watcher watches.
     *
     * Filtering is by date, so the window is always whole days: `P0D` means today only, `P1D` means
     * today and yesterday. The lower bound is `P0D` rather than `watchPeriod`'s one day, otherwise
     * "scan today only" would be inexpressible.
     */
    @field:NotNull
    val firstScanPeriod: Duration = watchPeriod,
```

и в блок `init`:

```kotlin
        require(!firstScanPeriod.isNegative) { "firstScanPeriod must not be negative, got: $firstScanPeriod" }
```

- [ ] **Step 4: Добавить строки в `application.yaml`**

В блок `records-watcher` (строки 28-33) добавить после `watch-period`:

```yaml
    # Defaults to watch-period: the startup backfill covers exactly the window the watcher watches.
    # The default references the RESOLVED property rather than $WATCH_PERIOD, so it follows
    # watch-period from whichever source sets it — env, the docker profile yaml, a CLI argument.
    # Same form as notifications.tracker.reappear-gap below.
    first-scan-period: ${FIRST_SCAN_PERIOD:${application.records-watcher.watch-period}}
```

В конец файла добавить блок **на нулевом отступе** — это корень документа, не вложение в `application:` (в файле сейчас нет ни одной строки `management`):

```yaml

management:
  endpoint:
    health:
      # Default `always`: this is a single-deployment project, and computeHealth already builds a
      # `reason` plus registeredDirs / lastSuccessfulRegistrationAt that Spring otherwise discards.
      # A bare {"status":"DOWN"} is what forced the 9-minute registration stall to be diagnosed
      # from logs instead of from the endpoint.
      show-details: ${HEALTH_SHOW_DETAILS:always}
```

- [ ] **Step 5: Запустить тест и убедиться, что он проходит**

Через `claude-forge:build-runner`: `./gradlew :frigate-analyzer-core:test`

Ожидается: PASS, 6 новых тестов.

- [ ] **Step 6: Обновить `docker/deploy/.env.example`**

Вставить между блоком `# Paths` и блоком `# Host port mapping`. Соблюдается конвенция файла: комментарий **над** строкой, никогда inline — парсеры `env_file` не считают `#` inline-комментарием и запекли бы текст в значение.

```
# --- Records watcher ---
# Skip the one-off startup scan that indexes recordings already present on disk.
# DISABLE_FIRST_SCAN=false
# How far back the watcher registers directories. Day granularity: P1D = today and yesterday.
# WATCH_PERIOD=P1D
# How far back the startup scan indexes files. Defaults to WATCH_PERIOD. P0D = today only.
# Every indexed file becomes a recording and is fed to the detection pipeline, so keep it small.
# FIRST_SCAN_PERIOD=P1D
# Expose /actuator/health details (reason, registeredDirs, timestamps): always | never | when-authorized
# HEALTH_SHOW_DETAILS=always
```

- [ ] **Step 7: Обновить `.claude/rules/configuration.md`**

В таблицу раздела «## Records Watcher» добавить строку после `WATCH_PERIOD`:

```markdown
| `FIRST_SCAN_PERIOD` | = `WATCH_PERIOD` | ISO-8601 duration, how far back the startup scan indexes files. Day granularity: `P0D` = today only, `P1D` = today and yesterday. Defaults to the resolved `watch-period`, so it follows it from any source. Every indexed file becomes a `recordings` row and enters the detection pipeline — one day of three cameras at 10-second segments is ~52 000 files. |
```

Сразу после этой таблицы добавить новый раздел:

```markdown
## Actuator

| Variable | Default | Purpose |
|----------|---------|---------|
| `HEALTH_SHOW_DETAILS` | always | `management.endpoint.health.show-details`. With `never` (Spring Boot's own default) `/actuator/health` returns a bare status, and `WatchRecordsTask.computeHealth`'s `reason`, `registeredDirs` and `lastSuccessfulRegistrationAt` are discarded — which is why a 9-minute registration stall had to be diagnosed from logs. `always` exposes them to anyone who can reach the port. |
```

- [ ] **Step 8: Коммит**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/RecordsWatcherProperties.kt \
        modules/core/src/main/resources/application.yaml \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/properties/RecordsWatcherPropertiesBindingTest.kt \
        docker/deploy/.env.example \
        .claude/rules/configuration.md
git commit -m "feat: add FIRST_SCAN_PERIOD window and expose actuator health details"
```

---

### Task 4: `FirstTimeScanTask` — окно, изоляция ошибок, тестируемость

Тот же неуправляемый `Files.walk` по всем 3.2 млн файлов, плюс `.catch{}`, который завершает поток на первом же сбойном файле.

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/FirstTimeScanTask.kt` (переписывается целиком)
- Create: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/FirstTimeScanTaskTest.kt`
- Modify: `.claude/rules/pipeline.md` (строка про FirstTimeScanTask в таблице «File Watching & Startup»)

**Interfaces:**
- Consumes: `watchCutoff(Duration, Clock): LocalDate`, `isPrunableDate(Path, Path, LocalDate): Boolean` из Task 1; `buildCanonicalTree(Path)`, `CANONICAL_DATES_OUT_OF_WINDOW` из Task 2; `RecordsWatcherProperties.firstScanPeriod` из Task 3
- Produces:
  - `internal data class FirstTimeScanTask.ScanResult(val indexed: Int, val failed: Int, val prunedSubtrees: Int, val visitedEntries: Int)`
  - `internal suspend fun FirstTimeScanTask.scan(): ScanResult`
  - конструктор `FirstTimeScanTask` получает четвёртый параметр `clock: Clock`

- [ ] **Step 1: Написать падающие тесты**

Создать `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/FirstTimeScanTaskTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.task

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.core.config.properties.RecordsWatcherProperties
import ru.zinin.frigate.analyzer.model.dto.RecordingFileDto
import ru.zinin.frigate.analyzer.model.request.CreateRecordingRequest
import ru.zinin.frigate.analyzer.service.helper.RecordingEntityHelper
import ru.zinin.frigate.analyzer.service.helper.RecordingFileHelper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class FirstTimeScanTaskTest {
    private val recordingEntityHelper = mockk<RecordingEntityHelper>()
    private val recordingFileHelper = mockk<RecordingFileHelper>()
    private val clock = Clock.fixed(Instant.parse("2026-05-23T12:00:00Z"), ZoneOffset.UTC)

    private val sampleDto =
        RecordingFileDto(
            basePath = "/mnt/data/frigate/recordings",
            camId = "cam1",
            date = LocalDate.of(2026, 5, 23),
            time = LocalTime.of(0, 10, 0),
            timestamp = Instant.parse("2026-05-23T00:10:00Z"),
        )

    private fun taskFor(
        root: Path,
        firstScanPeriod: Duration = Duration.ofDays(1),
    ) = FirstTimeScanTask(
        recordsWatcherProperties =
            RecordsWatcherProperties(
                folder = root,
                watchPeriod = Duration.ofDays(1),
                firstScanPeriod = firstScanPeriod,
                cleanupInterval = Duration.ofHours(1),
            ),
        recordingEntityHelper = recordingEntityHelper,
        recordingFileHelper = recordingFileHelper,
        clock = clock,
    )

    @Test
    fun `scan indexes only files inside the first-scan window`() =
        runTest {
            val root = Files.createTempDirectory("fts-window")
            try {
                buildCanonicalTree(root)
                // CopyOnWriteArrayList: flatMapMerge runs the per-file body concurrently.
                val requests = CopyOnWriteArrayList<CreateRecordingRequest>()
                every { recordingFileHelper.parse(any()) } returns sampleDto
                coEvery { recordingEntityHelper.createRecording(capture(requests)) } returns UUID.randomUUID()

                val result = taskFor(root).scan()

                assertEquals(24, result.indexed)
                assertEquals(0, result.failed)
                assertEquals(2, result.prunedSubtrees)
                assertEquals(24, requests.size)
                assertTrue(
                    requests.none { req -> CANONICAL_DATES_OUT_OF_WINDOW.any { req.filePath.contains(it) } },
                    "no file under an out-of-window date may be indexed",
                )
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `scan keeps going when one file fails`() =
        runTest {
            val root = Files.createTempDirectory("fts-failure")
            try {
                buildCanonicalTree(root)
                val poison = root.resolve("2026-05-23/00/cam1/00.20.mp4")
                every { recordingFileHelper.parse(any()) } answers {
                    if (firstArg<Path>() == poison) throw IllegalArgumentException("bogus filename")
                    sampleDto
                }
                coEvery { recordingEntityHelper.createRecording(any()) } returns UUID.randomUUID()

                val result = taskFor(root).scan()

                assertEquals(23, result.indexed)
                assertEquals(1, result.failed)
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `scan with a P0D window indexes today only`() =
        runTest {
            val root = Files.createTempDirectory("fts-today")
            try {
                buildCanonicalTree(root)
                every { recordingFileHelper.parse(any()) } returns sampleDto
                coEvery { recordingEntityHelper.createRecording(any()) } returns UUID.randomUUID()

                val result = taskFor(root, Duration.ZERO).scan()

                assertEquals(12, result.indexed)
                assertEquals(3, result.prunedSubtrees)
            } finally {
                root.toFile().deleteRecursively()
            }
        }
}
```

- [ ] **Step 2: Запустить тесты и убедиться, что они падают**

Через `claude-forge:build-runner`: `./gradlew :frigate-analyzer-core:test --tests 'ru.zinin.frigate.analyzer.core.task.FirstTimeScanTaskTest'`

Ожидается: компиляция падает — у `FirstTimeScanTask` нет параметра `clock` и нет метода `scan()`.

- [ ] **Step 3: Переписать `FirstTimeScanTask.kt`**

Заменить содержимое файла целиком:

```kotlin
package ru.zinin.frigate.analyzer.core.task

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.config.properties.RecordsWatcherProperties
import ru.zinin.frigate.analyzer.model.request.CreateRecordingRequest
import ru.zinin.frigate.analyzer.service.helper.RecordingEntityHelper
import ru.zinin.frigate.analyzer.service.helper.RecordingFileHelper
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.absolutePathString

private val logger = KotlinLogging.logger {}

private const val SCAN_CONCURRENCY = 8

@Component
class FirstTimeScanTask(
    private val recordsWatcherProperties: RecordsWatcherProperties,
    private val recordingEntityHelper: RecordingEntityHelper,
    private val recordingFileHelper: RecordingFileHelper,
    private val clock: Clock,
) {
    internal data class ScanResult(
        val indexed: Int,
        val failed: Int,
        val prunedSubtrees: Int,
        val visitedEntries: Int,
    )

    @Async
    fun run() {
        logger.info { "Starting first time scan task..." }

        CoroutineScope(Dispatchers.Default).launch {
            val result = scan()
            logger.info {
                "Finish first time scan task: indexed=${result.indexed}, failed=${result.failed}, " +
                    "pruned=${result.prunedSubtrees} date subtrees, visited=${result.visitedEntries} entries"
            }
        }
    }

    /**
     * Indexes every recording file inside the first-scan window.
     *
     * Split out of [run] so it can be driven from tests: [run] is a detached fire-and-forget
     * launch, the same split WatchRecordsLoop (logic) and WatchRecordsTask (supervision) already use.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal suspend fun scan(): ScanResult {
        val root = recordsWatcherProperties.folder
        val cutoff = watchCutoff(recordsWatcherProperties.firstScanPeriod, clock)
        val files = mutableListOf<Path>()
        var pruned = 0
        var visited = 0

        withContext(Dispatchers.IO) {
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        dir: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        visited++
                        // Same fail-closed prune as registerAllDirs. CAMERA_DEPTH deliberately does
                        // NOT apply here: the files below a camera directory are the point.
                        return if (isPrunableDate(dir, root, cutoff)) {
                            pruned++
                            FileVisitResult.SKIP_SUBTREE
                        } else {
                            FileVisitResult.CONTINUE
                        }
                    }

                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        visited++
                        if (attrs.isRegularFile) files += file
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        file: Path,
                        exc: IOException,
                    ): FileVisitResult {
                        visited++
                        logger.warn(exc) { "First scan: skipping unreadable entry $file" }
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(
                        dir: Path,
                        exc: IOException?,
                    ): FileVisitResult {
                        if (exc != null) logger.warn(exc) { "First scan: error after visiting $dir" }
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }

        logger.info {
            "First scan: ${files.size} files on or after $cutoff; pruned $pruned date subtrees"
        }

        val indexed = AtomicInteger()
        val failed = AtomicInteger()

        files
            .asFlow()
            .flatMapMerge(concurrency = SCAN_CONCURRENCY) { path ->
                flow {
                    // Per-file isolation. The previous shape put `.catch {}` on the outer chain,
                    // which TERMINATES the flow: the first duplicate file_path or unparseable name
                    // silently killed the whole scan.
                    val id: UUID? =
                        try {
                            val attrs =
                                withContext(Dispatchers.IO) {
                                    Files.readAttributes(path, BasicFileAttributes::class.java)
                                }
                            val recordingFile = recordingFileHelper.parse(path)
                            recordingEntityHelper.createRecording(
                                CreateRecordingRequest(
                                    path.absolutePathString(),
                                    attrs.creationTime().toInstant(),
                                    recordingFile.camId,
                                    recordingFile.date,
                                    recordingFile.time,
                                    recordingFile.timestamp,
                                ),
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.warn(e) { "First scan: skipping $path" }
                            failed.incrementAndGet()
                            null
                        }
                    if (id != null) {
                        indexed.incrementAndGet()
                        emit(id)
                    }
                }
            }.catch { e -> logger.error(e) { "First scan aborted unexpectedly" } }
            // DEBUG, not INFO: a one-day window of three cameras is tens of thousands of files.
            .collect { id -> logger.debug { "First scan indexed recording $id" } }

        return ScanResult(indexed.get(), failed.get(), pruned, visited)
    }
}
```

- [ ] **Step 4: Запустить тесты и убедиться, что они проходят**

Через `claude-forge:build-runner`: `./gradlew :frigate-analyzer-core:test`

Ожидается: PASS. Spring внедрит `Clock` в новый параметр конструктора — бин уже существует и используется в `WatchRecordsLoop`.

- [ ] **Step 5: Обновить `.claude/rules/pipeline.md`**

В таблице «File Watching & Startup» заменить строку про `FirstTimeScanTask` на:

```markdown
| FirstTimeScanTask | `core/task/` | One-off startup backfill of files already on disk, bounded by `FIRST_SCAN_PERIOD` (defaults to `WATCH_PERIOD`); disable with `DISABLE_FIRST_SCAN=true`. Prunes out-of-window date subtrees the same way `registerAllDirs` does. Per-file failures are counted and skipped, not fatal — the previous `.catch {}` on the outer flow terminated the whole scan on the first bad file. Logic lives in `scan()`; `run()` is the detached launch. |
```

- [ ] **Step 6: Коммит**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/FirstTimeScanTask.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/task/FirstTimeScanTaskTest.kt \
        .claude/rules/pipeline.md
git commit -m "fix: bound the first-time scan to its window and isolate per-file failures"
```

---

### Финальная проверка

- [ ] **Полная сборка**

Через `claude-forge:build-runner`: `./gradlew build`

Ожидается: BUILD SUCCESSFUL. На ошибках ktlint — `./gradlew ktlintFormat`, затем повторить.

- [ ] **Код-ревью**

Через skill `superpowers:requesting-code-review` — на диффе ветки относительно `master`. Чинить критичные замечания до чистоты, затем повторить сборку. Порядок «сначала ревью, потом сборка» задан в `CLAUDE.md`.

На что смотреть прицельно:
- в `preVisitDirectory` порядок правил: prune по дате идёт ДО регистрации, иначе каталог вне окна успеет получить watch key;
- `depthFromRoot` вызывается после `computeIfAbsent`, а не вместо проверки даты — глубина не должна влиять на отсев;
- ни одна ветка не превратилась в `!isWithinWatchPeriod(...)`;
- `visitFileFailed` возвращает `CONTINUE`, а не наследует пробрасывающую реализацию `SimpleFileVisitor`.

- [ ] **Перед PR: убрать документы планирования из диффа**

```bash
git rm -r docs/superpowers/specs/2026-08-02-watch-records-registration-design.md \
          docs/superpowers/plans/2026-08-02-watch-records-registration.md
git commit -m "chore: drop planning docs from the branch"
```

Документы остаются доступны в истории ветки.

## Приложение: контрольные числа канонической фикстуры

Дерево: 4 даты × 2 часа × 2 камеры × 3 файла. Часы зафиксированы на `2026-05-23T12:00:00Z`.

| Величина | `registerAllDirs(root)`, `P1D` | `registerAllDirs(root/2026-05-23)` | `scan()`, `P1D` | `scan()`, `P0D` |
|---|---|---|---|---|
| `registered` / `indexed` | 15 | 7 | 24 | 12 |
| `prunedSubtrees` | 2 | 0 | 2 | 3 |
| `visitedEntries` | 17 | 7 | 41 | 23 |
| посещено `.mp4` | **0** | **0** | 24 | 12 |

Для `scan()` файлы вне окна тоже не перечисляются — отсекаются вместе с датой. `visitedEntries = 41` при `P1D`: корень 1 + даты 4 + часы 4 + камеры 8 + файлы 24. При `P0D`: 1 + 4 + 2 + 4 + 12 = 23.

`registered = 15` — корень 1 + даты 2 + часы 4 + камеры 8.
`visitedEntries = 17` — 15 зарегистрированных + 2 отсечённые даты. Равенство `visitedEntries == registered + prunedSubtrees` и есть проверяемый инвариант «файлы не перечислялись».
