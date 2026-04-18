# Export Cancellation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add user-initiated cancellation for QuickExport and `/export` flows via the new vision-server `POST /jobs/{id}/cancel` endpoint.

**Architecture:** A new in-memory `ActiveExportRegistry` tracks active exports by synthetic `exportId` for the **execution phase** (submit → poll → download → send → cancel). The existing `ActiveExportTracker` is **kept** as the **dialog-phase lock** in `/export` — it prevents two parallel `/export` dialogs in the same DM from hijacking each other's waiter replies (registry has no hook into the dialog phase because `exportId` is generated only after dialog completion). A new `CancelExportHandler` processes `xc:{exportId}` callbacks — it atomically transitions the registry entry to CANCELLING, updates the Telegram keyboard, cancels the coroutine, and fire-and-forget-cancels the vision-server job via a new `CancellableJob` SAM plumbed through `VideoExportService` → `VideoVisualizationService`.

**Tech Stack:** Kotlin 2.3.10, Spring Boot 4.0.3, WebFlux, Kotlin Coroutines, ktgbotapi, JUnit 5, kotlin.test, mockk, mockwebserver3.

**Spec:** `docs/superpowers/specs/2026-04-17-export-cancel-design.md` (commit `c195617`).

---

## File Structure

### New files

| Path | Responsibility |
|---|---|
| `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/model/CancellableJob.kt` | SAM: abstraction over "cancel the running vision-server annotation job". Hides `AcquiredServer` inside core-module. |
| `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ActiveExportRegistry.kt` | In-memory registry of active exports, keyed by `exportId`. Atomic dedup per recordingId (QuickExport) and per chatId (/export). Atomic CAS for `ACTIVE → CANCELLING`. |
| `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ExportCoroutineScope.kt` | Shared `CoroutineScope` bean used by `QuickExportHandler`, `ExportExecutor`, and `CancelExportHandler`. `@PreDestroy`-cancelled. |
| `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/cancel/CancelExportHandler.kt` | Processes `xc:{exportId}` (real cancel) and `np:{exportId}` (silent noop-ack for progress-button taps). |
| `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ActiveExportRegistryTest.kt` | Tests for registry. |
| `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/cancel/CancelExportHandlerTest.kt` | Tests for cancel handler. |

### Modified files

| Path | Change |
|---|---|
| `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/config/properties/DetectProperties.kt` | Add `VideoVisualizeConfig.cancelTimeout: Duration = 10s`. |
| `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/DetectService.kt` | Add `suspend fun cancelJob(server: AcquiredServer, jobId: String)`. |
| `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/VideoVisualizationService.kt` | Add `onJobSubmitted: suspend (CancellableJob) -> Unit = {}` parameter, invoke inside `withContext(NonCancellable)` post-submit. |
| `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/service/VideoExportServiceImpl.kt` | Add `onJobSubmitted` param; plumb into `annotate(...)` only for ANNOTATED mode. |
| `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/VideoExportService.kt` | Add `onJobSubmitted: suspend (CancellableJob) -> Unit = {}` to both methods. |
| `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandler.kt` | Remove local `activeExports` set; use `ActiveExportRegistry`; launch job with `CoroutineStart.LAZY`; add cancel keyboard (two rows: progress + cancel); `np:` noop callback on progress button; cancellation UI branch in catch; constructor takes shared `ExportCoroutineScope` bean. |
| `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ExportExecutor.kt` | Wrap body in `exportScope.launch(start = LAZY)` with registry.tryStart; fire-and-forget `job.start()` (NO `job.join()` — would collapse two-tier lock model); add `[✖ Отмена]` keyboard to status message; cancellation UI branch; plumb `onJobSubmitted` → `registry.attachCancellable`. |
| `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/export/ExportCommandHandler.kt` | **Keep** `ActiveExportTracker` usage for dialog-phase lock; remove private `exportScope` (executor owns its own via `ExportCoroutineScope` bean). |
| `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBot.kt` | Add second `onDataCallbackQuery` route for prefixes `xc:` and `np:` → `CancelExportHandler.handle(callback)`. |
| `modules/telegram/src/main/resources/messages_ru.properties` | New keys (cancel.*, quickexport.button.cancel, quickexport.progress.cancelling, quickexport.cancelled, export.button.cancel, export.progress.cancelling, export.cancelled.by.user — see Task 6). |
| `modules/telegram/src/main/resources/messages_en.properties` | Same new keys in English. |
| `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/testsupport/DetectServiceDispatcher.kt` | Add handling for `POST /jobs/{id}/cancel` → 200 with status response. |
| `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/DetectServiceTest.kt` | If does not exist, create; add tests for `cancelJob`. |
| `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/service/VideoVisualizationServiceTest.kt` | Add tests for `onJobSubmitted` callback invocation. |
| `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandlerTest.kt` | Update mocks to 5-arg `exportByRecordingId` signature; add scenarios for cancel keyboard and cancellation path. |
| `.claude/rules/telegram.md` | Add "Cancellation" subsection in Quick Export. |
| `.claude/rules/configuration.md` | Add `DETECT_VIDEO_VISUALIZE_CANCEL_TIMEOUT`. |

