package ru.zinin.frigate.analyzer.telegram.bot.handler.aisettings

import ru.zinin.frigate.analyzer.ai.description.api.UnavailableReason

/**
 * Причина отказа на клик по пресету — код, а не готовый текст: локализуется рендером тем же
 * единственным `when` по [UnavailableReason], которым нарисован экран. Свободная строка от фабрики
 * привела бы на экран владельца адрес эндпоинта или кусок ключа, а английская строка выпала бы из
 * локализованного экрана.
 */
sealed interface AiSettingsAlertCause {
    /** Пресет объявлен, но провайдер не настроен. */
    data class Unavailable(
        val reason: UnavailableReason,
    ) : AiSettingsAlertCause

    /**
     * Id с кнопки больше нет в каталоге: экран открыли до правки конфигурации. Отдельный вариант,
     * а не [Unavailable] с выдуманной причиной — сказать «нет токена» про пресет, которого нет,
     * значило бы соврать.
     */
    data object Gone : AiSettingsAlertCause
}
