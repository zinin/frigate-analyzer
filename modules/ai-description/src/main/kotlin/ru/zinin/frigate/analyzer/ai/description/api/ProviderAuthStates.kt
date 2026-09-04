package ru.zinin.frigate.analyzer.ai.description.api

/** Состояние авторизации на чтение: диалог `/ai` рисует его иконкой. */
interface ProviderAuthStates {
    enum class Health { UNKNOWN, HEALTHY, LOST }

    /**
     * Ключ — `authScopeId`, область учётных данных, а не провайдер: `claude`, `grok:grok-4.6`,
     * `grok:codex-luna`. Авторизация принадлежит набору ключей: два grok-пресета на одной модели
     * живут в общем `auth.json`, а BYOK-модель ходит по собственному ключу из `config.toml`, и её
     * успех ничего не говорит о сессии xAI.
     *
     * Только области, которых уже вызывали; порядок не определён.
     */
    fun byScope(): Map<String, Health>
}
