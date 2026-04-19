# Review Iteration 1 — 2026-04-19

## Источник

- **Design:** `docs/superpowers/specs/2026-04-19-ai-description-design.md`
- **Plan:** `docs/superpowers/plans/2026-04-19-ai-description-plan.md`
- **Review agents (7):** codex-executor (gpt-5.4, xhigh), gemini-executor (3.1 Pro Preview), ccs-executor × 5 профилей (glm, albb-glm, albb-qwen, albb-kimi, albb-minimax)
- **Merged output:** `docs/superpowers/specs/2026-04-19-ai-description-review-merged-iter-1.md`
- **Сырых замечаний:** 106
- **Уникальных групп после дедупликации:** 52

## Внешние проверки API, задействованные в разрешении

Для точности разрешения SDK-/Telegram-вопросов запускались отдельные проверки:

1. **Spring AI Claude SDK 1.0.0** (`org.springaicommunity:claude-code-sdk`) — context7 не знает, получили через Maven Central sources jar (`repo1.maven.org/.../claude-code-sdk-1.0.0-sources.jar`). Подтверждено:
   - `workingDirectory(Path)` и `claudePath(String)` — на `ClaudeClient.AsyncSpec`, не в `CLIOptions`. `workingDirectory` обязательный.
   - `startupTimeout` не существует.
   - `ClaudeAsyncClient` **не** `AutoCloseable`; `close()` и `connect()` возвращают `Mono<Void>`.
   - `query(prompt).text()` возвращает `Mono<String>` — рекомендованный API. `queryAndReceive()` — deprecated.
   - `Query.isCliInstalled()` — static method, использует `ProcessBuilder("which", "claude")`.

2. **ktgbotapi v32.0.0** (`dev.inmo:tgbotapi`) — подтверждено по исходникам библиотеки:
   - `sendTextMessage(..., replyParameters = ReplyParameters(chatId, msgId), ...)` — **нет** `replyToMessageId`.
   - `sendMediaGroup(...)` возвращает **один** `ContentMessage<MediaGroupContent<...>>`, не `List<...>`.
   - `ContentMessage.messageId` — `MessageId` (value class, Long). `MessageIdentifier` — deprecated typealias.
   - Нет классов `EditMessageCaption/Text` — есть `EditChatMessageCaption/Text`. В проекте уже используется лучший паттерн — suspend-extensions `bot.editMessageText(contentMessage, ...)` (см. `ExportExecutor.kt`).
   - `HTMLParseMode` лежит в `dev.inmo.tgbotapi.types.message.HTMLParseMode` (не `types.ParseMode.HTMLParseMode`).
   - Есть `MessageIsNotModifiedException` и `MessageToEditNotFoundException` в `dev.inmo.tgbotapi.bot.exceptions` для специфичной обработки edit-ошибок.

## Замечания (52 группы)

### Group A — SDK wiring (Task 7, 11)

#### [SDK-WORKDIR] cliPath used as workingDirectory
**Источник:** codex (SDK-1), gemini (SDK-1), ccs-glm (SDK-1), ccs-albb-glm (SDK-1), ccs-albb-qwen (SDK-4 частично).
**Статус:** Автоисправлено после verification через Maven Central sources jar.
**Действие:** Plan Task 7 `ClaudeAsyncClientFactory` переписан: `workingDirectory(Path)` и `claudePath(String)` вызываются на `ClaudeClient.async(options)` builder (AsyncSpec), не на `CLIOptions.builder()`. `workingDirectory` получает обязательный путь из `ClaudeProperties.workingDirectory` (по умолчанию — `application.temp-folder`). `cliPath` → `spec.claudePath(cliPath)` когда не пусто. Design §5 `ClaudeAsyncClientFactory` обновлён соответственно.

#### [SDK-USE] `.use {}` на не-AutoCloseable клиенте
**Источник:** codex, gemini, ccs-albb-glm (SDK-2), ccs-albb-qwen (SDK-4), ccs-albb-kimi (CONFLICT-1), ccs-albb-minimax (SDK-1).
**Статус:** Автоисправлено (подтверждено: `ClaudeAsyncClient` не `AutoCloseable`).
**Действие:** Plan Task 11 `DefaultClaudeInvoker` переписан: явный `try/finally` вместо `.use {}`, cleanup через `client.close().awaitSingleOrNull()`. Design §5 обновлён.

#### [SDK-CONNECT] `connect()` вызван как suspend без `.awaitSingleOrNull()`
**Источник:** ccs-albb-glm (SDK-3), ccs-albb-minimax (SDK-2 частично).
**Статус:** Автоисправлено.
**Действие:** Plan Task 11 — `client.connect().awaitSingleOrNull()` (import `kotlinx.coroutines.reactor.awaitSingleOrNull`). Дополнительно заменили `queryAndReceive` (deprecated since 1.0.0) на `query(prompt).text().awaitSingle()` — возвращает `Mono<String>`, снимает необходимость ручного парсинга `AssistantMessage`/`TextBlock`.

