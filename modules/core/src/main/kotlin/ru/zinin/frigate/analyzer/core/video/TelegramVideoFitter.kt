package ru.zinin.frigate.analyzer.core.video

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.helper.TempFileHelper
import ru.zinin.frigate.analyzer.core.helper.VideoMergeHelper
import ru.zinin.frigate.analyzer.model.exception.VideoTooLargeException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

private val logger = KotlinLogging.logger {}

/**
 * Fits an export file into the Telegram upload limit.
 *
 * Files up to [FitLimits.thresholdBytes] are returned untouched. Larger files are probed, planned
 * with the threshold as the byte budget, re-encoded and checked against [FitLimits.maxBytes]; one
 * overshoot gets a second, smaller encode of the first result; a second overshoot is
 * [VideoTooLargeException].
 *
 * File ownership: on success the input is deleted when a new file was produced; on failure or
 * cancellation only the files this class created are deleted and the input is left to the caller.
 */
@Component
class TelegramVideoFitter internal constructor(
    private val probe: VideoProbe,
    private val planner: CompressionPlanner,
    private val mergeHelper: VideoMergeHelper,
    private val tempFileHelper: TempFileHelper,
    private val limits: FitLimits,
) {
    @Autowired
    constructor(
        probe: VideoProbe,
        planner: CompressionPlanner,
        mergeHelper: VideoMergeHelper,
        tempFileHelper: TempFileHelper,
    ) : this(probe, planner, mergeHelper, tempFileHelper, FitLimits.TELEGRAM)

    /**
     * @param onCompressStart called once, before probing, when [input] is above the threshold.
     * @return [input] itself, or a new temp file that fits.
     */
    suspend fun fit(
        input: Path,
        onCompressStart: suspend () -> Unit = {},
    ): Path {
        val inputSize = fileSize(input)
        if (inputSize <= limits.thresholdBytes) {
            logger.debug { "No compression needed for $input (${mib(inputSize)})" }
            return input
        }

        onCompressStart()
        val info = probe.probe(input)
        val plan = planner.plan(info, limits.thresholdBytes)
        logger.info {
            "Compressing $input: ${mib(inputSize)}, ${info.durationSeconds}s, " +
                "${info.width}x${info.height}@${info.fps}fps, audio=${info.hasAudio} -> " +
                describe(plan, sourceHeight = info.height)
        }

        val created = mutableListOf<Path>()
        try {
            var result = encode(input, plan, attempt = 1, created)
            var resultSize = fileSize(result)
            if (resultSize > limits.maxBytes) {
                val retryPlan = planner.shrink(plan, info, resultSize, limits.thresholdBytes)
                logger.warn {
                    "Compressed file is ${mib(resultSize)}, above the ${mib(limits.maxBytes)} limit; retrying with " +
                        describe(retryPlan, sourceHeight = plan.scaleHeight ?: info.height)
                }
                result = encode(result, retryPlan, attempt = 2, created)
                resultSize = fileSize(result)
            }
            if (resultSize > limits.maxBytes) {
                throw VideoTooLargeException(
                    "Video is ${mib(resultSize)} after two compression attempts, limit is ${mib(limits.maxBytes)}",
                )
            }
            created.remove(result)
            // Path is Iterable<Path> (name elements), so plus(input) would flatten the path.
            // NonCancellable like the failure path below: deleteIfExists is suspend, and a
            // cancellation arriving here would otherwise skip the deletes and leak the files.
            withContext(NonCancellable) { deleteAll(created.plusElement(input)) }
            return result
        } catch (e: Exception) {
            withContext(NonCancellable) { deleteAll(created) }
            throw e
        }
    }

    private suspend fun encode(
        source: Path,
        plan: CompressionPlan,
        attempt: Int,
        created: MutableList<Path>,
    ): Path {
        val output = mergeHelper.compressVideo(source, plan)
        created.add(output)
        val outputSize = fileSize(output)
        logger.info { "Compression attempt $attempt: ${mib(outputSize)} -> $output" }
        return output
    }

    /** Both callers run this under [NonCancellable], so the catch below never swallows a cancellation. */
    private suspend fun deleteAll(paths: List<Path>) {
        for (path in paths) {
            try {
                tempFileHelper.deleteIfExists(path)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to delete temp file: $path" }
            }
        }
    }

    private suspend fun fileSize(path: Path): Long = withContext(Dispatchers.IO) { Files.size(path) }

    private fun describe(
        plan: CompressionPlan,
        sourceHeight: Int,
    ): String =
        "height=${plan.scaleHeight ?: sourceHeight}, maxrate=${plan.videoMaxrateKbps}k, " +
            "audio=${plan.audioBitrateKbps?.let { "${it}k" } ?: "none"}, crf=${plan.crf}, preset=${plan.preset}"

    private fun mib(bytes: Long): String = "%.1f MiB".format(Locale.ROOT, bytes / MIB)

    companion object {
        private const val MIB = 1024.0 * 1024.0
    }
}
