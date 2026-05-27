package ru.zinin.frigate.analyzer.telegram.bot.supervisor

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.buildBehaviourWithLongPolling
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

fun interface TelegramLongPollingRunner {
    /**
     * Runs polling until it ends. Returns the cause on failure, or null on a clean exit.
     *
     * Failure propagation mechanics for the production [KtgBotApiLongPollingRunner] impl:
     * the polling job is started as a child of `coroutineScope { … }`. Per Kotlin's
     * structured-concurrency contract, a child failure cancels siblings and re-throws
     * the exception out of `coroutineScope`; the outer try-catch in `run()` then captures
     * it as the return value. `pollingJob.join()` itself is not the propagation vector —
     * it only suspends until the child completes.
     */
    suspend fun run(onUpdate: suspend BehaviourContext.() -> Unit): Throwable?
}

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class KtgBotApiLongPollingRunner(
    private val bot: TelegramBot,
) : TelegramLongPollingRunner {
    // [AUTO-16] Use NAMED argument `scope = this` for explicit intent and forward-compatibility.
    //           Per ktgbotapi 33.1.0 (verified by javap on the JVM jar), `scope: CoroutineScope`
    //           IS the first positional parameter, so `bot.buildBehaviourWithLongPolling(this) { … }`
    //           would compile cleanly today. We still pass the scope by name because the call has 8
    //           other parameters (defaultExceptionsHandler, timeoutSeconds, autoDisableWebhooks, …)
    //           and the library has reordered these in past versions — naming the arg prevents a
    //           silent semantic shift if a future ktgbotapi release reorders the head of the list.
    // [AUTO-18] Use explicit try/catch instead of `runCatching` so CancellationException
    //           propagates per Kotlin structured-concurrency convention (runCatching catches
    //           all Throwable, including CancellationException, then we'd have to re-throw it
    //           manually from a return value — fragile).
    //
    //           Catch `Exception` rather than `Throwable` so JVM-level `Error` (OOM,
    //           StackOverflowError, LinkageError) propagates through the standard uncaught-
    //           exception path. supervisor.runSupervised's `catch (e: Exception)` likewise
    //           lets Error escape — health BRANCH 1 will correctly report DOWN once the
    //           coroutine dies.
    override suspend fun run(onUpdate: suspend BehaviourContext.() -> Unit): Throwable? =
        try {
            coroutineScope {
                val pollingJob = bot.buildBehaviourWithLongPolling(scope = this) { onUpdate() }
                pollingJob.join()
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        }
}
