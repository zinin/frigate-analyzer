package ru.zinin.frigate.analyzer.core.service

import ru.zinin.frigate.analyzer.model.response.DetectResponse

interface DetectionFilterService {
    /**
     * Фильтрует детекции, оставляя только разрешенные классы.
     * Если после фильтрации не осталось объектов, вернет response с пустым списком детекций.
     * @return отфильтрованный response
     */
    fun filterDetections(response: DetectResponse): DetectResponse
}
