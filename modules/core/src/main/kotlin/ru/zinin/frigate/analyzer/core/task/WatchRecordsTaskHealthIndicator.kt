package ru.zinin.frigate.analyzer.core.task

import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

// iter-1 review §D9: sync HealthIndicator is enough (computeHealth is pure sync); Spring Actuator on WebFlux adapts automatically.
// iter-2 CRITICAL-4: @Profile("!test") prevents regression of FrigateAnalyzerApplicationTests.actuatorHealth() —
// in test profile WatchRecordsTask.start() short-circuits (early return), supervisorJob = null,
// and without @Profile("!test") the indicator would return DOWN → aggregated /actuator/health becomes DOWN →
// actuatorHealth() expects UP and would fail. Same pattern as StartupTelegramNotifier.
@Component
@Profile("!test")
class WatchRecordsTaskHealthIndicator(
    private val task: WatchRecordsTask,
    private val clock: Clock,
) : HealthIndicator {
    override fun health(): Health = task.computeHealth(Instant.now(clock))
}
