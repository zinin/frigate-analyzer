# AI Description Rate Limiter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Limit AI-description (Claude) invocations to a configurable sliding-window rate (default 10/hour). On limit-exceed, suppress both the Claude call and all Telegram placeholders/edit-jobs so the recording notification looks identical to one sent with `application.ai.description.enabled=false`.

**Architecture:** Single Spring `@Component` `DescriptionRateLimiter` (sliding-window deque under `Mutex`, in-memory only). One integration point: `TelegramNotificationServiceImpl.sendRecordingNotification` consults the limiter before invoking `descriptionSupplier`. Configuration extends the existing `DescriptionProperties.CommonSection` with a nested `RateLimit` data class. No DB migrations, no new modules.

**Tech Stack:** Kotlin 2.3.10, Spring Boot 4.0.3, Coroutines (`kotlinx.coroutines.sync.Mutex`), `java.time.Clock` (existing bean from `common.config.ClockConfig`), JUnit 5, MockK, `kotlinx-coroutines-test`.

**Reference Spec:** `docs/superpowers/specs/2026-04-25-description-rate-limit-design.md`

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/config/DescriptionProperties.kt` | MODIFY | Add nested `RateLimit` data class + `rateLimit: RateLimit` field in `CommonSection` |
| `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/ratelimit/DescriptionRateLimiter.kt` | CREATE | Sliding-window rate limiter component |
| `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/ratelimit/DescriptionRateLimiterTest.kt` | CREATE | Unit tests for limiter behaviour |
| `modules/core/src/main/resources/application.yaml` | MODIFY | Add `rate-limit` block under `application.ai.description.common` |
| `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImpl.kt` | MODIFY | Inject `ObjectProvider<DescriptionRateLimiter>`, gate `descriptionSupplier?.invoke()` |
| `modules/telegram/build.gradle.kts` | MODIFY (if needed) | Ensure dependency on `:modules:ai-description` exists for `DescriptionRateLimiter` import |
| `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImplTest.kt` | MODIFY | Add 2 new test cases (skip on limit, supplier called once below limit) |
| `.claude/rules/configuration.md` | MODIFY | Document new env vars |

---

## Task 1: Add `RateLimit` to `DescriptionProperties`

**Files:**
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/config/DescriptionProperties.kt`
- Modify: `modules/core/src/main/resources/application.yaml`

**Goal:** Make the new config block bindable. No new behaviour yet — Task 2 will use it.

- [ ] **Step 1.1: Add `RateLimit` nested data class and `rateLimit` field**

Open `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/config/DescriptionProperties.kt` and replace the entire file contents with:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.config

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@ConfigurationProperties(prefix = "application.ai.description")
@Validated
data class DescriptionProperties(
    val enabled: Boolean,
    // Без @NotBlank — при enabled=false provider может быть пустым в конфиге.
    // Валидация provider происходит в AiDescriptionAutoConfiguration:
    // если enabled=true и нет бина под provider — WARN.
    val provider: String,
    @field:Valid
    val common: CommonSection,
) {
    data class CommonSection(
        @field:Pattern(regexp = "ru|en", message = "must be 'ru' or 'en'")
        val language: String,
        @field:Min(50) @field:Max(500)
        val shortMaxLength: Int,
        @field:Min(200) @field:Max(3500)
        val detailedMaxLength: Int,
        @field:Min(1) @field:Max(50)
        val maxFrames: Int,
        val queueTimeout: Duration,
        val timeout: Duration,
        @field:Min(1) @field:Max(10)
        val maxConcurrent: Int,
        @field:Valid
        val rateLimit: RateLimit,
    ) {
        init {
            require(queueTimeout.toMillis() > 0) { "queue-timeout must be positive" }
            require(timeout.toMillis() > 0) { "timeout must be positive" }
        }
    }

    data class RateLimit(
        val enabled: Boolean,
        @field:Min(1) @field:Max(10000)
        val maxRequests: Int,
        val window: Duration,
    ) {
        init {
            require(window.toMillis() > 0) { "rate-limit.window must be positive" }
        }
    }
}
```

- [ ] **Step 1.2: Add `rate-limit` block to `application.yaml`**

Open `modules/core/src/main/resources/application.yaml`. Find the `application.ai.description.common` section. After the `max-concurrent:` line, add:

```yaml
        rate-limit:
          enabled: ${APP_AI_DESCRIPTION_RATE_LIMIT_ENABLED:true}
          max-requests: ${APP_AI_DESCRIPTION_RATE_LIMIT_MAX:10}
          window: ${APP_AI_DESCRIPTION_RATE_LIMIT_WINDOW:1h}
