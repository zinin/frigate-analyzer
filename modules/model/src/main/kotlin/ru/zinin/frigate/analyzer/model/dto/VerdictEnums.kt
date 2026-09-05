package ru.zinin.frigate.analyzer.model.dto

enum class VerdictStage { JUDGE, SNOOZE, FAILOVER, BYPASS }

enum class VerdictDecision { PUBLISH, SUPPRESS }

enum class VerdictReason {
    NEW_EVENT,
    CHANGED_SITUATION,
    FALSE_POSITIVE,
    STATIC_OBJECT,
    DUPLICATE,
    SNOOZED,
    JUDGE_OFF,
    TIMEOUT,
    RATE_LIMITED,
    UNAUTHORIZED,
    INVALID_RESPONSE,
    TRANSPORT,
    CONTEXT_ERROR,
}
