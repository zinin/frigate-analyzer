## Fixes Applied

All 5 code review issues addressed:

### [IMPORTANT] CALLBACK_PREFIX visibility (TelegramNotificationSender.kt:94)
- Changed `private const val CALLBACK_PREFIX` to `internal const val CALLBACK_PREFIX` so the upcoming `QuickExportHandler` can reference it directly instead of duplicating the `"qe:"` string.

### [MINOR] Reflection fragility (TelegramNotificationSenderTest.kt:55-68)
- Wrapped the entire reflection block in `extractReplyMarkup` with a try-catch that throws `AssertionError` with a clear diagnostic message mentioning tgbotapi version changes as the likely cause.

### [MINOR] `innerData!!` NPE risk (TelegramNotificationSenderTest.kt:66)
- Replaced `innerData!!` with `dataMethod.invoke(request) ?: error("getData() returned null for ${request::class}")` for an informative error message instead of a bare NPE.

### [MINOR] `assert()` vs JUnit assertions (TelegramNotificationSenderTest.kt:124,153)
- Replaced `assert(...)` with `assertTrue(...)` from `kotlin.test` on both lines, ensuring assertions always execute regardless of JVM `-ea` flag.

### [MINOR] Test string duplication (TelegramNotificationSenderTest.kt:43-44)
- Updated callback data assertion to reference `TelegramNotificationSender.CALLBACK_PREFIX` directly (now possible since it's `internal`). Kept button text `"📹 Экспорт видео"` as a literal — this is a deliberate testing practice that catches accidental constant changes.

### Verification
- Full build with all tests passes (30 telegram tests, 71 core tests). Ktlint clean.