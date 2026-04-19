# Merged Design Review — Iteration 1

**Date:** 2026-04-19
**Design:** `docs/superpowers/specs/2026-04-19-ai-description-design.md`
**Plan:** `docs/superpowers/plans/2026-04-19-ai-description-plan.md`
**Review agents (7):** codex (gpt-5.4), gemini (3.1 Pro), ccs-glm, ccs-albb-glm, ccs-albb-qwen, ccs-albb-kimi, ccs-albb-minimax

---

## codex-executor (gpt-5.4, REASONING_LEVEL=xhigh)

### [CRITICAL] DI-1: `ai-description` модуль не подключён в Spring context
**Файл/раздел:** Design §3/§5; Plan Task 12, Task 13.Step 4
**Описание:** Все новые бины живут в пакете `ru.zinin.frigate.analyzer.ai.description`, а приложение сканирует `ru.zinin.frigate.analyzer.core`. План добавляет `@EnableConfigurationProperties`, но не добавляет ни `@AutoConfiguration`, ни `@ComponentScan`, ни `@Import` для нового модуля; в текущем проекте `telegram` решает ту же проблему через `TelegramAutoConfiguration` и `META-INF/spring/...AutoConfiguration.imports`.
**Риск:** При `enabled=true` фича останется фактически выключенной: `DescriptionAgent` не создастся, `ObjectProvider` всегда вернёт `null`, стартовая валидация токена/CLI не сработает.
**Рекомендация:** Сделать для `ai-description` отдельный auto-configuration модуль по образцу `telegram` и зарегистрировать его через `AutoConfiguration.imports`; заодно добавить минимальный context test на бин `DescriptionAgent`.

### [CRITICAL] SDK-1: План неправильно собирает `ClaudeAsyncClient`
**Файл/раздел:** Design §5; Plan Task 7.Step 3, Task 11.Step 1
**Описание:** В Task 7 `cliPath` прокидывается в `workingDirectory(Paths.get(cliPath))`, хотя в SDK 1.0.0 это разные вещи: `workingDirectory(...)` обязателен, а путь к бинарю задаётся через `claudePath(...)`. Дополнительно `startupTimeout` объявлен в конфиге, но в wiring не используется вообще.
**Риск:** Либо получите `IllegalArgumentException("workingDirectory is required")`, либо CLI запустится из неверной директории, а кастомный путь к `claude` и отдельный startup-timeout останутся нерабочими.
**Рекомендация:** Строить клиента как `ClaudeClient.async(options).workingDirectory(<cwd>).claudePath(<cliPath?>).timeout(<startupTimeout/transport timeout>)...build()`, а не подменять `workingDirectory` значением `cliPath`.

### [CRITICAL] TELEGRAM-1: Media-group reply flow написан против неверного API
**Файл/раздел:** Design §6; Plan Task 16.Step 4
**Описание:** План использует `replyToMessageId` у `sendTextMessage` и обращается к результату `sendMediaGroup()` как к списку (`firstOrNull()`), но в текущем `ktgbotapi 32.0.0` `sendTextMessage` принимает `ReplyParameters`, а `sendMediaGroup` возвращает один `ContentMessage<MediaGroupContent<...>>`, не `List`.
**Риск:** Код и тесты media-group ветки не соберутся, а захват `messageId` для reply-сообщения будет реализован неверно.
**Рекомендация:** Хранить объект, который возвращает `sendMediaGroup`, брать у него `messageId` и вызывать `sendTextMessage(..., replyParameters = ReplyParameters(chatIdObj, group.messageId), ...)`.

### [MAJOR] CONFLICT-1: Timeout-семантика в плане расходится со spec
**Файл/раздел:** Design §7; Plan Task 8.Step 3, Task 10.Step 1, Task 10.Step 3
**Описание:** Spec требует нормализовать общий timeout в `DescriptionException.Timeout`, но план ловит внутри retry-loop любой `Throwable`, маппит его через `ClaudeExceptionMapper`, а тест в Task 10 ожидает сырой `TimeoutCancellationException`. Это ломает контракт spec и смешивает cancellation с transport-error.
**Риск:** Timeout будет логироваться и обрабатываться как transport, возможен лишний retry-path после таймаута, а Telegram-слой потеряет корректную классификацию причины fallback.
**Рекомендация:** На границе SDK-вызова отдельно rethrow `CancellationException`, явно маппить `TimeoutCancellationException` в `DescriptionException.Timeout` и синхронизировать с этим тесты Task 10.

