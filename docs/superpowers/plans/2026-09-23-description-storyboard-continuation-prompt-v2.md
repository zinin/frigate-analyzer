## TASK

Continue executing the implementation plan for «раскадровка для AI-описаний» (description storyboard): Tasks 10–11 of 11.

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
- Plan: `docs/superpowers/plans/2026-09-23-description-storyboard.md` (Tasks 1–9 are trimmed to commit references)
- SDD ledger — the authoritative progress record, preflight table, Rulings 1–7 and the deferred minor findings: `.superpowers/sdd/2026-09-23-description-storyboard/progress.md` (git-ignored). Its last line (`PAUSE: …`) says what Task 10 must carry.

Read both documents and the ledger to understand the full picture. Also read `CLAUDE.md`, `.claude/rules/ai-description.md`, `.claude/rules/pipeline.md`, `.claude/rules/configuration.md`.

## PROGRESS

**Completed tasks:**
- [x] Task 1: время кадра доходит до фасада — `55faa80`
- [x] Task 2: картинки с подписями в `VisionRequest` — `321dd14`
- [x] Task 3: короткое описание вдвое длиннее — `bc3ab96`
- [x] Task 4: раскадровка в промпте описания (`DescriptionRequest.Storyboard`, тексты `DescriptionTask`) — `b8045a4`
- [x] Task 5: `StoryboardPlanner` — `965eaf7`
- [x] Task 6: запросы соседних сегментов (репозиторий и сервис) — `552e3e6`
- [x] Task 7: `AdjacentSegmentFinder` — `6dca779`
- [x] Task 8: `StoryboardFrameSampler` — `816c0fe`, `ab6f8c4` (Ruling 4)
- [x] Task 9: `StoryboardComposer` — `8b08a3b`, `cd422a8` (Ruling 6), `87cdf8e` (Ruling 7)

**Remaining tasks:**
- [ ] Task 10: `StoryboardProperties` и `StoryboardBuilder`
- [ ] Task 11: фасад отдаёт раскадровку модели, документация

## SESSION CONTEXT

Отвечать по-русски. Идентификаторы кода и команды не переводить.

### Как шло исполнение

- Запуск: `/claude-mesh:do-plan <порог>` → `superpowers:subagent-driven-development`. В новой сессии do-plan запустить заново: он пишет конфиг хука контекста для новой сессии. `runtime.dispatch_model` = `opus` — каждый субагент (исполнитель, ревьюер, re-review) диспатчится с `model: "opus"`. Прошлая сессия шла с `500k` и остановилась по `/claude-mesh:pause-after-current-task` после Task 9.
- Ledger: задачи со строкой `Task N: complete` сделаны — не диспатчить повторно. Дописывать, не пересоздавать.
- Цикл задачи: `bash <skill-dir>/scripts/task-brief <PLAN> N` → brief (бриф Task 10 уже есть — `task-10-brief.md`, текст Task 10 при обрезке плана не менялся); исполнитель по `implementer-prompt.md` пишет полный отчёт в `task-N-report.md`; `bash <skill-dir>/scripts/review-package <PLAN> <BASE> <HEAD>` → один ревьюер по `task-reviewer-prompt.md`: brief, отчёт, пакет, дословные цитаты Global Constraints и разделов спеки, действующие Rulings. Раунд исправления — тому же исполнителю через `SendMessage`, затем scoped re-review по `re-review-prompt.md`. BASE — `git rev-parse HEAD` перед диспатчем.
- HEAD: `af7695c` (обрезка плана), затем коммит с этим промптом.

### Практика диспатча

- В диспатч исполнителю: «Run commands in the foreground and do not end your turn while a run is still in progress — finish, commit and report in one go». Без этого исполнитель Task 5 ушёл ждать фоновый прогон Gradle без коммита, пришлось будить.
- `ktlintCheck --rerun` перезапускает только lifecycle-задачу; принудительная проверка — задачи `runKtlintCheckOver*SourceSet`.
- Idle-уведомления субагентов повторяют уже пришедший отчёт — второй раз не обрабатывать. Отчёт в idle-уведомлении бывает обрезан — хвост запрашивать через `SendMessage`.
- Перед диспатчем полезно заранее проверить то, что бриф берёт на веру (конструкторы, бины, реализации интерфейса), и написать это в «Context»: так прошли задачи 6–8.

### Решения (Rulings, все есть в ledger)

