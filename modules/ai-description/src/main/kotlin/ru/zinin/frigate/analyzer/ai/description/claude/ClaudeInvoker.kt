package ru.zinin.frigate.analyzer.ai.description.claude

/**
 * Seam over the SDK call — implemented in production by `DefaultClaudeInvoker`, replaced in tests
 * with a fake that returns canned responses or throws specific exceptions.
 */
fun interface ClaudeInvoker {
    suspend fun invoke(prompt: String): String
}
