package ru.zinin.frigate.analyzer.telegram.queue

import dev.inmo.tgbotapi.requests.abstracts.FileId
import java.util.concurrent.atomic.AtomicReference

/**
 * Идентификаторы загруженных кадров одной записи, общие для всех её получателей.
 * Первый отправитель грузит байты и кладёт сюда `file_id`, остальные ссылаются на них.
 *
 * Очередь уведомлений разбирается одним потребителем, поэтому «первый» определяется порядком
 * задач и гонки нет. [AtomicReference] защищает не от неё, а от возможного распараллеливания
 * очереди в будущем: худшее, что тогда случится — лишняя загрузка, а не рассинхронизация.
 */
class SharedFrameIds {
    private val ref = AtomicReference<List<FileId>?>(null)

    fun get(): List<FileId>? = ref.get()

    /** Пустой список не кэшируется: иначе получатели без кадров отравили бы кэш остальным. */
    fun putIfAbsent(ids: List<FileId>): Boolean {
        if (ids.isEmpty()) return false
        return ref.compareAndSet(null, ids)
    }

    /** Сбрасывает кэш после отказа отправки по `file_id`, чтобы кадры ушли байтами. */
    fun invalidate() {
        ref.set(null)
    }
}
