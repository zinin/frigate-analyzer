package ru.zinin.frigate.analyzer.core.config.properties

import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.core.io.FileSystemResource
import java.io.File

/**
 * Binds a `@ConfigurationProperties` type out of the production `src/main/resources/application.yaml`.
 *
 * Nothing outside these binding tests reads that file — [RecordsWatcherPropertiesBindingTest] does
 * it inline rather than through here. The test classpath carries its own `application.yaml`, which
 * shadows it — deliberately, since that is what keeps signal-loss inert in integration tests (see
 * the `SIGNAL_LOSS_ENABLED` note in `.claude/rules/configuration.md`) — so every placeholder in the
 * production file is otherwise evaluated for the first time when production starts. These tests are
 * where a defaulting mistake is caught instead.
 *
 * [env] is exposed as a [SystemEnvironmentPropertySource] on purpose: that type is what makes
 * `APPLICATION_NOTIFICATIONS_TRACKER_TTL` answer a lookup for
 * `application.notifications.tracker.ttl`, and a plain map would not. [properties] stands in for
 * anything that contributes the property under its canonical name — a profile yaml, a CLI argument,
 * a system property. Both outrank the yaml, as they do at startup.
 */
internal object ProductionYamlBinder {
    fun <T : Any> bind(
        prefix: String,
        type: Class<T>,
        env: Map<String, Any> = emptyMap(),
        properties: Map<String, Any> = emptyMap(),
    ): T {
        val environment = StandardEnvironment()
        // Hermetic: whatever this machine happens to export must not reach the assertions.
        environment.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
        environment.propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
        if (properties.isNotEmpty()) {
            environment.propertySources.addFirst(MapPropertySource("profile-yaml-or-cli", properties))
        }
        if (env.isNotEmpty()) {
            environment.propertySources.addFirst(
                SystemEnvironmentPropertySource(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    env,
                ),
            )
        }
        productionYaml().forEach { environment.propertySources.addLast(it) }
        return Binder.get(environment).bind(prefix, type).get()
    }

    /** Gradle runs tests with the module directory as the working directory. */
    private fun productionYaml() =
        File("src/main/resources/application.yaml")
            .also { check(it.isFile) { "Expected the production yaml at ${it.absolutePath}" } }
            .let { YamlPropertySourceLoader().load("production-application.yaml", FileSystemResource(it)) }
}
