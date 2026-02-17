package ru.zinin.frigate.analyzer.core.helper

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.config.properties.ApplicationProperties
import java.nio.file.Files
import java.nio.file.Path
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

    companion object {
        private const val PREFIX = "frigate-analyzer-tmp-"
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")
    }
}
