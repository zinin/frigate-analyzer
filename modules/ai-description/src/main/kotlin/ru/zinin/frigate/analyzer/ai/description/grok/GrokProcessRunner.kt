package ru.zinin.frigate.analyzer.ai.description.grok

/** Результат одного запуска `grok`: код выхода, весь stdout и хвост stderr. */
data class GrokProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderrTail: String,
)

/**
 * Шов над запуском процесса: в проде [DefaultGrokProcessRunner], в тестах фейк с готовыми
 * результатами.
 */
fun interface GrokProcessRunner {
    suspend fun run(command: GrokCommand): GrokProcessResult
}
