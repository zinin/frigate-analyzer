# Require /start before other commands — Design

**Date:** 2026-05-24
**Branch:** `fix/require-start-activation`
**Module:** `modules/telegram`

## Problem

На чистой БД (или после ресета) запись в `telegram_users` для owner-а появляется только когда он отправит `/start`. До этого момента:

- `AuthorizationFilter.getRole(username)` для owner-а **возвращает `OWNER`** просто потому, что `username == properties.owner`, не проверяя БД.
- Поэтому в `FrigateAnalyzerBot.registerRoutes()` команды проходят авторизацию, но в handler передаётся `user = null`.
- Часть команд работает некорректно с `user = null`:

| Команда | Поведение без записи владельца в БД |
|---|---|
| `/help` | owner-секция списка команд не выводится (`user?.username == owner` → false) |
| `/timezone` | диалог проходит, но `updateTimezone(chatId, ...)` обновляет 0 строк → ошибка `error.save` |
| `/language` | `updateLanguage(chatId, ...)` обновляет 0 строк → `error.generic` |
| `/notifications` | `if (user == null) return` — handler молча завершается, бот ничего не показывает |
| `/export` | работает, но без сохранённого часового пояса/языка владельца (UTC, английский по умолчанию) |
| `/version`, `/adduser`, `/removeuser`, `/users` | работают, но в дефолтном языке |

Симметричная проблема существует для INVITED-юзеров (приглашённых через `/adduser`, но не отправивших `/start`): сейчас они получают `common.error.unauthorized` ("Доступ запрещён"), хотя на самом деле им нужно отправить `/start` для активации.

## Goal

Все команды кроме `/start` (и весь некомандный текст) должны **отказывать с явным сообщением "сначала /start"** для:

1. Owner-а, у которого нет записи в БД (чистая БД).
2. Owner-а с записью `INVITED` (теоретически — например, после восстановления из снапшота).
3. Любого пользователя с записью `INVITED`.

Не-приглашённые пользователи (никогда не упоминавшиеся в `/adduser`) продолжают получать `common.error.unauthorized`.

## Non-goals

- Поведение `/start` не меняется. `StartCommandHandler` сам обрабатывает invite + activate.
- Callback-обработчики (`QuickExportHandler`, `nfs:`-callback в `FrigateAnalyzerBot`, `CancelExportHandler`) не получают новую логику отказа: до `/start` пользователь физически не может получить inline-кнопки (рассылка нотификаций идёт только по ACTIVE chatId-ам). `QuickExportHandler` адаптируется только под новую сигнатуру `AuthorizationFilter` (поведение сохраняется).
- Не вводим новую сущность владельца — обходимся существующими `UserStatus.INVITED` / `UserStatus.ACTIVE` и фактом отсутствия записи.

## Architecture

### Новый sealed-тип `AuthResult`

Файл: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/filter/AuthResult.kt`

```kotlin
sealed class AuthResult {
    data class Active(val role: UserRole, val user: TelegramUserDto) : AuthResult()
    data object NeedsActivation : AuthResult()
    data object Unauthorized : AuthResult()
}
```

- `Active(role, user)` — активный owner или активный user. `user` гарантированно не null и `status == ACTIVE`.
- `NeedsActivation` — пользователь известен (либо это сконфигурированный owner, либо есть `INVITED`-запись), но активация ещё не завершена.
- `Unauthorized` — не сконфигурирован как owner и нет записи в БД.

### `AuthorizationFilter`

Файл: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/filter/AuthorizationFilter.kt`

Публичный API после изменения:

```kotlin
suspend fun authorize(message: CommonMessage<MessageContent>): AuthResult
suspend fun authorize(username: String): AuthResult
fun extractUsername(message: CommonMessage<MessageContent>): String?  // как сейчас
```

Старые перегрузки `getRole(message)` и `getRole(username)` удаляются (единая точка авторизации).

Логика `authorize(username)`:

```
record = repository.findByUsername(username)
isOwner = (username == properties.owner)

return when {
    record?.status == ACTIVE && isOwner  -> Active(OWNER, record.toDto())
    record?.status == ACTIVE && !isOwner -> Active(USER, record.toDto())
    record?.status == INVITED            -> NeedsActivation
    record == null && isOwner            -> NeedsActivation
    else                                  -> Unauthorized
}
```

`authorize(message)`:

```
username = extractUsername(message) ?: return Unauthorized
return authorize(username)
```

### `FrigateAnalyzerBot.registerRoutes()`

#### onCommand

Для handler-ов с `requiredRole != null`:

```kotlin
val resolvedUser: TelegramUserDto? = when (val result = authorizationFilter.authorize(message)) {
    AuthResult.Unauthorized -> {
        val lang = StartCommandHandler.detectLanguage(message.telegramLanguageCode())
        reply(message, msg.get("common.error.unauthorized", lang))
        return@onCommand
    }
    AuthResult.NeedsActivation -> {
        val lang = StartCommandHandler.detectLanguage(message.telegramLanguageCode())
        reply(message, msg.get("common.error.activation.required", lang))
        return@onCommand
    }
    is AuthResult.Active -> {
        if (handler.requiredRole == UserRole.OWNER && result.role != UserRole.OWNER) {
            val lang = result.user.languageCode ?: StartCommandHandler.detectLanguage(message.telegramLanguageCode())
            reply(message, msg.get("common.error.owner.only", lang))
            return@onCommand
        }
        result.user
    }
}
```

Для `/start` (`requiredRole == null`) — проверка авторизации в роутере не запускается, `resolvedUser = null`. `StartCommandHandler` сам обрабатывает invite/activate.

#### onContentMessage (некомандный текст)

