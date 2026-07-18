## TASK

Execute the implementation plan for the **notification schedule** feature (global
OWNER-configured daily window that suppresses recording-detection Telegram notifications
outside e.g. 00:00–07:00).

Use `/superpowers:subagent-driven-development` skill for execution (or the
`/claude-mesh:do-plan` wrapper — the user will say which).

## CRITICAL: DO NOT START WORKING

**STOP. READ THIS CAREFULLY.**

After loading all context below, you MUST:
1. Read the documents and understand the context
2. Report what you understood (brief summary)
3. **WAIT for explicit user instructions** before taking ANY action

**DO NOT:** implement tasks, change code, or run commands (beyond reading) until the user
explicitly says to begin.

## DOCUMENTS

- Design (final, review iter 1 applied): `docs/superpowers/specs/2026-07-18-notification-schedule-design.md`
- Plan (final, review iter 1 applied; 10 tasks): `docs/superpowers/plans/2026-07-18-notification-schedule.md`
- Review decision log: `docs/superpowers/specs/2026-07-18-notification-schedule-review-iter-1.md` —
  50 замечаний со статусами и обоснованиями. НЕ читать целиком сразу; открывать точечно,
  когда шаг плана выглядит спорным — скорее всего это сознательное решение ревью.

Read design + plan fully before summarizing.

## PROGRESS

- [x] Brainstorming → spec approved
- [x] Plan written (10 tasks)
- [x] mesh-design-review iteration 1: 7 внешних ревьюеров, 50 уникальных замечаний — все
  разрешены (33 авто-фикса, 1 авто-после-анализа, 6 обсуждено, 10 отклонено). Коммиты:
  `aaf98b8` (авто-фиксы), `ec63805` (merged+parsed), `ecc1e5b` (решения + журнал).
  Пользователь остановил цикл ревью — переход к имплементации.
- [ ] Implementation: NOT started — все 10 задач плана впереди (Task 1 … Task 10)

## SESSION CONTEXT

### Решения brainstorming (вне спеки)

- **Rejected alternatives:** per-user schedules (global OWNER-only chosen); applying the
  schedule to signal-loss alerts (they stay always-on — system health); silent delivery
  (`disable_notification`) and digest of suppressed notifications (dropped — daytime
  detections are pure noise, data stays in DB); free-text window input and a separate
  `/schedule` command (hour picker inside `/notifications` chosen); `answerCallbackQuery`
  toast for `end == start` (impossible — the bot answers every `nfs:` callback immediately
  to clear the spinner; inline warning in the screen text instead).
- **Time basis is `recording.recordTimestamp`** (event time), deliberately NOT "now":
  during backlog catch-up a night detection processed in the morning must still be
  delivered, a daytime detection must never be. Do not "simplify" to `Instant.now()`.
- **`evaluate()` signature intentionally unchanged.** The facade prefetches the global flag
  before `saveProcessingResult` so settings failures keep recordings retryable; the schedule
  is instead read inside `evaluate()` via `getRecordingSchedule()` which NEVER throws
  (fail-open null + warn). Do not "improve" this by prefetching the schedule in the facade
  or letting reads propagate.
- **Fail-open direction is a security decision:** corrupt/unreadable schedule settings must
  produce extra notifications, never lost ones.

### Решения review iter 1, которые НЕЛЬЗЯ «улучшать» при имплементации

- **Task 2 включает негативное кэширование absent-ключей** (`CachedValue`-обёртка в
  `AppSettingsServiceImpl`). Ошибочные чтения репозитория НЕ кэшируются — иначе fail-open
  переживёт сбой БД. Существующий `AppSettingsServiceImplTest` проверен — совместим.
- **Порядок reason нормативен** («первый сработавший гейт»): `NO_DETECTIONS →
  NO_VALID_DETECTIONS → GLOBAL_OFF → OUT_OF_SCHEDULE → NEW_OBJECTS/ALL_REPEATED`. Не
  «уточнять» ветку до `newTracksCount > 0 && !scheduleAllows` — отклонено на ревью.
- **`ScheduleSettingsFlow` (Task 9) сознательно БЕЗ юнит-тестов** (решение против
  mockkStatic-акробатики на extension-функциях ktgbotapi); обязательный ручной чек-лист
  Task 9 Step 5 — гейт мержа. Если waiter ведёт себя неожиданно — STOP, не импровизировать.
