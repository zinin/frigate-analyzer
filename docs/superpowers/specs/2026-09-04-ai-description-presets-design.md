# Пресеты AI-описаний и переключение из Telegram

**Дата:** 2026-09-04
**Ветка:** `feature/grok-description-provider`
**Предыстория:** модуль `ai-description` получил второго провайдера (Grok Build, PR #44). Живые
замеры показали, что интересные различия лежат на уровне модели, а не провайдера: grok-4.6 `low`
9 с / $0.0036, тот же grok-4.6 `xhigh` 48 с / $0.0047, BYOK `codex-luna` 8 с, claude `opus`
17.5 с / $0.26. Сменить модель сегодня можно только правкой `.env` и рестартом.

## Задача

Дать конфигу держать несколько конфигураций описаний сразу и позволить владельцу переключаться
между ними из Telegram без рестарта.

Ограничения:

- `ai-description` не зависит ни от одного модуля проекта; БД ему недоступна напрямую.
- Контракт наружу не меняется: тот же `DescriptionAgent`, тот же rate limiter, та же правка
  сообщения в Telegram.
- Выбор владельца переживает рестарт контейнера.
- Существующие деплои с `APP_AI_DESCRIPTION_PROVIDER` в `.env` продолжают работать без правок.
- Fallback-цепочки между пресетами нет: активен ровно один, переключение только руками.

## Что меняется для владельца

| | Сейчас | После |
|---|---|---|
| Конфигураций одновременно | одна | сколько угодно, карта `presets` |
| Смена модели или effort | правка `.env` и рестарт | кнопка в `/ai` |
| Выключить описания | `APP_AI_DESCRIPTION_ENABLED=false` и рестарт | кнопка в `/ai` |
| Состояние авторизации провайдера | видно только в момент отказа | видно по запросу в `/ai` |
| Отказ авторизации | сообщение с командой входа | то же плюс подсказка переключить пресет |

## Принятые решения

**Пресет это `provider` + `model` + `effort`, а не один только провайдер.** Переключения, ради
которых всё затевается, модельные: grok-4.6 `low` против `xhigh` против BYOK `codex-luna` — всё это
один провайдер `grok`. Провайдер-уровня для них не хватило бы. Дальше `model` расширять не нужно:
BYOK-эндпоинт и ключ уже привязаны к имени модели в `config.toml` Grok, поэтому одно поле `model`
выбирает и модель, и адрес, и ключ.

**Инфраструктура остаётся в секциях провайдера.** Пути к CLI, `GROK_HOME`, working-dir, токены,
прокси, `pass-through-env`, `max-buffer-size` пресет не переопределяет. Это свойства машины и
установленного бинарника, а не выбора модели; вынос их в пресет открыл бы путь секретам в
пресетные логи и в диалог Telegram.

Одно следствие этого решения надо назвать вслух: **`anthropic.model-override` (`ANTHROPIC_MODEL`)
вытесняет модель пресета.** `ClaudeAsyncClientFactory` ставит модель только при пустом override, а
при непустом экспортирует `ANTHROPIC_MODEL` в окружение CLI. Пока провайдер один, это безобидно; с
пресетами два claude-пресета `opus` и `sonnet` при заданном override отправляют один и тот же
запрос. Переменная остаётся единственной дорогой к Anthropic-совместимым эндпоинтам (пресетные
`base-url` отвергнуты выше), поэтому её семантика не меняется и старт не валится — это сломало бы
работающие деплои. Вместо этого: WARN на старте со списком затронутых пресетов, а `/ai` показывает
`effectiveModel`, которую фабрика заполняет с учётом override. Оператор видит, что кнопки ведут в
одно место, и решает сам.

**Пресеты живут в yaml, а не в env.** Формально Spring биндит карты и из окружения
(`APP_AI_DESCRIPTION_PRESETS_GROKFAST_MODEL` даёт ключ `grokfast`), но ключ при этом теряет регистр
и не может содержать `-`: id вида `grok-fast` из `.env` недостижим, а искажение ключа всплывёт
только в `callback_data` и в логах. В проекте карты уже настраиваются в `application-docker.yaml` (`detect-servers`),
и файл смонтирован в контейнер томом. Плата: `docker-entrypoint.sh` пресетов не видит, поэтому его
проверки перестают говорить о выбранном провайдере и начинают говорить о доступных.

**Backend-ы строятся фабриками, а не объявляются `@Component`-ами.** Их количество задаёт карта,
поэтому статические `@ConditionalOnProperty(provider=…)` больше не работают: нельзя объявить
`@Bean` в цикле. Провайдер отдаёт `DescriptionBackendFactory`, автоконфигурация строит по
экземпляру backend-а на пресет. `model` и `effort` при этом уезжают из свойств в параметры вызова
`GrokCommandBuilder.build(...)` и `ClaudeInvoker.invoke(...)`.

**Агент остаётся один, семафор глобальный, состояние авторизации — по области учётных данных.**
`maxConcurrent` ограничивает нагрузку на машину и расход денег — это свойство всей фичи, а не
пресета. Авторизация же принадлежит не провайдеру, а **набору учётных данных**, и это не одно и то
же: `grok-fast` и `grok-deep` действительно делят `auth.json`, но `byok-luna` (`codex-luna`) ходит
по собственному ключу из `config.toml`, и `GrokExceptionMapper` уже классифицирует `invalid api key`
как `Unauthorized`. При ключе по провайдеру получается ложь: OAuth-пресет ловит отказ →
`grok=LOST`; владелец переключается на рабочий BYOK → успех публикует `RESTORED`; экран показывает
весь Grok здоровым, хотя OAuth по-прежнему сломан. И протухшему BYOK-ключу предлагается
`grok login`, который его не чинит.

Область вычисляется **без нового знания**: приложение не разбирает `config.toml` и не должно
начинать. Достаточно того, что одна модель — одни учётные данные по построению. Поэтому
`authScopeId` это `claude` для claude (токен один на все его модели) и `grok:<model>` для grok.
Мотивирующий случай сохраняется точно: `grok-fast` и `grok-deep` делят модель `grok-4.6`, значит и
область, и одно событие на двоих. Расходятся только разные модели, где общность учётных данных и
так не гарантирована; плата — два события вместо одного, если оба OAuth-пресета на разных моделях
получат отказ.

**Активный пресет хранится в `app_settings` и читается на каждый вызов.** Ровно так живут
глобальные флаги `/notifications`. Чтение дёшево: `AppSettingsService` кэширует значение на процесс
и сбрасывает кэш только на собственной записи. Новых миграций не нужно — таблица есть, отсутствие
ключа означает дефолт.

**Негодный пресет не валит старт, ноль годных валит.** Сегодня `ClaudeBackend.init` роняет
приложение без `CLAUDE_CODE_OAUTH_TOKEN`, и это правильно, пока провайдер один и он выбран. С
пресетами стенд, где живёт только Grok, не должен падать из-за перечисленного claude-пресета:
такой пресет помечается недоступным и не выбирается. Fail-fast сохраняется для случая «всё
сконфигурированное сломано».

**Пустая конфигурация остаётся мягкой.** Если карта пуста и legacy-провайдер неизвестен, пресетов
нет вовсе: агента нет, `DescriptionAgentSanityChecker` пишет WARN, приложение работает и шлёт
уведомления без описаний — как сегодня при опечатке в `APP_AI_DESCRIPTION_PROVIDER`. Явная карта,
наоборот, валидируется строго: `provider` вне набора `claude|grok` это ошибка старта.

## Конфигурация

Новые свойства префикса `application.ai.description`:

| Свойство | Env | Default | Валидация |
|---|---|---|---|
| `presets.<id>.provider` | — | — | `claude` или `grok` |
| `presets.<id>.model` | — | — | `@NotBlank` |
| `presets.<id>.effort` | — | пусто | пусто, либо `low\|medium\|high\|xhigh\|max`; непустой при `provider=claude` — ошибка старта |
| `default-preset` | `APP_AI_DESCRIPTION_DEFAULT_PRESET` | пусто | пусто, либо существующий ключ карты (при пустой карте — WARN, а не отказ старта) |

`default-preset` действует **до первого явного выбора владельца** и после него на что-либо влиять
перестаёт: сохранённый ключ приоритетнее. Кнопки «вернуть к конфигу» нет сознательно — экран
владельца и так показывает активный пресет по имени, а оператору источник называет стартовая
INFO-строка каталога (`active preset 'grok-deep' from app_settings, overriding default-preset='grok-fast'`).
Смена выбора — один клик; постоянное место на экране под операцию, которая заменяется кликом,
не тратится.

Ключ карты (id пресета) — `[a-z0-9][a-z0-9-]{0,31}`: он едет в `callback_data`, у которого
64 байта на всё.

```yaml
application:
  ai:
    description:
      default-preset: ${APP_AI_DESCRIPTION_DEFAULT_PRESET:}
      presets:
        grok-fast:   { provider: grok,   model: grok-4.6,   effort: low }
        grok-deep:   { provider: grok,   model: grok-4.6,   effort: xhigh }
        byok-luna:   { provider: grok,   model: codex-luna, effort: "" }
        claude-opus: { provider: claude, model: opus }
```

Реальные пресеты объявляются в `application-docker.yaml` рядом с `detect-servers`; в
`modules/core/src/main/resources/application.yaml` карта пуста.

**Legacy-путь.** Пустая карта плюс известный `application.ai.description.provider` дают один
синтезированный пресет: `id` равен значению `provider`, `model` и `effort` берутся из секции
провайдера (`claude.model`, `grok.model`, `grok.effort`). Существующий `.env` работает без правок.

Значение legacy-`provider` перед сравнением нормализуется — `trim().lowercase()`. Сегодняшний
`@ConditionalOnProperty(havingValue = "claude")` сравнивает **без учёта регистра**, поэтому
работающий деплой с `APP_AI_DESCRIPTION_PROVIDER=CLAUDE` активирует Claude; регистрозависимая
проверка в новом коде тихо оставила бы такой деплой без агента и нарушила бы обещание «существующие
деплои работают без правок». Тест с mixed-case значением это фиксирует.

При непустой карте `APP_AI_DESCRIPTION_PROVIDER`, `GROK_MODEL`, `GROK_EFFORT` и `CLAUDE_MODEL` не
используются; секции провайдеров продолжают биндиться всегда, поэтому их значения обязаны
оставаться валидными. Непустой legacy-`provider` при объявленной карте даёт WARN
«`application.ai.description.provider='gemini'` ignored: presets are declared» — иначе опечатка в
переменной, которая перестала действовать, остаётся невидимой.

Ключи `app_settings`:

| Ключ | Тип | Отсутствует | Пишет |
|---|---|---|---|
| `ai.description.preset.active` | строка, id пресета | резолвер уходит к дефолту | `/ai`, `updatedBy` = username владельца |
| `ai.description.enabled` | boolean | `true` | `/ai` |

## Модуль `ai-description`

```
ai/description/
  api/      DescriptionAgent, DescriptionRequest, DescriptionResult, DescriptionException,
            TempFileWriter, DescriptionProviderAuthEvent,
            DescriptionPreset, DescriptionPresets, ProviderAuthStates,
            ActiveDescriptionPreset, DescriptionRuntimeSettings     контракт наружу
  core/     DescriptionBackend, DescriptionBackendFactory, DescriptionPresetCatalog,
            ActivePresetResolver, ProviderAuthTracker,
            DefaultDescriptionAgent, ResultNormalizer, LanguageNames,
            JsonBlockExtractor, FrameDownscaler                     общее ядро
  claude/   ClaudeBackend, ClaudeBackendFactory и Claude-специфика
  grok/     GrokBackend, GrokBackendFactory и Grok-специфика
  config/   AiDescriptionAutoConfiguration, DescriptionProperties, ClaudeProperties,
            GrokProperties, DescriptionAgentSanityChecker
  ratelimit/ DescriptionRateLimiter                                 без изменений
```

### Новое в `api/`

```kotlin
data class DescriptionPreset(
    val id: String,
    val provider: String,
    /** Модель, объявленная в конфиге. */
    val model: String,
    /**
     * Модель, которая реально уйдёт в запрос. Отличается от [model], когда её вытесняет
     * `anthropic.model-override` (`ANTHROPIC_MODEL`). Заполняется фабрикой — иначе `/ai` рисует
     * то, чего не будет.
     */
    val effectiveModel: String,
    val effort: String,            // пусто = флаг не передаётся
    val unavailableReason: String?, // null = пресет годен
)

interface DescriptionPresets {
    fun all(): List<DescriptionPreset>   // порядок объявления в yaml
}

/**
 * Активный пресет для потребителей за пределами модуля. Два метода, а не один: экран обязан
 * различать выбор владельца и то, что реально работает.
 */
interface ActiveDescriptionPreset {
    /** Что выбрал владелец; null = ключа нет или он пуст. Резолюции не делает. */
    suspend fun storedId(): String?
    /** Что применит следующий вызов describe: сохранённый, если годен, иначе fallback. */
    suspend fun effective(): DescriptionPreset
}

interface ProviderAuthStates {
    enum class Health { UNKNOWN, HEALTHY, LOST }

    /**
     * Ключ — `authScopeId`, область учётных данных, а не провайдер: `claude`, `grok:grok-4.6`,
     * `grok:codex-luna`. См. решение про авторизацию выше.
     */
    fun byScope(): Map<String, Health>
}

/** Реализуется в `core` поверх `AppSettingsService`; шов такой же, как у `TempFileWriter`. */
interface DescriptionRuntimeSettings {
    suspend fun activePresetId(): String?
    suspend fun setActivePresetId(id: String, changedBy: String?)
    suspend fun descriptionsEnabled(): Boolean
    suspend fun setDescriptionsEnabled(value: Boolean, changedBy: String?)
}
```

### Фабрики backend-ов

```kotlin
interface DescriptionBackendFactory {
    val providerId: String
    /** Пригодность провайдера целиком; вычисляется один раз на старте. */
    fun availability(): Availability
    fun create(preset: DescriptionProperties.Preset): DescriptionBackend

    sealed interface Availability {
        object Available : Availability
        data class Unavailable(val reason: String) : Availability
    }
}
```

`ClaudeBackendFactory` возвращает `Unavailable("no CLAUDE_CODE_OAUTH_TOKEN or ANTHROPIC_AUTH_TOKEN")`
вместо сегодняшнего `check()` в `ClaudeBackend.init`; проверка исполняемости CLI остаётся WARN-ом,
как сейчас. `GrokBackendFactory` всегда `Available`: отсутствие `auth.json` не значит неработоспособность,
BYOK-модель ходит по собственному ключу. Проверки каталогов и WARN про `auth.json` переезжают из
`GrokBackend.init` в фабрику, чтобы не повторяться на каждый grok-пресет.

**Фабрики строго пассивны в конструкторе; всё окружение осматривается в `availability()`.** Это не
стилистика, а следствие снятия `@ConditionalOnProperty(provider=…)`: фабрики теперь создаются при
любом `enabled=true`, и любой побочный эффект в их конструкторе выполняется на деплое, где пресетов
этого провайдера нет вовсе. Три конкретных следствия, которых так не будет:

- `GrokHomeSweeper` ежечасно удаляет содержимое `GROK_HOME/sessions/` и `logs/`. Его собственный
  KDoc исходит из того, что приложение — единственный пользователь этого каталога; на claude-only
  стенде допущение перестаёт быть верным, а `GROK_HOME` в compose задан **всегда** и том монтируется
  всегда. Sweeper получает `ObjectProvider<DescriptionPresets>` и молчит, когда grok-пресетов нет.
- `GrokBackendFactory` создаёт `GROK_HOME` и working-dir и бросает `IllegalStateException` при
  неудаче. Из конструктора это убивает контекст **раньше** сборки каталога — то есть claude-деплой с
  полностью годными claude-пресетами не стартует из-за чужого каталога, вопреки правилу «негодный
  пресет помечается, старт падает только когда не годен ни один». Превратить отказ в `⚠️` из
  конструктора невозможно: исключение приходит до того, как каталог начнёт спрашивать.
- Два WARN про grok на claude-деплое и симметричный WARN про claude CLI на grok-only.

`DescriptionPresetCatalogBuilder` вызывает `availability()` **только для провайдеров, встречающихся
хотя бы в одном объявленном пресете**, а результат мемоизируется (см. `KNOWN_PROVIDERS` и
`availability()` в разделе про автоконфигурацию). Провайдер, которого нет ни в одном пресете, не
трогает ни файловую систему, ни PATH и не пишет ни строки.

Коллаборанты остаются синглтон-бинами: `GrokPromptFileWriter`, `GrokCommandBuilder`,
`GrokProcessRunner`, `GrokOutputParser`, `GrokExceptionMapper`, `GrokHomeGuard`, `GrokHomeSweeper`,
`ClaudeImageStager`, `ClaudePromptBuilder`, `ClaudeAsyncClientFactory`, `ClaudeInvoker`,
`ClaudeResponseParser`, `ClaudeExceptionMapper`. Их условия меняются с `provider=<id>` на
`enabled=true`. Экземпляр backend-а на пресет — обычный объект, созданный фабрикой.

Сигнатуры, куда уезжают `model` и `effort`:

- `GrokCommandBuilder.build(promptFile: Path, model: String, effort: String, useSchema: Boolean)`
- `ClaudeAsyncClientFactory.create(workTimeout: Duration, model: String)`
- `ClaudeInvoker.invoke(prompt: String, model: String)`

`GrokBackend.schemaSupported` остаётся `@Volatile`-полем экземпляра, то есть теперь оно
per-preset — это ровно правильная область: модель зафиксирована пресетом. `GrokHomeGuard` и
`GrokHomeSweeper` общие: `GROK_HOME` один на все grok-пресеты.

### Каталог и резолюция

`DescriptionPresetCatalog` строится автоконфигурацией на старте и неизменен:

```kotlin
class DescriptionPresetCatalog(
    private val entries: List<Entry>,   // порядок объявления карты
    val fallbackId: String,
) : DescriptionPresets {
    class Entry(val view: DescriptionPreset, val backend: DescriptionBackend?)
    fun byId(id: String): Entry?
}
```

Порядок пресетов — порядок объявления в yaml: Spring биндит карту в `LinkedHashMap`, и тест на
биндинг это фиксирует. `fallbackId` вычисляется один раз: `default-preset`, если он годен, иначе
первый годный в порядке объявления. `ActivePresetResolver.resolve(): Entry` на каждый вызов
`describe`:

1. `runtimeSettings.activePresetId()`;
2. id найден в каталоге и годен — возвращаем его `Entry`;
3. иначе WARN и возвращаем `Entry` для `fallbackId`.

Чтение обёрнуто в `catch (CancellationException) { throw } catch (Exception) { warnOnce(...); null }`
и тоже уходит к `fallbackId`. **Fail-open здесь обязателен:** `AppSettingsServiceImpl` намеренно не
кэширует неудачные чтения, поэтому отказ БД бил бы по каждой записи подряд, а сырое исключение
R2DBC покидало бы контракт `DescriptionException` — ровно то, чего агент избегает для downscale.

`DescriptionBackend` не меняется: у него по-прежнему `providerId`, `authRecoveryHint` и
`describe(request)`. Id пресета для логов агент берёт из `Entry.view.id`, а не из backend-а.

WARN однократный на изменение текста сообщения (`AtomicReference` с последним залогированным), иначе
каждая запись с детекциями засоряла бы лог одной и той же строкой.

Пригодность вычисляется на старте и в рантайме не меняется: и токен Claude, и наличие CLI приходят
из окружения процесса. Поэтому пересчёта на каждый вызов нет.

### `DefaultDescriptionAgent`

Меняются три вещи, остальное — семафор, `queueTimeout`, `withTimeout`, downscale, политика повторов,
нерефанд слота лимитера — остаётся как есть.

1. Backend резолвится **один раз на вызов** `describe`, до входа в цикл повторов: переключение
   посреди retry не должно разносить попытки по разным провайдерам, а лог одной записи обязан
   называть один пресет. Резолюция выполняется **до `semaphore.acquire()`**, а не между захватом
   пермита и `withTimeout`. Иначе поход в БД удерживает пермит: при `maxConcurrent=2` и зависшем
   пуле R2DBC все пермиты оказываются заняты корутинами, ждущими один `cacheMutex`, остальные
   вызовы уходят по `queueTimeout`, и `withTimeout` от этого не спасает — он начинается позже.
   Второй довод: бюджет `withTimeout` целиком достаётся работе модели, что при пресете с
   `effort: xhigh` (≈ 48 с из 60 с) уже не мелочь.

   **Принятая плата:** вызов, простоявший в очереди, применит пресет, актуальный на момент
   постановки в очередь, а не старта работы. Окно ограничено сверху `queueTimeout` (по умолчанию
   30 с). Переключение «на лету» и так не влияет на уже запущенные вызовы — см. раздел про
   выключатель, — поэтому семантика единообразна: изменение действует со следующего вызова, а не
   задним числом.
2. Семафор остаётся один на агента, то есть общий на все пресеты.
3. Машина состояний авторизации выносится в `ProviderAuthTracker` и становится по-провайдерной.

```kotlin
class ProviderAuthTracker(private val eventPublisher: ApplicationEventPublisher) : ProviderAuthStates {
    fun onSuccess(authScopeId: String, recoveryHint: String)
    fun onUnauthorized(authScopeId: String, e: DescriptionException.Unauthorized, recoveryHint: String)
}
```

`authScopeId` приходит от backend-а (его заполняет фабрика), `recoveryHint` пишется под область: для
grok он покрывает оба пути — `grok login --device-code` либо `api_key`/`env_key` для этой модели в
`config.toml`, — потому что различить их без разбора `config.toml` нельзя, а предлагать заведомо
неподходящее действие хуже, чем назвать оба. Формулировка в дереве уже есть
(`GrokBackend.kt:67-68`). `DescriptionProviderAuthEvent` несёт `authScopeId` вместо `provider`, и
текст владельцу называет область.

Внутри — `ConcurrentHashMap<String, AtomicReference<Health>>` и замок на область. Переходы,
публикация события под тем же замком, откат состояния при исключении слушателя и `compareAndSet`
переносятся из агента без изменения семантики. Стартовое значение — `UNKNOWN`; для переходов оно
ведёт себя как сегодняшний `HEALTHY` (первый `Unauthorized` публикует `LOST`, первый успех события
не даёт), отличается только тем, что рисуется в `/ai`.

### Автоконфигурация

Все бины фичи объявляются во вложенной `@Configuration` под одним условием, обычными зависимыми
бинами. Порядок `@Bean`-методов при этом ни на что не влияет:

```kotlin
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
@Conditional(DescriptionPresetsDeclaredCondition::class)
class PresetBeans {
    @Bean fun descriptionPresetCatalog(
        properties: DescriptionProperties,
        factories: ObjectProvider<DescriptionBackendFactory>,
    ): DescriptionPresetCatalog
    @Bean fun activePresetResolver(...): ActivePresetResolver
    @Bean fun providerAuthTracker(...): ProviderAuthTracker
    @Bean fun descriptionAgent(...): DescriptionAgent
}
```

**Почему не `@ConditionalOnBean(DescriptionPresetCatalog::class)` на соседних `@Bean`-методах.**
Сегодняшний `@ConditionalOnBean(DescriptionBackend::class)` надёжен потому, что backend приходит из
`@ComponentScan` — из **другой фазы**, гарантированно раньше. Для соседнего `@Bean`-метода того же
класса такой гарантии нет: Spring Boot не обещает, что sibling-`@Bean` виден `OnBeanCondition`, а
порядок методов в байткоде Kotlin может разойтись с порядком в файле. Цена ошибки несимметрична:
каталог есть, агента нет, `/ai` рисует пресеты, а описания молча никогда не вызываются. Одно
условие на всю вложенную конфигурацию убирает и зависимость от порядка, и KDoc с тестом, которые
эту зависимость закрепляли.

`ObjectProvider` вместо `List<DescriptionBackendFactory>`: при нуле кандидатов Spring бросает
`NoSuchBeanDefinitionException`, а не подставляет пустой список.

**`DescriptionPresetsDeclaredCondition` — единственная точка истины о том, что объявлено.** Условие
и сборка списка пресетов пользуются одной функцией `DescriptionPresetDeclarations.resolve(env)`, а
внутри неё — `Binder.get(env).bind("application.ai.description.presets", Bindable.mapOf(...))`. Это
тот же механизм, которым Spring биндит `DescriptionProperties`, поэтому видны все источники,
relaxed binding из окружения, bracket-форма `presets[id]` и плейсхолдеры. Сканирование имён
`EnumerablePropertySource` не годится: оно не видит ни env-карту, ни bracket-форму — карта
связывается, условие говорит «пресетов нет», и получается молчаливое «описания не работают».

Порядок сборки:

1. Список пресетов — из карты, а при пустой карте синтез из нормализованного legacy-`provider`;
   неизвестный legacy-провайдер даёт пустой список.
2. Пустой список — условие не сошлось, бинов фичи нет, WARN от `DescriptionAgentSanityChecker`
   (сообщение упоминает и `presets`, и legacy-переменную).
3. Непустой список, но ни одного годного пресета — `IllegalStateException`, приложение не стартует,
   в сообщении перечислены причины по каждому пресету.
4. Иначе каталог и остальные бины внутри `PresetBeans`.

`DescriptionPresetCatalogBuilder.build` отдаёт `sealed`-результат (`Catalog` | `NoPresets` |
`NoneUsable`), а не `null` вперемешку с исключением: три исхода в одной сигнатуре — это ровно та
рассогласованность условия и билдера, ради которой иначе нужен `checkNotNull` с извиняющимся
сообщением.

Набор известных провайдеров выводится из зарегистрированных фабрик (`factories.map { it.providerId }`),
а не задаётся константой рядом с `when`; `availability()` мемоизируется, поэтому выполняется один
раз на провайдера, а не на каждый его пресет.

`DescriptionRuntimeSettings` приходит из `core` (бин `@Service`), как `TempFileWriter` приходит из
`TempFileWriterAdapter`. На случай отсутствия реализации автоконфигурация регистрирует
`@ConditionalOnMissingBean`-дефолт, который держит выбор в памяти: он же используется в тестах
модуля. Обе реализации пишут при создании строку INFO — `app_settings` либо
`in-memory (choice does not survive restart)`. Без неё in-memory-дефолт может незаметно оказаться в
проде (не зарегистрировался бин `core`, опечатка в пакете при рефакторинге), и выбор владельца
будет молча пропадать на каждом рестарте — ровно то, что этот дизайн отвергает. Тест в `core`
фиксирует, что бин `DescriptionRuntimeSettings` — это `AppSettingsDescriptionRuntimeSettings`.

## Модуль `core`

- `AppSettingsDescriptionRuntimeSettings` — реализация SPI поверх `AppSettingsService`, ключи из
  таблицы выше, `updatedBy` прокидывается в `app_settings`.
- `RecordingProcessingFacade.buildDescriptionSupplier` становится `suspend` и возвращает `null`,
  когда `descriptionsEnabled()` равно `false`. Чтение **fail-open к `true`** с WARN — дословно как
  `TelegramNotificationServiceImpl.signalNotificationsGloballyEnabled`. Гейт стоит после
  `saveProcessingResult`, поэтому исключение оттуда потеряло бы уведомление без повтора: запись уже
  помечена обработанной. Глобальный флаг уведомлений читается до сохранения по обратной причине —
  он решает, слать ли уведомление вообще, и не прочитав его, решение принять нельзя. Выключатель
  описаний решает лишь, обогащать ли уведомление, поэтому блокировать им основное сообщение
  неверно, а нечитаемый ключ трактуется так же, как отсутствующий. Это уже существующая ветка «агента нет»:
  `TelegramNotificationServiceImpl` отсекает `descriptionSupplier == null` **до** `limiter.tryAcquire()`,
  поэтому слот rate limiter не тратится, плейсхолдеров нет, edit-job не создаётся, уведомление
  уходит с `DescriptionState.Absent`.
- `DescriptionAuthAlertNotifier` не меняется: событие уже несёт `provider`.

Статический `APP_AI_DESCRIPTION_ENABLED=false` по-прежнему главнее рантайм-флага и убирает бины
целиком; рантайм-выключатель существует только внутри включённой фичи.

## Диалог `/ai`

Owner-only команда, устройство повторяет `/notifications`. Классы в
`modules/telegram/.../bot/handler/aisettings/`:

| Класс | Ответственность |
|---|---|
| `AiSettingsCommandHandler` | `command = "ai"`, `requiredRole = OWNER`, **`ownerOnly = true`**, `order = 9` |
| `AiSettingsViewStateFactory` | Единая точка сборки состояния: каталог, активный id, флаг, авторизация |
| `AiSettingsMessageRenderer` | Текст и клавиатура из состояния |
| `AiSettingsCallbackHandler` | Обработка `aip:*`, запись в `DescriptionRuntimeSettings`, перерисовка |

Регистрация коллбэков в `FrigateAnalyzerBot` рядом с блоком `nfs:`, фильтр `startsWith("aip:")`,
**с дефолтным `markerFactory`**: waiter-а здесь нет, а сериализация кликов одного пользователя даёт
бесплатную защиту от двойного нажатия — по той же причине её сохранили quick-export и cancel.

**Порядок: сначала ответ, потом запись** — как в `nfs:`. Разбор и валидация чистые, без
ввода-вывода; ответ на коллбэк уходит ровно один и сразу; и только затем выполняется запись и
перерисовка. Кажущееся противоречие «алерту нужен результат записи» разрешается тем, что **исходы,
требующие алерта, записи не требуют**: клик по недоступному пресету, по исчезнувшему из конфига id и
клик не-владельца разрешаются из каталога и роли. Исходы с записью — переключение и вкл/выкл — не
несут содержательного текста, их подтверждает перерисовка экрана, которая происходит после записи и
потому не может соврать: упавшая запись оставит на экране прежний активный пресет.

Причина, по которой это не косметика: дефолтный `markerFactory` сериализует коллбэки одного
пользователя, поэтому обработчик, зависший на медленной БД, блокирует **следующий** клик владельца,
а не только держит спиннер.

Экран:

```
🤖 AI-описания
Состояние: включены
Активный пресет: grok-fast (grok / grok-4.6 / low)

🟢 grok — авторизация в порядке
⚠️ claude — не настроен: нет CLAUDE_CODE_OAUTH_TOKEN
Состояние показано на момент последнего вызова описания.

[✅ grok-fast]  [grok-deep]
[byok-luna]    [⚠️ claude-opus]
[Выключить описания]  [Закрыть]
```

При расхождении сохранённого и эффективного пресета над списком печатается отдельная строка
(`ai.settings.active.mismatch`):

```
⚠️ Выбран claude-opus, но он недоступен — работает grok-fast.
   Выбор сохранён и применится снова, когда пресет станет доступен.
```

Кнопка на пресет, по одной в ряд; `✅` у **эффективного**, `⚠️` у недоступного. Пустой `effort`
рисуется как `—`. Строка авторизации на каждый провайдер, встречающийся в пресетах.

**Экран несёт оба id — сохранённый и эффективный.** Иначе `/ai` показывает только результат
резолюции, и обещанное разделом «Риски» «экран показывает несоответствие» не выполняется: владелец
не видит, что его выбор перекрыт, битый id живёт в `app_settings` вечно (кликать по fallback-у
незачем), а сценарий живой проверки «после рестарта активным остаётся выбранный пресет» проходит
успешно и обманывает. Отсюда два метода в `ActiveDescriptionPreset`.

Состояние авторизации меняется только на вызове описания: после `grok login` экран покажет 🔴 до
следующей записи с детекциями, а после рестарта — ⚪ `UNKNOWN` даже при протухшем `auth.json`. Это
принятое следствие отказа от кнопки «тест», поэтому строка-оговорка стоит прямо в тексте экрана.

Блок строк авторизации печатается всегда, когда каталог непуст. Ранний выход применяется только к
пустому каталогу: иначе состояние «каталог есть, активного нет» скрыло бы всю диагностику
авторизации — то есть ровно то, ради чего экран и открывают.

| Payload | Эффект |
|---|---|
| `aip:set:<id>` | Записать активный пресет, перерисовать экран |
| `aip:on` / `aip:off` | Включить или выключить описания (значение явное, не toggle) |
| `aip:close` | Закрыть клавиатуру |

Клик по недоступному пресету ничего не пишет и отвечает причиной через `answerCallbackQuery` с
`showAlert = true`: это единственное место, где владелец узнаёт причину недоступности, а тост в углу
легко пропустить. Клик по исчезнувшему из конфига id (экран открыт до рестарта) обрабатывается так же. Состояние после
любого клика перечитывается из БД и каталога, поэтому две вкладки одного экрана сходятся к одной
картине.

Команда условна на `application.telegram.enabled=true` и `application.ai.description.enabled=true`;
при выключенной фиче `/ai` отсутствует и в меню. Каталог берётся через `ObjectProvider`: если
пресетов нет вовсе, экран показывает `ai.settings.active.none` и только кнопку закрытия.

Ключи i18n, оба бандла, без апострофов (MessageFormat):

```
command.ai.description
ai.settings.title
ai.settings.state / ai.settings.state.on / ai.settings.state.off
ai.settings.active / ai.settings.active.none / ai.settings.active.mismatch
ai.settings.auth.healthy / .lost / .unknown / .unavailable
ai.settings.button.enable / .disable / .close
ai.settings.alert.switched / .enabled / .disabled / .unavailable
```

`ai.description.auth.lost` получает третью строку — «Переключить пресет: /ai» и её английский
аналог: владелец узнаёт о выходе там же, где узнал о проблеме.

## Деплой и документация

- `docker/deploy/docker-entrypoint.sh` перестаёт ветвиться по `APP_AI_DESCRIPTION_PROVIDER`: пресеты
  лежат в yaml и шеллу не видны. При `APP_AI_DESCRIPTION_ENABLED=true` прогоняются проверки того
  провайдера, чьи входные данные присутствуют. Признак «grok действительно нужен» — существующий
  `$GROK_HOME/auth.json` **или** `config.toml`, **или** legacy `APP_AI_DESCRIPTION_PROVIDER=grok`:
  самого по себе непустого `GROK_HOME` недостаточно, потому что в compose он задан всегда
  (`docker-compose.yml:35`) и том монтируется всегда, так что иначе WARN про отсутствующий
  `auth.json` получил бы каждый claude-деплой. Симметрично для claude. Сохраняются две сегодняшние
  диагностики, которые иначе пропали бы: сводный WARN «ни токена Claude, ни признаков Grok — старт,
  скорее всего, упадёт» (legacy-синтез одного claude-пресета без токена по-прежнему валит старт по
  правилу «ноль годных», и самый частый misconfig не должен выглядеть мягким INFO плюс падение JVM)
  и строка про неизвестное значение `APP_AI_DESCRIPTION_PROVIDER`. WARN только на сломанное,
  никаких утверждений о том, какой пресет активен.
- `docker/deploy/application-docker.yaml.example` получает **закомментированный** блок `presets` с
  явной строкой «раскомментирование отключает `APP_AI_DESCRIPTION_PROVIDER`, `GROK_MODEL`,
  `GROK_EFFORT` и `CLAUDE_MODEL`»: пример содержит живые значения, а профильный файл приоритетнее
  базового, поэтому копирование поверх claude-деплоя иначе молча переключило бы его на `grok-fast`.
  `default-preset` в примере пишется как `${APP_AI_DESCRIPTION_DEFAULT_PRESET:grok-fast}` — литерал
  сделал бы одноимённую переменную из `.env.example` мёртвой.
- Валидация `default-preset` при **пустой** карте ослабляется до WARN: оператор, скопировавший
  `.env.example` со значением до того, как объявил карту в yaml, иначе не стартует вовсе, и
  миграция «сначала env, потом yaml» становится невозможной.
- `docker/deploy/.env.example` помечает `APP_AI_DESCRIPTION_PROVIDER`, `GROK_MODEL`, `GROK_EFFORT`,
  `CLAUDE_MODEL` как путь одного пресета и указывает на yaml для нескольких; значение
  `APP_AI_DESCRIPTION_DEFAULT_PRESET` остаётся пустым.
- `docker/deploy/docker-compose.yml`: комментарий тома `grok-home` («Grok Build home
  (provider=grok)») перестаёт быть правдой и правится вместе с остальным.
- Переключение пресета и стартовая строка каталога логируются на INFO **со значением** —
  `id (provider/model/effort)`. Сегодня на INFO уходит только факт записи ключа, а значение на
  DEBUG; для вопроса «какая модель сейчас работает» этого мало. Стартовая строка заодно возвращает
  правду формулировке `.claude/rules/ai-description.md` про «logs model and effort at INFO once at
  startup», которую отменяет удаление `GrokBackend.init`.
- `.claude/rules/ai-description.md`: пресеты, фабрики, каталог и резолюция, авторизация по
  провайдеру, диалог; в `paths:` добавляется `**/handler/aisettings/**`.
- `.claude/rules/configuration.md`: новые свойства и ключи `app_settings`.
- `.claude/rules/database.md`: два новых ключа `app_settings`.
- `README.md`: `/ai` в списке команд, раздел про пресеты.
- `CLAUDE.md`: строка модуля `ai-description` и ключевой паттерн упоминают пресеты.

Миграций Liquibase нет.

## Тестирование

Все тесты без сети и без реальных CLI.

`ai-description`:

- Биндинг: карта пресетов, синтез legacy-пресета из `provider`, пустая карта с неизвестным
  провайдером, `default-preset` вне карты. **Порядок карты фиксируется `containsExactly`, а не
  `containsExactlyInAnyOrder`** — именно на порядок объявления опирается правило «`fallbackId` =
  первый годный». Отдельные случаи: legacy-`provider` в верхнем и смешанном регистре даёт тот же
  пресет; ключ карты в верхнем регистре и с пробелом — тест фиксирует, что именно делает relaxed
  binding (id теряет регистр и посторонние символы), чтобы искажение не всплывало впервые в
  `callback_data`; карта, объявленная bracket-формой `presets[id]` и через окружение, видна
  условию.
- Валидация: `effort` у claude, `effort` вне набора, кривой id, неизвестный `provider` в карте.
- `DescriptionPresetCatalogTest`: `fallbackId` при недоступном `default-preset`, порядок пресетов,
  ноль годных → исключение, пустой список → отсутствие каталога.
- `ActivePresetResolverTest`: сохранённый годный, сохранённый неизвестный, сохранённый недоступный,
  отсутствующий ключ; WARN однократен при повторных вызовах.
- Фабрики: claude без токена → `Unavailable` вместо исключения; grok всегда `Available`; созданный
  backend несёт модель и effort пресета (проверяется по argv и по `CLIOptions`).
- `DefaultDescriptionAgentTest` дополняется: резолюция один раз на вызов (переключение между
  попытками не меняет backend), общий семафор на два пресета.
- `ProviderAuthTrackerTest`: одно событие на переход при параллельных отказах, два grok-пресета
  дают одно событие, claude и grok независимы, `UNKNOWN` → `LOST` публикуется, откат при
  исключении слушателя. **Два concurrency-теста переносятся из `DefaultDescriptionAgentTest`
  живьём, с реальными потоками и `CountDownLatch`** — `a slow listener cannot reorder concurrent
  auth transitions` и `concurrent Unauthorized failures publish a single LOST`: это единственные
  тесты, проверяющие смысл существования замка, и однопоточные сценарии их не заменяют. Плюс новый:
  медленный слушатель на `grok` не задерживает событие `claude`. Тест `a throwing listener does not
  discard a successful description` остаётся в `DefaultDescriptionAgentTest` — это инвариант
  агента, а не трекера.
- `AiDescriptionAutoConfigurationTest`: N пресетов → N backend-ов и один агент; ноль годных →
  падение; `enabled=false` → нет бинов; legacy-путь даёт один пресет. Существующие сценарии
  **переписываются, а не дополняются**: backend перестал быть бином, а коллаборанты обоих
  провайдеров существуют при `enabled=true`, поэтому проверки `getBeansOfType(ClaudeBackend)` и
  «helpers чужого провайдера отсутствуют» становятся ложными и заменяются проверками каталога.
- Покрытие whitespace-токена Claude (`isBlank()`) переезжает из удаляемого
  `ClaudeBackendValidationTest` в `ClaudeBackendFactoryTest`, а сценарии «home — это файл» и
  «создание каталогов» — из `GrokBackendTest` в `GrokBackendFactoryTest` вместе с самим кодом.

`core`: `AppSettingsDescriptionRuntimeSettingsTest` (дефолты, запись, `updatedBy`), тест фасада на
выключенный рантайм-флаг (нет supplier-а, слот лимитера не тронут), тест «бин
`DescriptionRuntimeSettings` — это `AppSettingsDescriptionRuntimeSettings`, а не in-memory-дефолт».

`telegram`: рендер (включено/выключено, активный, недоступный, три состояния авторизации, пустой
каталог, **расхождение сохранённого и эффективного пресета**), `aip:set` на годный и на недоступный,
`aip:on`/`aip:off`, **callback подтверждается на каждом исходе, включая ранние выходы и исключение
записи**, метаданные команды (`ownerOnly = true`, `requiredRole = OWNER`, уникальность `order`),
наличие всех новых ключей в обоих бандлах.

**Живая проверка вне CI:** два пресета одного провайдера и один чужого; переключение в `/ai`
меняет модель в DEBUG-строке следующей записи; выключение описаний даёт уведомление без блоков;
переименование `auth.json` даёт 🔴 у grok и сообщение с подсказкой `/ai`.

## Риски и открытые вопросы

- **Кэш `AppSettingsService` не имеет TTL.** Прямой SQL по `app_settings` мимо сервиса не виден
  работающему процессу; после ручной правки нужен рестарт. Ограничение унаследовано от
  `/notifications` и остаётся в силе; оба новых ключа попадают в соответствующую оговорку
  `database.md`.
- **Фича рассчитана на один экземпляр приложения.** Запись через один экземпляр инвалидирует только
  его собственный кэш, поэтому при двух работающих контейнерах выбор пресета и рантайм-выключатель
  разъедутся и будут расходиться до рестарта. Сегодня это верно и для глобальных флагов
  `/notifications`, но там ограничение записано только в примечаниях; здесь оно фиксируется явно,
  потому что пресет меняет стоимость и латентность обработки.
- **Пресет может стать недоступным после рестарта** (убрали токен из `.env`). Резолвер уводит на
  годный и пишет WARN, экран `/ai` показывает несоответствие, описания продолжают работать.
- **Порядок кликов.** Дефолтный `markerFactory` сериализует коллбэки одного пользователя, так что
  два быстрых клика применяются по порядку. Между разными пользователями порядок не определён, но
  владелец в системе один.
- **Таймаут остаётся глобальным, и потолок ставится под самый медленный объявленный пресет.**
  `grok-4.6 xhigh` занимает ~48 с при дефолтных 60 с: `INVALID_RESPONSE_RETRY_MIN_BUDGET` (5 с)
  формально пройдёт, но повтор почти наверняка упрётся в `withTimeout` и даст `Timeout` вместо
  честного `InvalidResponse`, а `TRANSPORT_RETRY_MIN_BUDGET` (10 с плюс пауза 5 с) не запустится
  вовсе. Пресетного `timeout` **нет** сознательно: потолок выбирается в момент объявления пресета,
  а объявление и так означает правку `application-docker.yaml` и рестарт, поэтому поднять его в той
  же правке ничего не стоит — и обещание «переключение без рестарта» сохраняется полностью, ведь
  оно относится к переключению, а не к объявлению. Таймаут при этом не длительность, а момент
  отказа от ожидания: `grok-fast` завершается за 9 с независимо от того, стоит потолок на 60 с или
  на 120 с. Плата — зависание быстрого пресета обнаруживается позже.

  Чтобы ловушка не досталась проду молча: на старте выводится WARN, если у пресета
  `effort ∈ {xhigh, max}`, а `common.timeout` не оставляет бюджета повтора — с именем пресета и
  рекомендуемым значением; в `/ai` такие пресеты получают отметку.

  Если зависания окажутся частыми, естественный следующий шаг — типизированное поле
  `presets.<id>.timeout` (пусто = глобальный): оно не свободная карта overrides и не секрет, то
  есть принятым здесь принципам не противоречит.
- **Вместимость очереди меняется вместе с пресетом.** Два вызова на `xhigh` занимают оба слота
  семафора (`maxConcurrent=2`) на ~48 с, и третий уходит по `queueTimeout=30s` в fallback.
  Пресетный таймаут этого не лечит — это свойство глобального семафора, — но переключение из
  диалога меняет пропускную способность, и владелец об этом нигде не предупреждён.
- **`docker-entrypoint.sh` пресетов не видит.** Его проверки становятся приблизительными по
  построению; точную картину даёт `/ai`.

## Вне рамок

- Автопереключение пресета при отказе авторизации и любые fallback-цепочки.
- Пресетные `base-url` и ключи для claude (свои эндпоинты Anthropic-совместимых провайдеров).
- Кнопка «тест» с прогоном описания по требованию.
- Пресет на пользователя: настройка глобальная, владелец один.
- Состояние пресетов в `/actuator/health`.
