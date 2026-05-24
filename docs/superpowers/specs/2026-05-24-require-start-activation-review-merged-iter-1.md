# Merged Design Review — Iteration 1

**Date:** 2026-05-24
**Design:** `docs/superpowers/specs/2026-05-24-require-start-activation-design.md`
**Plan:** `docs/superpowers/plans/2026-05-24-require-start-activation.md`
**Reviewers:** codex (gpt-5.5 xhigh), ccs-glm, ollama-kimi, ollama-minimax, ollama-deepseek

---

## codex-executor (gpt-5.5, xhigh reasoning)

### Critical Issues

- План сломает компиляцию: `getRole(...)` удаляется в Task 7, но `CancelExportHandler.kt:71` всё ещё его вызывает, а `CancelExportHandlerTest.kt:85` содержит много моков `getRole`. Нужно либо мигрировать `CancelExportHandler` на `authorize(...)` с сохранением callback-поведения, либо не удалять legacy API.

- Цель "все команды кроме `/start`" не выполняется для неизвестных slash-команд. В `FrigateAnalyzerBot.kt:292` `onContentMessage` молча пропускает любой текст, начинающийся с `/`, а `onCommand(...)` зарегистрирован только для известных handler-ов. Значит неактивированный owner на чистой БД для `/foo` получит тишину, не "сначала /start". Нужно либо сузить требование до "известных команд", либо добавить обработку unknown commands.

- Edge case про гонку двух `/start` описан неверно. В design утверждается, что всё сводится к идемпотентному `UPDATE`, но для owner-а на чистой БД сначала идёт check-then-insert через `StartCommandHandler.kt:53` и `inviteUser` в `TelegramUserServiceImpl.kt:38`, а `username` уникален в БД. Два параллельных `/start` могут упереться в duplicate key до `activate`. Нужно либо признать это вне scope, либо сделать invite/activate идемпотентными.

- Callback non-goal построен на слишком сильном допущении. Inline-кнопки действительно не рассылаются INVITED-пользователям, но старые Telegram-сообщения с кнопками могут остаться после reset/restore БД. Тогда `QuickExportHandler` меняет поведение owner-а без записи: раньше `getRole(username)` пускал по env owner, после `authorize(username)` будет `NeedsActivation` и отказ. Это надо явно принять как изменение поведения или обработать отдельно.

### Concerns

- Дизайн почти не тестирует главный routing behavior. `AuthorizationFilterTest` проверит классификацию, но не проверит, что router отвечает нужным ключом, не вызывает handler, и передаёт non-null `user` для `Active`. Фраза "exhaustive when даёт высокую уверенность" слабая: ошибка может быть в выборе ключа, языка, `return@onCommand`, или в `/start` bypass.

- `authorize(username)` закрепляет username-only авторизацию как каноническую. Это уже текущая модель, но новый API делает её центральной: ACTIVE-запись ищется только по username, без проверки `userId`/`chatId`. Если username Telegram был переиспользован другим аккаунтом, новый владелец username получит доступ.

- Проверка owner-а остаётся разнородной. Новый код использует `username == properties.owner`, тогда как `TelegramUserService.isOwner()` уже делает case-insensitive сравнение. Telegram usernames фактически case-insensitive; лучше не плодить разные правила в `AuthorizationFilter`, `StartCommandHandler`, `HelpCommandHandler`, add/remove handlers.

- `onContentMessage` в плане называется "некомандный текст", но фактически покрывает любой content message: фото, стикеры, voice и т.п. Это существующее поведение, но в design стоит явно назвать "non-command content", либо ограничить обработчик только `TextContent`.

- Group/channel behavior не определён. `extractUsername()` принимает только `PrivateContentMessage`, поэтому group-команды от реального пользователя будут Unauthorized. Если бот private-only, это надо зафиксировать как явный invariant и, возможно, игнорировать non-private chats вместо reply.

### Suggestions

- Добавить Task для `CancelExportHandler`: `Active` пропускает, `NeedsActivation` и `Unauthorized` отвечают старым `common.error.unauthorized`. Обновить все `CancelExportHandlerTest`.

- Вынести route decision в маленькую тестируемую функцию, например `resolveCommandAccess(authResult, requiredRole, telegramLang): RouteDecision`. Тогда можно покрыть `Unauthorized`, `NeedsActivation`, owner-only, active user, active owner без мокания всего `BehaviourContext`.

- Для `AuthResult.NeedsActivation` можно хранить `username` и опционально `user: TelegramUserDto?`. Сейчас не нужно для reply, но это упростит логи и будущую локализацию/аудит без повторного lookup.

- `AuthResult.Active` уже несёт `TelegramUserDto`, поэтому отдельный `chatId` обычно не нужен. Более полезная альтернатива: для `authorize(message)` проверять соответствие ACTIVE-записи фактическому Telegram `userId`/`chatId`.

