# Merged Design Review — Iteration 1

**Дата:** 2026-08-03
**Design (ТЗ):** `docs/task-notification-noise-reduction.md`
**Plan:** `docs/superpowers/plans/2026-08-03-notification-noise-reduction.md`
**Ветка:** `feature/notification-noise-reduction` (план — коммит `f351ea4`)

## Состав панели

Диспатч: 8 ревьюеров из пресета `defaults.design_review` (`builtin: claude, codex`; `claude_models: opus, fable`;
`models: zai/glm, alibaba/qwen, deepseek/v4-pro, ollama/kimi, ollama/minimax`).

| Ревьюер | Итог |
|---|---|
| `claude:fable` | ✅ отчёт получен |
| `ext-claude (deepseek/v4-pro)` | ✅ отчёт получен |
| `ext-claude (ollama/kimi)` | ✅ отчёт получен |
| `claude:opus` | ❌ сбой — агент трижды уходил в idle, не прислав текст ревью |
| `codex-executor` | ❌ сбой — прогон убит на лету (SIGTERM, `verify-delegation: STALLED`), `output.txt` не создан |
| `ext-claude (zai/glm)` | ❌ сбой — первый прогон убит 10-минутным потолком Bash, перезапущенный убит массовым SIGTERM в 14:32:07, `STALLED` |
| `ext-claude (alibaba/qwen)` | ❌ сбой — то же (`STALLED`) |
| `ext-claude (ollama/minimax)` | ❌ сбой — то же (`STALLED`) |

**Причина потерь.** Две волны внешних убийств процессов, не связанные с провайдерами:
первая — 14:28:00–14:29:30, 10-минутный потолок Bash-инструмента у трёх исполнителей (все три сами
перезапустились в detached-режиме); вторая — 14:32:06–14:32:07, массовый SIGTERM по всем четырём
оставшимся watchdog'ам с точностью до секунды. Во всех случаях `watchdog.log` показывает здоровые
`alive`-heartbeat'ы вплоть до `cleanup exit_code=143`, то есть модели работали и не успели дописать
ответ. `verify-delegation.sh` для всех четырёх: `STALLED — run dir present but not finalized
(killed mid-flight)`. Передиспатч не выполнялся (правило скилла: `watchdog.sh` — единственный
retry-слой).

Итог: ревью опирается на 3 из 8 ревьюеров. Все три — независимые модели, каждая сверялась с кодом.

---

## claude:fable

Источник: прямой ответ агента (built-in general-purpose на модели fable).

### Проверено против реального кода ветки

Сверены цитаты и номера строк плана с `ObjectTrackerServiceImpl.kt`, `NotificationDecisionServiceImpl.kt`,
`ObjectTrackerProperties.kt`, `NotificationDecision.kt`, обоими тестовыми файлами,
`ObjectTrackerPropertiesBindingTest.kt`, `application.yaml`, `FrigateAnalyzerApplication.kt`,
`.env.example`, `configuration.md`, `telegram-notifications.md`.

Сошлось: диапазоны строк 165–168 / 208 / 218–222 / 242–257 в трекере и 75–81 в decision-сервисе точны;
smart-cast `absence` внутри гарда строки 213 делает `ClassAbsence(bbox.className, absence)`
компилируемым; порядок инициализации `reappearClassesNormalized` перед `init` корректен и планом
оговорён; фикстуры `watchFrom` / `longTtlProps` / `track()` / `det()` / `rec()` / `recId` существуют и
используются согласованно; конструктор `NotificationDecisionServiceImpl` имеет ровно один не-Spring
call site (строка 38 теста), `@EnableConfigurationProperties` обновляется, интеграционные тесты грузят
app-класс, так что новый бин доедет; `${NOTIFICATIONS_TRACK_REAPPEAR_CLASSES:}` → пустая строка →
пустой список (`delimitedListToStringArray("")` даёт пустой массив), и это правильно запиннено
биндинг-тестом; ожидаемая строка в `TrackerSummaryTest` посимвольно совпадает с `render()`. Логика
abs-кулдауна прогнана по всем 12 тестам плана — все проходят на предложенной реализации.

### Critical Issues

