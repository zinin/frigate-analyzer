# AI Description Presets Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Держать в конфиге несколько именованных пресетов AI-описаний (`provider` + `model` + `effort`) одновременно и дать владельцу переключать активный — и выключать описания целиком — из owner-команды `/ai`, с сохранением выбора в `app_settings` через рестарт.

**Architecture:** Backend-ы перестают быть статическими `@Component`-ами и создаются по экземпляру на пресет фабриками `DescriptionBackendFactory`; автоконфигурация собирает `DescriptionPresetCatalog`, `ActivePresetResolver` резолвит активный пресет на каждый вызов через SPI `DescriptionRuntimeSettings` (реализация в `core` поверх `AppSettingsService`), состояние авторизации переезжает из агента в `ProviderAuthTracker` и становится по-провайдерным. Telegram-диалог `/ai` читает каталог, резолвер и трекер, пишет через тот же SPI.

**Tech Stack:** Kotlin 2.4.10, Spring Boot 4.1.0, Java 25, kotlinx-coroutines 1.11.0, R2DBC/PostgreSQL, ktgbotapi 36.1.0, MockK, kotlin-test JUnit5, AssertJ, ktlint.

**Spec:** `docs/superpowers/specs/2026-09-04-ai-description-presets-design.md`

## Global Constraints

- Все команды Gradle (`./gradlew …`) запускаются **только** через агента `claude-forge:build-runner`, никогда напрямую в основной сессии (правило `CLAUDE.md`). На ошибки ktlint: `./gradlew ktlintFormat`, затем повтор.
- Тесты одного модуля: `./gradlew :frigate-analyzer-ai-description:test`, `:frigate-analyzer-core:test`, `:frigate-analyzer-telegram:test`. Один класс: `--tests <FQCN>`.
- После создания или изменения файла обязательно `git add <file>` (правило `CLAUDE.md`).
- Каждое сообщение коммита заканчивается отдельным `-m` со строкой `Claude-Session: https://claude.ai/code/session_01MppBgCU5bLAPtDtnjoCYVk`.
- Конструкторы `@ConfigurationProperties`-классов вызываются только с именованными аргументами; новые параметры добавляются в конец списка.
- Идентификаторы провайдеров: `claude`, `grok`. Id пресета: `[a-z0-9][a-z0-9-]{0,31}`.
- Уровни `effort`: пусто, `low`, `medium`, `high`, `xhigh`, `max`. Непустой `effort` допустим только при `provider=grok`.
- Ключи `app_settings`: `ai.description.preset.active` (строка), `ai.description.enabled` (boolean, отсутствует = `true`).
- Префикс callback-данных диалога: `aip:`. Значения в payload всегда явные, никогда не toggle.
- Значения i18n не содержат апострофов (MessageFormat), ключи добавляются **в оба** бандла `modules/telegram/src/main/resources/messages_{ru,en}.properties`.
- Секреты (токены, `auth.json`) не логируются и не попадают в сообщения Telegram.
- Kotlin allopen через `kotlin-spring` применён ко всем модулям: `@Bean`-методы в `@AutoConfiguration` не требуют `open`.

---

## Структура файлов

Модуль `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/`:

| Файл | Ответственность |
|---|---|
| `api/DescriptionPreset.kt` (create) | DTO пресета для потребителей: id, provider, model, effort, причина недоступности |
| `api/DescriptionPresets.kt` (create) | Read-only каталог наружу: `all()` |
| `api/ProviderAuthStates.kt` (create) | Read-only состояние авторизации по провайдерам: `byProvider()` |
| `api/DescriptionRuntimeSettings.kt` (create) | SPI рантайм-настроек: активный пресет и выключатель |
| `core/DescriptionBackendFactory.kt` (create) | SPI провайдера: пригодность и создание backend-а на пресет |
| `core/DescriptionPresetCatalog.kt` (create) | Неизменяемый каталог пресетов с backend-ами |
| `core/DescriptionPresetCatalogBuilder.kt` (create) | Чистая сборка каталога из карты пресетов и фабрик |
| `core/ActivePresetResolver.kt` (create) | Резолюция активного пресета на вызов, WARN однократно |
| `core/InMemoryDescriptionRuntimeSettings.kt` (create) | Дефолт SPI без БД: тесты и standalone-использование модуля |
| `core/ProviderAuthTracker.kt` (create) | Машина состояний авторизации по провайдеру и публикация событий |
| `core/DefaultDescriptionAgent.kt` (modify) | Резолвер вместо backend-а, трекер вместо своей машины состояний |
| `claude/ClaudeBackendFactory.kt` (create) | Проверки Claude, создание `ClaudeBackend` на пресет |
| `claude/ClaudeBackend.kt` (modify) | Модель из пресета, без `@Component` и без init-проверок |
| `grok/GrokBackendFactory.kt` (create) | Проверки Grok, создание `GrokBackend` на пресет |
| `grok/GrokBackend.kt` (modify) | Модель и effort из пресета, без `@Component` и без init-проверок |
| `claude/*`, `grok/*` (modify, 16 файлов) | Условие бина меняется с `provider=<id>` на `enabled=true` |
| `claude/ClaudeAsyncClientFactory.kt`, `claude/ClaudeInvoker.kt`, `claude/DefaultClaudeInvoker.kt`, `grok/GrokCommandBuilder.kt` (modify) | `model` и `effort` становятся параметрами вызова |
| `config/DescriptionProperties.kt` (modify) | `presets`, `default-preset`, валидация |
| `config/DescriptionPresetsDeclaredCondition.kt` (create) | Условие «пресеты объявлены» для бина каталога |
| `config/AiDescriptionAutoConfiguration.kt` (modify) | Каталог, резолвер, трекер, агент |
| `config/DescriptionAgentSanityChecker.kt` (modify) | Текст WARN упоминает пресеты |

Модуль `modules/core`:

| Файл | Ответственность |
|---|---|
| `application/AppSettingsDescriptionRuntimeSettings.kt` (create) | Реализация SPI поверх `AppSettingsService` |
| `facade/RecordingProcessingFacade.kt` (modify) | Гейт рантайм-выключателя |
| `src/main/resources/application.yaml`, `src/test/resources/application.yaml` (modify) | `default-preset` |

Модуль `modules/service`: `AppSettingKeys.kt` (modify) — два новых ключа.

Модуль `modules/telegram`:

| Файл | Ответственность |
|---|---|
| `dto/AiSettingsViewState.kt` (create) | Состояние экрана |
| `bot/handler/aisettings/AiSettingsViewStateFactory.kt` (create) | Единая точка сборки состояния |
| `bot/handler/aisettings/AiSettingsMessageRenderer.kt` (create) | Текст и клавиатура |
| `bot/handler/aisettings/AiSettingsCallbackHandler.kt` (create) | Диспетчер `aip:*` |
| `bot/handler/aisettings/AiSettingsCommandHandler.kt` (create) | Команда `/ai` |
| `bot/FrigateAnalyzerBot.kt` (modify) | Регистрация коллбэков `aip:` |
| `resources/messages_{ru,en}.properties` (modify) | Ключи диалога и строка `/ai` в алерте авторизации |

Деплой и документация: `docker/deploy/docker-entrypoint.sh`, `docker/deploy/application-docker.yaml.example`, `docker/deploy/.env.example`, `README.md`, `CLAUDE.md`, `.claude/rules/ai-description.md`, `.claude/rules/configuration.md`, `.claude/rules/database.md`.

---

### Task 1: Свойства пресетов, yaml и валидация

