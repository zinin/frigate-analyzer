# Require /start Activation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Заблокировать все команды (кроме `/start`) и некомандный текст для пользователей, не завершивших активацию (owner без записи в БД или статус `INVITED`), отвечая локализованным сообщением "сначала отправьте /start" вместо текущего "unauthorized" / тихого молчания.

**Architecture:** Ввести sealed-класс `AuthResult` (`Active(role, user)` / `NeedsActivation` / `Unauthorized`). `AuthorizationFilter` получает новый публичный метод `authorize(...)` вместо `getRole(...)`. Роутер `FrigateAnalyzerBot.registerRoutes()` делает exhaustive `when` по трём веткам результата. `QuickExportHandler` адаптируется под новую сигнатуру без изменения наблюдаемого поведения. Поведение `/start` (`StartCommandHandler`) не меняется.

**Tech Stack:** Kotlin 2.x, Spring Boot 4, R2DBC, kotlinx.coroutines, JUnit5, MockK, kotlin.test, `dev.inmo:tgbotapi`.

**Project commands:**
- Один тест: `./gradlew :frigate-analyzer-telegram:test --tests "<FQCN>"`
- Все тесты модуля: `./gradlew :frigate-analyzer-telegram:test`
- Полный build: **через `build` skill / build-runner агент** (CLAUDE.md запрещает прямой `./gradlew build`)
- На ktlint-ошибки: `./gradlew ktlintFormat` и retry

**Plan location:** `docs/superpowers/plans/2026-05-24-require-start-activation.md`
**Spec:** `docs/superpowers/specs/2026-05-24-require-start-activation-design.md`
**Branch:** `fix/require-start-activation`

---

## File Structure

**Создаются:**
- `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/filter/AuthResult.kt` — sealed-тип результата авторизации.
- `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/filter/AuthorizationFilterTest.kt` — юнит-тесты `authorize(...)`.

**Изменяются:**
- `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/filter/AuthorizationFilter.kt` — добавляется `authorize(...)`, после миграции callers удаляется `getRole(...)`.
- `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBot.kt` — переписываются `onCommand` (resolution блок) и `onContentMessage`.
- `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandler.kt` — `handle(...)` использует `authorize`.
- `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandlerTest.kt` — все моки `authorizationFilter.getRole(...)` переписаны на `authorize(...)`.
- `modules/telegram/src/main/resources/messages_ru.properties` — новый ключ.
- `modules/telegram/src/main/resources/messages_en.properties` — новый ключ.
- `.claude/rules/telegram.md` — описание `AuthorizationFilter`.

---

## Task 1: Add `AuthResult` sealed class

**Files:**
- Create: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/filter/AuthResult.kt`

- [ ] **Step 1: Создать файл `AuthResult.kt`**

```kotlin
package ru.zinin.frigate.analyzer.telegram.filter

import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto
import ru.zinin.frigate.analyzer.telegram.model.UserRole

sealed class AuthResult {
    data class Active(
        val role: UserRole,
        val user: TelegramUserDto,
    ) : AuthResult()

    data object NeedsActivation : AuthResult()

    data object Unauthorized : AuthResult()
}
```

- [ ] **Step 2: Стейджинг и коммит**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/filter/AuthResult.kt
git commit -m "feat(telegram): add AuthResult sealed type"
```

---

## Task 2: Добавить i18n-ключ `common.error.activation.required`

**Files:**
- Modify: `modules/telegram/src/main/resources/messages_ru.properties`
- Modify: `modules/telegram/src/main/resources/messages_en.properties`

- [ ] **Step 1: Добавить ключ в `messages_ru.properties`**

Добавить строку рядом с `common.error.*` (после `common.error.owner.only`):

```properties
common.error.activation.required=Для использования бота сначала отправьте /start.
```

- [ ] **Step 2: Добавить тот же ключ в `messages_en.properties`**

```properties
common.error.activation.required=Please send /start first to activate access.
```

- [ ] **Step 3: Запустить `MessageKeyParityTest`**

```bash
./gradlew :frigate-analyzer-telegram:test --tests "ru.zinin.frigate.analyzer.telegram.i18n.MessageKeyParityTest"
```
Ожидание: PASS (оба файла теперь содержат новый ключ).

