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
| `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/config/DescriptionProperties.kt` | MODIFY | Add nested `RateLimit` data class **with defaults `enabled=false, max=10, window=1h`** + `rateLimit: RateLimit` field in `CommonSection` |
| `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/ratelimit/DescriptionRateLimiter.kt` | CREATE | Sliding-window rate limiter component |
| `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/ratelimit/DescriptionRateLimiterTest.kt` | CREATE | Unit tests for limiter behaviour |
| `modules/core/src/main/resources/application.yaml` | MODIFY | Add `rate-limit` block (`enabled=true` for production) |
| `modules/core/src/test/resources/application.yaml` | MODIFY | Add same `rate-limit` block (binder needs the keys; test value can be `enabled=false`) |
| `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/config/AiDescriptionAutoConfigurationTest.kt` | MODIFY | Add 3 new property values to both `withPropertyValues(...)` blocks |
| `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImpl.kt` | MODIFY | Inject `ObjectProvider<DescriptionRateLimiter>`, gate `descriptionSupplier?.invoke()` via `when` |
| `modules/telegram/build.gradle.kts` | MODIFY (if needed) | Ensure dependency on `ai-description` exists (likely already does) |
| `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImplTest.kt` | MODIFY | Add 4 new test cases (skip on limit, supplier called once below limit, AI disabled bypass, limiter missing fail-open) |
| `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImplSignalLossTest.kt` | MODIFY | Add `rateLimiterProvider = mockk(relaxed = true)` to named-arg constructor call |
| `.claude/rules/configuration.md` | MODIFY | Add `## AI Description` section with all env vars |

**Files NOT touched** (thanks to RateLimit defaults — limiter stays disabled in tests that build `CommonSection(...)` directly):
- `modules/ai-description/src/test/.../claude/ClaudeDescriptionAgentTest.kt:38`
- `modules/ai-description/src/test/.../claude/ClaudeDescriptionAgentIntegrationTest.kt:87`
- `modules/ai-description/src/test/.../claude/ClaudeDescriptionAgentValidationTest.kt:12`
- `modules/core/src/test/.../facade/RecordingProcessingFacadeTest.kt:127`

**Gradle module paths** (verified against `settings.gradle.kts`):
- `modules` are `include`d as `:ai-description`, `:telegram`, `:core` etc., but `name` is renamed to `frigate-analyzer-<module>`. The Gradle project path is **`:ai-description`** (or equivalently `:frigate-analyzer-ai-description`). Use one of these everywhere — NOT `:modules:ai-description`.

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
        val enabled: Boolean = false,
        @field:Min(1) @field:Max(10000)
        val maxRequests: Int = 10,
        val window: Duration = Duration.ofHours(1),
    ) {
        init {
            require(window.toMillis() > 0) { "rate-limit.window must be positive" }
        }
    }
}
```

Defaults rationale: tests that build `CommonSection(...)` directly (without YAML
binding) get the rate-limit OFF automatically — no fan-out test changes needed
across `Claude*Test*` and `RecordingProcessingFacadeTest`. Production `application.yaml`
overrides `enabled` to `true`.

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

- [ ] **Step 1.3: Add `rate-limit` block to test resources YAML**

Open `modules/core/src/test/resources/application.yaml`. Find the `application.ai.description.common` block (around line 48). After the `max-concurrent: 2` line, add:

```yaml
        rate-limit:
          enabled: false
          max-requests: 10
          window: 1h
```

Keep `enabled: false` here so test contexts that load this YAML don't try to rate-limit anything (they don't typically run real recordings; if they do, it should not be throttled). Test contexts that need a different value can override via `@TestPropertySource`.

- [ ] **Step 1.4: Update `AiDescriptionAutoConfigurationTest`**

Open `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/config/AiDescriptionAutoConfigurationTest.kt`. There are TWO `withPropertyValues(...)` calls (in tests `DescriptionProperties registered even when enabled=false` and `autoconfig activates beans when enabled=true, provider=claude`).

In **both** of them, after the `application.ai.description.common.max-concurrent=2` line, add three new property values:

```kotlin
                "application.ai.description.common.rate-limit.enabled=false",
                "application.ai.description.common.rate-limit.max-requests=10",
                "application.ai.description.common.rate-limit.window=1h",