#### [SDK-STARTUP-TIMEOUT] `startupTimeout` не используется
**Источник:** codex (SDK-1 частично), ccs-albb-minimax (SDK-3).
**Статус:** Автоисправлено.
**Действие:** Поле `startupTimeout: Duration` удалено из `ClaudeProperties` (Plan Task 3) и из design §4 — в SDK 1.0.0 такого параметра нет. В `.env.example` (Task 20) убрали соответствующую env-переменную.

#### [SDK-ISCLIINSTALLED] `Query.isCliInstalled()` ищет CLI в PATH
**Источник:** ccs-albb-qwen (SDK-14), ccs-albb-kimi (SDK-1).
**Статус:** Обсуждено с пользователем (self-decide recommended) — оставить как есть, документировать.
**Действие:** Design §4 добавлен комментарий о том, что `Query.isCliInstalled()` использует `ProcessBuilder("which", "claude")` и требует корректный PATH на момент JVM startup. Текущий Dockerfile ставит `ENV PATH="/home/appuser/.local/bin:${PATH}"` до ENTRYPOINT, этого достаточно.

---

### Group B — DI / Spring wiring

#### [DI-AUTOCONFIG] ai-description модуль не подключён через AutoConfiguration
**Источник:** codex (DI-1).
**Статус:** Автоисправлено.
**Действие:** Создана новая Task 3.5 в Phase 1 plan: `AiDescriptionAutoConfiguration` (`@AutoConfiguration` + `@ComponentScan("ru.zinin.frigate.analyzer.ai.description")` + `@EnableConfigurationProperties(DescriptionProperties, ClaudeProperties)` + `@ConditionalOnProperty(enabled=true)`) и `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. Также удалены из `FrigateAnalyzerApplication.@EnableConfigurationProperties` (Task 13 Step 4). Design §3 добавлен новый раздел §3.1 "Auto-configuration модуля".

#### [DI-SCOPE-CONDITIONAL] `DescriptionCoroutineScope` с `@ConditionalOnProperty` ломает startup
**Источник:** ccs-glm (DI-1), ccs-albb-glm (DI-1), ccs-albb-kimi (DI-1), codex (implicit).
**Статус:** Обсуждено с пользователем — выбран вариант "Убрать @ConditionalOnProperty".
**Действие:** Plan Task 13 Step 3: `DescriptionCoroutineScope` остаётся `@Component` **без** `@ConditionalOnProperty` — scope создаётся всегда, при `enabled=false` idle SupervisorJob не потребляет ресурсов. Design §5 обновлён с объяснением.

#### [DI-AGENT-CONFIG] Двойное `@ConditionalOnProperty` + расходится с design
**Источник:** gemini (CONFLICT-1), ccs-albb-qwen (DI-2), ccs-albb-glm (CONFLICT ARCH-1), ccs-albb-kimi (CONFIG-1 related).
**Статус:** Обсуждено с пользователем — выбран вариант "Сделать по design".
**Действие:** Task 12 (ClaudeAgentConfig) удалена полностью — в плане помечена как УДАЛЕНА. `ClaudeDescriptionAgent` (Task 9/10) и `DefaultClaudeInvoker` (Task 11) теперь `@Component` с двумя `@ConditionalOnProperty` (enabled=true + provider=claude). Все helpers (`ClaudePromptBuilder`, `ClaudeResponseParser`, `ClaudeImageStager`, `ClaudeAsyncClientFactory`, `ClaudeExceptionMapper`) — `@Component` с `@ConditionalOnProperty(enabled=true)` только. Design §5 обновлён.

#### [DI-EDITRUNNER] `DescriptionEditJobRunner` inline scope без @PreDestroy
**Источник:** ccs-glm (COROUTINE-2), ccs-albb-glm (COROUTINE-1, DI-3), ccs-albb-qwen, ccs-albb-kimi (ARCH-1), ccs-albb-minimax (COROUTINE-1, ARCH-1), codex.
**Статус:** Обсуждено с пользователем — выбран вариант "@Component с собственным @PreDestroy scope".
**Действие:** Task 16 переписан: `DescriptionEditScope` — новый `@Component` с `@PreDestroy` (паттерн по `ExportCoroutineScope`). `DescriptionEditJobRunner` — `@Component` с `@ConditionalOnProperty(enabled=true)`, инжектит `DescriptionEditScope`. `TelegramNotificationSender` получает `editJobRunner: ObjectProvider<DescriptionEditJobRunner>` (sender условен на telegram.enabled, runner на ai.description.enabled — могут не совпадать). Design §5 добавил раздел "Telegram edit flow".

#### [DI-NOOP-UPDATE] `NoOpTelegramNotificationService` не обновляется
**Источник:** ccs-albb-glm (PLAN-1).
**Статус:** Автоисправлено.
**Действие:** В Plan Task 14 добавлен Step 3.1 с явным обновлением `NoOpTelegramNotificationService.kt` — добавить новый параметр `descriptionSupplier` в override. Без этого Step компиляция упадёт.

---

### Group C — Coroutines / concurrency

#### [COROUTINE-TIMEOUT-ORDER] `withTimeout` внутри `semaphore.withPermit`
**Источник:** gemini (COROUTINE-1), ccs-albb-kimi (COROUTINE-1), ccs-albb-qwen (COROUTINE-13 частично).
**Статус:** Обсуждено с пользователем — выбран вариант "Два timeout-а".
**Действие:** Plan Task 3: `DescriptionProperties.CommonSection` получает **два** поля — `queueTimeout` (default 30s) и `timeout` (work timeout, default 60s). Plan Task 10 `describe()` переписан: `withTimeout(queueTimeout) { semaphore.acquire() }` → `withTimeout(timeout) { stage + executeWithRetry + cleanup }` в `try { ... } finally { semaphore.release() }`. Оба `TimeoutCancellationException` оборачиваются в `DescriptionException.Timeout`. Design §5 обновлён. YAML + `.env.example` + `ClaudeProperties.CommonSection` синхронизированы.

#### [COROUTINE-RETRY-BUDGET] `delay(5s)` внутри `withTimeout` сжигает бюджет
**Источник:** ccs-glm (COROUTINE-1).
**Статус:** Обсуждено с пользователем — выбран вариант "Чекать оставшийся бюджет перед delay".
**Действие:** Plan Task 10 `executeWithRetry`: добавлен `TimeSource.Monotonic.markNow()` в начало, перед `delay(5s)` проверяется `remaining <= 7s` (5s delay + 2s headroom). Если недостаточно — не retry'им, пробрасываем Transport с WARN "budget exhausted". Design §7 обновлён.

#### [COROUTINE-CANCELLATION-MAPPER] else-branch перехватывает `CancellationException`
**Источник:** gemini (ERROR-1), ccs-glm implicit, codex (CONFLICT-1 related).
**Статус:** Автоисправлено.
**Действие:** Plan Task 8 `ClaudeExceptionMapper.map()`: в начало добавлен `if (throwable is CancellationException) throw throwable`. Добавлен тест `CancellationException is rethrown as-is`. Design §7 "Нормализация SDK-исключений" обновлён.

#### [COROUTINE-CANCELLATION-EDITJOB] `catch (e: Exception)` в `editOne` глотает cancellation
**Источник:** ccs-albb-glm (COROUTINE-2), ccs-albb-kimi, ccs-glm.
**Статус:** Автоисправлено.
**Действие:** Plan Task 16 `DescriptionEditJobRunner.runEdit()` helper добавлен; внутри первый `catch` — `CancellationException` → rethrow. Аналогично добавлены `CancellationException` rethrow в `RecordingProcessingFacade.processAndNotify` в двух `catch (e: Exception)` блоках.

#### [COROUTINE-CLI-SHUTDOWN] CLI subprocess не убивается при shutdown
**Источник:** ccs-albb-qwen (COROUTINE-3).
**Статус:** Обсуждено с пользователем — выбран вариант "Полагаться на SDK close()".
**Действие:** Plan Task 11 `DefaultClaudeInvoker` cleanup явный (`client.close().awaitSingleOrNull()` в finally). Design §7 документирует: "CLI subprocess может пережить JVM shutdown на короткое время, OS reaper подчистит". Отдельный `ProcessHandle.destroyForcibly` не делаем.

---

### Group D — Telegram HTML handling

#### [TELEGRAM-HTML-TRUNCATE] `toCaption(1024)` режет HTML-теги
**Источник:** codex (TELEGRAM-3), gemini (TELEGRAM-2), ccs-glm (TELEGRAM-1), ccs-albb-glm (TELEGRAM-1), ccs-albb-kimi (TELEGRAM-1).
**Статус:** Обсуждено с пользователем — выбран вариант "Бюджет заранее для plain-текста".
**Действие:** `DescriptionMessageFormatter` добавил метод `captionPlaceholderOverhead(language): Int`. `TelegramNotificationSender` пересчитывает бюджет: `captionBase = task.message.toCaption(1024 - hintOverhead)` для initial send, `editBaseCaption = task.message.toCaption(1024 - SHORT_MAX_LENGTH - 2)` для edit success-пути. Гарантирует что финальный HTML-caption укладывается в 1024 без разрывов тегов. Design §6 обновлён.

#### [TELEGRAM-EDIT-OVERFLOW] После edit caption может превысить 1024
**Источник:** ccs-glm (TELEGRAM-2), codex (TELEGRAM-3 частично).
**Статус:** Автоисправлено (покрыто TELEGRAM-HTML-TRUNCATE fix-ом).
**Действие:** `editBaseCaption` передаётся в `EditTarget.baseCaption` — урезан на `SHORT_MAX_LENGTH + 2`, поэтому `captionSuccess(editBaseCaption, result, lang)` гарантированно ≤ 1024.

#### [TELEGRAM-BASE-NOT-ESCAPED] `baseText` (camId/filePath) не экранируется
**Источник:** codex (TELEGRAM-2), gemini (TELEGRAM-1).
**Статус:** Автоисправлено.
**Действие:** `DescriptionMessageFormatter` — все три caption-метода (`captionInitialPlaceholder`, `captionSuccess`, `captionFallback`) вызывают `htmlEscape(baseText)`. Добавлен тест "escapes HTML in baseText too".

#### [TELEGRAM-QUOTE-ESCAPE] `htmlEscape` не покрывает `"`
**Источник:** ccs-albb-qwen (TELEGRAM-6), ccs-albb-minimax (CODE-1).
**Статус:** Отклонено (false positive).
**Ответ:** Telegram HTML text content **не требует** `&quot;` — это нужно только в attribute values, которых мы не формируем. Комментарий добавлен в `htmlEscape` документацию в Plan Task 15.

