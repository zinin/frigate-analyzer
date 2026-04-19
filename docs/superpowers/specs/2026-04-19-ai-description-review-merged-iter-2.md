# Merged Design Review — Iteration 2

**Date:** 2026-04-19
**Design:** `docs/superpowers/specs/2026-04-19-ai-description-design.md`
**Plan:** `docs/superpowers/plans/2026-04-19-ai-description-plan.md`
**Review agents (7):** codex (gpt-5.4), gemini (3.1 Pro), ccs-glm, ccs-albb-glm, ccs-albb-qwen, ccs-albb-kimi, ccs-albb-minimax

---

## codex-executor (gpt-5.4, REASONING_LEVEL=xhigh)

### [CRITICAL] DI-1: Несогласованная регистрация `DescriptionProperties`
**Файл/раздел:** design §3.1, §5; plan Task 3.5, Task 9, Task 14
**Описание:** `AiDescriptionAutoConfiguration` регистрирует `DescriptionProperties` только при `enabled=true`, но `RecordingProcessingFacade` в плане инжектит этот бин без условий. При дефолтном `APP_AI_DESCRIPTION_ENABLED=false` приложение не поднимется. Дополнительно design всё ещё показывает инъекцию `DescriptionProperties.CommonSection` прямо в `ClaudeDescriptionAgent`, хотя бин регистрируется только для root-класса.
**Риск:** startup-failure в default-конфигурации; design и plan ведут к двум разным DI-моделям.
**Рекомендация:** регистрировать `DescriptionProperties` без гейта по `enabled`, а conditional оставить только для `ClaudeProperties` и AI-бинов. Во всех сниппетах стандартизировать инъекцию root `DescriptionProperties` с обращением к `.common`. Добавить отдельный startup-тест на `enabled=false`.

### [CRITICAL] TELEGRAM-2: `runEdit` не принимает suspend-лямбду
**Файл/раздел:** plan Task 16, Step 3b (`DescriptionEditJobRunner`)
**Описание:** `runEdit` объявлен как `block: () -> Unit`, но внутрь передаются вызовы `bot.editMessageText(...)` и `bot.editMessageCaption(...)`, которые suspend. Такой код не скомпилируется.
**Риск:** блокирует реализацию Telegram edit-flow на этапе компиляции.
**Рекомендация:** менять сигнатуру на `block: suspend () -> Unit`.

### [MAJOR] TEST-3: Тест autoconfig не может поднять Claude-граф как описано
**Файл/раздел:** plan Task 3.5, Step 4 (`AiDescriptionAutoConfigurationTest`)
**Описание:** тест ожидает, что при `enabled=true, provider=claude` появится `DescriptionAgent`, но в `ApplicationContextRunner` не добавлен `TempFileWriter`, от которого зависит `ClaudeImageStager`, а значит и агент. Контекст в таком виде не соберётся.
**Риск:** плановый TDD-шаг становится ложным blocker-ом; зелёный тест по инструкции получить нельзя.
**Рекомендация:** либо добавить в runner stub/mock `TempFileWriter`, либо ограничить тест проверкой условной активации autoconfig без подъёма полного claude-пайплайна.

### [MAJOR] CONFLICT-4: Data flow в design всё ещё описывает eager-start до фильтрации получателей
**Файл/раздел:** design §5 "Потребление в core"; design §6 "Single photo (1 кадр)"
**Описание:** §5 уже фиксирует supplier pattern и lazy-start только после проверки получателей, но §6 по-прежнему пишет `t=0 facade стартует claudeHandle = descriptionScope.async { describe(...) }` и кладёт готовый handle в task. Это старое поведение, которое iter-1 уже заменил.
**Риск:** реализация или повторное ревью могут вернуть wasteful eager-start и снова тратить токены на записи без подписчиков.
**Рекомендация:** переписать таймлайн §6 под supplier-модель: до фильтрации передаётся только supplier, `Deferred` создаётся внутри Telegram-слоя один раз после подтверждения, что есть хотя бы один получатель.

