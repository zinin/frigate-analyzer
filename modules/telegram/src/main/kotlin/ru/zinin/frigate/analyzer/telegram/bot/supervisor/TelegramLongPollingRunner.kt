package ru.zinin.frigate.analyzer.telegram.bot.supervisor

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.buildBehaviourWithLongPolling
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

fun interface TelegramLongPollingRunner {
    /** Runs polling until it ends. Returns the cause on failure, or null on a clean exit. */
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
    override suspend fun run(onUpdate: suspend BehaviourContext.() -> Unit): Throwable? =
        try {
            coroutineScope {
                val pollingJob = bot.buildBehaviourWithLongPolling(scope = this) { onUpdate() }
                pollingJob.join()
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            e
        }
}
