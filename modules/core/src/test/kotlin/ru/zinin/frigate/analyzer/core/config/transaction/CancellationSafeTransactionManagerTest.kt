package ru.zinin.frigate.analyzer.core.config.transaction

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.reactivestreams.Subscription
import org.springframework.transaction.ReactiveTransaction
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.support.DefaultTransactionDefinition
import reactor.core.CoreSubscriber
import reactor.core.Disposable
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.time.Duration
import kotlin.test.assertFailsWith

/**
 * Декоратор вместе с настоящим [TransactionalOperator]: откат транзакции, пришедшей после отмены, делает `usingWhen`
 * внутри него. Настоящие [org.springframework.r2dbc.connection.R2dbcTransactionManager], пул и базу проверяет
 * [CancelledTransactionLeakTest].
 */
class CancellationSafeTransactionManagerTest {
    private val events = mutableListOf<String>()

    /** Ответ на `BEGIN`: транзакция появляется, когда его отпускает тест. */
    private val begin = Sinks.one<ReactiveTransaction>()
    private val transaction = mockk<ReactiveTransaction>()
    private val delegate =
        mockk<ReactiveTransactionManager> {
            every { getReactiveTransaction(any()) } returns
                begin.asMono().doOnRequest { events += "begin" }.doOnCancel { events += "begin cancelled" }
            every { commit(transaction) } returns Mono.fromRunnable { events += "commit" }
            every { rollback(transaction) } returns Mono.fromRunnable { events += "rollback" }
        }
    private val manager = CancellationSafeTransactionManager(delegate)
    private val work = Mono.fromRunnable<Void> { events += "work" }

    @Test
    fun `transaction opened after its caller was cancelled is rolled back`() {
        val call = TransactionalOperator.create(manager).transactional(work).subscribe()
        call.dispose()

        begin.tryEmitValue(transaction)

        assertThat(events).containsExactly("begin", "rollback")
    }

    /** Транзакция уже открыта, но до `usingWhen` ещё не дошла. */
    @Test
    fun `transaction being handed over when its caller is cancelled is rolled back`() {
        lateinit var call: Disposable
        every { delegate.getReactiveTransaction(any()) } returns begin.asMono().doOnNext { call.dispose() }
        call = TransactionalOperator.create(manager).transactional(work).subscribe()

        begin.tryEmitValue(transaction)

        assertThat(events).containsExactly("rollback")
    }

    @Test
    fun `caller cancelled during its work is rolled back`() {
        val call = TransactionalOperator.create(manager).transactional(Mono.never<Void>()).subscribe()
        begin.tryEmitValue(transaction)

        call.dispose()

        assertThat(events).containsExactly("begin", "rollback")
    }

    @Test
    fun `completed work is committed`() {
        begin.tryEmitValue(transaction)

        TransactionalOperator.create(manager).transactional(work).block(LIMIT)

        assertThat(events).containsExactly("begin", "work", "commit")
    }

    @Test
    fun `failure to open reaches the caller`() {
        begin.tryEmitError(IllegalStateException("no connection"))

        assertFailsWith<IllegalStateException> { TransactionalOperator.create(manager).transactional(work).block(LIMIT) }
    }

    /** Открывать нечего: до запроса ничего не начиналось. */
    @Test
    fun `caller cancelled before the opening was requested does not start it`() {
        val caller = Caller { subscription -> subscription.cancel() }

        manager.getReactiveTransaction(null).subscribe(caller)
        caller.subscription.request(1)

        assertThat(events).containsExactly("begin cancelled")
    }

    /** Откат к точке сохранения, появившейся после отмены, стёр бы то, что внешняя транзакция успела записать. */
    @Test
    fun `cancel of a nested opening reaches it`() {
        val caller = Caller { subscription -> subscription.request(1) }
        manager.getReactiveTransaction(DefaultTransactionDefinition(PROPAGATION_NESTED)).subscribe(caller)

        caller.subscription.cancel()

        assertThat(events).containsExactly("begin", "begin cancelled")
    }

    /** Ошибку уже некому обработать: ушедший вызывающий отдал бы её в `onErrorDropped`. */
    @Test
    fun `failure to open after the caller was cancelled reaches nobody`() {
        val caller = Caller { subscription -> subscription.request(1) }
        manager.getReactiveTransaction(null).subscribe(caller)
        caller.subscription.cancel()

        begin.tryEmitError(IllegalStateException("no connection"))

        assertThat(caller.signals).isEmpty()
        assertThat(events).containsExactly("begin")
    }

    private class Caller(
        private val start: (Subscription) -> Unit,
    ) : CoreSubscriber<ReactiveTransaction> {
        val signals = mutableListOf<Any>()
        lateinit var subscription: Subscription

        override fun onSubscribe(subscription: Subscription) {
            this.subscription = subscription
            start(subscription)
        }

        override fun onNext(transaction: ReactiveTransaction) {
            signals += transaction
        }

        override fun onError(error: Throwable) {
            signals += error
        }

        override fun onComplete() {
            signals += "complete"
        }
    }

    private companion object {
        val LIMIT: Duration = Duration.ofSeconds(5)
    }
}
