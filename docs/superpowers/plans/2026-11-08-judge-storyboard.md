# Раскадровка для LLM judge — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Дать judge временной визуальный контекст до решения об уведомлении, сохранив крупные кадры, fail-open и повторное использование раскадровки для описания.

**Architecture:** Существующий `StoryboardBuilder` оборачивается ленивым объектом одного события с мемоизацией результата. Judge получает сетку перед крупными кадрами после своих ранних проверок; описание использует тот же результат. Provider-neutral executor применяет разные feature caps к сетке и обычным кадрам, не обходя ограничения пресета.

**Tech Stack:** Kotlin 2.4.10, Java 25, Spring Boot 4.1.1, Coroutines, Jackson 3, MockK, kotlin.test/JUnit, Gradle.

**Spec:** `docs/superpowers/specs/2026-11-08-judge-storyboard-design.md` — прочитать вместе с планом до реализации.

## Global Constraints

- Ветка уже создана: `feature/judge-storyboard`; работа в текущем каталоге через `git switch -c`, не создавать worktree повторно.
- Раскадровка: существующие `before=5s`, `after=5s`, `tiles=16`, `MIN_STEP_SECONDS=0.5`, end guard `0.1s`, минимум четыре клетки. Не менять planner/sampler/composer и алгоритм соседних сегментов.
- Крупные кадры: прежний отбор, `max-frames=4`, cap `1280`; сетка не входит в `max-frames` и имеет отдельный cap `2560`.
- Новые настройки: `APP_AI_JUDGE_STORYBOARD_ENABLED=true`, `APP_AI_JUDGE_STORYBOARD_MAX_IMAGE_SIDE=2560` (`0` или `256..8192`), `APP_AI_JUDGE_STORYBOARD_TIMEOUT=10s` (положительный бюджет).
- Параметры sampling по-прежнему берутся из `APP_AI_DESCRIPTION_STORYBOARD_{BEFORE,AFTER,TILES,NEXT_SEGMENT_WAIT}`. Выключатели включения сетки в judge и описание независимы.
- Сетка и крупные кадры имеют один ноль `BuiltStoryboard.zeroSeconds`; неизвестное время не выдумывается. Bbox и frameIndex остаются исходными.
- Мемоизация только в пределах события, включая отсутствие сетки и собственный timeout; отмена родителя пробрасывается и не кэшируется. Новых scope/job и глобального кэша нет.
- Caps: минимум ненулевых feature cap выбранной роли и preset cap; `null` для cap сетки означает наследование cap кадров, `0` — отсутствие только feature-ограничения. Нет upscaling и обхода preset cap.
- Не менять модель, пороги YOLO, историю, verdict/reason/schema, политику публикации людей, snooze, `wanted`, Telegram API и схему БД.
- `latency_ms` остаётся временем vision-вызова, не временем сборки сетки. JPEG/base64, секреты и внутренние пути не попадают в `context_json`.
- После каждого создания/изменения файла выполнять `git add <конкретный файл>`. Чужой staged-файл `.claude/skills/rotating-claude-token/SKILL.md` и существующие untracked-файлы не включать в коммиты, не удалять и не форматировать.
- Коммиты задач — через `git commit --only` с явным списком файлов из их блока Files; не использовать `git add .`, `git commit -a` и коммит всего индекса. В шагах «только Files задачи» разрешено также включить `docs/superpowers/plans/2026-11-08-judge-storyboard.md`, если в нём изменены только отметки выполнения соответствующей задачи; отмечать только фактически завершённые шаги.
- ВСЕ build/test/lint, включая точечные RED/GREEN, делегировать `build-forge:build-runner` с `model: opus`. Команды ниже — для этого агента, не для главной сессии. Итоговый полный build — только после code review и исправления критичных замечаний.
- Никаких дополнительных Fable-вызовов, живых модельных запросов, отправки кадров внешнему сервису, деплоя, push или PR без отдельного разрешения.

## Review Focus