- [ ] **Step 4: Стейджинг и коммит**

```bash
git add modules/telegram/src/main/resources/messages_ru.properties \
        modules/telegram/src/main/resources/messages_en.properties
git commit -m "i18n(telegram): add common.error.activation.required (ru/en)"
```

---

## Task 3: Написать тесты для `authorize(...)` (red)

**Files:**
- Create: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/filter/AuthorizationFilterTest.kt`

- [ ] **Step 1: Создать тестовый файл с полным покрытием 7 кейсов**

```kotlin
package ru.zinin.frigate.analyzer.telegram.filter

import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.content.MessageContent
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.telegram.config.TelegramProperties
import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto
import ru.zinin.frigate.analyzer.telegram.model.UserRole
import ru.zinin.frigate.analyzer.telegram.model.UserStatus
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class AuthorizationFilterTest {
    private val userService = mockk<TelegramUserService>()
    private val properties =
        mockk<TelegramProperties>().also {
            every { it.owner } returns "ownerUser"
        }
    private val filter = AuthorizationFilter(properties, userService)

    private fun makeUser(
        username: String,
        status: UserStatus,
    ): TelegramUserDto =
        TelegramUserDto(
            id = UUID.randomUUID(),
            username = username,
            chatId = 12345L,
            userId = 67890L,
            firstName = "First",
            lastName = "Last",
            status = status,
            creationTimestamp = Instant.now(),
            activationTimestamp = if (status == UserStatus.ACTIVE) Instant.now() else null,
            languageCode = "en",
            notificationsRecordingEnabled = true,
            notificationsSignalEnabled = true,
        )

    @Test
    fun `authorize(username) returns Active(OWNER) for active owner record`() =
        runTest {
            val owner = makeUser("ownerUser", UserStatus.ACTIVE)
            coEvery { userService.findByUsername("ownerUser") } returns owner

            val result = filter.authorize("ownerUser")

            assertEquals(AuthResult.Active(UserRole.OWNER, owner), result)
        }

    @Test
    fun `authorize(username) returns Active(USER) for active non-owner record`() =
        runTest {
            val user = makeUser("alice", UserStatus.ACTIVE)
            coEvery { userService.findByUsername("alice") } returns user

            val result = filter.authorize("alice")

            assertEquals(AuthResult.Active(UserRole.USER, user), result)
        }

    @Test
    fun `authorize(username) returns NeedsActivation for INVITED owner record`() =
        runTest {
            val owner = makeUser("ownerUser", UserStatus.INVITED)
            coEvery { userService.findByUsername("ownerUser") } returns owner

            val result = filter.authorize("ownerUser")

            assertEquals(AuthResult.NeedsActivation, result)
        }

    @Test
    fun `authorize(username) returns NeedsActivation for INVITED non-owner record`() =
        runTest {
            val user = makeUser("alice", UserStatus.INVITED)
            coEvery { userService.findByUsername("alice") } returns user

            val result = filter.authorize("alice")

            assertEquals(AuthResult.NeedsActivation, result)
        }

    @Test
    fun `authorize(username) returns NeedsActivation for owner without DB record`() =
        runTest {
            coEvery { userService.findByUsername("ownerUser") } returns null

            val result = filter.authorize("ownerUser")

            assertEquals(AuthResult.NeedsActivation, result)
        }

    @Test
    fun `authorize(username) returns Unauthorized for non-owner without DB record`() =
        runTest {
            coEvery { userService.findByUsername("stranger") } returns null

            val result = filter.authorize("stranger")

            assertEquals(AuthResult.Unauthorized, result)
        }

    @Test
    fun `authorize(message) returns Unauthorized when extractUsername returns null`() =
        runTest {
            // CommonMessage без PrivateContentMessage-каста → extractUsername вернёт null
            val message = mockk<CommonMessage<MessageContent>>(relaxed = true)

            val result = filter.authorize(message)

            assertEquals(AuthResult.Unauthorized, result)
        }
}
```

- [ ] **Step 2: Запустить тест и убедиться, что красный**

```bash
./gradlew :frigate-analyzer-telegram:test --tests "ru.zinin.frigate.analyzer.telegram.filter.AuthorizationFilterTest"
```
Ожидание: компиляция падает — `Unresolved reference: authorize`. Это ожидаемый "red".

- [ ] **Step 3: Стейджинг (без коммита — коммит будет в Task 4 вместе с реализацией)**

```bash
git add modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/filter/AuthorizationFilterTest.kt
```

---

## Task 4: Реализовать `authorize(...)` в `AuthorizationFilter` (green)

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/filter/AuthorizationFilter.kt`

