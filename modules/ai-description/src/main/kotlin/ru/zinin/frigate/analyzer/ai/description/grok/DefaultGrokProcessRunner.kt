package ru.zinin.frigate.analyzer.ai.description.grok

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.api.TempFileWriter
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Запуск `grok` через ProcessBuilder. stdin закрывается сразу. stdout и stderr идут не в pipe, а во
 * временные файлы, которые читаются после выхода процесса: stdout целиком, но не больше
 * [STDOUT_MAX_BYTES] (превышение — [DescriptionException.Transport]), от stderr берётся хвост
 * [STDERR_TAIL_BYTES]. Отмена корутины (таймаут агента) убивает процесс в `finally` и ждёт
 * [KILL_WAIT_TIMEOUT_MS]; файлы удаляются под `NonCancellable`.
 *
 * Файлы вместо pipe именно из-за внуков. Процесс `grok` может оставить потомка, унаследовавшего
 * концы pipe: он держит их открытыми и после выхода самого `grok`, а блокирующий `InputStream.read`
 * не прерывается ни отменой корутины, ни `close()` на потоке — проверено, читатель остаётся висеть.
 * С pipe это стоило бы потока `Dispatchers.IO`, пары дескрипторов и, в худшем случае, готового
 * ответа модели. Файл же читается после `onExit()` независимо от того, держит ли его кто-то ещё.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class DefaultGrokProcessRunner(
    private val tempFileWriter: TempFileWriter,
) : GrokProcessRunner {
    override suspend fun run(command: GrokCommand): GrokProcessResult {
        val stdoutFile = tempFileWriter.createTempFile("grok-stdout-", ".log", EMPTY_CONTENT)
        val stderrFile = tempFileWriter.createTempFile("grok-stderr-", ".log", EMPTY_CONTENT)
        try {
            return withContext(Dispatchers.IO) {
                val process =
                    try {
                        ProcessBuilder(command.argv)
                            .directory(command.workingDirectory.toFile())
                            .redirectOutput(stdoutFile.toFile())
                            .redirectError(stderrFile.toFile())
                            .also { builder ->
                                val env = builder.environment()
                                env.keys.toList().forEach { env.remove(it) }
                                env.putAll(isolatedEnvironment(command.environment))
                            }.start()
                    } catch (e: IOException) {
                        throw DescriptionException.Transport(e, "cannot start ${command.argv.first()}: ${e.message}")
                    }
                try {
                    process.outputStream.close()
                    val exitCode = process.onExit().await().exitValue()
                    GrokProcessResult(
                        exitCode = exitCode,
                        stdout = readAtMost(stdoutFile, STDOUT_MAX_BYTES),
                        stderrTail = readTail(stderrFile, STDERR_TAIL_BYTES),
                    )
                } finally {
                    if (process.isAlive) {
                        logger.debug { "Killing grok process ${process.pid()} after cancellation" }
                        process.destroyForcibly()
                        if (!process.waitFor(KILL_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                            logger.warn {
                                "grok process ${process.pid()} still alive after ${KILL_WAIT_TIMEOUT_MS}ms SIGKILL"
                            }
                        }
                    }
                }
            }
        } finally {
            withContext(NonCancellable) { tempFileWriter.deleteFiles(listOf(stdoutFile, stderrFile)) }
        }
    }

    companion object {
        const val STDERR_TAIL_BYTES = 8 * 1024
        const val STDOUT_MAX_BYTES = 16 * 1024 * 1024
        const val KILL_WAIT_TIMEOUT_MS = 5_000L

        private val EMPTY_CONTENT = ByteArray(0)

        /** Размер известен заранее, поэтому переросший ответ отвергается до чтения. */
        fun readAtMost(
            file: Path,
            maxBytes: Int,
        ): String {
            val size = Files.size(file)
            if (size > maxBytes) {
                throw DescriptionException.Transport(detail = "grok stdout exceeded $maxBytes bytes")
            }
            // Не Files.readString: битый UTF-8 должен дать U+FFFD, а не исключение поверх ответа.
            return String(Files.readAllBytes(file), Charsets.UTF_8)
        }

        /** Хвост файла; разрезанный по границе байт символ становится U+FFFD, как и раньше. */
        fun readTail(
            file: Path,
            maxBytes: Int,
        ): String {
            val size = Files.size(file)
            val from = maxOf(0, size - maxBytes)
            val length = (size - from).toInt()
            if (length == 0) return ""
            val buffer = ByteBuffer.allocate(length)
            Files.newByteChannel(file, StandardOpenOption.READ).use { channel ->
                channel.position(from)
                while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                    // читаем до заполнения буфера или конца файла
                }
            }
            return String(buffer.array(), 0, buffer.position(), Charsets.UTF_8)
        }

        private val INHERITED_KEYS = listOf("PATH", "HOME", "LANG", "LC_ALL", "LC_CTYPE", "TZ", "USER", "LOGNAME", "TERM")

        /**
         * Env дочернего процесса: не копия JVM. PATH/HOME/locale, затем `GROK_*`/`XAI_*` с хоста
         * (BYOK `env_key`), затем [overrides] (`GROK_HOME`, изоляция, прокси).
         */
        fun isolatedEnvironment(overrides: Map<String, String>): Map<String, String> {
            val env = linkedMapOf<String, String>()
            INHERITED_KEYS.forEach { key ->
                System.getenv(key)?.let { env[key] = it }
            }
            System.getenv().forEach { (key, value) ->
                if (key.startsWith("GROK_") || key.startsWith("XAI_")) {
                    env[key] = value
                }
            }
            env.putAll(overrides)
            return env
        }
    }
}
