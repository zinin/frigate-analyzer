# Merged Design Review — Iteration 1

**Дата:** 2026-09-04
**Design:** `docs/superpowers/specs/2026-09-04-ai-description-presets-design.md`
**Plan:** `docs/superpowers/plans/2026-09-04-ai-description-presets.md`
**Ревьюеры:** claude:opus, claude:fable, codex (gpt-5.6-sol, reasoning max), grok:grok-4.6 (effort xhigh)

## Ход прогона

Первый заход набора `default` (claude:opus, claude:fable, codex) прошёл частично:

- **codex** упал: `all_attempts_failed`, три попытки, причина — `You've hit your usage limit … try again at Sep 7th, 2026`. Первая попытка успела прочитать код (127 событий), но ревью не написала. Перезапущен после восстановления квоты — успешно.
- **claude:fable** выбил лимит Fable, успев отдать три критических пункта и половину четвёртого. Перезапущен — успешно, с записью результата в файл вместо ответа в транспорт.
- **claude:opus** отработал с первого раза; текст пришёл обрезанным транспортом и был забран файлом.
- **grok:grok-4.6** добавлен по решению пользователя взамен выбывших, отработал с первого раза (`REAL`, agentic review, 24 хода).

Все четыре секции ниже — полные тексты, ничего не опущено.

---

## claude:opus

Ознакомился с обоими документами и сверил план с деревом. Ниже — ревью.

---

## Critical Issues

### C1. Провайдер-специфичная инфраструктура становится активной там, где её пресетов нет

Task 3 Step 8 снимает `@ConditionalOnProperty("...provider", havingValue="grok"|"claude")` с 14 коллаборантов, оставляя только `enabled=true`. В этом списке — `grok/GrokHomeSweeper.kt`, у которого

```kotlin
@Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
fun sweepScheduled()
```

и который под `guard.exclusive` **удаляет всё содержимое `GROK_HOME/sessions/` и файлы в `logs/`** (`modules/ai-description/.../grok/GrokHomeSweeper.kt:30-49`). После правки на стенде, где объявлены только claude-пресеты, он будет ежечасно подметать каталог, к которому приложение не имеет отношения — например, GROK_HOME, смонтированный оператором для ручного `grok`.

Симметрично:

- `GrokBackendFactory` (Task 3 Step 7) в `init` делает `Files.createDirectories(home)` / `createDirectories(cwd)` и бросает `IllegalStateException` при неудаче, плюс пишет два WARN (`grok CLI not found`, `No auth.json in …`). Фабрика — `@Component` на `enabled=true`, то есть создаётся и при чисто claude-конфигурации. Сегодня всё это живёт в `GrokBackend.init`, который создаётся только при `provider=grok`.
- `ClaudeBackendFactory.warnIfCliMissing` (`@PostConstruct`) зовёт `Query.isCliInstalled()` и на grok-only стенде выдаёт «Claude CLI not found in PATH».

Ни спека, ни план это не упоминают — в плане это одна строка «Условия меняются с `provider=<id>` на `enabled=true`». Нужен явный механизм «провайдер участвует в пресетах»: либо хук `DescriptionBackendFactory.prepare()`, который `DescriptionPresetCatalogBuilder` вызывает только для провайдеров, реально встречающихся в карте, либо кастомное `Condition` для `GrokHomeSweeper`.

### C2. `/ai` окажется в меню и в `/help` у всех пользователей

`AiSettingsCommandHandler` (Task 8 Step 4) объявляет только `requiredRole = UserRole.OWNER`. Но в этой кодовой базе видимость команды определяет **другое** свойство — `ownerOnly`:

- `FrigateAnalyzerBot.registerDefaultCommands()`: `sortedHandlers.filterNot { it.ownerOnly }` → `setMyCommands(..., BotCommandScopeDefault)`;
- `HelpCommandHandler`: `defaultCommands = sortedHandlers.filterNot { it.ownerOnly }`, а owner-секция — `filter { it.ownerOnly }`.

Все три существующие owner-команды ставят оба флага (`AddUserCommandHandler.kt:22-23`, `RemoveUserCommandHandler.kt:21-22`, `UsersCommandHandler.kt:22-23`). Без `override val ownerOnly = true` `/ai` попадёт в дефолтное меню каждого пользователя и в общую секцию `/help`, а при клике ответит `common.error.owner.only`. Это прямо противоречит формулировке спеки «Owner-only команда, устройство повторяет `/notifications`».

### C3. Task 3 ломает тесты, которых нет в его списке файлов

Task 3 Step 7 меняет конструкторы обоих backend-ов, а Step 10 — конструктор агента. Не перечислены и не собьются:

- `modules/ai-description/src/test/.../grok/GrokBackendTest.kt:53` — `GrokBackend(properties = properties, …)`;
- `modules/ai-description/src/test/.../claude/ClaudeBackendTest.kt:53` — `ClaudeBackend(claudeProperties = claudeProps, …)`;
- `modules/ai-description/src/test/.../claude/ClaudeBackendIntegrationTest.kt:122,131` — `ClaudeBackend(claudeProperties = …)` **и** `DefaultDescriptionAgent(backend, descriptionProps, ApplicationEventPublisher { })`. Файл `@Disabled`, но компилируется, и его конструктор агента ломается ещё раз в Task 4 и Task 5.

Кроме того, план говорит про `AiDescriptionAutoConfigurationTest` «существующие сценарии … **дополнить**», но четыре существующих утверждения станут ложными, их надо переписать или удалить:

- `assert(ctx.getBeansOfType(ClaudeBackend::class.java).isNotEmpty())` — backend больше не бин;
- `assert(ctx.getBeansOfType(GrokBackend::class.java).isNotEmpty())` — то же;
- в grok-сценарии `assert(ctx.getBeansOfType(ClaudeAsyncClientFactory::class.java).isEmpty())` — фабрика теперь есть всегда;
- в `unknown provider` сценарии то же утверждение про `ClaudeAsyncClientFactory` с комментарием «Claude helpers must be gated on provider=claude».

Для агента, исполняющего план по шагам, «Ожидание: PASS» в Step 12 недостижимо без импровизации.

### C4. `resolver.resolve()` кладёт неограниченный поход в БД внутрь семафора и вне всех таймаутов

Task 3 Step 10 / Task 4 Step 5 ставят резолюцию после `downscaleFrames`, то есть между `semaphore.acquire()` и `withTimeout(commonSection.timeout)`. Сегодня в этом промежутке нет ни одной операции ввода-вывода: downscale — чистый CPU под `Dispatchers.Default`.

`ActivePresetResolver.resolve()` → `AppSettingsDescriptionRuntimeSettings.activePresetId()` → `AppSettingsServiceImpl.getRaw()` → `cacheMutex.withLock { repository.findBySettingKey(key) }` (`modules/service/.../impl/AppSettingsServiceImpl.kt:82-93`). При исчерпанном пуле R2DBC или зависшем соединении вызов повиснет **удерживая пермит семафора**, а `maxConcurrent` по умолчанию 2 — вся фича встанет, причём `withTimeout` её не спасёт.

Второе следствие: исключение R2DBC уйдёт из `describe()` сырым, мимо контракта `DescriptionException`. Внешне это переживаемо (`RecordingProcessingFacade` ловит `Throwable`), но `getRaw` кэширует только успех — «errors stay transient», значит каждая запись будет заново биться о БД.

Минимум: обернуть резолюцию в собственный небольшой `withTimeout` с падением на `catalog.fallback()`, либо перенести её до `semaphore.acquire()`.

### C5. Переключаемая единица не содержит таймаут, хотя переключают именно ради латентности

Спека сама приводит замеры: `grok-4.6 xhigh` ≈ 48 с при `APP_AI_DESCRIPTION_TIMEOUT` = 60 с (`application.yaml:113`), и сама ставит `grok-deep: { effort: xhigh }` в пример для `application-docker.yaml.example` (Task 9 Step 2). При этом:

- `common.timeout` — глобальный и меняется только рестартом;
- бюджет повторов считается от него же: после 48-секундной попытки остаётся ~12 с, `INVALID_RESPONSE_RETRY_MIN_BUDGET` = 5 с, значит повтор запустится и почти гарантированно упрётся в `withTimeout` → `Timeout` вместо честного `InvalidResponse`;
- `DefaultClaudeInvoker.workTimeout` вычисляется из того же значения на этапе DI.

То есть ровно тот пресет, ради которого затевалось переключение без рестарта, невозможно включить без рестарта. «Документируем: под такой пресет таймаут поднимают» в разделе рисков не решает задачу — это возвращает нас к `.env` + `docker compose up -d`. Пресетный `timeout` не секрет и не свойство машины, он не нарушает принятого принципа «инфраструктура остаётся в секциях провайдера», и это, на мой взгляд, самый недостающий элемент модели.

### C6. `ANTHROPIC_MODEL` бесшумно обнуляет разницу между claude-пресетами

`ClaudeAsyncClientFactory.buildOptions` ставит модель только когда override пуст:

```kotlin
if (claudeProperties.anthropic.modelOverride.isBlank()) {
    optionsBuilder.model(model)
}
```

План это сохраняет и даже добавляет тест «`anthropic.model-override` по-прежнему её вытесняет» (Task 2 Step 1). Но следствие в мире пресетов другое, чем в мире одного провайдера: два claude-пресета с разными `model` становятся неразличимыми, а экран `/ai` уверенно рисует `claude-opus (claude / opus / —)` и `claude-sonnet (claude / sonnet / —)`. Нужно либо WARN на старте при непустом `ANTHROPIC_MODEL` и более чем одном claude-пресете, либо пометка в `unavailableReason`/на экране.

---

## Concerns

**Проводка каталога хрупка дважды.** `@ConditionalOnBean(DescriptionPresetCatalog::class)` на `@Bean`-методе в **том же** классе полагается на порядок объявления методов внутри `@AutoConfiguration` — это слабее сегодняшнего варианта, где backend приходит из `@ComponentScan` (фаза парсинга, гарантированно раньше). Простое устранение: повесить на `activePresetResolver` и `descriptionAgent` то же `@Conditional(DescriptionPresetsDeclaredCondition::class)`, что и на каталог. Тогда порядок методов перестаёт быть частью контракта, и KDoc/тест про него не нужны.

**`DescriptionPresetsDeclaredCondition` не понимает relaxed binding.** Он сканирует `EnumerablePropertySource.propertyNames` на префикс `application.ai.description.presets.`. Из yaml имена приходят канонические — работает. Из `SystemEnvironmentPropertySource` они приходят как `APP_AI_DESCRIPTION_PRESETS_…`, и условие вернёт false, хотя `Binder` карту привяжет. Итог: карта есть в свойствах, а каталога нет — молчаливое «описания не работают». Устойчивее спросить `Binder.get(environment).bind("application.ai.description.presets", Bindable.mapOf(String::class, String::class))` или `ConfigurationPropertySources.get(env)`.

**`default-preset` без карты валит старт.** `DescriptionProperties.init` требует `defaultPreset.isBlank() || presets.containsKey(defaultPreset)`. Task 9 Step 3 добавляет `APP_AI_DESCRIPTION_DEFAULT_PRESET` в `.env.example`. Оператор, который скопирует пример со значением и не объявит карту в yaml, получит отказ старта с сообщением «default-preset 'grok-fast' is not declared in presets: ». Стоит либо оставить в примере пустое значение с комментарием, либо ослабить проверку до WARN, когда карта пуста (legacy-путь).

