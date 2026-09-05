package ru.zinin.frigate.analyzer.core.bot.handler

data class VerdictsArguments(
    val camId: String?,
    val limit: Int,
) {
    companion object {
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 30

        /** `/verdicts [cam] [n]`; null = аргументы не разобрать. */
        fun parse(text: String): VerdictsArguments? {
            val tokens = text.trim().split(Regex("\\s+")).drop(1)
            return when (tokens.size) {
                0 -> {
                    VerdictsArguments(null, DEFAULT_LIMIT)
                }

                1 -> {
                    tokens[0].toIntOrNull()?.let { limit(it)?.let { n -> VerdictsArguments(null, n) } }
                        ?: VerdictsArguments(tokens[0], DEFAULT_LIMIT)
                }

                2 -> {
                    tokens[1].toIntOrNull()?.let(::limit)?.let { VerdictsArguments(tokens[0], it) }
                }

                else -> {
                    null
                }
            }
        }

        private fun limit(n: Int): Int? = n.takeIf { it in 1..MAX_LIMIT }
    }
}
