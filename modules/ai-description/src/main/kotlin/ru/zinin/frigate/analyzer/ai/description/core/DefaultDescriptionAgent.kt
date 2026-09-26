package ru.zinin.frigate.analyzer.ai.description.core

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionAgent
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult

/**
 * [DescriptionAgent] поверх [VisionCallExecutor]: собирает [VisionRequest] из [DescriptionTask]
 * и разбирает сырой ответ через [DescriptionResponseParser].
 *
 * Не `@Component`: бин создаёт `AiDescriptionAutoConfiguration`, когда есть каталог пресетов.
 */
class DefaultDescriptionAgent(
    private val executor: VisionCallExecutor,
    private val parser: DescriptionResponseParser,
) : DescriptionAgent {
    override suspend fun describe(request: DescriptionRequest): DescriptionResult =
        executor
            .execute(DescriptionTask.visionRequest(request)) { raw ->
                parser.parse(raw, request.shortMaxLength, request.detailedMaxLength)
            }.value
}