**Из выбора нет пути назад.** После первого клика в `/ai` ключ `ai.description.preset.active` живёт в БД вечно, и `default-preset` из yaml становится мёртвой настройкой. Кнопки «вернуть по умолчанию» нет, а прямой SQL, как честно пишет спека, невидим до рестарта из-за кэша `AppSettingsService`. Дёшево лечится кнопкой, пишущей `""` (резолвер трактует пусто как «нет выбора»).

**🔴 у провайдера не снимается после починки.** `ProviderAuthTracker` переходит в `HEALTHY` только на успешном `describe`. Владелец сделал `grok login`, открыл `/ai` — там по-прежнему 🔴 до следующей записи с детекциями. Это прямое следствие отказа от кнопки «тест», и его стоит хотя бы назвать в тексте экрана («состояние на момент последнего вызова»).

**Потеря покрытия по конкурентности авторизации.** Task 5 переносит сценарии из `DefaultDescriptionAgentTest` в `ProviderAuthTrackerTest`, но все шесть предложенных кейсов трекера однопоточные. Исчезают:
- `a slow listener cannot reorder concurrent auth transitions` (`runBlocking(Dispatchers.IO)` + `CountDownLatch`) — единственный тест, проверяющий смысл существования замка;
- `concurrent Unauthorized failures publish a single LOST` (пять параллельных вызовов);
- `a throwing listener does not discard a successful description` — агентский инвариант, трекером не покрываемый.

Их надо перенести, а не выбросить.

**Слой `api` vs `core` протекает в telegram.** Спека рисует `api/` как «контракт наружу», а `AiSettingsViewStateFactory` (Task 7) импортирует `ru.zinin.frigate.analyzer.ai.description.core.ActivePresetResolver`. Заодно вызов `activePresetId()` из диалога дёргает `warnOnce`, разделяемый с горячим путём `describe`. Чище вынести чтение активного id в `api` (например, метод на `DescriptionPresets` или отдельный `ActivePreset`), а `ActivePresetResolver` оставить внутренним.

**Обоснование `ObjectProvider` в Task 8 неверно.** «при `ai.description.enabled=false` этих бинов нет» — на самом деле `AiSettingsCallbackHandler` и `AiSettingsViewStateFactory` условны только на `application.telegram.enabled`, а у `AiSettingsMessageRenderer` условий нет вовсе. Гейт на `ai.description.enabled` стоит только у `AiSettingsCommandHandler`. Сам `ObjectProvider` безвреден, но комментарий вводит в заблуждение и мешает следующему читателю понять, что можно упростить.

**`List<DescriptionBackendFactory>` в сигнатуре `@Bean`.** При нуле кандидатов Spring бросает `NoSuchBeanDefinitionException`, а не подставляет пустой список. Сейчас обе фабрики есть всегда при `enabled=true`, так что это латентно, но `ObjectProvider<DescriptionBackendFactory>` + `orderedStream().toList()` надёжнее.

**Мёртвые ключи i18n и отсутствие подтверждения.** Спека объявляет `ai.settings.alert.switched / .enabled / .disabled`, план их добавляет в бандлы, но `AiSettingsCallbackHandler` возвращает `alertKey` только для `.unavailable`; на успешное переключение уходит `bot.answer(callback, text = null)`. Либо выдавать тост, либо убрать ключи. `MessageKeyParityTest` мёртвые ключи не поймает.

**Рендер прячет блок авторизации, когда `activePresetId == null`.** В `renderText` ветка `active == null` делает `return@buildString` до цикла по провайдерам. Если каталог есть, но резолвер недоступен (`resolverProvider.getIfAvailable() == null`), экран покажет список кнопок без единой строки состояния.

**Энтрипойнт теряет диагностику опечатки.** Ветка `*)` с «unknown APP_AI_DESCRIPTION_PROVIDER='…'» исчезает, а именно опечатка в этой переменной остаётся мягким случаем на уровне приложения (спека это специально сохраняет). Единственный сигнал уедет в WARN `DescriptionAgentSanityChecker` посреди лога.

**`docker-compose.yml` не в списке Task 9.** Строка 25 комментирует том как «Grok Build home (provider=grok)» — станет неверной.

**Асимметрия строгости.** Карта валидируется строго (неизвестный `provider` — отказ старта), а legacy-`provider` при непустой карте не проверяется вообще: опечатка молча игнорируется. Это защитимо, но стоит хотя бы WARN «`application.ai.description.provider='gemini'` ignored: presets are declared».

**Размер Task 3.** SPI + DTO + каталог + builder + две фабрики + переписывание двух backend-ов + 14 правок аннотаций + полная замена автоконфигурации + переписывание агента + пять тестовых файлов (плюс три незамеченных, C3) — в одном коммите и одном ревью. Аргумент «промежуточное состояние не собирается» верен только для второй половины: шаги 1–4 (SPI, DTO, `DescriptionPresetCatalog`, `DescriptionPresetCatalogBuilder` + его тест) — чистое добавление, ничего не ломающее, и отделяются без всякого ущерба.

---

## Suggestions

1. **Заменить `@ConditionalOnBean(DescriptionPresetCatalog)` на `@Conditional(DescriptionPresetsDeclaredCondition)`** на всех трёх зависимых бинах — исчезает зависимость от порядка `@Bean`-методов и требование фиксировать её KDoc-ом и тестом.
2. **Добавить `DescriptionBackendFactory.prepare(presets: List<Preset>)`** (или `onProviderInUse()`), вызываемый билдером каталога только для провайдеров, встречающихся в карте; перенести туда `GrokBackendFactory.init` и `ClaudeBackendFactory.warnIfCliMissing`. `GrokHomeSweeper` завести через фабрику или закрыть отдельным `Condition`. Снимает C1 целиком.
3. **Опциональный `timeout` в пресете** (`presets.<id>.timeout`, дефолт — `common.timeout`), проброшенный в `withTimeout` агента и в `ClaudeInvoker.invoke`. Снимает C5 и делает `grok-deep` реально переключаемым.
4. **Кнопка «По умолчанию»** в `/ai`, пишущая пустую строку в `ai.description.preset.active`; резолвер уже трактует отсутствие как fallback, достаточно добавить `?.takeIf { it.isNotBlank() }`.
5. **`showAlert = true`** в ответе на клик по недоступному пресету — тост в углу легко пропустить, а это единственное место, где владелец узнаёт причину.
6. **Разделить Task 3** на 3a (api-DTO + `DescriptionBackendFactory` + `DescriptionPresetCatalog` + builder + `DescriptionPresetCatalogBuilderTest`, всё аддитивно) и 3b (фабрики, backend-ы, аннотации, автоконфигурация, агент).
7. Мелочи «план против дерева», которые стоит поправить до исполнения:
   - `GrokCommandBuilderTest` имеет хелпер `props(cliPath, effort, http, https, noProxy, passThroughEnv)`, а не `properties(model, effort)` — предложенный в Task 2 Step 1 тест не скомпилируется;
   - в `GrokBackend.describe` `properties.model` встречается **три** раза (debug запроса, warn про `--json-schema`, debug результата), а не «в двух строках логов»;
   - `RecordingProcessingFacadeTest` не содержит хелперов `notifyDecision()` и `savedResult()` — там `@BeforeEach` со стабами; код Task 6 Step 6 в приведённом виде не соберётся;
   - `MessageKeyParityTest` уже проверяет паритет ключей обоих бандлов — `AiSettingsMessagesTest` из Task 8 Step 6 не нужен;
   - в `DefaultDescriptionAgent` `finally` не видит `entry`, объявленный внутри `try`; вариант «перенести `logger.debug` в тело» из Task 4 Step 5 неверен — он потеряет DEBUG-строку на путях с исключением. Нужен `var presetId: String? = null` до `try`.
8. `DescriptionPresetCatalogBuilder.build` возвращает `null` **и** бросает — три исхода в одной функции. Проще `sealed` результат или две функции; сейчас `checkNotNull(...)` в автоконфигурации с сообщением «No description preset declared while the catalog condition matched» описывает именно ту рассогласованность между `Condition` и билдером, которую предложение 1 или 2 убирает.

---

## Questions

1. **`ownerOnly`** — подтверждаете, что `/ai` должна быть скрыта из меню и из общего раздела `/help`, как остальные owner-команды? Если да, это одна строка в `AiSettingsCommandHandler`; если нет — почему отступаем от `/adduser`, `/removeuser`, `/users`?
2. **Возврат к `default-preset`** — какой предполагается штатный путь после того, как владелец один раз нажал кнопку? Через SQL плюс рестарт, или всё-таки кнопка?
3. **Таймаут** — принимается ли пресетный `timeout`, или сознательно оставляем `grok-deep` пресетом, который требует рестарта для смены `APP_AI_DESCRIPTION_TIMEOUT`?
4. **`GrokHomeSweeper` и проверки окружения** — согласны, что они должны молчать и не работать, когда grok-пресетов не объявлено, или это считается приемлемым шумом?
5. **`ANTHROPIC_MODEL`** — при непустом override и двух claude-пресетах: отказ старта, WARN, или пометка «модель переопределена» на экране `/ai`?
6. **Отказ БД при резолюции** — правильное поведение это fail-open на `catalog.fallback()` (описание всё-таки делается выбранным по умолчанию пресетом) или fail-closed? Сейчас в плане получается третий вариант — исключение из `describe`, то есть fallback-текст в уведомлении.
7. **Экран при рассогласовании** — если сохранённый пресет стал недоступен, `/ai` покажет ✅ на fallback-пресете и ⚠️ на сохранённом, но нигде не скажет «ваш выбор `claude-opus` не применяется». Нужна ли отдельная строка?

---

Ключевые файлы для проверки утверждений выше:
`/opt/github/zinin/frigate-analyzer/modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokHomeSweeper.kt`,
`/opt/github/zinin/frigate-analyzer/modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBot.kt`,
`/opt/github/zinin/frigate-analyzer/modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/HelpCommandHandler.kt`,
`/opt/github/zinin/frigate-analyzer/modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/AppSettingsServiceImpl.kt`,
`/opt/github/zinin/frigate-analyzer/modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeAsyncClientFactory.kt`,
`/opt/github/zinin/frigate-analyzer/modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/config/AiDescriptionAutoConfigurationTest.kt`,
`/opt/github/zinin/frigate-analyzer/modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeBackendIntegrationTest.kt`.

---

## claude:fable

# Ревью дизайна и плана: пресеты AI-описаний и `/ai` (итерация 1)

Документы: `docs/superpowers/specs/2026-09-04-ai-description-presets-design.md`,
`docs/superpowers/plans/2026-09-04-ai-description-presets.md` (3199 строк, прочитан целиком).
План сверен с текущим деревом ветки `feature/grok-description-provider`; ссылки ниже — на реальные
файлы и строки.

