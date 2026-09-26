package ru.zinin.frigate.analyzer.ai.description.config

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DescriptionPresetsValidationTest {
    private val common =
        DescriptionProperties.CommonSection(
            language = "en",
            shortMaxLength = 200,
            detailedMaxLength = 1500,
            maxFrames = 10,
            queueTimeout = Duration.ofSeconds(30),
            timeout = Duration.ofSeconds(60),
            maxConcurrent = 2,
        )

    private fun props(
        presets: Map<String, DescriptionProperties.Preset>,
        defaultPreset: String = "",
    ) = DescriptionProperties(
        enabled = true,
        provider = "grok",
        common = common,
        defaultPreset = defaultPreset,
        presets = presets,
    )

    @Test
    fun `a valid map binds`() {
        val parsed =
            props(
                mapOf(
                    "grok-fast" to DescriptionProperties.Preset(provider = "grok", model = "grok-4.6", effort = "low"),
                    "claude-opus" to DescriptionProperties.Preset(provider = "claude", model = "opus"),
                ),
                defaultPreset = "grok-fast",
            )

        assertEquals(listOf("grok-fast", "claude-opus"), parsed.presets.keys.toList())
        assertEquals("", parsed.presets.getValue("claude-opus").effort)
    }

    @Test
    fun `an unknown provider is rejected`() {
        val e =
            assertFailsWith<IllegalArgumentException> {
                props(mapOf("x" to DescriptionProperties.Preset(provider = "gemini", model = "m")))
            }
        assertTrue(e.message!!.contains("gemini"), e.message)
    }

    @Test
    fun `effort on a claude preset is rejected`() {
        val e =
            assertFailsWith<IllegalArgumentException> {
                props(mapOf("c" to DescriptionProperties.Preset(provider = "claude", model = "opus", effort = "low")))
            }
        assertTrue(e.message!!.contains("effort"), e.message)
    }

    @Test
    fun `an effort outside the set is rejected`() {
        val e =
            assertFailsWith<IllegalArgumentException> {
                props(mapOf("g" to DescriptionProperties.Preset(provider = "grok", model = "m", effort = "turbo")))
            }
        assertTrue(e.message!!.contains("turbo"), e.message)
    }

    @Test
    fun `a blank model is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            props(mapOf("g" to DescriptionProperties.Preset(provider = "grok", model = " ")))
        }
    }

    @Test
    fun `a malformed id is rejected`() {
        val e =
            assertFailsWith<IllegalArgumentException> {
                props(mapOf("Grok Fast" to DescriptionProperties.Preset(provider = "grok", model = "m")))
            }
        assertTrue(e.message!!.contains("Grok Fast"), e.message)
    }

    @Test
    fun `a default-preset outside the map is rejected`() {
        val e =
            assertFailsWith<IllegalArgumentException> {
                props(
                    mapOf("g" to DescriptionProperties.Preset(provider = "grok", model = "m")),
                    defaultPreset = "missing",
                )
            }
        assertTrue(e.message!!.contains("missing"), e.message)
    }

    /**
     * Миграция «сначала env, потом yaml»: оператор выставляет `APP_AI_DESCRIPTION_DEFAULT_PRESET`
     * до того, как объявит карту пресетов. Пустая карта — это legacy-путь, где имя пресета ещё
     * ни на что не ссылается, поэтому старт не ломаем: только WARN.
     */
    @Test
    fun `a non-blank default-preset with an empty map is allowed`() {
        val parsed = props(emptyMap(), defaultPreset = "grok-fast")

        assertEquals("grok-fast", parsed.defaultPreset)
        assertEquals(emptyMap(), parsed.presets)
    }

    @Test
    fun `an empty map with a blank default-preset is allowed`() {
        assertEquals(emptyMap(), props(emptyMap()).presets)
    }

    @Test
    fun `a preset max-image-side below 256 is rejected with the preset id`() {
        val e =
            assertFailsWith<IllegalArgumentException> {
                props(mapOf("byok" to DescriptionProperties.Preset(provider = "grok", model = "m", maxImageSide = 100)))
            }
        assertTrue(e.message!!.contains("byok"), e.message)
        assertTrue(e.message!!.contains("max-image-side"), e.message)
    }

    @Test
    fun `a preset max-image-side above 8192 is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            props(mapOf("byok" to DescriptionProperties.Preset(provider = "grok", model = "m", maxImageSide = 9000)))
        }
    }

    /** Потолок не зависит от провайдера: claude за сторонним шлюзом нуждается в нём так же, как BYOK-grok. */
    @Test
    fun `a preset max-image-side of zero or within range is accepted for any provider`() {
        val parsed =
            props(
                mapOf(
                    "byok" to DescriptionProperties.Preset(provider = "grok", model = "m", maxImageSide = 1568),
                    "gateway" to DescriptionProperties.Preset(provider = "claude", model = "opus", maxImageSide = 256),
                    "plain" to DescriptionProperties.Preset(provider = "grok", model = "m"),
                ),
            )

        assertEquals(1568, parsed.presets.getValue("byok").maxImageSide)
        assertEquals(256, parsed.presets.getValue("gateway").maxImageSide)
        assertEquals(0, parsed.presets.getValue("plain").maxImageSide)
    }
}
