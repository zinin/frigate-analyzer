package ru.zinin.frigate.analyzer.telegram.bot.handler.aisettings

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionPreset
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionPresets
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRuntimeSettings
import ru.zinin.frigate.analyzer.ai.description.api.JudgeRuntimeSettings

private val logger = KotlinLogging.logger {}

/**
 * Диспетчер `aip:*`. Разбор и проверка ([classify]) отделены от записи ([apply]), а [handle]
 * держит между ними единственный порядок, который этот экран допускает: **сначала ответ на
 * коллбэк, потом запись**.
 *
 * Порядок — не косметика. Регистрация коллбэков идёт с дефолтным `markerFactory`, который
 * сериализует клики одного пользователя, поэтому обработчик, ждущий медленную БД, задержит
 * СЛЕДУЮЩИЙ клик владельца, а не только собственный спиннер. Кажущееся противоречие «алерту нужен
 * результат записи» ложное: исходы, требующие текста (недоступный пресет, исчезнувший id, клик
 * не-владельца), разрешаются из каталога и роли без обращения к БД, а исходы с записью
 * содержательного текста не несут — их подтверждает перерисовка, которая идёт после записи и
 * потому соврать не может: упавшая запись оставит на экране прежний активный пресет.
 *
 * Значение в payload всегда явное (`aip:on` / `aip:off`, а не toggle), поэтому повторный клик
 * идемпотентен.
 *
 * Зависимости через [ObjectProvider]: пресеты и хранилище настроек могут быть не объявлены, а бот
 * обязан стартовать. Отсутствие любого из них гасит спиннер и ничего не пишет.
 */