```

Final shape of the `common` block must be:

```yaml
      common:
        language: ${APP_AI_DESCRIPTION_LANGUAGE:en}
        short-max-length: ${APP_AI_DESCRIPTION_SHORT_MAX:200}
        detailed-max-length: ${APP_AI_DESCRIPTION_DETAILED_MAX:1500}
        max-frames: ${APP_AI_DESCRIPTION_MAX_FRAMES:10}
        queue-timeout: ${APP_AI_DESCRIPTION_QUEUE_TIMEOUT:30s}
        timeout: ${APP_AI_DESCRIPTION_TIMEOUT:60s}
        max-concurrent: ${APP_AI_DESCRIPTION_MAX_CONCURRENT:2}
        rate-limit:
          enabled: ${APP_AI_DESCRIPTION_RATE_LIMIT_ENABLED:true}
          max-requests: ${APP_AI_DESCRIPTION_RATE_LIMIT_MAX:10}
          window: ${APP_AI_DESCRIPTION_RATE_LIMIT_WINDOW:1h}
```

- [ ] **Step 1.3: Verify the project still compiles by listing files**

Run: `git status`
Expected output should include:
- `modified: modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/config/DescriptionProperties.kt`
- `modified: modules/core/src/main/resources/application.yaml`

NOTE: do NOT run `./gradlew build` here — task 5 dispatches build-runner once at the end. We expect compilation to fail in any test that constructs `DescriptionProperties.CommonSection` without passing `rateLimit`. Existing tests that build `CommonSection` instances will be updated as part of Task 2 (limiter tests need the same constructor) and Task 3 (Telegram test setup uses an `ObjectProvider`, not `CommonSection` directly — verify by grep).

Run: `grep -rn "CommonSection(" /opt/github/zinin/frigate-analyzer/modules --include='*.kt'`
For each match, the construction must be updated in the same task that next touches that file. As of writing, all matches are in `modules/ai-description/src/test/...`. They will be addressed when each test is run.

- [ ] **Step 1.4: Update existing test constructors that build `CommonSection`**

Run: `grep -rn "CommonSection(" /opt/github/zinin/frigate-analyzer/modules/ai-description/src/test --include='*.kt' -l`

For each file in the result, locate every `CommonSection(...)` constructor call and add `rateLimit = DescriptionProperties.RateLimit(enabled = false, maxRequests = 10, window = Duration.ofHours(1))` as the last argument. Use named arguments for clarity. Add the import `import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties` if not already present (or qualify inline).

We use `enabled = false` in existing tests so prior behaviour (no rate-limiting) is preserved — those tests are not about the limiter.

- [ ] **Step 1.5: Stage and commit**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/config/DescriptionProperties.kt \
        modules/core/src/main/resources/application.yaml \
        modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/
git status
git commit -m "$(cat <<'EOF'
feat(ai-description): add rate-limit config block

Extend DescriptionProperties.CommonSection with RateLimit nested data
class (enabled, maxRequests, window). Wire defaults in application.yaml
(true / 10 / 1h). Existing tests pass rateLimit(enabled=false, ...) to
preserve prior behaviour.

No behaviour change yet — limiter component arrives in the next commit.
EOF
)"
```

---

## Task 2: Implement `DescriptionRateLimiter` (TDD)

**Files:**
- Create: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/ratelimit/DescriptionRateLimiterTest.kt`
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/ratelimit/DescriptionRateLimiter.kt`

