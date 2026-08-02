package ru.zinin.frigate.analyzer.core.config.properties

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.core.io.FileSystemResource
import ru.zinin.frigate.analyzer.service.config.ObjectTrackerProperties
import java.io.File
import java.time.Duration

/**
 * Binds `application.notifications.tracker` out of the production `src/main/resources/application.yaml`.
 *
 * Nothing else reads that file. The test classpath carries its own `application.yaml`, which shadows
 * it — deliberately, since that is what keeps signal-loss inert in integration tests (see the
 * `SIGNAL_LOSS_ENABLED` note in `.claude/rules/configuration.md`) — so every placeholder in the
 * production file is otherwise evaluated for the first time when production starts.
 *
 * What is pinned here is `reappear-gap` defaulting to `ttl`. Its default references the resolved
 * property, so it follows `ttl` from whichever source sets it; the `$NOTIFICATIONS_TRACK_TTL` form
 * it replaced only followed that one variable and silently fell back to PT30M otherwise — enabling
 * reappearance detection with a threshold nobody configured.
 */
class ObjectTrackerPropertiesBindingTest {
    @Test
    fun `with nothing set, reappear-gap equals ttl and both keep the documented default`() {
        val props = bind()

        assertThat(props.ttl).isEqualTo(Duration.ofMinutes(30))
        assertThat(props.reappearGap).isEqualTo(props.ttl)
    }

    @Test
    fun `reappear-gap follows a ttl set through NOTIFICATIONS_TRACK_TTL`() {
        // The path production uses today: docker-compose hands the container an env_file.
        val props =
            bind(
                env =
                    mapOf(
                        "NOTIFICATIONS_TRACK_TTL" to "PT12H",
                        "NOTIFICATIONS_TRACK_CLEANUP_RETENTION" to "PT48H",
                    ),
            )

        assertThat(props.ttl).isEqualTo(Duration.ofHours(12))
        assertThat(props.reappearGap).isEqualTo(Duration.ofHours(12))
    }

    @Test
    fun `reappear-gap follows a ttl set as a property rather than as that one variable`() {
        // A docker profile yaml is mounted alongside the env file (docker-compose.yml mounts
        // application-docker.yaml and sets SPRING_PROFILES_ACTIVE=docker); a CLI argument or a
        // system property lands the same way. This is the case the old form got wrong.
        val props =
            bind(
                properties =
                    mapOf(
                        "$PREFIX.ttl" to "PT12H",
                        "$PREFIX.cleanup-retention" to "PT48H",
                    ),
            )

        assertThat(props.ttl).isEqualTo(Duration.ofHours(12))
        assertThat(props.reappearGap).isEqualTo(Duration.ofHours(12))
    }

    @Test
    fun `reappear-gap follows a ttl set through the relaxed variable name`() {
        // Spring Boot's own spelling of the same property. Also missed by the old form.
        val props =
            bind(
                env =
                    mapOf(
                        "APPLICATION_NOTIFICATIONS_TRACKER_TTL" to "PT12H",
                        "APPLICATION_NOTIFICATIONS_TRACKER_CLEANUP_RETENTION" to "PT48H",
                    ),
            )

        assertThat(props.ttl).isEqualTo(Duration.ofHours(12))
        assertThat(props.reappearGap).isEqualTo(Duration.ofHours(12))
    }

    @Test
    fun `an explicitly set reappear-gap wins over the ttl default`() {
        val props =
            bind(
                env =
                    mapOf(
                        "NOTIFICATIONS_TRACK_TTL" to "PT12H",
                        "NOTIFICATIONS_TRACK_CLEANUP_RETENTION" to "PT48H",
                        "NOTIFICATIONS_TRACK_REAPPEAR_GAP" to "PT1H",
                    ),
            )

        assertThat(props.ttl).isEqualTo(Duration.ofHours(12))
        assertThat(props.reappearGap).isEqualTo(Duration.ofHours(1))
    }

    /**
     * [env] is exposed as a [SystemEnvironmentPropertySource] on purpose: that type is what makes
     * `APPLICATION_NOTIFICATIONS_TRACKER_TTL` answer a lookup for
     * `application.notifications.tracker.ttl`, and a plain map would not. [properties] stands in for
     * anything that contributes the property under its canonical name — a profile yaml, a CLI
     * argument, a system property. Both outrank the yaml, as they do at startup.
     */
    private fun bind(
        env: Map<String, Any> = emptyMap(),
        properties: Map<String, Any> = emptyMap(),
    ): ObjectTrackerProperties {
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
        return Binder.get(environment).bind(PREFIX, ObjectTrackerProperties::class.java).get()
    }

    private companion object {
        const val PREFIX = "application.notifications.tracker"

        /** Gradle runs tests with the module directory as the working directory. */
        fun productionYaml() =
            File("src/main/resources/application.yaml")
                .also { check(it.isFile) { "Expected the production yaml at ${it.absolutePath}" } }
                .let { YamlPropertySourceLoader().load("production-application.yaml", FileSystemResource(it)) }
    }
}