### [MINOR] TELEGRAM-5: Telegram-псевдокод и логирование в design не досинхронизированы
**Файл/раздел:** design §6 "Single photo / Media group"; design §7 "Failure matrix"
**Описание:** в псевдокоде всё ещё используется `replyToMessageId`, хотя выше документ уже фиксирует `ReplyParameters`. Там же `message is not modified` / `message to edit not found` местами описаны как `WARN`, а в других секциях design и в плане уже нормализованы в `DEBUG`.
**Риск:** документ снова подталкивает к устаревшему API и лишнему шуму в логах.
**Рекомендация:** синхронизировать все примеры и таблицы с фактическим решением: `ReplyParameters(...)`, а эти два edit-case логировать как `DEBUG`.

### [MINOR] CONFLICT-6: Документ не дочищен после iter-1
**Файл/раздел:** design §8 `.env.example`; design §10 "Новые файлы"
**Описание:** в `.env.example` design всё ещё есть `CLAUDE_STARTUP_TIMEOUT`, хотя поле уже удалено из конфигурации; в списке новых файлов всё ещё фигурирует `ClaudeAgentConfig.kt`, хотя plan Task 12 его уже удалил.
**Риск:** implementer/ops будут ориентироваться на устаревшие артефакты и добавят лишний конфиг или файл.
**Рекомендация:** убрать `CLAUDE_STARTUP_TIMEOUT` из design и обновить file inventory под текущую `@Component`-схему.

### [MINOR] DOCKER-7: Smoke-check в плане вызывает не тот процесс
**Файл/раздел:** plan Task 18, Step 2
**Описание:** команда `docker run --rm frigate-analyzer:ai-test claude --version` не запускает CLI, потому что образ уже имеет `ENTRYPOINT ["/application/docker-entrypoint.sh"]`; аргументы уйдут в Java-приложение.
**Риск:** ручная проверка даст ложный негатив или вообще не проверит установленный `claude`.
**Рекомендация:** использовать `docker run --rm --entrypoint claude frigate-analyzer:ai-test --version` или `--entrypoint /bin/sh -c 'claude --version'`.

### [MINOR] OPS-8: Проверка наличия CLI игнорирует `cli-path`
**Файл/раздел:** design §4, §8; plan Task 9, Task 19
**Описание:** startup/entrypoint проверки опираются на `Query.isCliInstalled()` и `command -v claude`, то есть только на `PATH`, хотя в той же спецификации поддержан явный `CLAUDE_CLI_PATH`. При кастомном пути предупреждение "all descriptions will return fallback" может быть ложным.
**Риск:** misleading WARN/INFO для валидной конфигурации с explicit path.
**Рекомендация:** если `cli-path` задан, валидировать именно его (`exists + executable`) и не делать PATH-based warning; PATH-проверку оставлять только для case с пустым `cli-path`.

---

## gemini-executor (3.1 Pro)

### [CRITICAL] TYPE-1: Ошибка старта (NoSuchBeanDefinitionException) при выключенной фиче
**Файл/раздел:** `AiDescriptionAutoConfiguration.kt` (Task 3.5) и `RecordingProcessingFacade.kt` (Task 14)
**Описание:** В Plan для `AiDescriptionAutoConfiguration` добавлена аннотация `@ConditionalOnProperty("application.ai.description.enabled", havingValue = "true")` на уровне класса (в отличие от Design). Это означает, что при дефолтном `enabled=false` класс автоконфигурации вообще не загружается, и `@EnableConfigurationProperties(DescriptionProperties::class)` игнорируется. Однако `RecordingProcessingFacade` в core-модуле инжектит `DescriptionProperties` безусловно (`private val descriptionProperties: DescriptionProperties`). Это приведёт к крашу Spring Boot при старте с `NoSuchBeanDefinitionException`.
**Риск:** Приложение гарантированно не стартует с настройками по умолчанию.
**Рекомендация:** Убрать `@ConditionalOnProperty` с уровня класса `AiDescriptionAutoConfiguration`, либо внедрять `ObjectProvider<DescriptionProperties>` в фасад, либо перенести `@EnableConfigurationProperties` в `DescriptionScopeConfig`, который грузится безусловно.

