package ru.zinin.frigate.analyzer.telegram.bot.handler.export

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class ActiveExportTracker {
    private val activeExports: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    fun tryAcquire(chatId: Long): Boolean = activeExports.add(chatId)

    fun release(chatId: Long) {
        activeExports.remove(chatId)
    }
}
