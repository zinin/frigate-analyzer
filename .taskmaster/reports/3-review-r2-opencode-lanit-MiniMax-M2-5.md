Reviewer: opencode / lanit/MiniMax-M2.5
Agent: opencode
Model: lanit/MiniMax-M2.5

### Strengths
- Все требования задачи выполнены корректно: добавлены импорты, создан метод `createExportKeyboard`, добавлен `replyMarkup` во все три ветки (empty frames, single frame, media group)
- Для медиагруппы используется отдельное сообщение с кнопкой — правильное решение, т.к. Telegram API не поддерживает inline клавиатуры в media groups
- Константы вынесены в companion object (EXPORT_BUTTON_TEXT, EXPORT_PROMPT_TEXT, CALLBACK_PREFIX) — улучшение над спецификацией
- Тесты покрывают все три сценария, включая edge case с 20 кадрами (2 чанка по 10)
- Код компилируется, проходит ktlint, все 28 тестов проходят

### Issues

**[MINOR] modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/TelegramNotificationSender.kt:107 — ktlint multiline expression formatting**
Исправлено в ходе ревью: изменён формат `keyboard = matrix {` на `keyboard =` с переносом `matrix {` на новую строку для соответствия стилю проекта (см. TimezoneCommandHandler.kt:49-51).

### Verdict
APPROVE

Реализация полностью соответствует требованиям задачи, использует правильные API Telegram Bot, покрыта тестами и следует конвенциям проекта.