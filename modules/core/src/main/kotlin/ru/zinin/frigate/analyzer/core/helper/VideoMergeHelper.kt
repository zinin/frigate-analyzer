package ru.zinin.frigate.analyzer.core.helper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.config.properties.ApplicationProperties
import ru.zinin.frigate.analyzer.core.video.CompressionPlan
import ru.zinin.frigate.analyzer.core.video.FfmpegProcessRunner
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration

@Component
class VideoMergeHelper(
    private val applicationProperties: ApplicationProperties,
    private val tempFileHelper: TempFileHelper,
    private val processRunner: FfmpegProcessRunner,
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
                runFfmpeg(buildMergeCommand(concatFile, outputFile))
                return outputFile
            } catch (e: Exception) {
                tempFileHelper.deleteIfExists(outputFile)
                throw e
            }
        } finally {
            tempFileHelper.deleteIfExists(concatFile)
        }
    }

    /**
     * Re-encodes [inputPath] with [plan]: libx264 at CRF quality capped by `-maxrate`/`-bufsize`,
     * optionally downscaled, AAC audio or none, `faststart` for streaming playback. The caller
     * owns both files; on failure the partial output is deleted under [NonCancellable] so that a
     * cancelled export does not leak it.
     */
    suspend fun compressVideo(
        inputPath: Path,
        plan: CompressionPlan,
    ): Path {
        val outputFile = tempFileHelper.createTempFile("compressed-", ".mp4")
        try {
            runFfmpeg(buildCompressCommand(inputPath, outputFile, plan))
            return outputFile
        } catch (e: Exception) {
            withContext(NonCancellable) { tempFileHelper.deleteIfExists(outputFile) }
            throw e
        }
    }

    internal fun buildCompressCommand(
        inputPath: Path,
        outputFile: Path,
        plan: CompressionPlan,
    ): List<String> =
        buildList {
            add(applicationProperties.ffmpegPath.toString())
            add("-hide_banner")
            add("-nostdin")
            add("-i")
            add(inputPath.toString())
            plan.scaleHeight?.let { height ->
                add("-vf")
                add("scale=-2:$height")
            }
            add("-c:v")
            add("libx264")
            add("-preset")
            add(plan.preset)
            add("-crf")
            add(plan.crf.toString())
            add("-maxrate")
            add("${plan.videoMaxrateKbps}k")
            add("-bufsize")
            add("${plan.videoMaxrateKbps * 2}k")
            add("-pix_fmt")
            add("yuv420p")
            val audioKbps = plan.audioBitrateKbps
            if (audioKbps != null) {
                add("-c:a")
                add("aac")
                add("-b:a")
                add("${audioKbps}k")
            } else {
                add("-an")
            }
            add("-movflags")
            add("+faststart")
            add("-y")
            add(outputFile.toString())
        }

    internal fun buildMergeCommand(
        concatFile: Path,
        outputFile: Path,
    ): List<String> =
        listOf(
            applicationProperties.ffmpegPath.toString(),
            "-hide_banner",
            "-nostdin",
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
        )

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
        processRunner.run(command, Duration.ofSeconds(FFMPEG_TIMEOUT_SECONDS))
    }

    private fun escapePath(path: Path): String = path.toAbsolutePath().toString().replace("'", "'\\''")

    companion object {
        const val FFMPEG_TIMEOUT_SECONDS = 300L
    }
}
