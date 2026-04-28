# Review Iteration 2 — 2026-04-27 23:59

## Источник

- Design: `docs/superpowers/specs/2026-04-27-notification-controls-and-object-tracking-design.md`
- Plan: `docs/superpowers/plans/2026-04-27-notification-controls-and-object-tracking.md`
- Review agents: codex-executor (gpt-5.5), gemini-executor, ccs-executor (glm), ccs-executor (albb-glm), ccs-executor (albb-qwen), ccs-executor (albb-kimi), ccs-executor (albb-minimax), ccs-executor (deepseek)
- Merged output: `docs/superpowers/specs/2026-04-27-notification-controls-and-object-tracking-review-merged-iter-2.md`

## Замечания

### C1: Tracker state mutates before Telegram enqueue succeeds

> If tracker creates/updates a track and Telegram enqueue fails later, future segments may be suppressed as repeats even though the first notification was never queued.

**Источник:** codex-executor
**Статус:** Обсуждено с пользователем
**Ответ:** Accepted risk for current iteration. Add follow-up document for a future Telegram outbox task.
**Действие:** Design documents the at-most-once gap. Added `docs/telegram-outbox.md` with problem statement, impact, and options (transactional outbox, compensation, accepted risk).

---

### C2: Out-of-order recording can roll bbox/last_recording_id backward

> `GREATEST(last_seen_at, :T)` protects timestamp only; older late recordings can still overwrite `bbox_*` and `last_recording_id`.

**Источник:** codex-executor, gemini-executor, ccs-executor (deepseek/albb-minimax)
**Статус:** Автоисправлено
**Ответ:** Add timestamp guard for mutable bbox/traceability fields.
**Действие:** Design updated. Plan Task 2 `updateOnMatch` now updates `bbox_*` and `last_recording_id` with `CASE WHEN :lastSeenAt >= last_seen_at THEN ... ELSE existing END`.

---

### C3: Signal-loss global OFF catch-up semantics unclear

> Signal-loss state transitions happen before Telegram gate; if global OFF drops notification, should it be sent after global ON?

**Источник:** codex-executor, ccs-executor
**Статус:** Обсуждено с пользователем
**Ответ:** Drop without catch-up. OFF means events during that period are intentionally not delivered; future transitions after ON notify normally.
**Действие:** Design documents drop-without-catch-up semantics for signal-loss/recovery global OFF.

---

### C4: Callback wiring/authorization mismatches project pattern

> Plan used `waitDataCallbackQuery` and message chat id. Existing bot uses `onDataCallbackQuery`; authorization should use callback sender.

**Источник:** codex-executor, gemini-executor, ccs-executor
**Статус:** Автоисправлено
**Ответ:** Use existing `FrigateAnalyzerBot.registerRoutes()` / `onDataCallbackQuery` pattern, authorize by `callback.user`, add try/catch around Telegram edits.
**Действие:** Plan Task 18 rewritten for `onDataCallbackQuery`. Task 17/18 now require callback sender lookup (`findByUserIdAsDto`) and try/catch around callback handling.

---

### C5: Toggle callback data can invert stale messages

> `nfs:*:tgl` toggles current DB state, so old `/notifications` messages can invert a newer setting.

**Источник:** gemini-executor
**Статус:** Автоисправлено
**Ответ:** Use explicit target state in callback data.
**Действие:** Design callback contract changed to `nfs:u:rec:1/0`, `nfs:u:sig:1/0`, `nfs:g:rec:1/0`, `nfs:g:sig:1/0`. Plan renderer/tests/dispatch updated accordingly.

---

### C6: `TransactionalOperator` compile blockers

> Task 8 test constructor was not updated after adding `TransactionalOperator`; `executeAndAwait` import was missing.

**Источник:** codex-executor
**Статус:** Автоисправлено
**Ответ:** Keep Iteration 1 transaction decision; fix plan snippets.
**Действие:** Plan Task 8 test constructor includes `transactionalOperator`; implementation imports `org.springframework.transaction.reactive.executeAndAwait`.

---

### C7: `AppSettingsService.getBoolean()` failure semantics

> Settings read failure could silently default open/closed depending on handling.

**Источник:** codex-executor
**Статус:** Обсуждено с пользователем
**Ответ:** Settings read exception is a runtime system failure. It should be logged/propagated so the pipeline stops and retries later; do not convert it to default boolean.
**Действие:** Design Error Handling updated. Plan Task 10 documents settings read failure propagation and adds a test that tracker is not called when settings read throws.

---

### C8: AppSettings cache race

> Concurrent cache miss can write stale value after `setBoolean().cache.remove()`.

**Источник:** codex-executor, gemini-executor, ccs-executor
**Статус:** Автоисправлено
**Ответ:** Synchronize cache populate/update.
**Действие:** Plan Task 9 uses a coroutine `Mutex`; `setBoolean` write-through updates cache after DB upsert.

---

### C9: `cleanupInterval` dead config

> `cleanupInterval` was exposed as env/property but `@Scheduled` used a hard-coded delay.

