package ru.zinin.frigate.analyzer.telegram.i18n

/** City presets shared by /timezone and the schedule zone screen (label message key → IANA id). */
object TimezonePresets {
    val CITIES =
        listOf(
            "command.timezone.zone.kaliningrad" to "Europe/Kaliningrad",
            "command.timezone.zone.moscow" to "Europe/Moscow",
            "command.timezone.zone.yekaterinburg" to "Asia/Yekaterinburg",
            "command.timezone.zone.omsk" to "Asia/Omsk",
            "command.timezone.zone.krasnoyarsk" to "Asia/Krasnoyarsk",
            "command.timezone.zone.irkutsk" to "Asia/Irkutsk",
            "command.timezone.zone.yakutsk" to "Asia/Yakutsk",
            "command.timezone.zone.vladivostok" to "Asia/Vladivostok",
        )
}