```kotlin
val lang = StartCommandHandler.detectLanguage(message.telegramLanguageCode())
when (authorizationFilter.authorize(message)) {
    AuthResult.Unauthorized   -> reply(message, msg.get("common.error.unauthorized", lang))
    AuthResult.NeedsActivation -> reply(message, msg.get("common.error.activation.required", lang))
    is AuthResult.Active       -> { /* no-op: на некомандный текст логики нет */ }
}
```

### `QuickExportHandler.handle()`

Текущий код:

```kotlin
if (authorizationFilter.getRole(username) == null) { ... unauthorized ... }
```

Заменяется на:

```kotlin
when (authorizationFilter.authorize(username)) {
    is AuthResult.Active -> { /* продолжаем */ }
    AuthResult.NeedsActivation, AuthResult.Unauthorized -> {
        bot.answer(callback, msg.get("common.error.unauthorized", lang))
        return null
    }
}
```

Поведение **не меняется**: NeedsActivation в callback-флоу теоретически не возникает (рассылка идёт только ACTIVE), но если возникнет — отказ с тем же сообщением unauthorized. Это адаптация к новой сигнатуре `AuthorizationFilter`, не функциональное изменение.

### i18n

Новый ключ — добавляется в обе локали (существующий `MessageKeyParityTest` гарантирует синхронность):

```properties
# messages_ru.properties
common.error.activation.required=Для использования бота сначала отправьте /start.

# messages_en.properties
common.error.activation.required=Please send /start first to activate access.
```

## Поток данных

```
authorize(message) ↓
  ├─ Active(OWNER, user)  →  requiredRole == OWNER → выполнить
  │                          requiredRole == USER  → выполнить
  ├─ Active(USER, user)   →  requiredRole == OWNER → "owner only"
  │                          requiredRole == USER  → выполнить
  ├─ NeedsActivation      →  "сначала /start"  (любая команда != /start; /start не доходит сюда — у него requiredRole == null)
  └─ Unauthorized         →  "access denied"
```

## Edge cases

| Сценарий | Поведение |
|---|---|
| Owner на чистой БД пишет `/start` | `requiredRole == null` → роутер не вызывает `authorize`. `StartCommandHandler` invite + activate. ✅ |
| INVITED-юзер пишет `/start` | то же; `StartCommandHandler` находит INVITED, активирует. ✅ |
| Не-приглашённый пишет `/start` | `StartCommandHandler` сам отвечает `common.error.unauthorized` (текущая логика). ✅ |
| Owner после `/start` пишет команду | `Active(OWNER, ACTIVE-запись)` → работает как сейчас. ✅ |
| USER без `@username` в Telegram | `extractUsername == null` → `Unauthorized`. ✅ |
| Owner с записью INVITED (восстановили снапшот) | `NeedsActivation` → "сначала /start". После `/start` — `activate` (INVITED → ACTIVE). ✅ |
| Гонка двух `/start` от owner-а | SQL `UPDATE ... WHERE status = 'INVITED'` идемпотентен: второй вызов вернёт 0 строк. Поведение `StartCommandHandler` не меняется. ✅ |
| Callback от INVITED-юзера (теоретически) | `QuickExportHandler` ответит `common.error.unauthorized` — приемлемо, рассылка идёт только ACTIVE. ✅ |

## Тестирование

### Новый файл `AuthorizationFilterTest.kt`

`modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/filter/AuthorizationFilterTest.kt`

Покрытие `authorize(username)`:

| # | Вход | Ожидание |
|---|---|---|
| 1 | ACTIVE owner-запись | `Active(OWNER, user)` |
| 2 | ACTIVE user-запись (не owner) | `Active(USER, user)` |
| 3 | INVITED owner-запись | `NeedsActivation` |
| 4 | INVITED user-запись | `NeedsActivation` |
| 5 | Owner без записи (чистая БД) | `NeedsActivation` |
| 6 | Не-owner без записи | `Unauthorized` |

Покрытие `authorize(message)`:

| # | Вход | Ожидание |
|---|---|---|
| 7 | message без username (extractUsername == null) | `Unauthorized` |

Стиль — JUnit5 + MockK (как в существующих тестах модуля), моки `TelegramProperties` и `TelegramUserService`/`TelegramUserRepository`.

### Существующие тесты

- `QuickExportHandlerTest` — обновить моки `authorizationFilter.getRole(...)` на `authorize(...)`, возвращающий `AuthResult.Active(...)` / `AuthResult.Unauthorized`.
- Прочие handler-тесты (`LanguageCommandHandlerTest`, `NotificationsCommandHandlerTest`, и т.д.) — проверка, что `AuthorizationFilter` не упоминается; handler-ы получают `user` параметром, auth — в роутере.
- `MessageKeyParityTest` — без правок, автоматически проверит парность нового ключа.

### Что не покрывается юнит-тестами

`FrigateAnalyzerBot.registerRoutes()` напрямую не покрывается — `BehaviourContext` ktgbotapi сложно мокать. Логика роутера состоит из exhaustive `when` по 3 веткам `AuthResult` — тесты `AuthorizationFilterTest` дают высокую уверенность в корректности.

## Затронутые файлы

**Новые:**
- `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/filter/AuthResult.kt`
- `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/filter/AuthorizationFilterTest.kt`

**Изменяемые:**
- `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/filter/AuthorizationFilter.kt`
- `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBot.kt`
- `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandler.kt`
- `modules/telegram/src/main/resources/messages_ru.properties`
- `modules/telegram/src/main/resources/messages_en.properties`
- `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandlerTest.kt` (если содержит mock-и фильтра)

## Документация

`.claude/rules/telegram.md` — секция "Authorization": обновить описание `AuthorizationFilter.authorize(...)` (возвращает `AuthResult` вместо `UserRole?`), добавить упоминание поведения `NeedsActivation`.
