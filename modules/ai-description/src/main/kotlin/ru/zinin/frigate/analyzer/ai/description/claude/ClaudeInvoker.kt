package ru.zinin.frigate.analyzer.ai.description.claude

import java.time.Duration

/**
 * Seam over the SDK call — implemented in production by `DefaultClaudeInvoker`, replaced in tests
 * with a fake that returns canned responses or throws specific exceptions.
 */
fun interface ClaudeInvoker {
    suspend fun invoke(
        prompt: String,
        model: String,
        systemPrompt: String,
        /** Бюджет вызова, отпущенный задачей: от него, а не от настроек описаний, считается таймаут SDK. */
        timeout: Duration,
    ): String
}
