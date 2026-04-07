package ru.zinin.frigate.analyzer.common.helper

import com.github.f4b6a3.uuid.UuidCreator
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Helper class for generating UUIDs of various versions.
 * Uses the UuidCreator library for UUID generation.
 */
@Component
class UUIDGeneratorHelper {
    /**
     * Generates a version 1 (time-based) UUID.
     * Version 1 UUIDs are created based on a timestamp and MAC address.
     *
     * @return version 1 UUID
     */
    fun generateV1(): UUID = UuidCreator.getTimeBased()

    /**
     * Generates a version 4 (random-based) UUID.
     * Version 4 UUIDs are created using random or pseudo-random numbers.
     *
     * @return version 4 UUID
     */
    fun generateV4(): UUID = UuidCreator.getRandomBased()
}
