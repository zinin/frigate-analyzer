package ru.zinin.frigate.analyzer.core.storyboard

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.config.properties.ApplicationProperties
import ru.zinin.frigate.analyzer.core.helper.TempFileHelper
import ru.zinin.frigate.analyzer.core.video.FfmpegProcessRunner
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Locale
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Нарезает кадры одного сегмента: [sample] делает один запуск ffmpeg — точный переход `-ss` к
 * первому моменту и фильтр `fps` с шагом раскадровки. Кадров может прийти меньше, если файл кончился
 * раньше.
 *
 * Кадры пишутся в temp-папку файлами с уникальным префиксом: `FfmpegProcessRunner` читает вывод
 * процесса как текст, так что JPEG через stdout не забрать. Файлы удаляются в `finally`.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class StoryboardFrameSampler(
    private val applicationProperties: ApplicationProperties,
    private val processRunner: FfmpegProcessRunner,
    private val tempFileHelper: TempFileHelper,
) {
    suspend fun sample(
        file: Path,
        firstSeconds: Double,
        stepSeconds: Double,
        count: Int,
        tileWidth: Int,
    ): List<ByteArray> {
        require(count > 0) { "count must be positive, was $count" }
        require(stepSeconds > 0.0) { "stepSeconds must be positive, was $stepSeconds" }
        val dir = applicationProperties.tempFolder.toAbsolutePath().normalize()
        val prefix = "storyboard-${UUID.randomUUID()}-"
        try {
            processRunner.run(
                command(file, firstSeconds, stepSeconds, count, tileWidth, dir.resolve("$prefix%03d.jpg")),
                FFMPEG_TIMEOUT,
            )
            return withContext(Dispatchers.IO) { outputs(dir, prefix).map { Files.readAllBytes(it) } }
        } finally {
            // NonCancellable: сюда часто попадают через отмену или таймаут, а файлы удалить нужно всё равно.
            withContext(NonCancellable) {
                runCatching { tempFileHelper.deleteFiles(withContext(Dispatchers.IO) { outputs(dir, prefix) }) }
                    .onFailure { logger.warn(it) { "Failed to delete storyboard frames $prefix*" } }
            }
        }
    }

    internal fun command(
        file: Path,
        firstSeconds: Double,
        stepSeconds: Double,
        count: Int,
        tileWidth: Int,
        output: Path,
    ): List<String> =
        listOf(
            applicationProperties.ffmpegPath.toString(),
            "-hide_banner",
            "-loglevel",
            "error",
            "-nostdin",
            "-y",
            "-ss",
            decimal(firstSeconds, 3),
            "-i",
            file.toString(),
            "-an",
            "-vf",
            "fps=${decimal(1.0 / stepSeconds, 6)},scale=$tileWidth:-2",
            "-frames:v",
            count.toString(),
            "-q:v",
            "3",
            output.toString(),
        )

    private fun outputs(
        dir: Path,
        prefix: String,
    ): List<Path> =
        Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().startsWith(prefix) }.sorted().toList()
        }

    private fun decimal(
        value: Double,
        digits: Int,
    ): String = String.format(Locale.US, "%.${digits}f", value)

    companion object {
        val FFMPEG_TIMEOUT: Duration = Duration.ofSeconds(20)
    }
}
