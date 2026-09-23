## TASK

Continue executing the implementation plan for «раскадровка для AI-описаний» (description storyboard): Tasks 4–11 of 11.

## CRITICAL: DO NOT START WORKING

**STOP. READ THIS CAREFULLY.**

After loading all context below, you MUST:
1. Read the documents and understand the context
2. Report what you understood (brief summary)
3. **WAIT for explicit user instructions** before taking ANY action

**DO NOT:**
- Start implementing tasks
- Make any code changes
- Run any commands (except reading documents)
- Assume what task to work on next

**The user will tell you exactly what to do.** Until then, only read and summarize.

## DOCUMENTS

- Design: `docs/superpowers/specs/2026-09-23-description-storyboard-design.md`
- Plan: `docs/superpowers/plans/2026-09-23-description-storyboard.md` (Tasks 1–3 are trimmed to commit references)
- SDD ledger — the authoritative progress record, preflight table, rulings and deferred minor findings: `.superpowers/sdd/2026-09-23-description-storyboard/progress.md` (git-ignored)

Read both documents and the ledger to understand the full picture. Also read `CLAUDE.md`, `.claude/rules/ai-description.md`, `.claude/rules/pipeline.md`, `.claude/rules/configuration.md`.

## PROGRESS

**Completed tasks:**
- [x] Task 1: время кадра доходит до фасада (`FrameData.offsetSeconds`, `toFrameData`) — `55faa80`
- [x] Task 2: картинки с подписями в `VisionRequest` (задача задаёт подписи, порядок и заголовок; промпт судьи не изменился) — `321dd14`
- [x] Task 3: короткое описание вдвое длиннее (правило для `short`, `APP_AI_DESCRIPTION_SHORT_MAX` 400) — `bc3ab96`

**Remaining tasks:**
- [ ] Task 4: раскадровка в промпте описания (`DescriptionRequest.Storyboard`, тексты `DescriptionTask`)
- [ ] Task 5: `StoryboardPlanner`
- [ ] Task 6: запросы соседних сегментов (репозиторий и сервис)
- [ ] Task 7: `AdjacentSegmentFinder`
- [ ] Task 8: `StoryboardFrameSampler`
- [ ] Task 9: `StoryboardComposer`
- [ ] Task 10: `StoryboardProperties` и `StoryboardBuilder`
- [ ] Task 11: фасад отдаёт раскадровку модели, документация

## SESSION CONTEXT

Отвечать по-русски. Идентификаторы кода и команды не переводить.

### Как шло исполнение

- Запуск: `/claude-mesh:do-plan 400k` → `superpowers:subagent-driven-development`. В новой сессии `/claude-mesh:do-plan <порог>` нужно запустить заново: он пишет конфиг хука контекста для новой сессии. `runtime.dispatch_model` = `opus` — каждый субагент (исполнитель, ревьюер) диспатчится с `model: "opus"`.
- Ledger `.superpowers/sdd/2026-09-23-description-storyboard/progress.md`: первая строка называет план; задачи со строкой `Task N: complete` сделаны — не диспатчить повторно. Дописывать в него, не пересоздавать. В нём таблица предполётной проверки: все интерфейсы между задачами сверены с кодом и между собой — противоречий нет, кроме Ruling 2.
- Цикл задачи: `bash <skill-dir>/scripts/task-brief <PLAN> N` → brief; исполнитель по шаблону `implementer-prompt.md` пишет полный отчёт в `task-N-report.md` рядом с brief; `bash <skill-dir>/scripts/review-package <PLAN> <BASE> <HEAD>` → один ревьюер по `task-reviewer-prompt.md` (brief + отчёт + пакет + дословные цитаты Global Constraints плана и разделов спеки). BASE — `git rev-parse HEAD` перед диспатчем. Задачи 1–3 прошли ревью с первого раза, без раундов исправлений.
- HEAD после сокращения плана — `be0bcda`, затем коммит с этим промптом. Brief задачи 4 уже сгенерирован (`task-4-brief.md`), при желании перегенерировать.

### Решения (Rulings, есть в ledger)

1. **Gradle.** Исполнители запускают `./gradlew` сами в своём контексте: `JAVA_HOME=/usr/lib/jvm/zulu25 ./gradlew … --no-watch-fs --console=plain`. Java по умолчанию на машине — 21; агент `claude-forge:build-runner` субагентам недоступен; так уже делали в этом репозитории. Контроллер `./gradlew` сам не запускает; финальный `./gradlew build` — через `claude-forge:build-runner`. Владельцу об этом сказано.
2. **Task 10.** Если нарезка СЛЕДУЮЩЕГО сегмента упала и его клетки выпали, `Storyboard.durationSeconds = min(footage.range.end, currentDuration) − zero`, чтобы «Footage after X s is not available» называла реальный конец (спека 4.3). Сбой ПРЕДЫДУЩЕГО сегмента оставляет `zeroSeconds = footage.range.start`, как в тесте плана (−4.0). Добавить один тест в `StoryboardBuilderTest`: нарезка следующего бросает → `missingAfter`, 11 клеток, `durationSeconds` 7.0 (кадр на 10.0 с, D = 12). Решение передать в диспатч исполнителя и в блок ограничений ревьюера задачи 10. Владелец о нём знает и не возражал.

