package ru.zinin.frigate.analyzer.model.response

data class JobCreatedResponse(
    val jobId: String,
    val status: JobStatus,
    val message: String,
)
