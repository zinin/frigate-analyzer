# Notification Schedule Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Global (OWNER-configured) daily schedule that suppresses recording-detection Telegram notifications outside a configured time window (e.g. deliver only 00:00–07:00).

**Architecture:** Three new string keys in the existing `app_settings` table, read through a new fail-open `NotificationScheduleService`; one new suppression branch (`OUT_OF_SCHEDULE`) in `NotificationDecisionServiceImpl` keyed off `recording.recordTimestamp` in the schedule's explicit timezone; UI is an extension of the `/notifications` dialog — stateless inline hour pickers and a zone screen under the new `nfs:g:sched:*` callback subtree.

**Tech Stack:** Kotlin 2.3, Spring Boot 4, R2DBC/PostgreSQL, ktgbotapi (inline keyboards + waiter API), JUnit 5 + mockk + kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-07-18-notification-schedule-design.md` (same branch).

## Global Constraints

- Gradle module names carry the `frigate-analyzer-` prefix: `:frigate-analyzer-model`, `:frigate-analyzer-service`, `:frigate-analyzer-telegram`.
- Do NOT run `./gradlew build` directly in the main session — dispatch the `build-runner` agent (project CLAUDE.md). Per-task module tests (`./gradlew :frigate-analyzer-<module>:test --tests "..."`) also go through `build-runner`.
- On ktlint errors: `./gradlew ktlintFormat`, then retry.
- `git add <file>` immediately after creating or modifying every file (project CLAUDE.md rule).
- Every user-visible string goes to BOTH `messages_en.properties` AND `messages_ru.properties` (`MessageKeyParityTest` fails otherwise).
- Telegram callback data must stay ≤ 64 bytes; longest new payload is `nfs:g:sched:z:Asia/Yekaterinburg` (32 bytes).
- Storage window format is `HH:mm-HH:mm` (hyphen); display format is `HH:mm–HH:mm` (en dash).
- Half-open interval semantics `[start, end)` everywhere; `start > end` = midnight crossing; `start == end` is invalid.
- New settings reads must be fail-open: corrupt/unreadable schedule → warn + treat as disabled (notifications flow).

---

### Task 1: `ScheduleWindow` + `NotificationSchedule` model types

**Files:**
- Modify: `modules/model/build.gradle.kts`
- Create: `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/NotificationSchedule.kt`
- Test: `modules/model/src/test/kotlin/ru/zinin/frigate/analyzer/model/dto/NotificationScheduleTest.kt` (new — model module has no test dir yet; root build already applies `useJUnitPlatform()` to all modules)

**Interfaces:**
- Consumes: nothing new.
- Produces (used by Tasks 3, 4, 5, 8):
  - `ScheduleWindow(start: LocalTime, end: LocalTime)` with `contains(time: LocalTime): Boolean`, `storageFormat(): String`, `displayFormat(): String`, `companion { parse(raw: String): ScheduleWindow?; ofHours(startHour: Int, endHour: Int): ScheduleWindow }`
  - `NotificationSchedule(window: ScheduleWindow, zone: ZoneId)` with `contains(instant: Instant): Boolean`

- [ ] **Step 1: Add test dependencies to the model module**

In `modules/model/build.gradle.kts` append to the existing `dependencies` block:

```kotlin
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
```

`git add modules/model/build.gradle.kts`

- [ ] **Step 2: Write the failing test**

Create `modules/model/src/test/kotlin/ru/zinin/frigate/analyzer/model/dto/NotificationScheduleTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.model.dto

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationScheduleTest {
    private val plain = ScheduleWindow(LocalTime.of(0, 0), LocalTime.of(7, 0))
    private val crossing = ScheduleWindow(LocalTime.of(23, 0), LocalTime.of(7, 0))

    // -- parse --

    @Test
    fun `parse accepts plain window`() {
        assertEquals(plain, ScheduleWindow.parse("00:00-07:00"))
    }

    @Test
    fun `parse accepts midnight-crossing window`() {
        assertEquals(crossing, ScheduleWindow.parse("23:00-07:00"))
    }

    @Test
    fun `parse rejects garbage`() {
        assertNull(ScheduleWindow.parse("garbage"))
    }

    @Test
    fun `parse rejects missing part`() {
        assertNull(ScheduleWindow.parse("00:00-"))
    }

    @Test
    fun `parse rejects single-digit hours`() {
        assertNull(ScheduleWindow.parse("0:00-7:00"))
    }

    @Test
    fun `parse rejects hour 24`() {
        // SMART resolver would silently turn "24:00" into 00:00 — STRICT must reject it.
        assertNull(ScheduleWindow.parse("24:00-07:00"))
    }

    @Test
    fun `parse rejects invalid minutes`() {
        assertNull(ScheduleWindow.parse("00:60-07:00"))
    }

    @Test
    fun `parse rejects equal start and end`() {
        assertNull(ScheduleWindow.parse("07:00-07:00"))
    }

    @Test
    fun `storage format round-trips through parse`() {
        assertEquals(crossing, ScheduleWindow.parse(crossing.storageFormat()))
        assertEquals("23:00-07:00", crossing.storageFormat())
    }

    @Test
    fun `display format uses en dash`() {
        assertEquals("00:00–07:00", plain.displayFormat())
    }

    @Test
    fun `ofHours builds whole-hour window`() {
        assertEquals(plain, ScheduleWindow.ofHours(0, 7))
    }

    @Test
    fun `equal start and end is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { ScheduleWindow.ofHours(7, 7) }
    }

    // -- contains: plain window --

    @Test
    fun `plain window includes start boundary`() {
        assertTrue(plain.contains(LocalTime.of(0, 0)))
    }

    @Test
    fun `plain window excludes end boundary`() {
        assertFalse(plain.contains(LocalTime.of(7, 0)))
    }

    @Test
    fun `plain window includes inner point`() {
        assertTrue(plain.contains(LocalTime.of(3, 30)))
    }

    @Test
    fun `plain window excludes daytime point`() {
        assertFalse(plain.contains(LocalTime.of(12, 0)))
    }

    // -- contains: midnight crossing --

    @Test
    fun `crossing window includes start boundary`() {
        assertTrue(crossing.contains(LocalTime.of(23, 0)))
    }

    @Test
    fun `crossing window includes late evening`() {
        assertTrue(crossing.contains(LocalTime.of(23, 30)))
    }

    @Test
    fun `crossing window includes early morning`() {
        assertTrue(crossing.contains(LocalTime.of(3, 0)))
    }

    @Test
    fun `crossing window includes midnight`() {
        assertTrue(crossing.contains(LocalTime.MIDNIGHT))
    }

    @Test
    fun `crossing window excludes end boundary`() {
        assertFalse(crossing.contains(LocalTime.of(7, 0)))
    }

    @Test
    fun `crossing window excludes daytime`() {
        assertFalse(crossing.contains(LocalTime.of(12, 0)))
    }

    // -- NotificationSchedule: zone-aware membership --

    @Test
    fun `instant is evaluated in the schedule zone`() {
        val schedule = NotificationSchedule(plain, ZoneId.of("Europe/Moscow"))
        // 23:30 UTC == 02:30 next day in Moscow (UTC+3) → inside 00:00–07:00
        assertTrue(schedule.contains(Instant.parse("2026-07-17T23:30:00Z")))
        // 12:00 UTC == 15:00 Moscow → outside
        assertFalse(schedule.contains(Instant.parse("2026-07-17T12:00:00Z")))
    }

    @Test
    fun `dst transition day evaluates without error`() {
        // Europe/Berlin switches to CEST on 2026-03-29 (02:00→03:00 local).
        val schedule = NotificationSchedule(plain, ZoneId.of("Europe/Berlin"))
        // 01:30 UTC == 03:30 CEST → inside 00:00–07:00
        assertTrue(schedule.contains(Instant.parse("2026-03-29T01:30:00Z")))
    }
}
```

`git add` the test file.

- [ ] **Step 3: Run the test to verify it fails**

Via `build-runner` agent: `./gradlew :frigate-analyzer-model:test --tests "ru.zinin.frigate.analyzer.model.dto.NotificationScheduleTest"`
Expected: COMPILATION FAILURE — `ScheduleWindow` / `NotificationSchedule` unresolved.

- [ ] **Step 4: Write the implementation**

Create `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/NotificationSchedule.kt`:

```kotlin
package ru.zinin.frigate.analyzer.model.dto

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/**
 * Daily notification window over the half-open interval [start, end).
 * start > end means the window crosses midnight (e.g. 23:00-07:00).
 * start == end is not a valid window: [parse] returns null, direct construction throws.
 */
data class ScheduleWindow(
    val start: LocalTime,
    val end: LocalTime,
) {
    init {
        require(start != end) { "Window start must differ from end" }
    }

    /** Membership in [start, end) with midnight wrap when start > end. */
    fun contains(time: LocalTime): Boolean =
        if (start < end) {
            time >= start && time < end
        } else {
            time >= start || time < end
        }

    /** Storage form, e.g. "00:00-07:00". */
    fun storageFormat(): String = "${FORMATTER.format(start)}-${FORMATTER.format(end)}"

    /** Human form for dialogs, e.g. "00:00–07:00" (en dash). */
    fun displayFormat(): String = "${FORMATTER.format(start)}–${FORMATTER.format(end)}"

    companion object {
        private val FORMATTER = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT)

        /** Parses the storage form; null on malformed input or start == end. */
        fun parse(raw: String): ScheduleWindow? {
            val parts = raw.split("-")
            if (parts.size != 2) return null
            val start = runCatching { LocalTime.parse(parts[0], FORMATTER) }.getOrNull() ?: return null
            val end = runCatching { LocalTime.parse(parts[1], FORMATTER) }.getOrNull() ?: return null
            if (start == end) return null
            return ScheduleWindow(start, end)
        }

        fun ofHours(
            startHour: Int,
            endHour: Int,
        ): ScheduleWindow = ScheduleWindow(LocalTime.of(startHour, 0), LocalTime.of(endHour, 0))
    }
}

/** Effective global schedule for recording-detection notifications. */
data class NotificationSchedule(
    val window: ScheduleWindow,
    val zone: ZoneId,
) {
    /** True when [instant], viewed in [zone], falls inside the window. */
    fun contains(instant: Instant): Boolean = window.contains(instant.atZone(zone).toLocalTime())
}
```

`git add` the file.

- [ ] **Step 5: Run the test to verify it passes**

Via `build-runner` agent: `./gradlew :frigate-analyzer-model:test --tests "ru.zinin.frigate.analyzer.model.dto.NotificationScheduleTest"`
Expected: BUILD SUCCESSFUL, all tests PASS.

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(model): schedule window and notification schedule types"
```

---

### Task 2: `AppSettingsService.getString` / `setString`

**Files:**
- Modify: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/AppSettingsService.kt`
- Modify: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/AppSettingsServiceImpl.kt`
- Test: `modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/impl/AppSettingsServiceImplTest.kt` (append tests)

**Interfaces:**
- Consumes: existing `AppSettingRepository.upsert/findBySettingKey`, existing cache/mutex fields.
- Produces (used by Task 3): `suspend fun getString(key: String, default: String? = null): String?`, `suspend fun setString(key: String, value: String, updatedBy: String? = null)`.

- [ ] **Step 1: Write the failing tests**

Append to `AppSettingsServiceImplTest` (inside the class; reuse existing `repo`/`fixed`/`clock`/`service` fields; add `import kotlin.test.assertEquals` and `import kotlin.test.assertNull` to the imports):

```kotlin
    @Test
    fun `getString returns stored value and caches it`() =
        runTest {
            coEvery { repo.findBySettingKey("s") } returns AppSettingEntity("s", "hello", fixed, null)

            assertEquals("hello", service.getString("s"))
            assertEquals("hello", service.getString("s"))

            coVerify(exactly = 1) { repo.findBySettingKey("s") }
        }

    @Test
    fun `getString returns default when key missing`() =
        runTest {
            coEvery { repo.findBySettingKey("missing") } returns null

            assertNull(service.getString("missing"))
            assertEquals("fallback", service.getString("missing", default = "fallback"))
        }

    @Test
    fun `setString upserts and invalidates cache`() =
        runTest {
            coEvery { repo.findBySettingKey("s") } returns AppSettingEntity("s", "old", fixed, null)
            coEvery { repo.upsert("s", "new", fixed, "alice") } returns 1L

            // populate cache
            assertEquals("old", service.getString("s"))
            // change
            service.setString("s", "new", updatedBy = "alice")
            // read again — must hit DB because cache invalidated
            coEvery { repo.findBySettingKey("s") } returns AppSettingEntity("s", "new", fixed, "alice")
            assertEquals("new", service.getString("s"))

            coVerify(exactly = 1) { repo.upsert("s", "new", fixed, "alice") }
            coVerify(atLeast = 2) { repo.findBySettingKey("s") }
        }
```

`git add` the test file.

- [ ] **Step 2: Run tests to verify they fail**

Via `build-runner` agent: `./gradlew :frigate-analyzer-service:test --tests "ru.zinin.frigate.analyzer.service.impl.AppSettingsServiceImplTest"`
Expected: COMPILATION FAILURE — `getString` / `setString` unresolved.

- [ ] **Step 3: Extend the interface**

In `AppSettingsService.kt` add below `setBoolean`:

```kotlin
    suspend fun getString(
        key: String,
        default: String? = null,
    ): String?

    suspend fun setString(
        key: String,
        value: String,
        updatedBy: String? = null,
    )
```

- [ ] **Step 4: Implement in `AppSettingsServiceImpl`**

Add below `setBoolean` (same file structure — cache lookup mirrors `getBoolean`, write mirrors `setBoolean`):

```kotlin
    override suspend fun getString(
        key: String,
        default: String?,
    ): String? {
        val raw =
            cache[key] ?: cacheMutex.withLock {
                cache[key] ?: loadAndCache(key)
            }
        return raw ?: default
    }

    override suspend fun setString(
        key: String,
        value: String,
        updatedBy: String?,
    ) {
        val rows = repository.upsert(key, value, Instant.now(clock), updatedBy)
        if (rows == 0L) {
            logger.warn { "AppSettings: upsert of '$key' reported 0 affected rows" }
        }
        cacheMutex.withLock {
            cache.remove(key)
        }
        // Value at debug only: general-purpose method, a future key may hold something sensitive.
        logger.info { "AppSettings: '$key' set by ${updatedBy ?: "<system>"}" }
        logger.debug { "AppSettings: '$key' value: '$value'" }
    }
```

Apply the same two changes to the existing `setBoolean` for consistency (check the upsert
return with a `0L` warn; move the value from the info line to a debug line).

`git add` both files.

- [ ] **Step 5: Run tests to verify they pass**

Via `build-runner` agent: `./gradlew :frigate-analyzer-service:test --tests "ru.zinin.frigate.analyzer.service.impl.AppSettingsServiceImplTest"`
Expected: PASS (new and pre-existing tests).

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(service): string values in AppSettingsService"
```

---

### Task 3: `AppSettingKeys` + `NotificationScheduleService`

**Files:**
- Modify: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/AppSettingKeys.kt`
- Create: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/NotificationScheduleService.kt`
- Create: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/NotificationScheduleServiceImpl.kt`
- Test: `modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/impl/NotificationScheduleServiceImplTest.kt`

**Interfaces:**
- Consumes: `AppSettingsService.getBoolean/setBoolean/getString/setString` (Task 2), `ScheduleWindow`/`NotificationSchedule` (Task 1).
- Produces (used by Tasks 4, 5, 8, 9):

```kotlin
interface NotificationScheduleService {
    suspend fun getRecordingSchedule(): NotificationSchedule?  // null = disabled/unset/corrupt; NEVER throws
    suspend fun isEnabled(): Boolean
    suspend fun getWindow(): ScheduleWindow?
    suspend fun getZone(): ZoneId?
    suspend fun setEnabled(value: Boolean, updatedBy: String?)
    suspend fun setWindow(window: ScheduleWindow, updatedBy: String?)
    suspend fun setZone(zone: ZoneId, updatedBy: String?)
}
```

- Key constants: `NOTIFICATIONS_RECORDING_SCHEDULE_ENABLED = "notifications.recording.schedule.enabled"`, `NOTIFICATIONS_RECORDING_SCHEDULE_WINDOW = "notifications.recording.schedule.window"`, `NOTIFICATIONS_RECORDING_SCHEDULE_ZONE = "notifications.recording.schedule.zone"`.

- [ ] **Step 1: Write the failing tests**

Create `NotificationScheduleServiceImplTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.service.impl

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.model.dto.NotificationSchedule
import ru.zinin.frigate.analyzer.model.dto.ScheduleWindow
import ru.zinin.frigate.analyzer.service.AppSettingKeys
import ru.zinin.frigate.analyzer.service.AppSettingsService
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotificationScheduleServiceImplTest {
    private val settings = mockk<AppSettingsService>()
    private val service = NotificationScheduleServiceImpl(settings)

    private fun stub(
        enabled: Boolean? = false,
        window: String? = null,
        zone: String? = null,
    ) {
        if (enabled != null) {
            coEvery {
                settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_SCHEDULE_ENABLED, false)
            } returns enabled
        }
        coEvery { settings.getString(AppSettingKeys.NOTIFICATIONS_RECORDING_SCHEDULE_WINDOW) } returns window
        coEvery { settings.getString(AppSettingKeys.NOTIFICATIONS_RECORDING_SCHEDULE_ZONE) } returns zone
    }

    @Test
    fun `disabled schedule yields null`() =
        runTest {
            stub(enabled = false, window = "00:00-07:00", zone = "Europe/Moscow")
            assertNull(service.getRecordingSchedule())
        }

    @Test
    fun `enabled with valid window and zone yields schedule`() =
        runTest {
            stub(enabled = true, window = "23:00-07:00", zone = "Europe/Moscow")
            assertEquals(
                NotificationSchedule(ScheduleWindow.parse("23:00-07:00")!!, ZoneId.of("Europe/Moscow")),
                service.getRecordingSchedule(),
            )
        }

    @Test
    fun `enabled without window yields null (fail-open)`() =
        runTest {
            stub(enabled = true, window = null, zone = "Europe/Moscow")
            assertNull(service.getRecordingSchedule())
        }

    @Test
    fun `enabled with corrupt window yields null (fail-open)`() =
        runTest {
            stub(enabled = true, window = "garbage", zone = "Europe/Moscow")
            assertNull(service.getRecordingSchedule())
        }

    @Test
    fun `enabled with equal start and end yields null (fail-open)`() =
        runTest {
            stub(enabled = true, window = "07:00-07:00", zone = "Europe/Moscow")
            assertNull(service.getRecordingSchedule())
        }

    @Test
    fun `enabled with corrupt zone yields null (fail-open)`() =
        runTest {
            stub(enabled = true, window = "00:00-07:00", zone = "Not/AZone")
            assertNull(service.getRecordingSchedule())
        }

    @Test
    fun `settings read failure yields null instead of throwing (fail-open)`() =
        runTest {
            coEvery {
                settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_SCHEDULE_ENABLED, false)
            } throws RuntimeException("db down")
            assertNull(service.getRecordingSchedule())
        }

    @Test
    fun `getWindow parses raw value regardless of enabled flag`() =
        runTest {
            stub(enabled = null, window = "01:00-05:00")
            assertEquals(ScheduleWindow.parse("01:00-05:00"), service.getWindow())
        }

    @Test
    fun `getZone returns null for corrupt value`() =
        runTest {
            stub(enabled = null, zone = "wat")
            assertNull(service.getZone())
        }

    @Test
    fun `setters write the dedicated keys`() =
        runTest {
            coEvery { settings.setBoolean(any(), any(), any()) } returns Unit
            coEvery { settings.setString(any(), any(), any()) } returns Unit

            service.setEnabled(true, "owner")
            service.setWindow(ScheduleWindow.ofHours(0, 7), "owner")
            service.setZone(ZoneId.of("Europe/Moscow"), "owner")

            coVerify(exactly = 1) {
                settings.setBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_SCHEDULE_ENABLED, true, "owner")
            }
            coVerify(exactly = 1) {
                settings.setString(AppSettingKeys.NOTIFICATIONS_RECORDING_SCHEDULE_WINDOW, "00:00-07:00", "owner")
            }
            coVerify(exactly = 1) {
                settings.setString(AppSettingKeys.NOTIFICATIONS_RECORDING_SCHEDULE_ZONE, "Europe/Moscow", "owner")
            }
        }
}
```

`git add` the test file.

- [ ] **Step 2: Run tests to verify they fail**

Via `build-runner` agent: `./gradlew :frigate-analyzer-service:test --tests "ru.zinin.frigate.analyzer.service.impl.NotificationScheduleServiceImplTest"`
Expected: COMPILATION FAILURE — `NotificationScheduleServiceImpl` and new keys unresolved.

- [ ] **Step 3: Add the keys**

`AppSettingKeys.kt` becomes:

```kotlin
package ru.zinin.frigate.analyzer.service

