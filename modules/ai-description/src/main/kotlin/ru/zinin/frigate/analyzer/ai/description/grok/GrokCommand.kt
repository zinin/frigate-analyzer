package ru.zinin.frigate.analyzer.ai.description.grok

import java.nio.file.Path

/** Готовый к запуску вызов `grok`: argv, переменные поверх окружения JVM и cwd процесса. */
data class GrokCommand(
    val argv: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: Path,
)
