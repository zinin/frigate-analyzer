# Review Iteration 1 — 2026-07-18 18:00

## Источник

- Design: `docs/superpowers/specs/2026-07-18-notification-schedule-design.md`
- Plan: `docs/superpowers/plans/2026-07-18-notification-schedule.md`
- Review agents: claude-self (Fable), codex (gpt-5.6-sol, reasoning max), zai/glm (GLM 5.2), alibaba/qwen (Qwen 3.7 Plus), deepseek/v4-pro, ollama/kimi (K2.7), ollama/minimax (M3)
- Merged output: `docs/superpowers/specs/2026-07-18-notification-schedule-review-merged-iter-1.md`
- Parsed issues: `docs/superpowers/specs/2026-07-18-notification-schedule-review-parsed-iter-1.md` (50 уникальных из ~95 сырых)
- Коммиты итерации: `aaf98b8` (авто-фиксы), текущий (решения + журнал)

## Замечания

### [CRITICAL-1] Отсутствующие ключи настроек не кэшируются — DB-запрос на каждую запись в дефолтном состоянии

> `AppSettingsServiceImpl.loadAndCache` не кэширует отрицательные результаты: для отсутствующего ключа каждый вызов идёт через общий `cacheMutex` + `repository.findBySettingKey`. Пока расписание не настроено (вечный дефолт большинства инсталляций), каждый `evaluate()` выполняет SELECT под глобальным мьютексом; обоснование дизайна «reads go through the settings cache» опирается на неверный факт.

**Источник:** claude (C1, S1, Q2), codex (Critical 1)
**Статус:** Обсуждено с пользователем (Спорное 1/6, в связке с CONCERN-5)
**Ответ:** Вариант A — негативное кэширование в `AppSettingsServiceImpl`, в этой же ветке. Кэш собранного расписания (вариант C) отклонён пользователем в пользу починки дефекта у его источника; сидинг миграцией (вариант B) отклонён как противоречащий решению «absent = disabled, no seeding». Критическое требование: ошибочные чтения репозитория НЕ кэшируются — fail-open не должен переживать сбой.
**Действие:** План Task 2 переименован в «… + negative caching of absent keys», добавлена поведенческая гарантия для Tasks 3–4, два теста (`absent key is negatively cached until invalidated`, `setString clears the negative cache entry`), Step 4 переписан (`CachedValue`-обёртка, общий `getRaw`, `loadAndCache` кэширует absence). Спека: Storage (механизм и правила инвалидации), Decision Enforcement («absent keys included»). Ops-строка Task 10: «values and key absence are cached».

---

### [CRITICAL-2] Ветка `on` не материализует зону — спека требует «on first enable/save»

> В плане материализация зоны была только в ветке `e:<S>:<E>`; ветка `on` при существующем окне делала только `setEnabled(true)` — достижимо состояние `enabled + window + zone=null`. Подняли 5 из 7 ревьюеров.

**Источник:** claude (C2), codex (Critical 3), zai/glm (Critical 2, Q19), alibaba/qwen (Critical 1 частично), ollama/kimi (Critical 1, Suggestion 2, Q3)
**Статус:** Автоисправлено
**Ответ:** Материализация зоны продублирована в ветке `on` (двухстрочный фикс, соответствие спеке).
**Действие:** План Task 8 — блок `if (getZone() == null) setZone(getUserZone(...))` перед `setEnabled(true)` в ветке `on` + тест `on with window but missing zone materializes zone before enabling`; спека Key UI decisions: «BOTH the `on` action and the window save». (aaf98b8)

---

### [CRITICAL-3] Парсер окна принимает «24:00» — повреждённое значение становится активным окном вместо fail-open

> `ScheduleWindow.parse` использовал `DateTimeFormatter.ofPattern("HH:mm")` с дефолтным `ResolverStyle.SMART`: проверено в jshell, `"24:00"` → `00:00`. Битое значение из БД молча превращалось бы в активное окно вместо отключения расписания.

**Источник:** codex (Critical 2), zai/glm (Suggestion 13), alibaba/qwen (Suggestion 9 смежное)
**Статус:** Автоисправлено
**Ответ:** `withResolverStyle(ResolverStyle.STRICT)` + тесты на отклонение `24:00` и `00:60`.
**Действие:** План Task 1 — STRICT-форматтер в реализации, тесты `parse rejects hour 24`, `parse rejects invalid minutes`. (aaf98b8)

---

### [CRITICAL-4] Приоритет `OUT_OF_SCHEDULE` относительно `ALL_REPEATED`/`NO_VALID_DETECTIONS` не зафиксирован

> План вставляет `!scheduleAllows` между `!resolvedGlobalEnabled` и `delta.newTracksCount > 0`: запись вне окна с только-повторами получит `OUT_OF_SCHEDULE`, а не `ALL_REPEATED`; пустая delta даст `NO_VALID_DETECTIONS` раньше расписания. На `shouldNotify` не влияет — семантика reason для логов; порядок нигде не оговорён.