@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class AiSettingsCallbackHandler(
    private val presetsProvider: ObjectProvider<DescriptionPresets>,
    private val runtimeSettingsProvider: ObjectProvider<DescriptionRuntimeSettings>,
    private val judgeSettingsProvider: ObjectProvider<JudgeRuntimeSettings>,
) {
    enum class DispatchOutcome { RERENDER, CLOSE, UNAUTHORIZED, IGNORE, ALERT }

    data class Dispatched(
        val outcome: DispatchOutcome,
        /** Ключ i18n для `answerCallbackQuery`; null, когда исход текста не несёт. */
        val alertKey: String? = null,
        /** Причина отказа кодом; null у ключей без аргументов. */
        val alertCause: AiSettingsAlertCause? = null,
    )

    /**
     * Полный цикл одного клика: ответ уходит ровно один раз и до записи. [answer] обязан не
     * бросать — на стороне бота он несёт собственный catch, потому что отказ Telegram на ответе
     * не повод отменять выбор владельца.
     *
     * Отказ записи пробрасывается: перерисовывать экран после неудачной записи незачем, он и так
     * покажет прежний пресет, а разбираться с исключением — дело вызывающей стороны, у которой
     * есть данные коллбэка для лога.
     */
    suspend fun handle(
        data: String,
        isOwner: Boolean,
        changedBy: String?,
        answer: suspend (Dispatched) -> Unit,
    ): Dispatched {
        val dispatched = classify(data, isOwner)
        answer(dispatched)
        if (dispatched.outcome == DispatchOutcome.RERENDER) {
            apply(data, changedBy)
        }
        return dispatched
    }

    /**
     * Чистая часть: разбирает payload, сверяется с каталогом и ролью. Ввода-вывода нет и быть не
     * должно — от неё зависит текст ответа, который уходит раньше записи. Не бросает: спиннер
     * владельца обязан гаснуть на любом исходе.
     */
    fun classify(
        data: String,
        isOwner: Boolean,
    ): Dispatched {
        // Экран owner-only, но клавиатура живёт в переписке: пересланное сообщение приносит клик
        // от кого угодно, и ответить на него надо тем же, чем ответила бы команда.
        if (!isOwner) return Dispatched(DispatchOutcome.UNAUTHORIZED, "common.error.owner.only")
        return when (val action = parse(data)) {
            null -> {
                logger.debug { "Ignoring malformed aip callback: $data" }
                Dispatched(DispatchOutcome.IGNORE)
            }

            Action.Close -> {
                Dispatched(DispatchOutcome.CLOSE)
            }

            is Action.Switch, is Action.JudgeSwitch -> {
                Dispatched(DispatchOutcome.RERENDER)
            }

            is Action.Select -> {
                classifySelect(action.id)
            }

            is Action.JudgeSelect -> {
                classifySelect(action.id)
            }
        }
    }

    /**
     * Запись выбора владельца. Каталог здесь не проверяется — это работа [classify]; вызывать
     * только для исхода, который её прошёл.
     */
    suspend fun apply(
        data: String,
        changedBy: String?,
    ) {
        when (val action = parse(data)) {
            is Action.Switch -> {
                val settings = runtimeSettingsProvider.getIfAvailable()
                if (settings == null) {
                    logger.warn { "No DescriptionRuntimeSettings bean: aip callback changed nothing ($data)" }
                } else {
                    settings.setDescriptionsEnabled(action.enabled, changedBy)
                }
            }

            is Action.Select -> {
                val settings = runtimeSettingsProvider.getIfAvailable()
                if (settings == null) {
                    logger.warn { "No DescriptionRuntimeSettings bean: aip callback changed nothing ($data)" }
                } else {
                    settings.setActivePresetId(action.id, changedBy)
                }
            }

            is Action.JudgeSwitch -> {
                val judgeSettings = judgeSettingsProvider.getIfAvailable()
                if (judgeSettings == null) {
                    logger.warn { "No JudgeRuntimeSettings bean: aip callback changed nothing ($data)" }
                } else {
                    judgeSettings.setJudgeEnabled(action.enabled, changedBy)
                }
            }

            is Action.JudgeSelect -> {
                val judgeSettings = judgeSettingsProvider.getIfAvailable()
                if (judgeSettings == null) {
                    logger.warn { "No JudgeRuntimeSettings bean: aip callback changed nothing ($data)" }
                } else {
                    judgeSettings.setActivePresetId(action.id, changedBy)
                }
            }

            Action.Close, null -> {
                // classify already decided; close and malformed payloads persist nothing
            }
        }
    }

    private fun classifySelect(id: String): Dispatched {
        val catalog = catalog()
        if (catalog == null) {
            // Проверить нечего, поэтому и писать нечего: молчаливый отказ честнее алерта, который
            // назвал бы выдуманную причину.
            logger.warn { "Preset catalog is unavailable: aip:set:$id refused" }
            return Dispatched(DispatchOutcome.IGNORE)
        }
        val preset: DescriptionPreset? = catalog.firstOrNull { it.id == id }
        return when {
            preset == null -> {
                Dispatched(DispatchOutcome.ALERT, ALERT_UNAVAILABLE, AiSettingsAlertCause.Gone)
            }

            !preset.available -> {
                Dispatched(
                    DispatchOutcome.ALERT,
                    ALERT_UNAVAILABLE,
                    // available == false означает непустую причину; в противном случае это Gone.
                    preset.unavailableReason?.let { AiSettingsAlertCause.Unavailable(it) } ?: AiSettingsAlertCause.Gone,
                )
            }

            else -> {
                Dispatched(DispatchOutcome.RERENDER)
            }
        }
    }

    /** null = каталога нет или он не прочитался: клик по пресету отбивается без записи. */
    private fun catalog(): List<DescriptionPreset>? =
        try {
            presetsProvider.getIfAvailable()?.all()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to read the preset catalog for an aip callback" }
            null
        }

    private fun parse(data: String): Action? =
        when {
            data == AiSettingsCallbacks.CLOSE -> {
                Action.Close
            }

            data == AiSettingsCallbacks.ON -> {
                Action.Switch(true)
            }

            data == AiSettingsCallbacks.OFF -> {
                Action.Switch(false)
            }

            data == AiSettingsCallbacks.JUDGE_ON -> {
                Action.JudgeSwitch(true)
            }

            data == AiSettingsCallbacks.JUDGE_OFF -> {
                Action.JudgeSwitch(false)
            }

            data.startsWith(AiSettingsCallbacks.JUDGE_SET_PREFIX) -> {
                data.removePrefix(AiSettingsCallbacks.JUDGE_SET_PREFIX).takeIf { it.isNotBlank() }?.let { Action.JudgeSelect(it) }
            }

            data.startsWith(AiSettingsCallbacks.SET_PREFIX) -> {
                data.removePrefix(AiSettingsCallbacks.SET_PREFIX).takeIf { it.isNotBlank() }?.let { Action.Select(it) }
            }

            else -> {
                null
            }
        }

    /** Разобранный payload: один разбор на [classify] и [apply], чтобы они не разошлись. */
    private sealed interface Action {
        data object Close : Action

        data class Switch(
            val enabled: Boolean,
        ) : Action

        data class Select(
            val id: String,
        ) : Action

        data class JudgeSwitch(
            val enabled: Boolean,
        ) : Action

        data class JudgeSelect(
            val id: String,
        ) : Action
    }

    private companion object {
        const val ALERT_UNAVAILABLE = "ai.settings.alert.unavailable"
    }
}