В Task 4 добавляем `authorize(...)` **рядом со старыми `getRole(...)`** — старые методы пока сохраняются, чтобы build не сломался у callers. Их удалим в Task 7 после миграции.

- [ ] **Step 1: Переписать `AuthorizationFilter.kt`**

Полностью заменить содержимое файла:

```kotlin
package ru.zinin.frigate.analyzer.telegram.filter

import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.abstracts.PrivateContentMessage
import dev.inmo.tgbotapi.types.message.content.MessageContent
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.config.TelegramProperties
import ru.zinin.frigate.analyzer.telegram.model.UserRole
import ru.zinin.frigate.analyzer.telegram.model.UserStatus
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class AuthorizationFilter(
    private val properties: TelegramProperties,
    private val userService: TelegramUserService,
) {
    suspend fun authorize(message: CommonMessage<MessageContent>): AuthResult {
        val username = extractUsername(message) ?: return AuthResult.Unauthorized
        return authorize(username)
    }

    suspend fun authorize(username: String): AuthResult {
        val record = userService.findByUsername(username)
        val isOwner = username == properties.owner

        return when {
            record?.status == UserStatus.ACTIVE && isOwner -> {
                logger.debug { "Owner access: @$username" }
                AuthResult.Active(UserRole.OWNER, record)
            }
            record?.status == UserStatus.ACTIVE && !isOwner -> {
                logger.debug { "User access: @$username" }
                AuthResult.Active(UserRole.USER, record)
            }
            record?.status == UserStatus.INVITED -> {
                logger.debug { "Invited (not yet activated) user: @$username" }
                AuthResult.NeedsActivation
            }
            record == null && isOwner -> {
                logger.debug { "Configured owner without DB record: @$username" }
                AuthResult.NeedsActivation
            }
            else -> {
                logger.warn { "Unauthorized access attempt from user: @$username" }
                AuthResult.Unauthorized
            }
        }
    }

    // Legacy API — kept temporarily so existing callers continue to compile during the migration.
    // Removed in the same series of commits after FrigateAnalyzerBot and QuickExportHandler switch
    // to authorize(...).
    suspend fun getRole(message: CommonMessage<MessageContent>): UserRole? {
        val username = extractUsername(message) ?: return null
        return getRole(username)
    }

    suspend fun getRole(username: String): UserRole? =
        when {
            username == properties.owner -> {
                logger.debug { "Owner access: @$username" }
                UserRole.OWNER
            }

            userService.findActiveByUsername(username) != null -> {
                logger.debug { "User access: @$username" }
                UserRole.USER
            }

            else -> {
                logger.warn { "Unauthorized access attempt from user: @$username" }
                null
            }
        }

    fun extractUsername(message: CommonMessage<MessageContent>): String? {
        val privateMessage = message as? PrivateContentMessage<*> ?: return null
        return privateMessage.user.username?.withoutAt
    }
}
```

- [ ] **Step 2: Запустить тест `AuthorizationFilterTest`**

```bash
./gradlew :frigate-analyzer-telegram:test --tests "ru.zinin.frigate.analyzer.telegram.filter.AuthorizationFilterTest"
```
Ожидание: PASS (все 7 тестов).

- [ ] **Step 3: Запустить полный тест модуля для регрессий**

```bash
./gradlew :frigate-analyzer-telegram:test
```
Ожидание: все тесты модуля проходят (старые `getRole`-моки в QuickExportHandlerTest пока работают).

- [ ] **Step 4: Стейджинг и коммит (включая тестовый файл из Task 3)**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/filter/AuthorizationFilter.kt \
        modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/filter/AuthorizationFilterTest.kt
git commit -m "feat(telegram): introduce AuthorizationFilter.authorize() with NeedsActivation"
```

---

## Task 5: Обновить `FrigateAnalyzerBot.registerRoutes()`

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBot.kt`