**C1. Все commit-шаги нарушают собственное правило плана о path-scoped коммитах — чужие staged-файлы
уедут в фичевые коммиты и в PR.**
Global Constraints (план, строки 27–34) требуют `git commit <path> <path> … -m` и проверку
`git show --stat HEAD` после каждого коммита. Но фактические команды во ВСЕХ шагах — `git add <paths>`
+ голый `git commit -m`: Task 1 Step 7 (строки 373–383), Task 2 Step 11 (798–815), Task 3 Step 11
(1340–1359), Task 4 Step 2 (1428–1431), Verification Step 4 (1461–1465). В индексе уже сидят
посторонние staged-файлы (`A docs/deep-research-review-report.md`,
`A docs/telegram-rich-message-migration.md`,
`A docs/superpowers/plans/2026-08-03-watch-records-registration-continuation-prompt.md`,
`A …-execution-prompt.md`, `M` самого плана) — первый же коммит Task 1 затащит их все, а
execution-prompt попадёт в PR diff вопреки глобальному правилу «docs/superpowers не должны появляться
в PR». Сабагент-исполнитель выполняет шаги буквально, значит это случится детерминированно.
Фикс: во всех пяти шагах `git commit -m "…" -- <те же пути>` и вписать `git show --stat HEAD` прямо в
текст шагов.

**C2. Verification Step 4 не выполнится как написано.**
Строки 1461–1465: `git rm -r --cached <plan>` убирает файл из индекса (он становится untracked), после
чего второй командой `git rm <тот же файл>` git падает с «pathspec did not match any files». Нужна одна
команда: либо `git rm <file>` (индекс + диск), либо только `--cached`, если файл должен остаться на
диске. Плюс тот же голый `git commit -m` из C1.

### Concerns

**1. Check-then-act гонка на якоре кулдауна при параллельной обработке одной камеры.**
`reappearCooldownGap` (чтение CHM) и `rememberReappearNotified` (запись `merge`) — два неатомарных
обращения. Конвейер держит несколько consumer-корутин: `FrameAnalysisPipeline.kt:56-59` —
`getTotalCapacity(FRAME).coerceAtLeast(minConsumers)`, и уже в примерном прод-конфиге
`docker/deploy/application-docker.yaml.example` `frame-requests.simultaneous-count: 4` даёт 4
консьюмера с одним сервером. Одновременная оценка двух записей одной камеры — задокументированная в
кодовой базе реальность: KDoc `Watch` в `ObjectTrackerServiceImpl.kt:47-52` написан ровно против неё, а
мьютекс на `:100` сериализует только участок трекера. Два конкурентных REAPPEARED могут оба прочитать
старый якорь и оба уведомить. Последствие ограничено лишним уведомлением (fail-open, в духе трекера),
но план молчит об этом полностью — при том что трекер свои конкурентные инварианты расписывает
подробно. Минимум — задокументировать в KDoc `lastReappearNotified` как принятый компромисс; дешёвое
полное закрытие — свернуть решение и запись в один `compute(camId)` (см. S1).

**2. Якорь пишется в момент решения, а не факта отправки — редкий fail-closed.**
`RecordingProcessingFacade.kt:70-95`: после `evaluate(...) == notify` отправка может упасть (catch на
`:91-95`, только лог). Якорь уже записан, значит следующие реаппиры камеры давятся кулдауном — событие
теряется целиком, хотя ни одно уведомление не ушло. До фичи потерялось бы одно уведомление, а следующий
реаппир через секунды прошёл бы. Вероятность мала (send — enqueue в очередь бота), горизонт — длина
кулдауна, но это единственное fail-closed место в подсистеме с последовательным fail-open биасом.
Минимум — задокументировать; «подтверждение отправкой» в объём задачи явно не входит.

**3. Склейка `maxAbsence` в `evaluateLocked` не покрыта ни одним тестом.**
Task 1 Step 5 (строки 288–295 плана): `maxAbsence = maxAbsence?.coerceAtLeast(absence) ?: absence` —
единственный потребитель значения это DEBUG-строка, в `DetectionDelta` оно не попадает,
`TrackerSummaryTest` пиннит только формат `render()`. Ошибка склейки — например, случайный перенос
обновления внутрь ветки `absence > reappearGap` — уничтожает главное назначение задачи 3 ТЗ («включая
те, что порог не перешли») и не роняет ни один тест. Варианты: (а) вынести накопление в крошечный
internal-агрегатор с юнит-тестом; (б) осознанно принять дыру и записать это в план текстом.

