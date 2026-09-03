package ru.zinin.frigate.analyzer.ai.description.grok

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Запуск `grok` через ProcessBuilder. stdin закрывается сразу. stdout читается потоком до
 * [STDOUT_MAX_BYTES], превышение — [DescriptionException.Transport]. От stderr остаётся хвост
 * [STDERR_TAIL_BYTES] в кольцевом буфере. Отмена корутины (таймаут агента) убивает процесс в
 * `finally` и ждёт [KILL_WAIT_TIMEOUT_MS]; если child не уходит, слот агента всё равно
 * освобождается.
 *
 * Чтение потоков идёт в собственном scope, а не в дочерних корутинах вызова, и ограничено
 * [drainTimeoutMs] после выхода процесса. Причина: внук, унаследовавший конец pipe, держит
 * `read` открытым и после смерти самого `grok`, а блокирующее чтение отмену корутины не
 * замечает — структурная иерархия не дала бы `withContext` вернуться, и слот семафора агента
 * пропал бы до перезапуска JVM.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
@ConditionalOnProperty("application.ai.description.provider", havingValue = "grok")
class DefaultGrokProcessRunner(
    /** Сколько ждать закрытия stdout/stderr после выхода процесса; параметром — ради тестов. */
    private val drainTimeoutMs: Long = STREAM_DRAIN_TIMEOUT_MS,
) : GrokProcessRunner {
    private val readerScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("grok-stream-reader"))

    @PreDestroy
    fun shutdown() {
        readerScope.cancel()
    }

    override suspend fun run(command: GrokCommand): GrokProcessResult =
        withContext(Dispatchers.IO) {
            val process =
                try {
                    ProcessBuilder(command.argv)
                        .directory(command.workingDirectory.toFile())
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
                val stdout = readerScope.async { process.inputStream.use { readAtMost(it, STDOUT_MAX_BYTES) } }
                val stderr = readerScope.async { process.errorStream.use { readTail(it, STDERR_TAIL_BYTES) } }
                val exitCode = process.onExit().await().exitValue()
                val streams = withTimeoutOrNull(drainTimeoutMs) { stdout.await() to stderr.await() }
                if (streams == null) {
                    stdout.cancel()
                    stderr.cancel()
                    throw DescriptionException.Transport(
                        detail = "grok exited with code $exitCode but left its output pipe open for ${drainTimeoutMs}ms",
                    )
                }
                GrokProcessResult(exitCode, streams.first, streams.second)
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

    companion object {
        const val STDERR_TAIL_BYTES = 8 * 1024
        const val STDOUT_MAX_BYTES = 16 * 1024 * 1024
        const val KILL_WAIT_TIMEOUT_MS = 5_000L

        /** Процесс уже вышел: свои буферы дочитываются мгновенно, ждать дольше нечего. */
        const val STREAM_DRAIN_TIMEOUT_MS = 5_000L

        fun readAtMost(
            stream: InputStream,
            maxBytes: Int,
        ): String {
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var total = 0
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                if (total + n > maxBytes) {
                    throw DescriptionException.Transport(detail = "grok stdout exceeded $maxBytes bytes")
                }
                out.write(chunk, 0, n)
                total += n
            }
            return out.toString(Charsets.UTF_8)
        }

        fun readTail(
            stream: InputStream,
            maxBytes: Int,
        ): String {
            val ring = ByteArray(maxBytes)
            var filled = 0
            var pos = 0
            val chunk = ByteArray(8192)
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                for (i in 0 until n) {
                    ring[pos] = chunk[i]
                    pos = (pos + 1) % maxBytes
                    if (filled < maxBytes) filled++
                }
            }
            if (filled < maxBytes) {
                return String(ring, 0, filled, Charsets.UTF_8)
            }
            val result = ByteArray(maxBytes)
            System.arraycopy(ring, pos, result, 0, maxBytes - pos)
            System.arraycopy(ring, 0, result, maxBytes - pos, pos)
            return String(result, Charsets.UTF_8)
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
