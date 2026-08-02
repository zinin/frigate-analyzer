package ru.zinin.frigate.analyzer.core.config.properties

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.core.io.FileSystemResource
import java.io.File
import java.nio.file.Path
import java.time.Duration

/**
 * Binds `application.records-watcher` out of the production `src/main/resources/application.yaml`,
 * the same way [ObjectTrackerPropertiesBindingTest] does — the test classpath carries its own
 * `application.yaml`, which shadows the production one, so its placeholders are otherwise evaluated
 * for the first time when production starts.
 *
 * What is pinned here is `first-scan-period` defaulting to `watch-period` — from whichever source
 * sets it. The yaml default is empty on purpose: an empty value binds to null, so the Kotlin default
 * `firstScanPeriod = watchPeriod` takes over and sees the already-resolved watch period.
 *
 * Neither placeholder form does that. `${FIRST_SCAN_PERIOD:${WATCH_PERIOD:P1D}}` follows only that
 * one env var. And relaxed name mapping is guaranteed for `@ConfigurationProperties` binding but not
 * for placeholder resolution: the relaxed-name test below is what showed
 * `${FIRST_SCAN_PERIOD:${application.records-watcher.watch-period}}` resolving to the yaml's own P1D
 * while `watch-period` itself bound the override.
 */
class RecordsWatcherPropertiesBindingTest {
    @Test
    fun `with nothing set, first-scan-period equals watch-period and both keep the documented default`() {
        val props = bind()

        assertThat(props.watchPeriod).isEqualTo(Duration.ofDays(1))
        assertThat(props.firstScanPeriod).isEqualTo(props.watchPeriod)
    }

    @Test
    fun `first-scan-period follows a watch-period set through WATCH_PERIOD`() {
        val props = bind(env = mapOf("WATCH_PERIOD" to "P3D"))

        assertThat(props.watchPeriod).isEqualTo(Duration.ofDays(3))
        assertThat(props.firstScanPeriod).isEqualTo(Duration.ofDays(3))
    }

    @Test
    fun `first-scan-period follows a watch-period set as a property rather than as that one variable`() {
        // A docker profile yaml, a CLI argument or a system property lands this way.
        // This is the case the nested-variable form would get wrong.
        val props = bind(properties = mapOf("$PREFIX.watch-period" to "P3D"))

        assertThat(props.watchPeriod).isEqualTo(Duration.ofDays(3))
        assertThat(props.firstScanPeriod).isEqualTo(Duration.ofDays(3))
    }

    @Test
    fun `first-scan-period follows a watch-period set through the relaxed variable name`() {
        // Spring Boot's own spelling of the same property. This is the case that showed a
        // ${application.records-watcher.watch-period} default binding P1D here, while watch-period
        // itself bound P3D — relaxed names are guaranteed for binding, not for placeholders.
        val props = bind(env = mapOf("APPLICATION_RECORDSWATCHER_WATCHPERIOD" to "P3D"))

        assertThat(props.watchPeriod).isEqualTo(Duration.ofDays(3))
        assertThat(props.firstScanPeriod).isEqualTo(Duration.ofDays(3))
    }

    @Test
    fun `an explicitly set FIRST_SCAN_PERIOD wins over the watch-period default`() {
        val props = bind(env = mapOf("WATCH_PERIOD" to "P3D", "FIRST_SCAN_PERIOD" to "P0D"))

        assertThat(props.watchPeriod).isEqualTo(Duration.ofDays(3))
        assertThat(props.firstScanPeriod).isEqualTo(Duration.ZERO)
    }

    @Test
    fun `with nothing set, the startup scan is disabled`() {
        // The scan is an opt-in backfill: a fixed scan that actually finishes would otherwise run
        // a never-observed ~52k backfill on a fresh deployment with default settings.
        assertThat(bind().disableFirstScan).isTrue()
    }

    @Test
    fun `a sub-day first-scan-period is rejected instead of being silently truncated`() {
        // toDays() truncates: PT12H would silently behave as "today only".
        assertThatThrownBy {
            RecordsWatcherProperties(
                folder = Path.of("/tmp"),
                firstScanPeriod = Duration.ofHours(12),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("whole days")
    }

    @Test
    fun `health details are exposed by default and can be switched off`() {
        assertThat(environmentWith().getProperty("management.endpoint.health.show-details"))
            .isEqualTo("always")
        assertThat(
            environmentWith(env = mapOf("HEALTH_SHOW_DETAILS" to "never"))
                .getProperty("management.endpoint.health.show-details"),
        ).isEqualTo("never")
    }

    private fun bind(
        env: Map<String, Any> = emptyMap(),
        properties: Map<String, Any> = emptyMap(),
    ): RecordsWatcherProperties =
        Binder
            .get(environmentWith(env, properties))
            .bind(PREFIX, RecordsWatcherProperties::class.java)
            .get()

    /**
     * [env] is exposed as a [SystemEnvironmentPropertySource] on purpose: that type is what makes
     * `APPLICATION_RECORDSWATCHER_WATCHPERIOD` answer a lookup for
     * `application.records-watcher.watch-period`, and a plain map would not.
     */
    private fun environmentWith(
        env: Map<String, Any> = emptyMap(),
        properties: Map<String, Any> = emptyMap(),
    ): StandardEnvironment {
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
        return environment
    }

    private companion object {
        const val PREFIX = "application.records-watcher"

        /** Gradle runs tests with the module directory as the working directory. */
        fun productionYaml() =
            File("src/main/resources/application.yaml")
                .also { check(it.isFile) { "Expected the production yaml at ${it.absolutePath}" } }
                .let { YamlPropertySourceLoader().load("production-application.yaml", FileSystemResource(it)) }
    }
}