#### [TELEGRAM-MEDIAGROUP-RETURNTYPE] План обращается к `sendMediaGroup()` как к List
**Источник:** codex (TELEGRAM-1).
**Статус:** Автоисправлено после verification ktgbotapi sources.
**Действие:** Plan Task 16 `TelegramNotificationSender`: `group.messageId` напрямую (без `.firstOrNull()`). Тесты sender'а обновлены: `ContentMessage<MediaGroupContent<MediaGroupPartContent>>` мок возвращает `MessageId(50L)`.

#### [TELEGRAM-REPLYPARAMETERS] `replyToMessageId` vs `ReplyParameters`
**Источник:** codex (TELEGRAM-1).
**Статус:** Автоисправлено.
**Действие:** Plan Task 16: заменено на `replyParameters = ReplyParameters(chatIdObj, <messageId>)` в обоих местах (single-photo details, media-group details). Тесты тоже обновлены.

#### [TELEGRAM-MESSAGE-ID-CAST] `messageId as MessageIdentifier` — хрупкий cast
**Источник:** ccs-albb-minimax (TELEGRAM-1), ccs-albb-kimi (TELEGRAM-3).
**Статус:** Автоисправлено.
**Действие:** Все `as MessageIdentifier` / `as? MessageIdentifier` удалены — `ContentMessage.messageId` уже `MessageId` (правильный тип). `EditTarget` использует `MessageId`. Аналогично импорт `import dev.inmo.tgbotapi.types.MessageId`. Также исправлен импорт `HTMLParseMode` → `dev.inmo.tgbotapi.types.message.HTMLParseMode` (был неправильный путь в плане).