В этом таске переписываются два блока: `onCommand` resolution (строки 115-157) и `onContentMessage` (строки 292-305). Остальные блоки (`onDataCallbackQuery`-обработчики) не трогаем.

- [ ] **Step 1: Добавить импорт `AuthResult` в `FrigateAnalyzerBot.kt`**

В блоке imports добавить:

```kotlin
import ru.zinin.frigate.analyzer.telegram.filter.AuthResult
```

- [ ] **Step 2: Заменить блок resolution внутри `onCommand`**

Найти в `private suspend fun BehaviourContext.registerRoutes()` блок:

```kotlin
            onCommand(handler.command, requireOnlyCommandInMessage = false) { message ->
                val resolvedUser: TelegramUserDto? =
                    if (handler.requiredRole != null) {
                        val username = authorizationFilter.extractUsername(message)
                        if (username == null) {
                            val telegramLang = message.telegramLanguageCode()
                            val lang = StartCommandHandler.detectLanguage(telegramLang)
                            reply(message, msg.get("common.error.unauthorized", lang))
                            return@onCommand
                        }

                        val foundUser =
                            userService.findActiveByUsername(username)
                                ?: if (username == properties.owner) userService.findByUsername(username) else null
                        val resolvedRole =
                            when {
                                username == properties.owner -> UserRole.OWNER
                                foundUser != null -> UserRole.USER
                                else -> null
                            }

                        if (resolvedRole == null) {
                            val telegramLang = message.telegramLanguageCode()
                            val lang = StartCommandHandler.detectLanguage(telegramLang)
                            reply(message, msg.get("common.error.unauthorized", lang))
                            return@onCommand
                        }

                        if (handler.requiredRole == UserRole.OWNER && resolvedRole != UserRole.OWNER) {
                            val lang =
                                foundUser?.languageCode
                                    ?: StartCommandHandler.detectLanguage(
                                        message.telegramLanguageCode(),
                                    )
                            reply(message, msg.get("common.error.owner.only", lang))
                            return@onCommand
                        }

                        foundUser
                    } else {
                        null
                    }
```

Заменить на:

```kotlin
            onCommand(handler.command, requireOnlyCommandInMessage = false) { message ->
                val resolvedUser: TelegramUserDto? =
                    if (handler.requiredRole != null) {
                        when (val result = authorizationFilter.authorize(message)) {
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
                                    val lang =
                                        result.user.languageCode
                                            ?: StartCommandHandler.detectLanguage(message.telegramLanguageCode())
                                    reply(message, msg.get("common.error.owner.only", lang))
                                    return@onCommand
                                }
                                result.user
                            }
                        }
                    } else {
                        null
                    }
```

- [ ] **Step 3: Заменить блок `onContentMessage`**

Найти в `registerRoutes()`:

```kotlin
        onContentMessage { message ->
            val textContent = message.content as? TextContent
            if (textContent?.text?.startsWith("/") == true) {
                return@onContentMessage
            }

            val role = authorizationFilter.getRole(message)
            if (role == null) {
                val telegramLang = message.telegramLanguageCode()
                val lang = StartCommandHandler.detectLanguage(telegramLang)
                reply(message, msg.get("common.error.unauthorized", lang))
                return@onContentMessage
            }
        }
```

Заменить на:

```kotlin
        onContentMessage { message ->
            val textContent = message.content as? TextContent
            if (textContent?.text?.startsWith("/") == true) {
                return@onContentMessage
            }

            val lang = StartCommandHandler.detectLanguage(message.telegramLanguageCode())
            when (authorizationFilter.authorize(message)) {
                AuthResult.Unauthorized -> reply(message, msg.get("common.error.unauthorized", lang))
                AuthResult.NeedsActivation -> reply(message, msg.get("common.error.activation.required", lang))
                is AuthResult.Active -> Unit
            }
        }
```

- [ ] **Step 4: Запустить тесты модуля для регрессий**

```bash
./gradlew :frigate-analyzer-telegram:test
```
Ожидание: все тесты проходят.