**4. Sub-threshold отсутствия почти никогда не попадут в лог — цель задачи 3 ТЗ достигается лишь
частично. Противоречие сидит в самом ТЗ.**
Условие эмиссии сохранено (требование ТЗ §2 задача 3), но запись «только matched, всё ниже порога» —
главный носитель sub-threshold данных — под него не попадает: `worthLogging == false`. За прод-ночь это
5156 all_repeated без единой строки; 50-минутное отсутствие мерцающего велосипеда при `gap=PT1H` не
будет видно нигде, кроме случайного совпадения с чужим new/unobserved на той же записи. То есть граница
видна только «сверху» (сработавшие реаппиры несут длительности в `reappeared=[class:duration]` — этого
для групп A и B, вероятно, достаточно), а запас «снизу» — нет: узнать, что понижение gap на 10 минут
начнёт ловить страховочный сценарий, из этих логов нельзя. Требования ТЗ «включая те, что порог не
перешли» и «условие логирования сохранить» конфликтуют между собой; план унаследовал конфликт молча.
Нужно осознанное решение автора: принять ограничение (и записать) или дать sub-threshold данным дешёвый
канал (например, безусловный TRACE).

**5. Третье незадекларированное отклонение от ТЗ: список из одних пробелов роняет контейнер.**
ТЗ (строка 168): «пустые элементы списка игнорировать» — буквально это значит `[" ", ""]` → пусто → все
классы (no-op). План (строки 592–595, тест 509–515) делает это ошибкой биндинга. Решение в стиле проекта
(fail-fast на инвариантах) и по сути правильное — тихое «все классы» при опечатке противоположно
намерению оператора, — но секция «Deviations from the spec» перечисляет два отклонения, а это третье.
Дописать его туда с обоснованием, иначе ревьюер исполнения «упростит» require по букве ТЗ. No-op-гарантия
не затронута: несконфигурированная переменная даёт пустую строку → пустой список → require проходит.

**6. Утверждение «DEBUG уже включён на проде» опирается на gitignored-файл; свежий деплой не увидит
ничего из задачи 1.**
`**/application-docker.yaml` в `.gitignore:49`; в репозитории только `application-docker.yaml.example`
(одни detect-servers, никаких `logging.level`) и `log4j2.yaml.example` (`ru.zinin` = info). Утверждение
ТЗ (§2 задача 3) проверить по репозиторию нельзя, а Task 4 переносит его в `configuration.md` как факт
(«is already enabled»), где оно молча протухнет. Деплой с example-конфигов получит INFO и ни одной
строки observability. В tuning-guide нужно писать не «уже включён», а КАК включить: `logging.level` для
двух классов через профильный yaml или `APP_LOG_LEVEL`.

**7. Пример строки в Task 4 расходится с форматом, который сам план запиннил тестом.**
Строка 1401 плана: пример в `configuration.md` не содержит `maxAbsence=` между `stale=` и
`(recording=…)` — при том что следующий же буллет объясняет maxAbsence, а формат зафиксирован в
`TrackerSummaryTest`. Оператор, грепающий по образцу из документации, не совпадёт с реальным логом.

**8. «Three independent changes» в шапке Architecture больше не соответствует плану.**
Task 2 потребляет `ClassAbsence`/`TrackerSummary` из Task 1, Task 3 — `ProductionYamlBinder` из Task 2
(в Interfaces это честно указано). Для последовательного subagent-исполнения нормально, но параллельный
диспатч задач невозможен — стоит сказать это явно, чтобы контроллер не раздал задачи параллельно.

### Suggestions

**S1. Атомарный гейт кулдауна одним `compute`** — закрывает Concern 1, не меняя ни места логики, ни
семантики якоря (max, запись только при отправке):

