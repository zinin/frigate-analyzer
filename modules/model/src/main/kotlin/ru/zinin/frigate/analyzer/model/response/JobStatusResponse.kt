package ru.zinin.frigate.analyzer.model.response

data class JobStatusResponse(
    val jobId: String,
    val status: JobStatus,
    val progress: Int,
    val createdAt: String,
    val completedAt: String?,
    val downloadUrl: String?,
    val error: String?,
    val stats: JobStats?,
)

data class JobStats(
    val totalFrames: Int,
    val detectedFrames: Int,
    val trackedFrames: Int,
    val totalDetections: Int,
    val processingTimeMs: Int,
)
