package ru.zinin.frigate.analyzer.telegram.dto

import java.time.ZoneId

data class UserZoneInfo(val chatId: Long, val zone: ZoneId)
