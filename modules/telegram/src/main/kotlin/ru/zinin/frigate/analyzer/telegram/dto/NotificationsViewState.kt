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
    val language: String,
)
