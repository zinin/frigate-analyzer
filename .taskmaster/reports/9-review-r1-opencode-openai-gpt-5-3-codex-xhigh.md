Reviewer: opencode / openai/gpt-5.3-codex / xhigh
Agent: opencode
Model: openai/gpt-5.3-codex

### Strengths
- В `QuickExportHandler` добавлена явная обработка пользователя без `username` с ранним выходом и понятным сообщением, что улучшает UX и убирает риск неявных ошибок.
- Авторизация переведена на схему `owner` или `active user` через `TelegramUserService`, как требовалось по задаче.
- Добавлено логирование неавторизованных попыток quick export, что полезно для аудита безопасности.
- Тесты расширены на ключевые сценарии (без `username`, unauthorized, owner, active user), плюс проверяется отсутствие вызова экспорта для неавторизованных.

### Issues

**[IMPORTANT] `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandler.kt:61` — Проверка owner не short-circuit и зависит от БД**  
`userService.findActiveByUsername(username)` вызывается всегда, даже если пользователь уже распознан как owner. Это неэквивалентно `AuthorizationFilter.getRole()` и создает риск: при деградации/ошибке БД owner может получить исключение до принятия решения об авторизации и потерять доступ к quick export.  
Suggested fix: вызывать `findActiveByUsername` только при `!isOwner` (или переписать блок в `when` по аналогии с `AuthorizationFilter.getRole()`).

**[MINOR] `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandlerTest.kt:296` — Тест для отсутствующего username не проверяет текст ответа**  
Тест `handle rejects user without username with set username message` проверяет только факт вызова `bot.execute(...)`, но не проверяет конкретный текст. В результате регрессия в пользовательском сообщении не будет поймана.  
Suggested fix: захватить `AnswerCallbackQuery` и проверить точное значение `text` (`"Пожалуйста, установите username в настройках Telegram."`).

### Verdict

REQUEST_CHANGES  
Изменения в целом хорошие и покрывают основную функциональность, но есть важное расхождение с эталонной логикой авторизации: owner не должен зависеть от запроса в БД. После short-circuit для owner и небольшого усиления теста можно апрувить.