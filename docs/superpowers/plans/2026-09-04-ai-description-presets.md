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
- Каждое сообщение коммита заканчивается отдельным `-m` со строкой `Claude-Session: <URL текущей сессии>`.
  **В командах ниже стоит плейсхолдер `<SESSION_URL>` — исполнитель подставляет URL СВОЕЙ сессии.**
  Ранее здесь был зашит id сессии, писавшей план; скопированный дословно, он приписал бы всю работу
  чужой сессии.
- Конструкторы `@ConfigurationProperties`-классов вызываются только с именованными аргументами; новые параметры добавляются в конец списка.
- Идентификаторы провайдеров: `claude`, `grok`. Id пресета: `[a-z0-9][a-z0-9-]{0,31}`.
- Уровни `effort`: пусто, `low`, `medium`, `high`, `xhigh`, `max`. Непустой `effort` допустим только при `provider=grok`.
- Ключи `app_settings`: `ai.description.preset.active` (строка), `ai.description.enabled` (boolean, отсутствует = `true`).
- Префикс callback-данных диалога: `aip:`. Значения в payload всегда явные, никогда не toggle.
- Значения i18n не содержат апострофов (MessageFormat), ключи добавляются **в оба** бандла `modules/telegram/src/main/resources/messages_{ru,en}.properties`.
- Секреты (токены, `auth.json`) не логируются и не попадают в сообщения Telegram.
- Kotlin allopen через `kotlin-spring` применён ко всем модулям: `@Bean`-методы в `@AutoConfiguration` не требуют `open`.
- **Tasks 3–5 — одна единица деплоя.** После Task 3 агент всегда берёт `catalog.fallback()`
  (резолвер появляется в Task 4, трекер — в Task 5), поэтому промежуточные коммиты собираются и
  проходят тесты, но переключать пресеты на стенде ещё не позволяют. Выкатывать их по отдельности
  нельзя; мержится вся тройка.

### Матрица тестов, которую обещает дизайн

Раздел «Тестирование» дизайна называет проверки, которые легко потерять при разнесении по задачам.
Каждая обязана получить шаг с файлом, иначе обещание остаётся только в дизайне:

| Тест | Где | Почему нельзя потерять |
|---|---|---|
| резолюция один раз на вызов (переключение `InMemory` между попытками не меняет backend) | Task 4, `DefaultDescriptionAgentTest` | иначе retry может разъехаться по провайдерам |
| общий семафор на два пресета | Task 4, `DefaultDescriptionAgentTest` | семафор — свойство фичи, а не пресета |
| `warnOnce` однократен при повторных вызовах | Task 4, `ActivePresetResolverTest` | иначе лог засоряется одной строкой на каждую запись |
| два многопоточных сценария авторизации | Task 5, `ProviderAuthTrackerTest` | единственная проверка смысла замка |
| порядок карты (`containsExactly`) | Task 1, `DescriptionPresetsBindingTest` | на порядок опирается `fallbackId` |
| регистр и посторонние символы в ключе карты | Task 1, `DescriptionPresetsBindingTest` | искажение id иначе всплывёт в `callback_data` |
| legacy-`provider` в верхнем/смешанном регистре | Task 1 или 3 | обещание обратной совместимости |
| карта через bracket-форму и через окружение видна условию | Task 3, тест условия | иначе «карта есть, каталога нет» |
| whitespace-токен Claude (`isBlank()`) | Task 3, `ClaudeBackendFactoryTest` | покрытие уезжает вместе с удаляемым `ClaudeBackendValidationTest` |
| бин `DescriptionRuntimeSettings` — не in-memory | Task 6, тест в `core` | иначе выбор молча не переживает рестарт |
| ошибка чтения `app_settings` не теряет уведомление | Task 6, `RecordingProcessingFacadeTest` | инвариант фасада |
| callback подтверждается на каждом исходе | Task 8, `AiSettingsCallbackHandlerTest` | иначе висящий спиннер |
| метаданные команды (`ownerOnly`, `requiredRole`, уникальность `order`) | Task 8 | закрывает весь класс ошибок разом |

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

✅ Done — see commit(s): `4fcff86`, `1893439`
---

### Task 2: `model` и `effort` как параметры вызова

✅ Done — see commit(s): `8a8e99a`, `775cb1e`
---

### Task 3: Фабрики backend-ов, каталог пресетов и проводка