### [MAJOR] CONFIG-1: `language` не валидируется по контракту spec
**Файл/раздел:** Design §4; Plan Task 3.Step 1, Task 4.Step 3, Task 9.Step 2
**Описание:** В design язык ограничен `ru|en` и должен валидироваться на старте, а в плане это просто `@NotBlank`; более того, `ClaudePromptBuilder` на неизвестный код тихо сваливается в English.
**Риск:** Неверная конфигурация уйдёт в production незамеченной, а AI начнёт отвечать на другом языке, чем ожидает оператор.
**Рекомендация:** Сделать `language` enum-ом или добавить `@Pattern(regexp = "ru|en")`, убрать silent fallback в builder и добавить отдельный validation test на startup.

### [MAJOR] CONFLICT-2: План нарушает лимит "до 10 кадров" из design
**Файл/раздел:** Design §2; Plan Task 14.Step 7; `modules/core/src/main/resources/application.yaml`
**Описание:** Design фиксирует "все оригинальные JPEG, но до 10", а Task 14 строит `DescriptionRequest` из всех `request.frames`. В текущем приложении extraction limit уже `50`, а лимит `10` есть только у визуализации.
**Риск:** В AI-пайплайн уйдёт до 50 изображений на запись вместо 10, что резко увеличит latency, стоимость и размер временных файлов.
**Рекомендация:** Добавить явный шаг отбора максимум 10 кадров до построения `DescriptionRequest`, причём отбор должен быть детерминированным и согласованным с тем, что пользователь видит в уведомлении.

### [MAJOR] CODE-1: Порядок `frameIndex → stagedPath` в плане неустойчив
**Файл/раздел:** Plan Task 4.Step 3, Task 6.Step 4, Task 14.Step 7; `RecordingState.kt`
**Описание:** `ClaudeImageStager` сортирует кадры перед staging, `ClaudePromptBuilder` потом zip-ит пути с исходным `request.frames`, а `RecordingState.getFrames()` сейчас возвращает `ConcurrentHashMap.values()` без стабильного порядка.
**Риск:** Claude увидит кадры не в той хронологии, в которой они были сняты, и описание станет неточным и плохо воспроизводимым.
**Рекомендация:** Сортировать кадры один раз до `DescriptionRequest` и передавать дальше уже связанный набор `(frame, path)`; повторно zip-ить несортированные `frames` с отсортированными `paths` нельзя.

### [MAJOR] TELEGRAM-2: HTML path не экранирует базовый текст уведомления
**Файл/раздел:** Plan Task 15.Step 4, Task 16.Step 4; `TelegramNotificationServiceImpl.kt`
**Описание:** При `descriptionHandle != null` single-photo caption уходит в `parseMode=HTML`, но formatter экранирует только AI-строки. Базовый `task.message` содержит `camId` и `filePath`, то есть внешние данные, и сейчас передаётся как raw HTML.
**Риск:** Символы `<`, `>` или `&` в имени камеры/файла дадут Telegram 400 на send/edit; из-за `RetryHelper.retryIndefinitely` это может превратиться в бесконечный ретрай одного и того же битого payload.
**Рекомендация:** Экранировать base text внутри `DescriptionMessageFormatter` для всех HTML-сценариев.

### [MAJOR] TELEGRAM-3: Ограничение 1024 символов для caption обрабатывается небезопасно
**Файл/раздел:** Plan Task 16.Step 4, Task 16.Step 3
**Описание:** Placeholder HTML сначала приклеивается к caption, а потом режется `substring(0, 1024)`, что может разрезать тег или entity. При этом caption после успешного/fallback edit вообще не ограничивается после добавления short/fallback текста.
**Риск:** Telegram отклонит initial send или последующий edit по длине/битому HTML.
**Рекомендация:** Считать бюджет до добавления HTML-хвоста, резать plain text заранее и проверять длину уже финального caption HTML-aware способом и на send, и на edit.

### [MAJOR] ERROR-1: Один failed edit блокирует второй edit того же target
**Файл/раздел:** Plan Task 16.Step 3
**Описание:** В `DescriptionEditJobRunner.editOne()` single-photo caption edit и details text edit завернуты в один `try`. Если caption edit падает, update подробного текста уже не выполняется.
**Риск:** Пользователь видит навсегда застрявший placeholder в reply-сообщении, хотя сломался только caption update.
**Рекомендация:** Разделить caption-edit и details-edit на независимые `try/catch`.

### [MAJOR] ARCH-1: Describe-job стартует слишком рано и тратит AI без получателей
**Файл/раздел:** Design §5; Plan Task 14.Step 7; `TelegramNotificationServiceImpl.kt`
**Описание:** Facade запускает `describe()` до того, как Telegram-слой проверит `detectionsCount`, наличие подписчиков и факт enqueue. В текущем коде `TelegramNotificationServiceImpl` легально может выйти раньше, ничего не отправив.
**Риск:** Claude будет вызываться на записи, для которых в итоге не будет ни одного уведомления.
**Рекомендация:** Сделать запуск ленивым: переносить создание handle в `TelegramNotificationServiceImpl` после фильтрации подписчиков, либо передавать в Telegram-слой supplier.