1. Собственный timeout против отмены родителя и отмены ожидающего mutex потребителя: только собственный timeout превращается в кэшируемый запасной путь; чужая отмена не портит общий результат. Тесты задачи 3.
2. Все комбинации двух выключателей сетки и runtime-описаний: judge не теряет сетку из-за выключенных описаний, обход judge не тратит его подготовку. Тесты задач 4–5.
3. Отрицательный ноль, неизвестные offsets и разные лимиты отбора крупных кадров: подписи и диагностика согласованы с фактическими изображениями, индекс не становится временем. Тесты задач 2 и 4–5.
4. Портретная камера, preset cap 1568, nullable/нулевой cap и переключение пресета: cap не протекает между вызовами; повторное использование не меняет исходный JPEG сетки. Тесты задач 1 и 5.
5. Shutdown во время подготовки и ошибка модели после подготовки: нет двойного verdict/отправки, а модельный failover сохраняет уже подготовленный `visual_input`. Тесты задачи 5.

---

## Карта файлов и зависимости

Пять задач — один связанный проект, не независимые подсистемы. Порядок: 1 → 2 → 3 → 4 → 5; задача 4 использует контракты задач 1–3, задача 5 связывает их в рабочий путь.

| Узел | Ответственность | Файлы |
|---|---|---|
| Vision limits/config | Роли изображений, caps, настройки | `VisionRequest.kt`, `VisionLimits.kt`, `VisionCallExecutor.kt`, `JudgeProperties.kt`, `AiDescriptionAutoConfiguration.kt`, `application.yaml` |
| Представление модели | DTO judge и общий текст чтения сетки | `JudgeRequest.kt`, `JudgeTask.kt`, `DescriptionTask.kt`, новый `StoryboardPromptFormat.kt` |
| Подготовка события | Ленивая сборка, бюджет, мемоизация | новые `RecordingStoryboard.kt`, `StoryboardPreparation.kt`; нейтральные логи в `StoryboardBuilder.kt` |
| Сборка judge request | Выбор кадров, единая шкала, диагностика | новый `JudgeRequestFactory.kt`, `JudgeCandidate.kt`, `JudgeContextBuilder.kt` |
| Оркестрация | Общий объект judge/description, сохранение контекста | `RecordingProcessingFacade.kt`, `NotificationJudgeService.kt`; документация |

Точные пути и тесты перечислены в задачах. Не создавать общий framework для визуальных задач, не переносить provider SPI и не реорганизовывать модули.

### Task 1: Независимые caps сетки и кадров, конфигурация judge

**Files:**
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/core/VisionRequest.kt`
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/core/VisionLimits.kt`
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/core/VisionCallExecutor.kt`
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/config/JudgeProperties.kt`
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/config/AiDescriptionAutoConfiguration.kt`
- Modify: `modules/core/src/main/resources/application.yaml`
- Create/Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/core/VisionImageTest.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/core/VisionCallExecutorTest.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/config/JudgePropertiesTest.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/config/AiJudgeAutoConfigurationTest.kt`

**Interfaces:**
- Consumes: существующие `FrameDownscaler.effectiveMaxSide(featureCap: Int, presetCap: Int): Int`, `ActivePresetResolver.resolve()` и fake backend в тестах.
- Produces: `VisionImage(bytes: ByteArray, caption: String, role: VisionImage.Role = VisionImage.Role.FRAME)` с вложенным `enum class Role { FRAME, STORYBOARD }`.
- Produces: новый последний параметр `VisionLimits.storyboardMaxImageSide: Int? = null`.
- Produces: новый последний параметр `JudgeProperties.storyboard: JudgeProperties.StoryboardSettings = StoryboardSettings()`; вложенный тип с `enabled: Boolean = true`, `maxImageSide: Int = 2560`, `timeout: java.time.Duration = Duration.ofSeconds(10)`.

- [ ] **Step 1: Написать RED-тесты caps и свойств.** В `VisionCallExecutorTest` переиспользовать существующие `jpeg`, `FakeBackend`, `build` и `call`; тест `storyboardAndFramesHaveIndependentCaps` передаёт два JPEG 4000×3000 с ролями FRAME/STORYBOARD и проверяет размеры у backend. Добавить именованные случаи с такими assertions:

```kotlin
// storyboardAndFramesHaveIndependentCaps: feature 1280/2560, preset 0
assertEquals(listOf(1280, 2560), longestSides)
// presetCapLimitsBothRoles: feature 1280/2560, preset 1568
assertEquals(listOf(1280, 1568), longestSides)
// portraitStoryboardUsesLongestSide: source 1200×4000
assertEquals(768, image.width)
assertEquals(2560, image.height)
// inheritedStoryboardCap: storyboardMaxImageSide=null, maxImageSide=1280
assertEquals(1280, storyboardLongestSide)
// disabledStoryboardFeatureCap: storyboardMaxImageSide=0, presetCap=1568
assertEquals(1568, storyboardLongestSide)
// storyboardCapWorksWithUnlimitedFrames: maxImageSide=0, storyboardMaxImageSide=2560
assertEquals(listOf(4000, 2560), longestSides)
```