#### [TELEGRAM-MESSAGE-NOT-MODIFIED] Нет специфичной обработки
**Источник:** ccs-albb-kimi (TELEGRAM-2).
**Статус:** Автоисправлено.
**Действие:** Plan Task 16 `runEdit()` helper ловит `MessageIsNotModifiedException` и `MessageToEditNotFoundException` специфично (импорты из `dev.inmo.tgbotapi.bot.exceptions`) и логирует как DEBUG. Всё остальное — WARN.

#### [TELEGRAM-SEPARATE-EDIT] Один failed edit блокирует второй
**Источник:** codex (ERROR-1).
**Статус:** Автоисправлено.
**Действие:** Plan Task 16 `DescriptionEditJobRunner.editOne` для single-photo разделён на `editSinglePhotoCaption()` и `editSinglePhotoDetails()` — вызываются последовательно, каждый в своём `runEdit()` с отдельным try/catch.

#### [TELEGRAM-EMPTY-I18N-KEY] Пустой ключ `notification.recording.export.prompt.with.description=`
**Источник:** ccs-albb-glm (TELEGRAM-2), ccs-albb-kimi (CODE-3).
**Статус:** Автоисправлено.
**Действие:** Plan Task 15 Step 1: строка `notification.recording.export.prompt.with.description=` удалена (была только в en, не используется).

---

### Group E — Telegram facade / flow ordering

#### [ARCH-DESCRIBE-EAGER] Describe-job стартует до проверки подписчиков
**Источник:** codex (ARCH-1).
**Статус:** Обсуждено self-decide (recommended) — supplier pattern.
**Действие:** Plan Task 14 переписан: facade возвращает `() -> Deferred<Result<DescriptionResult>>?`, supplier вызывается Telegram-слоем только после фильтрации получателей. Интерфейс `TelegramNotificationService.sendRecordingNotification(..., descriptionSupplier: (() -> ...?)?)` обновлён. Impl вызывает `supplier?.invoke()` ПОСЛЕ фильтрации. Design §5 "Потребление в core" обновлён.

#### [CODE-FRAME-LIMIT] План не ограничивает до 10 кадров
**Источник:** codex (CONFLICT-2).
**Статус:** Обсуждено self-decide — добавить `max-frames` config, `take()` по frameIndex.
**Действие:** `DescriptionProperties.CommonSection` получил поле `maxFrames: Int` с `@Min(1) @Max(50)`. YAML + `.env.example` добавлен `APP_AI_DESCRIPTION_MAX_FRAMES=10`. `RecordingProcessingFacade.buildDescriptionSupplier` — `.sortedBy { frameIndex }.take(common.maxFrames)`. Добавлен тест "frame limit 10 is applied". Design §4 обновлён.

