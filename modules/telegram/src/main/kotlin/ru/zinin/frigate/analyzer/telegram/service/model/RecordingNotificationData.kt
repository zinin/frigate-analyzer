package ru.zinin.frigate.analyzer.telegram.service.model

/**
 * Метаданные обработанной записи для уведомления. Даты приходят уже отформатированными
 * в зоне и локали получателя — форматирование остаётся в `TelegramNotificationServiceImpl`,
 * который единственный знает про `UserZone`.
 */
data class RecordingNotificationData(
    val camId: String,
    val fileName: String,
    val detectionsCount: Int,
    val analyzedFramesCount: Int,
    val analyzeTimeSeconds: Int,
    val recordTimestamp: String,
    /** "N/A", когда запись ещё не обработана — как и в прежнем текстовом формате, без локализации. */
    val processTimestamp: String,
)
