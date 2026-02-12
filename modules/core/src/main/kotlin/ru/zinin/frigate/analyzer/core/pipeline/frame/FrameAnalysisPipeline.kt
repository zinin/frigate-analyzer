package ru.zinin.frigate.analyzer.core.pipeline.frame

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.core.config.properties.PipelineProperties
import ru.zinin.frigate.analyzer.core.helper.SpringProfileHelper
import ru.zinin.frigate.analyzer.core.loadbalancer.DetectServerLoadBalancer
import ru.zinin.frigate.analyzer.core.loadbalancer.RequestType

private val logger = KotlinLogging.logger {}

@Component
class FrameAnalysisPipeline(
    private val frameExtractorProducer: FrameExtractorProducer,
    private val frameAnalyzerConsumer: FrameAnalyzerConsumer,
    private val detectServerLoadBalancer: DetectServerLoadBalancer,
    private val springProfileHelper: SpringProfileHelper,
    private val pipelineProperties: PipelineProperties,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var pipelineJob: Job? = null
    private var frameChannel: Channel<FrameTask>? = null

    @PostConstruct
    fun start() {
        if (springProfileHelper.isTestProfile()) {
            logger.info { "Test profile detected. Analysis pipeline not started." }
            return
        }

        logger.info { "Starting analysis pipeline..." }
        pipelineJob =
            scope.launch {
                runPipeline()
            }
    }

    private suspend fun runPipeline() {
        val frameConfig = pipelineProperties.frame

        // Создаём канал с ограниченным буфером
        val channel = Channel<FrameTask>(frameConfig.channelBufferSize)
        frameChannel = channel

        // Определяем количество консьюмеров на основе ёмкости серверов для кадров
        val consumerCount =
            detectServerLoadBalancer
                .getTotalCapacity(RequestType.FRAME)
                .coerceAtLeast(frameConfig.minConsumers)

        logger.info {
            "Pipeline configuration: buffer=${frameConfig.channelBufferSize}, consumers=$consumerCount"
        }

        // Запускаем консьюмеры
        val consumerJobs =
            (1..consumerCount).map { id ->
                scope.launch {
                    frameAnalyzerConsumer.consume(channel, id)
                }
            }
        logger.info { "Started $consumerCount consumer(s)" }

        // Запускаем продюсер
        val producerJobs =
            (1..frameConfig.producersCount).map { id ->
                scope.launch {
                    logger.info { "Pipeline configuration: producer started" }

                    frameExtractorProducer.produce(channel, id)
                }
            }
        logger.info { "Started producers" }

        // Ждём завершения (при нормальной работе не завершится)
        producerJobs.joinAll()

        // Закрываем канал и ждём завершения консьюмеров
        channel.close()
        consumerJobs.joinAll()

        logger.info { "Pipeline finished" }
    }

    @PreDestroy
    fun stop() {
        logger.info { "Stopping analysis pipeline..." }

        // Закрываем канал для graceful shutdown
        frameChannel?.close()

        // Отменяем все корутины
        pipelineJob?.cancel()
        scope.cancel()

        logger.info { "Analysis pipeline stopped" }
    }
}
