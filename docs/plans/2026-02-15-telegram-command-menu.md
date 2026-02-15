# Telegram Command Menu Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Register bot commands via Telegram Bot API `setMyCommands` at startup so users see a command menu in Telegram.

**Architecture:** Add two methods to `FrigateAnalyzerBot` — `registerDefaultCommands()` and `registerOwnerCommands(chatId)`. Default commands registered at startup; owner commands registered when owner's chatId is known (from DB at startup or after `/start`).

**Tech Stack:** Kotlin, ktgbotapi 30.0.2 (`dev.inmo.tgbotapi`), Spring Boot

**Design:** `docs/plans/2026-02-15-telegram-command-menu-design.md`

---

### Task 1: Add command registration methods to FrigateAnalyzerBot

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBot.kt`

**Step 1: Add imports**

Add these imports at the top of `FrigateAnalyzerBot.kt`:

```kotlin
import dev.inmo.tgbotapi.extensions.api.bot.setMyCommands
import dev.inmo.tgbotapi.types.BotCommand
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.RawChatId
import dev.inmo.tgbotapi.types.commands.BotCommandScopeChat
import dev.inmo.tgbotapi.types.commands.BotCommandScopeDefault
```

**Step 2: Add `registerDefaultCommands()` method**

Add this private method to `FrigateAnalyzerBot` class (after `handleUsers()`, before `stop()`):

```kotlin
private suspend fun registerDefaultCommands() {
    bot.setMyCommands(
        BotCommand("start", "Начать работу с ботом"),
        BotCommand("help", "Помощь"),
        scope = BotCommandScopeDefault,
    )
    logger.info { "Default bot commands registered" }
}
```

**Step 3: Add `registerOwnerCommands()` method**

Add this private method right after `registerDefaultCommands()`:

```kotlin
private suspend fun registerOwnerCommands(chatId: Long) {
    bot.setMyCommands(
        BotCommand("start", "Начать работу с ботом"),
        BotCommand("help", "Помощь"),
        BotCommand("adduser", "Добавить пользователя"),
        BotCommand("removeuser", "Удалить пользователя"),
        BotCommand("users", "Список пользователей"),
        scope = BotCommandScopeChat(ChatId(RawChatId(chatId))),
    )
    logger.info { "Owner bot commands registered for chat $chatId" }
}
```

**Step 4: Call registration at bot startup**

In the `start()` method, after the line `logger.info { "Bot started: ${botInfo.username} (${botInfo.firstName})" }` and before `bot.buildBehaviourWithLongPolling {`, add:

```kotlin
registerDefaultCommands()

val owner = userService.findActiveByUsername(properties.owner)
if (owner?.chatId != null) {
    registerOwnerCommands(owner.chatId)
}
```

**Step 5: Call registration on owner's `/start`**

In `handleStart()`, after the line `bot.reply(message, "Добро пожаловать, владелец! Используйте /help для списка команд.")` and before the `return` on line 119, add:

```kotlin
registerOwnerCommands(chatId)
```

**Step 6: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBot.kt
git commit -m "feat: register bot commands menu at startup (#4)"
```

### Task 2: Build and verify

**Step 1: Run ktlintFormat**

Run: `./gradlew ktlintFormat`

If there are formatting fixes, commit them:

```bash
git add -u
git commit -m "style: ktlint format"
```

**Step 2: Run build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

If build fails, fix issues and retry.

**Step 3: Final commit if needed**

If any fixes were made during build, commit them.
