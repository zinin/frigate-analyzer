package ru.zinin.frigate.analyzer.telegram.bot.handler.notifications

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Dialog-phase lock for the manual-zone waiter (`nfs:g:sched:zman`) — prevents two parallel
 * waiters in the same DM from consuming each other's replies (`waitTextMessage()` filters on
 * `chat.id` only, so both would see the same text message).
 *
 * Lock ordering: this tracker must never be acquired while holding `ActiveExportTracker` or
 * `ActiveExportRegistry` (nor they while holding it) — see the lock-ordering invariant in
 * `.claude/rules/telegram-export.md`. Today the export and schedule flows do not intersect;
 * keeping this lock's scope confined to [tryAcquire]/[release], as the export precedent does,
 * is what keeps it that way.
 */
@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class ActiveZoneInputTracker {
    private val activeInputs: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    fun tryAcquire(chatId: Long): Boolean = activeInputs.add(chatId)

    fun release(chatId: Long) {
        activeInputs.remove(chatId)
    }
}