**Goal:** Testable, thread-safe sliding-window limiter. No call site yet.

- [ ] **Step 2.1: Write failing unit-tests first (`DescriptionRateLimiterTest.kt`)**

Create `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/ratelimit/DescriptionRateLimiterTest.kt` with:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.ratelimit

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

private class MutableClock(initial: Instant) : Clock() {
    @Volatile var current: Instant = initial
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = throw UnsupportedOperationException("not used in tests")
    override fun instant(): Instant = current
}

class DescriptionRateLimiterTest {
    private val baseInstant = Instant.parse("2026-04-25T12:00:00Z")

    private fun props(
        enabled: Boolean,
        maxRequests: Int,
        window: Duration = Duration.ofHours(1),
    ) = DescriptionProperties(
        enabled = true,
        provider = "claude",
        common =
            DescriptionProperties.CommonSection(
                language = "en",
                shortMaxLength = 200,
                detailedMaxLength = 1500,
                maxFrames = 10,
                queueTimeout = Duration.ofSeconds(30),
                timeout = Duration.ofSeconds(60),
                maxConcurrent = 2,
                rateLimit = DescriptionProperties.RateLimit(enabled = enabled, maxRequests = maxRequests, window = window),
            ),
    )

    @Test
    fun `disabled rate-limit always returns true`() =
        runBlocking {
            val limiter = DescriptionRateLimiter(Clock.fixed(baseInstant, ZoneOffset.UTC), props(enabled = false, maxRequests = 1))
            repeat(100) {
                assertTrue(limiter.tryAcquire(), "iteration $it should be allowed when disabled")
            }
        }

    @Test
    fun `under limit allows`() =
        runBlocking {
            val limiter = DescriptionRateLimiter(Clock.fixed(baseInstant, ZoneOffset.UTC), props(enabled = true, maxRequests = 3))
            assertTrue(limiter.tryAcquire())
            assertTrue(limiter.tryAcquire())
            assertTrue(limiter.tryAcquire())
        }

    @Test
    fun `at limit blocks fourth call within same window`() =
        runBlocking {
            val limiter = DescriptionRateLimiter(Clock.fixed(baseInstant, ZoneOffset.UTC), props(enabled = true, maxRequests = 3))
            repeat(3) { assertTrue(limiter.tryAcquire()) }
            assertFalse(limiter.tryAcquire(), "4th call must be denied within window")
        }

    @Test
    fun `boundary - t plus window minus 1ms still blocks`() =
        runBlocking {
            // Implementation drops timestamps when `!timestamp.isAfter(cutoff)`,
            // i.e. timestamp <= cutoff. cutoff = now - window.
            // At now = t + window - 1ms, cutoff = t - 1ms, timestamp = t > cutoff → keep → block.
            val clock = MutableClock(baseInstant)
            val limiter = DescriptionRateLimiter(clock, props(enabled = true, maxRequests = 1))
            assertTrue(limiter.tryAcquire())

            clock.current = baseInstant.plus(Duration.ofHours(1)).minusMillis(1)
            assertFalse(limiter.tryAcquire(), "old timestamp still inside window → must block")
        }

    @Test
    fun `boundary - exactly t plus window releases slot`() =
        runBlocking {
            // At now = t + window, cutoff = t. timestamp = t, !t.isAfter(t) = !false = true → drop.
            // Documented design choice: a timestamp older or equal to cutoff is OUT of the window.
            val clock = MutableClock(baseInstant)
            val limiter = DescriptionRateLimiter(clock, props(enabled = true, maxRequests = 1))
            assertTrue(limiter.tryAcquire())

            clock.current = baseInstant.plus(Duration.ofHours(1))
            assertTrue(limiter.tryAcquire(), "old timestamp == cutoff → dropped → new slot free")
            assertFalse(limiter.tryAcquire(), "deque full again")
        }

