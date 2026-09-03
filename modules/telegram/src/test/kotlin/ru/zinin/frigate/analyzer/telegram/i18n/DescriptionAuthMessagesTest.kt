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
        }
    }
}
