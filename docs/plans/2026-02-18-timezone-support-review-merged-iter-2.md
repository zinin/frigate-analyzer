# Merged Design Review — Iteration 2

## codex-executor (gpt-5.3-codex)

### [Important] DST-переходы могут ломать корректность диапазона `/export`

**Описание:** В плане диапазон валидируется по `LocalTime` (до конвертации), а затем переводится через `LocalDateTime.atZone(userZone)`. В дни перехода на/с DST это может дать неожиданные `Instant`: несуществующее время сдвигается, двусмысленное время выбирает один из оффсетов. В итоге «5 минут» локально может стать существенно больше (или даже некорректным) в UTC.
**Где:** Design → Data Flow `/export`; Implementation Plan → Task 7, Step 5 (валидация до `toInstant()`).
**Предложение:** После конвертации проверять длительность по `Duration.between(startInstant, endInstant)` и отдельно обрабатывать `ZoneRules.getValidOffsets(localDateTime)` (reject/explicit choice для ambiguous/non-existent local time).

### [Important] Waiter-фильтры callback слишком широкие и не привязаны к конкретному сообщению

**Описание:** Фильтр `it.data.startsWith("export:")`/`startsWith("tz:")` + chatId позволяет поймать callback от старой клавиатуры того же чата. Это приводит к ложным веткам (например, не тот шаг диалога), неожиданным отменам/таймаутам и хрупкому поведению.
**Где:** Implementation Plan → Task 7, Step 4 и Step 5 (`waitDataCallbackQuery().filter { ... startsWith(...) ... }`).
**Предложение:** Привязывать callback к `messageId` конкретного отправленного prompt-сообщения и фильтровать по whitelist допустимых `data` для шага (`today/yesterday/custom/cancel`, и т.д.).

### [Minor] В ветке ручного ввода timezone нет явной отмены и «повтора» в рамках сессии

**Описание:** После выбора «Ввести вручную» пользователь не может корректно отменить шаг (`/cancel` превращается в ошибку timezone), а сообщение «Попробуйте снова» не поддержано циклом ввода.
**Где:** Implementation Plan → Task 7, Step 4 (manual input branch).
**Предложение:** Добавить обработку `/cancel`/`отмена` перед валидацией и цикл повторного ввода (хотя бы 1-2 попытки) до валидного Olson ID или отмены.

### [Minor] В плане есть внутреннее противоречие по типу исключения

**Описание:** В Task 9 указан `ZoneRulesException` как «common issue», что конфликтует с уже принятой логикой ловить `DateTimeException` (более широкий и корректный случай). Это может вернуть регрессию при реализации «по чеклисту».
**Где:** Implementation Plan → Task 9, Step 3 (bullet про `ZoneRulesException`).
**Предложение:** Удалить этот пункт из чеклиста Task 9 и зафиксировать единый стандарт: валидация/обработка через `DateTimeException`.

---

## gemini-executor

### [Important] Синтаксическая ошибка при получении ID чата (Bot)

**Описание:** В коде `FrigateAnalyzerBot.kt` (Task 7) используется конструкция `chatId.chatId.long`. Переменная `chatId` имеет тип `ChatId` (из `ktgbotapi`). У этого интерфейса (или его реализаций) обычно нет свойства `.chatId`. Конструкция `chatId.chatId.long` выглядит как опечатка или использование несуществующего расширения.
**Где:** Task 7, методы `handleTimezone` и `handleExport`.
**Предложение:** Проверить корректный способ извлечения `Long` из `ChatId`. Убедиться, что передается именно `Long`, ожидаемый сервисом `userService`.

### [Minor] Уточнение изменений в TelegramUserServiceImpl

**Описание:** В плане (Task 4) указано "Implement in TelegramUserServiceImpl" для методов `getUserZone`, `updateTimezone`, `getAuthorizedUsersWithZones`. Однако эти методы уже существуют. Формулировка "Add methods" может сбить с толку.
**Где:** Task 4, Step 2.
**Предложение:** Изменить формулировку на "Update implementation of existing methods in TelegramUserServiceImpl".

### [Minor] Размещение DTO UserZoneInfo

