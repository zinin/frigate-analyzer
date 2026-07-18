# Parsed Review Issues — Iteration 1 (notification-schedule)

Источник: `docs/superpowers/specs/2026-07-18-notification-schedule-review-merged-iter-1.md`
(7 ревьюеров), разобрано агентом `claude-mesh:review-discussion`. Из ~95 сырых находок после
семантической дедупликации — 50 уникальных, все NEW (предыдущих итераций нет).

Верификация спорных фактов, выполненная при парсинге: подтверждено эмпирически (jshell)
`LocalTime.parse("24:00")` → `00:00` при SMART; подтверждён литерал
`DetectionDelta(1, 0, 0, listOf("car"))` (NotificationDecisionServiceImplTest.kt:81);
опровергнуто утверждение MiniMax про проглатывание `CancellationException`; подтверждено
`setting_value NOT NULL` (1.0.4.xml:40); подтверждены «7 рядов клавиатуры» и перехват
callback в боте.

---

### [CRITICAL-1] Отсутствующие ключи настроек не кэшируются — DB-запрос на каждую запись в дефолтном состоянии
TEXT: `AppSettingsServiceImpl.loadAndCache` не кэширует отрицательные результаты: для отсутствующего ключа каждый вызов идёт через общий `cacheMutex` + `repository.findBySettingKey`. Существующие флаги спасает сидинг миграцией 1.0.4, а дизайн расписания сознательно выбирает «no seeding». Пока расписание не настроено (дефолт большинства инсталляций навсегда), каждый `evaluate()` выполняет SELECT под глобальным мьютексом. Обоснование дизайна «reads go through the settings cache» опирается на неверный факт. Функционально корректно (fail-open), но молчаливая постоянная регрессия hot path.
SOURCES: claude (C1, S1, Q2), codex (Critical 1)
STATUS: NEW
OPTIONS:
  1. Негативное кэширование (рекомендуется) — сентинел для отсутствующих ключей в `AppSettingsServiceImpl`, снятие в `setBoolean`/`setString`; чинит класс проблем для всех будущих ключей, без миграции; тест «повторное чтение отсутствующего ключа = один repository-read».
  2. Засеять три ключа миграцией `enabled='false'` — консистентно с конвенцией `global_enabled`, но противоречит дизайн-решению «absent = disabled» и требует миграции.
  3. Кэш собранного `NotificationSchedule` в `NotificationScheduleService` с инвалидацией в сеттерах — заодно закрывает CONCERN-5 и CONCERN-14, но не чинит будущие ключи `app_settings`.

---

### [CRITICAL-2] Ветка `on` не материализует зону — спека требует «on first enable/save»
TEXT: В плане (Task 8) материализация зоны есть только в ветке `e:<S>:<E>`; ветка `on` при существующем окне делает только `setEnabled(true)` (проверено, план ~1993). Достижимо состояние `enabled + window + zone=null` (внешняя порча БД): UI покажет «ON (?)», `getRecordingSchedule()` fail-open'ится в `null` — расписание тихо не работает, warn на каждой записи. Инвариант «window ⟹ zone» держится на негласном контракте UI-пути. Self-review плана («deviations: none») расхождение не зафиксировал. Подняли 5 из 7 ревьюеров.
SOURCES: claude (C2), codex (Critical 3), zai/glm (Critical 2, Q19), alibaba/qwen (Critical 1 частично), ollama/kimi (Critical 1, Suggestion 2, Q3)
STATUS: NEW
OPTIONS:
  1. Продублировать материализацию в ветке `on` (рекомендуется) — тот же блок `if (getZone() == null) setZone(userService.getUserZone(ownerChatId))` перед `setEnabled(true)`; двухстрочный фикс плана, соответствие спеке.
  2. Дефолтная зона в `getRecordingSchedule()` при `zone == null` — глубже защищает, но маскирует мисконфигурацию.
  3. Запретить включение без зоны (ветка `on` открывает zone-экран) — строже, но лишний шаг диалога.

---

### [CRITICAL-3] Парсер окна принимает «24:00» — повреждённое значение становится активным окном вместо fail-open
TEXT: `ScheduleWindow.parse` использует `DateTimeFormatter.ofPattern("HH:mm")` с дефолтным `ResolverStyle.SMART`. Проверено в jshell: `"24:00"` → `00:00` (а `"00:60"` отвергается). Значит `"24:00-07:00"` из БД молча превратится в активное окно `00:00–07:00`, хотя битое значение должно отключать расписание. Смежное (qwen): split по `"-"` хрупок и валидирует через исключения.
SOURCES: codex (Critical 2), zai/glm (Suggestion 13), alibaba/qwen (Suggestion 9 смежное)
STATUS: NEW
OPTIONS:
  1. `withResolverStyle(ResolverStyle.STRICT)` (рекомендуется) — минимальная правка + тесты на `24:00`, `00:60`, секунды, пробелы.
  2. Regex-предвалидация `^\d{2}:\d{2}-\d{2}:\d{2}$` + range-проверка — закрывает и хрупкий split.
  3. Принять SMART как нормализацию — слабый вариант: направление ошибки «подавление» противоречит fail-open.

---