**Files:**
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/config/DescriptionProperties.kt`
- Modify: `modules/core/src/main/resources/application.yaml:99-102`
- Modify: `modules/core/src/test/resources/application.yaml:44-47`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/config/DescriptionPresetsValidationTest.kt` (create)
- Test: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/properties/DescriptionPresetsBindingTest.kt` (create)

**Interfaces:**
- Produces: `DescriptionProperties.presets: Map<String, DescriptionProperties.Preset>`, `DescriptionProperties.defaultPreset: String`, `DescriptionProperties.Preset(provider: String, model: String, effort: String = "")`, `DescriptionProperties.KNOWN_PROVIDERS: List<String>`.

- [ ] **Step 1: Написать падающий тест валидации**

Создать `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/config/DescriptionPresetsValidationTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.config

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DescriptionPresetsValidationTest {
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

    private fun props(
        presets: Map<String, DescriptionProperties.Preset>,
        defaultPreset: String = "",
    ) = DescriptionProperties(
        enabled = true,
        provider = "grok",
        common = common,
        defaultPreset = defaultPreset,
        presets = presets,
    )

    @Test
    fun `a valid map binds`() {
        val parsed =
            props(
                mapOf(
                    "grok-fast" to DescriptionProperties.Preset(provider = "grok", model = "grok-4.6", effort = "low"),
                    "claude-opus" to DescriptionProperties.Preset(provider = "claude", model = "opus"),
                ),
                defaultPreset = "grok-fast",
            )

        assertEquals(listOf("grok-fast", "claude-opus"), parsed.presets.keys.toList())
        assertEquals("", parsed.presets.getValue("claude-opus").effort)
    }

    @Test
    fun `an unknown provider is rejected`() {
        val e =
            assertFailsWith<IllegalArgumentException> {
                props(mapOf("x" to DescriptionProperties.Preset(provider = "gemini", model = "m")))
            }
        assertTrue(e.message!!.contains("gemini"), e.message)
    }

    @Test
    fun `effort on a claude preset is rejected`() {
        val e =
            assertFailsWith<IllegalArgumentException> {
                props(mapOf("c" to DescriptionProperties.Preset(provider = "claude", model = "opus", effort = "low")))
            }
        assertTrue(e.message!!.contains("effort"), e.message)
    }

    @Test
    fun `an effort outside the set is rejected`() {
        val e =
            assertFailsWith<IllegalArgumentException> {
                props(mapOf("g" to DescriptionProperties.Preset(provider = "grok", model = "m", effort = "turbo")))
            }
        assertTrue(e.message!!.contains("turbo"), e.message)
    }

    @Test
    fun `a blank model is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            props(mapOf("g" to DescriptionProperties.Preset(provider = "grok", model = " ")))
        }
    }

    @Test
    fun `a malformed id is rejected`() {
        val e =
            assertFailsWith<IllegalArgumentException> {
                props(mapOf("Grok Fast" to DescriptionProperties.Preset(provider = "grok", model = "m")))
            }
        assertTrue(e.message!!.contains("Grok Fast"), e.message)
    }

    @Test
    fun `a default-preset outside the map is rejected`() {
        val e =
            assertFailsWith<IllegalArgumentException> {
                props(
                    mapOf("g" to DescriptionProperties.Preset(provider = "grok", model = "m")),
                    defaultPreset = "missing",
                )
            }
        assertTrue(e.message!!.contains("missing"), e.message)
    }

    @Test
    fun `an empty map with a blank default-preset is allowed`() {
        assertEquals(emptyMap(), props(emptyMap()).presets)
    }
}
```

- [ ] **Step 2: Запустить тест и убедиться, что он падает**

Через агента `claude-forge:build-runner`:
`./gradlew :frigate-analyzer-ai-description:test --tests ru.zinin.frigate.analyzer.ai.description.config.DescriptionPresetsValidationTest`
Ожидание: компиляция падает — у `DescriptionProperties` нет параметров `defaultPreset` и `presets`.

- [ ] **Step 3: Добавить свойства и валидацию**

В `DescriptionProperties.kt` добавить два параметра **в конец** конструктора (после `common`), вложенный класс и companion:

```kotlin
    val enabled: Boolean,
    // Без @NotBlank — при enabled=false provider может быть пустым в конфиге.
    // Legacy-путь: используется, только когда карта presets пуста.
    val provider: String,
    @field:Valid
    val common: CommonSection,
    /** Активный пресет по умолчанию; пусто = первый годный пресет каталога. */
    val defaultPreset: String = "",
    /**
     * Ключ карты — id пресета. Пустая карта означает legacy-путь: один пресет синтезируется из
     * [provider] и секции провайдера. Валидация здесь, а не через `@field:Valid`: значения карты
     * jakarta-валидатор не обходит, а сообщение с id пресета читается лучше стектрейса.
     */
    val presets: Map<String, Preset> = emptyMap(),
) {
    init {
        presets.forEach { (id, preset) ->
            require(PRESET_ID.matches(id)) { "preset id '$id' must match ${PRESET_ID.pattern}" }
            preset.validate(id)
        }
        require(defaultPreset.isBlank() || presets.containsKey(defaultPreset)) {
            "default-preset '$defaultPreset' is not declared in presets: ${presets.keys.joinToString()}"
        }
    }

    data class Preset(
        val provider: String,
        val model: String,
        val effort: String = "",
    ) {
        internal fun validate(id: String) {
            require(provider in KNOWN_PROVIDERS) {
                "preset '$id': provider '$provider' is unknown (known: ${KNOWN_PROVIDERS.joinToString()})"
            }
            require(model.isNotBlank()) { "preset '$id': model must not be blank" }
            require(effort.isBlank() || effort in EFFORTS) {
                "preset '$id': effort '$effort' must be empty or one of ${EFFORTS.joinToString()}"
            }
            require(effort.isBlank() || provider == "grok") {
                "preset '$id': effort is supported only by provider grok, not '$provider'"
            }
        }
    }

    companion object {
        val KNOWN_PROVIDERS = listOf("claude", "grok")
        private val EFFORTS = listOf("low", "medium", "high", "xhigh", "max")
        private val PRESET_ID = Regex("[a-z0-9][a-z0-9-]{0,31}")
    }
```

Существующие `CommonSection` и `RateLimit` не трогать.

- [ ] **Step 4: Запустить тест и убедиться, что он проходит**

`./gradlew :frigate-analyzer-ai-description:test --tests ru.zinin.frigate.analyzer.ai.description.config.DescriptionPresetsValidationTest`
Ожидание: PASS, 8 тестов.

- [ ] **Step 5: Написать падающий тест биндинга**

Создать `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/properties/DescriptionPresetsBindingTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.config.properties

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties

/**
 * Биндит `application.ai.description` из production-yaml. Пресеты в базовом файле не объявлены —
 * их место в смонтированном `application-docker.yaml`, поэтому здесь проверяются дефолты и то,
 * что объявленная снаружи карта доезжает до типа целиком.
 */
class DescriptionPresetsBindingTest {
    @Test
    fun `presets are empty and default-preset is blank out of the box`() {
        val props = bind()

        assertThat(props.presets).isEmpty()
        assertThat(props.defaultPreset).isEmpty()
        assertThat(props.provider).isEqualTo("claude")
    }

    @Test
    fun `a declared map binds together with APP_AI_DESCRIPTION_DEFAULT_PRESET`() {
        val props =
            bind(
                env = mapOf("APP_AI_DESCRIPTION_DEFAULT_PRESET" to "grok-fast"),
                properties =
                    mapOf(
                        "application.ai.description.presets.grok-fast.provider" to "grok",
                        "application.ai.description.presets.grok-fast.model" to "grok-4.6",
                        "application.ai.description.presets.grok-fast.effort" to "low",
                        "application.ai.description.presets.claude-opus.provider" to "claude",
                        "application.ai.description.presets.claude-opus.model" to "opus",
                    ),
            )

        assertThat(props.defaultPreset).isEqualTo("grok-fast")
        assertThat(props.presets.keys).containsExactlyInAnyOrder("grok-fast", "claude-opus")
        assertThat(props.presets.getValue("grok-fast").model).isEqualTo("grok-4.6")
        assertThat(props.presets.getValue("grok-fast").effort).isEqualTo("low")
        assertThat(props.presets.getValue("claude-opus").effort).isEmpty()
    }

    @Test
    fun `a default-preset outside the map fails the binding`() {
        val thrown =
            catchThrowable {
                bind(
                    env = mapOf("APP_AI_DESCRIPTION_DEFAULT_PRESET" to "missing"),
                    properties =
                        mapOf(
                            "application.ai.description.presets.grok-fast.provider" to "grok",
                            "application.ai.description.presets.grok-fast.model" to "grok-4.6",
                        ),
                )
            }

        assertThat(thrown).hasStackTraceContaining("default-preset")
    }

    private fun bind(
        env: Map<String, Any> = emptyMap(),
        properties: Map<String, Any> = emptyMap(),
    ): DescriptionProperties =
        ProductionYamlBinder.bind("application.ai.description", DescriptionProperties::class.java, env, properties)
}
```

- [ ] **Step 6: Запустить тест и убедиться, что он падает**

`./gradlew :frigate-analyzer-core:test --tests ru.zinin.frigate.analyzer.core.config.properties.DescriptionPresetsBindingTest`
Ожидание: FAIL на втором тесте — `default-preset` в yaml не объявлен, env-переменная не подхватывается.

- [ ] **Step 7: Объявить `default-preset` в обоих yaml**

`modules/core/src/main/resources/application.yaml`, строка после `provider:`:

```yaml
      enabled: ${APP_AI_DESCRIPTION_ENABLED:false}
      # Legacy-путь: применяется, только когда карта presets пуста. Тогда из него и из секции
      # провайдера синтезируется единственный пресет.
      provider: ${APP_AI_DESCRIPTION_PROVIDER:claude}
      # Активный пресет по умолчанию; пусто = первый годный из presets. Сами presets объявляются
      # в смонтированном application-docker.yaml, пример — в application-docker.yaml.example.
      default-preset: ${APP_AI_DESCRIPTION_DEFAULT_PRESET:}
```

`modules/core/src/test/resources/application.yaml`, после `provider: claude`:

```yaml
      default-preset: ""
```

- [ ] **Step 8: Запустить оба теста и убедиться, что они проходят**

`./gradlew :frigate-analyzer-ai-description:test --tests ru.zinin.frigate.analyzer.ai.description.config.DescriptionPresetsValidationTest`
`./gradlew :frigate-analyzer-core:test --tests ru.zinin.frigate.analyzer.core.config.properties.DescriptionPresetsBindingTest`
Ожидание: PASS.

- [ ] **Step 9: Коммит**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/config/DescriptionProperties.kt \
        modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/config/DescriptionPresetsValidationTest.kt \
        modules/core/src/main/resources/application.yaml \
        modules/core/src/test/resources/application.yaml \
        modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/config/properties/DescriptionPresetsBindingTest.kt
git commit -m "feat(ai-description): declare description presets in configuration" \
           -m "Claude-Session: https://claude.ai/code/session_01MppBgCU5bLAPtDtnjoCYVk"
```

---

### Task 2: `model` и `effort` как параметры вызова

Чистый рефакторинг сигнатур, без изменения проводки. После него backend-у достаточно передать модель, чтобы она доехала до CLI.

**Files:**
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokCommandBuilder.kt:29-51`
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokBackend.kt` (метод `runGrok`)
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeAsyncClientFactory.kt`
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeInvoker.kt`
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/DefaultClaudeInvoker.kt`
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeBackend.kt` (вызов invoker-а)
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokCommandBuilderTest.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeAsyncClientFactoryTest.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeBackendTest.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokBackendTest.kt`

**Interfaces:**
- Consumes: ничего из Task 1.
- Produces: `GrokCommandBuilder.build(promptFile: Path, model: String, effort: String, structuredOutput: Boolean = true): GrokCommand`; `ClaudeAsyncClientFactory.create(workTimeout: Duration, model: String): ClaudeAsyncClient`; `ClaudeAsyncClientFactory.buildOptions(workTimeout: Duration, model: String): CLIOptions`; `ClaudeInvoker.invoke(prompt: String, model: String): String`.

- [ ] **Step 1: Переписать тесты под новые сигнатуры**

В `GrokCommandBuilderTest` заменить каждый вызов `builder.build(promptFile)` на `builder.build(promptFile, model = "grok-4.6", effort = "low")`, а тест про пустой effort — на явную передачу `effort = ""`. Добавить тест:

```kotlin
    @Test
    fun `model and effort come from the call, not from the properties`() {
        val builder = GrokCommandBuilder(properties(model = "grok-4.6", effort = "low")) { null }

        val command = builder.build(promptFile, model = "codex-luna", effort = "")

        assertEquals("codex-luna", command.argv[command.argv.indexOf("-m") + 1])
        assertFalse(command.argv.contains("--effort"))
    }
```

В `ClaudeAsyncClientFactoryTest` заменить `buildOptions(timeout)` на `buildOptions(timeout, model = "opus")` и добавить проверку, что переданная модель попадает в `CLIOptions`, а `anthropic.model-override` по-прежнему её вытесняет.

В `ClaudeBackendTest` и `GrokBackendTest` подправить фейки под новые сигнатуры (`ClaudeInvoker { _, _ -> … }`, `commandBuilder.build(any(), any(), any(), any())`).

- [ ] **Step 2: Запустить тесты и убедиться, что они падают**

`./gradlew :frigate-analyzer-ai-description:test`
Ожидание: компиляция тестов падает — старые сигнатуры.

- [ ] **Step 3: Изменить сигнатуры в продакшн-коде**

`GrokCommandBuilder`:

```kotlin
    fun build(
        promptFile: Path,
        model: String,
        effort: String,
        structuredOutput: Boolean = true,
    ): GrokCommand {
```

внутри argv заменить два места:

```kotlin
                add("-m")
                add(model)
                if (effort.isNotBlank()) {
                    add("--effort")
                    add(effort)
                }
```

`GrokBackend.runGrok`:

```kotlin
    private suspend fun runGrok(
        promptFile: Path,
        structuredOutput: Boolean,
    ): GrokProcessResult {
        val command = commandBuilder.build(promptFile, properties.model, properties.effort, structuredOutput)
        return guard.shared { runner.run(command) }
    }
```

`ClaudeAsyncClientFactory`:

```kotlin
    fun create(
        workTimeout: Duration,
        model: String,
    ): ClaudeAsyncClient {
        check(claudeProperties.oauthToken.isNotBlank() || claudeProperties.anthropic.authToken.isNotBlank()) {
            "At least one of CLAUDE_CODE_OAUTH_TOKEN or ANTHROPIC_AUTH_TOKEN must be set " +
                "when application.ai.description.enabled=true"
        }
        val options = buildOptions(workTimeout, model)
        …
    }

    internal fun buildOptions(
        workTimeout: Duration,
        model: String,
    ): CLIOptions {
        …
        if (claudeProperties.anthropic.modelOverride.isBlank()) {
            optionsBuilder.model(model)
        }
        return optionsBuilder.build()
    }
```

`ClaudeInvoker`:

```kotlin
fun interface ClaudeInvoker {
    suspend fun invoke(
        prompt: String,
        model: String,
    ): String
}
```

`DefaultClaudeInvoker.invoke` получает второй параметр `model: String` и передаёт его в `clientFactory.create(workTimeout, model)`.

`ClaudeBackend.describe` вызывает `invoker.invoke(prompt, claudeProperties.model)` (в Task 3 это станет моделью пресета).

- [ ] **Step 4: Запустить тесты модуля**

`./gradlew :frigate-analyzer-ai-description:test`
Ожидание: PASS.

- [ ] **Step 5: Коммит**

```bash
git add modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokCommandBuilder.kt \
        modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokBackend.kt \
        modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeAsyncClientFactory.kt \
        modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeInvoker.kt \
        modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/DefaultClaudeInvoker.kt \
        modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeBackend.kt \
        modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/
git commit -m "refactor(ai-description): pass model and effort per call instead of per bean" \
           -m "Claude-Session: https://claude.ai/code/session_01MppBgCU5bLAPtDtnjoCYVk"
```

---

### Task 3: Фабрики backend-ов, каталог пресетов и проводка

Самая крупная задача: провайдерные бины перестают зависеть от `provider=<id>`, backend создаётся на пресет, агент получает пресет из каталога. Разрезать её нельзя — промежуточное состояние не собирается.

**Files:**
- Create: `…/ai/description/api/DescriptionPreset.kt`, `…/api/DescriptionPresets.kt`
- Create: `…/ai/description/core/DescriptionBackendFactory.kt`, `…/core/DescriptionPresetCatalog.kt`, `…/core/DescriptionPresetCatalogBuilder.kt`
- Create: `…/ai/description/claude/ClaudeBackendFactory.kt`, `…/ai/description/grok/GrokBackendFactory.kt`
- Create: `…/ai/description/config/DescriptionPresetsDeclaredCondition.kt`
- Modify: `…/claude/ClaudeBackend.kt`, `…/grok/GrokBackend.kt`
- Modify (только аннотации, 14 файлов): `claude/ClaudeAsyncClientFactory.kt`, `claude/ClaudeExceptionMapper.kt`, `claude/ClaudeImageStager.kt`, `claude/ClaudePromptBuilder.kt`, `claude/ClaudeResponseParser.kt`, `claude/DefaultClaudeInvoker.kt`, `grok/DefaultGrokProcessRunner.kt`, `grok/GrokCommandBuilder.kt`, `grok/GrokExceptionMapper.kt`, `grok/GrokHomeGuard.kt`, `grok/GrokHomeSweeper.kt`, `grok/GrokOutputParser.kt`, `grok/GrokPromptBuilder.kt`, `grok/GrokPromptFileWriter.kt`
- Modify: `…/config/AiDescriptionAutoConfiguration.kt`, `…/config/DescriptionAgentSanityChecker.kt`
- Modify: `…/core/DefaultDescriptionAgent.kt` (конструктор принимает каталог)
- Test: `…/core/DescriptionPresetCatalogBuilderTest.kt` (create), `…/claude/ClaudeBackendFactoryTest.kt` (create, вместо `ClaudeBackendValidationTest.kt` — старый файл удалить), `…/grok/GrokBackendFactoryTest.kt` (create), `…/config/AiDescriptionAutoConfigurationTest.kt` (modify), `…/core/DefaultDescriptionAgentTest.kt` (modify)

**Interfaces:**
- Consumes: `DescriptionProperties.Preset`, `DescriptionProperties.presets`, `DescriptionProperties.defaultPreset` (Task 1); `GrokCommandBuilder.build(file, model, effort, schema)`, `ClaudeInvoker.invoke(prompt, model)` (Task 2).
- Produces: `DescriptionBackendFactory` (`providerId`, `availability()`, `create(preset)`); `DescriptionBackendFactory.Availability.Available` / `.Unavailable(reason)`; `DescriptionPreset(id, provider, model, effort, unavailableReason)`; `DescriptionPresets.all()`; `DescriptionPresetCatalog.Entry(view, backend)`, `.byId(id)`, `.fallback()`, `.fallbackId`; `DescriptionPresetCatalogBuilder.build(presets, defaultPreset, factories)`; `DefaultDescriptionAgent(catalog, descriptionProperties, eventPublisher, timeSource)`.

- [ ] **Step 1: Написать падающий тест сборки каталога**

Создать `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/core/DescriptionPresetCatalogBuilderTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.core

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DescriptionPresetCatalogBuilderTest {
    private class FakeFactory(
        override val providerId: String,
        private val availability: DescriptionBackendFactory.Availability =
            DescriptionBackendFactory.Availability.Available,
    ) : DescriptionBackendFactory {
        override fun availability() = availability

        override fun create(preset: DescriptionProperties.Preset): DescriptionBackend =
            object : DescriptionBackend {
                override val providerId = this@FakeFactory.providerId
                override val authRecoveryHint = "hint"

                override suspend fun describe(request: DescriptionRequest): DescriptionResult =
                    DescriptionResult(preset.model, preset.effort)
            }
    }

    private val grok = DescriptionProperties.Preset(provider = "grok", model = "grok-4.6", effort = "low")
    private val claude = DescriptionProperties.Preset(provider = "claude", model = "opus")

    @Test
    fun `declaration order is preserved and the default preset wins`() {
        val catalog =
            DescriptionPresetCatalogBuilder.build(
                presets = linkedMapOf("grok-fast" to grok, "claude-opus" to claude),
                defaultPreset = "claude-opus",
                factories = listOf(FakeFactory("grok"), FakeFactory("claude")),
            )

        assertNotNull(catalog)
        assertEquals(listOf("grok-fast", "claude-opus"), catalog.all().map { it.id })
        assertEquals("claude-opus", catalog.fallbackId)
        assertNull(catalog.all().first().unavailableReason)
    }

    @Test
    fun `a blank default falls back to the first usable preset`() {
        val catalog =
            DescriptionPresetCatalogBuilder.build(
                presets = linkedMapOf("claude-opus" to claude, "grok-fast" to grok),
                defaultPreset = "",
                factories =
                    listOf(
                        FakeFactory("claude", DescriptionBackendFactory.Availability.Unavailable("no token")),
                        FakeFactory("grok"),
                    ),
            )

        assertNotNull(catalog)
        assertEquals("grok-fast", catalog.fallbackId)
        assertEquals("no token", catalog.all().first().unavailableReason)
        assertNull(catalog.byId("claude-opus")!!.backend)
    }

    @Test
    fun `an unusable default falls back to a usable preset`() {
        val catalog =
            DescriptionPresetCatalogBuilder.build(
                presets = linkedMapOf("claude-opus" to claude, "grok-fast" to grok),
                defaultPreset = "claude-opus",
                factories =
                    listOf(
                        FakeFactory("claude", DescriptionBackendFactory.Availability.Unavailable("no token")),
                        FakeFactory("grok"),
                    ),
            )

        assertEquals("grok-fast", assertNotNull(catalog).fallbackId)
    }

    @Test
    fun `no presets means no catalog`() {
        val catalog =
            DescriptionPresetCatalogBuilder.build(
                presets = emptyMap(),
                defaultPreset = "",
                factories = listOf(FakeFactory("grok")),
            )

        assertNull(catalog)
    }

    @Test
    fun `presets that are all unusable fail the startup`() {
        val e =
            assertFailsWith<IllegalStateException> {
                DescriptionPresetCatalogBuilder.build(
                    presets = linkedMapOf("claude-opus" to claude),
                    defaultPreset = "",
                    factories =
                        listOf(FakeFactory("claude", DescriptionBackendFactory.Availability.Unavailable("no token"))),
                )
            }
        assertTrue(e.message!!.contains("claude-opus"), e.message)
        assertTrue(e.message!!.contains("no token"), e.message)
    }

    @Test
    fun `a preset without a factory is unusable, not a crash`() {
        val e =
            assertFailsWith<IllegalStateException> {
                DescriptionPresetCatalogBuilder.build(
                    presets = linkedMapOf("grok-fast" to grok),
                    defaultPreset = "",
                    factories = emptyList(),
                )
            }
        assertTrue(e.message!!.contains("no backend factory"), e.message)
    }
}
```

- [ ] **Step 2: Запустить тест и убедиться, что он падает**

`./gradlew :frigate-analyzer-ai-description:test --tests ru.zinin.frigate.analyzer.ai.description.core.DescriptionPresetCatalogBuilderTest`
Ожидание: компиляция падает — нет `DescriptionBackendFactory`, `DescriptionPresetCatalogBuilder`.

- [ ] **Step 3: Создать SPI, DTO и каталог**

`api/DescriptionPreset.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.api

/**
 * Пресет, каким его видят потребители: диалог Telegram и логи. [unavailableReason] не null, если
 * провайдер пресета не настроен — такой пресет остаётся в списке, но не выбирается.
 */
data class DescriptionPreset(
    val id: String,
    val provider: String,
    val model: String,
    val effort: String,
    val unavailableReason: String?,
) {
    val available: Boolean get() = unavailableReason == null
}
```

`api/DescriptionPresets.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.api

/** Каталог пресетов только на чтение; порядок — порядок объявления в конфиге. */
interface DescriptionPresets {
    fun all(): List<DescriptionPreset>
}
```

`core/DescriptionBackendFactory.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.core

import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties

/**
 * SPI провайдера: пригодность (есть ли учётные данные и всё, без чего вызов заведомо не пройдёт) и
 * создание backend-а под конкретный пресет. Проверки, которые раньше жили в `init` backend-ов,
 * принадлежат фабрике: backend-ов на один провайдер теперь столько, сколько пресетов.
 */
interface DescriptionBackendFactory {
    val providerId: String

    /** Вычисляется один раз на старте: и токен, и наличие CLI приходят из окружения процесса. */
    fun availability(): Availability

    fun create(preset: DescriptionProperties.Preset): DescriptionBackend

    sealed interface Availability {
        data object Available : Availability

        data class Unavailable(
            val reason: String,
        ) : Availability
    }
}
```

`core/DescriptionPresetCatalog.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.core

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionPreset
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionPresets

/** Неизменяемый список пресетов с готовыми backend-ами. Создаётся один раз на старте. */
class DescriptionPresetCatalog(
    private val entries: List<Entry>,
    val fallbackId: String,
) : DescriptionPresets {
    class Entry(
        val view: DescriptionPreset,
        /** null, если пресет недоступен: провайдер не настроен. */
        val backend: DescriptionBackend?,
    )

    private val index = entries.associateBy { it.view.id }

    init {
        require(entries.isNotEmpty()) { "preset catalog must not be empty" }
        require(index.containsKey(fallbackId)) { "fallback preset '$fallbackId' is not in the catalog" }
        require(index.getValue(fallbackId).backend != null) { "fallback preset '$fallbackId' is unavailable" }
    }

    override fun all(): List<DescriptionPreset> = entries.map { it.view }

    fun byId(id: String): Entry? = index[id]

    fun fallback(): Entry = index.getValue(fallbackId)
}
```

`core/DescriptionPresetCatalogBuilder.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.core

import io.github.oshai.kotlinlogging.KotlinLogging
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionPreset
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties

private val logger = KotlinLogging.logger {}

/**
 * Чистая сборка каталога: без Spring, чтобы правила «пустой список — нет каталога» и «ни одного
 * годного — отказ старта» проверялись обычным unit-тестом.
 */
object DescriptionPresetCatalogBuilder {
    /**
     * @return null, если пресетов не объявлено вовсе — тогда агента не будет, как сегодня при
     *   неизвестном `provider`.
     * @throws IllegalStateException если пресеты объявлены, но ни один не пригоден.
     */
    fun build(
        presets: Map<String, DescriptionProperties.Preset>,
        defaultPreset: String,
        factories: List<DescriptionBackendFactory>,
    ): DescriptionPresetCatalog? {
        if (presets.isEmpty()) return null
        val byProvider = factories.associateBy { it.providerId }
        val entries =
            presets.map { (id, preset) ->
                val factory = byProvider[preset.provider]
                val reason =
                    when {
                        factory == null -> "no backend factory for provider '${preset.provider}'"
                        else ->
                            (factory.availability() as? DescriptionBackendFactory.Availability.Unavailable)?.reason
                    }
                val view =
                    DescriptionPreset(
                        id = id,
                        provider = preset.provider,
                        model = preset.model,
                        effort = preset.effort,
                        unavailableReason = reason,
                    )
                if (reason != null) {
                    logger.warn { "Description preset '$id' is unavailable: $reason" }
                }
                DescriptionPresetCatalog.Entry(view, if (reason == null) factory!!.create(preset) else null)
            }
        val usable = entries.filter { it.backend != null }
        check(usable.isNotEmpty()) {
            "No usable description preset: " +
                entries.joinToString { "${it.view.id} (${it.view.unavailableReason})" }
        }
        val fallbackId =
            usable.firstOrNull { it.view.id == defaultPreset }?.view?.id
                ?: usable.first().view.id
        if (defaultPreset.isNotBlank() && fallbackId != defaultPreset) {
            logger.warn { "default-preset '$defaultPreset' is unavailable; falling back to '$fallbackId'" }
        }
        logger.info {
            "Description presets: ${entries.joinToString { it.view.id }}; default '$fallbackId'"
        }
        return DescriptionPresetCatalog(entries, fallbackId)
    }
}
```

- [ ] **Step 4: Запустить тест каталога**

`./gradlew :frigate-analyzer-ai-description:test --tests ru.zinin.frigate.analyzer.ai.description.core.DescriptionPresetCatalogBuilderTest`
Ожидание: PASS, 6 тестов.

- [ ] **Step 5: Написать падающие тесты фабрик**

Создать `…/claude/ClaudeBackendFactoryTest.kt`, перенеся в него проверки из `ClaudeBackendValidationTest.kt` (старый файл удалить в Step 7):

```kotlin
package ru.zinin.frigate.analyzer.ai.description.claude

import io.mockk.mockk
import ru.zinin.frigate.analyzer.ai.description.config.ClaudeProperties
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import ru.zinin.frigate.analyzer.ai.description.core.DescriptionBackendFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ClaudeBackendFactoryTest {
    private fun factory(
        oauthToken: String = "token",
        anthropicToken: String = "",
    ) = ClaudeBackendFactory(
        claudeProperties = properties(oauthToken, anthropicToken),
        promptBuilder = mockk(relaxed = true),
        responseParser = mockk(relaxed = true),
        imageStager = mockk(relaxed = true),
        invoker = { _, _ -> "{}" },
        exceptionMapper = mockk(relaxed = true),
    )

    private fun properties(
        oauthToken: String,
        anthropicToken: String,
    ) = ClaudeProperties(
        oauthToken = oauthToken,
        model = "opus",
        cliPath = "",
        workingDirectory = "/tmp",
        proxy = ClaudeProperties.ProxySection("", "", ""),
        anthropic = ClaudeProperties.AnthropicSection(authToken = anthropicToken),
    )

    @Test
    fun `without any token the provider is unavailable instead of failing the startup`() {
        val availability = factory(oauthToken = "").availability()

        val unavailable = assertIs<DescriptionBackendFactory.Availability.Unavailable>(availability)
        assertEquals(true, unavailable.reason.contains("CLAUDE_CODE_OAUTH_TOKEN"))
    }

    @Test
    fun `an anthropic token alone makes the provider available`() {
        assertIs<DescriptionBackendFactory.Availability.Available>(
            factory(oauthToken = "", anthropicToken = "sk-ant").availability(),
        )
    }

    @Test
    fun `the created backend carries the preset model`() {
        val backend = factory().create(DescriptionProperties.Preset(provider = "claude", model = "sonnet"))

        assertEquals("claude", backend.providerId)
        assertEquals("sonnet", (backend as ClaudeBackend).model)
    }
}
```

Создать `…/grok/GrokBackendFactoryTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.grok

import io.mockk.mockk
import org.junit.jupiter.api.io.TempDir
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import ru.zinin.frigate.analyzer.ai.description.config.GrokProperties
import ru.zinin.frigate.analyzer.ai.description.core.DescriptionBackendFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GrokBackendFactoryTest {
    @TempDir
    lateinit var tempDir: Path

    private fun factory(): GrokBackendFactory =
        GrokBackendFactory(
            properties =
                GrokProperties(
                    cliPath = "",
                    model = "grok-4.6",
                    effort = "low",
                    home = tempDir.resolve("home").toString(),
                    workingDirectory = tempDir.resolve("cwd").toString(),
                    proxy = GrokProperties.ProxySection("", "", ""),
                ),
            promptFileWriter = mockk(relaxed = true),
            commandBuilder = mockk(relaxed = true),
            runner = mockk(relaxed = true),
            outputParser = mockk(relaxed = true),
            exceptionMapper = mockk(relaxed = true),
            guard = mockk(relaxed = true),
        )

    @Test
    fun `the factory creates the grok directories once`() {
        factory()

        assertTrue(Files.isDirectory(tempDir.resolve("home")))
        assertTrue(Files.isDirectory(tempDir.resolve("cwd")))
    }

    @Test
    fun `grok is available even without auth json - BYOK models carry their own key`() {
        assertIs<DescriptionBackendFactory.Availability.Available>(factory().availability())
    }

    @Test
    fun `the created backend carries the preset model and effort`() {
        val backend =
            factory().create(
                DescriptionProperties.Preset(provider = "grok", model = "codex-luna", effort = ""),
            ) as GrokBackend

        assertEquals("grok", backend.providerId)
        assertEquals("codex-luna", backend.model)
        assertEquals("", backend.effort)
    }
}
```

- [ ] **Step 6: Запустить тесты фабрик и убедиться, что они падают**

`./gradlew :frigate-analyzer-ai-description:test --tests "ru.zinin.frigate.analyzer.ai.description.*BackendFactoryTest"`
Ожидание: компиляция падает — фабрик нет.

- [ ] **Step 7: Создать фабрики и переписать backend-ы**

`claude/ClaudeBackendFactory.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.claude

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import org.springaicommunity.claude.agent.sdk.Query
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.config.ClaudeProperties
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import ru.zinin.frigate.analyzer.ai.description.core.DescriptionBackend
import ru.zinin.frigate.analyzer.ai.description.core.DescriptionBackendFactory
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class ClaudeBackendFactory(
    private val claudeProperties: ClaudeProperties,
    private val promptBuilder: ClaudePromptBuilder,
    private val responseParser: ClaudeResponseParser,
    private val imageStager: ClaudeImageStager,
    private val invoker: ClaudeInvoker,
    private val exceptionMapper: ClaudeExceptionMapper,
) : DescriptionBackendFactory {
    override val providerId: String = "claude"

    /**
     * Отсутствие токена больше не роняет приложение: с пресетами claude-пресет может стоять в
     * конфиге стенда, где живёт только Grok. Такой пресет помечается недоступным, а старт падает
     * только если недоступны все.
     */
    override fun availability(): DescriptionBackendFactory.Availability =
        if (claudeProperties.oauthToken.isBlank() && claudeProperties.anthropic.authToken.isBlank()) {
            DescriptionBackendFactory.Availability.Unavailable(
                "neither CLAUDE_CODE_OAUTH_TOKEN nor ANTHROPIC_AUTH_TOKEN is set",
            )
        } else {
            DescriptionBackendFactory.Availability.Available
        }

    override fun create(preset: DescriptionProperties.Preset): DescriptionBackend =
        ClaudeBackend(
            model = preset.model,
            promptBuilder = promptBuilder,
            responseParser = responseParser,
            imageStager = imageStager,
            invoker = invoker,
            exceptionMapper = exceptionMapper,
        )

    @PostConstruct
    fun warnIfCliMissing() {
        if (claudeProperties.cliPath.isBlank()) {
            if (!Query.isCliInstalled()) {
                logger.warn {
                    "Claude CLI not found in PATH (Query.isCliInstalled()==false); claude presets " +
                        "will return fallback. Check Dockerfile ENV PATH=... and claude install."
                }
            }
        } else if (!Files.isExecutable(Path.of(claudeProperties.cliPath))) {
            logger.warn {
                "Explicit claude.cli-path='${claudeProperties.cliPath}' not found or not executable; " +
                    "claude presets will return fallback."
            }
        }
    }
}
```

`claude/ClaudeBackend.kt`: убрать `@Component`, обе `@ConditionalOnProperty`, блок `init` и параметр `claudeProperties`; добавить первым параметром `val model: String` (публичное поле — его читает тест фабрики) и передавать его в invoker:

```kotlin
class ClaudeBackend(
    val model: String,
    private val promptBuilder: ClaudePromptBuilder,
    private val responseParser: ClaudeResponseParser,
    private val imageStager: ClaudeImageStager,
    private val invoker: ClaudeInvoker,
    private val exceptionMapper: ClaudeExceptionMapper,
) : DescriptionBackend {
    override val providerId: String = "claude"
    override val authRecoveryHint: String =
        "set CLAUDE_CODE_OAUTH_TOKEN from `claude setup-token` (or ANTHROPIC_AUTH_TOKEN) and restart"

    override suspend fun describe(request: DescriptionRequest): DescriptionResult {
        val stagedPaths = imageStager.stage(request)
        try {
            val prompt = promptBuilder.build(request, stagedPaths)
            val raw =
                try {
                    invoker.invoke(prompt, model)
                } catch (e: Throwable) {
                    throw exceptionMapper.map(e)
                }
            return responseParser.parse(raw, request.shortMaxLength, request.detailedMaxLength)
        } finally {
            imageStager.cleanup(stagedPaths)
        }
    }
}
```

`grok/GrokBackendFactory.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.grok

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import ru.zinin.frigate.analyzer.ai.description.config.GrokProperties
import ru.zinin.frigate.analyzer.ai.description.core.DescriptionBackend
import ru.zinin.frigate.analyzer.ai.description.core.DescriptionBackendFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Проверки окружения Grok живут здесь, а не в backend-е: backend-ов на провайдера теперь столько,
 * сколько grok-пресетов, и повторять создание каталогов и предупреждения на каждый из них незачем.
 */
@Component
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class GrokBackendFactory(
    private val properties: GrokProperties,
    private val promptFileWriter: GrokPromptFileWriter,
    private val commandBuilder: GrokCommandBuilder,
    private val runner: GrokProcessRunner,
    private val outputParser: GrokOutputParser,
    private val exceptionMapper: GrokExceptionMapper,
    private val guard: GrokHomeGuard,
) : DescriptionBackendFactory {
    override val providerId: String = "grok"

    init {
        val home = properties.homePath
        val cwd = properties.workingDirectoryPath
        try {
            Files.createDirectories(home)
            Files.createDirectories(cwd)
        } catch (e: IOException) {
            throw IllegalStateException(
                "Cannot create Grok directories home=$home working-directory=$cwd: ${e.message}",
                e,
            )
        }
        if (!Files.isWritable(home)) {
            logger.warn {
                "Grok home $home is not writable; grok login and token refresh will fail " +
                    "(fix: chown the volume to uid 1000)"
            }
        }
        if (!cliAvailable()) {
            logger.warn {
                "grok CLI not found (cli-path='${properties.cliPath}', PATH lookup otherwise); " +
                    "grok presets will return fallback"
            }
        }
        if (!Files.isRegularFile(home.resolve("auth.json"))) {
            logger.warn {
                "No auth.json in $home; run `${GrokBackend.AUTH_RECOVERY_HINT}`. Not needed only for " +
                    "BYOK models with their own api_key in config.toml"
            }
        }
        logger.info { "Grok description provider: home=$home, cwd=$cwd" }
    }

    /**
     * Отсутствие `auth.json` не делает провайдер непригодным: BYOK-модель ходит по собственному
     * ключу из `config.toml`, а протухшую сессию ловит `Unauthorized` и сообщение владельцу.
     */
    override fun availability(): DescriptionBackendFactory.Availability =
        DescriptionBackendFactory.Availability.Available

    override fun create(preset: DescriptionProperties.Preset): DescriptionBackend =
        GrokBackend(
            model = preset.model,
            effort = preset.effort,
            promptFileWriter = promptFileWriter,
            commandBuilder = commandBuilder,
            runner = runner,
            outputParser = outputParser,
            exceptionMapper = exceptionMapper,
            guard = guard,
        )

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

Текст подсказки выносится в `GrokBackend.companion object` как `const val AUTH_RECOVERY_HINT`, а поле `authRecoveryHint` ссылается на неё.

`grok/GrokBackend.kt`: убрать `@Component`, обе `@ConditionalOnProperty`, блок `init`, метод
`cliAvailable()` и параметр `properties`; заголовок и два метода становятся такими (тело `describe`
не меняется, кроме `properties.model` → `model` в двух строках логов):

```kotlin
class GrokBackend(
    val model: String,
    val effort: String,
    private val promptFileWriter: GrokPromptFileWriter,
    private val commandBuilder: GrokCommandBuilder,
    private val runner: GrokProcessRunner,
    private val outputParser: GrokOutputParser,
    private val exceptionMapper: GrokExceptionMapper,
    private val guard: GrokHomeGuard,
) : DescriptionBackend {
    override val providerId: String = "grok"
    override val authRecoveryHint: String = AUTH_RECOVERY_HINT

    /**
     * Схема поддержана, пока эндпоинт не доказал обратное. Поле экземпляра, то есть флаг живёт на
     * пресет — ровно правильная область: модель зафиксирована пресетом, и второй пресет с другой
     * моделью не должен наследовать чужой отказ.
     */
    @Volatile
    private var schemaSupported: Boolean = true

    …

    private suspend fun runGrok(
        promptFile: Path,
        structuredOutput: Boolean,
    ): GrokProcessResult {
        val command = commandBuilder.build(promptFile, model, effort, structuredOutput)
        return guard.shared { runner.run(command) }
    }

    private fun effortForLog(): String = effort.ifBlank { "<none>" }

    companion object {
        const val AUTH_RECOVERY_HINT =
            "grok login --device-code (in Docker: docker compose exec frigate-analyzer grok login --device-code)"
    }
}
```

- [ ] **Step 8: Снять условие `provider=<id>` с 14 коллаборантов**

В каждом из файлов `claude/ClaudeAsyncClientFactory.kt`, `claude/ClaudeExceptionMapper.kt`, `claude/ClaudeImageStager.kt`, `claude/ClaudePromptBuilder.kt`, `claude/ClaudeResponseParser.kt`, `claude/DefaultClaudeInvoker.kt`, `grok/DefaultGrokProcessRunner.kt`, `grok/GrokCommandBuilder.kt`, `grok/GrokExceptionMapper.kt`, `grok/GrokHomeGuard.kt`, `grok/GrokHomeSweeper.kt`, `grok/GrokOutputParser.kt`, `grok/GrokPromptBuilder.kt`, `grok/GrokPromptFileWriter.kt` удалить строку

```kotlin
@ConditionalOnProperty("application.ai.description.provider", havingValue = "<claude|grok>")
```

оставив только `@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")`. Неиспользованный импорт не удалять — вторая аннотация его использует. Проверка: `grep -rl 'application.ai.description.provider' modules/ai-description/src/main` должен вернуть пусто.

- [ ] **Step 9: Переписать автоконфигурацию, условие и sanity checker**

`config/DescriptionPresetsDeclaredCondition.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.config

import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.type.AnnotatedTypeMetadata

/**
 * Каталог пресетов существует, только когда есть что в него класть: либо объявлена карта
 * `presets`, либо legacy-`provider` называет известного провайдера. Иначе бина нет, агента нет и
 * `DescriptionAgentSanityChecker` пишет WARN — то же поведение, что сегодня даёт опечатка в
 * `APP_AI_DESCRIPTION_PROVIDER`. Свойства читаются напрямую: биндить карту в условии нельзя,
 * условия вычисляются до создания `@ConfigurationProperties`.
 */
class DescriptionPresetsDeclaredCondition : Condition {
    override fun matches(
        context: ConditionContext,
        metadata: AnnotatedTypeMetadata,
    ): Boolean {
        val environment = context.environment
        val hasPresets =
            (environment as? ConfigurableEnvironment)
                ?.propertySources
                ?.filterIsInstance<EnumerablePropertySource<*>>()
                ?.any { source -> source.propertyNames.any { it.startsWith(PRESETS_PREFIX) } } == true
        if (hasPresets) return true
        return environment.getProperty("application.ai.description.provider", "") in
            DescriptionProperties.KNOWN_PROVIDERS
    }

    private companion object {
        const val PRESETS_PREFIX = "application.ai.description.presets."
    }
}
```

`config/AiDescriptionAutoConfiguration.kt` целиком:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.config

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Conditional
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionAgent
import ru.zinin.frigate.analyzer.ai.description.core.DefaultDescriptionAgent
import ru.zinin.frigate.analyzer.ai.description.core.DescriptionBackendFactory
import ru.zinin.frigate.analyzer.ai.description.core.DescriptionPresetCatalog
import ru.zinin.frigate.analyzer.ai.description.core.DescriptionPresetCatalogBuilder

@AutoConfiguration
@ComponentScan("ru.zinin.frigate.analyzer.ai.description")
@EnableConfigurationProperties(DescriptionProperties::class, ClaudeProperties::class, GrokProperties::class)
open class AiDescriptionAutoConfiguration {
    /**
     * Порядок методов значим: `@ConditionalOnBean` ниже проверяется в порядке объявления
     * `@Bean`-методов внутри `@AutoConfiguration`, поэтому каталог обязан объявляться выше агента.
     */
    @Bean
    @ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
    @Conditional(DescriptionPresetsDeclaredCondition::class)
    fun descriptionPresetCatalog(
        descriptionProperties: DescriptionProperties,
        claudeProperties: ClaudeProperties,
        grokProperties: GrokProperties,
        factories: List<DescriptionBackendFactory>,
    ): DescriptionPresetCatalog =
        checkNotNull(
            DescriptionPresetCatalogBuilder.build(
                presets = declaredPresets(descriptionProperties, claudeProperties, grokProperties),
                defaultPreset = descriptionProperties.defaultPreset,
                factories = factories,
            ),
        ) { "No description preset declared while the catalog condition matched" }

    @Bean
    @ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
    @ConditionalOnBean(DescriptionPresetCatalog::class)
    fun descriptionAgent(
        catalog: DescriptionPresetCatalog,
        descriptionProperties: DescriptionProperties,
        eventPublisher: ApplicationEventPublisher,
    ): DescriptionAgent = DefaultDescriptionAgent(catalog, descriptionProperties, eventPublisher)

    /**
     * Пустая карта означает деплой, настроенный старым способом: один пресет из `provider` и
     * секции этого провайдера. Неизвестный `provider` даёт пустую карту — тогда сюда не доходит
     * даже условие каталога.
     */
    private fun declaredPresets(
        descriptionProperties: DescriptionProperties,
        claudeProperties: ClaudeProperties,
        grokProperties: GrokProperties,
    ): Map<String, DescriptionProperties.Preset> =
        descriptionProperties.presets.ifEmpty {
            when (descriptionProperties.provider) {
                "claude" ->
                    mapOf(
                        "claude" to
                            DescriptionProperties.Preset(provider = "claude", model = claudeProperties.model),
                    )

                "grok" ->
                    mapOf(
                        "grok" to
                            DescriptionProperties.Preset(
                                provider = "grok",
                                model = grokProperties.model,
                                effort = grokProperties.effort,
                            ),
                    )

                else -> emptyMap()
            }
        }
}
```

`config/DescriptionAgentSanityChecker.kt`: в тексте WARN заменить перечисление провайдеров на упоминание пресетов —

```kotlin
                "application.ai.description.enabled=true but no DescriptionAgent registered: " +
                    "neither application.ai.description.presets nor a known " +
                    "application.ai.description.provider='${descriptionProperties.provider}' " +
                    "(known: ${DescriptionProperties.KNOWN_PROVIDERS.joinToString()}); all describe-calls will fall back."
```

и удалить локальную константу `KNOWN_PROVIDERS`.

- [ ] **Step 10: Перевести агент на каталог**

В `core/DefaultDescriptionAgent.kt` заменить конструктор и обращения к backend-у:

```kotlin
class DefaultDescriptionAgent(
    private val catalog: DescriptionPresetCatalog,
    descriptionProperties: DescriptionProperties,
    private val eventPublisher: ApplicationEventPublisher,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : DescriptionAgent {
```

Внутри `describe` после downscale взять пресет и пробросить его в цикл повторов:

```kotlin
            val entry = catalog.fallback()
            val backend = requireNotNull(entry.backend) { "fallback preset has no backend" }
            return try {
                withTimeout(commonSection.timeout.toMillis()) {
                    executeWithRetry(backend, prepared)
                }
            } catch (e: TimeoutCancellationException) {
                throw DescriptionException.Timeout(cause = e)
            }
```

`executeWithRetry`, `attempt`, `onUnauthorized`, `onSuccess` получают `backend: DescriptionBackend` параметром вместо поля; в DEBUG-строке `finally` вместо `backend.providerId` печатать `catalog.fallbackId`. (В Task 4 `catalog.fallback()` станет `resolver.resolve()`.)

В `DefaultDescriptionAgentTest` добавить хелпер и заменить им прямое создание агента:

```kotlin
    private fun agentOf(
        backend: DescriptionBackend,
        timeSource: TimeSource = TimeSource.Monotonic,
        publisher: ApplicationEventPublisher = ApplicationEventPublisher {},
    ): DefaultDescriptionAgent =
        DefaultDescriptionAgent(catalogOf(backend), DescriptionProperties(…), publisher, timeSource)

    private fun catalogOf(backend: DescriptionBackend): DescriptionPresetCatalog =
        DescriptionPresetCatalog(
            listOf(
                DescriptionPresetCatalog.Entry(
                    DescriptionPreset("test", backend.providerId, "test-model", "", null),
                    backend,
                ),
            ),
            fallbackId = "test",
        )
```

- [ ] **Step 11: Обновить тест автоконфигурации**

Существующие сценарии `provider=claude` и `provider=grok` дополнить проверкой каталога:
`assertEquals(listOf("claude"), context.getBean(DescriptionPresetCatalog::class.java).all().map { it.id })`.
`TestStubConfig` не трогать. Добавить четыре теста (в `withPropertyValues` побеждает последнее
значение, поэтому пустой токен ставится **после** массива `properties(...)`):

```kotlin
    @Test
    fun `two presets give two usable entries and the default preset wins`() {
        runner
            .withPropertyValues(
                *properties(enabled = true, provider = "grok"),
                "application.ai.description.default-preset=grok-deep",
                "application.ai.description.presets.grok-fast.provider=grok",
                "application.ai.description.presets.grok-fast.model=grok-4.6",
                "application.ai.description.presets.grok-fast.effort=low",
                "application.ai.description.presets.grok-deep.provider=grok",
                "application.ai.description.presets.grok-deep.model=grok-4.6",
                "application.ai.description.presets.grok-deep.effort=xhigh",
            ).run { context ->
                val catalog = context.getBean(DescriptionPresetCatalog::class.java)
                assertEquals(listOf("grok-fast", "grok-deep"), catalog.all().map { it.id })
                assertEquals("grok-deep", catalog.fallbackId)
                assertEquals(2, catalog.all().count { it.available })
                assertNotNull(context.getBean(DescriptionAgent::class.java))
            }
    }

    @Test
    fun `a claude preset without a token stays listed while grok keeps working`() {
        runner
            .withPropertyValues(
                *properties(enabled = true, provider = "grok"),
                "application.ai.description.claude.oauth-token=",
                "application.ai.description.presets.claude-opus.provider=claude",
                "application.ai.description.presets.claude-opus.model=opus",
                "application.ai.description.presets.grok-fast.provider=grok",
                "application.ai.description.presets.grok-fast.model=grok-4.6",
            ).run { context ->
                val catalog = context.getBean(DescriptionPresetCatalog::class.java)
                assertNotNull(catalog.all().first { it.id == "claude-opus" }.unavailableReason)
                assertEquals("grok-fast", catalog.fallbackId)
                assertNotNull(context.getBean(DescriptionAgent::class.java))
            }
    }

    @Test
    fun `a single unusable preset fails the startup`() {
        runner
            .withPropertyValues(
                *properties(enabled = true, provider = "claude"),
                "application.ai.description.claude.oauth-token=",
                "application.ai.description.presets.claude-opus.provider=claude",
                "application.ai.description.presets.claude-opus.model=opus",
            ).run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasStackTraceContaining("No usable description preset")
            }
    }

    @Test
    fun `an unknown legacy provider without presets leaves the agent out`() {
        runner
            .withPropertyValues(*properties(enabled = true, provider = "gemini"))
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(DescriptionAgent::class.java)
                assertThat(context).doesNotHaveBean(DescriptionPresetCatalog::class.java)
            }
    }
```

- [ ] **Step 12: Запустить тесты модуля**

`./gradlew :frigate-analyzer-ai-description:test`
Ожидание: PASS. При ошибках ktlint — `./gradlew ktlintFormat`, повтор.

- [ ] **Step 13: Коммит**

```bash
git add modules/ai-description/src
git rm modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeBackendValidationTest.kt
git commit -m "feat(ai-description): build one backend per preset through provider factories" \
           -m "Claude-Session: https://claude.ai/code/session_01MppBgCU5bLAPtDtnjoCYVk"
```

---

### Task 4: Рантайм-настройки и резолюция активного пресета

**Files:**
- Create: `…/ai/description/api/DescriptionRuntimeSettings.kt`
- Create: `…/ai/description/core/InMemoryDescriptionRuntimeSettings.kt`
- Create: `…/ai/description/core/ActivePresetResolver.kt`
- Modify: `…/ai/description/core/DefaultDescriptionAgent.kt`
- Modify: `…/ai/description/config/AiDescriptionAutoConfiguration.kt`
- Test: `…/ai/description/core/ActivePresetResolverTest.kt` (create), `…/core/DefaultDescriptionAgentTest.kt` (modify)

**Interfaces:**
- Consumes: `DescriptionPresetCatalog`, `DescriptionPresetCatalog.Entry` (Task 3).
- Produces: `DescriptionRuntimeSettings` (`activePresetId`, `setActivePresetId`, `descriptionsEnabled`, `setDescriptionsEnabled`); `ActivePresetResolver.resolve(): DescriptionPresetCatalog.Entry`, `ActivePresetResolver.activePresetId(): String`; `DefaultDescriptionAgent(resolver, descriptionProperties, eventPublisher, timeSource)`.

- [ ] **Step 1: Написать падающий тест резолвера**

Создать `…/core/ActivePresetResolverTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.core

import kotlinx.coroutines.test.runTest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionPreset
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRequest
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult
import kotlin.test.Test
import kotlin.test.assertEquals

class ActivePresetResolverTest {
    private fun backend(id: String) =
        object : DescriptionBackend {
            override val providerId = "grok"
            override val authRecoveryHint = "hint"

            override suspend fun describe(request: DescriptionRequest) = DescriptionResult(id, id)
        }

    private fun entry(
        id: String,
        available: Boolean = true,
    ) = DescriptionPresetCatalog.Entry(
        DescriptionPreset(id, "grok", "m", "", if (available) null else "no token"),
        if (available) backend(id) else null,
    )

    private val catalog = DescriptionPresetCatalog(listOf(entry("fast"), entry("deep"), entry("broken", false)), "fast")

    @Test
    fun `an absent setting resolves to the fallback`() =
        runTest {
            val resolver = ActivePresetResolver(catalog, InMemoryDescriptionRuntimeSettings())

            assertEquals("fast", resolver.resolve().view.id)
        }

    @Test
    fun `a stored id wins`() =
        runTest {
            val settings = InMemoryDescriptionRuntimeSettings()
            settings.setActivePresetId("deep", changedBy = "owner")

            assertEquals("deep", ActivePresetResolver(catalog, settings).resolve().view.id)
        }

    @Test
    fun `an unknown stored id falls back`() =
        runTest {
            val settings = InMemoryDescriptionRuntimeSettings()
            settings.setActivePresetId("gone", changedBy = null)

            assertEquals("fast", ActivePresetResolver(catalog, settings).resolve().view.id)
        }

    @Test
    fun `an unavailable stored id falls back`() =
        runTest {
            val settings = InMemoryDescriptionRuntimeSettings()
            settings.setActivePresetId("broken", changedBy = null)

            assertEquals("fast", ActivePresetResolver(catalog, settings).resolve().view.id)
        }

    @Test
    fun `resolving twice keeps returning the same entry`() =
        runTest {
            val settings = InMemoryDescriptionRuntimeSettings()
            settings.setActivePresetId("gone", changedBy = null)
            val resolver = ActivePresetResolver(catalog, settings)

            assertEquals(resolver.resolve().view.id, resolver.resolve().view.id)
            assertEquals("fast", resolver.activePresetId())
        }
}
```

- [ ] **Step 2: Запустить тест и убедиться, что он падает**

`./gradlew :frigate-analyzer-ai-description:test --tests ru.zinin.frigate.analyzer.ai.description.core.ActivePresetResolverTest`
Ожидание: компиляция падает.

- [ ] **Step 3: Создать SPI, дефолтную реализацию и резолвер**

`api/DescriptionRuntimeSettings.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.api

/**
 * Рантайм-настройки описаний: какой пресет активен и включены ли описания вообще. Модуль
 * `ai-description` не знает про БД, поэтому это шов — как [TempFileWriter]. Реализация поверх
 * `app_settings` живёт в модуле `core`; при её отсутствии автоконфигурация даёт in-memory дефолт.
 */
interface DescriptionRuntimeSettings {
    /** null = владелец ничего не выбирал: берётся пресет по умолчанию. */
    suspend fun activePresetId(): String?

    suspend fun setActivePresetId(
        id: String,
        changedBy: String?,
    )

    /** Отсутствие настройки означает «включено»: статический выключатель фичи главнее. */
    suspend fun descriptionsEnabled(): Boolean

    suspend fun setDescriptionsEnabled(
        value: Boolean,
        changedBy: String?,
    )
}
```

`core/InMemoryDescriptionRuntimeSettings.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.core

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRuntimeSettings
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Дефолт на случай отсутствия реализации из `core`: выбор живёт до перезапуска процесса. */
class InMemoryDescriptionRuntimeSettings : DescriptionRuntimeSettings {
    private val presetId = AtomicReference<String?>(null)
    private val enabled = AtomicBoolean(true)

    override suspend fun activePresetId(): String? = presetId.get()

    override suspend fun setActivePresetId(
        id: String,
        changedBy: String?,
    ) {
        presetId.set(id)
    }

    override suspend fun descriptionsEnabled(): Boolean = enabled.get()

    override suspend fun setDescriptionsEnabled(
        value: Boolean,
        changedBy: String?,
    ) {
        enabled.set(value)
    }
}
```

`core/ActivePresetResolver.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.core

import io.github.oshai.kotlinlogging.KotlinLogging
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRuntimeSettings
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/**
 * Активный пресет на каждый вызов: чтение дешёвое, потому что реализация настроек кэширует
 * значение на процесс и сбрасывает кэш только на собственной записи.
 */
class ActivePresetResolver(
    private val catalog: DescriptionPresetCatalog,
    private val runtimeSettings: DescriptionRuntimeSettings,
) {
    /** Последнее залогированное предупреждение: иначе каждая запись повторяла бы одну строку. */
    private val lastWarning = AtomicReference<String?>(null)

    suspend fun resolve(): DescriptionPresetCatalog.Entry {
        val storedId = runtimeSettings.activePresetId()
        if (storedId != null) {
            val stored = catalog.byId(storedId)
            if (stored?.backend != null) return stored
            warnOnce(
                if (stored == null) {
                    "Active description preset '$storedId' is not configured; using '${catalog.fallbackId}'"
                } else {
                    "Active description preset '$storedId' is unavailable " +
                        "(${stored.view.unavailableReason}); using '${catalog.fallbackId}'"
                },
            )
        }
        return catalog.fallback()
    }

    suspend fun activePresetId(): String = resolve().view.id

    private fun warnOnce(message: String) {
        if (lastWarning.getAndSet(message) != message) {
            logger.warn { message }
        }
    }
}
```

- [ ] **Step 4: Запустить тест резолвера**

`./gradlew :frigate-analyzer-ai-description:test --tests ru.zinin.frigate.analyzer.ai.description.core.ActivePresetResolverTest`
Ожидание: PASS, 5 тестов.

- [ ] **Step 5: Перевести агента на резолвер**

`DefaultDescriptionAgent`: заменить параметр `catalog: DescriptionPresetCatalog` на `resolver: ActivePresetResolver`, в `describe` заменить

```kotlin
            val entry = catalog.fallback()
```

на

```kotlin
            // Резолюция один раз на вызов: повторы обязаны идти в тот же пресет, что и первая
            // попытка, иначе лог одной записи назвал бы два разных провайдера.
            val entry = resolver.resolve()
```

и печатать в DEBUG `entry.view.id` вместо `catalog.fallbackId` — для этого перенести `logger.debug` из `finally` в тело (или сохранить id в локальную переменную до `try`).

В автоконфигурации добавить бины между каталогом и агентом:

```kotlin
    @Bean
    @ConditionalOnMissingBean(DescriptionRuntimeSettings::class)
    @ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
    fun inMemoryDescriptionRuntimeSettings(): DescriptionRuntimeSettings = InMemoryDescriptionRuntimeSettings()

    @Bean
    @ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
    @ConditionalOnBean(DescriptionPresetCatalog::class)
    fun activePresetResolver(
        catalog: DescriptionPresetCatalog,
        runtimeSettings: DescriptionRuntimeSettings,
    ): ActivePresetResolver = ActivePresetResolver(catalog, runtimeSettings)
```

(объявить их **после** метода каталога и **до** метода агента), а `descriptionAgent` принимает `resolver: ActivePresetResolver`.

В `DefaultDescriptionAgentTest` хелпер `agentOf` заворачивает каталог в `ActivePresetResolver(catalogOf(backend), InMemoryDescriptionRuntimeSettings())`.

- [ ] **Step 6: Запустить тесты модуля**

`./gradlew :frigate-analyzer-ai-description:test`
Ожидание: PASS.

- [ ] **Step 7: Коммит**

```bash
git add modules/ai-description/src
git commit -m "feat(ai-description): resolve the active preset per call through a runtime SPI" \
           -m "Claude-Session: https://claude.ai/code/session_01MppBgCU5bLAPtDtnjoCYVk"
```

---

### Task 5: Состояние авторизации по провайдеру

**Files:**
- Create: `…/ai/description/api/ProviderAuthStates.kt`
- Create: `…/ai/description/core/ProviderAuthTracker.kt`
- Modify: `…/ai/description/core/DefaultDescriptionAgent.kt`
- Modify: `…/ai/description/config/AiDescriptionAutoConfiguration.kt`
- Test: `…/ai/description/core/ProviderAuthTrackerTest.kt` (create), `…/core/DefaultDescriptionAgentTest.kt` (modify)

**Interfaces:**
- Consumes: `DescriptionException.Unauthorized`, `DescriptionProviderAuthEvent` (существующие).
- Produces: `ProviderAuthStates.Health` (`UNKNOWN`, `HEALTHY`, `LOST`), `ProviderAuthStates.byProvider(): Map<String, Health>`; `ProviderAuthTracker.onSuccess(providerId, recoveryHint)`, `ProviderAuthTracker.onUnauthorized(providerId, e, recoveryHint)`; `DefaultDescriptionAgent(resolver, authTracker, descriptionProperties, timeSource)`.

- [ ] **Step 1: Написать падающий тест трекера**

Создать `…/core/ProviderAuthTrackerTest.kt` со сценариями:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.core

import org.springframework.context.ApplicationEventPublisher
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionProviderAuthEvent
import ru.zinin.frigate.analyzer.ai.description.api.ProviderAuthStates
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals

class ProviderAuthTrackerTest {
    private val events = Collections.synchronizedList(mutableListOf<DescriptionProviderAuthEvent>())
    private val publisher = ApplicationEventPublisher { event -> events.add(event as DescriptionProviderAuthEvent) }
    private val tracker = ProviderAuthTracker(publisher)
    private val unauthorized = DescriptionException.Unauthorized("expired")

    @Test
    fun `the first failure publishes LOST and the first success after it publishes RESTORED`() {
        tracker.onUnauthorized("grok", unauthorized, "hint")
        tracker.onUnauthorized("grok", unauthorized, "hint")
        tracker.onSuccess("grok", "hint")
        tracker.onSuccess("grok", "hint")

        assertEquals(
            listOf(DescriptionProviderAuthEvent.State.LOST, DescriptionProviderAuthEvent.State.RESTORED),
            events.map { it.state },
        )
    }

    @Test
    fun `a success from UNKNOWN publishes nothing`() {
        tracker.onSuccess("grok", "hint")

        assertEquals(emptyList(), events)
        assertEquals(ProviderAuthStates.Health.HEALTHY, tracker.byProvider().getValue("grok"))
    }

    @Test
    fun `two presets of one provider share the state`() {
        tracker.onUnauthorized("grok", unauthorized, "hint")
        tracker.onUnauthorized("grok", unauthorized, "hint")

        assertEquals(1, events.size)
    }

    @Test
    fun `providers are independent`() {
        tracker.onUnauthorized("grok", unauthorized, "hint")
        tracker.onUnauthorized("claude", unauthorized, "hint")

        assertEquals(listOf("grok", "claude"), events.map { it.provider })
        assertEquals(ProviderAuthStates.Health.LOST, tracker.byProvider().getValue("claude"))
    }

    @Test
    fun `a throwing listener rolls the state back so the transition is reported again`() {
        val failing = ProviderAuthTracker(ApplicationEventPublisher { error("listener down") })

        failing.onUnauthorized("grok", unauthorized, "hint")

        assertEquals(ProviderAuthStates.Health.UNKNOWN, failing.byProvider().getValue("grok"))
    }

    @Test
    fun `an untouched provider is not listed`() {
        assertEquals(emptyMap(), tracker.byProvider())
    }
}
```

- [ ] **Step 2: Запустить тест и убедиться, что он падает**

`./gradlew :frigate-analyzer-ai-description:test --tests ru.zinin.frigate.analyzer.ai.description.core.ProviderAuthTrackerTest`
Ожидание: компиляция падает.

- [ ] **Step 3: Создать `ProviderAuthStates` и `ProviderAuthTracker`**

`api/ProviderAuthStates.kt`:

```kotlin
package ru.zinin.frigate.analyzer.ai.description.api

/** Состояние авторизации по провайдеру на чтение: диалог `/ai` рисует его иконкой. */
interface ProviderAuthStates {
    enum class Health { UNKNOWN, HEALTHY, LOST }

    /** Только провайдеры, которых уже вызывали; порядок не определён. */
    fun byProvider(): Map<String, Health>
}
```

`core/ProviderAuthTracker.kt` — перенос машины состояний из `DefaultDescriptionAgent` с двумя отличиями: ключ по `providerId` и стартовое значение `UNKNOWN` (для переходов ведёт себя как `HEALTHY`).

```kotlin
package ru.zinin.frigate.analyzer.ai.description.core

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.ApplicationEventPublisher
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionProviderAuthEvent
import ru.zinin.frigate.analyzer.ai.description.api.ProviderAuthStates
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/**
 * Авторизация принадлежит провайдеру, а не пресету: два grok-пресета делят один `auth.json`, и
 * отказ обязан дать одно событие на двоих. Переход и публикация идут под одним замком провайдера:
 * слушатель доставляет события владельцу в порядке публикации, и разъехавшийся порядок оставил бы
 * его с сообщением об отказе при рабочих учётных данных.
 */
class ProviderAuthTracker(
    private val eventPublisher: ApplicationEventPublisher,
) : ProviderAuthStates {
    private val states = ConcurrentHashMap<String, AtomicReference<ProviderAuthStates.Health>>()
    private val locks = ConcurrentHashMap<String, Any>()

    override fun byProvider(): Map<String, ProviderAuthStates.Health> = states.mapValues { it.value.get() }

    fun onUnauthorized(
        providerId: String,
        e: DescriptionException.Unauthorized,
        recoveryHint: String,
    ) {
        synchronized(lockFor(providerId)) {
            val state = stateFor(providerId)
            val previous = state.get()
            if (previous == ProviderAuthStates.Health.LOST) return
            state.set(ProviderAuthStates.Health.LOST)
            logger.error(e) {
                "Description provider '$providerId' rejected the credentials; descriptions stay " +
                    "unavailable until re-login. Fix: $recoveryHint"
            }
            publish(
                DescriptionProviderAuthEvent(
                    provider = providerId,
                    state = DescriptionProviderAuthEvent.State.LOST,
                    detail = e.detail,
                    recoveryHint = recoveryHint,
                ),
                state,
                previous,
            )
        }
    }

    fun onSuccess(
        providerId: String,
        recoveryHint: String,
    ) {
        synchronized(lockFor(providerId)) {
            val state = stateFor(providerId)
            val previous = state.get()
            state.set(ProviderAuthStates.Health.HEALTHY)
            if (previous != ProviderAuthStates.Health.LOST) return
            logger.info { "Description provider '$providerId' credentials work again" }
            publish(
                DescriptionProviderAuthEvent(
                    provider = providerId,
                    state = DescriptionProviderAuthEvent.State.RESTORED,
                    detail = null,
                    recoveryHint = recoveryHint,
                ),
                state,
                previous,
            )
        }
    }

    /**
     * Spring доставляет событие синхронно. Слушатель, который бросил, не должен съесть переход:
     * без отката такой же отказ больше никогда не поднял бы событие, и владелец не узнал бы о нём.
     */
    private fun publish(
        event: DescriptionProviderAuthEvent,
        state: AtomicReference<ProviderAuthStates.Health>,
        previous: ProviderAuthStates.Health,
    ) {
        try {
            eventPublisher.publishEvent(event)
        } catch (e: Exception) {
            state.set(previous)
            logger.warn(e) {
                "Cannot publish ${event.state} auth event for '${event.provider}'; " +
                    "the transition will be reported again on the next occurrence"
            }
        }
    }

    private fun stateFor(providerId: String) =
        states.computeIfAbsent(providerId) { AtomicReference(ProviderAuthStates.Health.UNKNOWN) }

    private fun lockFor(providerId: String) = locks.computeIfAbsent(providerId) { Any() }
}
```

- [ ] **Step 4: Вынуть машину состояний из агента**

Из `DefaultDescriptionAgent` удалить `authState`, `authTransitionLock`, `enum class AuthState`, `onUnauthorized`, `onSuccess`, `publishAuthEvent` и параметр `eventPublisher`. Добавить параметр `private val authTracker: ProviderAuthTracker` и заменить вызовы в `executeWithRetry`:

```kotlin
                val result = attempt(backend, request)
                authTracker.onSuccess(backend.providerId, backend.authRecoveryHint)
                return result
            } catch (e: DescriptionException.Unauthorized) {
                authTracker.onUnauthorized(backend.providerId, e, backend.authRecoveryHint)
                throw e
```

В автоконфигурации добавить бин трекера (выше агента) и передать его в агента:

```kotlin
    @Bean
    @ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
    fun providerAuthTracker(eventPublisher: ApplicationEventPublisher): ProviderAuthTracker =
        ProviderAuthTracker(eventPublisher)
```

В `DefaultDescriptionAgentTest` сценарии про авторизацию переносятся в `ProviderAuthTrackerTest`; в самом тесте агента остаётся проверка «`Unauthorized` не повторяется» и хелпер создаёт агента с `ProviderAuthTracker(publisher)`.

- [ ] **Step 5: Запустить тесты модуля**

`./gradlew :frigate-analyzer-ai-description:test`
Ожидание: PASS.

- [ ] **Step 6: Коммит**

```bash
git add modules/ai-description/src
git commit -m "refactor(ai-description): track authorization per provider, not per agent" \
           -m "Claude-Session: https://claude.ai/code/session_01MppBgCU5bLAPtDtnjoCYVk"
```

---

### Task 6: Хранение настроек в `app_settings` и рантайм-выключатель

**Files:**
- Modify: `modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/AppSettingKeys.kt`
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/application/AppSettingsDescriptionRuntimeSettings.kt`
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/facade/RecordingProcessingFacade.kt:84,107-111`
- Test: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/application/AppSettingsDescriptionRuntimeSettingsTest.kt` (create)
- Test: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/facade/RecordingProcessingFacadeTest.kt` (modify — если файла нет, создать тест только на новый гейт)

**Interfaces:**
- Consumes: `DescriptionRuntimeSettings` (Task 4), `AppSettingsService.getString/setString/getBoolean/setBoolean`.
- Produces: `AppSettingKeys.AI_DESCRIPTION_PRESET_ACTIVE`, `AppSettingKeys.AI_DESCRIPTION_ENABLED`; бин `AppSettingsDescriptionRuntimeSettings`.

- [ ] **Step 1: Написать падающий тест реализации**

Создать `AppSettingsDescriptionRuntimeSettingsTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.application

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import ru.zinin.frigate.analyzer.service.AppSettingKeys
import ru.zinin.frigate.analyzer.service.AppSettingsService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppSettingsDescriptionRuntimeSettingsTest {
    private val appSettings = mockk<AppSettingsService>(relaxed = true)
    private val settings = AppSettingsDescriptionRuntimeSettings(appSettings)

    @Test
    fun `an absent preset key reads as null`() =
        runTest {
            coEvery { appSettings.getString(AppSettingKeys.AI_DESCRIPTION_PRESET_ACTIVE, null) } returns null

            assertNull(settings.activePresetId())
        }

    @Test
    fun `an absent enabled key reads as true`() =
        runTest {
            coEvery { appSettings.getBoolean(AppSettingKeys.AI_DESCRIPTION_ENABLED, true) } returns true

            assertTrue(settings.descriptionsEnabled())
        }

    @Test
    fun `the writer passes the actor through`() =
        runTest {
            settings.setActivePresetId("grok-fast", changedBy = "owner")

            coVerify {
                appSettings.setString(AppSettingKeys.AI_DESCRIPTION_PRESET_ACTIVE, "grok-fast", "owner")
            }
        }

    @Test
    fun `the switch writes a boolean`() =
        runTest {
            settings.setDescriptionsEnabled(false, changedBy = "owner")

            coVerify { appSettings.setBoolean(AppSettingKeys.AI_DESCRIPTION_ENABLED, false, "owner") }
        }

    @Test
    fun `keys are stable`() {
        assertEquals("ai.description.preset.active", AppSettingKeys.AI_DESCRIPTION_PRESET_ACTIVE)
        assertEquals("ai.description.enabled", AppSettingKeys.AI_DESCRIPTION_ENABLED)
    }
}
```

- [ ] **Step 2: Запустить тест и убедиться, что он падает**

`./gradlew :frigate-analyzer-core:test --tests ru.zinin.frigate.analyzer.core.application.AppSettingsDescriptionRuntimeSettingsTest`
Ожидание: компиляция падает.

- [ ] **Step 3: Добавить ключи и реализацию**

В `AppSettingKeys` добавить две константы:

```kotlin
    const val AI_DESCRIPTION_PRESET_ACTIVE = "ai.description.preset.active"
    const val AI_DESCRIPTION_ENABLED = "ai.description.enabled"
```

`modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/application/AppSettingsDescriptionRuntimeSettings.kt`:

```kotlin
package ru.zinin.frigate.analyzer.core.application

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRuntimeSettings
import ru.zinin.frigate.analyzer.service.AppSettingKeys
import ru.zinin.frigate.analyzer.service.AppSettingsService

/**
 * Рантайм-настройки описаний поверх `app_settings`. Кэш `AppSettingsService` живёт на процесс и
 * сбрасывается только записью через него же: прямой SQL по таблице работающий процесс не увидит.
 */
@Service
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class AppSettingsDescriptionRuntimeSettings(
    private val appSettings: AppSettingsService,
) : DescriptionRuntimeSettings {
    override suspend fun activePresetId(): String? =
        appSettings.getString(AppSettingKeys.AI_DESCRIPTION_PRESET_ACTIVE, null)

    override suspend fun setActivePresetId(
        id: String,
        changedBy: String?,
    ) = appSettings.setString(AppSettingKeys.AI_DESCRIPTION_PRESET_ACTIVE, id, changedBy)

    override suspend fun descriptionsEnabled(): Boolean =
        appSettings.getBoolean(AppSettingKeys.AI_DESCRIPTION_ENABLED, true)

    override suspend fun setDescriptionsEnabled(
        value: Boolean,
        changedBy: String?,
    ) = appSettings.setBoolean(AppSettingKeys.AI_DESCRIPTION_ENABLED, value, changedBy)
}
```

- [ ] **Step 4: Запустить тест и убедиться, что он проходит**

`./gradlew :frigate-analyzer-core:test --tests ru.zinin.frigate.analyzer.core.application.AppSettingsDescriptionRuntimeSettingsTest`
Ожидание: PASS.

- [ ] **Step 5: Закрыть фасад рантайм-выключателем**

В `RecordingProcessingFacade` добавить в конструктор

```kotlin
    private val runtimeSettingsProvider: ObjectProvider<DescriptionRuntimeSettings>,
```

сделать `buildDescriptionSupplier` `suspend` (вызов на строке 84 уже находится в suspend-функции) и добавить первым делом после получения агента:

```kotlin
        val agent = descriptionAgentProvider.getIfAvailable() ?: return null
        // Рантайм-выключатель: то же поведение, что «агента нет» — уведомление уходит с
        // DescriptionState.Absent, плейсхолдеров нет, слот rate limiter не тратится, потому что
        // TelegramNotificationServiceImpl отсекает null-supplier ДО tryAcquire().
        val runtimeSettings = runtimeSettingsProvider.getIfAvailable()
        if (runtimeSettings != null && !runtimeSettings.descriptionsEnabled()) {
            logger.debug { "AI descriptions switched off at runtime; skipping describe-job for $recordingId" }
            return null
        }
```

- [ ] **Step 6: Написать тест на гейт**

В существующем `RecordingProcessingFacadeTest` расширить хелпер `facade(...)` (строка ~143) новым
параметром и новой зависимостью:

```kotlin
    private fun TestScope.facade(
        agent: DescriptionAgent?,
        framesForRequest: List<FrameData> = listOf(frameWithDetection(0)),
        maxFrames: Int = 10,
        runtimeEnabled: Boolean = true,
    ): Pair<RecordingProcessingFacade, SaveProcessingResultRequest> {
        …
        val runtimeSettings = mockk<DescriptionRuntimeSettings>()
        coEvery { runtimeSettings.descriptionsEnabled() } returns runtimeEnabled
        val runtimeSettingsProvider = mockk<ObjectProvider<DescriptionRuntimeSettings>>()
        every { runtimeSettingsProvider.getIfAvailable() } returns runtimeSettings
        val facade =
            RecordingProcessingFacade(
                …,
                notificationDecisionService = notificationDecisionService,
                runtimeSettingsProvider = runtimeSettingsProvider,
            )
        …
    }
```

и добавить два теста рядом с существующими (stub-ы `notificationDecisionService` и
`recordingEntityService` скопировать из соседнего теста, который доходит до отправки уведомления):

```kotlin
    @Test
    fun `the runtime switch keeps the description supplier out`() =
        runTest {
            val (f, req) = facade(agent = mockk(relaxed = true), runtimeEnabled = false)
            coEvery { notificationDecisionService.isRecordingNotificationsGloballyEnabled() } returns true
            coEvery { notificationDecisionService.evaluate(any(), any(), any()) } returns notifyDecision()
            coEvery { recordingEntityService.saveProcessingResult(any()) } returns savedResult()

            val supplier = captureSupplierDuring { f.processAndNotify(req) }

            assertNull(supplier)
        }

    @Test
    fun `the runtime switch on leaves the supplier in place`() =
        runTest {
            val (f, req) = facade(agent = mockk(relaxed = true), runtimeEnabled = true)
            coEvery { notificationDecisionService.isRecordingNotificationsGloballyEnabled() } returns true
            coEvery { notificationDecisionService.evaluate(any(), any(), any()) } returns notifyDecision()
            coEvery { recordingEntityService.saveProcessingResult(any()) } returns savedResult()

            val supplier = captureSupplierDuring { f.processAndNotify(req) }

            assertNotNull(supplier)
        }
```

Слот rate limiter здесь не проверяется: лимитер живёт в `TelegramNotificationServiceImpl` и при
`null`-supplier-е недостижим — это уже покрыто `TelegramNotificationServiceImplTest`.

- [ ] **Step 7: Запустить тесты модуля**

`./gradlew :frigate-analyzer-core:test`
Ожидание: PASS.

- [ ] **Step 8: Коммит**

```bash
git add modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/AppSettingKeys.kt \
        modules/core/src
git commit -m "feat(core): persist the active preset and the runtime switch in app_settings" \
           -m "Claude-Session: https://claude.ai/code/session_01MppBgCU5bLAPtDtnjoCYVk"
```

---

### Task 7: Экран `/ai` — состояние, рендер и i18n

**Files:**
- Create: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/dto/AiSettingsViewState.kt`
- Create: `…/telegram/bot/handler/aisettings/AiSettingsCallbacks.kt`
- Create: `…/telegram/bot/handler/aisettings/AiSettingsViewStateFactory.kt`
- Create: `…/telegram/bot/handler/aisettings/AiSettingsMessageRenderer.kt`
- Modify: `modules/telegram/src/main/resources/messages_ru.properties`, `messages_en.properties`
- Test: `…/telegram/bot/handler/aisettings/AiSettingsMessageRendererTest.kt` (create)

**Interfaces:**
- Consumes: `DescriptionPresets.all()`, `DescriptionPreset` (Task 3); `ActivePresetResolver.activePresetId()` (Task 4); `ProviderAuthStates.byProvider()`, `ProviderAuthStates.Health` (Task 5); `DescriptionRuntimeSettings.descriptionsEnabled()` (Task 4).
- Produces: `AiSettingsViewState(descriptionsEnabled, activePresetId, presets, authByProvider, language)`; `AiSettingsCallbacks.PREFIX/CLOSE/ON/OFF/SET_PREFIX`; `AiSettingsViewStateFactory.build(language)`; `AiSettingsMessageRenderer.render(state): RenderedAiSettings(text, keyboard)`; ключи i18n `ai.settings.*`.

- [ ] **Step 1: Написать падающий тест рендера**

Создать `AiSettingsMessageRendererTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.bot.handler.aisettings

import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionPreset
import ru.zinin.frigate.analyzer.ai.description.api.ProviderAuthStates
import ru.zinin.frigate.analyzer.telegram.dto.AiSettingsViewState
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiSettingsMessageRendererTest {
    // Тексты живут в бандлах и проверяются отдельно; здесь важны ключи, аргументы и payload.
    private val msg =
        mockk<MessageResolver>().also {
            every { it.get(any<String>(), any<String>(), *anyVararg()) } answers { firstArg() }
        }
    private val renderer = AiSettingsMessageRenderer(msg)

    private val fast = DescriptionPreset("grok-fast", "grok", "grok-4.6", "low", null)
    private val luna = DescriptionPreset("byok-luna", "grok", "codex-luna", "", null)
    private val opus = DescriptionPreset("claude-opus", "claude", "opus", "", "no token")

    private fun state(
        enabled: Boolean = true,
        activeId: String? = "grok-fast",
        presets: List<DescriptionPreset> = listOf(fast, luna, opus),
        auth: Map<String, ProviderAuthStates.Health> = mapOf("grok" to ProviderAuthStates.Health.HEALTHY),
    ) = AiSettingsViewState(
        descriptionsEnabled = enabled,
        activePresetId = activeId,
        presets = presets,
        authByProvider = auth,
        language = "ru",
    )

    private fun payloads(state: AiSettingsViewState): List<String> =
        renderer
            .render(state)
            .keyboard.keyboard
            .flatten()
            .map { (it as CallbackDataInlineKeyboardButton).callbackData }

    @Test
    fun `the active preset line carries provider, model and effort`() {
        renderer.render(state())

        verify { msg.get("ai.settings.active", "ru", "grok-fast", "grok", "grok-4.6", "low") }
    }

    @Test
    fun `a blank effort renders as a dash`() {
        renderer.render(state(activeId = "byok-luna"))

        verify { msg.get("ai.settings.active", "ru", "byok-luna", "grok", "codex-luna", "—") }
    }

    @Test
    fun `each provider gets one line and an unconfigured one reports the reason`() {
        renderer.render(state())

        verify { msg.get("ai.settings.auth.healthy", "ru", "grok") }
        verify { msg.get("ai.settings.auth.unavailable", "ru", "claude", "no token") }
    }

    @Test
    fun `a provider that was never called reads as unknown`() {
        renderer.render(state(auth = emptyMap()))

        verify { msg.get("ai.settings.auth.unknown", "ru", "grok") }
    }

    @Test
    fun `every preset gets a button and the active one is marked`() {
        val rendered = renderer.render(state())
        val labels =
            rendered.keyboard.keyboard
                .flatten()
                .map { (it as CallbackDataInlineKeyboardButton).text }

        assertTrue(labels.contains("✅ grok-fast"), labels.toString())
        assertTrue(labels.contains("byok-luna"), labels.toString())
        assertTrue(labels.contains("⚠️ claude-opus"), labels.toString())
        assertTrue(payloads(state()).containsAll(listOf("aip:set:grok-fast", "aip:set:claude-opus")))
    }

    @Test
    fun `the switch button offers the opposite state`() {
        assertTrue(payloads(state(enabled = true)).contains("aip:off"))
        assertTrue(payloads(state(enabled = false)).contains("aip:on"))
        verify { msg.get("ai.settings.state.off", "ru") }
    }

    @Test
    fun `an empty catalog shows the none line and only close`() {
        val empty = state(activeId = null, presets = emptyList())

        assertTrue(renderer.render(empty).text.contains("ai.settings.active.none"))
        assertEquals(listOf("aip:close"), payloads(empty))
    }
}
```

- [ ] **Step 2: Запустить тест и убедиться, что он падает**

`./gradlew :frigate-analyzer-telegram:test --tests ru.zinin.frigate.analyzer.telegram.bot.handler.aisettings.AiSettingsMessageRendererTest`
Ожидание: компиляция падает.

- [ ] **Step 3: Создать DTO, фабрику состояния и рендер**

`dto/AiSettingsViewState.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.dto

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionPreset
import ru.zinin.frigate.analyzer.ai.description.api.ProviderAuthStates

data class AiSettingsViewState(
    val descriptionsEnabled: Boolean,
    /** null = каталога нет: фича включена, но пресеты не объявлены. */
    val activePresetId: String?,
    val presets: List<DescriptionPreset>,
    val authByProvider: Map<String, ProviderAuthStates.Health>,
    val language: String,
)
```

`bot/handler/aisettings/AiSettingsCallbacks.kt` — payload-и в одном месте, чтобы рендер и
диспетчер (Task 8) не разошлись:

```kotlin
package ru.zinin.frigate.analyzer.telegram.bot.handler.aisettings

object AiSettingsCallbacks {
    const val PREFIX = "aip:"
    const val CLOSE = PREFIX + "close"
    const val ON = PREFIX + "on"
    const val OFF = PREFIX + "off"
    const val SET_PREFIX = PREFIX + "set:"
}
```

`bot/handler/aisettings/AiSettingsViewStateFactory.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.bot.handler.aisettings

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionPresets
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRuntimeSettings
import ru.zinin.frigate.analyzer.ai.description.api.ProviderAuthStates
import ru.zinin.frigate.analyzer.ai.description.core.ActivePresetResolver
import ru.zinin.frigate.analyzer.telegram.dto.AiSettingsViewState

/**
 * Единая точка сборки состояния экрана: команда и перерисовка после коллбэка читают одно и то же.
 * Зависимости через [ObjectProvider] — при `ai.description.enabled=false` этих бинов нет, а бот
 * обязан стартовать.
 */
@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class AiSettingsViewStateFactory(
    private val presetsProvider: ObjectProvider<DescriptionPresets>,
    private val resolverProvider: ObjectProvider<ActivePresetResolver>,
    private val runtimeSettingsProvider: ObjectProvider<DescriptionRuntimeSettings>,
    private val authStatesProvider: ObjectProvider<ProviderAuthStates>,
) {
    suspend fun build(language: String): AiSettingsViewState {
        val presets = presetsProvider.getIfAvailable()?.all().orEmpty()
        return AiSettingsViewState(
            descriptionsEnabled = runtimeSettingsProvider.getIfAvailable()?.descriptionsEnabled() ?: true,
            activePresetId = if (presets.isEmpty()) null else resolverProvider.getIfAvailable()?.activePresetId(),
            presets = presets,
            authByProvider = authStatesProvider.getIfAvailable()?.byProvider().orEmpty(),
            language = language,
        )
    }
}
```

`bot/handler/aisettings/AiSettingsMessageRenderer.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.bot.handler.aisettings

import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.utils.matrix
import dev.inmo.tgbotapi.utils.row
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionPreset
import ru.zinin.frigate.analyzer.ai.description.api.ProviderAuthStates
import ru.zinin.frigate.analyzer.telegram.dto.AiSettingsViewState
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver

data class RenderedAiSettings(
    val text: String,
    val keyboard: InlineKeyboardMarkup,
)

@Component
class AiSettingsMessageRenderer(
    private val msg: MessageResolver,
) {
    fun render(state: AiSettingsViewState): RenderedAiSettings =
        RenderedAiSettings(renderText(state), renderKeyboard(state))

    private fun renderText(state: AiSettingsViewState): String {
        val lang = state.language
        val active = state.presets.firstOrNull { it.id == state.activePresetId }
        return buildString {
            appendLine(msg.get("ai.settings.title", lang))
            appendLine(
                msg.get(
                    "ai.settings.state",
                    lang,
                    msg.get(if (state.descriptionsEnabled) "ai.settings.state.on" else "ai.settings.state.off", lang),
                ),
            )
            if (active == null) {
                appendLine(msg.get("ai.settings.active.none", lang))
                return@buildString
            }
            appendLine(
                msg.get("ai.settings.active", lang, active.id, active.provider, active.model, effortLabel(active)),
            )
            appendLine()
            state.presets.map { it.provider }.distinct().forEach { provider ->
                appendLine(providerLine(state, provider, lang))
            }
        }
    }

    /**
     * Провайдер, у которого ни один пресет не годен, описывается причиной из конфигурации, а не
     * состоянием авторизации: его никто и не вызывал.
     */
    private fun providerLine(
        state: AiSettingsViewState,
        provider: String,
        lang: String,
    ): String {
        val presets = state.presets.filter { it.provider == provider }
        val unavailableReason = presets.firstOrNull()?.unavailableReason
        if (presets.none { it.available } && unavailableReason != null) {
            return msg.get("ai.settings.auth.unavailable", lang, provider, unavailableReason)
        }
        return when (state.authByProvider[provider] ?: ProviderAuthStates.Health.UNKNOWN) {
            ProviderAuthStates.Health.HEALTHY -> msg.get("ai.settings.auth.healthy", lang, provider)
            ProviderAuthStates.Health.LOST -> msg.get("ai.settings.auth.lost", lang, provider)
            ProviderAuthStates.Health.UNKNOWN -> msg.get("ai.settings.auth.unknown", lang, provider)
        }
    }

    private fun effortLabel(preset: DescriptionPreset): String = preset.effort.ifBlank { "—" }

    private fun renderKeyboard(state: AiSettingsViewState): InlineKeyboardMarkup {
        val lang = state.language
        return InlineKeyboardMarkup(
            keyboard =
                matrix {
                    state.presets.forEach { preset ->
                        row {
                            +CallbackDataInlineKeyboardButton(
                                presetLabel(preset, state.activePresetId),
                                AiSettingsCallbacks.SET_PREFIX + preset.id,
                            )
                        }
                    }
                    if (state.presets.isNotEmpty()) {
                        row {
                            +CallbackDataInlineKeyboardButton(
                                msg.get(
                                    if (state.descriptionsEnabled) {
                                        "ai.settings.button.disable"
                                    } else {
                                        "ai.settings.button.enable"
                                    },
                                    lang,
                                ),
                                if (state.descriptionsEnabled) AiSettingsCallbacks.OFF else AiSettingsCallbacks.ON,
                            )
                        }
                    }
                    row {
                        +CallbackDataInlineKeyboardButton(
                            msg.get("ai.settings.button.close", lang),
                            AiSettingsCallbacks.CLOSE,
                        )
                    }
                },
        )
    }

    private fun presetLabel(
        preset: DescriptionPreset,
        activeId: String?,
    ): String =
        when {
            !preset.available -> "⚠️ ${preset.id}"
            preset.id == activeId -> "✅ ${preset.id}"
            else -> preset.id
        }
}
```

- [ ] **Step 4: Добавить ключи в оба бандла**

`messages_ru.properties`:

```properties
ai.settings.title=🤖 AI-описания
ai.settings.state=Состояние: {0}
ai.settings.state.on=включены
ai.settings.state.off=выключены
ai.settings.active=Активный пресет: {0} ({1} / {2} / {3})
ai.settings.active.none=Пресеты не настроены
ai.settings.auth.healthy=🟢 {0} — авторизация в порядке
ai.settings.auth.lost=🔴 {0} — отказ авторизации
ai.settings.auth.unknown=⚪ {0} — ещё не вызывался
ai.settings.auth.unavailable=⚠️ {0} — не настроен: {1}
ai.settings.button.enable=Включить описания
ai.settings.button.disable=Выключить описания
ai.settings.button.close=Закрыть
ai.settings.alert.switched=Активен пресет {0}
ai.settings.alert.enabled=Описания включены
ai.settings.alert.disabled=Описания выключены
ai.settings.alert.unavailable=Пресет недоступен: {0}
```

`messages_en.properties` — те же ключи с английскими значениями (`AI descriptions`, `State: {0}`, `enabled`, `disabled`, `Active preset: {0} ({1} / {2} / {3})`, `No presets configured`, `{0} credentials work`, `{0} rejected the credentials`, `{0} not called yet`, `{0} not configured: {1}`, `Enable descriptions`, `Disable descriptions`, `Close`, `Preset {0} is active`, `Descriptions enabled`, `Descriptions disabled`, `Preset unavailable: {0}`).

- [ ] **Step 5: Запустить тест рендера**

`./gradlew :frigate-analyzer-telegram:test --tests ru.zinin.frigate.analyzer.telegram.bot.handler.aisettings.AiSettingsMessageRendererTest`
Ожидание: PASS.

- [ ] **Step 6: Коммит**

```bash
git add modules/telegram/src
git commit -m "feat(telegram): render the AI description settings screen" \
           -m "Claude-Session: https://claude.ai/code/session_01MppBgCU5bLAPtDtnjoCYVk"
```

---

### Task 8: Команда `/ai`, коллбэки и подсказка в алерте авторизации

**Files:**
- Create: `…/telegram/bot/handler/aisettings/AiSettingsCallbackHandler.kt`
- Create: `…/telegram/bot/handler/aisettings/AiSettingsCommandHandler.kt`
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBot.kt` (блок регистрации коллбэков, рядом с `nfs:`)
- Modify: `modules/telegram/src/main/resources/messages_{ru,en}.properties` (`command.ai.description`, третья строка в `ai.description.auth.lost`)
- Test: `…/telegram/bot/handler/aisettings/AiSettingsCallbackHandlerTest.kt` (create)

**Interfaces:**
- Consumes: `AiSettingsCallbacks`, `AiSettingsViewStateFactory.build(language)`, `AiSettingsMessageRenderer.render(state)` (Task 7); `DescriptionRuntimeSettings.setActivePresetId/setDescriptionsEnabled` (Task 4); `DescriptionPresets.all()` (Task 3).
- Produces: `AiSettingsCallbackHandler.DispatchOutcome` (`RERENDER`, `CLOSE`, `UNAUTHORIZED`, `IGNORE`, `ALERT`), `AiSettingsCallbackHandler.Dispatched(outcome, alertKey, alertArgument)`, `AiSettingsCallbackHandler.dispatch(data, isOwner, changedBy): Dispatched`; команда `/ai` с `order = 8`.

- [ ] **Step 1: Написать падающий тест диспетчера**

Создать `AiSettingsCallbackHandlerTest.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.bot.handler.aisettings

import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.springframework.beans.factory.ObjectProvider
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionPreset
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionPresets
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRuntimeSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class AiSettingsCallbackHandlerTest {
    private val settings = mockk<DescriptionRuntimeSettings>(relaxed = true)
    private val presets =
        mockk<DescriptionPresets>().also {
            every { it.all() } returns
                listOf(
                    DescriptionPreset("grok-fast", "grok", "grok-4.6", "low", null),
                    DescriptionPreset("grok-deep", "grok", "grok-4.6", "xhigh", null),
                    DescriptionPreset("claude-opus", "claude", "opus", "", "no token"),
                )
        }
    private val handler =
        AiSettingsCallbackHandler(
            presetsProvider = provider(presets),
            runtimeSettingsProvider = provider(settings),
        )

    private inline fun <reified T : Any> provider(value: T): ObjectProvider<T> =
        mockk<ObjectProvider<T>>().also { every { it.getIfAvailable() } returns value }

    @Test
    fun `the owner switches the preset`() =
        runTest {
            val dispatched = handler.dispatch("aip:set:grok-deep", isOwner = true, changedBy = "owner")

            assertEquals(AiSettingsCallbackHandler.DispatchOutcome.RERENDER, dispatched.outcome)
            coVerify { settings.setActivePresetId("grok-deep", "owner") }
        }

    @Test
    fun `an unavailable preset is refused with its reason and nothing is written`() =
        runTest {
            val dispatched = handler.dispatch("aip:set:claude-opus", isOwner = true, changedBy = "owner")

            assertEquals(AiSettingsCallbackHandler.DispatchOutcome.ALERT, dispatched.outcome)
            assertEquals("no token", dispatched.alertArgument)
            coVerify(exactly = 0) { settings.setActivePresetId(any(), any()) }
        }

    @Test
    fun `a preset that is gone from the config is refused`() =
        runTest {
            val dispatched = handler.dispatch("aip:set:missing", isOwner = true, changedBy = "owner")

            assertEquals(AiSettingsCallbackHandler.DispatchOutcome.ALERT, dispatched.outcome)
            coVerify(exactly = 0) { settings.setActivePresetId(any(), any()) }
        }

    @Test
    fun `the switch writes an explicit value in both directions`() =
        runTest {
            handler.dispatch("aip:off", isOwner = true, changedBy = "owner")
            handler.dispatch("aip:on", isOwner = true, changedBy = "owner")

            coVerify { settings.setDescriptionsEnabled(false, "owner") }
            coVerify { settings.setDescriptionsEnabled(true, "owner") }
        }

    @Test
    fun `close closes`() =
        runTest {
            assertEquals(
                AiSettingsCallbackHandler.DispatchOutcome.CLOSE,
                handler.dispatch("aip:close", isOwner = true, changedBy = "owner").outcome,
            )
        }

    @Test
    fun `a non-owner changes nothing`() =
        runTest {
            val dispatched = handler.dispatch("aip:set:grok-deep", isOwner = false, changedBy = "user")

            assertEquals(AiSettingsCallbackHandler.DispatchOutcome.UNAUTHORIZED, dispatched.outcome)
            coVerify(exactly = 0) { settings.setActivePresetId(any(), any()) }
            coVerify(exactly = 0) { settings.setDescriptionsEnabled(any(), any()) }
        }

    @Test
    fun `a malformed payload is ignored`() =
        runTest {
            assertEquals(
                AiSettingsCallbackHandler.DispatchOutcome.IGNORE,
                handler.dispatch("aip:set:", isOwner = true, changedBy = "owner").outcome,
            )
            assertEquals(
                AiSettingsCallbackHandler.DispatchOutcome.IGNORE,
                handler.dispatch("aip:", isOwner = true, changedBy = "owner").outcome,
            )
        }
}
```

- [ ] **Step 2: Запустить тест и убедиться, что он падает**

`./gradlew :frigate-analyzer-telegram:test --tests ru.zinin.frigate.analyzer.telegram.bot.handler.aisettings.AiSettingsCallbackHandlerTest`
Ожидание: компиляция падает.

- [ ] **Step 3: Создать диспетчер**

```kotlin
package ru.zinin.frigate.analyzer.telegram.bot.handler.aisettings

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionPresets
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionRuntimeSettings

private val logger = KotlinLogging.logger {}

/**
 * Чистая диспетчеризация `aip:*`: побочные эффекты (запись настроек) здесь, работа с Telegram —
 * на вызывающей стороне. Значение в payload всегда явное, поэтому повторный клик идемпотентен.
 */
@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class AiSettingsCallbackHandler(
    private val presetsProvider: ObjectProvider<DescriptionPresets>,
    private val runtimeSettingsProvider: ObjectProvider<DescriptionRuntimeSettings>,
) {
    enum class DispatchOutcome { RERENDER, CLOSE, UNAUTHORIZED, IGNORE, ALERT }

    data class Dispatched(
        val outcome: DispatchOutcome,
        /** Ключ i18n и аргумент для answerCallbackQuery, когда [outcome] это ALERT. */
        val alertKey: String? = null,
        val alertArgument: String? = null,
    )

    suspend fun dispatch(
        data: String,
        isOwner: Boolean,
        changedBy: String?,
    ): Dispatched {
        if (!isOwner) return Dispatched(DispatchOutcome.UNAUTHORIZED)
        val settings = runtimeSettingsProvider.getIfAvailable() ?: return Dispatched(DispatchOutcome.IGNORE)
        return when {
            data == AiSettingsCallbacks.CLOSE -> Dispatched(DispatchOutcome.CLOSE)

            data == AiSettingsCallbacks.ON || data == AiSettingsCallbacks.OFF -> {
                val enabled = data == AiSettingsCallbacks.ON
                settings.setDescriptionsEnabled(enabled, changedBy)
                Dispatched(DispatchOutcome.RERENDER)
            }

            data.startsWith(AiSettingsCallbacks.SET_PREFIX) -> {
                val id = data.removePrefix(AiSettingsCallbacks.SET_PREFIX)
                if (id.isBlank()) return Dispatched(DispatchOutcome.IGNORE)
                val preset = presetsProvider.getIfAvailable()?.all()?.firstOrNull { it.id == id }
                when {
                    preset == null ->
                        Dispatched(DispatchOutcome.ALERT, "ai.settings.alert.unavailable", id)

                    !preset.available ->
                        Dispatched(
                            DispatchOutcome.ALERT,
                            "ai.settings.alert.unavailable",
                            preset.unavailableReason,
                        )

                    else -> {
                        settings.setActivePresetId(id, changedBy)
                        Dispatched(DispatchOutcome.RERENDER)
                    }
                }
            }

            else -> {
                logger.debug { "Ignoring malformed aip callback: $data" }
                Dispatched(DispatchOutcome.IGNORE)
            }
        }
    }
}
```

- [ ] **Step 4: Создать команду и зарегистрировать коллбэки**

`bot/handler/aisettings/AiSettingsCommandHandler.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.bot.handler.aisettings

import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.types.message.abstracts.ChatContentMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.telegram.bot.handler.CommandHandler
import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto
import ru.zinin.frigate.analyzer.telegram.model.UserRole

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
class AiSettingsCommandHandler(
    private val viewStateFactory: AiSettingsViewStateFactory,
    private val renderer: AiSettingsMessageRenderer,
) : CommandHandler {
    override val command: String = "ai"
    override val requiredRole: UserRole = UserRole.OWNER
    override val order: Int = 8

    override suspend fun BehaviourContext.handle(
        message: ChatContentMessage<TextContent>,
        user: TelegramUserDto?,
    ) {
        if (user == null) return
        logger.debug { "/ai opened by chatId=${message.chat.id} username=${user.username}" }
        val rendered = renderer.render(viewStateFactory.build(user.languageCode ?: "en"))
        sendTextMessage(message.chat.id, rendered.text, replyMarkup = rendered.keyboard)
    }
}
```

В `FrigateAnalyzerBot` добавить три зависимости как `ObjectProvider<…>` (`AiSettingsCallbackHandler`,
`AiSettingsMessageRenderer`, `AiSettingsViewStateFactory`) — при `ai.description.enabled=false`
этих бинов нет, а бот обязан стартовать — и сразу после блока `nfs:` зарегистрировать:

```kotlin
            // Дефолтный markerFactory, в отличие от nfs: waiter-а здесь нет, а сериализация кликов
            // одного пользователя бесплатно защищает от двойного нажатия.
            onDataCallbackQuery(
                initialFilter = { it.data.startsWith(AiSettingsCallbacks.PREFIX) },
            ) { callback ->
                try {
                    val handler = aiSettingsCallbackHandler.getIfAvailable() ?: return@onDataCallbackQuery
                    val renderer = aiSettingsMessageRenderer.getIfAvailable() ?: return@onDataCallbackQuery
                    val viewStateFactory = aiSettingsViewStateFactory.getIfAvailable() ?: return@onDataCallbackQuery
                    val callbackMsg = (callback as? MessageDataCallbackQuery)?.message
                    val senderUsername = callback.user.username?.withoutAt
                    val current = senderUsername?.let { userService.findActiveByUsername(it) }
                    val owner = current != null && userService.isOwner(current.username)
                    val lang = current?.languageCode ?: "en"
                    val dispatched =
                        if (current == null) {
                            AiSettingsCallbackHandler.Dispatched(
                                AiSettingsCallbackHandler.DispatchOutcome.UNAUTHORIZED,
                            )
                        } else {
                            handler.dispatch(callback.data, owner, current.username)
                        }
                    // Ровно один ответ на коллбэк: с текстом, когда есть что сказать, иначе пустой —
                    // он и гасит спиннер кнопки.
                    try {
                        bot.answer(
                            callback,
                            text =
                                dispatched.alertKey?.let { key ->
                                    msg.get(key, lang, dispatched.alertArgument.orEmpty())
                                },
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to answer aip callback id=${callback.id}" }
                    }
                    if (callbackMsg == null) return@onDataCallbackQuery
                    when (dispatched.outcome) {
                        AiSettingsCallbackHandler.DispatchOutcome.RERENDER -> {
                            val rendered = renderer.render(viewStateFactory.build(lang))
                            try {
                                @Suppress("UNCHECKED_CAST")
                                bot.editMessageText(
                                    callbackMsg as ContentMessage<TextContent>,
                                    rendered.text,
                                    replyMarkup = rendered.keyboard,
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                val isNotModified =
                                    e.message?.contains("message is not modified", ignoreCase = true) == true
                                if (isNotModified) {
                                    logger.debug { "aip edit no-op (message not modified): ${callback.data}" }
                                } else {
                                    logger.warn(e) { "Failed to edit /ai message for callback=${callback.data}" }
                                }
                            }
                        }

                        AiSettingsCallbackHandler.DispatchOutcome.CLOSE -> {
                            try {
                                bot.editMessageReplyMarkup(callbackMsg, replyMarkup = null)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                logger.warn(e) { "Failed to close /ai keyboard" }
                            }
                        }

                        else -> {
                            Unit
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to handle aip callback data=${callback.data}" }
                }
            }
```

- [ ] **Step 5: Добавить i18n команды и строку `/ai` в алерт авторизации**

`messages_ru.properties`:

```properties
command.ai.description=Настройки AI-описаний
```

и заменить существующую строку:

```properties
ai.description.auth.lost=🔴 AI-описания: провайдер {0} отверг авторизацию. Описания на нём недоступны до повторного входа.\nКоманда для входа: {1}\nПереключить пресет: /ai
```

`messages_en.properties` — `command.ai.description=AI description settings` и

```properties
ai.description.auth.lost=🔴 AI descriptions: provider {0} rejected the credentials. Descriptions from it stay unavailable until you sign in again.\nSign-in command: {1}\nSwitch preset: /ai
```

- [ ] **Step 6: Запустить тесты модуля**

`./gradlew :frigate-analyzer-telegram:test`
Ожидание: PASS. Тест на наличие ключей в обоих бандлах (если такого нет — добавить `AiSettingsMessagesTest`, который проверяет, что каждый ключ `ai.settings.*` и `command.ai.description` есть в обоих файлах).

- [ ] **Step 7: Коммит**

```bash
git add modules/telegram/src
git commit -m "feat(telegram): switch the AI description preset from an owner-only /ai dialog" \
           -m "Claude-Session: https://claude.ai/code/session_01MppBgCU5bLAPtDtnjoCYVk"
```

---

### Task 9: Деплой и документация

**Files:**
- Modify: `docker/deploy/docker-entrypoint.sh:11-60`
- Modify: `docker/deploy/application-docker.yaml.example`
- Modify: `docker/deploy/.env.example` (блок Grok и блок AI description)
- Modify: `README.md` (список команд, раздел AI description)
- Modify: `CLAUDE.md` (строка модуля `ai-description`, ключевой паттерн)
- Modify: `.claude/rules/ai-description.md`, `.claude/rules/configuration.md`, `.claude/rules/database.md`

**Interfaces:**
- Consumes: всё из Tasks 1–8. Нового кода нет.

- [ ] **Step 1: Переписать проверки энтрипойнта**

Заменить весь блок `case "${APP_AI_DESCRIPTION_PROVIDER:-claude}" in … esac` (строки 12–59) на:

```sh
  # Пресеты живут в application-docker.yaml и шеллу не видны: проверяем не выбранный провайдер, а
  # тех, чьи входные данные присутствуют.
  if [ -n "${CLAUDE_CODE_OAUTH_TOKEN:-}" ] || [ -n "${ANTHROPIC_AUTH_TOKEN:-}" ]; then
      if [ -n "${CLAUDE_CLI_PATH:-}" ]; then
          # Explicit path override — check it directly; falling back to PATH would give a false negative.
          if [ -x "${CLAUDE_CLI_PATH}" ]; then
              echo "INFO: claude CLI detected at ${CLAUDE_CLI_PATH}: $(${CLAUDE_CLI_PATH} --version 2>/dev/null || echo 'unknown')"
          else
              echo "WARN: explicit CLAUDE_CLI_PATH=${CLAUDE_CLI_PATH} not found or not executable; claude presets will return fallback." >&2
          fi
      elif ! command -v claude >/dev/null 2>&1; then
          echo "WARN: claude CLI not found in PATH (CLAUDE_CLI_PATH is empty); claude presets will return fallback." >&2
      else
          echo "INFO: claude CLI detected: $(claude --version 2>/dev/null || echo 'unknown')"
      fi
  else
      echo "INFO: neither CLAUDE_CODE_OAUTH_TOKEN nor ANTHROPIC_AUTH_TOKEN is set; claude presets will be marked unavailable."
  fi

  if [ -n "${GROK_HOME:-}" ]; then
      if [ -n "${GROK_CLI_PATH:-}" ]; then
          if [ -x "${GROK_CLI_PATH}" ]; then
              echo "INFO: grok CLI detected at ${GROK_CLI_PATH}: $(${GROK_CLI_PATH} --version 2>/dev/null || echo 'unknown')"
          else
              echo "WARN: explicit GROK_CLI_PATH=${GROK_CLI_PATH} not found or not executable; grok presets will return fallback." >&2
          fi
      elif ! command -v grok >/dev/null 2>&1; then
          echo "WARN: grok CLI not found in PATH (GROK_CLI_PATH is empty); grok presets will return fallback." >&2
      else
          echo "INFO: grok CLI detected: $(grok --version 2>/dev/null || echo 'unknown')"
      fi
      if [ ! -d "${GROK_HOME}" ] || [ ! -w "${GROK_HOME}" ]; then
          echo "WARN: GROK_HOME=${GROK_HOME} is missing or not writable; 'grok login' and token refresh will fail. On the host: mkdir -p grok-home && chown 1000:1000 grok-home" >&2
      elif [ ! -f "${GROK_HOME}/auth.json" ]; then
          echo "WARN: ${GROK_HOME}/auth.json not found; run 'docker compose exec frigate-analyzer grok login --device-code' (not needed for BYOK models with their own api_key in config.toml)." >&2
      else
          echo "INFO: grok credentials found in ${GROK_HOME}"
      fi
  else
      echo "WARN: GROK_HOME is not set; grok presets would fall back to the ephemeral default under the temp folder, point GROK_HOME at a mounted volume." >&2
  fi

  echo "INFO: the active preset is chosen in application-docker.yaml and in the /ai dialog; this check only reports which providers look usable."
```

- [ ] **Step 2: Добавить пример пресетов в `application-docker.yaml.example`**

```yaml
  # AI-описания: сколько угодно пресетов, активный выбирается владельцем в /ai и переживает
  # рестарт. effort только у grok; пустой effort = флаг --effort не передаётся.
  ai:
    description:
      default-preset: grok-fast
      presets:
        grok-fast:   { provider: grok,   model: grok-4.6,   effort: low }
        grok-deep:   { provider: grok,   model: grok-4.6,   effort: xhigh }
        claude-opus: { provider: claude, model: opus }
```

- [ ] **Step 3: Обновить `.env.example`**

Пометить `APP_AI_DESCRIPTION_PROVIDER`, `GROK_MODEL`, `GROK_EFFORT`, `CLAUDE_MODEL` как путь одного пресета, который применяется, только когда `presets` пуст, и указать на `application-docker.yaml`. Добавить `APP_AI_DESCRIPTION_DEFAULT_PRESET`.

- [ ] **Step 4: Обновить README**

В список команд добавить `/ai` (только владелец). В разделе AI description — абзац про пресеты с примером yaml, про переключение из `/ai`, про то, что выбор переживает рестарт, и предупреждение про `xhigh` (~48 с при `APP_AI_DESCRIPTION_TIMEOUT=60s`).

- [ ] **Step 5: Обновить правила**

`.claude/rules/ai-description.md`: в `paths:` добавить `**/handler/aisettings/**`; таблицу слоёв дополнить строками про фабрики, каталог, резолвер и трекер; описать пресеты, резолюцию, рантайм-выключатель и диалог. `.claude/rules/configuration.md`: свойства `presets.*`, `default-preset`, ключи `app_settings`, пометка legacy у `APP_AI_DESCRIPTION_PROVIDER`. `.claude/rules/database.md`: два новых ключа `app_settings` и оговорка про кэш. `CLAUDE.md`: строка модуля `ai-description` и ключевой паттерн упоминают пресеты и `/ai`.

- [ ] **Step 6: Коммит**

```bash
git add docker/deploy README.md CLAUDE.md .claude/rules
git commit -m "docs(ai-description): document presets, the /ai dialog and the runtime switch" \
           -m "Claude-Session: https://claude.ai/code/session_01MppBgCU5bLAPtDtnjoCYVk"
```

---

### Task 10: Полная сборка, ревью и живая проверка

**Files:** нет новых; возможны точечные правки по результатам.

- [ ] **Step 1: ktlint и полная сборка**

`./gradlew ktlintFormat` (при необходимости), затем `./gradlew build` через `claude-forge:build-runner`.
Ожидание: BUILD SUCCESSFUL, ноль падений.

- [ ] **Step 2: Ревью**

Запустить `superpowers:code-reviewer`, исправить критические замечания, повторить до чистого прогона.

- [ ] **Step 3: Живая проверка на стенде (вручную, вне CI)**

```bash
# в application-docker.yaml объявить три пресета из Task 9 Step 2
docker compose up -d
docker compose logs frigate-analyzer | grep -i "Description presets"   # INFO со списком и дефолтом
# в Telegram: /ai — экран со списком, активным пресетом и состоянием авторизации
```

Проверить: переключение пресета меняет `model=` в DEBUG-строке следующей записи; выключатель даёт уведомление без блоков описания и без плейсхолдеров; `mv grok-home/auth.json grok-home/auth.json.bak` даёт 🔴 у grok в `/ai` и сообщение владельцу с подсказкой `/ai`; после рестарта контейнера активным остаётся выбранный пресет; claude-пресет без токена показан с `⚠️` и не выбирается.

- [ ] **Step 4: Убрать документы superpowers перед мержем PR**

```bash
git rm docs/superpowers/specs/2026-09-04-ai-description-presets-design.md \
       docs/superpowers/plans/2026-09-04-ai-description-presets.md
git commit -m "chore: drop the superpowers documents from the PR diff" \
           -m "Claude-Session: https://claude.ai/code/session_01MppBgCU5bLAPtDtnjoCYVk"
```

- [ ] **Step 5: Дописать результаты в описание PR #44**

---

## Self-review

- **Покрытие спеки.** Конфигурация и валидация: Task 1. Параметризация модели: Task 2. Фабрики, каталог, резолюция пригодности, автоконфигурация, sanity checker: Task 3. SPI рантайм-настроек, резолвер, резолюция на вызов: Task 4. Авторизация по провайдеру и `UNKNOWN`: Task 5. `app_settings`, рантайм-выключатель, гейт фасада: Task 6. Экран, i18n: Task 7. Команда, коллбэки, регистрация, подсказка `/ai` в алерте: Task 8. Энтрипойнт, yaml-пример, `.env.example`, README, правила: Task 9. Сборка, ревью, живая проверка: Task 10.
- **Плейсхолдеры.** Каждый шаг с кодом несёт код; «similar to Task N» нет; тексты i18n и argv приведены целиком.
- **Согласованность типов.** `DescriptionProperties.Preset(provider, model, effort)` — Tasks 1, 3; `DescriptionBackendFactory.availability()/create(preset)` — Task 3, используется в Tasks 3 и 7 через каталог; `DescriptionPresetCatalog.Entry(view, backend)`, `.byId`, `.fallback()`, `.fallbackId` — Tasks 3, 4; `ActivePresetResolver.resolve()/activePresetId()` — Tasks 4, 7; `ProviderAuthStates.Health` — Tasks 5, 7; `DescriptionRuntimeSettings` — Tasks 4, 6, 7, 8; `AiSettingsCallbacks` объявляется в Task 7 и используется рендером, диспетчером и регистрацией коллбэков в Task 8.
- **Известный порядок.** Конструктор `DefaultDescriptionAgent` меняется трижды (Tasks 3, 4, 5) — это осознанно: каждая правка оставляет модуль собранным и покрытым тестами, а сливать их в одну задачу значит сливать три независимо ревьюируемых изменения.
