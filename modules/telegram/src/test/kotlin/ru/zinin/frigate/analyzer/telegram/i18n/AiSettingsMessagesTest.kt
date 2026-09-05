package ru.zinin.frigate.analyzer.telegram.i18n

import java.text.MessageFormat
import java.util.Locale
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AiSettingsMessagesTest {
    private fun bundle(language: String): Properties =
        Properties().also { properties ->
            javaClass.getResourceAsStream("/messages_$language.properties")!!.reader(Charsets.UTF_8).use { properties.load(it) }
        }

    /**
     * Ключ и число аргументов, которые под него передаёт рендер. Тесты рендера работают с
     * мок-резолвером, поэтому расхождение арности — строка экрана с дырой `{2}` или потерянным
     * аргументом — видно только на живом бандле. `MessageKeyParityTest` сверяет бандлы между собой
     * и такого не ловит.
     */
    private val arityByKey =
        mapOf(
            "ai.settings.title" to 0,
            "ai.settings.state" to 1,
            "ai.settings.state.on" to 0,
            "ai.settings.state.off" to 0,
            "ai.settings.active" to 4,
            "ai.settings.active.none" to 0,
            "ai.settings.active.mismatch" to 4,
            "ai.settings.mismatch.kept" to 0,
            "ai.settings.mismatch.recheck" to 0,
            "ai.settings.reason.noToken" to 0,
            "ai.settings.reason.homeUnwritable" to 1,
            "ai.settings.reason.noFactory" to 1,
            "ai.settings.reason.gone" to 0,
            "ai.settings.reason.unknown" to 0,
            "ai.settings.auth.healthy" to 1,
            "ai.settings.auth.lost" to 1,
            "ai.settings.auth.unknown" to 1,
            "ai.settings.auth.unavailable" to 2,
            "ai.settings.auth.note" to 0,
            "ai.settings.slow.note" to 1,
            "ai.settings.button.enable" to 0,
            "ai.settings.button.disable" to 0,
            "ai.settings.button.close" to 0,
            "ai.settings.alert.unavailable" to 1,
            // Не строка экрана, а описание команды: его подставляет регистрация команд владельца
            // (`registerOwnerCommands`) и раздел `/help` для владельца.
            "command.ai.description" to 0,
        )

    @Test
    fun `both bundles carry every screen key with the placeholders the renderer fills`() {
        listOf("ru", "en").forEach { language ->
            val properties = bundle(language)
            arityByKey.forEach { (key, arity) ->
                val value = assertNotNull(properties.getProperty(key), "$language: missing $key")
                assertFalse(value.contains("'"), "$language: $key — апостроф ломает MessageFormat: $value")
                val rendered =
                    MessageFormat(value, Locale.forLanguageTag(language))
                        .format(Array<Any>(arity) { "arg$it" })
                repeat(arity) { index ->
                    assertTrue(rendered.contains("arg$index"), "$language: $key must use {$index}: $value")
                }
                assertFalse(rendered.contains("{"), "$language: $key has an unfilled placeholder: $rendered")
            }
        }
    }
}