- [ ] **Step 5: Стейджинг и коммит**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBot.kt
git commit -m "feat(telegram): require /start activation before non-/start commands and text"
```

---

## Task 6: Обновить `QuickExportHandler.handle()` + тесты

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandler.kt`
- Modify: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandlerTest.kt`

Поведение `QuickExportHandler` остаётся прежним: и `NeedsActivation`, и `Unauthorized` отвечают тем же ключом `common.error.unauthorized`. Меняется только сигнатура — `authorize(...)` вместо `getRole(...)`.

- [ ] **Step 1: Добавить импорт `AuthResult` в `QuickExportHandler.kt`**

В блоке imports добавить:

```kotlin
import ru.zinin.frigate.analyzer.telegram.filter.AuthResult
```

- [ ] **Step 2: Заменить блок авторизации в `handle()`**

Найти в `QuickExportHandler.handle()`:

```kotlin
        if (authorizationFilter.getRole(username) == null) {
            val lang = StartCommandHandler.detectLanguage(user.ietfLanguageCode?.code)
            bot.answer(callback, msg.get("common.error.unauthorized", lang))
            return null
        }
```

Заменить на:

```kotlin
        when (authorizationFilter.authorize(username)) {
            is AuthResult.Active -> Unit
            AuthResult.NeedsActivation, AuthResult.Unauthorized -> {
                val lang = StartCommandHandler.detectLanguage(user.ietfLanguageCode?.code)
                bot.answer(callback, msg.get("common.error.unauthorized", lang))
                return null
            }
        }
```

- [ ] **Step 3: Обновить импорты в `QuickExportHandlerTest.kt`**

В блоке imports добавить:

```kotlin
import ru.zinin.frigate.analyzer.telegram.filter.AuthResult
```

- [ ] **Step 4: Добавить хелпер `makeActiveUser` в `QuickExportHandlerTest.kt`**

В классе `QuickExportHandlerTest`, рядом с другими private-хелперами (например, после `tearDown()`), добавить:

```kotlin
    private fun makeActiveUser(username: String): ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto =
        ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto(
            id = java.util.UUID.randomUUID(),
            username = username,
            chatId = 1L,
            userId = 1L,
            firstName = "First",
            lastName = null,
            status = ru.zinin.frigate.analyzer.telegram.model.UserStatus.ACTIVE,
            creationTimestamp = java.time.Instant.now(),
            activationTimestamp = java.time.Instant.now(),
            languageCode = "en",
            notificationsRecordingEnabled = true,
            notificationsSignalEnabled = true,
        )
```

(Полные FQN в теле — чтобы избежать массового добавления импортов, при этом тип уже импортирован MockK-блоками выше косвенно. Если linter настаивает на импортах — добавить `import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto`, `import ru.zinin.frigate.analyzer.telegram.model.UserStatus`, `import java.time.Instant`, `import java.util.UUID` в шапку файла и переписать вызов через короткие имена.)

- [ ] **Step 5: Заменить глобальный stub в `HandleTest.init`**

Найти в `inner class HandleTest`:

```kotlin
        init {
            coEvery { authorizationFilter.getRole(any<String>()) } returns UserRole.USER
            coEvery { userService.getUserLanguage(any()) } returns "ru"
        }
```

Заменить на:

```kotlin
        init {
            coEvery { authorizationFilter.authorize(any<String>()) } returns
                AuthResult.Active(UserRole.USER, makeActiveUser("default"))
            coEvery { userService.getUserLanguage(any()) } returns "ru"
        }
```

- [ ] **Step 6: Заменить per-test моки `getRole` на `authorize` в `HandleTest`**

В тесте `handle rejects unauthorized user with username`:

```kotlin
                coEvery { authorizationFilter.getRole("testuser") } returns null
```

→

```kotlin
                coEvery { authorizationFilter.authorize("testuser") } returns AuthResult.Unauthorized
```

И в конце того же теста:

```kotlin
                coVerify { authorizationFilter.getRole("testuser") }
```

→

```kotlin
                coVerify { authorizationFilter.authorize("testuser") }
```

В тесте `handle rejects user without username with set username message`:

```kotlin
                coVerify(exactly = 0) { authorizationFilter.getRole(any<String>()) }
```

→

```kotlin
                coVerify(exactly = 0) { authorizationFilter.authorize(any<String>()) }
