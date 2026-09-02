# Grok Build Description Provider Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Второй провайдер AI-описаний детекций, `application.ai.description.provider=grok`, через headless-вызов бинарника Grok Build, с общим провайдер-нейтральным ядром модуля `ai-description` и уведомлением владельца об отказе авторизации.

**Architecture:** Семафор, таймауты и retry уезжают из `ClaudeDescriptionAgent` в `core/DefaultDescriptionAgent`; провайдеры реализуют SPI `DescriptionBackend` (одна попытка describe). `GrokBackend` пишет `prompt.json` с inline base64-кадрами, запускает `grok --prompt-file … --json-schema … --output-format json` через `ProcessBuilder` и читает `structuredOutput`. Отказ авторизации это `DescriptionException.Unauthorized`, ядро публикует Spring-событие, core-модуль шлёт владельцу сообщение в Telegram.

**Tech Stack:** Kotlin 2.4.10, Spring Boot 4.1.0, Java 25, kotlinx-coroutines 1.11.0, Jackson 3 (`tools.jackson`), MockK, kotlin-test JUnit5, ktlint, Grok Build 1.0.13.

**Spec:** `docs/superpowers/specs/2026-09-02-grok-description-provider-design.md`

## Global Constraints

- Все команды Gradle (`./gradlew …`) запускаются только через агента `claude-forge:build-runner`, никогда напрямую в основной сессии (правило `CLAUDE.md`). На ошибки ktlint: `./gradlew ktlintFormat`, затем повтор.
- Тесты одного модуля: `./gradlew :frigate-analyzer-ai-description:test`, `./gradlew :frigate-analyzer-core:test`, `./gradlew :frigate-analyzer-telegram:test`. Один класс: добавить `--tests <FQCN>`.
- После создания или изменения файла обязательно `git add <file>` (правило `CLAUDE.md`). В `docs/superpowers/` коммитятся только spec и этот план, остальные untracked-файлы там не трогать.
- Каждое сообщение коммита заканчивается строкой `Claude-Session: https://claude.ai/code/session_015ZWCHwRx1akjYX3o1wtJ6S` (отдельный `-m`).
- Идентификаторы провайдеров: `claude`, `grok`. Префикс свойств Grok: `application.ai.description.grok`. Env: `GROK_CLI_PATH`, `GROK_MODEL` (default `grok-4.6`), `GROK_EFFORT` (default `low`), `GROK_HOME` (default `${application.temp-folder}/grok-home`), `GROK_WORKING_DIR` (default `${application.temp-folder}/grok-cwd`), `GROK_HTTP_PROXY`, `GROK_HTTPS_PROXY`, `GROK_NO_PROXY`.
- Версия Grok в образе: `ARG GROK_VERSION=1.0.13`.
- Конструкторы `@ConfigurationProperties`-классов вызываются только с именованными аргументами.
- Тексты `DescriptionException` провайдер-нейтральны: `Description timed out`, `Description provider returned an invalid response`, `Description provider transport error`, `Description provider rate-limited the request`, `Description provider rejected the credentials: <detail>`.
- Ключи i18n: `ai.description.auth.lost`, `ai.description.auth.restored`, в обоих бандлах `modules/telegram/src/main/resources/messages_{ru,en}.properties`. В значениях нет апострофов (MessageFormat).
- JSON Schema для `--json-schema`, ровно: `{"type":"object","properties":{"short":{"type":"string"},"detailed":{"type":"string"}},"required":["short","detailed"],"additionalProperties":false}`.
- System prompt Grok, константа: `You describe frames from a security camera for a notification message. Answer only through the structured output. Do not call tools and do not ask questions.`
- Env изоляции дочернего `grok`: `GROK_DISABLE_AUTOUPDATER=1`, `GROK_MEMORY=0`, `GROK_SUBAGENTS=0`, `GROK_CLAUDE_AGENTS_ENABLED=0`, `GROK_CLAUDE_HOOKS_ENABLED=0`, `GROK_CLAUDE_MCPS_ENABLED=0`, `GROK_CLAUDE_RULES_ENABLED=0`, `GROK_CLAUDE_SKILLS_ENABLED=0`, `GROK_CURSOR_AGENTS_ENABLED=0`, `GROK_CURSOR_HOOKS_ENABLED=0`, `GROK_CURSOR_MCPS_ENABLED=0`, `GROK_CURSOR_RULES_ENABLED=0`, `GROK_CURSOR_SKILLS_ENABLED=0`.
- Секреты (`auth.json`, `config.toml` с ключами, `application-local.yaml`) не печатать и не коммитить.
- Kotlin allopen через `kotlin-spring` применён ко всем модулям: `@Bean`-методы в `@AutoConfiguration` не требуют `open`.

---

## Структура файлов

Модуль `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/`:

| Файл | Ответственность |
|---|---|
| `api/DescriptionException.kt` (modify) | Нейтральные тексты, `detail`, новый `Unauthorized` |
| `api/DescriptionProviderAuthEvent.kt` (create) | Spring-событие `LOST`/`RESTORED` |
| `core/DescriptionBackend.kt` (create) | SPI провайдера: одна попытка describe |
| `core/DefaultDescriptionAgent.kt` (create) | Семафор, таймауты, retry, машина состояний авторизации |
| `core/ResultNormalizer.kt` (create) | Проверка полей и обрезка «…» |
| `core/LanguageNames.kt` (create) | `ru` → `Russian`, `en` → `English` |
| `claude/ClaudeBackend.kt` (create, заменяет `ClaudeDescriptionAgent.kt`) | stage → prompt → invoker → parse |
| `claude/ClaudeResponseParser.kt`, `ClaudePromptBuilder.kt`, `ClaudeExceptionMapper.kt`, `ClaudeImageStager.kt`, `ClaudeAsyncClientFactory.kt` (modify) | Делегирование в core, условие `provider=claude`, ветка `Unauthorized` |
| `grok/GrokPromptBuilder.kt` (create) | Тексты промпта и system prompt |
| `grok/GrokPromptFileWriter.kt` (create) | `prompt.json` из ACP-блоков |
| `grok/GrokCommand.kt`, `grok/GrokCommandBuilder.kt` (create) | argv и env |
| `grok/GrokProcessRunner.kt`, `grok/DefaultGrokProcessRunner.kt` (create) | Шов и `ProcessBuilder` |
| `grok/GrokOutputParser.kt` (create) | JSON stdout → `GrokOutput`, error JSON → message |
| `grok/GrokExceptionMapper.kt` (create) | Классификация ошибок |
| `grok/GrokHomeGuard.kt` (create) | shared/exclusive блокировка `GROK_HOME` |
| `grok/GrokHomeSweeper.kt` (create) | Очистка `sessions/` и `logs/` |
| `grok/GrokBackend.kt` (create) | Оркестрация одной попытки, проверки в `init` |
| `config/GrokProperties.kt` (create) | `application.ai.description.grok.*` |
| `config/AiDescriptionAutoConfiguration.kt` (modify) | `@Bean` агента с `@ConditionalOnBean(DescriptionBackend)` |
| `config/DescriptionAgentSanityChecker.kt` (modify) | `KNOWN_PROVIDERS = claude, grok` |

Модуль `core`: `application/DescriptionAuthAlertNotifier.kt` (create), `src/main/resources/application.yaml` и `src/test/resources/application.yaml` (modify), `src/test/kotlin/.../config/properties/GrokPropertiesBindingTest.kt` (create).

Модуль `telegram`: `messages_ru.properties`, `messages_en.properties` (modify).

Деплой и документация: `docker/deploy/Dockerfile`, `docker-compose.yml`, `docker-entrypoint.sh`, `.env.example`, `README.md`, `CLAUDE.md`, `.claude/rules/ai-description.md`, `.claude/rules/configuration.md`.

---

### Task 1: Провайдер-нейтральные исключения и событие авторизации

**Files:**
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/api/DescriptionException.kt`
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/api/DescriptionProviderAuthEvent.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/api/DescriptionExceptionTest.kt`

**Interfaces:**
- Produces: `DescriptionException.Unauthorized(detail: String, cause: Throwable? = null)` с `val detail`; `InvalidResponse(cause: Throwable? = null, detail: String? = null)`, `Transport(cause, detail)`, `RateLimited(cause, detail)` с теми же параметрами; `Timeout(cause)` без изменений. `DescriptionProviderAuthEvent(provider: String, state: State, detail: String?, recoveryHint: String)` с `enum class State { LOST, RESTORED }`.

- [ ] **Step 1: Написать падающий тест**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DescriptionExceptionTest {
    @Test
    fun `Unauthorized carries detail in message and property`() {
        val e = DescriptionException.Unauthorized("Not signed in")
        assertEquals("Not signed in", e.detail)
        assertEquals("Description provider rejected the credentials: Not signed in", e.message)
    }

    @Test
    fun `Transport without detail keeps the neutral base message`() {
        assertEquals("Description provider transport error", DescriptionException.Transport().message)
    }

    @Test
    fun `Transport with detail appends it`() {
        val e = DescriptionException.Transport(detail = "exit 1: boom")
        assertEquals("Description provider transport error: exit 1: boom", e.message)
    }

    @Test
    fun `InvalidResponse and RateLimited accept a detail`() {
        assertEquals(
            "Description provider returned an invalid response: no structured output",
            DescriptionException.InvalidResponse(detail = "no structured output").message,
        )
        assertEquals(
            "Description provider rate-limited the request: 429",
            DescriptionException.RateLimited(detail = "429").message,
        )
    }

    @Test
    fun `messages never name a provider`() {
        listOf(
            DescriptionException.Timeout(),
            DescriptionException.InvalidResponse(),
            DescriptionException.Transport(),
            DescriptionException.RateLimited(),
            DescriptionException.Unauthorized("x"),
        ).forEach { e ->
            assertFalse(e.message!!.contains("Claude"), "message must be provider-neutral: ${e.message}")
        }
    }

    @Test
    fun `event exposes provider state detail and hint`() {
        val event =
            DescriptionProviderAuthEvent(
                provider = "grok",
                state = DescriptionProviderAuthEvent.State.LOST,
                detail = "Not signed in",
                recoveryHint = "grok login --device-code",
            )
        assertEquals("grok", event.provider)
        assertEquals(DescriptionProviderAuthEvent.State.LOST, event.state)
    }
}
```

- [ ] **Step 2: Запустить тест и убедиться, что он не компилируется**

Run (через build-runner): `./gradlew :frigate-analyzer-ai-description:test --tests ru.zinin.frigate.analyzer.ai.description.api.DescriptionExceptionTest`
Expected: FAIL, ошибки компиляции `Unresolved reference: Unauthorized`, `DescriptionProviderAuthEvent`.

- [ ] **Step 3: Переписать `DescriptionException.kt`**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.api

/**
 * Ошибки провайдера описаний, общие для всех провайдеров. Тексты нейтральны: провайдер и
 * подробности живут в `detail`, а не в типе.
 */
sealed class DescriptionException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    class Timeout(
        cause: Throwable? = null,
    ) : DescriptionException("Description timed out", cause)

    class InvalidResponse(
        cause: Throwable? = null,
        detail: String? = null,
    ) : DescriptionException(withDetail("Description provider returned an invalid response", detail), cause)

    class Transport(
        cause: Throwable? = null,
        detail: String? = null,
    ) : DescriptionException(withDetail("Description provider transport error", detail), cause)

    class RateLimited(
        cause: Throwable? = null,
        detail: String? = null,
    ) : DescriptionException(withDetail("Description provider rate-limited the request", detail), cause)

    /**
     * Провайдер отверг учётные данные. Не повторяется агентом; на первом таком отказе ядро
     * публикует [DescriptionProviderAuthEvent] со state = LOST.
     */
    class Unauthorized(
        val detail: String,
        cause: Throwable? = null,
    ) : DescriptionException("Description provider rejected the credentials: $detail", cause)
}

private fun withDetail(
    base: String,
    detail: String?,
): String = if (detail.isNullOrBlank()) base else "$base: $detail"
```

- [ ] **Step 4: Создать `DescriptionProviderAuthEvent.kt`**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.api

/**
 * Переход состояния авторизации провайдера описаний. Публикуется ядром модуля ровно один раз на
 * переход (первый [DescriptionException.Unauthorized] после успеха или старта даёт LOST, первый успех
 * после LOST даёт RESTORED). Слушает core-модуль и шлёт владельцу сообщение в Telegram.
 */
data class DescriptionProviderAuthEvent(
    val provider: String,
    val state: State,
    /** Техническое сообщение провайдера; только для LOST. */
    val detail: String?,
    /** Команда, которой владелец чинит авторизацию. */
    val recoveryHint: String,
) {
    enum class State { LOST, RESTORED }
}
```

- [ ] **Step 5: Запустить тесты модуля**

Run: `./gradlew :frigate-analyzer-ai-description:test`
Expected: PASS. Существующие тесты не проверяют тексты исключений, компиляция `ClaudeExceptionMapper` и `ClaudeResponseParser` не ломается: старые вызовы `Transport(throwable)`, `RateLimited(throwable)`, `InvalidResponse(e)` остаются валидными (первый позиционный параметр `cause`).

- [ ] **Step 6: Commit**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/api/DescriptionException.kt \
        modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/api/DescriptionProviderAuthEvent.kt \
        modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/api/DescriptionExceptionTest.kt
git commit -m "feat(ai-description): provider-neutral exceptions, Unauthorized type and auth event" \
           -m "Claude-Session: https://claude.ai/code/session_015ZWCHwRx1akjYX3o1wtJ6S"
```

---

### Task 2: `ResultNormalizer` и `LanguageNames` в `core/`

**Files:**
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/core/ResultNormalizer.kt`
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/core/LanguageNames.kt`
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeResponseParser.kt`
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudePromptBuilder.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/core/ResultNormalizerTest.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/core/LanguageNamesTest.kt`

**Interfaces:**
- Produces: `object ResultNormalizer { fun normalize(short: String?, detailed: String?, shortMaxLength: Int, detailedMaxLength: Int): DescriptionResult }` (бросает `DescriptionException.InvalidResponse` на пустое поле); `object LanguageNames { fun of(code: String): String }` (бросает `IllegalStateException` на неизвестный код).

- [ ] **Step 1: Написать падающие тесты**

`ResultNormalizerTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.core

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResultNormalizerTest {
    @Test
    fun `returns both fields unchanged when within limits`() {
        val result = ResultNormalizer.normalize("Two cars.", "Two cars entering the yard.", 200, 1500)
        assertEquals(DescriptionResult("Two cars.", "Two cars entering the yard."), result)
    }

    @Test
    fun `truncates short longer than limit with ellipsis`() {
        val result = ResultNormalizer.normalize("a".repeat(250), "d", 200, 1500)
        assertEquals(200, result.short.length)
        assertEquals("…", result.short.last().toString())
    }

    @Test
    fun `truncates detailed longer than limit with ellipsis`() {
        val result = ResultNormalizer.normalize("s", "b".repeat(2000), 200, 1500)
        assertEquals(1500, result.detailed.length)
        assertEquals("…", result.detailed.last().toString())
    }

    @Test
    fun `never splits a surrogate pair at the cut`() {
        // 198 ASCII chars, then an astral emoji (two UTF-16 units), then filler: the naive cut at
        // index 199 would land between the high and the low surrogate.
        val text = "a".repeat(198) + "😀" + "bbb"
        val result = ResultNormalizer.normalize(text, "d", 200, 1500)
        assertEquals(199, result.short.length)
        assertTrue(result.short.none { it.isSurrogate() }, "no lone surrogate may survive the cut")
        assertEquals("…", result.short.last().toString())
    }

    @Test
    fun `blank short is InvalidResponse`() {
        assertFailsWith<DescriptionException.InvalidResponse> { ResultNormalizer.normalize("  ", "d", 200, 1500) }
    }

    @Test
    fun `null detailed is InvalidResponse`() {
        assertFailsWith<DescriptionException.InvalidResponse> { ResultNormalizer.normalize("s", null, 200, 1500) }
    }
}
```

`LanguageNamesTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LanguageNamesTest {
    @Test
    fun `maps ru and en`() {
        assertEquals("Russian", LanguageNames.of("ru"))
        assertEquals("English", LanguageNames.of("en"))
    }

    @Test
    fun `is case-insensitive`() {
        assertEquals("Russian", LanguageNames.of("RU"))
    }

    @Test
    fun `rejects unknown code`() {
        assertFailsWith<IllegalStateException> { LanguageNames.of("de") }
    }
}
```

- [ ] **Step 2: Запустить и убедиться, что не компилируется**

Run: `./gradlew :frigate-analyzer-ai-description:test --tests 'ru.zinin.frigate.analyzer.ai.description.core.*'`
Expected: FAIL, `Unresolved reference: ResultNormalizer`, `LanguageNames`.

- [ ] **Step 3: Создать `ResultNormalizer.kt`**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.core

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult

/**
 * Провайдер-нейтральная нормализация ответа модели: непустые поля и обрезка по лимитам.
 * Провайдеры извлекают `short` и `detailed` по-своему (JSON в тексте у Claude, `structuredOutput`
 * у Grok) и отдают их сюда.
 */
object ResultNormalizer {
    fun normalize(
        short: String?,
        detailed: String?,
        shortMaxLength: Int,
        detailedMaxLength: Int,
    ): DescriptionResult {
        if (short.isNullOrBlank()) {
            throw DescriptionException.InvalidResponse(detail = "missing or blank 'short' field")
        }
        if (detailed.isNullOrBlank()) {
            throw DescriptionException.InvalidResponse(detail = "missing or blank 'detailed' field")
        }
        return DescriptionResult(
            short = truncate(short, shortMaxLength),
            detailed = truncate(detailed, detailedMaxLength),
        )
    }

    internal fun truncate(
        text: String,
        maxLength: Int,
    ): String {
        if (text.length <= maxLength) return text
        // Не рвём UTF-16 суррогатную пару: substring(…, maxLength-1) может попасть между
        // high- и low-surrogate (эмодзи, редкие CJK).
        val rawCut = maxLength - 1
        val cut = if (rawCut > 0 && text[rawCut - 1].isHighSurrogate()) rawCut - 1 else rawCut
        return text.substring(0, cut) + "…"
    }
}
```

- [ ] **Step 4: Создать `LanguageNames.kt`**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.core

/** Имя языка для промпта. `@Pattern` на `common.language` уже отсеивает всё, кроме ru и en. */
object LanguageNames {
    fun of(code: String): String =
        when (code.lowercase()) {
            "ru" -> "Russian"

            "en" -> "English"

            else -> error("Unsupported language code: '$code' (expected 'ru' or 'en')")
        }
}
```

- [ ] **Step 5: Делегировать из Claude-классов**

В `ClaudeResponseParser.kt` заменить тело после разбора JSON и удалить приватный `truncate`:

```kotlin
        val short = node["short"]?.takeUnless { it.isNull }?.asText()
        val detailed = node["detailed"]?.takeUnless { it.isNull }?.asText()
        return ResultNormalizer.normalize(short, detailed, shortMaxLength, detailedMaxLength)
```

Добавить `import ru.zinin.frigate.analyzer.ai.description.core.ResultNormalizer`, удалить блоки `if (short.isBlank()) …`, `if (detailed.isBlank()) …`, `return DescriptionResult(...)` и метод `truncate`. Импорт `DescriptionResult` остаётся (тип возврата).

В `ClaudePromptBuilder.kt` заменить `val languageName = languageNameFor(request.language)` на `val languageName = LanguageNames.of(request.language)`, удалить приватный `languageNameFor`, добавить `import ru.zinin.frigate.analyzer.ai.description.core.LanguageNames`.

- [ ] **Step 6: Запустить тесты модуля**

Run: `./gradlew :frigate-analyzer-ai-description:test`
Expected: PASS, включая старые `ClaudeResponseParserTest` (обрезка теперь идёт через normalizer) и `ClaudePromptBuilderTest` (`rejects unknown language code` ждёт `IllegalStateException`, который даёт `error(...)`).

- [ ] **Step 7: Commit**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/core/ResultNormalizer.kt \
        modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/core/LanguageNames.kt \
        modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeResponseParser.kt \
        modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudePromptBuilder.kt \
        modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/core/ResultNormalizerTest.kt \
        modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/core/LanguageNamesTest.kt
git commit -m "refactor(ai-description): extract ResultNormalizer and LanguageNames into core" \
           -m "Claude-Session: https://claude.ai/code/session_015ZWCHwRx1akjYX3o1wtJ6S"