### [CRITICAL] TYPE-2: Ошибка компиляции в DescriptionEditJobRunner (suspend в обычной лямбде)
**Файл/раздел:** `DescriptionEditJobRunner.kt` (Task 16 Step 3b)
**Описание:** Метод `runEdit` принимает параметр `block: () -> Unit`. Однако внутри `runEdit` в этот `block` передаются вызовы `bot.editMessageText` и `bot.editMessageCaption`, которые являются `suspend`-функциями.
**Риск:** Код не скомпилируется.
**Рекомендация:** Изменить сигнатуру параметра в helper-функции на `block: suspend () -> Unit`.

### [MAJOR] TYPE-3: Утечка временных файлов при Timeout/Cancellation
**Файл/раздел:** `ClaudeImageStager.kt` (Task 6)
**Описание:** В методе `cleanup` удаление файлов делегируется `suspend`-функции `tempWriter.deleteFiles(paths)`. Метод `cleanup` вызывается из блока `finally` в `describe()`, куда выполнение часто попадает из-за `TimeoutCancellationException`. Вызов `suspend`-функции в уже отменённой корутине немедленно выбрасывает `CancellationException` (которое `runCatching` глотает), и реального удаления файлов не происходит.
**Риск:** Молчаливая утечка места на диске при каждом таймауте Claude до срабатывания глобального cron-очистителя.
**Рекомендация:** Обернуть вызов удаления файлов в `withContext(NonCancellable)`.

### [MAJOR] TYPE-4: Превышение лимита 1024 символов в Caption (Telegram API Error)
**Файл/раздел:** `TelegramNotificationSender.kt` (Task 16) и `DescriptionMessageFormatter.kt`
**Описание:** Бюджет длины для `editBaseCaption` рассчитывается на основе исходного текста. Затем в `DescriptionMessageFormatter.captionSuccess` к этому `baseText` применяется `htmlEscape()`. Если в тексте много спецсимволов (`<`, `>`, `&`), после экранирования длина `baseCaption` может увеличиться в несколько раз (например, `&` -> `&amp;`), и итоговый текст вместе с добавленным AI-описанием превысит жесткий лимит Telegram в 1024 символа.
**Риск:** Исключение `Message too long` от Telegram API, из-за чего edit-операция будет падать.
**Рекомендация:** Учитывать расширение длины при HTML-экранировании. Безопаснее сначала применять `htmlEscape()`, а затем обрезать получившийся экранированный HTML (не разрывая сущности).

### [MAJOR] TYPE-5: Deadlock в RecordingProcessingFacadeTest
**Файл/раздел:** `RecordingProcessingFacadeTest.kt` (Task 14 Step 5)
**Описание:** Искусственный `DescriptionCoroutineScope` инициализируется с использованием нового инстанса `StandardTestDispatcher()`. В самом тесте вызывается `supplier!!.invoke()!!.await()`. Поскольку для этого отдельного диспатчера никто не вызывает `advanceUntilIdle()` или `runCurrent()`, корутина `async` никогда не получит тред для выполнения, и вызов `await()` заблокирует тест навсегда.
**Риск:** Зависание (hang) CI сборки.
**Рекомендация:** Использовать `UnconfinedTestDispatcher()` для инициализации `DescriptionCoroutineScope` в тестовом сетапе, либо явно передавать в него `TestScope`/`TestDispatcher` из `runTest`.

### [NIT] TYPE-6: Неиспользуемое исключение DescriptionException.Disabled
**Файл/раздел:** `DescriptionException.kt` (Task 2)
**Описание:** Исключение `Disabled` объявлено в sealed-иерархии, но нигде в коде не выбрасывается (при `enabled=false` фасад возвращает `null` supplier, и флоу просто не доходит до вызовов агента).
**Риск:** Мёртвый код, загрязняющий API.
**Рекомендация:** Удалить `class Disabled` из `DescriptionException`.

---

## ccs-executor (glm-5.1, direct)