**Источник:** zai/glm (Critical 1, Q18), alibaba/qwen (Concern 3), ollama/kimi (Concern, Q4), ollama/minimax (Critical 1 частично)
**Статус:** Обсуждено с пользователем (Спорное 2/6)
**Ответ:** Вариант A — текущий порядок зафиксирован как нормативный: семантика «первый сработавший гейт» консистентна с тем, как `GLOBAL_OFF` уже сегодня перекрывает `ALL_REPEATED`. Сужение ветки (вариант B) отклонено: породило бы две разные семантики reason в одной цепочке ради метрик, которых нет.
**Действие:** Спека Decision Enforcement — нормативная precedence-строка `NO_DETECTIONS → NO_VALID_DETECTIONS → GLOBAL_OFF → OUT_OF_SCHEDULE → NEW_OBJECTS/ALL_REPEATED` с принятыми следствиями. План Task 4 — два теста приоритета (`OUT_OF_SCHEDULE wins over ALL_REPEATED`, `NO_VALID_DETECTIONS wins over OUT_OF_SCHEDULE`), note о литералах `DetectionDelta`, KDoc-пункт 5 («reason = first tripped gate»).

---

### [CRITICAL-5] Сохранение окна не атомарно; инвариант порядка записи нигде не зафиксирован

> Ветка `e:` делает три отдельных upsert'а (`setWindow → setZone → setEnabled`); сбой между ними оставляет частичное состояние. Порядок «enabled — последним» корректен, но не назван инвариантом — будущая перестановка откроет окно «enabled без window/zone».

**Источник:** codex (Critical 4, Suggestion 1), ollama/kimi (Critical 2), claude (N5)
**Статус:** Автоисправлено
**Ответ:** Last-write-wins принят; порядок записи объявлен инвариантом (частичные состояния прикрыты fail-open, один владелец).
**Действие:** Спека Storage — «window → zone → enabled LAST … The write order is an invariant»; план Task 8 — комментарий «Write-order invariant» у ветки `e:`. (aaf98b8)

---

### [CRITICAL-6] Спека требовала re-await при невалидном ручном вводе зоны, план и референсный `/timezone` — one-shot

> Спека (edge case 6): «error message + re-await». Но `/timezone` (проверено `TimezoneCommandHandler.kt:135-137`) при `DateTimeException` шлёт ошибку и выходит. Спека внутренне противоречива — её референсный паттерн не делает re-await.

**Источник:** claude (N1, Q3), codex (Critical 5)
**Статус:** Автоисправлено
**Ответ:** Спека исправлена на one-shot — консистентно с фактическим `/timezone` и планом.
**Действие:** Спека edge case 6: «error message, then the flow exits (one-shot — matches the actual `/timezone` behavior)». (aaf98b8)

---

### [CONCERN-1] Инвариант `start != end` не защищён в типе `ScheduleWindow`

> Его отвергал только `parse`; публичный конструктор data class и `ofHours(5, 5)` создавали вырожденное окно с «всегда true» `contains`.

**Источник:** claude (N6), codex (Concern 1, Suggestion 3), zai/glm (Concern 11), deepseek (Critical 2, Suggestion 10), ollama/minimax (Suggestion 17 смежное)
**Статус:** Автоисправлено
**Ответ:** `init { require(start != end) }` в data class — закрывает конструктор и `ofHours` разом.
**Действие:** План Task 1 — `init`-блок в реализации + тест `equal start and end is rejected at construction`; KDoc типа фиксирует определение валидного окна. (aaf98b8)

---

### [CONCERN-2] UI при `enabled=true` и битой конфигурации: строка статуса и кнопка-тумблер противоречат друг другу

> Строка ведётся от `scheduleEnabled && scheduleWindow != null`, кнопка — от сырого `scheduleEnabled`. При `enabled=true, window=null`: «OFF» + кнопка «Disable»; при `enabled=true, zone=null`: «ON (?)» при неработающем расписании. После фикса CRITICAL-2 состояние достижимо только внешней порчей БД.

**Источник:** claude (Q4), codex (Q4, Suggestion 2), ollama/kimi (Critical 4, Suggestion 1), alibaba/qwen (Concern 8), ollama/minimax (Q2 смежное)
**Статус:** Обсуждено с пользователем (Спорное 4/6)
**Ответ:** Вариант A — третье состояние строки «misconfigured»: UI перестаёт врать в единственном состоянии, где это критично для диагностики (fail-open доставляет уведомления при видимом «ON»). Sealed `ScheduleStatus` (B) — оверкилл ради одного потребителя; «принять как есть» (C) — слепое пятно ровно там, где владелец ищет ответ.
**Действие:** План Task 6 — ключ `notifications.settings.sched.line.misconfigured` (en/ru), первая ветка `when` (`enabled && (window == null || zone == null)`), ужесточение ON-ветки до «окно И зона заданы», два renderer-теста. Спека Main screen (третье состояние) + edge case 1 (UI-отражение fail-open).

