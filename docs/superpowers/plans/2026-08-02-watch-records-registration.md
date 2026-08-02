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
  - `internal fun isDateAtUnexpectedDepth(path: Path, rootFolder: Path): Boolean`

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
        // Wide, boundary-heavy list on purpose: a future refactor to the `!isWithinWatchPeriod`
        // form must fail this test for SOME date, whatever the cutoff arithmetic does.
        val cutoff = watchCutoff(Duration.ofDays(1), CLOCK)
        listOf(
            "2027-01-01", "2026-12-31", "2026-02-16", "2026-02-15", "2026-02-14",
            "2026-02-13", "2026-02-12", "2026-01-01", "2025-12-31", "2020-06-15",
        ).forEach { date ->
            val path = ROOT.resolve(date)
            assertEquals(
                !isWithinWatchPeriod(path, ROOT, Duration.ofDays(1), CLOCK),
                isPrunableDate(path, ROOT, cutoff),
                "mismatch for $date",
            )
        }
    }

    @Test
    fun `isDateAtUnexpectedDepth detects a root set one level too high`() {
        assertFalse(isDateAtUnexpectedDepth(ROOT, ROOT))
        assertFalse(isDateAtUnexpectedDepth(ROOT.resolve("2026-02-15"), ROOT))
        assertFalse(isDateAtUnexpectedDepth(ROOT.resolve("2026-02-15/09"), ROOT))
        assertFalse(isDateAtUnexpectedDepth(ROOT.resolve("2026-02-15/09/cam1"), ROOT))
        val highRoot = Path.of("/mnt/data/frigate")
        assertTrue(isDateAtUnexpectedDepth(highRoot.resolve("recordings/2026-02-15"), highRoot))
        assertFalse(isDateAtUnexpectedDepth(Path.of("/var/tmp/elsewhere/2026-02-15"), ROOT))
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

Ожидается: компиляция теста падает с unresolved reference на `watchCutoff`, `isPrunableDate`, `depthFromRoot`, `CAMERA_DEPTH`, `isDateAtUnexpectedDepth`.

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
 *
 * Symlinks: the walk runs without FOLLOW_LINKS, so symlinked directories arrive in `visitFile`
 * and depth rules never apply to them at all.
 */
internal fun depthFromRoot(
    path: Path,
    rootFolder: Path,
): Int {
    if (!path.startsWith(rootFolder)) return -1
    val rel = rootFolder.relativize(path)
    return if (rel.toString().isEmpty()) 0 else rel.nameCount
}

/**
 * Misplaced-root detector: a date extracts from [path], yet the FIRST segment under [rootFolder]
 * is not a date. In Frigate's layout the date is always the first segment under the root, so this
 * means `FRIGATE_RECORDS_FOLDER` points one level above the recordings root — the walk would then
 * stop at the hour level and never register camera directories, silently dropping ENTRY_CREATE.
 *
 * Implemented via [extractDateFromPath] rather than DATE_PATTERN so it works in this task's Step 3,
 * while DATE_PATTERN is still private to WatchRecordsLoop.kt (it moves here in Step 6).
 */
internal fun isDateAtUnexpectedDepth(
    path: Path,
    rootFolder: Path,
): Boolean {
    if (!path.startsWith(rootFolder) || path == rootFolder) return false
    val rel = rootFolder.relativize(path)
    val firstSegmentIsDate = extractDateFromPath(rootFolder.resolve(rel.getName(0)), rootFolder) != null
    return !firstSegmentIsDate && extractDateFromPath(path, rootFolder) != null
}
```

- [ ] **Step 4: Запустить тест и убедиться, что он проходит**

Через `claude-forge:build-runner`: `./gradlew :frigate-analyzer-core:test --tests 'ru.zinin.frigate.analyzer.core.task.RecordingsTreeTest'`

Ожидается: PASS, 12 тестов.

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

/**
 * Scans path segments FROM LEAF TO ROOT and returns the first date-like one. Inherited contract:
 * a date-like camera ID or a date-like directory below the date level yields the wrong answer —
 * change only together with `RecordingFileHelper.parse`. Date-like camera IDs are declared an
 * unsupported configuration.
 */
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

Порядок объявлений в файле: `CAMERA_DEPTH`, `DATE_PATTERN`, `watchCutoff`, `extractDateFromPath`, `isWithinWatchPeriod`, `isPrunableDate`, `depthFromRoot`, `isDateAtUnexpectedDepth`.

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

Ожидается: PASS. Общее число тестов не изменилось — 12 переехали, 12 добавились ранее.

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
- Consumes: `CAMERA_DEPTH`, `watchCutoff(Duration, Clock): LocalDate`, `isPrunableDate(Path, Path, LocalDate): Boolean`, `depthFromRoot(Path, Path): Int`, `isDateAtUnexpectedDepth(Path, Path): Boolean` из Task 1; `buildCanonicalTree(Path)` из шага 1 этой задачи
- Produces:
  - `data class RegistrationResult(val registered: Int, val prunedSubtrees: Int, val visitedEntries: Int, val visitedFiles: Int, val failed: Int)`
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
import org.junit.jupiter.api.assertThrows
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
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

            // 48 .mp4 files exist on disk. None of them may ever reach visitFile: the walk stops
            // at the camera level, and visitedFiles counts exactly what visitFile saw.
            assertEquals(17, result.visitedEntries)
            assertEquals(0, result.visitedFiles, "no recording file may be enumerated during registration")
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
            assertEquals(0, result.visitedFiles)
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
            assertEquals(0, result.visitedFiles)
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
            assertEquals(0, second.visitedFiles)
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
            // Under root (typical CI containers) chmod 000 does not restrict access, the assumption
            // below is always false and the test silently skips — the visitFileFailed path has
            // automated coverage only on machines with a regular UID. Known and accepted.
            assumeTrue(
                runCatching { Files.newDirectoryStream(locked).use { it.iterator().hasNext() } }.isFailure,
                "chmod 000 does not restrict this user (running as root?) — cannot simulate an unreadable directory",
            )
            val dirs = ConcurrentHashMap<Path, WatchKey>()

            val result = loopFor(root).registerAllDirs(root, watchService, dirs)

            // 15 expected minus the locked hour directory and its two cameras.
            assertEquals(12, result.registered)
            assertEquals(1, result.failed)
            // 12 registered + 2 pruned + 1 failed; the locked cameras are never reached.
            assertEquals(15, result.visitedEntries)
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

    @Test
    fun `registerAllDirs visits but does not register a stray file at the date level`() {
        val root = Files.createTempDirectory("rad-stray")
        val watchService = FileSystems.getDefault().newWatchService()
        try {
            buildCanonicalTree(root)
            val stray = root.resolve("2026-05-23/stray.txt")
            Files.createFile(stray)
            val dirs = ConcurrentHashMap<Path, WatchKey>()

            val result = loopFor(root).registerAllDirs(root, watchService, dirs)

            // A foreign file above the camera level is visited (that is unavoidable) but never
            // registered; visitedFiles counts exactly it, keeping the invariant observable.
            assertEquals(15, result.registered)
            assertEquals(18, result.visitedEntries)
            assertEquals(1, result.visitedFiles)
            assertFalse(dirs.containsKey(stray))
        } finally {
            watchService.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `registerAllDirs does not register a start below the camera level`() {
        val root = Files.createTempDirectory("rad-deep-start")
        val watchService = FileSystems.getDefault().newWatchService()
        try {
            buildCanonicalTree(root)
            val nested = root.resolve("2026-05-23/00/cam1/nested")
            Files.createDirectories(nested)
            val dirs = ConcurrentHashMap<Path, WatchKey>()

            // runIteration passes freshly created directories as start. One below the camera level
            // must not acquire a watch key: the startup walk would never restore it after the
            // WatchService is recreated, so it would silently vanish.
            val result = loopFor(root).registerAllDirs(nested, watchService, dirs)

            assertEquals(0, result.registered)
            assertEquals(1, result.visitedEntries)
            assertTrue(dirs.isEmpty())
        } finally {
            watchService.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `registerAllDirs throws when the start does not exist`() {
        val root = Files.createTempDirectory("rad-missing-start")
        val watchService = FileSystems.getDefault().newWatchService()
        try {
            val dirs = ConcurrentHashMap<Path, WatchKey>()

            // A typo in FRIGATE_RECORDS_FOLDER or an unmounted NFS volume must stay retryable:
            // ensureWatchService treats the throw as a registration failure and backs off —
            // a silently "successful" empty registration would leave health stuck DOWN forever.
            assertThrows<NoSuchFileException> {
                loopFor(root).registerAllDirs(root.resolve("gone"), watchService, dirs)
            }
            assertTrue(dirs.isEmpty())
        } finally {
            watchService.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `registerAllDirs throws when the start is a symlink`() {
        val root = Files.createTempDirectory("rad-symlink-start")
        val watchService = FileSystems.getDefault().newWatchService()
        try {
            buildCanonicalTree(root)
            val link = root.resolve("latest")
            Files.createSymbolicLink(link, root.resolve("2026-05-23"))
            val dirs = ConcurrentHashMap<Path, WatchKey>()

            // The walk runs without FOLLOW_LINKS, so a symlinked start is classified as a FILE
            // and the traversal would end after one visit with nothing registered.
            assertThrows<NotDirectoryException> {
                loopFor(root).registerAllDirs(link, watchService, dirs)
            }
            assertTrue(dirs.isEmpty())
        } finally {
            watchService.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `registerAllDirs degrades to a full walk for a start outside the root`() {
        val root = Files.createTempDirectory("rad-outside-root")
        val outside = Files.createTempDirectory("rad-outside-tree")
        val watchService = FileSystems.getDefault().newWatchService()
        try {
            val leaf = outside.resolve("a/b/c/d")
            Files.createDirectories(leaf)
            Files.createFile(leaf.resolve("file.bin"))
            val dirs = ConcurrentHashMap<Path, WatchKey>()

            // depthFromRoot == -1 for every path here: depth rules do not apply and the walk
            // visits everything — today's behaviour, deliberately preserved.
            val result = loopFor(root).registerAllDirs(outside, watchService, dirs)

            assertEquals(5, result.registered)
            assertEquals(6, result.visitedEntries)
            assertEquals(1, result.visitedFiles)
        } finally {
            watchService.close()
            outside.toFile().deleteRecursively()
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
import java.nio.file.NotDirectoryException
import java.nio.file.SimpleFileVisitor
```

и константу на уровне файла (рядом с существующими private-объявлениями):

```kotlin
/** After this many unreadable entries the per-entry WARNs collapse into one summary line. */
private const val FAILURE_LOG_LIMIT = 100
```

Добавить data-класс рядом с существующим `IterationResult` (перед объявлением класса `WatchRecordsLoop`):

```kotlin
/**
 * Outcome of one [WatchRecordsLoop.registerAllDirs] traversal.
 *
 * [visitedFiles] is the load-bearing number: it counts entries delivered to `visitFile` — files
 * and symlinks ABOVE the camera level. On Frigate's tree it is 0 on every call (first walk,
 * re-walk over a populated map, runIteration sub-walks alike): the walk never descends below
 * [CAMERA_DEPTH], so no `.mp4` is ever enumerated — which is exactly the regression this
 * traversal exists to prevent, observable straight from the log line.
 *
 * [registered] counts NEW insertions only; a repeat walk over already-registered directories
 * reports 0 while still visiting them, so no arithmetic identity ties it to [visitedEntries].
 *
 * [failed] counts unreadable entries the walk skipped ([java.nio.file.SimpleFileVisitor]'s
 * visitFileFailed). Non-zero means partial blindness until the next full re-registration —
 * an accepted, observable degradation (it is printed in the log line). A failure of the START
 * itself is not counted here: it is rethrown so the supervisor retries with backoff.
 */
data class RegistrationResult(
    val registered: Int,
    val prunedSubtrees: Int,
    val visitedEntries: Int,
    val visitedFiles: Int,
    val failed: Int,
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
        // apply two different cutoffs to different branches of the same tree. The window check is
        // deliberately duplicated with runIteration's pre-check — registerAllDirs must stay
        // correct for ANY start, whoever calls it.
        val cutoff = watchCutoff(recordsWatcherProperties.watchPeriod, clock)
        var registered = 0
        var pruned = 0
        var visited = 0
        var visitedFiles = 0
        var failed = 0
        var misplacedDateReported = false
        val startedAt = System.nanoTime()

        Files.walkFileTree(
            start,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    visited++
                    // Nothing below a camera directory ever holds a watch key, from EITHER call
                    // path: runIteration may pass a start deeper than CAMERA_DEPTH, and a key
                    // taken there would silently vanish after the WatchService is recreated —
                    // the startup walk would never restore it.
                    if (depthFromRoot(dir, root) > CAMERA_DEPTH) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    // The date check runs at EVERY level, not just at depth 1: it is pure string
                    // work, and that keeps the PRUNE independent of where dates actually sit.
                    // Descent is what depth controls — and with it camera registration, which is
                    // why the misplaced-root detector below exists.
                    if (isPrunableDate(dir, root, cutoff)) {
                        pruned++
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    if (!misplacedDateReported && isDateAtUnexpectedDepth(dir, root)) {
                        misplacedDateReported = true
                        logger.warn {
                            "Registration: date directory at unexpected depth: $dir — " +
                                "check FRIGATE_RECORDS_FOLDER (it probably points one level above the recordings root)"
                        }
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
                    // A start that is a plain file or a symlink (the walk runs without
                    // FOLLOW_LINKS, so a symlinked root is classified as a FILE): an empty
                    // "successful" registration would report health DOWN forever with no retry.
                    if (file == start) throw NotDirectoryException(start.toString())
                    visited++
                    visitedFiles++
                    return FileVisitResult.CONTINUE
                }

                // SimpleFileVisitor rethrows by default, which would abort the whole registration
                // because of a single unreadable directory. The walk opens a directory BEFORE
                // preVisitDirectory, so an unreadable one arrives here, not there. Message-only
                // WARN: on a mass failure (unmounted subtree) a stack trace per entry floods the
                // log; the full trace is one DEBUG switch away.
                override fun visitFileFailed(
                    file: Path,
                    exc: IOException,
                ): FileVisitResult {
                    // A failure of the START itself (missing or unreadable root) must stay fatal
                    // and retryable, exactly like Files.walk today: ensureWatchService catches,
                    // calls onRegistrationFailure and retries with backoff.
                    if (file == start) throw exc
                    visited++
                    failed++
                    if (failed <= FAILURE_LOG_LIMIT) {
                        logger.warn { "Registration: skipping unreadable entry $file (${exc.message})" }
                        logger.debug(exc) { "Registration: failure details for $file" }
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    dir: Path,
                    exc: IOException?,
                ): FileVisitResult {
                    if (exc != null) {
                        logger.warn { "Registration: error after visiting $dir (${exc.message})" }
                        logger.debug(exc) { "Registration: failure details for $dir" }
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )

        if (failed > FAILURE_LOG_LIMIT) {
            logger.warn { "Registration: ${failed - FAILURE_LOG_LIMIT} more unreadable entries suppressed" }
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        logger.info {
            "Registered $registered dirs, pruned $pruned date subtrees, " +
                "visited $visited entries ($visitedFiles files, $failed failed) in ${elapsedMs}ms"
        }
        return RegistrationResult(registered, pruned, visited, visitedFiles, failed)
    }
```

Оба call-site (`WatchRecordsTask.kt:244` и `WatchRecordsLoop.kt:83`) игнорируют возвращаемое значение — менять их не нужно.

- [ ] **Step 5: Запустить тесты и убедиться, что они проходят**

Через `claude-forge:build-runner`: `./gradlew :frigate-analyzer-core:test`

Ожидается: PASS. Если `WatchRecordsTaskTest` не компилируется — найти стаб вида `every { loop.registerAllDirs(...) } returns <Int>` и заменить возвращаемое значение на `RegistrationResult(0, 0, 0, 0, 0)`. На момент написания плана таких стабов нет: строки 436 и 467 используют `throws`.

- [ ] **Step 6: Обновить `.claude/rules/pipeline.md`**

Заменить блок «### Selective watching» на:

```markdown
### Selective watching

WatchRecordsLoop uses selective watching to limit monitored directories:
- Only directories within `WATCH_PERIOD` are monitored (date extracted from Frigate's `YYYY-MM-DD` structure)
- The root recordings directory is always watched to catch new date directories
- A periodic cleanup removes expired watch keys based on `WATCH_CLEANUP_INTERVAL`

`registerAllDirs` walks with `Files.walkFileTree` and prunes as it goes, rather than filtering after
the fact. Three rules, all in `preVisitDirectory`:
- `depthFromRoot(dir) > CAMERA_DEPTH` (reachable only when `runIteration` passes a deep `start`)
  returns `SKIP_SUBTREE` **without registering**: nothing below a camera directory ever holds a
  watch key, from either call path — such a key would silently vanish after the WatchService is
  recreated.
- `isPrunableDate` (fail-CLOSED, `RecordingsTree.kt`) returns `SKIP_SUBTREE` for a date outside the
  window. It is deliberately not `!isWithinWatchPeriod` (fail-OPEN) — the root's date never
  extracts, and pruning the root would blind the watcher to new date directories.
- `depthFromRoot(dir) >= CAMERA_DEPTH` returns `SKIP_SUBTREE` after registering the camera
  directory. Below it there are only `.mp4` files, and the watch key on the camera directory is
  what delivers ENTRY_CREATE. **`RegistrationResult.visitedFiles == 0` is the observable
  invariant: no recording file is ever enumerated during registration**, and the log line shows it
  directly (`... visited 320 entries (0 files) in 41ms`).

The cutoff is computed once per traversal so a walk crossing midnight cannot apply two different
windows. A date directory whose first path segment under the root is not the date itself triggers a
one-shot WARN (`isDateAtUnexpectedDepth`): the typical cause is `FRIGATE_RECORDS_FOLDER` pointing
one level above the recordings root, which would otherwise silently stop camera registration.

Operator notes: the registration log line changed from `Registered N directories, skipped 11638 old
directories` to `Registered N dirs, pruned 122 date subtrees, visited 320 entries (0 files) in Xms`.
`pruned` counts whole date **subtrees**, not directories one by one — the number dropping by two
orders of magnitude is expected, not a regression. Before relying on the old line, check no external
log parsing is tied to its format. Symlinks inside the recordings tree are unsupported: the walk
does not follow them and they no longer acquire watch keys.

Error policy — strict root, soft-but-visible subtrees. A failure of the START itself (missing or
unreadable root: `visitFileFailed` rethrows; root that is a plain file or symlink:
`NotDirectoryException` from `visitFile`) stays fatal and lands in the supervisor's
backoff-and-retry, exactly like `Files.walk` before — an empty "successful" registration can never
leave health stuck DOWN. An unreadable SUBDIRECTORY reaches `visitFileFailed` (the walk opens a
directory before `preVisitDirectory` runs) and is counted in `RegistrationResult.failed`, logged
(per-entry WARNs collapse into one summary after `FAILURE_LOG_LIMIT = 100`) and skipped — accepted
observable degradation instead of a permanent retry loop over one broken directory. A failure of
`dir.register(...)` itself (inotify ENOSPC/EACCES) still aborts the walk and lands in the
supervisor's backoff-and-retry, as before.

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
- Modify: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/properties/ObjectTrackerPropertiesBindingTest.kt` (только комментарий)
- Create: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/properties/RecordsWatcherPropertiesBindingTest.kt`

**Interfaces:**
- Consumes: ничего из предыдущих задач
- Produces: `RecordsWatcherProperties.firstScanPeriod: Duration` (дефолт `= watchPeriod`) — потребляется Task 4

- [ ] **Step 1: Написать падающий биндинг-тест**

Создать `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/properties/RecordsWatcherPropertiesBindingTest.kt`. Форма повторяет соседний `ObjectTrackerPropertiesBindingTest`:

```kotlin
package ru.zinin.frigate.analyzer.core.config.properties

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.core.io.FileSystemResource
import java.io.File
import java.nio.file.Path
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
    fun `with nothing set, the startup scan is disabled`() {
        // The scan is an opt-in backfill: a fixed scan that actually finishes would otherwise run
        // a never-observed ~52k backfill on a fresh deployment with default settings.
        assertThat(bind().disableFirstScan).isTrue()
    }

    @Test
    fun `a sub-day first-scan-period is rejected instead of being silently truncated`() {
        // toDays() truncates: PT12H would silently behave as "today only".
        assertThatThrownBy {
            RecordsWatcherProperties(
                folder = Path.of("/tmp"),
                firstScanPeriod = Duration.ofHours(12),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("whole days")
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

Дополнительно (в этом же шаге): обновить устаревающий комментарий в соседнем `ObjectTrackerPropertiesBindingTest.kt` — фраза вида «Nothing else reads that file» про продовый `application.yaml` после этой задачи станет ложной: его читает и новый `RecordsWatcherPropertiesBindingTest`. Заменить на актуальную формулировку (например, «`RecordsWatcherPropertiesBindingTest` binds the same file the same way»).

- [ ] **Step 2: Запустить тест и убедиться, что он падает**

Через `claude-forge:build-runner`: `./gradlew :frigate-analyzer-core:test --tests 'ru.zinin.frigate.analyzer.core.config.properties.RecordsWatcherPropertiesBindingTest'`

Ожидается: компиляция падает — у `RecordsWatcherProperties` нет `firstScanPeriod`.

- [ ] **Step 3: Добавить свойство**

В `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/RecordsWatcherProperties.kt` добавить поле **последним параметром конструктора, после `cleanupInterval`** — вставка в середину сломала бы будущие позиционные вызовы, а Kotlin-дефолт может ссылаться на любой более ранний параметр (тот же приём, что `reappearGap: Duration = ttl` в `ObjectTrackerProperties`) — и строку валидации:

```kotlin
    @field:NotNull
    val cleanupInterval: Duration = Duration.ofHours(1),
    /**
     * How far back the one-off startup scan indexes files. Defaults to [watchPeriod]: the backfill
     * covers exactly the window the watcher watches.
     *
     * Filtering is by date, so the window is always whole days **in UTC** — Frigate names date
     * directories by UTC and [watchCutoff] evaluates "today" in UTC as well (documented
     * assumption; it matters for `P0D` on hosts west of UTC). `P0D` means today only, `P1D` means
     * today and yesterday. The lower bound is `P0D` rather than `watchPeriod`'s one day, otherwise
     * "scan today only" would be inexpressible.
     */
    @field:NotNull
    val firstScanPeriod: Duration = watchPeriod,
```

и в блок `init`:

```kotlin
        require(!firstScanPeriod.isNegative) { "firstScanPeriod must not be negative, got: $firstScanPeriod" }
        // Целые сутки: toDays() молча усекает, и PT12H превратился бы в «только сегодня».
        require(firstScanPeriod == Duration.ofDays(firstScanPeriod.toDays())) {
            "firstScanPeriod must be whole days (P0D, P1D, ...), got: $firstScanPeriod"
        }
```

В этом же файле изменить дефолт первого параметра — скан становится opt-in бэкфиллом:

```kotlin
    // true: скан — opt-in бэкфилл (первичная установка, восстановление индекса). Дефолт приведён
    // к единственной реальной эксплуатации; починенный (наконец доживающий до конца) скан при
    // включённом дефолте впервые исполнил бы никем не наблюдавшийся ~52k-бэкфилл на свежем деплое.
    val disableFirstScan: Boolean = true,
```

- [ ] **Step 4: Добавить строки в `application.yaml`**

В блоке `records-watcher` (строки 28-33) заменить строку `disable-first-scan`:

```yaml
    # Disabled by default: the startup scan is an opt-in backfill (first install, index recovery).
    # A fixed scan that actually finishes would otherwise run a never-observed ~52k backfill on a
    # fresh deployment with default settings.
    disable-first-scan: ${DISABLE_FIRST_SCAN:true}
```

и добавить после `watch-period`:

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

Ожидается: PASS, 8 новых тестов.

- [ ] **Step 6: Обновить `docker/deploy/.env.example`**

Вставить между блоком `# Paths` и блоком `# Host port mapping`. Соблюдается конвенция файла: комментарий **над** строкой, никогда inline — парсеры `env_file` не считают `#` inline-комментарием и запекли бы текст в значение.

```
# --- Records watcher ---
# The one-off startup scan that indexes recordings already on disk is DISABLED by default.
# Set to false to run the backfill once (first install, index recovery) — read FIRST_SCAN_PERIOD first.
# DISABLE_FIRST_SCAN=true
# How far back the watcher registers directories. Whole days in UTC, at least P1D.
# WATCH_PERIOD=P1D
# How far back the startup scan indexes files. Defaults to WATCH_PERIOD, so raising WATCH_PERIOD
# silently widens the startup backfill in the same proportion. Whole days in UTC; P0D = today only
# (WATCH_PERIOD itself must stay at least P1D). Every indexed file becomes a recording and is fed
# to the detection pipeline, so keep it small: one day of three cameras at 10-second segments is
# about 52 000 files.
# FIRST_SCAN_PERIOD=P1D
# How often expired watch keys are cleaned up (ISO-8601 duration).
# WATCH_CLEANUP_INTERVAL=PT1H
# Expose /actuator/health details: always | never | when-authorized. `always` shows the failure
# reason, registered directory paths, timestamps and the text of the last error to anyone who can
# reach the published port. Spring's relaxed binding also honours
# MANAGEMENT_ENDPOINT_HEALTH_SHOWDETAILS — set only one of the two.
# HEALTH_SHOW_DETAILS=always
```

- [ ] **Step 7: Обновить `.claude/rules/configuration.md`**

В таблицу раздела «## Records Watcher» добавить строку после `WATCH_PERIOD`:

```markdown
| `FIRST_SCAN_PERIOD` | = `WATCH_PERIOD` | ISO-8601 duration, how far back the startup scan indexes files. Whole days **in UTC** (Frigate names date directories by UTC): `P0D` = today only, `P1D` = today and yesterday; sub-day values are rejected at startup instead of being silently truncated. Defaults to the resolved `watch-period`, so it follows it from any source — raising `WATCH_PERIOD` widens the startup backfill in the same proportion. Every indexed file becomes a `recordings` row and enters the detection pipeline — one day of three cameras at 10-second segments is ~52 000 files. Note the validation asymmetry: `WATCH_PERIOD` must stay ≥ `P1D`; "today only" is expressible only here. |
```

Обновить строку `DISABLE_FIRST_SCAN` в той же таблице: дефолт **true**, формулировка «the startup scan is an opt-in backfill (first install, index recovery); set to `false` to run it once».

Сразу после таблицы Records Watcher добавить сноску:

```markdown
Поля `RecordsWatcherProperties` имеют Kotlin-дефолты, и `first-scan-period` добавлен последним параметром конструктора — позиционные вызовы конструктора не использовать, только именованные.
```

Сразу после этой таблицы добавить новый раздел:

```markdown
## Actuator

| Variable | Default | Purpose |
|----------|---------|---------|
| `HEALTH_SHOW_DETAILS` | always | `management.endpoint.health.show-details`. With `never` (Spring Boot's own default) `/actuator/health` returns a bare status, and `WatchRecordsTask.computeHealth`'s `reason`, `registeredDirs` and `lastSuccessfulRegistrationAt` are discarded — which is why a 9-minute registration stall had to be diagnosed from logs. `always` exposes them — including filesystem paths (`registeredDirs`) and the text of the last failure — to anyone who can reach the published port; accepted for a single-deployment behind a closed perimeter, switch to `never`/`when-authorized` otherwise. Spring's relaxed binding also honours `MANAGEMENT_ENDPOINT_HEALTH_SHOWDETAILS`; set only one of the two variables. |
```

- [ ] **Step 8: Коммит**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/RecordsWatcherProperties.kt \
        modules/core/src/main/resources/application.yaml \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/properties/RecordsWatcherPropertiesBindingTest.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/properties/ObjectTrackerPropertiesBindingTest.kt \
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
                // 1 root + 4 dates + 4 hours + 8 cameras + 24 files — the appendix breakdown.
                assertEquals(41, result.visitedEntries)
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
                // 1 root + 4 dates + 2 hours + 4 cameras + 12 files.
                assertEquals(23, result.visitedEntries)
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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.absolutePathString

private val logger = KotlinLogging.logger {}

private const val SCAN_CONCURRENCY = 8

/**
 * A file collected by the walk together with its creation time. The walk already stat-ed every
 * entry to produce [BasicFileAttributes]; re-reading attributes per file in the processing flow
 * would be the same double-stat defect this task removes from registerAllDirs.
 */
private data class ScanFile(
    val path: Path,
    val createdAt: Instant,
)

@Component
class FirstTimeScanTask(
    private val recordsWatcherProperties: RecordsWatcherProperties,
    private val recordingEntityHelper: RecordingEntityHelper,
    private val recordingFileHelper: RecordingFileHelper,
    private val clock: Clock,
) {
    internal data class ScanResult(
        /** Successfully processed files: create-or-find, NOT "new rows" — a duplicate returns the existing id. */
        val indexed: Int,
        val failed: Int,
        val prunedSubtrees: Int,
        /** Every filesystem entry visited: directories + files (files are the point here, unlike registerAllDirs). */
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
        val files = mutableListOf<ScanFile>()
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
                        // Attributes come from the walk itself — no per-file re-stat. Only real
                        // .mp4 files are collected: foreign files (thumbnails, tmp) are skipped
                        // silently instead of inflating `failed` via a doomed parse().
                        if (attrs.isRegularFile && file.fileName.toString().endsWith(".mp4")) {
                            files += ScanFile(file, attrs.creationTime().toInstant())
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        file: Path,
                        exc: IOException,
                    ): FileVisitResult {
                        visited++
                        logger.warn { "First scan: skipping unreadable entry $file (${exc.message})" }
                        logger.debug(exc) { "First scan: failure details for $file" }
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(
                        dir: Path,
                        exc: IOException?,
                    ): FileVisitResult {
                        if (exc != null) {
                            logger.warn { "First scan: error after visiting $dir (${exc.message})" }
                            logger.debug(exc) { "First scan: failure details for $dir" }
                        }
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
            .flatMapMerge(concurrency = SCAN_CONCURRENCY) { scanFile ->
                flow {
                    // Per-file isolation. The previous shape put `.catch {}` on the outer chain,
                    // which TERMINATES the flow: the first failing file (unparseable name, a file
                    // Frigate deleted mid-walk, a raced IllegalStateException) silently killed the
                    // whole scan. A duplicate file_path does NOT fail: createRecording is
                    // create-or-find and returns the existing id with a WARN.
                    val id: UUID? =
                        try {
                            val recordingFile = recordingFileHelper.parse(scanFile.path)
                            recordingEntityHelper.createRecording(
                                CreateRecordingRequest(
                                    scanFile.path.absolutePathString(),
                                    scanFile.createdAt,
                                    recordingFile.camId,
                                    recordingFile.date,
                                    recordingFile.time,
                                    recordingFile.timestamp,
                                ),
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.warn(e) { "First scan: skipping ${scanFile.path}" }
                            failed.incrementAndGet()
                            null
                        }
                    if (id != null) {
                        indexed.incrementAndGet()
                        emit(id)
                    }
                }
            }.catch { e ->
                // Flow.catch swallows CancellationException too — without this re-throw the
                // documented cancellability of scan() would be fiction.
                if (e is CancellationException) throw e
                logger.error(e) { "First scan aborted unexpectedly" }
            }
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
| FirstTimeScanTask | `core/task/` | One-off startup backfill of `.mp4` files already on disk, bounded by `FIRST_SCAN_PERIOD` (defaults to `WATCH_PERIOD`; whole days in UTC); disable with `DISABLE_FIRST_SCAN=true`. Prunes out-of-window date subtrees the same way `registerAllDirs` does and reuses the walk's own file attributes (no per-file re-stat). Per-file failures are counted and skipped, not fatal — the previous `.catch {}` on the outer flow terminated the whole scan on the first bad file. `indexed` means create-or-find: a re-run over an already indexed window finishes but logs ~52k "Recording already exists" warnings. Logic lives in `scan()`; `run()` is a detached fire-and-forget launch — TODO: the scope is not cancelled on shutdown (known, out of scope), which is exactly why tests drive `scan()` directly. |
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

Порядок «сначала ревью, потом сборка» задан в `CLAUDE.md`.

- [ ] **Код-ревью**

Через skill `superpowers:requesting-code-review` — на диффе ветки относительно `master`. Чинить критичные замечания до чистоты.

На что смотреть прицельно:
- в `preVisitDirectory` порядок правил: guard `> CAMERA_DEPTH` → prune по дате → детектор → регистрация; prune идёт ДО регистрации, иначе каталог вне окна успеет получить watch key;
- проверка `>= CAMERA_DEPTH` (остановка спуска) вызывается после `computeIfAbsent`, а не вместо проверки даты — глубина не должна влиять на отсев;
- ни одна ветка не превратилась в `!isWithinWatchPeriod(...)`;
- `visitFileFailed` возвращает `CONTINUE`, а не наследует пробрасывающую реализацию `SimpleFileVisitor`;
- внешний `.catch` в `scan()` пробрасывает `CancellationException`;
- в `scan()` нет второго `readAttributes` — атрибуты приходят из `visitFile`.

- [ ] **Полная сборка**

Через `claude-forge:build-runner`: `./gradlew build`

Ожидается: BUILD SUCCESSFUL. На ошибках ktlint — `./gradlew ktlintFormat`, затем повторить сборку.

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
| `visitedFiles` | **0** | **0** | — | — |
| `failed` | 0 | 0 | — | — |
| посещено `.mp4` | **0** | **0** | 24 | 12 |

Для `scan()` файлы вне окна тоже не перечисляются — отсекаются вместе с датой. `visitedEntries = 41` при `P1D`: корень 1 + даты 4 + часы 4 + камеры 8 + файлы 24. При `P0D`: 1 + 4 + 2 + 4 + 12 = 23.

`registered = 15` — корень 1 + даты 2 + часы 4 + камеры 8.
`visitedEntries = 17` — 15 зарегистрированных + 2 отсечённые даты. Проверяемый инвариант «файлы не перечислялись» — `visitedFiles == 0`: он безусловен и держится на любом вызове (повторном, из `runIteration`, при сбоях), в отличие от арифметики `registered + prunedSubtrees`, которая верна только для первого прохода по пустой map.

Тест с посторонним файлом на уровне даты: `registered = 15`, `visitedEntries = 18`, `visitedFiles = 1`. Тест со `start` вне корня (дерево `a/b/c/d` + 1 файл): `registered = 5`, `visitedEntries = 6`, `visitedFiles = 1`. Тест с `chmod 000` на каталоге часа: `registered = 12`, `failed = 1`, `visitedEntries = 15` (12 + 2 pruned + 1 failed). Несуществующий и симлинк-`start` бросают до каких-либо счётчиков.
