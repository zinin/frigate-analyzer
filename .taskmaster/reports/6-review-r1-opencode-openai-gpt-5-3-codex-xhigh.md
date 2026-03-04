Reviewer: opencode / openai/gpt-5.3-codex / xhigh
Agent: opencode
Model: openai/gpt-5.3-codex

### Strengths
- Выделен отдельный компонент `QuickExportHandler` с понятной ответственностью: парсинг callback data, управление состоянием кнопки, экспорт/отправка/cleanup.
- Хорошо обработаны таймауты и завершение корутин: используется `withTimeoutOrNull`, `CancellationException` корректно пробрасывается, очистка файла выполняется в `finally`.
- Покрытие юнит-тестами для самого `QuickExportHandler` получилось широким: проверены happy-path, таймауты, несколько веток ошибок и восстановление кнопки.

### Issues

**[IMPORTANT] modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBot.kt:93 — Нет маршрута для callback query `qe:`**
`registerRoutes()` обрабатывает только `onCommand` и `onContentMessage`; обработчика callback query нет, поэтому `QuickExportHandler` фактически не вызывается в рантайме, и кнопка быстрого экспорта не будет работать в проде.  
Suggested fix: заинжектить `QuickExportHandler` в `FrigateAnalyzerBot` и добавить `onDataCallbackQuery` (с фильтром по `QuickExportHandler.CALLBACK_PREFIX`) с делегированием в `quickExportHandler.handle(...)` и безопасной обработкой исключений.

**[IMPORTANT] modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandler.kt:50 — Авторизация проверяет только наличие username**
Сейчас проверяется только `callback.user.username != null`, но не факт, что пользователь активен/разрешен. Это обходит модель авторизации, используемую в командных хендлерах (`AuthorizationFilter.getRole(...)`), и позволяет, например, деактивированным пользователям запускать экспорт по старым сообщениям с кнопкой.  
Suggested fix: перед экспортом валидировать роль/статус пользователя (owner или active user) через `AuthorizationFilter`/`TelegramUserService`; при `null` возвращать unauthorized-ответ.

**[MINOR] modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandler.kt:138 — Парсер не требует префикс `qe:`**
`removePrefix` принимает и данные без префикса, если строка сама по себе валидный UUID. Это повышает риск ложного срабатывания при пересечении форматов callback data в будущем.  
Suggested fix: добавить явную проверку `startsWith(CALLBACK_PREFIX)` и возвращать `null`, если префикс не совпадает; дополнить тестом на bare UUID без префикса.

### Verdict
REQUEST_CHANGES  
Основной функциональный блок реализован аккуратно, но в текущем виде кнопка быстрого экспорта не подключена к маршрутизации callback query, а проверка авторизации недостаточна для прод-сценария.