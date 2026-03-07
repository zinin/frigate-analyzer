package ru.zinin.frigate.analyzer.service.mapper

import org.mapstruct.Mapper
import org.mapstruct.Mapping
import ru.zinin.frigate.analyzer.model.dto.RecordingDto
import ru.zinin.frigate.analyzer.model.persistent.RecordingEntity
import ru.zinin.frigate.analyzer.model.request.CreateRecordingRequest

@Mapper
interface RecordingMapper {
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "creationTimestamp", ignore = true)
    @Mapping(target = "startProcessingTimestamp", ignore = true)
    @Mapping(target = "processTimestamp", ignore = true)
    @Mapping(target = "processAttempts", constant = "0")
    @Mapping(target = "detectionsCount", constant = "0")
    @Mapping(target = "analyzeTime", constant = "0")
    @Mapping(target = "analyzedFramesCount", constant = "0")
    @Mapping(target = "errorMessage", ignore = true)
    fun toEntity(request: CreateRecordingRequest): RecordingEntity

    fun toDto(entity: RecordingEntity): RecordingDto
}
