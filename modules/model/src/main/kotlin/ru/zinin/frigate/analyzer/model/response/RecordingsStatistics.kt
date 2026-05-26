package ru.zinin.frigate.analyzer.model.response

data class RecordingsStatistics(
    val total: Long,
    /**
     * Кол-во recordings с непустым `process_timestamp` (успешные + ошибочные).
     * Для error-visibility предпочитайте новые поля `success` / `errors`.
     */
    val processed: Long,
    val unprocessed: Long,
    val success: Long,
    /**
     * Кол-во recordings с непустым `error_message` (включая возможные retry-pending
     * без `process_timestamp`). Поэтому `success + errors == processed` НЕ инвариант
     * в общем случае.
     */
    val errors: Long,
    val byCameras: List<CameraStatistics>,
    val processingRatePerMinute: Double,
)

data class CameraStatistics(
    val camId: String,
    val recordingsCount: Long,
    val recordingsProcessed: Long,
    val detectionsCount: Long,
)