#### [CODE-EMPTY-FRAMES] `describe()` вызывается с пустым `frames`
**Источник:** gemini (CODE-2).
**Статус:** Автоисправлено.
**Действие:** `RecordingProcessingFacade.buildDescriptionSupplier`: `if (trimmedFrames.isEmpty()) return null` — supplier возвращает null, describe не запускается.

#### [CODE-FRAME-ORDER] `frames.zip(paths)` без сортировки
**Источник:** codex (CODE-1), gemini (CODE-1).
**Статус:** Автоисправлено.
**Действие:** Plan Task 4 `ClaudePromptBuilder.build()`: добавлен `val sortedFrames = request.frames.sortedBy { it.frameIndex }`, потом `sortedFrames.zip(framePaths)`. Дополнительно `RecordingProcessingFacade` сортирует frames перед `take(maxFrames)` — двойная защита. Добавлен тест "sorts unordered frames by frameIndex before zip".

---

### Group F — Config validation

#### [CONFIG-LANGUAGE-VALIDATE] `language` не валидируется как `ru|en`
**Источник:** codex (CONFIG-1), gemini (CONFIG-1), ccs-glm (CONFIG-1), ccs-albb-glm (CONFIG-1).
**Статус:** Обсуждено self-decide (recommended) — @Pattern + убрать silent fallback.
**Действие:** Plan Task 3 `DescriptionProperties.CommonSection.language` — `@field:Pattern(regexp = "ru|en")`. Plan Task 4 `ClaudePromptBuilder.languageNameFor()`: убран silent-fallback, добавлен `else -> error("Unsupported language code")`. Добавлен тест "rejects unknown language code". Design §4 обновлён.

#### [CONFIG-PROVIDER-NOTBLANK] `@NotBlank` на provider
**Источник:** ccs-albb-minimax (CONFIG-1).
**Статус:** Автоисправлено.
**Действие:** Plan Task 3 — `@NotBlank` снят с `provider`. Validation provider происходит в `AiDescriptionAutoConfiguration.@PostConstruct` — WARN если `enabled=true` и нет бина агента.

#### [CONFIG-CLAUDE-VALIDATED-ALWAYS] `ClaudeProperties` валидируется всегда
**Источник:** ccs-albb-kimi (CONFIG-1).
**Статус:** Автоисправлено.
**Действие:** `AiDescriptionAutoConfiguration` имеет `@ConditionalOnProperty(enabled=true)` — при выключенной фиче ни autoconfig, ни `@EnableConfigurationProperties(ClaudeProperties::class)` не активируются; claude.* поля не валидируются.

#### [CONFIG-DURATION-RUNTIME] Duration fields без runtime-валидации
**Источник:** ccs-albb-qwen (CONFIG-7).
**Статус:** Автоисправлено.
**Действие:** `DescriptionProperties.CommonSection.init`: `require(queueTimeout.toMillis() > 0)` и `require(timeout.toMillis() > 0)`.

#### [CONFIG-YAML-INDENT] YAML `ai:` уровень отступа
**Источник:** ccs-albb-minimax (CONFLICT-1).
**Статус:** Отклонено (false positive после проверки реального `application.yaml`).
**Ответ:** В `modules/core/src/main/resources/application.yaml` `application:` на col 0, его дети (`records-watcher:`, `telegram:`, `detect:`) на 2-space indent. План правильно использует `  ai:` под `application:`. ccs-albb-minimax ошибся в интерпретации.

---

### Group G — Plan task ordering

#### [PLAN-TASK-DEPENDENCY] Task 6 → Task 13
**Источник:** ccs-albb-kimi (PLAN-1), ccs-albb-glm (DI-2).
**Статус:** Частично автоисправлено + документировано.
**Действие:** `TempFileWriter` interface перенесён в Task 2 (api/ пакет) — доступен с самого начала Phase 2. `TempFileWriterAdapter` остаётся в Task 13 как adapter-класс. Plan Task 6 context обновлён: unit-тесты с моком `TempFileWriter` изолированы, полная интеграция — с Task 13.

#### [PLAN-INTERFACE-BREAK] Task 14 меняет interface
**Источник:** codex (TEST-1 related), ccs-albb-glm (PLAN-2), ccs-albb-qwen (PLAN-8), ccs-albb-kimi (CONFLICT-2).
**Статус:** Обсуждено self-decide — atomic interface+impl+NoOp+test в одном Task 14.
**Действие:** Plan Task 14 Step 3.1 явно добавлен — обновление `NoOpTelegramNotificationService` в том же task'е. Все затронутые файлы (interface, Impl, NoOp, NotificationTask) коммитятся вместе. Facade test тоже в этом task'е.

#### [PLAN-YAML-EARLY] YAML в Task 17, код — с Task 13
**Источник:** ccs-glm (PLAN-1).
**Статус:** Автоисправлено.
**Действие:** YAML-секция перенесена из Task 17 в Task 13 Step 4.5. Task 17 помечена как УДАЛЕНА.

#### [PLAN-TASK22-24] Task 22 vs Task 24
**Источник:** ccs-albb-qwen (PLAN-16).
**Статус:** Отклонено (false positive).
**Ответ:** В плане Task 22 (code review) идёт ДО Task 24 (delete docs). Порядок уже правильный — ccs-albb-qwen ошибочно прочитал. Никаких изменений.

