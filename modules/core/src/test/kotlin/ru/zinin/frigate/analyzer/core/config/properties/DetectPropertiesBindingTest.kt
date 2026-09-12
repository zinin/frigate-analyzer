package ru.zinin.frigate.analyzer.core.config.properties

import jakarta.validation.Validation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Binds `application.detect` out of the production yaml via [ProductionYamlBinder] and runs the
 * jakarta validation Spring applies at startup.
 *
 * The frame-extraction block is the client half of the `/extract/frames` contract of
 * vision-api 3.0, so its ranges are the server's. A value the server answers with 422 is worse
 * than a boot failure: `FrameExtractorProducer` turns that answer into a recording marked
 * processed-with-error, so a typo in `.env` would bury every recording in silence.
 */
class DetectPropertiesBindingTest {
    private val validator = Validation.buildDefaultValidatorFactory().validator

    @Test
    fun `with nothing set, frame extraction keeps the documented defaults and validates`() {
        val props = bind()

        assertThat(props.frameExtraction).isEqualTo(
            FrameExtractionConfig(
                maxGap = 4.0,
                motionThreshold = 0.001,
                minInterval = 1.0,
                maxFrames = 6,
                quality = 85,
            ),
        )
        assertThat(validator.validate(props)).isEmpty()
    }

    @Test
    fun `the frame extraction tunables follow their environment variables`() {
        val props =
            bind(
                env =
                    mapOf(
                        "DETECT_MAX_GAP" to "2.5",
                        "DETECT_MOTION_THRESHOLD" to "0.005",
                        "DETECT_MIN_INTERVAL" to "0.5",
                        "DETECT_MAX_FRAMES" to "12",
                        "DETECT_FRAME_QUALITY" to "70",
                    ),
            )

        assertThat(props.frameExtraction).isEqualTo(
            FrameExtractionConfig(
                maxGap = 2.5,
                motionThreshold = 0.005,
                minInterval = 0.5,
                maxFrames = 12,
                quality = 70,
            ),
        )
        assertThat(validator.validate(props)).isEmpty()
    }

    @Test
    fun `a frame budget above the server's cap is rejected by validation`() {
        val props = bind(env = mapOf("DETECT_MAX_FRAMES" to "201"))

        val violations = validator.validate(props)

        assertThat(violations.map { it.propertyPath.toString() }).containsExactly("frameExtraction.maxFrames")
    }

    @Test
    fun `an interval below the server's floor is rejected by validation`() {
        val props = bind(env = mapOf("DETECT_MIN_INTERVAL" to "0"))

        val violations = validator.validate(props)

        assertThat(violations.map { it.propertyPath.toString() }).containsExactly("frameExtraction.minInterval")
    }

    @Test
    fun `a grid step below the server's floor is rejected by validation`() {
        val props = bind(env = mapOf("DETECT_MAX_GAP" to "0.4"))

        val violations = validator.validate(props)

        assertThat(violations.map { it.propertyPath.toString() }).containsExactly("frameExtraction.maxGap")
    }

    @Test
    fun `a motion threshold outside the server's range is rejected by validation`() {
        val props = bind(env = mapOf("DETECT_MOTION_THRESHOLD" to "0.5"))

        val violations = validator.validate(props)

        assertThat(violations.map { it.propertyPath.toString() }).containsExactly("frameExtraction.motionThreshold")
    }

    private fun bind(env: Map<String, Any> = emptyMap()): DetectProperties =
        ProductionYamlBinder.bind("application.detect", DetectProperties::class.java, env = env)
}
