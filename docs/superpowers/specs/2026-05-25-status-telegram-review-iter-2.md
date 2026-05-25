# Review Iteration 2 — 2026-05-25

## Источник

- Design: `docs/superpowers/specs/2026-05-25-status-telegram-design.md`
- Plan: `docs/superpowers/plans/2026-05-25-status-command.md`
- Review agents: codex-executor (gpt-5.5 xhigh) ✓, ccs-executor (glm) ⚠️ stalled, ollama-executor (kimi K2.6) ⚠️ stalled, ollama-executor (minimax M2.7) ✓, ollama-executor (deepseek V4-Pro) ✓
- Merged output: `docs/superpowers/specs/2026-05-25-status-telegram-review-merged-iter-2.md`

## Замечания

### [CRITICAL-1] Real-bundle i18n тест не собирается (нет AssertJ)

> Plan строки ~1117 используют `assertThat(out).contains(...)`, но `modules/telegram/build.gradle.kts:23-26` имеет только `kotlin.test.junit5`, `coroutines.test`, `mockk`. Real-bundle RU-тест оставлен как "body omitted" — пропускает половину защиты от placeholder-сдвигов.

**Источник:** codex
**Статус:** Автоисправлено
**Ответ:** Переписать `StatusMessageFormatterI18nTest` на `kotlin.test`-API (`assertTrue` / `assertFalse` / `assertEquals` — те же, что используются в `MessageResolverTest.kt`). Добавлен полный код RU-теста: проверка `оффлайн 7 мин (последняя 09:53:00)`, `frame 1/4`, отсутствие server-id в плейсхолдерах ALIVE, и проверка локализованного title "Статус Frigate Analyzer".
**Действие:** Plan Task 9 Step 1 — i18n integration test rewritten; добавлены `kotlin.test.assertFalse` import, `Locale`/`ZoneOffset`/`ReloadableResourceBundleMessageSource` imports.

---

### [CRITICAL-2] Env var именуется `SIGNAL_LOSS_ENABLED`, не `APPLICATION_SIGNAL_LOSS_ENABLED`

> Plan строка ~830 даёт i18n value `Monitoring disabled (set APPLICATION_SIGNAL_LOSS_ENABLED=true to enable)`, но реальный конфиг (`modules/core/src/main/resources/application.yaml:49`) использует `${SIGNAL_LOSS_ENABLED:true}` без префикса. Manual sanity check в Task 12 Step 5 содержит двойную ошибку — указывает `APPLICATION_SIGNAL_LOSS_ENABLED=false`, но ожидает старый текст `signal-loss.enabled=false`.

**Источник:** codex
**Статус:** Автоисправлено
**Ответ:** Все вхождения `APPLICATION_SIGNAL_LOSS_ENABLED` заменены на `SIGNAL_LOSS_ENABLED` — в EN/RU i18n значениях, в design example Disabled monitoring, в manual sanity check. Текст ожидаемого сообщения в manual check синхронизирован с i18n value.
**Действие:** Plan Task 7 (en+ru), Plan Task 12 Step 5, Design "Disabled monitoring" section.

---

### [CRITICAL-3] Server emoji `🟢/🔴` — design vs plan inconsistency

> Design example серверных строк без emoji (`srv-a ALIVE  frame 2/4 ...`); plan code префиксует `🟢/🔴` как у камер. Камеры консистентны, серверы — нет.

**Источник:** codex (CONCERN-3 + QUESTION-1), ollama-deepseek (CRITICAL-2 + QUESTION-1)
**Статус:** Обсуждено с пользователем
**Ответ:** Вариант A — оставить emoji, обновить design example. Симметрия с секцией камер, быстрая идентификация DEAD-сервера на маленьком экране, минимум правок (plan уже корректен).
**Действие:** Design "Telegram-команда / StatusMessageFormatter" — обновлён ASCII example серверов с `🟢/🔴` префиксами; добавлен поясняющий абзац.

---

### [CRITICAL-4] `reply(message, text, parseMode=HTMLParseMode)` не verified в проекте