```

---

### Task 3: SPI `DescriptionBackend` и `DefaultDescriptionAgent`

**Files:**
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/core/DescriptionBackend.kt`
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/core/DefaultDescriptionAgent.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/core/DefaultDescriptionAgentTest.kt`

**Interfaces:**
- Consumes: `DescriptionException.Unauthorized`, `DescriptionProviderAuthEvent` (Task 1).
- Produces: `interface DescriptionBackend { val providerId: String; val authRecoveryHint: String; suspend fun describe(request: DescriptionRequest): DescriptionResult }`; `class DefaultDescriptionAgent(backend: DescriptionBackend, descriptionProperties: DescriptionProperties, eventPublisher: ApplicationEventPublisher, timeSource: TimeSource = TimeSource.Monotonic) : DescriptionAgent`. Не `@Component`: бин создаёт автоконфигурация в Task 4.

- [ ] **Step 1: Написать падающий тест**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.springframework.context.ApplicationEventPublisher
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionProviderAuthEvent
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class DefaultDescriptionAgentTest {
    private val common =
        DescriptionProperties.CommonSection(
            language = "en",
            shortMaxLength = 200,
            detailedMaxLength = 1500,
            maxFrames = 10,
            queueTimeout = Duration.ofSeconds(30),
            timeout = Duration.ofSeconds(60),
            maxConcurrent = 2,
        )

    private val request =
        DescriptionRequest(
            recordingId = UUID.randomUUID(),
            frames = listOf(DescriptionRequest.FrameImage(0, ByteArray(1))),
            language = "en",
            shortMaxLength = 200,
            detailedMaxLength = 1500,
        )

    private val ok = DescriptionResult("s", "d")
    private val events = mutableListOf<Any>()
    private val publisher = ApplicationEventPublisher { event -> events.add(event) }

    private class FakeBackend(
        private val handler: suspend (DescriptionRequest) -> DescriptionResult,
    ) : DescriptionBackend {
        override val providerId = "fake"
        override val authRecoveryHint = "run fake-login"
        val calls = AtomicInteger()

        override suspend fun describe(request: DescriptionRequest): DescriptionResult {
            calls.incrementAndGet()
            return handler(request)
        }
    }

    private fun build(
        backend: FakeBackend,
        customCommon: DescriptionProperties.CommonSection = common,
        timeSource: TimeSource = TimeSource.Monotonic,
    ) = DefaultDescriptionAgent(
        backend = backend,
        descriptionProperties = DescriptionProperties(enabled = true, provider = "fake", common = customCommon),
        eventPublisher = publisher,
        timeSource = timeSource,
    )

    private fun authEvents() = events.filterIsInstance<DescriptionProviderAuthEvent>()

    @Test
    fun `happy path returns backend result and publishes nothing`() =
        runTest {
            val agent = build(FakeBackend { ok })
            assertEquals(ok, agent.describe(request))
            assertTrue(authEvents().isEmpty())
        }

    @Test
    fun `retries once on InvalidResponse then succeeds`() =
        runTest {
            var first = true
            val backend =
                FakeBackend {
                    if (first) {
                        first = false
                        throw DescriptionException.InvalidResponse()
                    }
                    ok
                }
            val agent = build(backend)
            agent.describe(request)
            assertEquals(2, backend.calls.get())
        }

    @Test
    fun `fails with InvalidResponse after two invalid responses`() =
        runTest {
            val agent = build(FakeBackend { throw DescriptionException.InvalidResponse() })
            assertFailsWith<DescriptionException.InvalidResponse> { agent.describe(request) }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `InvalidResponse retry gives up when budget exhausted`() =
        runTest {
            // timeout=10s, INVALID_RESPONSE_RETRY_MIN_BUDGET=5s. Первый вызов спит 8с виртуального
            // времени, остаток ~2с < 5с: агент отдаёт InvalidResponse без второго вызова, а не
            // уходит в повтор, который поймал бы внешний withTimeout как Timeout.
            val backend =
                FakeBackend {
                    delay(8_000)
                    throw DescriptionException.InvalidResponse()
                }
            val agent =
                build(
                    backend,
                    customCommon = common.copy(timeout = Duration.ofSeconds(10)),
                    timeSource = (this as TestScope).testTimeSource,
                )
            assertFailsWith<DescriptionException.InvalidResponse> { agent.describe(request) }
            assertEquals(1, backend.calls.get(), "second attempt must be skipped when remaining budget < threshold")
        }

    @Test
    fun `retries once on Transport then succeeds (virtual time)`() =
        runTest {
            var first = true
            val backend =
                FakeBackend {
                    if (first) {
                        first = false
                        throw DescriptionException.Transport()
                    }
                    ok
                }
            val agent = build(backend)
            agent.describe(request)
            assertEquals(2, backend.calls.get())
        }

    @Test
    fun `fails with Transport after two Transport errors`() =
        runTest {
            val agent = build(FakeBackend { throw DescriptionException.Transport() })
            assertFailsWith<DescriptionException.Transport> { agent.describe(request) }
        }

    @Test
    fun `unexpected backend exception is wrapped into Transport and retried once`() =
        runTest {
            var first = true
            val backend =
                FakeBackend {
                    if (first) {
                        first = false
                        throw IllegalStateException("boom")
                    }
                    ok
                }
            val agent = build(backend)
            assertEquals(ok, agent.describe(request))
            assertEquals(2, backend.calls.get())
        }

    @Test
    fun `RateLimited does not retry`() =
        runTest {
            val backend = FakeBackend { throw DescriptionException.RateLimited() }
            val agent = build(backend)
            assertFailsWith<DescriptionException.RateLimited> { agent.describe(request) }
            assertEquals(1, backend.calls.get())
        }

    @Test
    fun `Unauthorized does not retry and publishes LOST once per outage`() =
        runTest {
            val backend = FakeBackend { throw DescriptionException.Unauthorized("Not signed in") }
            val agent = build(backend)
            assertFailsWith<DescriptionException.Unauthorized> { agent.describe(request) }
            assertFailsWith<DescriptionException.Unauthorized> { agent.describe(request) }
            assertEquals(2, backend.calls.get())
            val lost = authEvents()
            assertEquals(1, lost.size)
            assertEquals(DescriptionProviderAuthEvent.State.LOST, lost.single().state)
            assertEquals("fake", lost.single().provider)
            assertEquals("Not signed in", lost.single().detail)
            assertEquals("run fake-login", lost.single().recoveryHint)
        }

    @Test
    fun `success after LOST publishes RESTORED once`() =
        runTest {
            var failing = true
            val backend =
                FakeBackend {
                    if (failing) throw DescriptionException.Unauthorized("Not signed in") else ok
                }
            val agent = build(backend)
            runCatching { agent.describe(request) }
            failing = false
            agent.describe(request)
            agent.describe(request)
            val states = authEvents().map { it.state }
            assertEquals(
                listOf(DescriptionProviderAuthEvent.State.LOST, DescriptionProviderAuthEvent.State.RESTORED),
                states,
            )
        }

    @Test
    fun `concurrent Unauthorized failures publish a single LOST`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val backend =
                FakeBackend {
                    gate.await()
                    throw DescriptionException.Unauthorized("Not signed in")
                }
            val agent = build(backend, customCommon = common.copy(maxConcurrent = 5))
            coroutineScope {
                repeat(5) { launch { runCatching { agent.describe(request) } } }
                advanceUntilIdle()
                gate.complete(Unit)
            }
            assertEquals(1, authEvents().size)
        }

    @Test
    fun `work timeout is normalized to DescriptionException_Timeout`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val agent =
                build(
                    FakeBackend {
                        gate.await()
                        ok
                    },
                    customCommon = common.copy(timeout = Duration.ofMillis(500)),
                )
            val job = async { runCatching { agent.describe(request) } }
            advanceTimeBy(1_000)
            advanceUntilIdle()
            assertFailsWith<DescriptionException.Timeout> { job.await().getOrThrow() }
        }

    @Test
    fun `queue timeout is normalized to DescriptionException_Timeout`() =
        runTest {
            val blocker = CompletableDeferred<Unit>()
            val agent =
                build(
                    FakeBackend {
                        blocker.await()
                        ok
                    },
                    customCommon =
                        common.copy(
                            maxConcurrent = 1,
                            queueTimeout = Duration.ofMillis(100),
                            timeout = Duration.ofSeconds(60),
                        ),
                )
            val first = async { runCatching { agent.describe(request) } }
            advanceTimeBy(1)
            val second = async { runCatching { agent.describe(request) } }
            advanceTimeBy(200)
            advanceUntilIdle()
            assertFailsWith<DescriptionException.Timeout> { second.await().getOrThrow() }
            blocker.complete(Unit)
            first.await()
        }

    @Test
    fun `third call waits for semaphore permit with maxConcurrent=2`() =
        runTest {
            val inFlight = AtomicInteger()
            val maxSeen = AtomicInteger()
            val agent =
                build(
                    FakeBackend {
                        val current = inFlight.incrementAndGet()
                        maxSeen.updateAndGet { kotlin.math.max(it, current) }
                        delay(100)
                        inFlight.decrementAndGet()
                        ok
                    },
                )
            coroutineScope {
                repeat(3) { launch { agent.describe(request) } }
            }
            // Ровно 2: и верхняя граница (лимит соблюдён), и нижняя (оба слота используются).
            assertEquals(2, maxSeen.get())
        }
}
```

- [ ] **Step 2: Запустить и убедиться, что не компилируется**

Run: `./gradlew :frigate-analyzer-ai-description:test --tests ru.zinin.frigate.analyzer.ai.description.core.DefaultDescriptionAgentTest`
Expected: FAIL, `Unresolved reference: DescriptionBackend`, `DefaultDescriptionAgent`.

- [ ] **Step 3: Создать `DescriptionBackend.kt`**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.core

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult

/**
 * SPI провайдера описаний: одна попытка без семафора, таймаутов и повторов. Всё это делает
 * [DefaultDescriptionAgent]. Реализация обязана бросать только `DescriptionException` или
 * `CancellationException`; любое другое исключение агент оборачивает в `Transport`.
 */
interface DescriptionBackend {
    /** `claude`, `grok`. Попадает в события авторизации и логи. */
    val providerId: String

    /** Команда, которой владелец чинит авторизацию; попадает в сообщение владельцу. */
    val authRecoveryHint: String

    suspend fun describe(request: DescriptionRequest): DescriptionResult
}
```

- [ ] **Step 4: Создать `DefaultDescriptionAgent.kt`**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.core

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import org.springframework.context.ApplicationEventPublisher
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionAgent
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionProviderAuthEvent
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.toKotlinDuration

private val logger = KotlinLogging.logger {}

/**
 * Единственная реализация [DescriptionAgent]. Провайдер-нейтральная оркестрация одной попытки
 * [DescriptionBackend.describe]: семафор на `maxConcurrent`, ожидание слота не дольше
 * `queueTimeout`, общий `withTimeout(timeout)`, по одному повтору на `InvalidResponse` (сразу) и
 * `Transport` (через [TRANSPORT_RETRY_DELAY]) с проверкой остатка бюджета. `Timeout`, `RateLimited`
 * и `Unauthorized` не повторяются.
 *
 * Состояние авторизации провайдера: первый `Unauthorized` после успеха или старта публикует
 * [DescriptionProviderAuthEvent] LOST, первый успех после него RESTORED. Переход делается через
 * `compareAndSet`, поэтому параллельные вызовы дают ровно одно событие.
 *
 * Не `@Component`: бин создаёт `AiDescriptionAutoConfiguration`, когда есть backend.
 */
class DefaultDescriptionAgent(
    private val backend: DescriptionBackend,
    descriptionProperties: DescriptionProperties,
    private val eventPublisher: ApplicationEventPublisher,
    // Wall-clock по умолчанию; тесты подставляют TestTimeSource из runTest, чтобы проверка
    // остатка бюджета жила в том же виртуальном времени, что и внешний withTimeout.
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : DescriptionAgent {
    private val commonSection: DescriptionProperties.CommonSection = descriptionProperties.common
    private val semaphore = Semaphore(commonSection.maxConcurrent)
    private val authState = AtomicReference(AuthState.HEALTHY)

    private enum class AuthState { HEALTHY, LOST }

    override suspend fun describe(request: DescriptionRequest): DescriptionResult {
        try {
            withTimeout(commonSection.queueTimeout.toMillis()) {
                semaphore.acquire()
            }
        } catch (e: TimeoutCancellationException) {
            throw DescriptionException.Timeout(cause = e)
        }

        val callStart = timeSource.markNow()
        try {
            return try {
                withTimeout(commonSection.timeout.toMillis()) {
                    executeWithRetry(request)
                }
            } catch (e: TimeoutCancellationException) {
                throw DescriptionException.Timeout(cause = e)
            }
        } finally {
            logger.debug {
                "Description via '${backend.providerId}' completed in ${callStart.elapsedNow()} " +
                    "for recording ${request.recordingId}"
            }
            semaphore.release()
        }
    }

    private suspend fun executeWithRetry(request: DescriptionRequest): DescriptionResult {
        val overallStart = timeSource.markNow()
        val totalBudget = commonSection.timeout.toKotlinDuration()
        var invalidRetries = 0
        var transportRetries = 0
        while (true) {
            try {
                val result = attempt(request)
                onSuccess()
                return result
            } catch (e: DescriptionException.Unauthorized) {
                onUnauthorized(e)
                throw e
            } catch (e: DescriptionException.InvalidResponse) {
                if (invalidRetries >= 1) throw e
                invalidRetries++
                val remaining = totalBudget - overallStart.elapsedNow()
                if (remaining <= INVALID_RESPONSE_RETRY_MIN_BUDGET) {
                    logger.warn(e) { "Invalid response from '${backend.providerId}' but retry budget exhausted (remaining=$remaining); giving up" }
                    throw e
                }
                logger.warn(e) { "Invalid response from '${backend.providerId}', retrying (attempt ${invalidRetries + 1}, remaining budget=$remaining)" }
            } catch (e: DescriptionException.Transport) {
                if (transportRetries >= 1) throw e
                transportRetries++
                val remaining = totalBudget - overallStart.elapsedNow()
                if (remaining <= TRANSPORT_RETRY_MIN_BUDGET) {
                    logger.warn(e) { "Transport error from '${backend.providerId}' but retry budget exhausted (remaining=$remaining); giving up" }
                    throw e
                }
                logger.warn(e) { "Transport error from '${backend.providerId}', retrying in $TRANSPORT_RETRY_DELAY (remaining budget=$remaining)" }
                delay(TRANSPORT_RETRY_DELAY)
            }
        }
    }

    /** Одна попытка; всё, что не DescriptionException и не отмена, становится Transport. */
    private suspend fun attempt(request: DescriptionRequest): DescriptionResult =
        try {
            backend.describe(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DescriptionException) {
            throw e
        } catch (e: Throwable) {
            throw DescriptionException.Transport(e)
        }

    private fun onUnauthorized(e: DescriptionException.Unauthorized) {
        if (!authState.compareAndSet(AuthState.HEALTHY, AuthState.LOST)) return
        logger.error(e) {
            "Description provider '${backend.providerId}' rejected the credentials; descriptions stay " +
                "unavailable until re-login. Fix: ${backend.authRecoveryHint}"
        }
        eventPublisher.publishEvent(
            DescriptionProviderAuthEvent(
                provider = backend.providerId,
                state = DescriptionProviderAuthEvent.State.LOST,
                detail = e.detail,
                recoveryHint = backend.authRecoveryHint,
            ),
        )
    }

    private fun onSuccess() {
        if (!authState.compareAndSet(AuthState.LOST, AuthState.HEALTHY)) return
        logger.info { "Description provider '${backend.providerId}' credentials work again" }
        eventPublisher.publishEvent(
            DescriptionProviderAuthEvent(
                provider = backend.providerId,
                state = DescriptionProviderAuthEvent.State.RESTORED,
                detail = null,
                recoveryHint = backend.authRecoveryHint,
            ),
        )
    }

    companion object {
        private val TRANSPORT_RETRY_DELAY = 5.seconds

        // Минимальный остаток бюджета перед повтором: пауза перед вызовом плюс запас на один
        // реальный вызов провайдера. Иначе внешний withTimeout отменил бы повтор посередине и
        // вместо честного Transport получился бы вводящий в заблуждение Timeout.
        private val TRANSPORT_RETRY_MIN_BUDGET = 10.seconds

        // То же для InvalidResponse, без паузы перед вызовом.
        private val INVALID_RESPONSE_RETRY_MIN_BUDGET = 5.seconds
    }
}
```

- [ ] **Step 5: Запустить тест**

Run: `./gradlew :frigate-analyzer-ai-description:test --tests ru.zinin.frigate.analyzer.ai.description.core.DefaultDescriptionAgentTest`
Expected: PASS, 15 тестов.

- [ ] **Step 6: Commit**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/core/DescriptionBackend.kt \
        modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/core/DefaultDescriptionAgent.kt \
        modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/core/DefaultDescriptionAgentTest.kt
git commit -m "feat(ai-description): provider-neutral DefaultDescriptionAgent over DescriptionBackend SPI" \
           -m "Claude-Session: https://claude.ai/code/session_015ZWCHwRx1akjYX3o1wtJ6S"
```

---

### Task 4: `ClaudeBackend` вместо `ClaudeDescriptionAgent`, агент из автоконфигурации

**Files:**
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeBackend.kt`
- Delete: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeDescriptionAgent.kt`
- Modify: `claude/ClaudePromptBuilder.kt`, `claude/ClaudeResponseParser.kt`, `claude/ClaudeImageStager.kt`, `claude/ClaudeExceptionMapper.kt`, `claude/ClaudeAsyncClientFactory.kt` (условие `provider=claude`)
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/config/AiDescriptionAutoConfiguration.kt`
- Test create: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeBackendTest.kt`
- Test rename: `ClaudeDescriptionAgentValidationTest.kt` → `ClaudeBackendValidationTest.kt`
- Test delete: `ClaudeDescriptionAgentTest.kt` (сценарии перенесены в Task 3)
- Test rename: `ClaudeDescriptionAgentIntegrationTest.kt` → `ClaudeBackendIntegrationTest.kt`
- Test modify: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/config/AiDescriptionAutoConfigurationTest.kt`

**Interfaces:**
- Consumes: `DescriptionBackend`, `DefaultDescriptionAgent` (Task 3).
- Produces: `class ClaudeBackend(claudeProperties: ClaudeProperties, promptBuilder: ClaudePromptBuilder, responseParser: ClaudeResponseParser, imageStager: ClaudeImageStager, invoker: ClaudeInvoker, exceptionMapper: ClaudeExceptionMapper) : DescriptionBackend` с `providerId = "claude"`; `@Bean fun descriptionAgent(backend, descriptionProperties, eventPublisher): DescriptionAgent` в `AiDescriptionAutoConfiguration`, условный на `enabled=true` и `@ConditionalOnBean(DescriptionBackend::class)`.

- [ ] **Step 1: Написать падающий тест `ClaudeBackendTest`**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.claude

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.springaicommunity.claude.agent.sdk.exceptions.ClaudeSDKException
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.ai.description.config.ClaudeProperties
import ru.zinin.frigate.analyzer.ai.description.testsupport.TestObjectMappers
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ClaudeBackendTest {
    private val claudeProps =
        ClaudeProperties(
            oauthToken = "token",
            model = "opus",
            cliPath = "",
            workingDirectory = "/tmp",
            proxy = ClaudeProperties.ProxySection("", "", ""),
            anthropic = ClaudeProperties.AnthropicSection(),
        )
    private val promptBuilder = mockk<ClaudePromptBuilder>()
    private val responseParser = ClaudeResponseParser(TestObjectMappers.internalMapper())
    private val imageStager = mockk<ClaudeImageStager>()
    private val exceptionMapper = ClaudeExceptionMapper()
    private val stagedPaths: List<Path> = listOf(Path.of("/tmp/f.jpg"))
    private val request =
        DescriptionRequest(
            recordingId = UUID.randomUUID(),
            frames = listOf(DescriptionRequest.FrameImage(0, ByteArray(1))),
            language = "en",
            shortMaxLength = 200,
            detailedMaxLength = 1500,
        )

    init {
        coEvery { imageStager.stage(any()) } returns stagedPaths
        coEvery { imageStager.cleanup(any()) } just Runs
        every { promptBuilder.build(any(), any()) } returns "prompt"
    }

    private fun build(invoker: ClaudeInvoker) =
        ClaudeBackend(
            claudeProperties = claudeProps,
            promptBuilder = promptBuilder,
            responseParser = responseParser,
            imageStager = imageStager,
            invoker = invoker,
            exceptionMapper = exceptionMapper,
        )

    @Test
    fun `happy path stages, invokes, parses and cleans up`() =
        runTest {
            val backend = build(ClaudeInvoker { """{"short": "s", "detailed": "d"}""" })
            assertEquals(DescriptionResult("s", "d"), backend.describe(request))
            coVerify(exactly = 1) { imageStager.cleanup(stagedPaths) }
        }

    @Test
    fun `invalid JSON is InvalidResponse and still cleans up`() =
        runTest {
            val backend = build(ClaudeInvoker { "not json" })
            assertFailsWith<DescriptionException.InvalidResponse> { backend.describe(request) }
            coVerify(exactly = 1) { imageStager.cleanup(stagedPaths) }
        }

    @Test
    fun `SDK exceptions go through the exception mapper`() =
        runTest {
            val backend = build(ClaudeInvoker { throw ClaudeSDKException("request was rate limited") })
            assertFailsWith<DescriptionException.RateLimited> { backend.describe(request) }
        }

    @Test
    fun `identifies itself as claude`() {
        val backend = build(ClaudeInvoker { "" })
        assertEquals("claude", backend.providerId)
        assert(backend.authRecoveryHint.contains("CLAUDE_CODE_OAUTH_TOKEN"))
    }
}
```

- [ ] **Step 2: Запустить и убедиться, что не компилируется**

Run: `./gradlew :frigate-analyzer-ai-description:test --tests ru.zinin.frigate.analyzer.ai.description.claude.ClaudeBackendTest`
Expected: FAIL, `Unresolved reference: ClaudeBackend`.

- [ ] **Step 3: Создать `ClaudeBackend.kt`**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.claude

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springaicommunity.claude.agent.sdk.Query
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.ai.description.config.ClaudeProperties
import ru.zinin.frigate.analyzer.ai.description.core.DescriptionBackend
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Одна попытка описания через Claude Code CLI: кадры во временные jpg, промпт со ссылками
 * `@/abs/path`, вызов SDK, разбор JSON. Семафор, таймауты и повторы живут в
 * `DefaultDescriptionAgent`.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
@ConditionalOnProperty("application.ai.description.provider", havingValue = "claude")
class ClaudeBackend(
    private val claudeProperties: ClaudeProperties,
    private val promptBuilder: ClaudePromptBuilder,
    private val responseParser: ClaudeResponseParser,
    private val imageStager: ClaudeImageStager,
    private val invoker: ClaudeInvoker,
    private val exceptionMapper: ClaudeExceptionMapper,
) : DescriptionBackend {
    override val providerId: String = "claude"
    override val authRecoveryHint: String =
        "set CLAUDE_CODE_OAUTH_TOKEN from `claude setup-token` (or ANTHROPIC_AUTH_TOKEN) and restart"

    init {
        check(claudeProperties.oauthToken.isNotBlank() || claudeProperties.anthropic.authToken.isNotBlank()) {
            "At least one of CLAUDE_CODE_OAUTH_TOKEN or ANTHROPIC_AUTH_TOKEN must be set " +
                "when application.ai.description.enabled=true"
        }
        // CLI detection зависит от cliPath: пустой → which claude; non-empty → проверяем executable напрямую.
        if (claudeProperties.cliPath.isBlank()) {
            if (!Query.isCliInstalled()) {
                logger.warn {
                    "Claude CLI not found in PATH (Query.isCliInstalled()==false); all description " +
                        "requests will return fallback. Check Dockerfile ENV PATH=... and claude install."
                }
            }
        } else {
            val cliFile = Path.of(claudeProperties.cliPath)
            if (!Files.isExecutable(cliFile)) {
                logger.warn {
                    "Explicit claude.cli-path='${claudeProperties.cliPath}' not found or not executable; " +
                        "all description requests will return fallback."
                }
            }
        }
    }

    override suspend fun describe(request: DescriptionRequest): DescriptionResult {
        val stagedPaths = imageStager.stage(request)
        try {
            val prompt = promptBuilder.build(request, stagedPaths)
            val raw =
                try {
                    invoker.invoke(prompt)
                } catch (e: Throwable) {
                    // map() пробрасывает CancellationException как есть, см. его KDoc.
                    throw exceptionMapper.map(e)
                }
            return responseParser.parse(raw, request.shortMaxLength, request.detailedMaxLength)
        } finally {
            // cleanup сам работает под NonCancellable.
            imageStager.cleanup(stagedPaths)
        }
    }
}
```

