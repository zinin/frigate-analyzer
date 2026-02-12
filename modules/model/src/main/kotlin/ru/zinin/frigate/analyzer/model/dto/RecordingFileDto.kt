package ru.zinin.frigate.analyzer.model.dto

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

data class RecordingFileDto(
    val basePath: String,
    val camId: String,
    val date: LocalDate,
    val time: LocalTime,
    val timestamp: Instant,
)
