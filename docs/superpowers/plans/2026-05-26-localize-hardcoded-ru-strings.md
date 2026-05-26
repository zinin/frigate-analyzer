# План: Локализация захардкоженных русских строк

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` или `superpowers:executing-plans` для пошагового исполнения.

**Goal:** Устранить захардкоженные русскоязычные UI-строки, перенеся их в существующую i18n-инфраструктуру (`MessageResolver` + `messages_*.properties`).

**Architecture:** Используем готовый `MessageResolver` (Spring `ReloadableResourceBundleMessageSource`). В `StartupTelegramNotifier` получаем язык владельца через новый метод `TelegramUserService.getOwnerLanguage()` с fallback на `"en"`. В `LanguageCommandHandler` имена/кнопки языков выносим в одинаковые ключи в обеих локалях (имя языка всегда отображается на своём языке).

**Tech Stack:** Kotlin 2.3.21, Spring Boot 4.0.6, MessageResolver (telegram-модуль), R2DBC, MapStruct.

---

## Контекст

Пользователь обнаружил жёстко зашитую русскую строку `"🟢 Frigate Analyzer запущен"` в `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/application/StartupTelegramNotifier.kt:50` и попросил проверить все остальные места с такой же проблемой.

Полная инвентаризация (тщательный поиск по всем модулям) нашла **4 USER_FACING строки в 2 файлах**:

| Файл | Строка | Текст | Контекст |
|------|--------|-------|----------|
| `core/.../StartupTelegramNotifier.kt` | 50 | `"🟢 Frigate Analyzer запущен"` | Уведомление owner-а при старте |
| `telegram/.../LanguageCommandHandler.kt` | 50 | `"🇷🇺 Русский"` | Текст inline-кнопки выбора языка |
| `telegram/.../LanguageCommandHandler.kt` | 51 | `"🇬🇧 English"` | Текст inline-кнопки выбора языка |
| `telegram/.../LanguageCommandHandler.kt` | 78 | `if (newLang == "ru") "Русский" else "English"` | Имя выбранного языка для подтверждающего сообщения |

Остальные русские строки в коде — это **комментарии разработчика** (не требуют локализации) и **AI-промпты** в `modules/ai-description/` (внутренние инструкции для Claude SDK, не отправляются конечному пользователю — вне scope).

## Решения (подтверждены пользователем)

- `StartupTelegramNotifier`: язык владельца берётся из БД через новый `TelegramUserService.getOwnerLanguage()`, fallback на `"en"`. Согласуется с паттерном `val lang = user?.languageCode ?: "en"`, который применяется во всех остальных handler-ах.
- Английские лейблы (`Version:`, `Commit:`, `Build time:`, `Started:`) в стартовом сообщении **не трогаем** — они уже универсальны.
- Имя языка (`Русский`, `English`) — спец-случай i18n: значение одинаково в обеих локалях, потому что имя языка всегда отображается на самом этом языке.

## Файлы

- **Modify** `modules/telegram/src/main/resources/messages_ru.properties` — добавить 3 ключа
- **Modify** `modules/telegram/src/main/resources/messages_en.properties` — добавить 3 ключа
- **Modify** `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/TelegramUserService.kt` — добавить `getOwnerLanguage()`
- **Modify** `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramUserServiceImpl.kt` — реализовать `getOwnerLanguage()`
- **Modify** `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/LanguageCommandHandler.kt` — строки 50, 51, 78
- **Modify** `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/application/StartupTelegramNotifier.kt` — конструктор + onReady
- **Modify** `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramUserServiceImplTest.kt` — тесты для `getOwnerLanguage`
- **Modify** `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/application/StartupTelegramNotifierTest.kt` — моки `MessageResolver` + `TelegramUserService`

`LanguageCommandHandlerTest.kt` менять **не нужно** — он проверяет только метаданные (command, role, order), не фактические строки.

---

## Подготовка: создать feature-ветку

По глобальным preferences пользователя — план/дизайн-документы **нельзя** коммитить в `master`. Спросить у пользователя: `git switch -c` или `git worktree`. После создания ветки сразу скопировать этот план в `docs/superpowers/plans/2026-05-26-localize-hardcoded-ru-strings.md` и закоммитить туда.

- [ ] **Шаг 1: Спросить про метод создания ветки**

Использовать `AskUserQuestion`: «git switch -c» или «git worktree». После ответа создать ветку (например, `feat/localize-ru-hardcoded`).

- [ ] **Шаг 2: Скопировать план в docs/superpowers/**

```bash
mkdir -p docs/superpowers/plans
cp /home/zinin/.claude/plans/appendline-frigate-analyzer-cheerful-pixel.md \
   docs/superpowers/plans/2026-05-26-localize-hardcoded-ru-strings.md