```kotlin
private fun reappearSuppressedBy(recording: RecordingDto): Duration? {
    if (!cooldown.reappearEnabled) return null
    var suppressed: Duration? = null
    lastReappearNotified.compute(recording.camId) { _, last ->
        val since = last?.let { Duration.between(it, recording.recordTimestamp) }
        if (since != null && since.abs() < cooldown.reappear) {
            suppressed = since
            last                       // подавили — якорь не трогаем
        } else {
            maxOf(last ?: recording.recordTimestamp, recording.recordTimestamp)
        }
    }
    return suppressed
}
```

Все 12 тестов плана проходят без изменений; `rememberReappearNotified` исчезает.

**S2. Заодно починить дрейф `configuration.md`:** строка `DETECTION_FILTER_CLASSES` в доке —
«…backpack,umbrella», в `application.yaml:102` — «…backpack,horse,sheep,cow,bear,elephant,zebra,giraffe».
Файл правится в трёх задачах из четырёх, а всё ТЗ построено на том, что коровы в фильтре есть, — дока
прямо противоречит этому.

**S3. Биндинг-тест на путь env→binder для мусорного списка:** `NOTIFICATIONS_TRACK_REAPPEAR_CLASSES=" , "`
→ падение биндинга. Require протестирован на уровне конструктора, а поведение спрингового
delimited-конвертера (trim элементов, пустые не отбрасываются) — нет; именно этот путь решает, дойдёт ли
`[" "]` до require вообще.

**S4. Уточнить точку вставки Task 4:** план утверждает, что секция «Tuning REAPPEAR_GAP…» «ends with the
three-variable code block», но фактически после код-блока идёт абзац «Per-user toggles…». Вставка
«immediately after the code block» затащит этот абзац под новую секцию «Reading the tracker's debug
line», где ему не место. Вставлять после абзаца Per-user toggles.

**S5. Одной фразой упомянуть в `configuration.md` / `.env.example`, что `sinceLast` в suppress-строке
бывает отрицательным** (`PT-11S` при newest-first дрейне — штатный случай по deviation (b)), иначе
первый же такой лог породит вопрос «что за минус».

### Questions

**Q1.** Сколько consumer-корутин на целевом проде (`getTotalCapacity(FRAME)` по реальному
`application-docker.yaml`)? Если >1 (по example-конфигу — 4), Concern 1 практический, и надо выбрать: S1
или явная KDoc-констатация компромисса.

**Q2.** Судьба остальных staged `docs/superpowers/*` (execution-prompt, watch-records continuation-prompt)
на момент PR: план чистит только сам plan-документ. При исправленном C1 они просто останутся
незакоммиченными — но стоит вписать в Verification явную проверку `git log --stat master..HEAD -- docs/`
перед созданием PR, чтобы правило «docs/superpowers не в PR» проверялось, а не подразумевалось.

**Q3.** Подтвердить у владельца отклонение из Concern 5 (fail-fast на списке из одних пробелов) и внести
его в секцию Deviations.

**Итог fable:** архитектурно план добротный и честно сверен с кодом — сниппеты компилируются, тесты
корректны против реальных фикстур, no-op-гарантия выдержана и запиннена биндинг-тестами. Блокируют
исполнение только git-механика (C1, C2); остальное — осознанные решения, которые надо либо
задокументировать, либо дёшево закрыть (S1).

---

## ext-claude (deepseek/v4-pro)

Источник: `/home/zinin/.claude/plugins/data/claude-mesh-zinin/runs/ext-claude/deepseek/v4-pro/2026-08-03-14-18-36-1838484-design-review-notification-noise-reduction-iter-1/output.txt`
(221 строка). Executor перепроверил каждый пункт по исходникам; его вердикты помечены как **Проверка**.

### Critical Issues

**1. Неоднозначность биндинга `${NOTIFICATIONS_TRACK_REAPPEAR_CLASSES:}` в `List<String>`.**
Рецензент: `${VAR:}` без переменной резолвится в пустую строку. Spring `Binder` для `List<String>` может
дать как `emptyList()`, так и `listOf("")` — это не часть публичного контракта Spring Boot. Если вернётся
`listOf("")`, то в `require(reappearClasses.isEmpty() || reappearClassesNormalized.isNotEmpty())`
(план:592) оба дизъюнкта дадут `false` → исключение в `init` → контейнер падает на старте.

