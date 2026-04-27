# Notification Controls and Object Tracking — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add per-user and global on/off toggles for two Telegram notification streams (recording detections; camera signal-loss/recovery), plus a system-level IoU-based object tracker that suppresses duplicate notifications when an object stays in view across consecutive recordings.

**Architecture:** New domain services in `service/` — `ObjectTrackerService` (per-camera IoU tracking with sliding-TTL state in PostgreSQL), `AppSettingsService` (key/value table for global toggles, in-memory cached), `NotificationDecisionService` (orchestrates tracker + global checks before recording fan-out). Per-user flags on `telegram_users`. Bot dialog `/notifications` with inline keyboards manages both per-user and (for OWNER) global state. `RecordingProcessingFacade` calls the decision service before invoking Telegram fan-out, which now also filters recipients by their per-user flags.

**Tech Stack:** Kotlin 2.3.10, Spring Boot 4.0.3, R2DBC PostgreSQL, Coroutines + kotlinx.coroutines.sync.Mutex, Liquibase, ktgbotapi, MockK + JUnit5 for tests.

**Spec:** `docs/superpowers/specs/2026-04-27-notification-controls-and-object-tracking-design.md`

---

## File Map

**New:**
- `docker/liquibase/migration/1.0.4.xml` — three changesets (tables + columns)
- `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/persistent/ObjectTrackEntity.kt`
- `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/persistent/AppSettingEntity.kt`
- `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/RepresentativeBbox.kt`
- `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/DetectionDelta.kt`
- `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/NotificationDecision.kt`
- `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/repository/ObjectTrackRepository.kt`
- `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/repository/AppSettingRepository.kt`
- `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/helper/IouHelper.kt`
- `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/helper/BboxClusteringHelper.kt`
- `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/config/ObjectTrackerProperties.kt`
- `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/AppSettingKeys.kt`
- `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/AppSettingsService.kt`
- `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/AppSettingsServiceImpl.kt`
- `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/ObjectTrackerService.kt`
- `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/ObjectTrackerServiceImpl.kt`
- `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/NotificationDecisionService.kt`
- `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/NotificationDecisionServiceImpl.kt`
- `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/ObjectTracksCleanupTask.kt`
- `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/notifications/NotificationsMessageRenderer.kt`
- `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/notifications/NotificationsCommandHandler.kt`
- `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/notifications/NotificationsSettingsCallbackHandler.kt`

**Modified:**
- `docker/liquibase/migration/master_frigate_analyzer.xml` — register 1.0.4
- `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/entity/TelegramUserEntity.kt` — two columns
- `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/dto/TelegramUserDto.kt` — two fields
- `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/dto/UserZoneInfo.kt` — two fields
- `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/repository/TelegramUserRepository.kt` — toggle queries
- `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/TelegramUserService.kt` — toggle methods
- `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramUserServiceImpl.kt`
- `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImpl.kt` — global check + per-user filter, both flows
- `modules/telegram/src/main/resources/messages_ru.properties` — new keys
- `modules/telegram/src/main/resources/messages_en.properties` — new keys
- `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/facade/RecordingProcessingFacade.kt` — call decision service
- `modules/core/src/main/resources/application.yaml` — `application.notifications.tracker` block
- `.env.example` — new env vars
- `.claude/rules/configuration.md`, `.claude/rules/telegram.md`, `.claude/rules/database.md` — docs

**New tests:**
- `modules/service/src/test/kotlin/.../helper/IouHelperTest.kt`
- `modules/service/src/test/kotlin/.../helper/BboxClusteringHelperTest.kt`
- `modules/service/src/test/kotlin/.../impl/AppSettingsServiceImplTest.kt`
- `modules/service/src/test/kotlin/.../impl/ObjectTrackerServiceImplTest.kt`
- `modules/service/src/test/kotlin/.../impl/NotificationDecisionServiceImplTest.kt`
- `modules/telegram/src/test/kotlin/.../bot/handler/notifications/NotificationsMessageRendererTest.kt`
- `modules/telegram/src/test/kotlin/.../bot/handler/notifications/NotificationsSettingsCallbackHandlerTest.kt`
- `modules/telegram/src/test/kotlin/.../service/impl/TelegramUserServiceImplNotificationsTest.kt`

**Modified tests:**
- `modules/telegram/src/test/kotlin/.../service/impl/TelegramNotificationServiceImplTest.kt` — recipient filtering + global gate
- `modules/telegram/src/test/kotlin/.../service/impl/TelegramNotificationServiceImplSignalLossTest.kt` — same

---

## Notes for Implementer

- **Build/test:** Per project conventions, do NOT run `./gradlew build` directly. After each task, dispatch `build-runner` to run `./gradlew :module:test` for the affected module. On ktlint errors run `./gradlew ktlintFormat` then retry.
- **Module dependencies:** `core → telegram → service → model → common`. Don't accidentally pull telegram into service or model.
- **Coordinates:** YOLO returns `Double`, persists as `Float` in `DetectionEntity`. Tracker DTOs use `Float` (matches DB and IoU math).
- **Time:** Always inject `java.time.Clock` for testability — never call `Instant.now()` directly.
- **Logging:** `kotlin-logging` (`io.github.oshai.kotlinlogging.KotlinLogging`).
- **Coroutines `Mutex`:** Use `kotlinx.coroutines.sync.Mutex` (suspend-friendly), not `java.util.concurrent.locks`.
- **Spring R2DBC:** repositories implement `CoroutineCrudRepository`; `@Modifying` on UPDATE/DELETE custom queries; `@Transactional` (`org.springframework.transaction.annotation.Transactional`) on service methods that mutate.
- **Commit per task** (no batching across tasks). After committing, `git add` the new files individually — never `git add .`.

---

## Task 1: Liquibase migration `1.0.4`

**Goal:** Create `object_tracks` table, create `app_settings` table with seed rows, add two columns to `telegram_users`. Register the new file in the master changelog.

**Files:**
- Create: `docker/liquibase/migration/1.0.4.xml`
- Modify: `docker/liquibase/migration/master_frigate_analyzer.xml`

- [ ] **Step 1: Write the new changelog**

Create `docker/liquibase/migration/1.0.4.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-5.0.xsd">
    <changeSet author="zinin" id="20260427-01-create-object-tracks">
        <comment>Create object_tracks table for per-camera object tracking with TTL</comment>
        <sql>
            CREATE TABLE object_tracks (
              id                  UUID         PRIMARY KEY,
              creation_timestamp  TIMESTAMPTZ  NOT NULL,
              cam_id              VARCHAR(255) NOT NULL,
              class_name          VARCHAR(255) NOT NULL,
              bbox_x1             REAL         NOT NULL,
              bbox_y1             REAL         NOT NULL,
              bbox_x2             REAL         NOT NULL,
              bbox_y2             REAL         NOT NULL,
              last_seen_at        TIMESTAMPTZ  NOT NULL,
              last_recording_id   UUID         NULL,
              CONSTRAINT fk_object_tracks_recording
                FOREIGN KEY (last_recording_id) REFERENCES recordings(id)
                ON DELETE SET NULL
            );
            CREATE INDEX idx_object_tracks_cam_lastseen
              ON object_tracks (cam_id, last_seen_at DESC);
        </sql>
    </changeSet>

    <changeSet author="zinin" id="20260427-02-create-app-settings">
        <comment>Create app_settings table; seed two notifications.* keys with default 'true'</comment>
        <sql>
            CREATE TABLE app_settings (
              setting_key    VARCHAR(64)   PRIMARY KEY,
              setting_value  VARCHAR(2048) NOT NULL,
              updated_at     TIMESTAMPTZ   NOT NULL,
              updated_by     VARCHAR(255)  NULL
            );

            INSERT INTO app_settings (setting_key, setting_value, updated_at, updated_by)
            VALUES
              ('notifications.recording.global_enabled', 'true', NOW(), NULL),
              ('notifications.signal.global_enabled',    'true', NOW(), NULL);
        </sql>
    </changeSet>

    <changeSet author="zinin" id="20260427-03-add-notification-flags-to-telegram-users">
        <comment>Per-user on/off toggles for recording and signal-loss notifications</comment>
        <sql>
            ALTER TABLE telegram_users
              ADD COLUMN notifications_recording_enabled BOOLEAN NOT NULL DEFAULT TRUE,
              ADD COLUMN notifications_signal_enabled    BOOLEAN NOT NULL DEFAULT TRUE;
        </sql>
    </changeSet>
</databaseChangeLog>
```

- [ ] **Step 2: Register in master changelog**

Edit `docker/liquibase/migration/master_frigate_analyzer.xml`. After the `<include file="1.0.3.xml" relativeToChangelogFile="true"/>` line add:

```xml
    <include file="1.0.4.xml" relativeToChangelogFile="true"/>
```

- [ ] **Step 3: Commit**

```bash
git add docker/liquibase/migration/1.0.4.xml docker/liquibase/migration/master_frigate_analyzer.xml
git commit -m "feat(db): add object_tracks, app_settings, telegram_users notification flags"
```

---

## Task 2: `ObjectTrackEntity` + `ObjectTrackRepository`

**Goal:** Persistent entity and Spring Data R2DBC repository for `object_tracks` with custom queries for active-track lookup, batch upsert, and cleanup.

**Files:**
- Create: `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/persistent/ObjectTrackEntity.kt`
- Create: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/repository/ObjectTrackRepository.kt`

- [ ] **Step 1: Create the entity**

Create `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/persistent/ObjectTrackEntity.kt`:

```kotlin
package ru.zinin.frigate.analyzer.model.persistent

import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table(name = "object_tracks")
data class ObjectTrackEntity(
    @JvmField
    @Id
    var id: UUID?,
    @Column("creation_timestamp")
    var creationTimestamp: Instant?,
    @Column("cam_id")
    var camId: String?,
    @Column("class_name")
    var className: String?,
    @Column("bbox_x1")
    var bboxX1: Float,
    @Column("bbox_y1")
    var bboxY1: Float,
    @Column("bbox_x2")
    var bboxX2: Float,
    @Column("bbox_y2")
    var bboxY2: Float,
    @Column("last_seen_at")
    var lastSeenAt: Instant?,
    @Column("last_recording_id")
    var lastRecordingId: UUID? = null,
) : Persistable<UUID> {
    override fun getId(): UUID? = id

    override fun isNew(): Boolean = true
}
```

- [ ] **Step 2: Create the repository interface**

Create `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/repository/ObjectTrackRepository.kt`:

```kotlin
package ru.zinin.frigate.analyzer.service.repository

import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import ru.zinin.frigate.analyzer.model.persistent.ObjectTrackEntity
import java.time.Instant
import java.util.UUID

@Repository
interface ObjectTrackRepository : CoroutineCrudRepository<ObjectTrackEntity, UUID> {
    @Query(
        """
        SELECT * FROM object_tracks
        WHERE cam_id = :camId
          AND last_seen_at >= :minLastSeen
        """,
    )
    suspend fun findActive(
        @Param("camId") camId: String,
        @Param("minLastSeen") minLastSeen: Instant,
    ): List<ObjectTrackEntity>

    @Modifying
    @Query(
        """
        UPDATE object_tracks
        SET bbox_x1 = :x1,
            bbox_y1 = :y1,
            bbox_x2 = :x2,
            bbox_y2 = :y2,
            last_seen_at = GREATEST(last_seen_at, :lastSeenAt),
            last_recording_id = :lastRecordingId
        WHERE id = :id
        """,
    )
    suspend fun updateOnMatch(
        @Param("id") id: UUID,
        @Param("x1") x1: Float,
        @Param("y1") y1: Float,
        @Param("x2") x2: Float,
        @Param("y2") y2: Float,
        @Param("lastSeenAt") lastSeenAt: Instant,
        @Param("lastRecordingId") lastRecordingId: UUID,
    ): Long

    @Modifying
    @Query("DELETE FROM object_tracks WHERE last_seen_at < :threshold")
    suspend fun deleteExpired(
        @Param("threshold") threshold: Instant,
    ): Long
}
```

- [ ] **Step 3: Run module tests to verify compilation**

Dispatch `build-runner` agent: `./gradlew :frigate-analyzer-service:compileKotlin :frigate-analyzer-model:compileKotlin`.
Expected: BUILD SUCCESSFUL with no errors.

- [ ] **Step 4: Commit**

```bash
git add modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/persistent/ObjectTrackEntity.kt \
        modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/repository/ObjectTrackRepository.kt
git commit -m "feat(service): add ObjectTrackEntity and ObjectTrackRepository"
```

---

## Task 3: `AppSettingEntity` + `AppSettingRepository` + `AppSettingKeys`

**Goal:** Persistent entity for `app_settings`, repository, and a `AppSettingKeys` constants object as the single source of truth for setting keys.

**Files:**
- Create: `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/persistent/AppSettingEntity.kt`
- Create: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/repository/AppSettingRepository.kt`
- Create: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/AppSettingKeys.kt`

- [ ] **Step 1: Create the entity**

Create `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/persistent/AppSettingEntity.kt`:

```kotlin
package ru.zinin.frigate.analyzer.model.persistent

import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table(name = "app_settings")
data class AppSettingEntity(
    @JvmField
    @Id
    @Column("setting_key")
    var settingKey: String?,
    @Column("setting_value")
    var settingValue: String?,
    @Column("updated_at")
    var updatedAt: Instant?,
    @Column("updated_by")
    var updatedBy: String? = null,
) : Persistable<String> {
    override fun getId(): String? = settingKey

    // Always treated as "new" so save() emits an INSERT; we use upsert SQL via the repo for updates.
    override fun isNew(): Boolean = true
}
```

- [ ] **Step 2: Create the repository**

Create `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/repository/AppSettingRepository.kt`:

```kotlin
package ru.zinin.frigate.analyzer.service.repository

import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import ru.zinin.frigate.analyzer.model.persistent.AppSettingEntity
import java.time.Instant

@Repository
interface AppSettingRepository : CoroutineCrudRepository<AppSettingEntity, String> {
    suspend fun findBySettingKey(settingKey: String): AppSettingEntity?

