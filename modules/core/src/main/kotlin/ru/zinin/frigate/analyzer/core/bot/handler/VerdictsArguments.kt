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
                    val token = tokens[0]
                    val asInt = token.toIntOrNull()
                    when {
                        asInt == null -> VerdictsArguments(token, DEFAULT_LIMIT)
                        else -> limit(asInt)?.let { VerdictsArguments(null, it) }
                    }
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
