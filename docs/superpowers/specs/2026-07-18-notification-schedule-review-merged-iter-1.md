# Merged Design Review — Iteration 1

Topic: notification-schedule. Design: `docs/superpowers/specs/2026-07-18-notification-schedule-design.md`, Plan: `docs/superpowers/plans/2026-07-18-notification-schedule.md`.

## claude (self-review, Fable)

### Critical Issues

**C1. Премиса «reads go through the settings cache» неверна для отсутствующих ключей — дефолтное состояние даёт DB-запрос на каждую запись под глобальным мьютексом.**
`AppSettingsServiceImpl.loadAndCache` (`modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/AppSettingsServiceImpl.kt:51`) не кэширует отрицательные результаты: для отсутствующего ключа каждый вызов проходит `cacheMutex.withLock` + `repository.findBySettingKey`. Существующие глобальные флаги этой проблемы не имеют только потому, что засеяны миграцией (`docker/liquibase/migration/1.0.4.xml:47-48`) и потому всегда присутствуют в БД. Дизайн же для расписания сознательно выбирает «no seeding — absent keys mean schedule disabled». Итог: в состоянии по умолчанию (расписание ни разу не настраивалось — это состояние большинства инсталляций навсегда) каждый `evaluate()` с детекциями выполняет `getRecordingSchedule()` → `isEnabled()` → cache miss → SELECT, сериализованный через единственный `cacheMutex`, общий для всех настроек. Обоснование дизайна «reads go through the settings cache, so no prefetch/interface change is needed» опирается на неверный факт — кэш начинает работать только после первой настройки расписания; штраф платят ровно те деплои, которые фичу не используют. Функционально всё корректно (fail-open прикрывает и ошибки), нагрузка в абсолюте невелика, но это молчаливая постоянная регрессия hot path, устранимая одной небольшой правкой. Варианты: (а) негативное кэширование сентинел-значением в `AppSettingsServiceImpl` (инвалидация в `set*` уже есть — самый системный вариант, чинит и будущие ключи); (б) засеять три ключа миграцией (`enabled='false'`) — консистентно с конвенцией проекта для `global_enabled`; (в) кэш собранного `NotificationSchedule` внутри `NotificationScheduleService` с инвалидацией в сеттерах. Решение нужно принять до имплементации и внести в план.

**C2. План реализует материализацию зоны только при сохранении окна — спека требует «on first enable/save».**
Спека (раздел Key UI decisions, «Zone materialization»): «on first enable/save, if the zone key is absent it is set from the owner's current `olson_code`». В плане (Task 8) материализация есть только в ветке `e:<S>:<E>`; ветка `on` при существующем окне и отсутствующей зоне просто делает `setEnabled(true)`. Получается достижимое состояние «enabled + window + zone absent»: UI показывает окно с зоной `?`, а `getRecordingSchedule()` fail-open'ится в `null` — расписание тихо не работает, дневные уведомления идут, warn-лог на каждой записи. Это ровно тот класс состояний («on but empty»), который дизайн декларирует как предотвращённый. Сценарий требует внешнего вмешательства (удалённая/испорченная строка зоны в БД при сохранившемся окне), но фикс тривиален — продублировать в ветке `on` тот же блок `if (scheduleService.getZone() == null) setZone(userService.getUserZone(ownerChatId))`. Self-review плана («Known intentional deviation: none») это расхождение не зафиксировал.

### Concerns

**N1. Спека и план расходятся по обработке невалидного ручного ввода зоны.**
Спека, edge case 6: «error message + **re-await**, cancel pattern as in `/timezone`». Проверка `TimezoneCommandHandler.kt:135-137` показывает: `/timezone` при `DateTimeException` шлёт ошибку и **выходит** (one-shot, без re-await). План следует `/timezone` (текст ошибки «Откройте экран пояса и попробуйте снова» подтверждает one-shot), т.е. спека внутренне противоречива: описанный ею референсный паттерн не делает re-await. Нужно решение автора — скорее всего поправить спеку, но зафиксировать явно; сейчас self-review утверждает отсутствие отклонений.

