package ru.zinin.frigate.analyzer.ai.description.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.springframework.context.ApplicationEventPublisher
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionProviderAuthEvent
import ru.zinin.frigate.analyzer.ai.description.api.ProviderAuthStates
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderAuthTrackerTest {
    private companion object {
        /** Слушатель занят дольше, чем параллельному вызову нужно, чтобы обогнать публикацию. */
        const val LISTENER_DELAY_MS = 200L

        /** Потолок ожидания в многопоточных сценариях: он же отличает «дошло» от дедлока. */
        const val AWAIT_TIMEOUT_MS = 5_000L

        const val CONCURRENT_FAILURES = 5
    }

    private val events = Collections.synchronizedList(mutableListOf<DescriptionProviderAuthEvent>())
    private val publisher = ApplicationEventPublisher { event -> events.add(event as DescriptionProviderAuthEvent) }
    private val tracker = ProviderAuthTracker(publisher)
    private val unauthorized = DescriptionException.Unauthorized("expired")

    @Test
    fun `the first failure publishes LOST and the first success after it publishes RESTORED`() {
        tracker.onUnauthorized("grok", unauthorized, "hint")
        tracker.onUnauthorized("grok", unauthorized, "hint")
        tracker.onSuccess("grok", "hint")
        tracker.onSuccess("grok", "hint")

        assertEquals(
            listOf(DescriptionProviderAuthEvent.State.LOST, DescriptionProviderAuthEvent.State.RESTORED),
            events.map { it.state },
        )
    }

    @Test
    fun `a success from UNKNOWN publishes nothing`() {
        tracker.onSuccess("grok", "hint")

        assertEquals(emptyList(), events)
        assertEquals(ProviderAuthStates.Health.HEALTHY, tracker.byScope().getValue("grok"))
    }

    @Test
    fun `two presets of one model share the state`() {
        // grok-fast и grok-deep: разный effort, одна модель — одни учётные данные, одна область.
        tracker.onUnauthorized("grok:grok-4.6", unauthorized, "hint")
        tracker.onUnauthorized("grok:grok-4.6", unauthorized, "hint")

        assertEquals(1, events.size)
    }

    @Test
    fun `byok and oauth scopes of one provider do not share the state`() {
        // Ключевой сценарий: OAuth сломан, BYOK работает. При ключе по провайдеру успех BYOK
        // опубликовал бы RESTORED и показал весь grok здоровым, хотя auth.json протух.
        tracker.onUnauthorized("grok:grok-4.6", unauthorized, "hint")
        tracker.onSuccess("grok:codex-luna", "hint")

        assertEquals(ProviderAuthStates.Health.LOST, tracker.byScope().getValue("grok:grok-4.6"))
        assertEquals(ProviderAuthStates.Health.HEALTHY, tracker.byScope().getValue("grok:codex-luna"))
        assertEquals(listOf(DescriptionProviderAuthEvent.State.LOST), events.map { it.state })
    }

    @Test
    fun `scopes are independent`() {
        tracker.onUnauthorized("grok", unauthorized, "hint")
        tracker.onUnauthorized("claude", unauthorized, "hint")

        assertEquals(listOf("grok", "claude"), events.map { it.authScopeId })
        assertEquals(ProviderAuthStates.Health.LOST, tracker.byScope().getValue("claude"))
    }

    // --- Ниже: два теста перенесены живьём из DefaultDescriptionAgentTest, с реальными потоками и
    // CountDownLatch. Это единственные тесты, проверяющие смысл существования замка; однопоточные
    // сценарии выше их не заменяют, и без них регрессия порядка LOST/RESTORED снова становится
    // возможной. Дизайн обещает «одно событие на переход при параллельных отказах» именно про них.

    @Test
    fun `a slow listener cannot reorder concurrent auth transitions`() =
        // Реальные потоки, не виртуальное время: проверяется сериализация перехода с публикацией.
        runBlocking(Dispatchers.IO) {
            val delivered = Collections.synchronizedList(mutableListOf<DescriptionProviderAuthEvent.State>())
            val lostListenerEntered = CountDownLatch(1)
            val slowListener =
                ApplicationEventPublisher { event ->
                    val state = (event as DescriptionProviderAuthEvent).state
                    if (state == DescriptionProviderAuthEvent.State.LOST) {
                        lostListenerEntered.countDown()
                        Thread.sleep(LISTENER_DELAY_MS)
                    }
                    delivered.add(state)
                }
            val slow = ProviderAuthTracker(slowListener)

            val first = launch { slow.onUnauthorized("grok", unauthorized, "hint") }
            val second =
                launch {
                    // Успех попадает ровно в окно, где отказ уже переключил состояние, но ещё публикуется.
                    lostListenerEntered.await()
                    slow.onSuccess("grok", "hint")
                }
            first.join()
            second.join()

            assertEquals(
                listOf(DescriptionProviderAuthEvent.State.LOST, DescriptionProviderAuthEvent.State.RESTORED),
                delivered.toList(),
            )
        }

    @Test
    fun `concurrent Unauthorized failures publish a single LOST`() =
        // Реальные потоки: пять отказов одной области, отпущенных одновременно, дают одно событие.
        runBlocking(Dispatchers.IO) {
            val allEntered = CountDownLatch(CONCURRENT_FAILURES)
            val release = CountDownLatch(1)
            val failures =
                List(CONCURRENT_FAILURES) {
                    launch {
                        allEntered.countDown()
                        release.await()
                        tracker.onUnauthorized("grok", unauthorized, "hint")
                    }
                }
            assertTrue(allEntered.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS), "all callers must reach the barrier")
            release.countDown()
            failures.forEach { it.join() }

            assertEquals(1, events.size)
        }

    @Test
    fun `a slow listener on one scope does not delay the other`() =
        // Замок берётся на область: событие claude обязано уйти, пока слушатель ещё держит
        // публикацию grok. Глобальный замок оставил бы claude ждать и await истёк бы впустую.
        runBlocking(Dispatchers.IO) {
            val grokListenerEntered = CountDownLatch(1)
            val claudeDelivered = CountDownLatch(1)
            val claudeArrivedWhileGrokHeld = AtomicBoolean(false)
            val blocking =
                ProviderAuthTracker(
                    ApplicationEventPublisher { event ->
                        when ((event as DescriptionProviderAuthEvent).authScopeId) {
                            "grok" -> {
                                grokListenerEntered.countDown()
                                claudeArrivedWhileGrokHeld.set(
                                    claudeDelivered.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS),
                                )
                            }

                            "claude" -> {
                                claudeDelivered.countDown()
                            }
                        }
                    },
                )

            val slow = launch { blocking.onUnauthorized("grok", unauthorized, "hint") }
            assertTrue(
                grokListenerEntered.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS),
                "the grok listener must be publishing before claude fails",
            )
            blocking.onUnauthorized("claude", unauthorized, "hint")
            slow.join()

            assertTrue(claudeArrivedWhileGrokHeld.get(), "claude must not wait for the grok listener")
        }

    @Test
    fun `a throwing listener rolls the state back so the transition is reported again`() {
        val failing = ProviderAuthTracker(ApplicationEventPublisher { error("listener down") })

        failing.onUnauthorized("grok", unauthorized, "hint")

        assertEquals(ProviderAuthStates.Health.UNKNOWN, failing.byScope().getValue("grok"))
    }

    @Test
    fun `success after LOST publishes RESTORED once`() {
        tracker.onUnauthorized("grok", unauthorized, "hint")
        tracker.onSuccess("grok", "hint")
        tracker.onSuccess("grok", "hint")

        assertEquals(
            listOf(DescriptionProviderAuthEvent.State.LOST, DescriptionProviderAuthEvent.State.RESTORED),
            events.map { it.state },
        )
    }

    @Test
    fun `an untouched scope is not listed`() {
        assertEquals(emptyMap(), tracker.byScope())
    }
}