✅ Done (executed as 3a + 3b) — see commit(s): `ab67789`, `c5a65d3`, `9870226`
---

### Task 4: Рантайм-настройки и резолюция активного пресета

✅ Done — see commit(s): `f1556c6`, `0a991ff`
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
    fun `two presets of one model share the state`() {
        // grok-fast и grok-deep: разный effort, одна модель — одни учётные данные, одна область.
        tracker.onUnauthorized("grok:grok-4.6", unauthorized, "hint")
        tracker.onUnauthorized("grok:grok-4.6", unauthorized, "hint")

        assertEquals(1, events.size)
    }

    @Test
    fun `byok and oauth scopes of one provider do not share the state`() {
        // Ключевой сценарий: OAuth сломан, BYOK работает. При ключе по провайдеру успех BYOK
        // опубликовал бы RESTORED и показал весь grok здоровым, хотя auth.json протух.
        tracker.onUnauthorized("grok:grok-4.6", unauthorized, "hint")
        tracker.onSuccess("grok:codex-luna", "hint")

        assertEquals(ProviderAuthStates.Health.LOST, tracker.byScope().getValue("grok:grok-4.6"))
        assertEquals(ProviderAuthStates.Health.HEALTHY, tracker.byScope().getValue("grok:codex-luna"))
        assertEquals(listOf(DescriptionProviderAuthEvent.State.LOST), events.map { it.state })
    }

    @Test
    fun `providers are independent`() {
        tracker.onUnauthorized("grok", unauthorized, "hint")
        tracker.onUnauthorized("claude", unauthorized, "hint")

        assertEquals(listOf("grok", "claude"), events.map { it.provider })
        assertEquals(ProviderAuthStates.Health.LOST, tracker.byProvider().getValue("claude"))
    }

    // --- Ниже: два теста ПЕРЕНОСЯТСЯ живьём из DefaultDescriptionAgentTest
    // (`:279-313` и `:370-391`), с реальными потоками и CountDownLatch. Это единственные тесты,
    // проверяющие смысл существования замка; однопоточные сценарии выше их не заменяют, и без
    // них регрессия порядка LOST/RESTORED снова становится возможной. Дизайн обещает
    // «одно событие на переход при параллельных отказах» именно про них.

    @Test
    fun `a slow listener cannot reorder concurrent auth transitions`() {
        // перенести тело из DefaultDescriptionAgentTest.kt:279-313, заменив вызовы агента
        // на прямые tracker.onUnauthorized(...) / tracker.onSuccess(...)
    }

    @Test
    fun `concurrent Unauthorized failures publish a single LOST`() {
        // перенести тело из DefaultDescriptionAgentTest.kt:370-391: пять параллельных вызовов
        // onUnauthorized под runBlocking(Dispatchers.IO) → ровно одно событие
    }

    @Test
    fun `a slow listener on one provider does not delay the other`() {
        // новый: медленный слушатель на "grok" не задерживает публикацию события "claude" —
        // замок берётся на провайдера, а не глобально
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

В автоконфигурации добавить бин трекера **внутрь `PresetBeans`** (порядок объявления там не значим) и передать его в агента:

```kotlin
        @Bean
        open fun providerAuthTracker(eventPublisher: ApplicationEventPublisher): ProviderAuthTracker =
            ProviderAuthTracker(eventPublisher)
```

В `DefaultDescriptionAgentTest` сценарии про авторизацию переносятся в `ProviderAuthTrackerTest` — **вместе с телами, включая оба многопоточных**, а не заменяются однопоточными аналогами. В самом тесте агента остаются проверка «`Unauthorized` не повторяется» и тест `a throwing listener does not discard a successful description` (`:335-348`): это инвариант **агента**, а не трекера, и трекером он не покрывается. Хелпер создаёт агента с настоящим `ProviderAuthTracker(publisher)`.

- [ ] **Step 5: Запустить тесты модуля**

`./gradlew :frigate-analyzer-ai-description:test`
Ожидание: PASS.

- [ ] **Step 6: Коммит**

```bash
git add modules/ai-description/src
git commit -m "refactor(ai-description): track authorization per provider, not per agent" \
           -m "Claude-Session: <SESSION_URL>"
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
        // fail-open к дефолту ключа, дословно по образцу
        // TelegramNotificationServiceImpl.signalNotificationsGloballyEnabled (:262-275).
        // Гейт вызывается ПОСЛЕ saveProcessingResult, поэтому пробрасывать исключение нельзя:
        // запись уже помечена обработанной, и уведомление было бы потеряно без повтора.
        val descriptionsEnabled =
            try {
                runtimeSettings?.descriptionsEnabled() ?: true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) {
                    "Failed to read the AI description switch for $recordingId; failing open"
                }
                true
            }
        if (!descriptionsEnabled) {
            logger.debug { "AI descriptions switched off at runtime; skipping describe-job for $recordingId" }
            return null
        }
```

**Та же проверка повторяется внутри возвращаемого supplier-а, непосредственно перед `agent.describe(...)`**
(тем же fail-open-хелпером). Между сборкой supplier-а и его вызовом лежат очередь уведомлений и rate
limiter, поэтому одной проверки хватает лишь на «подействует со следующей записи», а кнопку жмут
тогда, когда что-то идёт не так прямо сейчас. С двумя точками гарантия формулируется одной фразой:
*выключение отменяет все описания, которые ещё не начались*. Стоимость — поиск в процессном кэше
`AppSettingsService` на пути, который вот-вот потратит секунды и деньги на вызов модели.

**Почему fail-open, а не чтение до `saveProcessingResult`.** В фасаде уже есть два разных
механизма, и этот флаг относится ко второму. Глобальный флаг уведомлений **решает, слать ли
уведомление вообще**, поэтому читается до сохранения и его отказ оставляет запись retryable
(`RecordingProcessingFacade.kt:49-57` и тест `:198`). Выключатель описаний решает лишь, обогащать
ли уведомление; блокировать основное уведомление из-за нечитаемого ключа необязательной фичи
неверно. Значение по умолчанию — `true`, как у самого ключа («отсутствует = true») и как у
signal-флага: нечитаемый ключ трактуется так же, как отсутствующий.

Расход при этом ограничен почти нулём: `AppSettingsServiceImpl` кэширует успешные чтения на всё
время жизни процесса, поэтому окно отказа — от старта до первого удачного чтения (и момент после
записи из `/ai`). Если же БД недоступна настолько, что чтение падает постоянно, то и
`saveProcessingResult` упадёт раньше.

Тест на fail-open обязателен: «чтение настроек бросает → supplier не null, уведомление уходит».

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
            // Стабы evaluate/saveProcessingResult уже стоят в @BeforeEach самого теста
            // (RecordingProcessingFacadeTest.kt:94-105); хелперов notifyDecision()/savedResult()
            // в нём нет, дублировать их здесь не нужно.
            coEvery { notificationDecisionService.isRecordingNotificationsGloballyEnabled() } returns true

            val supplier = captureSupplierDuring { f.processAndNotify(req) }

            assertNull(supplier)
        }

    @Test
    fun `the runtime switch on leaves the supplier in place`() =
        runTest {
            val (f, req) = facade(agent = mockk(relaxed = true), runtimeEnabled = true)
            coEvery { notificationDecisionService.isRecordingNotificationsGloballyEnabled() } returns true

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
           -m "Claude-Session: <SESSION_URL>"
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
- Consumes: `DescriptionPresets.all()`, `DescriptionPreset` (Task 3); `ActiveDescriptionPreset.storedId()` / `.effective()` из `api` (Task 4); `ProviderAuthStates.byProvider()`, `ProviderAuthStates.Health` (Task 5); `DescriptionRuntimeSettings.descriptionsEnabled()` (Task 4).
- Produces: `AiSettingsViewState(descriptionsEnabled, storedPresetId, effectivePresetId, presets, authByProvider, language)` с `hasMismatch`; `AiSettingsCallbacks.PREFIX/CLOSE/ON/OFF/SET_PREFIX`; `AiSettingsViewStateFactory.build(language)`; `AiSettingsMessageRenderer.render(state): RenderedAiSettings(text, keyboard)`; ключи i18n `ai.settings.*`.

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
    /** Что выбрал владелец. null = ключа нет, работает `default-preset`. */
    val storedPresetId: String?,
    /** Что реально применит следующий вызов. null = каталога нет: пресеты не объявлены. */
    val effectivePresetId: String?,
    val presets: List<DescriptionPreset>,
    val authByProvider: Map<String, ProviderAuthStates.Health>,
    val language: String,
) {
    /**
     * Сохранённый пресет существует, но работает не он: рендер печатает строку
     * `ai.settings.active.mismatch`. Без этого владелец не видит, что его выбор перекрыт,
     * а битый id живёт в `app_settings` вечно — кликать по fallback-у незачем.
     */
    val hasMismatch: Boolean
        get() = storedPresetId != null && effectivePresetId != null && storedPresetId != effectivePresetId
}
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
 *
 * Зависимости через [ObjectProvider] потому, что **пресеты могут быть не объявлены** — тогда
 * `PresetBeans` целиком не создаётся, а бот обязан стартовать. (Прежнее обоснование «при
 * `ai.description.enabled=false` этих бинов нет» неверно: сам этот класс условен только на
 * `application.telegram.enabled`, гейт на ai-флаг стоит лишь у `AiSettingsCommandHandler`.)
 *
 * Зависимость только на `api`: `core.ActivePresetResolver` за пределы модуля не выходит.
 */