- Новый i18n ключ выглядит оправданным. Переиспользовать `common.error.unauthorized` нельзя.

- `makeActiveUser` в Task 6 лучше сделать нормальным test fixture с импортами, без FQN.

- 7-8 коммитов приемлемы, но можно объединить `AuthResult` + `AuthorizationFilter` + тесты в один функциональный commit.

### Questions

- Требование "все команды кроме `/start`" включает неизвестные команды (`/foo`) или только команды из `CommandHandler`?
- Нужно ли сохранять старое callback-поведение для owner-а без DB-записи после reset, если он нажимает старую inline-кнопку из Telegram history?
- Готовы ли в рамках этой задачи трогать идемпотентность `/start`, или concurrency race нужно явно записать как известное ограничение вне scope?

---

## ccs-executor (glm)

### Critical Issues

#### 1. `findByUsername` вместо `findActiveByUsername` — неявная смена контракта для `onCommand`

Текущий роутер для owner-а делает двухшаговый lookup и **owner с записью INVITED** (например, после восстановления снапшота) раньше **проходил авторизацию**. Новый `authorize()` блокирует этот кейс — это заявленное поведение, но стоит явно подтвердить, что INVITED owner с chatId — не ожидаемый production-сценарий.

#### 2. `authorize(message)` — непокрытый тестами путь делегирования

7 тестов покрывают `authorize(username)`, но happy-path делегации `authorize(message) → extractUsername → authorize(username) → Active(...)` не тестируется. Рекомендация: добавить хотя бы один тест с валидным `PrivateContentMessage` mock.

### Concerns

#### 3. `QuickExportHandler` глушит `NeedsActivation` как `Unauthorized`

Если в будущем рассылка изменится, пользователь получит "Доступ запрещён" вместо "Отправьте /start". Скрытый контракт, не выраженный в типах. Альтернатива — отдельный `NeedsActivation` branch с `common.error.activation.required`.

#### 4. FQN в хелпере `makeActiveUser` — запах кода

Лучше сразу добавить нормальные импорты в шапку `QuickExportHandlerTest.kt` и использовать короткие имена.

#### 5. `relaxed = true` mock для `CommonMessage` — хрупкий тест

Зависит от детали реализации MockK. Не блокер, но стоит добавить комментарий к тесту, объясняющий, почему relaxed mock возвращает null.

#### 6. Нет интеграционного теста роутера

Учитывая, что это ключевое изменение поведения, риск копипаст-ошибок реален. Минимальная mitigation — добавить detail-ревью именно блока `when (val result = ...)` из Task 5.

#### 7. Несогласованность design vs plan: `record.toDto()`

Дизайн пишет `AuthResult.Active(UserRole.OWNER, record.toDto())`, но `findByUsername` уже возвращает `TelegramUserDto`. План корректно использует `record` напрямую. Cosmetic, но может запутать читателя.

### Suggestions

#### 8. Вынести `makeActiveUser` в `TelegramUserDto` companion или тестовый fixture

Если duplicates только в двух файлах — overengineering. Но если в будущем добавятся ещё тесты, паттерн распространится.

#### 9. `when` в роутере — извлечь в отдельный метод

Блок занимает ~20 строк. Optional — текущая вложенность приемлема.

#### 10. `NeedsActivation` — рассмотреть `data class NeedsActivation(val isOwner: Boolean)`

Future-proof. YAGNI может быть сильнее.

#### 11. Коммит-гранулярность

7 коммитов — хорошо. Ничего менять не нужно.

### Questions

- Owner с INVITED-записью и `chatId != null` — реальный сценарий?
- Есть ли ещё вызовы `getRole()` за пределами `FrigateAnalyzerBot` и `QuickExportHandler`?
- Стоит ли покрыть `authorize(message)` → делегацию в `authorize(username)`?

**Итог:** Дизайн и план — качественные. Критических блокеров нет. Основные рекомендации: добавить тест на делегирование `authorize(message)` и убрать FQN.

---

## ollama-executor (ollama-kimi)

### Critical Issues

**1. Упущен call-site: `CancelExportHandler`.**
В плане Task 6 обновляется только `QuickExportHandler`, а `CancelExportHandler` (строка 71: `authFilter.getRole(username) == null`) полностью проигнорирован. Если в Task 7 удалить `getRole`, проект не скомпилируется. Тесты `CancelExportHandlerTest` тоже содержат множество моков. Блокер.

**2. Ложное утверждение в Task 7.**
«После Tasks 5 и 6 ни один call-site не использует `getRole`» — неверно. `CancelExportHandler` остаётся нетронутым.

