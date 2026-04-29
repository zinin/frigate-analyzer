package ru.zinin.frigate.analyzer.service.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.stereotype.Service
import ru.zinin.frigate.analyzer.service.AppSettingsService
import ru.zinin.frigate.analyzer.service.repository.AppSettingRepository
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

@Service
class AppSettingsServiceImpl(
    private val repository: AppSettingRepository,
    private val clock: Clock,
) : AppSettingsService {
    private val cache = ConcurrentHashMap<String, String>()
    private val cacheMutex = Mutex()

    override suspend fun getBoolean(
        key: String,
        default: Boolean,
    ): Boolean {
        val raw =
            cache[key] ?: cacheMutex.withLock {
                cache[key] ?: loadAndCache(key)
            }
        if (raw == null) return default
        return raw.toBooleanStrictOrNull() ?: run {
            logger.warn { "AppSettings: invalid stored value for '$key'='$raw'; falling back to default=$default" }
            default
        }
    }

    override suspend fun setBoolean(
        key: String,
        value: Boolean,
        updatedBy: String?,
    ) {
        val v = value.toString()
        repository.upsert(key, v, Instant.now(clock), updatedBy)
        cacheMutex.withLock {
            cache.remove(key)
        }
        logger.info { "AppSettings: '$key' set to $v by ${updatedBy ?: "<system>"}" }
    }

    private suspend fun loadAndCache(key: String): String? {
        val entity = repository.findBySettingKey(key) ?: return null
        val v = entity.settingValue ?: return null
        cache[key] = v
        return v
    }
}
