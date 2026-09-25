package ru.zinin.frigate.analyzer.ai.description.grok

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.config.GrokProperties
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

/** Хвост `unified.jsonl`, снятый сразу после выхода процесса `grok`. */
sealed interface CapturedLog {
    /** Только полные строки: обрывки на краях окна отброшены. */
    data class Lines(
        val lines: List<String>,
    ) : CapturedLog

    data class Unavailable(
        val reason: String,
    ) : CapturedLog
}

/** Дошли ли кадры до модели — по записям `grok` о своей сессии. */
sealed interface StripCheck {
    /** Записи сессии есть, выброса среди них нет. */
    data object Clean : StripCheck

    /** `grok` выкинул [images] картинок и повторил запрос без них: ответ получен вслепую. */
    data class Stripped(
        val images: Int,
        val reasons: Set<String>,
    ) : StripCheck

    /** Записей сессии нет — проверить нечем, ответ принимается как раньше. */
    data class Blind(
        val reason: String,
    ) : StripCheck
}

/**
 * Узнаёт, что `grok` выкинул картинки из запроса. grok 1.0.41 на ошибку эндпоинта, которую относит
 * к картинкам (`413 Payload Too Large`, `reason=payload_heuristic`), убирает их, вставляет вместо них
 * текст «The server could not process an image…» и повторяет запрос: процесс выходит с кодом 0, а
 * stdout несёт обычный ответ. Единственный машинный след — строка
 * `"msg":"shell.turn.images_stripped"` с `sid` сессии в `GROK_HOME/logs/unified.jsonl`.
 *
 * Читается хвост файла после запуска, а не смещение, запомненное до него: grok сам урезает
 * `unified.jsonl`, перечитывая и переписывая файл, и смещение после этого указало бы в чужую строку.
 * Записи только что завершившегося запуска — самые свежие и переживают урезание.
 *
 * Файл внутренний, не публичный контракт grok: при смене `GROK_VERSION` формат нужно перепроверить.
 * Пропавший лог или сменившийся формат дают [StripCheck.Blind] и WARN, а не ложный отказ.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class GrokImageStripDetector(
    properties: GrokProperties,
    private val objectMapper: ObjectMapper,
) {
    private val logPath: Path = properties.homePath.resolve(LOG_RELATIVE_PATH)
    private val blindReported = AtomicBoolean(false)

    /**
     * Вызывается сразу после выхода процесса и под тем же `GrokHomeGuard.shared`, что и запуск: иначе
     * ежечасная уборка могла бы удалить файл между выходом и чтением. Блокирующий ввод-вывод —
     * вызывающий уводит его на `Dispatchers.IO`.
     */
    fun capture(): CapturedLog =
        try {
            FileChannel.open(logPath, StandardOpenOption.READ).use { channel ->
                val size = channel.size()
                // Байт перед окном читается тоже: если там '\n', первая строка окна целая и
                // отбрасывать её нельзя; иначе отбрасывается только её обрывок.
                val start = if (size > CAPTURE_TAIL_BYTES) size - CAPTURE_TAIL_BYTES - 1 else 0L
                val text = String(readFrom(channel, start, (size - start).toInt()), Charsets.UTF_8)
                CapturedLog.Lines(completeLines(text, fromFileStart = start == 0L))
            }
        } catch (e: NoSuchFileException) {
            CapturedLog.Unavailable("$logPath not found")
        } catch (e: IOException) {
            CapturedLog.Unavailable("cannot read $logPath: ${e.message}")
        }

    /** Чистая функция: никакого ввода-вывода, только разбор захваченного по [sessionId] из stdout. */
    fun inspect(
        captured: CapturedLog,
        sessionId: String?,
    ): StripCheck {
        if (sessionId.isNullOrBlank()) return StripCheck.Blind("no sessionId in grok output")
        val lines =
            when (captured) {
                is CapturedLog.Unavailable -> return StripCheck.Blind(captured.reason)
                is CapturedLog.Lines -> captured.lines
            }
        var seen = false
        var images = 0
        val reasons = linkedSetOf<String>()
        lines
            .asSequence()
            // Подстрока отсеивает чужие строки до разбора JSON; равенство sid решает окончательно.
            .filter { it.contains(sessionId) }
            .mapNotNull(::parseObject)
            .filter { it.stringField("sid") == sessionId }
            .forEach { entry ->
                seen = true
                if (entry.stringField("msg") == STRIPPED_EVENT) {
                    val ctx = entry["ctx"]
                    // Каждое событие выкидывает картинки, ещё остававшиеся в запросе, поэтому сумма.
                    images += ctx?.get("stripped")?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.intValue() ?: 1
                    ctx?.stringField("reason")?.let(reasons::add)
                }
            }
        return when {
            images > 0 -> StripCheck.Stripped(images, reasons)
            seen -> StripCheck.Clean
            else -> StripCheck.Blind("no entries for session $sessionId in the last $CAPTURE_TAIL_BYTES bytes of $logPath")
        }
    }

    /** WARN при первом вызове за процесс, дальше DEBUG: слепота обычно постоянна, и WARN на каждый вызов был бы шумом. */
    fun reportBlind(reason: String) {
        if (blindReported.compareAndSet(false, true)) {
            logger.warn { "Cannot verify frame delivery for grok runs: $reason; image drops by grok will go unnoticed" }
        } else {
            logger.debug { "Cannot verify frame delivery for grok run: $reason" }
        }
    }

    private fun parseObject(line: String): JsonNode? =
        try {
            objectMapper.readTree(line).takeIf { it.isObject }
        } catch (e: JacksonException) {
            null
        }

    private fun JsonNode.stringField(name: String): String? = this[name]?.takeIf { it.isString }?.stringValue()

    companion object {
        /** Запуск пишет десятки килобайт; в мегабайт помещаются записи нескольких параллельных запусков. */
        const val CAPTURE_TAIL_BYTES: Long = 1024L * 1024L

        const val STRIPPED_EVENT = "shell.turn.images_stripped"

        private const val LOG_RELATIVE_PATH = "logs/unified.jsonl"

        /**
         * Полные строки текста. Последний элемент разбиения — пустая строка после завершающего `\n`
         * или недописанная строка другого запуска: отбрасывается всегда. Первый — обрывок строки,
         * начавшейся до окна, если чтение началось не с начала файла.
         */
        internal fun completeLines(
            text: String,
            fromFileStart: Boolean,
        ): List<String> {
            val parts = text.split('\n').dropLast(1)
            return (if (fromFileStart) parts else parts.drop(1)).filter { it.isNotBlank() }
        }

        private fun readFrom(
            channel: FileChannel,
            start: Long,
            length: Int,
        ): ByteArray {
            val buffer = ByteBuffer.allocate(length)
            var position = start
            while (buffer.hasRemaining()) {
                val read = channel.read(buffer, position)
                if (read < 0) break
                position += read
            }
            return buffer.array().copyOf(buffer.position())
        }
    }
}
