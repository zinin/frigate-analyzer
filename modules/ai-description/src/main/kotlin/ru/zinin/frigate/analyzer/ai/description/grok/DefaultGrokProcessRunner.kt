package ru.zinin.frigate.analyzer.ai.description.grok

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import java.io.IOException
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Запуск `grok` через ProcessBuilder. stdin закрывается сразу, stdout читается целиком, от stderr
 * остаётся хвост в [STDERR_TAIL_BYTES]. Отмена корутины (таймаут агента) убивает процесс в
 * `finally` и ждёт [KILL_WAIT_TIMEOUT_MS]; если child не уходит, слот агента всё равно
 * освобождается.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
@ConditionalOnProperty("application.ai.description.provider", havingValue = "grok")
class DefaultGrokProcessRunner : GrokProcessRunner {
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
                val stdout = async { process.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8) }
                val stderr = async { tail(process.errorStream.use { it.readBytes() }) }
                val exitCode = process.onExit().await().exitValue()
                GrokProcessResult(exitCode, stdout.await(), stderr.await())
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

    private fun tail(bytes: ByteArray): String =
        if (bytes.size <= STDERR_TAIL_BYTES) {
            bytes.toString(Charsets.UTF_8)
        } else {
            bytes.copyOfRange(bytes.size - STDERR_TAIL_BYTES, bytes.size).toString(Charsets.UTF_8)
        }

    companion object {
        const val STDERR_TAIL_BYTES = 8 * 1024
        const val KILL_WAIT_TIMEOUT_MS = 5_000L

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