**Описание:** В плане (Task 4) предлагается добавить `data class UserZoneInfo` прямо в файл интерфейса сервиса `TelegramUserService.kt`. В проекте уже есть пакет DTO. Размещение DTO в файле сервиса нарушает структуру пакетов.
**Где:** Task 4, Step 1.
**Предложение:** Создать отдельный файл `UserZoneInfo.kt` в пакете DTO, чтобы следовать структуре проекта.

### [Suggestion] Сообщение об ошибке при сжатии видео

**Описание:** В `VideoExportServiceImpl` (Task 6), если сжатый файл все еще слишком велик, выбрасывается `IllegalStateException`. Это исключение всплывет пользователю как общая ошибка бота.
**Где:** Task 6, Step 2.
**Предложение:** Рассмотреть возможность выброса более специфичного исключения или обработки этого случая в боте.

---

## ccs-executor (glmt)

### [Important] Q1: getUserZone — нет try/catch для невалидного olson_code

**Описание:** В существующем коде `TelegramUserServiceImpl.kt:104-107` нет try/catch fallback на UTC. План (Task 4) указывает добавить.
**Где:** `modules/telegram/.../TelegramUserServiceImpl.kt:104-107`
**Примечание:** Это pending code fix из итерации 1 (Fix C), ещё не применённый.

### [Important] Q2: getAuthorizedUsersWithZones — нет try/catch для каждого пользователя

**Описание:** В существующем коде нет per-user try/catch. Один невалидный olson_code уронит всю рассылку.
**Где:** `modules/telegram/.../TelegramUserServiceImpl.kt:119-123`
**Примечание:** Это pending code fix из итерации 1 (Fix C), ещё не применённый.

### [Important] Q3: updateTimezone — нет валидации offset-based zones и проверки affected rows

**Описание:** В существующем коде нет ни `require(olsonCode.contains('/'))`, ни проверки affected rows.
**Где:** `modules/telegram/.../TelegramUserServiceImpl.kt:109-116`
**Примечание:** Это pending code fix из итерации 1 (Fixes G, H), ещё не применённый.

### [Important] Q4: Return type Pair<Long, ZoneId> вместо UserZoneInfo

**Описание:** План указывает UserZoneInfo, но код использует Pair.
**Где:** `modules/telegram/.../service/TelegramUserService.kt:34`
**Примечание:** Это pending code fix из итерации 1 (Fix I), ещё не применённый.

### [Minor] Q5: handleTimezone manual input — нет проверки offset-based zones

**Описание:** В существующем коде нет проверки `contains('/')` при ручном вводе.
**Где:** `modules/telegram/.../bot/FrigateAnalyzerBot.kt:406-410`
**Примечание:** Это pending code fix из итерации 1 (Fix H), ещё не применённый.

### [Important] Q6: Кнопки Сегодня/Вчера используют серверное время

**Описание:** `LocalDate.now(clock)` вместо `Instant.now(clock).atZone(userZone).toLocalDate()`.
**Где:** `modules/telegram/.../bot/FrigateAnalyzerBot.kt:473-478`
**Примечание:** Это pending code fix из итерации 1 (Fix A), ещё не применённый.

### [Minor] Q7: ZoneRulesException вместо DateTimeException

**Описание:** Код ловит `ZoneRulesException`, план указывает `DateTimeException`.
**Где:** `modules/telegram/.../bot/FrigateAnalyzerBot.kt:411`
**Примечание:** Это pending code fix из итерации 1 (Fix B), ещё не применённый.

### [Suggestion] Q8: Нет импорта DateTimeException в TelegramUserServiceImpl

**Описание:** Для planned try/catch нужен импорт `java.time.DateTimeException`.
**Примечание:** Часть pending code fix из итерации 1 (Fix C).

### [Important] Q9: Liquibase rollback отсутствует

**Описание:** Миграция `1.0.1.xml` не имеет rollback инструкции.
**Где:** `docker/liquibase/migration/1.0.1.xml`
**Предложение:** Добавить `<rollback>ALTER TABLE telegram_users DROP COLUMN olson_code;</rollback>`

### [Minor] Q10: getUserZone молча возвращает UTC для несуществующего пользователя

**Описание:** Если `findByChatId` вернёт null, метод вернёт UTC без предупреждения, что может скрыть ошибку.
**Где:** `modules/telegram/.../service/impl/TelegramUserServiceImpl.kt:104-107`
**Предложение:** Добавить логирование предупреждения для несуществующих пользователей.
