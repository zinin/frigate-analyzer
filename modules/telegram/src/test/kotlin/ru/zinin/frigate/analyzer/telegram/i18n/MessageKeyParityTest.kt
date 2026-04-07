package ru.zinin.frigate.analyzer.telegram.i18n

import org.junit.jupiter.api.Test
import java.util.Properties
import kotlin.test.assertTrue

class MessageKeyParityTest {
    @Test
    fun `ru and en properties have same keys`() {
        val classLoader = Thread.currentThread().contextClassLoader
        val ruProps =
            Properties().apply {
                load(classLoader.getResourceAsStream("messages_ru.properties"))
            }
        val enProps =
            Properties().apply {
                load(classLoader.getResourceAsStream("messages_en.properties"))
            }
        val ruKeys = ruProps.stringPropertyNames()
        val enKeys = enProps.stringPropertyNames()

        val missingInEn = ruKeys - enKeys
        val missingInRu = enKeys - ruKeys

        assertTrue(missingInEn.isEmpty(), "Keys missing in messages_en.properties: $missingInEn")
        assertTrue(missingInRu.isEmpty(), "Keys missing in messages_ru.properties: $missingInRu")
    }
}