object AppSettingKeys {
    const val NOTIFICATIONS_RECORDING_GLOBAL_ENABLED = "notifications.recording.global_enabled"
    const val NOTIFICATIONS_SIGNAL_GLOBAL_ENABLED = "notifications.signal.global_enabled"
    const val NOTIFICATIONS_RECORDING_SCHEDULE_ENABLED = "notifications.recording.schedule.enabled"
    const val NOTIFICATIONS_RECORDING_SCHEDULE_WINDOW = "notifications.recording.schedule.window"
    const val NOTIFICATIONS_RECORDING_SCHEDULE_ZONE = "notifications.recording.schedule.zone"
}
```

- [ ] **Step 4: Create the interface**

`modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/NotificationScheduleService.kt`:

```kotlin
package ru.zinin.frigate.analyzer.service

import ru.zinin.frigate.analyzer.model.dto.NotificationSchedule
import ru.zinin.frigate.analyzer.model.dto.ScheduleWindow
import java.time.ZoneId

interface NotificationScheduleService {
    /**
     * Effective schedule for delivery decisions.
     * null — schedule disabled, not configured, or stored data is unusable (fail-open).
     * NEVER throws: transient/corrupt settings must not suppress notifications; the safe
     * failure direction here is an extra notification, never a lost one.
     */
    suspend fun getRecordingSchedule(): NotificationSchedule?

    /**
     * Raw enabled flag for the settings dialog (false when absent or invalid).
     * NOT fail-open: settings read failures propagate — only [getRecordingSchedule] never throws.
     */
    suspend fun isEnabled(): Boolean

    /**
     * Configured window regardless of the enabled flag; null when absent or corrupt.
     * NOT fail-open: settings read failures propagate — only [getRecordingSchedule] never throws.
     */
    suspend fun getWindow(): ScheduleWindow?

    /**
     * Configured zone regardless of the enabled flag; null when absent or corrupt.
     * NOT fail-open: settings read failures propagate — only [getRecordingSchedule] never throws.
     */
    suspend fun getZone(): ZoneId?

    suspend fun setEnabled(
        value: Boolean,
        updatedBy: String?,
    )

    suspend fun setWindow(
        window: ScheduleWindow,
        updatedBy: String?,
    )

    suspend fun setZone(
        zone: ZoneId,
        updatedBy: String?,
    )
}
```

- [ ] **Step 5: Create the implementation**

`modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/NotificationScheduleServiceImpl.kt`:

```kotlin
package ru.zinin.frigate.analyzer.service.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import org.springframework.stereotype.Service
import ru.zinin.frigate.analyzer.model.dto.NotificationSchedule
import ru.zinin.frigate.analyzer.model.dto.ScheduleWindow
import ru.zinin.frigate.analyzer.service.AppSettingKeys
import ru.zinin.frigate.analyzer.service.AppSettingsService
import ru.zinin.frigate.analyzer.service.NotificationScheduleService
import java.time.DateTimeException
import java.time.ZoneId

private val logger = KotlinLogging.logger {}

