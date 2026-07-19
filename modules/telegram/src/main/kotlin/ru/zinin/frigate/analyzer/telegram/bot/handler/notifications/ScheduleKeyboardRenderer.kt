package ru.zinin.frigate.analyzer.telegram.bot.handler.notifications

import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.utils.matrix
import dev.inmo.tgbotapi.utils.row
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.i18n.TimezonePresets
import java.util.Locale

/**
 * Screens of the /notifications schedule sub-dialog: hour pickers and the timezone
 * chooser. Stateless by design — the chosen start hour travels inside the end-picker
 * callback data, so screens survive restarts and stale keyboards.
 */
@Component
class ScheduleKeyboardRenderer(
    private val msg: MessageResolver,
) {
    fun startPicker(lang: String): RenderedNotifications =
        RenderedNotifications(
            text = msg.get("notifications.sched.picker.start.title", lang),
            keyboard = hourGrid(lang) { hour -> "nfs:g:sched:s:$hour" },
        )

    fun endPicker(
        startHour: Int,
        showEqualWarning: Boolean,
        lang: String,
    ): RenderedNotifications {
        val title = msg.get("notifications.sched.picker.end.title", lang, formatHour(startHour))
        val text =
            if (showEqualWarning) {
                title + "\n\n" + msg.get("notifications.sched.picker.end.invalid", lang)
            } else {
                title
            }
        return RenderedNotifications(text, hourGrid(lang) { hour -> "nfs:g:sched:e:$startHour:$hour" })
    }

    fun zoneScreen(
        currentZone: String?,
        lang: String,
    ): RenderedNotifications =
        RenderedNotifications(
            text =
                msg.get(
                    "notifications.sched.zone.title",
                    lang,
                    currentZone ?: msg.get("notifications.sched.zone.unset", lang),
                ),
            keyboard =
                InlineKeyboardMarkup(
                    keyboard =
                        matrix {
                            TimezonePresets.CITIES.chunked(2).forEach { pair ->
                                row {
                                    pair.forEach { (labelKey, olson) ->
                                        +CallbackDataInlineKeyboardButton(msg.get(labelKey, lang), "nfs:g:sched:z:$olson")
                                    }
                                }
                            }
                            row {
                                +CallbackDataInlineKeyboardButton(
                                    msg.get("notifications.sched.zone.manual", lang),
                                    "nfs:g:sched:zman",
                                )
                                +CallbackDataInlineKeyboardButton(
                                    msg.get("notifications.sched.picker.back", lang),
                                    "nfs:g:sched:home",
                                )
                            }
                        },
                ),
        )

    private fun hourGrid(
        lang: String,
        callbackFor: (Int) -> String,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            keyboard =
                matrix {
                    (0..23).chunked(6).forEach { hours ->
                        row {
                            hours.forEach { hour ->
                                // Picker hours are always 00–23 zero-padded; Locale.ROOT keeps the
                                // digits ASCII regardless of the JVM default locale.
                                +CallbackDataInlineKeyboardButton("%02d".format(Locale.ROOT, hour), callbackFor(hour))
                            }
                        }
                    }
                    row {
                        +CallbackDataInlineKeyboardButton(msg.get("notifications.sched.picker.back", lang), "nfs:g:sched:home")
                    }
                },
        )

    // Locale.ROOT so the start-hour label stays ASCII "HH:00" whatever the JVM default locale is.
    private fun formatHour(hour: Int): String = "%02d:00".format(Locale.ROOT, hour)
}