git add docs/superpowers/plans/2026-05-26-localize-hardcoded-ru-strings.md
git commit -m "docs: add localization plan for hardcoded RU strings"
```

---

## Задача 1: Добавить ключи в i18n ресурсы

**Files:**
- Modify: `modules/telegram/src/main/resources/messages_ru.properties`
- Modify: `modules/telegram/src/main/resources/messages_en.properties`

- [ ] **Шаг 1: Добавить ключи в `messages_ru.properties`**

В конце файла добавить раздел:

```properties

# Startup notification (sent to owner on application boot)
startup.notification.message=🟢 Frigate Analyzer запущен

# Language picker button labels and names (always rendered in their own language)
language.button.ru=🇷🇺 Русский
language.button.en=🇬🇧 English
language.name.ru=Русский
language.name.en=English
```

- [ ] **Шаг 2: Добавить ключи в `messages_en.properties`**

В конце файла добавить раздел (только `startup.notification.message` различается — остальное идентично русскому, имя языка отображается на своём языке всегда):

```properties

# Startup notification (sent to owner on application boot)
startup.notification.message=🟢 Frigate Analyzer started

# Language picker button labels and names (always rendered in their own language)
language.button.ru=🇷🇺 Русский
language.button.en=🇬🇧 English
language.name.ru=Русский
language.name.en=English
```

- [ ] **Шаг 3: `git add` оба файла**

```bash
git add modules/telegram/src/main/resources/messages_ru.properties \
        modules/telegram/src/main/resources/messages_en.properties
```

---

## Задача 2: Добавить `getOwnerLanguage()` в TelegramUserService

**Files:**
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/TelegramUserService.kt`
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramUserServiceImpl.kt`
- Modify: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramUserServiceImplTest.kt`

- [ ] **Шаг 1: Добавить метод в interface**

В `TelegramUserService.kt` рядом с существующим `getUserLanguage(chatId: Long): String?` добавить:

```kotlin
/**
 * Returns the language code of the configured owner (TELEGRAM_OWNER), or `null` if the owner has not
 * activated the bot yet or has no language set. Callers should fall back to "en" on null.
 */
suspend fun getOwnerLanguage(): String?
```

- [ ] **Шаг 2: Написать failing-тесты для нового метода**

В `TelegramUserServiceImplTest.kt` добавить три теста. Используют существующие фикстуры/моки (`repository`, `telegramProperties` мокаются). Изучить, как реализованы соседние тесты для `getUserLanguage` / `findByUsernameIgnoreCase`, и взять оттуда паттерн моков (`coEvery { repository.findByUsernameIgnoreCase(...) } returns ...`).

```kotlin
@Test
fun `getOwnerLanguage returns languageCode when owner exists`() = runBlocking {
    every { telegramProperties.owner } returns "alice"
    coEvery { repository.findByUsernameIgnoreCase("alice") } returns
        ownerEntity(username = "alice", languageCode = "ru")

    assertEquals("ru", service.getOwnerLanguage())
}

@Test
fun `getOwnerLanguage returns null when owner has no languageCode`() = runBlocking {
    every { telegramProperties.owner } returns "alice"
    coEvery { repository.findByUsernameIgnoreCase("alice") } returns
        ownerEntity(username = "alice", languageCode = null)

    assertNull(service.getOwnerLanguage())
}

@Test
fun `getOwnerLanguage returns null when owner not in database`() = runBlocking {
    every { telegramProperties.owner } returns "alice"
    coEvery { repository.findByUsernameIgnoreCase("alice") } returns null

    assertNull(service.getOwnerLanguage())
}
```

Перед написанием тестов прочитать `TelegramUserServiceImplTest.kt` целиком и переиспользовать существующий `ownerEntity()` / `userEntity()` helper, если он там есть; иначе создать минимальный inline-fixture, согласованный с другими тестами в файле.

