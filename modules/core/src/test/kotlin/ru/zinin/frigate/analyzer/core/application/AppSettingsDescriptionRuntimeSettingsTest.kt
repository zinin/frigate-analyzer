package ru.zinin.frigate.analyzer.core.application

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import ru.zinin.frigate.analyzer.service.AppSettingKeys
import ru.zinin.frigate.analyzer.service.AppSettingsService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppSettingsDescriptionRuntimeSettingsTest {
    private val appSettings = mockk<AppSettingsService>(relaxed = true)
    private val settings = AppSettingsDescriptionRuntimeSettings(appSettings)

    @Test
    fun `an absent preset key reads as null`() =
        runTest {
            coEvery { appSettings.getString(AppSettingKeys.AI_DESCRIPTION_PRESET_ACTIVE, null) } returns null

            assertNull(settings.activePresetId())
        }

    @Test
    fun `an absent enabled key reads as true`() =
        runTest {
            coEvery { appSettings.getBoolean(AppSettingKeys.AI_DESCRIPTION_ENABLED, true) } returns true

            assertTrue(settings.descriptionsEnabled())
        }

    @Test
    fun `the writer passes the actor through`() =
        runTest {
            settings.setActivePresetId("grok-fast", changedBy = "owner")

            coVerify {
                appSettings.setString(AppSettingKeys.AI_DESCRIPTION_PRESET_ACTIVE, "grok-fast", "owner")
            }
        }

    @Test
    fun `the switch writes a boolean`() =
        runTest {
            settings.setDescriptionsEnabled(false, changedBy = "owner")

            coVerify { appSettings.setBoolean(AppSettingKeys.AI_DESCRIPTION_ENABLED, false, "owner") }
        }

    @Test
    fun `keys are stable`() {
        assertEquals("ai.description.preset.active", AppSettingKeys.AI_DESCRIPTION_PRESET_ACTIVE)
        assertEquals("ai.description.enabled", AppSettingKeys.AI_DESCRIPTION_ENABLED)
    }

    /**
     * Имя источника попадает в лог активного пресета (`... from app_settings`) и в INFO-строку при
     * создании. Ошибка в нём не ломает ни одного вызова, поэтому единственное, что её удержит, —
     * ассерт на значение.
     */
    @Test
    fun `the source name says where the choice is stored`() {
        assertEquals("app_settings", settings.sourceName)
    }
}