В `smallImagesAreNotUpscaled` проверить неизменность байтов JPEG 240×160; в `retryReusesPreparedImagesForBothRoles` — одинаковые prepared bytes в обеих попытках; в `storyboardCapDoesNotLeakAcrossPresets` — вызовы пресетов с cap 1568 и без него дают 1568 и 2560 для одной исходной сетки. В `differentPresetsDoNotMutateSourceStoryboard` до вызовов сделать `val originalGridBytes = sourceGrid.copyOf()`, передать sourceGrid обоим вызовам и после них проверить `assertContentEquals(originalGridBytes, sourceGrid)`. В `VisionImageTest` проверить default FRAME, equality/hashCode одинакового содержимого с одинаковой ролью, неравенство разных ролей и отсутствие дампа байтов в `toString`.

В `JudgePropertiesTest` проверить defaults true/2560/10s, допустимые caps 0/256/8192, отказ для −1/255/8193 и timeout 0/−1s. В `AiJudgeAutoConfigurationTest` проверить binding значений false/1568/3s и сохранение отсутствия judge beans при master off.

- [ ] **Step 2: Запустить RED через build-runner.** Команда: `./gradlew :frigate-analyzer-ai-description:test --tests '*.VisionImageTest' --tests '*.VisionCallExecutorTest' --tests '*.JudgePropertiesTest' --tests '*.AiJudgeAutoConfigurationTest'`. Ожидается провал новых проверок/отсутствие новых контрактов; сбои окружения не считать подтверждением RED.
- [ ] **Step 3: Реализовать контракты.** Учитывать role в equality/hashCode/toString; выбирать feature cap по роли внутри `VisionCallExecutor.downscaleImages`. Нельзя выходить по `limits.maxImageSide == 0`, пока у STORYBOARD есть свой cap. Применять preset cap к каждой роли и готовить изображения один раз до retry; DEBUG содержит роль/cap, не содержимое. Настройки пометить `@Valid`, валидировать диапазон и `timeout.toMillis() > 0`; положительные sub-ms значения, которые округляются в 0, отклонять. Только judge executor получает отдельный cap; description executor сохраняет `null`. Добавить три env-backed значения под `application.ai.judge.storyboard` в yaml.
- [ ] **Step 4: Повторить команду Step 2 через build-runner.** Ожидается `BUILD SUCCESSFUL`, все новые и прежние проверки проходят, ни одного живого backend-вызова.
- [ ] **Step 5: Закоммитить только Files задачи.** Сообщение: `feat(ai): разделить лимиты кадров и раскадровки judge`.

### Task 2: Временная раскадровка в запросе и промпте judge