    @Test
    fun `boundary - t plus window plus 1ms releases slot`() =
        runBlocking {
            val clock = MutableClock(baseInstant)
            val limiter = DescriptionRateLimiter(clock, props(enabled = true, maxRequests = 1))
            assertTrue(limiter.tryAcquire())

            clock.current = baseInstant.plus(Duration.ofHours(1)).plusMillis(1)
            assertTrue(limiter.tryAcquire(), "well past cutoff → slot free")
            assertFalse(limiter.tryAcquire(), "now full again")
        }

    @Test
    fun `concurrent acquisitions never exceed limit`() =
        runBlocking {
            val limiter = DescriptionRateLimiter(Clock.fixed(baseInstant, ZoneOffset.UTC), props(enabled = true, maxRequests = 10))

            val results: List<Boolean> =
                coroutineScope {
                    (1..50)
                        .map { async { limiter.tryAcquire() } }
                        .awaitAll()
                }

            assertEquals(10, results.count { it }, "exactly 10 must succeed")
            assertEquals(40, results.count { !it }, "exactly 40 must be rejected")
        }

    @Test
    fun `cleanup keeps deque bounded - never grows past maxRequests across many iterations`() =
        runBlocking {
            // Move time forward each call by window+1ms — every call drops all previous timestamps
            // and stores a fresh one. If cleanup were broken, deque would grow unbounded; in practice
            // we observe that the limiter accepts every call (no false negatives) over 1000 iterations.
            val clock = MutableClock(baseInstant)
            val limiter = DescriptionRateLimiter(clock, props(enabled = true, maxRequests = 5))

            repeat(1000) { i ->
                clock.current = baseInstant.plus(Duration.ofHours(1)).plus(Duration.ofMillis(1L + i.toLong()))
                assertTrue(limiter.tryAcquire(), "iteration $i should always succeed because window slid")
            }
        }

