package ru.zinin.frigate.analyzer.ai.description.core

/** Имя языка для промпта. `@Pattern` на `common.language` уже отсеивает всё, кроме ru и en. */
object LanguageNames {
    fun of(code: String): String =
        when (code.lowercase()) {
            "ru" -> "Russian"
            "en" -> "English"
            else -> error("Unsupported language code: '$code' (expected 'ru' or 'en')")
        }
}