---

### Group H — Docker

#### [DOCKER-SETTINGS-PATH] settings.json в `/home/appuser/.claude/`
**Источник:** gemini (DOCKER-1).
**Статус:** Отклонено (false positive).
**Ответ:** `$HOME/.claude/settings.json` — стандартный user-scope конфиг Claude CLI (не project-scope). Dockerfile корректен.

#### [DOCKER-NETWORK-INSTALL] `curl | bash` требует network
**Источник:** ccs-albb-qwen (DOCKER-9).
**Статус:** Обсуждено self-decide — оставить как есть.
**Ответ:** Образ собирается в CI с интернетом, это стандартный паттерн (то же делает apk add). Offline fallback добавится когда возникнет прод-требование. Dockerfile не меняем.

#### [DOCKER-SH-BASH] `/bin/sh` vs bash
**Источник:** ccs-albb-kimi (DOCKER-1), ccs-albb-minimax (DOCKER-1).
**Статус:** Отклонено (false positive).
**Ответ:** Текущий `docker-entrypoint.sh` использует только POSIX sh-совместимые конструкции (`[ -f ... ]`, `command -v`, `elif`). Bash-specific нет. Shebang `#!/bin/sh` корректен.

---

### Group I — Error handling & misc

#### [ERROR-STACK-TRACE] `logger.warn(e)` теряет stack trace
**Источник:** ccs-albb-qwen (ERROR-5).
**Статус:** Отклонено (false positive).
**Ответ:** `kotlin-logging 5+` корректно логирует throwable с полным stack trace. Никаких изменений.

#### [ERROR-RATE-LIMITED-ELSE] `RateLimited` без явной else-ветки
**Источник:** ccs-albb-kimi (ERROR-1).
**Статус:** Автоисправлено.
**Действие:** В Plan Task 10 `executeWithRetry` после `catch Transport` добавлен расширенный комментарий: "RateLimited, Disabled pass through without retry. Timeout НЕ достигнет этого места — он генерируется на границе describe()".

#### [ERROR-RATE-LIMIT-PATTERN] "429" substring match false positive
**Источник:** ccs-glm (CODE-1).
**Статус:** Автоисправлено.
**Действие:** Plan Task 8 `ClaudeExceptionMapper.isRateLimit`: паттерн усилен до `"rate limit" in message` OR (`\b429\b` AND `"http" in message || "status" in message`). Добавлен тест на false-positive case.

#### [CODE-TIMEOUT-MAPPING] `TimeoutCancellationException` не становится `DescriptionException.Timeout`
**Источник:** codex (CONFLICT-1).
**Статус:** Обсуждено self-decide (recommended) — синхронизировать с design.
**Действие:** Plan Task 10 `describe()`: `TimeoutCancellationException` от каждого из двух `withTimeout` (queue + work) явно оборачивается в `DescriptionException.Timeout(cause = e)`. Тесты обновлены: `assertFailsWith<DescriptionException.Timeout>` вместо raw `TimeoutCancellationException`. Design §5, §7 согласованы.

#### [ARCH-TEMPFILE-LOCATION] `TempFileWriter` в `claude/`
**Источник:** ccs-glm (ARCH-1), ccs-albb-qwen (ARCH-1).
**Статус:** Автоисправлено.
**Действие:** `TempFileWriter` перемещён в `api/` пакет (Plan Task 2 Step 4.5). Импорты в стагере, адаптере, интеграционном тесте — все обновлены.

#### [OPS-OAUTH-ROTATION] OAuth token rotation
**Источник:** ccs-albb-qwen (OPS-12), ccs-glm.
**Статус:** Отклонено (scope).
**Ответ:** Token rotation — operational concern, не фича. Документировано в design §7 "Claude OAuth expired": требуется рестарт контейнера.

#### [OPS-PROVIDER-WARN] Нет WARN при provider=foo
**Источник:** ccs-glm (DI-2).
**Статус:** Автоисправлено.
**Действие:** `AiDescriptionAutoConfiguration.@PostConstruct warnIfProviderMissing()` — WARN если `enabled=true` и `agentProvider.getIfAvailable() == null`. Design §7 "Logging" обновлён.

#### [OPS-LATENCY-LOG] Нет latency logging
**Источник:** ccs-albb-kimi (OPS-1).
**Статус:** Автоисправлено.
**Действие:** Plan Task 10 `ClaudeDescriptionAgent.describe()`: `System.nanoTime()` вокруг work, `logger.debug { "Claude describe completed in ${elapsedMs}ms for recording ${request.recordingId}" }` в finally.

#### [MINOR-INVOKER-NAME] `claudeInvoker` → `defaultClaudeInvoker`
**Источник:** ccs-albb-qwen (CODE-11).
**Статус:** Отклонено (cosmetic, bean-конфиг удалён вместе с ClaudeAgentConfig).

