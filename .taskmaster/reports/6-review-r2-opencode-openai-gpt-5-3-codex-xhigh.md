Reviewer: opencode / openai/gpt-5.3-codex / xhigh
Agent: opencode
Model: openai/gpt-5.3-codex

### Strengths
- `QuickExportHandler` реализован аккуратно по структуре: есть таймауты, cleanup в `finally`, отдельная обработка `CancellationException`, и восстановление кнопки после большинства сценариев (`modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandler.kt`).
- Пользовательский UX продуман: callback подтверждается сразу, есть сообщения для timeout/error, и состояние кнопки переключается на processing и обратно.
- Добавлен хороший набор unit-тестов на клавиатуры, таймауты, ошибки и отмену корутин (`modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandlerTest.kt`).

### Issues

**[IMPORTANT] modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandler.kt:49 — Проверка авторизации фактически отсутствует**
Сейчас проверяется только наличие `username`, но не проверяется роль/активность пользователя. Это позволяет любому пользователю с username пройти в экспорт, если он получил callback, что нарушает ожидаемую модель доступа.
Suggested fix: использовать `authorizationFilter.getRole(username)` (или эквивалентную проверку owner/active user) и отклонять запрос при `null`.

**[IMPORTANT] modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBot.kt:93 — QuickExportHandler не подключен к маршрутизации callback query**
В `registerRoutes()` есть только `onCommand` и `onContentMessage`; обработчика `onDataCallbackQuery` для `qe:` нет. В результате кнопка быстрого экспорта не вызывает `QuickExportHandler` в runtime, и функциональность задачи не работает end-to-end.
Suggested fix: внедрить `QuickExportHandler` в `FrigateAnalyzerBot` и добавить `onDataCallbackQuery(initialFilter = { it.data.startsWith(QuickExportHandler.CALLBACK_PREFIX) })` с делегированием в `quickExportHandler.handle(callback)`.

**[MINOR] modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandler.kt:137 — parseRecordingId принимает UUID даже без префикса `qe:`**
`removePrefix` не валидирует наличие префикса; если придет “голый” UUID, парсинг пройдет успешно. Это ослабляет контракт формата callback data.
Suggested fix: сначала явно проверять `callbackData.startsWith(CALLBACK_PREFIX)`, и только потом парсить UUID.

### Verdict
REQUEST_CHANGES  
Базовая реализация сильная, но есть два важных блокера: отсутствие фактической авторизационной проверки и отсутствие подключения обработчика к callback routing. До исправления этих пунктов решение не готово к продакшену.