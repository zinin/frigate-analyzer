## TASK

Execute the implementation plan for «раскадровка для AI-описаний» (give the description model a timed storyboard of the event — a grid of frames around the detection, reaching into neighbouring Frigate segments — and make the short description twice as long).

Use `/superpowers:subagent-driven-development` skill for execution.

## DOCUMENTS

- Design: `docs/superpowers/specs/2026-09-23-description-storyboard-design.md`
- Plan: `docs/superpowers/plans/2026-09-23-description-storyboard.md`

Read both documents first. Затем прочитай `CLAUDE.md`, `.claude/rules/ai-description.md`, `.claude/rules/pipeline.md`, `.claude/rules/configuration.md` и главные файлы, которые план меняет или на которые опирается: `RecordingProcessingFacade.kt`, `FrameExtractorProducer.kt`, `DescriptionRequest.kt`, `VisionRequest.kt`, `DescriptionTask.kt`, `JudgeTask.kt`, `VisionCallExecutor.kt`, `ClaudePromptBuilder.kt`, `ClaudeImageStager.kt`, `GrokPromptFileWriter.kt`, `RecordingEntityRepository.kt`, `RecordingEntityServiceImpl.kt`, `VideoProbe.kt`, `FfmpegProcessRunner.kt`, `TempFileHelper.kt`, `LocalVisualizationService.kt`, `RecordingProcessingFacadeTest.kt`.

## IMPORTANT: DO NOT START WORK YET

After reading the documents:
1. Confirm you have loaded all context
2. Summarize your understanding briefly
3. **WAIT for user instruction before taking any action**

Do NOT begin implementation until the user explicitly tells you to start.

## SESSION CONTEXT

Отвечать по-русски. Идентификаторы кода и команды не переводить.

### Состояние репозитория

- Каталог `/opt/github/zinin/frigate-analyzer`. Ветка `feat/description-storyboard` создана через `git switch -c` от `master` (`447b738`). В ветке коммиты: спека `d5f3388`, правка спеки `6c3b5b6`, план `1c0a5c1` и коммит с этим промптом.
- В рабочем дереве много неотслеживаемых файлов, не относящихся к задаче: `.taskmaster/`, промпты и заметки в `docs/*.md`, старые файлы в `docs/superpowers/`, `tmp_diff_handler.txt`. Их не добавлять — `git add` только файлы задачи, поимённо. Шаги коммита в плане уже перечисляют файлы.

### Что хотел владелец и как это решилось

- **Проблема** — модель не видит динамику. Пример владельца: машину задетектировало только на одном кадре, и непонятно, стоит она или проехала; такое бывает часто. Причина по коду: vision-api отдаёт кадр раз в 4 с плюс пики движения, а в описание идут только кадры с детекциями — пустые кадры до и после, по которым видно «проехала», выбрасываются.
- **Подход** — владелец выбрал вариант C: плотная раскадровка плюс соседние сегменты. Пункт «видно только начало события» он не отмечал, но окно через границу сегмента хочет.
- **Время кадров** — у владельца камера пишет время на кадре, «а могла и не писать», поэтому время в подписях есть всегда (относительное, от начала показанного отрезка).
- **Полноразмерные кадры** — «все кадры, ну или до 4, редко когда присылают больше» → `APP_AI_DESCRIPTION_MAX_FRAMES` по умолчанию 4.
- **Короткое описание** — «сейчас слишком короткое, в 2 раза длиннее будет лучше» → `APP_AI_DESCRIPTION_SHORT_MAX` 400 плюс правило в промпте, что должно быть в `short`.
- **Задержка** — ждать следующий сегмент до ~30 с владелец согласовал; уведомление с кадрами уходит сразу, как сейчас.
- **Судья** не меняется: он стоит на пути уведомления.

### Отвергнутые варианты — не предлагать заново

- **Видео в модель через Gemini.** Claude (API и `Read` в Claude Code) и Grok (API и CLI) видео на вход не принимают. Gemini — третий провайдер (новый CLI, авторизация, фабрика), к тому же сам режет видео по кадру в секунду ~70 токенов на кадр, то есть та же раскадровка, только грубее.
- **Отдельные кадры вместо сетки.** Claude видит кадр только через `Read` и вправе прочитать часть файлов (`DefaultClaudeInvoker` на это лишь пишет WARN); при >20 картинках в запросе у Claude лимит 2000 px на сторону. Сетка — один `Read`.
- **Модель сама выбирает кадры** — время и цена непредсказуемы, у Grok инструменты отключены.
- **Два этапа** (сразу описание, потом уточнение) — два вызова модели, лимит 30/ч расходуется вдвое.
- **Только уже извлечённые кадры** — кадры раз в 4 с, направление и манёвр теряются.

### Проверенные факты о провайдерах (исследование 2026-09-23, по официальной документации)

