package ru.zinin.frigate.analyzer.core.video

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private val logger = KotlinLogging.logger {}

/**
 * Runs an external ffmpeg or ffprobe command and waits for it with a timeout.
 *
 * stdout and stderr are merged and drained in a daemon thread so that `waitFor(timeout)` never
 * blocks on a full pipe. Only the last [MAX_OUTPUT_LINES] lines are kept: ffprobe JSON is far
 * below that, and ffmpeg output is only needed as an error tail. ffmpeg prints a progress line
 * several times a second (carriage-return separated, which `readLine` treats as a line end), so a
 * long encode would otherwise push the real failure reason out of a first-N-lines window.
 */
@Component
class FfmpegProcessRunner {
    /**
     * @return captured output lines, stdout and stderr together, at most the last [MAX_OUTPUT_LINES].
     * @throws RuntimeException when the exit code is not zero (the message carries the last
     *   [ERROR_TAIL_LINES] lines of output) or when the process does not finish within [timeout]
     *   (the process is killed first).
     */
    suspend fun run(
        command: List<String>,
        timeout: Duration,
    ): List<String> {
        require(command.isNotEmpty()) { "command must not be empty" }
        require(command.first().isNotBlank()) { "command executable must not be blank" }
        val tool = Path.of(command.first()).fileName?.toString() ?: command.first()
        logger.debug { "Running $tool: ${command.joinToString(" ")}" }

        return withContext(Dispatchers.IO) {
            val process =
                ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()

            val outputLines = ArrayDeque<String>()
            val outputThread =
                thread(isDaemon = true) {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            logger.trace { "$tool: $line" }
                            synchronized(outputLines) {
                                if (outputLines.size == MAX_OUTPUT_LINES) {
                                    outputLines.removeFirst()
                                }
                                outputLines.addLast(line)
                            }
                        }
                    }
                }

            val completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroyForcibly()
                // Reap the killed process so it does not linger as a zombie until the JVM notices.
                process.waitFor(KILL_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                outputThread.join(OUTPUT_THREAD_JOIN_TIMEOUT_MS)
                throw RuntimeException("$tool timed out after ${timeout.toMillis()} ms")
            }
            outputThread.join(OUTPUT_THREAD_JOIN_TIMEOUT_MS)

            val captured = synchronized(outputLines) { outputLines.toList() }
            val exitCode = process.exitValue()
            if (exitCode != 0) {
                val lastLines = captured.takeLast(ERROR_TAIL_LINES)
                val errorDetail = if (lastLines.isNotEmpty()) ": ${lastLines.joinToString("\n")}" else ""
                throw RuntimeException("$tool exited with code $exitCode$errorDetail")
            }
            captured
        }
    }

    companion object {
        private const val MAX_OUTPUT_LINES = 500
        private const val ERROR_TAIL_LINES = 20
        private const val OUTPUT_THREAD_JOIN_TIMEOUT_MS = 5000L
        private const val KILL_WAIT_TIMEOUT_MS = 5000L
    }
}
