Reviewer: claude / sonnet / high
Agent: claude
Model: sonnet

### Strengths

- **Clean delegation pattern.** `exportByRecordingId` correctly follows single-responsibility: resolve the recording, validate nullable fields, compute the time range, delegate export mechanics to `exportVideo`. No business logic is duplicated.
- **Precise validation with informative messages.** Both null checks include `recordingId` in the exception message (`VideoExportServiceImpl.kt:153-161`), essential for diagnosing production issues without extra DB queries.
- **Correct exception type selection.** `IllegalArgumentException` for "recording not found" (bad caller input) and `IllegalStateException` for null `camId`/`recordTimestamp` (data integrity) is semantically correct and matches the interface KDoc.
- **Explicit `mode = ExportMode.ORIGINAL`.** Making the default explicit removes ambiguity and protects against accidental default changes.
- **Sound test structure.** 6 tests, each testing a single concern, no cross-test side effects. The `recordingWithTimestamp()` factory with defaulted parameters is clean within its scope.
- **Complete KDoc.** The interface documentation (`VideoExportService.kt:27-40`) covers all parameters, return value, and thrown exceptions, in English.
- **Full build passes.** 109 tests across all 5 modules pass with no ktlint errors.

---

### Issues

**[IMPORTANT] VideoExportServiceImplTest.kt:517-546 — Happy path test doesn't verify ExportMode.ORIGINAL**

The test is named `exportByRecordingId calls exportVideo with correct range and ORIGINAL mode` but never verifies that `ExportMode.ORIGINAL` was used. It only checks that `findByCamIdAndInstantRange` was called with the correct range. If someone changed the implementation to use `ExportMode.ANNOTATED`, this test would still pass.

Since `exportVideo` is a method on the same class (not a mock), the indirect way to verify ORIGINAL mode is: assert `videoVisualizationService.annotateVideo` was *never* called — the existing test `export original does not call VideoVisualizationService` at line ~431 already demonstrates this pattern.

Suggested fix:
```kotlin
coVerify(exactly = 0) { videoVisualizationService.annotateVideo(any()) }
```
Add this assertion to the happy path test, or add a dedicated test for it.

---

**[MINOR] VideoExportServiceImplTest.kt:492-515 — Growing class-level state and naming collision**

New fields `recordingId`, `recordTimestamp`, `exportDuration` are added at class level alongside the existing `start`, `end`, `camId`. The field `recordTimestamp` at class level forces the `recordingWithTimestamp()` factory to use the `this.recordTimestamp` disambiguation (line ~509), which is a code smell. The test class's shared state is growing uncontrollably.

Suggestion: Extract the `exportByRecordingId` tests into a separate inner class or file (`VideoExportServiceImplExportByRecordingIdTest.kt`) to keep concerns isolated.

---

**[MINOR] VideoExportServiceImpl.kt:149,166 — Info-level log for parameter echo**

The `logger.info` line logs `camId` and the computed range. The peer method `exportVideo` only elevates to `info` when it has production-meaningful context (file count at line ~71). The range log here duplicates what the caller already knows. Consider `logger.debug` for this line; reserve `info` for confirmed meaningful events.

---

**[MINOR] VideoExportServiceImplTest.kt:129-145 vs 496-515 — Duplicated entity factory**

`recording()` and `recordingWithTimestamp()` are nearly identical `RecordingEntity` builders. They could be unified into a single factory with optional `id`, `camId`, and `recordTimestamp` parameters, eliminating the duplication. Minor DRY concern, no correctness impact.

---

**[MINOR] VideoExportServiceImpl.kt — No validation for zero/negative Duration**

A negative `Duration` (e.g., `Duration.ofSeconds(-60)`) would produce `startInstant > endInstant`, causing `exportVideo` to fail with a generic "No recordings found" rather than a meaningful validation error. Given the default value of 1 minute this is unlikely in practice, but if the method is called from user-facing endpoints a guard would improve error clarity:
```kotlin
require(duration > Duration.ZERO) { "Duration must be positive, got: $duration" }
```

---

### Verdict

APPROVE_WITH_NOTES

The implementation is correct, complete, and faithful to the specification. The one issue worth addressing is the misleading test name for the happy path (IMPORTANT): the test claims to verify `ORIGINAL` mode but doesn't. All other issues are minor style or robustness improvements that can be addressed in follow-up work without blocking this change.