**N2. Stale end-picker + auto-enable нарушает заявленное «no toggle surprises».**
Дизайн (edge case 3) утверждает, что stale-клики безопасны: «rewrites the same value or opens a fresh screen». Но `nfs:g:sched:e:<S>:<E>` на устаревшей клавиатуре не только перезапишет окно (last-write-wins, принято в edge case 4), но и **включит** расписание, которое владелец успел явно выключить. Достижимо при двух параллельно открытых сообщениях `/notifications` (второй вызов команды создаёт новое сообщение, старое остаётся на экране end-picker'а). Вероятность мала (один владелец), но заявление в спеке сильнее реальности — либо принять осознанно и записать, либо не включать авто-enable для «чужого» сообщения (сложно, не стоит того).

**N3. 120-секундный waiter внутри обработчика `onDataCallbackQuery` — поведение при конкурентных кликах не подтверждено.**
`manualZoneInput` блокирует обработчик callback'а до 120 с. Если ktgbotapi обрабатывает апдейты триггера последовательно per-marker (для callback-запросов маркер по пользователю/чату), то следующие `nfs:`-клики владельца встанут в очередь за waiter'ом — включая их `bot.answer()` (`FrigateAnalyzerBot.kt:174`), т.е. спиннер на кнопках до 120 с, если владелец передумал и жмёт «Back» вместо ввода текста. Прецедент `/timezone` (waiter в `onCommand`) работает в проде, но это message-триггер с другим marker'ом — для callback-триггера поведение стоит проверить руками (клик `zman` → сразу клик другой кнопки). Обратная сторона: если обработка параллельна, двойной клик `zman` породит два конкурирующих waiter'а на один `chat.id`, и один текст обработается дважды (два `setZone`, два «saved»-сообщения) — идемпотентно, но неопрятно. Рекомендация: ручная проверка обоих сценариев на живом боте до мержа; при необходимости — in-flight-guard на chatId.

**N4. При выключенном расписании UI скрывает настроенное окно.**
`renderText` показывает окно только при `scheduleEnabled == true && scheduleWindow != null`; выключенное расписание — голое «⏰ Detection schedule: OFF». Владелец не видит, какое окно активируется кнопкой «Enable schedule» (auto-enable по кнопке `on` применяет невидимое ему значение). Дешёвый фикс — третий вариант строки: «OFF (00:00–07:00, Europe/Moscow)» при наличии сохранённого окна.

**N5. Инвариант порядка записи «enabled — последним» нигде не зафиксирован.**
В ветке `e:` порядок `setWindow → setZone → setEnabled(true)` корректен: конкурентный читатель видит либо старое `enabled=false`, либо полное состояние. Но ни спека, ни план, ни код-комментарий не называют это инвариантом — перестановка строк при будущем рефакторинге откроет окно «enabled без window/zone» (прикрытое fail-open'ом, но с warn-спамом и тихой неработой). Достаточно одного комментария в `ScheduleCallbackHandler`.

**N6. Инвариант `start != end` не защищён в типе `ScheduleWindow`.**
Его отклоняет только `parse`; `ofHours(5, 5)` и прямой конструктор создают окно, у которого `contains` вырождается в «всегда true» (`time >= start || time < end`). Все текущие call sites защищены проверкой в callback handler'е, но инвариант держится на дисциплине вызывающих. Надёжнее `init { require(start != end) }` (парсер и так возвращает null через явную проверку; `ofHours` вызывается после guard'а).

**N7. Единственная нетривиальная непокрытая логика — waiter-ветка `ScheduleSettingsFlow`.**
План честно декларирует «no new unit tests» для Task 9 как I/O-glue, и это консистентно с непокрытыми `/timezone` и nfs-блоком бота. Но timeout/`/cancel`/invalid-ветки manual zone — реальная логика, проверяемая только руками. Принять осознанно (и включить в ручной чек-лист перед PR: zman → валидная зона, невалидная, `/cancel`, таймаут).

**N8. Валидация ручного ввода зоны мягче, чем в `/timezone`.**
`/timezone` до `ZoneId.of` требует `input.contains('/')` (отвергает «UTC»), новый флоу — нет (принимает «UTC», «GMT+3», offset'ы). Новое поведение объективно лучше, но два диалога «того же паттерна» валидируют по-разному. Либо принять расхождение (и позже ослабить `/timezone`), либо скопировать проверку — главное, решить сознательно.

### Suggestions

**S1.** Для C1 предпочесть негативное кэширование в `AppSettingsServiceImpl` (сентинел в `ConcurrentHashMap`, снятие в `setBoolean`/`setString`): чинит класс проблем целиком, не требует миграции, тестируется парой строк в существующем тесте.

**S2.** `notifications.settings.sched.line.off=⏰ Detection schedule: OFF` дублирует перевод «OFF/ВЫКЛ», уже существующий как `notifications.settings.state.off`. Сделать ключ форматным (`⏰ Detection schedule: {0}`) и подставлять `state.off` — меньше рассинхронизации переводов.

**S3.** Task 4: плейсхолдер «copy the DetectionDelta(...) from the existing test» — лишняя косвенность: конструктор четырёхаргументный и стабильный, в существующих тестах это литерал `DetectionDelta(1, 0, 0, listOf("car"))`. Вписать литерал прямо в план — меньше свободы для агента-исполнителя.

**S4.** Текст таймаута «Откройте /notifications заново» вводит в заблуждение: клавиатура stateless и старое сообщение диалога остаётся полностью рабочим. Смягчить до «время ожидания истекло» без указания повторно открывать диалог.

**S5.** `hourGrid`: `formatHour(hour).substringBefore(":")` → просто `"%02d".format(hour)`.

**S6.** Наблюдаемость: подавление `OUT_OF_SCHEDULE` логируется только на debug — вопрос владельца «почему ночью не пришло уведомление» (например, из-за неверной зоны расписания) диагностируется только включением debug-логов. Рассмотреть INFO-лог первого подавления после включения расписания или счётчик — дизайн отверг digest, но это про диагностику, не про доставку.

**S7.** В Task 2 `setString` логирует значение на INFO. Для нынешних ключей безопасно, но метод общий — будущий секретный ключ утечёт в лог. Логировать только ключ, или зафиксировать KDoc'ом «не для секретов».

### Questions

**Q1.** Известна ли семантика конкурентности ktgbotapi для `onDataCallbackQuery` в проекте (marker factory: последовательно per-user или параллельно)? От ответа зависит серьёзность N3 — очередь callback'ов за 120-секундным waiter'ом либо реентерабельные waiter'ы.

**Q2.** Почему для ключей расписания отвергнут сидинг миграцией, при том что оба существующих глобальных флага сидируются (1.0.4) и именно сидинг делает утверждение «reads go through the settings cache» истинным? Если ответ «не хотим миграцию ради фичи» — негативное кэширование (C1/S1) становится обязательным, а не желательным.

**Q3.** Какой вариант нормативен для невалидного ручного ввода зоны: one-shot (как реализует план и как фактически работает `/timezone`) или re-await (как написано в спеке)? Спеку или план нужно привести к согласию до имплементации.

**Q4.** Строка статуса при «enabled, но окно/зона испорчены в БД» показывает «OFF», тогда как кнопка-тумблер в том же сообщении предлагает «Disable schedule» (рендер кнопки идёт от сырого `scheduleEnabled=true`). Противоречие текста и кнопки в одном сообщении — принято ли это сознательно как отображение fail-open-семантики, или строку и кнопку стоит вести от одного и того же эффективного состояния?

---

Итог: архитектура здравая (fail-open как security-решение, event-time базис, stateless-пикеры, слоение поверх существующих флагов — всё обосновано и подтверждено кодом), план детален и почти во всех сверенных мной интерфейсах точен (конструкторы, фикстуры, каталог версий, POM `kotlin-test-junit5` с engine в runtime — Task 1 самодостаточен). Блокеры до имплементации: C1 (выбрать механизм кэширования/сидинга) и C2 (двухстрочный фикс ветки `on`); N1/Q3 требуют синхронизации документов.

---

## codex-executor (gpt-5.6-sol, reasoning max)

### Critical Issues

- **Отсутствующее расписание не кэшируется.** Дизайн запрещает seed/migration и оставляет ключ `enabled` отсутствующим ([design](/opt/github/zinin/frigate-analyzer/docs/superpowers/specs/2026-07-18-notification-schedule-design.md:37)), но текущий кэш сохраняет только найденные значения ([AppSettingsServiceImpl.kt](/opt/github/zinin/frigate-analyzer/modules/service/src/main/kotlin/ru/zinin/frigate/analyzer/service/impl/AppSettingsServiceImpl.kt:20)). План копирует это поведение для `getString` ([plan](/opt/github/zinin/frigate-analyzer/docs/superpowers/plans/2026-07-18-notification-schedule.md:355)). В результате до первой настройки каждый recording с детекциями будет выполнять запрос в PostgreSQL за отсутствующим `schedule.enabled`. Это противоречит обоснованию «reads go through cache». Нужен negative caching (`Missing` sentinel) либо seed `enabled=false`; тест должен проверять один repository-read для повторных обращений к отсутствующему ключу.

- **Парсер принимает некорректное время и нарушает fail-open.** `DateTimeFormatter.ofPattern("HH:mm")` использует `ResolverStyle.SMART`; `LocalTime.parse("24:00", formatter)` возвращает `00:00`. Поэтому, например, `24:00-07:00` станет активным окном, хотя повреждённое значение должно отключать расписание и пропускать уведомления. Код находится в [Task 1](/opt/github/zinin/frigate-analyzer/docs/superpowers/plans/2026-07-18-notification-schedule.md:231). Нужны STRICT formatter либо точная regex+range-проверка и тесты для `24:00`, пробелов, секунд и лишних разделителей.

- **`sched:on` не материализует отсутствующую/повреждённую zone.** Спецификация требует делать это при первом enable ([design](/opt/github/zinin/frigate-analyzer/docs/superpowers/specs/2026-07-18-notification-schedule-design.md:136)), но обработчик проверяет только window и сразу пишет `enabled=true` ([plan](/opt/github/zinin/frigate-analyzer/docs/superpowers/plans/2026-07-18-notification-schedule.md:1993)). Получится raw-состояние "ON", тогда как `getRecordingSchedule()` вернёт `null`; UI вдобавок покажет окно и `?` как активное расписание ([renderer plan](/opt/github/zinin/frigate-analyzer/docs/superpowers/plans/2026-07-18-notification-schedule.md:1415)). Перед enable необходимо получить/восстановить zone и проверить всю эффективную конфигурацию.

- **Сохранение окна не атомарно.** Дизайн утверждает, что UI-операция пишет один ключ ([design](/opt/github/zinin/frigate-analyzer/docs/superpowers/specs/2026-07-18-notification-schedule-design.md:48)), но plan последовательно выполняет `setWindow`, возможно `setZone`, затем `setEnabled` ([plan](/opt/github/zinin/frigate-analyzer/docs/superpowers/plans/2026-07-18-notification-schedule.md:2030)). При сбое активное окно может уже измениться, хотя UI сообщит об ошибке, либо останется частично настроенное состояние. Fail-open чтений этого не исправляет. Нужна атомарная операция уровня service/repository либо единый сериализованный ключ конфигурации.

- **Invalid manual zone не делает re-await.** Это явно требуется спецификацией ([design](/opt/github/zinin/frigate-analyzer/docs/superpowers/specs/2026-07-18-notification-schedule-design.md:169)), но реализация ждёт ровно одно сообщение и после `DateTimeException` завершает flow ([plan](/opt/github/zinin/frigate-analyzer/docs/superpowers/plans/2026-07-18-notification-schedule.md:2198)). Следует повторять ожидание до корректного значения, `/cancel` или общего 120-секундного deadline.

### Concerns

- `ScheduleWindow` не защищает собственные инварианты: публичный конструктор и `ofHours()` допускают `start == end`; `contains()` тогда фактически разрешает весь день. Также `LocalTime` с секундами принимается, но `storageFormat()` молча их отбрасывает ([plan](/opt/github/zinin/frigate-analyzer/docs/superpowers/plans/2026-07-18-notification-schedule.md:213)).

- Повреждённые window/zone будут парситься и логироваться на каждом recording ([service plan](/opt/github/zinin/frigate-analyzer/docs/superpowers/plans/2026-07-18-notification-schedule.md:647)). При высокой частоте детекций это создаст log storm. Нужен parsed-state cache или предупреждение только при изменении ошибочного raw-значения.

- Task 9 сознательно оставлен без тестов ([plan](/opt/github/zinin/frigate-analyzer/docs/superpowers/plans/2026-07-18-notification-schedule.md:2092)), хотя именно там уже находится расхождение с re-await. Не покрыты timeout, cancel, повторный invalid input, двойной `zman` и фактическая маршрутизация callback.

- Есть несколько необъяснённых отклонений от design: вместо одной строки из трёх кнопок план добавляет две строки и увеличивает клавиатуру с 5 до 7 рядов ([design](/opt/github/zinin/frigate-analyzer/docs/superpowers/specs/2026-07-18-notification-schedule-design.md:96), [plan](/opt/github/zinin/frigate-analyzer/docs/superpowers/plans/2026-07-18-notification-schedule.md:1251)); callback перехватывается непосредственно в `FrigateAnalyzerBot`, хотя design поручает dispatch существующему `NotificationsSettingsCallbackHandler`. При этом self-review заявляет, что отклонений нет.

- План пропускает обязательный repository checkpoint: после реализации должен запускаться code-reviewer и только затем build-runner ([CLAUDE.md](/opt/github/zinin/frigate-analyzer/CLAUDE.md:11)); Task 10 сразу запускает build ([plan](/opt/github/zinin/frigate-analyzer/docs/superpowers/plans/2026-07-18-notification-schedule.md:2341)).

- Локальный бессрочный cache не поддерживает несколько экземпляров приложения: запись через один процесс не инвалидирует значения в остальных. Это уже существующее ограничение глобальных флагов, но для расписания его следует явно зафиксировать.

### Suggestions

- Предпочтительный вариант хранения — один versioned value, содержащий `enabled/window/zone`. Он даёт атомарный snapshot, исключает torn reads и упрощает эволюцию формата. Если три ключа сохраняются, добавить транзакционную `configureAndEnable(window, defaultZone, updatedBy)`.

- Ввести тип состояния конфигурации: `Disabled`, `Enabled(schedule)`, `Invalid(reason)`. Decision API по-прежнему может возвращать `null` fail-open, а UI сможет честно показать повреждённую конфигурацию вместо противоречивых "ON (?)"/"Disable".

- Сделать `ScheduleWindow` валидируемым value object: строгий minute-precision, `start != end`, единственная фабрика для parse/create.

- Расширить тесты: negative cache, `24:00`, исключения при чтении window/zone, cancellation propagation, enabled+missing-zone, частичный write failure, invalid-zone re-await и facade-тест отсутствия Telegram/AI вызовов при `OUT_OF_SCHEDULE`.

- Вынести список zone presets и правила валидации в общий компонент с `/timezone`; сейчас список копируется и со временем может разойтись.

### Questions

- Текущая конфигурация должна применяться ретроактивно ко всему backlog? План использует event time, но не хранит историю расписания, поэтому старое событие проверяется по сегодняшнему window/zone.

- Разрешены ли fixed-offset идентификаторы (`+03:00`, `GMT+03:00`)? Новый flow принимает любой `ZoneId`, тогда как существующий `/timezone` запрещает offset-based значения ([TelegramUserServiceImpl.kt](/opt/github/zinin/frigate-analyzer/modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/service/impl/TelegramUserServiceImpl.kt:133)).

- Гарантирован ли single-instance deployment? Если нет, нужна стратегия distributed invalidation/TTL для `app_settings`.

- Как UI должен отображать `enabled=true` при отсутствующем или повреждённом window/zone: "OFF", "invalid/fail-open" или автоматически восстанавливать конфигурацию?

---

## ext-claude-executor (zai/glm — GLM 5.2)

# Ревью дизайна и плана notification-schedule

Ревью опирается на верификацию реального кода (включая факты, расходящиеся с допущениями документов).

## Critical Issues

**1. Приоритет причины `OUT_OF_SCHEDULE` над `ALL_REPEATED` — semantic-проблема + отсутствие тестов.**
Реальный порядок веток в `NotificationDecisionServiceImpl.evaluate()` (`NotificationDecisionServiceImpl.kt:36-56`): `all-zero delta → NO_VALID_DETECTIONS` → `!resolvedGlobalEnabled → GLOBAL_OFF` → `newTracksCount>0 → NEW_OBJECTS` → `else → ALL_REPEATED`; catch → `TRACKER_ERROR`. План (Task 4 Step 4) вставляет `!scheduleAllows` **между** `!resolvedGlobalEnabled` и `delta.newTracksCount > 0`, т.е. **до** проверки `newTracksCount`. Следствие: запись вне окна с повторяющимися объектами (`newTracksCount==0`, но есть matched/stale) получит `reason=OUT_OF_SCHEDULE`, а не `ALL_REPEATED`. На `shouldNotify` (false в обоих случаях) это не влияет — доставка не ломается, **но**: метрика/лог «сколько подавлено расписанием» завышается (считает повторы), а `OUT_OF_SCHEDULE` семантически должно значить «иначе был бы доставлен». Тесты Task 4 покрывают только `GLOBAL_OFF` vs `OUT_OF_SCHEDULE` и не покрывают `OUT_OF_SCHEDULE` vs `ALL_REPEATED`/`NO_VALID_DETECTIONS`. Решение нужно зафиксировать до имплементации: ставить ветку так, чтобы она срабатывала только когда иначе сработал бы `NEW_OBJECTS` — `delta.newTracksCount > 0 && !scheduleAllows → OUT_OF_SCHEDULE`, и добавить соответствующие тесты приоритета.

**2. Несогласованная материализация зоны: `on` не материализует зону, `e:` — материализует.**
В `ScheduleCallbackHandler` (Task 8) действие `e:<S>:<E>` при `getZone()==null` вызывает `setZone(getUserZone(ownerChatId))`, а действие `on` при `getWindow()!=null` вызывает только `setEnabled(true)` — без зоны. Через текущий UI негласный инвариант «window ⟹ zone» держится (окно ставится исключительно через picker-save, который зону материализует), поэтому сейчас состояние `enabled + window + zone=null` недостижимо. Но это **хрупкий негласный контракт**: любой будущий путь (миграция БД, альтернативный UI, прямой доступ) его нарушит, и тогда `getRecordingSchedule()` fail-open вернёт `null` — расписание **тихо** перестанет работать при включённом тумблере, а в статусной строке владелец увидит «(?)». Контракт должен быть явным и защищённым: либо `on` материализует зону наравне с `e:`, либо `getRecordingSchedule()` берёт зону-по-умолчанию при `null`.

## Concerns

**3. `setString` игнорирует возвращаемое значение `upsert` (`Long`, rows affected).**
`AppSettingRepository.upsert` возвращает `Long` (rows affected). План Task 2 в `setString` пишет `repository.upsert(...)` без проверки возврата. При `0L` (маловероятно для `ON CONFLICT`, но возможно) `setString` тихо «успеет», кеш инвалидируется, но значение в БД не сохранится — следующий read вернёт старое. Существующий `setBoolean` ведёт себя так же (консистентно), но для schedule-настроек, которые владелец меняет руками, тихий сбой = «настройка не применилась без ошибки». Хотя бы `warn`-лог на `0L`.

**4. `setString` логирует значение ключа.**
`logger.info { "AppSettings: '$key' set to '$value'..." }`. Для window/zone это безобидно, но `AppSettingsService` — общий сервис, и любой будущий строковый ключ (токен, секрет) утечёт в лог. Логировать только ключ, либо параметризовать уровень для значения.

**5. Три независимых чтения в `getRecordingSchedule()` расширяют окно гонки.**
`isEnabled()` + `getWindow()` + `getZone()` — три отдельных чтения из кеша, не атомарных между собой. Это расширяет Edge Case №5 («Setting changed between decision and send»): при изменении настроек в момент потока можно прочитать смесь старых/новых значений. В большинстве вариантов несогласованное состояние либо валидное окно (доставит), либо невалидное (`parse → null → fail-open`, доставит) — уведомление не теряется, но может уйти вне окна. Согласуется с fail-open, но окно гонки шире, чем у одного флага; стоит явно задокументировать как принятый trade-off, а не оставлять по умолчанию.

**6. `ScheduleSettingsFlow` реализуется без unit-тестов (Task 9).**
План явно отказывается от тестов («deliverable gate is compilation + module suite green»). При этом `manualZoneInput` содержит нетривиальную логику: `withTimeoutOrNull(120s)`, проверку `/cancel`, rethrow `CancellationException`, **одиночную** попытку (при невалидной зоне — возврат, а не retry-loop). Паттерн зеркалит `/timezone`, но баги (двойной timeout-месседж, проглоченный `CancellationException`) реальны. Нужен хотя бы smoke-тест на mapping `Outcome → screen` и на manual-ветку.

**7. Рефакторинг `NotificationsViewStateFactory` — инвазивный, с регрессионным риском.**
План (Task 5) выносит inline-сборку `NotificationsViewState` из `NotificationsCommandHandler` и из RERENDER-блока `FrigateAnalyzerBot` в новую фабрику, затрагивая рабочий `/notifications`. Фабрика обоснованна (после schedule flow точек сборки три), но обязана **точно** сохранить null-discipline (новые и существующие `*Enabled` поля — non-null при `isOwner`). Тесты обновляются, но риск регрессии существующего диалога есть. Снижение риска: вынести фабрику отдельным PR до schedule-фичи (тогда регрессию `/notifications` ловят изолированно).

**8. Auto-enable при сохранении окна — нельзя «настроить, но держать выключенным».**
Любой save окна через picker включает расписание. Сценарий «настроить на будущее, не включая сейчас» невозможен. Сознательное UX-решение, но стоит отразить в UI (подсказка), иначе удивит.

**9. Fail-open расписания ≠ fail-семантика глобального флага — асимметрия неочевидна и потенциально тиха.**
Global flag: сбой settings при prefetch → exception → запись **не** сохраняется → retryable (backlog растёт, сбой **заметен**). Schedule: сбой settings внутри `evaluate()` (после save) → fail-open `null` → уведомление уходит (extra). Для одного и того же класса ошибок (settings unreadable) — **разные** направления. Обосновано (security: шум > пропуск; иначе flag остановил бы весь pipeline), но будущий разработчик может случайно «выровнять» их. Особый риск: schedule fail-open **тих** — при долговременном сбое settings владелец получает лишние дневные уведомления и замечает не сразу (в отличие от global flag, где обработки просто останавливаются). Нужен заметный комментарий в `evaluate()` и запись в `telegram-notifications.md`.

**10. Non-owner слеп к причине подавления.**
Schedule — OWNER-only, но решение принимается в `evaluate()` **до** per-user фильтра, т.е. подавляет детекции всех пользователей. Non-owner с включёнными детекциями перестанет получать дневные уведомления и не увидит почему (status line и кнопки schedule только для owner) — «магическое» пропадание. Приемлемо по дизайну, но стоит либо явно задокументировать, либо показывать non-owner read-only строку «активно расписание 00:00–07:00».

**11. `ScheduleWindow.ofHours` не валидирует `start == end`.**
`ofHours(5,5)` → `ScheduleWindow(05:00, 05:00)`, и `contains()` ломается (при `start == end` ветка `start < end` ложна, срабатывает `time >= start || time < end` → почти всегда true). UI-handler проверяет `start != end` до вызова, но `ofHours` public — прямой вызов даст невалидный объект без ошибки. Нужен `require(start != end)` в `ofHours` или в `init` data class (заодно это сделает инвариант типобезопасным, а не только UI-проверкой).

## Suggestions

**12. Ввести общую константу `PREFIX`.** В `NotificationsSettingsCallbackHandler` константы `PREFIX` нет; литерал `"nfs:"` захардкожен в трёх местах (`FrigateAnalyzerBot.kt:170`, `NotificationsSettingsCallbackHandler.kt:35`, `:39`). План правильно вводит `ScheduleCallbackHandler.PREFIX = "nfs:g:sched:"`, но стоит заодно вынести `"nfs:"` в общую константу — устранить дублирование и сделать `initialFilter`/диспетчер консистентными с обработчиком.

**13. Парсер window — усилить strictness и тесты.** Использовать `ResolverStyle.STRICT` явно и добавить тесты на невалидные минуты (`"00:60"`) и на `"24:00"` (должен reject — hour-of-day 0–23); сейчас таких тестов нет. Fixed-width `HH`/`mm` корректно отбрасывает single-digit, но minutes не покрыты.

**14. Обновить `database.md`.** Дизайн заявляет «database.md: no changes», но добавляются 3 ключа в `app_settings`. Если правило описывает `app_settings` — добавить ключи (по аналогии с `global_enabled`), а не ограничиваться `telegram-notifications.md`.

**15. Поправить формулировку «defaults to owner's `olson_code`» в спеке.** У `TelegramUserDto` **нет** поля `olsonCode`/`zone` (только у `TelegramUserEntity`). План корректно использует `getUserZone(chatId): ZoneId` (с UTC-fallback). Спеку стоит переформулировать в «defaults to owner's current timezone via `getUserZone` (UTC fallback)», чтобы не вводить в заблуждение относительно несуществующего DTO-поля.

**16. Дедуплицировать `editMessageText` + «message is not modified» handling.** `edit()` в `ScheduleSettingsFlow` (Task 9) повторяет try/catch из RERENDER-блока бота. Общий helper `editOrLogNotModified(msg, rendered)` уберёт дублирование и унифицирует поведение.

**17. Подтвердить в UI, что zone расписания ≠ персональная зона owner.** Дизайн правильно хранит zone явно и отвязывает от текущей зоны owner (детерминированность). Но это неочевидно пользователю; стоит короткой аннотацией в zone-экране обозначить, что это независимая настройка.

## Questions

**18.** Приоритет причин: `OUT_OF_SCHEDULE` должен иметь приоритет над `ALL_REPEATED`/`NO_VALID_DETECTIONS`, или срабатывать только когда иначе сработал бы `NEW_OBJECTS`? (Critical #1) — это меняет observability/аудит и положение ветки в `when`.

**19.** Контракт материализации зоны: согласован ли инвариант «window ⟹ zone», и должен ли `on` материализовать зону наравне с `e:`? (Critical #2)

**20.** `TRACKER_ERROR` при вне-окна: текущий fail-open трекера = notify при `global on`; план сужает до `resolvedGlobalEnabled && scheduleAllows`, т.е. при вне-окна suppress даже при ошибке трекера (где `newTracks` неизвестен). Это намеренное сужение подтверждено?

**21.** Non-owner видимость: показывать ли non-owner read-only инфо об активном расписании, чтобы отсутствие дневных уведомлений не было «магическим»? (Concern #10)

**22.** Возвращаемое `upsert` (`Long`): игнорировать (как `setBoolean`) или warn-логировать `0L` для schedule-настроек? (Concern #3)

**23.** Тестирование `ScheduleSettingsFlow`: добавить ли минимальные unit-тесты на manual-zone waiter и mapping `Outcome → screen`, или довериться «компиляция + module suite green»? (Concern #6)

**24.** Рефакторинг фабрики: выносить `NotificationsViewStateFactory` отдельным PR до schedule-фичы, чтобы регрессию `/notifications` ловить изолированно, или делать в одном PR? (Concern #7)

---

## ext-claude-executor (alibaba/qwen — Qwen 3.7 Plus)

Провёл критическое ревью обоих документов и сверил их с существующим кодом (`NotificationDecisionServiceImpl`, `AppSettingsServiceImpl`, `NotificationsMessageRenderer`, `FrigateAnalyzerBot`, `TelegramUserService`, `RecordingProcessingFacade`, `RecordingDto`, `AppSettingKeys`).

Общая оценка: план — высокого качества, с продуманной архитектурой, TDD-циклами, корректной fail-open семантикой и хорошим покрытием edge cases. Но есть несколько проблем — от серьёзных до стилистических.

---

### Critical Issues

**1. UI/реальность несовместимость при `enabled=true, zone=null/corrupt`.**

Когда `scheduleService.isEnabled()` возвращает `true`, но `getZone()` возвращает `null` (зона не настроена или повреждена), получаем:
- `NotificationsViewStateFactory.build()` выставляет `scheduleEnabled = true`, `scheduleZone = null`.
- `NotificationsMessageRenderer` рендерит `⏰ Detection schedule: 00:00–07:00 (?)` — выглядит как «включено».
- `NotificationDecisionServiceImpl.getRecordingSchedule()` возвращает `null` (fail-open) → расписание реально **не работает**, уведомления идут всегда.

Владелец видит «включено», а по факту — «выключено из-за поломки». Это противоречит обещанной «fail-open для уведомлений, а не для настроек»: тут как раз настройки создают иллюзию контроля.

**Как исправить (вариант наименьшего сопротивления):** в `ScheduleCallbackHandler.dispatch("on")` тоже материализовать зону, если её нет (сейчас это делается только в `dispatch("nfs:g:sched:e:S:E")`). Или в `NotificationsMessageRenderer` рендерить отдельный статус «configured but broken» при `scheduleEnabled=true && scheduleZone==null` — например, `⏰ Schedule: broken configuration, notifications flowing`.

**2. Placeholder в Task 4 step 1 — реальный риск для имплементера.**

```kotlin
coEvery { tracker.evaluate(recording, any()) } returns
    DetectionDelta(/* same args as the existing new-objects test: newTracksCount = 1, etc. */)
```

Конструктор `DetectionDelta` в плане не приведён, имплементер должен сам найти существующий тест в `NotificationDecisionServiceImplTest.kt`, скопировать literal и подставить. Это классический источник «почему тест не компилируется» при передаче задачи между сессиями/агентами.

**Как исправить:** дать точный путь+строку (напр. `NotificationDecisionServiceImplTest.kt:NN`) ИЛИ приложить `import`-готовый helper (`private fun newObjectDelta() = DetectionDelta(...)`) ИЛИ привести полный literal прямо в плане. Plan-документ не должен требовать от читателя «угадать».

---

### Concerns

**3. `NotificationDecisionServiceImpl.evaluate()` — `NO_VALID_DETECTIONS` приоритетнее `OUT_OF_SCHEDULE`.**

Текущий порядок веток `when`:
```
NO_VALID_DETECTIONS → GLOBAL_OFF → (план вставляет сюда OUT_OF_SCHEDULE) → NEW_OBJECTS → ALL_REPEATED
```

Если recording одновременно (а) вне окна расписания и (б) имеет `new=matched=stale=0` (все detection отфильтрованы по `confidenceFloor`), в лог уйдёт `NO_VALID_DETECTIONS`, а не `OUT_OF_SCHEDULE`. Spec §"Effective delivery condition" этого не оговаривает. Для пользователя разницы нет (оба `shouldNotify=false`), но для отладки/метрик — потеряна информация о том, что сработал gate расписания.

**Рекомендация:** не менять — это следствие существующей оптимизации «не смотрим gates, если трекер вернул пустой delta». Но стоит явно зафиксировать это в spec как "precedence: NO_VALID_DETECTIONS > GLOBAL_OFF > OUT_OF_SCHEDULE > NEW_OBJECTS > ALL_REPEATED > TRACKER_ERROR" — иначе имплементер будущей итерации может поменять порядок и сломать наблюдаемость.

**4. `ScheduleSettingsFlow` не покрыт тестами вообще.**

План открыто признаёт: «No new unit tests — the deliverable gate is compilation». Но `manualZoneInput()` содержит: `withTimeoutOrNull(120_000L)`, `waitTextMessage().filter{...}.first()`, `/cancel`, `DateTimeException` → retry, timeout → message. Это не чистый glue — там 4 ветки поведения.

Существующий `/timezone` handler, на который план ссылается как на образец, тоже не тестирован, но это не оправдание — это известная дыра. Для новой фичи, которая добавляет 4 i18n-ключа и 5 веток — стоит хотя бы `ScheduleSettingsFlowTest` c mock waiter-ом (или `FakeBehaviourContext`). Если это действительно дорого — задокументировать в плане как TODO и создать Jira-тикет.

**5. `ScheduleCallbackHandler` зависит от `TelegramUserService` только ради `getUserZone`.**

Handler — это «чистый dispatch» (так его называет план). Но `userService.getUserZone(ownerChatId)` — это чтение из user-specific settings, не из schedule settings. Это нарушает принцип «handler знает только про свой домен».

**Альтернатива:** вынести материализацию зоны наружу — `ScheduleSettingsFlow` уже имеет доступ к `userService`. Тогда `ScheduleCallbackHandler` станет ещё чище и тестируемее. Текущая схема работает, но architecturally — лишний coupling.

**6. `NotificationsViewStateFactory.build()` — три независимых вызова к settings.**

Для каждого рендера `/notifications` выполняется: `isEnabled()`, `getWindow()`, `getZone()` — каждый идёт через `AppSettingsService` в `ConcurrentHashMap`. Быстро, но при каждом открытии диалога — 9 map-lookup'ов (3 на schedule + 2 на global flags в команде/боте + старые чтения).

**Не критично**, но можно объединить в один `getScheduleState(): ScheduleState?` метод, который одним чтением вернёт все три поля. Это также закроет micro-race, когда между вызовами `isEnabled()` и `getWindow()` callback успевает поменять окно.

**7. Zone presets hardcoded в `ScheduleKeyboardRenderer.ZONE_PRESETS`.**

Те же 8 городов, что и в `/timezone` — это **копипаста данных**. Если в `/timezone` добавят город — нужно помнить, что надо добавить и здесь. И наоборот.

**Как улучшить:** вынести `ZONE_PRESETS` в общий ресурс (например, в `TelegramUserService.ZONE_PRESETS` или в `messages_en.properties`-список), и пусть оба renderer'а читают одно место.

**8. `ownerState(false)` test (Task 6 step 1) маскирует реальный сценарий.**

```kotlin
fun `owner text shows schedule off when disabled`() {
    val rendered = renderer.render(ownerState(false, "00:00–07:00", "Europe/Moscow"))
    assertTrue(rendered.text.contains("OFF"), ...)
}
```

Тут `scheduleEnabled=false`, но `scheduleWindow` и `scheduleZone` заданы. Это — обычный кейс «выключили, но настройки остались». OK.

НО! Нет теста для `scheduleEnabled=true, scheduleWindow=null, scheduleZone=null` — а это как раз кейс из finding #1 (окно задано, зона потеряна). Стоит добавить.

---

### Suggestions

**9. `ScheduleWindow.parse` — хрупкий split по `-`.**

```kotlin
val parts = raw.split("-")
if (parts.size != 2) return null
```

Это работает для валидных `HH:mm-HH:mm`, но ломается при `00:00-` (trailing empty отбрасывается — size=1, OK) и при `-07:00` (size=2, но `parts[0]=""`, parse бросает — OK, но через exception).

Более читаемо и надёжно — `raw.substringBefore('-')` / `raw.substringAfter('-')` + проверка, что строка действительно содержит ровно один `-`. Либо regex `^\d{2}:\d{2}-\d{2}:\d{2}$`. Текущая реализация функционально корректна, но прохождение через exception для валидации — это и медленно, и мутит логи (если включён exception-logging).

**10. `NotificationScheduleServiceImpl.getRecordingSchedule()` — catch-all с `warn`.**

```kotlin
} catch (e: Exception) {
    logger.warn(e) { "Failed to read notification schedule; treating as disabled (fail-open)" }
    null
}
```

Это правильно с точки зрения semantics. Но если `settings.getBoolean()` кинет exception — `isEnabled()` пробросит его наружу, и catch поймает. При этом `loadAndCache` в `AppSettingsServiceImpl` не бросает (он возвращает null при отсутствии ключа). Так что реальный сценарий catch — только `DateTimeException` от `ZoneId.of` или bug в коде.

**Предложение:** логировать на `warn` только если это не `DateTimeException` (который уже логируется внутри `getZone()`). Иначе — дубли.

**11. i18n-ключ `notifications.settings.sched.line.on.format` использует `{0}`/`{1}` — но `MessageResolver` может требовать явной передачи `MessageFormat`.**

Убедитесь, что `msg.get(key, lang, arg1, arg2)` корректно обрабатывает `{1}` как второй аргумент. Существующий `notifications.settings.line.owner.format` с `{0}...{4}` уже работает — значит, инфраструктура поддерживает. OK.

**12. `ScheduleWindow.storageFormat()`/`displayFormat()` — разные разделители.**

Storage: hyphen `-`, display: en dash `–`. План это проговаривает, но в `storageFormat()` используется literal `–` в `displayFormat()` — нужно убедиться, что файл сохранён в UTF-8 (ktgbotapi / properties-файлы в проекте уже UTF-8, но стоит проверить).

**13. Нет rollback-плана.**

Если фича выкачена, но в production обнаруживается баг (например, timezone материализуется некорректно) — как её откатить? Варианты: (a) env-var feature flag, (b) оператор удаляет 3 ключа из `app_settings`. План не описывает ни того, ни другого.

**Предложение:** добавить в documentation section (Task 10) пункт «disable at runtime: DELETE FROM app_settings WHERE setting_key LIKE 'notifications.recording.schedule.%'» — это мгновенный откат без деплоя.

---

### Questions

**Q1.** `TelegramUserService.getUserZone(chatId)` возвращает `ZoneId` (не `ZoneId?`). Что он возвращает, если пользователь никогда не настраивал timezone? Если это `UTC` — то материализация зоны при первом сохранении окна выставит `UTC` даже для владельца в Москве, и UI покажет "⏰ Detection schedule: 00:00–07:00 (UTC)" — неправильно по смыслу. Стоит ли требовать явного ввода зоны при первом enable, а не материализовать дефолт?

**Q2.** Почему spec отверг «тихую доставку» (`disable_notification=true`) для вне-оконных детекций, но не рассмотрел «batched delivery» — т.е. накопление suppressed detections и отправку дайджеста при следующем входе в окно? Аргумент «дневные детекции — чистый шум» убедителен, но для некоторых сценариев (например, владелец в отпуске) дайджест был бы полезен. Стоит зафиксировать, что это осознанный отказ, а не упущение.

**Q3.** `evaluate()` signature не меняется (по плану), но `getRecordingSchedule()` читается на каждый recording. Для горячего пути pipeline — не будет ли это bottleneck при большом RPS? (Я проверил — `ConcurrentHashMap.get` lock-free, так что, скорее всего, нет — но стоит упомянуть в плане, что overhead — O(1) map lookup на recording.)

**Q4.** Планируется ли валидация при `setZone(zone)`, что zone действительно IANA-валидна? Текущая реализация `NotificationScheduleServiceImpl.setZone()` просто пишет `zone.id` в settings — но `ZoneId.of(...)` уже валидирует на уровне конструктора. ОК, это covered. Но стоит ли в `ScheduleCallbackHandler.dispatch("nfs:g:sched:z:<olson>")` игнорировать пресеты, которых нет в `ZONE_PRESETS`, но которые валидны (например, `Europe/London`)? Текущий план принимает любой валидный IANA. Это правильно, но нужно понимать, что `ZONE_PRESETS` — это только UI-spaced список, не whitelist.

**Q5.** Что происходит, если recording имеет `recordTimestamp` ровно на границе окна? `[start, end)` — start inclusive, end exclusive. Тесты это покрывают (`plain window includes start boundary`, `plain window excludes end boundary`). Но на DST-переходе: если окно `00:00-07:00`, и в день перевода стрелок 02:00 → 03:00, то 02:30 локально не существует. `Instant.atZone(zone).toLocalTime()` для такого instant вернёт 03:30 — OK. А для «повторного» 02:30 (которое бывает при осеннем переходе) — какое из двух 02:30 выбирается? Java выбирает «earlier offset», так что оба instant'а маппятся в разные `LocalTime`. Это корректно, но стоит упомянуть в spec — finding #2 из edge cases говорит об этом недостаточно точно.

---

### Итог

Архитектура и TDD-структура плана — на высоком уровне. Критических блокеров два: **(1) UI/реальность несовместимость** при misconfigured schedule и **(2) placeholder DetectionDelta** в тестах. Оба фиксятся за 10–15 минут в документации.

Остальное — concerns и suggestions уровня «сделать план ещё лучше», но даже без них имплементация будет корректной.

---

## ext-claude-executor (deepseek/v4-pro — DeepSeek V4 Pro)

# Рецензия: Notification Schedule — Design & Implementation Plan

## Critical Issues

### 1. Task 9 (`ScheduleSettingsFlow`) не имеет ни одного теста

**Это самый сложный компонент во всём плане** — Telegram I/O glue, которое управляет:
- Ручным вводом таймзоны через waiter (concurrent UI flow с 120s таймаутом)
- Редактированием сообщений с обработкой ошибок (`message is not modified`, network failures)
- Маршрутизацией 8+ исходов `ScheduleCallbackHandler.Outcome` в реальные экраны

При этом план прямо заявляет:

> No new unit tests — the deliverable gate is compilation plus the full telegram module test suite staying green.

Это неприемлемо для компонента такой сложности. Существующие тесты (`NotificationsCommandHandlerTest`, `NotificationsMessageRendererTest`) не покрывают flow переходов между экранами, обработку таймаута waiter'а, или fallback при ошибках `editMessageText`. Этот код идентичен по природе нынешнему untested `nfs:`-блоку в `FrigateAnalyzerBot` — и план добавляет ещё один слой untested UI-логики поверх уже untested.

**Последствия:** Баги в screen transitions (например, `RenderEndPicker` вместо `RenderMain` при edge case) не будут пойманы до ручного тестирования. Ошибка в `manualZoneInput` (waiter не завершается, callback не отвечен) может привести к зависшему состоянию диалога.

**Рекомендация:** Как минимум — unit-тесты на `ScheduleSettingsFlow.handle()` с mock-ами `BehaviourContext`, проверяющие каждый переход `Outcome → editMessageText/sendTextMessage`. Либо выделить чистую функцию `resolveScreen(outcome, currentZone, lang) → Screen` и тестировать её изолированно.

### 2. `ScheduleWindow.ofHours` не валидирует `start != end`

```kotlin
fun ofHours(startHour: Int, endHour: Int): ScheduleWindow = 
    ScheduleWindow(LocalTime.of(startHour, 0), LocalTime.of(endHour, 0))
```

При этом `parse("07:00-07:00")` возвращает `null`. Это inconsistent API:
- `parse` — отклоняет `start == end`  
- `ofHours` — молча создаёт невалидный объект

В текущем плане `ofHours` вызывается только из `ScheduleCallbackHandler.dispatch()`, который предварительно проверяет `start == end` → `rejectedEqualEnd = true`. Но это защита на уровне коллера, а не инвариант типа. Если кто-то в будущем вызовет `ofHours(0, 0)` из другого места, `ScheduleWindow.contains()` вернёт `false` для любого времени (полуинтервал нулевой длины), и окно станет бесконечным suppressor'ом — **нотификации будут потеряны навсегда**.

**Рекомендация:** Добавить `require(start != end)` в `ofHours` — либо возвращать `null` и сделать возвращаемый тип `ScheduleWindow?`.

### 3. `NotificationScheduleServiceImpl` — асимметричная обработка ошибок

```kotlin
// fail-open — NEVER throws
override suspend fun getRecordingSchedule(): NotificationSchedule? =
    try { ... } catch (e: Exception) { null }

// Эти методы могут бросить (DB down, etc.)
override suspend fun isEnabled(): Boolean = settings.getBoolean(...)
override suspend fun getWindow(): ScheduleWindow? = settings.getString(...)
```

Методы разделены на два «тира» с разной семантикой ошибок:
- `getRecordingSchedule()` — fail-open, никогда не бросает
- `isEnabled()`, `getWindow()`, `getZone()` — пробрасывают исключения

Это задокументировано в плане (Task 3: "NEVER throws" в KDoc `getRecordingSchedule`), но НЕ отражено в KDoc индивидуальных геттеров. Разработчик, читающий интерфейс, должен понять это различие из одного комментария на `getRecordingSchedule()`. В плане это используется корректно (UI вызывает индивидуальные геттеры, pipeline — только `getRecordingSchedule()`), но asymmetry создаёт риск misuse в будущем.

**Рекомендация:** Добавить KDoc `@throws` на `isEnabled()/getWindow()/getZone()`, явно указывая, что они не fail-open и могут бросить.

## Concerns

### 4. Ручной ввод таймзоны не отклоняет offset-based зоны

`TimezoneCommandHandler` явно отвергает offset-based зоны:
```kotlin
require(olsonCode.contains('/')) { "Offset-based zone IDs are not allowed: $olsonCode" }
```

`ScheduleSettingsFlow.manualZoneInput` просто вызывает `ZoneId.of(input)` — и принимает `+03:00` как валидный ZoneId.

Спецификация говорит: "Manual input uses the `/timezone` dialog conventions." Но конвенции `/timezone` включают reject offset-зон. Это inconsistency между двумя dialogs одного и того же бота.

**Последствия:** Если OWNER введёт `+03:00`, это будет принято. `NotificationSchedule.contains(Instant)` будет работать корректно (Java умеет конвертировать offset-based зоны). Проблемы нет на функциональном уровне, но inconsistency запутывает.

### 5. `NotificationsViewStateFactory` refactoring посреди feature implementation

Task 5 одновременно добавляет schedule-поля в `NotificationsViewState` И выносит создание стейта в отдельный factory-класс. Это cross-cutting изменение, которое затрагивает:
- `NotificationsCommandHandler` (меняется конструктор)
- `FrigateAnalyzerBot` (меняется конструктор, убирается `AppSettingsService`)

Если factory сломается, `/notifications` диалог перестанет работать полностью — не только schedule, но и базовые флаги. План защищается тестами, но это всё равно рискованный refactoring в середине feature-ветки.

**Смягчение:** Сам refactoring корректен и улучшает код (single assembly point). Но стоит отметить этот риск явно.

### 6. `ScheduleKeyboardRenderer.zoneScreen` жёстко ссылается на i18n-ключи `/timezone`

```kotlin
private val ZONE_PRESETS = listOf(
    "command.timezone.zone.kaliningrad" to "Europe/Kaliningrad",
    "command.timezone.zone.moscow" to "Europe/Moscow",
    ...
)
```

Если кто-то изменит или удалит ключ `command.timezone.zone.kaliningrad` при рефакторинге `/timezone`, экран выбора таймзоны расписания сломается — `msg.get()` бросит `NoSuchMessageException`.

**Рекомендация:** Либо добавить отдельные ключи для schedule zone screen (дублирование, но независимость), либо документировать coupling в комментарии и в `telegram-notifications.md`.

### 7. Отсутствие проверки: `nfs:g:sched:*` callback НЕ должен затекать в старый `NotificationsSettingsCallbackHandler.dispatch`

План корректно делает early return в боте (Task 9 Step 3):

```kotlin
if (callback.data.startsWith(ScheduleCallbackHandler.PREFIX)) {
    // ... handle schedule
    return@onDataCallbackQuery
}
```

Но `NotificationsSettingsCallbackHandler.dispatch()` парсит `nfs:g:sched:on` как `parts.size == 4` + `parts[0] == "nfs"` + `parts[1] == "g"` + `parts[2] == "sched"` → unknown scope → `IGNORE`. План полагается ИСКЛЮЧИТЕЛЬНО на порядок проверок в боте. Если когда-нибудь этот порядок изменится (новый handler встанет между ними), callback затечёт в старый dispatcher и будет молча проигнорирован.

**Риск низкий** (IGNORE — safe outcome), но архитектурно хрупко.

### 8. Кэш `AppSettingsService` и гонка при параллельных запросах

Три schedule-ключа (`enabled`, `window`, `zone`) читаются независимо из кэша/DB. Между чтением `isEnabled()` и чтением `getWindow()` другой поток может изменить `window`. Результат: `isEnabled=true`, `window=null` (старое значение в кэше уже инвалидировано, новое прочитано из DB). `getRecordingSchedule()` корректно обрабатывает этот случай — возвращает `null` + warn. Но это создаёт «дрожание» расписания на одно вычисление.

**Приемлемо** для single-owner системы. Стоит отметить в документации.

## Suggestions

### 9. Добавить `NotificationScheduleService.getRecordingSchedule()` в `RecordingProcessingFacade` precompute

Сейчас план размещает чтение расписания внутри `evaluate()` — оно происходит ПОСЛЕ `saveProcessingResult`. Это дизайн-решение обосновано в спецификации:

> schedule is read inside `evaluate()` (after the save) — its safe failure direction is "extra notification", never a lost one.

Это корректно и не требует изменений. Но можно рассмотреть precompute `schedule` в фасаде (аналогично `globalEnabled`) для консистентности подхода — хотя бы как rejected alternative в документации.

### 10. `ScheduleWindow` заслуживает инварианта `start != end`

Добавить `init { require(start != end) { "Window start must differ from end" } }` в data class. Это сделает невозможным создание невалидного окна даже при прямом вызове конструктора.

### 11. Рассмотреть unit-тесты для `ScheduleSettingsFlow`

Минимальный набор:
- `RenderMain` → вызывает `editMessageText` с правильным текстом и клавиатурой
- `AwaitManualZone` → отправляет prompt, ждёт ответа, вызывает `setZone`
- `AwaitManualZone` + таймаут → отправляет timeout-сообщение
- `AwaitManualZone` + `/cancel` → отправляет cancelled-сообщение, НЕ перерендеривает main

## Questions

### 12. Что происходит, если OWNER меняет свой собственный часовой пояс после настройки schedule zone?

Дизайн говорит: "not tied to owner's current zone afterwards." Schedule zone остаётся той, что была при первом сохранении. Это by design. Но UX неочевидный: OWNER настраивает schedule (зона Moscow → окно 00:00–07:00 MSK), потом переезжает во Владивосток и меняет СВОЙ часовой пояс через `/timezone`. Окно расписания остаётся Moscow. Дневные детекции во Владивостоке (UTC+10) в 11:00 MSK — это 21:00 VLAT, всё ещё вне окна — нотификации подавляются.

Пользователь может не понять, почему «расписание не работает» после переезда. Стоит ли показывать зону расписания явно (уже сделано в status line: `⏰ Detection schedule: 00:00–07:00 (Europe/Moscow)`) и/или добавить предупреждение при несовпадении зоны пользователя и зоны расписания?

### 13. Стоит ли унифицировать валидацию зон между `/timezone` и schedule zone input?

Сейчас поведение различается (offset zones принимаются в schedule, отвергаются в `/timezone`). Есть ли причина для этого различия или это просто oversight?

## Итог

План структурно проработан и аккуратно следует дизайн-спецификации. Основные риски:

1. **Task 9 без тестов** — главная проблема. Самый сложный код идёт без тестового покрытия.
2. **`ScheduleWindow.ofHours` без валидации** — щель для будущих багов.
3. **Асимметричная обработка ошибок** — требует явного документирования.
4. **Inconsistency валидации таймзон** — minor, но сбивает с толку.

Критических архитектурных проблем нет. Fail-open семантика, stateless callback design, и выбор `recordTimestamp` как time basis — правильные решения.

---

## ext-claude-executor (ollama/kimi — Kimi K2.7)

# Ревью: Notification Schedule — design & implementation plan

Ниже критический разбор документов и связанных мест в коде. Положительные моменты (stateless picker, fail-open, вынесение `NotificationsViewStateFactory`) отмечены в контексте, но акцент — на проблемах, которые нужно закрыть до реализации.

---

## Critical Issues

### 1. Включение расписания (`nfs:g:sched:on`) не материализует часовой пояс, если он отсутствует

В `ScheduleCallbackHandler` действие `on` проверяет только наличие окна (`scheduleService.getWindow() == null`), но не зону. Если окно сохранено, а ключ `notifications.recording.schedule.zone` отсутствует/повреждён, `on` просто установит `enabled=true`. Но `NotificationScheduleService.getRecordingSchedule()` вернёт `null` из-за `window == null || zone == null`, то есть effective schedule останется выключенным. В UI при этом:

- строка статуса покажет «OFF» (`scheduleEnabled == true`, но `scheduleWindow != null`, а `scheduleZone == null` — wait, `scheduleWindow` есть, `scheduleZone` null → `scheduleEnabled && scheduleWindow != null` true, но zone null, поэтому отобразится `00:00–07:00 (?)`? Нет: в renderer `scheduleEnabled == true && state.scheduleWindow != null` → `line.on.format` с `scheduleZone ?: "?"`. То есть статус покажет ON с `?`, а effective schedule disabled. Но в любом случае зона не задана и расписание не работает);
- кнопка переключателя будет предлагать «Выключить расписание».

Это inconsistent state. Нужно либо материализовать зону в `on`, либо явно запретить включение без зоны.

### 2. Частичная запись окна/зоны/enabled — нет атомарности

В `ScheduleCallbackHandler` обработка `nfs:g:sched:e:<S>:<E>` делает три отдельных `upsert`:

```kotlin
scheduleService.setWindow(...)
if (scheduleService.getZone() == null) scheduleService.setZone(...)
scheduleService.setEnabled(true, ...)
```

`app_settings` — per-key upsert, rollback невозможен. Если упадёт между `setWindow` и `setEnabled`, получим сохранённое окно, но `enabled=false`. Если между `setZone` и `setWindow` — зона без окна. Fail-open не пропустит тревогу, но UI будет врать.

Рекомендация: ввести композитный метод в `NotificationScheduleService` вроде `configureAndEnable(window, zone, updatedBy)`, который пишет в deterministic order и обрабатывает ошибки, либо обернуть в `@Transactional` если `AppSettingRepository` это позволяет (R2DBC + транзакции в корутинах — уточнить).

### 3. Ручной ввод зоны не валидирует формат `Continent/City`

В `ScheduleSettingsFlow.manualZoneInput` используется:

```kotlin
val zone = ZoneId.of(input)
```

Нет проверки `input.contains('/')`, в отличие от `/timezone` (`TelegramUserServiceImpl.updateTimezone`). Это допускает offset ID вроде `+03:00`, у которого нет DST и который противоречит заявленной IANA-семантике. Нужно добавить ту же валидацию формата, что и в `TimezoneCommandHandler`.

### 4. UI показывает два разных статуса расписания

`NotificationsViewState.scheduleEnabled` — это только флаг `enabled`. Effective schedule зависит ещё от валидности `window` и `zone`. Renderer уже пытается скрыть это (`scheduleEnabled && scheduleWindow != null`), но кнопка toggle смотрит только на `scheduleEnabled`. В результате:

- `enabled=true`, `window=null` → статус OFF, кнопка «Выключить расписание»;
- `enabled=true`, `window=ok`, `zone=null` → статус ON с `?`, effective schedule disabled.

Нужен единый источник истины для UI, например sealed class `ScheduleStatus` (Disabled / Active(window, zone) / Misconfigured).

---

## Concerns

### Асимметрия fail-open между глобальным флагом и расписанием

`RecordingProcessingFacade` префетчит глобальный флаг **до** `saveProcessingResult`, чтобы сбой настроек оставил запись retryable. Расписание же читается внутри `evaluate()` и глотает любые исключения. Это осознанное решение сессии, но оно неочевидно в коде: при отказе БД настроек глобальный флаг остановит pipeline, а расписание будет проигнорировано и уведомления пойдут. Нужно явно задокументировать это в `.claude/rules/telegram-notifications.md` и в KDoc `NotificationDecisionService`.

### `OUT_OF_SCHEDULE` не проставляется при `NO_VALID_DETECTIONS`

Ветка `NO_VALID_DETECTIONS` идёт до проверки расписания. Если detections отфильтрованы `confidenceFloor` до tracker, причина будет `NO_VALID_DETECTIONS`, а не `OUT_OF_SCHEDULE`. Для функциональности без разницы, но если позже заведут метрики по причинам подавления, статистика расписания будет занижена. Стоит зафиксировать осознанный приоритет в документации.

### `getRecordingSchedule()` ловит слишком широко

```kotlin
} catch (e: Exception) {
    logger.warn(e) { ... }
    null
}
```

Это безопасно для fail-open, но программные ошибки (например, `NullPointerException`) тоже превращаются в «расписание выключено». Лучше разделить: логировать и возвращать `null` только на ошибки чтения/парсинга настроек, а неожиданные runtime исключения прокидывать дальше.

### `ScheduleSettingsFlow` не покрыт unit-тестами

План явно говорит: «No new unit tests — the deliverable gate is compilation plus the full telegram module test suite staying green». Но `ScheduleSettingsFlow` — самый хрупкий клей: manual-zone waiter, timeout, `/cancel`, invalid zone, editMessageText с обработкой «message is not modified». Без тестов высок риск регрессий при изменениях ktgbotapi. Хотя бы один тест на happy path и timeout стоит добавить.

### Парсинг расписания на каждую запись

`NotificationScheduleService.getRecordingSchedule()` парсит строку окна и `ZoneId` при каждом вызове `evaluate()`. `AppSettingsService` кеширует строки, но `ScheduleWindow.parse` и `ZoneId.of` вызываются заново. При высоком потоке записей это лишняя нагрузка. Стоит кешировать уже распарсенный `NotificationSchedule?` в `NotificationScheduleService` и инвалидировать при `set*`.

### `/notifications` падает для OWNER при сбое настроек

`NotificationsViewStateFactory` не оборачивает чтение глобальных флагов и расписания в `try/catch`. Если БД настроек недоступна, OWNER не сможет открыть `/notifications` (получит generic error). Это не fail-open, но и не критично для безопасности. Можно обсудить, нужно ли fallback.

---

## Suggestions

1. **Ввести `ScheduleStatus` как единый источник истины**
   ```kotlin
   sealed interface ScheduleStatus {
       data object Disabled : ScheduleStatus
       data class Active(val window: ScheduleWindow, val zone: ZoneId) : ScheduleStatus
       data class Misconfigured(val reason: String) : ScheduleStatus
   }
   ```
   Использовать его и в `NotificationScheduleService`, и в UI. Это уберёт рассогласование между `isEnabled`, `getWindow`, `getZone`.

2. **Материализовать зону при `on`, если её нет**
   В `ScheduleCallbackHandler` действие `on` должно не только проверять окно, но и, при отсутствии зоны, устанавливать её из `userService.getUserZone(ownerChatId)` перед включением.

3. **Вынести валидацию и waiter для IANA-зоны в общий компонент**
   Повторяющийся код ручного ввода зоны в `TimezoneCommandHandler` и `ScheduleSettingsFlow` стоит объединить в `ZoneInputDialog`, чтобы не дублировать логику timeout, `/cancel`, проверку `/`.

4. **Кешировать эффективное расписание**
   Добавить в `NotificationScheduleService` внутренний кеш `NotificationSchedule?` с TTL или инвалидацией в `setEnabled/setWindow/setZone`. Это уменьшит накладные расходы в pipeline.

5. **Добавить тесты на `ScheduleSettingsFlow`**
   Хотя бы:
   - `RenderMain` по `home`;
   - `AwaitManualZone` → valid zone → saved message + rerender;
   - timeout;
   - `/cancel`;
   - invalid zone message.

6. **Уточнить отображение при `global recording off`**
   Сейчас строка расписания рисуется независимо от глобального флага. Возможно, стоит добавить hint, что расписание работает только когда глобальные детекты включены.

---

## Questions

1. **Fail-open асимметрия**: подтвердить, что при отказе БД настроек ожидаемое поведение — глобальный флаг вызывает retry записи, а расписание игнорируется (уведомления идут вне окна). Это стоит явно прописать в правилах.
2. **Что делать с `enabled=true`, но отсутствующей/испорченной зоной или окном?** Показывать статус «OFF», «ON (not configured)» или блокировать кнопку включения?
3. **Должен ли `nfs:g:sched:on` материализовать зону из `userService.getUserZone`, если она отсутствует?**
4. **Приоритет `OUT_OF_SCHEDULE` vs `NO_VALID_DETECTIONS`**: нужно ли для аналитики/метрик учитывать out-of-schedule даже когда нет валидных detections?
5. **Будет ли seed/migration для ключей `notifications.recording.schedule.*`, или они только через UI?** Если ops захочет задать расписание через SQL, нужен runbook.
6. **Ручной ввод зоны**: ограничивать строго формат `Continent/City` (как `/timezone`) или допускать offset ID?

---

## ext-claude-executor (ollama/minimax — MiniMax M3)

### Critical Issues

**1. Бранч-ордер в `evaluate()` ломает спецификацию "reason precedence".**

Спека (строки 79–84) и Self-Review (строки 2354–2358) провозглашают порядок: `GLOBAL_OFF` → `OUT_OF_SCHEDULE` → `NEW_OBJECTS`/`ALL_REPEATED`. Но в `evaluate()` сейчас `!resolvedGlobalEnabled` стоит **после** ветки `NO_VALID_DETECTIONS` и **после** вызова `tracker.evaluate()`. По тексту Task 4 (псевдокод) `!scheduleAllows` вставляется после `!resolvedGlobalEnabled` и до `delta.newTracksCount > 0` — но не учитывается, что `NO_VALID_DETECTIONS` (delta полностью нулевая) перебивает `!resolvedGlobalEnabled`. Сейчас это даёт `GLOBAL_OFF`, по плану останется `GLOBAL_OFF` — но **после** вставки новой ветки тест `GLOBAL_OFF wins over OUT_OF_SCHEDULE` пройдёт только потому, что детектор в нём вообще не настроен правильно (см. плейсхолдер `DetectionDelta(...)` без аргументов). Конкретная претензия к плану: тест «GLOBAL_OFF wins over OUT_OF_SCHEDULE» должен явно показать, что обе ветки проверки читаются до вычисления reason, а сейчас полагается на то, что в файле уже есть нужная константа. **Нужно:** либо переписать тест с явным `DetectionDelta(newTracksCount = 0, matchedTracksCount = 0, staleTracksCount = 0, …)`, либо перенести проверку `GLOBAL_OFF`/`OUT_OF_SCHEDULE` **перед** проверкой дельты — что в действительности правильнее: «детекции есть, но все отфильтрованы confidence-порогом» — это `NO_VALID_DETECTIONS`, который должен читаться первым (текущий код корректен), а вот «запись вне расписания и при этом все детекции мусор» — это `OUT_OF_SCHEDULE` (логичнее, чем `NO_VALID_DETECTIONS`)? На этот вопрос спека не отвечает, и план молча копирует текущий ордер, не анализируя.

**2. `NotificationScheduleServiceImpl.getRecordingSchedule()` проглатывает `CancellationException` через `catch (e: Exception)`.**

В Kotlin coroutines `CancellationException` — это `IllegalStateException` → он попадает в `catch (e: Exception)`. План корректно обрабатывает это в `getRecordingSchedule()` явным `catch (e: CancellationException) { throw e }` (строки 664–665 плана), **однако** `isEnabled()`/`getWindow()`/`getZone()` не имеют такой защиты. Если `settings.getBoolean`/`getString` по какой-то причине (например, R2DBC отмена родительской корутины) бросит `CancellationException`, он будет проглочен и заменён на `null` + warn-лог. Это нарушает structured concurrency: пользовательский `cancel()` потеряется, корутина продолжит выполняться. **Нужно:** либо завернуть каждую обёртку в `try { … } catch (e: CancellationException) { throw e } catch (e: Exception) { … }`, либо в `AppSettingsService` гарантировать, что `getBoolean`/`getString` либо возвращают значение, либо пробрасывают исключение, не проглатывая отмену (это поведение там сейчас корректно — `getBoolean`/`getString` не ловят `Exception`, так что проблема именно в `NotificationScheduleServiceImpl`).

**3. `ScheduleSettingsFlow.manualZoneInput` гоняется с диспатчем `nfs:` callback'ов.**

Когда бот ждёт `waitTextMessage().filter { it.chat.id == cid }.first()` внутри `withTimeoutOrNull(120_000L)`, любой `nfs:`-callback от того же пользователя будет проигнорирован (он не текст, и фильтр это не пропустит). Но **другие** `onContentMessage` обработчики (`FrigateAnalyzerBot.kt:258-270`) тоже сработают на текст — а там сейчас для не-команд просто отвечают `Active` без действий. То есть одновременно два waiter'а на одном `chatId` — наш и любой будущий, который может появиться, если кто-то добавит `waitTextMessage` для другой фичи. Это типичная «газета на полу» ktgbotapi: один чат, один активный waiter. Допустимо документировать, но план нигде не фиксирует ограничение, и через полгода кто-нибудь добавит ещё один flow и сломает оба.

**4. `NotificationsMessageRenderer` падает на `requireNotNull(state.scheduleEnabled)`, но `NotificationsViewState` по плану имеет `scheduleEnabled: Boolean? = null` — то есть дефолт `null`.**

В Task 6 (строки 1396–1410) рендерер после Task 5 начинает `requireNotNull(state.scheduleEnabled)` для OWNER. Это правильно, но **все тесты из Task 5 с `isOwner = true` в `NotificationsViewStateFactoryTest` обязаны передавать `scheduleEnabled`** — иначе `factory.build` для non-owner-варианта (по умолчанию) даст `null` в новых полях, а для owner — `true`/`false`. В плане это указано корректно. **Но** `NotificationsMessageRendererTest` сейчас не имеет в файле конструкции owner, которая забывает `scheduleEnabled` — тест `owner variant has 5 rows` (строка 41) использует `isOwner = true` и забывает добавить `scheduleEnabled`. После Task 5 этот тест начнёт падать, и план верно говорит «after Step 4 the renderer requireNotNull's this field for owners and those tests would otherwise fail at runtime». Однако план пишет «Search the whole test file for every other construction with `isOwner = true`» — это ручной шаг, который легко забыть. **Нужно:** либо явно перечислить все 4 таких конструкции в плане (я насчитал их: строки 41, 124, 142), либо сделать проверку в рендерере ленивой (`scheduleEnabled` остаётся nullable, проверяется в момент использования), либо (предпочтительно) добавить `init` блок в `NotificationsViewState` для ассерта согласованности.

**5. Callback `nfs:g:sched:on` без окна открывает start picker, но описание спека утверждает «auto-enable on save».**

Этот сценарий: OWNER нажал «Enable schedule», ничего не настроено, бот открыл picker — хорошо. Но если OWNER затем передумал и нажал «‹ Back», он возвращается на главный экран с `scheduleEnabled = false` (ничего не изменилось). Если же он прошёл весь путь и нажал «Back» в **end picker** — то же самое. План не описывает никакого «отменить выбор» после того, как end-hour нажат; единственный путь назад — на главный экран с уже сохранённым окном. Это значит: «back» из start/end picker = no-op корректно, но «back» после успешного end-pick — это неявный commit. Документация спека об этом умалчивает, и план Task 8 не вводит отдельного `Cancel` outcome. В тесте `home re-renders main without changes` (строки 1911–1917 плана) это подразумевается, но в реальном сценарии OWNER мог нажать `s:23`, потом случайно нажать `home` (из end-picker) — и конфигурация не сохранится (правильно), а вот если он нажмёт `e:23:7` случайно — сохранится без отката. **Нужно:** явно указать в спеке и UI, что нажатие часа в end-picker = commit (т.е. показать "Confirm?" не предусмотрено).

---

### Concerns

**6. `ScheduleKeyboardRenderer.formatHour` возвращает `"%02d:00"`, но `endPicker` рендерит `msg.get("notifications.sched.picker.end.title", lang, formatHour(startHour))` — `LocalTime` по локали.**

`DateTimeFormatter.ofPattern("HH:mm")` всегда даёт 24-часовой формат (это `HH`, не `hh`), так что для русской локали текст «00:00» будет корректен. Но если в будущем кто-то переключит на `hh:mm a` (12-часовой), `formatHour` начнёт выдавать мусор. Стоит либо использовать `DateTimeFormatter.ISO_LOCAL_TIME` (даёт всегда `HH:mm`), либо явно закрепить контракт: «часы в hour picker всегда `00..23` zero-padded, локаль не применяется».

**7. Зонная логика для `recordTimestamp` теряет исходную зону источника записи.**

`recording.recordTimestamp` — это `Instant`. Окно проверяется в зоне расписания (например, `Europe/Moscow`). Если детекция пришла с камеры в другом часовом поясе (Frigate в UTC, OWNER в Москве), `recordTimestamp` всегда трактуется как UTC-момент, приведённый к Москве. Это правильно и в спеке зафиксировано. **Но** в UI не показывается «зона, в которой трактуется ваше расписание» — только «Schedule timezone: Europe/Moscow». OWNER может решить, что расписание работает в зоне камеры, и удивиться, что ночная детекция 23:30 UTC → 02:30 MSK всё-таки доставлена. Документация в `.claude/rules/telegram-notifications.md` (Task 10) должна явно указать: «schedule timezone = timezone the window is interpreted in, not camera timezone».

**8. `loadAndCache` (Task 2) срока действия кэша нет — глобальный кеш `app_settings` живёт до перезапуска.**

Сейчас это работает, потому что все записи идут через `setString`/`setBoolean`, которые инвалидируют ключ. Но если кто-то запишет ключ напрямую через SQL миграцию или admin-инструмент, кэш останется с устаревшим значением навсегда. Это не блокер для данной фичи, но расписание — флаг, который хочется «обновить прямо сейчас» (например, переехал в другой часовой пояс). Стоит документировать в `.claude/rules/database.md`/`telegram-notifications.md`, что прямые SQL-обновления этих ключей не подхватятся до перезапуска.

**9. `Outcome.RenderEndPicker(5, rejectedEqualEnd = true)` — семантика `rejectedEqualEnd` не отделима от «новый выбор».**

В Task 8 план возвращает `RenderEndPicker(5, rejectedEqualEnd = true)` на `e:5:5`. Это означает: OWNER выбрал start=5, end=5, мы отрендерили end-picker с warning. Но если он затем выберет `e:5:6`, мы получим `e:5:6`, и переход `rejectedEqualEnd = true → false` нигде не сбрасывается (точнее сбрасывается естественно — мы перерендерим end-picker без warning). Проблем не вижу, но **state параметр `rejectedEqualEnd`** имеет смысл только в пределах одного `editMessageText`; если callback приходит строго после нашего `edit`, всё хорошо. Но если Telegram API даст сбой и придёт ретрай — мы не сможем отличить «новый» выбор от «ретрая» rejected. Сейчас это работает потому, что `data` естественно различается (`e:5:5` против `e:5:6`); в более общем случае стоило бы передавать `showEqualWarning` в callback data, а не возвращать его из outcome. **Низкий приоритет**, но стоит зафиксировать в спеке.

**10. План ссылается на `TimezoneCommandHandler` и `ZONE_PRESETS`, но не даёт полного пути.**

Task 7 (строки 1712–1724 плана) жёстко зашивает «Same city presets as /timezone» в `ZONE_PRESETS` — дублирующий список, который придётся синхронизировать вручную, если кто-то добавит пресет в `/timezone`. Правильнее — вынести `ZONE_PRESETS` в общий объект (например, `telegram/i18n/TimezonePresets.kt`) и импортировать в оба места. **Нужно:** добавить мини-рефакторинг: выделить константу, переиспользовать. Иначе через год расписание и `/timezone` разойдутся.

**11. «Stateless by design» — но `ScheduleSettingsFlow` всё-таки хранит состояние waiter'а.**

`ScheduleSettingsFlow.manualZoneInput` запускает `waitTextMessage().filter { it.chat.id == cid }.first()` — это **активный** waiter. Если бот перезапустится во время ручного ввода зоны, OWNER отправит `Europe/Berlin`, текст попадёт в `onContentMessage` (строки 258–270 в `FrigateAnalyzerBot.kt`), который сейчас только проверяет `AuthResult.Active` и молчит. То есть зона **не сохранится**, и OWNER не получит подтверждения. Это известное ограничение waiter-паттерна в проекте, но спека об этом умалчивает. Стоит хотя бы отметить в документации: «manual zone input не переживает перезапуск бота».

---

### Suggestions

**12. Группировка `getRecordingSchedule()` reads + кэширование на уровне запроса.**

`NotificationScheduleServiceImpl.getRecordingSchedule()` сейчас делает три последовательных `suspend` вызова (`isEnabled` → `getWindow` → `getZone`). Каждый идёт через `AppSettingsService`, который может брать `cacheMutex` три раза подряд. В горячем пути (`RecordingProcessingFacade` → `evaluate()`) это на каждую запись три мьютексных секции. Стоит либо ввести in-memory snapshot в рамках одного `evaluate()` (через `coroutineContext`-scoped cache), либо агрегировать три ключа в один pipeline-snapshot. **Низкий приоритет** (3 ключа — это немного), но на 100 записей/мин может стать заметным под нагрузкой.

**13. Алертинг на «schedule enabled but never notifies».**

Если OWNER включил расписание, окно `00:00-07:00`, но `recordTimestamp` ни одной записи за сутки не попал — OWNER не получит фидбэка. Спека говорит «outside drop» без какой-либо диагностики. Полезно (но не блокер) показывать в `/notifications` счётчик «записей подавлено за 24ч», или хотя бы debug-метрику в actuator. Иначе OWNER включит и забудет.

**14. `recordTimestamp` vs `processingTime` в дизайне — стоит подсветить в i18n/UI.**

Это сознательный выбор, но OWNER может забыть: «я обработал утренний бэклог, и дневные записи доставляются» — не очевидно без чтения спеки. Документация (Task 10) должна явно объяснить.

**15. Альтернатива: `nfs:g:sched:zone` теряет уже выбранное окно.**

В текущем дизайне: OWNER открывает start picker → end picker → end `23:00-07:00` сохраняется. Потом открывает zone screen → выбирает `Asia/Irkaterinburg` → возвращается на главный экран. Окно сохранилось, зона обновилась — хорошо. Но если OWNER нажмёт `‹ Back` из zone screen — он попадёт на главный экран **без** сохранения выбора, **с** старой зоной. Это правильно. **Но** UX: только что выбранная зона исчезает без feedback. Стоит показать «выбрано X, нажмите пресет чтобы подтвердить» — но это уже не баг, а UX-улучшение.

**16. `Cancel` outcome отсутствует на start-picker.**

Task 8: на `nfs:g:sched:s:H` → `RenderEndPicker(H)`. Хорошо. Но если OWNER выбрал start=23, потом нажал `home` (back) **из end-picker**, мы возвращаемся на главный экран **без** сохранения — корректно. Однако теста на этот сценарий нет: `home re-renders main without changes` (строки 1911–1917) не учитывает контекст «после start-pick». Стоит добавить тест «home из end-picker не сохраняет окно».

**17. `end == start` обрабатывается, но `end == start + 24h` (т.е. обнуление окна) — нет.**

Спека говорит «one daily window, midnight crossing supported», но `start=10, end=10` отвергается. Что насчёт `start=10, end=10+24=10` (бессмысленно) или `start=00, end=00` (отвергнут)? Это эквивалентные варианты, и `parse` их одинаково отвергнет. **OK** — никакого дополнительного действия не нужно, но стоит явно указать в спеке: «valid window = `[start, end)`, non-empty, `start != end`; `start > end` = midnight wrap; `start < end` = plain».

**18. Альтернатива: использовать cron-подобное выражение для будущих фич.**

Сейчас (start, end, zone) — это минимум. Когда понадобится «по будням 22–06, по выходным 00–24» (вне scope), придётся ломать storage. Альтернатива — cron-выражение (5 полей) уже сейчас. Не предлагаю делать, но стоит отметить в «Out of Scope», что cron-формат не рассматривался.

**19. Тест фабрики (`NotificationsViewStateFactoryTest`) проверяет «non-owner state has null globals and null schedule fields, no settings reads»** — но **текущая семантика для non-owner `appSettings` уже «no reads»** (см. текущий `NotificationsCommandHandler.kt`). План это сохраняет корректно. **Однако** `isOwner = false` сейчас определяется в `NotificationsCommandHandler` через `userService.isOwner(user.username)`, а в `FrigateAnalyzerBot.RERENDER` блоке — через `userService.isOwner(current.username)` (строка 189). Если owner-username поменяется в runtime (например, в `app_settings` — `TELEGRAM_OWNER` env переменная), `isOwner` может дать разные ответы в двух местах. План вводит третий `isOwner` в `ScheduleSettingsFlow.handle` (Task 9, строка 2170) — снова тот же `userService.isOwner(current.username)`. Согласовано, но это **третье место**, где проверяется owner. Стоит вынести в helper (например, `AuthorizationFilter.isOwnerByUsername`).

**20. Self-Review плана упоминает «No DB migration by design» — это правильно, но `app_settings` имеет `NOT NULL` constraints?**

`AppSettingsServiceImpl` (строки 51–56) делает `entity.settingValue ?: return null` — если в БД значение почему-то null, кэшируется `null` и возвращается default. Расписание на это полагается (через `getString(key) ?: return null`). Но если в схеме `app_settings.setting_value NOT NULL`, то null невозможен; если `NULLABLE` — есть теоретический сценарий получения `null` для существующего ключа. Стоит проверить миграцию `app_settings` и либо задокументировать (NOT NULL — мы в безопасности), либо защититься (в `loadAndCache` явно валить, если `settingValue == null` для существующего ключа).

---

### Questions

**Q1.** Спека в разделе "Decision Enforcement" (строка 73) говорит: «reads go through the settings cache, so no prefetch/interface change is needed». Но `evaluate()` остаётся без изменений сигнатуры, а `scheduleService.getRecordingSchedule()` теперь вызывается **внутри** `evaluate()` после `try { tracker.evaluate(...) }`. Если `tracker.evaluate` занимает 50ms, а `scheduleService.getRecordingSchedule()` — ещё 5ms (три R2DBC reads при cache miss), получаем +5ms в горячем пути. Почему бы не префетчить расписание **параллельно** с `tracker.evaluate` через `async { … }`? Это сохранило бы fail-open-семантику (расписание читается параллельно, его ошибка не влияет на tracker) и сократило latency. **Вопрос автору:** рассматривалось ли распараллеливание, и если да — почему отвергли?

**Q2.** `NotificationsViewState` (Task 5) получает `scheduleEnabled: Boolean? = null` как nullable с дефолтом. Но **для OWNER** это поле всегда non-null в корректном коде. Разве не стоит сделать два разных типа (`NotificationsViewState` и `OwnerNotificationsViewState`) или хотя бы перенести owner-only поля в `OwnerNotificationsViewState` через композицию? Сейчас `requireNotNull` в рендерере — это runtime-проверка, которая дублирует контракт. **Вопрос:** есть ли проектная причина не использовать sum types / sealed interface?

**Q3.** В Task 9 (строка 2169): `val chatId = current.chatId ?: return`. Если `current.chatId` null (это возможно, если юзер был invited, но не активирован) — flow выходит молча. Но мы уже зашли в `nfs:g:sched:*` callback, что подразумевает owner-статус, а owner всегда ACTIVE. **Вопрос:** нужен ли тут `requireNotNull` с warn-логом для диагностики невозможного состояния, или null-check достаточен?

**Q4.** Task 10 добавляет строки в `notifications.sched.picker.end.invalid = "⚠️ … must differ from the start hour"` — но **в чём смысл «must differ» с точки зрения UX**? Получасовое окно 23:00–23:00 дало бы ровно 0 минут — это действительно бессмысленно. Но `start=10, end=11` (1 час) валидно. Так что «must differ» — это корректное сообщение. **Однако** в тесте (строки 1552–1555 плана) проверка `text.contains("must differ", ignoreCase = true)`. Если локализация переведёт на русский «должны отличаться», а кто-то поменяет формулировку — тест упадёт. **Вопрос:** использовать message-key в тесте вместо строки?

**Q5.** План нигде не описывает, что произойдёт, если OWNER сменит **язык интерфейса** (`/timezone` диалог сохраняет `languageCode`). Например, был английский, в `/notifications` открыл picker, нажал `‹ Back`, переключил язык на русский через `/timezone`, вернулся — пикер отрисуется на русском? `current.languageCode` берётся в `ScheduleSettingsFlow.handle` (строка 2170) на момент прихода callback, что корректно. **Вопрос:** подтверждено ли это ручное тестирование?

**Q6.** `app_settings` — per-key cache через `ConcurrentHashMap<String, String>`. Расписание читает 3 ключа последовательно (`isEnabled` → `getWindow` → `getZone`). При cache miss на всех трёх — три похода в БД. **Вопрос:** рассматривался ли bulk-read метод `getStrings(keys: List<String>): Map<String, String?>` чтобы сократить cache miss penalty? Или намеренно оставлено для простоты?

**Q7.** Task 8 (строки 2029–2037): при `e:23:7` (midnight crossing) мы сохраняем окно `ScheduleWindow.ofHours(23, 7)`, что даёт `start=23:00, end=07:00`. `ScheduleWindow.contains(LocalTime.of(7, 0))` возвращает `false` (half-open), `contains(LocalTime.of(6, 59))` — `true`. Корректно. **Вопрос:** покрыт ли тестом случай `contains(LocalTime.of(23, 0))` для midnight-crossing (план имеет его, строка 145) и `contains(LocalTime.of(7, 0))` (строка 160)? Да, оба покрыты. **Подвопрос:** покрыт ли `contains(LocalTime.MIDNIGHT)` = `00:00` для `23:00-07:00`? `LocalTime.MIDNIGHT = 00:00`, `23:00 > 00:00` → `00:00 < 07:00` → `true` через midnight-wrap ветку. **Нужен тест.**

**Q8.** План нигде не упоминает поведение при **выключенном Telegram** (`application.telegram.enabled=false`). `NotificationScheduleServiceImpl` — в `service` модуле, **без** `@ConditionalOnProperty`, и `NotificationDecisionServiceImpl` тоже без условия. Это правильно: расписание должно работать даже без бота (на случай, если позже появятся другие каналы уведомлений). **Вопрос:** подтверждено ли, что `NotificationScheduleService` намеренно **не** под `ConditionalOnProperty`? Если да — стоит зафиксировать в спеке.

**Q9.** `TelegramBotSupervisor` (см. `.claude/rules/telegram.md`) перезапускает polling при сбоях. Если бот перезапустится в момент, когда OWNER находился в start-picker — у нас **stateless** design: следующий callback `s:23` имеет тот же data, мы отрисуем end-picker заново. Это работает. **Вопрос:** покрыт ли этот сценарий в `ScheduleCallbackHandlerTest`? План пишет тесты на `s:24` (out of range) и `s:x` (parse fail), но не на «вызов `s:H` после «stale» состояния» (т.е. когда пользователь видел start-picker, потом idle 10 минут, потом нажал). **Предложение:** добавить тест, который симулирует stale-клавиатуру (хотя бы проверить, что outcome не зависит от предыдущих вызовов).

**Q10.** В спецификации (строка 88) сказано: «No `Clock` dependency». Это правильно — `NotificationSchedule.contains(Instant)` детерминирован. **Вопрос:** покрыт ли тестом случай с **leap second** (30 июня 23:59:60)? `Instant.parse` отвергает leap seconds; `LocalTime` не имеет понятия о них. Технически, Java Time API трактует 23:59:60 как 23:59:59 следующего дня. **Низкий приоритет**, но если где-то `recordTimestamp` имеет leap-second-семантику — может быть сюрприз.

---

**Резюме:** План в целом проработан добротно, TDD-структура (test-first → impl → run) выдержана, fail-open семантика явно зафиксирована. **Критические проблемы** связаны с (1) проглатыванием `CancellationException` в `isEnabled/getWindow/getZone`, (2) `requireNotNull` в рендерере требует ручного обновления нескольких тестов, (3) `ScheduleSettingsFlow.manualZoneInput` и общий `onContentMessage` могут конкурировать за waiter, (4) order-of-branches в `evaluate()` нуждается в явном тесте с правильным `DetectionDelta`, (5) `home`-из-end-picker коммитит выбор без отката. **Concerns** в основном касаются семантической ясности в UI и долгосрочной синхронизации с `/timezone`. **Suggestions** указывают на возможные улучшения UX, наблюдаемости и архитектурной чистоты.
