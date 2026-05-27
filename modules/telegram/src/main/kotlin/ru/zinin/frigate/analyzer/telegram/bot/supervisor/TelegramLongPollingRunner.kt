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
    // [AUTO-16] Use NAMED argument `scope = this`. Per Context7-verified ktgbotapi 33.1.0
    //           signature, the first positional parameter is `timeoutSeconds: Int = 30`, so a
    //           positional `this` (CoroutineScope) would land on `timeoutSeconds` and fail to
    //           compile with a type mismatch.
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