---

### [CONCERN-3] `ScheduleSettingsFlow` (Task 9) без единого теста — самый сложный клей фичи

> План декларирует «no new unit tests», при этом в flow — `manualZoneInput` (120-с таймаут, `/cancel`, invalid-ветка), маршрутизация 7 `Outcome`, обработка «message is not modified». Подняли 6 из 7 ревьюеров.

**Источник:** claude (N7), codex (Concern 3), zai/glm (Concern 6, Q23), alibaba/qwen (Concern 4), deepseek (Critical 1, Suggestion 11), ollama/kimi (Concern, Suggestion 5)
**Статус:** Обсуждено с пользователем (Спорное 3/6)
**Ответ:** Вариант B — принято осознанно. Решающая логика уже покрыта юнитами нижних слоёв (dispatch — Task 8, экраны — Task 7, factory — Task 5); реальные риски flow — runtime-семантика ktgbotapi (waiter в callback-контексте, конкурентность, рестарт), которую моки принципиально не ловят — закрывается обязательным ручным чек-листом (Task 9 Step 5, авто-фикс CONCERN-4). Мок `BehaviourContext` (A) — mockkStatic-акробатика на extension-функциях, хрупко, «тест мока»; чистая функция маршрутизации (C) — тавтологичный тест при 1:1-маппинге.
**Действие:** — (чек-лист уже в плане; решение зафиксировано здесь).

---

### [CONCERN-4] 120-с waiter внутри `onDataCallbackQuery`: конкурентность не подтверждена, рестарт не переживает

> Если ktgbotapi обрабатывает callback'и последовательно per-marker — клики встанут в очередь за waiter'ом; если параллельно — двойной `zman` породит конкурирующие waiter'ы. Waiter не переживает рестарт бота; ограничение «один чат — один waiter» нигде не зафиксировано.

**Источник:** claude (N3, Q1), ollama/minimax (Critical 3, Concern 11, Q5)
**Статус:** Автоисправлено
**Ответ:** Обязательная ручная проверка обоих сценариев на живом боте до мержа + документирование ограничений waiter-паттерна.
**Действие:** План Task 9 Step 5 — чек-лист из 6 сценариев (REQUIRED before merge, STOP при фейле), включая конкурентные клики и двойной `zman`; ограничения waiter'а — в ops-заметку Task 10. (aaf98b8)

---

### [CONCERN-5] Битые window/zone парсятся и warn-логируются на каждой записи — log storm

> `getRecordingSchedule()` вызывается из каждого `evaluate()`; при повреждённом значении warn'ы пишутся на каждую запись. Парсинг window/zone повторяется на каждой записи и в здоровом состоянии.

**Источник:** codex (Concern 2), ollama/kimi (Concern, Suggestion 4)
**Статус:** Обсуждено с пользователем (Спорное 1/6, в связке с CRITICAL-1)
**Ответ:** С выбором негативного кэширования (вариант A по CRITICAL-1) остаток принят осознанно: повторный парсинг пренебрежим, per-record warn на битом значении — сознательно громкий сигнал аномалии, а не дефект логирования.
**Действие:** Спека Out of Scope — отказ от кэша собранного `NotificationSchedule` с обоснованием остатка.

---

### [CONCERN-6] Отклонения плана от дизайна не задекларированы (self-review: «deviations: none»)

> Проверено: (а) клавиатура 5 → 7 рядов вместо «One extra keyboard row»; (б) `nfs:g:sched:*` перехватывается в боте, а дизайн поручал диспатч handler'у. Self-review плана расхождений не фиксировал.

**Источник:** codex (Concern 4)
**Статус:** Автоисправлено
**Ответ:** Оба отклонения зафиксированы в спеке (план обоснован: 3 кнопки в ряд узки; wiring через бот проще).
**Действие:** Спека Main screen (7 рядов) и Component changes (перехват в `FrigateAnalyzerBot` с routing-инвариантом); план self-review обновлён. (aaf98b8)

---

### [CONCERN-7] Маршрутизация `nfs:g:sched:*` держится исключительно на порядке проверок в боте

> При изменении порядка callback утечёт в старый `NotificationsSettingsCallbackHandler.dispatch()` → unknown scope → `IGNORE` — молчаливое проглатывание.

**Источник:** deepseek (Concern 7)
**Статус:** Автоисправлено
**Ответ:** Комментарий-инвариант в боте + защитный тест.
**Действие:** План Task 9 Step 3 — комментарий «Routing invariant: … MUST be intercepted BEFORE …» + Step 3 п.4: guard-тест `dispatch("nfs:g:sched:on") == IGNORE` в `NotificationsSettingsCallbackHandlerTest`. (aaf98b8)

---

### [CONCERN-8] Fail-open асимметрия расписания и глобального флага — принята, но тиха

> Один класс ошибок (settings unreadable) даёт разные направления: флаг префетчится → сбой заметен, запись retryable; расписание fail-open внутри `evaluate()` → сбой тих. Будущий разработчик может «выровнять» семантики.