**Files:**
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/api/JudgeRequest.kt`
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/core/JudgeTask.kt`
- Modify: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/core/DescriptionTask.kt`
- Create: `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/core/StoryboardPromptFormat.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/core/JudgeTaskTest.kt`
- Test: `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description/core/DescriptionTaskTest.kt`

**Interfaces:**
- Consumes: `VisionImage.Role` задачи 1; существующие `DescriptionRequest.Storyboard`, `FrameImage.offsetSeconds` и `DetectionMark`.
- Produces: новый последний параметр `JudgeRequest.storyboard: DescriptionRequest.Storyboard? = null`.
- Produces: `internal object StoryboardPromptFormat` с `caption(storyboard: DescriptionRequest.Storyboard): String`, `explanation(storyboard: DescriptionRequest.Storyboard, hasFrames: Boolean): String`, `seconds(value: Double): String`. `explanation` — общий блок текущего `DescriptionTask` от “The first image is a storyboard” до сообщения о remaining images, без политики задачи.

- [ ] **Step 1: Написать RED-тесты представления.** В `JudgeTaskTest` добавить `storyboardPrecedesChronologicalDetailFrames`: сетка с 16 клетками, step 0.66, duration 10.0, детекцией bird 0.6697 в 5.0s; крупные кадры переданы в порядке 3/0, у 3 offset 5.0, у 0 время неизвестно. Assertions:

```kotlin
assertEquals(listOf(VisionImage.Role.STORYBOARD, VisionImage.Role.FRAME, VisionImage.Role.FRAME), vision.images.map { it.role })
assertEquals(listOf("Storyboard: 16 frames, 0.7s apart, left to right, top to bottom", "Frame 0", "Frame 3 at 5.0s"), vision.images.map { it.caption })
assertEquals("Images:", vision.instructions.imagesHeader)
assertTrue(vision.instructions.preamble.contains("closest to a detection"))
assertTrue(vision.instructions.epilogue.contains("When in doubt about a person, PUBLISH"))
```

Добавить `storyboardExplainsMissingFootageAndDetectionTimes`: флаги missing-before/after отражаются в тексте, время детекции имеет точку и одну цифру после неё, нет утверждения о точном совпадении клетки с детекцией. `withoutStoryboardKeepsLegacyHeaderAndUnknownTimes` сохраняет старый заголовок и `Frame N` для неизвестного offset. Обновить намеренно устаревающий тест `frames are captioned Frame N...`: известный offset теперь даёт `Frame 2 at 5.0s`; его комментарий о полном игнорировании времени больше неверен. В `DescriptionTaskTest` проверить роль сетки, прежние captions/порядок и прежние формулировки; schema, policy, snooze ceiling и provider tool rules не менять.

- [ ] **Step 2: Запустить RED через build-runner.** `./gradlew :frigate-analyzer-ai-description:test --tests '*.JudgeTaskTest' --tests '*.DescriptionTaskTest' --tests '*.DefaultJudgeAgentTest' --tests '*.ClaudePromptBuilderTest' --tests '*.GrokPromptFileWriterTest'`. Ожидается провал новых проверок DTO/представления, не инфраструктурная ошибка.
- [ ] **Step 3: Реализовать представление.** Добавить поле DTO. Вынести только общее форматирование сетки из `DescriptionTask`, сохранив его существующие тексты и `DescriptionTask.seconds` как делегат для совместимости. Обе задачи помечают сетку ролью STORYBOARD. В `JudgeTask` сетка идёт первой, кадры сортируются по индексу; caption кадра — `Frame N at X.Xs`, если offset известен, иначе `Frame N`. При сетке preamble различает неаннотированный обзор и аннотированные detail frames, объясняет шкалу и ограничения; без неё сохраняет прежний режим. Не добавлять новый policy о снеге, количестве кадров или исчезновении объекта.
- [ ] **Step 4: Повторить команду Step 2 через build-runner.** Ожидается `BUILD SUCCESSFUL`; оба provider renderer продолжают получать готовый упорядоченный список изображений без специальной логики раскадровки в backend.
- [ ] **Step 5: Закоммитить только Files задачи.** Сообщение: `feat(ai): добавить раскадровку во вход judge`.

### Task 3: Ленивый результат раскадровки одного события

**Files:**
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/storyboard/StoryboardPreparation.kt`
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/storyboard/RecordingStoryboard.kt`
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/storyboard/StoryboardBuilder.kt`
- Create/Test: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/storyboard/RecordingStoryboardTest.kt`
- Test: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/storyboard/StoryboardBuilderTest.kt`

**Interfaces:**
- Consumes: существующий `suspend StoryboardBuilder.build(recording: RecordingDto, detectionFrames: List<FrameData>): BuiltStoryboard?`; `BuiltStoryboard` остаётся в `StoryboardBuilder.kt`.
- Produces: `data class StoryboardPreparation(val status: Status, val built: BuiltStoryboard?, val elapsed: java.time.Duration)` с вложенным `enum class Status { AVAILABLE, UNAVAILABLE, TIMEOUT }`; AVAILABLE означает ненулевой built, остальные — null.
- Produces: `class RecordingStoryboard(recording: RecordingDto, detectionFrames: List<FrameData>, builder: StoryboardBuilder, timeSource: kotlin.time.TimeSource = TimeSource.Monotonic)` и `suspend fun get(timeout: java.time.Duration? = null): StoryboardPreparation`.

- [ ] **Step 1: Написать RED-тесты жизненного цикла.** Использовать `runTest`, `CompletableDeferred` и mock builder, без настоящего ffmpeg/ожидания стены. Минимальные private recording/frame fixtures взять по образцу существующих storyboard-тестов. `constructionDoesNotBuild` проверяет ноль вызовов до get. `concurrentConsumersShareOneResult` запускает два get через один gate:

