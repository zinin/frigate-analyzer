package ru.zinin.frigate.analyzer.model.response

data class JobCreatedResponse(
    val jobId: String,
    val status: String,
    val message: String,
)