**Проверка:** логика `require` прочитана верно, механика описана корректно. Но severity завышена:
Spring `StringToCollectionConverter` идёт через `StringUtils.commaDelimitedListToStringArray("")`,
который возвращает пустой массив (давнее стабильное поведение); план **уже** содержит тест, который
поймал бы это на билде (план:756 `with nothing set, reappear-classes is empty and every class may
reappear`, через `ProductionYamlBinder`, читающий реальный `application.yaml`). Прямого прецедента
`${VAR:}` → `List<String>` в проекте действительно нет: у `DETECTION_FILTER_CLASSES` непустой дефолт
(`application.yaml:102`), а `CLAUDE_NO_PROXY` — это `String` (`ClaudeProperties.kt:25`).
**Вердикт: понизить до Concern.** Не покрыт только случай *явно выставленной пустой* переменной.

**2. TOCTOU race в кулдауне — нет per-camera синхронизации.**
Трекер сериализует обработку по камере через `perCameraMutex`, но кулдаун-гейт выполняется **после**
выхода из мьютекса. Если T2 читает до записи T1, проходят оба. Ущерб: максимум +1 лишнее уведомление на
камеру в пределах burst'а, что согласуется с fail-open bias. Но кулдаун становится soft guarantee, и в
плане это не задокументировано.
**Проверка: валидно, ссылки точны.** `perCameraMutex` объявлен в `ObjectTrackerServiceImpl.kt:36`, лок
берётся на `:100-101` и отпускается до возврата из `evaluate`.

**3. Смена формата DEBUG-строки трекера ломает операторские grep'ы.**
`reappeared=1` → `reappeared=[person:PT3H12M]`: любой grep вида `reappeared=[1-9]` перестаёт работать.
План документирует новый формат в Task 4, но не помечает это как breaking change.
**Проверка: фактически верно.** Текущий код — `ObjectTrackerServiceImpl.kt:244-247`.

**4. ~~Хрупкий exact-match с trailing-пробелами в `TrackerSummaryTest`~~ — ОТОЗВАН самим рецензентом**
по ходу вывода (строки 86–100 `output.txt`): «Отзываю critical #4 — тест корректен».

### Concerns

**5. `classFiltered` логируется только для absences, уже превысивших `reappearGap`.** Рецензент сам
заключает, что это корректно. Действий не требует.

**6. `ConcurrentHashMap` кулдаун-якорей без верхней границы.** Переименованные/удалённые камеры никогда
не вычищаются. Для single-instance с тремя камерами не проблема, но ограничение стоит упомянуть
комментарием.

**7. `@Validated` на `NotificationCooldownProperties` без JSR-380 аннотаций — no-op, вводит в
заблуждение.** **Проверка: НЕВЕРНО, отбросить.** Сплошная конвенция проекта: `@Validated` стоит на всех
13 классах `@ConfigurationProperties`. Точный прецедент — `DetectionFilterProperties.kt:6-11`:
`@Validated`, ноль аннотаций валидации, поле `List<String> = emptyList()`.

**8. `ProductionYamlBinder` перечитывает production YAML с диска на каждый тест.** При 3–5 тестах
незаметно. Действий сейчас не требует.

**9. Нет теста на `markObserved` + кулдаун** (recording без детекций, путь `NO_DETECTIONS`). Корректно и
так — ветка стоит до кулдаун-гейта; явный тест — дешёвая страховка.

**10. `TrackerSummary` — `internal` visibility и граница модуля.** План использует корректно, но молча.

**11. `--tests '*TrackerSummaryTest*'` в одинарных кавычках «передаст звёздочку литералом».**
**Проверка: НЕВЕРНО, причём наоборот.** Одинарные кавычки не дают шеллу раскрыть glob по именам файлов, и
литеральный паттерн уходит в Gradle, который сам его интерпретирует. Сломалось бы как раз без кавычек.

### Suggestions