```kotlin
assertSame(firstResult, secondResult)
assertEquals(StoryboardPreparation.Status.AVAILABLE, firstResult.status)
coVerify(exactly = 1) { builder.build(any(), any()) }
```

`unavailableResultIsMemoized`: builder возвращает null, оба get дают UNAVAILABLE и один вызов. `ownTimeoutIsMemoized` задерживает builder на 11 s виртуального времени, вызывает get с 10 s, затем get без бюджета: TIMEOUT/null, один build. `parentTimeoutIsNotOwnTimeout` оборачивает get(10 s) во внешний 1 s timeout: наружу выходит отмена, последующий живой get успешно повторяет build. `cancelledMutexWaiterDoesNotInvalidateOwner` отменяет только второго ожидающего потребителя: первый завершает сборку, третий получает тот же объект, всего один build. `cancelledBuilderCanBeRetried` отменяет первый активный get и проверяет успешную вторую попытку. `builderReceivesOnlyDetectionMetadata` захватывает аргумент: исходные JPEG пусты у всех элементов, offsets/детекции сохранены, исходные входные FrameData не изменены. `elapsedUsesInjectedMonotonicSource` проверяет elapsed через `TestTimeSource`, а не часы сервера.

- [ ] **Step 2: Запустить RED через build-runner.** `./gradlew :frigate-analyzer-core:test --tests '*.RecordingStoryboardTest' --tests '*.StoryboardBuilderTest'`. Ожидается отсутствие нового helper/провал его новых проверок.
- [ ] **Step 3: Реализовать helper.** Сразу создать собственный список только кадров с детекциями с `frameBytes=ByteArray(0)`, не удерживать исходный список в closure. Синхронизировать get через Mutex, кэшировать ненулевой `StoryboardPreparation`, включая UNAVAILABLE/TIMEOUT. Бюджет применяется к первой сборке внутри mutex, включая все ожидания builder; повторное обращение не запускает новый бюджет. Отличать null builder от собственного timeout ненулевой обёрткой результата внутри `withTimeoutOrNull`; не ловить произвольный `TimeoutCancellationException` как собственный timeout. Отмену родителя не кэшировать. Обычный сбой builder уже превращает в null по его контракту. Измерять monotonic elapsed; не создавать scope/async. Изменить только consumer-specific WARN-формулировки builder на нейтральные, сохранив INFO и sampling.
- [ ] **Step 4: Повторить команду Step 2 через build-runner.** Ожидается `BUILD SUCCESSFUL`, нет виртуально зависших корутин или реальных задержек 10 s.
- [ ] **Step 5: Закоммитить только Files задачи.** Сообщение: `feat(core): мемоизировать раскадровку события для двух потребителей`.

### Task 4: Сборка JudgeRequest и диагностического visual_input

