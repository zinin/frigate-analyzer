package ru.zinin.frigate.analyzer.telegram.dto

data class NotificationsViewState(
    val isOwner: Boolean,
    val recordingUserEnabled: Boolean,
    val signalUserEnabled: Boolean,
    /**
     * Recording global toggle. Required when [isOwner] = true; must be `null` for non-OWNER
     * to make accidental reads of `app_settings` for plain users a programming error.
     */
    val recordingGlobalEnabled: Boolean?,
    /** Signal global toggle. Same null-discipline as [recordingGlobalEnabled]. */
    val signalGlobalEnabled: Boolean?,
    /** Schedule enabled flag. Populated for OWNER; `null` for non-OWNER. */
    val scheduleEnabled: Boolean? = null,
    /** Configured window in display form ("00:00–07:00"); `null` when not configured or non-OWNER. */
    val scheduleWindow: String? = null,
    /** Configured zone id ("Europe/Moscow"); `null` when not configured or non-OWNER. */
    val scheduleZone: String? = null,
    val language: String,
)