**Источник:** zai/glm (Concern 9), ollama/kimi (Concern, Q1)
**Статус:** Автоисправлено
**Ответ:** Асимметрия зафиксирована ближе к коду (комментарий в `evaluate()`) и в rules.
**Действие:** План Task 4 — комментарий «Deliberate asymmetry with the global flag…»; Task 10 Consumers-абзац включает асимметрию. (aaf98b8)

---

### [CONCERN-9] Non-owner не видит причину пропажи дневных уведомлений

> Расписание OWNER-only, но решение в `evaluate()` до per-user фильтра — подавляет всем; non-owner не увидит почему.

**Источник:** zai/glm (Concern 10, Q21)
**Статус:** Автоисправлено
**Ответ:** Задокументировано как принятое (single-owner household system).
**Действие:** Спека Agreed Requirements — абзац «suppresses … for ALL users, while only the OWNER sees or controls it — accepted»; rules (Task 10). (aaf98b8)

---

### [CONCERN-10] Валидация ручного ввода зоны мягче, чем в `/timezone` (offset-зоны принимаются)

> `/timezone` требует `contains('/')` (отвергает «UTC» и offset'ы), новый флоу принимает всё валидное для `ZoneId.of`. Два диалога «одного паттерна» валидируют по-разному; функциональной проблемы нет.

**Источник:** claude (N8), codex (Q2), deepseek (Concern 4, Q13), ollama/kimi (Critical 3, Q6)
**Статус:** Авто-применено после анализа (Спорное 5/6)
**Ответ:** Вариант A — расширенная валидация сознательна. Дисквалифицирующий довод против копирования `contains('/')`: «UTC» — собственный материализационный fallback фичи; диалог, отвергающий значение, которое система сама штатно записывает, внутренне противоречив. Offset-зоны для фиксированного окна даже DST-стабильны.
**Действие:** Спека Key UI decisions (Zone screen) — валидация сознательно шире `/timezone`; ослабление `/timezone` тем же способом — возможный follow-up вне ветки.

---

### [CONCERN-11] Stale end-picker + auto-enable нарушает заявленное «no toggle surprises»

> `nfs:g:sched:e:<S>:<E>` на устаревшей клавиатуре не только перезапишет окно, но и включит расписание, которое владелец успел явно выключить.

**Источник:** claude (N2)
**Статус:** Автоисправлено
**Ответ:** Принято и записано в спеку (вероятность мала, single owner).
**Действие:** Спека edge case 3 — исключение задокументировано явно. (aaf98b8)

---

### [CONCERN-12] При выключенном расписании UI скрывает настроенное окно

> Выключенное расписание — голое «OFF»; владелец не видит, какое окно активирует кнопка «Enable».

**Источник:** claude (N4)
**Статус:** Автоисправлено
**Ответ:** Третий вариант строки «OFF (окно, зона)» при сохранённом окне.
**Действие:** Спека Main screen; план Task 6 — ключ `sched.line.off.configured.format` + тест `owner text shows off with stored window when disabled`. Совместно с SUGGESTION-2. (aaf98b8)

---

### [CONCERN-13] `setString`: значение в INFO-логе + игнорирование результата `upsert`

> (а) INFO-лог со значением — будущий секретный ключ утечёт; (б) возврат `upsert` игнорируется — при `0L` тихий «успех».

**Источник:** claude (S7), zai/glm (Concern 3, Concern 4, Q22)
**Статус:** Автоисправлено
**Ответ:** Обе правки: значение только на debug, warn при `rows == 0L`; то же в `setBoolean` для консистентности.
**Действие:** План Task 2 Step 4 (в переписанном виде сохранено). (aaf98b8)

---

### [CONCERN-14] Три независимых чтения в `getRecordingSchedule()` — окно гонки шире одного флага

> `isEnabled()` + `getWindow()` + `getZone()` не атомарны между собой: при конкурентной записи можно прочитать смесь. Все варианты дают либо валидное окно, либо fail-open.

**Источник:** zai/glm (Concern 5), deepseek (Concern 8), alibaba/qwen (Concern 6), ollama/minimax (Suggestion 12, Q6)
**Статус:** Автоисправлено
**Ответ:** Задокументировано как принятый trade-off (write-order инвариант + fail-open; один владелец). Выбор варианта A по CRITICAL-1 (негативное кэширование, без кэша собранного расписания) оставляет гонку в задокументированном виде.
**Действие:** Спека edge case 5 — три ключа читаются неатомарно, последствия перечислены, принято. (aaf98b8)

---

### [CONCERN-15] Рефакторинг `NotificationsViewStateFactory` — инвазивный, посреди feature-ветки

> Task 5 одновременно добавляет schedule-поля и выносит сборку state в фабрику; ошибка в null-discipline уронит `/notifications` целиком.

**Источник:** zai/glm (Concern 7, Q24), deepseek (Concern 5)
**Статус:** Отклонено
**Ответ:** Рефакторинг остаётся в ветке: фабрика покрыта тестами Task 5 + module suite; отдельный PR — процессный оверхед для solo-проекта.
**Действие:** —

---

### [CONCERN-16] Обновление существующих renderer-тестов с `isOwner = true` — ручной «search the whole file»

> После Task 5 рендерер делает `requireNotNull(state.scheduleEnabled)` для owner; существующие owner-конструкции в тестах обязаны получить новые поля.

**Источник:** ollama/minimax (Critical 4)
**Статус:** Автоисправлено
**Ответ:** Конкретные места перечислены в плане (строки 128/146, с fallback-инструкцией при дрейфе файла).
**Действие:** План Task 6 Step 1 п.2. (aaf98b8)

---

### [CONCERN-17] `ZONE_PRESETS` — копипаста списка `/timezone` + жёсткий coupling на его i18n-ключи

> 8 городов дублируются данными; schedule-экран ссылается на `command.timezone.zone.*` — переименование уронит zone-экран.

**Источник:** codex (Suggestion 5), alibaba/qwen (Concern 7), deepseek (Concern 6), ollama/minimax (Concern 10), ollama/kimi (Suggestion 3)
**Статус:** Автоисправлено
**Ответ:** Общая константа пресетов `(i18n-key → olson)`, импорт в оба рендерера — coupling становится явным и компилируемым.
**Действие:** План Task 7 Step 4a — `TimezonePresets.CITIES` + рефакторинг `TimezoneCommandHandler` на цикл по общему списку (поведение идентично сегодняшнему). (aaf98b8)

---

### [CONCERN-18] Multi-instance и прямые SQL-правки не подхватываются кэшем; нет rollback-runbook

> Бессрочный локальный кэш инвалидируется только через `set*` того же процесса; нет плана отката фичи.

**Источник:** codex (Concern 6, Q3), ollama/minimax (Concern 8), ollama/kimi (Q5), alibaba/qwen (Suggestion 13)
**Статус:** Автоисправлено
**Ответ:** Задокументировано: single-instance как предусловие; SQL-правки требуют рестарта; runbook отката.
**Действие:** План Task 10 — ops-заметка (после Спорного 1 уточнена: «values and key absence», «edits or inserts»). (aaf98b8 + фаза спорных)

---

### [CONCERN-19] План не содержит code-review checkpoint перед финальным build (CLAUDE.md)

> CLAUDE.md требует «code-reviewer first → fix critical → build-runner»; финальный Task 10 шёл сразу в build.

**Источник:** codex (Concern 5)
**Статус:** Автоисправлено
**Ответ:** Добавлен шаг branch-wide review перед build.
**Действие:** План Task 10 Step 2 — dispatch code-reviewer по диффу всей ветки, фикс critical, повтор до чистоты. (aaf98b8)

---

### [CONCERN-20] Обработка ошибок сервиса: широкий catch, нет `@throws` на геттерах, дублирование warn

> `catch (e: Exception)` превращает и NPE в «расписание выключено»; асимметрия контрактов never-throws vs пробрасывающие геттеры не отражена в KDoc; битое окно логировалось бы дважды.

**Источник:** ollama/kimi (Concern), deepseek (Critical 3), alibaba/qwen (Suggestion 10)
**Статус:** Автоисправлено
**Ответ:** Широкий catch оставлен по дизайну («любой сбой чтения → уведомления идут»); документация и логи поправлены.
**Действие:** План Task 3 — KDoc геттеров «NOT fail-open: … read failures propagate — only getRecordingSchedule never throws». (aaf98b8)

---

### [CONCERN-21] Ретроактивность: события из бэклога проверяются по сегодняшним window/zone

> Event time используется, но истории расписания нет — старое событие проверяется по текущей конфигурации.

**Источник:** codex (Q1)
**Статус:** Автоисправлено
**Ответ:** Принято и зафиксировано одним предложением (окно меняется редко, бэклог короток).
**Действие:** Спека edge case 8. (aaf98b8)

---

### [CONCERN-22] `/notifications` для OWNER падает при сбое настроек (factory без try/catch)

> При недоступной БД настроек владелец не откроет диалог.

**Источник:** ollama/kimi (Concern)
**Статус:** Отклонено
**Ответ:** Поведение фичей не меняется — текущий handler уже читает `getBoolean` без try/catch (проверено); громкая ошибка диагностична; fail-open нужен доставке, а не диалогу настроек.
**Действие:** —

---

### [CONCERN-23] Auto-enable при сохранении окна: нельзя «настроить, но держать выключенным»

> Любой save окна включает расписание — сценарий «настроить на будущее» невозможен.

**Источник:** zai/glm (Concern 8)
**Статус:** Отклонено
**Ответ:** Сознательное решение, уже зафиксировано в спеке («picked a window, expected it to work» — базовый сценарий); «настроить и сразу выключить» — кнопка `off` в один клик.
**Действие:** —

---

### [CONCERN-24] `ScheduleCallbackHandler` зависит от `TelegramUserService` только ради `getUserZone`

> Handler позиционируется как «чистый dispatch», но тянет user-сервис ради материализации зоны.

**Источник:** alibaba/qwen (Concern 5)
**Статус:** Отклонено
**Ответ:** Материализация зоны — бизнес-правило, ему место рядом с логикой веток `on`/`e:`; вынос в flow спрятал бы правило от unit-тестов (см. CONCERN-3 — flow сознательно не тестируется).
**Действие:** —

---

### [CONCERN-25] Утверждение про проглатывание `CancellationException` — опровергнуто проверкой

> MiniMax утверждал, что `isEnabled()/getWindow()/getZone()` проглатывают `CancellationException`.

**Источник:** ollama/minimax (Critical 2)
**Статус:** Отклонено (false positive)
**Ответ:** Опровергнуто построчно: `getRecordingSchedule()` явно делает `catch (e: CancellationException) { throw e }` до общего catch; геттеры catch-блоков не содержат вовсе (кроме точечного `DateTimeException` в `getZone`) — проглатывать отмену им нечем.
**Действие:** —

---

### [SUGGESTION-1] Заменить placeholder `DetectionDelta(...)` в Task 4 точным литералом

> Тесты Task 4 содержали плейсхолдер-по-ссылке; литерал подтверждён по `NotificationDecisionServiceImplTest.kt:81`.

**Источник:** claude (S3), alibaba/qwen (Critical 2), ollama/minimax (Critical 1 частично)
**Статус:** Автоисправлено
**Ответ:** Литерал `DetectionDelta(1, 0, 0, listOf("car"))` вписан во все плейсхолдеры.
**Действие:** План Task 4 (после Спорного 2 note расширена порядком полей для литералов тестов приоритета). (aaf98b8 + фаза спорных)

---

### [SUGGESTION-2] i18n: `sched.line.off` дублирует перевод «OFF/ВЫКЛ»

> Ключ дублировал существующий `notifications.settings.state.off` — риск рассинхронизации переводов.

**Источник:** claude (S2)
**Статус:** Автоисправлено
**Ответ:** Off-ключи сделаны форматными с подстановкой существующего `state.off`.
**Действие:** План Task 6 Step 3 — `sched.line.off.format`/`sched.line.off.configured.format` с `{0}` = локализованный OFF. Совместно с CONCERN-12. (aaf98b8)

---

### [SUGGESTION-3] Текст таймаута manual-zone вводит в заблуждение

> «Откройте /notifications заново» — но клавиатура stateless, старое сообщение работает.

**Источник:** claude (S4)
**Статус:** Автоисправлено
**Ответ:** Смягчён до «время ожидания истекло».
**Действие:** План Task 9 Step 1 — ключи `sched.zone.timeout`. (aaf98b8)

---

### [SUGGESTION-4] `formatHour`: упростить и зафиксировать 24-часовой контракт

> `formatHour(hour).substringBefore(":")` → `"%02d".format(hour)`; зафиксировать «часы пикера всегда 00–23 zero-padded, локаль не применяется».

**Источник:** claude (S5), ollama/minimax (Concern 6)
**Статус:** Автоисправлено
**Ответ:** `"%02d".format(hour)` + комментарий контракта.
**Действие:** План Task 7 Step 4 — `hourGrid` с комментарием «always 00–23 zero-padded, locale-independent». (aaf98b8)

---

### [SUGGESTION-5] Наблюдаемость подавлений: `OUT_OF_SCHEDULE` виден только на debug

> Вопрос «почему не пришло уведомление» диагностируется только включением debug-логов; владелец не получает фидбэка о работе гейта.

**Источник:** claude (S6), ollama/minimax (Suggestion 13)
**Статус:** Обсуждено с пользователем (Спорное 6/6)
**Ответ:** Вариант C — оставить debug-only. Все ветки решения логируются на debug — единая конвенция; дневная тишина при включённом расписании — ожидаемое поведение; сценарий «битая зона» после Спорного 4 диагностируется строкой «misconfigured» в UI. INFO-«первое подавление» (A) требует мутабельного состояния в stateless-сервисе с размытой семантикой «первого»; метрики (B) — инфраструктуры нет, digest отвергнут дизайном.
**Действие:** —

---

### [SUGGESTION-6] Дедуплицировать `editMessageText` + «message is not modified»

> `edit()` в `ScheduleSettingsFlow` повторяет try/catch из RERENDER-блока бота.

**Источник:** zai/glm (Suggestion 16)
**Статус:** Отклонено
**Ответ:** Rule of three — два места, вынос helper'а преждевременен.
**Действие:** —

---

### [SUGGESTION-7] Вынести литерал `"nfs:"` в общую константу

> `"nfs:"` захардкожен в боте и handler'е; план вводит `ScheduleCallbackHandler.PREFIX` — заодно вынести и `"nfs:"`.

**Источник:** zai/glm (Suggestion 12)
**Статус:** Отклонено
**Ответ:** Вне scope фичи; возможный follow-up-рефакторинг.
**Действие:** —

---

### [SUGGESTION-8] Обновить `database.md`: три новых ключа `app_settings`

> Дизайн заявлял «database.md: no changes», но добавляются 3 ключа.

**Источник:** zai/glm (Suggestion 14)
**Статус:** Автоисправлено
**Ответ:** Ключи добавляются в `database.md`; раздел Documentation спеки поправлен.
**Действие:** План Task 10 Step 1 п.6; спека Documentation. (aaf98b8)

---

### [SUGGESTION-9] Спека: «defaults to owner's `olson_code`» — такого DTO-поля нет

> У `TelegramUserDto` нет поля zone; план корректно использует `getUserZone(chatId)` с UTC-fallback.

**Источник:** zai/glm (Suggestion 15)
**Статус:** Автоисправлено
**Ответ:** Переформулировано через фактический API.
**Действие:** Спека Agreed Requirements — «defaults to the owner's current timezone via `TelegramUserService.getUserZone`, UTC fallback». (aaf98b8)

---

### [SUGGESTION-10] Кластер UX/док-пояснений семантики расписания

> (а) зона расписания независима от персональной зоны владельца; (б) event-time базис неочевиден; (в) зона расписания — зона интерпретации окна, не зона камеры; (г) расписание действует только при включённом глобальном флаге.

**Источник:** zai/glm (Suggestion 17), deepseek (Q12), ollama/minimax (Concern 7, Suggestion 14), ollama/kimi (Suggestion 6)
**Статус:** Автоисправлено
**Ответ:** Вся семантика — в rules (Task 10), тексты бота не раздуваются.
**Действие:** План Task 10 Consumers-абзац: event time, зона окна независима от камеры и `/timezone` владельца, гейт значим только при включённом глобальном флаге. (aaf98b8)

---

### [SUGGESTION-11] Дополнительные тесты по мелким пробелам покрытия

> (а) для crossing-окна не покрыт `contains(00:00)`; (б) нет теста «`home` из end-picker не сохраняет окно»; (в) facade-тест «при `OUT_OF_SCHEDULE` нет вызовов Telegram/AI».

**Источник:** ollama/minimax (Q7, Suggestion 16), codex (Suggestion 4 частично)
**Статус:** Автоисправлено
**Ответ:** (а) и (б) добавлены; (в) не добавлен — подавление уже покрыто decision-тестами.
**Действие:** План Task 1 — тест `crossing window includes midnight`; Task 8 — тест `home re-renders main without changes`. (aaf98b8)

---

### [SUGGESTION-12] Дополнить Out of Scope и rejected alternatives

> (а) cron-формат не рассматривался; (б) prefetch/параллельное чтение отвергнуто; (в) `ZONE_PRESETS` — UI-список, не whitelist.

**Источник:** ollama/minimax (Suggestion 18, Q1), deepseek (Suggestion 9), alibaba/qwen (Q4)
**Статус:** Автоисправлено
**Ответ:** По предложению на пункт в спеку — дешевле повторных вопросов в iter-2.
**Действие:** Спека Out of Scope (cron, prefetch-отказ) + Key UI decisions (presets — не whitelist). (aaf98b8)

---

### [SUGGESTION-13] Группа мелких замечаний, рекомендуемых к отклонению

> 11 позиций: (а) leap seconds; (б) retry-семантика `rejectedEqualEnd`; (в) UX «Back из zone-экрана»; (г) helper для `isOwner`; (д) NOT NULL `setting_value`; (е) en-dash/UTF-8; (ж) i18n `{0}/{1}`; (з) hot-path bottleneck; (и) digest; (к) commit без Confirm в end-picker; (л) тест stale-`s:H` после idle.

**Источник:** ollama/minimax (Q9, Q10, Concern 9, Suggestions 15/19/20, Critical 5), alibaba/qwen (Suggestions 11/12, Q2, Q3)
**Статус:** Отклонено (группой)
**Ответ:** (а) `Instant` leap seconds не представляет; (б) callback data естественно различается, сам ревьюер: «низкий приоритет»; (в) сам ревьюер: «не баг»; (г) все места уже зовут один `userService.isOwner()`; (д) проверено: `VARCHAR(2048) NOT NULL` в 1.0.4.xml:40 — противоречия нет; (е) properties уже UTF-8; (ж) сам ревьюер подтвердил OK; (з) сам ревьюер снял; (и) digest уже в Out of Scope спеки; (к) спека явно фиксирует «End hour chosen → save window» — Confirm-шаг не задумывался; (л) handler stateless по построению — тест тавтологичен.
**Действие:** —

---

### [QUESTION-1] `TRACKER_ERROR` вне окна: подавление при неизвестном `newTracks` — намеренно?

> План сужает fail-open трекера до `resolvedGlobalEnabled && scheduleAllows` — при ошибке трекера вне окна suppress, хотя новые объекты могли быть.

**Источник:** zai/glm (Q20)
**Статус:** Автоисправлено
**Ответ:** Намеренно: «a daytime event is never delivered» покрывает и этот случай — расписание есть жёсткий временнóй гейт, независимый от состояния трекера.
**Действие:** Спека Decision Enforcement — ветка `TRACKER_ERROR` с обоснованием. (aaf98b8)

---

### [QUESTION-2] Материализация зоны с UTC-fallback: приемлемо ли молчаливое UTC для владельца без `/timezone`?

> Первый save окна выставит расписанию UTC, окно «00:00–07:00» будет означать «03:00–10:00 MSK».

**Источник:** alibaba/qwen (Q1)
**Статус:** Отклонено
**Ответ:** UTC-fallback принят и уже отражён в спеке: зона сразу видна в строке статуса «(UTC)», меняется в два клика; owner реального деплоя `/timezone` уже настроил.
**Действие:** —

---

### [QUESTION-3] `NotificationScheduleService` без `@ConditionalOnProperty` telegram.enabled — намеренно?

> Сервис в `service`-модуле, decision-слой применяет расписание независимо от Telegram.

**Источник:** ollama/minimax (Q8)
**Статус:** Автоисправлено
**Ответ:** Намеренно: `service`-модуль архитектурно не знает о telegram-свойствах.
**Действие:** Спека Model & Services — «deliberately NOT gated by `application.telegram.enabled`». (aaf98b8)

---

### [QUESTION-4] `val chatId = current.chatId ?: return` в flow — молчаливый выход или warn?

> Owner всегда ACTIVE с chatId; молчаливый return скрыл бы диагностику при нарушении инварианта.

**Источник:** ollama/minimax (Q3)
**Статус:** Автоисправлено
**Ответ:** Warn-лог перед return (падение через requireNotNull слишком жёстко для UI-пути).
**Действие:** План Task 9 Step 2 — `logger.warn { "sched callback from user without chatId…" }`. (aaf98b8)

---

### [QUESTION-5] Тесты ассертят англоязычные строки («must differ») — использовать message-key?

> Смена формулировки уронит тест, привязанный к подстроке.

**Источник:** ollama/minimax (Q4)
**Статус:** Отклонено
**Ответ:** Конвенция проекта: существующие renderer-тесты ассертят рендеренный текст en-локали; падение при смене формулировки — осознанная цена читаемости и проверки фактического рендера.
**Действие:** —

---

### [QUESTION-6] Уточнить DST-формулировку в спеке (осенний повтор локального времени)

> Edge case 2 не проговаривал: весенний gap — времена окна могут не существовать; осенний overlap — повторившийся час маппится в два Instant.

**Источник:** alibaba/qwen (Q5)
**Статус:** Автоисправлено
**Ответ:** Edge case 2 дополнен обеими ситуациями (каждый Instant всё равно резолвится ровно в одно локальное время).
**Действие:** Спека edge case 2. (aaf98b8)

---

## Изменения в документах

| Файл | Изменение |
|------|-----------|
| `docs/superpowers/specs/2026-07-18-notification-schedule-design.md` | Авто-фиксы (aaf98b8): материализация зоны в `on`, one-shot manual-zone, write-order инвариант, edge cases 2/3/5/8, задекларированные отклонения плана, `getUserZone`-формулировка, Out of Scope-дополнения. Фаза спорных: Storage — негативное кэширование absent-ключей; Decision Enforcement — absent keys в кэше + нормативная reason-precedence; Main screen + edge case 1 — состояние «misconfigured»; Key UI decisions — сознательно широкая валидация зоны; Out of Scope — отказ от кэша собранного расписания |
| `docs/superpowers/plans/2026-07-18-notification-schedule.md` | Авто-фиксы (aaf98b8): STRICT-парсер + тесты, `init require`, литералы `DetectionDelta`, чек-лист waiter'а (Task 9 Step 5), guard-тест IGNORE, `TimezonePresets`, ops/rollback, branch-wide review, KDoc'и. Фаза спорных: Task 2 — негативное кэширование (заголовок, гарантия, 2 теста, Step 4 переписан под `CachedValue`/`getRaw`); Task 4 — 2 теста приоритета + note + KDoc «first tripped gate»; Task 6 — ключ `misconfigured` (en/ru), ветка `when`, 2 renderer-теста; Task 10 — ops-строка «values and key absence» |

## Статистика

- Всего замечаний: 50
- Автоисправлено (без обсуждения): 33
- Авто-применено после анализа: 1
- Обсуждено с пользователем: 6
- Отклонено: 10
- Повторов (автоответ): 0
- Отложено (стоп): 0
- Пользователь сказал «стоп»: Нет
- Агенты: claude-self (Fable), codex (gpt-5.6-sol, reasoning max), zai/glm (GLM 5.2), alibaba/qwen (Qwen 3.7 Plus), deepseek/v4-pro, ollama/kimi (K2.7), ollama/minimax (M3)
