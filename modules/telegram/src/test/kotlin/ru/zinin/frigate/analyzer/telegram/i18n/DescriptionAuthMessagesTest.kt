package ru.zinin.frigate.analyzer.telegram.i18n

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DescriptionAuthMessagesTest {
    private fun bundle(language: String): Properties =
        Properties().also { properties ->
            javaClass.getResourceAsStream("/messages_$language.properties")!!.reader(Charsets.UTF_8).use { properties.load(it) }
        }

    @Test
    fun `both bundles carry the auth keys with MessageFormat placeholders and no apostrophes`() {
        listOf("ru", "en").forEach { language ->
            val properties = bundle(language)
            val lost = assertNotNull(properties.getProperty("ai.description.auth.lost"), "$language: lost")
            val restored = assertNotNull(properties.getProperty("ai.description.auth.restored"), "$language: restored")
            assertTrue(lost.contains("{0}") && lost.contains("{1}"), "$language: lost needs {0} and {1}")
            assertTrue(restored.contains("{0}"), "$language: restored needs {0}")
            assertFalse(lost.contains("'") || restored.contains("'"), "$language: apostrophes break MessageFormat")
            // Владелец узнаёт о выходе из авторизации там же, где узнал о проблеме: третья строка
            // ведёт на экран, с которого можно переключиться на живой пресет.
            assertTrue(lost.contains("/ai"), "$language: lost must point at /ai")
        }
    }

    /**
     * `{0}` — это `authScopeId` события (`grok:grok-4.6`), а не провайдер: с Task 5 авторизация
     * принадлежит набору ключей. Оба текста обязаны называть именно область, иначе владелец
     * прочитает `grok:grok-4.6` как имя провайдера и пойдёт искать несуществующий.
     */
    @Test
    fun `both auth texts name the credential scope`() {
        val scopeWords = mapOf("ru" to "област", "en" to "scope")
        listOf("ru", "en").forEach { language ->
            val properties = bundle(language)
            listOf("ai.description.auth.lost", "ai.description.auth.restored").forEach { key ->
                val value = assertNotNull(properties.getProperty(key), "$language: $key")
                assertTrue(value.contains(scopeWords.getValue(language)), "$language: $key must name the scope: $value")
            }
        }
    }
}