Что в плане **сходится** с кодом (проверено, замечаний нет): сигнатуры
`AppSettingsService.getString/setString/getBoolean/setBoolean` (`modules/service/.../AppSettingsService.kt`);
`ProductionYamlBinder.bind(prefix, type, env, properties)` (`modules/core/src/test/.../ProductionYamlBinder.kt:28-33`);
`TelegramNotificationServiceImpl` действительно отсекает `descriptionSupplier == null` **до**
`limiter.tryAcquire()` (`TelegramNotificationServiceImpl.kt:77-103`); `TelegramUserService.isOwner(String?)`,
`findActiveByUsername(String)`; `MessageResolver.get(key, language, vararg)`; `matrix`/`row` из
`dev.inmo.tgbotapi.utils` — как в `NotificationsMessageRenderer`; `bot.answer(callback, text = …)` —
в `tgbotapi.api-jvm 36.1.0` есть `answer(RequestsExecutor, CallbackQuery, String?, Boolean?, String?, Int?)`;
`DescriptionException.Unauthorized.detail`, форма `DescriptionProviderAuthEvent`; `Query.isCliInstalled()`
уже используется в `ClaudeBackend`; две `@ConditionalOnProperty` на одном классе — существующий
паттерн (`ClaudeBackend.kt:22-23`); `setting_value VARCHAR(2048)` (`docker/liquibase/migration/1.0.4.xml:40`)
вмещает id пресета; `DescriptionProperties` не имеет `init` на уровне класса — добавить его можно;
`AppSettingsServiceImpl` кэширует на процесс и сбрасывает только в `setString` (`AppSettingsServiceImpl.kt:64-66,75-86`),
как и заявлено в дизайне.

---

## Critical Issues

### C1. `/ai` без `ownerOnly = true` попадает в общее меню команд и в `/help` всех пользователей

План (Task 8, Step 4) объявляет `AiSettingsCommandHandler` с `requiredRole = UserRole.OWNER` и
`order = 8`, но **не** переопределяет `ownerOnly`. В дереве owner-команды делают и то и другое:
`AddUserCommandHandler.kt:22-24`, `modules/core/.../StatusCommandHandler.kt:29-31`. Именно `ownerOnly`
решает видимость:

- `FrigateAnalyzerBot.registerDefaultCommands()` (`FrigateAnalyzerBot.kt:291-301`) регистрирует в
  `BotCommandScopeDefault` всё, что `filterNot { it.ownerOnly }` → `/ai` окажется в меню каждого
  пользователя;
- `HelpCommandHandler` (`HelpCommandHandler.kt:31-46`) печатает `/ai` в общем списке, а не в разделе
  «Команды владельца».

Роутер отобьёт вызов с `common.error.owner.only`, то есть утечки прав нет, но дизайн обещает
«owner-only команда … при выключенной фиче `/ai` отсутствует и в меню» — при включённой фиче она
будет в меню у всех. Правка одной строкой, но именно потому её легко не заметить. Заодно:
`order = 8` уже занят `/status` (`StatusCommandHandler.kt:31`); тай-брейк по имени поставит `/ai`
перед `/status` — не ошибка, но план представляет 8 как свободный слот.

### C2. Task 3 Step 11 не переписывает ассерты `AiDescriptionAutoConfigurationTest`, которые теперь заведомо ложны

План говорит «существующие сценарии `provider=claude` и `provider=grok` **дополнить** проверкой
каталога» и «`TestStubConfig` не трогать». Но после Task 3:

- `ctx.getBeansOfType(ClaudeBackend::class.java).isNotEmpty()` (`AiDescriptionAutoConfigurationTest.kt:127`)
  и `getBeansOfType(GrokBackend::class.java).isNotEmpty()` (`:143`) станут ложными — backend-ы больше
  не бины, их создаёт фабрика;
- `getBeansOfType(ClaudeBackend::class.java).isEmpty()` и `getBeansOfType(ClaudeAsyncClientFactory::class.java).isEmpty()`
  для `provider=grok` (`:144-145`) и для `unknown` (`:157-159`, с сообщением «Claude helpers must be
  gated on provider=claude») станут ложными в обратную сторону — Step 8 снимает условие
  `provider=<id>` со всех 14 коллаборантов, и `ClaudeAsyncClientFactory` теперь есть в любом
  контексте с `enabled=true`.

Итого три из шести существующих тестов падают, и Step 12 «`./gradlew :frigate-analyzer-ai-description:test`
→ PASS» недостижим по плану как написано. Нужен явный шаг: заменить проверки бинов backend-ов на
проверки `DescriptionPresetCatalog.all()`/`byId(...).backend`, а ассерты «Claude helpers absent» —
удалить или инвертировать с новой формулировкой инварианта.

### C3. Task 3 меняет конструкторы `ClaudeBackend`/`GrokBackend`, но не называет три теста, которые их вызывают

`ClaudeBackendTest.build()` (`ClaudeBackendTest.kt:52-60`) передаёт `claudeProperties = claudeProps`;
`GrokBackendTest.backend()` (`GrokBackendTest.kt:53-61`) передаёт `properties = properties`;
`ClaudeBackendIntegrationTest` (`:122-123`) тоже — он `@Disabled`, но компилируется вместе с модулем.
В Task 3 в списке `Files`/`Test` этих файлов нет (в Task 2 они упомянуты только ради сигнатур
`ClaudeInvoker`/`GrokCommandBuilder.build`). После Step 7 тестовый исходник модуля не компилируется,
и Step 12 снова не проходит. Это не «исполнитель догадается» — план заявляет в Self-review
«каждый шаг с кодом несёт код», а здесь целый класс правок отсутствует.

### C4. Рантайм-выключатель в фасаде — fail-closed и стоит ПОСЛЕ сохранения записи: транзиентная ошибка чтения `app_settings` теряет уведомление без повтора

Task 6 Step 5 вставляет `runtimeSettings.descriptionsEnabled()` в `buildDescriptionSupplier`, который
вызывается на `RecordingProcessingFacade.kt:84` — уже после `recordingEntityService.saveProcessingResult`
(`:59-67`) и `notificationDecisionService.evaluate` (`:70-75`). Если чтение бросит, исключение уйдёт из
`processAndNotify`, `FrameAnalyzerConsumer.finalizeRecording` залогирует ERROR
(`FrameAnalyzerConsumer.kt:118-120`), а запись уже помечена обработанной: уведомление не уйдёт и не
повторится. `AppSettingsServiceImpl` намеренно **не** кэширует неудачные чтения
(`AppSettingsServiceImpl.kt:20-22, 83-86`), так что это не только первый вызов после рестарта, а
каждый вызов, пока ключ не удалось загрузить.

Фасад уже решает ровно эту задачу для глобального флага: он читается **до** `saveProcessingResult`
именно чтобы ошибка оставила запись retryable (`RecordingProcessingFacade.kt:50-57` и комментарий там),
а для signal-флага сервис делает fail-open с WARN (`TelegramNotificationServiceImpl.kt:262-275`).
Новый гейт ломает этот инвариант. Описание — best-effort, потерянное уведомление — нет: гейт
должен быть fail-open (`catch` → `true` + WARN), либо читаться до сохранения, в той же точке, что
`isRecordingNotificationsGloballyEnabled`. Тест на fail-open в план тоже стоит добавить.

---

## Concerns

### K1. `ActivePresetResolver.resolve()` внутри `describe`: сырые исключения из R2DBC и слот семафора без таймаута

Task 4 Step 5 ставит `resolver.resolve()` после downscale — то есть **под** семафором и **вне**
`withTimeout(commonSection.timeout)` (`DefaultDescriptionAgent.kt:62-92`). Два следствия:

1. Исключение из `runtimeSettings.activePresetId()` (тот же некэшированный read из `app_settings`)
   уйдёт из `describe` сырым, мимо контракта `DescriptionException` — агент тратит целый абзац
   (`:73-76`) на то, чтобы downscale так не делал. Фасад завернёт его в `Result.failure`, но слот
   rate limiter уже потрачен, а владелец увидит fallback без внятной причины.
2. Пока висит DB-запрос, удерживается слот семафора без всякого таймаута; при зависшем пуле это
   блокирует `maxConcurrent` описаний целиком.

Рекомендация: в резолвере `try { settings.activePresetId() } catch (e: Exception) { warnOnce(...); null }`
(fail-open на `fallbackId`), и либо резолвить до `semaphore.acquire()`, либо внутри
`withTimeout`. Компромисс: резолв до `acquire` даёт вызову, простоявшему в очереди до
`queueTimeout`, «устаревший» пресет; резолв после — актуальный. Любой выбор годится, если он
задокументирован; нынешняя позиция без таймаута и без перехвата — нет.

### K2. Проводка каталога: логика «что объявлено» продублирована в двух местах и разъедется

`DescriptionPresetsDeclaredCondition` (сканирование имён `EnumerablePropertySource` + проверка
`provider ∈ KNOWN_PROVIDERS`) и `AiDescriptionAutoConfiguration.declaredPresets` (`presets.ifEmpty { when(provider) … }`)
отвечают на один вопрос разным кодом. `checkNotNull(...) { "No description preset declared while the
catalog condition matched" }` — это уже признание, что они могут разойтись. Сканирование имён
хрупко само по себе: оно видит только `EnumerablePropertySource`, не резолвит плейсхолдеры, не
знает про relaxed binding и про `presets: {}` (пустая карта не даёт ни одного имени → legacy-путь,
что случайно совпадает с ожиданием). См. S1/S2 — есть способ свести всё к одной точке истины.

### K3. `@ConditionalOnBean(DescriptionPresetCatalog)` на трёх бинах держится на порядке методов в классе

Дизайн (`design.md:285-288`) и план (KDoc в Task 3 Step 9) честно фиксируют требование «метод
каталога выше агента». Оно верно (`ConfigurationClassBeanDefinitionReader` регистрирует
`@Bean`-методы в порядке объявления), но сегодняшний `@ConditionalOnBean(DescriptionBackend::class)`
надёжен по **другой** причине — backend-ы приходят из `@ComponentScan`, то есть из другой фазы, а
не из соседнего метода. Дизайн ставит между ними знак равенства зря. В Task 4 и Task 5 к цепочке
добавляются `activePresetResolver` и `providerAuthTracker`, и требование «вставлять строго между»
превращается в правило, которое нарушит первый же рефакторинг. Условие для всех четырёх бинов
может быть одним и тем же environment-based условием — тогда порядок не важен вовсе (S1).

### K4. Дизайн и план расходятся в том, как достигается «пустой список → каталога нет»

Фрагмент автоконфигурации в дизайне (`design.md:266-283`) обусловливает каталог только на
`enabled=true` и пишет «Пустой список — каталога и агента нет». `@Bean`-метод так не умеет: `null`
из фабричного метода регистрируется как `NullBean`, а `@ConditionalOnBean` матчится на
**определение**, которое существует. План правильно добавил `DescriptionPresetsDeclaredCondition`,
но в дизайн эта сущность не попала (ни в раздел «Модуль ai-description», ни в «Автоконфигурация»).
Дизайн надо привести к плану, иначе следующий читатель дизайна воспроизведёт нерабочую схему.

### K5. `/ai` показывает **эффективный** пресет, а не сохранённый — вопреки дизайну

Дизайн в «Рисках» обещает: «Резолвер уводит на годный и пишет WARN, экран `/ai` показывает
несоответствие». В плане `AiSettingsViewStateFactory.build` берёт `resolverProvider.getIfAvailable()?.activePresetId()`,
а `ActivePresetResolver.activePresetId()` возвращает `resolve().view.id` — то есть уже
`fallbackId`. Экран нарисует `✅` на fallback-е и ничем не покажет, что владелец выбирал другое.
Сценарий из «живой проверки» («после рестарта активным остаётся выбранный пресет») при исчезнувшем
из конфига id пройдёт «успешно» и обманет. Состоянию экрана нужны оба id — сохранённый и
эффективный (S4).