**Files:**
- Create: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/judge/JudgeRequestFactory.kt`
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/judge/JudgeCandidate.kt`
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/judge/JudgeContextBuilder.kt`
- Create/Test: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/judge/JudgeRequestFactoryTest.kt`
- Test: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/judge/JudgeContextBuilderTest.kt`

**Interfaces:**
- Consumes: контракты задач 1–3, `JudgeContextResult(json: String, errors: List<String>)` из `JudgeContext.kt` и существующие `DescriptionProperties.common.language`/`JudgeProperties.maxSnoozeMinutes`.
- Produces: новый последний параметр `JudgeCandidate.storyboard: RecordingStoryboard? = null`.
- Produces: `@Component` с тем же judge-enabled условием, что у NotificationJudgeService: `class JudgeRequestFactory(properties: JudgeProperties, descriptionProperties: DescriptionProperties, contextBuilder: JudgeContextBuilder)`; метод `suspend fun create(candidate: JudgeCandidate, context: JudgeContextResult): JudgeRequest`.
- Produces: несуспендирующий `JudgeContextBuilder.withVisualInput(context: JudgeContextResult, visualInput: Map<String, Any?>, recordingId: UUID): JudgeContextResult`; он не делает SQL и возвращает исходный context при диагностической ошибке, с WARN.

- [ ] **Step 1: Написать RED-тесты выбора и времени.** В `JudgeRequestFactoryTest` использовать реальный RecordingStoryboard с mock builder и существующий `TestObjectMappers.internalMapper()` для проверки JSON. `selectedFramesShareStoryboardZero` задаёт maxFrames=2, ranked visualized frames 3/1/2, offsets 2.0/null/8.0 и built.zeroSeconds=−3.0:

```kotlin
assertEquals(listOf(1, 3), result.frames.map { it.frameIndex })
assertEquals(listOf(null, 5.0), result.frames.map { it.offsetSeconds })
assertSame(built.storyboard, result.storyboard)
assertEquals(3, json["visual_input"]["image_count"].asInt())
assertEquals("available", json["visual_input"]["storyboard"]["status"].asText())
assertEquals(-3.0, json["visual_input"]["storyboard"]["zero_recording_seconds"].asDouble())
```

`unknownOffsetsStayUnknown` не превращает индекс в секунды; `withoutStoryboardTimesStartAtRecordingZero` оставляет исходный +2.0 без сдвига. `judgeStoryboardSwitchDoesNotAccessHandle` при enabled=false даёт disabled, ноль build, только кадры; `missingHandleIsUnavailable` даёт unavailable без исключения. `unavailableAndTimedOutBuildStillProduceJudgeRequest` проверяет оба исхода задачи 3. `visualInputMatchesActualSelection` сверяет выбранные индексы/времена, image_count, flags, tiles/step/duration и отсутствие bytes/base64/path в JSON. `parentCancellationEscapesFactory` не превращает отмену в готовый запрос.

В `JudgeContextBuilderTest` добавить `visualInputIsAddedWithoutDatabaseReads`: исходные блоки и errors сохранены, вызовов stats/tracks/verdicts нет; `brokenDiagnosticContextReturnsOriginal`: невалидный исходный JSON возвращает исходный объект, без исключения. В factory-тесте с таким контекстом изображения всё равно готовы.

- [ ] **Step 2: Запустить RED через build-runner.** `./gradlew :frigate-analyzer-core:test --tests '*.JudgeRequestFactoryTest' --tests '*.JudgeContextBuilderTest'`. Ожидается отсутствие нового factory/провал новых assertions.
- [ ] **Step 3: Реализовать factory и локальную диагностику.** Выбор кадров точно как прежде: `candidate.visualizedFrames.take(maxFrames).sortedBy { it.frameIndex }`, затем присоединить известные offsets из candidate.frames по индексу. Только при judge.storyboard.enabled вызвать handle.get(properties.storyboard.timeout). В отсутствие handle статус unavailable; при выключателе disabled, без get. Сдвигать времена на built.zeroSeconds или 0.0. Factory не читает БД и не знает о Telegram.

Зафиксировать JSON-форму, используя Map с явными snake_case ключами: `visual_input.frames=[{"index":3,"offset_seconds":5.0}]`, `visual_input.storyboard={"status":"available","tiles":16,"step_seconds":0.66,"duration_seconds":10.0,"zero_recording_seconds":-3.0,"missing_before":false,"missing_after":false}`, `visual_input.preparation_ms` и `visual_input.image_count`. Неизвестное время — JSON null. Для disabled/unavailable/timeout у storyboard остаётся только status; preparation_ms — 0 при отсутствии попытки, иначе elapsed из общего результата. Добавлять блок через withVisualInput; он сохраняет errors, ловит ошибку parse/serialize и возвращает исходный context, не выдавая диагностический сбой за сбой judge. Factory возвращает один окончательный JudgeRequest с этим contextJson, языком и maxSnoozeMinutes; отмена пробрасывается. Лог подготовки содержит recording id, статус, elapsed и количество изображений, но не содержимое.

- [ ] **Step 4: Повторить команду Step 2 через build-runner.** Ожидается `BUILD SUCCESSFUL`; отключённый/missing/timeout storyboard не мешает построить запрос по кадрам.
- [ ] **Step 5: Закоммитить только Files задачи.** Сообщение: `feat(core): собирать временной вход и диагностику judge`.

### Task 5: Подключение общего результата к judge и описаниям

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/facade/RecordingProcessingFacade.kt`
- Modify: `modules/core/src/main/kotlin/ru/zinin/frigate/analyzer/core/judge/NotificationJudgeService.kt`
- Test: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/facade/RecordingProcessingFacadeTest.kt`
- Test: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/judge/NotificationJudgeServiceTest.kt`
- Test: `modules/core/src/test/kotlin/ru/zinin/frigate/analyzer/core/storyboard/StoryboardWiringTest.kt`
- Modify: `README.md`
- Modify: `.claude/rules/ai-description.md`
- Modify: `.claude/rules/configuration.md`
- Modify: `.claude/rules/pipeline.md`

