package ru.zinin.frigate.analyzer.telegram.bot.handler.aisettings

import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.utils.matrix
import dev.inmo.tgbotapi.utils.row
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionPreset
import ru.zinin.frigate.analyzer.ai.description.api.ProviderAuthStates
import ru.zinin.frigate.analyzer.ai.description.api.UnavailableReason
import ru.zinin.frigate.analyzer.telegram.dto.AiSettingsViewState
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver

data class RenderedAiSettings(
    val text: String,
    val keyboard: InlineKeyboardMarkup,
)

@Component
class AiSettingsMessageRenderer(
    private val msg: MessageResolver,
) {
    fun render(state: AiSettingsViewState): RenderedAiSettings = RenderedAiSettings(renderText(state), renderKeyboard(state))

    /**
     * Текст модалки на отказанный клик. Живёт здесь, а не в диспетчере коллбэков, ровно потому,
     * что причина обязана пройти через тот же единственный `when` по [UnavailableReason], что и
     * экран: второй такой `when` где-то ещё молча пропустил бы новый вариант причины.
     *
     * [cause] == null у ключей без аргументов (клик не-владельца).
     */
    fun alertText(
        key: String,
        cause: AiSettingsAlertCause?,
        lang: String,
    ): String =
        when (cause) {
            null -> msg.get(key, lang)
            is AiSettingsAlertCause.Unavailable -> msg.get(key, lang, reasonText(cause.reason, lang))
            AiSettingsAlertCause.Gone -> msg.get(key, lang, msg.get("ai.settings.reason.gone", lang))
        }.take(CALLBACK_ALERT_MAX_CHARS)

    private fun renderText(state: AiSettingsViewState): String {
        val lang = state.language
        val active = state.presets.firstOrNull { it.id == state.effectivePresetId }
        return buildString {
            appendLine(msg.get("ai.settings.title", lang))
            appendLine(
                msg.get(
                    "ai.settings.state",
                    lang,
                    msg.get(if (state.descriptionsEnabled) "ai.settings.state.on" else "ai.settings.state.off", lang),
                ),
            )
            // Ранний выход ТОЛЬКО при пустом каталоге. На `active == null` при непустом списке он
            // унёс бы с собой весь блок авторизации, то есть ровно ту диагностику, ради которой
            // экран и открывают.
            if (state.presets.isEmpty()) {
                appendLine(msg.get("ai.settings.active.none", lang))
                return@buildString
            }
            if (active == null) {
                appendLine(msg.get("ai.settings.active.none", lang))
            } else {
                // Модель именно эффективная: `ANTHROPIC_MODEL` вытесняет объявленную, и печатать
                // объявленную значило бы рисовать запрос, которого не будет.
                appendLine(
                    msg.get("ai.settings.active", lang, active.id, active.provider, active.effectiveModel, effortLabel(active)),
                )
            }
            if (state.hasMismatch) {
                appendLine(mismatchLine(state, lang))
            }
            appendLine()
            state.presets.map { it.authScopeId }.distinct().forEach { scope ->
                appendLine(scopeLine(state, scope, lang))
            }
            // Состояние меняется только на вызове описания: после `grok login` здесь будет 🔴
            // до следующей записи с детекциями, после рестарта — ⚪ при протухшем auth.json.
            // Оговорка обязательна, иначе экран читается как проверка «сейчас».
            appendLine(msg.get("ai.settings.auth.note", lang))
            if (state.presets.any { it.slowEffort }) {
                appendLine(msg.get("ai.settings.slow.note", lang, SLOW_MARK))
            }
        }
    }

    /**
     * Причина расхождения. Пустая причина у сохранённого пресета означает разное, и один текст на
     * оба случая соврал бы: id, исчезнувшего из конфига, в каталоге просто нет — это `.gone`;
     * годный же сохранённый пресет расходится с эффективным только если чтение настроек не удалось
     * между двумя запросами, и назвать это «пресет удалён» нельзя.
     *
     * Следствие подбирается тем же `when`, что и причина, и уходит отдельным аргументом, а не
     * фиксированным хвостом строки: хвост «применится снова, когда пресет станет доступен» верен
     * для непригодного пресета, но при `.unknown` сохранённый пресет как раз годен, и хвост врал бы
     * на единственном экране, ради диагностики и открываемом. В сам ключ `reason.*` следствие не
     * вносится: те же ключи читает строка авторизации и модалка отказа, где о выборе речи нет.
     */
    private fun mismatchLine(
        state: AiSettingsViewState,
        lang: String,
    ): String {
        val stored = state.presets.firstOrNull { it.id == state.storedPresetId }
        val cause = stored?.unavailableReason
        val (reason, consequenceKey) =
            when {
                cause != null -> reasonText(cause, lang) to KEY_MISMATCH_KEPT
                stored == null -> msg.get("ai.settings.reason.gone", lang) to KEY_MISMATCH_KEPT
                else -> msg.get("ai.settings.reason.unknown", lang) to KEY_MISMATCH_RECHECK
            }
        return msg.get(
            "ai.settings.active.mismatch",
            lang,
            state.storedPresetId.orEmpty(),
            reason,
            state.effectivePresetId.orEmpty(),
            msg.get(consequenceKey, lang),
        )
    }

    /**
     * Область учётных данных, у которой ни один пресет не годен, описывается причиной из
     * конфигурации, а не состоянием авторизации: её никто и не вызывал.
     */
    private fun scopeLine(
        state: AiSettingsViewState,
        scope: String,
        lang: String,
    ): String {
        val presets = state.presets.filter { it.authScopeId == scope }
        val unavailableReason = presets.firstOrNull()?.unavailableReason
        if (presets.none { it.available } && unavailableReason != null) {
            return msg.get("ai.settings.auth.unavailable", lang, scope, reasonText(unavailableReason, lang))
        }
        return when (state.authByScope[scope] ?: ProviderAuthStates.Health.UNKNOWN) {
            ProviderAuthStates.Health.HEALTHY -> msg.get("ai.settings.auth.healthy", lang, scope)
            ProviderAuthStates.Health.LOST -> msg.get("ai.settings.auth.lost", lang, scope)
            ProviderAuthStates.Health.UNKNOWN -> msg.get("ai.settings.auth.unknown", lang, scope)
        }
    }

    /**
     * Причина — код, а не текст от фабрики: свободная строка провайдера открыла бы дорогу адресу
     * эндпоинта или куску ключа прямо на экран владельца, а английская строка выпала бы из
     * локализованного экрана. `when` без `else` намеренно: новый вариант обязан упасть здесь на
     * компиляции, а не тихо остаться без перевода.
     */
    private fun reasonText(
        reason: UnavailableReason,
        lang: String,
    ): String =
        when (reason) {
            is UnavailableReason.NoToken -> msg.get("ai.settings.reason.noToken", lang)

            // Формулировка ключа не называет каталог домашним: тем же вариантом сообщается и о
            // непригодном рабочем каталоге.
            is UnavailableReason.HomeUnwritable -> msg.get("ai.settings.reason.homeUnwritable", lang, reason.path)

            is UnavailableReason.NoFactory -> msg.get("ai.settings.reason.noFactory", lang, reason.provider)
        }

    private fun effortLabel(preset: DescriptionPreset): String = preset.effort.ifBlank { NO_EFFORT }

    private fun renderKeyboard(state: AiSettingsViewState): InlineKeyboardMarkup {
        val lang = state.language
        return InlineKeyboardMarkup(
            keyboard =
                matrix {
                    state.presets.forEach { preset ->
                        row {
                            +CallbackDataInlineKeyboardButton(
                                presetLabel(preset, state.effectivePresetId),
                                AiSettingsCallbacks.SET_PREFIX + preset.id,
                            )
                        }
                    }
                    if (state.presets.isNotEmpty()) {
                        row {
                            +CallbackDataInlineKeyboardButton(
                                msg.get(
                                    if (state.descriptionsEnabled) {
                                        "ai.settings.button.disable"
                                    } else {
                                        "ai.settings.button.enable"
                                    },
                                    lang,
                                ),
                                if (state.descriptionsEnabled) AiSettingsCallbacks.OFF else AiSettingsCallbacks.ON,
                            )
                        }
                    }
                    row {
                        +CallbackDataInlineKeyboardButton(
                            msg.get("ai.settings.button.close", lang),
                            AiSettingsCallbacks.CLOSE,
                        )
                    }
                },
        )
    }

    /** ✅ у эффективного, а не у сохранённого: отметка говорит, что работает, а не что записано. */
    private fun presetLabel(
        preset: DescriptionPreset,
        effectiveId: String?,
    ): String {
        val label =
            when {
                !preset.available -> "$UNAVAILABLE_MARK ${preset.id}"
                preset.id == effectiveId -> "$ACTIVE_MARK ${preset.id}"
                else -> preset.id
            }
        return if (preset.slowEffort) "$label $SLOW_MARK" else label
    }

    private companion object {
        /** Пресет недоступен (или не объявлен) — выбор лежит и ждёт. */
        const val KEY_MISMATCH_KEPT = "ai.settings.mismatch.kept"

        /** Пресет цел и годен: расхождение — след несогласованного чтения, а не состояние выбора. */
        const val KEY_MISMATCH_RECHECK = "ai.settings.mismatch.recheck"

        const val ACTIVE_MARK = "✅"
        const val UNAVAILABLE_MARK = "⚠️"

        /** Отметка живёт в коде, а не в бандлах: иначе кнопка и легенда разъезжаются по языкам. */
        const val SLOW_MARK = "🐢"

        const val NO_EFFORT = "—"

        /** Потолок `answerCallbackQuery.text` в Bot API. */
        const val CALLBACK_ALERT_MAX_CHARS = 200
    }
}