### K6. Модуль `telegram` импортирует `ru.zinin.frigate.analyzer.ai.description.core.ActivePresetResolver`

Дизайн делит модуль на `api/` («контракт наружу») и `core/`. План в Task 7 тянет из `core`
конкретный класс `ActivePresetResolver` в `AiSettingsViewStateFactory` — единственный случай, когда
потребитель за пределами модуля обращается мимо `api`. Это ровно тот шов, который потом нельзя
будет менять. Нужен `api`-интерфейс (см. S3), тем более что вместе с K5 у него появляется второй
метод.

### K7. Побочные эффекты фабрик у провайдера, которого ни один пресет не использует

После Task 3 обе фабрики — `@Component` на `enabled=true`, независимо от того, есть ли пресеты
этого провайдера. `GrokBackendFactory.init` (план, Task 3 Step 7) создаёт `GROK_HOME` и cwd, пишет
WARN «grok CLI not found» и WARN «No auth.json in …» — то, что сегодня делает `GrokBackend.init`
только при `provider=grok` (`GrokBackend.kt:47-74`). В compose `GROK_HOME=/application/grok-home`
задан всегда (`docker-compose.yml:35`) и том `./grok-home` монтируется всегда (`:27`), так что
**каждый** claude-only деплой после обновления получит на старте WARN про отсутствующий `auth.json`
плюс `GrokHomeSweeper`, который теперь тоже условен только на `enabled=true` и будет ежечасно
обходить чужой каталог (безвредно — `clearDirectory` возвращает 0 на несуществующем каталоге,
`GrokHomeSweeper.kt:69-75`, но это шум и потраченный `@Scheduled`). Симметрично
`ClaudeBackendFactory.warnIfCliMissing` в grok-only локальной разработке. Дизайн обещает, что
«существующие деплои … продолжают работать без правок» — работают, но с новыми предупреждениями,
которые владелец прочитает как поломку. Проверки провайдера должны выполняться только когда он
упомянут хотя бы одним объявленным пресетом (S5).

### K8. `anthropic.model-override` молча перебивает `model` всех claude-пресетов

`ClaudeAsyncClientFactory.buildOptions` ставит `optionsBuilder.model(...)` только при пустом
`anthropic.modelOverride` (`ClaudeAsyncClientFactory.kt:48-50`), а при непустом — экспортирует
`ANTHROPIC_MODEL` в env CLI (`:76-78`). Task 2 сохраняет это поведение и даже добавляет тест «model-override
по-прежнему её вытесняет». С пресетами это значит: два claude-пресета с `model: opus` и
`model: sonnet` при заданном override отправят один и тот же запрос, а `/ai` покажет разные
модели. Нужна либо валидация на старте (override + ≥2 claude-пресетов с разными `model` → ошибка
или WARN), либо честный рендер в `/ai` («model: opus (override: …)»), либо документация, что
override исключает пресетную модель. Сейчас ничего из этого нет.

### K9. Перенос машины состояний в `ProviderAuthTracker` теряет два concurrency-теста

В `DefaultDescriptionAgentTest` есть тесты `a slow listener cannot reorder concurrent auth transitions`
(`DefaultDescriptionAgentTest.kt:279-313`, реальные потоки, `CountDownLatch`) и
`concurrent Unauthorized failures publish a single LOST` (`:370-391`). Именно они охраняют инвариант
«переход и публикация под одним замком», ради которого существует `authTransitionLock`
(`DefaultDescriptionAgent.kt:52-58`). Task 5 Step 4 говорит «сценарии про авторизацию переносятся в
`ProviderAuthTrackerTest`», но приведённый `ProviderAuthTrackerTest` содержит только
последовательные сценарии. Дизайн в разделе «Тестирование» обещает «одно событие на переход при
параллельных отказах» — в плане этого теста нет. Плюс тест `a throwing listener does not discard a
successful description` (`:335-348`) — про агента, а не про трекер: он должен остаться в
`DefaultDescriptionAgentTest` с настоящим `ProviderAuthTracker`.

### K10. `application-docker.yaml.example` делает `APP_AI_DESCRIPTION_DEFAULT_PRESET` мёртвой переменной

Task 9 Step 2 кладёт в профильный yaml литерал `default-preset: grok-fast`. Базовый
`application.yaml` объявляет `default-preset: ${APP_AI_DESCRIPTION_DEFAULT_PRESET:}` — но
профильный файл имеет приоритет над базовым, и env-переменная, которую тот же план добавляет в
`.env.example` (Step 3), перестаёт действовать, как только пользователь скопирует пример. Либо в
примере писать `${APP_AI_DESCRIPTION_DEFAULT_PRESET:grok-fast}`, либо не объявлять `default-preset`
в профильном файле вовсе и держать его только в `.env`.

### K11. Пресет `xhigh` при глобальном `timeout=60s` — дизайн знает о проблеме, но оставляет её на документацию

Замер: 48 с у `grok-4.6 xhigh` при `APP_AI_DESCRIPTION_TIMEOUT=60s` и `TRANSPORT_RETRY_MIN_BUDGET=10s`.
Любой хвост латентности даёт `Timeout`, а повтор по `Transport` не запустится (остаток < 10 с).
Поднять глобальный таймаут значит поднять его и для `grok-fast` (9 с), где 60 с и так с запасом.
Дизайн отверг свободную карту overrides — справедливо; но одно **типизированное** и
валидируемое поле `presets.<id>.timeout` (пусто = глобальный) не открывает ни путь секретам, ни
потерю валидации, а решает ровно ту проблему, ради которой всё затевалось (effort ↔ время).
Минимум — WARN на старте, если у пресета `effort ∈ {xhigh, max}` и `timeout < 90s`.

### K12. `docker-entrypoint.sh` после правки шумит на claude-only деплоях

Новая ветка grok выполняется при непустом `GROK_HOME` — а он в compose непустой всегда
(`docker-compose.yml:35`). Значит claude-only деплой получит `WARN: …/auth.json not found` при каждом
старте — то, чего сегодня не происходит (проверка сидит в `case grok)`, `docker-entrypoint.sh:32-55`).
Дизайн обещает «WARN только на сломанное». Признак «grok действительно нужен» шеллу доступен:
`auth.json` или `config.toml` в `GROK_HOME`, либо `APP_AI_DESCRIPTION_PROVIDER=grok` в `.env`
(legacy). Без такого гейта строку про `auth.json` лучше понизить до INFO.

### K13. In-memory `DescriptionRuntimeSettings` может тихо оказаться в проде

`@ConditionalOnMissingBean`-дефолт (Task 4 Step 5) нужен для тестов модуля, но если по любой
причине `AppSettingsDescriptionRuntimeSettings` не зарегистрируется (условие, опечатка в пакете при
рефакторинге), приложение стартует, `/ai` работает, а выбор молча пропадает на рестарте — ровно
то, что дизайн отвергал как «временный эксперимент». Нужна хотя бы INFO-строка на старте с именем
реализации, а лучше — тест в `core`, что бин `DescriptionRuntimeSettings` это
`AppSettingsDescriptionRuntimeSettings`.

### K14. Мелкие расхождения плана с деревом

- Task 6 Step 6: тесты ссылаются на `notifyDecision()` и `savedResult()` — таких хелперов в
  `RecordingProcessingFacadeTest` нет; сами stub-ы уже стоят в `init` (`RecordingProcessingFacadeTest.kt:94-105`),
  копировать их не нужно.
- Task 3 Step 10: хелпер в `DefaultDescriptionAgentTest` называется `build(...)` (`:84-94`), а не
  `agentOf`; в сниппете `DescriptionProperties(…)` — плейсхолдер.
- Task 3 Step 13: `git add modules/ai-description/src` уже застейджит удаление
  `ClaudeBackendValidationTest.kt` (если файл удалён с диска на Step 7), после чего `git rm` на него
  упадёт. Либо `git rm` на Step 7, либо убрать его из Step 13.
- Task 8 Step 4: комментарий «при `ai.description.enabled=false` этих бинов нет» неверен для
  `AiSettingsViewStateFactory`, `AiSettingsCallbackHandler` и `AiSettingsMessageRenderer` — они
  условны только на `telegram.enabled` (или безусловны). `ObjectProvider` там безвреден, но
  обоснование ложное; условен на ai-флаг только `AiSettingsCommandHandler`.
- Task 3 Step 7 удаляет INFO-строку `Grok description backend: model=…, effort=…`
  (`GrokBackend.kt:71-73`), на которую ссылается `.claude/rules/ai-description.md` («logs model and
  effort at INFO once at startup»); в новой INFO-строке каталога печатаются только id. Стоит
  печатать `id (provider/model/effort)` — тогда и правило останется правдой.
- Trailer коммитов: план зашивает `session_01MppBgCU5bLAPtDtnjoCYVk`, у этой сессии другой id;
  исполнитель должен подставить свой.
- `DescriptionPresetCatalogBuilderTest.a preset without a factory is unusable, not a crash`
  ассертит `IllegalStateException` — имя противоречит телу (единственный пресет негоден → старт
  падает, и это правильно; переименовать).

### K15. Порядок «ответить на коллбэк» в `aip:` отличается от `nfs:`

`nfs:` отвечает на callback **первым** делом, чтобы снять спиннер (`FrigateAnalyzerBot.kt:188-195`).
`aip:` (Task 8 Step 4) сначала ходит в БД (`findActiveByUsername`, `dispatch` с записью), затем
отвечает — потому что тексту alert-а нужен результат. При медленной БД ответ уйдёт позже окна
Telegram (`query is too old`), исключение перехватывается, но спиннер у владельца провисит до
таймаута клиента. Приемлемо для single-owner, но стоит зафиксировать в комментарии, почему
порядок другой, — иначе кто-то «исправит» его обратно.

---

## Suggestions

### S1. Одно environment-условие на все четыре бина вместо цепочки `@ConditionalOnBean`

Каталог, резолвер, трекер и агент должны существовать при одном и том же наборе фактов: `enabled=true`
и «есть что класть в каталог». Оба факта известны из `Environment` до создания любого бина. Если
повесить на все четыре `@Bean`-метода одну и ту же пару `@ConditionalOnProperty(enabled) +
@Conditional(DescriptionPresetsDeclaredCondition)`, то:

- порядок методов перестаёт что-либо значить (K3);
- KDoc «каталог обязан стоять выше агента» и тест на порядок становятся не нужны;
- поведение при «условие сошлось, а builder вернул null» остаётся тем же `checkNotNull` — это
  ошибка конфигурации, и падать на ней правильно.

`@ConditionalOnBean` в проекте останется только там, где он уместен — на бинах из другой фазы.

### S2. Условие «пресеты объявлены» через `Binder`, а не через имена property source-ов

Вместо сканирования `EnumerablePropertySource.propertyNames`:

```kotlin
val presetsBound =
    Binder.get(context.environment)
        .bind("application.ai.description.presets", Bindable.mapOf(String::class.java, Any::class.java))
        .isBound
```

Это тот же механизм, которым Spring биндит `DescriptionProperties`, поэтому он видит все источники,
relaxed binding и плейсхолдеры, а не только `Enumerable`. Ещё лучше — вынести «объявленные пресеты»
в одну функцию `DescriptionPresetDeclarations.resolve(environment)`/`(properties, claude, grok)`,
которой пользуются и условие, и `declaredPresets` в автоконфигурации; тогда K2 исчезает по
построению, а `checkNotNull` со странным сообщением можно убрать.