> Plan Task 10 Step 3 использует `reply(message, text, parseMode = HTMLParseMode)`. Проверка кода: ни один существующий handler не вызывает `reply` с `parseMode`. `VersionCommandHandler` использует `reply(message, text)` БЕЗ parseMode, `TimezoneCommandHandler` использует `sendTextMessage`, `TelegramNotificationSender` использует `bot.sendTextMessage(..., parseMode=HTMLParseMode, replyParameters=...)`. Существующий fallback в плане откладывает проверку до runtime — лучше принять проверенный API сразу.

**Источник:** ollama-deepseek (CRITICAL-1 + SUGGESTION-1)
**Статус:** Автоисправлено
**Ответ:** Handler переключён на `sendTextMessage(message.chat, text, parseMode = HTMLParseMode, replyParameters = ReplyParameters(message.metaInfo))` — proven pattern в проекте. Импорты обновлены, runtime-fallback блок удалён из Task 10 Step 4.
**Действие:** Plan Task 10 Step 3 handler code + design "StatusCommandHandler" section.

---

### [CONCERN-1] `StatusControllerTest` не использует base-path `/frigate-analyzer`

> `application.yaml:11` задаёт `spring.webflux.base-path: /frigate-analyzer`. Manual curl в Task 12 использует `/frigate-analyzer/status`, но `StatusControllerTest` (Task 6 Step 1) ходит на `/status`. Даже если WebTestClient это проглатывает, тест не фиксирует реальный внешний контракт.

**Источник:** codex
**Статус:** Автоисправлено
**Ответ:** URI в WebTestClient изменён на `/frigate-analyzer/status`, добавлен комментарий ссылающийся на `application.yaml:11`.
**Действие:** Plan Task 6 Step 1.

---

### [CONCERN-2] `StatusServiceTest` нет теста для `monitoringEnabled=true + empty snapshot`

> Design (line 442 после iter-2 правок stale-следов) явно различает `monitoringEnabled=false` (signal-loss disabled) и `items=emptyList()` при monitoringEnabled=true (signal-loss on, но snapshot пуст). В StatusServiceTest есть только absent-monitor и non-empty cases.

**Источник:** codex
**Статус:** Автоисправлено
**Ответ:** Добавлен новый тест `collect returns monitoringEnabled=true with empty items when snapshot empty` — mock'aет `SignalLossMonitorTask.snapshotStates()` возвращающий `emptyMap()` и assert'ит правильное состояние секции.
**Действие:** Plan Task 5 Step 1.

---

### [CONCERN-3] `appendByCamera` padding баг — escape ДО padding ломает выравнивание

> В `appendByCamera` (Task 9 Step 3) escape применяется к ячейкам, потом `formatRow` считает widths по escaped длинам. Для `camId = "cam<&>"` после escape становится `cam&lt;&amp;&gt;` (19 char), что делает column width = 19 — выравнивание сломано. В `appendCameras`/`appendServers` сделано правильно (escape после padding).

**Источник:** codex (CONCERN-4 — частично, только byCamera; cameras/servers OK)
**Статус:** Автоисправлено
**Ответ:** `appendByCamera` переписан: widths считаются по RAW длинам, `padEnd/padStart` применяются ДО escape, escape только к ячейке 0 (camId — user-derived), числовые столбцы безопасны без escape. Добавлен поясняющий комментарий.
**Действие:** Plan Task 9 Step 3 — `appendByCamera` function body.

---

### [CONCERN-4] Stale-следы iter-1 в design

> Design содержит ссылки на несуществующее поле `user.olsonCode` (Edge cases line 456) и устаревший путь теста handler в `telegram` модуле (Testing line 466), хотя iter-1 уже переместил handler в `core` и заменил на `getUserZone(chatId)`.

