# Continuation: mesh-design-review iter 1 — disputed phase (notification-schedule)

## TASK

Продолжить **итерацию 1 mesh-design-review** для фичи notification-schedule: провести фазу
обсуждения спорных замечаний (Step 12 skill'а `claude-mesh:mesh-design-review`), затем
сгенерировать iter-файл, закоммитить и предложить следующие шаги (Steps 13–15).

Имплементация фичи НЕ начата и НЕ является задачей этой сессии. Ревью-агенты больше не
нужны — все отчёты собраны и разобраны.

## CRITICAL: DO NOT START WORKING

**STOP. READ THIS CAREFULLY.**

After loading all context below, you MUST:
1. Read the documents and understand the context
2. Report what you understood (brief summary)
3. **WAIT for explicit user instructions** before taking ANY action

**DO NOT:** start discussing disputed issues, edit documents, or run commands (beyond reading)
until the user explicitly says to begin.

## DOCUMENTS

- Design (обновлён авто-фиксами, aaf98b8): `docs/superpowers/specs/2026-07-18-notification-schedule-design.md`
- Plan (обновлён авто-фиксами, aaf98b8): `docs/superpowers/plans/2026-07-18-notification-schedule.md`
- Merged review, 7 ревьюеров: `docs/superpowers/specs/2026-07-18-notification-schedule-review-merged-iter-1.md`
- **Parsed issues (50 шт., дедуплицировано, с вариантами решений): `docs/superpowers/specs/2026-07-18-notification-schedule-review-parsed-iter-1.md` — прочитать ЦЕЛИКОМ, это источник истины для обсуждения**

Design и plan прочитать для контекста (спорные решения станут правками именно в них).
Merged читать не обязательно (parsed его замещает; открывать точечно при спорах об источнике).

## PROGRESS

Выполнено (итерация 1):
- [x] Step 5–6: ревью 7 агентами (preset `defaults.design_review`): claude-self (Fable), codex (gpt-5.6-sol, reasoning max), zai/glm (GLM 5.2), alibaba/qwen (Qwen 3.7 Plus), deepseek/v4-pro, ollama/kimi (K2.7), ollama/minimax (M3)
- [x] Step 7: merged-файл собран
- [x] Step 8: `claude-mesh:review-discussion` → 50 уникальных замечаний (из ~95), все NEW
- [x] Step 9: классификация — 33 AUTO / 7 DISPUTED (6 обсуждений) / 10 DISMISSED / 0 REPEAT
- [x] Step 10–11: все 33 авто-фикса применены к design+plan, промежуточный коммит **aaf98b8** «docs: review iter 1 — auto-fixes (notification-schedule)» (+282/−71)

Осталось:
- [ ] Step 12: обсудить 6 спорных вопросов ПО ОДНОМУ (очередь ниже)
- [ ] Step 13: сгенерировать `docs/superpowers/specs/2026-07-18-notification-schedule-review-iter-1.md` — ВСЕ 50 замечаний со статусами/ответами/действиями (формат в skill'е; дата в имени — из design-файла, НЕ текущая)
- [ ] Step 14: закоммитить правки документов + iter-файл: `docs: review iter 1 — decisions + log (notification-schedule)` (merged и parsed уже закоммичены отдельным коммитом)
- [ ] Step 15: AskUserQuestion «Итерация 1 завершена… Что дальше?» → «Новая итерация (fresh session)» / «Остановиться и начать работу (fresh session)» → сгенерировать соответствующий fresh-session prompt через `/claude-mesh:continue-plan-fresh-session`

## IRON RULES ФАЗЫ ОБСУЖДЕНИЯ (Step 12)

1. Спорные вопросы СТРОГО ПО ОДНОМУ, каждый в отдельном сообщении. Никаких пакетных списков.
2. Каждый — структурированный анализ: **Суть → Анализ → Варианты (каждый с плюсами/минусами) → Рекомендация** (одна, обоснованная). Не одно-строчные буллеты.
3. Если после анализа адекватен только ОДИН вариант — НЕ спрашивать: объявить решение, применить правку в том же сообщении, перейти к следующему.
4. Если выбор реален — анализ является ФИНАЛЬНЫМ сообщением хода (никаких tool-вызовов после него), завершается «Выберите вариант… Моя рекомендация — X». Ответ — свободным текстом. **НИКОГДА не использовать AskUserQuestion для спорных** (модалка проглатывает анализ).
5. После ответа: применить правки к design И/ИЛИ plan — проверять ОБА документа (design = WHAT, plan = HOW; большинство решений требуют правок в обоих), затем следующий вопрос.
6. «стоп»/«stop»/«достаточно» в ответе → текущий и остальные спорные помечаются deferred (`отложено (стоп)`), переход к Step 13.
7. Каждое решение фиксировать для iter-файла: {issue, status, answer, action}.

## ОЧЕРЕДЬ СПОРНЫХ (6 обсуждений; полные тексты — в parsed-файле)

Рекомендации выработаны в прошлой сессии; пользователь ещё НЕ отвечал ни на один вопрос.
Вопрос 1 уже был презентован — ответа не последовало, начать с него заново.

**1. CRITICAL-1 + CONCERN-5 (связка; затрагивает и CONCERN-14)** — отсутствующие ключи не
кэшируются: SELECT под общим `cacheMutex` на каждую запись, пока расписание не настроено;
плюс повторный парсинг window/zone на каждой записи и warn-спам при битых значениях.
Варианты: **A** негативное кэширование (сентинел) в `AppSettingsServiceImpl`; **B** сидинг
`enabled='false'` миграцией; **C** кэш собранного `NotificationSchedule?` в
`NotificationScheduleServiceImpl` с инвалидацией в сеттерах.
Рекомендация: **C** — закрывает CRITICAL-1 + CONCERN-5 + CONCERN-14 одним механизмом и не
трогает общий сервис в фиче-ветке. КРИТИЧЕСКИЙ нюанс реализации: ошибочные чтения
(exception → fail-open null) НЕ кэшировать — иначе после восстановления БД расписание
«залипнет» выключенным до рестарта; кэшировать только успешные чтения (включая
«absent → disabled»). Вариант A зафиксировать как платформенный follow-up вне ветки.
Правки при выборе C: план Task 3 (impl + тесты кэша/инвалидации/не-кэширования ошибок),
спека Model & Services + edge case 5 (гонка схлопывается).

**2. CRITICAL-4** — приоритет reason: план вставляет `OUT_OF_SCHEDULE` между `GLOBAL_OFF` и
`NEW_OBJECTS` → вне окна с только-повторами reason=`OUT_OF_SCHEDULE` (не `ALL_REPEATED`);
пустая delta даёт `NO_VALID_DETECTIONS` раньше расписания. На `shouldNotify` не влияет —
семантика для логов/метрик. Варианты: **A** зафиксировать текущий порядок как нормативный
(precedence-строка в спеку + 2 теста приоритета в Task 4); **B** сузить ветку до
`delta.newTracksCount > 0 && !scheduleAllows`.
Рекомендация: **A** — «первый сработавший гейт» консистентен с тем, как `GLOBAL_OFF` уже
сегодня перекрывает `ALL_REPEATED`; правка только доки+тесты.

**3. CONCERN-3** — `ScheduleSettingsFlow` (Task 9) без тестов; подняли 6/7 ревьюеров.
Варианты: **A** unit-тесты с mock `BehaviourContext` (риск: extension-функции ktgbotapi
мокаются плохо, возможна mockkStatic-акробатика, тест мока); **B** принять осознанно —
ручной чек-лист УЖЕ добавлен в план (Task 9 Step 5, авто-фикс CONCERN-4); **C** выделить
чистую функцию маршрутизации и тестировать только её.
Рекомендация: **B** (чек-лист уже в плане); если пользователь хочет тесты — C дешевле A.

**4. CONCERN-2** — UI при `enabled=true` и битой конфигурации: строка «OFF»/«ON (?)» и
кнопка «Disable» противоречат друг другу. После авто-фикса CRITICAL-2 состояние достижимо
только внешней порчей БД. Варианты: **A** третье состояние строки «misconfigured»
(i18n-ключ ×2 + ветка рендера + тест); **B** sealed `ScheduleStatus` в сервисе; **C**
принять как есть (fail-open уже warn'ит).
Рекомендация: **A** — дёшево и честно; B оверкилл.

**5. CONCERN-10** — ручной ввод зоны принимает offset-ID и «UTC», `/timezone` требует
`Continent/City`. Функционально offset безвреден (окно фиксировано от UTC — нет
DST-сюрпризов). Варианты: **A** принять расширенную валидацию (зафиксировать в спеке;
позже ослабить `/timezone`); **B** скопировать `contains('/')` (консистентность, но
отвергает валидный «UTC»).
Рекомендация: **A**.

**6. SUGGESTION-5** — наблюдаемость: подавление `OUT_OF_SCHEDULE` видно только на debug.
Варианты: **A** INFO-лог первого подавления после включения (нужен state-флажок в
decision-сервисе); **B** счётчик/метрика; **C** оставить debug-only.
Рекомендация: **C** (владелец сам включил; INFO-на-каждое — шум; state ради строки — не
стоит) — вкусовое, потому и спорное.

## DISMISSED — 10 позиций (обоснования для iter-файла)

- CONCERN-15 (фабрика отдельным PR) — отклонено: остаётся в ветке, покрыта тестами Task 5; отдельный PR — процессный оверхед для solo.
- CONCERN-22 (`/notifications` падает при сбое БД) — поведение фичей не меняется (нынешний handler уже читает без try/catch); громкая ошибка диагностична.
- CONCERN-23 (auto-enable нельзя «настроить выключенным») — уже зафиксировано в спеке; выключение — один клик.
- CONCERN-24 (handler зависит от TelegramUserService) — материализация зоны остаётся в handler'е: бизнес-правило, вынос в flow спрятал бы его от unit-тестов.
- CONCERN-25 (`CancellationException`) — false positive, опровергнуто построчно (см. parsed).
- SUGGESTION-6 (helper editMessageText) — rule of three: два места, преждевременно.
- SUGGESTION-7 (вынос `"nfs:"`) — вне scope, возможный follow-up.
- SUGGESTION-13 (группа из 11 мелких) — отклонена группой; обоснования по буквам (а)–(л) в parsed-файле, перенести в iter-файл.
- QUESTION-2 (UTC-fallback) — уже отражено в спеке; зона видна в статусе, меняется в 2 клика.
- QUESTION-5 (message-key в тест-ассертах) — конвенция проекта: renderer-тесты ассертят рендеренный текст en-локали.

## AUTO-ПРИМЕНЁННЫЕ — 33 позиции (коммит aaf98b8; для iter-файла)

Полный маппинг issue → правка: `git show aaf98b8`. Сжато: CRITICAL-2 (материализация зоны в
ветке `on` + тест), CRITICAL-3 (STRICT-парсер + тесты 24:00/00:60), CRITICAL-5 (инвариант
порядка записи в спеке и коде плана), CRITICAL-6 (спека → one-shot), CONCERN-1
(`init require(start != end)` + тест), CONCERN-4 (ручной чек-лист waiter'а — Task 9 Step 5),
CONCERN-6 (отклонения задекларированы в спеке), CONCERN-7 (инвариант маршрутизации +
guard-тест IGNORE), CONCERN-8 (комментарий асимметрии в evaluate()), CONCERN-9 (подавление
для всех — в спеку), CONCERN-11 (stale `e:` re-enables — в спеку), CONCERN-12+SUGGESTION-2
(OFF-строка с окном; off-ключи форматные с подстановкой `state.off`), CONCERN-13 (setString:
значение на debug, warn при rows==0; то же в setBoolean), CONCERN-14 (гонка — в спеку),
CONCERN-16 (строки 128/146 renderer-тестов), CONCERN-17 (общий `TimezonePresets.CITIES` +
рефакторинг TimezoneCommandHandler), CONCERN-18 (ops: single-instance, SQL→рестарт,
rollback), CONCERN-19 (branch-wide review перед build), CONCERN-20 (KDoc @throws),
CONCERN-21 (ретроактивность — edge case 8), SUGGESTION-1 (`DetectionDelta(1, 0, 0,
listOf("car"))`), SUGGESTION-3 (текст таймаута), SUGGESTION-4 (`"%02d".format` + контракт),
SUGGESTION-8 (database.md в Task 10), SUGGESTION-9 (`getUserZone` в спеке), SUGGESTION-10
(семантика в rules — Task 10), SUGGESTION-11 (тесты midnight + home-не-пишет), SUGGESTION-12
(Out of Scope: cron, prefetch-отказ, presets-не-whitelist), QUESTION-1 (TRACKER_ERROR вне
окна — намеренно), QUESTION-3 (без ConditionalOnProperty — в спеку), QUESTION-4 (warn при
chatId==null), QUESTION-6 (DST-уточнение).

## SESSION CONTEXT (неявные знания прошлой сессии)

- Инфраструктурная кухня ревью (для истории, действий не требует): все 4 первых ext-claude
  прогона умерли от обрывов стрима провайдеров; рестарт сессии убил 3 supervised-ретрая
  (SIGTERM 143); итоговые отчёты получены со 2–3 попыток (glm — через `--resume`).
- Рабочее дерево содержит НЕсвязанные незакоммиченные файлы (staged
  `docs/deep-research-review-report.md`; untracked `.taskmaster/`,
  `docs/log-token-sanitization-issue.md`, `docs/reset-liquibase-checksums.sh`,
  `tmp_diff_handler.txt`, старые `docs/superpowers/plans/*-prompt.md`) — НЕ коммитить и НЕ
  удалять; коммитить ТОЛЬКО явными путями: `git commit -m "..." -- <paths>`.
- Ветка `feature/notification-schedule`. Коммиты итерации: aaf98b8 (auto-fixes) и следующий
  за ним (merged + parsed + этот промпт). Проверить: `git log --oneline -3`.
- Дата в именах файлов итерации — **2026-07-18** (из имени design-файла), НЕ текущая.
- Правило проекта: `git add <file>` сразу после создания/изменения каждого файла.
- Ожидаемые правки по рекомендациям (если приняты): №1→C: план Task 3 + спека Model &
  Services; №2→A: спека Decision Enforcement (precedence) + план Task 4 (2 теста); №4→A:
  план Task 6 (ключи misconfigured ×2 языка, ветка when в renderText, тест) + спека Main
  screen/Edge case 1; №5→A: спека Key UI decisions (одно предложение); №3→B и №6→C: правок
  нет (зафиксировать решение в iter-файле).
- В iter-файле статистика по формату skill'а: авто 33, dismissed 10, повторов 0, спорных по
  фактическим итогам обсуждения; агенты — перечислить всех 7.

## PLAN QUALITY WARNING

Классификация и рекомендации могли содержать ошибки. Если при обсуждении или применении
правки обнаружится, что вариант противоречит коду/спеке или рекомендация опирается на
неверный факт — STOP, описать проблему пользователю, не работать молча в обход.

## INSTRUCTIONS

1. Прочитать design, plan (обзорно) и parsed-файл (целиком, внимательно).
2. Кратко изложить понимание: статус итерации, очередь из 6 спорных.
3. **STOP и ждать** явной команды пользователя начать обсуждение.
4. По команде — Step 12 по Iron Rules, начиная со Спорного 1/6 (CRITICAL-1 + CONCERN-5).
5. После всех спорных: Step 13 (iter-файл) → Step 14 (коммит) → Step 15 (AskUserQuestion
   про следующую итерацию / переход к имплементации).
