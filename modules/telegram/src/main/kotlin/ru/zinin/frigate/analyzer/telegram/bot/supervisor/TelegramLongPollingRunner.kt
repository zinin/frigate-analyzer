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
    // Use NAMED argument `scope = this`: per ktgbotapi 33.1.0 (verified by javap), `scope` IS
    // the first positional parameter, but the call has 8 others (defaultExceptionsHandler,
    // timeoutSeconds, autoDisableWebhooks, …) and the library has reordered these in past
    // versions — naming the arg prevents a silent semantic shift on future releases.
    //
    // Use explicit try/catch instead of `runCatching` so CancellationException propagates per
    // Kotlin structured-concurrency convention (runCatching catches all Throwable, including
    // CancellationException, then we'd have to re-throw it manually from a return value).
    //
    // Catch `Exception` rather than `Throwable` so JVM-level `Error` (OOM, StackOverflowError,
    // LinkageError) propagates through the standard uncaught-exception path. The supervisor's
    // runSupervised also catches `Exception` — Error escapes and health BRANCH 1 reports DOWN.
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
