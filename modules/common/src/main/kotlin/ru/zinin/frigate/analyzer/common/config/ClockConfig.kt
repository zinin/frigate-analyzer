package ru.zinin.frigate.analyzer.common.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.ZoneId
import java.util.Locale

@Configuration
class ClockConfig {
    companion object {
        val DEFAULT_ZONE_ID: ZoneId = ZoneId.systemDefault()

        val MOSCOW_ZONE_ID = ZoneId.of("Europe/Moscow")

        val LOCALE_RU = Locale.of("ru")
    }

    @Bean
    fun clock(): Clock = Clock.systemDefaultZone()
}