### S3. `api`-интерфейс для активного пресета вместо `core.ActivePresetResolver` в telegram

```kotlin
// api/
interface ActiveDescriptionPreset {
    /** Что выбрал владелец; null = ничего или ключ отсутствует. */
    suspend fun storedId(): String?
    /** Что реально будет использовано следующим вызовом describe. */
    suspend fun effective(): DescriptionPreset
}
```

`ActivePresetResolver` реализует его (плюс свой `resolve(): Entry` для агента), telegram зависит
только от `api`, и K5/K6 закрываются одним интерфейсом.

### S4. Состояние экрана несёт оба id, рендер показывает несоответствие

`AiSettingsViewState(storedPresetId: String?, effectivePresetId: String?, …)`; когда они различаются,
над списком строка вроде `⚠️ Выбран claude-opus, но он недоступен — работает grok-fast`
(ключ `ai.settings.active.mismatch`). `✅` рисуется у эффективного; сохранённый, если он в списке,
получает отдельную пометку. Это и есть обещанное дизайном «экран показывает несоответствие», и
именно это делает сценарий «убрали токен из `.env`» видимым без чтения логов.

### S5. Проверки провайдера — только для провайдеров, упомянутых пресетами

Перенести создание каталогов, WARN про CLI и `auth.json` из `init` фабрики в `availability()` (с
`lazy`, чтобы выполнялось один раз), а builder вызывает `availability()` только для провайдеров, на
которые ссылается хотя бы один объявленный пресет. Тогда claude-only деплой не трогает `GROK_HOME`
и не видит grok-предупреждений, grok-only — claude-предупреждений. `GrokHomeSweeper` можно оставить
как есть (он безвреден), но условие вида `@ConditionalOnBean(GrokBackendFactory)` там ничего не даст —
фабрика теперь есть всегда; если шум `@Scheduled` мешает, пусть sweeper проверяет
`catalog.all().any { it.provider == "grok" }` через `ObjectProvider<DescriptionPresets>`.

### S6. Fail-open на обоих чтениях `app_settings` в горячем пути

Фасад (C4) и резолвер (K1): `catch (e: CancellationException) { throw e } catch (e: Exception) { WARN; default }`.
Ключи `ai.description.*` — про удобство, не про безопасность; их недоступность не должна стоить
ни уведомления, ни слота лимитера, ни слота семафора. Плюс тесты: «settings read throws → supplier
не null», «settings read throws → resolve() возвращает fallback и логирует один раз».

### S7. Типизированный `presets.<id>.timeout` (опционально) или хотя бы WARN на старте

См. K11. Поле `timeout: Duration? = null` в `Preset`, валидация «пусто или > 0», при непустом —
`withTimeout(preset.timeout)` вместо глобального в `DefaultDescriptionAgent`. Это не «свободная карта
overrides», а второе после `effort` типизированное свойство модели. Если это вне рамок — WARN
`preset 'grok-deep': effort=xhigh with timeout=60s leaves no retry budget` при
`effort ∈ {xhigh, max}` и `timeout < 90s`.

### S8. `anthropic.model-override` и пресеты

Самое дешёвое — валидация в builder-е/фабрике: если `modelOverride` непустой и среди claude-пресетов
больше одной различной `model`, старт падает с текстом, объясняющим, что override исключает
пресетную модель. Альтернатива — в `DescriptionPreset` поле `effectiveModel`, которое фабрика
заполняет override-ом, и `/ai` печатает его.

### S9. Тесты трекера с реальными потоками

Перенести в `ProviderAuthTrackerTest` оба concurrency-сценария из `DefaultDescriptionAgentTest`
(`:279-313`, `:370-391`) — теперь без агента они станут короче: два потока зовут
`onUnauthorized`/`onSuccess` напрямую под `runBlocking(Dispatchers.IO)` с тем же `CountDownLatch`.
Добавить «два провайдера не делят замок»: медленный слушатель на `grok` не задерживает событие
`claude`.

### S10. Один INFO о том, где живут рантайм-настройки

В `AppSettingsDescriptionRuntimeSettings` и в `InMemoryDescriptionRuntimeSettings` — по строке
`Description runtime settings: app_settings` / `in-memory (choice does not survive restart)` при
создании бина. Закрывает K13 почти бесплатно; тест в `core` на класс бина — ещё лучше.

### S11. Энтрипойнт: проверять grok только когда он нужен

```sh
grok_intended=false
[ -f "${GROK_HOME:-}/auth.json" ] || [ -f "${GROK_HOME:-}/config.toml" ] || [ "${APP_AI_DESCRIPTION_PROVIDER:-}" = "grok" ] && grok_intended=true
```

и весь grok-блок под этим флагом; иначе строка про `auth.json` — INFO. Тогда обещание «WARN только на
сломанное» выполняется и для claude-only деплоя.

### S12. Пример `application-docker.yaml`: `default-preset` через плейсхолдер или не объявлять

`default-preset: ${APP_AI_DESCRIPTION_DEFAULT_PRESET:grok-fast}` в примере, и в `.env.example`
оговорка, что профильный yaml выигрывает у базового. Или не объявлять в примере вовсе — при пустом
значении дефолт всё равно «первый годный», и для типичного файла с `grok-fast` первым это то же
самое.

### S13. Ужесточить границу ключей карты на этапе биндинга

Relaxed binding для `Map<String, …>` без квадратных скобок удаляет из ключа всё, что не
буквенно-цифровое/`-`/`.`, и (вопрос Q4) может менять регистр. `PRESET_ID` в `init` поймает часть
случаев, но ключ вроде `grok fast` доедет как `grokfast` и пройдёт. Достаточно упомянуть в
документации примера: «id — только `[a-z0-9-]`, иначе используйте `"[…]"`-нотацию», либо
добавить в `DescriptionPresetCatalogBuilder` INFO со списком id, чтобы искажение было видно в логе
(это уже есть — оставить).

### S14. Порядок задач

Task 3 действительно неделима; но Task 2 можно расширить так, чтобы Task 3 стала меньше:
перенос `AUTH_RECOVERY_HINT` в companion, вынос `cliAvailable()` из `GrokBackend` в
`GrokEnvironmentChecks`/фабрику-«проверяльщик», и снятие `provider=<id>` с коллабораторов
(Step 8) — всё это компилируется и без каталога (при `provider=claude` grok-коллабораторы просто
станут бинами, что и так произойдёт в Task 3). Тогда Task 3 остаётся только про фабрики, каталог и
автоконфигурацию.

### S15. Замечание к обоснованию «yaml, а не env»

Дизайн пишет, что `.env` «карт не умеет без индексных имён». Точнее: Spring биндит карты из
переменных окружения (`APPLICATION_AI_DESCRIPTION_PRESETS_GROKFAST_MODEL` → ключ `grokfast`), но
ключи не могут содержать `-` и теряют регистр. Решение остаётся верным (yaml читабельнее и
сохраняет id), но аргумент стоит поправить, чтобы не спорить с фактами.

---

## Questions

**Q1.** Как должны вести себя claude-пресеты при заданном `anthropic.model-override` (K8): падение
на старте, WARN, или override считается «ещё одним слоем» и просто документируется?

**Q2.** Приемлемо ли, что claude-only деплой после обновления получит WARN про `auth.json` и
`grok CLI` на каждом старте плюс ежечасный sweep чужого каталога (K7, K12)? Если нет — S5/S11
нужно включить в план, а не оставлять на «потом».

**Q3.** Экран `/ai` должен показывать расхождение «сохранён X / работает Y» (как обещает дизайн)
или только эффективный пресет (как делает план)? От ответа зависит форма `api`-интерфейса (S3/S4).

**Q4.** Проверялось ли, что relaxed binding сохраняет регистр ключа карты? Если ключ `GrokFast`
из yaml биндится как `grokfast`, регекс `PRESET_ID` его пропустит, а `callback_data` и логи будут
показывать не то, что написано в конфиге. Достаточно одного теста в `DescriptionPresetsBindingTest`
с ключом в верхнем регистре, чтобы зафиксировать факт в ту или другую сторону.

**Q5.** Per-preset `timeout` (S7) — в рамках этой работы или отдельная итерация? Если отдельная,
стоит ли хотя бы WARN на старте?

**Q6.** Что ожидается от `/ai` после рестарта для провайдера, который до рестарта был `LOST`:
`⚪ ещё не вызывался` (как сейчас по плану — трекер стартует с `UNKNOWN`) или состояние стоит
пересчитать первым вызовом? Владелец, получивший 🔴-алерт вечером и открывший `/ai` утром после
`docker compose up -d`, увидит белый кружок при всё ещё протухшем `auth.json`.

**Q7.** Резолв активного пресета — до `semaphore.acquire()` (стабильный выбор на момент постановки
в очередь) или после (актуальный на момент старта)? Обе семантики защитимы; план выбирает вторую
молча.

**Q8.** Какой `Claude-Session` trailer использовать при исполнении — из плана или текущей сессии?
План зашивает чужой id (K14).

---

## codex-executor

Использую обязательный навык `using-superpowers`, затем полностью прочитаю `CLAUDE.md`, дизайн и весь план. После этого сверю ключевые шаги плана с фактическими сигнатурами и Spring/coroutine/Telegram-проводкой в коде; файлы менять не буду.Навык требует также прочитать его инструкцию для среды Codex. Затем я зафиксирую структуру и объём документов и буду читать план последовательными диапазонами, чтобы не потерять детали конкретных патчей.Дизайн прочитан полностью. Теперь прохожу план по задачам и одновременно отмечаю проверяемые утверждения (условия бинов, точные сигнатуры, изменения конструкторов и тестовые ожидания); после чтения плана отдельно открою соответствующие текущие файлы, а не буду доверять вставкам в документе.План прочитан целиком, включая все вставки кода. Уже видны несколько внутренних противоречий (особенно вокруг условий автоконфигурации, области auth-состояния и жизненного цикла callback), но прежде чем классифицировать их как критические, сверяю их с текущим деревом и API библиотеки, которые реально подключены.Сверка с кодом подтвердила два конкретных регресса: новый DB-гейт читается уже после отметки записи обработанной, а callback `/ai` подтверждается только после DB-записи. Сейчас проверяю ещё две вещи, влияющие на обратную совместимость: фактическую семантику `@ConditionalOnProperty` для legacy provider и набор компиляционных call-site’ов, который план не обновляет.### Critical Issues

1. **Spring-проводка опирается на недокументированный порядок.** Текущий `@ConditionalOnBean(DescriptionBackend)` работает после `@ComponentScan`; это не эквивалент новой цепочке из нескольких `@Bean`-методов одной автоконфигурации. План последовательно условно объявляет каталог, резолвер и агент и полагается на порядок методов ([план:1359](/opt/github/zinin/frigate-analyzer/docs/superpowers/plans/2026-09-04-ai-description-presets.md:1359)). Перестановка при обработке метаданных может оставить часть цепочки без бинов или дать ошибку зависимости. Дополнительно `DescriptionPresetsDeclaredCondition`:

   - видит только `EnumerablePropertySource`;
   - распознаёт только имена с `presets.`, но не bracket-форму `presets[id]`;
   - молча превращает пропущенную карту в «пресетов нет».

   Нужна одна условная вложенная конфигурация, внутри которой каталог, резолвер и агент объявляются обычными зависимыми бинами. Наличие карты следует определять через Spring `Binder`, а не сканированием имён property source.