- [ ] **Шаг 3: Убедиться, что тесты падают**

```bash
./gradlew :frigate-analyzer-telegram:test --tests \
  "ru.zinin.frigate.analyzer.telegram.service.impl.TelegramUserServiceImplTest"
```
Expected: FAIL (метод ещё не реализован, компиляция падает).

- [ ] **Шаг 4: Реализовать метод в `TelegramUserServiceImpl`**

Добавить (рядом с `getUserLanguage`):

```kotlin
@Transactional(readOnly = true)
override suspend fun getOwnerLanguage(): String? =
    findByUsernameIgnoreCase(telegramProperties.owner)?.languageCode
```

- [ ] **Шаг 5: Тесты зелёные**

```bash
./gradlew :frigate-analyzer-telegram:test --tests \
  "ru.zinin.frigate.analyzer.telegram.service.impl.TelegramUserServiceImplTest"
```
Expected: PASS.

- [ ] **Шаг 6: `git add`**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/TelegramUserService.kt \
        modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramUserServiceImpl.kt \
        modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramUserServiceImplTest.kt
```

---

## Задача 3: Локализовать LanguageCommandHandler

**File:** `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/LanguageCommandHandler.kt`

- [ ] **Шаг 1: Заменить строки 50-51 (кнопки) и 78 (имя языка)**

Изменения внутри `withTimeoutOrNull`:

```kotlin
val keyboard =
    InlineKeyboardMarkup(
        keyboard =
            matrix {
                row {
                    +CallbackDataInlineKeyboardButton(msg.get("language.button.ru", lang), "lang:ru")
                    +CallbackDataInlineKeyboardButton(msg.get("language.button.en", lang), "lang:en")
                }
            },
    )
```

И на строке 78:

```kotlin
val langName = msg.get("language.name.$newLang", newLang)
sendTextMessage(chatId, msg.get("command.language.set", newLang, langName))
```

Замечания:
- В кнопках берём `lang` (текущий язык юзера) для согласованности вызовов, но т.к. значения в `messages_ru.properties` и `messages_en.properties` идентичны для `language.button.*`, фактический результат не зависит от локали.
- В `langName` передаём `newLang` (тот язык, на который юзер переключается), потому что подтверждение придёт уже на новом языке.

- [ ] **Шаг 2: Прогнать существующие тесты handler-а**

```bash
./gradlew :frigate-analyzer-telegram:test --tests \
  "ru.zinin.frigate.analyzer.telegram.bot.handler.LanguageCommandHandlerTest"
```
Expected: PASS. Тест не зависит от фактических строк, только от metadata.

- [ ] **Шаг 3: `git add`**

```bash
git add modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/LanguageCommandHandler.kt
```

---

## Задача 4: Локализовать StartupTelegramNotifier

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/application/StartupTelegramNotifier.kt`
- Modify: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/application/StartupTelegramNotifierTest.kt`

- [ ] **Шаг 1: Обновить тест — добавить моки и зафиксировать новое поведение**

Заменить блок мокирования и assertions так, чтобы тест проверял использование `MessageResolver` и `TelegramUserService`. Заголовок класса (фрагмент):

```kotlin
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService

class StartupTelegramNotifierTest {
    private val telegramNotificationService = mockk<TelegramNotificationService>()
    private val telegramUserService = mockk<TelegramUserService>()
    private val messageResolver = mockk<MessageResolver>()
    private val gitProperties = mockk<GitProperties>()
    private val buildProperties = mockk<BuildProperties>()
    private val clock = Clock.fixed(Instant.parse("2026-05-23T15:14:00Z"), ZoneOffset.UTC)

    private val notifier =
        StartupTelegramNotifier(
            telegramNotificationService = telegramNotificationService,
            telegramUserService = telegramUserService,
            messageResolver = messageResolver,
            gitProperties = gitProperties,
            buildProperties = buildProperties,
            clock = clock,
        )

    @BeforeEach
    fun setUp() {
        clearMocks(telegramNotificationService, telegramUserService, messageResolver,
                   gitProperties, buildProperties)
    }
    ...
}
```

В каждом тесте, который запускает `notifier.onReady()`, нужно мокать:

```kotlin
coEvery { telegramUserService.getOwnerLanguage() } returns "ru"
every { messageResolver.get("startup.notification.message", "ru") } returns
    "🟢 Frigate Analyzer запущен"