@Service
class NotificationScheduleServiceImpl(
    private val settings: AppSettingsService,
) : NotificationScheduleService {
    override suspend fun getRecordingSchedule(): NotificationSchedule? =
        try {
            if (!isEnabled()) {
                null
            } else {
                val window = getWindow()
                val zone = getZone()
                if (window == null || zone == null) {
                    logger.warn {
                        "Notification schedule enabled but misconfigured (window=$window, zone=$zone); " +
                            "treating as disabled (fail-open)"
                    }
                    null
                } else {
                    NotificationSchedule(window, zone)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to read notification schedule; treating as disabled (fail-open)" }
            null
        }

    override suspend fun isEnabled(): Boolean =
        settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_SCHEDULE_ENABLED, default = false)

    override suspend fun getWindow(): ScheduleWindow? {
        val raw = settings.getString(AppSettingKeys.NOTIFICATIONS_RECORDING_SCHEDULE_WINDOW) ?: return null
        val window = ScheduleWindow.parse(raw)
        if (window == null) {
            logger.warn { "Invalid stored schedule window '$raw'" }
        }
        return window
    }

    override suspend fun getZone(): ZoneId? {
        val raw = settings.getString(AppSettingKeys.NOTIFICATIONS_RECORDING_SCHEDULE_ZONE) ?: return null
        return try {
            ZoneId.of(raw)
        } catch (e: DateTimeException) {
            logger.warn { "Invalid stored schedule zone '$raw'" }
            null
        }
    }

    override suspend fun setEnabled(
        value: Boolean,
        updatedBy: String?,
    ) {
        settings.setBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_SCHEDULE_ENABLED, value, updatedBy)
    }

    override suspend fun setWindow(
        window: ScheduleWindow,
        updatedBy: String?,
    ) {
        settings.setString(AppSettingKeys.NOTIFICATIONS_RECORDING_SCHEDULE_WINDOW, window.storageFormat(), updatedBy)
    }

    override suspend fun setZone(
        zone: ZoneId,
        updatedBy: String?,
    ) {
        settings.setString(AppSettingKeys.NOTIFICATIONS_RECORDING_SCHEDULE_ZONE, zone.id, updatedBy)
    }
}
```

`git add` all three main files.

- [ ] **Step 6: Run tests to verify they pass**

Via `build-runner` agent: `./gradlew :frigate-analyzer-service:test --tests "ru.zinin.frigate.analyzer.service.impl.NotificationScheduleServiceImplTest"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git commit -m "feat(service): notification schedule storage service"
```

---

### Task 4: `OUT_OF_SCHEDULE` suppression in the decision service

**Files:**
- Modify: `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/NotificationDecision.kt`
- Modify: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/NotificationDecisionService.kt` (KDoc)
- Modify: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/NotificationDecisionServiceImpl.kt`
- Test: `modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/impl/NotificationDecisionServiceImplTest.kt`

**Interfaces:**
- Consumes: `NotificationScheduleService.getRecordingSchedule()` (Task 3), `NotificationSchedule.contains(Instant)` (Task 1).
- Produces: new enum value `NotificationDecisionReason.OUT_OF_SCHEDULE`; `NotificationDecisionServiceImpl` constructor becomes `(tracker: ObjectTrackerService, settings: AppSettingsService, scheduleService: NotificationScheduleService)`. `evaluate()` signature unchanged.

- [ ] **Step 1: Update existing test fixture and write the failing tests**

In `NotificationDecisionServiceImplTest`:

Add imports:

```kotlin
import ru.zinin.frigate.analyzer.model.dto.NotificationSchedule
import ru.zinin.frigate.analyzer.model.dto.ScheduleWindow
import ru.zinin.frigate.analyzer.service.NotificationScheduleService
import java.time.ZoneId
```

Replace the service construction lines:

```kotlin
    private val tracker = mockk<ObjectTrackerService>()
    private val settings = mockk<AppSettingsService>()
    private val scheduleService =
        mockk<NotificationScheduleService> {
            coEvery { getRecordingSchedule() } returns null
        }
    private val service = NotificationDecisionServiceImpl(tracker, settings, scheduleService)
```

(The `returns null` default keeps every pre-existing test behaviorally identical: no schedule → no new branch taken.)

Append new tests (fixture `recording.recordTimestamp` is `2026-04-27T12:00:00Z`; `nightUtc` = window `00:00-07:00` in UTC — 12:00Z is outside; `dayUtc` = window `10:00-14:00` in UTC — 12:00Z is inside; reuse the existing `det()` helper and `DetectionDelta` construction style already present in this file for NEW_OBJECTS/ALL_REPEATED tests — copy the exact `DetectionDelta(...)` argument list used by the existing `new tracks → notify` test):

```kotlin
    private val nightUtc = NotificationSchedule(ScheduleWindow.parse("00:00-07:00")!!, ZoneId.of("UTC"))
    private val dayUtc = NotificationSchedule(ScheduleWindow.parse("10:00-14:00")!!, ZoneId.of("UTC"))

    @Test
    fun `recording outside schedule window is suppressed with OUT_OF_SCHEDULE`() =
        runTest {
            coEvery { scheduleService.getRecordingSchedule() } returns nightUtc
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { tracker.evaluate(recording, any()) } returns
                DetectionDelta(1, 0, 0, listOf("car"))

            val decision = service.evaluate(recording, listOf(det()))

            assertFalse(decision.shouldNotify)
            assertEquals(NotificationDecisionReason.OUT_OF_SCHEDULE, decision.reason)
        }

    @Test
    fun `recording inside schedule window notifies as usual`() =
        runTest {
            coEvery { scheduleService.getRecordingSchedule() } returns dayUtc
            coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { tracker.evaluate(recording, any()) } returns
                DetectionDelta(1, 0, 0, listOf("car"))

            val decision = service.evaluate(recording, listOf(det()))

            assertTrue(decision.shouldNotify)
            assertEquals(NotificationDecisionReason.NEW_OBJECTS, decision.reason)
        }

    @Test
    fun `GLOBAL_OFF wins over OUT_OF_SCHEDULE`() =
        runTest {
            coEvery { scheduleService.getRecordingSchedule() } returns nightUtc
            coEvery { tracker.evaluate(recording, any()) } returns
                DetectionDelta(1, 0, 0, listOf("car"))

            val decision = service.evaluate(recording, listOf(det()), globalEnabled = false)

            assertFalse(decision.shouldNotify)
            assertEquals(NotificationDecisionReason.GLOBAL_OFF, decision.reason)
        }

    @Test
    fun `tracker error honors schedule (outside window means no notify)`() =
        runTest {
            coEvery { scheduleService.getRecordingSchedule() } returns nightUtc
            coEvery { tracker.evaluate(recording, any()) } throws RuntimeException("boom")

            val decision = service.evaluate(recording, listOf(det()), globalEnabled = true)

            assertFalse(decision.shouldNotify)
            assertEquals(NotificationDecisionReason.TRACKER_ERROR, decision.reason)
        }

    @Test
    fun `tracker error inside window stays fail-open`() =
        runTest {
            coEvery { scheduleService.getRecordingSchedule() } returns dayUtc
            coEvery { tracker.evaluate(recording, any()) } throws RuntimeException("boom")

            val decision = service.evaluate(recording, listOf(det()), globalEnabled = true)

            assertTrue(decision.shouldNotify)
            assertEquals(NotificationDecisionReason.TRACKER_ERROR, decision.reason)
        }
```

**Note for the implementer:** the `DetectionDelta(1, 0, 0, listOf("car"))` literal was verified against this file's existing "new objects → notify" test (`NotificationDecisionServiceImplTest.kt:81`, checked 2026-07-18); if the file has drifted, copy the literal from that test instead.

`git add` the test file.

- [ ] **Step 2: Run tests to verify they fail**

Via `build-runner` agent: `./gradlew :frigate-analyzer-service:test --tests "ru.zinin.frigate.analyzer.service.impl.NotificationDecisionServiceImplTest"`
Expected: COMPILATION FAILURE — constructor arity and `OUT_OF_SCHEDULE` unresolved.

- [ ] **Step 3: Add the enum value**

In `NotificationDecision.kt`, extend the enum (keep existing entries and KDoc untouched):

```kotlin
enum class NotificationDecisionReason {
    NEW_OBJECTS,
    ALL_REPEATED,
    NO_DETECTIONS,

    /** Detections were present but all were filtered out by `confidenceFloor` before tracker. */
    NO_VALID_DETECTIONS,
    GLOBAL_OFF,

    /** Recording's recordTimestamp falls outside the configured global notification window. */
    OUT_OF_SCHEDULE,
    TRACKER_ERROR,
}
```

- [ ] **Step 4: Implement the branch**

In `NotificationDecisionServiceImpl`:

Constructor:

```kotlin
@Service
class NotificationDecisionServiceImpl(
    private val tracker: ObjectTrackerService,
    private val settings: AppSettingsService,
    private val scheduleService: NotificationScheduleService,
) : NotificationDecisionService {
```

(add `import ru.zinin.frigate.analyzer.service.NotificationScheduleService`)

In `evaluate()`, after `val resolvedGlobalEnabled = ...`:

```kotlin
        // Never throws: fail-open null on unreadable/corrupt settings (see NotificationScheduleService).
        // Deliberate asymmetry with the global flag: flag read failures propagate (recording stays
        // retryable), schedule read failures yield an EXTRA notification, never a lost one.
        val schedule = scheduleService.getRecordingSchedule()
        val scheduleAllows = schedule == null || schedule.contains(recording.recordTimestamp)
```

Insert a new `when` branch between `!resolvedGlobalEnabled` and `delta.newTracksCount > 0`:

```kotlin
                !scheduleAllows -> {
                    logger.debug { "Decision: suppress (out_of_schedule): cam=${recording.camId} recording=${recording.id}" }
                    NotificationDecision(false, NotificationDecisionReason.OUT_OF_SCHEDULE, delta)
                }
```

Replace the catch-block decision so the tracker-error path honors both gates:

```kotlin
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val shouldNotify = resolvedGlobalEnabled && scheduleAllows
            logger.warn(e) {
                "Tracker failure for recording=${recording.id} cam=${recording.camId}; " +
                    "globalEnabled=$resolvedGlobalEnabled, scheduleAllows=$scheduleAllows, shouldNotify=$shouldNotify"
            }
            NotificationDecision(shouldNotify, NotificationDecisionReason.TRACKER_ERROR)
        }
```

- [ ] **Step 5: Update the interface KDoc**

In `NotificationDecisionService.kt`, in the `Order:` list of the `evaluate` KDoc, replace item 5 with:

```
     *   5. compose decision: GLOBAL_OFF if !globalEnabled, OUT_OF_SCHEDULE if the recording's
     *      recordTimestamp is outside the configured notification window (schedule read is
     *      fail-open and never throws), otherwise NEW_OBJECTS / ALL_REPEATED.
```

`git add` the three modified main files.

- [ ] **Step 6: Run the module tests**

Via `build-runner` agent: `./gradlew :frigate-analyzer-service:test`
Expected: PASS (all service tests, including untouched ones).

- [ ] **Step 7: Commit**

```bash
git commit -m "feat(service): suppress detections outside notification schedule"
```

---

### Task 5: `NotificationsViewState` schedule fields + `NotificationsViewStateFactory`

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/dto/NotificationsViewState.kt`
- Create: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/notifications/NotificationsViewStateFactory.kt`
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/notifications/NotificationsCommandHandler.kt`
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBot.kt`
- Test: create `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/notifications/NotificationsViewStateFactoryTest.kt`; modify `NotificationsCommandHandlerTest.kt`

**Interfaces:**
- Consumes: `NotificationScheduleService.isEnabled/getWindow/getZone` (Task 3), `ScheduleWindow.displayFormat()` (Task 1).
- Produces (used by Tasks 6, 9):
  - `NotificationsViewState` gains `scheduleEnabled: Boolean? = null`, `scheduleWindow: String? = null`, `scheduleZone: String? = null` (defaults keep non-owner construction sites and Task-6-pending renderer tests compiling).
  - `NotificationsViewStateFactory.build(user: TelegramUserDto, isOwner: Boolean): NotificationsViewState` — the ONLY place that assembles the state; the command handler and the bot both call it.

- [ ] **Step 1: Extend the DTO**

In `NotificationsViewState.kt` add after `signalGlobalEnabled`:

```kotlin
    /** Schedule enabled flag. Populated for OWNER; `null` for non-OWNER. */
    val scheduleEnabled: Boolean? = null,
    /** Configured window in display form ("00:00–07:00"); `null` when not configured or non-OWNER. */
    val scheduleWindow: String? = null,
    /** Configured zone id ("Europe/Moscow"); `null` when not configured or non-OWNER. */
    val scheduleZone: String? = null,