### [CRITICAL-4] Приоритет `OUT_OF_SCHEDULE` относительно `ALL_REPEATED`/`NO_VALID_DETECTIONS` не зафиксирован
TEXT: План вставляет `!scheduleAllows` между `!resolvedGlobalEnabled` и `delta.newTracksCount > 0` (проверено, план ~890–900). Следствие: запись вне окна с только-повторами получит `OUT_OF_SCHEDULE`, а не `ALL_REPEATED` (glm), а пустая delta даст `NO_VALID_DETECTIONS` раньше расписания (qwen, kimi). На `shouldNotify` не влияет — вопрос семантики reason для логов/метрик. Тесты покрывают только пару `GLOBAL_OFF` vs `OUT_OF_SCHEDULE`; спека полный порядок не оговаривает.
SOURCES: zai/glm (Critical 1, Q18), alibaba/qwen (Concern 3), ollama/kimi (Concern, Q4), ollama/minimax (Critical 1 частично)
STATUS: NEW
OPTIONS:
  1. Зафиксировать текущий порядок как нормативный (рекомендуется) — прописать в спеке precedence `NO_VALID_DETECTIONS > GLOBAL_OFF > OUT_OF_SCHEDULE > NEW_OBJECTS/ALL_REPEATED` + тесты приоритета; семантика «первый сработавший гейт» консистентна с тем, как `GLOBAL_OFF` уже перекрывает повторы.
  2. Сузить до `delta.newTracksCount > 0 && !scheduleAllows` — метрика точнее («иначе был бы доставлен»), но ломает симметрию с `GLOBAL_OFF`.

---

### [CRITICAL-5] Сохранение окна не атомарно; инвариант порядка записи нигде не зафиксирован
TEXT: Ветка `e:` делает три отдельных upsert'а: `setWindow → setZone(если null) → setEnabled(true)` (проверено, план ~2030). Сбой между ними оставляет частичное состояние, а дизайн декларирует «Each UI operation writes exactly one key». Порядок «enabled — последним» корректен, но нигде не назван инвариантом — будущая перестановка откроет окно «enabled без window/zone».
SOURCES: codex (Critical 4, Suggestion 1), ollama/kimi (Critical 2), claude (N5)
STATUS: NEW
OPTIONS:
  1. Принять last-write-wins + зафиксировать инвариант (рекомендуется) — комментарий в `ScheduleCallbackHandler` («порядок: window → zone → enabled последним»), поправить формулировку спеки; частичные состояния прикрыты fail-open, один владелец.
  2. Композитный `configureAndEnable(window, zone?, updatedBy)` — детерминированный порядок в одном методе, опционально `@Transactional` (R2DBC-транзакционность проверить); новый API.
  3. Единый versioned-ключ со всей конфигурацией — атомарный снапшот, но ломает принятую модель трёх ключей.

---

### [CRITICAL-6] Спека требует re-await при невалидном ручном вводе зоны, план и референсный `/timezone` — one-shot
TEXT: Спека (edge case 6): «error message + re-await, cancel pattern as in `/timezone`». Но `/timezone` (проверено `TimezoneCommandHandler.kt:135-137`) при `DateTimeException` шлёт ошибку и выходит (one-shot). План следует `/timezone`. Спека внутренне противоречива — её референсный паттерн не делает re-await.
SOURCES: claude (N1, Q3), codex (Critical 5)
STATUS: NEW
OPTIONS:
  1. Поправить спеку на one-shot (рекомендуется) — консистентно с фактическим `/timezone` и планом; одна строка правки.
  2. Реализовать re-await-цикл (до валидной зоны / `/cancel` / 120-с дедлайна) — лучше UX, но отклоняется от референса и добавляет логики в непокрытый waiter-код.

---

### [CONCERN-1] Инвариант `start != end` не защищён в типе `ScheduleWindow`
TEXT: Его отвергает только `parse`; публичный конструктор data class и `ofHours(5, 5)` создают вырожденное окно, у которого `contains` — «всегда true». Инвариант держится на дисциплине вызывающих. Смежное (codex): конструктор принимает `LocalTime` с секундами, которые `storageFormat()` молча отбрасывает. Подняли 4 ревьюера (deepseek ошибся в направлении вырождения, но инвариант нужен).
SOURCES: claude (N6), codex (Concern 1, Suggestion 3), zai/glm (Concern 11), deepseek (Critical 2, Suggestion 10), ollama/minimax (Suggestion 17 смежное)
STATUS: NEW
OPTIONS:
  1. `init { require(start != end) }` в data class (рекомендуется) — закрывает конструктор и `ofHours` разом; ничего не ломается (parse и UI-guard стоят до); заодно вписать в спеку определение валидного окна.
  2. `require` только в `ofHours` — конструктор остаётся дырой.

---

### [CONCERN-2] UI при `enabled=true` и битой конфигурации: строка статуса и кнопка-тумблер противоречат друг другу
TEXT: Строка ведётся от `scheduleEnabled && scheduleWindow != null`, кнопка — от сырого `scheduleEnabled`. При `enabled=true, window=null`: «OFF» + кнопка «Disable»; при `enabled=true, zone=null`: «ON (?)» при неработающем расписании. Нет единого источника истины. После фикса CRITICAL-2 состояние достижимо только внешней порчей БД. Смежное (qwen): нет renderer-теста на это состояние.
SOURCES: claude (Q4), codex (Q4, Suggestion 2), ollama/kimi (Critical 4, Suggestion 1), alibaba/qwen (Concern 8), ollama/minimax (Q2 смежное)
STATUS: NEW
OPTIONS:
  1. Третье состояние строки «misconfigured» (рекомендуется) — при `enabled && (window == null || zone == null)` рендерить «⚠️ настроено некорректно, уведомления идут»; кнопка «Disable» согласована; + renderer-тест.
  2. Sealed `ScheduleStatus` (Disabled/Active/Misconfigured) в сервисе — системно, но много кода ради маловероятного состояния.
  3. Принять как есть и задокументировать.

