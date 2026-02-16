package ru.zinin.frigate.analyzer.model.response

import com.fasterxml.jackson.annotation.JsonProperty

enum class JobStatus {
    @JsonProperty("queued") QUEUED,
    @JsonProperty("processing") PROCESSING,
    @JsonProperty("completed") COMPLETED,
    @JsonProperty("failed") FAILED,
}