2. **Legacy-совместимость уже нарушается регистром provider.** Сейчас `@ConditionalOnProperty(... havingValue="claude|grok")` сравнивает значения без учёта регистра, поэтому работающий деплой с `APP_AI_DESCRIPTION_PROVIDER=CLAUDE` активирует Claude. Новый condition и `declaredPresets()` используют регистрозависимые `in`/`when` ([план:1303](/opt/github/zinin/frigate-analyzer/docs/superpowers/plans/2026-09-04-ai-description-presets.md:1303)). Такой деплой после обновления тихо останется без агента. Legacy provider нужно нормализовать через `trim().lowercase()` и покрыть mixed-case тестом.

3. **Неиспользуемый Grok начнёт выполнять побочные действия во всех AI-конфигурациях.** План снимает provider-condition с `GrokHomeSweeper` и всех Grok-компонентов ([план:1263](/opt/github/zinin/frigate-analyzer/docs/superpowers/plans/2026-09-04-ai-description-presets.md:1263)). При этом новая Grok-фабрика в конструкторе создаёт каталоги, проверяет файловую систему, а sweeper спустя минуту удаляет `sessions/` и `logs/`. В результате Claude-only или даже soft-case с неизвестным legacy provider:

   - трогает `GROK_HOME`;
   - может упасть из-за невалидного Grok path;
   - запускает ненужную периодическую очистку.

   Сейчас sweeper корректно ограничен provider-condition ([GrokHomeSweeper.kt:30](/opt/github/zinin/frigate-analyzer/modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokHomeSweeper.kt:30)). Фабрики должны быть пассивными, а provider-инфраструктура — активироваться только при наличии хотя бы одного соответствующего пресета.

4. **Auth-состояние нельзя агрегировать только по provider.** Grok OAuth и Grok BYOK используют разные учётные данные. Текущий mapper классифицирует `invalid api key` как `Unauthorized` ([GrokExceptionMapper.kt:68](/opt/github/zinin/frigate-analyzer/modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokExceptionMapper.kt:68)), но recovery hint всегда предлагает `grok login` ([GrokBackend.kt:35](/opt/github/zinin/frigate-analyzer/modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokBackend.kt:35)).

   Реальный сценарий:

   - OAuth-пресет получает `Unauthorized` → `grok=LOST`;
   - владелец переключается на рабочий BYOK → успех публикует `RESTORED`;
   - UI показывает весь Grok здоровым, хотя OAuth по-прежнему сломан.

   Возможен и обратный порядок при незавершённых вызовах. Нужен `authScopeId`/credential realm, например `grok:oauth` и `grok:byok:<profile>`, с отображением состояния по пресету или auth scope. Два OAuth-пресета тогда по-прежнему смогут разделять одно состояние.

5. **Ошибка чтения runtime-флага может навсегда потерять основное уведомление.** Фасад сначала сохраняет результат и помечает запись обработанной ([RecordingProcessingFacade.kt:59](/opt/github/zinin/frigate-analyzer/modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/facade/RecordingProcessingFacade.kt:59)), затем строит supplier, а план именно туда добавляет suspend-чтение `descriptionsEnabled()` ([план:2229](/opt/github/zinin/frigate-analyzer/docs/superpowers/plans/2026-09-04-ai-description-presets.md:2229)). Это чтение находится до `try`, защищающего Telegram-отправку. При сбое R2DBC исключение выйдет наружу после сохранения, а уведомление не будет поставлено в очередь. Это противоречит уже зафиксированному инварианту «settings failure before processed state is saved» ([RecordingProcessingFacadeTest.kt:198](/opt/github/zinin/frigate-analyzer/modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/facade/RecordingProcessingFacadeTest.kt:198)). Для необязательного AI безопасное поведение — залогировать ошибку, отключить описание для этой записи и всё равно отправить основное уведомление.

6. **Резолюция пресета удерживает глобальный семафор вне work-timeout.** Агент сначала захватывает permit ([DefaultDescriptionAgent.kt:62](/opt/github/zinin/frigate-analyzer/modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/core/DefaultDescriptionAgent.kt:62)), а план вызывает suspend-резолвер после downscale и до `withTimeout` ([план:1771](/opt/github/zinin/frigate-analyzer/docs/superpowers/plans/2026-09-04-ai-description-presets.md:1771)). На холодном кэше это запрос в БД. При зависании БД все permits могут оказаться заняты корутинами, ожидающими один `AppSettingsService.cacheMutex`, после чего остальные вызовы истекут по queue timeout. Резолюцию следует делать до захвата permit либо включать в общий ограниченный timeout; ещё лучше — держать активный ID в атомарном runtime snapshot, предварительно загруженном из БД.

7. **`/ai` не будет owner-only в меню и `/help`.** В плане установлен только `requiredRole = OWNER` ([план:2920](/opt/github/zinin/frigate-analyzer/docs/superpowers/plans/2026-09-04-ai-description-presets.md:2920)), но не `ownerOnly = true`. У `CommandHandler` это независимое поле с дефолтом `false` ([CommandHandler.kt:14](/opt/github/zinin/frigate-analyzer/modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/CommandHandler.kt:14)); именно оно фильтрует обычное меню и help ([HelpCommandHandler.kt:31](/opt/github/zinin/frigate-analyzer/modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/HelpCommandHandler.kt:31)). Команда будет отклонять вызов пользователя, но отображаться всем пользователям. Нужны `override val ownerOnly = true` и тест метаданных команды.

8. **Callback lifecycle и повторная авторизация реализованы некорректно.**

   - В отличие от существующего `nfs:`, который первым делом вызывает `answer` ([FrigateAnalyzerBot.kt:188](/opt/github/zinin/frigate-analyzer/modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBot.kt:188)), план сначала делает DB lookup и suspend-запись, и лишь затем отвечает callback ([план:2960](/opt/github/zinin/frigate-analyzer/docs/superpowers/plans/2026-09-04-ai-description-presets.md:2960)). Исключение или зависание оставит spinner до истечения Telegram timeout; дефолтный marker дополнительно заблокирует следующие `aip:` клики этого пользователя.
   - Ранние `return` при отсутствии любого `ObjectProvider` также происходят без `answer`.
   - Команда авторизуется case-insensitive, а callback использует `findActiveByUsername`, который в реальном сервисе делает регистрозависимый запрос ([TelegramUserServiceImpl.kt:37](/opt/github/zinin/frigate-analyzer/modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramUserServiceImpl.kt:37)). Владелец с изменившимся регистром username сможет открыть `/ai`, но клики молча перестанут работать.

   Callback должен гарантированно подтверждаться в каждом исходе, а авторизация — идти через `AuthorizationFilter.authorize(username)` или case-insensitive lookup.

9. **`ANTHROPIC_MODEL` обнуляет смысл Claude-пресетов.** План сознательно сохраняет приоритет `anthropic.modelOverride` над переданным `model` ([план:498](/opt/github/zinin/frigate-analyzer/docs/superpowers/plans/2026-09-04-ai-description-presets.md:498)); текущее поведение видно в [ClaudeAsyncClientFactory.kt:48](/opt/github/zinin/frigate-analyzer/modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeAsyncClientFactory.kt:48). При непустом `ANTHROPIC_MODEL` все Claude-пресеты фактически вызывают одну модель, тогда как `/ai` показывает разные. Нужно либо запретить эту комбинацию, либо сделать override legacy-only, либо показывать эффективную модель.

10. **План в текущем виде не проходит заявленные промежуточные сборки.** Конкретные расхождения с деревом:

   - [ClaudeBackendIntegrationTest.kt:121](/opt/github/zinin/frigate-analyzer/modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeBackendIntegrationTest.kt:121) напрямую создаёт и `ClaudeBackend`, и `DefaultDescriptionAgent`; конструктор агента меняется в Tasks 3, 4 и 5, но этот тест не указан ни в одной из них.
   - [ClaudeBackendTest.kt:52](/opt/github/zinin/frigate-analyzer/modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/claude/ClaudeBackendTest.kt:52) и [GrokBackendTest.kt:48](/opt/github/zinin/frigate-analyzer/modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/grok/GrokBackendTest.kt:48) используют удаляемые параметры конструкторов, но Task 3 их не обновляет.
   - Текущий автоконфигурационный тест требует Spring-бины `ClaudeBackend`/`GrokBackend` и отсутствие helpers чужого provider ([AiDescriptionAutoConfigurationTest.kt:127](/opt/github/zinin/frigate-analyzer/modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/config/AiDescriptionAutoConfigurationTest.kt:127)); новая архитектура утверждает прямо обратное. План предлагает лишь «дополнить» эти сценарии.
   - Код тестов Task 6 вызывает отсутствующие `notifyDecision()` и `savedResult()` ([план:2271](/opt/github/zinin/frigate-analyzer/docs/superpowers/plans/2026-09-04-ai-description-presets.md:2271)).

   Значит, обещание PASS после каждой задачи невыполнимо без незапланированных правок.

### Concerns

1. **Тест-план не проверяет центральные concurrency-инварианты.** Дизайн обещает тест «резолвер вызывается один раз и retry не меняет backend» и общий семафор двух пресетов ([дизайн:406](/opt/github/zinin/frigate-analyzer/docs/superpowers/specs/2026-09-04-ai-description-presets-design.md:406)), но в Tasks 3–5 добавляется только helper. Тест «parallel failures» у `ProviderAuthTracker` вызывает методы последовательно. Нет также теста callback acknowledgment, `ownerOnly`, выбора DB-реализации вместо in-memory и ошибки runtime-setting после save.

2. **Тест биндинга не фиксирует порядок.** Дизайн полагается на порядок YAML, но предложенный assertion использует `containsExactlyInAnyOrder` ([план:321](/opt/github/zinin/frigate-analyzer/docs/superpowers/plans/2026-09-04-ai-description-presets.md:321)). Это не защищает выбор «первого годного» пресета.

3. **Определение “available” противоречиво.** Каталог должен падать при нуле годных пресетов, но обе фабрики оставляют отсутствующий CLI всего лишь WARN-ом, а Grok всегда возвращает `Available`. Следовательно, конфигурация без единого запускаемого backend-а считается годной и стартует. Нужно чётко разделить `CONFIGURED`, `RUNTIME_AUTH_UNKNOWN` и `UNAVAILABLE_STATIC`.

4. **Сохранённый и эффективный preset смешаны.** При удалённом или недоступном сохранённом ID резолвер возвращает fallback, а state factory отдаёт только эффективный ID. Поэтому экран не может показать обещанное дизайном «несоответствие» и не сообщает, что после восстановления старый preset снова автоматически станет активным. Также нет команды «снова следовать default-preset»: после первого ручного выбора очистить ключ можно только SQL плюс рестарт.

5. **`APP_AI_DESCRIPTION_DEFAULT_PRESET` из `.env` будет проигрывать примеру docker YAML.** Базовый YAML содержит placeholder, но внешний `application-docker.yaml` из Task 9 задаёт прямое `default-preset: grok-fast`. Более приоритетное внешнее значение не позволит placeholder прочитать env. В примере нужно использовать `default-preset: ${APP_AI_DESCRIPTION_DEFAULT_PRESET:grok-fast}` либо не обещать env override.

6. **Кэш закрепляет single-instance архитектуру.** Запись через один экземпляр инвалидирует только его локальный кэш; другие экземпляры продолжат использовать старый preset/флаг неограниченно долго. Если multi-instance действительно исключён, это нужно явно зафиксировать в спецификации этой функции, а не только в примечаниях `/notifications`.