- [ ] **Step 4: Удалить `ClaudeDescriptionAgent.kt` и его тесты, переименовать остальные**

```bash
git rm modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeDescriptionAgent.kt \
       modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeDescriptionAgentTest.kt
git mv modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeDescriptionAgentValidationTest.kt \
       modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeBackendValidationTest.kt
git mv modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeDescriptionAgentIntegrationTest.kt \
       modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeBackendIntegrationTest.kt
```

Содержимое `ClaudeBackendValidationTest.kt` целиком:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.claude

import io.mockk.mockk
import ru.zinin.frigate.analyzer.ai.description.config.ClaudeProperties
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ClaudeBackendValidationTest {
    private fun backend(
        oauthToken: String = "token",
        authToken: String = "",
    ): ClaudeBackend =
        ClaudeBackend(
            claudeProperties =
                ClaudeProperties(
                    oauthToken = oauthToken,
                    model = "opus",
                    cliPath = "",
                    workingDirectory = "/tmp",
                    proxy = ClaudeProperties.ProxySection("", "", ""),
                    anthropic = ClaudeProperties.AnthropicSection(authToken = authToken),
                ),
            promptBuilder = mockk(),
            responseParser = mockk(),
            imageStager = mockk(),
            invoker = mockk(),
            exceptionMapper = mockk(),
        )

    @Test
    fun `init rejects when both tokens blank`() {
        assertFailsWith<IllegalStateException> { backend(oauthToken = "", authToken = "") }
    }

    @Test
    fun `init rejects when both tokens whitespace`() {
        assertFailsWith<IllegalStateException> { backend(oauthToken = "   ", authToken = "   ") }
    }

    @Test
    fun `init accepts oauth token only`() {
        backend(oauthToken = "token-xyz")
    }

    @Test
    fun `init accepts anthropic auth token only`() {
        backend(oauthToken = "", authToken = "sk-sp-xxx")
    }

    @Test
    fun `init accepts both tokens`() {
        backend(oauthToken = "token-xyz", authToken = "sk-sp-xxx")
    }
}
```

В `ClaudeBackendIntegrationTest.kt` (остаётся `@Disabled`): переименовать класс в `ClaudeBackendIntegrationTest`, в KDoc заменить `[ClaudeDescriptionAgent]` на `[ClaudeBackend]` и `[ClaudeDescriptionAgentTest]` на `[ClaudeBackendTest]`, а сборку агента заменить на:

```kotlin
        val backend =
            ClaudeBackend(
                claudeProperties = claudeProps,
                promptBuilder = ClaudePromptBuilder(),
                responseParser = ClaudeResponseParser(mapper),
                imageStager = stager,
                invoker = invoker,
                exceptionMapper = ClaudeExceptionMapper(),
            )
        val agent = DefaultDescriptionAgent(backend, descriptionProps, ApplicationEventPublisher { })
```

с импортами `org.springframework.context.ApplicationEventPublisher` и `ru.zinin.frigate.analyzer.ai.description.core.DefaultDescriptionAgent`.

- [ ] **Step 5: Условие `provider=claude` на помощниках Claude**

В каждом из `ClaudePromptBuilder.kt`, `ClaudeResponseParser.kt`, `ClaudeImageStager.kt`, `ClaudeExceptionMapper.kt`, `ClaudeAsyncClientFactory.kt` после существующей строки

```kotlin
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
```

добавить

```kotlin
@ConditionalOnProperty("application.ai.description.provider", havingValue = "claude")
```

`DefaultClaudeInvoker` уже несёт оба условия.

- [ ] **Step 6: `@Bean` агента в автоконфигурации**

`AiDescriptionAutoConfiguration.kt` целиком:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.config

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionAgent
import ru.zinin.frigate.analyzer.ai.description.core.DefaultDescriptionAgent
import ru.zinin.frigate.analyzer.ai.description.core.DescriptionBackend

@AutoConfiguration
@ComponentScan("ru.zinin.frigate.analyzer.ai.description")
@EnableConfigurationProperties(DescriptionProperties::class, ClaudeProperties::class)
open class AiDescriptionAutoConfiguration {
    /**
     * Агент существует только вместе с backend-ом выбранного провайдера. `@ConditionalOnBean`
     * надёжен здесь потому, что это `@AutoConfiguration`: `@Bean`-методы читаются после того, как
     * `@ComponentScan` этого же класса зарегистрировал backend-ы. Неизвестный `provider` даёт
     * отсутствие агента и WARN от [DescriptionAgentSanityChecker], как раньше.
     */
    @Bean
    @ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
    @ConditionalOnBean(DescriptionBackend::class)
    fun descriptionAgent(
        backend: DescriptionBackend,
        descriptionProperties: DescriptionProperties,
        eventPublisher: ApplicationEventPublisher,
    ): DescriptionAgent = DefaultDescriptionAgent(backend, descriptionProperties, eventPublisher)
}
```

- [ ] **Step 7: Дополнить `AiDescriptionAutoConfigurationTest`**

В тест `autoconfig activates beans when enabled=true, provider=claude` внутри `run { ctx -> … }` добавить:

```kotlin
                assert(ctx.getBean(DescriptionAgent::class.java) is DefaultDescriptionAgent) {
                    "the agent must be the provider-neutral DefaultDescriptionAgent"
                }
                assert(ctx.getBeansOfType(ClaudeBackend::class.java).isNotEmpty()) {
                    "ClaudeBackend should be registered for provider=claude"
                }
```

и новый тест (список свойств тот же, что в claude-тесте, но `provider=unknown`):

```kotlin
    @Test
    fun `unknown provider registers neither backend nor agent and does not fail startup`() {
        runner
            .withPropertyValues(
                "application.ai.description.enabled=true",
                "application.ai.description.provider=unknown",
                "application.ai.description.common.language=en",
                "application.ai.description.common.short-max-length=200",
                "application.ai.description.common.detailed-max-length=1500",
                "application.ai.description.common.max-frames=10",
                "application.ai.description.common.queue-timeout=30s",
                "application.ai.description.common.timeout=60s",
                "application.ai.description.common.max-concurrent=2",
                "application.ai.description.common.rate-limit.enabled=false",
                "application.ai.description.common.rate-limit.max-requests=10",
                "application.ai.description.common.rate-limit.window=1h",
                "application.ai.description.claude.oauth-token=fake",
                "application.ai.description.claude.model=opus",
                "application.ai.description.claude.cli-path=",
                "application.ai.description.claude.working-directory=/tmp",
                "application.ai.description.claude.proxy.http=",
                "application.ai.description.claude.proxy.https=",
                "application.ai.description.claude.proxy.no-proxy=",
                "application.ai.description.claude.anthropic.auth-token=",
                "application.ai.description.claude.anthropic.base-url=",
                "application.ai.description.claude.anthropic.model-override=",
                "application.ai.description.claude.anthropic.default-opus-model=",
                "application.ai.description.claude.anthropic.default-sonnet-model=",
                "application.ai.description.claude.anthropic.default-haiku-model=",
            ).run { ctx ->
                assert(ctx.startupFailure == null) { "unknown provider must not break startup: ${ctx.startupFailure}" }
                assert(ctx.getBeansOfType(DescriptionBackend::class.java).isEmpty())
                assert(ctx.getBeansOfType(DescriptionAgent::class.java).isEmpty())
                assert(ctx.getBeansOfType(ClaudeAsyncClientFactory::class.java).isEmpty()) {
                    "Claude helpers must be gated on provider=claude"
                }
            }
    }
```

Импорты: `ru.zinin.frigate.analyzer.ai.description.claude.ClaudeAsyncClientFactory`, `ru.zinin.frigate.analyzer.ai.description.claude.ClaudeBackend`, `ru.zinin.frigate.analyzer.ai.description.core.DefaultDescriptionAgent`, `ru.zinin.frigate.analyzer.ai.description.core.DescriptionBackend`.

- [ ] **Step 8: Запустить тесты модуля**

Run: `./gradlew :frigate-analyzer-ai-description:test`
Expected: PASS. Если ktlint ругается на порядок импортов, `./gradlew ktlintFormat` и повтор.

- [ ] **Step 9: Commit**

```bash
git add -A modules/ai-description/src
git commit -m "refactor(ai-description): ClaudeBackend behind DescriptionBackend, agent bean from auto-configuration" \
           -m "Claude-Session: https://claude.ai/code/session_015ZWCHwRx1akjYX3o1wtJ6S"
```

---

### Task 5: `ClaudeExceptionMapper` распознаёт отказ авторизации

**Files:**
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeExceptionMapper.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeExceptionMapperTest.kt`

**Interfaces:**
- Produces: `ClaudeSDKException` с текстом, содержащим `authentication_error`, `invalid api key` или `oauth token` (без учёта регистра), маппится в `DescriptionException.Unauthorized(message)`.

- [ ] **Step 1: Добавить падающие тесты в `ClaudeExceptionMapperTest`**

```kotlin
    @Test
    fun `authentication_error maps to Unauthorized`() {
        val e = mapper.map(ClaudeSDKException("API Error: 401 {\"type\":\"authentication_error\"}"))
        assertIs<DescriptionException.Unauthorized>(e)
        assert(e.detail.contains("authentication_error"))
    }

    @Test
    fun `invalid api key maps to Unauthorized`() {
        assertIs<DescriptionException.Unauthorized>(mapper.map(ClaudeSDKException("Invalid API key provided")))
    }

    @Test
    fun `expired oauth token maps to Unauthorized`() {
        assertIs<DescriptionException.Unauthorized>(mapper.map(ClaudeSDKException("OAuth token has expired")))
    }

    @Test
    fun `Unauthorized wins over rate-limit words in the same message`() {
        val e = mapper.map(ClaudeSDKException("authentication_error while checking rate limit"))
        assertIs<DescriptionException.Unauthorized>(e)
    }
```

- [ ] **Step 2: Запустить и увидеть падение**

Run: `./gradlew :frigate-analyzer-ai-description:test --tests ru.zinin.frigate.analyzer.ai.description.claude.ClaudeExceptionMapperTest`
Expected: FAIL, четыре новых теста получают `Transport` вместо `Unauthorized`.

- [ ] **Step 3: Реализовать ветку**

В `ClaudeExceptionMapper.map` заменить ветку `is ClaudeSDKException -> { … }` на:

```kotlin
            // Авторизация проверяется раньше rate limit, а rate limit раньше общего Transport:
            // Unauthorized не повторяется и поднимает событие, RateLimited не повторяется,
            // Transport повторяется один раз.
            is ClaudeSDKException -> {
                when {
                    isUnauthorized(throwable) -> DescriptionException.Unauthorized(throwable.message ?: "authentication error", throwable)
                    isRateLimit(throwable) -> DescriptionException.RateLimited(throwable)
                    else -> DescriptionException.Transport(throwable)
                }
            }
```

и добавить в класс:

```kotlin
    private fun isUnauthorized(throwable: Throwable): Boolean {
        val message = throwable.message?.lowercase() ?: return false
        return AUTH_MARKERS.any { it in message }
    }

    private companion object {
        val AUTH_MARKERS = listOf("authentication_error", "invalid api key", "oauth token")
    }
```

- [ ] **Step 4: Запустить тесты модуля**

Run: `./gradlew :frigate-analyzer-ai-description:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeExceptionMapper.kt \
        modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeExceptionMapperTest.kt
git commit -m "feat(ai-description): map Claude authentication errors to Unauthorized" \
           -m "Claude-Session: https://claude.ai/code/session_015ZWCHwRx1akjYX3o1wtJ6S"
```

---

### Task 6: `GrokProperties`, yaml и тесты биндинга

**Files:**
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/config/GrokProperties.kt`
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/config/AiDescriptionAutoConfiguration.kt` (регистрация `GrokProperties`)
- Modify: `modules/core/src/main/resources/application.yaml` (после блока `claude.proxy`, перед `detection-filter:`)
- Modify: `modules/core/src/test/resources/application.yaml` (после блока `claude.proxy`, перед `detection-filter:`)
- Test rewrite: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/config/AiDescriptionAutoConfigurationTest.kt`
- Test create: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/properties/GrokPropertiesBindingTest.kt`

**Interfaces:**
- Produces: `data class GrokProperties(cliPath: String, model: String, effort: String, home: String, workingDirectory: String, proxy: ProxySection)` с `val homePath: Path`, `val workingDirectoryPath: Path`, `data class ProxySection(http: String, https: String, noProxy: String)`; префикс `application.ai.description.grok`.

- [ ] **Step 1: Написать падающий тест биндинга в core**

```kotlin
package ru.zinin.frigate.analyzer.core.config.properties

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.ai.description.config.GrokProperties
import java.nio.file.Path

/**
 * Binds `application.ai.description.grok` out of the production yaml via [ProductionYamlBinder].
 * The section binds on every deployment, Claude ones included, so a defaulting mistake here would
 * stop a container that never asked for Grok.
 */
class GrokPropertiesBindingTest {
    @Test
    fun `defaults follow the spec`() {
        val props = bind()

        assertThat(props.cliPath).isEmpty()
        assertThat(props.model).isEqualTo("grok-4.6")
        assertThat(props.effort).isEqualTo("low")
        assertThat(props.homePath).isEqualTo(Path.of("/tmp/frigate-analyzer/grok-home"))
        assertThat(props.workingDirectoryPath).isEqualTo(Path.of("/tmp/frigate-analyzer/grok-cwd"))
        assertThat(props.proxy.http).isEmpty()
        assertThat(props.proxy.https).isEmpty()
        assertThat(props.proxy.noProxy).isEmpty()
    }

    @Test
    fun `an empty GROK_EFFORT binds to an empty string, not the default`() {
        val props = bind(env = mapOf("GROK_EFFORT" to ""))

        assertThat(props.effort).isEmpty()
    }

    @Test
    fun `GROK_HOME and GROK_MODEL override the defaults`() {
        val props = bind(env = mapOf("GROK_HOME" to "/application/grok-home", "GROK_MODEL" to "dks-vision"))

        assertThat(props.homePath).isEqualTo(Path.of("/application/grok-home"))
        assertThat(props.model).isEqualTo("dks-vision")
    }

    @Test
    fun `TEMP_FOLDER moves both default directories`() {
        val props = bind(env = mapOf("TEMP_FOLDER" to "/var/tmp/fa"))

        assertThat(props.homePath).isEqualTo(Path.of("/var/tmp/fa/grok-home"))
        assertThat(props.workingDirectoryPath).isEqualTo(Path.of("/var/tmp/fa/grok-cwd"))
    }

    private fun bind(env: Map<String, Any> = emptyMap()): GrokProperties =
        ProductionYamlBinder.bind("application.ai.description.grok", GrokProperties::class.java, env)
}
```

- [ ] **Step 2: Запустить и убедиться, что не компилируется**

Run: `./gradlew :frigate-analyzer-core:test --tests ru.zinin.frigate.analyzer.core.config.properties.GrokPropertiesBindingTest`
Expected: FAIL, `Unresolved reference: GrokProperties`.

- [ ] **Step 3: Создать `GrokProperties.kt`**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.config

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.nio.file.Path

/**
 * Провайдер `grok`: headless-вызов бинарника Grok Build. Биндится всегда, как [ClaudeProperties],
 * поэтому дефолты в yaml обязаны быть валидными и при `provider=claude`.
 */
@ConfigurationProperties(prefix = "application.ai.description.grok")
@Validated
data class GrokProperties(
    /** Пусто = `grok` ищется по PATH. */
    val cliPath: String,
    /** Модель xAI или имя `[model.<name>]` BYOK-записи из `config.toml` в [home]. */
    @field:NotBlank
    val model: String,
    /** Пусто = флаг `--effort` не передаётся (BYOK-модели без уровней reasoning). */
    val effort: String,
    /** `GROK_HOME` дочернего процесса: `auth.json`, `config.toml`, сессии. В контейнере это том. */
    @field:NotBlank
    val home: String,
    /** Пустой каталог для `--cwd`: Grok читает из cwd AGENTS.md, CLAUDE.md, `.claude/rules`, `.grok`. */
    @field:NotBlank
    val workingDirectory: String,
    @field:Valid
    val proxy: ProxySection,
) {
    val homePath: Path
        get() = Path.of(home).toAbsolutePath().normalize()

    val workingDirectoryPath: Path
        get() = Path.of(workingDirectory).toAbsolutePath().normalize()

    data class ProxySection(
        val http: String,
        val https: String,
        val noProxy: String,
    )
}
```

- [ ] **Step 4: Зарегистрировать в автоконфигурации**

В `AiDescriptionAutoConfiguration.kt` заменить `@EnableConfigurationProperties(DescriptionProperties::class, ClaudeProperties::class)` на `@EnableConfigurationProperties(DescriptionProperties::class, ClaudeProperties::class, GrokProperties::class)`.

- [ ] **Step 5: Секция в production yaml**

В `modules/core/src/main/resources/application.yaml` после строки `          no-proxy: ${CLAUDE_NO_PROXY:}` (конец блока `claude`) и перед `  detection-filter:` вставить:

```yaml
      grok:
        cli-path: ${GROK_CLI_PATH:}
        model: ${GROK_MODEL:grok-4.6}
        # Пусто = флаг --effort не передаётся (BYOK-модели без уровней reasoning).
        effort: ${GROK_EFFORT:low}
        # Собственный каталог Grok: auth.json, config.toml, сессии. В контейнере это том.
        # Та же переменная ведёт ручной `grok login` внутри `docker compose exec`.
        home: ${GROK_HOME:${application.temp-folder}/grok-home}
        # Пустой каталог для --cwd: Grok читает из cwd AGENTS.md, CLAUDE.md, .claude/rules и .grok.
        working-directory: ${GROK_WORKING_DIR:${application.temp-folder}/grok-cwd}
        proxy:
          http: ${GROK_HTTP_PROXY:}
          https: ${GROK_HTTPS_PROXY:}
          no-proxy: ${GROK_NO_PROXY:}
```

- [ ] **Step 6: Секция в тестовом yaml**

В `modules/core/src/test/resources/application.yaml` после `          no-proxy: ""` блока `claude` и перед `  detection-filter:` вставить:

```yaml
      grok:
        cli-path: ""
        model: grok-4.6
        effort: low
        home: /tmp/frigate-analyzer/grok-home
        working-directory: /tmp/frigate-analyzer/grok-cwd
        proxy:
          http: ""
          https: ""
          no-proxy: ""
```

- [ ] **Step 7: Переписать `AiDescriptionAutoConfigurationTest` с общим списком свойств**

Файл целиком (grok-тест с backend-ом добавится в Task 12):

