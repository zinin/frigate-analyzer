package ru.zinin.frigate.analyzer.common.helper

import com.github.f4b6a3.uuid.UuidCreator
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Вспомогательный класс для генерации UUID различных версий.
 * Использует библиотеку UuidCreator для создания UUID.
 */
@Component
class UUIDGeneratorHelper {
    /**
     * Генерирует UUID версии 1 (time-based).
     * UUID версии 1 создается на основе временной метки и MAC-адреса.
     *
     * @return UUID версии 1
     */
    fun generateV1(): UUID = UuidCreator.getTimeBased()

    /**
     * Генерирует UUID версии 4 (random-based).
     * UUID версии 4 создается на основе случайных или псевдослучайных чисел.
     *
     * @return UUID версии 4
     */
    fun generateV4(): UUID = UuidCreator.getRandomBased()
}