### Deleted files

None. (Earlier plan iteration had `ActiveExportTracker` deletion; after review-iter-1 it is kept for `/export` dialog-phase lock.)

---

### Task 1: `CancellableJob` SAM + `DetectProperties.cancelTimeout`
✅ Done — see commit: `5772f0e`

---

### Task 2: `DetectService.cancelJob`
✅ Done — see commits: `423fa27`, `90691c4` (Russian→English comment follow-up)

---

### Task 3: `VideoVisualizationService.annotateVideo` — add `onJobSubmitted`
✅ Done — see commit: `4bd19e1`

---

### Task 4: `VideoExportService` — plumb `onJobSubmitted`
✅ Done — see commits: `d436982`, `d3eb951` (@TempDir + named coVerify polish)

---

### Task 5: `ActiveExportRegistry` (new, with tests)
✅ Done — see commits: `15ad1b2`, `2276b36` (future-refactor hardening: `data class` → `class`, startLock doc, DO NOT REORDER comment)

---

### Task 6: `ExportCoroutineScope` bean + i18n keys
✅ Done — see commit: `f52e7f7`

---

### Task 7: `CancelExportHandler` (new, with tests)
✅ Done — see commit: `a6e3f68`

---

### Task 8: `FrigateAnalyzerBot` — route `xc:` and `np:` callbacks
✅ Done — see commit: `8c09ab2`

---

### Task 9: Migrate `QuickExportHandler` to registry + cancel UI
✅ Done — see commit: `0e0eb62` (also made `ExportCoroutineScope` `open` with `internal constructor(delegate)` for test injection — production-code deviation documented in commit body)

---

### Task 10: Migrate `ExportExecutor` + `ExportCommandHandler`
✅ Done — see commit: `9d65fb2`

---

### Task 11: ~~Delete `ActiveExportTracker`~~ — SKIPPED

After review iteration 1 (2026-04-17), this task is **removed** from the plan. The tracker is retained for `/export` dialog-phase locking per iter-1 CRITICAL-1.

---

### Task 12: Update `.claude/rules` documentation
✅ Done — see commit: `45d7537`

---

### Task 13: Full build + ktlint
✅ Done — `./gradlew ktlintFormat build` → BUILD SUCCESSFUL in 1m 9s, 210+ tests passed, ktlint clean. No follow-up commit needed (no ktlint auto-fixes applied).

**Not yet done (out-of-band):**
- Manual e2e smoke: launch QuickExport ANNOTATED, click ✖ Отмена during ANNOTATING, verify vision-server `POST /jobs/{id}/cancel` → 200 + job → cancelled. Repeat for `/export`.
- Before PR: `git rm docs/superpowers/specs/2026-04-17-export-cancel-design.md docs/superpowers/plans/2026-04-17-export-cancel.md` + commit, per user's global `CLAUDE.md`.