    @Test
    fun `rate-limit window must be positive - validated at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            DescriptionProperties.RateLimit(enabled = true, maxRequests = 10, window = Duration.ZERO)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DescriptionProperties.RateLimit(enabled = true, maxRequests = 10, window = Duration.ofSeconds(-1))
        }
    }
}
```

- [ ] **Step 2.2: Confirm tests fail to compile**

Run: `git status` (to verify file is on disk)

Don't dispatch build-runner here — Task 5 does that. The test file references `DescriptionRateLimiter`, which does not yet exist. Compilation will fail. That is the "test fails first" gate.

- [ ] **Step 2.3: Implement `DescriptionRateLimiter`**

Create `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/ratelimit/DescriptionRateLimiter.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.ratelimit

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import java.time.Clock
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Sliding-window rate limiter for AI description requests.
 *
 * Caller logs on `false` return — the limiter intentionally stays domain-agnostic.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class DescriptionRateLimiter(
    private val clock: Clock,
    descriptionProperties: DescriptionProperties,
) {
    private val rateLimit = descriptionProperties.common.rateLimit
    private val mutex = Mutex()
    private val timestamps = ArrayDeque<Instant>(rateLimit.maxRequests)

    init {
        if (rateLimit.enabled) {
            logger.info {
                "AI description rate limiter enabled: max=${rateLimit.maxRequests}, window=${rateLimit.window}"
            }
        } else {
            logger.info { "AI description rate limiter disabled (rate-limit.enabled=false)" }
        }
    }

    suspend fun tryAcquire(): Boolean {
        if (!rateLimit.enabled) return true

        return mutex.withLock {
            val now = clock.instant()
            val cutoff = now.minus(rateLimit.window)

            while (timestamps.isNotEmpty() && !timestamps.first().isAfter(cutoff)) {
                timestamps.removeFirst()
            }

            if (timestamps.size < rateLimit.maxRequests) {
                timestamps.addLast(now)
                true
            } else {
                false
            }
        }
    }
}
```

- [ ] **Step 2.4: Run only the new tests via build-runner**

Dispatch the `build` skill (which dispatches `build-runner` agent). Tell it:

> Run only `:modules:ai-description:test --tests "ru.zinin.frigate.analyzer.ai.description.ratelimit.DescriptionRateLimiterTest"` and report pass/fail per test method. If ktlint errors appear in the new files, run `./gradlew ktlintFormat` first, then retry. Do not run the full `./gradlew build`.

Expected: all 8 test methods pass.

- [ ] **Step 2.5: Stage and commit**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/ratelimit/DescriptionRateLimiter.kt \
        modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/ratelimit/DescriptionRateLimiterTest.kt
git commit -m "$(cat <<'EOF'
feat(ai-description): sliding-window DescriptionRateLimiter

In-memory ArrayDeque<Instant> under Mutex. fast-path returns true when
rate-limit.enabled=false (no mutex, no allocation). When enabled, drops
timestamps <= cutoff (now - window), allows if deque size < maxRequests,
appends current Instant on success.

Caller responsible for logging on false return (limiter is
domain-agnostic). Concurrent test verifies exactly maxRequests pass when
50 coroutines race a max=10 limiter.
EOF
)"
```

---

## Task 3: Wire limiter into `TelegramNotificationServiceImpl`

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImpl.kt`
- Modify: `modules/telegram/build.gradle.kts` (only if dependency missing — verify first)
- Modify: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImplTest.kt`

**Goal:** Plug the limiter into the single decision point. Existing tests stay green; two new tests cover the new behaviour.

- [ ] **Step 3.1: Verify telegram → ai-description dependency**

Run:
```bash
grep -n "ai-description" /opt/github/zinin/frigate-analyzer/modules/telegram/build.gradle.kts
```

Expected: at least one `implementation(project(":modules:ai-description"))` line. If missing, add it under `dependencies { ... }`. The existing `TelegramNotificationServiceImpl.kt` already imports `ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult`, so the dependency is almost certainly already there.

- [ ] **Step 3.2: Write failing integration tests**

Open `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImplTest.kt`.

(a) Add imports at the top of the file (alongside existing ones):

```kotlin
import io.mockk.coVerify
import org.springframework.beans.factory.ObjectProvider
import ru.zinin.frigate.analyzer.ai.description.ratelimit.DescriptionRateLimiter
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
```

(Some of these may already be present — keep imports unique, no duplicates.)

(b) Replace the existing `service` field initialization (lines 41-43 area, where `TelegramNotificationServiceImpl(...)` is constructed) so the constructor includes a new `rateLimiterProvider` field:

```kotlin
    private val rateLimiterProvider = mockk<ObjectProvider<DescriptionRateLimiter>>(relaxed = true)
    private val service: TelegramNotificationService =
        TelegramNotificationServiceImpl(
            userService,
            notificationQueue,
            uuidGeneratorHelper,
            msg,
            signalLossFormatter,
            rateLimiterProvider,
        )
