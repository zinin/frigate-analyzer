package ru.zinin.frigate.analyzer.service.helper

import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.model.dto.RecordingFileDto
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

@Component
class RecordingFileHelper {
    fun parse(path: Path): RecordingFileDto {
        require(path.nameCount >= 6) { "Invalid recording path: $path" }

        // .../recordings/2025-12-28/09/cam1/01.25.mp4
        val fileName = path.fileName.toString() // 01.25.mp4
        val camId = path.parent.fileName.toString() // cam1
        val hour =
            path.parent.parent.fileName
                .toString()
                .toInt() // 09
        val date =
            LocalDate.parse(
                path.parent.parent.parent.fileName
                    .toString(), // 2025-12-28
            )

        val basePath =
            path.parent.parent.parent.parent
                .toString() // /mnt/data/frigate/recordings

        val match =
            Regex("""(\d{2})\.(\d{2})\.mp4""")
                .matchEntire(fileName)
                ?: error("Invalid recording file name: $fileName")

        val minute = match.groupValues[1].toInt()
        val second = match.groupValues[2].toInt()

        val time = LocalTime.of(hour, minute, second)

        val recordTimestamp =
            LocalDateTime
                .of(date, time)
                .toInstant(ZoneOffset.UTC)

        return RecordingFileDto(
            basePath = basePath,
            camId = camId,
            date = date,
            time = time,
            timestamp = recordTimestamp,
        )
    }
}
