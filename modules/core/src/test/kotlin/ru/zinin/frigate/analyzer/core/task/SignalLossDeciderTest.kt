package ru.zinin.frigate.analyzer.core.task

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class SignalLossDeciderTest {
    private val now = Instant.parse("2026-04-25T10:00:00Z")
    private val threshold = Duration.ofMinutes(3)

    private fun cfg(inGrace: Boolean = false) = Config(threshold = threshold, inGrace = inGrace)

    private fun obs(maxRec: Instant) = Observation(maxRecordTs = maxRec, now = now)

    @Test
    fun `null + healthy gap returns Healthy without event`() {
        val maxRec = now.minusSeconds(60)
        val d = decide("cam_a", prev = null, obs = obs(maxRec), cfg = cfg())
        assertThat(d.newState).isEqualTo(CameraSignalState.Healthy(maxRec))
        assertThat(d.event).isNull()
    }

    @Test
    fun `null + lost gap + in grace returns SignalLost(sent=false) without event`() {
        val maxRec = now.minus(threshold).minusSeconds(30)
        val d = decide("cam_a", prev = null, obs = obs(maxRec), cfg = cfg(inGrace = true))
        assertThat(d.newState).isEqualTo(
            CameraSignalState.SignalLost(maxRec, notificationSent = false),
        )
        assertThat(d.event).isNull()
    }

    @Test
    fun `null + lost gap + not in grace returns SignalLost(sent=true) and Loss event`() {
        val maxRec = now.minus(threshold).minusSeconds(30)
        val d = decide("cam_a", prev = null, obs = obs(maxRec), cfg = cfg(inGrace = false))
        assertThat(d.newState).isEqualTo(
            CameraSignalState.SignalLost(maxRec, notificationSent = true),
        )
        assertThat(d.event).isInstanceOf(SignalLossEvent.Loss::class.java)
        val loss = d.event as SignalLossEvent.Loss
        assertThat(loss.camId).isEqualTo("cam_a")
        assertThat(loss.lastSeenAt).isEqualTo(maxRec)
    }

    @Test
    fun `Healthy + healthy gap advances Healthy without event`() {
        val prev = CameraSignalState.Healthy(now.minusSeconds(120))
        val maxRec = now.minusSeconds(10)
        val d = decide("cam_a", prev = prev, obs = obs(maxRec), cfg = cfg())
        assertThat(d.newState).isEqualTo(CameraSignalState.Healthy(maxRec))
        assertThat(d.event).isNull()
    }

    @Test
    fun `Healthy + lost gap + not in grace uses fresh maxRecordTs (not stale prev) for SignalLost and Loss`() {
        // KEY ASSERTION: lastSeenAt is the fresh maxRecordTs, not the stale prev.lastSeenAt
        val prevSeen = now.minusSeconds(600) // 10 min ago
        val maxRec = now.minus(threshold).minusSeconds(30) // 3 min 30 s ago — NEWER than prev
        val prev = CameraSignalState.Healthy(prevSeen)
        val d = decide("cam_a", prev = prev, obs = obs(maxRec), cfg = cfg(inGrace = false))
        assertThat(d.newState).isEqualTo(
            CameraSignalState.SignalLost(maxRec, notificationSent = true),
        )
        val loss = d.event as SignalLossEvent.Loss
        assertThat(loss.lastSeenAt).isEqualTo(maxRec)
        assertThat(loss.lastSeenAt).isNotEqualTo(prevSeen)
    }

    @Test
    fun `Healthy + lost gap + in grace returns SignalLost(sent=false) without event`() {
        val prev = CameraSignalState.Healthy(now.minusSeconds(600))
        val maxRec = now.minus(threshold).minusSeconds(30)
        val d = decide("cam_a", prev = prev, obs = obs(maxRec), cfg = cfg(inGrace = true))
        assertThat(d.newState).isEqualTo(
            CameraSignalState.SignalLost(maxRec, notificationSent = false),
        )
        assertThat(d.event).isNull()
    }

    @Test
    fun `SignalLost(sent=true) + healthy gap returns Healthy and Recovery with downtime from prev_lastSeenAt`() {
        val prevSeen = now.minusSeconds(600)
        val maxRec = now.minusSeconds(10)
        val prev = CameraSignalState.SignalLost(prevSeen, notificationSent = true)
        val d = decide("cam_a", prev = prev, obs = obs(maxRec), cfg = cfg())
        assertThat(d.newState).isEqualTo(CameraSignalState.Healthy(maxRec))
        val recovery = d.event as SignalLossEvent.Recovery
        assertThat(recovery.camId).isEqualTo("cam_a")
        assertThat(recovery.downtime).isEqualTo(Duration.between(prevSeen, maxRec))
    }

    @Test
    fun `SignalLost(sent=false) + healthy gap returns Healthy without Recovery (silent — no orphan recovery)`() {
        // Critical-bug guard: if the original Loss was never user-visible (suppressed during
        // startupGrace), the Recovery must also be silent. Otherwise the user receives a "Camera
        // back online" message without ever seeing a "Camera lost signal" — confusing UX that
        // would defeat the entire startupGrace deferred-alert design.
        val prevSeen = now.minusSeconds(600)
        val maxRec = now.minusSeconds(10)
        val prev = CameraSignalState.SignalLost(prevSeen, notificationSent = false)
        val d = decide("cam_a", prev = prev, obs = obs(maxRec), cfg = cfg())
        assertThat(d.newState).isEqualTo(CameraSignalState.Healthy(maxRec))
        assertThat(d.event).isNull()
    }

    @Test
    fun `SignalLost(sent=true) + still lost is no-op without event`() {
        val prev = CameraSignalState.SignalLost(now.minusSeconds(600), notificationSent = true)
        val maxRec = now.minus(threshold).minusSeconds(60)
        val d = decide("cam_a", prev = prev, obs = obs(maxRec), cfg = cfg())
        assertThat(d.newState).isEqualTo(prev) // unchanged
        assertThat(d.event).isNull()
    }

    @Test
    fun `LATE ALERT - SignalLost(sent=false) + still lost + not in grace fires Loss with prev_lastSeenAt`() {
        val prevSeen = now.minusSeconds(600)
        val prev = CameraSignalState.SignalLost(prevSeen, notificationSent = false)
        val maxRec = now.minus(threshold).minusSeconds(60)
        val d = decide("cam_a", prev = prev, obs = obs(maxRec), cfg = cfg(inGrace = false))
        assertThat(d.newState).isEqualTo(
            CameraSignalState.SignalLost(prevSeen, notificationSent = true),
        )
        val loss = d.event as SignalLossEvent.Loss
        assertThat(loss.lastSeenAt).isEqualTo(prevSeen)
    }

    @Test
    fun `SignalLost(sent=false) + still lost + in grace stays deferred without event`() {
        val prevSeen = now.minusSeconds(600)
        val prev = CameraSignalState.SignalLost(prevSeen, notificationSent = false)
        val maxRec = now.minus(threshold).minusSeconds(60)
        val d = decide("cam_a", prev = prev, obs = obs(maxRec), cfg = cfg(inGrace = true))
        assertThat(d.newState).isEqualTo(prev) // unchanged
        assertThat(d.event).isNull()
    }

    @Test
    fun `boundary - gap exactly equal to threshold is healthy (strict greater-than)`() {
        val maxRec = now.minus(threshold) // exactly threshold
        val d = decide("cam_a", prev = null, obs = obs(maxRec), cfg = cfg())
        assertThat(d.newState).isEqualTo(CameraSignalState.Healthy(maxRec))
        assertThat(d.event).isNull()
    }

    @Test
    fun `clock skew - maxRecordTs in the future is clamped to gap zero, healthy`() {
        val maxRec = now.plusSeconds(5)
        val d = decide("cam_a", prev = null, obs = obs(maxRec), cfg = cfg())
        assertThat(d.newState).isEqualTo(CameraSignalState.Healthy(maxRec))
        assertThat(d.event).isNull()
    }

    @Test
    fun `Loss event carries computed gap`() {
        val maxRec = now.minus(threshold).minusSeconds(45) // gap = 3 min 45 s
        val d = decide("cam_a", prev = null, obs = obs(maxRec), cfg = cfg(inGrace = false))
        val loss = d.event as SignalLossEvent.Loss
        assertThat(loss.gap).isEqualTo(Duration.ofMinutes(3).plusSeconds(45))
    }
}
