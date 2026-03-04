Reviewer: opencode / lanit/MiniMax-M2.5
Agent: opencode
Model: lanit/MiniMax-M2.5

### Strengths
- Импорт `QuickExportHandler` добавлен в алфавитном порядке (строка 14)
- Все три места вызова используют `QuickExportHandler.createExportKeyboard(task.recordingId)`:
  - строка 41 (text-only notification)
  - строка 54 (single photo notification)
  - строка 78 (media group follow-up button)
- Нет локального метода `createExportKeyboard` — дублирование отсутствует
- Единый формат callback data: `"qe:"` определён только в константе `CALLBACK_PREFIX` в `QuickExportHandler.kt:145`
- `NotificationTask` имеет поле `recordingId` (строка 13 в NotificationTask.kt)

### Issues

### Verdict
**APPROVE**

Рефакторинг соответствует требованиям: TelegramNotificationSender использует QuickExportHandler.createExportKeyboard как единый источник истины. Код чистый, без дублирования. Проблема сборки (Java toolchain) — вопрос окружения, не связан с качеством кода.