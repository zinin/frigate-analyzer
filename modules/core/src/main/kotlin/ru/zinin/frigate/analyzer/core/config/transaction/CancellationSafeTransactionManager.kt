package ru.zinin.frigate.analyzer.core.config.transaction

import io.github.oshai.kotlinlogging.KotlinLogging
import org.reactivestreams.Subscription
import org.springframework.transaction.NestedTransactionNotSupportedException
import org.springframework.transaction.ReactiveTransaction
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED
import reactor.core.CoreSubscriber
import reactor.core.publisher.Mono
import reactor.core.publisher.MonoOperator
import reactor.util.context.Context
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/**
 * Менеджер транзакций, у которого начатое открытие транзакции нельзя отменить
 * (`docs/r2dbc-cancelled-transaction-leak.md`).
 *
 * Spring открывает транзакцию как ресурс `usingWhen`. Отмена, пришедшая раньше ресурса, отменяет само открытие, и убрать за
 * ним некому: `BEGIN` уже на сервере, а соединение возвращается в пул. Если открытие довести до конца, `usingWhen` сам
 * откатит транзакцию, появившуюся после отмены, и вернёт соединение как положено. Вызывающий этого не ждёт: для него
 * отмена срабатывает сразу.
 *
 * `PROPAGATION_NESTED` отвергается. Внутри внешней транзакции это точка сохранения, и откат к ней после отмены стёр бы то,
 * что внешняя успела записать, а без внешней — обычный `BEGIN`. В проекте NESTED нет, и различать эти случаи незачем.
 */
class CancellationSafeTransactionManager(
    private val delegate: ReactiveTransactionManager,
) : ReactiveTransactionManager by delegate {
    override fun getReactiveTransaction(definition: TransactionDefinition?): Mono<ReactiveTransaction> =
        if (definition?.propagationBehavior == PROPAGATION_NESTED) {
            Mono.error(
                NestedTransactionNotSupportedException(
                    "PROPAGATION_NESTED is not supported: see docs/r2dbc-cancelled-transaction-leak.md",
                ),
            )
        } else {
            UncancellableOpening(delegate.getReactiveTransaction(definition), definition?.name ?: "unnamed")
        }
}

private class UncancellableOpening(
    source: Mono<ReactiveTransaction>,
    private val transactionName: String,
) : MonoOperator<ReactiveTransaction, ReactiveTransaction>(source) {
    override fun subscribe(actual: CoreSubscriber<in ReactiveTransaction>) {
        source.subscribe(Opening(actual, transactionName))
    }

    private enum class State { NOT_STARTED, STARTED, CANCELLED_BEFORE_START, CANCELLED_AFTER_START }

    private class Opening(
        private val caller: CoreSubscriber<in ReactiveTransaction>,
        private val transactionName: String,
    ) : CoreSubscriber<ReactiveTransaction>,
        Subscription {
        private val state = AtomicReference(State.NOT_STARTED)
        private lateinit var opening: Subscription

        override fun currentContext(): Context = caller.currentContext()

        override fun onSubscribe(subscription: Subscription) {
            opening = subscription
            caller.onSubscribe(this)
        }

        override fun request(n: Long) {
            state.compareAndSet(State.NOT_STARTED, State.STARTED)
            if (state.get() != State.CANCELLED_BEFORE_START) opening.request(n)
        }

        override fun cancel() {
            if (state.compareAndSet(State.NOT_STARTED, State.CANCELLED_BEFORE_START)) {
                opening.cancel()
            } else {
                state.compareAndSet(State.STARTED, State.CANCELLED_AFTER_START)
            }
        }

        /** Отдаётся и после отмены: откатить транзакцию может только `usingWhen`, который её ждал. */
        override fun onNext(transaction: ReactiveTransaction) {
            if (state.get() == State.CANCELLED_AFTER_START) {
                logger.info { "Transaction was opened after its caller had been cancelled, rolling it back: $transactionName" }
            }
            caller.onNext(transaction)
        }

        override fun onError(error: Throwable) {
            if (state.get() == State.CANCELLED_AFTER_START) {
                logger.debug(error) { "Transaction whose caller had been cancelled could not be opened: $transactionName" }
            } else {
                caller.onError(error)
            }
        }

        override fun onComplete() {
            caller.onComplete()
        }
    }
}
