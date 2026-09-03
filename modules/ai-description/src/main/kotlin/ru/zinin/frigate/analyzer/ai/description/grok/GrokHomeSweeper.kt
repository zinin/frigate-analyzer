package ru.zinin.frigate.analyzer.ai.description.grok

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.config.GrokProperties
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Каждый headless-запуск оставляет в `GROK_HOME/sessions/<cwd>/<id>/` копию промпта с base64
 * кадров, а `sessions/session_search.sqlite` растёт на ~9 КБ за запуск и при удалении каталогов
 * не сжимается. Политики хранения у Grok нет. Раз в час под [GrokHomeGuard.exclusive] удаляется
 * всё содержимое `sessions/` и файлы в `logs/`; Grok пересоздаёт индекс и логи при следующем
 * запуске. `auth.json`, `config.toml` и остальное не трогаются. Приложение единственный
 * пользователь этого GROK_HOME, `grok login` сессий не создаёт.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
@ConditionalOnProperty("application.ai.description.provider", havingValue = "grok")
class GrokHomeSweeper(
    private val properties: GrokProperties,
    private val guard: GrokHomeGuard,
) {
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    fun sweepScheduled() {
        runBlocking(Dispatchers.IO) {
            try {
                sweep()
            } catch (e: Exception) {
                logger.warn(e) { "Grok home sweep failed" }
            }
        }
    }

    /** Возвращает число удалённых записей верхнего уровня (каталогов сессий и файлов). */
    suspend fun sweep(): Int =
        guard.exclusive {
            withContext(Dispatchers.IO) {
                val home = properties.homePath
                val removed =
                    clearDirectory(home.resolve("sessions"), removeSubdirectories = true) +
                        clearDirectory(home.resolve("logs"), removeSubdirectories = false)
                logger.debug { "Grok home sweep removed $removed entries under $home" }
                removed
            }
        }

    private fun clearDirectory(
        dir: Path,
        removeSubdirectories: Boolean,
    ): Int {
        if (!Files.isDirectory(dir)) return 0
        var removed = 0
        Files.list(dir).use { entries ->
            entries.forEach { entry ->
                try {
                    when {
                        Files.isRegularFile(entry) -> {
                            Files.deleteIfExists(entry)
                            removed++
                        }

                        Files.isDirectory(entry) && removeSubdirectories -> {
                            deleteRecursively(entry)
                            removed++
                        }
                    }
                } catch (e: IOException) {
                    logger.warn(e) { "Failed to remove $entry during Grok home sweep" }
                }
            }
        }
        return removed
    }

    private fun deleteRecursively(root: Path) {
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