1. **Gradle.** Исполнители запускают `./gradlew` сами: `JAVA_HOME=/usr/lib/jvm/zulu25 ./gradlew … --no-watch-fs --console=plain` (Java по умолчанию — 21; `claude-forge:build-runner` субагентам недоступен). Контроллер `./gradlew` сам не запускает; финальный `./gradlew build` — через `claude-forge:build-runner`.
2. **Task 10.** Если нарезка СЛЕДУЮЩЕГО сегмента упала и его клетки выпали, `Storyboard.durationSeconds = min(footage.range.end, currentDuration) − zero`, чтобы «Footage after X s is not available» называла реальный конец. Сбой ПРЕДЫДУЩЕГО оставляет `zeroSeconds = footage.range.start` (−4.0 в тесте плана). Один тест в `StoryboardBuilderTest`: нарезка следующего бросает → `missingAfter`, 11 клеток, `durationSeconds` 7.0 (кадр на 10.0 с, D = 12).
3. **ffmpeg по следующему сегменту не повторяется** (как в плане): при сбое его клетки выпадают, `missingAfter`. Спека 5.4 говорит «повторяем ffmpeg в пределах срока», но её же таблица отказов (раздел 7) описывает именно выпадение; ffprobe в finder повторяется.
4. **Task 8, фильтр ffmpeg** — `fps=<R>:start_time=0:round=up,scale=<w>:-2` вместо плановского `fps=<R>`. Плановский (round=near) на ffmpeg 8.0.1 показывал кадр на ≈ step/2 позже подписи и терял клетку у конца файла (спека 5.5 обещает погрешность около кадра). Теперь клетка — последний кадр не позже своего момента; первая — первый кадр после точки перехода.
5. **Task 10, сетка.** В `composer.compose` передавать `GridLayout(layout.columns, ceil(tiles.size / layout.columns))`: столбцы и ширина клетки, в которую уже нарезал ffmpeg, те же, строк — по фактическим клеткам, поэтому пустые ячейки бывают только в последнем ряду. Закрепить в тесте «a failure on a neighbour leaves only its tiles out»: 9 клеток 64×36 в 4 столбцах → холст 256×108.
6. **Task 9, подпись.** Шрифт — база `max(12, h/10)`, уменьшается, пока самая широкая подпись сетки с подложкой не влезет в клетку (один шрифт на сетку); 16:9 побайтно как в плане; у 9:16 подпись отмеченной клетки раньше обрезалась.
7. **Task 9, `JpegCodec`.** Публичный `object` в `ai-description/core`: `decode(bytes): BufferedImage?` через `MemoryCacheImageInputStream` и `encode(image, quality)` через `MemoryCacheImageOutputStream`. Им пользуются `FrameDownscaler` и `StoryboardComposer`, у каждого своя `JPEG_QUALITY = 0.85f`. `core` впервые импортирует пакет `ai.description.core`.

### Что передать в Task 10

- В диспатч исполнителю и в блок ограничений ревьюера — Rulings 2 и 5 с их тестами, Ruling 3 как контекст.
- Интерфейсы, которых бриф не знает:
  - `StoryboardComposer.compose` декодирует клетки через `JpegCodec` и бросает `IllegalArgumentException` на нечитаемой клетке;
  - `StoryboardFrameSampler.sample` бросает при сбое ffmpeg и может вернуть меньше кадров, чем просили, если файл кончился раньше. Тогда выпадает хвост группы, и `group.zip(images)` в плане это уже учитывает;
  - у `AdjacentSegmentFinder.next` срок мягкий (до +1 с после него), ffprobe соседа идёт вне семафора.
- На ревью Task 10 проверить (пункты ⚠️ из ревью задач 4–9):
  - срок `min(начало задачи, fileCreationTimestamp + D) + next-segment-wait`;
  - `next()` вызывается без permit;
  - fail-open ловит всё, кроме отмены, включая `InvalidPathException` из finder;
  - если когда-нибудь ставить жёсткий потолок вокруг `next()` — только `withTimeoutOrNull`, не `withTimeout`;
  - подпись и `marked` строятся из одного флага (`tileLabel(moment − zero, index in marked)`);
  - `tiles` валидируется в пределах 4..25: на этом держится KDoc композера «клетка в проде не уже 512 px».
