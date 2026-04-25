package ru.zinin.frigate.analyzer.ai.description.api

interface DescriptionAgent {
    suspend fun describe(request: DescriptionRequest): DescriptionResult
}