### [MAJOR] D2-01: HTML caption budget не учитывает расширение при escape-преобразовании
**Файл/раздел:** Design §6 "Caption 1024-лимит", Plan Task 16 `TelegramNotificationSender`
**Описание:** `toCaption(MAX_CAPTION_LENGTH - hintOverhead)` обрезает сырой текст, затем `htmlEscape()` может расширить `&` → `&amp;` (+4), `<` → `&lt;` (+3), `>` → `&gt;` (+3). Финальный caption может превысить 1024 символа.
**Риск:** `RetryHelper.retryIndefinitely` зациклится при 400 от Telegram.
**Рекомендация:** Экранировать ДО truncation; либо вычислять бюджет с worst-case коэффициентом.

### [MINOR] D2-02: Design §10 список файлов устарел
**Файл/раздел:** Design §10 "Новые файлы"
**Описание:** `ClaudeAgentConfig.kt` всё ещё перечислен (удалён в iter-1). Отсутствуют: `ClaudeInvoker.kt`, `ClaudeExceptionMapper.kt`, `DefaultClaudeInvoker.kt`, `DescriptionEditJobRunner.kt`, `DescriptionEditScope.kt`.
**Риск:** Затрудняет сверку plan ↔ design.
**Рекомендация:** Актуализировать.

### [MINOR] D2-03: Design .env.example содержит удалённый CLAUDE_STARTUP_TIMEOUT
**Файл/раздел:** Design §8 `.env.example` (строка 701)
**Описание:** `# CLAUDE_STARTUP_TIMEOUT=10s` — поле удалено, но пример env-переменной остался.
**Рекомендация:** Удалить строку.

### [MINOR] D2-04: Design §5 — расхождение сигнатуры конструктора ClaudeDescriptionAgent
**Файл/раздел:** Design §5 vs Plan Task 9/10
**Описание:** Design показывает `commonProperties: DescriptionProperties.CommonSection`. Plan использует `descriptionProperties: DescriptionProperties` — потому что только корневой класс является Spring-бином.
**Риск:** Если реализовать строго по design — `NoSuchBeanDefinitionException`.
**Рекомендация:** Обновить design §5: заменить `commonProperties: DescriptionProperties.CommonSection` на `descriptionProperties: DescriptionProperties`.

### [MINOR] D2-05: Design §6 использует устаревшие имена ktgbotapi API
**Файл/раздел:** Design §6 "Data flow"
**Описание:** `replyToMessageId=photoMsgId` → v32 использует `replyParameters = ReplyParameters(chatId, messageId)`. `messages[0].messageId` → `sendMediaGroup` возвращает один `ContentMessage`.
**Рекомендация:** Обновить design §6 data flow.

### [MINOR] D2-06: Design↔plan расхождение условия на DescriptionEditScope
**Файл/раздел:** Design §5 vs Plan Task 16 Step 3a
**Описание:** Design: `telegram.enabled=true`. Plan: `ai.description.enabled=true`. Plan точнее.
**Рекомендация:** Привести design к plan.

### [MINOR] D2-07: ClaudeProperties валидируется для всех провайдеров
**Файл/раздел:** Design §4, Plan Task 3.5
**Описание:** `ClaudeProperties.workingDirectory` помечен `@NotBlank`. При появлении `provider=openai` приложение не стартует.
**Риск:** Сейчас не блокирует (только `provider=claude`), станет проблемой при добавлении нового провайдера.
**Рекомендация:** Убрать `@NotBlank` и проверять в `ClaudeDescriptionAgent.init`, либо условный `@EnableConfigurationProperties`.

### [NIT] D2-08: editBaseCaption использует захардкоженный SHORT_MAX_LENGTH=500
**Файл/раздел:** Plan Task 16 `TelegramNotificationSender`
**Описание:** `SHORT_MAX_LENGTH = 500` — максимум из `@Max(500)`, а дефолт `200`. Неоптимальное использование caption space.
**Рекомендация:** Передавать реальный `shortMaxLength` через `DescriptionMessageFormatter` или `EditTarget`.

