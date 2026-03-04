Reviewer: opencode / openai/gpt-5.3-codex / xhigh
Agent: opencode
Model: openai/gpt-5.3-codex

### Strengths
- Логика авторизации стала явной и читаемой: отдельно обработан кейс без `username`, затем разделены проверки `owner` и активного пользователя.
- Добавлено полезное security-логирование неавторизованных попыток (`warn`), что улучшает наблюдаемость инцидентов.
- Тесты обновлены в нужных местах: покрыты сценарии `unauthorized`, `owner`, `active user`, а также ранний выход при отсутствии `username`.

### Issues

**[IMPORTANT] modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandler.kt:61 — Проверка owner все еще зависит от вызова userService**
Сейчас `userService.findActiveByUsername(username)` вызывается даже для владельца. Это расходится с `AuthorizationFilter.getRole()` (owner short-circuit) и добавляет лишнюю зависимость от БД/сервиса для owner-пути. При временной проблеме с `userService` владелец может потерять доступ к quick export, хотя по бизнес-логике должен проходить сразу.
Suggested fix: сделать short-circuit, например `val isActiveUser = !isOwner && userService.findActiveByUsername(username) != null`, и добавить проверку в тесте, что для owner `findActiveByUsername` не вызывается.

**[MINOR] modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandlerTest.kt:305 — Нет проверки текста ответа для пользователя без username**
Тест `handle rejects user without username...` подтверждает, что был любой вызов `bot.execute`, но не проверяет, что отправлено именно требуемое сообщение `"Пожалуйста, установите username в настройках Telegram."`. Это ослабляет защиту от регрессии UX/текста ошибки.
Suggested fix: захватить `AnswerCallbackQuery` и проверить точное значение `text`, как это уже сделано в тесте unauthorized-сценария.

### Verdict
REQUEST_CHANGES

Изменения в целом в правильном направлении и хорошо покрыты тестами, но важный нюанс с owner short-circuit нарушает эквивалентность `AuthorizationFilter.getRole()` и может дать отказ owner при сбое `userService`. Это стоит исправить до мержа.