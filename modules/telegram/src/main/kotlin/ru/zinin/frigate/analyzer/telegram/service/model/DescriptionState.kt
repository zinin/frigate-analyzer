package ru.zinin.frigate.analyzer.telegram.service.model

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult

/**
 * Состояние AI-описания на момент рендера сообщения. Заменяет прежний признак
 * «`formatter == null` значит описание выключено».
 */
sealed interface DescriptionState {
    /** Описание выключено настройкой или кадров нет — блоков описания в сообщении не будет. */
    data object Absent : DescriptionState

    /** Запрос к модели в полёте — рендерятся плейсхолдеры, которые перепишет правка. */
    data object Pending : DescriptionState

    data class Ready(
        val result: DescriptionResult,
    ) : DescriptionState

    /** Модель не ответила — в оба блока идёт текст fallback. */
    data object Failed : DescriptionState
}
