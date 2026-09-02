package ru.zinin.frigate.analyzer.core.config.properties

import jakarta.validation.Validation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Binds `application.export` out of the production yaml via [ProductionYamlBinder] and runs the
 * jakarta validation Spring applies at startup, so a preset typo fails the boot rather than the
 * first oversized export.
 */
class ExportPropertiesBindingTest {
    private val validator = Validation.buildDefaultValidatorFactory().validator

    @Test
    fun `with nothing set, the compress block keeps the documented defaults and validates`() {
        val props = bind()

        assertThat(props.compress).isEqualTo(CompressProperties(preset = "fast", crf = 23, minBitsPerPixel = 0.1))
        assertThat(validator.validate(props)).isEmpty()
    }

    @Test
    fun `the compress tunables follow their environment variables`() {
        val props =
            bind(
                env =
                    mapOf(
                        "EXPORT_COMPRESS_PRESET" to "veryfast",
                        "EXPORT_COMPRESS_CRF" to "26",
                        "EXPORT_COMPRESS_MIN_BITS_PER_PIXEL" to "0.08",
                    ),
            )

        assertThat(props.compress).isEqualTo(CompressProperties(preset = "veryfast", crf = 26, minBitsPerPixel = 0.08))
        assertThat(validator.validate(props)).isEmpty()
    }

    @Test
    fun `a preset that libx264 does not know is rejected by validation`() {
        val props = bind(env = mapOf("EXPORT_COMPRESS_PRESET" to "fst"))

        val violations = validator.validate(props)

        assertThat(violations.map { it.propertyPath.toString() }).containsExactly("compress.preset")
    }

    private fun bind(env: Map<String, Any> = emptyMap()): ExportProperties =
        ProductionYamlBinder.bind("application.export", ExportProperties::class.java, env = env)
}
