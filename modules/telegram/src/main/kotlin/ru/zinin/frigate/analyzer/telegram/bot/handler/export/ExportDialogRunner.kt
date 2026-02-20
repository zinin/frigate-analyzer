package ru.zinin.frigate.analyzer.telegram.bot.handler.export

import dev.inmo.tgbotapi.extensions.api.answers.answer
import dev.inmo.tgbotapi.extensions.api.edit.reply_markup.editMessageReplyMarkup
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitDataCallbackQuery
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitTextMessage
import dev.inmo.tgbotapi.types.IdChatIdentifier
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.queries.callback.MessageDataCallbackQuery
import dev.inmo.tgbotapi.utils.matrix
import dev.inmo.tgbotapi.utils.row
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.service.VideoExportService
import ru.zinin.frigate.analyzer.telegram.service.model.ExportMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class ExportDialogRunner(
    private val videoExportService: VideoExportService,
    private val clock: Clock,
) {
    @Suppress("LongMethod")
    suspend fun BehaviourContext.runDialog(
        chatId: IdChatIdentifier,
        userZone: ZoneId,
    ): ExportDialogOutcome =
        withTimeoutOrNull(EXPORT_DIALOG_TIMEOUT_MS) {
            val dateKeyboard =
                InlineKeyboardMarkup(
                    keyboard =
                        matrix {
                            row {
                                +CallbackDataInlineKeyboardButton("Сегодня", "export:today")
                                +CallbackDataInlineKeyboardButton("Вчера", "export:yesterday")
                            }
                            row {
                                +CallbackDataInlineKeyboardButton("Ввести дату", "export:custom")
                                +CallbackDataInlineKeyboardButton("Отмена", "export:cancel")
                            }
                        },
                )

            val dateSentMessage = sendTextMessage(chatId, "Выберите дату:", replyMarkup = dateKeyboard)

            val dateCallback =
                waitDataCallbackQuery()
                    .filter {
                        it.data.startsWith("export:") &&
                            (it as? MessageDataCallbackQuery)?.message?.let { msg ->
                                msg.messageId == dateSentMessage.messageId && msg.chat.id == chatId
                            } == true
                    }.first()
            answer(dateCallback)
            try {
                editMessageReplyMarkup(dateSentMessage, replyMarkup = null)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }

            if (dateCallback.data == "export:cancel") {
                sendTextMessage(chatId, "Экспорт отменён.")
                return@withTimeoutOrNull ExportDialogOutcome.Cancelled
            }

            val date: LocalDate =
                when (dateCallback.data) {
                    "export:today" -> {
                        Instant.now(clock).atZone(userZone).toLocalDate()
                    }

                    "export:yesterday" -> {
                        Instant
                            .now(clock)
                            .atZone(userZone)
                            .toLocalDate()
                            .minusDays(1)
                    }

                    "export:custom" -> {
                        sendTextMessage(chatId, "Введите дату (формат: YYYY-MM-DD) или /cancel для отмены:")
                        val dateMsg =
                            waitTextMessage()
                                .filter { it.chat.id == chatId }
                                .first()
                        val dateInput = dateMsg.content.text.trim()
                        if (dateInput == "/cancel" || dateInput.equals("отмена", ignoreCase = true)) {
                            sendTextMessage(chatId, "Экспорт отменён.")
                            return@withTimeoutOrNull ExportDialogOutcome.Cancelled
                        }
                        try {
                            LocalDate.parse(dateInput)
                        } catch (e: DateTimeParseException) {
                            sendTextMessage(chatId, "Неверный формат даты. Используйте YYYY-MM-DD. Экспорт отменён.")
                            return@withTimeoutOrNull ExportDialogOutcome.Cancelled
                        }
                    }

                    else -> {
                        return@withTimeoutOrNull ExportDialogOutcome.Cancelled
                    }
                }

            sendTextMessage(
                chatId,
                "Введите диапазон времени (например: 9:15-9:20, макс. 5 минут)\nВремя в вашем часовом поясе: $userZone\nИли /cancel:",
            )
            val timeMsg =
                waitTextMessage()
                    .filter { it.chat.id == chatId }
                    .first()

            val timeInput = timeMsg.content.text.trim()
            if (timeInput == "/cancel" || timeInput.equals("отмена", ignoreCase = true)) {
                sendTextMessage(chatId, "Экспорт отменён.")
                return@withTimeoutOrNull ExportDialogOutcome.Cancelled
            }

            val timeRange = parseTimeRange(timeInput)
            if (timeRange == null) {
                sendTextMessage(chatId, "Неверный формат. Используйте H:MM-H:MM (например, 9:15-9:20). Экспорт отменён.")
                return@withTimeoutOrNull ExportDialogOutcome.Cancelled
            }

            val (startTime, endTime) = timeRange
            val durationMinutes = ChronoUnit.MINUTES.between(startTime, endTime)
            if (durationMinutes > MAX_EXPORT_DURATION_MINUTES || durationMinutes <= 0) {
                sendTextMessage(
                    chatId,
                    "Диапазон должен быть от 1 до $MAX_EXPORT_DURATION_MINUTES минут. Экспорт отменён.",
                )
                return@withTimeoutOrNull ExportDialogOutcome.Cancelled
            }

            val startInstant = LocalDateTime.of(date, startTime).atZone(userZone).toInstant()
            val endInstant = LocalDateTime.of(date, endTime).atZone(userZone).toInstant()

            val actualDuration = Duration.between(startInstant, endInstant)
            if (actualDuration.isNegative || actualDuration.isZero || actualDuration.toMinutes() > MAX_EXPORT_DURATION_MINUTES) {
                sendTextMessage(
                    chatId,
                    "Диапазон после конвертации в UTC превышает 5 минут " +
                        "(возможно из-за перехода на летнее/зимнее время). Попробуйте другой диапазон.",
                )
                return@withTimeoutOrNull ExportDialogOutcome.Cancelled
            }

            val cameras = videoExportService.findCamerasWithRecordings(startInstant, endInstant)
            if (cameras.isEmpty()) {
                sendTextMessage(chatId, "Записей за $date $startTime-$endTime не найдено.")
                return@withTimeoutOrNull ExportDialogOutcome.Cancelled
            }

            val cameraKeyboard =
                InlineKeyboardMarkup(
                    keyboard =
                        matrix {
                            cameras.forEach { cam ->
                                row {
                                    +CallbackDataInlineKeyboardButton(
                                        "${cam.camId} (${cam.recordingsCount})",
                                        "export:cam:${cam.camId}",
                                    )
                                }
                            }
                            row {
                                +CallbackDataInlineKeyboardButton("Отмена", "export:cancel")
                            }
                        },
                )

            val camSentMessage = sendTextMessage(chatId, "Выберите камеру:", replyMarkup = cameraKeyboard)

            val camCallback =
                waitDataCallbackQuery()
                    .filter {
                        (it.data.startsWith("export:cam:") || it.data == "export:cancel") &&
                            (it as? MessageDataCallbackQuery)?.message?.let { msg ->
                                msg.messageId == camSentMessage.messageId && msg.chat.id == chatId
                            } == true
                    }.first()
            answer(camCallback)
            try {
                editMessageReplyMarkup(camSentMessage, replyMarkup = null)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }

            if (camCallback.data == "export:cancel") {
                sendTextMessage(chatId, "Экспорт отменён.")
                return@withTimeoutOrNull ExportDialogOutcome.Cancelled
            }

            val camId = camCallback.data.removePrefix("export:cam:")

            val modeKeyboard =
                InlineKeyboardMarkup(
                    keyboard =
                        matrix {
                            row {
                                +CallbackDataInlineKeyboardButton("Оригинал", "export:mode:original")
                                +CallbackDataInlineKeyboardButton("С объектами", "export:mode:annotated")
                            }
                            row {
                                +CallbackDataInlineKeyboardButton("Отмена", "export:cancel")
                            }
                        },
                )

            val modeSentMessage = sendTextMessage(chatId, "Выберите режим экспорта:", replyMarkup = modeKeyboard)

            val modeCallback =
                waitDataCallbackQuery()
                    .filter {
                        (it.data.startsWith("export:mode:") || it.data == "export:cancel") &&
                            (it as? MessageDataCallbackQuery)?.message?.let { msg ->
                                msg.messageId == modeSentMessage.messageId && msg.chat.id == chatId
                            } == true
                    }.first()
            answer(modeCallback)
            try {
                editMessageReplyMarkup(modeSentMessage, replyMarkup = null)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }

            if (modeCallback.data == "export:cancel") {
                sendTextMessage(chatId, "Экспорт отменён.")
                return@withTimeoutOrNull ExportDialogOutcome.Cancelled
            }

            val mode =
                when (modeCallback.data) {
                    "export:mode:annotated" -> ExportMode.ANNOTATED
                    else -> ExportMode.ORIGINAL
                }

            ExportDialogOutcome.Success(startInstant, endInstant, camId, mode)
        } ?: ExportDialogOutcome.Timeout

    private fun parseTimeRange(input: String): Pair<LocalTime, LocalTime>? {
        val parts = input.split("-", limit = 2)
        if (parts.size != 2) return null

        return try {
            val formatter = DateTimeFormatter.ofPattern("H:mm")
            val start = LocalTime.parse(parts[0].trim(), formatter)
            val end = LocalTime.parse(parts[1].trim(), formatter)
            start to end
        } catch (e: DateTimeParseException) {
            null
        }
    }
}