### [MAJOR] TEST-1: TDD-сниппеты в плане не совпадают с текущими API и тестовым стилем
**Файл/раздел:** Plan Task 14.Step 5, Task 16.Step 1
**Описание:** В примерах есть `coEvery` для не-suspend методов (`getIfAvailable()`, `build()`), `RecordingDto` создаётся по устаревшему конструктору, а sender tests пытаются мокать extension-функции `bot.sendTextMessage`/`bot.sendMediaGroup`, хотя текущий тестовый код проекта ловит underlying `bot.execute(...)`.
**Риск:** Исполнитель плана будет получать красные тесты и compile errors из-за неверных примеров.
**Рекомендация:** Переписать сниппеты на актуальные конструкторы и MockK-паттерны проекта.

---

## gemini-executor (3.1 Pro)

### [CRITICAL] CONFLICT-1: Конфликт способа создания бина ClaudeDescriptionAgent
**Файл/раздел:** `ClaudeDescriptionAgent.kt` (Task 9) и `ClaudeAgentConfig.kt` (Task 12)
**Описание:** Дизайн-документ (раздел 5) явно указывает, что `ClaudeDescriptionAgent` должен быть помечен `@Component` и `@ConditionalOnProperty("application.ai.description.provider", ...)`. В плане же в Task 9 класс создаётся без аннотаций, а в Task 12 инстанцируется вручную через `@Bean`. В то же время все вспомогательные классы (`ClaudePromptBuilder`, `ClaudeResponseParser` и др.) в плане помечены `@Component` без условий (Task 4-8).
**Риск:** Рассинхронизация с архитектурой. Засорение ApplicationContext ненужными бинами при отключенной фиче.
**Рекомендация:** Убрать `ClaudeAgentConfig` из Task 12. Развесить `@Component` и `@ConditionalOnProperty(...)` на все классы агента в пакете `claude/` согласно дизайну.

### [CRITICAL] ERROR-1: Проглатывание CancellationException при маппинге ошибок
**Файл/раздел:** `ClaudeExceptionMapper.kt` (Task 8)
**Описание:** Маппер перехватывает неизвестные ошибки веткой `else -> DescriptionException.Transport(throwable)`. Если корутина отменяется (вызовом cancel или по тайм-ауту через `withTimeout`), генерируется `CancellationException`. Оно будет перехвачено и превращено в `Transport`.
**Риск:** Полная поломка структурированной конкурентности. При отмене корутины агент залогирует "Claude transport error", попытается сделать retry, вызовет `delay(5s)`, который выбросит новое `CancellationException`.
**Рекомендация:** В начале метода `map()` добавить: `if (throwable is CancellationException) throw throwable`.

### [CRITICAL] CODE-1: Рассинхронизация сортировки кадров и путей при сборке промпта
**Файл/раздел:** `ClaudePromptBuilder.kt` (Task 4)
**Описание:** В `ClaudeImageStager.stage` кадры сортируются по `frameIndex`, и возвращается список путей в этом отсортированном порядке. Однако в `ClaudePromptBuilder` оригинальный список `request.frames` объединяется с путями через `zip`: `request.frames.zip(framePaths)`.
**Риск:** Если `request.frames` придёт неотсортированным, то `zip` привяжет путь от 0-го кадра к 2-му. Нейросеть получит перемешанную хронологию.
**Рекомендация:** В `ClaudePromptBuilder.build` сначала отсортировать исходный список: `val sortedFrames = request.frames.sortedBy { it.frameIndex }`, а затем `sortedFrames.zip(framePaths)`.

### [MAJOR] COROUTINE-1: Тайм-аут не включает время ожидания семафора
**Файл/раздел:** `ClaudeDescriptionAgent.kt` (Task 10)
**Описание:** Блок `withTimeout(...)` находится *внутри* блока `semaphore.withPermit`. Тайм-аут начинает тикать только после получения разрешения.
**Риск:** Если очередь забита, последние задачи будут ждать своей очереди неограниченное время.
**Рекомендация:** Поменять их местами: `withTimeout(timeout) { semaphore.withPermit { ... } }`.

### [MAJOR] TELEGRAM-1: Отсутствие HTML-экранирования пользовательского базового текста
**Файл/раздел:** `DescriptionMessageFormatter.kt` (Task 15)
**Описание:** При наличии описания Telegram-уведомления отправляются с `parseMode = HTMLParseMode`. Однако пользовательский текст (`baseText` / `captionBase`), содержащий имена камер и зон детекции, не экранируется.
**Риск:** Если в имени зоны есть символы `<` или `&` (например, `Zone <Entrance>`), Telegram API отклонит запрос.
**Рекомендация:** В `DescriptionMessageFormatter` экранировать `baseText` через `htmlEscape()`.

