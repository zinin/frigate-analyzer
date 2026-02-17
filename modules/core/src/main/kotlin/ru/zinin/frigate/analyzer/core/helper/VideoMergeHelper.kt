package ru.zinin.frigate.analyzer.core.helper

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.config.properties.ApplicationProperties
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

@Component
class VideoMergeHelper(
    private val applicationProperties: ApplicationProperties,
    private val tempFileHelper: TempFileHelper,
) {
    suspend fun mergeVideos(filePaths: List<Path>): Path {
        require(filePaths.isNotEmpty()) { "filePaths must not be empty" }

        if (filePaths.size == 1) {
            return copyToTemp(filePaths.first())
        }

        val concatFile = tempFileHelper.createTempFile("concat-", ".txt")
        try {
            withContext(Dispatchers.IO) {
                Files.write(
                    concatFile,
                    filePaths.map { "file '${escapePath(it)}'" },
                )
            }

            val outputFile = tempFileHelper.createTempFile("merged-", ".mp4")
            try {
                runFfmpeg(
                    listOf(
                        applicationProperties.ffmpegPath.toString(),
                        "-hide_banner",
                        "-f",
                        "concat",
                        "-safe",
                        "0",
                        "-i",
                        concatFile.toString(),
                        "-c",
                        "copy",
                        "-y",
                        outputFile.toString(),
                    ),
                )
                return outputFile
            } catch (e: Exception) {
                tempFileHelper.deleteIfExists(outputFile)
                throw e
            }
        } finally {
            tempFileHelper.deleteIfExists(concatFile)
        }
    }

    suspend fun compressVideo(inputPath: Path): Path {
        val outputFile = tempFileHelper.createTempFile("compressed-", ".mp4")
        try {
            runFfmpeg(
                listOf(
                    applicationProperties.ffmpegPath.toString(),
                    "-hide_banner",
                    "-i",
                    inputPath.toString(),
                    "-vcodec",
                    "libx264",
                    "-crf",
                    "28",
                    "-preset",
                    "fast",
                    "-acodec",
                    "aac",
                    "-y",
                    outputFile.toString(),
                ),
            )
            return outputFile
        } catch (e: Exception) {
            tempFileHelper.deleteIfExists(outputFile)
            throw e
        }
    }

    private suspend fun copyToTemp(source: Path): Path {
        val outputFile = tempFileHelper.createTempFile("merged-", ".mp4")
        try {
            withContext(Dispatchers.IO) {
                Files.copy(source, outputFile, StandardCopyOption.REPLACE_EXISTING)
            }
            return outputFile
        } catch (e: Exception) {
            tempFileHelper.deleteIfExists(outputFile)
            throw e
        }
    }

    private suspend fun runFfmpeg(command: List<String>) {
        logger.debug { "Running ffmpeg: ${command.joinToString(" ")}" }

        withContext(Dispatchers.IO) {
            val process =
                ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()

            // Drain stdout in a separate thread so waitFor timeout works correctly
            val outputLines = mutableListOf<String>()
            val outputThread =
                kotlin.concurrent.thread(isDaemon = true) {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            logger.trace { "ffmpeg: $line" }
                            synchronized(outputLines) {
                                if (outputLines.size < MAX_OUTPUT_LINES) {
                                    outputLines.add(line)
                                }
                            }
                        }
                    }
                }

            val completed = process.waitFor(FFMPEG_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                outputThread.join(OUTPUT_THREAD_JOIN_TIMEOUT_MS)
                throw RuntimeException("ffmpeg timed out after ${FFMPEG_TIMEOUT_SECONDS}s")
            }
            outputThread.join(OUTPUT_THREAD_JOIN_TIMEOUT_MS)

            val exitCode = process.exitValue()
            if (exitCode != 0) {
                val lastLines = synchronized(outputLines) { outputLines.takeLast(ERROR_TAIL_LINES) }
                val errorDetail = if (lastLines.isNotEmpty()) ": ${lastLines.joinToString("\n")}" else ""
                throw RuntimeException("ffmpeg exited with code $exitCode$errorDetail")
            }
        }
    }

    private fun escapePath(path: Path): String = path.toAbsolutePath().toString().replace("'", "'\\''")

    companion object {
        const val MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024
        const val COMPRESS_THRESHOLD_BYTES = 45L * 1024 * 1024
        const val FFMPEG_TIMEOUT_SECONDS = 300L
        private const val MAX_OUTPUT_LINES = 500
        private const val ERROR_TAIL_LINES = 20
        private const val OUTPUT_THREAD_JOIN_TIMEOUT_MS = 5000L
    }
}
