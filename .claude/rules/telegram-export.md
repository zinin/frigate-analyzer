---
paths: "modules/telegram/**/handler/export/**,modules/telegram/**/handler/quickexport/**,modules/telegram/**/handler/cancel/**"
---

# Telegram Video Export

Two paths to video export: `/export` (interactive dialog) and Quick Export (inline buttons on
detection notifications). Both share the execution layer, cancellation flow, and concurrency
guards described below.

General Telegram bot context (auth, queue, ktgbotapi waiter API) lives in `telegram.md` and is
loaded alongside this file whenever you touch `modules/telegram/**`.

## Components

| Component | Location | Purpose |
|---|---|---|
| ExportDialogRunner | `telegram/bot/handler/export/` | Drives the `/export` interactive dialog (date → time range → camera → mode) |
| ExportExecutor | `telegram/bot/handler/export/` | Submits the export job, tracks progress, updates status message |
| ActiveExportTracker | `telegram/bot/handler/export/` | Dialog-phase lock for `/export` — prevents two parallel `/export` dialogs in the same DM from hijacking each other's waiter replies |
| ActiveExportRegistry | `telegram/bot/handler/export/` | Tracks active exports **in execution phase** by synthetic exportId. Dedup: by recordingId for QuickExport, by chatId for `/export` |
| ExportCoroutineScope | `telegram/bot/handler/export/` | Shared scope for export coroutines, gracefully cancelled on `@PreDestroy` |
| QuickExportHandler | `telegram/bot/handler/quickexport/` | Handles callback queries `qe:{recordingId}` and `qea:{recordingId}` |
| CancelExportHandler | `telegram/bot/handler/cancel/` | Handles `xc:{exportId}` (cancel) and `np:{exportId}` (noop-ack) |
| NotificationTask.recordingId | `telegram/queue/` | Recording ID embedded in callback data |
| CancellableJob (SAM) | `telegram/service/model/` | Hides AcquiredServer behind an abstraction; published via onJobSubmitted callback |

## Quick Export Flow

1. When sending a notification, `TelegramNotificationSender` adds two inline buttons in one row:
   "Original" and "Annotated" (button labels are localised — i18n source remains Russian by design).
2. Callback data format: `qe:{UUID}` (original) / `qea:{UUID}` (annotated).
3. On button press:
   - Both buttons replaced with a single progress button (e.g. "Merging video…", "Annotating 45%…").
   - Calls `VideoExportService.exportByRecordingId(recordingId, mode=ORIGINAL|ANNOTATED)`.
   - Exports ±1 min from `recordTimestamp` (2 min total).
   - Timeouts: outer 5 min (original), outer 50 min (annotated); inner annotation timeout
     `DETECT_VIDEO_VISUALIZE_TIMEOUT` defaults to 45 min.
   - On inner annotation timeout a dedicated message `quickexport.error.annotation.timeout` is
     shown (not the generic one).
   - Video is sent to the chat.
   - Two buttons restored.

Only the owner and active users can use Quick Export — enforced by the bot router's
`AuthorizationFilter` (see `telegram.md`).

## Cancellation

Both QuickExport and `/export` support user-initiated cancellation.

Callback payload formats:
- `xc:{exportId}` — cancel: triggers registry `ACTIVE → CANCELLING` transition, cancels the
  coroutine (`Job.cancel()`), and if the vision server already has an active annotation job,
  `DetectService.cancelJob(server, jobId)` is fire-and-forget-posted to `POST /jobs/{id}/cancel`.
- `np:{exportId}` — no-op / "ack spinner" for the progress button shown alongside the cancel
  button (ktgbotapi requires every inline button to have either a URL or callback data). The
  handler silently acks the callback so the Telegram client stops the inline spinner without any
  UI change.

## Lock Ordering Invariant (Tracker + Registry)

`ActiveExportTracker` and `ActiveExportRegistry` both contain short-lived locks. To prevent any
future refactor from introducing a dual-lock deadlock, the following ordering must hold:

1. **Acquire `ActiveExportTracker.tryAcquire(chatId)` BEFORE any `ActiveExportRegistry.*` call.**
2. **Never call `ActiveExportRegistry.*` methods while holding `ActiveExportTracker`'s internal
   lock** (today the tracker's lock scope covers only `tryAcquire`/`release` — keep it that way).
3. Inside `ActiveExportRegistry`: `startLock` is held briefly by `tryStart*`;
   `synchronized(entry)` is held briefly inside `markCancelling`/`release` secondary cleanup.
   Neither acquires the other.
4. `ActiveExportRegistry.release()` must remove from `byExportId` BEFORE taking
   `synchronized(entry)` — see design for the rationale (avoids reverse-order deadlock with
   `markCancelling`).

## Known Limitations

- **ffmpeg cancellation is best-effort.** On MERGING/COMPRESSING stages `CancellationException`
  waits for `VideoMergeHelper.process.waitFor(...)` to return before unwinding. UI shows a
  "Cancelling…" state immediately but the final "Cancelled" state appears only after ffmpeg
  finishes (seconds for merge, up to minutes for compress). Full sync cancel would need a
  cancellation-aware ffmpeg wrapper (out of scope).
- **Restart wipes registry.** Old cancel buttons respond with the "export already finished or
  unavailable" i18n message. Vision-server jobs orphaned on restart are killed by the server's
  TTL.
- **Legacy Telegram notifications.** Pre-deploy messages with `qe:/qea:` buttons don't have
  inline cancel — start a new export, which opens a fresh status message with the cancel button.
