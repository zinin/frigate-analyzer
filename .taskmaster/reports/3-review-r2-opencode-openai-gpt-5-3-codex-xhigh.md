Reviewer: opencode / openai/gpt-5.3-codex / xhigh
Agent: opencode
Model: openai/gpt-5.3-codex

### Strengths
- Требования задачи покрыты полностью: `replyMarkup` добавлен во все 3 ветки `send()` (`frames.isEmpty()`, `frames.size == 1`, `else`) в `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/TelegramNotificationSender.kt`.
- Реализация клавиатуры аккуратная: выделен отдельный метод `createExportKeyboard(...)` с корректным форматом callback data `qe:{UUID}`.
- Для ветки медиагруппы выбран правильный Telegram-совместимый подход: отдельное сообщение с кнопкой после `sendMediaGroup`.
- Добавлены тесты на ключевые сценарии (0/1/>1 кадров) с проверкой и текста кнопки, и callback data, что хорошо фиксирует контракт поведения.

### Issues

**[MINOR] modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/TelegramNotificationSender.kt:81 — Захардкоженные UI/callback строки**
Тексты кнопки/подсказки и префикс callback (`"📹 Экспорт видео"`, `"👆 Нажмите..."`, `"qe:"`) зашиты строковыми литералами прямо в коде отправки. Это не ломает функциональность, но усложняет сопровождение и повышает риск рассинхронизации при дальнейшем развитии quick export flow.
Suggested fix: вынести значения в `companion object` (или общий контракт/константы для sender + handler).

**[MINOR] modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/queue/TelegramNotificationSenderTest.kt:106 — Нет проверки многократного chunking медиагруппы**
Тест для ветки медиагруппы покрывает только случай с 2 кадрами (один chunk) и не проверяет поведение при `frames > MAX_MEDIA_GROUP_SIZE` (несколько `sendMediaGroup` вызовов). Из-за этого регресс в порядке отправки кнопки после всех chunks может пройти незамеченным.
Suggested fix: добавить тест с 11+ кадрами и проверить, что сообщение с кнопкой уходит после последнего chunk.

### Verdict
APPROVE_WITH_NOTES

Функциональные требования задачи выполнены корректно и без блокирующих дефектов; реализация выглядит production-готовой для текущего scope. Замечания носят поддерживающий характер (улучшение сопровождаемости и усиление edge-case покрытия тестов).