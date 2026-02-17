package ru.zinin.frigate.analyzer.core.helper

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.config.properties.ApplicationProperties
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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

    suspend fun createTempFile(prefix: String, suffix: String): Path =
        withContext(Dispatchers.IO) {
            val fullPrefix = PREFIX + DATE_FORMAT.format(LocalDateTime.now(clock)) + "-" + prefix
            Files.createTempFile(tempDirPath, fullPrefix, suffix)
        }

    suspend fun createTempFile(prefix: String, suffix: String, content: ByteArray): Path {
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

    suspend fun createTempFile(prefix: String, suffix: String, content: Flow<ByteArray>): Path {
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

    fun readFile(path: Path, bufferSize: Int = BUFFER_SIZE): Flow<ByteArray> {
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

    private fun requirePathInTempDir(path: Path) {
        require(path.normalize().startsWith(tempDirPath)) {
            "Path '$path' is outside tempFolder '$tempDirPath'"
        }
    }

    companion object {
        private const val PREFIX = "frigate-analyzer-tmp-"
        private const val BUFFER_SIZE = 32 * 1024
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")
    }
}
