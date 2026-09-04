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

**Пресеты живут в yaml, а не в env.** Это карта, а `.env` карт не умеет без индексных имён вида
`..._PRESETS_0_MODEL`. В проекте карты уже настраиваются в `application-docker.yaml` (`detect-servers`),
и файл смонтирован в контейнер томом. Плата: `docker-entrypoint.sh` пресетов не видит, поэтому его
проверки перестают говорить о выбранном провайдере и начинают говорить о доступных.

**Backend-ы строятся фабриками, а не объявляются `@Component`-ами.** Их количество задаёт карта,
поэтому статические `@ConditionalOnProperty(provider=…)` больше не работают: нельзя объявить
`@Bean` в цикле. Провайдер отдаёт `DescriptionBackendFactory`, автоконфигурация строит по
экземпляру backend-а на пресет. `model` и `effort` при этом уезжают из свойств в параметры вызова
`GrokCommandBuilder.build(...)` и `ClaudeInvoker.invoke(...)`.

**Агент остаётся один, семафор глобальный, состояние авторизации по провайдеру.** `maxConcurrent`
ограничивает нагрузку на машину и расход денег — это свойство всей фичи, а не пресета. Авторизация,
наоборот, принадлежит провайдеру: два grok-пресета делят один `auth.json`, и отказ должен дать одно
событие на двоих, а не два.

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
| `default-preset` | `APP_AI_DESCRIPTION_DEFAULT_PRESET` | пусто | пусто, либо существующий ключ карты |

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
При непустой карте `APP_AI_DESCRIPTION_PROVIDER`, `GROK_MODEL`, `GROK_EFFORT` и `CLAUDE_MODEL` не
используются; секции провайдеров продолжают биндиться всегда, поэтому их значения обязаны
оставаться валидными.

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
            DescriptionRuntimeSettings                              контракт наружу
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
    val model: String,
    val effort: String,            // пусто = флаг не передаётся
    val unavailableReason: String?, // null = пресет годен
)

interface DescriptionPresets {
    fun all(): List<DescriptionPreset>   // порядок объявления в yaml
}

