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

private val logger = KotlinLogging.logger {}

/**
 * Запуск `grok` через ProcessBuilder. stdin закрывается сразу, stdout читается целиком, от stderr
 * остаётся хвост в [STDERR_TAIL_BYTES]. Отмена корутины (таймаут агента) убивает процесс в
 * `finally`, поэтому зависший `grok` не переживает свой вызов.
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
                        .also { it.environment().putAll(command.environment) }
                        .start()
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
                    process.waitFor()
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
    }
}