### [NIT] D2-09: Plan — TestDescriptionEditJobRunner не специфицирован
**Файл/раздел:** Plan Task 16 Step 1
**Описание:** Тест использует `TestDescriptionEditJobRunner(bot, formatter)`, но реализация не дана — только ссылка "адаптировать по ExportExecutorTest".
**Рекомендация:** Добавить минимальную реализацию в план.

---

## ccs-executor (albb-glm / glm-5)

### [MAJOR] CODE-1: `runEdit` inline function — неверный тип lambda-параметра
**Файл/раздел:** Plan Task 16 Step 3b (DescriptionEditJobRunner.kt)
**Описание:** `private suspend inline fun runEdit(block: () -> Unit)` — параметр объявлен как обычная lambda, но внутри вызываются suspend-функции `bot.editMessageCaption/editMessageText`.
**Риск:** Compile error.
**Рекомендация:** Изменить сигнатуру на `private suspend inline fun runEdit(crossinline block: suspend () -> Unit)` или убрать `inline`.

### [MAJOR] TEST-1: ClaudeDescriptionAgentTest — отсутствует import `delay`
**Файл/раздел:** Plan Task 10 Step 1 (ClaudeDescriptionAgentTest.kt)
**Описание:** Тест `third call waits for semaphore permit` использует `delay(100)`, но нет импорта `kotlinx.coroutines.delay`.
**Риск:** Compile error.
**Рекомендация:** Добавить `import kotlinx.coroutines.delay`.

### [MINOR] CONFLICT-1: DescriptionEditJobRunner @ConditionalOnProperty — расходится с design
**Файл/раздел:** Design §5 vs Plan Task 16 Step 3b
**Описание:** Design: `application.telegram.enabled=true`. Plan: `application.ai.description.enabled=true`. Plan безопаснее.
**Рекомендация:** Обновить design §5 на условие плана.

### [MINOR] CONFLICT-2: SHORT_MAX_LENGTH hardcoded вместо configurable shortMaxLength
**Файл/раздел:** Design §6 vs Plan Task 16 Step 4
**Описание:** Design: `toCaption(MAX_CAPTION - shortMaxLength - 2)` из конфига. Plan: `const val SHORT_MAX_LENGTH = 500` hardcoded.
**Рекомендация:** (a) инжектить CommonSection, (b) обновить design "used max possible (500) as safe budget", (c) передавать через formatter.

### [NIT] TEST-2: TestDescriptionEditJobRunner — реализация не приведена
**Файл/раздел:** Plan Task 16 Step 1
**Описание:** Тест использует `TestDescriptionEditJobRunner(bot, formatter)`, но план не содержит код.
**Рекомендация:** Добавить минимальный пример.

---

## ccs-executor (albb-qwen / qwen3.5-plus)

### [CRITICAL] CONFLICT-1: Расхождение в ConditionalOnProperty для AutoConfiguration
**Файл/раздел:** Design §3.1 / Plan Task 3.5
**Описание:** Design: `@AutoConfiguration` БЕЗ `@ConditionalOnProperty` на классе. Plan: WITH `@ConditionalOnProperty`.
**Рекомендация:** Унифицировать.

### [CRITICAL] SDK-1: DefaultClaudeInvoker принимает DescriptionProperties, но использует только common.timeout
**Файл/раздел:** Plan Task 11
**Описание:** Лишняя зависимость. YAGNI/минимальные зависимости.
**Рекомендация:** Принимать только `Duration workTimeout` или переименовать в `commonTimeout: Duration`.

### [MAJOR] TEST-1: Пропущен тест для TempFileWriterAdapter
**Файл/раздел:** Plan Task 13
**Описание:** Тонкий adapter без теста — легко пропустить mismatch сигнатур.
**Рекомендация:** Добавить `TempFileWriterAdapterTest` с 2 тестами.