### [MAJOR] TELEGRAM-2: Жёсткая обрезка текста ломает HTML-разметку
**Файл/раздел:** `TelegramNotificationSender.kt` (Task 16)
**Описание:** Метод `String.toCaption(MAX_CAPTION_LENGTH)` делает обычный `substring(0, maxLength)`. Обрезка может разорвать тег пополам.
**Рекомендация:** Делать `toCaption(MAX_CAPTION_LENGTH - margin)` *до* добавления HTML-разметки.

### [MAJOR] CODE-2: Обращение к Claude с пустым массивом кадров
**Файл/раздел:** `RecordingProcessingFacade.kt` (Task 14)
**Описание:** В методе `startDescribeJob` нет проверки на пустоту списка кадров.
**Риск:** Пустая трата токенов, которая скорее всего приведёт к "галлюцинациям" AI.
**Рекомендация:** Добавить проверку: `if (request.frames.isEmpty()) return null`.

### [MAJOR] SDK-1: Использование cliPath в качестве workingDirectory
**Файл/раздел:** `ClaudeAsyncClientFactory.kt` (Task 7) и интеграционный тест (Task 23)
**Описание:** Если `cliPath` настроен, код передаёт его в `builder.workingDirectory(Paths.get(cliPath))`. В интеграционном тесте в качестве `cliPath` передаётся абсолютный путь к файлу.
**Риск:** Попытка запустить процесс с файлом в качестве `workingDirectory` вызовет ошибку ОС `ENOTDIR`.
**Рекомендация:** Если SDK не позволяет задать путь к исполняемому файлу, то `cliPath` должен принимать путь к папке. В тесте передавать путь до временной директории.

### [MAJOR] DOCKER-1: Файл настроек Claude CLI создаётся в неверной директории
**Файл/раздел:** `Dockerfile` (Task 18)
**Описание:** Dockerfile создаёт файл по пути `/home/appuser/.claude/settings.json`. Однако приложение работает в `WORKDIR /application`. Claude CLI ищет `.claude/settings.json` в рабочей директории.
**Риск:** Отключение апдейтера и ripgrep может не сработать.
**Рекомендация:** Записывать файл настроек в `/application/.claude/settings.json`.

### [MAJOR] CONFIG-1: Валидация языка не соответствует требованиям дизайна
**Файл/раздел:** `DescriptionProperties.kt` (Task 3)
**Описание:** Дизайн указывает: `language ∉ {"ru", "en"} → падение`. Однако в properties стоит только `@field:NotBlank`.
**Рекомендация:** Добавить `@field:Pattern(regexp = "^(ru|en)$")`.

### [MINOR] TEST-1: Отсутствует тест на ограничение конкурентности (Semaphore)
**Файл/раздел:** `ClaudeDescriptionAgentTest.kt` (Task 10)
**Описание:** В разделе 9 спецификации прямо указано требование: `Concurrency: 3 вызова при maxConcurrent=2`. В реализации тестов в Task 10 данный кейс пропущен.
**Рекомендация:** Добавить тест.

---

## ccs-executor (GLM-5.1, direct)

### [CRITICAL] DI-1: RecordingProcessingFacade напрямую зависит от условного бина DescriptionCoroutineScope
**Файл/раздел:** Plan Task 14, Plan Task 13
**Описание:** `RecordingProcessingFacade` объявляет прямую зависимость `private val descriptionScope: DescriptionCoroutineScope`. При этом `DescriptionCoroutineScope` — `@Component` с `@ConditionalOnProperty(havingValue = "true")`. Когда `enabled=false` (значение по умолчанию), бин не создаётся, Spring не может инжектнуть его в `RecordingProcessingFacade` → приложение не стартует.
**Рекомендация:** Заменить на `ObjectProvider<DescriptionCoroutineScope>` или убрать `@ConditionalOnProperty` с `DescriptionCoroutineScope`.

### [CRITICAL] TELEGRAM-1: `toCaption(1024)` ломает HTML-теги placeholder
**Файл/раздел:** Plan Task 16 (`TelegramNotificationSender`), строки ~2565–2570
**Описание:** Начальный caption формируется как `captionBase + "\n\n" + placeholder`. Если `captionBase` близок к 1024, сумма превышает лимит, `toCaption(MAX_CAPTION_LENGTH)` обрезает тег `<i>`.
**Рекомендация:** Урезать `captionBase` до `1024 - placeholder.length - 2` при `withDescription=true`.

### [MAJOR] SDK-1: `cliPath` используется как `workingDirectory` вместо пути к CLI
**Файл/раздел:** Plan Task 7, строка ~883
**Рекомендация:** Если SDK поддерживает `builder.cliPath()`, использовать его. Если нет — переименовать конфиг в `working-dir`.