    @Modifying
    @Query(
        """
        INSERT INTO app_settings (setting_key, setting_value, updated_at, updated_by)
        VALUES (:settingKey, :settingValue, :updatedAt, :updatedBy)
        ON CONFLICT (setting_key) DO UPDATE
          SET setting_value = EXCLUDED.setting_value,
              updated_at    = EXCLUDED.updated_at,
              updated_by    = EXCLUDED.updated_by
        """,
    )
    suspend fun upsert(
        @Param("settingKey") settingKey: String,
        @Param("settingValue") settingValue: String,
        @Param("updatedAt") updatedAt: Instant,
        @Param("updatedBy") updatedBy: String?,
    ): Long
}
```

- [ ] **Step 3: Create the key constants**

Create `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/AppSettingKeys.kt`:

```kotlin
package ru.zinin.frigate.analyzer.service

object AppSettingKeys {
    const val NOTIFICATIONS_RECORDING_GLOBAL_ENABLED = "notifications.recording.global_enabled"
    const val NOTIFICATIONS_SIGNAL_GLOBAL_ENABLED = "notifications.signal.global_enabled"
}
```

- [ ] **Step 4: Verify compilation**

Dispatch `build-runner`: `./gradlew :frigate-analyzer-service:compileKotlin`.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/persistent/AppSettingEntity.kt \
        modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/repository/AppSettingRepository.kt \
        modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/AppSettingKeys.kt
git commit -m "feat(service): add AppSettingEntity, AppSettingRepository, AppSettingKeys"
```

---

## Task 4: Domain DTOs (`RepresentativeBbox`, `DetectionDelta`, `NotificationDecision`)

**Goal:** Pure data classes used by services. No logic; no Spring.

**Files:**
- Create: `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/RepresentativeBbox.kt`
- Create: `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/DetectionDelta.kt`
- Create: `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/NotificationDecision.kt`

- [ ] **Step 1: `RepresentativeBbox`**

Create `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/RepresentativeBbox.kt`:

```kotlin
package ru.zinin.frigate.analyzer.model.dto

/**
 * Aggregated bbox for one logical object inside a single recording.
 * Coordinates are normalized [0..1] from YOLO output, stored as Float to match DetectionEntity.
 */
data class RepresentativeBbox(
    val className: String,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
)
```

- [ ] **Step 2: `DetectionDelta`**

Create `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/DetectionDelta.kt`:

```kotlin
package ru.zinin.frigate.analyzer.model.dto

/** Result of one ObjectTrackerService.evaluate call: how many tracks were new vs matched vs stale. */
data class DetectionDelta(
    val newTracksCount: Int,
    val matchedTracksCount: Int,
    val staleTracksCount: Int,
    val newClasses: List<String>,
)
```

- [ ] **Step 3: `NotificationDecision`**

Create `modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/NotificationDecision.kt`:

```kotlin
package ru.zinin.frigate.analyzer.model.dto

data class NotificationDecision(
    val shouldNotify: Boolean,
    val reason: NotificationDecisionReason,
    val delta: DetectionDelta? = null,
)

enum class NotificationDecisionReason {
    NEW_OBJECTS,
    ALL_REPEATED,
    NO_DETECTIONS,
    GLOBAL_OFF,
    TRACKER_ERROR,
}
```

- [ ] **Step 4: Verify compilation**

Dispatch `build-runner`: `./gradlew :frigate-analyzer-model:compileKotlin`.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/RepresentativeBbox.kt \
        modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/DetectionDelta.kt \
        modules/model/src/main/kotlin/ru/zinin/frigate/analyzer/model/dto/NotificationDecision.kt
git commit -m "feat(model): add tracker DTOs (RepresentativeBbox, DetectionDelta, NotificationDecision)"
```

---

## Task 5: `IouHelper` + tests (TDD)

**Goal:** Pure object that computes Intersection-over-Union for axis-aligned bboxes. Two overloads: raw floats and `RepresentativeBbox`.

**Files:**
- Create test: `modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/helper/IouHelperTest.kt`
- Create: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/helper/IouHelper.kt`

- [ ] **Step 1: Write the failing test**

Create `modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/helper/IouHelperTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.service.helper

import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.model.dto.RepresentativeBbox
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IouHelperTest {
    private fun assertNear(expected: Float, actual: Float, eps: Float = 1e-5f) {
        assertTrue(abs(expected - actual) < eps, "expected $expected, got $actual")
    }

    @Test
    fun `identical bboxes return 1`() {
        val iou = IouHelper.iou(0.1f, 0.1f, 0.5f, 0.5f, 0.1f, 0.1f, 0.5f, 0.5f)
        assertNear(1.0f, iou)
    }

    @Test
    fun `disjoint bboxes return 0`() {
        val iou = IouHelper.iou(0f, 0f, 0.2f, 0.2f, 0.5f, 0.5f, 0.8f, 0.8f)
        assertEquals(0f, iou)
    }

    @Test
    fun `touching but not overlapping bboxes return 0`() {
        val iou = IouHelper.iou(0f, 0f, 0.5f, 0.5f, 0.5f, 0f, 1.0f, 0.5f)
        assertEquals(0f, iou)
    }

    @Test
    fun `half overlap returns one third`() {
        // A: [0,0]-[1,1] area=1; B: [0.5,0]-[1,1] area=0.5; intersection=0.5; union=1.0; iou=0.5
        val iou = IouHelper.iou(0f, 0f, 1f, 1f, 0.5f, 0f, 1f, 1f)
        assertNear(0.5f, iou)
    }

    @Test
    fun `fully contained returns ratio of areas`() {
        // A: [0,0]-[1,1] area=1; B: [0.25,0.25]-[0.75,0.75] area=0.25; iou = 0.25/1.0 = 0.25
        val iou = IouHelper.iou(0f, 0f, 1f, 1f, 0.25f, 0.25f, 0.75f, 0.75f)
        assertNear(0.25f, iou)
    }

    @Test
    fun `zero-area bbox returns 0`() {
        val iou = IouHelper.iou(0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f)
        assertEquals(0f, iou)
    }

    @Test
    fun `RepresentativeBbox overload delegates to raw overload`() {
        val a = RepresentativeBbox("car", 0f, 0f, 1f, 1f)
        val b = RepresentativeBbox("car", 0.5f, 0f, 1f, 1f)
        assertNear(0.5f, IouHelper.iou(a, b))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Dispatch `build-runner`: `./gradlew :frigate-analyzer-service:test --tests "ru.zinin.frigate.analyzer.service.helper.IouHelperTest"`.
Expected: FAIL with "Unresolved reference: IouHelper".

- [ ] **Step 3: Write the minimal implementation**

Create `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/helper/IouHelper.kt`:

```kotlin
package ru.zinin.frigate.analyzer.service.helper

import ru.zinin.frigate.analyzer.model.dto.RepresentativeBbox

object IouHelper {
    @Suppress("LongParameterList")
    fun iou(
        ax1: Float, ay1: Float, ax2: Float, ay2: Float,
        bx1: Float, by1: Float, bx2: Float, by2: Float,
    ): Float {
        val ix1 = maxOf(ax1, bx1)
        val iy1 = maxOf(ay1, by1)
        val ix2 = minOf(ax2, bx2)
        val iy2 = minOf(ay2, by2)
        val iw = maxOf(0f, ix2 - ix1)
        val ih = maxOf(0f, iy2 - iy1)
        val intersection = iw * ih

        val areaA = (ax2 - ax1) * (ay2 - ay1)
        val areaB = (bx2 - bx1) * (by2 - by1)
        val union = areaA + areaB - intersection

        return if (union > 0f) intersection / union else 0f
    }

    fun iou(a: RepresentativeBbox, b: RepresentativeBbox): Float =
        iou(a.x1, a.y1, a.x2, a.y2, b.x1, b.y1, b.x2, b.y2)
}
```

- [ ] **Step 4: Run test to verify it passes**

Dispatch `build-runner`: `./gradlew :frigate-analyzer-service:test --tests "ru.zinin.frigate.analyzer.service.helper.IouHelperTest"`.
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/helper/IouHelper.kt \
        modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/helper/IouHelperTest.kt
git commit -m "feat(service): add IouHelper with tests"
```

---

## Task 6: `BboxClusteringHelper` + tests (TDD)

**Goal:** Cluster a recording's per-frame detections into representative bboxes per `(class, cluster)`. Greedy IoU-based clustering with `INNER_IOU` threshold.

**Files:**
- Create test: `modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/helper/BboxClusteringHelperTest.kt`
- Create: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/helper/BboxClusteringHelper.kt`

- [ ] **Step 1: Write the failing test**

Create `modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/helper/BboxClusteringHelperTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.service.helper

