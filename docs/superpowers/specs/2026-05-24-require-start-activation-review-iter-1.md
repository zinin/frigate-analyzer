# Review Iteration 1 — 2026-05-24 23:24

## Источник

- Design: `docs/superpowers/specs/2026-05-24-require-start-activation-design.md`
- Plan: `docs/superpowers/plans/2026-05-24-require-start-activation.md`
- Review agents: codex-executor (gpt-5.5 xhigh), ccs-executor (glm), ollama-executor (ollama-kimi, ollama-minimax, ollama-deepseek)
- Merged output: `docs/superpowers/specs/2026-05-24-require-start-activation-review-merged-iter-1.md`

## Замечания

### [CRITICAL-1] Пропущен `CancelExportHandler` — Task 7 сломает компиляцию

> `CancelExportHandler.kt:71` использует `authFilter.getRole(username)`; `CancelExportHandlerTest.kt` содержит 8 моков. Без миграции этого файла Task 7 (удаление legacy `getRole`) не скомпилируется.

**Источник:** codex, ccs-glm, ollama-kimi, ollama-minimax, ollama-deepseek (5/5)
**Статус:** Автоисправлено
**Ответ:** Добавлен Task 6b в plan с полной миграцией `CancelExportHandler` и обновлением 8 моков. Сигнатура: `authorize(username)`, поведение сохраняется (`NeedsActivation` и `Unauthorized` отвечают `common.error.unauthorized`). Файл также добавлен в список изменяемых в design.
**Действие:** Plan: новый Task 6b после Task 6 (~8 шагов). Design: `CancelExportHandler.kt` + `CancelExportHandlerTest.kt` добавлены в «Затронутые файлы → Изменяемые». Task 7 описание обновлено: «После Tasks 5, 6 и 6b...».

---

### [CRITICAL-2] Unknown slash-команды не покрыты целью

> `onContentMessage` early-return-ит для `/`-текста; `onCommand` зарегистрирован только для известных handler-ов. Неактивированный owner на `/foo` получит тишину вместо «сначала /start». Pre-existing, но формулировка цели не точная.

**Источник:** codex (1/5)
**Статус:** Обсуждено с пользователем (Вариант A)
**Ответ:** Сузить формулировку цели до «известных команд из CommandHandler-списка». Pre-existing поведение для unknown commands явно вынесено в Out of scope. Никаких изменений в коде.
**Действие:** Design «Goal» секция: явный список команд + строка «Out of scope: unknown slash-команды».

---

### [CRITICAL-3] Гонка двух `/start` от owner-а описана неверно

> `inviteUser` делает check-then-insert, при concurrent-вызовах может упереться в unique-constraint. В edge-case таблице помечено как ✅, что неверно.

**Источник:** codex, ollama-kimi, ollama-minimax (3/5)
**Статус:** Автоисправлено
**Ответ:** Снять ✅, явно вынести в Non-goals как pre-existing limitation. Edge-case таблица обновлена.
**Действие:** Design «Non-goals» + edge-case row (значок ✅ → ⚠️ + пояснение «pre-existing limitation, out of scope»).

---

### [CRITICAL-4] Несогласованность case-sensitivity owner-а

> `TelegramUserServiceImpl.isOwner()` уже case-insensitive, новый `AuthorizationFilter.authorize()` использует `==`. При `MyOwner` в env и `myowner` в Telegram — Unauthorized вместо NeedsActivation, ломает onboarding.

**Источник:** codex (Concerns), ollama-deepseek (Critical) (2/5)
**Статус:** Обсуждено с пользователем (Вариант B)
**Ответ:** Использовать `userService.isOwner(username)` в `AuthorizationFilter` — DRY, case-insensitive, единая точка истины. Добавлен 9-й unit-тест: «authorize(`OWNERUSER`) при config=`ownerUser` → Active(OWNER, ...)».
**Действие:** Design: pseudocode логики `authorize` использует `userService.isOwner(username)` + примечание про case-insensitivity. Plan: реализация `AuthorizationFilter` (Task 4 и Task 7) использует `userService.isOwner(username)`; в `AuthorizationFilterTest` добавлен `@BeforeEach setupOwnerCheck` с mock-ом `userService.isOwner`, плюс тест #9.

