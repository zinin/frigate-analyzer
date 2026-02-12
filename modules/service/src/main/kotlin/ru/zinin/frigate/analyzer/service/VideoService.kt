package ru.zinin.frigate.analyzer.service

import ru.zinin.frigate.analyzer.model.request.ExtractFramesRequest
import java.nio.file.Path

interface VideoService {
    fun extractFramesLocal(request: ExtractFramesRequest): List<Path>
}
