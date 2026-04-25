package ru.zinin.frigate.analyzer.core.task

import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ru.zinin.frigate.analyzer.core.config.properties.SignalLossProperties
import ru.zinin.frigate.analyzer.model.dto.LastRecordingPerCameraDto
import ru.zinin.frigate.analyzer.service.repository.RecordingEntityRepository
import ru.zinin.frigate.analyzer.telegram.service.TelegramNotificationService
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class SignalLossMonitorTaskTest {
    private val properties =
        SignalLossProperties(
            enabled = true,
            threshold = Duration.ofMinutes(3),
            pollInterval = Duration.ofSeconds(30),
            activeWindow = Duration.ofHours(24),
            startupGrace = Duration.ofMinutes(5),
        )
    private val repository = mockk<RecordingEntityRepository>()
    private val notifier = mockk<TelegramNotificationService>(relaxed = true)
    private val baseInstant: Instant = Instant.parse("2026-04-25T10:00:00Z")

    private fun mutableClockTask(initial: Instant): Pair<SignalLossMonitorTask, MutableClock> {
        // The task's `init()` captures `startedAt = clock.instant()`. We always seed `startedAt` at
        // [baseInstant] and then advance the clock to `initial` AFTER init, so the first tick can
        // land before, exactly at, or past the grace window depending on caller intent — without
        // pinning `startedAt` to the same wall-clock value as the first tick (which would always
        // make `inGrace=true` regardless of `initial`).
        val mutableClock = MutableClock(baseInstant)
        val task =
            SignalLossMonitorTask(
                properties = properties,
                repository = repository,
                notificationService = notifier,
                clock = mutableClock,
            ).also { it.init() }
        mutableClock.advance(Duration.between(baseInstant, initial))
        return task to mutableClock
    }

    @BeforeEach
    fun setUp() {
        clearMocks(repository, notifier)
    }

    @Test
    fun `cleanup keeps SignalLost but removes Healthy when camera falls out of activeWindow`() =
        runTest {
            val (task, clock) = mutableClockTask(baseInstant.plus(properties.startupGrace).plusSeconds(1))

            // Tick 1: seed cam_a Healthy, cam_b will be SignalLost(sent=true) since we're past grace
            val camBLastSeen = clock.instant().minus(properties.threshold).minusSeconds(60)
            coEvery { repository.findLastRecordingPerCamera(any()) } returns
                listOf(
                    LastRecordingPerCameraDto("cam_a", clock.instant().minusSeconds(10)),
                    LastRecordingPerCameraDto("cam_b", camBLastSeen),
                )
            task.tick()

            // Tick 2: both cameras absent from stats (fell out of activeWindow / no recordings).
            // cam_a (Healthy) → removed; cam_b (SignalLost) → KEPT so future recovery still fires.
            clock.advance(Duration.ofMinutes(1))
            coEvery { repository.findLastRecordingPerCamera(any()) } returns emptyList()
            task.tick()

            // Tick 3: both cameras return as healthy.
            // cam_a re-enters with prev=null → silent Healthy seed (no Loss alert).
            // cam_b re-enters with prev=SignalLost (preserved!) → Recovery emitted with correct downtime.
            // The decider's recovery branch uses `Duration.between(prev.lastSeenAt, obs.maxRecordTs)`
            // where `prev.lastSeenAt == camBLastSeen` (seeded in tick 1).
            clock.advance(Duration.ofMinutes(1))
            val camBRecovered = clock.instant().minusSeconds(5)
            coEvery { repository.findLastRecordingPerCamera(any()) } returns
                listOf(
                    LastRecordingPerCameraDto("cam_a", clock.instant().minusSeconds(5)),
                    LastRecordingPerCameraDto("cam_b", camBRecovered),
                )
            // Reset call counters AND re-prime stubs (clearMocks wipes both).
            clearMocks(notifier)
            coEvery { notifier.sendCameraSignalRecovered(any(), any()) } returns Unit
            coEvery { notifier.sendCameraSignalLost(any(), any(), any()) } returns Unit
            task.tick()

            // cam_a re-entered as fresh (prev=null, healthy) → silent.
            coVerify(exactly = 0) { notifier.sendCameraSignalLost("cam_a", any(), any()) }
            // cam_b: state preserved across the empty tick → Recovery fires with downtime
            // computed from the original loss `lastSeenAt` to the fresh `maxRecordTs`.
            coVerify(exactly = 1) {
                notifier.sendCameraSignalRecovered(
                    "cam_b",
                    Duration.between(camBLastSeen, camBRecovered),
                )
            }
        }

    @Test
    fun `late alert after grace ends for camera that was lost during grace`() =
        runTest {
            // Start exactly at baseInstant — initial tick is inside the 5-minute grace window.
            val (task, clock) = mutableClockTask(baseInstant)
            val lastSeen = clock.instant().minus(properties.threshold).minusSeconds(60)
            coEvery { repository.findLastRecordingPerCamera(any()) } returns
                listOf(LastRecordingPerCameraDto("cam_a", lastSeen))

            // Tick during grace: state seeded as SignalLost(sent=false), no notification dispatched.
            task.tick()
            coVerify(exactly = 0) { notifier.sendCameraSignalLost(any(), any(), any()) }

            // Advance past grace; same data → LATE ALERT fires (deferred → fires on first
            // post-grace tick).
            clock.advance(properties.startupGrace.plusSeconds(1))
            task.tick()
            coVerify(exactly = 1) {
                notifier.sendCameraSignalLost("cam_a", lastSeen, clock.instant())
            }

            // Subsequent tick (still lost, sent=true now) → no repeat.
            clock.advance(Duration.ofMinutes(1))
            task.tick()
            coVerify(exactly = 1) { notifier.sendCameraSignalLost("cam_a", any(), any()) }
        }

    @Test
    fun `tick swallows repository exception and leaves state unchanged`() =
        runTest {
            val (task, clock) = mutableClockTask(baseInstant.plus(properties.startupGrace).plusSeconds(1))
            coEvery { repository.findLastRecordingPerCamera(any()) } returns
                listOf(LastRecordingPerCameraDto("cam_a", clock.instant().minusSeconds(10)))
            task.tick() // Healthy seeded.

            // Mid-test failure: repository throws. tick() must NOT throw and must NOT mutate state.
            clock.advance(Duration.ofMinutes(1))
            coEvery { repository.findLastRecordingPerCamera(any()) } throws RuntimeException("DB exploded")
            task.tick()

            // Subsequent tick: cam_a healthy gap remains within threshold → no Loss alert.
            // (Confirms that the prior Healthy state survived the exception path.)
            clock.advance(Duration.ofMinutes(1))
            coEvery { repository.findLastRecordingPerCamera(any()) } returns
                listOf(LastRecordingPerCameraDto("cam_a", clock.instant().minusSeconds(5)))
            task.tick()
            coVerify(exactly = 0) { notifier.sendCameraSignalLost(any(), any(), any()) }
            coVerify(exactly = 0) { notifier.sendCameraSignalRecovered(any(), any()) }
        }

    @Test
    fun `tick re-throws CancellationException`() {
        // Plain @Test (no runTest) on purpose: runTest's TestScope intercepts cancellation in ways
        // that interact poorly with `runBlocking { task.tick() }`. A bare `runBlocking` keeps the
        // exception path identical to what production sees when Spring's scheduler is cancelled.
        val (task, _) = mutableClockTask(baseInstant.plus(properties.startupGrace).plusSeconds(1))
        coEvery { repository.findLastRecordingPerCamera(any()) } throws CancellationException("shutdown")

        assertThrows<CancellationException> {
            runBlocking { task.tick() }
        }
    }

    @Test
    fun `enqueue throw retains transition (no repeat next tick)`() =
        runTest {
            val (task, clock) = mutableClockTask(baseInstant.plus(properties.startupGrace).plusSeconds(1))
            val lastSeen = clock.instant().minus(properties.threshold).minusSeconds(60)
            coEvery { repository.findLastRecordingPerCamera(any()) } returns
                listOf(LastRecordingPerCameraDto("cam_a", lastSeen))
            coEvery {
                notifier.sendCameraSignalLost(any(), any(), any())
            } throws RuntimeException("queue full")

            // Tick 1: decide() emits Loss → emitLoss() throws (and swallows).
            // The state mutation `state[camId] = SignalLost(..., sent = true)` happens BEFORE the
            // emit attempt, so even though the dispatch fails we've already recorded "alerted" in
            // memory. This is intentional: an at-most-once delivery model — better to drop one
            // alert on transient queue failure than to spam on every subsequent tick.
            task.tick()

            clock.advance(Duration.ofSeconds(30))
            clearMocks(notifier)
            coEvery { notifier.sendCameraSignalLost(any(), any(), any()) } returns Unit
            // Same DB stats → still lost → decide() sees prev=SignalLost(sent=true) → no event.
            task.tick()
            coVerify(exactly = 0) { notifier.sendCameraSignalLost(any(), any(), any()) }
        }

    /** Simple mutable clock for tests; not thread-safe but tests run serially. */
    private class MutableClock(
        private var current: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current

        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }
}