```kotlin
package ru.zinin.frigate.analyzer.ai.description.config

import io.mockk.mockk
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.util.unit.DataSize
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionAgent
import ru.zinin.frigate.analyzer.ai.description.api.TempFileWriter
import ru.zinin.frigate.analyzer.ai.description.claude.ClaudeAsyncClientFactory
import ru.zinin.frigate.analyzer.ai.description.claude.ClaudeBackend
import ru.zinin.frigate.analyzer.ai.description.core.DefaultDescriptionAgent
import ru.zinin.frigate.analyzer.ai.description.core.DescriptionBackend
import ru.zinin.frigate.analyzer.ai.description.ratelimit.DescriptionRateLimiter
import ru.zinin.frigate.analyzer.ai.description.testsupport.TestObjectMappers
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals

class AiDescriptionAutoConfigurationTest {
    @TempDir
    lateinit var tempDir: Path

    private val runner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AiDescriptionAutoConfiguration::class.java))
            .withUserConfiguration(TestStubConfig::class.java)

    @Configuration
    class TestStubConfig {
        // TempFileWriter is an SPI — in production provided by the core module.
        @Bean
        fun tempFileWriter(): TempFileWriter = mockk(relaxed = true)

        // ObjectMapper is provided by Spring Boot's JacksonAutoConfiguration in production
        // (via spring-boot-jackson on the runtime classpath of the main application).
        // This module does not depend on spring-boot-jackson, so we supply a plain mapper here.
        // Return type is tools.jackson JsonMapper so Spring registers the bean as a
        // tools.jackson.databind.ObjectMapper (its supertype).
        @Bean
        fun objectMapper(): JsonMapper = TestObjectMappers.internalMapper()

        // Clock is provided in production by `:frigate-analyzer-common`'s ClockConfig.
        @Bean
        fun clock(): Clock = Clock.systemUTC()
    }

    /**
     * Полный набор свойств модуля в стиле application.yaml. Обе провайдерские секции биндятся
     * всегда, поэтому присутствуют при любом provider; grok.home и working-directory указывают
     * в @TempDir, чтобы GrokBackend.init не создавал каталоги в /tmp/frigate-analyzer.
     */
    private fun properties(
        enabled: Boolean,
        provider: String,
        rateLimitEnabled: Boolean = false,
        grokModel: String = "grok-4.6",
    ): Array<String> =
        arrayOf(
            "application.ai.description.enabled=$enabled",
            "application.ai.description.provider=$provider",
            "application.ai.description.common.language=en",
            "application.ai.description.common.short-max-length=200",
            "application.ai.description.common.detailed-max-length=1500",
            "application.ai.description.common.max-frames=10",
            "application.ai.description.common.queue-timeout=30s",
            "application.ai.description.common.timeout=60s",
            "application.ai.description.common.max-concurrent=2",
            "application.ai.description.common.rate-limit.enabled=$rateLimitEnabled",
            "application.ai.description.common.rate-limit.max-requests=10",
            "application.ai.description.common.rate-limit.window=1h",
            "application.ai.description.claude.oauth-token=fake",
            "application.ai.description.claude.model=opus",
            "application.ai.description.claude.cli-path=",
            "application.ai.description.claude.working-directory=/tmp",
            "application.ai.description.claude.proxy.http=",
            "application.ai.description.claude.proxy.https=",
            "application.ai.description.claude.proxy.no-proxy=",
            "application.ai.description.claude.anthropic.auth-token=",
            "application.ai.description.claude.anthropic.base-url=",
            "application.ai.description.claude.anthropic.model-override=",
            "application.ai.description.claude.anthropic.default-opus-model=",
            "application.ai.description.claude.anthropic.default-sonnet-model=",
            "application.ai.description.claude.anthropic.default-haiku-model=",
            "application.ai.description.claude.max-buffer-size=32MB",
            "application.ai.description.grok.cli-path=${tempDir.resolve("missing-grok")}",
            "application.ai.description.grok.model=$grokModel",
            "application.ai.description.grok.effort=low",
            "application.ai.description.grok.home=${tempDir.resolve("grok-home")}",
            "application.ai.description.grok.working-directory=${tempDir.resolve("grok-cwd")}",
            "application.ai.description.grok.proxy.http=",
            "application.ai.description.grok.proxy.https=",
            "application.ai.description.grok.proxy.no-proxy=",
        )

    @Test
    fun `DescriptionProperties registered even when enabled=false`() {
        // Критично: facade инжектит DescriptionProperties безусловно — бин должен быть всегда.
        runner
            .withPropertyValues(*properties(enabled = false, provider = "claude"))
            .run { ctx ->
                assert(ctx.getBeansOfType(DescriptionProperties::class.java).isNotEmpty()) {
                    "DescriptionProperties must be available when enabled=false (facade inject)"
                }
                assert(ctx.getBeansOfType(GrokProperties::class.java).isNotEmpty()) {
                    "GrokProperties binds regardless of provider"
                }
                assert(ctx.getBeansOfType(DescriptionAgent::class.java).isEmpty()) {
                    "DescriptionAgent must NOT be registered when enabled=false"
                }
            }
    }

    @Test
    fun `autoconfig activates beans when enabled=true, provider=claude`() {
        runner
            .withPropertyValues(*properties(enabled = true, provider = "claude"))
            .run { ctx ->
                assert(ctx.getBean(DescriptionAgent::class.java) is DefaultDescriptionAgent) {
                    "the agent must be the provider-neutral DefaultDescriptionAgent"
                }
                assert(ctx.getBeansOfType(ClaudeBackend::class.java).isNotEmpty()) {
                    "ClaudeBackend should be registered for provider=claude"
                }
                // Строка в стиле application.yaml должна привязаться к DataSize: это единственное
                // место, где реальный старт может упасть, а полный build в CI его не проверяет.
                assertEquals(DataSize.ofMegabytes(32), ctx.getBean(ClaudeProperties::class.java).maxBufferSize)
            }
    }

    @Test
    fun `unknown provider registers neither backend nor agent and does not fail startup`() {
        runner
            .withPropertyValues(*properties(enabled = true, provider = "unknown"))
            .run { ctx ->
                assert(ctx.startupFailure == null) { "unknown provider must not break startup: ${ctx.startupFailure}" }
                assert(ctx.getBeansOfType(DescriptionBackend::class.java).isEmpty())
                assert(ctx.getBeansOfType(DescriptionAgent::class.java).isEmpty())
                assert(ctx.getBeansOfType(ClaudeAsyncClientFactory::class.java).isEmpty()) {
                    "Claude helpers must be gated on provider=claude"
                }
            }
    }

    @Test
    fun `DescriptionRateLimiter bean registered when ai-description and rate-limit both enabled`() {
        runner
            .withPropertyValues(*properties(enabled = true, provider = "claude", rateLimitEnabled = true))
            .run { ctx ->
                assert(ctx.getBeansOfType(DescriptionRateLimiter::class.java).isNotEmpty()) {
                    "DescriptionRateLimiter must be registered when ai-description.enabled=true (regardless of rate-limit.enabled)"
                }
            }
    }

    @Test
    fun `blank grok model fails binding even for provider=claude`() {
        // GrokProperties биндится всегда: пустой GROK_MODEL валит старт любого деплоя,
        // ровно как пустой CLAUDE_MODEL. Тест делает это свойство явным.
        runner
            .withPropertyValues(*properties(enabled = true, provider = "claude", grokModel = ""))
            .run { ctx ->
                assert(ctx.startupFailure != null) { "blank grok.model must fail validation" }
            }
    }
}
```

- [ ] **Step 8: Запустить тесты обоих модулей**

Run: `./gradlew :frigate-analyzer-ai-description:test :frigate-analyzer-core:test --tests '*AiDescriptionAutoConfigurationTest' --tests '*GrokPropertiesBindingTest'`
Expected: PASS. Если Gradle не принимает `--tests` для двух модулей разом, запустить по очереди: `./gradlew :frigate-analyzer-ai-description:test` и `./gradlew :frigate-analyzer-core:test --tests ru.zinin.frigate.analyzer.core.config.properties.GrokPropertiesBindingTest`.

- [ ] **Step 9: Commit**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/config/GrokProperties.kt \
        modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/config/AiDescriptionAutoConfiguration.kt \
        modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/config/AiDescriptionAutoConfigurationTest.kt \
        modules/core/src/main/resources/application.yaml \
        modules/core/src/test/resources/application.yaml \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/properties/GrokPropertiesBindingTest.kt
git commit -m "feat(ai-description): GrokProperties and application.ai.description.grok section" \
           -m "Claude-Session: https://claude.ai/code/session_015ZWCHwRx1akjYX3o1wtJ6S"
```

---

### Task 7: `GrokPromptBuilder` и `GrokPromptFileWriter`

**Files:**
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokPromptBuilder.kt`
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokPromptFileWriter.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokPromptBuilderTest.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokPromptFileWriterTest.kt`

**Interfaces:**
- Consumes: `LanguageNames.of(code)` (Task 2), `TempFileWriter` (api).
- Produces: `class GrokPromptBuilder { fun introduction(language: String): String; fun frameLabel(frameIndex: Int): String; fun rules(shortMaxLength: Int, detailedMaxLength: Int): String; companion object { const val SYSTEM_PROMPT } }`; `class GrokPromptFileWriter(tempFileWriter: TempFileWriter, promptBuilder: GrokPromptBuilder, objectMapper: tools.jackson.databind.ObjectMapper) { suspend fun write(request: DescriptionRequest): Path; internal fun buildBlocks(request): List<Map<String, String>>; suspend fun delete(path: Path) }`.

- [ ] **Step 1: Написать падающие тесты**

`GrokPromptBuilderTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.grok

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GrokPromptBuilderTest {
    private val builder = GrokPromptBuilder()

    @Test
    fun `introduction names the language`() {
        assertTrue(builder.introduction("en").contains("in English"))
        assertTrue(builder.introduction("ru").contains("in Russian"))
    }

    @Test
    fun `introduction announces the frames block`() {
        assertTrue(builder.introduction("en").endsWith("Frames (in chronological order):"))
    }

    @Test
    fun `introduction rejects unknown language`() {
        assertFailsWith<IllegalStateException> { builder.introduction("de") }
    }

    @Test
    fun `frame label carries the frame index`() {
        assertEquals("Frame 17:", builder.frameLabel(17))
    }

    @Test
    fun `rules carry both limits and both field names`() {
        val rules = builder.rules(150, 800)
        assertTrue(rules.contains("\"short\" must not exceed 150 characters"))
        assertTrue(rules.contains("\"detailed\" must not exceed 800 characters"))
        assertTrue(rules.contains("No markdown"))
    }

    @Test
    fun `system prompt forbids tools and asks for structured output`() {
        assertTrue(GrokPromptBuilder.SYSTEM_PROMPT.contains("structured output"))
        assertTrue(GrokPromptBuilder.SYSTEM_PROMPT.contains("Do not call tools"))
    }
}
```

`GrokPromptFileWriterTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.grok

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.TempFileWriter
import ru.zinin.frigate.analyzer.ai.description.testsupport.TestObjectMappers
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GrokPromptFileWriterTest {
    private val tempWriter = mockk<TempFileWriter>()
    private val mapper = TestObjectMappers.internalMapper()
    private val writer = GrokPromptFileWriter(tempWriter, GrokPromptBuilder(), mapper)

    private val recordingId = UUID.randomUUID()
    private val request =
        DescriptionRequest(
            recordingId = recordingId,
            frames =
                listOf(
                    DescriptionRequest.FrameImage(2, byteArrayOf(1, 2)),
                    DescriptionRequest.FrameImage(0, byteArrayOf(3, 4)),
                ),
            language = "ru",
            shortMaxLength = 150,
            detailedMaxLength = 800,
        )

    @Test
    fun `blocks are intro, label+image per frame in frameIndex order, rules`() {
        val blocks = writer.buildBlocks(request)

        assertEquals(6, blocks.size)
        assertEquals("text", blocks[0]["type"])
        assertTrue(blocks[0]["text"]!!.contains("in Russian"))
        assertEquals(mapOf("type" to "text", "text" to "Frame 0:"), blocks[1])
        assertEquals("image", blocks[2]["type"])
        assertEquals("image/jpeg", blocks[2]["mimeType"])
        assertEquals(Base64.getEncoder().encodeToString(byteArrayOf(3, 4)), blocks[2]["data"])
        assertEquals(mapOf("type" to "text", "text" to "Frame 2:"), blocks[3])
        assertEquals(Base64.getEncoder().encodeToString(byteArrayOf(1, 2)), blocks[4]["data"])
        assertEquals("text", blocks[5]["type"])
        assertTrue(blocks[5]["text"]!!.contains("150"))
    }

    @Test
    fun `write stores a json file whose content parses back to the blocks`() =
        runTest {
            val prefix = slot<String>()
            val suffix = slot<String>()
            val bytes = slot<ByteArray>()
            coEvery { tempWriter.createTempFile(capture(prefix), capture(suffix), capture(bytes)) } returns
                Path.of("/tmp/prompt.json")

            val path = writer.write(request)

            assertEquals(Path.of("/tmp/prompt.json"), path)
            assertEquals("grok-$recordingId", prefix.captured)
            assertEquals(".json", suffix.captured)
            val parsed = mapper.readTree(bytes.captured)
            assertTrue(parsed.isArray)
            assertEquals(6, parsed.size())
            assertEquals("image", parsed[2]["type"].asText())
        }

    @Test
    fun `delete removes the file through the temp writer`() =
        runTest {
            coEvery { tempWriter.deleteFiles(listOf(Path.of("/tmp/prompt.json"))) } returns 1

            writer.delete(Path.of("/tmp/prompt.json"))

            coVerify(exactly = 1) { tempWriter.deleteFiles(listOf(Path.of("/tmp/prompt.json"))) }
        }

    @Test
    fun `delete swallows temp writer failures`() =
        runTest {
            coEvery { tempWriter.deleteFiles(any()) } throws IllegalStateException("disk gone")

            writer.delete(Path.of("/tmp/prompt.json"))
        }
}
```

- [ ] **Step 2: Запустить и убедиться, что не компилируется**

Run: `./gradlew :frigate-analyzer-ai-description:test --tests 'ru.zinin.frigate.analyzer.ai.description.grok.*'`
Expected: FAIL, `Unresolved reference: GrokPromptBuilder`, `GrokPromptFileWriter`.

- [ ] **Step 3: Создать `GrokPromptBuilder.kt`**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.grok

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.core.LanguageNames

/**
 * Текстовые части промпта для Grok. Кадры идут отдельными image-блоками между [frameLabel]-ами,
 * см. [GrokPromptFileWriter]. Ответ приходит через `--json-schema`, поэтому правила говорят о полях
 * structured output, а не о JSON в тексте.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
@ConditionalOnProperty("application.ai.description.provider", havingValue = "grok")
class GrokPromptBuilder {
    fun introduction(language: String): String =
        buildString {
            appendLine("You are analyzing surveillance camera frames captured during an object detection event.")
            appendLine("Write both descriptions in ${LanguageNames.of(language)}.")
            appendLine()
            append("Frames (in chronological order):")
        }

    fun frameLabel(frameIndex: Int): String = "Frame $frameIndex:"

    fun rules(
        shortMaxLength: Int,
        detailedMaxLength: Int,
    ): String =
        buildString {
            appendLine("Fill the structured output fields \"short\" and \"detailed\".")
            appendLine("Rules:")
            appendLine("- \"short\" must not exceed $shortMaxLength characters.")
            appendLine("- \"detailed\" must not exceed $detailedMaxLength characters.")
            append("- No markdown, no explanations.")
        }

    companion object {
        /** Для `--system-prompt-override`: вместо стандартного промпта кодового агента. */
        const val SYSTEM_PROMPT =
            "You describe frames from a security camera for a notification message. " +
                "Answer only through the structured output. Do not call tools and do not ask questions."
    }
}
```

- [ ] **Step 4: Создать `GrokPromptFileWriter.kt`**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.grok

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.TempFileWriter
import tools.jackson.databind.ObjectMapper
import java.nio.file.Path
import java.util.Base64

private val logger = KotlinLogging.logger {}

/**
 * Пишет `prompt.json` для `grok --prompt-file`: массив ACP content blocks. Суффикс `.json`
 * обязателен, любое другое расширение Grok читает как обычный текст. Base64 кадров в логи не
 * попадает, только размер файла.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
@ConditionalOnProperty("application.ai.description.provider", havingValue = "grok")
class GrokPromptFileWriter(
    private val tempFileWriter: TempFileWriter,
    private val promptBuilder: GrokPromptBuilder,
    private val objectMapper: ObjectMapper,
) {
    suspend fun write(request: DescriptionRequest): Path {
        val bytes = objectMapper.writeValueAsBytes(buildBlocks(request))
        val path = tempFileWriter.createTempFile("grok-${request.recordingId}", ".json", bytes)
        logger.debug { "Grok prompt file $path: ${bytes.size} bytes, ${request.frames.size} frames" }
        return path
    }

    internal fun buildBlocks(request: DescriptionRequest): List<Map<String, String>> {
        val encoder = Base64.getEncoder()
        return buildList {
            add(text(promptBuilder.introduction(request.language)))
            request.frames.sortedBy { it.frameIndex }.forEach { frame ->
                add(text(promptBuilder.frameLabel(frame.frameIndex)))
                add(
                    mapOf(
                        "type" to "image",
                        "mimeType" to "image/jpeg",
                        "data" to encoder.encodeToString(frame.bytes),
                    ),
                )
            }
            add(text(promptBuilder.rules(request.shortMaxLength, request.detailedMaxLength)))
        }
    }

    /**
     * NonCancellable обязателен: вызывается из finally в GrokBackend.describe, куда выполнение
     * часто попадает через TimeoutCancellationException.
     */
    suspend fun delete(path: Path) {
        withContext(NonCancellable) {
            runCatching { tempFileWriter.deleteFiles(listOf(path)) }
                .onFailure { logger.warn(it) { "Failed to delete Grok prompt file $path" } }
        }
    }

    private fun text(value: String): Map<String, String> = mapOf("type" to "text", "text" to value)
}
```

- [ ] **Step 5: Запустить тесты**

Run: `./gradlew :frigate-analyzer-ai-description:test --tests 'ru.zinin.frigate.analyzer.ai.description.grok.*'`
Expected: PASS, 10 тестов.

- [ ] **Step 6: Commit**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokPromptBuilder.kt \
        modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokPromptFileWriter.kt \
        modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokPromptBuilderTest.kt \
        modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokPromptFileWriterTest.kt
git commit -m "feat(ai-description): Grok prompt builder and prompt.json writer" \
           -m "Claude-Session: https://claude.ai/code/session_015ZWCHwRx1akjYX3o1wtJ6S"
```

---

### Task 8: `GrokCommandBuilder`

**Files:**
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokCommand.kt`
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokCommandBuilder.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokCommandBuilderTest.kt`

**Interfaces:**
- Consumes: `GrokProperties` (Task 6), `GrokPromptBuilder.SYSTEM_PROMPT` (Task 7).
- Produces: `data class GrokCommand(argv: List<String>, environment: Map<String, String>, workingDirectory: Path)`; `class GrokCommandBuilder(properties: GrokProperties) { fun build(promptFile: Path): GrokCommand; companion object { const val JSON_SCHEMA; val ISOLATION_ENV: Map<String, String> } }`.

- [ ] **Step 1: Написать падающий тест**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.grok

import ru.zinin.frigate.analyzer.ai.description.config.GrokProperties
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GrokCommandBuilderTest {
    private fun props(
        cliPath: String = "",
        effort: String = "low",
        http: String = "",
        https: String = "",
        noProxy: String = "",
    ) = GrokProperties(
        cliPath = cliPath,
        model = "grok-4.6",
        effort = effort,
        home = "/data/grok-home",
        workingDirectory = "/data/grok-cwd",
        proxy = GrokProperties.ProxySection(http, https, noProxy),
    )

    private val promptFile = Path.of("/tmp/frigate-analyzer/prompt.json")

    @Test
    fun `argv matches the spec exactly`() {
        val command = GrokCommandBuilder(props()).build(promptFile)

        assertEquals(
            listOf(
                "grok",
                "--prompt-file", "/tmp/frigate-analyzer/prompt.json",
                "--json-schema", GrokCommandBuilder.JSON_SCHEMA,
                "--output-format", "json",
                "-m", "grok-4.6",
                "--effort", "low",
                "--max-turns", "1",
                "--tools", "read_file",
                "--no-plan", "--no-subagents", "--disable-web-search",
                "--permission-mode", "bypassPermissions",
                "--no-auto-update",
                "--system-prompt-override", GrokPromptBuilder.SYSTEM_PROMPT,
                "--cwd", "/data/grok-cwd",
            ),
            command.argv,
        )
        assertEquals(Path.of("/data/grok-cwd"), command.workingDirectory)
    }

    @Test
    fun `blank effort omits the flag`() {
        val argv = GrokCommandBuilder(props(effort = "")).build(promptFile).argv
        assertFalse(argv.contains("--effort"))
    }

    @Test
    fun `explicit cli path replaces the bare binary name`() {
        val argv = GrokCommandBuilder(props(cliPath = "/opt/grok/bin/grok")).build(promptFile).argv
        assertEquals("/opt/grok/bin/grok", argv.first())
    }

    @Test
    fun `environment carries GROK_HOME and the isolation variables, no proxy when blank`() {
        val env = GrokCommandBuilder(props()).build(promptFile).environment

        assertEquals("/data/grok-home", env["GROK_HOME"])
        assertEquals("1", env["GROK_DISABLE_AUTOUPDATER"])
        assertEquals("0", env["GROK_MEMORY"])
        assertEquals("0", env["GROK_SUBAGENTS"])
        listOf("AGENTS", "HOOKS", "MCPS", "RULES", "SKILLS").forEach { kind ->
            assertEquals("0", env["GROK_CLAUDE_${kind}_ENABLED"], "GROK_CLAUDE_${kind}_ENABLED")
            assertEquals("0", env["GROK_CURSOR_${kind}_ENABLED"], "GROK_CURSOR_${kind}_ENABLED")
        }
        assertFalse(env.containsKey("HTTP_PROXY"))
        assertFalse(env.containsKey("HTTPS_PROXY"))
        assertFalse(env.containsKey("NO_PROXY"))
    }

    @Test
    fun `proxy variables are passed when configured`() {
        val env =
            GrokCommandBuilder(props(http = "http://proxy:80", https = "http://proxy:443", noProxy = "localhost"))
                .build(promptFile)
                .environment

        assertEquals("http://proxy:80", env["HTTP_PROXY"])
        assertEquals("http://proxy:443", env["HTTPS_PROXY"])
        assertEquals("localhost", env["NO_PROXY"])
    }

    @Test
    fun `json schema requires exactly short and detailed`() {
        assertTrue(GrokCommandBuilder.JSON_SCHEMA.contains("\"required\":[\"short\",\"detailed\"]"))
        assertTrue(GrokCommandBuilder.JSON_SCHEMA.contains("\"additionalProperties\":false"))
    }
}
```

- [ ] **Step 2: Запустить и убедиться, что не компилируется**

Run: `./gradlew :frigate-analyzer-ai-description:test --tests ru.zinin.frigate.analyzer.ai.description.grok.GrokCommandBuilderTest`
Expected: FAIL, `Unresolved reference: GrokCommandBuilder`.

- [ ] **Step 3: Создать `GrokCommand.kt`**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.grok

import java.nio.file.Path

/** Готовый к запуску вызов `grok`: argv, переменные поверх окружения JVM и cwd процесса. */
data class GrokCommand(
    val argv: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: Path,
)
```

- [ ] **Step 4: Создать `GrokCommandBuilder.kt`**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.grok

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.config.GrokProperties
import java.nio.file.Path