**Источник:** codex-executor, ccs-executor
**Статус:** Автоисправлено
**Ответ:** Use millisecond property in `@Scheduled` to avoid Duration parsing ambiguity while keeping configurability.
**Действие:** Plan Task 7/11/20/21 changed to `cleanupIntervalMs` / `NOTIFICATIONS_TRACK_CLEANUP_INTERVAL_MS` and `@Scheduled(fixedDelayString = "\${application.notifications.tracker.cleanup-interval-ms:3600000}")`.

---

### C10: Bbox coordinate contract wrong/ambiguous

> Design said normalized `0..1`, but current pipeline uses pixel coordinates.

**Источник:** codex-executor
**Статус:** Обсуждено с пользователем
**Ответ:** Pixel coordinates.
**Действие:** Design now says coordinates are in the same coordinate space as detections, currently pixel coordinates; no normalized constraints.

---

### C11: Missing `ObjectTrackerProperties` registration

> Core uses explicit `@EnableConfigurationProperties`; new properties need explicit registration.

**Источник:** ccs-executor
**Статус:** Автоисправлено
**Ответ:** Make Task 20 explicit.
**Действие:** Plan Task 20 now says to add `ObjectTrackerProperties::class` to core configuration unless project has switched to scanning.

---

### C12: `findByRecordingId` needs explicit method and tests

> Current service/repository do not expose `findByRecordingId`; Task 19 should not hide it as incidental.

**Источник:** ccs-executor
**Статус:** Автоисправлено
**Ответ:** Strengthen Task 19.
**Действие:** Plan Task 19 keeps explicit repository/service additions and adds facade tests for suppression, AI supplier not called, shouldNotify true path, and settings exception propagation.

---

### C13: All detections below `confidenceFloor`

> Non-empty detections can produce empty representatives after confidence filtering.

**Источник:** ccs-executor
**Статус:** Автоисправлено
**Ответ:** Add coverage.
**Действие:** Plan Task 8 adds `low confidence detections below floor produce zero delta and no writes` test.

---

### C14: Cleanup/index/batch operations

> Cleanup delete cannot use `(cam_id, last_seen_at)` efficiently; batch insert/transaction coverage were suggested.

**Источник:** ccs-executor
**Статус:** Частично автоисправлено
**Ответ:** Add cleanup index. Keep per-row `save()` as acceptable for small object counts in this iteration; batch insert is optimization.
**Действие:** Design and Plan Task 1 add `idx_object_tracks_lastseen (last_seen_at)`.

---

### C15: Sliding TTL means long-lived tracks can live indefinitely

> A stationary object visible for days refreshes `last_seen_at` forever.

**Источник:** ccs-executor
**Статус:** Автоисправлено
**Ответ:** This is intended sliding-TTL behavior.
**Действие:** Design edge-case table documents that tracks can live indefinitely while continuously matching.

---

### C16: `telegram → service` dependency

> Telegram module may not currently depend on service.

**Источник:** ccs-executor
**Статус:** Повтор (iter-1 C7)
**Ответ:** Already handled by Task 13 Step 0 from iteration 1.
**Действие:** Без новых изменений.

---

### C17: `runBlocking`/cleanup timeout/shutdown

> Cleanup task blocks scheduled thread / graceful shutdown concerns.

**Источник:** ccs-executor
**Статус:** Повтор (iter-1 C11)
**Ответ:** Already accepted as tech debt for a single hourly DELETE.
**Действие:** Без изменений.

---

### C18: Static `BboxClusteringHelper`

> Static `object` is less mockable.

**Источник:** ccs-executor
**Статус:** Отклонено
**Ответ:** Helper is pure deterministic logic and has direct unit tests; DI would add noise without value.
**Действие:** Без изменений.

---

### C19: JSON for future `app_settings` schedules

> Should boolean settings be JSON now to ease future schedules?

**Источник:** gemini-executor
**Статус:** Отклонено
**Ответ:** Schedules are explicitly out of scope; storing booleans as strings is sufficient for current settings and avoids premature schema complexity.
**Действие:** Без изменений.

---

## Изменения в документах

| Файл | Изменение |
|------|-----------|
| `docs/superpowers/specs/2026-04-27-notification-controls-and-object-tracking-design.md` | Added cleanup index, pixel bbox contract, out-of-order bbox guard, signal OFF drop semantics, settings fail-stop, enqueue accepted-risk note, sliding TTL note |
| `docs/superpowers/plans/2026-04-27-notification-controls-and-object-tracking.md` | Updated migration/index, repository update SQL, TransactionalOperator imports/test constructor, AppSettings cache, callback data/wiring, config vars, tests, properties registration |
| `docs/telegram-outbox.md` | New follow-up document for Telegram outbox / enqueue-after-tracker risk |
| `docs/superpowers/specs/2026-04-27-notification-controls-and-object-tracking-review-merged-iter-2.md` | Merged external review outputs including late Gemini result |

## Статистика

- Всего замечаний: 19
- Автоисправлено: 10
- Обсуждено с пользователем: 4
- Отклонено: 2
- Повторов (автоответ): 2
- Частично автоисправлено: 1
- Пользователь сказал "стоп": Нет
- Агенты: codex-executor, gemini-executor, ccs-executor (glm, albb-glm, albb-qwen, albb-kimi, albb-minimax, deepseek)
