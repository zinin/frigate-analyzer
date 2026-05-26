package ru.zinin.frigate.analyzer.model.dto

/**
 * SQL projection одного `COUNT(*) FILTER (WHERE …)` агрегата по таблице `recordings`.
 * Инвариант: `total = processed + unprocessed`.
 * НЕ инвариант: `success + errors == processed` — строка с `error_message IS NOT NULL
 * AND process_timestamp IS NULL` попадает одновременно в `errors` и `unprocessed`.
 */
data class RecordingCountsDto(
    val total: Long,
    val processed: Long,
    val unprocessed: Long,
    val success: Long,
    val errors: Long,
)