/**
 * Собирает argv и env для одного headless-вызова Grok Build. Флаги зафиксированы spec-ом:
 * `--json-schema` даёт готовый объект в `structuredOutput`; `--tools read_file` это allowlist,
 * который отключает инъекцию инструментов по умолчанию (кадры уже inline, инструмент модели не
 * нужен); `--max-turns 1` запрещает второй ход; `--system-prompt-override` заменяет промпт
 * кодового агента; `--cwd` указывает на пустой каталог. Env изолирует процесс от skills, rules и
 * плагинов Claude Code и Cursor, которые Grok иначе читает из HOME.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
@ConditionalOnProperty("application.ai.description.provider", havingValue = "grok")
class GrokCommandBuilder(
    private val properties: GrokProperties,
) {
    fun build(promptFile: Path): GrokCommand {
        val argv =
            buildList {
                add(properties.cliPath.ifBlank { "grok" })
                add("--prompt-file")
                add(promptFile.toAbsolutePath().normalize().toString())
                add("--json-schema")
                add(JSON_SCHEMA)
                add("--output-format")
                add("json")
                add("-m")
                add(properties.model)
                if (properties.effort.isNotBlank()) {
                    add("--effort")
                    add(properties.effort)
                }
                add("--max-turns")
                add("1")
                add("--tools")
                add("read_file")
                add("--no-plan")
                add("--no-subagents")
                add("--disable-web-search")
                add("--permission-mode")
                add("bypassPermissions")
                add("--no-auto-update")
                add("--system-prompt-override")
                add(GrokPromptBuilder.SYSTEM_PROMPT)
                add("--cwd")
                add(properties.workingDirectoryPath.toString())
            }
        val environment =
            buildMap {
                put("GROK_HOME", properties.homePath.toString())
                putAll(ISOLATION_ENV)
                val proxy = properties.proxy
                if (proxy.http.isNotBlank()) put("HTTP_PROXY", proxy.http)
                if (proxy.https.isNotBlank()) put("HTTPS_PROXY", proxy.https)
                if (proxy.noProxy.isNotBlank()) put("NO_PROXY", proxy.noProxy)
            }
        return GrokCommand(argv, environment, properties.workingDirectoryPath)
    }

    companion object {
        const val JSON_SCHEMA =
            """{"type":"object","properties":{"short":{"type":"string"},"detailed":{"type":"string"}},"required":["short","detailed"],"additionalProperties":false}"""

        val ISOLATION_ENV: Map<String, String> =
            buildMap {
                put("GROK_DISABLE_AUTOUPDATER", "1")
                put("GROK_MEMORY", "0")
                put("GROK_SUBAGENTS", "0")
                listOf("CLAUDE", "CURSOR").forEach { tool ->
                    listOf("AGENTS", "HOOKS", "MCPS", "RULES", "SKILLS").forEach { kind ->
                        put("GROK_${tool}_${kind}_ENABLED", "0")
                    }
                }
            }
    }
}
```

- [ ] **Step 5: Запустить тест**

Run: `./gradlew :frigate-analyzer-ai-description:test --tests ru.zinin.frigate.analyzer.ai.description.grok.GrokCommandBuilderTest`
Expected: PASS. Если ktlint требует по одному аргументу в строке в `listOf(...)` теста, применить `./gradlew ktlintFormat`.

- [ ] **Step 6: Commit**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokCommand.kt \
        modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokCommandBuilder.kt \
        modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokCommandBuilderTest.kt
git commit -m "feat(ai-description): Grok command line and isolated environment" \
           -m "Claude-Session: https://claude.ai/code/session_015ZWCHwRx1akjYX3o1wtJ6S"
```

---

### Task 9: `GrokProcessRunner` и `DefaultGrokProcessRunner`

**Files:**
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokProcessRunner.kt`
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/DefaultGrokProcessRunner.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/DefaultGrokProcessRunnerTest.kt`

**Interfaces:**
- Consumes: `GrokCommand` (Task 8).
- Produces: `data class GrokProcessResult(exitCode: Int, stdout: String, stderrTail: String)`; `fun interface GrokProcessRunner { suspend fun run(command: GrokCommand): GrokProcessResult }`; `class DefaultGrokProcessRunner : GrokProcessRunner` (бросает `DescriptionException.Transport`, если процесс не запускается).

- [ ] **Step 1: Написать падающий тест со stub-скриптом**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.grok

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@EnabledOnOs(OS.LINUX, OS.MAC)
class DefaultGrokProcessRunnerTest {
    @TempDir
    lateinit var tempDir: Path

    private val runner = DefaultGrokProcessRunner()

    private fun stub(script: String): Path {
        val file = tempDir.resolve("grok")
        file.writeText("#!/bin/sh\n$script\n")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"))
        return file
    }

    private fun command(
        binary: Path,
        env: Map<String, String> = emptyMap(),
    ) = GrokCommand(argv = listOf(binary.toString()), environment = env, workingDirectory = tempDir)

    @Test
    fun `captures stdout and exit code 0`() =
        runBlocking {
            val result = runner.run(command(stub("""printf '%s' '{"text":"ok"}'""")))

            assertEquals(0, result.exitCode)
            assertEquals("""{"text":"ok"}""", result.stdout)
        }

    @Test
    fun `captures non-zero exit code and stderr tail`() =
        runBlocking {
            val result = runner.run(command(stub("""echo 'Error: boom' >&2; printf '%s' '{"type":"error","message":"boom"}'; exit 1""")))

            assertEquals(1, result.exitCode)
            assertEquals("""{"type":"error","message":"boom"}""", result.stdout)
            assertTrue(result.stderrTail.contains("Error: boom"))
        }

    @Test
    fun `passes environment and working directory to the child`() =
        runBlocking {
            val result = runner.run(command(stub("""printf '%s|%s' "$GROK_HOME" "$(pwd -P)""""), env = mapOf("GROK_HOME" to "/data/home")))

            assertEquals("/data/home|${tempDir.toRealPath()}", result.stdout)
        }

    @Test
    fun `stderr is trimmed to the tail`() =
        runBlocking {
            val result = runner.run(command(stub("""head -c 20000 /dev/zero | tr '\0' 'x' >&2; echo END >&2""")))

            assertTrue(result.stderrTail.length <= DefaultGrokProcessRunner.STDERR_TAIL_BYTES)
            assertTrue(result.stderrTail.endsWith("END\n"))
        }

    @Test
    fun `cancellation kills the child process`() =
        runBlocking {
            val pidFile = tempDir.resolve("pid")
            // exec заменяет sh на sleep: PID в файле и есть процесс, который должен умереть.
            val binary = stub("""echo $$ > "$pidFile"; exec sleep 30""")

            val job = launch { runner.run(command(binary)) }
            while (!Files.exists(pidFile)) delay(20)
            delay(100)
            val pid = pidFile.readText().trim().toLong()
            assertTrue(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "child must be alive before cancel")

            job.cancelAndJoin()

            delay(200)
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "child must be dead after cancel")
        }

    @Test
    fun `missing binary is Transport`() =
        runBlocking {
            assertFailsWith<DescriptionException.Transport> {
                runner.run(command(tempDir.resolve("does-not-exist")))
            }
        }
}
```

- [ ] **Step 2: Запустить и убедиться, что не компилируется**

Run: `./gradlew :frigate-analyzer-ai-description:test --tests ru.zinin.frigate.analyzer.ai.description.grok.DefaultGrokProcessRunnerTest`
Expected: FAIL, `Unresolved reference: DefaultGrokProcessRunner`.

- [ ] **Step 3: Создать `GrokProcessRunner.kt`**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.grok

/** Результат одного запуска `grok`: код выхода, весь stdout и хвост stderr. */
data class GrokProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderrTail: String,
)

/**
 * Шов над запуском процесса: в проде [DefaultGrokProcessRunner], в тестах фейк с готовыми
 * результатами.
 */
fun interface GrokProcessRunner {
    suspend fun run(command: GrokCommand): GrokProcessResult
}
```

- [ ] **Step 4: Создать `DefaultGrokProcessRunner.kt`**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.grok

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import java.io.IOException

private val logger = KotlinLogging.logger {}

/**
 * Запуск `grok` через ProcessBuilder. stdin закрывается сразу, stdout читается целиком, от stderr
 * остаётся хвост в [STDERR_TAIL_BYTES]. Отмена корутины (таймаут агента) убивает процесс в
 * `finally`, поэтому зависший `grok` не переживает свой вызов.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
@ConditionalOnProperty("application.ai.description.provider", havingValue = "grok")
class DefaultGrokProcessRunner : GrokProcessRunner {
    override suspend fun run(command: GrokCommand): GrokProcessResult =
        withContext(Dispatchers.IO) {
            val process =
                try {
                    ProcessBuilder(command.argv)
                        .directory(command.workingDirectory.toFile())
                        .also { it.environment().putAll(command.environment) }
                        .start()
                } catch (e: IOException) {
                    throw DescriptionException.Transport(e, "cannot start ${command.argv.first()}: ${e.message}")
                }
            try {
                process.outputStream.close()
                val stdout = async { process.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8) }
                val stderr = async { tail(process.errorStream.use { it.readBytes() }) }
                val exitCode = process.onExit().await().exitValue()
                GrokProcessResult(exitCode, stdout.await(), stderr.await())
            } finally {
                if (process.isAlive) {
                    logger.debug { "Killing grok process ${process.pid()} after cancellation" }
                    process.destroyForcibly()
                    process.waitFor()
                }
            }
        }

    private fun tail(bytes: ByteArray): String =
        if (bytes.size <= STDERR_TAIL_BYTES) {
            bytes.toString(Charsets.UTF_8)
        } else {
            bytes.copyOfRange(bytes.size - STDERR_TAIL_BYTES, bytes.size).toString(Charsets.UTF_8)
        }

    companion object {
        const val STDERR_TAIL_BYTES = 8 * 1024
    }
}
```

- [ ] **Step 5: Запустить тест**

Run: `./gradlew :frigate-analyzer-ai-description:test --tests ru.zinin.frigate.analyzer.ai.description.grok.DefaultGrokProcessRunnerTest`
Expected: PASS, 6 тестов. Если `cancellation kills the child process` мигает из-за `delay(200)`, поднять ожидание до 500 мс: `destroyForcibly` шлёт SIGKILL, а `waitFor` в `finally` возвращается только после смерти процесса, поэтому после `cancelAndJoin` процесс уже мёртв, и задержка нужна лишь `ProcessHandle`.

- [ ] **Step 6: Commit**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokProcessRunner.kt \
        modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/DefaultGrokProcessRunner.kt \
        modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/DefaultGrokProcessRunnerTest.kt
git commit -m "feat(ai-description): ProcessBuilder runner for grok with cancellation-safe kill" \
           -m "Claude-Session: https://claude.ai/code/session_015ZWCHwRx1akjYX3o1wtJ6S"
```

---

### Task 10: `GrokOutputParser` и `GrokExceptionMapper`

**Files:**
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokOutputParser.kt`
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokExceptionMapper.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokOutputParserTest.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokExceptionMapperTest.kt`

**Interfaces:**
- Produces: `data class GrokOutput(stopReason: String?, sessionId: String?, short: String?, detailed: String?, usageSummary: String)`; `class GrokOutputParser(objectMapper: tools.jackson.databind.ObjectMapper) { fun parse(stdout: String): GrokOutput; fun errorMessage(stdout: String): String? }`; `class GrokExceptionMapper { fun fromFailure(exitCode: Int, errorMessage: String?, stderrTail: String): DescriptionException; fun fromStopReason(stopReason: String?): DescriptionException }`.

- [ ] **Step 1: Написать падающие тесты**

`GrokOutputParserTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.grok

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.testsupport.TestObjectMappers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GrokOutputParserTest {
    private val parser = GrokOutputParser(TestObjectMappers.internalMapper())

    private val success =
        """
        {"text":"{\"short\":\"Car\",\"detailed\":\"A car in the yard.\"}","stopReason":"end_turn",
         "sessionId":"01a06332-cee4-7a82-ac73-8556a6ea21c4","requestId":"r1",
         "usage":{"input_tokens":3048,"cache_read_input_tokens":0,"cache_creation_input_tokens":0,
                  "output_tokens":120,"reasoning_tokens":119,"total_tokens":3168},
         "modelUsage":{"grok-4.6":{"inputTokens":3048,"outputTokens":120,"modelCalls":1,"costUSD":0.0013}},
         "total_cost_usd":0.0013,
         "structuredOutput":{"short":"Car","detailed":"A car in the yard."}}
        """.trimIndent()

    @Test
    fun `parses structured output and metadata`() {
        val output = parser.parse(success)

        assertEquals("end_turn", output.stopReason)
        assertEquals("01a06332-cee4-7a82-ac73-8556a6ea21c4", output.sessionId)
        assertEquals("Car", output.short)
        assertEquals("A car in the yard.", output.detailed)
        assertTrue(output.usageSummary.contains("input_tokens=3048"))
        assertTrue(output.usageSummary.contains("output_tokens=120"))
        assertTrue(output.usageSummary.contains("total_cost_usd=0.0013"))
    }

    @Test
    fun `missing structured output yields null fields`() {
        val output = parser.parse("""{"text":"sorry","stopReason":"max_tokens","sessionId":"s"}""")

        assertNull(output.short)
        assertNull(output.detailed)
        assertEquals("max_tokens", output.stopReason)
        assertTrue(output.usageSummary.contains("usage=absent"))
    }

    @Test
    fun `non-json stdout is InvalidResponse`() {
        assertFailsWith<DescriptionException.InvalidResponse> { parser.parse("Segmentation fault") }
    }

    @Test
    fun `json array is InvalidResponse`() {
        assertFailsWith<DescriptionException.InvalidResponse> { parser.parse("[1,2]") }
    }

    @Test
    fun `errorMessage reads the error envelope`() {
        val stdout = """{"type":"error","message":"Not signed in. To authenticate without a browser, run:\n  grok login --device-code"}"""
        assertEquals("Not signed in. To authenticate without a browser, run:\n  grok login --device-code", parser.errorMessage(stdout))
    }

    @Test
    fun `errorMessage is null for a success object or garbage`() {
        assertNull(parser.errorMessage(success))
        assertNull(parser.errorMessage("garbage"))
        assertNull(parser.errorMessage(""))
    }
}
```

`GrokExceptionMapperTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.grok

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GrokExceptionMapperTest {
    private val mapper = GrokExceptionMapper()

    @Test
    fun `not signed in is Unauthorized with the message as detail`() {
        val e = mapper.fromFailure(1, "Not signed in. To authenticate without a browser, run:\n  grok login --device-code", "")
        assertIs<DescriptionException.Unauthorized>(e)
        assertTrue(e.detail.startsWith("Not signed in"))
    }

    @Test
    fun `every auth marker is Unauthorized`() {
        listOf(
            "please run grok login",
            "Not authenticated",
            "401 Unauthorized",
            "token refresh failed: invalid_grant",
            "Refresh token rejected by auth.x.ai",
            "Authentication failed",
        ).forEach { message ->
            assertIs<DescriptionException.Unauthorized>(mapper.fromFailure(1, message, ""), message)
        }
    }

    @Test
    fun `rate limit texts are RateLimited`() {
        listOf("Rate limit exceeded", "Too Many Requests", "HTTP 429 from proxy").forEach { message ->
            assertIs<DescriptionException.RateLimited>(mapper.fromFailure(1, message, ""), message)
        }
    }

    @Test
    fun `auth wins over rate limit words`() {
        assertIs<DescriptionException.Unauthorized>(mapper.fromFailure(1, "Not signed in; rate limit unknown", ""))
    }

    @Test
    fun `bare 4290 does not match 429`() {
        assertIs<DescriptionException.Transport>(mapper.fromFailure(1, "session 4290 lost", ""))
    }

    @Test
    fun `other error message is Transport with exit code and message`() {
        val e = mapper.fromFailure(1, "Couldn't set model to nope", "")
        assertIs<DescriptionException.Transport>(e)
        assertEquals("Description provider transport error: exit 1: Couldn't set model to nope", e.message)
    }

    @Test
    fun `without error json the stderr tail becomes the detail`() {
        val e = mapper.fromFailure(143, null, "Terminated\n")
        assertIs<DescriptionException.Transport>(e)
        assertTrue(e.message!!.contains("exit 143: Terminated"))
    }

    @Test
    fun `without error json and stderr the exit code alone is the detail`() {
        val e = mapper.fromFailure(2, null, "   ")
        assertEquals("Description provider transport error: exit 2: grok exited with code 2", e.message)
    }

    @Test
    fun `stop reasons map per spec`() {
        assertIs<DescriptionException.InvalidResponse>(mapper.fromStopReason("max_tokens"))
        assertIs<DescriptionException.InvalidResponse>(mapper.fromStopReason("refusal"))
        assertIs<DescriptionException.InvalidResponse>(mapper.fromStopReason("max_turn_requests"))
        assertIs<DescriptionException.InvalidResponse>(mapper.fromStopReason(null))
        assertIs<DescriptionException.InvalidResponse>(mapper.fromStopReason("end_turn"))
        assertIs<DescriptionException.Transport>(mapper.fromStopReason("cancelled"))
    }
}
```

- [ ] **Step 2: Запустить и убедиться, что не компилируется**

Run: `./gradlew :frigate-analyzer-ai-description:test --tests 'ru.zinin.frigate.analyzer.ai.description.grok.Grok*'`
Expected: FAIL, `Unresolved reference: GrokOutputParser`, `GrokExceptionMapper`.

- [ ] **Step 3: Создать `GrokOutputParser.kt`**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.grok

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** Разобранный stdout `grok --output-format json`. Поля structured output могут отсутствовать. */
data class GrokOutput(
    val stopReason: String?,
    val sessionId: String?,
    val short: String?,
    val detailed: String?,
    /** Одна строка для DEBUG-лога: токены и стоимость. */
    val usageSummary: String,
)

/**
 * `--output-format json` даёт один объект: `text`, `stopReason`, `sessionId`, `usage`,
 * `modelUsage`, `total_cost_usd` и `structuredOutput` (объект по `--json-schema`). При ошибке
 * на stdout лежит `{"type":"error","message":"…"}`.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
@ConditionalOnProperty("application.ai.description.provider", havingValue = "grok")
class GrokOutputParser(
    private val objectMapper: ObjectMapper,
) {
    fun parse(stdout: String): GrokOutput {
        val node =
            readObject(stdout)
                ?: throw DescriptionException.InvalidResponse(detail = "stdout is not a JSON object: ${stdout.take(200)}")
        val structured = node["structuredOutput"]?.takeIf { it.isObject }
        return GrokOutput(
            stopReason = node["stopReason"]?.textOrNull(),
            sessionId = node["sessionId"]?.textOrNull(),
            short = structured?.get("short")?.textOrNull(),
            detailed = structured?.get("detailed")?.textOrNull(),
            usageSummary = usageSummary(node),
        )
    }

    /** Текст из error-конверта или null, если stdout не такой конверт. */
    fun errorMessage(stdout: String): String? {
        val node = readObject(stdout) ?: return null
        if (node["type"]?.textOrNull() != "error") return null
        return node["message"]?.textOrNull()
    }

    private fun readObject(text: String): JsonNode? =
        try {
            objectMapper.readTree(text).takeIf { it.isObject }
        } catch (e: JacksonException) {
            null
        }

    private fun JsonNode.textOrNull(): String? = if (isNull) null else asText()

    private fun usageSummary(node: JsonNode): String {
        val usage = node["usage"] ?: return "usage=absent"
        val cost = node["total_cost_usd"]?.textOrNull() ?: "unknown"
        return listOf("input_tokens", "cache_read_input_tokens", "output_tokens", "reasoning_tokens")
            .joinToString(" ") { key -> "$key=${usage[key]?.textOrNull() ?: "?"}" } +
            " total_cost_usd=$cost"
    }
}
```

- [ ] **Step 4: Создать `GrokExceptionMapper.kt`**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.grok

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException

/**
 * Классификация неудачного запуска `grok` по spec: авторизация раньше rate limit, rate limit
 * раньше общего Transport. Unauthorized и RateLimited агент не повторяет, Transport повторяет
 * один раз.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
@ConditionalOnProperty("application.ai.description.provider", havingValue = "grok")
class GrokExceptionMapper {
    fun fromFailure(
        exitCode: Int,
        errorMessage: String?,
        stderrTail: String,
    ): DescriptionException {
        val message = errorMessage ?: stderrTail.trim().ifBlank { "grok exited with code $exitCode" }
        val lower = message.lowercase()
        return when {
            AUTH_MARKERS.any { it in lower } -> DescriptionException.Unauthorized(message)
            RATE_LIMIT_MARKERS.any { it in lower } || RATE_LIMIT_429.containsMatchIn(lower) ->
                DescriptionException.RateLimited(detail = message)
            else -> DescriptionException.Transport(detail = "exit $exitCode: $message")
        }
    }

    /** exit 0, но structured output неполный: решает stopReason. */
    fun fromStopReason(stopReason: String?): DescriptionException =
        when (stopReason) {
            "cancelled" -> DescriptionException.Transport(detail = "grok reported stopReason=cancelled")
            else -> DescriptionException.InvalidResponse(detail = "no structured output, stopReason=${stopReason ?: "unknown"}")
        }

    companion object {
        val AUTH_MARKERS =
            listOf(
                "not signed in",
                "grok login",
                "not authenticated",
                "unauthorized",
                "invalid_grant",
                "refresh token",
                "authentication failed",
            )
        val RATE_LIMIT_MARKERS = listOf("rate limit", "too many requests")
        val RATE_LIMIT_429 = Regex("\\b429\\b")
    }
}
```

- [ ] **Step 5: Запустить тесты**

Run: `./gradlew :frigate-analyzer-ai-description:test --tests 'ru.zinin.frigate.analyzer.ai.description.grok.Grok*'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokOutputParser.kt \
        modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokExceptionMapper.kt \
        modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokOutputParserTest.kt \
        modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokExceptionMapperTest.kt
git commit -m "feat(ai-description): parse grok json output and classify failures" \
           -m "Claude-Session: https://claude.ai/code/session_015ZWCHwRx1akjYX3o1wtJ6S"
