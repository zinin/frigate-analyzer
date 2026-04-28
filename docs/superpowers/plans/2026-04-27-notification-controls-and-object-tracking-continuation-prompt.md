## TASK

Continue iterative external design review for notification controls and object tracking.

## CRITICAL: DO NOT IMPLEMENT

**STOP. READ THIS CAREFULLY.**

This fresh session is for **review iteration 3 only**, not for implementation.

After loading all context below, you MUST:
1. Read the listed documents and understand current review state.
2. Run `/review-design-external-iterative default` to start the next review iteration.
3. Follow that skill exactly.

**DO NOT:**
- Start implementing plan tasks.
- Modify application code.
- Run build/test/lint commands unless the review skill explicitly requires them.
- Assume implementation should begin after review.

## COMMAND TO RUN

Run this slash command in the fresh session:

```text
/review-design-external-iterative default
```

## DOCUMENTS

- Design: `docs/superpowers/specs/2026-04-27-notification-controls-and-object-tracking-design.md`
- Plan: `docs/superpowers/plans/2026-04-27-notification-controls-and-object-tracking.md`
- Iteration 1: `docs/superpowers/specs/2026-04-27-notification-controls-and-object-tracking-review-iter-1.md`
- Iteration 2: `docs/superpowers/specs/2026-04-27-notification-controls-and-object-tracking-review-iter-2.md`
- Merged iteration 2 output: `docs/superpowers/specs/2026-04-27-notification-controls-and-object-tracking-review-merged-iter-2.md`
- Telegram outbox follow-up note: `docs/telegram-outbox.md`

## PROGRESS

**Implementation tasks completed:** None. All 22 implementation tasks remain pending.

**Review progress:**
- Iteration 1 completed and committed earlier.
- Iteration 2 completed and committed as `8425ade docs: review iteration 2 for notification-controls-and-object-tracking`.
- Design and plan documents have been updated with iteration 2 fixes.
- `docs/telegram-outbox.md` was created as a follow-up note for the accepted Telegram enqueue/outbox risk.

## SESSION CONTEXT FROM ITERATION 2

Iteration 2 reviewed design and plan using Codex, Gemini, and CCS profiles.

Key decisions from the user:

1. **Tracker state before Telegram enqueue failure**
   - If tracker creates/updates a track and Telegram enqueue/fan-out later fails, future segments may be suppressed as repeats even though the first notification was not queued.
   - User chose: document this as an accepted risk for the current iteration.
   - A follow-up document was created: `docs/telegram-outbox.md`.

2. **Signal-loss/recovery while global signal notifications are OFF**
   - User chose: drop without catch-up.
   - OFF means signal events during that period are intentionally not delivered; after ON, only future transitions notify.

3. **`app_settings` read failure semantics**
   - User clarified that a runtime exception while reading settings means the application is malfunctioning.
   - Do not default-open or default-closed.
   - Let the exception propagate/log so the pipeline stops and can retry the recording later.

4. **Bbox coordinate contract**
   - User chose: pixel coordinates.
   - Docs now say coordinates use the same coordinate space as detections, currently pixel coordinates; do not add normalized `0..1` constraints.

Major iteration 2 document changes already applied:

- Added `idx_object_tracks_lastseen (last_seen_at)` for cleanup deletes.
- Added out-of-order timestamp guard for `bbox_*` and `last_recording_id` in `updateOnMatch`.
- Replaced callback toggle semantics with explicit target state callback data: `nfs:*:*:1/0`.
- Rewrote bot callback wiring guidance to use existing `onDataCallbackQuery(initialFilter = { ... })` pattern in `FrigateAnalyzerBot.registerRoutes()`.
- Added callback sender authorization requirement via `callback.user`, not only `message.chat.id`.
- Added try/catch around callback handling/editing to avoid breaking the callback flow.
- Fixed `TransactionalOperator` test/import snippets in the plan.
- Updated `AppSettingsServiceImpl` cache plan to use coroutine `Mutex` and write-through cache updates.
- Changed cleanup scheduling config to `cleanupIntervalMs` / `NOTIFICATIONS_TRACK_CLEANUP_INTERVAL_MS`.
- Added `NOTIFICATIONS_TRACK_CONFIDENCE_FLOOR` to config/docs.
- Made `ObjectTrackerProperties` registration in core explicit.
- Added/focused tests for low-confidence detections, settings failure propagation, facade suppression/no-AI-supplier behavior, callback semantics, and `isOwner`.
- Documented sliding TTL: a continuously matched track may live indefinitely while the object stays visible.

Known dismissed/repeated issues:

- `telegram → service` dependency is already handled by Task 13 Step 0 from iteration 1; during implementation, verify and add if missing.
- `runBlocking` in cleanup remains accepted tech debt for a simple hourly DELETE.
- Static `BboxClusteringHelper` is intentionally kept as pure deterministic logic, not DI.
- JSON `app_settings` for future schedules was rejected as premature; schedules remain out of scope.
- Full Hungarian matching remains out of scope; current best-match is accepted for this iteration.

## PROJECT CONVENTIONS

- Do NOT implement in this fresh session unless explicitly instructed after review.
- Do NOT run `./gradlew build` directly. Any build/test/lint/format command must be delegated to `build-runner`.
- One commit per implementation task when implementation eventually begins.
- Before PR, `docs/superpowers/...` plan/spec/review files must be removed from PR diff per project workflow, while remaining available in branch history.

## PLAN QUALITY WARNING

The design/plan are large and may still contain:
- errors or inaccuracies in implementation details;
- assumptions that do not match current code;
- missing tests or edge cases;
- stale snippets after review edits.

During review iteration 3, be critical and focus on **new issues not already covered** by iterations 1 and 2.

## INSTRUCTIONS FOR THE FRESH SESSION

1. Read the documents listed above.
2. Invoke `/review-design-external-iterative default`.
3. Let the skill find previous iterations and run iteration 3.
4. Process new issues according to the skill.
5. Do not start implementation.