```

(c) Add two new `@Test` methods at the end of the class (just before the closing brace):

```kotlin
    @Test
    fun `sendRecordingNotification skips description when rate limit denies`() =
        runTest {
            val recording = createRecording()
            val visualizedFrames =
                listOf(
                    VisualizedFrameData(frameIndex = 0, visualizedBytes = byteArrayOf(1, 2, 3), detectionsCount = 1),
                )
            val taskSlot = slot<RecordingNotificationTask>()
            var supplierInvocations = 0
            val supplier: () -> kotlinx.coroutines.Deferred<Result<ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult>>? = {
                supplierInvocations++
                null
            }

            val limiter = mockk<DescriptionRateLimiter>()
            coEvery { limiter.tryAcquire() } returns false
            every { rateLimiterProvider.getIfAvailable() } returns limiter

            coEvery { uuidGeneratorHelper.generateV1() } returns taskId
            coEvery { userService.getAuthorizedUsersWithZones() } returns
                listOf(UserZoneInfo(chatId = chatId, zone = ZoneId.of("UTC"), language = "ru"))
            coEvery { notificationQueue.enqueue(capture(taskSlot)) } returns Unit

            service.sendRecordingNotification(recording, visualizedFrames, supplier)

            assertEquals(0, supplierInvocations, "supplier must not be invoked when rate-limit denies")
            assertEquals(null, taskSlot.captured.descriptionHandle)
            coVerify(exactly = 1) { limiter.tryAcquire() }
        }

    @Test
    fun `sendRecordingNotification invokes supplier exactly once below limit`() =
        runTest {
            val recording = createRecording()
            val visualizedFrames =
                listOf(
                    VisualizedFrameData(frameIndex = 0, visualizedBytes = byteArrayOf(1, 2, 3), detectionsCount = 1),
                )
            var supplierInvocations = 0
            val supplier: () -> kotlinx.coroutines.Deferred<Result<ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult>>? = {
                supplierInvocations++
                null
            }

            val limiter = mockk<DescriptionRateLimiter>()
            coEvery { limiter.tryAcquire() } returns true
            every { rateLimiterProvider.getIfAvailable() } returns limiter

            coEvery { uuidGeneratorHelper.generateV1() } returns taskId
            // 3 recipients of the same recording — supplier still must be invoked exactly once
            coEvery { userService.getAuthorizedUsersWithZones() } returns
                listOf(
                    UserZoneInfo(chatId = 1L, zone = ZoneId.of("UTC"), language = "ru"),
                    UserZoneInfo(chatId = 2L, zone = ZoneId.of("UTC"), language = "ru"),
                    UserZoneInfo(chatId = 3L, zone = ZoneId.of("UTC"), language = "en"),
                )
            coEvery { notificationQueue.enqueue(any()) } returns Unit

            service.sendRecordingNotification(recording, visualizedFrames, supplier)

            assertEquals(1, supplierInvocations, "supplier must be invoked exactly once across all recipients")
            coVerify(exactly = 1) { limiter.tryAcquire() }
            coVerify(exactly = 3) { notificationQueue.enqueue(any()) }
        }
