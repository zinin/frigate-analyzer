package ru.zinin.frigate.analyzer.telegram.bot.supervisor

import dev.inmo.tgbotapi.bot.TelegramBot
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.health.contributor.Health
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.bot.FrigateAnalyzerBot
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

private val INITIAL_BACKOFF: Duration = Duration.ofSeconds(5)
private val MAX_BACKOFF: Duration = Duration.ofSeconds(60)
private val STABLE_THRESHOLD: Duration = Duration.ofSeconds(60)
private val HEALTH_STALENESS: Duration = Duration.ofMinutes(5)
private val STARTUP_GRACE: Duration = Duration.ofMinutes(2)
private const val STARTUP_FAILURE_THRESHOLD: Long = 5L
private val SHUTDOWN_JOIN_TIMEOUT: Duration = Duration.ofSeconds(30)

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class TelegramBotSupervisor(
    // [AUTO-2] runner is the D2 adapter dependency, FIRST constructor parameter per design §3.2
    private val runner: TelegramLongPollingRunner,
    private val bot: TelegramBot,
    private val frigateAnalyzerBot: FrigateAnalyzerBot,
    private val clock: Clock,
    private val dispatcher: CoroutineDispatcher =
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        Dispatchers.IO.limitedParallelism(1),
) {
    private val scope =
        CoroutineScope(SupervisorJob() + dispatcher + CoroutineName("telegram-bot-supervisor"))
    // [A6] Production default is Dispatchers.IO.limitedParallelism(1) for parity with
    //      WatchRecordsTask. Long-polling is I/O-bound and the supervisor is single-threaded
    //      by design. Constructor takes `dispatcher` for testability (StandardTestDispatcher).

    @Volatile internal var supervisorJob: Job? = null

    @Volatile internal var startupAt: Instant? = null

    @Volatile internal var lastAttemptAt: Instant? = null

    @Volatile internal var lastPollingStartAt: Instant? = null

    @Volatile internal var lastStableAt: Instant? = null

    @Volatile internal var lastFailure: Throwable? = null

    @Volatile internal var lastFailureAt: Instant? = null

    @Volatile internal var consecutiveFailures: Long = 0

    @Volatile internal var currentBackoff: Duration = INITIAL_BACKOFF

    @PostConstruct
    fun start() {
        // Filled in Task 7.
    }

    @PreDestroy
    fun shutdown() {
        // Filled in Task 7.
        scope.cancel()
    }

    fun computeHealth(now: Instant): Health {
        val builder = baseBuilder()
        val job = supervisorJob
        if (job == null || !job.isActive) {
            return builder.down().withDetail("reason", "supervisor not active").build()
        }
        // Remaining branches filled in Task 6.
        return builder.outOfService().withDetail("reason", "not implemented yet").build()
    }

    private fun baseBuilder(): Health.Builder =
        Health
            .Builder()
            .withDetail("startupAt", startupAt?.toString() ?: "never")
            .withDetail("lastAttemptAt", lastAttemptAt?.toString() ?: "never")
            .withDetail("lastPollingStartAt", lastPollingStartAt?.toString() ?: "never")
            .withDetail("lastStableAt", lastStableAt?.toString() ?: "never")
            .withDetail("lastFailureAt", lastFailureAt?.toString() ?: "never")
            .withDetail("consecutiveFailures", consecutiveFailures)
            .withDetail("currentBackoffMs", currentBackoff.toMillis())
            .also { b ->
                lastFailure?.let {
                    b.withDetail(
                        "lastFailure",
                        "${it.javaClass.simpleName}: ${it.message?.take(500) ?: "<no-message>"}",
                    )
                }
            }
}