### [MAJOR] TELEGRAM-2: Отредактированный caption может превысить 1024 символа
**Файл/раздел:** Plan Task 16 (`DescriptionEditJobRunner.editOne`)
**Описание:** `formatter.captionSuccess(...)` возвращает `baseCaption + "\n\n" + htmlEscape(result.short)`. `baseCaption` уже урезан до 1024. Добавление `short` даёт до ~1226 символов.
**Рекомендация:** Урезать `baseCaption` с запасом `shortMaxLength + 2` на этапе создания `EditTarget`.

### [MAJOR] COROUTINE-1: Retry delay(5s) сжигает бюджет timeout, маскируя реальную ошибку
**Файл/раздел:** Plan Task 10 (`ClaudeDescriptionAgent.executeWithRetry`)
**Описание:** Если первый Transport error на 55-й секунде (при timeout=60s), 5-секундный delay исчерпает timeout → `TimeoutCancellationException` вместо `Transport`.
**Рекомендация:** Либо retry delay за пределы timeout, либо уменьшить задержку и добавить WARN-лог с оставшимся бюджетом.

### [MINOR] ARCH-1: `TempFileWriter` в пакете `claude/` вместо `api/`
**Файл/раздел:** Plan Task 6
**Рекомендация:** Переместить `TempFileWriter` в пакет `api/`.

### [MINOR] CONFIG-1: Отсутствует валидация language при старте
**Рекомендация:** Добавить `require(language.lowercase() in setOf("ru", "en"))`.

### [MINOR] COROUTINE-2: DescriptionEditJobRunner scope без @PreDestroy
**Рекомендация:** Передавать scope извне или сделать класс @Component с @PreDestroy.

### [MINOR] PLAN-1: YAML-секция добавляется поздно (Task 17) — приложение не стартует между Task 13–17
**Файл/раздел:** Plan Task 13 vs Task 17
**Рекомендация:** Перенести YAML-секцию в Task 13 или добавить default-значение через `@DefaultValue`.

### [MINOR] DI-2: Нет WARN при enabled=true, но provider не имеет реализации
**Рекомендация:** Добавить `@PostConstruct` WARN-лог.

### [NIT] CODE-1: `ClaudeExceptionMapper` — ложное срабатывание на "429" в произвольном тексте
**Рекомендация:** Уточнить паттерн: `"429" in message && ("http" in message || "rate" in message)`.

### [NIT] TEST-1: expandableBlockquoteSuccess не тестирует HTML-escape detailed
**Рекомендация:** Добавить отдельный тест.

---

## ccs-executor (Alibaba GLM / glm-5)

### [CRITICAL] SDK-1: cliPath интерпретирован как рабочая директория
**Файл/раздел:** Task 7 Step 3, ClaudeAsyncClientFactory.kt:883-885
**Рекомендация:** Проверить SDK API: возможно есть `cliPath()` метод в `CLIOptions.builder()`.

### [CRITICAL] SDK-2: .use {} на ClaudeAsyncClient (НЕ AutoCloseable)
**Файл/раздел:** Task 11 Step 1, DefaultClaudeInvoker.kt:1494
**Описание:** `clientFactory.create(...).use { client -> ... }` — в учтённых правках указано "ClaudeAsyncClient НЕ AutoCloseable". Код противоречит.
**Рекомендация:** Убрать `.use {}`.

### [CRITICAL] SDK-3: connect() вызван без awaitSingle()
**Файл/раздел:** Task 11 Step 1, DefaultClaudeInvoker.kt:1495
**Описание:** `client.connect()` — в учтённых правках "connect() → Mono<Void>". Mono требует подписки/await, но код вызывает как suspend.
**Рекомендация:** `client.connect().awaitSingle()` или убрать connect() если SDK auto-connects.

### [CRITICAL] DI-1: DescriptionCoroutineScope @ConditionalOnProperty ломает startup при enabled=false
**Файл/раздел:** Task 13 Step 3, DescriptionCoroutineScope.kt
**Рекомендация:** Убрать `@ConditionalOnProperty` (учтённая правка уже говорит это, но план всё ещё содержит).

### [CRITICAL] COROUTINE-1: DescriptionEditJobRunner scope без lifecycle management
**Файл/раздел:** Task 16 Step 3, DescriptionEditJobRunner.kt:2445-2446
**Рекомендация:** Inject scope из Spring, или @PreDestroy shutdown метод.

### [MAJOR] PLAN-1: NoOpTelegramNotificationService не обновляется
**Файл/раздел:** Task 14 Step 2-4
**Описание:** Уже учтённая правка говорит это сделано, но план не отражает.
**Рекомендация:** Добавить Step в Task 14: обновить NoOpTelegramNotificationService.kt.

