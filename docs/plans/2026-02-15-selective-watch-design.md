# Selective Watch for Frigate Recordings

GitHub Issue: https://github.com/zinin/frigate-analyzer/issues/2

## Problem

`WatchRecordsTask` registers ALL subdirectories under `frigate-records-folder` with WatchService.
Old date directories (e.g., months ago) never receive new files, wasting OS inotify watches.

## Solution: Date-filtered registration + periodic cleanup

### Approach

1. At startup, `registerAllDirs` filters directories by date from path (`YYYY-MM-DD`)
2. New directories created by Frigate are caught via existing `ENTRY_CREATE` and registered only if within `watch-period`
3. Periodic cleanup (configurable interval) cancels WatchKeys for directories that aged out of `watch-period`

### Configuration

New group `records-watcher` in `application.yaml`:

```yaml
application:
  records-watcher:
    disable-first-scan: ${DISABLE_FIRST_SCAN:false}
    folder: ${FRIGATE_RECORDS_FOLDER:/mnt/data/frigate/recordings}
    watch-period: ${WATCH_PERIOD:P1D}
    cleanup-interval: ${WATCH_CLEANUP_INTERVAL:PT1H}
```

Moves `disable-first-scan-task` and `frigate-records-folder` into this group.
Removes them from top-level `ApplicationProperties`.

### Date extraction logic

Given Frigate directory structure: `recordings/YYYY-MM-DD/HH/cam_id/`

- Extract date from path by finding `YYYY-MM-DD` pattern among path components
- If no date found (root folder) — register it (needed to catch new date-directories)
- If date found and within `watch-period` — register
- If date found and older — skip

### Cleanup

- Track `lastCleanupTime` in the watch loop
- When `cleanupInterval` elapsed, iterate `registeredDirs`
- Cancel and remove WatchKeys for directories with dates older than `watch-period`
- Log count of removed watches

### Edge cases

- Root `recordings/` folder: no date in path → always registered
- Midnight transition: new date folder caught by ENTRY_CREATE on root; old folders cleaned up on next cleanup cycle
- Unparseable folder names: treated as no-date → registered (safe default)
