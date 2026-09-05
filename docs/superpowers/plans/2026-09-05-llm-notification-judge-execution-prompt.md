## TASK

Execute the implementation plan for the LLM notification judge (третья ступень проверки уведомлений: LLM-судья между трекером и отправкой в Telegram).

Use `/superpowers:subagent-driven-development` skill for execution.

## DOCUMENTS

- Design: `docs/superpowers/specs/2026-09-05-llm-notification-judge-design.md`
- Plan: `docs/superpowers/plans/2026-09-05-llm-notification-judge.md`

Read both documents first.

## IMPORTANT: DO NOT START WORK YET

After reading the documents:
1. Confirm you have loaded all context
2. Summarize your understanding briefly
3. **WAIT for user instruction before taking any action**

Do NOT begin implementation until the user explicitly tells you to start.

## SESSION CONTEXT

### Где мы находимся

- Ветка `feature/llm-notification-judge` уже создана (`git switch -c` от `master` на `ad6c3e5`) и содержит три коммита с документами: спека `19d8543`, план `16d31a6`, правка плана `7d4fa88`. В свежей сессии делай `git switch feature/llm-notification-judge`, ветку не пересоздавай.
- Правило владельца: `docs/superpowers/**` не коммитится в `master`; перед созданием PR все файлы `docs/superpowers/` удаляются из индекса отдельным коммитом (`git rm`), в диффе PR их быть не должно. Они остаются в истории ветки.
- Все `./gradlew …` только через агента `claude-forge:build-runner`; на ktlint-ошибки — `./gradlew ktlintFormat` и повтор. После реализации — `superpowers:code-reviewer`, критические замечания исправить, повторить до чистого прохода (правило `CLAUDE.md`).
- Коммиты заканчиваются отдельным `-m "Claude-Session: <URL текущей сессии>"` — URL **своей** сессии.

### Решения владельца и их причины

- **Держать все уведомления до вердикта.** Владелец принял задержку на время ответа быстрой модели. Вариант «отправить и удалить» отвергнут: push на телефон всё равно приходит. Вариант «держать только сомнительные» отвергнут как два пути доставки и критерий сомнительности в коде.
- **Fail-open везде.** Любой сбой судьи (таймаут, потеря авторизации, лимит, невалидный ответ, ошибка контекста, ошибка записи вердикта) отправляет уведомление как сегодня. Владелец явно выбрал это против fail-closed и против «отправлять только людей».
- **Отдельная быстрая модель для судьи** (Sonnet или Grok fast), описания остаются на Opus правкой уже отправленного сообщения. Поэтому вариант «один вызов на вердикт и описание» отвергнут, поток «плейсхолдер → правка» не трогаем.
- **Два лимита:** описания 30/ч (было 10, меняем дефолт), судья 200/ч защитный.
- **Длинное событие:** одно сообщение, дальше только по существу. Реализуется snooze-ом, который назначает сама модель (потолок `max-snooze`, по умолчанию 30 мин). Побуждение — новый класс или больше объектов того же класса.
- **Контекст собираем сами, агентного доступа модели к базе нет.** Причины: несколько ходов быстрой модели превращают 15 с в минуту и умножают токены; фиксированный контекст воспроизводим и хранится рядом с вердиктом; быстрые модели ненадёжны в многошаговых сценариях на таблице в 2,5 млн строк. Оставлено поле `wanted` в ответе: через недели по нему решат, нужен ли ещё один провайдер контекста.
- **Хранить вердикты вечно**, чистки нет (владелец убрал retention из конфигурации).
- **Зона времени в промпте** по умолчанию — зона владельца из `/timezone`; контейнер на проде работает в UTC (проверено: `TZ` пустой, `date` даёт UTC). `APP_AI_JUDGE_ZONE` — явное переопределение.
- **Кнопка обратной связи «ложное»** — не в первой версии. Таблица вердиктов ничего под неё не резервирует: будущая таблица обратной связи сошлётся на `id` вердикта.
- **Текст уведомления не меняется**, вердикт получателям не показывается.

### Проверенные факты о кодовой базе, на которые опирается план

