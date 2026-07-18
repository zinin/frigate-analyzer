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
    // Wrapper so the cache can also hold "key is absent" (value == null):
    // ConcurrentHashMap forbids null values. A failed repository read caches
    // NOTHING — read errors must stay transient, or fail-open would outlive the failure.
    private class CachedValue(
        val value: String?,
    )

    private val cache = ConcurrentHashMap<String, CachedValue>()
    private val cacheMutex = Mutex()

    override suspend fun getBoolean(
        key: String,
        default: Boolean,
    ): Boolean {
        val raw = getRaw(key) ?: return default
        return raw.toBooleanStrictOrNull() ?: run {
            logger.warn { "AppSettings: invalid stored value for '$key'='$raw'; falling back to default=$default" }
            default
        }
    }

    // Booleans are stored as their string form; the whole write path lives in setString.
    override suspend fun setBoolean(
        key: String,
        value: Boolean,
        updatedBy: String?,
    ) {
        setString(key, value.toString(), updatedBy)
    }

    override suspend fun getString(
        key: String,
        default: String?,
    ): String? = getRaw(key) ?: default

    override suspend fun setString(
        key: String,
        value: String,
        updatedBy: String?,
    ) {
        val rows = repository.upsert(key, value, Instant.now(clock), updatedBy)
        if (rows == 0L) {
            logger.warn { "AppSettings: upsert of '$key' reported 0 affected rows" }
        }
        cacheMutex.withLock {
            cache.remove(key)
        }
        // Value at debug only: general-purpose method, a future key may hold something sensitive.
        logger.info { "AppSettings: '$key' set by ${updatedBy ?: "<system>"}" }
        logger.debug { "AppSettings: '$key' value: '$value'" }
    }

    private suspend fun getRaw(key: String): String? {
        val cached =
            cache[key] ?: cacheMutex.withLock {
                cache[key] ?: loadAndCache(key)
            }
        return cached.value
    }

    private suspend fun loadAndCache(key: String): CachedValue {
        val entity = repository.findBySettingKey(key)
        return CachedValue(entity?.settingValue).also { cache[key] = it }
    }
}
