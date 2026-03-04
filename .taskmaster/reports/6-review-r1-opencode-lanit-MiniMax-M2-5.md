Reviewer: opencode / lanit/MiniMax-M2.5
Agent: opencode
Model: lanit/MiniMax-M2.5

### Strengths
- Чистая, хорошо структурированная реализация с использованием стандартных паттернов проекта
- Компонент с `@ConditionalOnProperty` для корректного включения/отключения
- Хорошее покрытие тестами (24 теста): парсинг UUID, создание клавиатур, обработка ошибок, таймауты
- Правильная обработка CancellationException - не перехватывается
- Надёжная очистка временных файлов в finally блоке

### Issues

**[CRITICAL] modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandler.kt — Компонент не интегрирован в бота**
QuickExportHandler создан и протестирован, но **нигде не вызывается**. В FrigateAnalyzerBot.registerRoutes() нет обработки callback queries (onDataCallbackQuery). При нажатии кнопки "📹 Экспорт видео" с callback data `qe:{recordingId}` ничего не произойдёт.
Нужно добавить регистрацию обработчика в FrigateAnalyzerBot, аналогично командам:
```kotlin
onDataCallbackQuery { callback ->
    if (callback.data?.startsWith(QuickExportHandler.CALLBACK_PREFIX) == true) {
        quickExportHandler.handle(callback)
    }
}
```

**[IMPORTANT] modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandlerTest.kt:33 — ktlint error: неверный порядок импортов**
Импорт `kotlinx.coroutines.CancellationException` (строка 33) должен быть между строками 24 и 25 (после `kotlinx.coroutines.test.runTest`, перед `org.junit.jupiter.api.Nested`), т.к. `kotlin.coroutines` < `kotlinx.coroutines` в лексикографическом порядке.
Запустить `./gradlew ktlintFormat` для автоматического исправления.

**[MINOR] modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandler.kt:90-92 — Кнопка не восстанавливается при таймауте отправки видео**
При таймауте отправки видео (`sent == null`) кнопка НЕ восстанавливается. Код восстановления (строка 102) находится после блока try-finally, но не вызывается в случае `sent == null`. Следует добавить `restoreButton` в блок `if (sent == null)`.

### Verdict
**REQUEST_CHANGES**

Реализация функционально корректна и хорошо протестирована, однако **не работает** в боевом режиме из-за отсутствия интеграции с Telegram ботом. Компонент никогда не будет вызван при нажатии кнопки. Также требуется исправление ktlint ошибки для успешного билда.