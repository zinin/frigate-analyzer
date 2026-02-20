# Add Telegram /version Command Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Allow authorized users to check the application version (Git commit, build time) via Telegram.

**Architecture:** Inject `BuildProperties` and `GitProperties` into `FrigateAnalyzerBot` and expose them via a new `/version` command.

**Tech Stack:** Kotlin, Spring Boot, Telegram Bot API (ktgbotapi)

---

### Task 1: Add Dependencies and Command Registration

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBot.kt`

**Step 1: Modify `FrigateAnalyzerBot` constructor**

Add `ObjectProvider` for build/git properties and necessary imports.

```kotlin
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.boot.info.GitProperties
// ... other imports

class FrigateAnalyzerBot(
    // ... existing args
    private val clock: Clock,
    private val buildProperties: ObjectProvider<BuildProperties>,
    private val gitProperties: ObjectProvider<GitProperties>,
) {
```

**Step 2: Add `/version` to `DEFAULT_COMMANDS`**

```kotlin
        private val DEFAULT_COMMANDS =
            listOf(
                BotCommand("start", "Начать работу с ботом"),
                BotCommand("help", "Помощь"),
                BotCommand("export", "Выгрузить видео"),
                BotCommand("timezone", "Часовой пояс"),
                BotCommand("version", "Версия приложения"),
            )
```

**Step 3: Register command handler in `start()`**

```kotlin
                        onCommand("timezone") { message ->
                            handleTimezone(message)
                        }

                        onCommand("version") { message ->
                            handleVersion(message)
                        }
```

**Step 4: Implement `handleVersion`**

Add this method to `FrigateAnalyzerBot`:

```kotlin
    private suspend fun handleVersion(message: CommonMessage<TextContent>) {
        val role = authorizationFilter.getRole(message)
        if (role == null) {
            bot.reply(message, authorizationFilter.getUnauthorizedMessage())
            return
        }

        val sb = StringBuilder()
        val git = gitProperties.ifAvailable
        val build = buildProperties.ifAvailable

        if (git != null) {
            sb.append("Git version: ").append(git.commitId).append("\n")
            sb.append("Git commit time: ").append(git.commitTime).append("\n")
        } else {
            sb.append("Git info not available\n")
        }

        if (build != null) {
            sb.append("Build version: ").append(build.version).append("\n")
            sb.append("Build time: ").append(build.time).append("\n")
        } else {
            sb.append("Build info not available\n")
        }

        bot.reply(message, sb.toString())
    }
```

**Step 5: Verify compilation**

Run: `./gradlew :frigate-analyzer-telegram:compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 6: Commit**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBot.kt
git commit -m "feat: add /version command to telegram bot (FA-28)"
```