#### [MINOR-EXCEPTION-NAME] `Disabled` → `AgentDisabled`
**Источник:** ccs-albb-qwen (CODE-15).
**Статус:** Отклонено (cosmetic, из контекста `DescriptionException.Disabled` уже ясно).

#### [MINOR-HANDLE-NAME] `descriptionHandle` vs `claudeHandle`
**Источник:** ccs-albb-kimi (CODE-5).
**Статус:** Отклонено (false positive — план уже использует `descriptionHandle` консистентно).

#### [MINOR-GITKEEP] `.gitkeep` удаление
**Источник:** ccs-albb-glm (PLAN-3).
**Статус:** Автоисправлено.
**Действие:** Plan Task 2 Step 4.6 добавлен: `git rm .gitkeep` после создания первых реальных файлов в `api/`.

#### [MINOR-WITHTIMEOUT-TOMILLIS] `.toMillis()` redundant
**Источник:** ccs-albb-glm (CODE-1).
**Статус:** Отклонено (cosmetic, `toMillis()` работает корректно).

#### [MINOR-PATHS-GET] `Paths.get()` vs `Path.of()`
**Источник:** ccs-albb-kimi (CODE-2).
**Статус:** Автоисправлено.
**Действие:** Plan Task 4, 6, 7, 23: все `java.nio.file.Paths.get(...)` заменены на `Path.of(...)`.

---

### Group J — Tests

#### [TEST-CONCURRENCY-MISSING] Нет теста на семафор
**Источник:** gemini (TEST-1), ccs-albb-glm (TEST-2).
**Статус:** Автоисправлено.
**Действие:** Plan Task 10 Step 1 добавлен тест `third call waits for semaphore permit with maxConcurrent=2` — 3 concurrent describe вызова, проверяем `maxSeen <= 2`.

#### [TEST-VIRTUAL-TIME] Retry delay без `advanceTimeBy`
**Источник:** ccs-albb-glm (TEST-1).
**Статус:** Автоисправлено.
**Действие:** Plan Task 10 тест "retries once on Transport" — комментарий что `runTest` virtual clock сам продвигает через `delay(5.seconds)`. Тест работает.

#### [TEST-DISPATCHER] `Dispatchers.Unconfined` vs `StandardTestDispatcher`
**Источник:** ccs-albb-kimi (TEST-1).
**Статус:** Автоисправлено.
**Действие:** Plan Task 14 facade test: `CoroutineScope(Dispatchers.Unconfined + ...)` заменён на `CoroutineScope(StandardTestDispatcher() + SupervisorJob())`.

#### [TEST-TDD-SNIPPETS] Сниппеты не совпадают с API проекта
**Источник:** codex (TEST-1).
**Статус:** Обсуждено self-decide (recommended) — preflight-проверка.
**Действие:** В Phase 2 header плана добавлен "Preflight check": перед Task 4 проверить сигнатуру `RecordingDto`, паттерн `TelegramNotificationSenderTest`, применение `coEvery` vs `every`. Отдельные сниппеты обновлены: `every { provider.getIfAvailable() }` (не `coEvery`), добавлены корректные мок-сигнатуры для suspend-extensions sendTextMessage/editMessage*.

#### [TEST-INTEGRATION-FRAGILE] Stub CLI fragile
**Источник:** ccs-albb-qwen (TEST-10).
**Статус:** Отклонено.
**Ответ:** Уже решено в исходном плане: `@EnabledIfEnvironmentVariable(INTEGRATION_CLAUDE=stub)` + note "nice-to-have, not a gate".

#### [TEST-EXPANDABLE-ESCAPE] Escape detailed не тестируется
**Источник:** ccs-glm (TEST-1).
**Статус:** Автоисправлено.
**Действие:** Plan Task 15 Step 2: добавлен тест `expandableBlockquoteSuccess escapes HTML in detailed`.

---

## Изменения в документах