```

---

### Task 11: `GrokHomeGuard` и `GrokHomeSweeper`

**Files:**
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokHomeGuard.kt`
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokHomeSweeper.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokHomeGuardTest.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokHomeSweeperTest.kt`

**Interfaces:**
- Consumes: `GrokProperties.homePath` (Task 6).
- Produces: `class GrokHomeGuard { suspend fun <T> shared(block: suspend () -> T): T; suspend fun <T> exclusive(block: suspend () -> T): T }`; `class GrokHomeSweeper(properties: GrokProperties, guard: GrokHomeGuard) { suspend fun sweep(): Int; fun sweepScheduled() }`.

- [ ] **Step 1: Написать падающие тесты**

`GrokHomeGuardTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.grok

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GrokHomeGuardTest {
    @Test
    fun `exclusive waits for in-flight shared blocks`() =
        runTest {
            val guard = GrokHomeGuard()
            val gate = CompletableDeferred<Unit>()
            var exclusiveRan = false

            launch { guard.shared { gate.await() } }
            advanceUntilIdle()
            launch { guard.exclusive { exclusiveRan = true } }
            advanceTimeBy(5_000)
            assertFalse(exclusiveRan, "exclusive must wait while a shared block is in flight")

            gate.complete(Unit)
            advanceUntilIdle()
            assertTrue(exclusiveRan)
        }

    @Test
    fun `shared waits while exclusive runs`() =
        runTest {
            val guard = GrokHomeGuard()
            val gate = CompletableDeferred<Unit>()
            var sharedRan = false

            launch { guard.exclusive { gate.await() } }
            advanceUntilIdle()
            launch { guard.shared { sharedRan = true } }
            advanceTimeBy(5_000)
            assertFalse(sharedRan, "shared must wait while exclusive holds the guard")

            gate.complete(Unit)
            advanceUntilIdle()
            assertTrue(sharedRan)
        }

    @Test
    fun `shared blocks run concurrently with each other`() =
        runTest {
            val guard = GrokHomeGuard()
            val gate = CompletableDeferred<Unit>()
            var entered = 0

            repeat(3) { launch { guard.shared { entered++; gate.await() } } }
            advanceUntilIdle()
            assertEquals(3, entered)
            gate.complete(Unit)
            advanceUntilIdle()
        }

    @Test
    fun `block results and exceptions propagate`() =
        runTest {
            val guard = GrokHomeGuard()
            assertEquals(42, guard.shared { 42 })
            assertEquals("x", guard.exclusive { "x" })
            var failed = false
            try {
                guard.shared<Unit> { throw IllegalStateException("boom") }
            } catch (e: IllegalStateException) {
                failed = true
            }
            assertTrue(failed)
            assertEquals(1, guard.exclusive { 1 }, "a failed shared block must release its slot")
        }
}
```

`GrokHomeSweeperTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.grok

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import ru.zinin.frigate.analyzer.ai.description.config.GrokProperties
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GrokHomeSweeperTest {
    @TempDir
    lateinit var home: Path

    private fun sweeper() =
        GrokHomeSweeper(
            GrokProperties(
                cliPath = "",
                model = "grok-4.6",
                effort = "",
                home = home.toString(),
                workingDirectory = home.resolve("cwd").toString(),
                proxy = GrokProperties.ProxySection("", "", ""),
            ),
            GrokHomeGuard(),
        )

    @Test
    fun `removes session directories, the search index and log files, keeps credentials and config`() {
        val session = home.resolve("sessions/%2Ftmp%2Fcwd/01a06332-cee4-7a82-ac73-8556a6ea21c4").createDirectories()
        session.resolve("chat_history.jsonl").writeText("{}")
        session.resolve("compaction_checkpoints").createDirectories().resolve("c1.json").writeText("{}")
        home.resolve("sessions/session_search.sqlite").writeText("db")
        home.resolve("sessions/session_search.sqlite-wal").writeText("wal")
        home.resolve("logs").createDirectories().resolve("unified.jsonl").writeText("log")
        home.resolve("logs/mcp").createDirectories().resolve("x.log").writeText("mcp")
        home.resolve("auth.json").writeText("secret")
        home.resolve("config.toml").writeText("[models]")

        val removed = runBlocking { sweeper().sweep() }

        assertEquals(4, removed, "one cwd dir, two index files, one log file")
        assertTrue(home.resolve("sessions").exists())
        assertTrue(home.resolve("sessions").listDirectoryEntries().isEmpty())
        assertTrue(home.resolve("logs/mcp/x.log").exists(), "subdirectories under logs/ are left alone")
        assertTrue(home.resolve("auth.json").exists())
        assertTrue(home.resolve("config.toml").exists())
    }

    @Test
    fun `missing directories are not an error`() {
        assertEquals(0, runBlocking { sweeper().sweep() })
        assertTrue(Files.notExists(home.resolve("sessions")))
    }
}
```

- [ ] **Step 2: Запустить и убедиться, что не компилируется**

Run: `./gradlew :frigate-analyzer-ai-description:test --tests 'ru.zinin.frigate.analyzer.ai.description.grok.GrokHome*'`
Expected: FAIL, `Unresolved reference: GrokHomeGuard`, `GrokHomeSweeper`.

- [ ] **Step 3: Создать `GrokHomeGuard.kt`**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.grok

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicInteger

/**
 * Мини-RW-lock на корутинах для GROK_HOME: запуски `grok` берут [shared], sweeper берёт
 * [exclusive]. `exclusive` держит мьютекс, чем не пускает новые запуски, и ждёт, пока текущие
 * не завершатся; `shared` берёт мьютекс только на инкремент счётчика, поэтому запуски друг друга
 * не ждут. Ожидание `exclusive` ограничено сверху таймаутом агента.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
@ConditionalOnProperty("application.ai.description.provider", havingValue = "grok")
class GrokHomeGuard {
    private val mutex = Mutex()
    private val inFlight = AtomicInteger(0)

    suspend fun <T> shared(block: suspend () -> T): T {
        mutex.withLock { inFlight.incrementAndGet() }
        try {
            return block()
        } finally {
            inFlight.decrementAndGet()
        }
    }

    suspend fun <T> exclusive(block: suspend () -> T): T =
        mutex.withLock {
            while (inFlight.get() > 0) {
                delay(DRAIN_POLL_MS)
            }
            block()
        }

    private companion object {
        const val DRAIN_POLL_MS = 100L
    }
}
```

- [ ] **Step 4: Создать `GrokHomeSweeper.kt`**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.grok

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.config.GrokProperties
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Каждый headless-запуск оставляет в `GROK_HOME/sessions/<cwd>/<id>/` копию промпта с base64
 * кадров, а `sessions/session_search.sqlite` растёт на ~9 КБ за запуск и при удалении каталогов
 * не сжимается. Политики хранения у Grok нет. Раз в час под [GrokHomeGuard.exclusive] удаляется
 * всё содержимое `sessions/` и файлы в `logs/`; Grok пересоздаёт индекс и логи при следующем
 * запуске. `auth.json`, `config.toml` и остальное не трогаются. Приложение единственный
 * пользователь этого GROK_HOME, `grok login` сессий не создаёт.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
@ConditionalOnProperty("application.ai.description.provider", havingValue = "grok")
class GrokHomeSweeper(
    private val properties: GrokProperties,
    private val guard: GrokHomeGuard,
) {
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    fun sweepScheduled() {
        runBlocking(Dispatchers.IO) {
            try {
                sweep()
            } catch (e: Exception) {
                logger.warn(e) { "Grok home sweep failed" }
            }
        }
    }

    /** Возвращает число удалённых записей верхнего уровня (каталогов сессий и файлов). */
    suspend fun sweep(): Int =
        guard.exclusive {
            withContext(Dispatchers.IO) {
                val home = properties.homePath
                val removed =
                    clearDirectory(home.resolve("sessions"), removeSubdirectories = true) +
                        clearDirectory(home.resolve("logs"), removeSubdirectories = false)
                logger.debug { "Grok home sweep removed $removed entries under $home" }
                removed
            }
        }

    private fun clearDirectory(
        dir: Path,
        removeSubdirectories: Boolean,
    ): Int {
        if (!Files.isDirectory(dir)) return 0
        var removed = 0
        Files.list(dir).use { entries ->
            entries.forEach { entry ->
                try {
                    when {
                        Files.isRegularFile(entry) -> {
                            Files.deleteIfExists(entry)
                            removed++
                        }

                        Files.isDirectory(entry) && removeSubdirectories -> {
                            deleteRecursively(entry)
                            removed++
                        }
                    }
                } catch (e: IOException) {
                    logger.warn(e) { "Failed to remove $entry during Grok home sweep" }
                }
            }
        }
        return removed
    }

    private fun deleteRecursively(root: Path) {
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
```

- [ ] **Step 5: Запустить тесты**

Run: `./gradlew :frigate-analyzer-ai-description:test --tests 'ru.zinin.frigate.analyzer.ai.description.grok.GrokHome*'`
Expected: PASS, 6 тестов.

- [ ] **Step 6: Commit**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokHomeGuard.kt \
        modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokHomeSweeper.kt \
        modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokHomeGuardTest.kt \
        modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokHomeSweeperTest.kt
git commit -m "feat(ai-description): guard and hourly sweep for the Grok home directory" \
           -m "Claude-Session: https://claude.ai/code/session_015ZWCHwRx1akjYX3o1wtJ6S"
```

---

### Task 12: `GrokBackend`, автоконфигурация для `provider=grok`

**Files:**
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokBackend.kt`
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/config/DescriptionAgentSanityChecker.kt`
- Test create: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokBackendTest.kt`
- Test modify: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/config/AiDescriptionAutoConfigurationTest.kt`

**Interfaces:**
- Consumes: всё из Tasks 6–11.
- Produces: `class GrokBackend(properties: GrokProperties, promptFileWriter: GrokPromptFileWriter, commandBuilder: GrokCommandBuilder, runner: GrokProcessRunner, outputParser: GrokOutputParser, exceptionMapper: GrokExceptionMapper, guard: GrokHomeGuard) : DescriptionBackend` с `providerId = "grok"`.

- [ ] **Step 1: Написать падающий тест `GrokBackendTest`**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.grok

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.ai.description.config.GrokProperties
import ru.zinin.frigate.analyzer.ai.description.testsupport.TestObjectMappers
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GrokBackendTest {
    @TempDir
    lateinit var tempDir: Path

    private val promptFile = Path.of("/tmp/frigate-analyzer/prompt.json")
    private val promptFileWriter = mockk<GrokPromptFileWriter>(relaxUnitFun = true)
    private val request =
        DescriptionRequest(
            recordingId = UUID.randomUUID(),
            frames = listOf(DescriptionRequest.FrameImage(0, byteArrayOf(1))),
            language = "en",
            shortMaxLength = 200,
            detailedMaxLength = 1500,
        )

    private fun props(home: Path = tempDir.resolve("home")) =
        GrokProperties(
            cliPath = tempDir.resolve("missing-grok").toString(),
            model = "grok-4.6",
            effort = "low",
            home = home.toString(),
            workingDirectory = tempDir.resolve("cwd").toString(),
            proxy = GrokProperties.ProxySection("", "", ""),
        )

    private fun backend(
        runner: GrokProcessRunner,
        properties: GrokProperties = props(),
    ): GrokBackend {
        coEvery { promptFileWriter.write(any()) } returns promptFile
        return GrokBackend(
            properties = properties,
            promptFileWriter = promptFileWriter,
            commandBuilder = GrokCommandBuilder(properties),
            runner = runner,
            outputParser = GrokOutputParser(TestObjectMappers.internalMapper()),
            exceptionMapper = GrokExceptionMapper(),
            guard = GrokHomeGuard(),
        )
    }

    private fun result(
        exitCode: Int,
        stdout: String,
        stderr: String = "",
    ) = GrokProcessResult(exitCode, stdout, stderr)

    @Test
    fun `success returns normalized structured output and deletes the prompt file`() =
        runTest {
            val stdout = """{"stopReason":"end_turn","sessionId":"s","structuredOutput":{"short":"Car","detailed":"A car."}}"""
            val backend = backend(GrokProcessRunner { result(0, stdout) })

            assertEquals(DescriptionResult("Car", "A car."), backend.describe(request))
            coVerify(exactly = 1) { promptFileWriter.delete(promptFile) }
        }

    @Test
    fun `runner receives the command built for the prompt file`() =
        runTest {
            var seen: GrokCommand? = null
            val backend =
                backend(
                    GrokProcessRunner {
                        seen = it
                        result(0, """{"stopReason":"end_turn","structuredOutput":{"short":"a","detailed":"b"}}""")
                    },
                )
            backend.describe(request)
            assertTrue(seen!!.argv.contains(promptFile.toString()))
            assertEquals(tempDir.resolve("home").toString(), seen!!.environment["GROK_HOME"])
        }

    @Test
    fun `auth error envelope is Unauthorized and still deletes the prompt file`() =
        runTest {
            val stdout = """{"type":"error","message":"Not signed in. To authenticate without a browser, run:\n  grok login --device-code"}"""
            val backend = backend(GrokProcessRunner { result(1, stdout, "Error: Not signed in") })

            val e = assertFailsWith<DescriptionException.Unauthorized> { backend.describe(request) }
            assertTrue(e.detail.startsWith("Not signed in"))
            coVerify(exactly = 1) { promptFileWriter.delete(promptFile) }
        }

    @Test
    fun `other non-zero exit is Transport`() =
        runTest {
            val backend = backend(GrokProcessRunner { result(1, "", "connection reset") })
            assertFailsWith<DescriptionException.Transport> { backend.describe(request) }
        }

    @Test
    fun `missing structured output with max_tokens is InvalidResponse`() =
        runTest {
            val backend = backend(GrokProcessRunner { result(0, """{"stopReason":"max_tokens","text":"..."}""") })
            assertFailsWith<DescriptionException.InvalidResponse> { backend.describe(request) }
        }

    @Test
    fun `runner failure still deletes the prompt file`() =
        runTest {
            val backend = backend(GrokProcessRunner { throw DescriptionException.Transport(detail = "cannot start") })
            assertFailsWith<DescriptionException.Transport> { backend.describe(request) }
            coVerify(exactly = 1) { promptFileWriter.delete(promptFile) }
        }

    @Test
    fun `init creates home and working directory`() {
        backend(GrokProcessRunner { result(0, "{}") })
        assertTrue(Files.isDirectory(tempDir.resolve("home")))
        assertTrue(Files.isDirectory(tempDir.resolve("cwd")))
    }

    @Test
    fun `init fails when the home path is a file`() {
        val file = tempDir.resolve("home-file")
        file.writeText("x")
        assertFailsWith<IllegalStateException> { backend(GrokProcessRunner { result(0, "{}") }, props(home = file)) }
    }

    @Test
    fun `identifies itself as grok with a device-code hint`() {
        val backend = backend(GrokProcessRunner { result(0, "{}") })
        assertEquals("grok", backend.providerId)
        assertTrue(backend.authRecoveryHint.contains("grok login --device-code"))
    }
}
```

- [ ] **Step 2: Запустить и убедиться, что не компилируется**

Run: `./gradlew :frigate-analyzer-ai-description:test --tests ru.zinin.frigate.analyzer.ai.description.grok.GrokBackendTest`
Expected: FAIL, `Unresolved reference: GrokBackend`.

- [ ] **Step 3: Создать `GrokBackend.kt`**

```kotlin
package ru.zinin.frigate.analyzer.ai.description.grok

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.ai.description.config.GrokProperties
import ru.zinin.frigate.analyzer.ai.description.core.DescriptionBackend
import ru.zinin.frigate.analyzer.ai.description.core.ResultNormalizer
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Одна попытка описания через headless Grok Build: `prompt.json` → процесс → `structuredOutput`.
 * Семафор, таймауты и повторы живут в `DefaultDescriptionAgent`; отмена корутины по таймауту
 * убивает процесс в runner-е, а prompt-файл удаляется в `finally` под NonCancellable.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
@ConditionalOnProperty("application.ai.description.provider", havingValue = "grok")
class GrokBackend(
    private val properties: GrokProperties,
    private val promptFileWriter: GrokPromptFileWriter,
    private val commandBuilder: GrokCommandBuilder,
    private val runner: GrokProcessRunner,
    private val outputParser: GrokOutputParser,
    private val exceptionMapper: GrokExceptionMapper,
    private val guard: GrokHomeGuard,
) : DescriptionBackend {
    override val providerId: String = "grok"
    override val authRecoveryHint: String =
        "grok login --device-code (in Docker: docker compose exec frigate-analyzer grok login --device-code)"

    init {
        val home = properties.homePath
        val cwd = properties.workingDirectoryPath
        try {
            Files.createDirectories(home)
            Files.createDirectories(cwd)
        } catch (e: IOException) {
            throw IllegalStateException("Cannot create Grok directories home=$home working-directory=$cwd: ${e.message}", e)
        }
        if (!Files.isWritable(home)) {
            logger.warn { "Grok home $home is not writable; grok login and token refresh will fail (fix: chown the volume to uid 1000)" }
        }
        if (!cliAvailable()) {
            logger.warn {
                "grok CLI not found (cli-path='${properties.cliPath}', PATH lookup otherwise); " +
                    "all description requests will return fallback"
            }
        }
        if (!Files.isRegularFile(home.resolve("auth.json"))) {
            logger.warn {
                "No auth.json in $home; run `$authRecoveryHint`. Not needed only for BYOK models " +
                    "with their own api_key in config.toml"
            }
        }
    }

    override suspend fun describe(request: DescriptionRequest): DescriptionResult {
        val promptFile = promptFileWriter.write(request)
        try {
            val command = commandBuilder.build(promptFile)
            val result = guard.shared { runner.run(command) }
            if (result.exitCode != 0) {
                throw exceptionMapper.fromFailure(result.exitCode, outputParser.errorMessage(result.stdout), result.stderrTail)
            }
            val output = outputParser.parse(result.stdout)
            logger.debug {
                "Grok describe for recording ${request.recordingId}: ${output.usageSummary}, " +
                    "stopReason=${output.stopReason}, session=${output.sessionId}"
            }
            if (!output.short.isNullOrBlank() && !output.detailed.isNullOrBlank()) {
                return ResultNormalizer.normalize(output.short, output.detailed, request.shortMaxLength, request.detailedMaxLength)
            }
            throw exceptionMapper.fromStopReason(output.stopReason)
        } finally {
            promptFileWriter.delete(promptFile)
        }
    }

    private fun cliAvailable(): Boolean {
        val cliPath = properties.cliPath
        if (cliPath.isNotBlank()) return Files.isExecutable(Path.of(cliPath))
        return System
            .getenv("PATH")
            ?.split(File.pathSeparator)
            .orEmpty()
            .filter { it.isNotBlank() }
            .any { Files.isExecutable(Path.of(it, "grok")) }
    }
}
```

- [ ] **Step 4: Известные провайдеры в sanity checker**

В `DescriptionAgentSanityChecker.kt` заменить `private val KNOWN_PROVIDERS = listOf("claude")` на `private val KNOWN_PROVIDERS = listOf("claude", "grok")`.

- [ ] **Step 5: Тест автоконфигурации для `provider=grok`**

В `AiDescriptionAutoConfigurationTest` добавить импорт `ru.zinin.frigate.analyzer.ai.description.grok.GrokBackend` и тест:

```kotlin
    @Test
    fun `autoconfig activates the grok backend when provider=grok and no claude beans`() {
        runner
            .withPropertyValues(*properties(enabled = true, provider = "grok"))
            .run { ctx ->
                assert(ctx.startupFailure == null) { "grok context must start: ${ctx.startupFailure}" }
                assert(ctx.getBean(DescriptionAgent::class.java) is DefaultDescriptionAgent)
                assert(ctx.getBeansOfType(GrokBackend::class.java).isNotEmpty()) { "GrokBackend should be registered" }
                assert(ctx.getBeansOfType(ClaudeBackend::class.java).isEmpty()) { "ClaudeBackend must be absent" }
                assert(ctx.getBeansOfType(ClaudeAsyncClientFactory::class.java).isEmpty()) { "Claude helpers must be absent" }
            }
    }
```

- [ ] **Step 6: Запустить тесты модуля**

Run: `./gradlew :frigate-analyzer-ai-description:test`
Expected: PASS. `GrokBackend.init` в контексте теста пишет два WARN (нет бинарника, нет `auth.json`), это ожидаемо.

- [ ] **Step 7: Commit**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokBackend.kt \
        modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/config/DescriptionAgentSanityChecker.kt \
        modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokBackendTest.kt \
        modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/config/AiDescriptionAutoConfigurationTest.kt
git commit -m "feat(ai-description): GrokBackend wires prompt file, process runner and output parsing" \
           -m "Claude-Session: https://claude.ai/code/session_015ZWCHwRx1akjYX3o1wtJ6S"
```

---

### Task 13: Уведомление владельца об авторизации (`core` + i18n)

**Files:**
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/application/DescriptionAuthAlertNotifier.kt`
- Modify: `modules/telegram/src/main/resources/messages_ru.properties` (после строки `ai.description.details.summary=…`)
- Modify: `modules/telegram/src/main/resources/messages_en.properties` (после строки `ai.description.details.summary=…`)
- Test: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/application/DescriptionAuthAlertNotifierTest.kt`
- Test: `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/i18n/DescriptionAuthMessagesTest.kt`

**Interfaces:**
- Consumes: `DescriptionProviderAuthEvent` (Task 1), `TelegramNotificationService.sendOwnerMessage`, `MessageResolver.get(key, language, vararg args)`.
- Produces: `class DescriptionAuthAlertNotifier(telegramNotificationService, messageResolver) { fun onAuthEvent(event); internal fun render(event, language): String; fun shutdown() }` с `internal val scope`.

- [ ] **Step 1: Написать падающие тесты**

`DescriptionAuthAlertNotifierTest.kt` (core):

```kotlin
package ru.zinin.frigate.analyzer.core.application

import io.mockk.CapturingSlot
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionProviderAuthEvent
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.service.TelegramNotificationService
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DescriptionAuthAlertNotifierTest {
    private val telegramNotificationService = mockk<TelegramNotificationService>()
    private val messageResolver = mockk<MessageResolver>()
    private val notifier = DescriptionAuthAlertNotifier(telegramNotificationService, messageResolver)

    @AfterEach
    fun tearDown() {
        notifier.shutdown()
    }

    private fun awaitAlert() =
        runBlocking {
            notifier.scope.coroutineContext.job.children
                .toList()
                .joinAll()
        }

    private fun captureBuilder(): CapturingSlot<(String) -> String> {
        val builderSlot = slot<(String) -> String>()
        coEvery { telegramNotificationService.sendOwnerMessage(capture(builderSlot)) } just Runs
        return builderSlot
    }

    private fun lost(detail: String? = "Not signed in. Run grok login --device-code") =
        DescriptionProviderAuthEvent(
            provider = "grok",
            state = DescriptionProviderAuthEvent.State.LOST,
            detail = detail,
            recoveryHint = "grok login --device-code",
        )

    @Test
    fun `LOST sends the localized text with provider, hint and detail`() {
        every { messageResolver.get("ai.description.auth.lost", "ru", "grok", "grok login --device-code") } returns
            "🔴 grok: вход. Команда: grok login --device-code"
        val builder = captureBuilder()

        notifier.onAuthEvent(lost())
        awaitAlert()

        coVerify(exactly = 1) { telegramNotificationService.sendOwnerMessage(any()) }
        val text = builder.captured.invoke("ru")
        assertTrue(text.startsWith("🔴 grok: вход. Команда: grok login --device-code"))
        assertTrue(text.endsWith("\n\nNot signed in. Run grok login --device-code"))
    }

    @Test
    fun `LOST detail is trimmed to 300 characters and skipped when blank`() {
        every { messageResolver.get("ai.description.auth.lost", "en", "grok", "grok login --device-code") } returns "lost"

        val long = notifier.render(lost(detail = "x".repeat(1000)), "en")
        assertEquals("lost\n\n" + "x".repeat(300), long)

        val blank = notifier.render(lost(detail = "   "), "en")
        assertEquals("lost", blank)

        val none = notifier.render(lost(detail = null), "en")
        assertEquals("lost", none)
    }

    @Test
    fun `RESTORED sends the restored text without detail`() {
        every { messageResolver.get("ai.description.auth.restored", "en", "grok") } returns "🟢 grok ok"
        val builder = captureBuilder()

        notifier.onAuthEvent(
            DescriptionProviderAuthEvent(
                provider = "grok",
                state = DescriptionProviderAuthEvent.State.RESTORED,
                detail = null,
                recoveryHint = "grok login --device-code",
            ),
        )
        awaitAlert()

        assertEquals("🟢 grok ok", builder.captured.invoke("en"))
        assertFalse(builder.captured.invoke("en").contains("\n"))
    }

    @Test
    fun `delivery failures are swallowed`() {
        coEvery { telegramNotificationService.sendOwnerMessage(any()) } throws RuntimeException("boom")

        notifier.onAuthEvent(lost())
        awaitAlert()

        coVerify(exactly = 1) { telegramNotificationService.sendOwnerMessage(any()) }
    }
}
```

`DescriptionAuthMessagesTest.kt` (telegram):

```kotlin
package ru.zinin.frigate.analyzer.telegram.i18n

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DescriptionAuthMessagesTest {
    private fun bundle(language: String): Properties =
        Properties().also { properties ->
            javaClass.getResourceAsStream("/messages_$language.properties")!!.reader(Charsets.UTF_8).use { properties.load(it) }
        }

    @Test
    fun `both bundles carry the auth keys with MessageFormat placeholders and no apostrophes`() {
        listOf("ru", "en").forEach { language ->
            val properties = bundle(language)
            val lost = assertNotNull(properties.getProperty("ai.description.auth.lost"), "$language: lost")
            val restored = assertNotNull(properties.getProperty("ai.description.auth.restored"), "$language: restored")
            assertTrue(lost.contains("{0}") && lost.contains("{1}"), "$language: lost needs {0} and {1}")
            assertTrue(restored.contains("{0}"), "$language: restored needs {0}")
            assertFalse(lost.contains("'") || restored.contains("'"), "$language: apostrophes break MessageFormat")
        }
    }
}
```

- [ ] **Step 2: Запустить и убедиться, что падает**

Run: `./gradlew :frigate-analyzer-core:test --tests ru.zinin.frigate.analyzer.core.application.DescriptionAuthAlertNotifierTest` и `./gradlew :frigate-analyzer-telegram:test --tests ru.zinin.frigate.analyzer.telegram.i18n.DescriptionAuthMessagesTest`
Expected: core FAIL с `Unresolved reference: DescriptionAuthAlertNotifier`; telegram FAIL на `assertNotNull` (ключей нет).

- [ ] **Step 3: Добавить ключи в бандлы**

В `messages_ru.properties` после строки `ai.description.details.summary=Подробное описание`:

```properties
ai.description.auth.lost=🔴 AI-описания: провайдер {0} отверг авторизацию. Описания недоступны до повторного входа.\nКоманда для входа: {1}
ai.description.auth.restored=🟢 AI-описания: авторизация провайдера {0} восстановлена.
```

В `messages_en.properties` после строки `ai.description.details.summary=Detailed description`:

```properties
ai.description.auth.lost=🔴 AI descriptions: provider {0} rejected the credentials. Descriptions stay unavailable until you sign in again.\nSign-in command: {1}
ai.description.auth.restored=🟢 AI descriptions: provider {0} credentials work again.
```

- [ ] **Step 4: Создать `DescriptionAuthAlertNotifier.kt`**

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
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionProviderAuthEvent
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.service.TelegramNotificationService
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Доставляет владельцу переходы авторизации провайдера описаний: LOST с командой для починки и
 * техническим сообщением провайдера, RESTORED после первого успеха. Дедупликацию делает ядро
 * ai-description (одно событие на переход), здесь только рендер и отправка. Устройство повторяет
 * [StartupTelegramNotifier]: свой scope, чтобы доставка не держала поток публикации события, и
 * таймаут, чтобы зависший Telegram не копил корутины.
 */
@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class DescriptionAuthAlertNotifier(
    private val telegramNotificationService: TelegramNotificationService,
    private val messageResolver: MessageResolver,
) {
    internal val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("description-auth-alert"))

    @EventListener
    fun onAuthEvent(event: DescriptionProviderAuthEvent) {
        scope.launch {
            try {
                withTimeout(ALERT_TIMEOUT.toMillis()) {
                    telegramNotificationService.sendOwnerMessage { language -> render(event, language) }
                }
            } catch (e: TimeoutCancellationException) {
                logger.warn { "Description auth alert (${event.provider}, ${event.state}) timed out after $ALERT_TIMEOUT" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.warn(e) { "Failed to send description auth alert (${event.provider}, ${event.state})" }
            }
        }
    }

    internal fun render(
        event: DescriptionProviderAuthEvent,
        language: String,
    ): String =
        when (event.state) {
            DescriptionProviderAuthEvent.State.LOST ->
                buildString {
                    append(messageResolver.get("ai.description.auth.lost", language, event.provider, event.recoveryHint))
                    event.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                        append("\n\n")
                        append(detail.take(DETAIL_MAX_LENGTH))
                    }
                }

            DescriptionProviderAuthEvent.State.RESTORED ->
                messageResolver.get("ai.description.auth.restored", language, event.provider)
        }

    @PreDestroy
    fun shutdown() {
        scope.cancel()
    }

    private companion object {
        val ALERT_TIMEOUT: Duration = Duration.ofSeconds(5)
        const val DETAIL_MAX_LENGTH = 300
    }
}
```

- [ ] **Step 5: Запустить тесты**

Run: `./gradlew :frigate-analyzer-core:test --tests ru.zinin.frigate.analyzer.core.application.DescriptionAuthAlertNotifierTest` и `./gradlew :frigate-analyzer-telegram:test --tests ru.zinin.frigate.analyzer.telegram.i18n.DescriptionAuthMessagesTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/application/DescriptionAuthAlertNotifier.kt \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/application/DescriptionAuthAlertNotifierTest.kt \
        modules/telegram/src/main/resources/messages_ru.properties \
        modules/telegram/src/main/resources/messages_en.properties \
        modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/i18n/DescriptionAuthMessagesTest.kt
git commit -m "feat(core): notify the owner when a description provider loses or regains credentials" \
           -m "Claude-Session: https://claude.ai/code/session_015ZWCHwRx1akjYX3o1wtJ6S"
```

---

### Task 14: Docker: образ, compose, entrypoint, `.env.example`

**Files:**
- Modify: `docker/deploy/Dockerfile`
- Modify: `docker/deploy/docker-compose.yml`
- Modify: `docker/deploy/docker-entrypoint.sh`
- Modify: `docker/deploy/.env.example`

**Interfaces:**
- Consumes: env-переменные Grok из Global Constraints.
- Produces: образ с `grok` версии `GROK_VERSION`, том `./grok-home:/application/grok-home`, `GROK_HOME=/application/grok-home`.

- [ ] **Step 1: Dockerfile**

После `FROM azul/zulu-openjdk-alpine:25` второго этапа и `WORKDIR /application` добавить:

```dockerfile
# Grok Build (xAI) pinned: the app depends on specific headless flags and the JSON output shape.
ARG GROK_VERSION=1.0.13
```

В блоке создания каталогов заменить `RUN mkdir -p /tmp/frigate-analyzer /application/logs /application/config && \` на

```dockerfile
RUN mkdir -p /tmp/frigate-analyzer /application/logs /application/config /application/grok-home && \
```

(`chown -R appuser:appgroup /application` ниже в той же команде покрывает новый каталог).

После `RUN` с установкой Claude (тот, что пишет `settings.json`), всё ещё под `USER appuser` и `WORKDIR /tmp`, добавить:

```dockerfile
# Install Grok Build as appuser: static binary in ~/.grok/bin/grok, symlink in ~/.local/bin (already
# on PATH below). GROK_HOME at runtime points at the mounted volume, not at this ~/.grok.
RUN curl -fsSL https://x.ai/cli/install.sh | bash -s "$GROK_VERSION" && \
    /home/appuser/.local/bin/grok --version
```

- [ ] **Step 2: docker-compose.yml**

В сервисе `frigate-analyzer` в `volumes:` после строки `- ./logs:/application/logs` добавить:

```yaml
      # Grok Build home (provider=grok): auth.json, optional config.toml with BYOK models, sessions.
      # Create it on the host first: mkdir -p grok-home && sudo chown 1000:1000 grok-home
      - ./grok-home:/application/grok-home
```

В `environment:` после `- APP_LOG_LEVEL=${APP_LOG_LEVEL:-info}` добавить:

```yaml
      - GROK_HOME=/application/grok-home
```

- [ ] **Step 3: docker-entrypoint.sh**

Заменить весь блок `if [ "${APP_AI_DESCRIPTION_ENABLED:-false}" = "true" ]; then … fi` на:

```sh
if [ "${APP_AI_DESCRIPTION_ENABLED:-false}" = "true" ]; then
  case "${APP_AI_DESCRIPTION_PROVIDER:-claude}" in
    claude)
      if [ -z "${CLAUDE_CODE_OAUTH_TOKEN:-}" ] && [ -z "${ANTHROPIC_AUTH_TOKEN:-}" ]; then
          # ClaudeBackend.init fails fast with IllegalStateException when neither token is set
          # (avoid a silently broken feature). Advisory WARN here so the hint reaches stderr
          # before the JVM stack trace drowns it out.
          echo "WARN: APP_AI_DESCRIPTION_ENABLED=true but neither CLAUDE_CODE_OAUTH_TOKEN nor ANTHROPIC_AUTH_TOKEN is set; application will FAIL at startup." >&2
      elif [ -n "${CLAUDE_CLI_PATH:-}" ]; then
          # Explicit path override — check it directly; falling back to PATH would give a false negative.
          if [ -x "${CLAUDE_CLI_PATH}" ]; then
              echo "INFO: claude CLI detected at ${CLAUDE_CLI_PATH}: $(${CLAUDE_CLI_PATH} --version 2>/dev/null || echo 'unknown')"
          else
              echo "WARN: explicit CLAUDE_CLI_PATH=${CLAUDE_CLI_PATH} not found or not executable; AI descriptions will return fallback." >&2
          fi
      elif ! command -v claude >/dev/null 2>&1; then
          echo "WARN: claude CLI not found in PATH (CLAUDE_CLI_PATH is empty); AI descriptions will return fallback." >&2
      else
          echo "INFO: claude CLI detected: $(claude --version 2>/dev/null || echo 'unknown')"
      fi
      ;;
    grok)
      if [ -n "${GROK_CLI_PATH:-}" ]; then
          if [ -x "${GROK_CLI_PATH}" ]; then
              echo "INFO: grok CLI detected at ${GROK_CLI_PATH}: $(${GROK_CLI_PATH} --version 2>/dev/null || echo 'unknown')"
          else
              echo "WARN: explicit GROK_CLI_PATH=${GROK_CLI_PATH} not found or not executable; AI descriptions will return fallback." >&2
          fi
      elif ! command -v grok >/dev/null 2>&1; then
          echo "WARN: grok CLI not found in PATH (GROK_CLI_PATH is empty); AI descriptions will return fallback." >&2
      else
          echo "INFO: grok CLI detected: $(grok --version 2>/dev/null || echo 'unknown')"
      fi
      if [ -n "${GROK_HOME:-}" ]; then
          if [ ! -d "${GROK_HOME}" ] || [ ! -w "${GROK_HOME}" ]; then
              echo "WARN: GROK_HOME=${GROK_HOME} is missing or not writable; 'grok login' and token refresh will fail. On the host: mkdir -p grok-home && chown 1000:1000 grok-home" >&2
          elif [ ! -f "${GROK_HOME}/auth.json" ]; then
              echo "WARN: ${GROK_HOME}/auth.json not found; run 'docker compose exec frigate-analyzer grok login --device-code' (not needed for BYOK models with their own api_key in config.toml)." >&2
          else
              echo "INFO: grok credentials found in ${GROK_HOME}"
          fi
      else
          echo "WARN: GROK_HOME is not set; the application default under the temp folder is ephemeral, point GROK_HOME at a mounted volume." >&2
      fi
      ;;
    *)
      echo "WARN: unknown APP_AI_DESCRIPTION_PROVIDER='${APP_AI_DESCRIPTION_PROVIDER}' (known: claude, grok); AI descriptions will return fallback." >&2
      ;;
  esac
