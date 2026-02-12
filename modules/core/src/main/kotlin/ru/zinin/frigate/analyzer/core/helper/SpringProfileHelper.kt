package ru.zinin.frigate.analyzer.core.helper

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class SpringProfileHelper(
    private val environment: Environment,
) {
    private var activeProfiles: Set<String> = emptySet()

    @PostConstruct
    fun init() {
        activeProfiles = environment.activeProfiles.toSet()
        logger.info { "activeProfiles: $activeProfiles" }
    }

    fun isTestProfile(): Boolean = activeProfiles.contains(TEST_PROFILE)

    private companion object {
        const val TEST_PROFILE = "test"
    }
}