```

В тесте `handle allows owner access even when userService returns null`:

```kotlin
                coEvery { authorizationFilter.getRole(properties.owner) } returns UserRole.OWNER
```

→

```kotlin
                coEvery { authorizationFilter.authorize(properties.owner) } returns
                    AuthResult.Active(UserRole.OWNER, makeActiveUser(properties.owner))
```

В тесте `handle allows active user access and performs export`:

```kotlin
                coEvery { authorizationFilter.getRole("testuser") } returns UserRole.USER
```

→

```kotlin
                coEvery { authorizationFilter.authorize("testuser") } returns
                    AuthResult.Active(UserRole.USER, makeActiveUser("testuser"))
```

И в конце:

```kotlin
                coVerify { authorizationFilter.getRole("testuser") }
```

→

```kotlin
                coVerify { authorizationFilter.authorize("testuser") }
```

- [ ] **Step 7: Заменить mock в `CancellationTest`**

Найти в `inner class CancellationTest`:

```kotlin
                coEvery { authFilter.getRole(any<String>()) } returns UserRole.USER
```

Заменить на (с использованием хелпера из шага 4 — если он private в `QuickExportHandlerTest`, его видно изнутри `inner class`):

```kotlin
                coEvery { authFilter.authorize(any<String>()) } returns
                    AuthResult.Active(UserRole.USER, makeActiveUser("testuser"))
```

- [ ] **Step 8: Запустить все тесты `QuickExportHandlerTest`**

```bash
./gradlew :frigate-analyzer-telegram:test --tests "ru.zinin.frigate.analyzer.telegram.bot.handler.quickexport.QuickExportHandlerTest"
```
Ожидание: все тесты PASS.

- [ ] **Step 9: Запустить полный набор тестов модуля**

```bash
./gradlew :frigate-analyzer-telegram:test
```
Ожидание: все тесты проходят.

- [ ] **Step 10: Стейджинг и коммит**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandler.kt \
        modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandlerTest.kt
git commit -m "refactor(telegram): QuickExportHandler uses AuthorizationFilter.authorize()"
```

---

## Task 7: Удалить legacy `getRole(...)` из `AuthorizationFilter`

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/filter/AuthorizationFilter.kt`

После Tasks 5 и 6 ни один call-site не использует `getRole`. Удаляем.

- [ ] **Step 1: Удалить блок legacy `getRole(...)` из `AuthorizationFilter.kt`**

Удалить целиком блок:

```kotlin
    // Legacy API — kept temporarily so existing callers continue to compile during the migration.
    // Removed in the same series of commits after FrigateAnalyzerBot and QuickExportHandler switch
    // to authorize(...).
    suspend fun getRole(message: CommonMessage<MessageContent>): UserRole? {
        val username = extractUsername(message) ?: return null
        return getRole(username)
    }

    suspend fun getRole(username: String): UserRole? =
        when {
            username == properties.owner -> {
                logger.debug { "Owner access: @$username" }
                UserRole.OWNER
            }

            userService.findActiveByUsername(username) != null -> {
                logger.debug { "User access: @$username" }
                UserRole.USER
            }

            else -> {
                logger.warn { "Unauthorized access attempt from user: @$username" }
                null
            }
        }
```

Файл должен выглядеть так после удаления:

```kotlin
package ru.zinin.frigate.analyzer.telegram.filter

