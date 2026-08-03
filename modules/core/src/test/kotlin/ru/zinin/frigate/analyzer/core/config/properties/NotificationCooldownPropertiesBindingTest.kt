package ru.zinin.frigate.analyzer.core.config.properties

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.service.config.NotificationCooldownProperties
import java.time.Duration

/**
 * Binds `application.notifications.cooldown` out of the production yaml via [ProductionYamlBinder].
 *
 * The property this pins is the disabled default. The gate silences notifications, so a placeholder
 * that ever resolved to something other than `PT0S` would drop real events on a deployment that
 * never asked for a cooldown — and the production yaml is otherwise first evaluated in production.
 */
class NotificationCooldownPropertiesBindingTest {
    @Test
    fun `with nothing set the reappear cooldown is disabled`() {
        val props = bind()

        assertThat(props.reappear).isEqualTo(Duration.ZERO)
        assertThat(props.reappearEnabled).isFalse()
    }

    @Test
    fun `NOTIFICATIONS_COOLDOWN_REAPPEAR enables it`() {
        val props = bind(env = mapOf("NOTIFICATIONS_COOLDOWN_REAPPEAR" to "PT5M"))

        assertThat(props.reappear).isEqualTo(Duration.ofMinutes(5))
        assertThat(props.reappearEnabled).isTrue()
    }

    @Test
    fun `the relaxed variable name works too`() {
        val props = bind(env = mapOf("APPLICATION_NOTIFICATIONS_COOLDOWN_REAPPEAR" to "PT5M"))

        assertThat(props.reappear).isEqualTo(Duration.ofMinutes(5))
    }

    private fun bind(env: Map<String, Any> = emptyMap()): NotificationCooldownProperties =
        ProductionYamlBinder.bind(
            "application.notifications.cooldown",
            NotificationCooldownProperties::class.java,
            env,
        )
}
