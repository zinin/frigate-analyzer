package ru.zinin.frigate.analyzer.core.config.properties

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Duration

/**
 * Binds `application.records-watcher` out of the production yaml via [ProductionYamlBinder], the
 * same way [ObjectTrackerPropertiesBindingTest] does.
 *
 * What is pinned here is `first-scan-period` defaulting to `watch-period` — from whichever source
 * sets it. The yaml default is empty on purpose: an empty value binds to null, so the Kotlin default
 * `firstScanPeriod = Duration.ofDays(watchPeriod.toDays())` takes over and sees the already-resolved
 * watch period, truncated to the whole days the scan window is measured in.
 *
 * Neither placeholder form does that here. `${FIRST_SCAN_PERIOD:${WATCH_PERIOD:P1D}}` follows only
 * that one env var. And relaxed name mapping is guaranteed for `@ConfigurationProperties` binding
 * but not for placeholder resolution: the relaxed-name test below is what showed
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
    fun `a non-whole-day WATCH_PERIOD still binds, truncated to whole days`() {
        // PT36H passes watchPeriod's own `toDays() >= 1` check and has always MEANT one day to
        // watchCutoff, which truncates the same way. Inheriting it verbatim would have failed the
        // whole-days require at startup, naming FIRST_SCAN_PERIOD — a variable the operator never set.
        val props = bind(env = mapOf("WATCH_PERIOD" to "PT36H"))

        assertThat(props.watchPeriod).isEqualTo(Duration.ofHours(36))
        assertThat(props.firstScanPeriod).isEqualTo(Duration.ofDays(1))
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
    fun `a negative first-scan-period is rejected`() {
        // The whole-days require alone would let this through: ofDays(-1) equals ofDays(ofDays(-1)
        // .toDays()). The non-negative require is the only thing rejecting it.
        assertThatThrownBy {
            RecordsWatcherProperties(
                folder = Path.of("/tmp"),
                firstScanPeriod = Duration.ofDays(-1),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must not be negative")
    }

    @Test
    fun `health details are exposed by default and can be switched off`() {
        // Read rather than bound: this one is Spring's own property, not ours.
        assertThat(ProductionYamlBinder.environment().getProperty(SHOW_DETAILS))
            .isEqualTo("always")
        assertThat(
            ProductionYamlBinder
                .environment(env = mapOf("HEALTH_SHOW_DETAILS" to "never"))
                .getProperty(SHOW_DETAILS),
        ).isEqualTo("never")
    }

    private fun bind(
        env: Map<String, Any> = emptyMap(),
        properties: Map<String, Any> = emptyMap(),
    ): RecordsWatcherProperties = ProductionYamlBinder.bind(PREFIX, RecordsWatcherProperties::class.java, env, properties)

    private companion object {
        const val PREFIX = "application.records-watcher"
        const val SHOW_DETAILS = "management.endpoint.health.show-details"
    }
}