fi
```

- [ ] **Step 4: .env.example**

Заменить строку `# APP_AI_DESCRIPTION_PROVIDER=claude` на:

```
# Provider: claude | grok. Each has its own block below.
# APP_AI_DESCRIPTION_PROVIDER=claude
```

После блока `# --- Optional proxy for Claude API calls ---` (три закомментированные `CLAUDE_*_PROXY` строки) и перед `# --- Notification tracker …` вставить:

```
# --- Grok-specific (when provider=grok) ---
# Grok Build (https://x.ai/cli) signs in with your SuperGrok subscription; no API key is needed.
# First start: on the host `mkdir -p grok-home && sudo chown 1000:1000 grok-home`, then
# `docker compose up -d` and `docker compose exec frigate-analyzer grok login --device-code`.
# grok-home/auth.json refreshes itself afterwards. Never copy auth.json from another machine:
# the refresh token rotates and only one copy survives. The owner gets a Telegram message if the
# credentials stop working, with the command to run.
# Model id, or the name of a [model.<name>] BYOK entry from grok-home/config.toml.
# GROK_MODEL=grok-4.6
# Reasoning effort: low | medium | high | xhigh. Empty = the flag is not passed (BYOK models without
# reasoning levels).
# GROK_EFFORT=low
# Empty = `grok` from PATH. Non-empty = explicit binary path.
# GROK_CLI_PATH=
# Grok home on the persistent volume (auth.json, config.toml, sessions). docker-compose.yml sets it;
# keep both in sync if you change it.
# GROK_HOME=/application/grok-home
# Empty directory Grok runs in. Empty = <TEMP_FOLDER>/grok-cwd.
# GROK_WORKING_DIR=
# BYOK example, in grok-home/config.toml:
#   [model.my-vision]
#   model = "my-vision-model"
#   base_url = "https://gateway.example.com/v1"
#   env_key = "MY_GATEWAY_KEY"
# then GROK_MODEL=my-vision, GROK_EFFORT= (empty) and MY_GATEWAY_KEY=... in this file.

# --- Optional proxy for Grok API calls ---
# GROK_HTTP_PROXY=http://proxy:8080
# GROK_HTTPS_PROXY=http://proxy:8080
# GROK_NO_PROXY=localhost,127.0.0.1

```

- [ ] **Step 5: Проверить синтаксис и конфигурацию**

Run:

```bash
sh -n docker/deploy/docker-entrypoint.sh && bash -n docker/deploy/docker-entrypoint.sh && echo "entrypoint OK"
cd docker/deploy && ( [ -f .env ] || cp .env.example .env ) && docker compose config >/dev/null && echo "compose OK"; cd -
grep -n "GROK_VERSION\|grok-home\|x.ai/cli" docker/deploy/Dockerfile
```

Expected: `entrypoint OK`, `compose OK` (если `docker` недоступен, пропустить compose-проверку и отметить это в отчёте задачи), три совпадения в Dockerfile. Сборка образа и запуск идут в живой проверке Task 16. Если `.env` был создан только для проверки, удалить его: `rm docker/deploy/.env` (файл в `.gitignore`, но чтобы не оставлять мусор).

- [ ] **Step 6: Commit**

```bash
git add docker/deploy/Dockerfile docker/deploy/docker-compose.yml docker/deploy/docker-entrypoint.sh docker/deploy/.env.example
git commit -m "build(docker): install Grok Build, mount grok-home, provider-aware entrypoint checks" \
           -m "Claude-Session: https://claude.ai/code/session_015ZWCHwRx1akjYX3o1wtJ6S"
```

---

### Task 15: Документация

**Files:**
- Rewrite: `.claude/rules/ai-description.md`
- Modify: `.claude/rules/configuration.md` (раздел «AI Description»)
- Modify: `README.md` (раздел «AI description (optional)» и «Project Structure»)
- Modify: `CLAUDE.md` (таблица модулей, Key Patterns, таблица Modular Documentation)

- [ ] **Step 1: Переписать `.claude/rules/ai-description.md`**

Файл целиком:

```markdown
---
paths: "modules/ai-description/**,**/DescriptionEditJobRunner*,**/AiDescription*,**/RichNotificationRenderer*,**/DescriptionState*,**/DescriptionAuthAlertNotifier*"
---

# AI Description Module

Optional module that generates short and detailed natural-language descriptions of detections and
edits them into the Telegram notification. Two providers, selected by
`application.ai.description.provider`: `claude` (Claude Code CLI via `org.springaicommunity:claude-code-sdk`)
and `grok` (Grok Build CLI from xAI, headless via `ProcessBuilder`). Gated by
`application.ai.description.enabled` — when `false`, no agent bean is created, no CLI is required, and
the notification goes out with `DescriptionState.Absent`: no description blocks, no edit job.

Both CLIs are installed into the runtime container by `docker/deploy/Dockerfile` (`claude.ai/install.sh`
and `x.ai/cli/install.sh` pinned by `ARG GROK_VERSION`); local development needs the chosen binary on
`PATH` or an explicit `*_CLI_PATH`.

## Layers

| Layer | Component | Location | Purpose |
|-------|-----------|----------|---------|
| API | `DescriptionAgent` | `api/` | Single-method `suspend fun describe(request): DescriptionResult` |
| API | `DescriptionRequest` / `DescriptionResult` / `DescriptionException` | `api/` | Public DTOs; `DescriptionException` is provider-neutral: `Timeout`, `InvalidResponse`, `Transport`, `RateLimited`, `Unauthorized` |
| API | `DescriptionProviderAuthEvent` | `api/` | Spring event `LOST` / `RESTORED`, one per transition |
| API | `TempFileWriter` | `api/` | Filesystem abstraction for staging files (implemented in core) |
| Core | `DescriptionBackend` | `core/` | Provider SPI: one attempt, no semaphore, no retry |
| Core | `DefaultDescriptionAgent` | `core/` | Semaphore, queue/work timeouts, retry policy, auth state machine |
| Core | `ResultNormalizer` / `LanguageNames` | `core/` | Blank-field check + `…` truncation; language names for prompts |
| Claude | `ClaudeBackend` | `claude/` | stage jpg → prompt with `@/abs/path` → SDK → parse |
| Claude | `ClaudeImageStager`, `ClaudePromptBuilder`, `ClaudeInvoker`/`DefaultClaudeInvoker`, `ClaudeAsyncClientFactory`, `ClaudeResponseParser`, `ClaudeExceptionMapper` | `claude/` | Claude specifics; all gated on `provider=claude` |
| Grok | `GrokBackend` | `grok/` | prompt.json → process → `structuredOutput` |
| Grok | `GrokPromptBuilder`, `GrokPromptFileWriter` | `grok/` | Prompt text, ACP content blocks with inline base64 frames |
| Grok | `GrokCommandBuilder`, `GrokProcessRunner`/`DefaultGrokProcessRunner` | `grok/` | argv + isolated env; `ProcessBuilder` with cancellation-safe kill |
| Grok | `GrokOutputParser`, `GrokExceptionMapper` | `grok/` | JSON stdout, error envelope, classification |
| Grok | `GrokHomeGuard`, `GrokHomeSweeper` | `grok/` | shared/exclusive lock on `GROK_HOME`; hourly cleanup of `sessions/` and `logs/` |
| Config | `AiDescriptionAutoConfiguration` | `config/` | Registers properties; creates the agent `@Bean` when a `DescriptionBackend` exists |
| Config | `DescriptionProperties` / `ClaudeProperties` / `GrokProperties` | `config/` | `@ConfigurationProperties` for `application.ai.description.*`; both provider sections bind always |
| Config | `DescriptionAgentSanityChecker` | `config/` | WARN when `enabled=true` but no agent (unknown provider) |
| Limits | `DescriptionRateLimiter` | `ratelimit/` | Sliding-window throttle; `tryAcquire()` returns false when quota exceeded |

## Provider selection and retry

Backends are `@Component`s gated on `enabled=true` and `provider=<id>`. The agent is a `@Bean` in the
auto-configuration guarded by `@ConditionalOnBean(DescriptionBackend::class)`, so an unknown provider
yields no agent, a WARN from `DescriptionAgentSanityChecker`, and notifications without placeholders.
Consumers (`RecordingProcessingFacade`, telegram) use `ObjectProvider<DescriptionAgent>` and never see
the provider.

`DefaultDescriptionAgent` retries once on `InvalidResponse` (immediately) and once on `Transport`
(after 5 s), each only if enough of `timeout` is left (5 s and 10 s respectively). `Timeout`,
`RateLimited` and `Unauthorized` are not retried. Anything a backend throws that is not a
`DescriptionException` becomes `Transport`.

## Grok invocation

Per recording `GrokPromptFileWriter` writes `prompt.json` (suffix mandatory: any other extension is
read as plain text) with ACP content blocks: intro text, then `Frame N:` + `{"type":"image",
"mimeType":"image/jpeg","data":"<base64>"}` per frame in `frameIndex` order, then the rules.
`GrokCommandBuilder` runs:

```
grok --prompt-file <file> --json-schema '{…short,detailed…}' --output-format json -m <model>
     [--effort <effort>] --max-turns 1 --tools read_file --no-plan --no-subagents
     --disable-web-search --permission-mode bypassPermissions --no-auto-update
     --system-prompt-override "<constant>" --cwd <working-directory>
