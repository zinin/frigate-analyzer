package ru.zinin.frigate.analyzer.core.config.properties

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.service.config.ObjectTrackerProperties
import java.time.Duration

/**
 * Binds `application.notifications.tracker` out of the production yaml via [ProductionYamlBinder].
 *
 * What is pinned here is `reappear-gap` defaulting to `ttl`. Its default references the resolved
 * property, so it follows `ttl` from whichever source sets it; the `$NOTIFICATIONS_TRACK_TTL` form
 * it replaced only followed that one variable and silently fell back to PT30M otherwise — enabling
 * reappearance detection with a threshold nobody configured. `reappear-classes` is pinned for the
 * same class of mistake: it must arrive empty when unset, since a non-empty default would silence
 * reappearances nobody asked to silence.
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

    @Test
    fun `with nothing set, reappear-classes is empty and every class may reappear`() {
        val props = bind()

        assertThat(props.reappearClasses).isEmpty()
        assertThat(props.reappearAllows("cow")).isTrue()
    }

    @Test
    fun `reappear-classes binds a comma-separated variable and normalizes it`() {
        val props = bind(env = mapOf("NOTIFICATIONS_TRACK_REAPPEAR_CLASSES" to "person, Cow "))

        assertThat(props.reappearClassesNormalized).containsExactlyInAnyOrder("person", "cow")
        assertThat(props.reappearAllows("PERSON")).isTrue()
        assertThat(props.reappearAllows("bicycle")).isFalse()
    }

    @Test
    fun `an explicitly empty variable binds the same as an unset one`() {
        // A different path through the binder than the test above: docker compose and systemd both
        // export `NOTIFICATIONS_TRACK_REAPPEAR_CLASSES=` as a present-but-empty variable, where the
        // unset case never reaches the environment at all and resolves through the yaml default.
        // Both have to end at an empty list — anything else means the container fails to start on a
        // configuration that asked for nothing.
        val props = bind(env = mapOf("NOTIFICATIONS_TRACK_REAPPEAR_CLASSES" to ""))

        assertThat(props.reappearClasses).isEmpty()
        assertThat(props.reappearAllows("cow")).isTrue()
    }

    @Test
    fun `an all-blank variable is rejected rather than silently meaning every class`() {
        assertThatThrownBy { bind(env = mapOf("NOTIFICATIONS_TRACK_REAPPEAR_CLASSES" to " , ")) }
            .hasRootCauseInstanceOf(IllegalArgumentException::class.java)
            // Pins *which* require fired — seven of them guard this constructor, and the type alone
            // would let the test pass on an unrelated one. What it establishes is that the delimited
            // converter splits on the comma and keeps both blank elements, so a non-empty list of
            // nothing usable reaches the constructor and the fail-fast is reachable from the
            // environment path at all. Were the converter to drop them, the list would arrive empty,
            // the require would pass, and the typo would silently mean "every class".
            .rootCause()
            .hasMessageContaining("reappear-classes was set but holds no usable class name")
    }

    private fun bind(
        env: Map<String, Any> = emptyMap(),
        properties: Map<String, Any> = emptyMap(),
    ): ObjectTrackerProperties = ProductionYamlBinder.bind(PREFIX, ObjectTrackerProperties::class.java, env, properties)

    private companion object {
        const val PREFIX = "application.notifications.tracker"
    }
}
