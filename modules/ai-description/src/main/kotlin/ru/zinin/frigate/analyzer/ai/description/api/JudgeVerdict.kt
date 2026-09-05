package ru.zinin.frigate.analyzer.ai.description.api

data class JudgeVerdict(
    val decision: Decision,
    val reason: Reason,
    /** null = модель не дала число в [0, 1]. Только сохраняется. */
    val confidence: Double?,
    val summary: String,
    /** Уже обрезано парсером до потолка запроса; 0 = не усыплять. */
    val snoozeMinutes: Int,
    val wanted: String,
) {
    enum class Decision { PUBLISH, SUPPRESS }

    enum class Reason(
        val publishes: Boolean,
    ) {
        NEW_EVENT(true),
        CHANGED_SITUATION(true),
        FALSE_POSITIVE(false),
        STATIC_OBJECT(false),
        DUPLICATE(false),
    }
}