```

Тесты `onReady sends owner message...` и `onReady uses unknown placeholder...` продолжают работать — assertions с `text.contains("Frigate Analyzer запущен")` остаются актуальны, потому что мок возвращает ту же строку.

Добавить новый тест (fallback на en):

```kotlin
@Test
fun `onReady falls back to en when owner language is unknown`() {
    coEvery { telegramUserService.getOwnerLanguage() } returns null
    every { messageResolver.get("startup.notification.message", "en") } returns
        "🟢 Frigate Analyzer started"
    every { gitProperties.commitId } returns "abc1234567890def"
    every { buildProperties.version } returns "1.2.3"
    every { buildProperties.time } returns Instant.parse("2026-05-20T10:00:00Z")
    coEvery { telegramNotificationService.sendOwnerMessage(any()) } just Runs

    notifier.onReady()
    awaitStartupNotification()

    coVerify(exactly = 1) {
        telegramNotificationService.sendOwnerMessage(
            match { text -> text.contains("Frigate Analyzer started") },
        )
    }
}
```

- [ ] **Шаг 2: Убедиться, что тесты падают (компиляция)**

```bash
./gradlew :frigate-analyzer-core:test --tests \
  "ru.zinin.frigate.analyzer.core.application.StartupTelegramNotifierTest"
```
Expected: FAIL (компиляция — конструктор `StartupTelegramNotifier` ещё старый).

- [ ] **Шаг 3: Реализация — обновить `StartupTelegramNotifier.kt`**

Полный новый вид класса (импорты + конструктор + `onReady`):

```kotlin
package ru.zinin.frigate.analyzer.core.application

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.info.BuildProperties
import org.springframework.boot.info.GitProperties
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.service.TelegramNotificationService
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class StartupTelegramNotifier(
    private val telegramNotificationService: TelegramNotificationService,
    private val telegramUserService: TelegramUserService,
    private val messageResolver: MessageResolver,
    private val gitProperties: GitProperties,
    private val buildProperties: BuildProperties,
    private val clock: Clock,
) {
    internal val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("startup-telegram-notifier"))

    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        scope.launch {
            try {
                withTimeout(STARTUP_NOTIFICATION_TIMEOUT.toMillis()) {
                    val ownerLang = telegramUserService.getOwnerLanguage() ?: DEFAULT_LANGUAGE
                    val text =
                        buildString {
                            appendLine(messageResolver.get("startup.notification.message", ownerLang))
                            appendLine("Version: ${buildProperties.version ?: UNKNOWN}")
                            appendLine("Commit: ${gitProperties.commitId?.take(8) ?: UNKNOWN}")
                            appendLine("Build time: ${buildProperties.time ?: UNKNOWN}")
                            append("Started: ${Instant.now(clock)}")
                        }
                    telegramNotificationService.sendOwnerMessage(text)
                }
            } catch (e: TimeoutCancellationException) {
                logger.warn { "Startup notification timed out after $STARTUP_NOTIFICATION_TIMEOUT: ${e.message}" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.warn(e) { "Failed to send startup notification" }
            }
        }
    }

    @PreDestroy
    fun shutdown() {
        scope.cancel()
    }

    private companion object {
        val STARTUP_NOTIFICATION_TIMEOUT: Duration = Duration.ofSeconds(5)
        const val UNKNOWN: String = "<unknown>"
        const val DEFAULT_LANGUAGE: String = "en"
    }
}
```

Ключевые отличия от старой версии:
- Добавлены `telegramUserService` и `messageResolver` в конструктор.
- `buildString` и получение `ownerLang` перенесены **внутрь** `scope.launch` (потому что `getOwnerLanguage()` — `suspend`).
- `getOwnerLanguage()` тоже находится под `withTimeout(STARTUP_NOTIFICATION_TIMEOUT)` — если БД зависнет, отвалится по таймауту.
- Существующие комментарии «iter-2 CONCERN-5», «iter-1 review §D5» можно сохранить, если важно для истории — но согласно правилам репозитория (CLAUDE.md «default no comments») в новом виде допустимо убрать референсы на старые iter-комментарии; оставим только содержательные.

- [ ] **Шаг 4: Тесты зелёные**

```bash
./gradlew :frigate-analyzer-core:test --tests \
  "ru.zinin.frigate.analyzer.core.application.StartupTelegramNotifierTest"