**3. Отсутствие `CancelExportHandler` в списке затронутых файлов дизайн-документа.**

### Concerns

**1. Гонка при двух параллельных `/start` от owner-а.**
`StartCommandHandler` для owner на чистой БД сначала делает `inviteUser()` (INSERT), а потом `activateUser()` (UPDATE). INSERT с unique constraint — не идемпотентен. Pre-existing баг, но таблица edge cases его маскирует.

**2. Owner без записи не получает owner-меню при старте бота.**
`FrigateAnalyzerBot` строка 93: `userService.findActiveByUsername(properties.owner)`. На чистой БД вернёт `null`, и `registerOwnerCommands` не вызовется. Owner получит owner-меню только после `/start`. Текущее поведение, дизайн его не меняет.

**3. `makeActiveUser` с полными FQN в тестах.** Лучше добавить импорты явно.

**4. Гранулярность коммитов.** 8 коммитов избыточно для атомарной задачи. Task 1+2+3-4 можно объединить.

**5. `relaxed = true` в `AuthorizationFilterTest`.** Стоит добавить комментарий.

### Suggestions

**1. Добавить Task 6b: `CancelExportHandler`.**

**2. Объединить мелкие таски.** Task 1+2+3+4 → один коммит.

**3. Не удалять `getRole` до миграции всех call-sites.** Лучше — удалить legacy API в одном коммите вместе с последней миграцией.

**4. В `AuthResult.Active` уже есть `chatId` через `user.chatId`.** Дублировать не нужно.

**5. i18n-ключ лучше новый, а не reuse.**

### Questions

1. Почему `CancelExportHandler` не был обнаружен как call-site при проектировании? Нужен ли отдельный grep-аудит?
2. Для `CancelExportHandler` при `NeedsActivation` должен ли ответ быть `common.error.unauthorized` или `common.error.activation.required`?
3. Стоит ли в рамках этого PR исправить `registerOwnerCommands` на старте бота?

---

## ollama-executor (ollama-minimax)

### Critical Issues

#### 1. `CancelExportHandler` не упомянут — Task 7 сломает компиляцию

`CancelExportHandler.kt:71` и 8 моков `coEvery { authFilter.getRole(...) }` в `CancelExportHandlerTest.kt`. Логика отказа — та же, что в `QuickExportHandler`.

#### 2. `MessageKeyParityTest` (Task 2 Step 3) запускается напрямую `./gradlew`

В Task 4 Step 3, Task 5 Step 4, Task 6 Step 9 — `./gradlew :frigate-analyzer-telegram:test` (без `--tests`) запускается 5 раз подряд. Стоит явно прописать `Использовать build skill / build-runner` рядом с этими командами.

#### 3. Гонка двух `/start` от owner — описана слишком оптимистично

`StartCommandHandler` для owner делает два отдельных вызова: `inviteUser()` (INSERT) → `activateUser()` (UPDATE). Если оба видят `null`, оба попытаются `inviteUser` — второй упадёт с unique constraint. Существующая проблема. Стоит снять «✅» в таблице edge cases или вынести в Non-goals.

### Concerns

#### 4. `extractUsername`-тест слишком хрупок

Зависит от того, что `extractUsername` использует именно `as? PrivateContentMessage<*>`. Лучше собирать настоящий `PrivateChatImpl` + `CommonUser(username=null)` или удалить case.

#### 5. `nfs:`-callback в `FrigateAnalyzerBot.kt:221` не унифицирован

После рефакторинга в роутере будет два разных способа auth. Стоит оставить inline-комментарий.

#### 6. `AuthResult.Active.user.chatId: Long?` всё ещё nullable

`TelegramUserDto.chatId: Long?` остаётся nullable. Семантика Active обещает больше, чем декларирует. Стоит KDoc или узкий тип `ActiveUserDto`.

#### 7. Логирование: `Configured owner without DB record` каждый раз `debug`

Стоит рассмотреть `info`/`warn` для сигнализирования администратору о неактивированном owner-е.

#### 8. Гранулярность коммитов (8 коммитов) — оправдана, но Task 4 хрупок

Между Task 4 и Task 7 в ветке два разных способа auth. Если хоть один пропущен — Task 7 ломает build. Лучше: в Task 4 не оставлять `getRole(...)` вообще, а в одном коммите изменить `AuthorizationFilter` + все три call-sites.

### Suggestions

#### 9. Расширьте список call-sites авторизации в плане

Явный шаг: `grep -rn "authFilter\.getRole\|authorizationFilter\.getRole" modules/`. Превращает Task 7 из «удалить legacy» в проверяемую операцию.

#### 10. `AuthResult` мог бы выставлять `chatId: Long` напрямую

Удалит nullable-проверки в роутере и сделает контракт сильнее.

#### 11. Test helper `makeUser` стоит вынести в общий fixture

