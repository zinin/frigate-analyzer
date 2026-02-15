# Telegram Command Menu Registration

**Issue:** [#4](https://github.com/zinin/frigate-analyzer/issues/4) / FA-16
**Date:** 2026-02-15

## Problem

Bot commands are not registered via Telegram Bot API `setMyCommands`. Users don't see a menu button with available commands in the Telegram client.

## Solution

Register commands at bot startup using `setMyCommands` with role-based scoping.

### Command Scopes

**Default scope (all users):**

| Command | Description |
|---------|-------------|
| `/start` | Начать работу с ботом |
| `/help` | Помощь |

**Owner scope (BotCommandScopeChat):**

| Command | Description |
|---------|-------------|
| `/start` | Начать работу с ботом |
| `/help` | Помощь |
| `/adduser` | Добавить пользователя |
| `/removeuser` | Удалить пользователя |
| `/users` | Список пользователей |

### Registration Logic

1. **At bot startup** (in `start()`, after `bot.getMe()`):
   - Call `setMyCommands` with `BotCommandScopeDefault`: `/start`, `/help`
   - Look up owner in DB via `TelegramUserService.findActiveByUsername(owner)`
   - If found with chatId — register owner commands for their chat via `BotCommandScopeChat(chatId)`

2. **On owner's `/start` execution** (in `handleStart()`):
   - After successful owner registration/activation — register owner commands for their chatId

### Implementation Approach

All changes in `FrigateAnalyzerBot.kt`:
- Define `DEFAULT_COMMANDS` and `OWNER_COMMANDS` as companion object constants (DRY — no duplication)
- Add `registerDefaultCommands()` method — sets default scope commands
- Add `registerOwnerCommands(chatId)` method — sets owner-scoped commands
- Both methods wrapped in `try-catch` — command registration is UX enhancement, not critical for bot operation
- Call `registerDefaultCommands()` in `start()` after `bot.getMe()`
- Attempt owner command registration in `start()` if owner is already in DB
- Call `registerOwnerCommands(chatId)` in `handleStart()` inside the `if (username == properties.owner)` block only

### Error Handling

`setMyCommands` failures are logged as warnings but do not prevent bot startup or operation.

### Known Limitations

- If owner is somehow deactivated, their extended command menu remains until bot restart. This is acceptable since owner self-removal is not a supported scenario.

### Language

All command descriptions in Russian, consistent with existing bot messages.