### [MAJOR] CODE-1: Потерянный WARN-лог при missing agent provider
**Файл/раздел:** Design §3.1 / Plan Task 3.5
**Описание:** При `provider=foo` (не claude), autoconfig активен (enabled=true), `@PostConstruct` WARN должен сработать — нужно убедиться.
**Рекомендация:** Вынести `@PostConstruct` WARN в отдельный безусловный `@Component` с `ObjectProvider<DescriptionAgent>`.

### [MAJOR] COROUTINE-1: descriptionScope.async без явного Dispatcher в design
**Файл/раздел:** Design §5
**Описание:** `DescriptionCoroutineScope` использует `Dispatchers.IO`, но это не задокументировано в design.
**Рекомендация:** Зафиксировать в design `Dispatchers.IO + SupervisorJob()`.

### [MINOR] MINOR-1: Unused imports в ClaudeAsyncClientFactoryTest
**Файл/раздел:** Plan Task 7
**Описание:** Тест проверяет `buildEnvMap()`, но импортирует SDK-классы.
**Рекомендация:** Удалить unused imports.

### [MINOR] DOCKER-1: Дублирование apk-пакетов в Dockerfile
**Файл/раздел:** Plan Task 18
**Описание:** `bash libgcc libstdc++ ripgrep` в отдельном `apk add`, дублирующем base packages.
**Рекомендация:** Консолидировать в одну строку.

### [NIT] PLAN-1: Task 12 удалён, но ссылка в Task 11 Step 1.5
**Рекомендация:** Убрать ссылку.

### [NIT] TEST-2: Интеграционный тест INTEGRATION_CLAUDE=stub не задокументирован как opt-in в design
**Файл/раздел:** Plan Task 23 vs Design §9
**Рекомендация:** Добавить пометку.

---

## ccs-executor (albb-kimi / kimi-k2.5)

### [CRITICAL] DESIGN-PLAN-3: Дублирование Task 12 в плане
**Файл/раздел:** Plan Task 12 "[УДАЛЁН]" и Task 11 Step 1.5
**Описание:** Task 11 Step 1.5 содержит "Delete Task 12 (ClaudeAgentConfig) — больше не нужен" — избыточно.
**Рекомендация:** Удалить Task 11 Step 1.5 как избыточный.

### [CRITICAL] DESIGN-PLAN-6: Отсутствует `ObjectProvider` импорт в `TelegramNotificationSender`
**Файл/раздел:** Plan Task 16 Step 4
**Описание:** Используется `ObjectProvider<DescriptionMessageFormatter>`, импорт указан только после кода.
**Рекомендация:** Перенести импорт в начало файла.

### [CRITICAL] PLAN-8: Concurrency test — отсутствует импорт `AtomicInteger`
**Файл/раздел:** Plan Task 10 Step 1
**Описание:** Используется `AtomicInteger` без импорта `java.util.concurrent.atomic.AtomicInteger`.
**Рекомендация:** Добавить импорт.

### [MAJOR] DESIGN-PLAN-1: Несоответствие названия модуля в Gradle
**Описание:** В Task 1 `:frigate-analyzer-ai-description`, в design `ai-description`.
**Рекомендация:** Унифицировать: Gradle project name `:frigate-analyzer-ai-description`, директория `modules/ai-description/`.

### [MAJOR] DESIGN-PLAN-5: Несоответствие в `ClaudeDescriptionAgent` — конструктор
**Файл/раздел:** Design §5 vs Plan Task 9/10
**Описание:** Design: `commonProperties: DescriptionProperties.CommonSection` напрямую. Plan: `descriptionProperties: DescriptionProperties` целиком.
**Рекомендация:** Следовать плану — обновить design §5.

### [MAJOR] DESIGN-PLAN-4: Пропущена `init` валидация в `DescriptionProperties.CommonSection`
**Файл/раздел:** Design §4 vs Plan Task 3 Step 1
**Описание:** Design: `require(queueTimeout.toMillis() > 0)` в `CommonSection.init`. Plan Task 3 не содержит.
**Рекомендация:** Добавить `init` в plan Task 3.