```

- [ ] **Step 2: Write the failing factory test**

Create `NotificationsViewStateFactoryTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.bot.handler.notifications

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.model.dto.ScheduleWindow
import ru.zinin.frigate.analyzer.service.AppSettingKeys
import ru.zinin.frigate.analyzer.service.AppSettingsService
import ru.zinin.frigate.analyzer.service.NotificationScheduleService
import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto
import ru.zinin.frigate.analyzer.telegram.model.UserStatus
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationsViewStateFactoryTest {
    private val appSettings = mockk<AppSettingsService>()
    private val scheduleService = mockk<NotificationScheduleService>()
    private val factory = NotificationsViewStateFactory(appSettings, scheduleService)

    private fun user(languageCode: String? = "en") =
        TelegramUserDto(
            id = UUID.randomUUID(),
            username = "alice",
            chatId = 100L,
            userId = 1L,
            firstName = null,
            lastName = null,
            status = UserStatus.ACTIVE,
            creationTimestamp = Instant.EPOCH,
            activationTimestamp = Instant.EPOCH,
            languageCode = languageCode,
            notificationsRecordingEnabled = true,
            notificationsSignalEnabled = false,
        )

    @Test
    fun `non-owner state has null globals and null schedule fields, no settings reads`() =
        runTest {
            val state = factory.build(user(), isOwner = false)

            assertFalse(state.isOwner)
            assertTrue(state.recordingUserEnabled)
            assertFalse(state.signalUserEnabled)
            assertNull(state.recordingGlobalEnabled)
            assertNull(state.signalGlobalEnabled)
            assertNull(state.scheduleEnabled)
            assertNull(state.scheduleWindow)
            assertNull(state.scheduleZone)
            assertEquals("en", state.language)
            coVerify(exactly = 0) { appSettings.getBoolean(any(), any()) }
            coVerify(exactly = 0) { scheduleService.isEnabled() }
        }

    @Test
    fun `owner state carries globals and schedule fields`() =
        runTest {
            coEvery { appSettings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
            coEvery { appSettings.getBoolean(AppSettingKeys.NOTIFICATIONS_SIGNAL_GLOBAL_ENABLED, true) } returns false
            coEvery { scheduleService.isEnabled() } returns true
            coEvery { scheduleService.getWindow() } returns ScheduleWindow.ofHours(0, 7)
            coEvery { scheduleService.getZone() } returns ZoneId.of("Europe/Moscow")

            val state = factory.build(user(), isOwner = true)

            assertTrue(state.isOwner)
            assertEquals(true, state.recordingGlobalEnabled)
            assertEquals(false, state.signalGlobalEnabled)
            assertEquals(true, state.scheduleEnabled)
            assertEquals("00:00–07:00", state.scheduleWindow)
            assertEquals("Europe/Moscow", state.scheduleZone)
        }

    @Test
    fun `owner without configured window gets null window and zone`() =
        runTest {
            coEvery { appSettings.getBoolean(any(), any()) } returns true
            coEvery { scheduleService.isEnabled() } returns false
            coEvery { scheduleService.getWindow() } returns null
            coEvery { scheduleService.getZone() } returns null

            val state = factory.build(user(languageCode = null), isOwner = true)

            assertEquals(false, state.scheduleEnabled)
            assertNull(state.scheduleWindow)
            assertNull(state.scheduleZone)
            assertEquals("en", state.language)
        }
}
```

`git add` the test file.

- [ ] **Step 3: Run the factory test to verify it fails**

Via `build-runner` agent: `./gradlew :frigate-analyzer-telegram:test --tests "ru.zinin.frigate.analyzer.telegram.bot.handler.notifications.NotificationsViewStateFactoryTest"`
Expected: COMPILATION FAILURE — `NotificationsViewStateFactory` unresolved.

- [ ] **Step 4: Create the factory**

`NotificationsViewStateFactory.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.bot.handler.notifications

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.service.AppSettingKeys
import ru.zinin.frigate.analyzer.service.AppSettingsService
import ru.zinin.frigate.analyzer.service.NotificationScheduleService
import ru.zinin.frigate.analyzer.telegram.dto.NotificationsViewState
import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto

/**
 * Single assembly point for [NotificationsViewState]: the /notifications command handler,
 * the nfs-callback re-render in FrigateAnalyzerBot, and the schedule flow all build the
 * state here so global/schedule reads stay consistent.
 */
@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class NotificationsViewStateFactory(
    private val appSettings: AppSettingsService,
    private val scheduleService: NotificationScheduleService,
) {
    suspend fun build(
        user: TelegramUserDto,
        isOwner: Boolean,
    ): NotificationsViewState =
        NotificationsViewState(
            isOwner = isOwner,
            recordingUserEnabled = user.notificationsRecordingEnabled,
            signalUserEnabled = user.notificationsSignalEnabled,
            recordingGlobalEnabled =
                if (isOwner) {
                    appSettings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true)
                } else {
                    null
                },
            signalGlobalEnabled =
                if (isOwner) {
                    appSettings.getBoolean(AppSettingKeys.NOTIFICATIONS_SIGNAL_GLOBAL_ENABLED, true)
                } else {
                    null
                },
            scheduleEnabled = if (isOwner) scheduleService.isEnabled() else null,
            scheduleWindow = if (isOwner) scheduleService.getWindow()?.displayFormat() else null,
            scheduleZone = if (isOwner) scheduleService.getZone()?.id else null,
            language = user.languageCode ?: "en",
        )
}
```

`git add` the file.

- [ ] **Step 5: Refactor `NotificationsCommandHandler` to use the factory**

Replace the class body of `NotificationsCommandHandler.kt` (constructor + `handle`) with:

```kotlin
@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class NotificationsCommandHandler(
    private val userService: TelegramUserService,
    private val viewStateFactory: NotificationsViewStateFactory,
    private val renderer: NotificationsMessageRenderer,
) : CommandHandler {
    override val command: String = "notifications"
    override val requiredRole: UserRole = UserRole.USER
    override val order: Int = 7

    override suspend fun BehaviourContext.handle(
        message: CommonMessage<TextContent>,
        user: TelegramUserDto?,
    ) {
        if (user == null) return
        val chatId = message.chat.id
        val isOwner = userService.isOwner(user.username)
        logger.debug { "/notifications opened by chatId=$chatId username=${user.username} isOwner=$isOwner" }

        val state = viewStateFactory.build(user, isOwner)
        val rendered = renderer.render(state)
        sendTextMessage(chatId, rendered.text, replyMarkup = rendered.keyboard)
    }
}
```

Remove now-unused imports (`AppSettingKeys`, `AppSettingsService`, `NotificationsViewState`).

- [ ] **Step 6: Refactor the bot's RERENDER block to use the factory**

In `FrigateAnalyzerBot.kt`:

1. Constructor: add `private val notificationsViewStateFactory: NotificationsViewStateFactory,` after `notificationsMessageRenderer` and REMOVE `private val appSettings: AppSettingsService,` (verify first with a grep that `appSettings` has no other usage in this file than the RERENDER block — expected: exactly the two `getBoolean` calls).
2. Replace the state assembly inside `DispatchOutcome.RERENDER ->` (the `val state = NotificationsViewState(...)` block spanning the `if (owner) appSettings.getBoolean...` reads) with:

```kotlin
                        NotificationsSettingsCallbackHandler.DispatchOutcome.RERENDER -> {
                            val updated = userService.findByChatIdAsDto(cid) ?: current
                            val state = notificationsViewStateFactory.build(updated, owner)
                            val rendered = notificationsMessageRenderer.render(state)
```

(the surrounding `try { bot.editMessageText(...) }` stays untouched).
3. Remove now-unused imports: `AppSettingKeys`, `AppSettingsService`, `NotificationsViewState`; add `NotificationsViewStateFactory` import.

`git add` both files.

- [ ] **Step 7: Update `NotificationsCommandHandlerTest` to construct a real factory**

In `NotificationsCommandHandlerTest.kt`:

1. Add fields and swap the handler construction:

```kotlin
    private val userService = mockk<TelegramUserService>()
    private val appSettings = mockk<AppSettingsService>()
    private val scheduleService = mockk<ru.zinin.frigate.analyzer.service.NotificationScheduleService>()
    private val renderer = mockk<NotificationsMessageRenderer>(relaxed = true)
    private val handler =
        NotificationsCommandHandler(
            userService,
            NotificationsViewStateFactory(appSettings, scheduleService),
            renderer,
        )
```

2. In every test that stubs OWNER (`isOwner(...) returns true`), add schedule stubs right after the `appSettings` stubs:

```kotlin
            coEvery { scheduleService.isEnabled() } returns false
            coEvery { scheduleService.getWindow() } returns null
            coEvery { scheduleService.getZone() } returns null
```

3. In the non-owner test, additionally assert the schedule fields are null:

```kotlin
            assertNull(captured.scheduleEnabled)
            assertNull(captured.scheduleWindow)
            assertNull(captured.scheduleZone)
```

All other assertions (including `appSettings NOT called` for non-owner and the `db down` propagation test) remain valid unchanged.

`git add` the test file.

- [ ] **Step 8: Run telegram module tests**

Via `build-runner` agent: `./gradlew :frigate-analyzer-telegram:test`
Expected: PASS — factory tests, updated command handler tests, and untouched renderer tests (new DTO fields have defaults).

- [ ] **Step 9: Commit**

```bash
git commit -m "refactor(telegram): extract NotificationsViewStateFactory with schedule fields"
```

---

### Task 6: Schedule status line and buttons on the `/notifications` main screen

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/notifications/NotificationsMessageRenderer.kt`
- Modify: `modules/telegram/src/main/resources/messages_en.properties`
- Modify: `modules/telegram/src/main/resources/messages_ru.properties`
- Test: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/notifications/NotificationsMessageRendererTest.kt`

**Interfaces:**
- Consumes: `NotificationsViewState.scheduleEnabled/scheduleWindow/scheduleZone` (Task 5).
- Produces: main-screen keyboard rows emitting callbacks `nfs:g:sched:on`, `nfs:g:sched:off`, `nfs:g:sched:cfg`, `nfs:g:sched:zone` (consumed by Task 8). Owner keyboard grows from 5 to 7 rows.

- [ ] **Step 1: Update the owner row-count test and write failing tests**

In `NotificationsMessageRendererTest.kt`:

1. Change the existing owner test name and expectation:

```kotlin
    @Test
    fun `owner variant has 7 rows (2 user toggles, 2 global toggles, sched toggle, sched config, close)`() {
        val rendered =
            renderer.render(
                NotificationsViewState(
                    isOwner = true,
                    recordingUserEnabled = true,
                    signalUserEnabled = true,
                    recordingGlobalEnabled = true,
                    signalGlobalEnabled = true,
                    scheduleEnabled = false,
                    language = "en",
                ),
            )
        assertEquals(7, rendered.keyboard.keyboard.size)
    }
```

2. Update every other construction with `isOwner = true` in this file — currently exactly two
   besides the row-count one, at lines 128 and 146 (verified 2026-07-18; re-check with a search
   for `isOwner = true` if the file has drifted). Each of them MUST add
   `scheduleEnabled = false,` to the `NotificationsViewState(...)` call — after Step 4 the
   renderer `requireNotNull`s this field for owners and those tests would otherwise fail at
   runtime. Non-owner constructions stay untouched (defaults are correct for them).

3. Add new tests:

```kotlin
    private fun ownerState(
        scheduleEnabled: Boolean,
        scheduleWindow: String? = null,
        scheduleZone: String? = null,
    ) = NotificationsViewState(
        isOwner = true,
        recordingUserEnabled = true,
        signalUserEnabled = true,
        recordingGlobalEnabled = true,
        signalGlobalEnabled = true,
        scheduleEnabled = scheduleEnabled,
        scheduleWindow = scheduleWindow,
        scheduleZone = scheduleZone,
        language = "en",
    )

    @Test
    fun `owner text shows configured schedule window and zone`() {
        val rendered = renderer.render(ownerState(true, "00:00–07:00", "Europe/Moscow"))
        assertTrue(rendered.text.contains("00:00–07:00"), "text=${rendered.text}")
        assertTrue(rendered.text.contains("Europe/Moscow"), "text=${rendered.text}")
    }

    @Test
    fun `owner text shows off with stored window when disabled`() {
        val rendered = renderer.render(ownerState(false, "00:00–07:00", "Europe/Moscow"))
        assertTrue(rendered.text.contains("OFF"), "text=${rendered.text}")
        assertTrue(rendered.text.contains("00:00–07:00"), "text=${rendered.text}")
    }

    @Test
    fun `owner text shows plain off when nothing configured`() {
        val rendered = renderer.render(ownerState(false))
        assertTrue(rendered.text.contains("OFF"), "text=${rendered.text}")
        assertFalse(rendered.text.contains("00:00"), "text=${rendered.text}")
    }

    @Test
    fun `schedule toggle button emits explicit enable callback when disabled`() {
        val rendered = renderer.render(ownerState(false))
        val toggle = rendered.keyboard.keyboard[4][0] as CallbackDataInlineKeyboardButton
        assertEquals("nfs:g:sched:on", toggle.callbackData)
    }

    @Test
    fun `schedule toggle button emits explicit disable callback when enabled`() {
        val rendered = renderer.render(ownerState(true, "00:00–07:00", "Europe/Moscow"))
        val toggle = rendered.keyboard.keyboard[4][0] as CallbackDataInlineKeyboardButton
        assertEquals("nfs:g:sched:off", toggle.callbackData)
    }

    @Test
    fun `window and zone buttons sit in one row with cfg and zone callbacks`() {
        val rendered = renderer.render(ownerState(false))
        val row = rendered.keyboard.keyboard[5]
        assertEquals(2, row.size)
        assertEquals("nfs:g:sched:cfg", (row[0] as CallbackDataInlineKeyboardButton).callbackData)
        assertEquals("nfs:g:sched:zone", (row[1] as CallbackDataInlineKeyboardButton).callbackData)
    }

    @Test
    fun `non-owner keyboard has no schedule rows and text has no schedule line`() {
        val rendered =
            renderer.render(
                NotificationsViewState(
                    isOwner = false,
                    recordingUserEnabled = true,
                    signalUserEnabled = true,
                    recordingGlobalEnabled = null,
                    signalGlobalEnabled = null,
                    language = "en",
                ),
            )
        assertEquals(3, rendered.keyboard.keyboard.size)
        assertFalse(rendered.text.contains("schedule", ignoreCase = true), "text=${rendered.text}")
    }
```

Add missing imports if absent: `kotlin.test.assertFalse`.

`git add` the test file.

- [ ] **Step 2: Run to verify failures**

Via `build-runner` agent: `./gradlew :frigate-analyzer-telegram:test --tests "ru.zinin.frigate.analyzer.telegram.bot.handler.notifications.NotificationsMessageRendererTest"`
Expected: FAIL — old owner test asserts 5 rows / missing i18n keys / missing rows.

- [ ] **Step 3: Add i18n keys**

Append to `messages_en.properties` (after line `notifications.settings.line.owner.format=...`):

```properties
notifications.settings.sched.line.on.format=⏰ Detection schedule: {0} ({1})
notifications.settings.sched.line.off.format=⏰ Detection schedule: {0}
notifications.settings.sched.line.off.configured.format=⏰ Detection schedule: {0} ({1}, {2})
notifications.settings.button.sched.enable=⏰ Enable schedule
notifications.settings.button.sched.disable=⏰ Disable schedule
notifications.settings.button.sched.window=🕐 Window
notifications.settings.button.sched.zone=🌐 Timezone
```

(`{0}` in both off-formats is the localized OFF label — reuse the existing
`notifications.settings.state.off` key so the OFF/ВЫКЛ translation lives in one place.)

Append to `messages_ru.properties` (same location):

```properties
notifications.settings.sched.line.on.format=⏰ Расписание детектов: {0} ({1})
notifications.settings.sched.line.off.format=⏰ Расписание детектов: {0}
notifications.settings.sched.line.off.configured.format=⏰ Расписание детектов: {0} ({1}, {2})
notifications.settings.button.sched.enable=⏰ Включить расписание
notifications.settings.button.sched.disable=⏰ Выключить расписание
notifications.settings.button.sched.window=🕐 Окно
notifications.settings.button.sched.zone=🌐 Часовой пояс
```

- [ ] **Step 4: Implement renderer changes**

In `NotificationsMessageRenderer.kt`:

1. In `render()`, extend the owner precondition block:

```kotlin
        if (state.isOwner) {
            requireNotNull(state.recordingGlobalEnabled) {
                "OWNER NotificationsViewState.recordingGlobalEnabled must not be null"
            }
            requireNotNull(state.signalGlobalEnabled) {
                "OWNER NotificationsViewState.signalGlobalEnabled must not be null"
            }
            requireNotNull(state.scheduleEnabled) {
                "OWNER NotificationsViewState.scheduleEnabled must not be null"
            }
        }
```

2. In `renderText()`, after `appendLine(signalLine)` add the schedule line (inside the same `buildString`):

```kotlin
            if (state.isOwner) {
                val offLabel = msg.get("notifications.settings.state.off", lang)
                val scheduleLine =
                    when {
                        state.scheduleEnabled == true && state.scheduleWindow != null ->
                            msg.get(
                                "notifications.settings.sched.line.on.format",
                                lang,
                                state.scheduleWindow,
                                state.scheduleZone ?: "?",
                            )
                        state.scheduleWindow != null ->
                            // Disabled but configured: show what "Enable schedule" will activate.
                            msg.get(
                                "notifications.settings.sched.line.off.configured.format",
                                lang,
                                offLabel,
                                state.scheduleWindow,
                                state.scheduleZone ?: "?",
                            )
                        else -> msg.get("notifications.settings.sched.line.off.format", lang, offLabel)
                    }
                appendLine(scheduleLine)
            }
```

3. In `renderKeyboard()`, inside the `if (state.isOwner) { ... }` block, after the two existing global rows add:

```kotlin
                        val schedEnabled = state.scheduleEnabled!!
                        row {
                            +CallbackDataInlineKeyboardButton(
                                msg.get(
                                    if (schedEnabled) {
                                        "notifications.settings.button.sched.disable"
                                    } else {
                                        "notifications.settings.button.sched.enable"
                                    },
                                    lang,
                                ),
                                if (schedEnabled) "nfs:g:sched:off" else "nfs:g:sched:on",
                            )
                        }
                        row {
                            +CallbackDataInlineKeyboardButton(
                                msg.get("notifications.settings.button.sched.window", lang),
                                "nfs:g:sched:cfg",
                            )
                            +CallbackDataInlineKeyboardButton(
                                msg.get("notifications.settings.button.sched.zone", lang),
                                "nfs:g:sched:zone",
                            )
                        }
```

`git add` all three modified files.

- [ ] **Step 5: Run tests to verify they pass**

Via `build-runner` agent: `./gradlew :frigate-analyzer-telegram:test --tests "ru.zinin.frigate.analyzer.telegram.bot.handler.notifications.NotificationsMessageRendererTest" --tests "ru.zinin.frigate.analyzer.telegram.i18n.MessageKeyParityTest"`
Expected: PASS (parity test confirms en/ru sync).

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(telegram): schedule status and buttons in /notifications"
```

---

### Task 7: `ScheduleKeyboardRenderer` — hour pickers and zone screen

**Files:**
- Create: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/notifications/ScheduleKeyboardRenderer.kt`
- Create: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/i18n/TimezonePresets.kt`
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/TimezoneCommandHandler.kt`
- Modify: `modules/telegram/src/main/resources/messages_en.properties`
- Modify: `modules/telegram/src/main/resources/messages_ru.properties`
- Test: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/notifications/ScheduleKeyboardRendererTest.kt`

**Interfaces:**
- Consumes: `RenderedNotifications` (defined in `NotificationsMessageRenderer.kt`), `MessageResolver`.
- Produces (used by Task 9):
  - `startPicker(lang: String): RenderedNotifications` — 24-hour grid, buttons `nfs:g:sched:s:<0..23>`
  - `endPicker(startHour: Int, showEqualWarning: Boolean, lang: String): RenderedNotifications` — buttons `nfs:g:sched:e:<startHour>:<0..23>`
  - `zoneScreen(currentZone: String?, lang: String): RenderedNotifications` — preset buttons `nfs:g:sched:z:<olson>`, manual `nfs:g:sched:zman`, back `nfs:g:sched:home`

- [ ] **Step 1: Write the failing tests**

Create `ScheduleKeyboardRendererTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.bot.handler.notifications

import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import org.junit.jupiter.api.Test
import org.springframework.context.support.ReloadableResourceBundleMessageSource
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScheduleKeyboardRendererTest {
    private val msg =
        MessageResolver(
            ReloadableResourceBundleMessageSource().apply {
                setBasename("classpath:messages")
                setDefaultEncoding("UTF-8")
                setFallbackToSystemLocale(false)
                setDefaultLocale(Locale.forLanguageTag("en"))
            },
        )
    private val renderer = ScheduleKeyboardRenderer(msg)

    private fun data(button: Any?): String = (button as CallbackDataInlineKeyboardButton).callbackData

    @Test
    fun `start picker has 4 hour rows of 6 plus back row`() {
        val rendered = renderer.startPicker("en")
        assertEquals(5, rendered.keyboard.keyboard.size)
        assertEquals(6, rendered.keyboard.keyboard[0].size)
        assertEquals("nfs:g:sched:s:0", data(rendered.keyboard.keyboard[0][0]))
        assertEquals("nfs:g:sched:s:23", data(rendered.keyboard.keyboard[3][5]))
        assertEquals("nfs:g:sched:home", data(rendered.keyboard.keyboard[4][0]))
    }

    @Test
    fun `start picker labels are zero-padded hours`() {
        val rendered = renderer.startPicker("en")
        assertEquals("00", (rendered.keyboard.keyboard[0][0] as CallbackDataInlineKeyboardButton).text)
        assertEquals("23", (rendered.keyboard.keyboard[3][5] as CallbackDataInlineKeyboardButton).text)
    }

    @Test
    fun `end picker embeds start hour in every callback`() {
        val rendered = renderer.endPicker(startHour = 23, showEqualWarning = false, lang = "en")
        assertEquals("nfs:g:sched:e:23:0", data(rendered.keyboard.keyboard[0][0]))
        assertEquals("nfs:g:sched:e:23:7", data(rendered.keyboard.keyboard[1][1]))
        assertTrue(rendered.text.contains("23:00"), "text=${rendered.text}")
    }

    @Test
    fun `end picker without warning has no warning line`() {
        val rendered = renderer.endPicker(startHour = 5, showEqualWarning = false, lang = "en")
        assertFalse(rendered.text.contains("must differ", ignoreCase = true), "text=${rendered.text}")
    }

    @Test
    fun `end picker with warning shows warning line`() {
        val rendered = renderer.endPicker(startHour = 5, showEqualWarning = true, lang = "en")
        assertTrue(rendered.text.contains("must differ", ignoreCase = true), "text=${rendered.text}")
    }

    @Test
    fun `zone screen offers presets, manual input and back`() {
        val rendered = renderer.zoneScreen(currentZone = "Europe/Moscow", lang = "en")
        assertTrue(rendered.text.contains("Europe/Moscow"), "text=${rendered.text}")
        // 8 presets in 4 rows of 2, then manual+back row
        assertEquals(5, rendered.keyboard.keyboard.size)
        assertEquals("nfs:g:sched:z:Europe/Kaliningrad", data(rendered.keyboard.keyboard[0][0]))
        assertEquals("nfs:g:sched:z:Asia/Vladivostok", data(rendered.keyboard.keyboard[3][1]))
        assertEquals("nfs:g:sched:zman", data(rendered.keyboard.keyboard[4][0]))
        assertEquals("nfs:g:sched:home", data(rendered.keyboard.keyboard[4][1]))
    }

    @Test
    fun `zone screen shows unset marker when zone missing`() {
        val rendered = renderer.zoneScreen(currentZone = null, lang = "en")
        assertTrue(rendered.text.contains("not set"), "text=${rendered.text}")
    }
}
```

`git add` the test file.

- [ ] **Step 2: Run to verify failure**

Via `build-runner` agent: `./gradlew :frigate-analyzer-telegram:test --tests "ru.zinin.frigate.analyzer.telegram.bot.handler.notifications.ScheduleKeyboardRendererTest"`
Expected: COMPILATION FAILURE — `ScheduleKeyboardRenderer` unresolved.

- [ ] **Step 3: Add i18n keys**

`messages_en.properties`:

```properties
notifications.sched.picker.start.title=🕐 Detection window — choose the START hour
notifications.sched.picker.end.title=🕐 Window starts at {0} — choose the END hour
notifications.sched.picker.end.invalid=⚠️ The end hour must differ from the start hour
notifications.sched.picker.back=‹ Back
notifications.sched.zone.title=🌐 Schedule timezone: {0}\nChoose a new one:
notifications.sched.zone.unset=not set
notifications.sched.zone.manual=Enter manually
```

`messages_ru.properties`:

```properties
notifications.sched.picker.start.title=🕐 Окно детектов — выберите час НАЧАЛА
notifications.sched.picker.end.title=🕐 Начало окна {0} — выберите час КОНЦА
notifications.sched.picker.end.invalid=⚠️ Час конца должен отличаться от часа начала
notifications.sched.picker.back=‹ Назад
notifications.sched.zone.title=🌐 Часовой пояс расписания: {0}\nВыберите новый:
notifications.sched.zone.unset=не задан
notifications.sched.zone.manual=Ввести вручную
```

- [ ] **Step 4: Implement the renderer**

`ScheduleKeyboardRenderer.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.bot.handler.notifications

import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.utils.matrix
import dev.inmo.tgbotapi.utils.row
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver

/**
 * Screens of the /notifications schedule sub-dialog: hour pickers and the timezone
 * chooser. Stateless by design — the chosen start hour travels inside the end-picker
 * callback data, so screens survive restarts and stale keyboards.
 */
@Component
class ScheduleKeyboardRenderer(
    private val msg: MessageResolver,
) {
    fun startPicker(lang: String): RenderedNotifications =
        RenderedNotifications(
            text = msg.get("notifications.sched.picker.start.title", lang),
            keyboard = hourGrid(lang) { hour -> "nfs:g:sched:s:$hour" },
        )

    fun endPicker(
        startHour: Int,
        showEqualWarning: Boolean,
        lang: String,
    ): RenderedNotifications {
        val title = msg.get("notifications.sched.picker.end.title", lang, formatHour(startHour))
        val text =
            if (showEqualWarning) {
                title + "\n\n" + msg.get("notifications.sched.picker.end.invalid", lang)
            } else {
                title
            }
        return RenderedNotifications(text, hourGrid(lang) { hour -> "nfs:g:sched:e:$startHour:$hour" })
    }

    fun zoneScreen(
        currentZone: String?,
        lang: String,
    ): RenderedNotifications =
        RenderedNotifications(
            text =
                msg.get(
                    "notifications.sched.zone.title",
                    lang,
                    currentZone ?: msg.get("notifications.sched.zone.unset", lang),
                ),
            keyboard =
                InlineKeyboardMarkup(
                    keyboard =
                        matrix {
                            ZONE_PRESETS.chunked(2).forEach { pair ->
                                row {
                                    pair.forEach { (labelKey, olson) ->
                                        +CallbackDataInlineKeyboardButton(msg.get(labelKey, lang), "nfs:g:sched:z:$olson")
                                    }
                                }
                            }
                            row {
                                +CallbackDataInlineKeyboardButton(
                                    msg.get("notifications.sched.zone.manual", lang),
                                    "nfs:g:sched:zman",
                                )
                                +CallbackDataInlineKeyboardButton(
                                    msg.get("notifications.sched.picker.back", lang),
                                    "nfs:g:sched:home",
                                )
                            }
                        },
                ),
        )

    private fun hourGrid(
        lang: String,
        callbackFor: (Int) -> String,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            keyboard =
                matrix {
                    (0..23).chunked(6).forEach { hours ->
                        row {
                            hours.forEach { hour ->
                                // Picker hours are always 00–23 zero-padded, locale-independent.
                                +CallbackDataInlineKeyboardButton("%02d".format(hour), callbackFor(hour))
                            }
                        }
                    }
                    row {
                        +CallbackDataInlineKeyboardButton(msg.get("notifications.sched.picker.back", lang), "nfs:g:sched:home")
                    }
                },
        )

    private fun formatHour(hour: Int): String = "%02d:00".format(hour)
}
```

In `zoneScreen`, `ZONE_PRESETS.chunked(2)` becomes `TimezonePresets.CITIES.chunked(2)` (add
import `ru.zinin.frigate.analyzer.telegram.i18n.TimezonePresets`).

- [ ] **Step 4a: Extract the shared preset list and reuse it in `/timezone`**

Create `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/i18n/TimezonePresets.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.i18n

/** City presets shared by /timezone and the schedule zone screen (label message key → IANA id). */
object TimezonePresets {
    val CITIES =
        listOf(
            "command.timezone.zone.kaliningrad" to "Europe/Kaliningrad",
            "command.timezone.zone.moscow" to "Europe/Moscow",
            "command.timezone.zone.yekaterinburg" to "Asia/Yekaterinburg",
            "command.timezone.zone.omsk" to "Asia/Omsk",
            "command.timezone.zone.krasnoyarsk" to "Asia/Krasnoyarsk",
            "command.timezone.zone.irkutsk" to "Asia/Irkutsk",
            "command.timezone.zone.yakutsk" to "Asia/Yakutsk",
            "command.timezone.zone.vladivostok" to "Asia/Vladivostok",
        )
}
```

In `TimezoneCommandHandler` replace the eight hardcoded preset buttons (currently four
inline rows of two `CallbackDataInlineKeyboardButton(msg.get("command.timezone.zone.…"), "tz:<olson>")`)
with a loop over `TimezonePresets.CITIES.chunked(2)` producing `"tz:$olson"` callbacks — the
resulting 4×2 layout, labels and callback data are IDENTICAL to today (the manual-input row
stays as is), so existing behavior and any existing tests are unchanged. The two preset lists
can no longer drift apart.

`git add` all modified/created files.

- [ ] **Step 5: Run tests to verify they pass**

Via `build-runner` agent: `./gradlew :frigate-analyzer-telegram:test --tests "ru.zinin.frigate.analyzer.telegram.bot.handler.notifications.ScheduleKeyboardRendererTest" --tests "ru.zinin.frigate.analyzer.telegram.i18n.MessageKeyParityTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(telegram): schedule hour picker and zone screens"
```

---

### Task 8: `ScheduleCallbackHandler` — pure dispatch for `nfs:g:sched:*`

**Files:**
- Create: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/notifications/ScheduleCallbackHandler.kt`
- Test: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/notifications/ScheduleCallbackHandlerTest.kt`

**Interfaces:**
- Consumes: `NotificationScheduleService` (Task 3), `TelegramUserService.getUserZone(chatId: Long): ZoneId`, `ScheduleWindow.ofHours` (Task 1).
- Produces (used by Task 9):
  - `ScheduleCallbackHandler.PREFIX = "nfs:g:sched:"`
  - `suspend fun dispatch(data: String, isOwner: Boolean, ownerChatId: Long, updatedBy: String?): Outcome`
  - `sealed interface Outcome`: `RenderMain`, `RenderStartPicker`, `RenderEndPicker(startHour: Int, rejectedEqualEnd: Boolean)`, `RenderZoneScreen`, `AwaitManualZone`, `Unauthorized`, `Ignore`

- [ ] **Step 1: Write the failing tests**

Create `ScheduleCallbackHandlerTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.bot.handler.notifications

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.model.dto.ScheduleWindow
import ru.zinin.frigate.analyzer.service.NotificationScheduleService
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import java.time.ZoneId
import kotlin.test.assertEquals

class ScheduleCallbackHandlerTest {
    private val scheduleService = mockk<NotificationScheduleService>(relaxed = true)
    private val userService = mockk<TelegramUserService>(relaxed = true)
    private val handler = ScheduleCallbackHandler(scheduleService, userService)

    private val chatId = 100L

    private suspend fun dispatch(
        data: String,
        isOwner: Boolean = true,
    ) = handler.dispatch(data, isOwner, chatId, "owner")

    @Test
    fun `non-owner is rejected without side effects`() =
        runTest {
            val outcome = dispatch("nfs:g:sched:on", isOwner = false)
            assertEquals(ScheduleCallbackHandler.Outcome.Unauthorized, outcome)
            coVerify(exactly = 0) { scheduleService.setEnabled(any(), any()) }
        }

    @Test
    fun `on with configured window enables and re-renders main`() =
        runTest {
            coEvery { scheduleService.getWindow() } returns ScheduleWindow.ofHours(0, 7)
            val outcome = dispatch("nfs:g:sched:on")
            assertEquals(ScheduleCallbackHandler.Outcome.RenderMain, outcome)
            coVerify(exactly = 1) { scheduleService.setEnabled(true, "owner") }
        }

    @Test
    fun `on with window but missing zone materializes zone before enabling`() =
        runTest {
            coEvery { scheduleService.getWindow() } returns ScheduleWindow.ofHours(0, 7)
            coEvery { scheduleService.getZone() } returns null
            coEvery { userService.getUserZone(chatId) } returns ZoneId.of("Europe/Moscow")

            val outcome = dispatch("nfs:g:sched:on")

            assertEquals(ScheduleCallbackHandler.Outcome.RenderMain, outcome)
            coVerify(exactly = 1) { scheduleService.setZone(ZoneId.of("Europe/Moscow"), "owner") }
            coVerify(exactly = 1) { scheduleService.setEnabled(true, "owner") }
        }

    @Test
    fun `on without window opens the start picker instead of enabling`() =
        runTest {
            coEvery { scheduleService.getWindow() } returns null
            val outcome = dispatch("nfs:g:sched:on")
            assertEquals(ScheduleCallbackHandler.Outcome.RenderStartPicker, outcome)
            coVerify(exactly = 0) { scheduleService.setEnabled(any(), any()) }
        }

    @Test
    fun `off disables and re-renders main`() =
        runTest {
            val outcome = dispatch("nfs:g:sched:off")
            assertEquals(ScheduleCallbackHandler.Outcome.RenderMain, outcome)
            coVerify(exactly = 1) { scheduleService.setEnabled(false, "owner") }
        }

    @Test
    fun `cfg opens start picker`() =
        runTest {
            assertEquals(ScheduleCallbackHandler.Outcome.RenderStartPicker, dispatch("nfs:g:sched:cfg"))
        }

    @Test
    fun `start hour selection opens end picker with the hour`() =
        runTest {
            assertEquals(
                ScheduleCallbackHandler.Outcome.RenderEndPicker(23, rejectedEqualEnd = false),
                dispatch("nfs:g:sched:s:23"),
            )
        }

    @Test
    fun `start hour out of range is ignored`() =
        runTest {
            assertEquals(ScheduleCallbackHandler.Outcome.Ignore, dispatch("nfs:g:sched:s:24"))
            assertEquals(ScheduleCallbackHandler.Outcome.Ignore, dispatch("nfs:g:sched:s:x"))
        }

    @Test
    fun `end equal to start re-renders end picker with warning and writes nothing`() =
        runTest {
            val outcome = dispatch("nfs:g:sched:e:5:5")
            assertEquals(ScheduleCallbackHandler.Outcome.RenderEndPicker(5, rejectedEqualEnd = true), outcome)
            coVerify(exactly = 0) { scheduleService.setWindow(any(), any()) }
            coVerify(exactly = 0) { scheduleService.setEnabled(any(), any()) }
        }

    @Test
    fun `end selection saves window, materializes zone from owner, auto-enables`() =
        runTest {
            coEvery { scheduleService.getZone() } returns null
            coEvery { userService.getUserZone(chatId) } returns ZoneId.of("Europe/Moscow")

            val outcome = dispatch("nfs:g:sched:e:23:7")

            assertEquals(ScheduleCallbackHandler.Outcome.RenderMain, outcome)
            coVerify(exactly = 1) { scheduleService.setWindow(ScheduleWindow.ofHours(23, 7), "owner") }
            coVerify(exactly = 1) { scheduleService.setZone(ZoneId.of("Europe/Moscow"), "owner") }
            coVerify(exactly = 1) { scheduleService.setEnabled(true, "owner") }
        }

    @Test
    fun `end selection keeps existing zone`() =
        runTest {
            coEvery { scheduleService.getZone() } returns ZoneId.of("Asia/Omsk")

            dispatch("nfs:g:sched:e:0:7")

            coVerify(exactly = 0) { scheduleService.setZone(any(), any()) }
            coVerify(exactly = 1) { scheduleService.setWindow(ScheduleWindow.ofHours(0, 7), "owner") }
        }

    @Test
    fun `end hour out of range is ignored`() =
        runTest {
            assertEquals(ScheduleCallbackHandler.Outcome.Ignore, dispatch("nfs:g:sched:e:0:24"))
            assertEquals(ScheduleCallbackHandler.Outcome.Ignore, dispatch("nfs:g:sched:e:x:7"))
        }

    @Test
    fun `zone opens zone screen`() =
        runTest {
            assertEquals(ScheduleCallbackHandler.Outcome.RenderZoneScreen, dispatch("nfs:g:sched:zone"))
        }

    @Test
    fun `zone preset saves and re-renders main`() =
        runTest {
            val outcome = dispatch("nfs:g:sched:z:Asia/Irkutsk")
            assertEquals(ScheduleCallbackHandler.Outcome.RenderMain, outcome)
            coVerify(exactly = 1) { scheduleService.setZone(ZoneId.of("Asia/Irkutsk"), "owner") }
        }

    @Test
    fun `invalid zone preset is ignored`() =
        runTest {
            assertEquals(ScheduleCallbackHandler.Outcome.Ignore, dispatch("nfs:g:sched:z:Not/AZone"))
            coVerify(exactly = 0) { scheduleService.setZone(any(), any()) }
        }

    @Test
    fun `zman awaits manual input`() =
        runTest {
            assertEquals(ScheduleCallbackHandler.Outcome.AwaitManualZone, dispatch("nfs:g:sched:zman"))
        }

    @Test
    fun `home re-renders main without changes`() =
        runTest {
            val outcome = dispatch("nfs:g:sched:home")
            assertEquals(ScheduleCallbackHandler.Outcome.RenderMain, outcome)
            coVerify(exactly = 0) { scheduleService.setEnabled(any(), any()) }
            coVerify(exactly = 0) { scheduleService.setWindow(any(), any()) }
            coVerify(exactly = 0) { scheduleService.setZone(any(), any()) }
        }

    @Test
    fun `unknown action is ignored`() =
        runTest {
            assertEquals(ScheduleCallbackHandler.Outcome.Ignore, dispatch("nfs:g:sched:wat"))
        }
}
```

`git add` the test file.

- [ ] **Step 2: Run to verify failure**

Via `build-runner` agent: `./gradlew :frigate-analyzer-telegram:test --tests "ru.zinin.frigate.analyzer.telegram.bot.handler.notifications.ScheduleCallbackHandlerTest"`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Implement the handler**

`ScheduleCallbackHandler.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.bot.handler.notifications

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.model.dto.ScheduleWindow
import ru.zinin.frigate.analyzer.service.NotificationScheduleService
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import java.time.DateTimeException
import java.time.ZoneId

private val logger = KotlinLogging.logger {}

/**
 * Pure dispatch for the `nfs:g:sched:*` callback subtree (mirrors
 * [NotificationsSettingsCallbackHandler.dispatch]): mutations go through
 * [NotificationScheduleService]; Telegram I/O stays at the call site (ScheduleSettingsFlow).
 */
@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class ScheduleCallbackHandler(
    private val scheduleService: NotificationScheduleService,
    private val userService: TelegramUserService,
) {
    sealed interface Outcome {
        data object RenderMain : Outcome

        data object RenderStartPicker : Outcome

        data class RenderEndPicker(
            val startHour: Int,
            val rejectedEqualEnd: Boolean = false,
        ) : Outcome

        data object RenderZoneScreen : Outcome

        data object AwaitManualZone : Outcome

        data object Unauthorized : Outcome

        data object Ignore : Outcome
    }

    suspend fun dispatch(
        data: String,
        isOwner: Boolean,
        ownerChatId: Long,
        updatedBy: String?,
    ): Outcome {
        if (!data.startsWith(PREFIX)) return Outcome.Ignore
        if (!isOwner) return Outcome.Unauthorized
        val action = data.removePrefix(PREFIX)
        val parts = action.split(":")
        return when {
            action == "on" -> {
                if (scheduleService.getWindow() == null) {
                    // Nothing to enable yet — lead the owner into configuration instead
                    // of creating an "on but empty" state that behaves as disabled.
                    Outcome.RenderStartPicker
                } else {
                    // Spec: the zone materializes on first enable/save — repair it here too so
                    // "enabled + window + no zone" (externally corrupted DB) cannot survive.
                    if (scheduleService.getZone() == null) {
                        scheduleService.setZone(userService.getUserZone(ownerChatId), updatedBy)
                    }
                    scheduleService.setEnabled(true, updatedBy)
                    Outcome.RenderMain
                }
            }

            action == "off" -> {
                scheduleService.setEnabled(false, updatedBy)
                Outcome.RenderMain
            }

            action == "cfg" -> Outcome.RenderStartPicker

            action == "zone" -> Outcome.RenderZoneScreen

            action == "zman" -> Outcome.AwaitManualZone

            action == "home" -> Outcome.RenderMain

            parts.size == 2 && parts[0] == "s" -> {
                val hour = parts[1].toIntOrNull()
                if (hour == null || hour !in 0..23) ignore(data) else Outcome.RenderEndPicker(hour)
            }

            parts.size == 3 && parts[0] == "e" -> {
                val start = parts[1].toIntOrNull()
                val end = parts[2].toIntOrNull()
                when {
                    start == null || start !in 0..23 || end == null || end !in 0..23 -> ignore(data)

                    start == end -> Outcome.RenderEndPicker(start, rejectedEqualEnd = true)

                    else -> {
                        // Write-order invariant: window → zone → enabled LAST, so a concurrent
                        // reader sees either the old enabled=false or the complete new state.
                        scheduleService.setWindow(ScheduleWindow.ofHours(start, end), updatedBy)
                        if (scheduleService.getZone() == null) {
                            scheduleService.setZone(userService.getUserZone(ownerChatId), updatedBy)
                        }
                        scheduleService.setEnabled(true, updatedBy)
                        Outcome.RenderMain
                    }
                }
            }

            parts.size == 2 && parts[0] == "z" -> {
                try {
                    scheduleService.setZone(ZoneId.of(parts[1]), updatedBy)
                    Outcome.RenderMain
                } catch (e: DateTimeException) {
                    ignore(data)
                }
            }

            else -> ignore(data)
        }
    }

    private fun ignore(data: String): Outcome {
        logger.debug { "Ignoring malformed sched callback: $data" }
        return Outcome.Ignore
    }

    companion object {
        const val PREFIX = "nfs:g:sched:"
    }
}
```

`git add` the file.

- [ ] **Step 4: Run tests to verify they pass**

Via `build-runner` agent: `./gradlew :frigate-analyzer-telegram:test --tests "ru.zinin.frigate.analyzer.telegram.bot.handler.notifications.ScheduleCallbackHandlerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(telegram): schedule callback dispatch"
```

---

### Task 9: `ScheduleSettingsFlow` + bot wiring

**Files:**
- Create: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/notifications/ScheduleSettingsFlow.kt`
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBot.kt`
- Modify: `modules/telegram/src/main/resources/messages_en.properties`
- Modify: `modules/telegram/src/main/resources/messages_ru.properties`

**Interfaces:**
- Consumes: `ScheduleCallbackHandler.dispatch/PREFIX/Outcome` (Task 8), `ScheduleKeyboardRenderer` (Task 7), `NotificationsViewStateFactory` (Task 5), `NotificationsMessageRenderer.render`, `NotificationScheduleService.getZone/setZone`, `TelegramUserService.findByChatIdAsDto`, ktgbotapi waiter API (`waitTextMessage`, pattern from `TimezoneCommandHandler`).
- Produces: `ScheduleSettingsFlow` with `suspend fun BehaviourContext.handle(data: String, callbackMsg: ContentMessage<TextContent>, current: TelegramUserDto, isOwner: Boolean)` — the bot routes every callback starting with `nfs:g:sched:` here BEFORE the generic nfs dispatch.

This is Telegram I/O glue mirroring the existing untested nfs-callback block in the bot; all decision logic is already unit-tested in Task 8, screens in Task 7. No new unit tests — the deliverable gate is compilation plus the full telegram module test suite staying green.

- [ ] **Step 1: Add i18n keys for the manual zone dialog**

`messages_en.properties`:

```properties
notifications.sched.zone.manual.prompt=Send an IANA timezone code (e.g. Europe/Moscow), or /cancel
notifications.sched.zone.saved=✅ Schedule timezone saved: {0}
notifications.sched.zone.invalid=⚠️ Unknown timezone. Open the zone screen and try again.
notifications.sched.zone.cancelled=Timezone input cancelled
notifications.sched.zone.timeout=⏰ Timed out waiting for a timezone.
```

`messages_ru.properties`:

```properties
notifications.sched.zone.manual.prompt=Пришлите код таймзоны IANA (например, Europe/Moscow) или /cancel
notifications.sched.zone.saved=✅ Часовой пояс расписания сохранён: {0}
notifications.sched.zone.invalid=⚠️ Неизвестный часовой пояс. Откройте экран пояса и попробуйте снова.
notifications.sched.zone.cancelled=Ввод часового пояса отменён
notifications.sched.zone.timeout=⏰ Время ожидания часового пояса истекло.
```

`git add` both files.

- [ ] **Step 2: Create `ScheduleSettingsFlow`**

`ScheduleSettingsFlow.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.bot.handler.notifications

import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitTextMessage
import dev.inmo.tgbotapi.types.message.abstracts.ContentMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.service.NotificationScheduleService
import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import java.time.DateTimeException
import java.time.ZoneId

private val logger = KotlinLogging.logger {}

/**
 * Telegram I/O for the /notifications schedule sub-dialog: maps [ScheduleCallbackHandler]
 * outcomes to screen edits and runs the manual-zone waiter (same conventions as /timezone:
 * 120 s timeout, /cancel, error reply on unknown zone).
 */
@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class ScheduleSettingsFlow(
    private val callbackHandler: ScheduleCallbackHandler,
    private val scheduleService: NotificationScheduleService,
    private val userService: TelegramUserService,
    private val viewStateFactory: NotificationsViewStateFactory,
    private val mainRenderer: NotificationsMessageRenderer,
    private val scheduleRenderer: ScheduleKeyboardRenderer,
    private val msg: MessageResolver,
) {
    suspend fun BehaviourContext.handle(
        data: String,
        callbackMsg: ContentMessage<TextContent>,
        current: TelegramUserDto,
        isOwner: Boolean,
    ) {
        val chatId =
            current.chatId ?: run {
                // Owner is always ACTIVE with a chatId — reaching here means a broken invariant.
                logger.warn { "sched callback from user without chatId: ${current.username}" }
                return
            }
        val lang = current.languageCode ?: "en"
        when (val outcome = callbackHandler.dispatch(data, isOwner, chatId, current.username)) {
            ScheduleCallbackHandler.Outcome.RenderMain -> renderMain(callbackMsg, current, isOwner)

            ScheduleCallbackHandler.Outcome.RenderStartPicker -> edit(callbackMsg, scheduleRenderer.startPicker(lang))

            is ScheduleCallbackHandler.Outcome.RenderEndPicker ->
                edit(callbackMsg, scheduleRenderer.endPicker(outcome.startHour, outcome.rejectedEqualEnd, lang))

            ScheduleCallbackHandler.Outcome.RenderZoneScreen ->
                edit(callbackMsg, scheduleRenderer.zoneScreen(scheduleService.getZone()?.id, lang))

            ScheduleCallbackHandler.Outcome.AwaitManualZone -> manualZoneInput(callbackMsg, current, isOwner, lang)

            ScheduleCallbackHandler.Outcome.Unauthorized -> Unit

            ScheduleCallbackHandler.Outcome.Ignore -> Unit
        }
    }

    private suspend fun BehaviourContext.manualZoneInput(
        callbackMsg: ContentMessage<TextContent>,
        current: TelegramUserDto,
        isOwner: Boolean,
        lang: String,
    ) {
        val cid = callbackMsg.chat.id
        sendTextMessage(cid, msg.get("notifications.sched.zone.manual.prompt", lang))
        val completed =
            withTimeoutOrNull(MANUAL_ZONE_TIMEOUT_MS) {
                val inputMsg = waitTextMessage().filter { it.chat.id == cid }.first()
                val input = inputMsg.content.text.trim()
                if (input == "/cancel") {
                    sendTextMessage(cid, msg.get("notifications.sched.zone.cancelled", lang))
                    return@withTimeoutOrNull
                }
                try {
                    val zone = ZoneId.of(input)
                    scheduleService.setZone(zone, current.username)
                    sendTextMessage(cid, msg.get("notifications.sched.zone.saved", lang, zone.id))
                    renderMain(callbackMsg, current, isOwner)
                } catch (e: DateTimeException) {
                    sendTextMessage(cid, msg.get("notifications.sched.zone.invalid", lang))
                }
            }
        if (completed == null) {
            sendTextMessage(cid, msg.get("notifications.sched.zone.timeout", lang))
        }
    }

    private suspend fun BehaviourContext.renderMain(
        callbackMsg: ContentMessage<TextContent>,
        current: TelegramUserDto,
        isOwner: Boolean,
    ) {
        val updated = current.chatId?.let { userService.findByChatIdAsDto(it) } ?: current
        val state = viewStateFactory.build(updated, isOwner)
        edit(callbackMsg, mainRenderer.render(state))
    }

    private suspend fun BehaviourContext.edit(
        callbackMsg: ContentMessage<TextContent>,
        rendered: RenderedNotifications,
    ) {
        try {
            editMessageText(callbackMsg, rendered.text, replyMarkup = rendered.keyboard)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.message?.contains("message is not modified", ignoreCase = true) == true) {
                logger.debug { "sched edit no-op (message not modified)" }
            } else {
                logger.warn(e) { "Failed to edit /notifications schedule screen" }
            }
        }
    }

    companion object {
        private const val MANUAL_ZONE_TIMEOUT_MS = 120_000L
    }
}
```

`git add` the file.

- [ ] **Step 3: Wire into `FrigateAnalyzerBot`**

1. Constructor: add `private val scheduleSettingsFlow: ScheduleSettingsFlow,` after `notificationsViewStateFactory`.
2. Add import `ru.zinin.frigate.analyzer.telegram.bot.handler.notifications.ScheduleCallbackHandler` and `...ScheduleSettingsFlow`.
3. Inside the `onDataCallbackQuery(initialFilter = { it.data.startsWith("nfs:") })` block, immediately after `val owner = userService.isOwner(current.username)` and BEFORE the `notificationsSettingsCallbackHandler.dispatch(...)` call, insert:

```kotlin
                    // Routing invariant: the sched subtree MUST be intercepted BEFORE the generic
                    // notificationsSettingsCallbackHandler.dispatch (which silently IGNOREs it).
                    if (callback.data.startsWith(ScheduleCallbackHandler.PREFIX)) {
                        @Suppress("UNCHECKED_CAST")
                        with(scheduleSettingsFlow) {
                            handle(callback.data, callbackMsg as ContentMessage<TextContent>, current, owner)
                        }
                        return@onDataCallbackQuery
                    }
```

(`ContentMessage` is already imported in this file for the existing `editMessageText` cast; if not, add `dev.inmo.tgbotapi.types.message.abstracts.ContentMessage`.)

4. Guard the routing invariant: in the existing `NotificationsSettingsCallbackHandlerTest` add a
   test asserting that `dispatch("nfs:g:sched:on", ...)` returns the IGNORE outcome (copy the
   dispatch-call signature from a neighbouring test in that file) — if someone later reorders the
   bot checks, the silent swallow becomes visible.

`git add` the files.

- [ ] **Step 4: Full telegram module test run**

Via `build-runner` agent: `./gradlew :frigate-analyzer-telegram:test`
Expected: PASS (includes `MessageKeyParityTest` for the new keys). Fix any ktlint findings with `./gradlew ktlintFormat`.

- [ ] **Step 5: Manual waiter verification (live bot) — REQUIRED before merge**

The manual-zone waiter runs from a callback context for the first time (see the plan-quality
warning). On a live bot verify:

1. `zman` → valid zone (e.g. `Europe/Berlin`) → saved message + main screen re-rendered.
2. `zman` → invalid input → error message, flow exits (one-shot).
3. `zman` → `/cancel` → cancelled message.
4. `zman` → no input for 120 s → timeout message.
5. `zman`, then immediately click another `nfs:` button — the button spinner must not hang for
   120 s (ktgbotapi callback-concurrency semantics are unverified for waiters).
6. Double `zman` click → no duplicated saves/messages.

Document the waiter limitations while here (Task 10 covers the rules file): the waiter does not
survive a bot restart; one active waiter per chat.

If any check fails — STOP and discuss (do not improvise around the waiter).

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(telegram): wire schedule flow into bot"
```

---

### Task 10: Documentation + full build

**Files:**
- Modify: `.claude/rules/telegram-notifications.md`
- Modify: `.claude/rules/database.md`

**Interfaces:** none (docs + verification only).

- [ ] **Step 1: Update the module rule doc**

In `.claude/rules/telegram-notifications.md`:

1. Components table — add rows:

```markdown
| `NotificationsViewStateFactory` | `bot/handler/notifications/` | Single assembly point for `NotificationsViewState` (command, re-render, schedule flow) |
| `ScheduleCallbackHandler` | `bot/handler/notifications/` | Pure dispatch for `nfs:g:sched:*`, mutates schedule settings |
| `ScheduleKeyboardRenderer` | `bot/handler/notifications/` | Hour-picker and timezone screens of the schedule sub-dialog |
| `ScheduleSettingsFlow` | `bot/handler/notifications/` | Telegram I/O: maps dispatch outcomes to screen edits, manual-zone waiter |
```

2. Callback Protocol table — add rows:

```markdown
| `nfs:g:sched:on` / `nfs:g:sched:off` | Enable / disable the detection schedule (OWNER only; `on` without a configured window opens the picker) |
| `nfs:g:sched:cfg` | Open the start-hour picker |
| `nfs:g:sched:s:<H>` | Start hour chosen → end-hour picker (start rides in callback data) |
| `nfs:g:sched:e:<S>:<E>` | End hour chosen → save window `[S:00, E:00)`, materialize zone if unset, auto-enable |
| `nfs:g:sched:zone` | Open the timezone screen (presets as in `/timezone` + manual input) |
| `nfs:g:sched:z:<olson>` | Set schedule zone from preset |
| `nfs:g:sched:zman` | Manual zone input via waiter (120 s timeout, `/cancel`) |
| `nfs:g:sched:home` | Back to the main screen |
```

3. State Storage table — add rows:

```markdown
| Schedule enabled | `app_settings` → `notifications.recording.schedule.enabled` | absent = `FALSE` |
| Schedule window | `app_settings` → `notifications.recording.schedule.window` (`HH:mm-HH:mm`, `[start,end)`, start>end crosses midnight) | absent |
| Schedule zone | `app_settings` → `notifications.recording.schedule.zone` (IANA id) | absent |
```

4. Consumers section — append:

```markdown
- **Detection schedule** — `NotificationDecisionServiceImpl` additionally suppresses recording
  notifications with reason `OUT_OF_SCHEDULE` when the schedule is enabled and
  `recording.recordTimestamp` falls outside the window. The timestamp is event time (a morning
  backlog run still delivers night events) evaluated in the schedule's own zone — the zone the
  window is interpreted in, independent of the camera and of the owner's personal `/timezone`.
  The gate applies before per-user fan-out (suppresses for ALL users; only the OWNER sees it)
  and only matters while the global recording flag is on. Schedule reads are fail-open:
  corrupt/unreadable settings degrade to "no schedule" with a warn log — deliberately
  asymmetric with the global flag, whose read failures keep the recording retryable; a schedule
  read failure produces extra notifications, never lost ones. Signal-loss alerts ignore the
  schedule.
```

5. Operational notes — append:

```markdown
- **Schedule ops:** `app_settings` values are cached per-process without TTL — direct SQL edits
  of `notifications.recording.schedule.*` are NOT picked up until restart (single-instance
  deployment assumed). The manual-zone waiter does not survive a bot restart; one active waiter
  per chat. Rollback: disable via the `/notifications` toggle (instant), or
  `DELETE FROM app_settings WHERE setting_key LIKE 'notifications.recording.schedule.%'` +
  restart for a full reset.
```

6. In `.claude/rules/database.md`, `app_settings` section — after the seeding sentence add:

```markdown
Schedule keys `notifications.recording.schedule.{enabled,window,zone}` are NOT seeded — they are
created on first configuration via `/notifications`; absent keys mean "schedule disabled".
```

`git add .claude/rules/telegram-notifications.md .claude/rules/database.md`

- [ ] **Step 2: Branch-wide code review**

Dispatch the `superpowers:code-reviewer` agent over the full feature-branch diff; fix critical
findings and repeat until clean (project CLAUDE.md: review BEFORE build).

- [ ] **Step 3: Full build**

Dispatch the `build-runner` agent: `./gradlew build`
Expected: BUILD SUCCESSFUL (all modules, all tests, ktlint). On ktlint errors run `./gradlew ktlintFormat` and retry.

- [ ] **Step 4: Commit**

```bash
git commit -m "docs: schedule settings in telegram-notifications rule"
```

---

## Self-Review (performed at plan time)

- **Spec coverage:** storage keys → Task 3; `AppSettingsService` strings → Task 2; window/zone types + `[start,end)` + midnight + DST → Task 1; `OUT_OF_SCHEDULE` + branch order + tracker-error + fail-open never-throws → Task 4; ViewState/factory fields → Task 5; status line + buttons → Task 6; pickers/zone screens + stateless callback data + equal-end warning → Tasks 7–8; auto-enable on save, zone materialization, `on`-without-window → Task 8; OWNER-only → Task 8; manual zone waiter (120 s, `/cancel`) → Task 9; bot routing → Task 9; docs → Task 10. No DB migration by design.
- **Known intentional deviation:** none after the iter-1 review sync — the spec now records the two-row keyboard (owner keyboard 5 → 7 rows) and the bot-level interception of `nfs:g:sched:*` (formerly implied handler-level dispatch), in addition to the pre-planning amendments (inline warning instead of toast; `on` without window opens picker; never-throws schedule reads).
- **Type consistency:** `ScheduleWindow.ofHours/parse/storageFormat/displayFormat/contains`, `NotificationSchedule.contains(Instant)`, `NotificationScheduleService` method set, `Outcome.RenderEndPicker(startHour, rejectedEqualEnd)` ↔ `endPicker(startHour, showEqualWarning, lang)`, `PREFIX = "nfs:g:sched:"` — verified consistent across Tasks 1/3/7/8/9.
- **Single placeholder-by-reference:** Task 4 asks the implementer to copy the literal `DetectionDelta(...)` argument list from the existing NEW_OBJECTS test in the same file (constructor not visible at plan time; copying the in-file literal is safer than inventing one).
