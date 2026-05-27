package ru.zinin.frigate.analyzer.telegram.bot.supervisor

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

// @Profile("!test") guards FrigateAnalyzerApplicationTests.actuatorHealth(): in test profile
// telegram.enabled=false, the supervisor is not created, and computeHealth would return
// branch-1 DOWN, breaking the aggregated /actuator/health. Same pattern as
// WatchRecordsTaskHealthIndicator (core/task/WatchRecordsTaskHealthIndicator.kt).
@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
@Profile("!test")
class TelegramBotSupervisorHealthIndicator(
    private val supervisor: TelegramBotSupervisor,
    private val clock: Clock,
) : HealthIndicator {
    override fun health(): Health = supervisor.computeHealth(Instant.now(clock))
}