**12. Извлечь `CooldownManager` в отдельный класс.** На усмотрение автора; YAGNI-аргумент против валиден.
**13. Добавить `sinceLast` в COOLDOWN-решение** (`NotificationDecision`). Рецензент сам помечает как
вкусовое.
**14. Явно задокументировать, что кулдаун — soft guarantee.** Формулировка: «Two concurrent evaluations
of the same camera may both pass — the cooldown is a best-effort barrier, not a mutex.» **Рекомендую
принять.**
**15. Добавить binding-тест на явно пустую переменную** (`NOTIFICATIONS_TRACK_REAPPEAR_CLASSES=""`).
**Рекомендую принять.**

### Questions

Все три вопроса (Q1 `trimElements`, Q2 явный `PT0S`, Q3 `Clock`) рецензент закрыл самостоятельно.
Открытых вопросов к автору не осталось.

---

## ext-claude (ollama/kimi)

Источник: `/home/zinin/.claude/plugins/data/claude-mesh-zinin/runs/ext-claude/ollama/kimi/2026-08-03-14-18-59-1839021-design-review-notification-noise-reduction-iter-1/output.txt`
(213 строк). Модель `kimi-k2.7-code:cloud`. Executor перепроверил находки; вердикты помечены.

### Critical Issues

**1. `ObjectTrackerServiceImplTest`: лишний аргумент в `coVerify` для `updateOnMatch`.**
Рецензент утверждает, что в плане (строка 437) стоит 8 аргументов при 7-параметровой сигнатуре
`ObjectTrackRepository.updateOnMatch` (`ObjectTrackRepository.kt:41-49`).
**ЛОЖНОЕ СРАБАТЫВАНИЕ (проверено executor'ом и оркестратором).** Фактическая строка 437 плана:
`coVerify(exactly = 1) { repo.updateOnMatch(existing.id!!, any(), any(), any(), any(), any(), recId) }`
— это `id + 5×any() + recId` = ровно 7. «Исправление» рецензента дословно совпадает с уже написанным.

**2. `maxAbsence` может стать отрицательным для out-of-order записей.**
План (строки 291–294) накапливает `maxAbsence = maxAbsence?.coerceAtLeast(absence) ?: absence`, где
`absence = Duration.between(lastSeen, recordingTimestamp)`. Для out-of-order записи `lastSeen >
recordingTimestamp` → `absence` отрицателен. Не гипотеза: `ObjectTrackerServiceImpl.kt:191-193` прямо
документирует «Negative for out-of-order (older) recordings», а существующий тест
`out-of-order older recording never counts as reappeared` (`ObjectTrackerServiceImplTest.kt:362-374`)
покрывает сценарий. Ветка `?: absence` берёт значение безусловно, поэтому в debug-строку уедет
`maxAbsence=PT-3H` — семантически бессмысленно и ломает ровно ту диагностику, ради которой поле
вводится.
**ПОДТВЕРЖДЕНО.** Фикс — одна строка:
```kotlin
if (absence != null && !absence.isNegative) {
    maxAbsence = maxAbsence?.coerceAtLeast(absence) ?: absence
}
```

**3. Пустой дефолт `reappear-classes` в yaml опасен** — то же, что deepseek Critical 1. Executor
дополнительно проверил прецеденты: все существующие `${VAR:}` в `application.yaml` (bot-token:41,
owner:42, proxy host:46, claude-code токены/модели/прокси:85-99) — строковые; единственное
`List<String>`-свойство `DetectionFilterProperties.allowedClasses` биндится с непустым дефолтом.
Прецедента пустого биндинга в `List` в проекте НЕТ. Вывод: binding-тест — обязательный merge-gate.

### Concerns

1. **Read-check-write race в кулдауне** — то же, что fable Concern 1 и deepseek Critical 2. Варианты:
   per-camera `Mutex` или `ConcurrentHashMap.compute`.
2. **Нет теста на anchor при `GLOBAL_OFF` / `OUT_OF_SCHEDULE`.** Поведение корректное (гейты стоят до
   ветки REAPPEARED), но не покрыто — регрессия легко введёт «обновление anchor при подавлении».
3. **`maxAbsence` не попадает в лог для match-only записей** — то же, что fable Concern 4.
4. **План не указывает импорты.** **ЛОЖНАЯ (проверено оркестратором):** `TrackerSummary` и
   `ClassAbsence` лежат в том же пакете `ru.zinin.frigate.analyzer.service.impl`, а `java.time.Duration`
   уже импортирован (`ObjectTrackerServiceImpl.kt:21`).
5. **Нет теста на явно пустую env-переменную** — совпадает с deepseek Suggestion 15.
6. **Абсолютное значение в кулдауне и «два уведомления подряд».** Логика под сомнение не ставится —
   только видимость: оператор увидит два уведомления подряд и решит, что кулдаун сломался.
   Задокументировать в `configuration.md`.
7. **`reappearAllows` нормализует `className` на каждом вызове.** Микро-оптимизация, не блокер.

### Suggestions

1. Исключить отрицательные `absence` из расчёта `maxAbsence` (Critical 2).
2. Правка `coVerify` — НЕ НУЖНА (ложное срабатывание).
3. Если race решено оставить — явная оговорка в KDoc `NotificationDecisionServiceImpl`.
4. В `configuration.md` — строка про видимость `maxAbsence`.
5. Тест на explicit empty string в `ObjectTrackerPropertiesBindingTest`.
6. Атомарный кулдаун через `ConcurrentHashMap.compute` (готовый сниппет `checkAndUpdateCooldown()`).
7. Прописать в плане явные import-шаги (см. ложную Concern 4).

### Questions

1. Сознательно ли принят read-check-write race (fail-open)?
2. `maxAbsence` для out-of-order: игнорировать отрицательные или брать модуль?
3. Проверялось ли на Spring Boot 4.1.0, что `${NOTIFICATIONS_TRACK_REAPPEAR_CLASSES:}` биндится в
   `emptyList()`?
4. Нужен ли явный тест, что `GLOBAL_OFF` / `OUT_OF_SCHEDULE` не сдвигает anchor?
5. `claude-forge:build-runner` в плане vs `/build` в `CLAUDE.md` — разные имена одного агента?

### Примечание по SESSION CONTEXT

Ни одна из уже принятых декизий (пункты 1–7 SESSION CONTEXT) рецензентом не переоткрывалась. Concern 6
про `|sinceLast|` явно признаёт логику корректной и просит только документирования; Suggestion 6 про
`compute` не оспаривает выбор `ConcurrentHashMap`, а предлагает атомарную форму той же структуры.
Требований изменить размещение фильтра классов, инъекцию `Clock` или семантику max-anchor нет.

---

## Проверки оркестратора по репозиторию

Выполнены до классификации, независимо от ревьюеров:

| Утверждение | Источник | Результат |
|---|---|---|
| `coVerify` в плане имеет 7 аргументов, а не 8 | kimi C1 | ✅ подтверждено: план:437 = `id + 5×any() + recId`; `ObjectTrackRepository.kt:42-50` — 7 параметров. Находка ложная |
| `absence` отрицателен для out-of-order | kimi C2 | ✅ подтверждено: `ObjectTrackerServiceImpl.kt:196`, обновление `maxAbsence` по плану встаёт до гарда `:213`; существующий тест использует `lastSeen = fixedNow + 3h` |
| Импорты не нужны | kimi Concern 4 | ✅ находка ложная: тот же пакет; `Duration` импортирован (`:21`) |
| Все commit-шаги плана используют голый `git commit -m` | fable C1 | ✅ подтверждено по тексту плана (строки 373–383, 798–815, 1340–1359, 1428–1431, 1461–1465) при Global Constraints на строках 27–34 |
| `git rm --cached` + `git rm` упадёт | fable C2 | ✅ подтверждено: план закоммичен (`git ls-files` → tracked) |
| Дрейф `configuration.md` по `DETECTION_FILTER_CLASSES` | fable S2 | ✅ подтверждено: дока — `…backpack,umbrella`; `application.yaml:102` — `…backpack,horse,sheep,cow,bear,elephant,zebra,giraffe` |
| `application-docker.yaml` вне репозитория | fable Concern 6 | ✅ подтверждено: `.gitignore:49` |
| После код-блока Tuning идёт абзац Per-user toggles | fable S4 | ✅ подтверждено: `configuration.md:228` |