`modules/telegram/src/test/kotlin/.../fixtures/TelegramUserDtoFixtures.kt`. Не обязательно.

#### 12. FQN в test helper Task 6 Step 4 — почти гарантированный ktlint-fail

Сделать импорты сразу.

#### 13. Уберите тест `extractUsername == null` или сделайте его настоящим

Простейший фикс — построить настоящий `MessageDataCallbackQuery`-подобный объект.

#### 14. Уточните дизайн edge case: «Owner написал `/start` дважды подряд»

Зафиксировать в edge-case таблице, что повторный `/start` от ACTIVE owner — OK.

### Questions

#### 15. `CancelExportHandler` — почему callback-обработчики выпали из scope?

Это пропуск или сознательное решение оставить legacy `getRole(...)` навсегда?

#### 16. Можно ли удалить `findActiveByUsername` после рефакторинга?

Метод остаётся нужным. Стоит ли в `nfs:` callback вместо `findActiveByUsername` использовать новый `authorize`?

#### 17. Что если owner-record существует, но `chatId == null`?

Дизайн перечисляет edge cases, но не упоминает «owner-запись `ACTIVE`, но `chatId == null`».

#### 18. Coverage Task 3: должен ли быть тест «`authorize(message)` возвращает `Active` для PrivateChatImpl с username»?

Положительная ветка `authorize(message)` не покрыта.

**Сводка:** основной блокер — пропущенный `CancelExportHandler`. План не закрывает периметр call-sites. Серьёзные вопросы о хрупкости тестов, гранулярности и гонке `/start`.

---

## ollama-executor (ollama-deepseek)

### Critical Issues

#### 1. `CancelExportHandler` не учтён в плане — код не скомпилируется после Task 7

`CancelExportHandler.kt:71` и 8 вызовов `coEvery { authFilter.getRole(...) }` (строки 85, 115, 159, 197, 234, 269, 303, 342). Нужен отдельный task: заменить `getRole` на `authorize` в `CancelExportHandler` + обновить все 8 моков. Логика отказа — та же, что в `QuickExportHandler`.

#### 2. Несогласованность case-sensitivity проверки owner-а

`TelegramUserServiceImpl.isOwner()`: `username.equals(configured, ignoreCase = true)`. `AuthorizationFilter.authorize()`: `val isOwner = username == properties.owner` (case-sensitive). Telegram usernames case-insensitive. Если owner в конфиге `MyOwner`, а Telegram `myowner` — Unauthorized вместо NeedsActivation. **Стоит исправить заодно**: `val isOwner = username.equals(properties.owner, ignoreCase = true)`.

### Concerns

#### 3. Тест `authorize(message)` с `relaxed = true` — хрупкость

На практике безопасен, но стоит оставить комментарий о предположении.

#### 4. `makeActiveUser` с fully-qualified names — неопрятно

Лучше добавить 4 обычных импорта.

#### 5. `findByUsername` меняет семантику auth-фильтра

Grep показал, что другие call-sites не зависят от инварианта «только ACTIVE» — проверка пройдена.

#### 6. Количество коммитов: 7 для ~7 файлов

Tasks 1+2 (AuthResult + i18n) можно объединить — оба additive-only. Tasks 6+7 тоже кандидаты на squash. Приемлемо, замечание на усмотрение.

### Suggestions

#### 7. Добавить Task 6b — обновление `CancelExportHandler`

Заменить `authFilter.getRole(username) == null` на `when(authFilter.authorize(username))`. Обновить все 8 моков.

#### 8. Вынести `isOwner` в `TelegramUserService.isOwner()`

Использовать `userService.isOwner(username)` — унифицирует case-insensitivity и устраняет дублирование. `TelegramUserService` уже заинжектен.

#### 9. Альтернатива: добавить `chatId` в `AuthResult.NeedsActivation`

YAGNI — оставить как есть.

#### 10. Переиспользовать `common.error.activation.required` вместо нового ключа для INVITED в callback-ах?

Нет. В callback-ах мапить на `common.error.unauthorized` — корректно.

### Questions

#### 11. Нужен ли тест на case-insensitivity owner-а?

Если поправить — добавить восьмой тест.

#### 12. Что происходит, когда `owner` пишет НЕ-командный текст на чистой БД?

Получает `common.error.activation.required`. Лучше, чем текущее поведение.

#### 13. `MessageKeyParityTest` — где он находится?

Убедиться, что путь `ru.zinin.frigate.analyzer.telegram.i18n.MessageKeyParityTest` корректен.

**Итог:** Блокирующая проблема одна — `CancelExportHandler` пропущен. Архитектура с `AuthResult` грамотная. Если добавить Task 6b и подумать над case-insensitivity, план готов к реализации.