---

### [CRITICAL-5] Callback от owner-а без записи теперь даёт отказ

> Раньше `getRole(username)` пускал owner-а по env-конфигу даже без записи в БД (для callback). Новый `authorize` вернёт `NeedsActivation` → отказ. Это subtle поведенческое изменение.

**Источник:** codex (1/5)
**Статус:** Автоисправлено
**Ответ:** Зафиксировать как принимаемое изменение поведения в Non-goals: «callback от owner-а без записи теперь даёт отказ; данные за кнопкой всё равно отсутствуют после reset/restore БД». Сценарий маловероятен в проде.
**Действие:** Design Non-goals + edge-case row для callback owner-without-record.

---

### [CONCERN-1] Нет интеграционного теста для router behavior

> `FrigateAnalyzerBot.registerRoutes()` напрямую не покрыт тестами (BehaviourContext сложно мокать). Риск опечаток в i18n-ключах, lang-источниках, забытого `return@onCommand`.

**Источник:** codex, ccs-glm (2/5)
**Статус:** Обсуждено с пользователем (Вариант B)
**Ответ:** Добавлен manual smoke-test чеклист в plan (Task 5 Step 5) с 10 сценариями: owner на чистой БД, владелец после /start, INVITED user, не-приглашённый user, case-insensitivity. Покрывает ключевые ветки роутера.
**Действие:** Plan Task 5: новый Step 5 «Manual smoke-test чеклист» с таблицей сценариев; остальные шаги сдвинуты (Step 5→6 для коммита).

---

### [CONCERN-2 + CONCERN-3] `authorize(message)` happy-path не покрыт + relaxed-mock хрупок

> Из 7 тестов только 1 для `authorize(message)` — отрицательный. Использует `mockk<CommonMessage<MessageContent>>(relaxed = true)`, что хрупко (зависит от деталей MockK).