import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.model.persistent.DetectionEntity
import java.util.UUID
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BboxClusteringHelperTest {
    private fun det(
        className: String,
        x1: Float, y1: Float, x2: Float, y2: Float,
        confidence: Float = 0.9f,
    ): DetectionEntity =
        DetectionEntity(
            id = UUID.randomUUID(),
            creationTimestamp = null,
            recordingId = null,
            detectionTimestamp = null,
            frameIndex = 0,
            model = "yolo",
            classId = 0,
            className = className,
            confidence = confidence,
            x1 = x1, y1 = y1, x2 = x2, y2 = y2,
        )

    @Test
    fun `empty input yields empty output`() {
        val result = BboxClusteringHelper.cluster(emptyList(), innerIou = 0.5f)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `one detection yields one bbox`() {
        val result = BboxClusteringHelper.cluster(listOf(det("car", 0f, 0f, 1f, 1f)), innerIou = 0.5f)
        assertEquals(1, result.size)
        assertEquals("car", result[0].className)
    }

    @Test
    fun `near-identical bboxes of same class merge into one cluster`() {
        val result = BboxClusteringHelper.cluster(
            listOf(
                det("car", 0.0f, 0.0f, 0.5f, 0.5f, confidence = 1.0f),
                det("car", 0.01f, 0.0f, 0.51f, 0.5f, confidence = 0.8f),
                det("car", -0.01f, 0.0f, 0.49f, 0.5f, confidence = 0.6f),
            ),
            innerIou = 0.5f,
        )
        assertEquals(1, result.size)
        // Confidence-weighted average should be close to the highest-confidence sample.
        val box = result[0]
        assertTrue(abs(box.x1 - 0.0f) < 0.01f, "x1=${box.x1}")
        assertTrue(abs(box.x2 - 0.5f) < 0.01f, "x2=${box.x2}")
    }

    @Test
    fun `same class but distant bboxes form two clusters`() {
        val result = BboxClusteringHelper.cluster(
            listOf(
                det("car", 0.0f, 0.0f, 0.2f, 0.2f),
                det("car", 0.7f, 0.7f, 0.9f, 0.9f),
            ),
            innerIou = 0.5f,
        )
        assertEquals(2, result.size)
    }

    @Test
    fun `different classes never merge`() {
        val result = BboxClusteringHelper.cluster(
            listOf(
                det("car", 0.0f, 0.0f, 0.5f, 0.5f),
                det("person", 0.0f, 0.0f, 0.5f, 0.5f),
            ),
            innerIou = 0.5f,
        )
        assertEquals(2, result.size)
        assertEquals(setOf("car", "person"), result.map { it.className }.toSet())
    }

    @Test
    fun `higher confidence detection seeds cluster center`() {
        val result = BboxClusteringHelper.cluster(
            listOf(
                det("car", 0.45f, 0.45f, 0.55f, 0.55f, confidence = 0.5f),
                det("car", 0.40f, 0.40f, 0.60f, 0.60f, confidence = 0.95f), // strongest, processed first
            ),
            innerIou = 0.4f,
        )
        assertEquals(1, result.size)
        // Bias toward the high-confidence one
        assertTrue(result[0].x1 < 0.43f, "x1=${result[0].x1}")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Dispatch `build-runner`: `./gradlew :frigate-analyzer-service:test --tests "ru.zinin.frigate.analyzer.service.helper.BboxClusteringHelperTest"`.
Expected: FAIL with "Unresolved reference: BboxClusteringHelper".

- [ ] **Step 3: Write the minimal implementation**

Create `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/helper/BboxClusteringHelper.kt`:

```kotlin
package ru.zinin.frigate.analyzer.service.helper

import ru.zinin.frigate.analyzer.model.dto.RepresentativeBbox
import ru.zinin.frigate.analyzer.model.persistent.DetectionEntity

object BboxClusteringHelper {
    /**
     * Greedy clustering. For each class, sort detections by confidence DESC and grow clusters
     * by adding detections whose IoU with the cluster's running union is above [innerIou].
     * Each cluster yields one [RepresentativeBbox] computed as a confidence-weighted average.
     */
    fun cluster(
        detections: List<DetectionEntity>,
        innerIou: Float,
    ): List<RepresentativeBbox> {
        if (detections.isEmpty()) return emptyList()

        return detections
            .groupBy { it.className }
            .flatMap { (className, group) ->
                clusterOneClass(className, group.sortedByDescending { it.confidence }, innerIou)
            }
    }

    private data class WorkingCluster(
        var unionX1: Float, var unionY1: Float, var unionX2: Float, var unionY2: Float,
        var weightedX1: Float, var weightedY1: Float, var weightedX2: Float, var weightedY2: Float,
        var weightSum: Float,
    )

    private fun clusterOneClass(
        className: String,
        sortedByConfidenceDesc: List<DetectionEntity>,
        innerIou: Float,
    ): List<RepresentativeBbox> {
        val clusters = mutableListOf<WorkingCluster>()
        for (det in sortedByConfidenceDesc) {
            val matched = clusters.firstOrNull { c ->
                IouHelper.iou(c.unionX1, c.unionY1, c.unionX2, c.unionY2, det.x1, det.y1, det.x2, det.y2) > innerIou
            }
            if (matched != null) {
                addToCluster(matched, det)
            } else {
                clusters += newCluster(det)
            }
        }
        return clusters.map { c ->
            val w = c.weightSum
            RepresentativeBbox(
                className = className,
                x1 = c.weightedX1 / w,
                y1 = c.weightedY1 / w,
                x2 = c.weightedX2 / w,
                y2 = c.weightedY2 / w,
            )
        }
    }

    private fun newCluster(det: DetectionEntity): WorkingCluster {
        val w = det.confidence
        return WorkingCluster(
            unionX1 = det.x1, unionY1 = det.y1, unionX2 = det.x2, unionY2 = det.y2,
            weightedX1 = det.x1 * w, weightedY1 = det.y1 * w,
            weightedX2 = det.x2 * w, weightedY2 = det.y2 * w,
            weightSum = w,
        )
    }

    private fun addToCluster(c: WorkingCluster, det: DetectionEntity) {
        c.unionX1 = minOf(c.unionX1, det.x1)
        c.unionY1 = minOf(c.unionY1, det.y1)
        c.unionX2 = maxOf(c.unionX2, det.x2)
        c.unionY2 = maxOf(c.unionY2, det.y2)
        val w = det.confidence
        c.weightedX1 += det.x1 * w
        c.weightedY1 += det.y1 * w
        c.weightedX2 += det.x2 * w
        c.weightedY2 += det.y2 * w
        c.weightSum += w
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Dispatch `build-runner`: `./gradlew :frigate-analyzer-service:test --tests "ru.zinin.frigate.analyzer.service.helper.BboxClusteringHelperTest"`.
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/helper/BboxClusteringHelper.kt \
        modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/helper/BboxClusteringHelperTest.kt
git commit -m "feat(service): add BboxClusteringHelper with tests"
```

---

## Task 7: `ObjectTrackerProperties` (Spring config)

**Goal:** Configurable parameters for the tracker via `@ConfigurationProperties`.

**Files:**
- Create: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/config/ObjectTrackerProperties.kt`

- [ ] **Step 1: Create the properties class**

Create `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/config/ObjectTrackerProperties.kt`:

```kotlin
package ru.zinin.frigate.analyzer.service.config

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@ConfigurationProperties(prefix = "application.notifications.tracker")
@Validated
data class ObjectTrackerProperties(
    val ttl: Duration = Duration.ofSeconds(120),
    @field:DecimalMin("0.0") @field:DecimalMax("1.0")
    val iouThreshold: Float = 0.3f,
    @field:DecimalMin("0.0") @field:DecimalMax("1.0")
    val innerIou: Float = 0.5f,
    val cleanupInterval: Duration = Duration.ofHours(1),
    val cleanupRetention: Duration = Duration.ofHours(1),
)
```

- [ ] **Step 2: Verify compilation**

Dispatch `build-runner`: `./gradlew :frigate-analyzer-service:compileKotlin`.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/config/ObjectTrackerProperties.kt
git commit -m "feat(service): add ObjectTrackerProperties"
```

---

## Task 8: `ObjectTrackerService` interface + impl + tests

**Goal:** Core service that aggregates a recording's detections, matches against active tracks, persists updates, and reports a `DetectionDelta`. Per-camera `Mutex` serialization.

**Files:**
- Create: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/ObjectTrackerService.kt`
- Create test: `modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/impl/ObjectTrackerServiceImplTest.kt`
- Create: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/ObjectTrackerServiceImpl.kt`

- [ ] **Step 1: Define the interface**

Create `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/ObjectTrackerService.kt`:

```kotlin
package ru.zinin.frigate.analyzer.service

import ru.zinin.frigate.analyzer.model.dto.DetectionDelta
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.persistent.DetectionEntity

interface ObjectTrackerService {
    /**
     * Aggregates [detections] for [recording], matches each representative bbox against the
     * camera's active tracks (within configured TTL), persists updates and inserts, and returns
     * a [DetectionDelta] summarizing the outcome.
     *
     * Idempotent under retries within the same recording: matching is timestamp-based and
     * `last_seen_at` uses GREATEST in SQL.
     */
    suspend fun evaluate(
        recording: RecordingDto,
        detections: List<DetectionEntity>,
    ): DetectionDelta

    /** Removes tracks with `last_seen_at < threshold`. Returns deleted row count. */
    suspend fun cleanupExpired(): Long
}
```

- [ ] **Step 2: Write the failing test**

Create `modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/impl/ObjectTrackerServiceImplTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.service.impl

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.common.helper.UUIDGeneratorHelper
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.persistent.DetectionEntity
import ru.zinin.frigate.analyzer.model.persistent.ObjectTrackEntity
import ru.zinin.frigate.analyzer.service.config.ObjectTrackerProperties
import ru.zinin.frigate.analyzer.service.repository.ObjectTrackRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObjectTrackerServiceImplTest {
    private val repo = mockk<ObjectTrackRepository>(relaxed = true)
    private val uuid = mockk<UUIDGeneratorHelper>()
    private val fixedNow = Instant.parse("2026-04-27T12:00:00Z")
    private val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
    private val props = ObjectTrackerProperties()
    private val service: ObjectTrackerServiceImpl =
        ObjectTrackerServiceImpl(repo, uuid, clock, props)

    private val camId = "front"
    private val recId = UUID.randomUUID()

    private fun rec(t: Instant = fixedNow): RecordingDto =
        RecordingDto(
            id = recId,
            creationTimestamp = t,
            filePath = "/r.mp4",
            fileCreationTimestamp = t,
            camId = camId,
            recordDate = LocalDate.from(t.atZone(ZoneOffset.UTC)),
            recordTime = LocalTime.from(t.atZone(ZoneOffset.UTC)),
            recordTimestamp = t,
            startProcessingTimestamp = t,
            processTimestamp = t,
            processAttempts = 1,
            detectionsCount = 1,
            analyzeTime = 1,
            analyzedFramesCount = 1,
            errorMessage = null,
        )

    private fun det(
        className: String, x1: Float, y1: Float, x2: Float, y2: Float, conf: Float = 0.9f,
    ) = DetectionEntity(
        id = UUID.randomUUID(),
        creationTimestamp = fixedNow,
        recordingId = recId,
        detectionTimestamp = fixedNow,
        frameIndex = 0,
        model = "yolo",
        classId = 0,
        className = className,
        confidence = conf,
        x1 = x1, y1 = y1, x2 = x2, y2 = y2,
    )

    private fun track(
        className: String, x1: Float, y1: Float, x2: Float, y2: Float, lastSeen: Instant = fixedNow,
    ) = ObjectTrackEntity(
        id = UUID.randomUUID(),
        creationTimestamp = lastSeen,
        camId = camId,
        className = className,
        bboxX1 = x1, bboxY1 = y1, bboxX2 = x2, bboxY2 = y2,
        lastSeenAt = lastSeen,
        lastRecordingId = null,
    )

    @Test
    fun `empty detections produce zero delta and no DB writes`() = runTest {
        coEvery { repo.findActive(any(), any()) } returns emptyList()

        val delta = service.evaluate(rec(), emptyList())

        assertEquals(0, delta.newTracksCount)
        assertEquals(0, delta.matchedTracksCount)
        coVerify(exactly = 0) { repo.save(any()) }
        coVerify(exactly = 0) { repo.updateOnMatch(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `first time appearance creates a new track and reports new=1`() = runTest {
        coEvery { repo.findActive(any(), any()) } returns emptyList()
        coEvery { uuid.generateV1() } returns UUID.randomUUID()

        val delta = service.evaluate(
            rec(),
            listOf(det("car", 0f, 0f, 0.5f, 0.5f)),
        )

        assertEquals(1, delta.newTracksCount)
        assertEquals(0, delta.matchedTracksCount)
        assertTrue(delta.newClasses.contains("car"))
        coVerify(exactly = 1) { repo.save(any()) }
    }

    @Test
    fun `match against existing active track reports matched=1 and updates`() = runTest {
        val existing = track("car", 0f, 0f, 0.5f, 0.5f)
        coEvery { repo.findActive(any(), any()) } returns listOf(existing)

        val delta = service.evaluate(
            rec(),
            listOf(det("car", 0.01f, 0.0f, 0.51f, 0.5f)), // high IoU
        )

        assertEquals(0, delta.newTracksCount)
        assertEquals(1, delta.matchedTracksCount)
        coVerify(exactly = 1) { repo.updateOnMatch(existing.id!!, any(), any(), any(), any(), any(), recId) }
        coVerify(exactly = 0) { repo.save(any()) }
    }

    @Test
    fun `same class but distant bbox treated as new track`() = runTest {
        val existing = track("car", 0f, 0f, 0.2f, 0.2f)
        coEvery { repo.findActive(any(), any()) } returns listOf(existing)
        coEvery { uuid.generateV1() } returns UUID.randomUUID()

        val delta = service.evaluate(
            rec(),
            listOf(det("car", 0.7f, 0.7f, 0.9f, 0.9f)),
        )

        assertEquals(1, delta.newTracksCount)
        assertEquals(0, delta.matchedTracksCount)
    }

    @Test
    fun `mixed scenario car matches person is new`() = runTest {
        val existing = track("car", 0f, 0f, 0.5f, 0.5f)
        coEvery { repo.findActive(any(), any()) } returns listOf(existing)
        coEvery { uuid.generateV1() } returns UUID.randomUUID()

        val delta = service.evaluate(
            rec(),
            listOf(
                det("car", 0.01f, 0.0f, 0.51f, 0.5f),
                det("person", 0.6f, 0.6f, 0.8f, 0.9f),
            ),
        )

        assertEquals(1, delta.newTracksCount)
        assertEquals(1, delta.matchedTracksCount)
        assertEquals(listOf("person"), delta.newClasses)
    }

    @Test
    fun `findActive uses now minus TTL as threshold`() = runTest {
        val captured = slot<Instant>()
        coEvery { repo.findActive(eq(camId), capture(captured)) } returns emptyList()

        service.evaluate(rec(), emptyList())

        // Default TTL = 120s. fixedNow - 120s = 11:58:00Z.
        assertEquals(Instant.parse("2026-04-27T11:58:00Z"), captured.captured)
    }

    @Test
    fun `cleanupExpired delegates to repo with now-minus-retention`() = runTest {
        coEvery { repo.deleteExpired(any()) } returns 7L

        val deleted = service.cleanupExpired()

        assertEquals(7L, deleted)
        coVerify { repo.deleteExpired(Instant.parse("2026-04-27T11:00:00Z")) } // -1h retention
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Dispatch `build-runner`: `./gradlew :frigate-analyzer-service:test --tests "ru.zinin.frigate.analyzer.service.impl.ObjectTrackerServiceImplTest"`.
Expected: FAIL with "Unresolved reference: ObjectTrackerServiceImpl".

- [ ] **Step 4: Write the implementation**

Create `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/ObjectTrackerServiceImpl.kt`:

```kotlin
package ru.zinin.frigate.analyzer.service.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.zinin.frigate.analyzer.common.helper.UUIDGeneratorHelper
import ru.zinin.frigate.analyzer.model.dto.DetectionDelta
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.dto.RepresentativeBbox
import ru.zinin.frigate.analyzer.model.persistent.DetectionEntity
import ru.zinin.frigate.analyzer.model.persistent.ObjectTrackEntity
import ru.zinin.frigate.analyzer.service.ObjectTrackerService
import ru.zinin.frigate.analyzer.service.config.ObjectTrackerProperties
import ru.zinin.frigate.analyzer.service.helper.BboxClusteringHelper
import ru.zinin.frigate.analyzer.service.helper.IouHelper
import ru.zinin.frigate.analyzer.service.repository.ObjectTrackRepository
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

@Service
class ObjectTrackerServiceImpl(
    private val repository: ObjectTrackRepository,
    private val uuid: UUIDGeneratorHelper,
    private val clock: Clock,
    private val properties: ObjectTrackerProperties,
) : ObjectTrackerService {
    private val perCameraMutex = ConcurrentHashMap<String, Mutex>()

    @Transactional
    override suspend fun evaluate(
        recording: RecordingDto,
        detections: List<DetectionEntity>,
    ): DetectionDelta {
        val mutex = perCameraMutex.computeIfAbsent(recording.camId) { Mutex() }
        return mutex.withLock { evaluateLocked(recording, detections) }
    }

    private suspend fun evaluateLocked(
        recording: RecordingDto,
        detections: List<DetectionEntity>,
    ): DetectionDelta {
        if (detections.isEmpty()) {
            return DetectionDelta(0, 0, 0, emptyList())
        }
        val representatives = BboxClusteringHelper.cluster(detections, properties.innerIou)
        val recordingTimestamp = recording.recordTimestamp
        val ttlThreshold = recordingTimestamp.minus(properties.ttl)
        val active = repository.findActive(recording.camId, ttlThreshold).toMutableList()

        var matched = 0
        val newClasses = mutableListOf<String>()
        for (bbox in representatives) {
            val match = active.firstOrNull { it.matches(bbox, properties.iouThreshold) }
            if (match != null) {
                active.remove(match)
                repository.updateOnMatch(
                    id = match.id!!,
                    x1 = bbox.x1, y1 = bbox.y1, x2 = bbox.x2, y2 = bbox.y2,
                    lastSeenAt = recordingTimestamp,
                    lastRecordingId = recording.id,
                )
                matched++
            } else {
                repository.save(
                    ObjectTrackEntity(
                        id = uuid.generateV1(),
                        creationTimestamp = recordingTimestamp,
                        camId = recording.camId,
                        className = bbox.className,
                        bboxX1 = bbox.x1, bboxY1 = bbox.y1, bboxX2 = bbox.x2, bboxY2 = bbox.y2,
                        lastSeenAt = recordingTimestamp,
                        lastRecordingId = recording.id,
                    ),
                )
                newClasses += bbox.className
            }
        }
        val newCount = newClasses.size
        if (newCount > 0) {
            logger.info {
                "ObjectTracker: cam=${recording.camId} new=$newCount matched=$matched stale=${active.size} " +
                    "(recording=${recording.id})"
            }
        }
        return DetectionDelta(
            newTracksCount = newCount,
            matchedTracksCount = matched,
            staleTracksCount = active.size,
            newClasses = newClasses,
        )
    }

    override suspend fun cleanupExpired(): Long {
        val threshold = Instant.now(clock).minus(properties.cleanupRetention)
        val deleted = repository.deleteExpired(threshold)
        if (deleted > 0) {
            logger.info { "ObjectTracker cleanup: deleted $deleted expired tracks (older than $threshold)" }
        }
        return deleted
    }

    private fun ObjectTrackEntity.matches(bbox: RepresentativeBbox, threshold: Float): Boolean =
        className == bbox.className &&
            IouHelper.iou(bboxX1, bboxY1, bboxX2, bboxY2, bbox.x1, bbox.y1, bbox.x2, bbox.y2) > threshold
}
```

- [ ] **Step 5: Run test to verify it passes**

Dispatch `build-runner`: `./gradlew :frigate-analyzer-service:test --tests "ru.zinin.frigate.analyzer.service.impl.ObjectTrackerServiceImplTest"`.
Expected: PASS (7 tests).

- [ ] **Step 6: Commit**

```bash
git add modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/ObjectTrackerService.kt \
        modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/ObjectTrackerServiceImpl.kt \
        modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/impl/ObjectTrackerServiceImplTest.kt
git commit -m "feat(service): add ObjectTrackerService with IoU tracking and tests"
```

---

## Task 9: `AppSettingsService` interface + impl + tests

**Goal:** Get/set boolean settings backed by `app_settings`. Use a simple in-memory cache with explicit invalidation on writes; lazy load on first read.

**Files:**
- Create: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/AppSettingsService.kt`
- Create test: `modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/impl/AppSettingsServiceImplTest.kt`
- Create: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/AppSettingsServiceImpl.kt`

- [ ] **Step 1: Define the interface**

Create `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/AppSettingsService.kt`:

```kotlin
package ru.zinin.frigate.analyzer.service

interface AppSettingsService {
    suspend fun getBoolean(key: String, default: Boolean = false): Boolean

    suspend fun setBoolean(key: String, value: Boolean, updatedBy: String? = null)
}
```

- [ ] **Step 2: Write the failing test**

Create `modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/impl/AppSettingsServiceImplTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.service.impl

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.model.persistent.AppSettingEntity
import ru.zinin.frigate.analyzer.service.repository.AppSettingRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppSettingsServiceImplTest {
    private val repo = mockk<AppSettingRepository>()
    private val fixed = Instant.parse("2026-04-27T12:00:00Z")
    private val clock = Clock.fixed(fixed, ZoneOffset.UTC)
    private val service = AppSettingsServiceImpl(repo, clock)

    @Test
    fun `getBoolean returns default when key missing`() = runTest {
        coEvery { repo.findBySettingKey("k") } returns null
        assertTrue(service.getBoolean("k", default = true))
        assertFalse(service.getBoolean("k", default = false))
    }

    @Test
    fun `getBoolean parses true and false`() = runTest {
        coEvery { repo.findBySettingKey("k") } returns
            AppSettingEntity("k", "true", fixed, null) andThen
            AppSettingEntity("k", "false", fixed, null)
        // First read populates cache; second read here uses NEW service to bypass cache
        val s1 = AppSettingsServiceImpl(repo, clock)
        val s2 = AppSettingsServiceImpl(repo, clock)
        assertTrue(s1.getBoolean("k", default = false))
        assertFalse(s2.getBoolean("k", default = true))
    }

    @Test
    fun `cache hit avoids repository on second read`() = runTest {
        coEvery { repo.findBySettingKey("k") } returns AppSettingEntity("k", "true", fixed, null)

        service.getBoolean("k")
        service.getBoolean("k")

        coVerify(exactly = 1) { repo.findBySettingKey("k") }
    }

    @Test
    fun `setBoolean upserts and invalidates cache`() = runTest {
        coEvery { repo.findBySettingKey("k") } returns AppSettingEntity("k", "true", fixed, null)
        coEvery { repo.upsert("k", "false", fixed, "alice") } returns 1L

        // populate cache
        service.getBoolean("k")
        // change
        service.setBoolean("k", value = false, updatedBy = "alice")
        // read again — must hit DB because cache invalidated
        coEvery { repo.findBySettingKey("k") } returns AppSettingEntity("k", "false", fixed, "alice")
        val v = service.getBoolean("k", default = true)

        assertFalse(v)
        coVerify(exactly = 1) { repo.upsert("k", "false", fixed, "alice") }
        coVerify(atLeast = 2) { repo.findBySettingKey("k") }
    }

    @Test
    fun `unparseable value falls back to default`() = runTest {
        coEvery { repo.findBySettingKey("k") } returns AppSettingEntity("k", "weird", fixed, null)
        assertTrue(service.getBoolean("k", default = true))
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Dispatch `build-runner`: `./gradlew :frigate-analyzer-service:test --tests "ru.zinin.frigate.analyzer.service.impl.AppSettingsServiceImplTest"`.
Expected: FAIL with "Unresolved reference: AppSettingsServiceImpl".

- [ ] **Step 4: Write the implementation**

Create `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/AppSettingsServiceImpl.kt`:

```kotlin
package ru.zinin.frigate.analyzer.service.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.zinin.frigate.analyzer.service.AppSettingsService
import ru.zinin.frigate.analyzer.service.repository.AppSettingRepository
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

@Service
class AppSettingsServiceImpl(
    private val repository: AppSettingRepository,
    private val clock: Clock,
) : AppSettingsService {
    private val cache = ConcurrentHashMap<String, String>()

    @Transactional(readOnly = true)
    override suspend fun getBoolean(key: String, default: Boolean): Boolean {
        val raw = cache[key] ?: loadAndCache(key)
        if (raw == null) return default
        return raw.toBooleanStrictOrNull() ?: default
    }

    @Transactional
    override suspend fun setBoolean(key: String, value: Boolean, updatedBy: String?) {
        val v = value.toString()
        repository.upsert(key, v, Instant.now(clock), updatedBy)
        cache.remove(key)
        logger.info { "AppSettings: '$key' set to $v by ${updatedBy ?: "<system>"}" }
    }

    private suspend fun loadAndCache(key: String): String? {
        val entity = repository.findBySettingKey(key) ?: return null
        val v = entity.settingValue ?: return null
        cache[key] = v
        return v
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Dispatch `build-runner`: `./gradlew :frigate-analyzer-service:test --tests "ru.zinin.frigate.analyzer.service.impl.AppSettingsServiceImplTest"`.
Expected: PASS (5 tests).

- [ ] **Step 6: Commit**

```bash
git add modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/AppSettingsService.kt \
        modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/AppSettingsServiceImpl.kt \
        modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/impl/AppSettingsServiceImplTest.kt
git commit -m "feat(service): add AppSettingsService with cached get/set and tests"
```

---

## Task 10: `NotificationDecisionService` interface + impl + tests

**Goal:** Orchestrate the decision: short-circuit on no detections, gate on global toggle, run tracker, return verdict. Fail-safe to "do not notify" on tracker errors.

**Files:**
- Create: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/NotificationDecisionService.kt`
- Create test: `modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/impl/NotificationDecisionServiceImplTest.kt`
- Create: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/NotificationDecisionServiceImpl.kt`

- [ ] **Step 1: Define the interface**

Create `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/NotificationDecisionService.kt`:

```kotlin
package ru.zinin.frigate.analyzer.service

import ru.zinin.frigate.analyzer.model.dto.NotificationDecision
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.persistent.DetectionEntity

interface NotificationDecisionService {
    /**
     * Decides whether to notify users about [recording] given its [detections].
     *
     * Order:
     *   1. detections empty → NO_DETECTIONS, no tracker call.
     *   2. global recording toggle off → GLOBAL_OFF, but tracker still runs to keep state coherent.
     *   3. tracker.evaluate → NEW_OBJECTS or ALL_REPEATED.
     *
     * On tracker exceptions: returns shouldNotify=false with TRACKER_ERROR (fail-safe).
     */
    suspend fun evaluate(
        recording: RecordingDto,
        detections: List<DetectionEntity>,
    ): NotificationDecision
}
```

- [ ] **Step 2: Write the failing test**

Create `modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/impl/NotificationDecisionServiceImplTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.service.impl

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.model.dto.DetectionDelta
import ru.zinin.frigate.analyzer.model.dto.NotificationDecisionReason
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.persistent.DetectionEntity
import ru.zinin.frigate.analyzer.service.AppSettingKeys
import ru.zinin.frigate.analyzer.service.AppSettingsService
import ru.zinin.frigate.analyzer.service.ObjectTrackerService
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationDecisionServiceImplTest {
    private val tracker = mockk<ObjectTrackerService>()
    private val settings = mockk<AppSettingsService>()
    private val service = NotificationDecisionServiceImpl(tracker, settings)

    private val now = Instant.parse("2026-04-27T12:00:00Z")
    private val recording: RecordingDto =
        RecordingDto(
            id = UUID.randomUUID(),
            creationTimestamp = now,
            filePath = "/r.mp4",
            fileCreationTimestamp = now,
            camId = "cam",
            recordDate = LocalDate.from(now.atZone(ZoneOffset.UTC)),
            recordTime = LocalTime.from(now.atZone(ZoneOffset.UTC)),
            recordTimestamp = now,
            startProcessingTimestamp = now,
            processTimestamp = now,
            processAttempts = 1,
            detectionsCount = 1,
            analyzeTime = 1,
            analyzedFramesCount = 1,
            errorMessage = null,
        )

    private fun det() = DetectionEntity(
        id = UUID.randomUUID(),
        creationTimestamp = now,
        recordingId = recording.id,
        detectionTimestamp = now,
        frameIndex = 0,
        model = "yolo",
        classId = 0,
        className = "car",
        confidence = 0.9f,
        x1 = 0f, y1 = 0f, x2 = 1f, y2 = 1f,
    )

    @Test
    fun `empty detections short-circuit to NO_DETECTIONS without calling tracker`() = runTest {
        val decision = service.evaluate(recording, emptyList())

        assertFalse(decision.shouldNotify)
        assertEquals(NotificationDecisionReason.NO_DETECTIONS, decision.reason)
        coVerify(exactly = 0) { tracker.evaluate(any(), any()) }
    }

    @Test
    fun `global off keeps tracker running but suppresses notify`() = runTest {
        coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns false
        coEvery { tracker.evaluate(recording, any()) } returns DetectionDelta(1, 0, 0, listOf("car"))

        val decision = service.evaluate(recording, listOf(det()))

        assertFalse(decision.shouldNotify)
        assertEquals(NotificationDecisionReason.GLOBAL_OFF, decision.reason)
        coVerify(exactly = 1) { tracker.evaluate(recording, any()) }
    }

    @Test
    fun `new tracks lead to NEW_OBJECTS and shouldNotify true`() = runTest {
        coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
        coEvery { tracker.evaluate(recording, any()) } returns DetectionDelta(1, 0, 0, listOf("car"))

        val decision = service.evaluate(recording, listOf(det()))

        assertTrue(decision.shouldNotify)
        assertEquals(NotificationDecisionReason.NEW_OBJECTS, decision.reason)
    }

    @Test
    fun `all matched leads to ALL_REPEATED and shouldNotify false`() = runTest {
        coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
        coEvery { tracker.evaluate(recording, any()) } returns DetectionDelta(0, 1, 0, emptyList())

        val decision = service.evaluate(recording, listOf(det()))

        assertFalse(decision.shouldNotify)
        assertEquals(NotificationDecisionReason.ALL_REPEATED, decision.reason)
    }

    @Test
    fun `tracker exception leads to TRACKER_ERROR and shouldNotify false`() = runTest {
        coEvery { settings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
        coEvery { tracker.evaluate(recording, any()) } throws RuntimeException("db down")

        val decision = service.evaluate(recording, listOf(det()))

        assertFalse(decision.shouldNotify)
        assertEquals(NotificationDecisionReason.TRACKER_ERROR, decision.reason)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Dispatch `build-runner`: `./gradlew :frigate-analyzer-service:test --tests "ru.zinin.frigate.analyzer.service.impl.NotificationDecisionServiceImplTest"`.
Expected: FAIL with "Unresolved reference: NotificationDecisionServiceImpl".

- [ ] **Step 4: Write the implementation**

Create `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/NotificationDecisionServiceImpl.kt`:

```kotlin
package ru.zinin.frigate.analyzer.service.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import org.springframework.stereotype.Service
import ru.zinin.frigate.analyzer.model.dto.NotificationDecision
import ru.zinin.frigate.analyzer.model.dto.NotificationDecisionReason
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.persistent.DetectionEntity
import ru.zinin.frigate.analyzer.service.AppSettingKeys
import ru.zinin.frigate.analyzer.service.AppSettingsService
import ru.zinin.frigate.analyzer.service.NotificationDecisionService
import ru.zinin.frigate.analyzer.service.ObjectTrackerService

private val logger = KotlinLogging.logger {}

@Service
class NotificationDecisionServiceImpl(
    private val tracker: ObjectTrackerService,
    private val settings: AppSettingsService,
) : NotificationDecisionService {
    override suspend fun evaluate(
        recording: RecordingDto,
        detections: List<DetectionEntity>,
    ): NotificationDecision {
        if (detections.isEmpty()) {
            return NotificationDecision(false, NotificationDecisionReason.NO_DETECTIONS)
        }

        val globalEnabled = settings.getBoolean(
            AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, default = true,
        )

        return try {
            val delta = tracker.evaluate(recording, detections)
            when {
                !globalEnabled -> {
                    logger.debug { "Decision: suppress (global_off): cam=${recording.camId} recording=${recording.id}" }
                    NotificationDecision(false, NotificationDecisionReason.GLOBAL_OFF, delta)
                }
                delta.newTracksCount > 0 -> {
                    logger.debug {
                        "Decision: notify: cam=${recording.camId} newClasses=${delta.newClasses} recording=${recording.id}"
                    }
                    NotificationDecision(true, NotificationDecisionReason.NEW_OBJECTS, delta)
                }
                else -> {
                    logger.debug { "Decision: suppress (all_repeated): cam=${recording.camId} recording=${recording.id}" }
                    NotificationDecision(false, NotificationDecisionReason.ALL_REPEATED, delta)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) {
                "Tracker failure for recording=${recording.id} cam=${recording.camId}; suppressing notification"
            }
            NotificationDecision(false, NotificationDecisionReason.TRACKER_ERROR)
        }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Dispatch `build-runner`: `./gradlew :frigate-analyzer-service:test --tests "ru.zinin.frigate.analyzer.service.impl.NotificationDecisionServiceImplTest"`.
Expected: PASS (5 tests).

- [ ] **Step 6: Commit**

```bash
git add modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/NotificationDecisionService.kt \
        modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/NotificationDecisionServiceImpl.kt \
        modules/service/src/test/kotlin/ru/zinin/frigate/analyzer/service/impl/NotificationDecisionServiceImplTest.kt
git commit -m "feat(service): add NotificationDecisionService orchestrating tracker + settings"
```

---

## Task 11: `ObjectTracksCleanupTask` (`@Scheduled`)

**Goal:** Periodic background cleanup of expired tracks. Lives in `core` because that's where existing scheduled tasks (`WatchRecordsTask`, signal-loss detector) live.

**Files:**
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/ObjectTracksCleanupTask.kt`

- [ ] **Step 1: Create the task**

Create `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/ObjectTracksCleanupTask.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.task

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.service.ObjectTrackerService

private val logger = KotlinLogging.logger {}

@Component
class ObjectTracksCleanupTask(
    private val tracker: ObjectTrackerService,
) {
    /** Cleanup interval is read from properties (ISO-8601 / simple Duration); default PT1H. */
    @Scheduled(fixedDelayString = "\${application.notifications.tracker.cleanup-interval:PT1H}")
    fun cleanup() {
        try {
            runBlocking {
                tracker.cleanupExpired()
            }
        } catch (e: Exception) {
            logger.warn(e) { "ObjectTracker cleanup task failed" }
        }
    }
}
```

- [ ] **Step 2: Verify @Scheduled is enabled in core**

Search for an existing `@EnableScheduling` annotation in core:

Run: `grep -rn "EnableScheduling" modules/core/src/main/kotlin/`

Expected: at least one match (the project already uses `@Scheduled` for `WatchRecordsTask`). If none, add `@EnableScheduling` to the main configuration class. Otherwise no action needed.

- [ ] **Step 3: Verify compilation**

Dispatch `build-runner`: `./gradlew :frigate-analyzer-core:compileKotlin`.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/task/ObjectTracksCleanupTask.kt
git commit -m "feat(core): add ObjectTracksCleanupTask scheduled job"
```

---

## Task 12: Telegram user model extensions (entity, DTO, UserZoneInfo, repo, service)

**Goal:** Add `notificationsRecordingEnabled` and `notificationsSignalEnabled` flags through the Telegram-user data path. Service gains four toggle methods and getters.

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/entity/TelegramUserEntity.kt`
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/dto/TelegramUserDto.kt`
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/dto/UserZoneInfo.kt`
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/repository/TelegramUserRepository.kt`
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/TelegramUserService.kt`
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramUserServiceImpl.kt`

- [ ] **Step 1: Extend `TelegramUserEntity`**

Edit `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/entity/TelegramUserEntity.kt`. After the `languageCode` line add:

```kotlin
    @Column("notifications_recording_enabled")
    var notificationsRecordingEnabled: Boolean = true,
    @Column("notifications_signal_enabled")
    var notificationsSignalEnabled: Boolean = true,
```

- [ ] **Step 2: Extend `TelegramUserDto`**

Edit `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/dto/TelegramUserDto.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.dto

import ru.zinin.frigate.analyzer.telegram.model.UserStatus
import java.time.Instant
import java.util.UUID

data class TelegramUserDto(
    val id: UUID,
    val username: String,
    val chatId: Long?,
    val userId: Long?,
    val firstName: String?,
    val lastName: String?,
    val status: UserStatus,
    val creationTimestamp: Instant,
    val activationTimestamp: Instant?,
    val languageCode: String? = null,
    val notificationsRecordingEnabled: Boolean = true,
    val notificationsSignalEnabled: Boolean = true,
)
```

- [ ] **Step 3: Extend `UserZoneInfo`**

Edit `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/dto/UserZoneInfo.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.dto

import java.time.ZoneId

data class UserZoneInfo(
    val chatId: Long,
    val zone: ZoneId,
    val language: String? = null,
    val notificationsRecordingEnabled: Boolean = true,
    val notificationsSignalEnabled: Boolean = true,
)
```

- [ ] **Step 4: Add repository update queries**

Edit `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/repository/TelegramUserRepository.kt`. Append before the closing brace:

```kotlin
    @Modifying
    @Query("UPDATE telegram_users SET notifications_recording_enabled = :enabled WHERE chat_id = :chatId")
    suspend fun updateNotificationsRecordingEnabled(
        @Param("chatId") chatId: Long,
        @Param("enabled") enabled: Boolean,
    ): Long

    @Modifying
    @Query("UPDATE telegram_users SET notifications_signal_enabled = :enabled WHERE chat_id = :chatId")
    suspend fun updateNotificationsSignalEnabled(
        @Param("chatId") chatId: Long,
        @Param("enabled") enabled: Boolean,
    ): Long
```

- [ ] **Step 5: Extend `TelegramUserService` interface**

Edit `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/TelegramUserService.kt`. Append before the closing brace:

```kotlin
    suspend fun updateNotificationsRecordingEnabled(
        chatId: Long,
        enabled: Boolean,
    ): Boolean

    suspend fun updateNotificationsSignalEnabled(
        chatId: Long,
        enabled: Boolean,
    ): Boolean
```

- [ ] **Step 6: Implement in `TelegramUserServiceImpl`**

Edit `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramUserServiceImpl.kt`. Inside the class, after `updateLanguage`, add:

```kotlin
    @Transactional
    override suspend fun updateNotificationsRecordingEnabled(
        chatId: Long,
        enabled: Boolean,
    ): Boolean {
        val updated = repository.updateNotificationsRecordingEnabled(chatId, enabled)
        if (updated == 0L) {
            logger.warn { "updateNotificationsRecordingEnabled: no rows updated for chatId=$chatId" }
            return false
        }
        logger.info { "Updated notifications.recording=$enabled for chatId=$chatId" }
        return true
    }

    @Transactional
    override suspend fun updateNotificationsSignalEnabled(
        chatId: Long,
        enabled: Boolean,
    ): Boolean {
        val updated = repository.updateNotificationsSignalEnabled(chatId, enabled)
        if (updated == 0L) {
            logger.warn { "updateNotificationsSignalEnabled: no rows updated for chatId=$chatId" }
            return false
        }
        logger.info { "Updated notifications.signal=$enabled for chatId=$chatId" }
        return true
    }
```

Update the `toDto` extension function inside the same file to populate the new fields. Replace the existing `private fun TelegramUserEntity.toDto()` body with:

```kotlin
    private fun TelegramUserEntity.toDto(): TelegramUserDto =
        TelegramUserDto(
            id = id!!,
            username = username!!,
            chatId = chatId,
            userId = userId,
            firstName = firstName,
            lastName = lastName,
            status = UserStatus.valueOf(status!!),
            creationTimestamp = creationTimestamp!!,
            activationTimestamp = activationTimestamp,
            languageCode = languageCode,
            notificationsRecordingEnabled = notificationsRecordingEnabled,
            notificationsSignalEnabled = notificationsSignalEnabled,
        )
```

Update `getAuthorizedUsersWithZones` to include the new flags. Replace its body with:

```kotlin
    @Transactional(readOnly = true)
    override suspend fun getAuthorizedUsersWithZones(): List<UserZoneInfo> =
        repository
            .findAllByStatus(UserStatus.ACTIVE.name)
            .filter { it.chatId != null }
            .map { user ->
                val zone =
                    try {
                        ZoneId.of(user.olsonCode ?: "UTC")
                    } catch (e: DateTimeException) {
                        logger.warn { "Invalid olson_code='${user.olsonCode}' for chatId=${user.chatId}, falling back to UTC" }
                        ZoneId.of("UTC")
                    }
                UserZoneInfo(
                    chatId = user.chatId!!,
                    zone = zone,
                    language = user.languageCode,
                    notificationsRecordingEnabled = user.notificationsRecordingEnabled,
                    notificationsSignalEnabled = user.notificationsSignalEnabled,
                )
            }
```

- [ ] **Step 7: Verify compilation and existing tests still pass**

Dispatch `build-runner`: `./gradlew :frigate-analyzer-telegram:test`.
Expected: BUILD SUCCESSFUL — existing tests pass (defaults preserve current behaviour).
If a test fails because it constructs `TelegramUserDto` or `UserZoneInfo` positionally with extra fields breaking it, fix the test by using the new defaults explicitly.

- [ ] **Step 8: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/entity/TelegramUserEntity.kt \
        modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/dto/TelegramUserDto.kt \
        modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/dto/UserZoneInfo.kt \
        modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/repository/TelegramUserRepository.kt \
        modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/TelegramUserService.kt \
        modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramUserServiceImpl.kt
git commit -m "feat(telegram): add per-user notification flags through entity/DTO/service"
```

---

## Task 13: `TelegramNotificationServiceImpl` global gate + recipient filtering (both flows)

**Goal:** Apply global toggles and per-user filtering inside the existing `sendRecordingNotification` and `sendCameraSignalLost`/`sendCameraSignalRecovered` flows.

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImpl.kt`
- Modify test: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImplTest.kt`
- Modify test: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImplSignalLossTest.kt`

- [ ] **Step 1: Inject `AppSettingsService`**

Edit `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImpl.kt`. Add the import:

```kotlin
import ru.zinin.frigate.analyzer.service.AppSettingKeys
import ru.zinin.frigate.analyzer.service.AppSettingsService
```

Add to the constructor parameter list (after `rateLimiterProvider`):

```kotlin
    private val appSettings: AppSettingsService,
```

- [ ] **Step 2: Add the global+per-user gate to `sendRecordingNotification`**

In the same file, modify `sendRecordingNotification`. After the existing early returns for `detectionsCount == 0` and empty `usersWithZones`, insert:

```kotlin
        if (!appSettings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, default = true)) {
            logger.debug { "Recording notifications globally disabled — skipping for ${recording.filePath}" }
            return
        }

        val recipients = usersWithZones.filter { it.notificationsRecordingEnabled }
        if (recipients.isEmpty()) {
            logger.debug { "No subscribers with recording-notifications enabled" }
            return
        }
```

Replace the existing `usersWithZones.forEach` with `recipients.forEach`.

- [ ] **Step 3: Apply analogous gate to signal-loss/recovery**

In the same file, locate `sendCameraSignalLost`. After the existing `if (usersWithZones.isEmpty())` early return, insert:

```kotlin
        if (!appSettings.getBoolean(AppSettingKeys.NOTIFICATIONS_SIGNAL_GLOBAL_ENABLED, default = true)) {
            logger.debug { "Signal-loss notifications globally disabled — skipping cam=$camId" }
            return
        }
        val recipients = usersWithZones.filter { it.notificationsSignalEnabled }
        if (recipients.isEmpty()) {
            logger.debug { "No subscribers with signal-loss notifications enabled (cam=$camId)" }
            return
        }
```

Replace `usersWithZones.forEach` with `recipients.forEach` in that method.

Repeat for `sendCameraSignalRecovered` (same pattern, same flag, swap log strings to "recovery").

- [ ] **Step 4: Update existing test setup**

Edit `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImplTest.kt`. Add to the imports:

```kotlin
import ru.zinin.frigate.analyzer.service.AppSettingKeys
import ru.zinin.frigate.analyzer.service.AppSettingsService
```

Add a mocked `appSettings` field after `rateLimiterProvider`:

```kotlin
    private val appSettings = mockk<AppSettingsService>()
```

Update the service-under-test instantiation to include `appSettings` as the last constructor argument.

In the `init` (or just-above field for default behavior), add:

```kotlin
    init {
        coEvery { appSettings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
    }
```

(Place the `init` block after field declarations.)

- [ ] **Step 5: Add new test cases**

Append two test methods to `TelegramNotificationServiceImplTest`:

```kotlin
    @Test
    fun `global recording toggle off skips notification entirely`() = runTest {
        coEvery { appSettings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns false
        coEvery { userService.getAuthorizedUsersWithZones() } returns
            listOf(UserZoneInfo(chatId, ZoneId.of("UTC"), "en"))

        service.sendRecordingNotification(createRecording(detectionsCount = 1), emptyList())

        coVerify(exactly = 0) { notificationQueue.enqueue(any()) }
    }

    @Test
    fun `user with notificationsRecordingEnabled false is filtered out`() = runTest {
        coEvery { appSettings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true
        every { uuidGeneratorHelper.generateV1() } returns taskId
        coEvery { userService.getAuthorizedUsersWithZones() } returns listOf(
            UserZoneInfo(111L, ZoneId.of("UTC"), "en", notificationsRecordingEnabled = false),
            UserZoneInfo(222L, ZoneId.of("UTC"), "en", notificationsRecordingEnabled = true),
        )

        val captured = slot<RecordingNotificationTask>()
        coEvery { notificationQueue.enqueue(capture(captured)) } returns Unit

        service.sendRecordingNotification(createRecording(detectionsCount = 1), emptyList())

        coVerify(exactly = 1) { notificationQueue.enqueue(any()) }
        assertEquals(222L, captured.captured.chatId)
    }
```

- [ ] **Step 6: Same wiring for signal-loss test**

Edit `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImplSignalLossTest.kt`. Add the same imports, the `appSettings = mockk<...>()`, the constructor argument, and an `init {}` block setting `NOTIFICATIONS_SIGNAL_GLOBAL_ENABLED` to `true` by default.

Append two cases:

```kotlin
    @Test
    fun `global signal toggle off skips signal-loss alert`() = runTest {
        coEvery { appSettings.getBoolean(AppSettingKeys.NOTIFICATIONS_SIGNAL_GLOBAL_ENABLED, true) } returns false
        coEvery { userService.getAuthorizedUsersWithZones() } returns
            listOf(UserZoneInfo(123L, ZoneId.of("UTC"), "en"))

        service.sendCameraSignalLost("cam", Instant.parse("2026-04-27T11:00:00Z"), Instant.parse("2026-04-27T12:00:00Z"))

        coVerify(exactly = 0) { notificationQueue.enqueue(any()) }
    }

    @Test
    fun `user with notificationsSignalEnabled false is filtered out`() = runTest {
        coEvery { appSettings.getBoolean(AppSettingKeys.NOTIFICATIONS_SIGNAL_GLOBAL_ENABLED, true) } returns true
        every { uuidGeneratorHelper.generateV1() } returns UUID.randomUUID()
        coEvery { userService.getAuthorizedUsersWithZones() } returns listOf(
            UserZoneInfo(111L, ZoneId.of("UTC"), "en", notificationsSignalEnabled = false),
            UserZoneInfo(222L, ZoneId.of("UTC"), "en", notificationsSignalEnabled = true),
        )

        val captured = slot<SimpleTextNotificationTask>()
        coEvery { notificationQueue.enqueue(capture(captured)) } returns Unit

        service.sendCameraSignalLost("cam", Instant.parse("2026-04-27T11:00:00Z"), Instant.parse("2026-04-27T12:00:00Z"))

        coVerify(exactly = 1) { notificationQueue.enqueue(any()) }
        assertEquals(222L, captured.captured.chatId)
    }
```

(Adjust imports for `SimpleTextNotificationTask`, `UUID`.)

- [ ] **Step 7: Run all telegram tests**

Dispatch `build-runner`: `./gradlew :frigate-analyzer-telegram:test`.
Expected: PASS — including the new and modified cases.

- [ ] **Step 8: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImpl.kt \
        modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImplTest.kt \
        modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImplSignalLossTest.kt
git commit -m "feat(telegram): gate recording and signal-loss flows on global + per-user flags"
```

---

## Task 14: i18n message keys

**Goal:** Add Russian and English strings for the `/notifications` dialog.

**Files:**
- Modify: `modules/telegram/src/main/resources/messages_ru.properties`
- Modify: `modules/telegram/src/main/resources/messages_en.properties`

- [ ] **Step 1: Append to `messages_en.properties`**

Edit `modules/telegram/src/main/resources/messages_en.properties`. Append at end:

```properties
# /notifications
command.notifications.description=Notification settings
notifications.settings.title=🔔 Notification settings
notifications.settings.recording.label=📹 Recording detections
notifications.settings.signal.label=⚠️ Camera alerts
notifications.settings.state.on=ON
notifications.settings.state.off=OFF
notifications.settings.user.suffix=mine
notifications.settings.global.suffix=global
notifications.settings.button.toggle.recording.user.enable=📹 Enable detections
notifications.settings.button.toggle.recording.user.disable=📹 Disable detections
notifications.settings.button.toggle.signal.user.enable=⚠️ Enable alerts
notifications.settings.button.toggle.signal.user.disable=⚠️ Disable alerts
notifications.settings.button.toggle.recording.global.enable=🌐 Enable detections globally
notifications.settings.button.toggle.recording.global.disable=🌐 Disable detections globally
notifications.settings.button.toggle.signal.global.enable=🌐 Enable alerts globally
notifications.settings.button.toggle.signal.global.disable=🌐 Disable alerts globally
notifications.settings.button.close=✖ Close
notifications.settings.line.format={0}: {1}
notifications.settings.line.owner.format={0}: {1} ({2}) | {3} ({4})
```

- [ ] **Step 2: Append to `messages_ru.properties`**

Edit `modules/telegram/src/main/resources/messages_ru.properties`. Append at end:

```properties
# /notifications
command.notifications.description=Настройки уведомлений
notifications.settings.title=🔔 Настройки уведомлений
notifications.settings.recording.label=📹 Уведомления о детектах
notifications.settings.signal.label=⚠️ Алерты о камерах
notifications.settings.state.on=ВКЛ
notifications.settings.state.off=ВЫКЛ
notifications.settings.user.suffix=моё
notifications.settings.global.suffix=глобально
notifications.settings.button.toggle.recording.user.enable=📹 Включить детекты
notifications.settings.button.toggle.recording.user.disable=📹 Выключить детекты
notifications.settings.button.toggle.signal.user.enable=⚠️ Включить алерты
notifications.settings.button.toggle.signal.user.disable=⚠️ Выключить алерты
notifications.settings.button.toggle.recording.global.enable=🌐 Включить детекты для всех
notifications.settings.button.toggle.recording.global.disable=🌐 Выключить детекты для всех
notifications.settings.button.toggle.signal.global.enable=🌐 Включить алерты для всех
notifications.settings.button.toggle.signal.global.disable=🌐 Выключить алерты для всех
notifications.settings.button.close=✖ Закрыть
notifications.settings.line.format={0}: {1}
notifications.settings.line.owner.format={0}: {1} ({2}) | {3} ({4})
```

- [ ] **Step 3: Commit**

```bash
git add modules/telegram/src/main/resources/messages_ru.properties \
        modules/telegram/src/main/resources/messages_en.properties
git commit -m "i18n(telegram): add /notifications dialog strings (ru, en)"
```

---

## Task 15: `NotificationsMessageRenderer` + tests

**Goal:** Pure renderer that produces the dialog text and inline keyboard for both USER and OWNER variants. Single source of truth for layout.

**Files:**
- Create test: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/notifications/NotificationsMessageRendererTest.kt`
- Create: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/notifications/NotificationsMessageRenderer.kt`

- [ ] **Step 1: Define the data shape**

In the new package, the renderer takes a small data record. We'll declare it inside the renderer file:

```kotlin
data class NotificationsViewState(
    val isOwner: Boolean,
    val recordingUserEnabled: Boolean,
    val signalUserEnabled: Boolean,
    val recordingGlobalEnabled: Boolean,
    val signalGlobalEnabled: Boolean,
    val language: String,
)

data class RenderedNotifications(
    val text: String,
    val keyboard: InlineKeyboardMarkup,
)
```

- [ ] **Step 2: Write the failing test**

Create `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/notifications/NotificationsMessageRendererTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.bot.handler.notifications

import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import org.junit.jupiter.api.Test
import org.springframework.context.support.ReloadableResourceBundleMessageSource
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationsMessageRendererTest {
    private val msg = MessageResolver(
        ReloadableResourceBundleMessageSource().apply {
            setBasename("classpath:messages")
            setDefaultEncoding("UTF-8")
            setFallbackToSystemLocale(false)
            setDefaultLocale(Locale.forLanguageTag("en"))
        },
    )
    private val renderer = NotificationsMessageRenderer(msg)

    @Test
    fun `user variant has 3 rows (recording, signal, close)`() {
        val rendered = renderer.render(
            NotificationsViewState(
                isOwner = false,
                recordingUserEnabled = true,
                signalUserEnabled = true,
                recordingGlobalEnabled = true,
                signalGlobalEnabled = true,
                language = "en",
            ),
        )
        assertEquals(3, rendered.keyboard.keyboard.size)
    }

    @Test
    fun `owner variant has 5 rows (recording user, signal user, recording global, signal global, close)`() {
        val rendered = renderer.render(
            NotificationsViewState(
                isOwner = true,
                recordingUserEnabled = true,
                signalUserEnabled = true,
                recordingGlobalEnabled = true,
                signalGlobalEnabled = true,
                language = "en",
            ),
        )
        assertEquals(5, rendered.keyboard.keyboard.size)
    }

    @Test
    fun `disabled per-user toggle button shows enable label`() {
        val rendered = renderer.render(
            NotificationsViewState(
                isOwner = false,
                recordingUserEnabled = false,
                signalUserEnabled = true,
                recordingGlobalEnabled = true,
                signalGlobalEnabled = true,
                language = "en",
            ),
        )
        val recordingBtn = rendered.keyboard.keyboard[0][0] as CallbackDataInlineKeyboardButton
        assertTrue(recordingBtn.text.contains("Enable"), "button=${recordingBtn.text}")
        assertEquals("nfs:u:rec:tgl", recordingBtn.callbackData)
    }

    @Test
    fun `text mentions both ON state for user when both enabled`() {
        val rendered = renderer.render(
            NotificationsViewState(
                isOwner = false,
                recordingUserEnabled = true,
                signalUserEnabled = true,
                recordingGlobalEnabled = true,
                signalGlobalEnabled = true,
                language = "en",
            ),
        )
        // English ON = "ON", appears at least twice (once per line)
        val onCount = "ON".toRegex().findAll(rendered.text).count()
        assertTrue(onCount >= 2, "ON count=$onCount in text=${rendered.text}")
    }

    @Test
    fun `russian language uses russian state words`() {
        val rendered = renderer.render(
            NotificationsViewState(
                isOwner = false,
                recordingUserEnabled = true,
                signalUserEnabled = true,
                recordingGlobalEnabled = true,
                signalGlobalEnabled = true,
                language = "ru",
            ),
        )
        assertTrue(rendered.text.contains("ВКЛ"))
    }

    @Test
    fun `unknown language falls back to english strings`() {
        val rendered = renderer.render(
            NotificationsViewState(
                isOwner = false,
                recordingUserEnabled = true,
                signalUserEnabled = true,
                recordingGlobalEnabled = true,
                signalGlobalEnabled = true,
                language = "fr",
            ),
        )
        assertTrue(rendered.text.contains("ON"))
    }

    @Test
    fun `close button always present as last row with single button`() {
        val rendered = renderer.render(
            NotificationsViewState(
                isOwner = true,
                recordingUserEnabled = true,
                signalUserEnabled = true,
                recordingGlobalEnabled = true,
                signalGlobalEnabled = true,
                language = "en",
            ),
        )
        val lastRow = rendered.keyboard.keyboard.last()
        assertEquals(1, lastRow.size)
        val btn = lastRow[0] as CallbackDataInlineKeyboardButton
        assertEquals("nfs:close", btn.callbackData)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Dispatch `build-runner`: `./gradlew :frigate-analyzer-telegram:test --tests "ru.zinin.frigate.analyzer.telegram.bot.handler.notifications.NotificationsMessageRendererTest"`.
Expected: FAIL with "Unresolved reference: NotificationsMessageRenderer".

- [ ] **Step 4: Write the implementation**

Create `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/notifications/NotificationsMessageRenderer.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.bot.handler.notifications

import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.utils.matrix
import dev.inmo.tgbotapi.utils.row
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver

data class NotificationsViewState(
    val isOwner: Boolean,
    val recordingUserEnabled: Boolean,
    val signalUserEnabled: Boolean,
    val recordingGlobalEnabled: Boolean,
    val signalGlobalEnabled: Boolean,
    val language: String,
)

data class RenderedNotifications(
    val text: String,
    val keyboard: InlineKeyboardMarkup,
)

@Component
class NotificationsMessageRenderer(
    private val msg: MessageResolver,
) {
    fun render(state: NotificationsViewState): RenderedNotifications {
        val text = renderText(state)
        val keyboard = renderKeyboard(state)
        return RenderedNotifications(text, keyboard)
    }

    private fun renderText(state: NotificationsViewState): String {
        val lang = state.language
        val on = msg.get("notifications.settings.state.on", lang)
        val off = msg.get("notifications.settings.state.off", lang)
        val user = msg.get("notifications.settings.user.suffix", lang)
        val global = msg.get("notifications.settings.global.suffix", lang)

        val recordingLabel = msg.get("notifications.settings.recording.label", lang)
        val signalLabel = msg.get("notifications.settings.signal.label", lang)

        val recordingLine =
            if (state.isOwner) {
                msg.get(
                    "notifications.settings.line.owner.format", lang,
                    recordingLabel,
                    if (state.recordingUserEnabled) on else off, user,
                    if (state.recordingGlobalEnabled) on else off, global,
                )
            } else {
                msg.get(
                    "notifications.settings.line.format", lang,
                    recordingLabel,
                    if (state.recordingUserEnabled) on else off,
                )
            }

        val signalLine =
            if (state.isOwner) {
                msg.get(
                    "notifications.settings.line.owner.format", lang,
                    signalLabel,
                    if (state.signalUserEnabled) on else off, user,
                    if (state.signalGlobalEnabled) on else off, global,
                )
            } else {
                msg.get(
                    "notifications.settings.line.format", lang,
                    signalLabel,
                    if (state.signalUserEnabled) on else off,
                )
            }

        return buildString {
            appendLine(msg.get("notifications.settings.title", lang))
            appendLine()
            appendLine(recordingLine)
            appendLine(signalLine)
        }
    }

    private fun renderKeyboard(state: NotificationsViewState): InlineKeyboardMarkup {
        val lang = state.language
        val close = msg.get("notifications.settings.button.close", lang)

        return InlineKeyboardMarkup(
            keyboard = matrix {
                row {
                    +CallbackDataInlineKeyboardButton(
                        msg.get(toggleKey("recording", "user", state.recordingUserEnabled), lang),
                        "nfs:u:rec:tgl",
                    )
                }
                row {
                    +CallbackDataInlineKeyboardButton(
                        msg.get(toggleKey("signal", "user", state.signalUserEnabled), lang),
                        "nfs:u:sig:tgl",
                    )
                }
                if (state.isOwner) {
                    row {
                        +CallbackDataInlineKeyboardButton(
                            msg.get(toggleKey("recording", "global", state.recordingGlobalEnabled), lang),
                            "nfs:g:rec:tgl",
                        )
                    }
                    row {
                        +CallbackDataInlineKeyboardButton(
                            msg.get(toggleKey("signal", "global", state.signalGlobalEnabled), lang),
                            "nfs:g:sig:tgl",
                        )
                    }
                }
                row {
                    +CallbackDataInlineKeyboardButton(close, "nfs:close")
                }
            },
        )
    }

    private fun toggleKey(stream: String, scope: String, currentlyEnabled: Boolean): String {
        val action = if (currentlyEnabled) "disable" else "enable"
        return "notifications.settings.button.toggle.$stream.$scope.$action"
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Dispatch `build-runner`: `./gradlew :frigate-analyzer-telegram:test --tests "ru.zinin.frigate.analyzer.telegram.bot.handler.notifications.NotificationsMessageRendererTest"`.
Expected: PASS (7 tests).

- [ ] **Step 6: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/notifications/NotificationsMessageRenderer.kt \
        modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/notifications/NotificationsMessageRendererTest.kt
git commit -m "feat(telegram): add NotificationsMessageRenderer with tests"
```

---

## Task 16: `NotificationsCommandHandler`

**Goal:** Handler for `/notifications` that pulls current state and sends the initial dialog message. Adds `isOwner` helper to `TelegramUserService` first so the handler can render the OWNER variant correctly.

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/TelegramUserService.kt`
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramUserServiceImpl.kt`
- Create: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/notifications/NotificationsCommandHandler.kt`

- [ ] **Step 1: Pick the next available `order`**

Run: `grep -n "override val order" modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/*CommandHandler.kt`

Identify the largest existing `order` and pick the next integer. The plan assumes `order = 6` — adjust if the codebase has shifted.

- [ ] **Step 2: Add `isOwner` helper to `TelegramUserService`**

Run: `grep -n "isOwner" modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/`

If a method already exists, skip this step. Otherwise:

In `TelegramUserService.kt` append before the closing brace:

```kotlin
    fun isOwner(username: String): Boolean
```

Edit `TelegramUserServiceImpl.kt`. Inject `TelegramProperties` into the constructor if not already present:

```kotlin
class TelegramUserServiceImpl(
    private val repository: TelegramUserRepository,
    private val uuidGeneratorHelper: UUIDGeneratorHelper,
    private val clock: Clock,
    private val telegramProperties: TelegramProperties,
) : TelegramUserService {
```

Add inside the class:

```kotlin
    override fun isOwner(username: String): Boolean =
        username.equals(telegramProperties.owner, ignoreCase = true)
```

- [ ] **Step 3: Implement the handler**

Create `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/notifications/NotificationsCommandHandler.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.bot.handler.notifications

import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.service.AppSettingKeys
import ru.zinin.frigate.analyzer.service.AppSettingsService
import ru.zinin.frigate.analyzer.telegram.bot.handler.CommandHandler
import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto
import ru.zinin.frigate.analyzer.telegram.model.UserRole
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class NotificationsCommandHandler(
    private val userService: TelegramUserService,
    private val appSettings: AppSettingsService,
    private val renderer: NotificationsMessageRenderer,
) : CommandHandler {
    override val command: String = "notifications"
    override val requiredRole: UserRole = UserRole.USER
    override val order: Int = 6

    override suspend fun BehaviourContext.handle(
        message: CommonMessage<TextContent>,
        user: TelegramUserDto?,
    ) {
        if (user == null) return
        val chatId = message.chat.id
        val isOwner = userService.isOwner(user.username)

        val state = NotificationsViewState(
            isOwner = isOwner,
            recordingUserEnabled = user.notificationsRecordingEnabled,
            signalUserEnabled = user.notificationsSignalEnabled,
            recordingGlobalEnabled = appSettings.getBoolean(
                AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true,
            ),
            signalGlobalEnabled = appSettings.getBoolean(
                AppSettingKeys.NOTIFICATIONS_SIGNAL_GLOBAL_ENABLED, true,
            ),
            language = user.languageCode ?: "en",
        )

        val rendered = renderer.render(state)
        sendTextMessage(chatId, rendered.text, replyMarkup = rendered.keyboard)
    }
}
```

- [ ] **Step 4: Verify compilation and existing tests still pass**

Dispatch `build-runner`: `./gradlew :frigate-analyzer-telegram:test`.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/notifications/NotificationsCommandHandler.kt \
        modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/TelegramUserService.kt \
        modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramUserServiceImpl.kt
git commit -m "feat(telegram): add /notifications command handler and isOwner helper"
```

---

## Task 17: `NotificationsSettingsCallbackHandler` + tests

**Goal:** Handle `nfs:*` callbacks: read current state, mutate per-user or global flag, re-render the same message. Adds `findByChatIdAsDto` to `TelegramUserService` first so the dispatch can read current user state.

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/TelegramUserService.kt`
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramUserServiceImpl.kt`
- Create test: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/notifications/NotificationsSettingsCallbackHandlerTest.kt`
- Create: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/notifications/NotificationsSettingsCallbackHandler.kt`

- [ ] **Step 1: Inspect an existing callback handler**

Run: `grep -rn "DataCallbackQuery\|onDataCallbackQuery\|CallbackHandler" modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/ | head -20`

Read `QuickExportHandler.kt` and any cancel/registration code to understand the project's callback registration pattern. Mirror it.

- [ ] **Step 2: Add `findByChatIdAsDto` to `TelegramUserService`**

Run: `grep -n "findByChatId" modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/`

If only the repository-level `findByChatId` exists, add to `TelegramUserService` interface:

```kotlin
    suspend fun findByChatIdAsDto(chatId: Long): TelegramUserDto?
```

And implement in `TelegramUserServiceImpl`:

```kotlin
    @Transactional(readOnly = true)
    override suspend fun findByChatIdAsDto(chatId: Long): TelegramUserDto? =
        repository.findByChatId(chatId)?.toDto()
```

- [ ] **Step 3: Write the failing test**

The handler is mostly orchestration: parse callback, check role, mutate, re-render. We test the pure decision/dispatch portion via a method `dispatch(callbackData, callerChatId, isOwner): DispatchResult` extracted for testability. The bot-side wiring (Telegram client, edit message) we verify with integration-style mock + spy.

Create `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/notifications/NotificationsSettingsCallbackHandlerTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.bot.handler.notifications

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.service.AppSettingKeys
import ru.zinin.frigate.analyzer.service.AppSettingsService
import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto
import ru.zinin.frigate.analyzer.telegram.model.UserStatus
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class NotificationsSettingsCallbackHandlerTest {
    private val userService = mockk<TelegramUserService>(relaxed = true)
    private val appSettings = mockk<AppSettingsService>(relaxed = true)

    private val handler = NotificationsSettingsCallbackHandler(
        userService = userService,
        appSettings = appSettings,
        renderer = mockk(relaxed = true),
    )

    private val chatId = 100L

    private fun user(
        recording: Boolean = true,
        signal: Boolean = true,
    ): TelegramUserDto = TelegramUserDto(
        id = UUID.randomUUID(),
        username = "alice",
        chatId = chatId,
        userId = 1L,
        firstName = null, lastName = null,
        status = UserStatus.ACTIVE,
        creationTimestamp = Instant.EPOCH,
        activationTimestamp = Instant.EPOCH,
        languageCode = "en",
        notificationsRecordingEnabled = recording,
        notificationsSignalEnabled = signal,
    )

    @Test
    fun `nfs u rec tgl flips per-user recording flag`() = runTest {
        val current = user(recording = true)
        coEvery { userService.findByChatIdAsDto(chatId) } returns current

        val result = handler.dispatch("nfs:u:rec:tgl", chatId, isOwner = false, current)

        coVerify(exactly = 1) { userService.updateNotificationsRecordingEnabled(chatId, false) }
        assertEquals(NotificationsSettingsCallbackHandler.DispatchOutcome.RERENDER, result)
    }

    @Test
    fun `nfs u sig tgl flips per-user signal flag`() = runTest {
        val current = user(signal = false)
        val result = handler.dispatch("nfs:u:sig:tgl", chatId, isOwner = false, current)

        coVerify(exactly = 1) { userService.updateNotificationsSignalEnabled(chatId, true) }
        assertEquals(NotificationsSettingsCallbackHandler.DispatchOutcome.RERENDER, result)
    }

    @Test
    fun `nfs g rec tgl rejected for non-owner`() = runTest {
        val current = user()
        val result = handler.dispatch("nfs:g:rec:tgl", chatId, isOwner = false, current)

        coVerify(exactly = 0) {
            appSettings.setBoolean(any(), any(), any())
        }
        assertEquals(NotificationsSettingsCallbackHandler.DispatchOutcome.UNAUTHORIZED, result)
    }

    @Test
    fun `nfs g rec tgl flips global recording flag for owner`() = runTest {
        val current = user()
        coEvery { appSettings.getBoolean(AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true) } returns true

        val result = handler.dispatch("nfs:g:rec:tgl", chatId, isOwner = true, current)

        coVerify(exactly = 1) {
            appSettings.setBoolean(
                AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED,
                false,
                "alice",
            )
        }
        assertEquals(NotificationsSettingsCallbackHandler.DispatchOutcome.RERENDER, result)
    }

    @Test
    fun `nfs g sig tgl flips global signal flag for owner`() = runTest {
        val current = user()
        coEvery { appSettings.getBoolean(AppSettingKeys.NOTIFICATIONS_SIGNAL_GLOBAL_ENABLED, true) } returns false

        val result = handler.dispatch("nfs:g:sig:tgl", chatId, isOwner = true, current)

        coVerify(exactly = 1) {
            appSettings.setBoolean(
                AppSettingKeys.NOTIFICATIONS_SIGNAL_GLOBAL_ENABLED,
                true,
                "alice",
            )
        }
        assertEquals(NotificationsSettingsCallbackHandler.DispatchOutcome.RERENDER, result)
    }

    @Test
    fun `nfs close returns CLOSE`() = runTest {
        val result = handler.dispatch("nfs:close", chatId, isOwner = false, user())
        assertEquals(NotificationsSettingsCallbackHandler.DispatchOutcome.CLOSE, result)
    }

    @Test
    fun `unknown nfs callback returns IGNORE`() = runTest {
        val result = handler.dispatch("nfs:unknown", chatId, isOwner = true, user())
        assertEquals(NotificationsSettingsCallbackHandler.DispatchOutcome.IGNORE, result)
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Dispatch `build-runner`: `./gradlew :frigate-analyzer-telegram:test --tests "ru.zinin.frigate.analyzer.telegram.bot.handler.notifications.NotificationsSettingsCallbackHandlerTest"`.
Expected: FAIL with "Unresolved reference: NotificationsSettingsCallbackHandler".

- [ ] **Step 5: Write the implementation**

Create `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/notifications/NotificationsSettingsCallbackHandler.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.bot.handler.notifications

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.answers.answer
import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
import dev.inmo.tgbotapi.extensions.api.edit.reply_markup.editMessageReplyMarkup
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitDataCallbackQuery
import dev.inmo.tgbotapi.types.queries.callback.MessageDataCallbackQuery
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.service.AppSettingKeys
import ru.zinin.frigate.analyzer.service.AppSettingsService
import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class NotificationsSettingsCallbackHandler(
    private val userService: TelegramUserService,
    private val appSettings: AppSettingsService,
    private val renderer: NotificationsMessageRenderer,
) {
    enum class DispatchOutcome { RERENDER, CLOSE, UNAUTHORIZED, IGNORE }

    /**
     * Pure dispatch for testability: returns the outcome based on the callback data and current
     * caller state. Side-effects (DB updates) are issued here; the calling site handles
     * Telegram message editing.
     */
    suspend fun dispatch(
        data: String,
        chatId: Long,
        isOwner: Boolean,
        currentUser: TelegramUserDto,
    ): DispatchOutcome {
        return when (data) {
            "nfs:u:rec:tgl" -> {
                val newValue = !currentUser.notificationsRecordingEnabled
                userService.updateNotificationsRecordingEnabled(chatId, newValue)
                DispatchOutcome.RERENDER
            }
            "nfs:u:sig:tgl" -> {
                val newValue = !currentUser.notificationsSignalEnabled
                userService.updateNotificationsSignalEnabled(chatId, newValue)
                DispatchOutcome.RERENDER
            }
            "nfs:g:rec:tgl" -> {
                if (!isOwner) return DispatchOutcome.UNAUTHORIZED
                val current = appSettings.getBoolean(
                    AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true,
                )
                appSettings.setBoolean(
                    AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED,
                    !current,
                    currentUser.username,
                )
                DispatchOutcome.RERENDER
            }
            "nfs:g:sig:tgl" -> {
                if (!isOwner) return DispatchOutcome.UNAUTHORIZED
                val current = appSettings.getBoolean(
                    AppSettingKeys.NOTIFICATIONS_SIGNAL_GLOBAL_ENABLED, true,
                )
                appSettings.setBoolean(
                    AppSettingKeys.NOTIFICATIONS_SIGNAL_GLOBAL_ENABLED,
                    !current,
                    currentUser.username,
                )
                DispatchOutcome.RERENDER
            }
            "nfs:close" -> DispatchOutcome.CLOSE
            else -> DispatchOutcome.IGNORE
        }
    }
}
```

> **Note:** wiring into the bot's behaviour-builder flow (subscribing to `waitDataCallbackQuery`, editing message after dispatch) is part of the bot-startup orchestration. Inspect `FrigateAnalyzerBot` and existing handlers (e.g. `QuickExportHandler` registration) and add a corresponding subscription in the same place. Pseudocode:

```kotlin
waitDataCallbackQuery()
    .filter { it.data.startsWith("nfs:") }
    .collect { cb ->
        answer(cb)
        val msg = (cb as? MessageDataCallbackQuery)?.message ?: return@collect
        val cid = msg.chat.id.chatId.long
        val current = userService.findByChatIdAsDto(cid) ?: return@collect
        val owner = userService.isOwner(current.username)
        val outcome = handler.dispatch(cb.data, cid, owner, current)
        when (outcome) {
            DispatchOutcome.RERENDER -> {
                val updated = userService.findByChatIdAsDto(cid) ?: current
                val state = NotificationsViewState(
                    isOwner = owner,
                    recordingUserEnabled = updated.notificationsRecordingEnabled,
                    signalUserEnabled = updated.notificationsSignalEnabled,
                    recordingGlobalEnabled = appSettings.getBoolean(
                        AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true),
                    signalGlobalEnabled = appSettings.getBoolean(
                        AppSettingKeys.NOTIFICATIONS_SIGNAL_GLOBAL_ENABLED, true),
                    language = updated.languageCode ?: "en",
                )
                val rendered = renderer.render(state)
                editMessageText(msg, rendered.text, replyMarkup = rendered.keyboard)
            }
            DispatchOutcome.CLOSE -> editMessageReplyMarkup(msg, replyMarkup = null)
            else -> { /* no-op */ }
        }
    }
```

- [ ] **Step 6: Run test to verify it passes**

Dispatch `build-runner`: `./gradlew :frigate-analyzer-telegram:test --tests "ru.zinin.frigate.analyzer.telegram.bot.handler.notifications.NotificationsSettingsCallbackHandlerTest"`.
Expected: PASS (7 tests).

- [ ] **Step 7: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/notifications/NotificationsSettingsCallbackHandler.kt \
        modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/notifications/NotificationsSettingsCallbackHandlerTest.kt \
        modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/TelegramUserService.kt \
        modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramUserServiceImpl.kt
git commit -m "feat(telegram): add NotificationsSettingsCallbackHandler with dispatch tests"
```

---

## Task 18: Wire callback subscription into the bot startup

**Goal:** Subscribe to `nfs:*` callbacks in the same place where existing callback subscriptions live (e.g. `FrigateAnalyzerBot` / `QuickExportHandler` registration). The pseudocode block from Task 17 becomes a real subscription.

**Files:**
- Modify: bot startup wiring file (find via `grep -rn "waitDataCallbackQuery" modules/telegram/src/main/kotlin/`)

- [ ] **Step 1: Locate the registration site**

Run: `grep -rn "waitDataCallbackQuery\|onDataCallbackQuery\|CommandHandler\|qe:\|xc:" modules/telegram/src/main/kotlin/ | head -20`

Read the file(s) with existing callback subscriptions. The new subscription must run inside the same `BehaviourContext` lifecycle (typically inside `FrigateAnalyzerBot`'s `start()` or an `@PostConstruct` that opens a behaviour scope).

- [ ] **Step 2: Add the subscription**

Inside the existing `BehaviourContext.buildBehaviour { ... }` block (or equivalent), add:

```kotlin
waitDataCallbackQuery()
    .filter { it.data.startsWith("nfs:") }
    .onEach { cb ->
        answer(cb)
        val msg = (cb as? MessageDataCallbackQuery)?.message ?: return@onEach
        val cid = msg.chat.id.chatId.long
        val current = userService.findByChatIdAsDto(cid) ?: return@onEach
        val owner = userService.isOwner(current.username)
        val outcome = notificationsSettingsCallbackHandler.dispatch(cb.data, cid, owner, current)
        when (outcome) {
            NotificationsSettingsCallbackHandler.DispatchOutcome.RERENDER -> {
                val updated = userService.findByChatIdAsDto(cid) ?: current
                val state = NotificationsViewState(
                    isOwner = owner,
                    recordingUserEnabled = updated.notificationsRecordingEnabled,
                    signalUserEnabled = updated.notificationsSignalEnabled,
                    recordingGlobalEnabled = appSettings.getBoolean(
                        AppSettingKeys.NOTIFICATIONS_RECORDING_GLOBAL_ENABLED, true),
                    signalGlobalEnabled = appSettings.getBoolean(
                        AppSettingKeys.NOTIFICATIONS_SIGNAL_GLOBAL_ENABLED, true),
                    language = updated.languageCode ?: "en",
                )
                val rendered = notificationsMessageRenderer.render(state)
                editMessageText(msg, rendered.text, replyMarkup = rendered.keyboard)
            }
            NotificationsSettingsCallbackHandler.DispatchOutcome.CLOSE -> {
                editMessageReplyMarkup(msg, replyMarkup = null)
            }
            else -> { /* unauthorized / ignore */ }
        }
    }
    .launchIn(this)
```

Wire the constructor of the registering class to inject `notificationsSettingsCallbackHandler`, `notificationsMessageRenderer`, `appSettings`, and (if not already present) `userService`. Imports as needed.

- [ ] **Step 3: Run telegram tests**

Dispatch `build-runner`: `./gradlew :frigate-analyzer-telegram:test`.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add <bot-wiring-file>
git commit -m "feat(telegram): subscribe to nfs:* callbacks in bot lifecycle"
```

---

## Task 19: `RecordingProcessingFacade` — call `NotificationDecisionService`

**Goal:** Before invoking Telegram fan-out, gate via decision service. Skip notification (and AI description) when `shouldNotify=false`.

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/facade/RecordingProcessingFacade.kt`

- [ ] **Step 1: Inject `NotificationDecisionService` and `DetectionEntityService`**

Edit `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/facade/RecordingProcessingFacade.kt`. Add imports:

```kotlin
import ru.zinin.frigate.analyzer.service.DetectionEntityService
import ru.zinin.frigate.analyzer.service.NotificationDecisionService
```

Add to constructor:

```kotlin
    private val notificationDecisionService: NotificationDecisionService,
    private val detectionEntityService: DetectionEntityService,
```

- [ ] **Step 2: Read detections and gate via decision**

Inside `processAndNotify`, after `recordingEntityService.saveProcessingResult(request)` and after fetching the recording, before the `try { telegramNotificationService.sendRecordingNotification(...) }` block, insert:

```kotlin
                val detections = detectionEntityService.findByRecordingId(recordingId)
                val decision = notificationDecisionService.evaluate(recording, detections)
                if (!decision.shouldNotify) {
                    logger.debug {
                        "Notification suppressed for recording=$recordingId reason=${decision.reason}"
                    }
                    return
                }
```

- [ ] **Step 3: Add `findByRecordingId` to `DetectionEntityService` if missing**

Run: `grep -n "findByRecordingId" modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/`

If absent, add to `DetectionEntityService` interface:

```kotlin
    suspend fun findByRecordingId(recordingId: UUID): List<DetectionEntity>
```

And in `DetectionEntityServiceImpl`:

```kotlin
    @Transactional(readOnly = true)
    override suspend fun findByRecordingId(recordingId: UUID): List<DetectionEntity> =
        repository.findByRecordingId(recordingId)
```

Add to `DetectionEntityRepository`:

```kotlin
    suspend fun findByRecordingId(recordingId: UUID): List<DetectionEntity>
```

(Spring Data derives the query.)

- [ ] **Step 4: Verify compilation**

Dispatch `build-runner`: `./gradlew :frigate-analyzer-core:test`.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/facade/RecordingProcessingFacade.kt \
        modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/DetectionEntityService.kt \
        modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/DetectionEntityServiceImpl.kt \
        modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/repository/DetectionEntityRepository.kt
git commit -m "feat(core): gate recording notifications via NotificationDecisionService"
```

---

## Task 20: `application.yaml` + `.env.example`

**Goal:** Surface tracker properties as environment variables and document defaults.

**Files:**
- Modify: `modules/core/src/main/resources/application.yaml`
- Modify: `.env.example`

- [ ] **Step 1: Add `application.notifications` block to YAML**

Edit `modules/core/src/main/resources/application.yaml`. After the `signal-loss:` block insert:

```yaml
  notifications:
    tracker:
      ttl: ${NOTIFICATIONS_TRACK_TTL:120s}
      iou-threshold: ${NOTIFICATIONS_TRACK_IOU_THRESHOLD:0.3}
      inner-iou: ${NOTIFICATIONS_TRACK_INNER_IOU:0.5}
      cleanup-interval: ${NOTIFICATIONS_TRACK_CLEANUP_INTERVAL:1h}
      cleanup-retention: ${NOTIFICATIONS_TRACK_CLEANUP_RETENTION:1h}
```

- [ ] **Step 2: Update `.env.example`**

Edit `.env.example`. Append:

```bash

# Notification tracker (object tracking + suppression)
NOTIFICATIONS_TRACK_TTL=120s
NOTIFICATIONS_TRACK_IOU_THRESHOLD=0.3
NOTIFICATIONS_TRACK_INNER_IOU=0.5
NOTIFICATIONS_TRACK_CLEANUP_INTERVAL=1h
NOTIFICATIONS_TRACK_CLEANUP_RETENTION=1h
```

- [ ] **Step 3: Register `ObjectTrackerProperties` in core configuration**

Find the existing `@ConfigurationPropertiesScan` or `@EnableConfigurationProperties` annotation in core:

Run: `grep -rn "ConfigurationPropertiesScan\|EnableConfigurationProperties" modules/core/src/main/kotlin/`

If `@ConfigurationPropertiesScan` exists with a basePackages list that already covers `ru.zinin.frigate.analyzer.service.config`, no action needed. Otherwise add `ru.zinin.frigate.analyzer.service.config` to the scan path or add `ObjectTrackerProperties::class` to `@EnableConfigurationProperties`.

- [ ] **Step 4: Verify with full build**

Dispatch `build-runner`: `./gradlew build -x test`.
Expected: BUILD SUCCESSFUL — application context loads with new properties bound.

- [ ] **Step 5: Commit**

```bash
git add modules/core/src/main/resources/application.yaml .env.example \
        <core-config-class-if-modified>
git commit -m "feat(config): wire NOTIFICATIONS_TRACK_* env vars and ObjectTrackerProperties"
```

---

## Task 21: Documentation updates

**Goal:** Reflect the new schema, env vars, and bot command in `.claude/rules/`.

**Files:**
- Modify: `.claude/rules/configuration.md`
- Modify: `.claude/rules/telegram.md`
- Modify: `.claude/rules/database.md`

- [ ] **Step 1: `configuration.md`**

Edit `.claude/rules/configuration.md`. Append after the Signal Loss block:

```markdown
## Notifications

Settings under `application.notifications` in `application.yaml`. Object tracker suppresses duplicate recording notifications when an object remains in view across consecutive recordings.

| Variable | Default | Purpose |
|----------|---------|---------|
| `NOTIFICATIONS_TRACK_TTL` | 120s | Track stays "active" this long after last detection. Match → updateLastSeen → no spam. |
| `NOTIFICATIONS_TRACK_IOU_THRESHOLD` | 0.3 | IoU threshold for cross-recording matching of (class, bbox). |
| `NOTIFICATIONS_TRACK_INNER_IOU` | 0.5 | IoU threshold for clustering same-class detections within one recording. |
| `NOTIFICATIONS_TRACK_CLEANUP_INTERVAL` | 1h | `@Scheduled` cleanup job period. |
| `NOTIFICATIONS_TRACK_CLEANUP_RETENTION` | 1h | DELETE rows with `last_seen_at < now() - retention`. Larger than TTL. |

Per-user toggles for recording detections and camera signal-loss alerts are stored in `telegram_users.notifications_recording_enabled` / `notifications_signal_enabled` (default `true`). Global toggles in `app_settings`: `notifications.recording.global_enabled`, `notifications.signal.global_enabled`. OWNER manages globals via `/notifications`.
```

- [ ] **Step 2: `telegram.md`**

Edit `.claude/rules/telegram.md`. Add a row to the Bot Commands table (after `/timezone`):

```markdown
| /notifications | USER, OWNER | Manage notification subscriptions |
```

Add a new section near the bottom:

```markdown
## Notifications Dialog

`/notifications` opens an inline-keyboard dialog managed by:

| Component | Location | Purpose |
|---|---|---|
| `NotificationsCommandHandler` | `bot/handler/notifications/` | Sends initial dialog message |
| `NotificationsSettingsCallbackHandler` | `bot/handler/notifications/` | Handles `nfs:*` callbacks, mutates per-user/global state |
| `NotificationsMessageRenderer` | `bot/handler/notifications/` | Renders text + keyboard from current state |

Callback prefix: `nfs:`. Variants:
- `nfs:u:rec:tgl` / `nfs:u:sig:tgl` — per-user toggles (USER, OWNER)
- `nfs:g:rec:tgl` / `nfs:g:sig:tgl` — global toggles (OWNER only; non-OWNER receives no-op acknowledgement)
- `nfs:close` — close keyboard
```

- [ ] **Step 3: `database.md`**

Edit `.claude/rules/database.md`. Append two table sections:

```markdown
### object_tracks

| Column | Type | Purpose |
|--------|------|---------|
| id | UUID | Primary key |
| creation_timestamp | TIMESTAMPTZ | First time this track was seen |
| cam_id | VARCHAR(255) | Camera identifier |
| class_name | VARCHAR(255) | YOLO class |
| bbox_x1, bbox_y1, bbox_x2, bbox_y2 | REAL | Representative bbox of latest match |
| last_seen_at | TIMESTAMPTZ | Last match timestamp (updated via GREATEST) |
| last_recording_id | UUID NULL | FK → recordings (ON DELETE SET NULL) |

Index: `idx_object_tracks_cam_lastseen (cam_id, last_seen_at DESC)`. Cleanup via `ObjectTracksCleanupTask`.

### app_settings

| Column | Type | Purpose |
|--------|------|---------|
| setting_key | VARCHAR(64) | PK; hierarchical key |
| setting_value | VARCHAR(2048) | Serialized scalar |
| updated_at | TIMESTAMPTZ | |
| updated_by | VARCHAR(255) NULL | OWNER username, NULL for migration-seeded |

Seeded with `notifications.recording.global_enabled=true` and `notifications.signal.global_enabled=true`.
```

Add to `telegram_users` table description: two new BOOLEAN NOT NULL DEFAULT TRUE columns: `notifications_recording_enabled`, `notifications_signal_enabled`.

- [ ] **Step 4: Commit**

```bash
git add .claude/rules/configuration.md .claude/rules/telegram.md .claude/rules/database.md
git commit -m "docs(rules): document notification toggles, object_tracks, app_settings"
```

---

## Task 22: Final integration test + remove design/plan from PR

**Goal:** Run the full build to catch cross-module issues, then prepare the branch for PR by removing the design/plan documents (per the project's brainstorming workflow rule — they live in branch git history, not in the PR diff).

**Files:**
- Remove: `docs/superpowers/specs/2026-04-27-notification-controls-and-object-tracking-design.md`
- Remove: `docs/superpowers/plans/2026-04-27-notification-controls-and-object-tracking.md`

- [ ] **Step 1: Run the full build**

Dispatch `build-runner`: `./gradlew build`.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run ktlint formatter**

If the previous step reported ktlint errors:

Dispatch `build-runner`: `./gradlew ktlintFormat` then re-run the build.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Remove docs/superpowers content from the branch**

```bash
git rm docs/superpowers/specs/2026-04-27-notification-controls-and-object-tracking-design.md
git rm docs/superpowers/plans/2026-04-27-notification-controls-and-object-tracking.md
git commit -m "chore: remove superpowers docs before PR (kept in branch history)"
```

- [ ] **Step 4: Verify final state**

```bash
git status
git log --oneline | head -25
```

Expected: clean working tree; commits represent the full feature.

---

## Done

The branch `feature/notification-controls` should now contain:
- One Liquibase migration `1.0.4.xml`
- New domain services: `ObjectTrackerService`, `AppSettingsService`, `NotificationDecisionService`
- Helpers: `IouHelper`, `BboxClusteringHelper`
- Per-user notification flags through Telegram entity/DTO/service
- Bot dialog `/notifications` with inline keyboard
- Wired into `RecordingProcessingFacade`
- Configuration in `application.yaml` and `.env.example`
- Updated docs in `.claude/rules/`
- Comprehensive unit test coverage

**Open a PR against `master`.**
