package ru.zinin.frigate.analyzer.core.loadbalancer

import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.core.config.properties.DetectServerProperties
import ru.zinin.frigate.analyzer.core.config.properties.RequestConfig
import java.time.Instant
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ServerStateTest {
    private fun newServerState(id: String = "s1"): ServerState =
        ServerState(
            id = id,
            properties =
                DetectServerProperties(
                    schema = "http",
                    host = "localhost",
                    port = 12345,
                    frameRequests = RequestConfig(simultaneousCount = 1, priority = 0),
                    framesExtractRequests = RequestConfig(simultaneousCount = 1, priority = 0),
                    visualizeRequests = RequestConfig(simultaneousCount = 1, priority = 0),
                    videoVisualizeRequests = RequestConfig(simultaneousCount = 1, priority = 0),
                ),
        )

    // ----- HealthSnapshot defaults & accessors -----

    @Test
    fun `initial snapshot is alive=false, lastCheckTimestamp=EPOCH`() {
        val s = newServerState()
        assertFalse(s.alive)
        assertEquals(Instant.EPOCH, s.lastCheckTimestamp)
        assertEquals(ServerState.HealthSnapshot(alive = false, lastCheckTimestamp = Instant.EPOCH), s.snapshot())
    }

    @Test
    fun `updateHealth returns post-update snapshot`() {
        val s = newServerState()
        val now = Instant.parse("2026-05-28T10:00:00Z")
        val result = s.updateHealth { it.copy(alive = true, lastCheckTimestamp = now) }
        assertEquals(ServerState.HealthSnapshot(alive = true, lastCheckTimestamp = now), result)
        // Accessors observe the new snapshot.
        assertTrue(s.alive)
        assertEquals(now, s.lastCheckTimestamp)
    }

    @Test
    fun `getAndUpdateHealth returns pre-update snapshot`() {
        val s = newServerState()
        // Seed a non-default state so we can tell pre-update from post-update.
        val seed = Instant.parse("2026-05-28T09:00:00Z")
        s.updateHealth { it.copy(alive = true, lastCheckTimestamp = seed) }

        val now = Instant.parse("2026-05-28T10:00:00Z")
        val prev = s.getAndUpdateHealth { it.copy(alive = false, lastCheckTimestamp = now) }

        // Returned snapshot reflects PRE-update values.
        assertEquals(ServerState.HealthSnapshot(alive = true, lastCheckTimestamp = seed), prev)
        // Storage holds the POST-update values.
        assertFalse(s.alive)
        assertEquals(now, s.lastCheckTimestamp)
    }

    // ----- equals / hashCode / toString -----

    @Test
    fun `equals and hashCode are id-based regardless of live snapshot`() {
        val a = newServerState(id = "same-id")
        val b = newServerState(id = "same-id")
        // Make their live snapshots diverge — equality must still hold by id alone.
        a.updateHealth { it.copy(alive = true, lastCheckTimestamp = Instant.parse("2026-05-28T01:00:00Z")) }
        b.updateHealth { it.copy(alive = false, lastCheckTimestamp = Instant.parse("2026-05-28T02:00:00Z")) }

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `equals returns false for different ids`() {
        val a = newServerState(id = "s1")
        val b = newServerState(id = "s2")
        assertNotEquals(a, b)
    }

    @Test
    fun `toString embeds id, alive, lastCheckTimestamp from the live snapshot`() {
        val s = newServerState(id = "s-tostr")
        val now = Instant.parse("2026-05-28T10:00:00Z")
        s.updateHealth { it.copy(alive = true, lastCheckTimestamp = now) }
        val str = s.toString()
        assertTrue(str.contains("id=s-tostr"))
        assertTrue(str.contains("alive=true"))
        assertTrue(str.contains(now.toString()))
    }

    // ----- Concurrent transition-detection contract (KDoc: 3 potential writers) -----

    @Test
    fun `getAndUpdateHealth race — exactly one writer observes the alive=false→true transition`() {
        // Replays the production transition-detection pattern in ServerHealthMonitor: the writer
        // calls getAndUpdateHealth { copy(alive=true, ...) } and only the one that observed
        // prev.alive==false logs "Server X is now ALIVE". With N concurrent threads racing on
        // the same false→true edge, exactly one must observe the edge — never zero (we'd miss
        // the log entirely) and never more than one (we'd log duplicates).
        //
        // The contract holds because AtomicReference.getAndUpdate's CAS retries until the
        // compareAndSet succeeds — each thread sees the prior-state snapshot from its WINNING
        // CAS, not from the first read. So all losers see prev.alive=true (set by the winner)
        // and the lone winner sees prev.alive=false.
        repeat(20) {
            val s = newServerState()
            val transitionObservers = AtomicInteger(0)
            val threadCount = 16
            val barrier = CyclicBarrier(threadCount)
            val threads =
                (1..threadCount).map { idx ->
                    Thread {
                        barrier.await()
                        val now = Instant.ofEpochMilli(idx.toLong())
                        val prev = s.getAndUpdateHealth { it.copy(alive = true, lastCheckTimestamp = now) }
                        if (!prev.alive) transitionObservers.incrementAndGet()
                    }
                }
            threads.forEach { it.start() }
            threads.forEach { it.join() }

            assertEquals(1, transitionObservers.get(), "exactly one writer must observe the false→true edge")
            assertTrue(s.alive, "final state must be alive=true after the race")
        }
    }

    @Test
    fun `getAndUpdateHealth race — same edge in the opposite direction (alive=true→false)`() {
        // Mirror of the previous test: seed alive=true, then race N writers each flipping to
        // alive=false. The producer of the actual true→false edge (the FIRST CAS-winner) is the
        // one whose log line "Server X is now DEAD" / "marked as dead" must fire. All others
        // observe prev.alive=false and stay silent.
        repeat(20) {
            val s = newServerState()
            s.updateHealth { it.copy(alive = true, lastCheckTimestamp = Instant.parse("2026-05-28T00:00:00Z")) }
            val transitionObservers = AtomicInteger(0)
            val threadCount = 16
            val barrier = CyclicBarrier(threadCount)
            val threads =
                (1..threadCount).map { idx ->
                    Thread {
                        barrier.await()
                        val now = Instant.ofEpochSecond(idx.toLong())
                        val prev = s.getAndUpdateHealth { it.copy(alive = false, lastCheckTimestamp = now) }
                        if (prev.alive) transitionObservers.incrementAndGet()
                    }
                }
            threads.forEach { it.start() }
            threads.forEach { it.join() }

            assertEquals(1, transitionObservers.get(), "exactly one writer must observe the true→false edge")
            assertFalse(s.alive, "final state must be alive=false after the race")
        }
    }
}