**Interfaces:**
- Consumes: `RecordingStoryboard`, `StoryboardPreparation.built`, `JudgeCandidate.storyboard`, `JudgeRequestFactory.create` задач 3–4.
- Produces: прежние `processAndNotify`, `submit` и `descriptionSupplier: (() -> Deferred<Result<DescriptionResult>>)?` без изменения внешних сигнатур. У NotificationJudgeService новый constructor dependency `requestFactory: JudgeRequestFactory` вместо его единственного использования DescriptionProperties.
- Produces: приватный `RecordingProcessingFacade.buildDescriptionSupplier(recording: RecordingDto, request: SaveProcessingResultRequest, recordingStoryboard: RecordingStoryboard?): (() -> Deferred<Result<DescriptionResult>>)?`; оба потребителя получают тот же handle.

- [ ] **Step 1: Написать RED-тесты общего объекта.** В `RecordingProcessingFacadeTest` захватить реальный JudgeCandidate из submit: проверить, что до обращения к нему builder не вызван. `judgeAndDescriptionSharePreparedStoryboard` получает результат через candidate.storyboard.get(10s), затем запускает descriptionSupplier и сравнивает `assertSame(prepared.built!!.storyboard, capturedDescription.storyboard)`, времена и `coVerify(exactly=1) { builder.build(any(), any()) }`. Передавать в builder все кадры с детекциями с пустыми bytes, даже если image caps потребителей меньше их числа. `judgeWorksWhenRuntimeDescriptionsAreOff` даёт ненулевой handle и null descriptionSupplier; `descriptionStoryboardOffDoesNotDisableJudgeHandle` не добавляет сетку в описание, но handle для judge существует. `unavailablePreparationIsNotRetriedByDescription` проверить для UNAVAILABLE и TIMEOUT. Сохранить прежние тесты lazy запуска и повторного чтения runtime switch; при отсутствующем judge описание остаётся ленивым и использует свой обычный бюджет. До запуска потребителей сохранить независимую копию `val originalGridBytes = built.storyboard.image.copyOf()`; после judge/description проверить `assertContentEquals(originalGridBytes, prepared.built!!.storyboard.image)`. Сам downscale одного исходного JPEG через разные пресеты отдельно проверен в задаче 1; здесь не дублировать executor fixtures.

- [ ] **Step 2: Написать RED-тесты порядка judge и сохранения контекста.** В `NotificationJudgeServiceTest` внедрить mock factory в существующий `TestScope.service`, сохранив смысл старых fixtures. `requestIsPreparedOnlyAfterAllGates` подтверждает порядок context build → limiter → factory → agent. Для JUDGE_OFF, snooze, NO_RECIPIENTS, CONTEXT_ERROR, RATE_LIMITED, queue overflow и in-flight overflow проверять `coVerify(exactly=0) { requestFactory.create(any(), any()) }`. `missingAgentSkipsPreparation` сохраняет прежний failover без вызова factory.

Для `preparedContextIsPersistedOnPublishSuppressAndModelFailure` factory возвращает JudgeRequest с отличимым `contextJson`, а агент по очереди даёт PUBLISH, SUPPRESS и DescriptionException.Transport; в каждой проверке `assertEquals(preparedRequest.contextJson, recorded.single().contextJson)`, не исходный JSON. `cancellationDuringPreparationSendsOnce` останавливает create на gate, отменяет job и проверяет ровно одну строку FAILOVER/TRANSPORT и одну отправку. Старые тесты отмены во время записи verdict/fan-out оставить. В `StoryboardWiringTest` подтвердить, что runtime-выключатели не убирают builder bean, master-каталог по-прежнему необходим, а отсутствие нового judge factory при master judge off не ломает приложение.

- [ ] **Step 3: Запустить RED через build-runner.** `./gradlew :frigate-analyzer-core:test --tests '*.RecordingProcessingFacadeTest' --tests '*.NotificationJudgeServiceTest' --tests '*.StoryboardWiringTest'`. Ожидается провал новых проверок wiring/повторного использования/контекста, а не внешнего PostgreSQL или модельного endpoint.
- [ ] **Step 4: Подключить потребителей.** В facade после положительного решения трекера создать handle при наличии builder независимо от включённости описаний, передать его и в candidate, и в supplier. Внутри supplier вызывать get() только при description storyboard enabled, а затем сохранить существующее повторное чтение runtime switch и сдвиг времён. Результат с built=null не пересобирать. Не запускать helper в constructor/submit/pipeline consumer.