- **Строка статуса имеет три состояния + «misconfigured»** (`enabled` при `window == null
  || zone == null`); ON-ветка рендерера требует окно И зону (smart cast).
- **Ручной ввод зоны сознательно шире `/timezone`:** принимает «UTC» (собственный
  materialization-fallback фичи) и offset-зоны. НЕ копировать `contains('/')`.
- **`OUT_OF_SCHEDULE` логируется на debug сознательно** (единая конвенция всех веток
  решения) — не поднимать до INFO, не добавлять метрики/счётчики.

### Git-состояние и конвенции репозитория

- Ветка **`feature/notification-schedule`** (создана и активна; spec/plan/журналы
  закоммичены в ней). Проверить: `git branch --show-current`, `git log --oneline -3`.
- Рабочее дерево содержит **несвязанные незакоммиченные файлы** (staged
  `docs/deep-research-review-report.md`; untracked `.taskmaster/`, старые
  `docs/superpowers/plans/*-prompt.md`, `docs/log-token-sanitization-issue.md`,
  `docs/reset-liquibase-checksums.sh`, `tmp_diff_handler.txt`). НЕ коммитить и НЕ удалять;
  коммитить только явными путями (`git commit -- <paths>`); `git add <file>` сразу после
  каждого создания/изменения (правило проекта).
- Gradle-модули с префиксом `frigate-analyzer-` (`:frigate-analyzer-model` и т.д.).
- Сборки/тесты — только через `build-runner` агент, никогда `./gradlew` напрямую в главной
  сессии. На ktlint-ошибках: `./gradlew ktlintFormat`, повторить.
- Каждая user-visible строка — в ОБА файла `messages_en.properties` /
  `messages_ru.properties` (`MessageKeyParityTest` падает иначе).
- Перед будущим PR: `git rm` всего `docs/superpowers/` и коммит (plan-доки не должны попасть
  в PR-дифф) — это finishing-шаг, не часть execution.

### Предупреждения по задачам

- **Task 1** добавляет тест-инфраструктуру в `model`-модуль (её там не было); root build
  уже применяет `useJUnitPlatform()` глобально.
- **Task 4:** литералы `DetectionDelta(...)` в тестах теперь точные — порядок полей
  `(newTracksCount, matchedTracksCount, staleTracksCount, newClasses)` сверен с
  `DetectionDelta.kt`; референс — существующий тест `NotificationDecisionServiceImplTest.kt:81`;
  при дрейфе файла копировать литерал оттуда.
- **Task 5:** перед удалением `appSettings` из `FrigateAnalyzerBot` — grep, что единственные
  использования — два `getBoolean` в RERENDER-блоке.
- **Task 6:** помимо переписанного row-count теста, конструкции с `isOwner = true` в
  renderer-тестах — строки 128/146 (сверено 2026-07-18); при дрейфе — искать `isOwner = true`
  по файлу. Индексы рядов owner-клавиатуры: [0] rec user, [1] sig user, [2] rec global,
  [3] sig global, [4] sched toggle, [5] window+zone, [6] close.
- **Task 7:** рефакторинг `TimezoneCommandHandler` на общий `TimezonePresets.CITIES` обязан
  сохранить сегодняшние labels/callbacks/раскладку 4×2 в точности (поведение и тесты
  `/timezone` не меняются).
- **Task 9:** waiter (`waitTextMessage`) впервые используется из callback-контекста; паттерн
  из `TimezoneCommandHandler` (120 с таймаут, `/cancel`). Ручная проверка Step 5 —
  REQUIRED before merge.

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

Do NOT silently work around plan issues or make significant deviations without user
approval. Перед «исправлением» странно выглядящего места — свериться с iter-1 журналом:
возможно, это сознательное решение ревью.

## INSTRUCTIONS

1. Прочитать design и plan (целиком); iter-1 журнал — точечно при вопросах.
2. Кратко изложить понимание: цель фичи, состав 10 задач, статус (имплементация не начата).
3. **STOP и ждать** явной команды пользователя.
4. Спросить: «С чего начать?» (ожидаемо: Task 1 через
   `/superpowers:subagent-driven-development` либо `/claude-mesh:do-plan`).
