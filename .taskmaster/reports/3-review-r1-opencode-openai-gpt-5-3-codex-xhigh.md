Reviewer: opencode / openai/gpt-5.3-codex / xhigh
Agent: opencode
Model: openai/gpt-5.3-codex

### Strengths
- Реализация точно соответствует ТЗ: inline-клавиатура добавлена во все 3 ветки `send()` (`frames.isEmpty()`, `frames.size == 1`, `else`) в `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/TelegramNotificationSender.kt`.
- Вынесен отдельный приватный метод `createExportKeyboard(recordingId: UUID)` — это убирает дублирование и упрощает поддержку.
- Для ветки медиагруппы корректно выбран отдельный `SendTextMessage` после `sendMediaGroup`, что учитывает ограничение Telegram API на inline-кнопки в media group.
- Добавлены целевые тесты для всех трех сценариев в `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/queue/TelegramNotificationSenderTest.kt`, включая проверку текста кнопки и callback data.
- Проверка выполнения: `:frigate-analyzer-telegram:test` проходит успешно (28 тестов).

### Issues
- Существенных замечаний не найдено.

### Verdict
APPROVE

Изменения соответствуют заявленным требованиям без scope creep и не нарушают текущую архитектуру модуля. Покрытие тестами достаточное для этой задачи, регрессий по измененному поведению не выявлено.