**Источник:** codex
**Статус:** Автоисправлено
**Ответ:** Edge case переформулирован: `TelegramUserService.getUserZone(chatId)` не нашёл валидную TZ → возвращает `ZoneOffset.UTC` (контракт `getUserZone`, не handler). Testing table обновлена: handler-тест путь = `core/src/test/.../bot/handler/`, добавлен `JacksonConfigurationTest` и `SignalLossMonitorTaskSnapshotTest`, обновлено описание формат-теста (layered defence + I18nTest), удалён "exception → common.error.generic" promise.
**Действие:** Design Edge cases + Testing sections.

---

### [CONCERN-5] `StatusControllerTest` требует Docker (`IntegrationTestBase`)

> `IntegrationTestBase` поднимает Docker Compose (PostgreSQL + Liquibase) в `companion object init {}`. Чистый JSON-shape тест в этом не нуждается, но альтернатива `@WebFluxTest + @Import + mocks` пропускает реальный ObjectMapper round-trip. Не блокирующая проблема — нужно документировать.

**Источник:** ollama-deepseek
**Статус:** Автоисправлено
**Ответ:** Добавлен комментарий перед классом `StatusControllerTest` с явным указанием Docker dependency и ссылкой на DB-free `JacksonConfigurationTest` (Step 1a), который покрывает ISO-8601 контракт независимо.
**Действие:** Plan Task 6 Step 1.

---

### [CONCERN-6] `offlineFor` defensive fallback в formatter маскирует баг сервиса

> Formatter (`appendCameras` OFFLINE branch) использует `(item.offlineFor ?: Duration.between(item.lastSeenAt, now)).coerceAtLeast(...)`. `StatusService.toDto()` всегда заполняет `offlineFor` для OFFLINE — fallback в форматтере молча маскирует контрактное нарушение.

**Источник:** ollama-deepseek (CONCERN-2 + SUGGESTION-2)
**Статус:** Автоисправлено
**Ответ:** Заменено на `requireNotNull(item.offlineFor) { "offlineFor must not be null for OFFLINE camera ${item.camId} (StatusService.toDto contract violation)" }.coerceAtLeast(Duration.ZERO)`. Громкий fail вместо тихого маскирования.
**Действие:** Plan Task 9 Step 3 — `appendCameras` OFFLINE branch.

---

### [CONCERN-7] `StatusMessageFormatterI18nTest` не задаёт `setFallbackToSystemLocale(false)` / `setDefaultLocale`

> `MessageResolverTest.kt:12-16` ставит обе настройки; без них тест нестабилен на non-English dev машинах (system locale меняет fallback resolution).

**Источник:** ollama-deepseek (CONCERN-3 + SUGGESTION-3)
**Статус:** Автоисправлено
**Ответ:** В I18n test setup добавлены `setFallbackToSystemLocale(false)` и `setDefaultLocale(Locale.forLanguageTag("en"))`. Объединено с правкой CRITICAL-1 (kotlin.test rewrite).
**Действие:** Plan Task 9 Step 1.

---

### [CONCERN-8] `CameraStatistics` vs `CameraStatisticsDto` — идентичные позиционные поля, разные пакеты

> DTO (model.dto, SQL projection) и response model (model.response) имеют тот же конструктор: `(camId, recordingsCount, recordingsProcessed, detectionsCount)`. Легко перепутать при будущих правках `StatusService.buildRecordings()`.

**Источник:** ollama-deepseek
**Статус:** Автоисправлено
**Ответ:** Добавлен поясняющий комментарий в `StatusService.buildRecordings()` перед `.map { dto -> CameraStatistics(...) }`: указывает на оба типа, объясняет необходимость маппинга (не утечка SQL/R2DBC слоя в response), и заодно фиксирует sort invariant (SQL уже ORDER BY cam_id ASC).
**Действие:** Plan Task 5 Step 3 — `buildRecordings()`.

---

### [SUGGESTION-1] `--tests` фильтр в Task 9 не запускает sibling I18nTest

> `--tests StatusMessageFormatterTest` не захватывает `StatusMessageFormatterI18nTest` (sibling класс в том же файле). Регрессии placeholder-сдвигов будут незаметны до полного билда.

**Источник:** codex
**Статус:** Автоисправлено
**Ответ:** Заменено на glob: `--tests "StatusMessageFormatter*Test"`.
**Действие:** Plan Task 9 Step 2 + Step 4.

