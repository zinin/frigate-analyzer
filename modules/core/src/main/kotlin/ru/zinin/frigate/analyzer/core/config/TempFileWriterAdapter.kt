package ru.zinin.frigate.analyzer.core.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.zinin.frigate.analyzer.ai.description.api.TempFileWriter
import ru.zinin.frigate.analyzer.core.helper.TempFileHelper
import java.nio.file.Path

@Configuration
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class TempFileWriterAdapter {
    @Bean
    fun tempFileWriter(tempFileHelper: TempFileHelper): TempFileWriter =
        object : TempFileWriter {
            override suspend fun createTempFile(
                prefix: String,
                suffix: String,
                content: ByteArray,
            ): Path = tempFileHelper.createTempFile(prefix, suffix, content)

            override suspend fun deleteFiles(files: List<Path>): Int = tempFileHelper.deleteFiles(files)
        }
}