7. **`xhigh` слишком близок к timeout и ухудшает справедливость общего семафора.** 48 секунд при лимите 60 секунд не оставляют нормального запаса на jitter; два таких вызова при `maxConcurrent=2` уже заставляют третий ждать дольше стандартного queue timeout 30 секунд. После переключения на быстрый preset старые медленные вызовы продолжают занимать общую очередь. Нужен хотя бы startup WARN для опасной комбинации или типизированный per-preset timeout.

8. **“Сколько угодно пресетов” не сочетается с одной непагинированной клавиатурой.** Нет верхнего предела количества пресетов и длины model. Большая карта создаёт неограниченную клавиатуру и неудобный экран; ktgbotapi клиентского лимита не накладывает. Нужен разумный `@Size` либо пагинация.

9. **Сырые причины недоступности и auth detail пересекают UI trust boundary.** `unavailableReason` проектируется как произвольная строка фабрики и показывается без локализации. Кроме того, текущий notifier прикладывает первые 300 символов технической ошибки ([DescriptionAuthAlertNotifier.kt:121](/opt/github/zinin/frigate-analyzer/modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/application/DescriptionAuthAlertNotifier.kt:121)). Для BYOK провайдер может вернуть адрес или часть ключа. Утверждение «секреты не попадут в Telegram» пока не обеспечено типом, sanitization или тестом.

10. **Factory SPI лишь выглядит расширяемым.** `DescriptionBackendFactory` зависит от `config.DescriptionProperties.Preset`, provider-ы одновременно захардкожены в `KNOWN_PROVIDERS` и `when` автоконфигурации. Добавление третьего provider потребует менять центральный код. Кроме того, `availability()` обещана «один раз», но builder вызывает её для каждого пресета одного provider.

### Suggestions

1. Ввести независимый от Spring-конфига `PresetDefinition` и API фабрики вида `prepare(preset): PreparedBackend | Unavailable`. Результат должен содержать backend, безопасный reason code и `authScopeId`.

2. Собрать условную проводку в одной вложенной `@Configuration`, активируемой условием на основе `Binder`. Внутри убрать `@ConditionalOnBean` между собственными методами.

3. Сделать отдельный публичный `AiDescriptionControl`/snapshot API: эффективный ID, сохранённый ID, enabled, список пресетов и auth-состояния. Telegram тогда не будет импортировать внутренний `core.ActivePresetResolver` и собирать потенциально несогласованный snapshot из четырёх `ObjectProvider`.

4. Хранить runtime-состояние в `AtomicReference`/`StateFlow`, загружая его из БД при старте и обновляя после успешной записи. Это уберёт suspend DB-read из semaphore hot path; БД останется источником восстановления после рестарта.

5. Разделить callback на чистый разбор/валидацию и побочные эффекты. Callback следует подтвердить гарантированно и быстро; запись и перерисовку выполнять после acknowledgment с отдельной обработкой ошибки.

6. Добавить матрицу интеграционных тестов: legacy lower/mixed case; dot и bracket property names; только Claude без пригодного Grok path; только Grok без Claude token; core DB adapter вместо in-memory; retry со сменой setting; два backend-а под одним семафором; OAuth/BYOK auth scopes; меню обычного пользователя; DB exception до callback answer.

### Questions

1. Что является настоящей единицей auth-health: provider, credential profile или preset? Как должен отображаться одновременно рабочий BYOK и сломанный Grok OAuth?

2. Должен ли `ANTHROPIC_MODEL` оставаться поддерживаемым при непустой карте presets? Если да, что именно `/ai` обязан показывать как model?

3. При недоступном сохранённом preset нужно сохранять намерение владельца и автоматически возвращаться к нему после восстановления, либо переписать выбор на fallback? Как владелец сбрасывает ручной выбор к config default?

4. Что именно означает runtime «Выключить»: только не запускать новые описания или также отменять уже ожидающие semaphore/in-flight вызовы и edit jobs?

5. Входит ли отсутствие CLI/неисполняемый path в понятие «негодный preset», при котором ноль годных обязан валить старт?

6. Single-instance является постоянным ограничением проекта или эта функция должна корректно работать при двух экземплярах приложения?
---

## grok-executor (grok-4.6)

Сверил оба документа с деревом на `feature/grok-description-provider`. Ниже — расхождения с фактическими сигнатурами и поведением, а не с намерениями спеки.

### Critical Issues

**1. `/ai` попадёт в меню и `/help` всех пользователей**

У `CommandHandler.ownerOnly` дефолт `false`. Все owner-команды в дереве ставят оба поля: `requiredRole = OWNER` и `ownerOnly = true` (`AddUserCommandHandler`, `RemoveUserCommandHandler`, `UsersCommandHandler`).

План для `AiSettingsCommandHandler` задаёт только `requiredRole = OWNER` и `order = 8`. Тогда:

- `FrigateAnalyzerBot.registerDefaultCommands()` отправит `/ai` в `BotCommandScopeDefault`;
- `HelpCommandHandler` покажет её в общем списке, не в блоке владельца;
- обычный пользователь получит `common.error.owner.only`.

Это ломает заявленный контракт «owner-only команда».

**2. Аналогия с `@ConditionalOnBean(DescriptionBackend)` неверна — агент может тихо не создаться**

Сейчас `@ConditionalOnBean(DescriptionBackend)` работает потому, что backend — `@Component` из `@ComponentScan` той же автоконфигурации. Скан регистрирует бины **до** `@Bean`-методов. Это прямо сказано в KDoc `AiDescriptionAutoConfiguration`.

План ставит `@ConditionalOnBean(DescriptionPresetCatalog)` на `descriptionAgent` и `activePresetResolver`, то есть на соседний `@Bean` **того же** класса, и предлагает положиться на порядок методов в исходнике. Это другой механизм:

- Spring Boot не обещает, что sibling-`@Bean` виден `OnBeanCondition`;
- Kotlin не гарантирует, что порядок методов в байткоде совпадёт с порядком в файле (`allopen` / новые методы это легко сдвинут).

Если условие не увидит каталог: каталог есть, агента нет, `DescriptionAgentSanityChecker` пишет WARN, описания никогда не вызываются, `/ai` при этом рисует пресеты. Тест «агент создаётся» это поймает один раз на этой версии компилятора, но KDoc закрепит ложную гарантию.

Надёжный вариант: вложенный `@Configuration` под тем же `DescriptionPresetsDeclaredCondition` (каталог + резолвер + агент) или то же custom-condition на всех трёх методах, без `@ConditionalOnBean`.

**3. `AiDescriptionAutoConfigurationTest` после Task 3 не соберётся в том виде, как его оставляют**

Сейчас тесты проверяют:

- `getBeansOfType(ClaudeBackend)` / `GrokBackend` — непусто;
- при `provider=grok` и unknown provider — `ClaudeAsyncClientFactory` отсутствует;
- при grok — `ClaudeBackend` отсутствует.

После Task 3 backend — обычный объект внутри каталога, не Spring-бин. Коллабораторы висят только на `enabled=true`. Значит:

- `getBeansOfType(GrokBackend)` будет пуст даже в успешном сценарии;
- `ClaudeAsyncClientFactory` появится при grok и при опечатке в `provider`.

Task 3 Step 11 говорит «существующие сценарии дополнить проверкой каталога» и «`TestStubConfig` не трогать» — переписать эти assert'ы шаг не велит. `./gradlew :frigate-analyzer-ai-description:test` на Step 12 упадёт. То же с прямыми конструкторами `GrokBackend(properties = …)` и `ClaudeBackend(claudeProperties = …)` в `GrokBackendTest` / `ClaudeBackendTest`: в списке файлов Task 3 их нет.

**4. Экран `/ai` не показывает обещанное «несоответствие» сохранённого и реального пресета**

Спека: если сохранённый пресет исчез или стал недоступен, резолвер уходит на fallback, а `/ai` это показывает.

План: `AiSettingsViewState.activePresetId = resolver.activePresetId()`, а `activePresetId()` возвращает уже **резолвнутый** fallback. Рендер ставит `✅` на него. Сохраненный битый id выглядит как обычная кнопка.

Итог: владелец не видит, что его выбор перекрыт; WARN только в логе; кликать по fallback незачем, поэтому битый ключ в `app_settings` живёт вечно. Нужны оба id (stored vs resolved) и явная строка в тексте.

**5. BYOK и grok-oauth делят одну лампочку авторизации и одну подсказку `grok login`**

Спека одновременно говорит «два grok-пресета делят `auth.json`» и приводит карту `grok-fast` / `grok-deep` / `byok-luna`. `ProviderAuthTracker` ключует по `providerId == "grok"`. `GrokBackend.authRecoveryHint` — константа про `grok login --device-code`.

`codex-luna` ходит по ключу из `config.toml`, grok-4.6 — по `auth.json`. Отказ одного красит обоих, шлёт RESTORED/LOST на весь провайдер и предлагает `grok login` для протухшего BYOK-ключа (`GrokExceptionMapper` уже классифицирует `invalid api key` как `Unauthorized`). Для двух effort-пресетов одной модели это верно; для смеси, которую спека сама рекламирует, — нет.

**6. Инфраструктура обоих провайдеров поднимается при любом `enabled=true`**

Сейчас `@ConditionalOnProperty(provider=claude|grok)` отсекает чужие бины. После снятия условия:

- опечатка в `APP_AI_DESCRIPTION_PROVIDER` больше не «мягкая»: создаются обе фабрики, `GrokHomeSweeper`, CLI-проверки;
- claude-only стенд создаёт `GROK_HOME`, пишет WARN про отсутствие `auth.json` и CLI, раз в час метёт `sessions/`;
- `GrokBackendFactory.init` по-прежнему бросает `IllegalStateException`, если каталоги не создаются. Это валит старт даже при годных claude-пресетах и прямо противоречит правилу «негодный пресет помечается, старт падает только если не годен ни один».

`availability()` у Grok всегда `Available`, поэтому этот отказ нельзя превратить в `⚠️` на пресете — он убивает контекст раньше каталога.

**7. Коллбэки `aip:` отвечают Telegram после записи, без `finally`**

`nfs:` сначала делает `bot.answer(callback)`, потом пишет. Quick Export отвечает в каждой ранней ветке. План для `aip:` вызывает `dispatch()` (upsert в БД) и только потом `answer`. Исключение из `setActivePresetId` / `setDescriptionsEnabled` попадает в внешний `catch`, который **не** гасит спиннер.

Нужен `answer` в `finally` или схема nfs: ответ сразу, тост для `ALERT` — вторым вызовом нельзя (один ответ на callback). Значит: `try/finally { answer }`, а текст алерта считать до ответа.

---

### Concerns

**`DescriptionPresetsDeclaredCondition` и EnumerablePropertySource.** Для `application-docker.yaml` (обычный `OriginTrackedMapPropertySource`) префикс `application.ai.description.presets.` сработает. Для env-карты вида `APPLICATION_AI_DESCRIPTION_PRESETS_GROKFAST_PROVIDER` имена источника **не** начинаются с canonical-префикса: condition скажет «карты нет», а `@ConfigurationProperties` карту всё-таки свяжет. Если при этом `provider` неизвестен — каталога не будет при непустом `presets`. Обратное (мусорные ключи с этим префиксом) дойдёт до `checkNotNull(build())` и уронит старт. Спека обещает «как сегодня при опечатке»; condition это не гарантирует.