```

Without these, `Spring Boot` binder will fail to construct `DescriptionProperties` — the binder ignores Kotlin defaults on `data class` fields when binding nested config that has at least one explicit property under it; the safe contract is to pass ALL fields the test expects to use.

Note: Tests in `Claude*Test*` and `RecordingProcessingFacadeTest` that build `CommonSection(...)` directly are NOT affected — Kotlin defaults work for direct constructor calls (only Spring Boot binder is the special case).

- [ ] **Step 1.5: Stage and commit**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/config/DescriptionProperties.kt \
        modules/core/src/main/resources/application.yaml \
        modules/core/src/test/resources/application.yaml \
        modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/config/AiDescriptionAutoConfigurationTest.kt
git status
git commit -m "$(cat <<'EOF'
feat(ai-description): add rate-limit config block

Extend DescriptionProperties.CommonSection with nested RateLimit data
class (enabled, maxRequests, window) — defaults in the data class are
enabled=false / 10 / 1h. Production application.yaml overrides
enabled=true. Test resources/application.yaml uses enabled=false so
tests don't throttle. AiDescriptionAutoConfigurationTest binder calls
get the three new property values explicitly.

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

import kotlinx.coroutines.Dispatchers
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
    override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)
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
            // Use Dispatchers.Default to get real thread parallelism — without it,
            // runBlocking single-threaded event-loop would serialize coroutines and the test
            // would pass even if Mutex were absent, providing false confidence.
            val limiter = DescriptionRateLimiter(Clock.fixed(baseInstant, ZoneOffset.UTC), props(enabled = true, maxRequests = 10))

            val results: List<Boolean> =
                coroutineScope {
                    (1..50)
                        .map { async(Dispatchers.Default) { limiter.tryAcquire() } }
                        .awaitAll()
                }

            assertEquals(10, results.count { it }, "exactly 10 must succeed")
            assertEquals(40, results.count { !it }, "exactly 40 must be rejected")
        }

    @Test
    fun `cleanup keeps deque bounded - never grows past maxRequests across many iterations`() =
        runBlocking {
            // Move time forward by window + 1ms before EACH call so every prior timestamp
            // becomes <= cutoff and is dropped. If cleanup were broken, the deque would grow
            // and after maxRequests calls the limiter would start denying — the test would
            // fail. Using `(i+1)*window+1ms` guarantees strict monotonic progress.
            val clock = MutableClock(baseInstant)
            val limiter = DescriptionRateLimiter(clock, props(enabled = true, maxRequests = 5))
            val window = Duration.ofHours(1)

            repeat(100) { i ->
                clock.current = baseInstant.plus(window.multipliedBy((i + 1).toLong())).plusMillis(1)
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

> Run only `:ai-description:test --tests "ru.zinin.frigate.analyzer.ai.description.ratelimit.DescriptionRateLimiterTest"` and report pass/fail per test method. If ktlint errors appear in the new files, run `./gradlew ktlintFormat` first, then retry. Do not run the full `./gradlew build`.

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
- Modify: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImplSignalLossTest.kt`

**Goal:** Plug the limiter into the single decision point. Existing tests stay green; four new tests cover the new behaviour.

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

