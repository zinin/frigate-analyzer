package ru.zinin.frigate.analyzer.telegram.bot.handler.notifications

import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitTextMessage
import dev.inmo.tgbotapi.types.message.abstracts.ContentMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.service.NotificationScheduleService
import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import java.time.DateTimeException
import java.time.ZoneId

private val logger = KotlinLogging.logger {}

/**
 * Telegram I/O for the /notifications schedule sub-dialog: maps [ScheduleCallbackHandler]
 * outcomes to screen edits and runs the manual-zone waiter (same conventions as /timezone:
 * 120 s timeout, /cancel, error reply on unknown zone).
 */
@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class ScheduleSettingsFlow(
    private val callbackHandler: ScheduleCallbackHandler,
    private val scheduleService: NotificationScheduleService,
    private val userService: TelegramUserService,
    private val viewStateFactory: NotificationsViewStateFactory,
    private val mainRenderer: NotificationsMessageRenderer,
    private val scheduleRenderer: ScheduleKeyboardRenderer,
    private val msg: MessageResolver,
) {
    suspend fun BehaviourContext.handle(
        data: String,
        callbackMsg: ContentMessage<TextContent>,
        current: TelegramUserDto,
        isOwner: Boolean,
    ) {
        val chatId =
            current.chatId ?: run {
                // Owner is always ACTIVE with a chatId — reaching here means a broken invariant.
                logger.warn { "sched callback from user without chatId: ${current.username}" }
                return
            }
        val lang = current.languageCode ?: "en"
        // Settings reads/writes propagate here by design (only getRecordingSchedule is fail-open),
        // so a settings failure must surface to the owner instead of a dead button.
        try {
            when (val outcome = callbackHandler.dispatch(data, isOwner, chatId, current.username)) {
                ScheduleCallbackHandler.Outcome.RenderMain -> {
                    renderMain(callbackMsg, current, isOwner)
                }

                ScheduleCallbackHandler.Outcome.RenderStartPicker -> {
                    edit(callbackMsg, scheduleRenderer.startPicker(lang))
                }

                is ScheduleCallbackHandler.Outcome.RenderEndPicker -> {
                    edit(callbackMsg, scheduleRenderer.endPicker(outcome.startHour, outcome.rejectedEqualEnd, lang))
                }

                ScheduleCallbackHandler.Outcome.RenderZoneScreen -> {
                    edit(callbackMsg, scheduleRenderer.zoneScreen(scheduleService.getZone()?.id, lang))
                }

                ScheduleCallbackHandler.Outcome.AwaitManualZone -> {
                    manualZoneInput(callbackMsg, current, isOwner, lang)
                }

                // Both terminal no-ops leave the screen untouched: the bot router already answered
                // the callback, so a non-owner click (Unauthorized) or a stale/malformed payload
                // (Ignore) just clears the spinner.
                ScheduleCallbackHandler.Outcome.Unauthorized -> {
                    Unit
                }

                ScheduleCallbackHandler.Outcome.Ignore -> {
                    Unit
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "sched dispatch failed: $data" }
            sendTextMessage(callbackMsg.chat.id, msg.get("notifications.sched.error", lang))
        }
    }

    private suspend fun BehaviourContext.manualZoneInput(
        callbackMsg: ContentMessage<TextContent>,
        current: TelegramUserDto,
        isOwner: Boolean,
        lang: String,
    ) {
        val cid = callbackMsg.chat.id
        sendTextMessage(cid, msg.get("notifications.sched.zone.manual.prompt", lang))
        val completed =
            withTimeoutOrNull(MANUAL_ZONE_TIMEOUT_MS) {
                val inputMsg = waitTextMessage().filter { it.chat.id == cid }.first()
                val input = inputMsg.content.text.trim()
                if (input == "/cancel") {
                    sendTextMessage(cid, msg.get("notifications.sched.zone.cancelled", lang))
                    return@withTimeoutOrNull
                }
                try {
                    val zone = ZoneId.of(input)
                    scheduleService.setZone(zone, current.username)
                    sendTextMessage(cid, msg.get("notifications.sched.zone.saved", lang, zone.id))
                    renderMain(callbackMsg, current, isOwner)
                } catch (_: DateTimeException) {
                    sendTextMessage(cid, msg.get("notifications.sched.zone.invalid", lang))
                }
            }
        if (completed == null) {
            sendTextMessage(cid, msg.get("notifications.sched.zone.timeout", lang))
        }
    }

    private suspend fun BehaviourContext.renderMain(
        callbackMsg: ContentMessage<TextContent>,
        current: TelegramUserDto,
        isOwner: Boolean,
    ) {
        val updated = current.chatId?.let { userService.findByChatIdAsDto(it) } ?: current
        val state = viewStateFactory.build(updated, isOwner)
        edit(callbackMsg, mainRenderer.render(state))
    }

    private suspend fun BehaviourContext.edit(
        callbackMsg: ContentMessage<TextContent>,
        rendered: RenderedNotifications,
    ) {
        try {
            editMessageText(callbackMsg, rendered.text, replyMarkup = rendered.keyboard)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.message?.contains("message is not modified", ignoreCase = true) == true) {
                logger.debug { "sched edit no-op (message not modified)" }
            } else {
                logger.warn(e) { "Failed to edit /notifications schedule screen" }
            }
        }
    }

    companion object {
        private const val MANUAL_ZONE_TIMEOUT_MS = 120_000L
    }
}
