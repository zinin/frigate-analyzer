Reviewer: opencode / zai-coding-plan/glm-4.7
Agent: opencode
Model: zai-coding-plan/glm-4.7

### Strengths
- Правильно добавлена зависимость TelegramUserService в конструктор и тесты
- Извлечение username с null check и понятным сообщением об ошибке для пользователя без username
- Четко разделены проверки: сначала username null, затем авторизация (owner/active user)
- Правильная реализация owner check через properties.owner (short-circuits auth)
- Правильная реализация active user check через userService.findActiveByUsername()
- Добавлено логирование неавторизованных попыток на уровне WARN (QuickExportHandler.kt:64)
- Тесты покрывают все сценарии: без username, неавторизованный, owner, активный пользователь
- Удалён неиспользуемый импорт UserRole в тестах
- Все 57 тестов проходят (согласно отчётам агентов)

### Issues

**[MINOR] QuickExportHandler.kt:34,65 — Ненужная зависимость от AuthorizationFilter**
В конструкторе оставлена зависимость `authorizationFilter: AuthorizationFilter`, которая используется только для вызова `getUnauthorizedMessage()` на строке 65. По требованиям задачи нужно использовать `properties.unauthorizedMessage` напрямую. Сейчас AuthorizationFilter.getUnauthorizedMessage() всё равно возвращает properties.unauthorizedMessage (AuthorizationFilter.kt:49), но это создаёт ненужную зависимость только ради одного метода.

Fix: Заменить строку 65 на `bot.answer(callback, properties.unauthorizedMessage)` и удалить параметр `authorizationFilter` из конструктора, а также импорт `ru.zinin.frigate.analyzer.telegram.filter.AuthorizationFilter`. Обновить тесты, убрав mock для authorizationFilter.

### Verdict

APPROVE_WITH_NOTES

Реализация соответствует требованиям безопасности: проверка авторизации работает корректно (owner или active user), все сценарии протестированы. Единственная проблема — неоправданная зависимость от AuthorizationFilter только ради одного метода getUnauthorizedMessage(), хотя свойства TelegramProperties уже доступны в классе. Это легко исправить, но на функциональность не влияет.