**Рантайм-выключатель читается после save, вне try/catch Telegram.** `buildDescriptionSupplier` вызывается после `saveProcessingResult` и **до** `try { sendRecordingNotification }`. `descriptionsEnabled()` идёт в БД (первый раз после рестарта). Исключение из `AppSettingsService` всплывает из `processAndNotify`; `FrameAnalyzerConsumer.finalizeRecording` ловит его уже после того, как запись помечена обработанной — уведомление теряется, ретрая нет. Глобальный флаг уведомлений для сравнения читается **до** save. Здесь нужен fail-open (`true`) в `catch`, как у расписания.

**Гейт выключателя только в момент сборки supplier.** Между `buildDescriptionSupplier` и `descriptionSupplier.invoke()` в `TelegramNotificationServiceImpl` (очередь, лимитер) владелец может нажать «Выключить». In-flight describe и edit всё равно уйдут. Обратно: включение не догонит уже собранный `null`. Для кнопки без «теста» это приемлемо, но в спеке этого нет.

**`xhigh` ~48 с при `timeout=60s`.** Повтор `InvalidResponse` требует остаток ≥ 5 с, `Transport` — ≥ 10 с плюс пауза 5 с. Первая попытка на 48 с почти гарантированно съедает бюджет повтора; внутренний schema-fallback Grok сидит в той же попытке. `/ai` это не показывает. Документировать в yaml недостаточно: переключение из диалога не требует рестарта и не поднимает timeout.

**Глобальный семафор + очередь 30 с.** Два `xhigh` занимают оба слота на ~48 с; третий `describe` упрётся в `queueTimeout` и уйдёт в fallback. Спека говорит, что семафор — свойство машины, но диалог не предупреждает, что «deep» меняет вместимость очереди.

**`ANTHROPIC_MODEL` вытесняет модель пресета.** Task 2 это тестирует. Два claude-пресета (`opus` / `sonnet`) при непустом `anthropic.model-override` станут одним и тем же вызовом. В `/ai` кнопки будут врать. Это следствие «инфраструктура остаётся в секции провайдера», и его надо явно написать в configuration.md и на экране.

**Мёртвые ключи i18n.** `ai.settings.alert.switched` / `.enabled` / `.disabled` объявлены, диспетчер на успех всегда возвращает `RERENDER` без `alertKey`. Либо тост, либо не заводить ключи.

**`ownerOnly` — не единственная дыра в команде.** `AiSettingsCommandHandler` висит на `telegram.enabled` **и** `ai.description.enabled`; фабрика, рендер и callback-handler — только на `telegram.enabled`. Комментарий «при `ai.description.enabled=false` этих бинов нет, поэтому ObjectProvider» ложен. ObjectProvider тогда лишний; старые кнопки `aip:` после выключения фичи в `.env` продолжат жить и упрутся в `IGNORE` из-за отсутствия `DescriptionRuntimeSettings`.

**Ранний `return@buildString` в рендере.** Если `activePresetId == null` при непустом списке (резолвер не создался, каталог создался), пропадают строки авторизации, остаётся «пресеты не настроены» и кнопки пресетов.

**Порядок карты не зафиксирован биндингом.** Спека: Spring биндит в `LinkedHashMap`, тест это фиксирует. Фактический `DescriptionPresetsBindingTest` использует `containsExactlyInAnyOrder`. Порядок проверяет только builder с `linkedMapOf`. Для yaml это обычно так, гарантии в тесте нет.

**`default-preset` при пустой карте.** `require(defaultPreset.isBlank() || presets.containsKey(defaultPreset))` уронит биндинг, если в `.env` уже прописали `APP_AI_DESCRIPTION_DEFAULT_PRESET=grok-fast`, а блок `presets` в yaml ещё не добавили. Миграция «сначала env, потом yaml» не стартует.

**Пример `application-docker.yaml.example` — живые значения, не комментарий.** Непустая карта отключает `APP_AI_DESCRIPTION_PROVIDER`. Копирование примера поверх текущего claude-`.env` внезапно переключит дефолт на `grok-fast`.

**Entrypoint теряет WARN про fail-fast Claude.** Сейчас нет токена + `provider=claude` → WARN «application will FAIL at startup» до стектрейса. После — INFO «claude presets will be marked unavailable», а legacy-синтез одного claude-пресета без токена по-прежнему валит старт (`ноль годных`). Самый частый misconfig станет выглядеть как мягкий INFO плюс падение JVM.

**Чтение `AppSettingsService` под семафором агента.** `resolve()` после downscale, ещё держа слот. Кэш без TTL, так что после прогрева это дёшево; первый вызов после рестарта — R2DBC на горячем пути describe. Имеет смысл резолвить до `semaphore.acquire()` (и получить свежий пресет, если ждали слот).

**Интеграционные тесты авторизации не переносятся.** В `DefaultDescriptionAgentTest` есть `a slow listener cannot reorder concurrent auth transitions` — это обоснование замка. `ProviderAuthTrackerTest` в плане параллельности не содержит; `get/set` под lock заменяет `compareAndSet`. Без этого теста регрессия порядка LOST/RESTORED снова возможна. Спека обещает тест «резолюция один раз на вызов» и «общий семафор на два пресета» — в плане их нет.

**Слой telegram → `ai.description.core`.** `AiSettingsViewStateFactory` тянет `ActivePresetResolver` из `core/`, не из `api/`. Модуль и сейчас импортирует `ratelimit` и `config`, так что это не новый разрыв, но публичный контракт спеки (`DescriptionPresets` + `DescriptionRuntimeSettings`) для диалога недостаточен: fallback `default-preset` иначе придётся дублировать.

**Task 6: хелперы `notifyDecision()` / `savedResult()` не существуют.** В `RecordingProcessingFacadeTest` эти stub'ы уже стоят в `init`; `facade()` и `captureSupplierDuring` есть. Сниппет с выдуманными функциями просто не скомпилируется. Новый параметр конструктора через хелпер — правильный путь, лишние `coEvery` в тестах гейта не нужны.

**Конструктор агента трижды.** Осознанно, но Task 3 при этом оставляет модуль в состоянии, где агент **всегда** берёт `catalog.fallback()`, игнорируя будущий SPI. Это нормально только если Task 3 и 4 мержатся до деплоя; промежуточный коммит на стенде переключать пресеты ещё нельзя.

**Кэш `AppSettingsService`.** Как у `/notifications`: прямой SQL невидим, `updatedBy` пишется, DEBUG логирует значение ключа (`grok-fast` / `false` — не секрет). Ок, но два новых ключа стоит явно добавить в оговорку `database.md` про рестарт после SQL.

---

### Suggestions

**Гейт фабрик по фактическому использованию провайдера.** В `init`/`@PostConstruct` смотреть `DescriptionProperties`: есть ли grok-пресет или legacy `provider=grok`. Нет — не создавать каталоги, не предупреждать про `auth.json`, не запускать sweeper. Каталог по-прежнему может держать фабрику в `List`; `availability()` для неиспользуемого провайдера не вызывается. Это возвращает изоляцию сегодняшнего `@ConditionalOnProperty(provider=…)`, не требуя парсить карту в `Condition`.

**Не опираться на порядок `@Bean`.** Вложенная конфигурация:

```kotlin
@Configuration
@Conditional(DescriptionPresetsDeclaredCondition::class)
class PresetBeans { /* catalog, inMemory, resolver, agent */ }
```

Одно условие, без `@ConditionalOnBean` на sibling-методах.

**Состояние авторизации для Grok — по «домену учётных данных», не по `providerId`.** Минимум: не смешивать в примере oauth и BYOK; для BYOK — другой `authRecoveryHint` («проверьте `api_key`/`env_key` в `config.toml`»). Лучше: ключ трекера `preset.id` с дедупом событий для пресетов с одним `GROK_HOME` и пустым отличием только в `effort`.

**`/ai`: stored vs resolved, hint по timeout/effort.** Строка «сохранён grok-deep, сейчас используется grok-fast (недоступен: …)». Для `xhigh` — предупреждение, что при дефолтных 60 с повтора нет. Это дешевле кнопки «тест».

**`ownerOnly = true`, `order = 8`.** Восьмой слот свободен (notifications=7, adduser=10). В `/help` владельца `/ai` встанет сразу после `/notifications`.

**Fail-open на `descriptionsEnabled()`.**

```kotlin
val enabled = try {
    runtimeSettings?.descriptionsEnabled() ?: true
} catch (e: Exception) {
    logger.warn(e) { "…" }
    true
}
```

Не терять уведомление из-за чтения необязательного ключа.

**Резолв пресета до семафора.** Слот не держится на R2DBC; переключение во время ожидания очереди попадёт в этот же вызов.

**Entrypoint: если нет ни токена Claude, ни `GROK_HOME`/`auth.json` — WARN уровня «старт, скорее всего, упадёт».** INFO по каждому провайдеру отдельно оставить.

**Пример yaml — закомментированный блок**, с явной строкой «непустой `presets` отключает `APP_AI_DESCRIPTION_PROVIDER`».

**Тесты, которые спека обещает и план теряет:** резолв один раз на retry (переключить `InMemory` между попытками); общий семафор на два backend-а; `warnOnce`; concurrent LOST/RESTORED на трекере; биндинг порядка yaml-карты (`containsExactly`, не `InAnyOrder`); whitespace-токен Claude (`isBlank()`, сейчас это покрывает удаляемый `ClaudeBackendValidationTest`).

**Не таскать `ActivePresetResolver` в telegram.** Либо `DescriptionPresets.resolvedActiveId(): String?` / `fallbackId` в api, либо фабрика сама делает `stored?.takeIf { available } ?: firstAvailable`, но тогда дублируется правило `default-preset`. Лучше расширить api.

**Лог переключения в INFO.** Сейчас будет только `AppSettings: 'ai.description.preset.active' set by owner` без значения на INFO (значение на DEBUG). Для операции «какая модель сейчас» этого мало.

---

### Questions

1. Смесь grok-oauth и BYOK в одной карте — осознанный продукт или пример перетянули из замеров? Если продукт, как владелец должен читать одну 🔴 на двоих?

2. Нужен ли тост на успешное переключение/выключатель (`alert.switched` и т.д.), или ключи лишние?

3. Должен ли рантайм-выключатель действовать на in-flight `describe`, или достаточно следующего recording?

4. `APP_AI_DESCRIPTION_TIMEOUT` остаётся глобальным навсегда, или для `xhigh` допустим опциональный `presets.<id>.timeout`? Сейчас ловушка переключается без рестарта, а timeout — нет.

5. Промежуточный коммит Task 3 (агент всегда на `fallback()`) можно выкатывать на стенд, или Tasks 3–5 считаются одним деплоем?

6. Живой `application-docker.yaml` на стенде уже смонтирован. Добавление `presets` в example — это предложение копировать в прод-файл в том же PR #44, или отдельно, чтобы не смешивать с провайдером Grok?

7. Для claude-only деплоя новые WARN про grok CLI/`auth.json` приемлемы или это регрессия операторского UX?

---

Архитектура в целом (пресет = provider+model+effort, один агент, SPI как у `TempFileWriter`, выбор в `app_settings`, без fallback-цепочек) согласована с ограничениями модуля и с `/notifications`. Ломается не идея, а проводка: условия бинов, owner-меню, смешение oauth/BYOK, и то, что диалог врёт про активный пресет в ровно том сценарии, который спека называет риском.