- `CLIOptions.Builder` из `claude-code-sdk-1.0.0.jar` имеет и `systemPrompt(String)`, и `appendSystemPrompt(String)` (проверено `javap`). План использует `appendSystemPrompt`: замена системного промпта CLI меняет обработку `@`-ссылок на кадры, а нужно лишь добавить правило «только JSON, без инструментов».
- Две аннотации `@ConditionalOnProperty` на одном классе уже применяются в `AiSettingsCommandHandler` — repeatable работает в этой версии Boot.
- `ObjectProvider.getIfAvailable()` бросает при двух кандидатах одного типа. Поэтому `ActivePresetResolver` перестаёт реализовывать `ActiveDescriptionPreset`, а бинами становятся адаптеры `DescriptionPresetResolver : ActiveDescriptionPreset` и `JudgePresetResolver : ActiveJudgePreset`. Два бина `VisionCallExecutor` разрешаются по имени параметра (`descriptionVisionCallExecutor`, `judgeVisionCallExecutor`); никто не инжектит `VisionCallExecutor` по типу.
- `TimeoutCancellationException` — наследник `CancellationException`: ветка таймаута при чтении выключателя обязана стоять до/внутри `catch (CancellationException)`, иначе таймаут уйдёт как отмена (учтено в `judgeEnabled` Task 7).
- `BboxClusteringHelper.cluster` склеивает одинаковые bbox одного класса в один объект: тестовые фикстуры для «второго человека» должны иметь разнесённые bbox (в плане исправлено сдвигом `x1` на 500 px).
- `DatabaseClient` в кодовой базе ещё не использовался — `JudgeStatsRepository` его первый потребитель; расширения `org.springframework.r2dbc.core.awaitSingle` / `flow`. Enum-ы вердикта хранятся как `String`, чтобы не зависеть от конвертации enum в R2DBC.
- `RecordingProcessingFacadeTest` использует настоящий `FrameVisualizationService` поверх `spyk(LocalVisualizationService)` из-за ловушки MockK с default-параметрами — сохранять этот приём.
- `MessageKeyParityTest` требует одинаковых ключей в `messages_ru.properties` и `messages_en.properties`; `AiSettingsMessagesTest` держит карту «ключ → число аргументов» — новые ключи `ai.settings.judge.*` добавить и туда.
- `StatusControllerTest` в `core` работает с выключенным судьёй: `$.judge.enabled` должен быть `false`.
- Существующие тесты рендера `/ai` проверяют подписи кнопок — с префиксом `📝 ` для описаний и `⚖️ ` для судьи их ожидания нужно обновить.
- `AppSettingsService` кэширует ключи per-process без TTL: правки `ai.judge.*` напрямую в SQL невидимы до рестарта.
- Интеграционные тесты `core` (`IntegrationTestBase`) поднимают Postgres + liquibase через `docker/test-compose.yml`; миграция `1.0.6.xml` подхватывается автоматически (`COPY ./liquibase/migration/` в Dockerfile).

### Что проверено на проде (`old.zinin.ru`) и зачем это знать

- Static score запросом с пересечением bbox по индексу `idx_detections_detection_timestamp` выполняется ~160 мс на неделю cam2; новых индексов не нужно. Значения на примерах из спеки: машина cam2 у ворот — 29 095 записей за 8 дней; `person` у поленницы cam4 — 332 записи за 7 дней; `motorcycle` там же — 18. Это ориентиры для приёмки, не тестовые данные.
- Пресеты на проде: `claude-opus` (активный для описаний), `claude-sonnet`, `claude-fable`, `grok-fast`, `grok-deep`. Судью рекомендовано запускать на `claude-sonnet` или `grok-fast`.
- Трекер на проде: `NOTIFICATIONS_TRACK_TTL=PT12H`, `REAPPEAR_GAP=PT1H`, `COOLDOWN_REAPPEAR=PT5M`, `REAPPEAR_CLASSES` не задан. Заплатка `NOTIFICATIONS_TRACK_REAPPEAR_CLASSES=person` — операционное действие владельца на проде, **не часть кода этой задачи**.
- Уровень логов `ObjectTrackerServiceImpl` и `NotificationDecisionServiceImpl` на проде — DEBUG; строки `Judge: cam=… verdict=…` пишутся на INFO по образцу `Decision: notify`.

### Границы и ловушки

- Task 1 и Task 2 — одна единица деплоя: после Task 1 всё собирается, но выкатывать между ними нельзя.
- `DescriptionPreset*`, `DescriptionPresetCatalog`, ключ yaml `application.ai.description.presets`, команда `/ai` и имя модуля **не переименовываются** — это общий каталог AI-пресетов, что фиксируется в документации. Переименовываются только `DescriptionBackend` → `VisionBackend`, `DescriptionBackendFactory` → `VisionBackendFactory`, а `DefaultDescriptionAgent` превращается в тонкую обёртку над `VisionCallExecutor`.
- Per-camera `Mutex` держится на время ответа модели намеренно (порядок вердиктов одной камеры входит в контекст следующей); таймаута на мьютексе нет, при очереди > 20 кандидатов на камеру пишется WARN.
- Snooze меняют только вердикты `stage = JUDGE`; `FAILOVER` и `BYPASS` действующий snooze не трогают. Окно snooze считается по модулю разницы времени записи и якоря — бэклог разбирается от новых к старым.
- Static score исключает собственную запись кандидата: она уже сохранена к моменту вызова судьи и иначе считала бы сама себя доказательством статичности.
- В `error` вердикта только класс и сообщение исключения (до 1024 символов), без стека и без секретов.
- Кадры судье — визуализированные (с рамками), первые `max-frames` по ранжированию `selectTopFrames`, затем в хронологическом порядке; уменьшение до `max-image-side` делает `FrameDownscaler` внутри executor-а.
- `JudgeProperties` обязаны биндиться и при `enabled=false` (как `DescriptionProperties`) — регистрируются через `@EnableConfigurationProperties` в `AiDescriptionAutoConfiguration`.

## PLAN QUALITY WARNING

The plan was written for a large task and may contain:
- Errors or inaccuracies in implementation details
- Oversights about edge cases or dependencies
- Assumptions that don't match the actual codebase
- Missing steps or incomplete instructions

**If you notice any issues during implementation:**
1. STOP before proceeding with the problematic step
2. Clearly describe the problem you found
3. Explain why the plan doesn't work or seems incorrect
4. Ask the user how to proceed

Do NOT silently work around plan issues or make significant deviations without user approval.
