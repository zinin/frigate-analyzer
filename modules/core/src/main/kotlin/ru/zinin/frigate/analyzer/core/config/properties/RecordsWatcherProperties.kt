package ru.zinin.frigate.analyzer.core.config.properties

import jakarta.validation.constraints.NotNull
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.nio.file.Path
import java.time.Duration

@ConfigurationProperties(prefix = "application.records-watcher")
@Validated
data class RecordsWatcherProperties(
    // true: скан — opt-in бэкфилл (первичная установка, восстановление индекса). Дефолт приведён
    // к единственной реальной эксплуатации; починенный (наконец доживающий до конца) скан при
    // включённом дефолте впервые исполнил бы никем не наблюдавшийся ~52k-бэкфилл на свежем деплое.
    val disableFirstScan: Boolean = true,
    @field:NotNull
    val folder: Path,
    @field:NotNull
    val watchPeriod: Duration = Duration.ofDays(1),
    @field:NotNull
    val cleanupInterval: Duration = Duration.ofHours(1),
    /**
     * How far back the one-off startup scan indexes files. Defaults to [watchPeriod] truncated to
     * whole days: the backfill covers exactly the window the watcher watches.
     *
     * Filtering is by date, so the window is always whole days **in UTC** — Frigate names date
     * directories by UTC and watchCutoff evaluates "today" in UTC as well (documented
     * assumption; it matters for `P0D` on hosts west of UTC). `P0D` means today only, `P1D` means
     * today and yesterday. The lower bound is `P0D` rather than `watchPeriod`'s one day, otherwise
     * "scan today only" would be inexpressible.
     *
     * The inherited default is truncated rather than validated because watchCutoff already
     * truncates through `toDays()`: a `watchPeriod` of `PT36H` has always *meant* one day for the
     * watch window, so normalizing here makes the scan window agree with the watch window instead
     * of rejecting a value the watcher itself accepts. An explicitly set `FIRST_SCAN_PERIOD` is
     * still validated, so a deliberate `PT12H` fails at startup — naming a variable the operator
     * actually set.
     */
    @field:NotNull
    val firstScanPeriod: Duration = Duration.ofDays(watchPeriod.toDays()),
) {
    init {
        require(watchPeriod.toDays() >= 1) { "watchPeriod must be at least 1 day, got: $watchPeriod" }
        require(!cleanupInterval.isNegative && !cleanupInterval.isZero) { "cleanupInterval must be positive, got: $cleanupInterval" }
        require(!firstScanPeriod.isNegative) { "firstScanPeriod must not be negative, got: $firstScanPeriod" }
        // Целые сутки: toDays() молча усекает, и PT12H превратился бы в «только сегодня».
        // Унаследованный дефолт уже нормализован, так что сюда доходит только явно заданное значение.
        require(firstScanPeriod == Duration.ofDays(firstScanPeriod.toDays())) {
            "firstScanPeriod must be whole days (P0D, P1D, ...), got: $firstScanPeriod"
        }
    }
}
