package ru.zinin.frigate.analyzer.core.video

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
 *
 * The wait is cooperative: the process is polled every [WAIT_POLL_MS] with `ensureActive()` in
 * between, so a cancelled export (the cancel button, the outer export timeout, application
 * shutdown) kills ffmpeg within that interval instead of waiting for it to finish on its own.
 */
@Component
class FfmpegProcessRunner {
    /**
     * @return captured output lines, stdout and stderr together, at most the last [MAX_OUTPUT_LINES].
     * @throws RuntimeException when the exit code is not zero (the message carries the last
     *   [ERROR_TAIL_LINES] lines of output) or when the process does not finish within [timeout]
     *   (the process is killed first).
     * @throws kotlinx.coroutines.CancellationException when the calling coroutine is cancelled; the
     *   process is killed before it propagates.
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

            try {
                val deadline = System.nanoTime() + timeout.toNanos()
                // One blocking waitFor(timeout) would not notice that the export was cancelled and
                // ffmpeg would keep encoding for nobody; poll instead and let cancellation through.
                while (!process.waitFor(WAIT_POLL_MS, TimeUnit.MILLISECONDS)) {
                    ensureActive()
                    if (System.nanoTime() >= deadline) {
                        throw RuntimeException("$tool timed out after ${timeout.toMillis()} ms")
                    }
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
            } finally {
                if (process.isAlive) {
                    // Timeout or cancellation: kill the process and reap it so it does not linger
                    // as a zombie until the JVM notices.
                    process.destroyForcibly()
                    process.waitFor(KILL_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    outputThread.join(OUTPUT_THREAD_JOIN_TIMEOUT_MS)
                }
            }
        }
    }

    companion object {
        private const val MAX_OUTPUT_LINES = 500
        private const val ERROR_TAIL_LINES = 20
        private const val OUTPUT_THREAD_JOIN_TIMEOUT_MS = 5000L
        private const val KILL_WAIT_TIMEOUT_MS = 5000L
        private const val WAIT_POLL_MS = 200L
    }
}