### Что передать в следующие задачи

- Task 2 дала: `VisionImage(bytes, caption)`; `VisionRequest(requestId, images, instructions)`; `VisionInstructions(systemPrompt, preamble, imagesHeader, epilogue, jsonSchema)`; в `DescriptionTask` — `FRAMES_HEADER`, `images()`, `visionRequest()`, `frameCaption()`, `internal seconds()`; в `JudgeTask` — `FRAMES_HEADER`, `images()`, `visionRequest()` (одной строкой после ktlintFormat); `DescriptionRequest.FrameImage(frameIndex, bytes, offsetSeconds = null)`; временные файлы Claude — `claude-<id>-image-<позиция>`.
- Task 3: правило для `short` в epilogue — ровно текст плана; Task 4 воспроизводит его в своём единственном epilogue.
- Task 4 — в диспатче указать на отложенные minor из Task 2 в её зоне: слово «frames» в catch-логе `ClaudeImageStager` (:33) и комментарии `VisionCallExecutor` (:96); строка Layers в `ai-description.md` про `DescriptionTask`/`JudgeTask` («Build `VisionInstructions`») устарела — они строят весь `VisionRequest`.
- Task 11 правит `RecordingProcessingFacadeTest.kt` — там давнее предупреждение компилятора на :223 «Expression is unused» (отложенный minor из Task 1).

### Окружение

- ffmpeg/ffprobe в `/usr/bin` с кодерами `mpeg4` и `mjpeg`, шрифты DejaVu, Docker 29.7.2: интеграционный тест ffmpeg (Task 8) и Java2D-тесты (Task 9) должны проходить, а не пропускаться; Testcontainers работают.
- База на `bc3ab96`: ai-description 335 тестов (1 пропуск — `@Disabled` `ClaudeBackendIntegrationTest`), core 453, service 123 — всё зелёное.
- Давний шум в выводе тестов (не от этой ветки): предупреждения byte-buddy про `sun.misc.Unsafe`, deprecation `asText()` в `GrokPromptFileWriterTest.kt:98`, лишний `seen!!` в `GrokBackendTest.kt:135`.

### Проверенные ожидания плана

- Числа в тестах Planner и Builder (7 клеток с 6.0 + 9 с 0.62; 11 + 5 с 0.26; 14 клеток по 0.5 с; шаг 0.66; срок 10:00:54; 31 проверка и 30 000 мс виртуального времени) пересчитаны контроллером по формулам плана — совпадают. Если тест падает, сверять реализацию с формулой спеки 5.3, а не подгонять числа.
- Ещё не проверено на деле: MockK `coEvery { … } throws X andThen value`, `kotlinx.coroutines.test.currentTime`/`runCurrent` с `@OptIn(ExperimentalCoroutinesApi::class)` (Tasks 7, 10). Тест «waiting for a next segment does not hold a sampling permit» при зависании означает, что ожидание держит семафор.

### После Task 11

- Финальное ревью всей ветки (SDD, opus) с отложенными minor из ledger (сейчас их 13) → одна волна исправлений → одно scoped re-review.
- Затем по `CLAUDE.md`: `superpowers:code-reviewer`, критичные замечания исправить, потом `./gradlew build` через `claude-forge:build-runner`; при ошибках ktlint — `./gradlew ktlintFormat` и повтор.
- По do-plan (шаг 7): до `superpowers:finishing-a-development-branch` предложить внешнее ревью (`/claude-mesh:code-review-fresh-session`); доступность remote проверять, как там описано.
- Перед PR: отдельный коммит `git rm -r docs/superpowers/`; в описании PR напомнить, что явные `APP_AI_DESCRIPTION_MAX_FRAMES` / `APP_AI_DESCRIPTION_SHORT_MAX` в рабочем `.env` перекроют новые значения по умолчанию.
- В финальном сообщении — список «Rulings I made» из ledger.

### Правила владельца

- Субагенты: не haiku; opus.
- Локальную память (`~/.claude/projects/.../memory/`) не использовать.
- Пакеты ставить через apt (sudo есть), не pipx.
- `git add` только файлы задачи, поимённо: в рабочем дереве много неотслеживаемых файлов не из задачи (`.taskmaster/`, `docs/*.md`, старые файлы в `docs/superpowers/`, `tmp_diff_handler.txt`, `.superpowers/`).
- Сообщения коммитов в стиле репозитория; строку `Claude-Session:` добавлять, только если у сессии есть URL.

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

## INSTRUCTIONS

1. Read the documents listed above
2. Understand current progress and session context
3. Provide a brief summary of what you understood
4. **STOP and WAIT** — do NOT proceed with any implementation
5. Ask: "What would you like me to work on?"
