package ru.zinin.frigate.analyzer.core.config.properties

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration

class SignalLossPropertiesTest {
    @Test
    fun `valid properties pass cross-field validation`() {
        val props = base()
        assertThatCode { props.validateCrossField() }.doesNotThrowAnyException()
        assertThat(props.threshold).isEqualTo(Duration.ofMinutes(3))
        assertThat(props.pollInterval).isEqualTo(Duration.ofSeconds(30))
        assertThat(props.activeWindow).isEqualTo(Duration.ofHours(24))
        assertThat(props.startupGrace).isEqualTo(Duration.ofMinutes(5))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidConfigs")
    fun `invalid properties fail cross-field validation with informative message`(invalid: InvalidCase) {
        assertThatThrownBy { invalid.props.validateCrossField() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(invalid.expectedFragment)
    }

    data class InvalidCase(
        val name: String,
        val props: SignalLossProperties,
        val expectedFragment: String,
    ) {
        override fun toString(): String = name
    }

    companion object {
        @JvmStatic
        fun invalidConfigs(): List<InvalidCase> =
            listOf(
                InvalidCase(
                    name = "threshold zero",
                    props = base().copy(threshold = Duration.ZERO),
                    expectedFragment = "threshold",
                ),
                InvalidCase(
                    name = "threshold negative",
                    props = base().copy(threshold = Duration.ofSeconds(-1)),
                    expectedFragment = "threshold",
                ),
                InvalidCase(
                    name = "pollInterval zero",
                    props = base().copy(pollInterval = Duration.ZERO),
                    expectedFragment = "pollInterval",
                ),
                InvalidCase(
                    name = "pollInterval equals threshold",
                    props =
                        base().copy(
                            pollInterval = Duration.ofMinutes(3),
                            threshold = Duration.ofMinutes(3),
                        ),
                    expectedFragment = "pollInterval",
                ),
                InvalidCase(
                    name = "pollInterval greater than threshold",
                    props =
                        base().copy(
                            pollInterval = Duration.ofMinutes(5),
                            threshold = Duration.ofMinutes(3),
                        ),
                    expectedFragment = "pollInterval",
                ),
                InvalidCase(
                    name = "activeWindow equals threshold",
                    props =
                        base().copy(
                            activeWindow = Duration.ofMinutes(3),
                            threshold = Duration.ofMinutes(3),
                        ),
                    expectedFragment = "activeWindow",
                ),
                InvalidCase(
                    name = "activeWindow less than threshold",
                    props =
                        base().copy(
                            activeWindow = Duration.ofMinutes(1),
                            threshold = Duration.ofMinutes(3),
                        ),
                    expectedFragment = "activeWindow",
                ),
                InvalidCase(
                    name = "startupGrace negative",
                    props = base().copy(startupGrace = Duration.ofSeconds(-1)),
                    expectedFragment = "startupGrace",
                ),
            )

        private fun base() =
            SignalLossProperties(
                enabled = true,
                threshold = Duration.ofMinutes(3),
                pollInterval = Duration.ofSeconds(30),
                activeWindow = Duration.ofHours(24),
                startupGrace = Duration.ofMinutes(5),
            )
    }
}
