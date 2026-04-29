package ru.zinin.frigate.analyzer.telegram.bot.handler.notifications

import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.utils.matrix
import dev.inmo.tgbotapi.utils.row
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.dto.NotificationsViewState
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver

data class RenderedNotifications(
    val text: String,
    val keyboard: InlineKeyboardMarkup,
)

@Component
class NotificationsMessageRenderer(
    private val msg: MessageResolver,
) {
    fun render(state: NotificationsViewState): RenderedNotifications {
        if (state.isOwner) {
            requireNotNull(state.recordingGlobalEnabled) {
                "OWNER NotificationsViewState.recordingGlobalEnabled must not be null"
            }
            requireNotNull(state.signalGlobalEnabled) {
                "OWNER NotificationsViewState.signalGlobalEnabled must not be null"
            }
        }
        val text = renderText(state)
        val keyboard = renderKeyboard(state)
        return RenderedNotifications(text, keyboard)
    }

    private fun renderText(state: NotificationsViewState): String {
        val lang = state.language
        val on = msg.get("notifications.settings.state.on", lang)
        val off = msg.get("notifications.settings.state.off", lang)
        val user = msg.get("notifications.settings.user.suffix", lang)
        val global = msg.get("notifications.settings.global.suffix", lang)

        val recordingLabel = msg.get("notifications.settings.recording.label", lang)
        val signalLabel = msg.get("notifications.settings.signal.label", lang)

        val recordingLine =
            if (state.isOwner) {
                msg.get(
                    "notifications.settings.line.owner.format",
                    lang,
                    recordingLabel,
                    if (state.recordingUserEnabled) on else off,
                    user,
                    if (state.recordingGlobalEnabled!!) on else off,
                    global,
                )
            } else {
                msg.get(
                    "notifications.settings.line.format",
                    lang,
                    recordingLabel,
                    if (state.recordingUserEnabled) on else off,
                )
            }

        val signalLine =
            if (state.isOwner) {
                msg.get(
                    "notifications.settings.line.owner.format",
                    lang,
                    signalLabel,
                    if (state.signalUserEnabled) on else off,
                    user,
                    if (state.signalGlobalEnabled!!) on else off,
                    global,
                )
            } else {
                msg.get(
                    "notifications.settings.line.format",
                    lang,
                    signalLabel,
                    if (state.signalUserEnabled) on else off,
                )
            }

        return buildString {
            appendLine(msg.get("notifications.settings.title", lang))
            appendLine()
            appendLine(recordingLine)
            appendLine(signalLine)
        }
    }

    private fun renderKeyboard(state: NotificationsViewState): InlineKeyboardMarkup {
        val lang = state.language
        val close = msg.get("notifications.settings.button.close", lang)

        return InlineKeyboardMarkup(
            keyboard =
                matrix {
                    row {
                        +CallbackDataInlineKeyboardButton(
                            msg.get(toggleKey("recording", "user", state.recordingUserEnabled), lang),
                            "nfs:u:rec:${targetValue(state.recordingUserEnabled)}",
                        )
                    }
                    row {
                        +CallbackDataInlineKeyboardButton(
                            msg.get(toggleKey("signal", "user", state.signalUserEnabled), lang),
                            "nfs:u:sig:${targetValue(state.signalUserEnabled)}",
                        )
                    }
                    if (state.isOwner) {
                        val recGlobal = state.recordingGlobalEnabled!!
                        val sigGlobal = state.signalGlobalEnabled!!
                        row {
                            +CallbackDataInlineKeyboardButton(
                                msg.get(toggleKey("recording", "global", recGlobal), lang),
                                "nfs:g:rec:${targetValue(recGlobal)}",
                            )
                        }
                        row {
                            +CallbackDataInlineKeyboardButton(
                                msg.get(toggleKey("signal", "global", sigGlobal), lang),
                                "nfs:g:sig:${targetValue(sigGlobal)}",
                            )
                        }
                    }
                    row {
                        +CallbackDataInlineKeyboardButton(close, "nfs:close")
                    }
                },
        )
    }

    private fun toggleKey(
        stream: String,
        scope: String,
        currentlyEnabled: Boolean,
    ): String {
        val action = if (currentlyEnabled) "disable" else "enable"
        return "notifications.settings.button.toggle.$stream.$scope.$action"
    }

    private fun targetValue(currentlyEnabled: Boolean): String = if (currentlyEnabled) "0" else "1"
}