### [MAJOR] PLAN-6: `NoOpTelegramNotificationService` не в design §10
**Файл/раздел:** Plan Task 14 Step 3.1 vs Design §10
**Описание:** Task 14 обновляет `NoOpTelegramNotificationService`, но design §10 не упоминает этот файл как изменённый.
**Рекомендация:** Добавить в список изменённых файлов design §10.

### [MAJOR] PLAN-9: Task 14 Step 5 — тест использует `FrameData` без импорта
**Файл/раздел:** Plan Task 14 Step 5 (RecordingProcessingFacadeTest)
**Описание:** Используется `FrameData`, но нет импорта `ru.zinin.frigate.analyzer.model.dto.FrameData`.
**Рекомендация:** Добавить импорт.

### [MAJOR] PLAN-11: Task 22 — устаревший формат `superpowers:code-reviewer`
**Описание:** Лучше использовать `Agent` с `subagent_type: superpowers:code-reviewer`.
**Рекомендация:** Обновить инструкцию.

### [MINOR] DESIGN-PLAN-7 / DESIGN-PLAN-13: `SHORT_MAX_LENGTH = 500`
**Рекомендация:** Комментарий "worst-case из @Max(500)".

### [MINOR] DESIGN-PLAN-11: `${application.temp-folder}` не определено в YAML-примере
**Рекомендация:** Проверить что свойство определено в проекте.

### [MINOR] PLAN-5: Комментарий в retry-loop упоминает `Disabled`
**Описание:** `Disabled` не генерируется внутри `executeWithRetry`.
**Рекомендация:** Убрать из комментария.

### [NIT] PLAN-2: Порядок импортов в тестах
**Описание:** `every`/`ObjectProvider` импорты указаны после кода теста.
**Рекомендация:** Переставить в начало.

### Прочие помечены самим рецензентом как false positive / не критично

- DESIGN-PLAN-2 (зависимость model в ai-description): false positive — FrameData используется в core, design корректен.
- DESIGN-PLAN-12: ок, соответствует.
- PLAN-10: ок.
- PLAN-1: не критично.
- PLAN-7: printf heredoc корректен.
- DESIGN-PLAN-10: проверить package.
- DESIGN-PLAN-9: ок.

---

## ccs-executor (albb-minimax / MiniMax-M2.5)

### [CRITICAL] CONFLICT-N1: Design ссылается на удалённый CLAUDE_STARTUP_TIMEOUT
**Файл/раздел:** Design §8 `.env.example`, строка 701
**Описание:** `# CLAUDE_STARTUP_TIMEOUT=10s` присутствует, хотя §4 корректно указывает что поле удалено.
**Риск:** Разработчик добавит несуществующий параметр.
**Рекомендация:** Удалить строку.

### [MAJOR] CONFLICT-N2: Противоречие в design относительно i18n-реализации
**Файл/раздел:** Design §4 (line 234-238) vs §9 (lines 773-783, 849-850)
**Описание:**
- §4 line 234: «i18n-ключи захардкожены в коде (стабильные константы)»
- §9/§10: указывает добавить ключи в `messages_ru.properties` и `messages_en.properties`
Plan (Task 15) корректно реализует через properties files.
**Рекомендация:** Уточнить §4 — либо константы (удалить из §9/§10), либо properties (исправить формулировку в §4).

---

## Summary (raw aggregated)

| Agent | CRIT | MAJ | MIN | NIT | Total |
|---|---|---|---|---|---|
| codex | 2 | 2 | 4 | 0 | 8 |
| gemini | 2 | 3 | 0 | 1 | 6 |
| ccs-glm | 0 | 1 | 6 | 2 | 9 |
| ccs-albb-glm | 0 | 2 | 2 | 1 | 5 |
| ccs-albb-qwen | 2 | 3 | 2 | 2 | 9 |
| ccs-albb-kimi | 3 | 6 | 4 | 1 | 14 |
| ccs-albb-minimax | 1 | 1 | 0 | 0 | 2 |
| **ИТОГО** | **10** | **18** | **18** | **7** | **53** |

После дедупликации ожидается ~25 уникальных issue-групп (меньше, чем в iter-1's 52 — ожидаемо при сходимости).