```
Expected: PASS, все 5 тестов.

- [ ] **Шаг 5: `git add`**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/application/StartupTelegramNotifier.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/application/StartupTelegramNotifierTest.kt
```

---

## Задача 5: Финальная верификация и code review

- [ ] **Шаг 1: Контрольный grep — не осталось ли хардкодов?**

```bash
grep -rn '"[^"]*[а-яА-Я][^"]*"' modules/core/src/main/kotlin/ modules/telegram/src/main/kotlin/ \
  | grep -v '//' | grep -v 'package ' || echo "no russian hardcodes found"
```

Ожидается: пустой вывод (только комментарии останутся, они отфильтрованы grep-ом строк с `//`). Проверить вручную, что все совпадения — это комментарии разработчика, имена пакетов или ключи в kebab-case (типа `command.removeuser.removed`).

- [ ] **Шаг 2: ktlint**

```bash
./gradlew ktlintCheck
```
Если упадёт: `./gradlew ktlintFormat`, потом снова `ktlintCheck`.

- [ ] **Шаг 3: Code review (через subagent по правилу проекта)**

Согласно `CLAUDE.md`: после реализации запустить `superpowers:requesting-code-review` (или соответствующего внешнего ревьюера) на diff'е ветки. Исправить критические замечания, повторить пока не чисто.

- [ ] **Шаг 4: Полный build через build-runner (по правилу проекта)**

Делегировать `build-runner` агенту:

```
./gradlew build
```

На ktlint-ошибках: `./gradlew ktlintFormat`, retry. На тестовых падениях — диагностировать через `superpowers:systematic-debugging`.

- [ ] **Шаг 5: Финальные коммиты**

Разбить изменения на 2-3 логических коммита (рекомендация — один коммит на задачу):

```bash
# Если есть незакоммиченные изменения — собрать в один-два коммита:
git status
git diff --stat
# Пример сообщений:
# i18n: add startup, language-button and language-name keys
# refactor(telegram): localize LanguageCommandHandler buttons and labels
# refactor(core): use MessageResolver for StartupTelegramNotifier with owner language
```

Сообщения коммитов в стиле текущего репо (см. `git log --oneline -10` — преобладает Conventional Commits с областью).

- [ ] **Шаг 6: Перед PR — удалить план из docs/superpowers/**

По глобальным preferences пользователя — план-документ не должен быть в diff'е PR:

```bash
git rm docs/superpowers/plans/2026-05-26-localize-hardcoded-ru-strings.md
git commit -m "chore: drop plan doc before opening PR"
```

(План остаётся доступен через `git log -- docs/superpowers/plans/` в истории ветки.)

---

## Verification (как проверить руками)

1. **Юнит-тесты:** `./gradlew :frigate-analyzer-telegram:test :frigate-analyzer-core:test` — должны пройти.
2. **Smoke-тест локализации i18n-ключей** (быстрый sanity):
   ```bash
   grep -E "^(startup\.notification|language\.(button|name))" \
     modules/telegram/src/main/resources/messages_*.properties
   ```
   Ожидается 10 строк (5 в каждом файле, с одинаковыми ключами).
3. **Smoke-тест приложения** (если есть локальное окружение):
   - Запустить `./gradlew :frigate-analyzer-core:bootRun` с настроенным `TELEGRAM_OWNER`.
   - Через несколько секунд после старта owner должен получить уведомление "🟢 Frigate Analyzer запущен" (если у него `languageCode=ru` в БД) или "started" (если `en`/null/не активирован).
   - Выполнить `/language` в чате — кнопки "🇷🇺 Русский" и "🇬🇧 English" должны быть теми же независимо от текущей локали; после выбора нового языка приходит "Язык установлен: Русский" / "Language set: English".

## Что НЕ входит в скоуп

- Русские комментарии разработчика в `modules/ai-description/` и других модулях — это не UI-текст.
- AI-промпты в Claude SDK обвязке — внутренние инструкции, не отправляются пользователю.
- Английские лейблы `Version:`, `Commit:`, `Build time:`, `Started:` — оставляем как есть (universal, low-value to localize).
- Перевод других строк в `messages_en.properties`, если найдутся несоответствия с `messages_ru.properties` — отдельная задача.