| Файл | Изменение |
|------|-----------|
| `docs/superpowers/specs/2026-04-19-ai-description-design.md` | Обновлена §2 (2 timeout-а, max-frames), §3 (AutoConfiguration §3.1, @Component подход), §4 (новые поля properties + удаление startupTimeout, @Pattern), §5 (ClaudeAsyncClientFactory с workingDirectory+claudePath, DefaultClaudeInvoker без .use, describe() с двумя timeout-ами и TimeoutCancellationException mapping, TempFileWriter в api/, DescriptionCoroutineScope без условия, supplier pattern для describe-job), §6 (HTML-budget, escape baseText, ktgbotapi v32 API corrections, MessageIsNotModifiedException, independent try/catch), §7 (CancellationException rethrow, retry budget check, logging + provider WARN) |
| `docs/superpowers/plans/2026-04-19-ai-description-plan.md` | Task 1: добавлена зависимость spring-boot-autoconfigure. Task 2: добавлен TempFileWriter в api/ + git rm .gitkeep. Task 3: новые поля (maxFrames, queueTimeout), удалён startupTimeout, добавлен workingDirectory в ClaudeProperties, @Pattern на language, убран @NotBlank на provider. **Добавлена Task 3.5 (AutoConfiguration).** Task 4: сортировка frames + error на unknown language. Task 5-8: @ConditionalOnProperty на компоненты. Task 8: rethrow CancellationException, улучшенный 429 паттерн. Task 9/10: @Component с двумя @ConditionalOnProperty, два withTimeout (queue + work), оба TimeoutCancellationException → DescriptionException.Timeout, retry budget check, latency log. Task 10 тесты: добавлены concurrency test, queue-timeout test, обновлены моки. Task 11: явный try/finally вместо .use, awaitSingleOrNull для Mono<Void>, query().text() вместо queryAndReceive. **Task 12 УДАЛЕНА.** Task 13: убран @ConditionalOnProperty с DescriptionCoroutineScope, перенесён YAML из Task 17, TempFileWriterAdapter из api/ пакета. Task 14: supplier pattern, обновление NoOp обязательно, frame limit, empty guard, CancellationException rethrow в facade. Task 15: HTML escape baseText, captionPlaceholderOverhead метод, удалён пустой i18n ключ. Task 16: полный рефакторинг — DescriptionEditScope + DescriptionEditJobRunner как @Component, HTML-budget в sender, ReplyParameters, правильный ktgbotapi v32 API (editMessage* suspend-extensions, MessageId без cast, HTMLParseMode import, sendMediaGroup single return), MessageIsNotModifiedException/MessageToEditNotFoundException handling, independent try/catch для caption+details. **Task 17 УДАЛЕНА (перенесена в Task 13).** Task 20: .env.example обновлён (max-frames, queue-timeout, working-directory; удалён startup-timeout). Task 23: integration test с правильными config полями. Post-plan checklist: расширен manual-test'ами для camId-с-HTML, >10 кадров, provider-WARN. |
| `docs/superpowers/specs/2026-04-19-ai-description-review-merged-iter-1.md` | Создан — содержит raw выводы всех 7 ревьюеров. |
| `docs/superpowers/specs/2026-04-19-ai-description-review-iter-1.md` | Этот файл. |

## Статистика

- **Всего уникальных замечаний:** 52
- **Автоисправлено (clear-auto):** 32
- **Обсуждено с пользователем:** 7 (DI-scope, DI-agent-config, DI-editrunner, 3× coroutine, HTML-truncate)
- **Принято self-decide (recommended):** 8 (ARCH-describe-eager, CODE-frame-limit, CODE-timeout-mapping, CONFIG-language, TEST-TDD-snippets, DOCKER-network, SDK-iscliinstalled, PLAN-interface-break)
- **Отклонено (false positives / scope):** 12 (TELEGRAM-quote, CONFIG-yaml-indent, DOCKER-settings-path, DOCKER-sh-bash, ERROR-stack, OPS-oauth, MINOR-invoker, MINOR-exception, MINOR-handle, MINOR-withtimeout-tomillis, PLAN-TASK22-24, TEST-integration-fragile)
- **Повторов (автоответ):** 0 (первая итерация)
- **Пользователь сказал "стоп":** Нет
- **Агенты:** codex-executor, gemini-executor, ccs-executor × 5 профилей (glm, albb-glm, albb-qwen, albb-kimi, albb-minimax)

## Ключевые уроки итерации 1

1. **Критично проверять фактическое состояние плана после предыдущих правок** — commit 44759ad claimed 11 corrections, но ревьюеры независимо нашли 5 из них в старом виде (`.use {}`, `@ConditionalOnProperty` на DescriptionCoroutineScope, NoOp не обновлён, inline scope в editJobRunner, cliPath используется как workingDirectory). Это говорит о необходимости verification-шага при "учтённых" правках.
2. **SDK-документация важна** — context7 не знает `claude-code-sdk`, понадобился fallback на Maven Central sources jar. Оказалось что SDK имеет иную структуру API чем предполагал исходный план (workingDirectory на client builder, не в CLIOptions). Это критический класс ошибок которые unit-тесты не ловят.
3. **ktgbotapi v32 имеет много breaking changes** от v30-series — `replyToMessageId` deprecated, `sendMediaGroup` возвращает single, `EditMessageCaption/Text` → `EditChatMessage*`, `HTMLParseMode` переехал. Автоматический проверочный step (context7 + Grep проекта) дал точные ответы.
4. **Structured concurrency легко сломать** — 2 независимых места где `catch (e: Exception)` глотало `CancellationException` (exception mapper, edit runner). Универсальное правило: `CancellationException` всегда rethrow.
5. **Supplier pattern для lazy AI вызовов** — самый чистый способ избежать траты токенов на записи без получателей, без необходимости перепроектировать Telegram-слой.

## Следующие шаги

План готов к повторному ревью (итерация 2) либо к исполнению. Рекомендуемые проверки перед стартом реализации:

1. `git diff master...feature/ai-description -- docs/superpowers/` — глазная проверка что design/plan согласованы.
2. Повторить `/review-design-external-iterative` с `default` — убедиться что замечаний критичности CRITICAL/MAJOR больше нет.
3. Начать Phase 1 (Task 1 → Task 2 → Task 3 → **Task 3.5 (новый!)** → Phase 2).