### [MAJOR] TELEGRAM-1: HTML truncation после escape ломает entities
**Рекомендация:** Truncate BEFORE escape, или awareness of HTML entities.

### [MAJOR] COROUTINE-2: CancellationException не rethrown в edit job
**Файл/раздел:** Task 16 Step 3, DescriptionEditJobRunner.kt:2514-2516
**Рекомендация:** Добавить `if (e is CancellationException) throw e`.

### [MAJOR] CONFIG-1: language validation missing в @PostConstruct
**Рекомендация:** `check(language.lowercase() in setOf("ru", "en"))`.

### [MAJOR] TEST-1: Retry delay test не использует virtual time advance
**Файл/раздел:** Task 10 Step 1 — test "retries once on Transport"
**Рекомендация:** Добавить `advanceTimeBy(5_000)` после первого вызова.

### [MAJOR] TEST-2: Missing concurrency test
**Файл/раздел:** Task 10
**Рекомендация:** Добавить test для semaphore behavior.

### [MAJOR] DI-2: Dependency order violation — ClaudeImageStager needs TempFileWriter
**Файл/раздел:** Task 6 vs Task 13
**Рекомендация:** Переставить Task 13 BEFORE Task 6.

### [CONFLICT] ARCH-1: Scope implementation отличается design ↔ plan
**Рекомендация:** Sync design и plan.

### [MAJOR] DI-3: DescriptionEditJobRunner не @Component, создаётся inline
**Рекомендация:** Добавить `@Component`, inject в sender.

### [MAJOR] PLAN-2: Interface change ломает existing callers
**Рекомендация:** Использовать default parameter в interface.

### [MINOR] TELEGRAM-2: Пустой i18n key .with.description не используется
**Рекомендация:** Убрать пустой key.

### [MINOR] CODE-1: withTimeout timeout.toMillis() redundant
**Рекомендация:** `withTimeout(commonSection.timeout)` напрямую.

### [MINOR] PLAN-3: Missing .gitkeep removal
**Рекомендация:** Добавить note о удалении .gitkeep.

---

## ccs-executor (Alibaba Qwen / qwen3.5-plus)

### [CRITICAL] ARCH-1: Противоречие Design ↔ Plan по структуре модуля ai-description
**Файл/раздел:** Design §3, Plan Task 1
**Описание:** Design (§3): "Модуль НЕ зависит от `model` или `common`". Plan Task 6 вводит `TempFileWriter.kt` — скрытая зависимость от `core`.
**Рекомендация:** Явно зафиксировать в Design, что `TempFileWriter` — SPI-интерфейс.

### [CRITICAL] DI-2: Двойное @ConditionalOnProperty в ClaudeAgentConfig
**Файл/раздел:** Plan Task 12
**Описание:** Класс аннотирован `@ConditionalOnProperty("application.ai.description.enabled")`, бины `claudeInvoker` и `claudeDescriptionAgent` аннотированы `@ConditionalOnProperty("application.ai.description.provider", havingValue = "claude")`.
**Рекомендация:** Переместить `provider=claude` на весь класс, внутри без условий.

### [CRITICAL] COROUTINE-3: Lifecycle DescriptionCoroutineScope не покрывает shutdown hook
**Файл/раздел:** Design §5, Plan Task 13
**Описание:** Если `describe()` завис на уровне CLI subprocess, `cancelAndJoin()` не убьёт процесс — subprocess останется висеть после выхода JVM.
**Рекомендация:** Добавить отслеживание запущенных CLI processes или `ProcessHandle` для принудительного завершения.

### [CRITICAL] SDK-4: ClaudeAsyncClient НЕ AutoCloseable, но в Plan используется .use {}
**Файл/раздел:** Plan Task 11, Design §5
**Рекомендация:** Проверить SDK. Если `ClaudeAsyncClient` не `Closeable` — убрать `.use {}`.

### [MAJOR] ERROR-5: Mapping SDK exceptions теряет оригинальный стек трейс
**Рекомендация:** Проверить, что `kotlin-logging` с `logger.warn(e)` логирует полный стек.

### [MAJOR] TELEGRAM-6: HTML-escaping не покрывает все Telegram-спецсимволы
**Описание:** Экранируются только `<`, `>`, `&`, но не `"`.
**Рекомендация:** Добавить `"` → `&quot;`.

### [MAJOR] CONFIG-7: Duration в @ConfigurationProperties без runtime-валидации
**Рекомендация:** Добавить runtime-валидацию `timeout > 0`.

### [MAJOR] PLAN-8: Task 14 меняет интерфейс TelegramNotificationService до реализации (Task 16)
**Рекомендация:** Объединить Task 14+16 в один атомарный коммит.