interface ProviderAuthStates {
    enum class Health { UNKNOWN, HEALTHY, LOST }
    fun byProvider(): Map<String, Health>
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
   называть один пресет.
2. Семафор остаётся один на агента, то есть общий на все пресеты.
3. Машина состояний авторизации выносится в `ProviderAuthTracker` и становится по-провайдерной.

```kotlin
class ProviderAuthTracker(private val eventPublisher: ApplicationEventPublisher) : ProviderAuthStates {
    fun onSuccess(providerId: String, recoveryHint: String)
    fun onUnauthorized(providerId: String, e: DescriptionException.Unauthorized, recoveryHint: String)
}
```

Внутри — `ConcurrentHashMap<String, AtomicReference<Health>>` и замок на провайдера. Переходы,
публикация события под тем же замком, откат состояния при исключении слушателя и `compareAndSet`
переносятся из агента без изменения семантики. Стартовое значение — `UNKNOWN`; для переходов оно
ведёт себя как сегодняшний `HEALTHY` (первый `Unauthorized` публикует `LOST`, первый успех события
не даёт), отличается только тем, что рисуется в `/ai`.

### Автоконфигурация

```kotlin
@Bean
@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")
fun descriptionPresetCatalog(
    properties: DescriptionProperties,
    factories: List<DescriptionBackendFactory>,
): DescriptionPresetCatalog
```

Порядок сборки:

1. Список пресетов — из карты, а при пустой карте синтез из legacy-`provider`; неизвестный
   legacy-провайдер даёт пустой список.
2. Пустой список — каталога и агента нет, WARN от `DescriptionAgentSanityChecker` (сообщение
   упоминает и `presets`, и legacy-переменную).
3. Непустой список, но ни одного годного пресета — `IllegalStateException`, приложение не стартует,
   в сообщении перечислены причины по каждому пресету.
4. Иначе каталог, а следом агент: `@Bean` с `@ConditionalOnBean(DescriptionPresetCatalog::class)`.

`@ConditionalOnBean` здесь надёжен по той же причине, что и сегодняшний
`@ConditionalOnBean(DescriptionBackend::class)`: это `@AutoConfiguration`, и условия `@Bean`-методов
проверяются в порядке объявления, поэтому метод каталога обязан стоять **выше** метода агента. Это
требование фиксируется KDoc-ом и тестом «пустой список пресетов не даёт агента».

`DescriptionRuntimeSettings` приходит из `core` (бин `@Service`), как `TempFileWriter` приходит из
`TempFileWriterAdapter`. На случай отсутствия реализации автоконфигурация регистрирует
`@ConditionalOnMissingBean`-дефолт, который держит выбор в памяти: он же используется в тестах
модуля.

## Модуль `core`

- `AppSettingsDescriptionRuntimeSettings` — реализация SPI поверх `AppSettingsService`, ключи из
  таблицы выше, `updatedBy` прокидывается в `app_settings`.
- `RecordingProcessingFacade.buildDescriptionSupplier` становится `suspend` и возвращает `null`,
  когда `descriptionsEnabled()` равно `false`. Это уже существующая ветка «агента нет»:
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
| `AiSettingsCommandHandler` | `command = "ai"`, `requiredRole = OWNER`, `order = 8` |
| `AiSettingsViewStateFactory` | Единая точка сборки состояния: каталог, активный id, флаг, авторизация |
| `AiSettingsMessageRenderer` | Текст и клавиатура из состояния |
| `AiSettingsCallbackHandler` | Обработка `aip:*`, запись в `DescriptionRuntimeSettings`, перерисовка |

Регистрация коллбэков в `FrigateAnalyzerBot` рядом с блоком `nfs:`, фильтр `startsWith("aip:")`,
**с дефолтным `markerFactory`**: waiter-а здесь нет, а сериализация кликов одного пользователя даёт
бесплатную защиту от двойного нажатия — по той же причине её сохранили quick-export и cancel.

Экран:

```
🤖 AI-описания
Состояние: включены
Активный пресет: grok-fast (grok / grok-4.6 / low)

🟢 grok — авторизация в порядке
⚠️ claude — не настроен: нет CLAUDE_CODE_OAUTH_TOKEN

[✅ grok-fast]  [grok-deep]
[byok-luna]    [⚠️ claude-opus]
[Выключить описания]  [Закрыть]
```

Кнопка на пресет, по одной в ряд; `✅` у активного, `⚠️` у недоступного. Пустой `effort` рисуется
как `—`. Строка авторизации на каждый провайдер, встречающийся в пресетах.

| Payload | Эффект |
|---|---|
| `aip:set:<id>` | Записать активный пресет, перерисовать экран |
| `aip:on` / `aip:off` | Включить или выключить описания (значение явное, не toggle) |
| `aip:close` | Закрыть клавиатуру |

Клик по недоступному пресету ничего не пишет и отвечает причиной через `answerCallbackQuery`. Клик
по исчезнувшему из конфига id (экран открыт до рестарта) обрабатывается так же. Состояние после
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
ai.settings.active / ai.settings.active.none
ai.settings.auth.healthy / .lost / .unknown / .unavailable
ai.settings.button.enable / .disable / .close
ai.settings.alert.switched / .enabled / .disabled / .unavailable
```

`ai.description.auth.lost` получает третью строку — «Переключить пресет: /ai» и её английский
аналог: владелец узнаёт о выходе там же, где узнал о проблеме.

## Деплой и документация

- `docker/deploy/docker-entrypoint.sh` перестаёт ветвиться по `APP_AI_DESCRIPTION_PROVIDER`: пресеты
  лежат в yaml и шеллу не видны. При `APP_AI_DESCRIPTION_ENABLED=true` прогоняются проверки того
  провайдера, чьи входные данные присутствуют — claude при непустом токене, grok при существующем
  `GROK_HOME`. WARN только на сломанное, никаких утверждений о том, какой пресет активен.
- `docker/deploy/application-docker.yaml.example` получает блок `presets` с комментарием.
- `docker/deploy/.env.example` помечает `APP_AI_DESCRIPTION_PROVIDER`, `GROK_MODEL`, `GROK_EFFORT`,
  `CLAUDE_MODEL` как путь одного пресета и указывает на yaml для нескольких.
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
  провайдером, `default-preset` вне карты.
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
  исключении слушателя.
- `AiDescriptionAutoConfigurationTest`: N пресетов → N backend-ов и один агент; ноль годных →
  падение; `enabled=false` → нет бинов; legacy-путь даёт один пресет.

`core`: `AppSettingsDescriptionRuntimeSettingsTest` (дефолты, запись, `updatedBy`), тест фасада на
выключенный рантайм-флаг (нет supplier-а, слот лимитера не тронут).

`telegram`: рендер (включено/выключено, активный, недоступный, три состояния авторизации, пустой
каталог), `aip:set` на годный и на недоступный, `aip:on`/`aip:off`, наличие всех новых ключей в
обоих бандлах.

**Живая проверка вне CI:** два пресета одного провайдера и один чужого; переключение в `/ai`
меняет модель в DEBUG-строке следующей записи; выключение описаний даёт уведомление без блоков;
переименование `auth.json` даёт 🔴 у grok и сообщение с подсказкой `/ai`.

## Риски и открытые вопросы

- **Кэш `AppSettingsService` не имеет TTL.** Прямой SQL по `app_settings` мимо сервиса не виден
  работающему процессу; после ручной правки нужен рестарт. Ограничение унаследовано от
  `/notifications` и остаётся в силе.
- **Пресет может стать недоступным после рестарта** (убрали токен из `.env`). Резолвер уводит на
  годный и пишет WARN, экран `/ai` показывает несоответствие, описания продолжают работать.
- **Порядок кликов.** Дефолтный `markerFactory` сериализует коллбэки одного пользователя, так что
  два быстрых клика применяются по порядку. Между разными пользователями порядок не определён, но
  владелец в системе один.
- **Стоимость переключения.** Пресет с `effort: xhigh` на grok-4.6 занимает ~48 с при дефолтном
  `APP_AI_DESCRIPTION_TIMEOUT=60s`. Документируем: под такой пресет таймаут поднимают.
- **`docker-entrypoint.sh` пресетов не видит.** Его проверки становятся приблизительными по
  построению; точную картину даёт `/ai`.

## Вне рамок

- Автопереключение пресета при отказе авторизации и любые fallback-цепочки.
- Пресетные `base-url` и ключи для claude (свои эндпоинты Anthropic-совместимых провайдеров).
- Кнопка «тест» с прогоном описания по требованию.
- Пресет на пользователя: настройка глобальная, владелец один.
- Состояние пресетов в `/actuator/health`.