---

### [CONCERN-3] `ScheduleSettingsFlow` (Task 9) без единого теста — самый сложный клей фичи
TEXT: План декларирует «no new unit tests». При этом `manualZoneInput`: `withTimeoutOrNull(120s)`, `/cancel`, invalid-ветка, rethrow `CancellationException`; плюс маршрутизация 8+ `Outcome` и обработка «message is not modified». Подняли 6 из 7 ревьюеров. `/timezone` тоже не тестирован — известная дыра, не оправдание.
SOURCES: claude (N7), codex (Concern 3), zai/glm (Concern 6, Q23), alibaba/qwen (Concern 4), deepseek (Critical 1, Suggestion 11), ollama/kimi (Concern, Suggestion 5)
STATUS: NEW
OPTIONS:
  1. Минимальный набор unit-тестов (рекомендуется) — mapping `Outcome → edit/send` + manual-zone ветки (valid/invalid//cancel/timeout) через mock `BehaviourContext` или чистую `resolveScreen`; 5–6 тестов.
  2. Принять осознанно + обязательный ручной чек-лист перед PR (zman → валидная/невалидная//cancel/таймаут/конкурентные клики/смена языка).
  3. Компромисс: чистая функция маршрутизации + тесты только на неё.

---

### [CONCERN-4] 120-с waiter внутри `onDataCallbackQuery`: конкурентность не подтверждена, рестарт не переживает
TEXT: Если ktgbotapi обрабатывает callback'и последовательно per-marker — клики встанут в очередь за waiter'ом (спиннер до 120 с); если параллельно — двойной `zman` породит два конкурирующих waiter'а. Прецедент `/timezone` — message-триггер с другим marker'ом. Плюс (minimax): waiter не переживает рестарт бота (текст молча уйдёт в `onContentMessage`); ограничение «один чат — один waiter» нигде не зафиксировано.
SOURCES: claude (N3, Q1), ollama/minimax (Critical 3, Concern 11, Q5)
STATUS: NEW
OPTIONS:
  1. Ручная проверка обоих сценариев на живом боте до мержа (рекомендуется) + задокументировать ограничения waiter-паттерна в rules; вписать в чек-лист CONCERN-3.
  2. In-flight guard на chatId сразу — защита независимо от семантики, но возможно мёртвый код.

---

### [CONCERN-5] Битые window/zone парсятся и warn-логируются на каждой записи — log storm
TEXT: `getRecordingSchedule()` вызывается из каждого `evaluate()`; при повреждённом значении warn'ы пишутся на каждую запись с детекциями. Смежное (kimi): парсинг `ScheduleWindow.parse` + `ZoneId.of` повторяется на каждой записи и в здоровом состоянии.
SOURCES: codex (Concern 2), ollama/kimi (Concern, Suggestion 4)
STATUS: NEW
OPTIONS:
  1. Кэш распарсенного результата в `NotificationScheduleService` с инвалидацией в `set*` (рекомендуется) — закрывает шторм и повторный парсинг; совпадает с вариантом 3 CRITICAL-1.
  2. Warn-once-per-value (лог при смене raw-значения).
  3. Принять — битые значения аномальны, шторм сигнализирует.

---

### [CONCERN-6] Отклонения плана от дизайна не задекларированы (self-review: «deviations: none»)
TEXT: Проверено: (а) клавиатура 5 → 7 рядов (план, строка 1253) вместо «One extra keyboard row» с 3 кнопками по дизайну; (б) `nfs:g:sched:*` перехватывается в `FrigateAnalyzerBot` перед `notificationsSettingsCallbackHandler.dispatch(...)`, а дизайн поручает диспатч subtree самому handler'у. Self-review плана: «Known intentional deviation: none» (строка 2357).
SOURCES: codex (Concern 4)
STATUS: NEW
OPTIONS:
  1. Зафиксировать оба отклонения в спеке (рекомендуется) — план обоснован (3 кнопки в ряд узки; wiring через бот проще), спека должна отражать реальность.
  2. Привести план к спеке — консистентность ценой худшей эргономики и рефакторинга.

---

### [CONCERN-7] Маршрутизация `nfs:g:sched:*` держится исключительно на порядке проверок в боте
TEXT: При изменении порядка callback утечёт в старый `NotificationsSettingsCallbackHandler.dispatch()` → unknown scope → `IGNORE` — молчаливое проглатывание. Риск низкий (IGNORE безопасен), но хрупко.
SOURCES: deepseek (Concern 7)
STATUS: NEW
OPTIONS:
  1. Комментарий-инвариант в боте + защитный тест `dispatch("nfs:g:sched:on") == IGNORE` (рекомендуется).
  2. Перенести диспатч subtree внутрь `NotificationsSettingsCallbackHandler` — структурно, но см. CONCERN-6.

---

### [CONCERN-8] Fail-open асимметрия расписания и глобального флага — принята, но тиха
TEXT: Один класс ошибок (settings unreadable) даёт разные направления: флаг префетчится → сбой заметен, запись retryable; расписание fail-open внутри `evaluate()` → лишние дневные уведомления, сбой тих. Спека обосновывает, но ревьюеры требуют фиксации ближе к коду: будущий разработчик может «выровнять» семантики.
SOURCES: zai/glm (Concern 9), ollama/kimi (Concern, Q1)
STATUS: NEW
OPTIONS:
  1. Расширить комментарий в `evaluate()` («сбой настроек здесь = лишнее уведомление, НЕ retry — намеренно») + убедиться, что Task 10 включает пункт об асимметрии в `telegram-notifications.md` (рекомендуется).
  2. Считать достаточным (спека + KDoc «NEVER throws»).

---

### [CONCERN-9] Non-owner не видит причину пропажи дневных уведомлений
TEXT: Расписание OWNER-only, но решение в `evaluate()` до per-user фильтра — подавляет всем. Non-owner с включёнными детекциями не увидит почему — «магическое» пропадание.
SOURCES: zai/glm (Concern 10, Q21)
STATUS: NEW
OPTIONS:
  1. Задокументировать как принятое (рекомендуется) — single-owner household; одно предложение в спеку/rules.
  2. Read-only строка расписания для non-owner — честнее, но новые ключи и рендер-ветка ради маргинального сценария.

---

### [CONCERN-10] Валидация ручного ввода зоны мягче, чем в `/timezone` (offset-зоны принимаются)
TEXT: `/timezone` требует `contains('/')` (отвергает «UTC» и offset'ы; проверено `TelegramUserServiceImpl.kt:133`), новый флоу принимает «UTC», «GMT+3», «+03:00». Функционально проблем нет (Java конвертирует offset-зоны корректно), но два диалога «одного паттерна» валидируют по-разному. Kimi оценил Critical, deepseek подтверждает отсутствие функциональной проблемы.
SOURCES: claude (N8), codex (Q2), deepseek (Concern 4, Q13), ollama/kimi (Critical 3, Q6)
STATUS: NEW
OPTIONS:
  1. Принять расширенную валидацию как сознательную (рекомендуется) — «UTC» валидная зона; зафиксировать в спеке, позже ослабить `/timezone`.
  2. Скопировать `contains('/')` — консистентность сейчас, ценой отклонения валидного «UTC».
  3. Общий компонент `ZoneInputDialog` — системно (закрывает и CONCERN-17), но расширяет scope.

---

### [CONCERN-11] Stale end-picker + auto-enable нарушает заявленное «no toggle surprises»
TEXT: `nfs:g:sched:e:<S>:<E>` на устаревшей клавиатуре (второе открытое сообщение `/notifications`) не только перезапишет окно, но и включит расписание, которое владелец успел явно выключить. Заявление спеки (edge case 3) сильнее реальности.
SOURCES: claude (N2)
STATUS: NEW
OPTIONS:
  1. Принять и записать в спеку (рекомендуется) — «stale `e:`-клик пересохраняет окно и повторно включает — принято»; вероятность мала.
  2. Не авто-включать из «не-последнего» сообщения — требует состояния, противоречит stateless-дизайну.

---

### [CONCERN-12] При выключенном расписании UI скрывает настроенное окно
TEXT: Выключенное расписание — голое «OFF». Владелец не видит, какое окно активирует кнопка «Enable» (auto-enable применяет невидимое значение).
SOURCES: claude (N4)
STATUS: NEW
OPTIONS:
  1. Третий вариант строки «OFF (00:00–07:00, Europe/Moscow)» при сохранённом окне (рекомендуется).
  2. Оставить.

---

### [CONCERN-13] `setString`: значение в INFO-логе + игнорирование результата `upsert`
TEXT: Проверено: `AppSettingRepository.upsert` возвращает `Long`. (а) `logger.info` с значением — будущий секретный ключ утечёт; (б) возврат игнорируется — при `0L` тихий «успех» с несохранённым значением. `setBoolean` ведёт себя так же (консистентно).
SOURCES: claude (S7), zai/glm (Concern 3, Concern 4, Q22)
STATUS: NEW
OPTIONS:
  1. Обе правки по строчке (рекомендуется) — логировать только ключ (значение на debug) + warn при `rows == 0L`; заодно в `setBoolean`.
  2. Минимум — KDoc «не для секретов», остальное как `setBoolean`.

---

### [CONCERN-14] Три независимых чтения в `getRecordingSchedule()` — окно гонки шире одного флага
TEXT: `isEnabled()` + `getWindow()` + `getZone()` не атомарны между собой: при конкурентной записи можно прочитать смесь. Все варианты дают либо валидное окно, либо fail-open — уведомление не теряется, но может уйти вне окна на одно вычисление. Edge case 5 спеки описывает один флаг, не три.
SOURCES: zai/glm (Concern 5), deepseek (Concern 8), alibaba/qwen (Concern 6), ollama/minimax (Suggestion 12, Q6)
STATUS: NEW
OPTIONS:
  1. Задокументировать как принятый trade-off в edge case 5 (рекомендуется) — если в CRITICAL-1/CONCERN-5 выбран кэш собранного расписания, гонка схлопывается автоматически.
  2. Единый снапшот `getScheduleState()` / bulk-read — новый API ради маловероятного кейса.

---

### [CONCERN-15] Рефакторинг `NotificationsViewStateFactory` — инвазивный, посреди feature-ветки
TEXT: Task 5 одновременно добавляет schedule-поля и выносит сборку state из handler'а и RERENDER-блока в фабрику. Ошибка в null-discipline уронит `/notifications` целиком. Рефакторинг обоснован, риск в совмещении с фичей.
SOURCES: zai/glm (Concern 7, Q24), deepseek (Concern 5)
STATUS: NEW
OPTIONS:
  1. Оставить в одной ветке (рекомендуется) — фабрика покрыта тестами Task 5 + module suite; отдельный PR — оверхед для solo-проекта.
  2. Вынести фабрику отдельным PR до фичи — изолированная регрессия, дороже процесс.

---

### [CONCERN-16] Обновление существующих renderer-тестов с `isOwner = true` — ручной «search the whole file»
TEXT: После Task 5 рендерер делает `requireNotNull(state.scheduleEnabled)` для owner; существующие owner-конструкции в тестах обязаны получить новые поля. План говорит «search the whole test file» — ручной шаг (minimax насчитал ~3 места). Смягчение: при пропуске тесты падают громко, не молча.
SOURCES: ollama/minimax (Critical 4)
STATUS: NEW
OPTIONS:
  1. Перечислить конкретные тесты в плане (рекомендуется) — двухминутная правка, убирает свободу исполнителя.
  2. Оставить — режим отказа безопасный (громкое падение в TDD-цикле).

---

### [CONCERN-17] `ZONE_PRESETS` — копипаста списка `/timezone` + жёсткий coupling на его i18n-ключи
TEXT: 8 городов дублируются данными; при добавлении города списки разойдутся. Плюс schedule-экран ссылается на `command.timezone.zone.*` — переименование при рефакторинге `/timezone` уронит zone-экран (`NoSuchMessageException`). Подняли 5 ревьюеров.
SOURCES: codex (Suggestion 5), alibaba/qwen (Concern 7), deepseek (Concern 6), ollama/minimax (Concern 10), ollama/kimi (Suggestion 3)
STATUS: NEW
OPTIONS:
  1. Общая константа пресетов `(i18n-key → olson)` в `telegram/i18n/TimezonePresets.kt`, импорт в оба рендерера (рекомендуется) — coupling становится явным и компилируемым.
  2. Независимые ключи и список для schedule — изоляция ценой дублирования переводов.
  3. Минимум — комментарий о coupling + упоминание в rules.

---

### [CONCERN-18] Multi-instance и прямые SQL-правки не подхватываются кэшем; нет rollback-runbook
TEXT: Бессрочный локальный кэш инвалидируется только через `set*` того же процесса: второй инстанс или SQL-апдейт не видны до рестарта (существующее ограничение флагов). Смежное (qwen): нет плана отката фичи.
SOURCES: codex (Concern 6, Q3), ollama/minimax (Concern 8), ollama/kimi (Q5), alibaba/qwen (Suggestion 13)
STATUS: NEW
OPTIONS:
  1. Задокументировать (рекомендуется) — single-instance как предусловие; SQL-правки требуют рестарта; runbook отката: `DELETE FROM app_settings WHERE setting_key LIKE 'notifications.recording.schedule.%'` + рестарт (или `off` в UI).
  2. TTL/распределённая инвалидация — решает несуществующее требование.

---

### [CONCERN-19] План не содержит code-review checkpoint перед финальным build (CLAUDE.md)
TEXT: CLAUDE.md требует «code-reviewer first → fix critical → build-runner»; финальный Task 10 идёт сразу в `./gradlew build`. Смягчение: исполнение через `superpowers:subagent-driven-development` (проверено в execution-prompt) делает review per-task — но финального ревью всей ветки в плане нет.
SOURCES: codex (Concern 5)
STATUS: NEW
OPTIONS:
  1. Добавить шаг в Task 10 (рекомендуется) — «dispatch code-reviewer → исправить critical → build-runner»; одна строка.
  2. Отклонить — per-task review покрывает по духу.

---

### [CONCERN-20] Обработка ошибок сервиса: широкий catch, нет `@throws` на геттерах, дублирование warn
TEXT: (а) `catch (e: Exception)` в `getRecordingSchedule()` превращает и NPE в «расписание выключено» (kimi); (б) асимметрия контрактов never-throws vs пробрасывающие геттеры не отражена в KDoc геттеров (deepseek); (в) битое окно логируется дважды — warn в `getWindow` + «misconfigured» в `getRecordingSchedule` (qwen, с неточностью: `getZone` сам ловит `DateTimeException`).
SOURCES: ollama/kimi (Concern), deepseek (Critical 3), alibaba/qwen (Suggestion 10)
STATUS: NEW
OPTIONS:
  1. Оставить широкий catch, поправить документацию и логи (рекомендуется) — fail-open по дизайну «любой сбой чтения → уведомления идут»; добавить `@throws` в KDoc геттеров; убрать дублирующий warn.
  2. Сузить catch до ожидаемых типов — точнее, но противоречит контракту «NEVER throws» и принципу фичи.

---

### [CONCERN-21] Ретроактивность: события из бэклога проверяются по сегодняшним window/zone
TEXT: Event time используется, но истории расписания нет — старое событие проверяется по текущей конфигурации на момент обработки.
SOURCES: codex (Q1)
STATUS: NEW
OPTIONS:
  1. Принять и зафиксировать в спеке одним предложением (рекомендуется) — окно меняется редко, бэклог короток.
  2. Хранить историю конфигураций — overkill.

---

### [CONCERN-22] `/notifications` для OWNER падает при сбое настроек (factory без try/catch)
TEXT: При недоступной БД настроек владелец не откроет диалог. Проверено: текущий `NotificationsCommandHandler` уже читает `getBoolean` без try/catch — поведение не меняется, план лишь добавляет три чтения к существующим двум.
SOURCES: ollama/kimi (Concern)
STATUS: NEW
OPTIONS:
  1. Принять как есть (рекомендуется) — идентично сегодняшнему; громкая ошибка диагностична; fail-open нужен доставке, не диалогу настроек.
  2. Fallback-рендер «настройки недоступны» — расширение scope ради редкого сбоя.

---

### [CONCERN-23] Auto-enable при сохранении окна: нельзя «настроить, но держать выключенным»
TEXT: Любой save окна включает расписание — сценарий «настроить на будущее» невозможен. Сознательное решение спеки, но может удивить.
SOURCES: zai/glm (Concern 8)
STATUS: NEW
OPTIONS:
  1. Принять, поведение уже в спеке (рекомендуется) — «настроить и сразу выключить» = кнопка `off` в один клик; опционально строка-подсказка в end-picker'е («сохранение включит расписание»).
  2. Не авто-включать после явного выключения — усложняет ментальную модель.

---

### [CONCERN-24] `ScheduleCallbackHandler` зависит от `TelegramUserService` только ради `getUserZone`
TEXT: Handler позиционируется как «чистый dispatch», но тянет user-сервис ради материализации зоны; qwen предлагает поднять материализацию в flow.
SOURCES: alibaba/qwen (Concern 5)
STATUS: NEW
OPTIONS:
  1. Оставить в handler'е (рекомендуется) — материализация зоны это бизнес-правило, ему место рядом с логикой веток `on`/`e:`; вынос в flow сделал бы правило невидимым для unit-тестов (см. CONCERN-3).
  2. Перенести в `ScheduleSettingsFlow` — handler чище, но правило уезжает в непокрытый слой.

---

### [CONCERN-25] Утверждение про проглатывание `CancellationException` — опровергнуто проверкой
TEXT: MiniMax (Critical 2) утверждает, что `isEnabled()/getWindow()/getZone()` проглатывают `CancellationException`. Проверено по плану (строки 664–665, 672–691 до-правок): `getRecordingSchedule()` явно делает `catch (e: CancellationException) { throw e }` до общего catch, а геттеры вообще не содержат catch-блоков (кроме точечного `DateTimeException` в `getZone`) — проглатывать отмену им нечем.
SOURCES: ollama/minimax (Critical 2)
STATUS: NEW
OPTIONS:
  1. Отклонить как false positive (рекомендуется) — зафиксировать в iter-файле с указанием строк, чтобы не всплыло в iter-2.
  2. Защитный rethrow в геттеры «на будущее» — мёртвый код.

---

### [SUGGESTION-1] Заменить placeholder `DetectionDelta(...)` в Task 4 точным литералом
TEXT: Тесты Task 4 содержат `DetectionDelta(/* same args as the existing new-objects test */)` — плейсхолдер-по-ссылке (qwen оценил Critical). Проверено по `NotificationDecisionServiceImplTest.kt:81`: литерал NEW_OBJECTS-теста — `DetectionDelta(1, 0, 0, listOf("car"))`.
SOURCES: claude (S3), alibaba/qwen (Critical 2), ollama/minimax (Critical 1 частично)
STATUS: NEW
OPTIONS:
  1. Вписать литерал `DetectionDelta(1, 0, 0, listOf("car"))` во все три плейсхолдера плана (рекомендуется).
  2. Оставить placeholder-by-reference — план содержит явную note (строка 842), но зачем.

---

### [SUGGESTION-2] i18n: `sched.line.off` дублирует перевод «OFF/ВЫКЛ»
TEXT: Ключ дублирует существующий `notifications.settings.state.off` — риск рассинхронизации переводов.
SOURCES: claude (S2)
STATUS: NEW
OPTIONS:
  1. Форматный ключ `⏰ Detection schedule: {0}` с подстановкой `state.off` (рекомендуется); учесть CONCERN-12.
  2. Оставить два ключа.

---

### [SUGGESTION-3] Текст таймаута manual-zone вводит в заблуждение
TEXT: «Откройте /notifications заново» — но клавиатура stateless, старое сообщение работает.
SOURCES: claude (S4)
STATUS: NEW
OPTIONS:
  1. Смягчить до «время ожидания истекло» (рекомендуется).
  2. Оставить.

---

### [SUGGESTION-4] `formatHour`: упростить и зафиксировать 24-часовой контракт
TEXT: `formatHour(hour).substringBefore(":")` → `"%02d".format(hour)`. Смежное (minimax): зафиксировать контракт «часы пикера всегда 00–23 zero-padded, локаль не применяется».
SOURCES: claude (S5), ollama/minimax (Concern 6)
STATUS: NEW
OPTIONS:
  1. `"%02d".format(hour)` + комментарий контракта (рекомендуется).
  2. Оставить как в плане.

---

### [SUGGESTION-5] Наблюдаемость подавлений: `OUT_OF_SCHEDULE` виден только на debug
TEXT: Вопрос «почему не пришло уведомление» (например, из-за неверной зоны) диагностируется только включением debug-логов; владелец не получает фидбэка о работе гейта.
SOURCES: claude (S6), ollama/minimax (Suggestion 13)
STATUS: NEW
OPTIONS:
  1. INFO-лог первого подавления после включения расписания (рекомендуется) — подтверждает работу гейта без спама.
  2. Счётчик (micrometer/строка в `/notifications`) — богаче, дороже; digest дизайн уже отверг.
  3. Оставить debug-only.

---

### [SUGGESTION-6] Дедуплицировать `editMessageText` + «message is not modified»
TEXT: `edit()` в `ScheduleSettingsFlow` повторяет try/catch из RERENDER-блока бота.
SOURCES: zai/glm (Suggestion 16)
STATUS: NEW
OPTIONS:
  1. Общий helper `editOrLogNotModified(msg, rendered)` (рекомендуется).
  2. Оставить дублирование (два места).

---

### [SUGGESTION-7] Вынести литерал `"nfs:"` в общую константу
TEXT: Проверено: в `NotificationsSettingsCallbackHandler` константы PREFIX нет, `"nfs:"` захардкожен в боте и handler'е; план вводит `ScheduleCallbackHandler.PREFIX` — заодно вынести и `"nfs:"`.
SOURCES: zai/glm (Suggestion 12)
STATUS: NEW
OPTIONS:
  1. Общая константа (рекомендуется).
  2. Оставить — отдельный мелкий рефакторинг вне scope.

---

### [SUGGESTION-8] Обновить `database.md`: три новых ключа `app_settings`
TEXT: Дизайн заявляет «database.md: no changes», но добавляются 3 ключа — по аналогии с `global_enabled` их стоит перечислить.
SOURCES: zai/glm (Suggestion 14)
STATUS: NEW
OPTIONS:
  1. Добавить ключи в `database.md` + поправить раздел Documentation спеки (рекомендуется).
  2. Ограничиться `telegram-notifications.md`.

---

### [SUGGESTION-9] Спека: «defaults to owner's `olson_code`» — такого DTO-поля нет
TEXT: У `TelegramUserDto` нет поля zone (только у entity). План корректно использует `getUserZone(chatId)` с UTC-fallback.
SOURCES: zai/glm (Suggestion 15)
STATUS: NEW
OPTIONS:
  1. Переформулировать: «defaults to owner's current timezone via `getUserZone` (UTC fallback)» (рекомендуется).
  2. Оставить (термин отсылает к колонке БД).

---

### [SUGGESTION-10] Кластер UX/док-пояснений семантики расписания
TEXT: (а) зона расписания независима от персональной зоны владельца — аннотация на zone-экране и/или в доке (glm; deepseek Q12 — сценарий переезда владельца); (б) event-time базис — «утренний бэклог доставит ночные события» неочевиден (minimax); (в) зона расписания — зона интерпретации окна, не зона камеры (minimax); (г) подсказка, что расписание действует только при включённом глобальном флаге (kimi).
SOURCES: zai/glm (Suggestion 17), deepseek (Q12), ollama/minimax (Concern 7, Suggestion 14), ollama/kimi (Suggestion 6)
STATUS: NEW
OPTIONS:
  1. Всё — в `telegram-notifications.md` (Task 10), в UI только короткая строка на zone-экране про независимость зоны (рекомендуется) — не раздувать тексты бота.
  2. Только документация, UI не трогать.
  3. Полный вариант с аннотациями в UI — дороже, больше i18n-ключей.

---

### [SUGGESTION-11] Дополнительные тесты по мелким пробелам покрытия
TEXT: Проверено по плану: (а) для crossing-окна `23:00–07:00` не покрыт `contains(00:00)` — сама полночь через wrap-ветку (есть 23:00, 23:30, 03:00, 07:00, 12:00); (б) нет теста «`home` из end-picker не сохраняет окно»; (в) codex предлагает facade-тест «при `OUT_OF_SCHEDULE` нет вызовов Telegram/AI».
SOURCES: ollama/minimax (Q7, Suggestion 16), codex (Suggestion 4 частично)
STATUS: NEW
OPTIONS:
  1. Добавить (а) и (б) в план (рекомендуется) — по строке в тест-листы Task 1 и Task 8; (в) опционально — подавление уже покрыто decision-тестами.
  2. Только (а) — midnight-точка самая содержательная.

---

### [SUGGESTION-12] Дополнить Out of Scope и rejected alternatives
TEXT: (а) cron-формат не рассматривался (minimax); (б) prefetch/параллельное чтение расписания с трекером отвергнуто — кэшированное чтение ~O(1), выигрыш нулевой (deepseek, minimax Q1); (в) `ZONE_PRESETS` — UI-список, не whitelist: `z:<olson>` принимает любую валидную IANA-зону сознательно (qwen Q4).
SOURCES: ollama/minimax (Suggestion 18, Q1), deepseek (Suggestion 9), alibaba/qwen (Q4)
STATUS: NEW
OPTIONS:
  1. По одному предложению на пункт в спеку (рекомендуется) — дешевле повторных вопросов в iter-2.
  2. Ничего не добавлять.

---

### [SUGGESTION-13] Группа мелких замечаний, рекомендуемых к отклонению (с обоснованиями)
TEXT: (а) leap seconds — `Instant` их не представляет (minimax Q10); (б) retry-семантика `rejectedEqualEnd` — callback data естественно различается, сам ревьюер: «низкий приоритет» (minimax Concern 9); (в) UX-фидбэк «Back из zone-экрана» — сам ревьюер: «не баг» (minimax Suggestion 15); (г) helper для `isOwner` — все места уже зовут один `userService.isOwner()` (minimax Suggestion 19); (д) NOT NULL `setting_value` — проверено: `VARCHAR(2048) NOT NULL` в 1.0.4.xml:40 (minimax Suggestion 20); (е) en-dash/UTF-8 — properties уже UTF-8 (qwen Suggestion 12); (ж) i18n `{0}/{1}` — сам ревьюер подтвердил OK (qwen Suggestion 11); (з) hot-path bottleneck — сам ревьюер снял (qwen Q3); (и) digest — уже в Out of Scope спеки (qwen Q2); (к) «commit без Confirm в end-picker» — спека уже явно фиксирует «End hour chosen → save window» (minimax Critical 5); (л) тест stale-`s:H` после idle — handler stateless по построению, тест тавтологичен (minimax Q9).
SOURCES: ollama/minimax (Q9, Q10, Concern 9, Suggestions 15/19/20, Critical 5), alibaba/qwen (Suggestions 11/12, Q2, Q3)
STATUS: NEW
OPTIONS:
  1. Отклонить всей группой с фиксацией обоснований в iter-файле (рекомендуется) — чтобы не всплыли в iter-2.
  2. Выборочно принять (д)/(к) как однострочные пометки в доках, остальное отклонить.

---

### [QUESTION-1] `TRACKER_ERROR` вне окна: подавление при неизвестном `newTracks` — намеренно?
TEXT: Текущий fail-open трекера = notify при `global on`; план сужает до `resolvedGlobalEnabled && scheduleAllows` (проверено, план ~906–915) — при ошибке трекера вне окна suppress, хотя новые объекты могли быть.
SOURCES: zai/glm (Q20)
STATUS: NEW
OPTIONS:
  1. Подтвердить намеренность (рекомендуется) — «a daytime event is never delivered» покрывает и этот случай: расписание — жёсткий гейт, независимый от трекера; добавить строку в спеку.
  2. Notify при tracker error независимо от расписания — дневной шум при каждом сбое трекера противоречит цели фичи.

---

### [QUESTION-2] Материализация зоны с UTC-fallback: приемлемо ли молчаливое UTC для владельца без `/timezone`?
TEXT: `getUserZone` возвращает UTC, если владелец не настраивал персональную зону — первый save окна выставит расписанию UTC, окно «00:00–07:00» будет означать «03:00–10:00 MSK».
SOURCES: alibaba/qwen (Q1)
STATUS: NEW
OPTIONS:
  1. Принять UTC-fallback (рекомендуется) — зона сразу видна в строке «(UTC)», меняется в два клика; owner реального деплоя `/timezone` уже настроил.
  2. Требовать явного выбора зоны при первом сохранении окна — честнее, но лишний шаг.

---

### [QUESTION-3] `NotificationScheduleService` без `@ConditionalOnProperty` telegram.enabled — намеренно?
TEXT: Сервис в `service`-модуле, decision-слой применяет расписание независимо от Telegram.
SOURCES: ollama/minimax (Q8)
STATUS: NEW
OPTIONS:
  1. Подтвердить: намеренно (рекомендуется) — `service`-модуль архитектурно не знает о telegram-свойствах; опционально строка в спеку.
  2. Повесить условие — архитектурно хуже.

---

### [QUESTION-4] `val chatId = current.chatId ?: return` в flow — молчаливый выход или warn?
TEXT: Owner всегда ACTIVE с chatId; null — «невозможное» состояние, молчаливый return скроет диагностику при нарушении инварианта.
SOURCES: ollama/minimax (Q3)
STATUS: NEW
OPTIONS:
  1. Warn-лог перед return (рекомендуется) — одна строка; requireNotNull с падением слишком жёстко для UI-пути.
  2. Оставить молчаливый return.

---

### [QUESTION-5] Тесты ассертят англоязычные строки («must differ») — использовать message-key?
TEXT: Смена формулировки уронит тест, привязанный к подстроке. Вопрос консистентности с существующими тестами проекта.
SOURCES: ollama/minimax (Q4)
STATUS: NEW
OPTIONS:
  1. Следовать существующей конвенции (рекомендуется) — существующие renderer-тесты ассертят рендеренный текст (en-локаль); падение при смене формулировки — осознанная цена читаемости.
  2. Ассертить через `MessageResolver.get(key)` — устойчивее, но тест перестаёт проверять фактический рендер.

---

### [QUESTION-6] Уточнить DST-формулировку в спеке (осенний повтор локального времени)
TEXT: Edge case 2 не проговаривает: весенний gap — локальные времена окна могут не существовать (окно короче); осенний overlap — повторившийся час маппится в два разных Instant, оба проверяются корректно (Java выбирает earlier offset).
SOURCES: alibaba/qwen (Q5)
STATUS: NEW
OPTIONS:
  1. Дополнить edge case 2 одним предложением (рекомендуется) — DST-тест в Task 1 уже есть.
  2. Оставить — текущая формулировка технически верна.

---

SUMMARY:
total: 50
new: 50
repeated: 0

Подсказка для классификации: кандидаты в auto-fix — CRITICAL-2 (подняли 5/7 ревьюеров, двухстрочный фикс), CRITICAL-3 (подтверждено эмпирически), CRITICAL-6, SUGGESTION-1 (литерал подтверждён), SUGGESTION-9; спорные с реальными развилками — CRITICAL-1, CRITICAL-4, CRITICAL-5, CONCERN-2, CONCERN-3, CONCERN-10; кандидаты в dismissed — CONCERN-25 (false positive с доказательствами) и группа SUGGESTION-13.