```

with `GROK_HOME=<home>`, `GROK_DISABLE_AUTOUPDATER=1`, `GROK_MEMORY=0`, `GROK_SUBAGENTS=0` and
`GROK_CLAUDE_*_ENABLED=0` / `GROK_CURSOR_*_ENABLED=0` on top of the JVM environment (Grok otherwise
loads Claude Code skills, rules and plugins from `HOME`). `--tools read_file` is only an allowlist that
disables default tool injection; frames are inline, the model needs no tool. `--effort` is omitted
when blank so BYOK models without reasoning levels work.

Output classification (`GrokExceptionMapper`): exit 0 with both `structuredOutput` fields → result;
exit 0 with `stopReason` `max_tokens` / `refusal` / `max_turn_requests` or a partial object →
`InvalidResponse`; `cancelled` → `Transport`; non-zero exit with `{"type":"error","message":…}` on
stdout → `Unauthorized` when the message mentions `not signed in`, `grok login`, `not authenticated`,
`unauthorized`, `invalid_grant`, `refresh token`, `authentication failed`; `RateLimited` on
`rate limit`, `too many requests`, `429`; everything else `Transport` with the stderr tail. Token usage
and cost are logged at DEBUG.

**GROK_HOME hygiene.** Every headless run persists a session under `GROK_HOME/sessions/<cwd>/<id>/`
with the base64 frames, and `sessions/session_search.sqlite` grows ~9 KB per run without shrinking.
`GrokHomeSweeper` runs one minute after startup and then hourly under `GrokHomeGuard.exclusive`
(waits for in-flight runs, blocks new ones for milliseconds) and deletes everything under `sessions/`
plus the files in `logs/`. `auth.json` and `config.toml` are never touched. The app must be the only
user of that `GROK_HOME`; `grok login` creates no sessions.

**Credentials.** OAuth via `grok login --device-code` inside the container; the access token lives
6 hours and refreshes itself, the refresh token rotates, so `auth.json` must never be copied from
another machine. BYOK models are `[model.<name>]` entries in `GROK_HOME/config.toml` with their own
`api_key`/`env_key`; the app only passes `-m <name>`.

## Authorization alerts

`DefaultDescriptionAgent` keeps an `AtomicReference` of `HEALTHY`/`LOST`. The first `Unauthorized`
after a success (or startup) flips it and publishes `DescriptionProviderAuthEvent(LOST, detail,
recoveryHint)` with an ERROR log; the first success afterwards flips it back and publishes `RESTORED`.
`compareAndSet` guarantees one event per transition under concurrency. Calls are not short-circuited
while `LOST`: a failing `grok` exits fast and costs nothing, and the next success is what restores.

`DescriptionAuthAlertNotifier` (core, `application/`, gated on telegram and ai enabled) listens and
calls `TelegramNotificationService.sendOwnerMessage` with `ai.description.auth.lost` /
`ai.description.auth.restored` (args: provider, recovery hint), appending the provider's technical
detail trimmed to 300 characters. Rate-limiter slots are never refunded on failure.

## Integration with Telegram

When a notification is enqueued and AI description is enabled:

1. `TelegramNotificationSender` sends the rich message rendered with `DescriptionState.Pending` —
   the short and detailed placeholders (only if `DescriptionRateLimiter.tryAcquire()` succeeded).
2. `DescriptionEditJobRunner` (in `telegram/queue/`) launches a coroutine on `DescriptionEditScope`
   that awaits `DescriptionAgent.describe(...)`. One model call per recording fans out to one
   `EditTarget` per recipient.
3. **One** edit closes the flow per recipient: `EditChatMessageRichText` with the HTML re-rendered
   for `DescriptionState.Ready` (model text) or `DescriptionState.Failed` (the
   `ai.description.fallback.unavailable` line in the `<p>`, and **no** `<details>` at all — a
   spoiler labelled "detailed description" holding the same one-line apology promises detail and
   delivers none; no error text is exposed either way). The edit re-declares `reply_markup` too,
   from `QuickExportHandler.currentKeyboard` (per chat and per export state), and re-checks it once
   the edit has landed — see the Quick Export keyboard row in `telegram.md`.

The render state is an explicit type, `DescriptionState` (`Absent` / `Pending` / `Ready` / `Failed`
in `telegram/service/model/`), instead of the former "no formatter means the feature is off" flag.

**Media are re-declared on every edit.** Omitting the `media` array while the HTML still references
`tg://photo?id=…` fails with `400 RICH_MESSAGE_PHOTO_INVALID`, even though the ids are unchanged.
That is why `EditTarget` carries the frame `file_id`s and not just a message id — and why the sender
never edits by a photo-id list whose length differs from the number of frames sent: a short array
would strip frames off the delivered message. On such an answer it falls back to the full list another
recipient cached, and skips its own edit only when there is none.

`AiDescriptionTelegramGuard` (in telegram module) fails fast at startup when
`ai.description.enabled=true` is paired with `telegram.enabled=false` — the feature only makes
sense when there's a chat to edit.

## Rate Limiting

- `DescriptionRateLimiter` enforces a sliding window (`max` requests per `window`).
- Counter increments **when a slot is granted**; failed model calls do NOT refund the slot —
  this is intentional to keep cost predictable when the binary is misbehaving.
- When the limit is exceeded, the recording is sent with `DescriptionState.Absent` — no
  placeholders, no edit job, no model call.
- Disable with `APP_AI_DESCRIPTION_RATE_LIMIT_ENABLED=false`.

## Concurrency

- `APP_AI_DESCRIPTION_MAX_CONCURRENT` (default `2`) bounds simultaneous model calls — enforced
  inside `DefaultDescriptionAgent` via a `Semaphore`; for Grok that is also the number of `grok`
  processes alive at once.
- `APP_AI_DESCRIPTION_QUEUE_TIMEOUT` (default `30s`) — max wait for a free slot before failing
  the describe call.
- `APP_AI_DESCRIPTION_TIMEOUT` (default `60s`) — per-call timeout including the agent's retries;
  on expiry the Grok process is killed.

## Configuration

All variables documented in `.claude/rules/configuration.md` under "AI Description". Key flags:

- `APP_AI_DESCRIPTION_ENABLED` — master gate
- `APP_AI_DESCRIPTION_PROVIDER` — `claude` or `grok`
- `APP_AI_DESCRIPTION_LANGUAGE` — `ru` or `en`
- `APP_AI_DESCRIPTION_SHORT_MAX` / `APP_AI_DESCRIPTION_DETAILED_MAX` — character caps for the
  short paragraph and the `<details>` body
- `APP_AI_DESCRIPTION_MAX_FRAMES` — frames forwarded to the model per recording
- `CLAUDE_MAX_BUFFER_SIZE` — max size of one JSON message from the Claude CLI (default 16MB). The CLI
  echoes every frame the model reads back as base64, so the SDK's 1 MiB default overflowed on
  ~800 KB frames; an oversized line is dropped with `Failed to process message (continuing)`
- `GROK_MODEL`, `GROK_EFFORT`, `GROK_HOME`, `GROK_WORKING_DIR`, `GROK_CLI_PATH`, `GROK_*_PROXY` — Grok
  section, see `configuration.md`

## Testing

Unit tests use fakes at the seams: `DescriptionBackend` for the agent, `ClaudeInvoker` for Claude,
`GrokProcessRunner` for Grok. `DefaultGrokProcessRunnerTest` runs a stub `grok` shell script
(POSIX only) and covers stdout/stderr capture, environment, and the kill on cancellation.
`AiDescriptionAutoConfigurationTest` covers `provider=claude`, `provider=grok` and an unknown provider.
```

- [ ] **Step 2: `.claude/rules/configuration.md`, раздел «AI Description»**

Заменить вводный абзац раздела на:

```markdown
Settings under `application.ai.description` in `application.yaml`. Enables AI-generated short and detailed descriptions of detections via Claude Code CLI or Grok Build CLI. Requires `APP_AI_DESCRIPTION_ENABLED=true`. Both provider sections bind on every deployment, so their defaults must stay valid regardless of `APP_AI_DESCRIPTION_PROVIDER`.
```

Заменить строку таблицы `APP_AI_DESCRIPTION_PROVIDER` на:

```markdown
| `APP_AI_DESCRIPTION_PROVIDER` | claude | Provider implementation: `claude` or `grok`. An unknown value logs a WARN at startup and every recording goes out without description blocks. |
```

Добавить после строки `APP_AI_DESCRIPTION_RATE_LIMIT_WINDOW` подраздел:

```markdown
### Grok provider (`APP_AI_DESCRIPTION_PROVIDER=grok`)

| Variable | Default | Purpose |
|----------|---------|---------|
| `GROK_MODEL` | grok-4.6 | Model id, or the name of a `[model.<name>]` BYOK entry from `GROK_HOME/config.toml`. Must not be blank even when the provider is `claude`. |
| `GROK_EFFORT` | low | Reasoning effort passed as `--effort`. Empty = the flag is not passed, which BYOK models without reasoning levels need. grok-4.6 accepts `low`, `medium`, `high`, `xhigh`. |
| `GROK_CLI_PATH` | (empty) | Explicit binary; empty = `grok` from `PATH`. |
| `GROK_HOME` | `<TEMP_FOLDER>/grok-home` | Grok's own directory: `auth.json`, optional `config.toml`, sessions. The same variable drives a manual `grok login` inside `docker compose exec`, so `docker-compose.yml` sets it to the mounted `./grok-home`. Must be writable by uid 1000: the refresh token rotates and is written back. `GrokHomeSweeper` empties `sessions/` and `logs/` hourly. |
| `GROK_WORKING_DIR` | `<TEMP_FOLDER>/grok-cwd` | Empty directory passed as `--cwd`; Grok reads `AGENTS.md`, `CLAUDE.md`, `.claude/rules` and `.grok` from there, so keep it empty. |
| `GROK_HTTP_PROXY` / `GROK_HTTPS_PROXY` / `GROK_NO_PROXY` | (empty) | Passed to the `grok` process as `HTTP_PROXY` / `HTTPS_PROXY` / `NO_PROXY` when set. |

First sign-in: `mkdir -p grok-home && sudo chown 1000:1000 grok-home`, `docker compose up -d`, then
`docker compose exec frigate-analyzer grok login --device-code`. Never copy `auth.json` from another
machine. When the credentials stop working the owner receives one Telegram message per outage with
the command to run, and another when descriptions work again.
```

- [ ] **Step 3: `README.md`**

Заменить раздел `### AI description (optional)` (до `## Detection Servers`) на:

```markdown
### AI description (optional)

When enabled, generates a short and a detailed natural-language description of detections and edits
them into the notification. Two providers: the Claude Code CLI (`claude`) and the xAI Grok Build CLI
(`grok`). Both binaries ship in the image; pick one with `APP_AI_DESCRIPTION_PROVIDER`.

| Variable | Default | Description |
|----------|---------|-------------|
| `APP_AI_DESCRIPTION_ENABLED` | `false` | Master switch |
| `APP_AI_DESCRIPTION_PROVIDER` | `claude` | `claude` or `grok` |
| `APP_AI_DESCRIPTION_LANGUAGE` | `en` | `ru` or `en` |
| `APP_AI_DESCRIPTION_MAX_CONCURRENT` | `2` | Max simultaneous model requests |
| `APP_AI_DESCRIPTION_RATE_LIMIT_MAX` | `10` | Max invocations per sliding window |
| `APP_AI_DESCRIPTION_RATE_LIMIT_WINDOW` | `1h` | Sliding-window length |
| `CLAUDE_CODE_OAUTH_TOKEN` | *(required for claude)* | Token from `claude setup-token` |
| `CLAUDE_MODEL` | `opus` | `opus` / `sonnet` / `haiku` |
| `CLAUDE_MAX_BUFFER_SIZE` | `16MB` | Max size of one JSON message from the Claude CLI. Frames the model reads are echoed back as base64, so raise it for cameras with frames above ~12 MB |
| `GROK_MODEL` | `grok-4.6` | Model id, or a BYOK model name from `grok-home/config.toml` |
| `GROK_EFFORT` | `low` | Reasoning effort; empty = not passed |

**Grok sign-in.** Grok uses your SuperGrok subscription, no API key. Once, on the host:

```bash
mkdir -p grok-home && sudo chown 1000:1000 grok-home   # compose would create it as root otherwise
docker compose up -d
docker compose exec frigate-analyzer grok login --device-code
```

Open the printed URL, enter the code. `grok-home/auth.json` then refreshes itself; never copy it from
another machine, the refresh token rotates and only one copy survives. If the credentials stop
working, the bot owner receives a Telegram message with the command to run.

**Custom models (BYOK).** Put a `[model.<name>]` section with `model`, `base_url` and `env_key` into
`grok-home/config.toml`, pass the key through `.env`, set `GROK_MODEL=<name>` and an empty
`GROK_EFFORT`.

Full list of variables (notification dedup, ffmpeg tuning, detection thresholds, etc.) lives in
[`.claude/rules/configuration.md`](.claude/rules/configuration.md) and `docker/deploy/.env.example`.
```

В `### Project Structure` заменить строку `├── ai-description/ # AI-generated detection descriptions via Claude Code SDK` на `├── ai-description/ # AI-generated detection descriptions via Claude Code SDK or Grok Build CLI`.

- [ ] **Step 4: `CLAUDE.md`**

Заменить строку таблицы модулей `| ai-description | AI-generated detection descriptions via Claude Code SDK |` на `| ai-description | AI-generated detection descriptions; providers: Claude Code SDK, Grok Build CLI |`.

В Key Patterns заменить строку `- **AI description:** Async Claude Code CLI invocation with rate-limit + queue, edits notification message` на `- **AI description:** Provider-neutral agent (semaphore, retry, auth-loss alert to owner) over `DescriptionBackend`; Claude Code SDK or headless Grok Build CLI; edits the notification message`.

В таблице Modular Documentation заменить строку `| ai-description.md | Claude Code SDK integration, rate limiter, description agent | \`modules/ai-description/**\` |` на `| ai-description.md | Provider SPI, Claude and Grok backends, auth alerts, rate limiter | \`modules/ai-description/**\` |`.

- [ ] **Step 5: Проверить ссылки и опечатки**

Run:

```bash
grep -n "provider=grok\|GROK_HOME\|DescriptionBackend" .claude/rules/ai-description.md | head -5
grep -n "GROK_MODEL" .claude/rules/configuration.md README.md
grep -n "Grok" CLAUDE.md
```

Expected: совпадения во всех четырёх файлах.

- [ ] **Step 6: Commit**

```bash
git add .claude/rules/ai-description.md .claude/rules/configuration.md README.md CLAUDE.md
git commit -m "docs: describe the Grok description provider, auth alerts and GROK_HOME setup" \
           -m "Claude-Session: https://claude.ai/code/session_015ZWCHwRx1akjYX3o1wtJ6S"
```

---

### Task 16: Полная сборка, ревью и живая проверка

**Files:**
- Нет новых файлов. Возможны точечные правки по результатам сборки и ревью.

- [ ] **Step 1: ktlint и полная сборка**

Run (через build-runner): `./gradlew ktlintFormat` затем `./gradlew build`
Expected: BUILD SUCCESSFUL, все тесты всех модулей зелёные. Ошибки чинить в том же коммите, что и вызвавшая их задача, если они локальны, иначе отдельным коммитом `fix(...)`.

- [ ] **Step 2: Ревью кода**

Запустить агента `superpowers:code-reviewer` (правило `CLAUDE.md`) на диффе `master..HEAD`. Критичные замечания исправить, повторить до чистого результата, затем снова `./gradlew build`.

- [ ] **Step 3: Живая проверка на стенде (вручную, вне CI)**

```bash
# 1. Образ
docker build -f docker/deploy/Dockerfile -t frigate-analyzer:grok .
docker run --rm --entrypoint grok frigate-analyzer:grok --version        # grok 1.0.13

# 2. Деплой с provider=grok
cd docker/deploy
mkdir -p grok-home && sudo chown 1000:1000 grok-home
# в .env: APP_AI_DESCRIPTION_ENABLED=true, APP_AI_DESCRIPTION_PROVIDER=grok, IMAGE_TAG под собранный образ
docker compose up -d
docker compose logs frigate-analyzer | grep -i "grok"                    # INFO про бинарник, WARN про auth.json
docker compose exec frigate-analyzer grok login --device-code             # URL + код
ls -la grok-home/auth.json                                                # появился, 0600, владелец 1000

# 3. Изоляция промпта
docker compose exec -e GROK_HOME=/application/grok-home frigate-analyzer \
  sh -c 'cd /tmp/frigate-analyzer/grok-cwd && GROK_CLAUDE_SKILLS_ENABLED=0 GROK_CLAUDE_RULES_ENABLED=0 grok inspect | head -30'
# ожидание: 0 skills, 0 rules, 0 plugins
```

Затем дождаться одной записи с детекциями:

- в уведомлении Telegram появились короткое и подробное описания на языке `APP_AI_DESCRIPTION_LANGUAGE`;
- в логе на DEBUG строка `Grok describe for recording …: input_tokens=… total_cost_usd=…` (включить `ru.zinin.frigate.analyzer.ai.description: DEBUG` в `application-docker.yaml`);
- размер `grok-home/sessions/` до и после часового прохода sweeper-а (или дождаться строки `Grok home sweep removed N entries` на DEBUG);
- имитация потери авторизации: `mv grok-home/auth.json grok-home/auth.json.bak`, следующая запись даёт «Описание недоступно», владельцу приходит сообщение `ai.description.auth.lost` с командой; `mv` обратно, следующая запись даёт описание и сообщение `ai.description.auth.restored`;
- при десяти кадрах по 300–800 КБ вызов укладывается в `APP_AI_DESCRIPTION_TIMEOUT`; если API отвергает размер запроса, снизить `APP_AI_DESCRIPTION_MAX_FRAMES` и записать предел в `configuration.md`.

Результаты живой проверки записать в описание PR.

- [ ] **Step 4: Убрать документы superpowers перед PR**

Правило владельца: spec и план не попадают в диф PR, они остаются в истории ветки.

```bash
git rm docs/superpowers/specs/2026-09-02-grok-description-provider-design.md \
       docs/superpowers/plans/2026-09-02-grok-description-provider.md
git commit -m "docs: remove superpowers design and plan documents before PR" \
           -m "Claude-Session: https://claude.ai/code/session_015ZWCHwRx1akjYX3o1wtJ6S"
```

Остальные untracked-файлы в `docs/superpowers/plans/` не трогать.

- [ ] **Step 5: PR**

Открыть PR из `feature/grok-description-provider` в `master` (`gh pr create`). Описание: цель, архитектура в трёх предложениях, список env-переменных, процедура входа, результаты живой проверки, и последняя строка `https://claude.ai/code/session_015ZWCHwRx1akjYX3o1wtJ6S`.

---

## Self-review

- **Spec coverage.** Ядро и SPI: Tasks 1–3. Claude backend и условия: Task 4. Claude `Unauthorized`: Task 5. `GrokProperties`, yaml, биндинг: Task 6. Prompt и `prompt.json`: Task 7. argv/env: Task 8. Runner: Task 9. Разбор и классификация: Task 10. Guard и sweeper: Task 11. `GrokBackend`, sanity checker, автоконфиг для grok: Task 12. Событие → владелец, i18n: Task 13. Dockerfile, compose, entrypoint, `.env.example`: Task 14. Документация: Task 15. Живая проверка и риски spec-а: Task 16.
- **Placeholder scan.** Каждый шаг с кодом содержит код; нет «TBD», «add validation», «similar to Task N».
- **Type consistency.** `DescriptionBackend.describe(request)`, `DefaultDescriptionAgent(backend, descriptionProperties, eventPublisher, timeSource)`, `GrokCommand(argv, environment, workingDirectory)`, `GrokProcessResult(exitCode, stdout, stderrTail)`, `GrokOutput(stopReason, sessionId, short, detailed, usageSummary)`, `GrokExceptionMapper.fromFailure(exitCode, errorMessage, stderrTail)` / `fromStopReason(stopReason)`, `GrokHomeGuard.shared/exclusive`, `GrokHomeSweeper.sweep(): Int`, `DescriptionProviderAuthEvent(provider, state, detail, recoveryHint)` используются одинаково во всех задачах.
