package ru.zinin.frigate.analyzer.ai.description.api

/**
 * Переход состояния авторизации провайдера описаний. Публикуется ядром модуля ровно один раз на
 * переход (первый [DescriptionException.Unauthorized] после успеха или старта даёт LOST, первый успех
 * после LOST даёт RESTORED). Слушает core-модуль и шлёт владельцу сообщение в Telegram.
 */
data class DescriptionProviderAuthEvent(
    /**
     * Область учётных данных, а не провайдер: `claude`, `grok:grok-4.6`, `grok:codex-luna`. Переход
     * принадлежит набору ключей — успех BYOK-модели ничего не говорит о протухшей сессии OAuth.
     */
    val authScopeId: String,
    val state: State,
    /** Техническое сообщение провайдера; только для LOST. */
    val detail: String?,
    /** Команда, которой владелец чинит авторизацию. */
    val recoveryHint: String,
) {
    enum class State { LOST, RESTORED }
}