```

- [ ] **Step 3.3: Modify `TelegramNotificationServiceImpl` to inject the limiter and gate the call**

Open `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImpl.kt`.

(a) Add imports (after the existing import block):

```kotlin
import org.springframework.beans.factory.ObjectProvider
import ru.zinin.frigate.analyzer.ai.description.ratelimit.DescriptionRateLimiter
```

(b) Update the constructor (replace the existing primary constructor parameter list) to add `rateLimiterProvider`:

```kotlin
class TelegramNotificationServiceImpl(
    private val userService: TelegramUserService,
    private val notificationQueue: TelegramNotificationQueue,
    private val uuidGeneratorHelper: UUIDGeneratorHelper,
    private val msg: MessageResolver,
    private val signalLossFormatter: SignalLossMessageFormatter,
    private val rateLimiterProvider: ObjectProvider<DescriptionRateLimiter>,
) : TelegramNotificationService {
```

(c) Replace line 52 (`val descriptionHandle = descriptionSupplier?.invoke()`) and the comment immediately above it with:

```kotlin
        // Lazy start of describe-job: invoked ONCE, shared across all recipients of the same recording.
        // Rate limiter (when AI description is enabled) gates the invocation: if the sliding-window
        // limit is exceeded, the recording goes out as a plain notification — no placeholder, no
        // second message, no edit job, no Claude call. ObjectProvider is used because the limiter
        // bean only exists when application.ai.description.enabled=true.
        val descriptionHandle =
            if (descriptionSupplier != null && (rateLimiterProvider.getIfAvailable()?.tryAcquire() != false)) {
                descriptionSupplier.invoke()
            } else {
                if (descriptionSupplier != null) {
                    logger.warn {
                        "AI description rate limit reached, skipping description for recording ${recording.id}"
                    }
                }
                null
            }
```

The inner `if (descriptionSupplier != null)` guard around the WARN log avoids spamming logs when the supplier was already null (AI description disabled — that case has nothing to skip).

- [ ] **Step 3.4: Update `NoOpTelegramNotificationService` if it shares the constructor signature**

Run: `grep -rn "NoOpTelegramNotificationService" /opt/github/zinin/frigate-analyzer/modules/telegram/src/main/kotlin --include='*.kt'`

If the class exists and implements `TelegramNotificationService` directly without delegation, no change is needed (it has its own constructor). If it delegates to `TelegramNotificationServiceImpl`, add the new `ObjectProvider<DescriptionRateLimiter>` parameter wherever `TelegramNotificationServiceImpl` is constructed. Inspect with: `grep -rn "TelegramNotificationServiceImpl(" /opt/github/zinin/frigate-analyzer/modules --include='*.kt'`. Update each construction to include `rateLimiterProvider` (production code uses Spring DI, so this is mostly tests).

- [ ] **Step 3.5: Run the affected test class via build-runner**

Dispatch the `build` skill. Tell it:

> Run `:modules:telegram:test --tests "ru.zinin.frigate.analyzer.telegram.service.impl.TelegramNotificationServiceImplTest"` and report results. If ktlint fails on the modified files, `./gradlew ktlintFormat` then retry. Do not run the full build.

Expected: all tests pass — both pre-existing ones (with the new `rateLimiterProvider` mock present in the constructor) and the two new ones (`...skips description when rate limit denies` and `...invokes supplier exactly once below limit`).

- [ ] **Step 3.6: Stage and commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImpl.kt \
        modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImplTest.kt
git commit -m "$(cat <<'EOF'
feat(telegram): gate AI description supplier behind DescriptionRateLimiter

TelegramNotificationServiceImpl now consults DescriptionRateLimiter
(injected via ObjectProvider so it stays optional when ai.description
is disabled). On a deny: descriptionHandle stays null → existing
formatter==null branch in TelegramNotificationSender suppresses caption
placeholder, second reply message, and edit-job. WARN log fires once
per skip, with recordingId.
EOF
)"
```

---

## Task 4: Documentation

**Files:**
- Modify: `.claude/rules/configuration.md`

NOTE: The current `configuration.md` has no section for `APP_AI_DESCRIPTION_*` (verified by grep). We add a brand-new section. Pick the position alphabetically/semantically — between "Core Settings" and "Records Watcher" sections is a sensible spot, but matching existing ordering (`Core Settings` → `Records Watcher` → ... → `Telegram` → `Signal Loss`) suggests an "AI Description" block could go just before "Telegram" since notifications about descriptions ride the Telegram channel.

- [ ] **Step 4.1: Insert the new section into `configuration.md`**

Open `.claude/rules/configuration.md`. Find the `## Telegram` section header. Insert the following new section IMMEDIATELY BEFORE it:

```markdown
## AI Description

Settings under `application.ai.description` in `application.yaml`. Enables AI-generated short and detailed descriptions of detections via Claude (or future providers). Requires `APP_AI_DESCRIPTION_ENABLED=true`.

| Variable | Default | Purpose |
|----------|---------|---------|
| `APP_AI_DESCRIPTION_ENABLED` | `false` | Master flag for AI description. When `false`, no Claude calls, no placeholders, no edit jobs. |
| `APP_AI_DESCRIPTION_PROVIDER` | `claude` | Provider implementation. Currently only `claude` is supported. |
| `APP_AI_DESCRIPTION_LANGUAGE` | `en` | Reply language. `ru` or `en`. |
| `APP_AI_DESCRIPTION_SHORT_MAX` | `200` | Max characters of the short description (caption suffix). |
| `APP_AI_DESCRIPTION_DETAILED_MAX` | `1500` | Max characters of the detailed description (expandable blockquote). |
| `APP_AI_DESCRIPTION_MAX_FRAMES` | `10` | Max frames forwarded to the model per recording. |
| `APP_AI_DESCRIPTION_QUEUE_TIMEOUT` | `30s` | Max wait for a free concurrency slot. |
| `APP_AI_DESCRIPTION_TIMEOUT` | `60s` | Per-call describe timeout (including internal retries). |
| `APP_AI_DESCRIPTION_MAX_CONCURRENT` | `2` | Max simultaneous Claude requests. |
| `APP_AI_DESCRIPTION_RATE_LIMIT_ENABLED` | `true` | Enable sliding-window throttle on AI description invocations. When `false`, every recording with AI enabled gets a description request. |
| `APP_AI_DESCRIPTION_RATE_LIMIT_MAX` | `10` | Max invocations within the sliding window. Counter increments when a slot is granted; failed Claude calls (transport errors, retries) do not refund the slot. |
| `APP_AI_DESCRIPTION_RATE_LIMIT_WINDOW` | `1h` | Sliding-window length (Spring Boot `Duration`: `1h`, `30m`, `2h30m`, ...). When the limit is exceeded, the recording goes to Telegram as a plain notification — no caption placeholder, no second reply message, no edit-job, no Claude call. |

```

(Trailing blank line before `## Telegram` is intentional.)

- [ ] **Step 4.2: Stage and commit**

```bash
git add .claude/rules/configuration.md
git commit -m "$(cat <<'EOF'
docs(config): add AI Description section with rate-limit

Documents all APP_AI_DESCRIPTION_* env vars (the previous PR shipped
the feature but didn't add a row to configuration.md), plus the three
new rate-limit variables: ENABLED (default true), MAX (default 10),
WINDOW (default 1h). Notes that failed Claude calls do not refund the
slot, and that limit-exceed produces a placeholder-free Telegram
notification.
EOF
)"
```

---

## Task 5: Full build + code review

**Files:** none (process step)

**Goal:** Project-wide green build + external review.

- [ ] **Step 5.1: Dispatch superpowers:code-reviewer**

Per project CLAUDE.md: run code-reviewer BEFORE the final build. Dispatch the `superpowers:code-reviewer` agent with the prompt:

> Review this branch (`feature/description-rate-limit`) against the spec in `docs/superpowers/specs/2026-04-25-description-rate-limit-design.md`. Check: thread-safety of `DescriptionRateLimiter`, correctness of the cutoff comparison (`isAfter` semantics), the `ObjectProvider` short-circuit logic in `TelegramNotificationServiceImpl`, test coverage of boundary conditions, and that the WARN log doesn't fire when the supplier was already null. Report critical issues only.

If the reviewer raises critical issues, address them with focused commits and re-dispatch the agent until clean.

- [ ] **Step 5.2: Dispatch the build skill**

Tell the `build` skill:

> Run `./gradlew build` for the whole project. If ktlint fails: `./gradlew ktlintFormat`, then retry once. Report final status and the first 30 lines of any failure.

Expected: BUILD SUCCESSFUL. All tests in `:modules:ai-description:test`, `:modules:telegram:test`, and other modules pass.

- [ ] **Step 5.3: Manual smoke check (optional, only if a dev environment is available)**

Set `APP_AI_DESCRIPTION_RATE_LIMIT_MAX=2` and `APP_AI_DESCRIPTION_RATE_LIMIT_WINDOW=5m` in the dev profile. Trigger 3 recordings within 30 seconds. Verify:
- First two notifications include the AI placeholder caption and receive an edit with the description.
- Third notification arrives as a plain text/photo without the placeholder hint and without a second reply message.
- Logs show one `WARN AI description rate limit reached, skipping description for recording <uuid>` line for the third recording.

- [ ] **Step 5.4: Final commit (only if anything fixed during code review)**

```bash
git status   # confirm working tree is clean if no fixes were needed
```

If there are pending changes from code-review fixes, group them into a single commit:

```bash
git add <changed files>
git commit -m "fix: address code-review feedback on rate limiter"
```

---

## Cleanup before PR

Project-level rule from `~/.claude/CLAUDE.md`: design and plan documents from `docs/superpowers/` must NOT appear in the PR diff. They live in branch git history and are removed before opening the PR.

- [ ] **Step 6.1: Remove planning artefacts from the branch tree (when ready to open PR)**

```bash
git rm docs/superpowers/specs/2026-04-25-description-rate-limit-design.md \
       docs/superpowers/plans/2026-04-25-description-rate-limit.md
git commit -m "chore: drop planning docs before PR (preserved in git history)"
```

The two files remain accessible at the earlier commits on this branch (`docs(spec):` for the spec, and the plan-creation commit for the plan).