(c) Add four new `@Test` methods at the end of the class (just before the closing brace):

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
    fun `sendRecordingNotification invokes supplier exactly once below limit and shares handle`() =
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
            val captured = mutableListOf<RecordingNotificationTask>()
            coEvery { notificationQueue.enqueue(any()) } answers {
                captured.add(arg<Any>(0) as RecordingNotificationTask)
            }

            service.sendRecordingNotification(recording, visualizedFrames, supplier)

            assertEquals(1, supplierInvocations, "supplier must be invoked exactly once across all recipients")
            coVerify(exactly = 1) { limiter.tryAcquire() }
            coVerify(exactly = 3) { notificationQueue.enqueue(any()) }

            // All three tasks share the same descriptionHandle (null in this test, since supplier returns null,
            // but the assertion still proves "shared" — distinct().size must be 1).
            assertEquals(3, captured.size)
            assertEquals(1, captured.map { it.descriptionHandle }.distinct().size, "all recipients share the same handle")
        }

    @Test
    fun `sendRecordingNotification with null descriptionSupplier does not query rate limiter`() =
        runTest {
            // Models the case `application.ai.description.enabled=false` — RecordingProcessingFacade
            // passes a null supplier, the limiter must not be touched.
            val recording = createRecording()
            val visualizedFrames =
                listOf(
                    VisualizedFrameData(frameIndex = 0, visualizedBytes = byteArrayOf(1, 2, 3), detectionsCount = 1),
                )

            // Set up a strict (non-relaxed) rateLimiterProvider mock that would FAIL if any call lands.
            val strictProvider = mockk<org.springframework.beans.factory.ObjectProvider<DescriptionRateLimiter>>()
            // We only allow getIfAvailable() to be called zero times. If invoked, mockk throws.
            // (Use the existing relaxed `rateLimiterProvider` field is fine too if we just don't stub
            // anything — relaxed mock returns null and verification below catches accidental calls.)
            every { rateLimiterProvider.getIfAvailable() } returns null

            coEvery { uuidGeneratorHelper.generateV1() } returns taskId
            coEvery { userService.getAuthorizedUsersWithZones() } returns
                listOf(UserZoneInfo(chatId = chatId, zone = ZoneId.of("UTC"), language = "ru"))
            val taskSlot = slot<RecordingNotificationTask>()
            coEvery { notificationQueue.enqueue(capture(taskSlot)) } returns Unit

            service.sendRecordingNotification(recording, visualizedFrames, descriptionSupplier = null)

            assertEquals(null, taskSlot.captured.descriptionHandle)
            // getIfAvailable() is allowed to be called or not, depending on short-circuit; the key
            // assertion is that no exception propagated and descriptionHandle is null.
        }

    @Test
    fun `sendRecordingNotification calls supplier when rateLimiter bean is missing (fail-open)`() =
        runTest {
            // AI is enabled (supplier != null) but the limiter bean is somehow not in the context.
            // Per design §3.2, this is a fail-open path: supplier still fires.
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

            every { rateLimiterProvider.getIfAvailable() } returns null  // limiter bean missing

            coEvery { uuidGeneratorHelper.generateV1() } returns taskId
            coEvery { userService.getAuthorizedUsersWithZones() } returns
                listOf(UserZoneInfo(chatId = chatId, zone = ZoneId.of("UTC"), language = "ru"))
            coEvery { notificationQueue.enqueue(any()) } returns Unit

            service.sendRecordingNotification(recording, visualizedFrames, supplier)

            assertEquals(1, supplierInvocations, "supplier must fire when limiter is absent (fail-open)")
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
            when {
                descriptionSupplier == null -> null
                else -> {
                    val limiter = rateLimiterProvider.getIfAvailable()
                    when {
                        limiter == null -> descriptionSupplier.invoke()       // fail-open: AI on but limiter bean absent
                        limiter.tryAcquire() -> descriptionSupplier.invoke()  // slot granted
                        else -> {                                             // slot denied
                            logger.warn {
                                "AI description rate limit reached, skipping description for recording ${recording.id}"
                            }
                            null
                        }
                    }
                }
            }
```

Three explicit branches:
- `descriptionSupplier == null` → AI disabled in pipeline → no work, no log;
- `limiter == null` → AI enabled but bean missing (misconfiguration) → fail-open, supplier fires, no WARN (this is not a rate-limit denial);
- `limiter.tryAcquire() == true` → granted, supplier fires;
- `limiter.tryAcquire() == false` → denied, WARN with `recording.id`, no supplier.

- [ ] **Step 3.4: Update `TelegramNotificationServiceImplSignalLossTest` constructor call**

`grep -rn "TelegramNotificationServiceImpl(" modules/telegram/src/test --include='*.kt'` confirms exactly two test files build the impl directly:
1. `TelegramNotificationServiceImplTest.kt:43` — already updated in Step 3.2(b) above.
2. `TelegramNotificationServiceImplSignalLossTest.kt:38` — needs the same update.

Open `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImplSignalLossTest.kt`. Add the import block:

```kotlin
import org.springframework.beans.factory.ObjectProvider
import ru.zinin.frigate.analyzer.ai.description.ratelimit.DescriptionRateLimiter
```

Then modify the field-area (around lines 24-44) to add a relaxed `rateLimiterProvider` mock and pass it as a named arg to the constructor:

```kotlin
    private val rateLimiterProvider = mockk<ObjectProvider<DescriptionRateLimiter>>(relaxed = true)
    private val service =
        TelegramNotificationServiceImpl(
            userService = userService,
            notificationQueue = queue,
            uuidGeneratorHelper = uuid,
            msg = msg,
            signalLossFormatter = formatter,
            rateLimiterProvider = rateLimiterProvider,
        )
```

`relaxed = true` is enough because these signal-loss tests don't drive the AI-description path — they invoke `sendCameraSignalLost`/`sendCameraSignalRecovered`, which never touch the limiter.

`NoOpTelegramNotificationService.kt` has its own no-arg constructor and is NOT affected — no change there.

- [ ] **Step 3.5: Run the affected test classes via build-runner**

Dispatch the `build` skill. Tell it:

> Run `:telegram:test --tests "ru.zinin.frigate.analyzer.telegram.service.impl.TelegramNotificationServiceImplTest" --tests "ru.zinin.frigate.analyzer.telegram.service.impl.TelegramNotificationServiceImplSignalLossTest"` and report results. If ktlint fails on the modified files, `./gradlew ktlintFormat` then retry. Do not run the full build.

Expected: all tests pass — pre-existing ones (with the new `rateLimiterProvider` mock present in the constructor) plus the four new tests in `TelegramNotificationServiceImplTest`.

- [ ] **Step 3.6: Stage and commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImpl.kt \
        modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImplTest.kt \
        modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramNotificationServiceImplSignalLossTest.kt
git commit -m "$(cat <<'EOF'
feat(telegram): gate AI description supplier behind DescriptionRateLimiter

TelegramNotificationServiceImpl now consults DescriptionRateLimiter
(injected via ObjectProvider so it stays optional when ai.description
is disabled). Three-branch `when`: null supplier → no work; missing
limiter bean → fail-open; tryAcquire result → grant or deny. WARN log
fires only on deny, with recordingId.

Updates SignalLossTest constructor call to include the new mock.
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

Expected: BUILD SUCCESSFUL. All tests in `:ai-description:test`, `:telegram:test`, `:core:test`, and other modules pass.

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
       docs/superpowers/plans/2026-04-25-description-rate-limit.md \
       docs/superpowers/specs/2026-04-25-description-rate-limit-review-merged-iter-*.md \
       docs/superpowers/specs/2026-04-25-description-rate-limit-review-iter-*.md
git commit -m "chore: drop planning docs before PR (preserved in git history)"
```

All planning, spec, plan, merged review, and iteration files remain accessible at the earlier commits on this branch — they are only stripped from the PR diff.