В NotificationJudgeService заменить private judgeRequest на factory. Вызов create находится после rate limiter, внутри уже существующего try вызова модели и после проверки наличия JudgeAgent. Сохранить итоговый request.contextJson в локальной переменной перед agent.judge и использовать её как для JUDGE, так и для модельного FAILOVER; до готовности request остаётся исходный context.json. Не менять границы NonCancellable, handedOver, record, send, per-camera mutex и in-flight permit. Обновить только затронутые constructor fixtures, не переписывать старые тесты под новый смысл.

- [ ] **Step 5: Обновить документацию вместе с рабочим подключением.** В README — новый визуальный вход, три настройки, откат и дополнительная стоимость изображений. В ai-description.md — новый этап подготовки между rate limit и моделью, общий lazy handle, timeout/cancellation, временная шкала и диагностика. В configuration.md — точные env defaults, 0/null semantics, независимые switches и общие sampling-настройки под старым description prefix. В pipeline.md — consumer по-прежнему не ждёт judge, подготовка после лимитов. Не править корневой CLAUDE.md или чужие локальные документы: нужные подробности уже живут в conditional rules.
- [ ] **Step 6: Повторить команду Step 3 через build-runner.** Ожидается `BUILD SUCCESSFUL`; все новые проверки и существующие гарантии отмены/рассылки проходят. Далее через того же агента запустить `./gradlew :frigate-analyzer-ai-description:test :frigate-analyzer-core:test`; ожидается `BUILD SUCCESSFUL`, включая прежние storyboard/provider-тесты. О сбое инфраструктуры сообщать отдельно, не объявлять тесты зелёными.
- [ ] **Step 7: Закоммитить только Files задачи.** Сообщение: `feat(core): дать judge общий временной контекст до уведомления`.

## Итоговая проверка и передача

- [ ] Самостоятельно сверить diff с дизайном: пять пунктов Review Focus имеют названные тесты; sampling/policy/schema/Telegram API/БД не изменены; все новые файлы добавлены явно.
- [ ] Использовать `superpowers:requesting-code-review` для свежего whole-branch review относительно базового `74d99f0b`, по проектному правилу `superpowers:code-reviewer`; модель — Opus, не Fable. Если требуемый тип агента недоступен, согласовать эквивалентного reviewer, не пропускать ревью молча. Исправить критичные замечания, повторить до чистого результата. При subagent-driven исполнении это дополняет проверки задач; при Native это первая независимая проверка всей реализации.
- [ ] После review делегировать `build-forge:build-runner` (`model: opus`) полный `./gradlew build`. Ожидается `BUILD SUCCESSFUL` и отсутствие упавших тестов. При ktlint errors тот же агент выполняет `./gradlew ktlintFormat`; просмотреть весь diff форматирования, не включать посторонние изменения, явно git add только файлы задачи и повторить review существенных правок, затем build.
- [ ] Использовать `superpowers:verification-before-completion`; подтвердить итоговый diff/commit list и состояние чужих staged/untracked файлов. Не заявлять о снижении false positives только по unit-тестам: подтверждено добавление контекста, а не качество живой модели.
- [ ] Живое сравнение старого/нового входа на исходной записи и настоящих событиях, отправка кадров провайдерам, deploy/push/PR — только после отдельного разрешения. Не запускать их автоматически по завершении build.
- [ ] Перед PR выполнить проектное правило удаления design/plan: `git rm -- docs/superpowers/specs/2026-11-08-judge-storyboard-design.md docs/superpowers/plans/2026-11-08-judge-storyboard.md`, отдельный коммит только этих удалений. Существующие чужие untracked-файлы внутри docs/superpowers не трогать; убедиться, что ни одного docs/superpowers файла нет в PR diff. Документы сохранятся в истории ветки.

## Самопроверка плана

Спецификация покрыта: визуальный вход/время — задачи 2 и 4; разрешение и конфигурация — 1; единичная подготовка/ресурсы/бюджет — 3; диагностика — 4–5; порядок выполнения, switches, fail-open, отсутствие регрессий описаний — 5; неизменность sampler/provider-семантики и безопасная передача — Global Constraints и итоговая проверка. Интерфейсы между задачами определены выше; новых незаданных зависимостей нет.

Реализация начинается только после просмотра этого плана пользователем и выбора способа исполнения. Само составление плана не разрешает выполнять его задачи.
