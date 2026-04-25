package ru.zinin.frigate.analyzer.core.config.properties

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration

class SignalLossPropertiesTest {
    @Test
    fun `valid properties construct without exception and retain documented defaults`() {
        val props = base() // no exception
        assertThat(props.threshold).isEqualTo(Duration.ofMinutes(3))
        assertThat(props.pollInterval).isEqualTo(Duration.ofSeconds(30))
        assertThat(props.activeWindow).isEqualTo(Duration.ofHours(24))
        assertThat(props.startupGrace).isEqualTo(Duration.ofMinutes(5))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidConfigs")
    fun `invalid properties throw IllegalStateException on construction with informative message`(invalid: InvalidCase) {
        assertThatThrownBy { invalid.constructor() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(invalid.expectedFragment)
    }

    data class InvalidCase(
        val name: String,
        val constructor: () -> SignalLossProperties,
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
                    constructor = { base().copy(threshold = Duration.ZERO) },
                    expectedFragment = "threshold",
                ),
                InvalidCase(
                    name = "threshold negative",
                    constructor = { base().copy(threshold = Duration.ofSeconds(-1)) },
                    expectedFragment = "threshold",
                ),
                InvalidCase(
                    name = "pollInterval zero",
                    constructor = { base().copy(pollInterval = Duration.ZERO) },
                    expectedFragment = "pollInterval",
                ),
                InvalidCase(
                    name = "pollInterval negative",
                    constructor = { base().copy(pollInterval = Duration.ofSeconds(-1)) },
                    expectedFragment = "pollInterval",
                ),
                InvalidCase(
                    name = "pollInterval equals threshold",
                    constructor = {
                        base().copy(
                            pollInterval = Duration.ofMinutes(3),
                            threshold = Duration.ofMinutes(3),
                        )
                    },
                    expectedFragment = "pollInterval",
                ),
                InvalidCase(
                    name = "pollInterval greater than threshold",
                    constructor = {
                        base().copy(
                            pollInterval = Duration.ofMinutes(5),
                            threshold = Duration.ofMinutes(3),
                        )
                    },
                    expectedFragment = "pollInterval",
                ),
                InvalidCase(
                    name = "activeWindow zero",
                    constructor = { base().copy(activeWindow = Duration.ZERO) },
                    expectedFragment = "activeWindow",
                ),
                InvalidCase(
                    name = "activeWindow negative",
                    constructor = { base().copy(activeWindow = Duration.ofSeconds(-1)) },
                    expectedFragment = "activeWindow",
                ),
                InvalidCase(
                    name = "activeWindow equals threshold",
                    constructor = {
                        base().copy(
                            activeWindow = Duration.ofMinutes(3),
                            threshold = Duration.ofMinutes(3),
                        )
                    },
                    expectedFragment = "activeWindow",
                ),
                InvalidCase(
                    name = "activeWindow less than threshold",
                    constructor = {
                        base().copy(
                            activeWindow = Duration.ofMinutes(1),
                            threshold = Duration.ofMinutes(3),
                        )
                    },
                    expectedFragment = "activeWindow",
                ),
                InvalidCase(
                    // Codex P2: activeWindow > threshold alone is insufficient. With grace=5m,
                    // threshold=3m, activeWindow=4m: a camera lost just before startup falls out of
                    // activeWindow before grace ends, so its deferred SignalLost(sent=false) never
                    // gets revisited by decide() (camera no longer in stats) and the late-alert
                    // never fires. Reject at startup instead of silently dropping outages.
                    name = "activeWindow greater than threshold but not greater than threshold+startupGrace",
                    constructor = {
                        base().copy(
                            threshold = Duration.ofMinutes(3),
                            activeWindow = Duration.ofMinutes(4),
                            startupGrace = Duration.ofMinutes(5),
                        )
                    },
                    expectedFragment = "activeWindow",
                ),
                InvalidCase(
                    name = "activeWindow equals threshold+startupGrace exactly (strict inequality required)",
                    constructor = {
                        base().copy(
                            threshold = Duration.ofMinutes(3),
                            activeWindow = Duration.ofMinutes(8),
                            startupGrace = Duration.ofMinutes(5),
                        )
                    },
                    expectedFragment = "activeWindow",
                ),
                InvalidCase(
                    name = "startupGrace negative",
                    constructor = { base().copy(startupGrace = Duration.ofSeconds(-1)) },
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