- Claude: видео на вход нет ни у одной модели; `Read` читает картинки, PDF и `.ipynb`, не `.mp4`. До 600 картинок в запросе (100 у моделей с контекстом 200k). Цена картинки `⌈w/28⌉ × ⌈h/28⌉` токенов; Claude 4.7+ уменьшает до 2576 px по длинной стороне — сетка 2560×1440 и кадр 2880×1620 стоят по 4 784 токена.
- Grok: видео только на выходе; картинки jpg/png до 20 MiB, число не ограничено; цена картинки у grok-4.x не документирована.

### Что выяснилось при чтении кода (не всё есть в спеке)

- Пайплайн берёт запись в работу только через 30 с после появления файла: `RecordingEntityServiceImpl.findUnprocessedRecordings` выбирает `file_creation_timestamp < now − 30 s`. Поэтому к началу описания следующий сегмент обычно уже в базе.
- Watcher (`WatchRecordsLoop`) заносит файл в базу по `ENTRY_CREATE` без проверки, что Frigate его дописал, — отсюда проверка «файл не менялся секунду» для следующего сегмента.
- `recordings.file_path` допускает NULL. Индексы по `recordings`: `record_timestamp`, `creation_timestamp`, `file_creation_timestamp`, `process_timestamp`, уникальный по `file_path`; композитного `(cam_id, record_timestamp)` нет, поэтому запросы соседей ограничены по времени.
- `FfmpegProcessRunner` сливает stdout и stderr в текстовые строки — бинарный вывод через него не забрать; отсюда JPEG во временной папке с уникальным префиксом. `TempFileHelper.deleteFiles` удаляет любой файл внутри temp-папки; его почасовая чистка трогает только файлы с его собственным префиксом.
- ffmpeg/ffprobe есть в образе (`docker/deploy/Dockerfile`, `apk add ffmpeg`), пути в `ApplicationProperties.ffmpegPath`/`ffprobePath`. Образец теста с настоящим ffmpeg — `FfmpegCompressionIntegrationTest` (`assumeTrue`).
- `PackagedApplicationYamlTest.every configuration properties class binds from the packaged config` сам находит все `@ConfigurationProperties` — новый `StoryboardProperties` попадёт под проверку без правки теста. `ProductionYamlBinder` — хелпер для binding-тестов production yaml.
- Промпт судьи идёт через те же `VisionRequest` и провайдеров; судья передаёт кадры уже отсортированными по `frameIndex`, поэтому перенос сортировки и подписей в задачи его не меняет — это закрепляют тесты «the judge prompt keeps its shape» и «the judge blocks keep their shape».
- Коммит `6f130a7`: общий текст задачи с «Do not call tools» лишил Claude единственного способа увидеть кадр (`Read`). С тех пор правила про инструменты живут только в бэкендах; `DescriptionTask` не должен упоминать инструменты.
- `RecordingEntityService` реализован одним классом (`RecordingEntityServiceImpl`), фейков в тестах нет — новые методы интерфейса ничего не ломают.

### Места плана, которые стоит проверить при исполнении

- Синтаксис MockK `coEvery { … } throws X andThen value` и `kotlinx.coroutines.test.currentTime`/`runCurrent` (нужен `@OptIn(ExperimentalCoroutinesApi::class)`).
- Тест «waiting for a next segment does not hold a sampling permit» опирается на планирование `runTest`; если он зависает — это признак того, что ожидание держит семафор, а не ошибка теста.
- Фильтр `fps` у конца файла может отдать на кадр меньше, чем просили; сборщик берёт то, что пришло (`zip`), это ожидаемо.
- Интеграционный тест нарезки кодирует синтетическое видео встроенным кодером `mpeg4`; если на машине сборки его нет или нет ffmpeg — поставить `sudo apt install ffmpeg`, а не пропускать тест.
- Java2D-тесты сетки рисуют текст — на машине сборки нужны шрифты (в контейнере DejaVu).
- Числа в ожиданиях тестов сборщика (7 клеток предыдущего сегмента с локального 6.0, 9 клеток текущего с 0.62, 11+5 клеток, 14 клеток по 0.5 с, шаг 0.66) проверены Python-скриптом по формуле планировщика; если тест с ними падает, сначала сверить реализацию с формулой спеки (раздел 5.3), а не подгонять числа.
- ktlint: при ошибках `./gradlew ktlintFormat` и повтор.

### Правила владельца

- Субагенты: не haiku; для простых задач — opus, минимум sonnet.
- Локальную память (`~/.claude/projects/.../memory/`) не использовать.
- Пакеты ставить через apt (sudo есть), не pipx.
- Сборка и тесты — только через агента `claude-forge:build-runner`, `./gradlew` напрямую не запускать. После реализации — сначала `superpowers:code-reviewer`, критичные замечания исправить, потом сборка.
- `git add` после каждого созданного или изменённого файла.
- Сообщения коммитов в стиле репозитория; строку `Claude-Session:` добавлять, только если у сессии есть URL.
- Перед PR: `git rm -r docs/superpowers/` отдельным коммитом — в диффе PR этих файлов быть не должно.

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