- Вероятная находка ревьюера: строка INFO из плана перечисляет `next` в `(prev+current+next)` и печатает `footage …..end`, даже если нарезка соседа упала. Решить на ревью.
- Ещё не проверено на деле: `kotlinx.coroutines.test.runCurrent` с `@OptIn(ExperimentalCoroutinesApi::class)`. `throws … andThen` (MockK 1.14.11) и `currentTime` уже проверены в Task 7 и работают. Если зависнет тест «waiting for a next segment does not hold a sampling permit», значит, ожидание держит семафор.
- Числа в тестах Builder контроллер пересчитал по формулам плана: 7 клеток с 6.0 + 9 с 0.62; 11 + 5 с 0.26; 14 клеток по 0.5 с; шаг 0.66; срок 10:00:54. Если тест падает, сверять реализацию со спекой 5.3, а не подгонять числа. Сэмплер в этих тестах замокан, так что Ruling 4 на числа не влияет.
- Бины. Все зависимости раскадровки — безусловные бины:
  - `VideoProbe`, `FfmpegProcessRunner`, `TempFileHelper` — `@Component`;
  - `Clock` — из `common/ClockConfig`;
  - `RecordingEntityService`.
  `StoryboardProperties` регистрирует сама Task 10. Контекстного теста, который поднял бы бины `core/storyboard` при `application.ai.description.enabled=true`, нет. `DescriptionRuntimeSettingsWiringTest` и `JudgeRuntimeSettingsWiringTest` ставят этот флаг — стоит посмотреть, что они поднимают.

### Что передать в Task 11

- `RecordingProcessingFacadeTest.kt:223` — давнее предупреждение «Expression is unused».
- На ревью проверить, что `FrameImage.offsetSeconds` сдвигается к нулю раскадровки: `?.minus(zero)`, а без раскадровки `zero = 0.0`.
- Раздел `## Storyboard` в `.claude/rules/ai-description.md` — на него уже ссылается строка Layers про `DescriptionRequest` («see "Storyboard"»). Текст раздела в плане написан до Rulings 3–7, в него нужно внести:
  - фильтр сэмплера `round=up`/`start_time=0`: клетка — последний кадр не позже своего момента;
  - строки сетки считаются по фактическим клеткам;
  - подпись ужимается под клетку;
  - `JpegCodec`;
  - ffmpeg по соседу не повторяется.
  Строка `JpegCodec` в Layers уже добавлена в Task 9.

### После Task 11

- Финальное ревью всей ветки (SDD, opus) с отложенными minor из ledger. Сейчас там 47 строк `minor (deferred` — часть повторяет одни и те же давние предупреждения компилятора. Затем одна волна исправлений и одно scoped re-review.
- Затем по `CLAUDE.md`: `superpowers:code-reviewer`, критичные замечания исправить, потом `./gradlew build` через `claude-forge:build-runner`. При ошибках ktlint — `./gradlew ktlintFormat` и повтор.
- По do-plan (шаг 7): до `superpowers:finishing-a-development-branch` предложить внешнее ревью (`/claude-mesh:code-review-fresh-session`). Доступность remote проверять так, как описано там.
- Перед PR: отдельный коммит `git rm -r docs/superpowers/`. В описании PR напомнить, что явные `APP_AI_DESCRIPTION_MAX_FRAMES` / `APP_AI_DESCRIPTION_SHORT_MAX` в рабочем `.env` перекроют новые значения по умолчанию.
- В финальном сообщении — список «Rulings I made» из ledger (1–7 и все новые), у каждого — цена ошибки.

### Окружение

- ffmpeg/ffprobe 8.0.1 в `/usr/bin` с кодерами `mpeg4` и `mjpeg`, шрифты DejaVu, Docker: интеграционные тесты ffmpeg и Java2D должны проходить, а не пропускаться. Testcontainers работают.
- База на `87cdf8e`: ai-description — 345 тестов (1 пропуск: `ClaudeBackendIntegrationTest` с `@Disabled`), core — 497, service — 125. Всё зелёное.
- Давний шум в выводе тестов, появился не в этой ветке (в ledger отложен):
  - предупреждения компилятора `DetectService.kt:213/:215`, `DetectServiceDispatcher.kt:19/:267`, `RecordingProcessingFacadeTest.kt:223`, `GrokPromptFileWriterTest.kt:98`, `GrokBackendTest.kt:135`;
  - предупреждения byte-buddy про `sun.misc.Unsafe`.

### Правила владельца

- Субагенты: не haiku; opus.
- Локальную память (`~/.claude/projects/.../memory/`) не использовать.
- Пакеты ставить через apt (sudo есть), не pipx.
- `git add` только файлы задачи, поимённо. В рабочем дереве много неотслеживаемых файлов не из задачи: `.taskmaster/`, `docs/*.md`, старые файлы в `docs/superpowers/`, `tmp_diff_handler.txt`, `.superpowers/`.
- Сообщения коммитов в стиле репозитория. Строку `Claude-Session:` добавлять, только если у сессии есть URL. Коммиты задач 5–9 несут URL прошлой сессии: он появился после `/remote-control`. В новой сессии — её собственный URL, если он есть.
- Ничего не пушить без просьбы.

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
