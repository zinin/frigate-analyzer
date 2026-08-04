package ru.zinin.frigate.analyzer.core.task

import java.nio.file.Files
import java.nio.file.Path

/**
 * The canonical fixture shared by registerAllDirs and first-scan tests.
 *
 * Assumes a clock fixed at 2026-05-23T12:00:00Z and a watch period of P1D, which puts the cutoff
 * at 2026-05-22: today and the cutoff day itself are inside the window, the other two dates are not.
 *
 * Counts it pins:
 *  - directories in window  = 2 dates + 4 hours + 8 cameras           = 14, plus the root = 15
 *  - directories out of window that the walk still touches            = 2 (pruned at date level)
 *  - `.mp4` files in window = 2 x 2 x 2 x 3                           = 24
 *  - `.mp4` files out of window                                       = 24
 */
internal val CANONICAL_DATES_IN_WINDOW = listOf("2026-05-23", "2026-05-22")
internal val CANONICAL_DATES_OUT_OF_WINDOW = listOf("2026-05-21", "2025-01-01")
internal val CANONICAL_HOURS = listOf("00", "01")
internal val CANONICAL_CAMERAS = listOf("cam1", "cam2")
internal val CANONICAL_FILES = listOf("00.10.mp4", "00.20.mp4", "00.30.mp4")

internal fun buildCanonicalTree(root: Path) {
    (CANONICAL_DATES_IN_WINDOW + CANONICAL_DATES_OUT_OF_WINDOW).forEach { date ->
        CANONICAL_HOURS.forEach { hour ->
            CANONICAL_CAMERAS.forEach { camera ->
                val leaf = root.resolve(date).resolve(hour).resolve(camera)
                Files.createDirectories(leaf)
                CANONICAL_FILES.forEach { name -> Files.createFile(leaf.resolve(name)) }
            }
        }
    }
}

/** Every directory the walk is expected to register when started from [root]. */
internal fun canonicalRegisteredDirs(root: Path): Set<Path> =
    buildSet {
        add(root)
        CANONICAL_DATES_IN_WINDOW.forEach { date ->
            add(root.resolve(date))
            CANONICAL_HOURS.forEach { hour ->
                add(root.resolve(date).resolve(hour))
                CANONICAL_CAMERAS.forEach { camera ->
                    add(root.resolve(date).resolve(hour).resolve(camera))
                }
            }
        }
    }
