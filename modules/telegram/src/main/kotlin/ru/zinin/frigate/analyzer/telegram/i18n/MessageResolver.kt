package ru.zinin.frigate.analyzer.telegram.i18n

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.MessageSource
import org.springframework.context.NoSuchMessageException
import org.springframework.stereotype.Component
import java.util.Locale

private val logger = KotlinLogging.logger {}

@Component
class MessageResolver(private val messageSource: MessageSource) {

    fun get(key: String, language: String, vararg args: Any): String =
        get(key, Locale.forLanguageTag(language), *args)

    fun get(key: String, locale: Locale, vararg args: Any): String =
        try {
            messageSource.getMessage(key, args, locale)
        } catch (e: NoSuchMessageException) {
            logger.warn { "Missing translation key='$key' for locale='$locale'" }
            try {
                messageSource.getMessage(key, args, DEFAULT_LOCALE)
            } catch (e: NoSuchMessageException) {
                key
            }
        }

    companion object {
        private val DEFAULT_LOCALE = Locale.forLanguageTag("ru")
    }
}
