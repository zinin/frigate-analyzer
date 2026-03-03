## Fixes Applied

All 6 MINOR review issues addressed:

1. **Log level `debug` vs `info` discrepancy** — **Skipped (intentional).** The `debug` level is consistent with the sibling `exportVideo` method's entry-trace pattern and was already accepted in a prior commit (`9cc34e6`). Changing to `info` would break consistency.

2. **Unused progress collection in happy path test** — **Fixed.** Removed the `val progress = mutableListOf<>()` and simplified to use `onProgress = {}` (implicitly via default parameter). Also renamed the test from `"…correct range and ORIGINAL mode"` to `"…correct range and no annotation"` to better reflect what is actually asserted (addresses issue #4).

3. **No validation of negative duration** — **Fixed.** Added `require(!duration.isNegative) { "duration must be non-negative" }` at method entry in `VideoExportServiceImpl`. Added a corresponding test. Updated interface KDoc `@throws` annotation.

4. **Test implicitly verifies ORIGINAL mode** — **Fixed.** Renamed the test to `"…correct range and no annotation"` to accurately reflect the actual assertions (verifies annotateVideo is never called, not that mode = ORIGINAL is passed).

5. **Test fixtures placed mid-class** — **Fixed.** Moved `recordingId`, `recordTimestamp`, `exportDuration` declarations and the `recordingWithTimestamp()` helper to the top of the class alongside existing fixtures (near line 63).

6. **Duplicate happy-path setup** — **Fixed.** Extracted `stubExportByRecordingIdHappyPath()` helper method that encapsulates the common `coEvery` stubs. All three tests that had duplicate setup (happy-path, custom-duration, progress-propagation) now use this helper.

**Build:** All tests pass (110+ tests across 5 modules), no ktlint violations.