### [MAJOR] DOCKER-9: Native install Claude CLI требует network access в runtime
**Описание:** `curl -fsSL https://claude.ai/install.sh | bash`.
**Рекомендация:** Скачать CLI binary заранее как COPY или multi-stage с кэшированием.

### [MAJOR] TEST-10: Интеграционный тест со stub CLI хрупкий (Task 23)
**Рекомендация:** Пометить тест как `@Disabled` по умолчанию.

### [MINOR] CODE-11: Опечатка в Plan Task 12 (имя метода)
**Рекомендация:** Переименовать `claudeInvoker` в `defaultClaudeInvoker`.

### [MINOR] OPS-12: OAuth токен нельзя обновить без рестарта
**Рекомендация:** Добавить health check endpoint.

### [MINOR] COROUTINE-13: semaphore.withPermit не освобождается при TimeoutCancellationException
**Рекомендация:** Добавить тест на concurrent вызовы с timeouts.

### [MINOR] SDK-14: Query.isCliInstalled() проверяет PATH, но CLI установлен в ~/.local/bin
**Рекомендация:** Проверить SDK исходники как `Query.isCliInstalled()` ищет CLI.

### [NIT] CODE-15: Непоследовательное именование исключений
**Рекомендация:** Переименовать `Disabled` в `AgentDisabled`.

### [NIT] PLAN-16: Task 24 удаляет docs, но они нужны для code-reviewer
**Файл/раздел:** Plan Task 22, Task 24
**Рекомендация:** Поменять порядок: Task 22 (review) → fix issues → Task 24 (delete docs).

---

## ccs-executor (Alibaba Kimi / kimi-k2.5)

### [CRITICAL] CONFLICT-1: Несоответствие Task 11 и реальности SDK — `use {}` на НЕ-AutoCloseable
**Файл/раздел:** Task 11, `DefaultClaudeInvoker.kt` (lines 1493-1504)
**Рекомендация:** Удалить `.use {}`, использовать явный cleanup pattern SDK.

### [CRITICAL] CONFLICT-2: Task 14 противоречит Task 15-16 по порядку изменений
**Рекомендация:** Переструктурировать: Task 14 — только `core` (facade); Task 15 — formatter + i18n; Task 16 — atomic change interface + NotificationTask + Sender + тесты.

### [CRITICAL] ARCH-1: `DescriptionEditJobRunner` создаёт новый CoroutineScope без управления lifecycle
**Рекомендация:** Сделать `@Component` с `@PreDestroy shutdown()`.

### [CRITICAL] COROUTINE-1: Неправильный порядок `withTimeout` и `semaphore.withPermit`
**Описание:** `semaphore.withPermit { withTimeout(...) { ... } }` — таймаут включает время ожидания семафора.
**Рекомендация:** Отдельный таймаут на acquire семафора, `withTimeout` только к работе.

### [MAJOR] DI-1: `ClaudeAgentConfig` vs `DescriptionCoroutineScope` при `enabled=false`
**Рекомендация:** Использовать `ObjectProvider<DescriptionCoroutineScope>` в facade.

### [MAJOR] TELEGRAM-1: HTML truncation ломает теги
**Рекомендация:** HTML-aware truncation или без HTML parse mode для caption.

### [MAJOR] TELEGRAM-2: Отсутствует специфичная обработка `message is not modified`
**Рекомендация:** Добавить специфичную обработку для `MessageIsNotModified`.

### [MAJOR] SDK-1: `Query.isCliInstalled()` в `@PostConstruct`
**Описание:** Вызов в init блоке может сработать до того, как CLI доступен.
**Рекомендация:** Перенести проверку в первый вызов `describe()` или lazy.

### [MAJOR] CONFIG-1: `ClaudeProperties` валидируется всегда
**Описание:** Если `enabled=false`, но в YAML невалидные значения для `claude.*`, приложение упадёт.
**Рекомендация:** `@ConditionalOnProperty` на уровне `@Configuration` для `ClaudeProperties`.

### [MAJOR] PLAN-1: Task 6 использует `TempFileWriter`, но реализация в Task 13
**Рекомендация:** Перенести `TempFileWriter` раньше.

### [MINOR] CODE-1: Неиспользуемый `ReloadableResourceBundleMessageSource` в тесте
**Рекомендация:** Удалить лишний import.

### [MINOR] CODE-2: `Paths.get()` вместо `Path.of()`
**Рекомендация:** Использовать современный `Path.of()`.

### [MINOR] CODE-3: Пустой i18n ключ `notification.recording.export.prompt.with.description=`
**Рекомендация:** Удалить неиспользуемый ключ.

### [MINOR] DOCKER-1: Alpine sh совместимость
**Рекомендация:** Убедиться что нет bash-specific синтаксиса.