@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class AiSettingsViewStateFactory(
    private val presetsProvider: ObjectProvider<DescriptionPresets>,
    private val activePresetProvider: ObjectProvider<ActiveDescriptionPreset>,
    private val runtimeSettingsProvider: ObjectProvider<DescriptionRuntimeSettings>,
    private val authStatesProvider: ObjectProvider<ProviderAuthStates>,
) {
    suspend fun build(language: String): AiSettingsViewState {
        val presets = presetsProvider.getIfAvailable()?.all().orEmpty()
        val active = activePresetProvider.getIfAvailable()
        return AiSettingsViewState(
            descriptionsEnabled = runtimeSettingsProvider.getIfAvailable()?.descriptionsEnabled() ?: true,
            storedPresetId = active?.storedId(),
            effectivePresetId = if (presets.isEmpty()) null else active?.effective()?.id,
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
        val active = state.presets.firstOrNull { it.id == state.effectivePresetId }
        val stored = state.storedPresetId?.let { id -> state.presets.firstOrNull { it.id == id } }
        return buildString {
            appendLine(msg.get("ai.settings.title", lang))
            appendLine(
                msg.get(
                    "ai.settings.state",
                    lang,
                    msg.get(if (state.descriptionsEnabled) "ai.settings.state.on" else "ai.settings.state.off", lang),
                ),
            )
            // Ранний выход ТОЛЬКО при пустом каталоге. Раньше он срабатывал и на
            // `active == null` при непустом списке (резолвер недоступен, ключ пуст) — и уносил
            // с собой весь блок авторизации, то есть ровно ту диагностику, ради которой экран
            // и открывают.
            if (state.presets.isEmpty()) {
                appendLine(msg.get("ai.settings.active.none", lang))
                return@buildString
            }
            if (active == null) {
                appendLine(msg.get("ai.settings.active.none", lang))
            } else {
                appendLine(
                    msg.get("ai.settings.active", lang, active.id, active.provider, active.model, effortLabel(active)),
                )
            }
            if (state.hasMismatch) {
                appendLine(
                    msg.get(
                        "ai.settings.active.mismatch",
                        lang,
                        state.storedPresetId.orEmpty(),
                        stored?.unavailableReason ?: msg.get("ai.settings.active.none", lang),
                        state.effectivePresetId.orEmpty(),
                    ),
                )
            }
            appendLine()
            state.presets.map { it.provider }.distinct().forEach { provider ->
                appendLine(providerLine(state, provider, lang))
            }
            // Состояние меняется только на вызове описания: после `grok login` здесь будет 🔴
            // до следующей записи с детекциями, после рестарта — ⚪ при протухшем auth.json.
            // Оговорка обязательна, иначе экран читается как проверка «сейчас».
            appendLine(msg.get("ai.settings.auth.note", lang))
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
ai.settings.active.mismatch=⚠️ Выбран {0} ({1}) — работает {2}. Выбор сохранён и применится снова, когда пресет станет доступен.
ai.settings.auth.healthy=🟢 {0} — авторизация в порядке
ai.settings.auth.lost=🔴 {0} — отказ авторизации
ai.settings.auth.unknown=⚪ {0} — ещё не вызывался
ai.settings.auth.unavailable=⚠️ {0} — не настроен: {1}
ai.settings.auth.note=Состояние показано на момент последнего вызова описания.
ai.settings.button.enable=Включить описания
ai.settings.button.disable=Выключить описания
ai.settings.button.close=Закрыть
ai.settings.alert.unavailable=Пресет недоступен: {0}
```

`messages_en.properties` — те же ключи с английскими значениями (`AI descriptions`, `State: {0}`, `enabled`, `disabled`, `Active preset: {0} ({1} / {2} / {3})`, `No presets configured`, `⚠️ Selected {0} ({1}) — running {2}. The choice is kept and applies again once the preset becomes available.`, `Auth state is shown as of the last description call.`, `{0} credentials work`, `{0} rejected the credentials`, `{0} not called yet`, `{0} not configured: {1}`, `Enable descriptions`, `Disable descriptions`, `Close`, `Preset unavailable: {0}`).

**Ключей `ai.settings.alert.switched` / `.enabled` / `.disabled` нет сознательно.** Ответ на коллбэк
уходит ДО записи (Task 8), поэтому тост «Активен пресет X» был бы обещанием, данным до факта:
упади запись — и владелец получил бы подтверждение операции, которой не произошло. Подтверждает
успех перерисовка (переезд `✅`), как у переключателей `/notifications`. Единственный оставшийся
ключ алерта — `.unavailable`, и он относится к исходу, который записи не требует.
`MessageKeyParityTest` сверяет бандлы между собой, а не с использованием, поэтому мёртвые ключи он
бы не поймал.

- [ ] **Step 5: Запустить тест рендера**

`./gradlew :frigate-analyzer-telegram:test --tests ru.zinin.frigate.analyzer.telegram.bot.handler.aisettings.AiSettingsMessageRendererTest`
Ожидание: PASS.

- [ ] **Step 6: Коммит**

```bash
git add modules/telegram/src
git commit -m "feat(telegram): render the AI description settings screen" \
           -m "Claude-Session: <SESSION_URL>"
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
- Produces: `AiSettingsCallbackHandler.DispatchOutcome` (`RERENDER`, `CLOSE`, `UNAUTHORIZED`, `IGNORE`, `ALERT`), `AiSettingsCallbackHandler.Dispatched(outcome, alertKey, alertArgument)`; **`dispatch` разделён на две фазы** — `AiSettingsCallbackHandler.classify(data, isOwner): Dispatched` (чистая, без ввода-вывода: разбирает payload, сверяется с каталогом, отдаёт исход и текст алерта) и `AiSettingsCallbackHandler.apply(data, changedBy)` (запись в `DescriptionRuntimeSettings`, вызывается только для исходов, требующих записи). Регистрация коллбэков отвечает по результату `classify`, затем зовёт `apply` и перерисовывает; команда `/ai` с `ownerOnly = true` и `order = 9`.

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

    /**
     * Видимость команды определяет `ownerOnly`, а НЕ `requiredRole`:
     * `FrigateAnalyzerBot.registerDefaultCommands()` регистрирует в `BotCommandScopeDefault` всё,
     * что `filterNot { it.ownerOnly }`, а `HelpCommandHandler` печатает такие команды в общем
     * списке. Без этой строки `/ai` попала бы в меню каждого пользователя и в общий раздел
     * `/help`, отбиваясь на клике `common.error.owner.only`. Все три owner-команды в дереве
     * ставят оба поля.
     */
    override val ownerOnly: Boolean = true

    // 8 занят `/status` (modules/core/.../StatusCommandHandler.kt:31). Раскладка:
    // start=1, help=2, export=3, timezone=4, version=5, language=6, notifications=7,
    // status=8, adduser=10, removeuser=11, users=12 — свободен 9.
    override val order: Int = 9

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
                    // Ровно один ответ на коллбэк, и он уходит ДО записи в БД — как в блоке
                    // nfs: (:188-195). Кажущееся противоречие "алерту нужен результат записи"
                    // ложное: исходы, требующие алерта (ALERT/IGNORE/UNAUTHORIZED), разрешаются
                    // из каталога и роли БЕЗ обращения к БД, а исходы с записью (RERENDER после
                    // set/on/off) содержательного текста не несут — их подтверждает перерисовка,
                    // которая идёт после записи и потому не может соврать: упавшая запись оставит
                    // на экране прежний активный пресет.
                    //
                    // Это не косметика: дефолтный markerFactory сериализует коллбэки одного
                    // пользователя, поэтому обработчик, зависший на медленной БД, блокирует
                    // СЛЕДУЮЩИЙ клик владельца, а не только держит спиннер. Отсюда же требование
                    // отвечать в каждой ранней ветке, включая отсутствие любого ObjectProvider.
                    try {
                        bot.answer(
                            callback,
                            text =
                                dispatched.alertKey?.let { key ->
                                    msg.get(key, lang, dispatched.alertArgument.orEmpty())
                                },
                            // Модалка, а не тост: причина недоступности пресета — единственное
                            // место, где владелец её узнаёт, а тост в углу легко пропустить.
                            showAlert = dispatched.outcome == AiSettingsCallbackHandler.DispatchOutcome.ALERT,
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
Ожидание: PASS. Отдельный тест на паритет ключей писать не нужно: `MessageKeyParityTest` уже проверяет, что множества ключей обоих бандлов совпадают, и новые `ai.settings.*` попадут под него автоматически.

- [ ] **Step 7: Коммит**

```bash
git add modules/telegram/src
git commit -m "feat(telegram): switch the AI description preset from an owner-only /ai dialog" \
           -m "Claude-Session: <SESSION_URL>"
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

  # ВАЖНО: сам по себе непустой GROK_HOME признаком не является — в docker-compose.yml он задан
  # ВСЕГДА (:35) и том монтируется всегда (:27). Гейт по нему выдал бы WARN про отсутствующий
  # auth.json каждому claude-only деплою, а дизайн обещает "WARN только на сломанное".
  grok_intended=false
  if [ -f "${GROK_HOME:-}/auth.json" ] || [ -f "${GROK_HOME:-}/config.toml" ] || \
     [ "$(printf '%s' "${APP_AI_DESCRIPTION_PROVIDER:-}" | tr '[:upper:]' '[:lower:]')" = "grok" ]; then
      grok_intended=true
  fi

  if [ "$grok_intended" = true ] && [ -n "${GROK_HOME:-}" ]; then
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
  elif [ "$grok_intended" = true ]; then
      echo "WARN: GROK_HOME is not set; grok presets would fall back to the ephemeral default under the temp folder, point GROK_HOME at a mounted volume." >&2
  else
      echo "INFO: no grok credentials found under GROK_HOME and APP_AI_DESCRIPTION_PROVIDER is not 'grok'; skipping grok checks."
  fi

  # Две диагностики, которые нельзя терять вместе со старым `case`:
  # 1) самый частый misconfig — включённая фича без единого признака провайдера. Legacy-синтез
  #    одного claude-пресета без токена по-прежнему валит старт по правилу "ноль годных", и это
  #    не должно выглядеть мягким INFO плюс падение JVM.
  if [ -z "${CLAUDE_CODE_OAUTH_TOKEN:-}" ] && [ -z "${ANTHROPIC_AUTH_TOKEN:-}" ] && [ "$grok_intended" != true ]; then
      echo "WARN: APP_AI_DESCRIPTION_ENABLED=true but neither a Claude token nor grok credentials were found; startup will fail if the only declared preset is unusable." >&2
  fi
  # 2) опечатка в legacy-переменной остаётся мягкой на уровне приложения, поэтому шелл — последнее
  #    место, где её ещё видно рядом с причиной.
  case "$(printf '%s' "${APP_AI_DESCRIPTION_PROVIDER:-}" | tr '[:upper:]' '[:lower:]')" in
      ''|claude|grok) ;;
      *) echo "WARN: unknown APP_AI_DESCRIPTION_PROVIDER='${APP_AI_DESCRIPTION_PROVIDER}'; it is ignored when presets are declared, and yields no preset otherwise." >&2 ;;
  esac

  echo "INFO: the active preset is chosen in application-docker.yaml and in the /ai dialog; this check only reports which providers look usable."
```

- [ ] **Step 2: Добавить пример пресетов в `application-docker.yaml.example`**

Блок **закомментирован целиком**: пример содержит живые значения, профильный файл приоритетнее
базового `application.yaml`, и копирование поверх работающего claude-деплоя иначе молча
переключило бы его на `grok-fast`. `default-preset` — через плейсхолдер, иначе литерал делает
`APP_AI_DESCRIPTION_DEFAULT_PRESET` из `.env.example` мёртвой переменной.

```yaml
  # AI-описания: сколько угодно пресетов, активный выбирается владельцем в /ai и переживает
  # рестарт. effort только у grok; пустой effort = флаг --effort не передаётся.
  #
  # ВНИМАНИЕ: раскомментированный блок presets ОТКЛЮЧАЕТ APP_AI_DESCRIPTION_PROVIDER,
  # GROK_MODEL, GROK_EFFORT и CLAUDE_MODEL из .env — они применяются только при пустой карте.
  #
  # ai:
  #   description:
  #     default-preset: ${APP_AI_DESCRIPTION_DEFAULT_PRESET:grok-fast}
  #     presets:
  #       grok-fast:   { provider: grok,   model: grok-4.6,   effort: low }
  #       grok-deep:   { provider: grok,   model: grok-4.6,   effort: xhigh }
  #       claude-opus: { provider: claude, model: opus }
```

Заодно в `docker/deploy/docker-compose.yml` поправить комментарий тома `grok-home` («Grok Build
home (provider=grok)», строка 25) — после перехода на пресеты он перестаёт быть правдой.

- [ ] **Step 3: Обновить `.env.example`**

Пометить `APP_AI_DESCRIPTION_PROVIDER`, `GROK_MODEL`, `GROK_EFFORT`, `CLAUDE_MODEL` как путь одного пресета, который применяется, только когда `presets` пуст, и указать на `application-docker.yaml`. Добавить `APP_AI_DESCRIPTION_DEFAULT_PRESET` **с пустым значением** и комментарием, что оно действует только при объявленной карте.

**Смежная правка в Task 1:** валидация `default-preset` при **пустой** карте ослабляется с
`require` до WARN. Иначе оператор, скопировавший `.env.example` со значением до того, как объявил
карту в yaml, получает отказ старта («default-preset 'grok-fast' is not declared in presets: »), и
миграция «сначала env, потом yaml» становится невозможной. При непустой карте проверка остаётся
строгой.

- [ ] **Step 3a: Логировать значение, а не только факт**

Переключение пресета и стартовая строка каталога пишутся на INFO **со значением** —
`id (provider/model/effort)`. Стартовая строка вдобавок называет **источник** активного пресета:
`active preset 'grok-deep' (grok/grok-4.6/xhigh) from app_settings, overriding default-preset='grok-fast'`
либо `… from default-preset`. Без этого `default-preset` после первого клика владельца перестаёт
действовать молча, и оператор, поправивший его в yaml и перезапустивший контейнер, не получает
никакого сигнала (`default-preset` действует только до первого явного выбора). Сегодня на INFO уходит лишь `AppSettings: 'ai.description.preset.active' set by owner`,
а само значение на DEBUG, чего для вопроса «какая модель сейчас работает» мало. Стартовая строка
заодно возвращает правду формулировке `.claude/rules/ai-description.md` про «logs model and effort
at INFO once at startup»: её источник, INFO-строка в `GrokBackend.init`, удаляется в Task 3.

- [ ] **Step 4: Обновить README**

В список команд добавить `/ai` (только владелец). В разделе AI description — абзац про пресеты с примером yaml, про переключение из `/ai`, про то, что выбор переживает рестарт.

Отдельным абзацем — правило потолка: **`APP_AI_DESCRIPTION_TIMEOUT` ставится под самый медленный
объявленный пресет**, а не под типичный. `grok-4.6 xhigh` занимает ~48 с при дефолтных 60 с, и
бюджета на повтор не остаётся: `TRANSPORT_RETRY_MIN_BUDGET` (10 с плюс пауза 5 с) не запустится, а
`INVALID_RESPONSE_RETRY_MIN_BUDGET` (5 с) запустится и упрётся в `withTimeout`, дав `Timeout` вместо
`InvalidResponse`. Потолок — момент отказа от ожидания, а не длительность, поэтому 120 с не
замедляют `grok-fast` (9 с); плата только в том, что зависание обнаруживается позже. Выбирается он
один раз, вместе с объявлением пресета, поэтому цикл переключения рестарта не требует.

Там же — про вместимость: два вызова на `xhigh` занимают оба слота при `maxConcurrent=2` на ~48 с,
и третий уходит по `queueTimeout` в fallback.

**Смежная правка в Task 1:** WARN на старте, если у пресета `effort ∈ {xhigh, max}`, а
`common.timeout` не оставляет бюджета повтора —
`preset 'grok-deep': effort=xhigh with timeout=60s leaves no retry budget; consider APP_AI_DESCRIPTION_TIMEOUT=120s`.
**Смежная правка в Task 7:** такие пресеты получают отметку в строке экрана.

- [ ] **Step 5: Обновить правила**

`.claude/rules/ai-description.md`: в `paths:` добавить `**/handler/aisettings/**`; таблицу слоёв дополнить строками про фабрики, каталог, резолвер и трекер; описать пресеты, резолюцию, рантайм-выключатель и диалог. `.claude/rules/configuration.md`: свойства `presets.*`, `default-preset`, ключи `app_settings`, пометка legacy у `APP_AI_DESCRIPTION_PROVIDER`. `.claude/rules/database.md`: два новых ключа `app_settings`, оговорка про кэш (прямой SQL невиден до рестарта) и явная строка о том, что **фича рассчитана на один экземпляр приложения**: запись инвалидирует только собственный кэш процесса, поэтому при двух контейнерах выбор пресета и рантайм-выключатель разъедутся и будут расходиться до рестарта. `CLAUDE.md`: строка модуля `ai-description` и ключевой паттерн упоминают пресеты и `/ai`.

- [ ] **Step 6: Коммит**

```bash
git add docker/deploy README.md CLAUDE.md .claude/rules
git commit -m "docs(ai-description): document presets, the /ai dialog and the runtime switch" \
           -m "Claude-Session: <SESSION_URL>"
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

**Выкатка в два шага, оба до мержа.** В диф PR идёт только закомментированный пример; живой
`application-docker.yaml` на стенде — развёрнутая копия, и её правка это действие выкатки, а не
изменение кода. Порядок содержателен:

1. **Сначала деплой с пустой картой.** Работает синтезированный legacy-пресет, то есть ровно та
   конфигурация, в которой окажется каждый существующий деплой. Убедиться, что поведение не
   отличается от сегодняшнего: описания приходят, модель в DEBUG-строке прежняя, новых WARN нет.
   Это главная проверка фичи — её собственное ограничение гласит «существующие деплои продолжают
   работать без правок».
2. **Затем объявить пресеты на стенде** и прогнать проверки ниже.

Раскомментировав блок сразу, шаг 1 провести уже нельзя: в одну выкатку попадут и новый код, и новая
конфигурация, и регрессию описаний будет не к чему отнести.

```bash
# ШАГ 2: в application-docker.yaml объявить три пресета из Task 9 Step 2
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
           -m "Claude-Session: <SESSION_URL>"
```

- [ ] **Step 5: Дописать результаты в описание PR #44**

---

## Self-review

- **Покрытие спеки.** Конфигурация и валидация: Task 1. Параметризация модели: Task 2. Фабрики, каталог, резолюция пригодности, автоконфигурация, sanity checker: Task 3. SPI рантайм-настроек, резолвер, резолюция на вызов: Task 4. Авторизация по провайдеру и `UNKNOWN`: Task 5. `app_settings`, рантайм-выключатель, гейт фасада: Task 6. Экран, i18n: Task 7. Команда, коллбэки, регистрация, подсказка `/ai` в алерте: Task 8. Энтрипойнт, yaml-пример, `.env.example`, README, правила: Task 9. Сборка, ревью, живая проверка: Task 10.
- **Плейсхолдеры.** Каждый шаг с кодом несёт код; «similar to Task N» нет; тексты i18n и argv приведены целиком.
- **Согласованность типов.** `DescriptionProperties.Preset(provider, model, effort)` — Tasks 1, 3; `DescriptionBackendFactory.availability()/create(preset)` — Task 3, используется в Tasks 3 и 7 через каталог; `DescriptionPresetCatalog.Entry(view, backend)`, `.byId`, `.fallback()`, `.fallbackId` — Tasks 3, 4; `ActivePresetResolver.resolve()/activePresetId()` — Tasks 4, 7; `ProviderAuthStates.Health` — Tasks 5, 7; `DescriptionRuntimeSettings` — Tasks 4, 6, 7, 8; `AiSettingsCallbacks` объявляется в Task 7 и используется рендером, диспетчером и регистрацией коллбэков в Task 8.
- **Известный порядок.** Конструктор `DefaultDescriptionAgent` меняется трижды (Tasks 3, 4, 5) — это осознанно: каждая правка оставляет модуль собранным и покрытым тестами, а сливать их в одну задачу значит сливать три независимо ревьюируемых изменения.
