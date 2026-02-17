package ru.zinin.frigate.analyzer.core.helper

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.config.properties.ApplicationProperties
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

private val logger = KotlinLogging.logger {}

@Component
class TempFileHelper(
    private val applicationProperties: ApplicationProperties,
    private val clock: Clock,
) {
    private lateinit var tempDirPath: Path

    @PostConstruct
    fun init() {
        tempDirPath = applicationProperties.tempFolder
        if (Files.exists(tempDirPath)) {
            check(Files.isDirectory(tempDirPath)) { "tempFolder='$tempDirPath' is not a directory" }
            logger.debug { "tempFolder='$tempDirPath' exists" }
        } else {
            Files.createDirectories(tempDirPath)
            logger.debug { "tempFolder='$tempDirPath' created" }
        }
    }

    suspend fun createTempFile(
        prefix: String,
        suffix: String,
    ): Path =
        withContext(Dispatchers.IO) {
            val fullPrefix = PREFIX + DATE_FORMAT.format(LocalDateTime.now(clock)) + "-" + prefix
            Files.createTempFile(tempDirPath, fullPrefix, suffix)
        }

    suspend fun createTempFile(
        prefix: String,
        suffix: String,
        content: ByteArray,
    ): Path {
        val path = createTempFile(prefix, suffix)
        try {
            withContext(Dispatchers.IO) {
                Files.write(path, content)
            }
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { Files.deleteIfExists(path) }
            throw e
        }
        return path
    }

    suspend fun createTempFile(
        prefix: String,
        suffix: String,
        content: Flow<ByteArray>,
    ): Path {
        val path = createTempFile(prefix, suffix)
        try {
            withContext(Dispatchers.IO) {
                path.outputStream().buffered().use { out ->
                    content.collect { chunk -> out.write(chunk) }
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { Files.deleteIfExists(path) }
            throw e
        }
        return path
    }

    fun readFile(
        path: Path,
        bufferSize: Int = BUFFER_SIZE,
    ): Flow<ByteArray> {
        require(bufferSize > 0) { "bufferSize must be positive" }
        requirePathInTempDir(path)
        return flow {
            path.inputStream().buffered().use { stream ->
                val buffer = ByteArray(bufferSize)
                while (true) {
                    val bytesRead = stream.read(buffer)
                    if (bytesRead == -1) break
                    emit(buffer.copyOf(bytesRead))
                }
            }
        }.flowOn(Dispatchers.IO)
    }

    suspend fun deleteIfExists(path: Path): Boolean {
        requirePathInTempDir(path)
        return withContext(Dispatchers.IO) {
            if (Files.isDirectory(path)) {
                logger.warn { "Refusing to delete directory: $path" }
                return@withContext false
            }
            Files.deleteIfExists(path)
        }
    }

    suspend fun deleteFiles(files: List<Path>): Int =
        withContext(Dispatchers.IO) {
            var count = 0
            for (file in files) {
                try {
                    requirePathInTempDir(file)
                    if (Files.isDirectory(file)) {
                        logger.warn { "Skipping directory: $file" }
                        continue
                    }
                    if (Files.deleteIfExists(file)) {
                        count++
                    }
                } catch (e: IOException) {
                    logger.error(e) { "Error deleting file: $file" }
                }
            }
            count
        }

    suspend fun findOldFiles(): List<Path> =
        withContext(Dispatchers.IO) {
            Files.list(tempDirPath).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) }
                    .filter { it.fileName.toString().startsWith(PREFIX) }
                    .filter { path ->
                        val filename = path.fileName.toString()
                        val matcher = PATTERN.matcher(filename)
                        if (matcher.matches()) {
                            try {
                                val timestamp = LocalDateTime.parse(matcher.group(1), DATE_FORMAT)
                                timestamp.isBefore(LocalDateTime.now(clock).minusDays(MAX_AGE_DAYS))
                            } catch (e: java.time.format.DateTimeParseException) {
                                logger.warn(e) { "Cannot parse timestamp from filename: $filename" }
                                false
                            }
                        } else {
                            false
                        }
                    }.toList()
            }
        }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1H")
    fun cleanOldFiles() {
        runBlocking(Dispatchers.IO) {
            logger.debug { "Cleaning old temp files..." }
            val oldFiles = findOldFiles()
            val count = deleteFiles(oldFiles)
            logger.debug { "Cleaned $count old temp files" }
        }
    }

    private fun requirePathInTempDir(path: Path) {
        require(path.normalize().startsWith(tempDirPath)) {
            "Path '$path' is outside tempFolder '$tempDirPath'"
        }
    }

    companion object {
        private const val PREFIX = "frigate-analyzer-tmp-"
        private const val BUFFER_SIZE = 32 * 1024
        private const val MAX_AGE_DAYS = 7L
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")
        private val PATTERN = Pattern.compile("""frigate-analyzer-tmp-(\d{4}-\d{2}-\d{2}-\d{2}-\d{2}-\d{2})-.+""")
    }
}