---

### [SUGGESTION-2] Формат больших чисел `12 450`

> Design example показывает `Total: 12 450` (с пробелом как тысячный разделитель), реализация делает plain `r.total.toString()` → `12450`.

**Источник:** codex
**Статус:** Отклонено
**Ответ:** Design example был иллюстративный (mobile readable form). Реальная реализация через MessageFormat не поддерживает группировку чисел красиво без отдельного локалезависимого NumberFormat-helper'а — over-engineering для diagnostic endpoint. `toString()` работает; если когда-нибудь захочется красиво — добавится в один helper позже.
**Действие:** Без изменений.

---

### [SUGGESTION-3] Escape-тесты только для `cameras.items.camId`, не для `byCameras.camId` и `server.id`

> Текущие тесты покрывают только секцию `cameras`. Регрессия escape в by-camera или server секциях не будет поймана.

**Источник:** codex
**Статус:** Автоисправлено
**Ответ:** Добавлены два теста: `format escapes HTML special chars in byCameras camId` и `format escapes HTML special chars in server id`. Каждый строит snapshot с одним элементом содержащим `<&>` в соответствующем поле и проверяет наличие escaped формы (`&lt;&amp;&gt;`) и отсутствие raw.
**Действие:** Plan Task 9 Step 1 — `StatusMessageFormatterTest` class.

---

### [SUGGESTION-4] Документировать sort invariant `byCameras` в комментарии

> SQL уже сортирует ORDER BY cam_id ASC, но это implicit knowledge — будущий рефакторинг репозитория может это сломать без видимого failure.

**Источник:** ollama-minimax
**Статус:** Автоисправлено (объединено с CONCERN-8)
**Ответ:** Комментарий в `buildRecordings()` теперь содержит фразу "SQL already orders by `cam_id ASC`, so no additional sort is needed here".
**Действие:** Plan Task 5 Step 3 (combined edit).

---

### [DISMISSED-1] minimax CONCERN-1 — weakly-consistent snapshot

> "Документация в KDoc достаточна, не блокирует."

**Источник:** ollama-minimax
**Статус:** Отклонено
**Ответ:** Подтверждение текущего решения, не actionable. KDoc уже расширен в iter-1 (CONCERN-13).
**Действие:** Без изменений.

---

### [DISMISSED-2] minimax CONCERN-2 — пустой state при первом tick

> "Поведение согласовано с дизайном."

**Источник:** ollama-minimax
**Статус:** Отклонено
**Ответ:** Согласовано с design (Edge case "state map пуст"). Поведение покрыто новым тестом CONCERN-2 этой итерации.
**Действие:** Без изменений.

---

### [DISMISSED-3] minimax CONCERN-3 — секундная гранулярность `formatDuration`

> "YAGNI, наследуется."

**Источник:** ollama-minimax
**Статус:** Отклонено
**Ответ:** Наследовано от `SignalLossMessageFormatter`; изменение требует тач существующих notification-форматов, что вне scope `/status`.
**Действие:** Без изменений.

---

### [DISMISSED-4] minimax QUESTION-1 — pollInterval upper-bound

> "Есть ли upper-bound check для `pollInterval` в `SignalLossProperties`?"

**Источник:** ollama-minimax
**Статус:** Отклонено
**Ответ:** Вне scope `/status` команды. Если это actual issue — отдельный bug в `SignalLossProperties` валидации, не в /status feature.
**Действие:** Без изменений.

---

### [DISMISSED-5] minimax QUESTION-3 — dynamic toggle signal-loss runtime

> "Нетипичный сценарий dev-env, out-of-scope?"

**Источник:** ollama-minimax
**Статус:** Отклонено
**Ответ:** Out-of-scope. Spring `@ConditionalOnProperty` не реагирует на runtime изменения свойств — feature toggle требует restart, что и есть текущий контракт.
**Действие:** Без изменений.

---

### [REPEAT-1] minimax SUGGESTION-2 — 30+ cameras и 4096-char limit

