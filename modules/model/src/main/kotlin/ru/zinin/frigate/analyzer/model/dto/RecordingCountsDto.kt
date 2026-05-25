package ru.zinin.frigate.analyzer.model.dto

import org.springframework.data.relational.core.mapping.Column

data class RecordingCountsDto(
    @Column("total")
    val total: Long,
    @Column("processed")
    val processed: Long,
    @Column("unprocessed")
    val unprocessed: Long,
    @Column("success")
    val success: Long,
    @Column("errors")
    val errors: Long,
)