### [MINOR] ERROR-1: `RateLimited` без явной обработки в `executeWithRetry`
**Рекомендация:** Добавить явный комментарий или `else -> throw e`.

### [MINOR] TEST-1: `Dispatchers.Unconfined` в `ClaudeDescriptionAgentTest`
**Рекомендация:** `StandardTestDispatcher` с `advanceUntilIdle()`.

### [MINOR] TELEGRAM-3: `MessageIdentifier` vs `MessageId`
**Рекомендация:** Проверить все использования.

### [NIT] CODE-4: Неиспользуемый import `kotlinx.coroutines.async` в Task 10
**Рекомендация:** Удалить.

### [NIT] CODE-5: `descriptionHandle` vs `claudeHandle` — несоответствие именования
**Рекомендация:** Использовать `descriptionHandle` везде.

### [NIT] OPS-1: Отсутствует логирование latency вызовов Claude
**Рекомендация:** Добавить `logger.debug { "Claude describe completed in ${duration}ms" }`.

### [NIT] OPS-2: `Duration.toMillis()` vs `toKotlinDuration()`
**Рекомендация:** Использовать `timeout.toKotlinDuration()`.

---

## ccs-executor (Alibaba MiniMax / MiniMax-M2.5)

### [MAJOR] SDK-1: DefaultClaudeInvoker использует `.use {}` блок для не-AutoCloseable клиента
**Рекомендация:** Убрать `.use {}`.

### [MAJOR] SDK-2: DefaultClaudeInvoker вызывает `connect()` перед каждым запросом
**Описание:** Каждый вызов `describe()` создает новый клиент и вызывает `client.connect()`.
**Рекомендация:** Рассмотреть переиспользование клиента между вызовами.

### [MAJOR] CODE-1: DescriptionMessageFormatter не экранирует кавычки
**Рекомендация:** Добавить `.replace("\"", "&quot;")`.

### [MAJOR] CONFLICT-1: Indentation в application.yaml секции AI
**Файл/раздел:** Plan Task 17, Step 1, строки 2729-2747
**Описание:** YAML показывает `ai:` с 2-пробельным отступом от `application:`, но нужен 4-пробельный для полного пути `application.ai.description`.
**Рекомендация:** Исправить отступ.

### [MAJOR] TELEGRAM-1: Кастинг к MessageIdentifier без проверки типа
**Файл/раздел:** Plan Task 16, Step 4, строки 2614-2616
**Описание:** Если ktgbotapi v32.0.0 возвращает `MessageId` — будет `ClassCastException`.
**Рекомендация:** Проверить фактический тип.

### [MAJOR] COROUTINE-1: DescriptionEditJobRunner создает новый CoroutineScope
**Рекомендация:** Инжектировать существующий scope.

### [MINOR] CODE-2: application.yaml использует `cli-path` vs `cliPath`
**Рекомендация:** Оставить либо сделать консистентно.

### [MINOR] CONFIG-1: DescriptionProperties.provider имеет @NotBlank
**Описание:** При `disabled=true` provider может быть пустым.
**Рекомендация:** Убрать `@NotBlank` с provider.

### [MINOR] SDK-3: ClaudeAsyncClientFactory не использует startupTimeout
**Рекомендация:** Добавить `startupTimeout` в `CLIOptions` или задокументировать как future use.

### [MINOR] TEST-1: DescriptionMessageFormatterTest не проверяет экранирование кавычек
**Рекомендация:** Добавить тест.

### [MINOR] DOCKER-1: entrypoint использует /bin/sh но Claude install может требовать bash
**Рекомендация:** `#!/bin/bash` или проверка.

### [NIT] PLAN-1: Task 14 Step 2 изменяет интерфейс TelegramNotificationService
**Риск:** Низкий — nullable default сохраняет совместимость.

### [NIT] ARCH-1: DescriptionEditJobRunner — @Component vs direct instantiation
**Риск:** Низкий.

---

## Summary (raw aggregated)

| Agent | CRIT | MAJ | MIN | NIT | Total |
|---|---|---|---|---|---|
| codex | 3 | 9 | 0 | 0 | 12 |
| gemini | 3 | 7 | 1 | 0 | 11 |
| ccs-glm | 2 | 3 | 5 | 2 | 12 |
| ccs-albb-glm | 5 | 10+1conflict | 3 | 0 | 19 |
| ccs-albb-qwen | 4 | 6 | 4 | 2 | 16 |
| ccs-albb-kimi | 4 | 7 | 8 | 4 | 23 |
| ccs-albb-minimax | 0 | 6 | 5 | 2 | 13 |
| **ИТОГО** | **21** | **49** | **26** | **10** | **106** |

После дедупликации (разные ревьюеры поднимают одну проблему) ожидается ~30-40 уникальных issue-групп.