import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.abstracts.PrivateContentMessage
import dev.inmo.tgbotapi.types.message.content.MessageContent
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.config.TelegramProperties
import ru.zinin.frigate.analyzer.telegram.model.UserRole
import ru.zinin.frigate.analyzer.telegram.model.UserStatus
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class AuthorizationFilter(
    private val properties: TelegramProperties,
    private val userService: TelegramUserService,
) {
    suspend fun authorize(message: CommonMessage<MessageContent>): AuthResult {
        val username = extractUsername(message) ?: return AuthResult.Unauthorized
        return authorize(username)
    }

    suspend fun authorize(username: String): AuthResult {
        val record = userService.findByUsername(username)
        val isOwner = username == properties.owner

        return when {
            record?.status == UserStatus.ACTIVE && isOwner -> {
                logger.debug { "Owner access: @$username" }
                AuthResult.Active(UserRole.OWNER, record)
            }
            record?.status == UserStatus.ACTIVE && !isOwner -> {
                logger.debug { "User access: @$username" }
                AuthResult.Active(UserRole.USER, record)
            }
            record?.status == UserStatus.INVITED -> {
                logger.debug { "Invited (not yet activated) user: @$username" }
                AuthResult.NeedsActivation
            }
            record == null && isOwner -> {
                logger.debug { "Configured owner without DB record: @$username" }
                AuthResult.NeedsActivation
            }
            else -> {
                logger.warn { "Unauthorized access attempt from user: @$username" }
                AuthResult.Unauthorized
            }
        }
    }

    fun extractUsername(message: CommonMessage<MessageContent>): String? {
        val privateMessage = message as? PrivateContentMessage<*> ?: return null
        return privateMessage.user.username?.withoutAt
    }
}
```

- [ ] **Step 2: Запустить полный тест модуля**

```bash
./gradlew :frigate-analyzer-telegram:test
```
Ожидание: всё компилируется и все тесты проходят. Если есть остаточные упоминания `getRole` где-то — Kotlin-компилятор скажет `Unresolved reference`. Найти и исправить.

- [ ] **Step 3: Стейджинг и коммит**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/filter/AuthorizationFilter.kt
git commit -m "refactor(telegram): remove legacy AuthorizationFilter.getRole()"
```

---

## Task 8: Обновить документацию `.claude/rules/telegram.md`

**Files:**
- Modify: `.claude/rules/telegram.md`

- [ ] **Step 1: Обновить секцию "Authorization"**

Найти в `.claude/rules/telegram.md`:

```markdown
## Authorization

AuthorizationFilter returns UserRole (OWNER, USER) or null for unauthorized.
```

Заменить на:

```markdown
## Authorization

`AuthorizationFilter.authorize(...)` returns a `sealed AuthResult`:

| Result | Meaning |
|---|---|
| `Active(role: UserRole, user: TelegramUserDto)` | ACTIVE record found; `role` is `OWNER` or `USER`. |
| `NeedsActivation` | Owner without a DB row (clean DB), or any user with `INVITED` status. Router replies `common.error.activation.required` for every command except `/start` and for non-command text. |
| `Unauthorized` | Not the configured owner and no DB record. Router replies `common.error.unauthorized`. |

The router (`FrigateAnalyzerBot.registerRoutes()`) does an exhaustive `when` over the three branches. `/start` (`requiredRole == null`) bypasses the auth check and is handled directly by `StartCommandHandler`, which performs invite + activate.
```

- [ ] **Step 2: Стейджинг и коммит**

```bash
git add .claude/rules/telegram.md
git commit -m "docs(telegram): document AuthorizationFilter.authorize() and NeedsActivation"
```

---

## Финальная верификация

- [ ] **Step 1: Полный билд через build-runner агента**

> CLAUDE.md запрещает прямой `./gradlew build`. Использовать build skill / build-runner.

Команда для агента: build the project to verify everything compiles, all tests pass, and ktlint is clean.

При ktlint-ошибках:
```bash
./gradlew ktlintFormat
```
И повторить build.

- [ ] **Step 2: Просмотреть git log ветки**

```bash
git log --oneline master..fix/require-start-activation
```

Ожидание (порядок коммитов):
1. `feat(telegram): add AuthResult sealed type`
2. `i18n(telegram): add common.error.activation.required (ru/en)`
3. `feat(telegram): introduce AuthorizationFilter.authorize() with NeedsActivation`
4. `feat(telegram): require /start activation before non-/start commands and text`
5. `refactor(telegram): QuickExportHandler uses AuthorizationFilter.authorize()`
6. `refactor(telegram): remove legacy AuthorizationFilter.getRole()`
7. `docs(telegram): document AuthorizationFilter.authorize() and NeedsActivation`

(Плюс design-doc-коммит, который уже в ветке.)

- [ ] **Step 3: Перед созданием PR**

Из global CLAUDE.md (superpowers workflow):
> Before creating a PR: `git rm` all files from `docs/superpowers/` and commit — plan documents must NOT appear in the PR diff.

```bash
git rm -r docs/superpowers/
git commit -m "chore: remove design/plan docs from tree before PR"
```

(Документы остаются доступными в истории ветки.)