**Источник:** codex (Concerns), ccs-glm (#2, #5), ollama-kimi (#5), ollama-minimax (#4, #18), ollama-deepseek (#3)
**Статус:** Автоисправлено
**Ответ:** Заменить хрупкий relaxed-mock на реальную конструкцию `PrivateContentMessage` mock + добавить happy-path тест #8 («Active(USER) for PrivateContentMessage with active user»).
**Действие:** Design «Тестирование»: добавлена строка #8 и заметка про реальную конструкцию `PrivateContentMessage`. Plan Task 3 Step 1: переписаны тесты #7 (null username) и #8 (happy-path) с использованием `mockk<PrivateContentMessage<MessageContent>>` + stub `user.username`.

---

### [CONCERN-4] FQN в хелпере `makeActiveUser` — запах кода

> Task 6 Step 4 предлагает использовать `ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto(...)` в теле функции. Снижает читаемость, почти гарантированный ktlint-fail.

**Источник:** codex (Suggestion), ccs-glm (#4), ollama-kimi (#3), ollama-minimax (#12), ollama-deepseek (#4) (5/5)
**Статус:** Автоисправлено
**Ответ:** Добавить нормальные импорты (`TelegramUserDto`, `UserStatus`, `Instant`, `UUID`) в шапку файла, использовать короткие имена.
**Действие:** Plan Task 6 Step 4: блок переписан с импортами наверху + хелпер на коротких именах.

---

### [CONCERN-5] Username-only авторизация закрепляется без проверки userId/chatId

> Если Telegram username переиспользован другим аккаунтом — новый владелец username получит доступ.

**Источник:** codex (1/5)
**Статус:** Автоисправлено
**Ответ:** Зафиксировать как pre-existing model в Non-goals, не менять в рамках этого PR.
**Действие:** Design Non-goals: «Username-only авторизация — pre-existing модель, не меняется».

---

### [CONCERN-6] `onContentMessage` обрабатывает не только текст

> Дизайн называет блок «некомандный текст», но фактически покрывает фото, стикеры, voice. Pre-existing.

**Источник:** codex (1/5)
**Статус:** Автоисправлено
**Ответ:** Переименовать в дизайне «non-command content (text, photo, sticker, voice etc.)» с уточнением про реальное покрытие.
**Действие:** Design `onContentMessage` секция: переименована + пояснение поведения.

---

### [CONCERN-7] Group/channel behavior не определён

> `extractUsername` принимает только `PrivateContentMessage` — group-команды → Unauthorized. Не зафиксировано в дизайне.

**Источник:** codex (1/5)
**Статус:** Автоисправлено
**Ответ:** Зафиксировать private-only invariant в Non-goals + добавить edge case в таблицу.
**Действие:** Design Non-goals: «Bot — private-only invariant». Edge-case таблица: row «Group/channel-сообщение → Unauthorized».

---

### [CONCERN-8] `nfs:`-callback не унифицирован с новым auth

> После рефакторинга в роутере будет два разных способа auth: новый `authorize(...)` и старый `findActiveByUsername` в `nfs:`-callback.

**Источник:** ollama-minimax (1/5)
**Статус:** Автоисправлено
**Ответ:** Добавить inline-комментарий в `nfs:`-callback handler, объясняющий намеренность direct `findActiveByUsername`-вызова.
**Действие:** Plan Task 5 Step 1: расширен step добавлением кода для inline-комментария. Design «Затронутые файлы» отмечен.

---

### [CONCERN-9] `chatId: Long?` остаётся nullable в `AuthResult.Active`

> `TelegramUserDto.chatId` nullable. Семантика Active обещает больше, чем декларирует тип.

**Источник:** ollama-minimax (1/5)
**Статус:** Автоисправлено
**Ответ:** Добавить явный invariant note: «ACTIVE-запись всегда имеет chatId, заполняемый `activateUser`». KDoc-уровень.
**Действие:** Design «Новый sealed-тип AuthResult»: расширено описание Active с пометкой про инвариант `chatId != null`.

---

### [CONCERN-10 + CONCERN-11] Гранулярность коммитов + хрупкое окно

> 7-8 коммитов избыточно; между Task 4 и Task 7 «два API» одновременно, забытый call-site = broken build.

**Источник:** codex, ccs-glm, ollama-kimi, ollama-minimax, ollama-deepseek (5/5)
**Статус:** Обсуждено с пользователем (Вариант A)
**Ответ:** Оставить 8 коммитов как есть. Хрупкость окна смягчена двумя grep audit-шагами (Step 0 в Task 3 + Step 0 в Task 7), уже добавленными в auto-fixes.
**Действие:** Никаких изменений в plan не требуется — структура уже соответствует. Grep audits добавлены в Task 3 и Task 7.

---

### [CONCERN-12] Log level для «owner without DB record»

> Сейчас `debug`. Ревьюер предлагает `info`/`warn` чтобы оператор видел в проде.

**Источник:** ollama-minimax (1/5)
**Статус:** Обсуждено с пользователем (Вариант A — info)
**Ответ:** Заменить `debug` на `info` для owner-without-record ветки. Текст сообщения: «Configured owner without DB record (waiting for /start)».
**Действие:** Plan Task 4 и Task 7: оба блока `record == null && isOwner -> ...` переписаны на `logger.info`.

---

### [CONCERN-13] Несогласованность design vs plan: `record.toDto()`

> Дизайн пишет `Active(OWNER, record.toDto())`, а `findByUsername` возвращает DTO.

**Источник:** ccs-glm (1/5)
**Статус:** Автоисправлено
**Ответ:** Fix typo в design.
**Действие:** Design pseudocode `authorize(username)`: `record.toDto()` → `record`.

---

### [SUGGESTION-1] Использовать `userService.isOwner` в фильтре

> DRY, единая точка истины для case-insensitivity.

**Источник:** codex (Concerns), ollama-deepseek (#8) (2/5)
**Статус:** Автоисправлено (резолвится через CRITICAL-4 Вариант B)
**Ответ:** Принят как часть CRITICAL-4 — оба замечания указывают на одно решение.
**Действие:** Тот же, что и для CRITICAL-4.

---

### [SUGGESTION-2] `NeedsActivation` несёт `username`/`isOwner`

> Future-proof для аудита.

**Источник:** codex (Suggestion), ccs-glm (#10) (2/5)
**Статус:** Отклонено (YAGNI)
**Ответ:** Сами ревьюеры рекомендуют YAGNI. Оставить `data object NeedsActivation` без полей. Текущая логика не использует context — добавление полей было бы преждевременной оптимизацией.

---

### [SUGGESTION-3] Owner-меню при boot не регистрируется для owner без записи

> Pre-existing: `FrigateAnalyzerBot.start()` использует `findActiveByUsername`, на чистой БД owner-меню недоступно до первого `/start`.

**Источник:** ollama-kimi (1/5)
**Статус:** Автоисправлено
**Ответ:** Зафиксировать как pre-existing limitation в Non-goals.
**Действие:** Design Non-goals: явное упоминание «Owner-меню при boot для owner-а без записи — pre-existing limitation, не исправляется».

---

### [SUGGESTION-4] «Use build skill» в test commands

> Точечные `./gradlew :module:test` — это full module test, не нужно ли применять build skill?

**Источник:** ollama-minimax (1/5)
**Статус:** Отклонено
**Ответ:** Plan уже корректно прописал: «Полный build: через `build` skill / build-runner агента (CLAUDE.md запрещает прямой `./gradlew build`)». Точечный `./gradlew :module:test` не запрещён CLAUDE.md и используется по тексту. Никаких изменений не требуется.

---

### [SUGGESTION-5] Edge case «Owner /start повторно»

> Нет упоминания нормального сценария: ACTIVE owner шлёт /start ещё раз.

**Источник:** ollama-minimax (1/5)
**Статус:** Автоисправлено
**Ответ:** Добавить строку в edge-case таблицу.
**Действие:** Design edge-case row: «ACTIVE owner шлёт /start повторно — StartCommandHandler отвечает welcome/already.subscribed, activate возвращает 0 строк, не регрессия».

---

### [SUGGESTION-6] `AuthResult.Active` уже несёт `chatId` через `user`

> Положительное подтверждение корректности дизайна.

**Источник:** codex (Suggestion), ollama-kimi (#4) (2/5)
**Статус:** Отклонено (positive confirmation)
**Ответ:** Дизайн уже корректный — не дублируем `chatId` отдельным полем. Никаких изменений не требуется.

---

### [QUESTION-1] Owner с INVITED + chatId — реальный сценарий?

**Источник:** ccs-glm, косвенно codex (2/5)
**Статус:** Автоисправлено
**Ответ:** Пометить как теоретический сценарий (восстановление из снапшота), defensive обработка.
**Действие:** Design edge-case row: «Owner с записью INVITED (теоретически, например после restore from snapshot)».

---

### [QUESTION-2] Owner ACTIVE без chatId — возможно?

**Источник:** ollama-minimax (1/5)
**Статус:** Автоисправлено
**Ответ:** Невозможно по инварианту `activateUser` (chatId устанавливается в той же транзакции, что и status=ACTIVE). Зафиксировать как DB-invariant.
**Действие:** Design edge-case row + расширенное описание Active в sealed-типе.

---

### [QUESTION-3] Grep-аудит call-sites `getRole`

**Источник:** ccs-glm, ollama-kimi, ollama-minimax, ollama-deepseek (4/5)
**Статус:** Автоисправлено
**Ответ:** Добавить явный grep-шаг в plan (Step 0 в Task 3 — pre-audit перед началом миграции; Step 0 в Task 7 — final audit перед удалением legacy).
**Действие:** Plan Task 3: новый «Step 0: Аудит call-sites». Plan Task 7: новый «Step 0: Финальный grep-аудит».

---

### [QUESTION-4] Можно ли удалить `findActiveByUsername`?

**Источник:** ollama-minimax (1/5)
**Статус:** Автоисправлено
**Ответ:** Out of scope. Метод остаётся используется в `nfs:`-callback (намеренно — рассылка только ACTIVE), `FrigateAnalyzerBot.start()` (owner-меню boot — отдельный pre-existing issue) и `TelegramNotificationServiceImpl`. Документируется через CONCERN-8 (inline-комментарий про nfs:) и Non-goals.
**Действие:** Plan Task 5: inline-комментарий в `nfs:`-callback. Design Non-goals: owner-меню boot отмечен как pre-existing.

---

### [QUESTION-5] `MessageKeyParityTest` — где?

**Источник:** ollama-deepseek (1/5)
**Статус:** Отклонено (verified)
**Ответ:** Файл существует по пути `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/i18n/MessageKeyParityTest.kt`. Plan корректен.

---

### [QUESTION-6] Ключ для `CancelExport` NeedsActivation

**Источник:** ollama-kimi (1/5)
**Статус:** Автоисправлено (резолвится через CRITICAL-1)
**Ответ:** `common.error.unauthorized` — единообразно с `QuickExportHandler`. Зафиксировано в plan Task 6b и в design Non-goals.

---

### [QUESTION-7] Тест на case-insensitivity owner

**Источник:** ollama-deepseek (1/5)
**Статус:** Автоисправлено (резолвится через CRITICAL-4 Вариант B)
**Ответ:** Принят. Plan Task 3 Step 1 содержит 9-й тест: «authorize(`OWNERUSER`) at config=`ownerUser` → Active(OWNER, ...)».
**Действие:** Тот же, что и для CRITICAL-4.

---

## Изменения в документах

| Файл | Изменение |
|------|-----------|
| `docs/superpowers/specs/2026-05-24-require-start-activation-design.md` | Goal сужен до известных команд; Non-goals расширены (callback owner change, race /start, username-only auth, private-only, owner-menu boot); Architecture описание Active с invariant chatId; pseudocode `userService.isOwner` + комментарий case-insensitivity; pseudocode `record.toDto()` → `record`; onContentMessage переименован в non-command content; testing section + тест #8 + заметка про real PrivateContentMessage; CancelExportHandler + Test добавлены в Затронутые файлы; edge-case таблица расширена (race ⚠️, повторный /start, ACTIVE без chatId, owner callback after reset ⚠️, group/channel) |
| `docs/superpowers/plans/2026-05-24-require-start-activation.md` | Затронутые файлы расширены (CancelExport, nfs: comment); Task 3: Step 0 grep audit + Step 1 переписан с PrivateContentMessage construction + BeforeEach setupOwnerCheck + тест #9 case-insensitivity; Task 4 и Task 7: `userService.isOwner` + log level info; Task 5: Step 1 расширен nfs: комментарием + новый Step 5 manual smoke-test чеклист; Task 6 Step 4: импорты вместо FQN; новый Task 6b: миграция CancelExportHandler + 8 моков; Task 7: Step 0 финальный grep audit; ожидаемые коммиты обновлены (8 шт.) |
| `docs/superpowers/specs/2026-05-24-require-start-activation-review-merged-iter-1.md` | Создан merged-файл с ревью всех 5 агентов |

## Статистика

- Всего замечаний: 31 (5 critical + 13 concerns + 6 suggestions + 7 questions)
- Автоисправлено (без обсуждения): 17 (CRITICAL-1, 3, 5; CONCERN-2+3, 4, 5, 6, 7, 8, 9, 13; SUGGESTION-3, 5; QUESTION-1, 2, 3, 4)
- Авто-применено после анализа: 1 (CONCERN-10+11: оставить как есть)
- Обсуждено с пользователем: 4 (CRITICAL-2, 4; CONCERN-1, 12)
- Отклонено: 6 (SUGGESTION-2, 4, 6; QUESTION-5; QUESTION-6/7 резолвятся через CRITICAL-1/4 — учтены там)
- Повторов (автоответ): 0 (первая итерация)
- Пользователь сказал «стоп»: Нет
- Агенты: codex-executor (gpt-5.5 xhigh), ccs-executor (glm), ollama-executor (kimi, minimax, deepseek)
