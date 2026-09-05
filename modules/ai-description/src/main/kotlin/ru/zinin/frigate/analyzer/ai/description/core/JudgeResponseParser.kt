package ru.zinin.frigate.analyzer.ai.description.core

import kotlinx.coroutines.CancellationException
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.api.JudgeVerdict
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Component
@ConditionalOnProperty("application.ai.judge.enabled", havingValue = "true")
class JudgeResponseParser(
    private val objectMapper: ObjectMapper,
) {
    fun parse(
        raw: String,
        maxSnoozeMinutes: Int,
    ): JudgeVerdict {
        val node: JsonNode =
            try {
                objectMapper.readTree(JsonBlockExtractor.extract(raw))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw DescriptionException.InvalidResponse(e, detail = "judge answer is not JSON: ${raw.take(200)}")
            }
        val decision = enumOrInvalid<JudgeVerdict.Decision>(node["verdict"]?.scalarOrNull(), "verdict")
        val reason = enumOrInvalid<JudgeVerdict.Reason>(node["reason"]?.scalarOrNull(), "reason")
        if (reason.publishes != (decision == JudgeVerdict.Decision.PUBLISH)) {
            throw DescriptionException.InvalidResponse(detail = "reason $reason does not match verdict $decision")
        }
        val confidence = node["confidence"]?.takeIf { it.isNumber }?.doubleValue()?.takeIf { it in 0.0..1.0 }
        // Jackson 3 intValue() throws on a non-integral number; truncate toward zero then clamp.
        val snooze =
            node["snooze_minutes"]
                ?.takeIf { it.isNumber }
                ?.doubleValue()
                ?.toInt()
                ?.coerceIn(0, maxSnoozeMinutes)
                ?: 0
        return JudgeVerdict(
            decision = decision,
            reason = reason,
            confidence = confidence,
            summary = ResultNormalizer.truncate(node["summary"]?.scalarOrNull().orEmpty(), TEXT_MAX),
            snoozeMinutes = snooze,
            wanted = ResultNormalizer.truncate(node["wanted"]?.scalarOrNull().orEmpty(), TEXT_MAX),
        )
    }

    private inline fun <reified E : Enum<E>> enumOrInvalid(
        value: String?,
        field: String,
    ): E =
        enumValues<E>().firstOrNull { it.name == value }
            ?: throw DescriptionException.InvalidResponse(detail = "missing or unknown '$field': $value")

    private fun JsonNode.scalarOrNull(): String? = if (isValueNode && !isNull) asString() else null

    companion object {
        const val TEXT_MAX = 512
    }
}