---

## Self-Review

**1. Spec coverage:**

| Spec section | Covered by task(s) |
|---|---|
| 2.1 ActiveExportRegistry | Task 5 |
| 2.1 CancelExportHandler | Task 7 |
| 2.1 CancellableJob SAM | Task 1 |
| 2.1 ExportCoroutineScope | Task 6 |
| 2.1 DetectService.cancelJob | Task 2 |
| 2.2 Delete ActiveExportTracker | Task 11 — SKIPPED (tracker retained for `/export` dialog-phase lock per iter-1 CRITICAL-1) |
| 2.3 annotateVideo onJobSubmitted + NonCancellable | Task 3 |
| 2.3 VideoExportService plumbing | Task 4 |
| 2.4 FrigateAnalyzerBot routing | Task 8 |
| 3.1 Export launch skeleton | Task 9 (QuickExport), Task 10 (/export) |
| 3.2 Cancel data flow | Task 7 |
| 3.3 UI by phases (QuickExport) | Task 9 |
| 3.3 UI by phases (/export) | Task 10 |
| 4. State Machine (ACTIVE/CANCELLING) | Task 5 (registry CAS), Task 7 (handler), Tasks 9/10 (finally branch) |
| 5.1 DetectService.cancelJob error handling | Task 2 |
| 5.2 Final UI branching on CancellationException | Tasks 9, 10 |
| 5.3 Edge cases | Tasks 5, 7, 9, 10 (various) |
| 6. Configuration (cancelTimeout) | Task 1 |
| 7. i18n keys | Task 6 |
| 8. Testing | Tasks 2, 3, 4, 5, 7, 9 |
| 9. Observability logs | Tasks 2, 7 |
| 10. Docs updates | Task 12 |

All spec sections have tasks.

**2. Placeholder scan:** No "TBD", "TODO", "implement later", or vague steps. Task 9 Step 3 — both cancellation tests (keyboard-shape unit + end-to-end cancel flow) are **mandatory** per iter-1 TEST-2 — no "optional/flexible" language remains.

**3. Type consistency:**
- `CancellableJob` — one declaration site (Task 1), consistent `suspend fun cancel()` across all uses.
- `ActiveExportRegistry.StartResult.Success|DuplicateRecording|DuplicateChat` — used consistently in Tasks 5, 7, 9, 10.
- `ActiveExportRegistry.Entry.state` (`ACTIVE|CANCELLING`) — consistent.
- `CancelExportHandler.CANCEL_PREFIX = "xc:"` and `NOOP_PREFIX = "np:"` — used in Tasks 7, 8, 9, 10, 12.
- `onJobSubmitted: suspend (CancellableJob) -> Unit = {}` — identical signature in `VideoVisualizationService.annotateVideo`, `VideoExportService.exportVideo` / `exportByRecordingId`.

**4. No methods/types referenced without being defined:** all types defined in Tasks 1 and 5 before first use. `ExportCoroutineScope` defined in Task 6 before first use in Task 7. `CancelExportHandler` defined in Task 7 before first use in Task 8.

**5. Lock Ordering Invariant:** Tracker acquired before Registry (ExportCommandHandler `finally { tracker.release }` wraps `exportExecutor.execute(...)` which fires-and-forgets the LAZY job — tracker held only for dialog phase). Registry's internal locks: `release()` removes from `byExportId` BEFORE taking `synchronized(entry)` to avoid reverse-order deadlock with `markCancelling` (which holds CHM bucket lock inside `computeIfPresent` and takes entry monitor inside the lambda). Documented in Task 12 Step 1 for `.claude/rules/telegram.md`.

**6. i18n key consistency:** File Structure row lists `export.cancelled.by.user` (not `export.cancelled.action`). Task 6 creates it; design §7 references it. No asymmetry with `quickexport.cancelled`: the legacy `export.cancelled` key is owned by the dialog-cancel-before-start path, so the new key needs the `.by.user` suffix to disambiguate.

No gaps found.