> "При 30+ камерах с длинными `camId` риск приблизиться к 4096-char limit."

**Источник:** ollama-minimax
**Статус:** Повтор (iter-1, CONCERN-5)
**Ответ:** Iter-1 CONCERN-5 — Вариант A — out of scope; проект ≤10 cameras + 1–3 servers в практике, realistic message ≈1500 chars. YAGNI; добавится при необходимости.
**Действие:** Без изменений.

---

### [REPEAT-2] minimax QUESTION-2 — StatusService.collect() логирование ошибок

> "Нужно ли явное try/catch с логированием на уровне service, или router-level достаточно?"

**Источник:** ollama-minimax
**Статус:** Повтор (iter-1, CONCERN-3)
**Ответ:** Iter-1 CONCERN-3 — `FrigateAnalyzerBot.registerRoutes()` оборачивает диспетчеризацию try/catch на уровне роутера. Применяется и к Service (исключения проброшатся вверх). Local handler/service try/catch не нужен.
**Действие:** Без изменений.

---

## Stalled reviewers

- **ccs-glm** (CCS, GLM-5.1) — прочитал 56 файлов проекта, `raw.jsonl` остановился на 15:24:52 и не обновлялся 12+ минут. Финальный `output.txt` не сгенерирован. Пропущен по решению пользователя.
- **ollama-kimi** (Ollama, Kimi K2.6 cloud) — оставался в tool-use фазе (Grep/Read), не дошёл до финальной генерации. Watchdog last alive 15:26:58, потом перестал писать события. Пропущен по решению пользователя.

При желании их можно перезапустить в iter-3.

## Изменения в документах

| Файл | Изменение |
|------|-----------|
| `docs/superpowers/specs/2026-05-25-status-telegram-design.md` | Disabled monitoring example обновлён на `SIGNAL_LOSS_ENABLED`; server example с emoji `🟢/🔴`; Edge cases — `getUserZone` контракт вместо `user.olsonCode`; Testing table обновлена (handler в `core`, добавлены `JacksonConfigurationTest` и `SignalLossMonitorTaskSnapshotTest`, layered defence для formatter); handler signature использует `sendTextMessage` |
| `docs/superpowers/plans/2026-05-25-status-command.md` | Task 5 — новый empty-snapshot test, комментарий в `buildRecordings` про DTO vs response model; Task 6 — base-path `/frigate-analyzer/status` в WebTestClient URI + Docker-dep комментарий; Task 7 — env var `SIGNAL_LOSS_ENABLED` без префикса; Task 9 — `appendByCamera` padding fix, `requireNotNull` в OFFLINE branch, два escape-теста для byCameras/server-id, real-bundle i18n test на kotlin.test API + полный RU test + `setFallbackToSystemLocale`, glob `--tests "StatusMessageFormatter*Test"`; Task 10 — handler на `sendTextMessage` с `replyParameters`, удалён runtime fallback; Task 12 Step 5 — `SIGNAL_LOSS_ENABLED=false` |
| `docs/superpowers/specs/2026-05-25-status-telegram-review-merged-iter-2.md` | Новый — merged outputs от 3 ответивших агентов (codex, minimax, deepseek) + статус 2 застрявших |
| `docs/superpowers/specs/2026-05-25-status-telegram-review-iter-2.md` | Новый — этот файл |

## Статистика

- Всего замечаний: 22 (из 3 ответивших агентов; 2 застрявших не анализируются)
- Автоисправлено (без обсуждения): 14
- Авто-применено после анализа: 0
- Обсуждено с пользователем: 1 (CRITICAL-3 server emoji)
- Отклонено: 5 (1 design example sugar + 3 minimax-confirmations + 2 out-of-scope questions)
- Повторов (автоответ): 2
- Пользователь сказал "стоп": Нет
- Агенты: codex-executor (gpt-5.5 xhigh) ✓, ccs-executor (glm) ⚠️ stalled, ollama-executor × 3 (kimi ⚠️ stalled, minimax ✓, deepseek ✓)
