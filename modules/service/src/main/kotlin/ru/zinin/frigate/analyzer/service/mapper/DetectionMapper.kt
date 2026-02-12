package ru.zinin.frigate.analyzer.service.mapper

import org.mapstruct.Mapper
import org.mapstruct.Mapping
import ru.zinin.frigate.analyzer.model.dto.DetectionDto
import ru.zinin.frigate.analyzer.model.persistent.DetectionEntity
import ru.zinin.frigate.analyzer.model.request.CreateDetectionRequest

@Mapper
interface DetectionMapper {
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "creationTimestamp", ignore = true)
    fun toEntity(request: CreateDetectionRequest): DetectionEntity

    fun toDto(entity: DetectionEntity): DetectionDto
}
