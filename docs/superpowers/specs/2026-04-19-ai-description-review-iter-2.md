# Review Iteration 2 — 2026-04-19 18:40

## Источник

- **Design:** `docs/superpowers/specs/2026-04-19-ai-description-design.md`
- **Plan:** `docs/superpowers/plans/2026-04-19-ai-description-plan.md`
- **Review agents (7):** codex-executor (gpt-5.4, xhigh), gemini-executor (3.1 Pro), ccs-executor × 5 профилей (glm, albb-glm, albb-qwen, albb-kimi, albb-minimax)
- **Merged output:** `docs/superpowers/specs/2026-04-19-ai-description-review-merged-iter-2.md`
- **Сырых замечаний:** 53 (↓ с 106 в iter-1)
- **Уникальных групп после дедупликации:** ~29 (↓ с 52 в iter-1)

## Замечания по группам

### Group A — AutoConfiguration conditional scope (3 ревьюера подняли)

#### [CRITICAL] AUTOCONFIG-CONDITIONAL: `@ConditionalOnProperty` на классе AutoConfiguration делает `DescriptionProperties` недоступным
**Источник:** codex (DI-1), gemini (TYPE-1), ccs-albb-qwen (CONFLICT-1).
**Статус:** Автоисправлено.
**Действие:** Design §3.1 уточнён: класс `AiDescriptionAutoConfiguration` **без** `@ConditionalOnProperty` — только `@EnableConfigurationProperties` и `@ComponentScan`. Условность переносится на уровень `@Component`-бинов (PromptBuilder, ResponseParser, Agent, Invoker). Plan Task 3.5: обновлено тело класса (убран `@ConditionalOnProperty`), WARN-логика перенесена в новый отдельный безусловный `@Component DescriptionAgentSanityChecker` (Step 1.5), который корректно работает при `enabled=true, provider=foo`. Тест в Task 3.5 Step 4 добавил проверку "DescriptionProperties registered even when enabled=false" + `TempFileWriterStubConfig` для полноты подъёма контекста.

---

### Group B — runEdit suspend lambda (3 ревьюера)

#### [CRITICAL] RUN-EDIT-SUSPEND: `runEdit(block: () -> Unit)` не принимает suspend calls
**Источник:** codex (TELEGRAM-2), gemini (TYPE-2), ccs-albb-glm (CODE-1).
**Статус:** Автоисправлено.
**Действие:** Plan Task 16 `DescriptionEditJobRunner.runEdit`: сигнатура изменена на `block: suspend () -> Unit`, `inline` убран (несовместим с `suspend` в non-inline context без `crossinline`). Компиляция теперь проходит для `bot.editMessageCaption`/`bot.editMessageText` suspend calls.

---

### Group C — HTML-aware caption truncation (2 ревьюера)

#### [MAJOR] HTML-ESCAPE-OVERFLOW: Truncate до escape может превысить 1024 после escape
**Источник:** gemini (TYPE-4), ccs-glm (D2-01).
**Статус:** Автоисправлено.
**Действие:** Truncation перенесён внутрь `DescriptionMessageFormatter` — добавлен private helper `escapeAndTrim(text, budget)`, выполняющий escape → HTML-aware truncate (не разрывая `&amp;`/`&lt;`/`&gt;` entity, с ellipsis). `captionInitialPlaceholder`/`captionSuccess`/`captionFallback` теперь принимают `baseText: String` + `maxLength: Int` и сами выполняют escape+trim. Sender передаёт raw `task.message` + бюджет, не делая pre-trim. `EditTarget.baseCaption` → `baseText` + `captionBudget`. Design §6 обновлён под HTML-aware алгоритм.

---

### Group D — File cleanup at Timeout (unique)

#### [MAJOR] CLEANUP-NONCANCELLABLE: `deleteFiles` при TimeoutCancellationException — silent leak
**Источник:** gemini (TYPE-3).
**Статус:** Автоисправлено.
**Действие:** Plan Task 6 `ClaudeImageStager.cleanup()` и catch-ветка `stage()` обёрнуты в `withContext(NonCancellable) { ... }`. Design §5 обновлён с объяснением. Без этого `TimeoutCancellationException` в describe() не давал завершить cleanup — suspend-вызов в отменённой корутине сразу бросал `CancellationException`.

---

### Group E — Design cleanup after iter-1 (3 ревьюера)

#### [MINOR] DESIGN-STARTUP-TIMEOUT: `CLAUDE_STARTUP_TIMEOUT` остался в design §8
**Источник:** codex (CONFLICT-6), ccs-glm (D2-03), ccs-albb-minimax (CONFLICT-N1).
**Статус:** Автоисправлено.
**Действие:** Строка `# CLAUDE_STARTUP_TIMEOUT=10s` удалена из design §8 `.env.example`; добавлены комментарии к `CLAUDE_CLI_PATH` (PATH vs explicit) и `CLAUDE_WORKING_DIR` defaults. Plan уже корректен.

#### [MINOR] DESIGN-FILE-INVENTORY: `ClaudeAgentConfig.kt` в §10 и пропущены новые файлы
**Источник:** codex (CONFLICT-6), ccs-glm (D2-02).
**Статус:** Автоисправлено.
**Действие:** Design §10 "Новые файлы" полностью переписан: убран `ClaudeAgentConfig.kt`, добавлены `TempFileWriter.kt`, `AiDescriptionAutoConfiguration.kt`, `DescriptionAgentSanityChecker.kt`, `ClaudeExceptionMapper.kt`, `ClaudeInvoker.kt`, `DefaultClaudeInvoker.kt`, `AiDescriptionAutoConfigurationTest.kt`, `ClaudeAsyncClientFactoryTest.kt`, а также `DescriptionEditScope.kt`, `DescriptionEditJobRunner.kt`, `TempFileWriterAdapter.kt`, `NoOpTelegramNotificationService` (изменённый).

#### [MINOR] DESIGN-§6-PSEUDOCODE: Eager describe + `replyToMessageId` + `messages[0]`
**Источник:** codex (CONFLICT-4, TELEGRAM-5), ccs-glm (D2-05).
**Статус:** Автоисправлено.
**Действие:** Design §6 Single photo / Media group pseudocode полностью переписан под supplier pattern (supplier вызывается **после** фильтрации подписчиков) + `ReplyParameters(chatIdObj, msgId)` + `mediaGroupMsg.messageId` (single return). Failure matrix §7 edit-исключения нормализованы: `MessageIsNotModifiedException`/`MessageToEditNotFoundException` → DEBUG, прочие edit-ошибки → WARN.

#### [MAJOR] DESIGN-CONSTRUCTOR-SIG: `CommonSection` vs `DescriptionProperties` в конструкторе
**Источник:** ccs-albb-kimi (DESIGN-PLAN-5), ccs-glm (D2-04).
**Статус:** Автоисправлено.
**Действие:** Design §5 обновлён: `ClaudeDescriptionAgent` принимает `descriptionProperties: DescriptionProperties` (root), внутри извлекается `commonProperties = descriptionProperties.common`. Plan уже корректен.

#### [MAJOR] DESIGN-I18N-CONTRADICTION: §4 "захардкожены константы" vs §9/§10 properties files
**Источник:** ccs-albb-minimax (CONFLICT-N2).
**Статус:** Автоисправлено.
**Действие:** Design §4 отредактирован: "i18n-ключи загружаются через `MessageSource` из `messages_ru/en.properties`" — противоречие устранено. Plan Task 15 корректно реализует через properties.

---

### Group F — Test infrastructure fixes

#### [MAJOR] TEST-DEADLOCK: `StandardTestDispatcher` в facade test блокирует `await()`
**Источник:** gemini (TYPE-5).
**Статус:** Автоисправлено.
**Действие:** Plan Task 14 Step 5 `RecordingProcessingFacadeTest.facade()` helper: `StandardTestDispatcher()` → `UnconfinedTestDispatcher()`. Корутины исполняются сразу, `supplier().await()` возвращается моментально.

#### [MAJOR] TEST-AUTOCONFIG-TEMPWRITER: AutoConfigurationTest context не собирается без `TempFileWriter`
**Источник:** codex (TEST-3).
**Статус:** Автоисправлено.
**Действие:** Plan Task 3.5 Step 4 test: добавлен `TempFileWriterStubConfig` с `@Bean fun tempFileWriter(): TempFileWriter = mockk(relaxed = true)` через `.withUserConfiguration()`. Также тест переименован и перефразирован под новое поведение (`DescriptionProperties` доступны при `enabled=false`).

#### [MAJOR] TEST-IMPORTS: Пропущенные импорты в test-сниппетах
**Источник:** ccs-albb-kimi (PLAN-8 AtomicInteger, PLAN-9 FrameData, DESIGN-PLAN-6 ObjectProvider), ccs-albb-glm (TEST-1 delay), kimi (PLAN-2 import order).
**Статус:** Автоисправлено.
**Действие:**
- Plan Task 10 concurrency test imports: добавлен `import kotlinx.coroutines.delay` в шапку (блок "Импорты для concurrency-теста").
- Plan Task 14 Step 5 facade test: добавлены `import io.mockk.every`, `import ru.zinin.frigate.analyzer.model.dto.FrameData`, `import kotlinx.coroutines.test.UnconfinedTestDispatcher`. Удалён неиспользуемый `Dispatchers`.
- Plan Task 16 Step 4 sender: добавлены `import kotlinx.coroutines.Deferred`, `org.springframework.beans.factory.ObjectProvider`, `ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult`, `ru.zinin.frigate.analyzer.telegram.queue.DescriptionEditJobRunner` в шапку файла. Старая footnote с отдельным `Импорт ObjectProvider` удалена.

#### [MAJOR] TEST-ADAPTER: Отсутствует unit-тест `TempFileWriterAdapter`
**Источник:** ccs-albb-qwen (TEST-1).
**Статус:** Автоисправлено.
**Действие:** Plan Task 13 Step 2.1 — добавлен `TempFileWriterAdapterTest` с 2 тестами (createTempFile/deleteFiles делегирование). Полный код в плане.

#### [NIT] TEST-RUNNER-SPEC: `TestDescriptionEditJobRunner` не описан
**Источник:** ccs-albb-glm (TEST-2), ccs-glm (D2-09).
**Статус:** Автоисправлено.
**Действие:** Plan Task 16 добавлена minimal-реализация `TestDescriptionEditJobRunner` с `UnconfinedTestDispatcher` + override `launchEditJob` для экспонирования `lastJob` через `getEditJob()`. Ссылка на factory-метод `DescriptionEditScope.forTest(...)` (или открытый класс) — implementer выберет при Phase 5.

---

### Group G — Configuration / API signature

#### [MAJOR] INVOKER-DEPENDENCY: `DefaultClaudeInvoker` принимает полный `DescriptionProperties`
**Источник:** ccs-albb-qwen (SDK-1).
**Статус:** Автоисправлено.
**Действие:** Plan Task 11 `DefaultClaudeInvoker`: конструктор всё ещё принимает `DescriptionProperties` (root — Spring bean), но класс хранит только `workTimeout: Duration = descriptionProperties.common.timeout` как final field. Остальная часть properties отбрасывается. `invoke()` использует `workTimeout` напрямую — изолирован от будущих изменений `CommonSection`.

#### [MAJOR] SANITY-CHECKER-SEPARATE: WARN-логика должна срабатывать при `provider=foo`
**Источник:** ccs-albb-qwen (CODE-1).
**Статус:** Автоисправлено.
**Действие:** WARN перенесён из `AiDescriptionAutoConfiguration.@PostConstruct` (условен по enabled=true) в отдельный безусловный `@Component DescriptionAgentSanityChecker` (Plan Task 3.5 Step 1.5). Срабатывает и при `enabled=true, provider=foo` (когда claude-бинов нет, но autoconfig активен). Design §3.1 + §7 обновлены.

#### [MAJOR] DISPATCHERS-IO-DOC: `Dispatchers.IO` не задокументирован в design
**Источник:** ccs-albb-qwen (COROUTINE-1).
**Статус:** Автоисправлено.
**Действие:** Design §5 "описание `DescriptionCoroutineScope`" дополнено: `CoroutineScope(Dispatchers.IO + SupervisorJob())` — IO выбран намеренно (запись temp-файлов, subprocess-запуск CLI).

#### [MINOR] CLAUDE-PROPERTIES-FUTURE: `@NotBlank workingDirectory` заблокирует будущий `provider=openai`
**Источник:** ccs-glm (D2-07).
**Статус:** Автоисправлено (документировано как open issue).
**Действие:** Design §11 "Риски и открытые моменты" — добавлен пункт о `ClaudeProperties` валидации. На момент первого провайдера проблемы нет (`provider=claude` → нужен config). При добавлении OpenAI решится одним из трёх способов (документированы).

---

### Group H — Design↔plan alignment

#### [MINOR] EDIT-SCOPE-CONDITION: design говорит `telegram.enabled`, plan — `ai.description.enabled`
**Источник:** ccs-albb-glm (CONFLICT-1), ccs-glm (D2-06), ccs-albb-kimi (PLAN-3).
**Статус:** Автоисправлено.
**Действие:** Design §5 "Telegram edit flow" обновлён: `DescriptionEditScope`/`DescriptionEditJobRunner` условны по `application.ai.description.enabled=true` (не `telegram.enabled`). Plan корректен.

#### [NIT] SHORT-MAX-LENGTH-DOC: hardcoded `500` vs configurable
**Источник:** ccs-albb-glm (CONFLICT-2), ccs-glm (D2-08), ccs-albb-kimi (DESIGN-PLAN-7/13).
**Статус:** Автоисправлено (вариант b — документировать как worst-case).
**Действие:** Plan Task 16 companion object `SHORT_MAX_LENGTH = 500` получил развёрнутый комментарий (worst-case из `@Max(500)`), Design §6 приведён в соответствие (используем pessimistic budget). Альтернативы (инжект CommonSection, проброс через formatter) зафиксированы в комментарии как возможное будущее улучшение.

#### [MAJOR] FILE-INVENTORY-NOOP: `NoOpTelegramNotificationService` не в design §10
**Источник:** ccs-albb-kimi (PLAN-6).
**Статус:** Автоисправлено.
**Действие:** Design §10 "Изменённые файлы" — добавлен `NoOpTelegramNotificationService.kt` с пометкой "обновить override". Plan Task 14 Step 3.1 уже содержит этот шаг.

#### [CRITICAL] DISABLED-EXCEPTION-DEAD: `DescriptionException.Disabled` нигде не используется
**Источник:** gemini (TYPE-6), ccs-albb-kimi (PLAN-5 comment).
**Статус:** Автоисправлено.
**Действие:** `class Disabled` удалён из sealed hierarchy (Plan Task 2, Design §5). Комментарий в Plan Task 10 retry-loop (`// RateLimited, Disabled pass through...`) → `// RateLimited пробрасывается без retry`. Failure matrix §7 тоже обновлён.

---

### Group I — Docker / operations

#### [MINOR] DOCKER-SMOKE-ENTRYPOINT: `docker run claude --version` уходит в Java, не в CLI
**Источник:** codex (DOCKER-7).
**Статус:** Автоисправлено.
**Действие:** Plan Task 18 Step 2 smoke-check: `docker run --rm --entrypoint claude frigate-analyzer:ai-test --version`. Комментарий почему `--entrypoint` обязателен.

#### [MINOR] DOCKER-APK-CONSOLIDATE: Дублирование `apk add`
**Источник:** ccs-albb-qwen (DOCKER-1).
**Статус:** Автоисправлено.
**Действие:** Plan Task 18 Step 1 инструкция переписана: не добавлять отдельную строку `RUN apk add --no-cache bash libgcc libstdc++ ripgrep`, а расширить существующую `RUN apk add ... ffmpeg curl fontconfig ttf-dejavu ...` в той же строке. Финальный "concrete Dockerfile" block уже был корректен.

#### [MINOR] OPS-CLI-PATH-CHECK: Startup check игнорирует `CLAUDE_CLI_PATH`
**Источник:** codex (OPS-8).
**Статус:** Автоисправлено.
**Действие:** Plan Task 9 (`ClaudeDescriptionAgent.init`): branch на `cliPath.isBlank()` → `Query.isCliInstalled()` vs `cliPath.isNotBlank()` → `Files.isExecutable(Path.of(cliPath))`. Plan Task 19 `docker-entrypoint.sh`: симметричная логика — если `CLAUDE_CLI_PATH` задан, проверяем `[ -x "${CLAUDE_CLI_PATH}" ]`, иначе `command -v claude`. Design §4 обновлён.

---

### Group J — Dismissed (false positives / cosmetic)

- **ccs-albb-kimi DESIGN-PLAN-1** (Gradle name prefix `:frigate-analyzer-ai-description` vs dir `modules/ai-description/`) — project standard, документировано в CLAUDE.md и самом плане.
- **ccs-albb-kimi DESIGN-PLAN-2** (ai-description zависит от model) — reviewer сам отметил false positive: `FrameData` используется в core, не в ai-description. Design §3 корректен.
- **ccs-albb-kimi DESIGN-PLAN-4** (`require(queueTimeout>0)` отсутствует в plan) — false positive: `init` блок с `require()` ЕСТЬ в plan Task 3 Step 1 (line 269-274).
- **ccs-albb-kimi PLAN-1** (MockK verify сигнатуры) — reviewer сам написал "проверить" без конкретной проблемы.
- **ccs-albb-kimi PLAN-7** (printf heredoc в Dockerfile) — reviewer сам написал "работает корректно".
- **ccs-albb-kimi DESIGN-PLAN-8** (CancellationException catch) — purely cautionary, iter-1 уже зафиксировал rethrow.
- **ccs-albb-kimi DESIGN-PLAN-9** (FrameImage equals/hashCode design note) — reviewer сам написал "несущественно".
- **ccs-albb-kimi DESIGN-PLAN-10** (TempFileWriterAdapter package) — пакет `ru.zinin.frigate.analyzer.core.config` корректен.
- **ccs-albb-kimi DESIGN-PLAN-11** (`application.temp-folder` не определено в YAML) — это property из существующего `application.yaml` проекта (временная папка), наш design только использует. Не design-вопрос.
- **ccs-albb-kimi DESIGN-PLAN-12** (DescriptionCoroutineScope) — reviewer сам написал "соответствует, ок".
- **ccs-albb-kimi PLAN-11** (`superpowers:code-reviewer` устаревший формат) — false positive: Task 22 использует `Agent` с `subagent_type: superpowers:code-reviewer` — именно актуальный формат.
- **ccs-albb-qwen MINOR-1** (unused imports в ClaudeAsyncClientFactoryTest) — impost в тесте только `ClaudeProperties` + `kotlin.test.*`, лишних нет. Imports в factory class (production) — используются для SDK вызовов.
- **ccs-albb-qwen TEST-2** (integration test opt-in не задокументирован) — автоисправлено в §9 (добавлено явное "opt-in, не gate CI").
- **ccs-albb-qwen PLAN-1** (Task 12 [УДАЛЕНА] создаёт шум) — оставлен как исторический маркер; removal теперь корректен (Step 1.5 в Task 11 удалён).

## Изменения в документах

| Файл | Изменение |
|------|-----------|
| `docs/superpowers/specs/2026-04-19-ai-description-design.md` | §3.1: `@AutoConfiguration` без `@ConditionalOnProperty`; отдельный `DescriptionAgentSanityChecker`. §4: i18n через MessageSource (не константы), CLI detection branches для `cli-path`. §5: конструктор `ClaudeDescriptionAgent` принимает `DescriptionProperties`; cleanup в NonCancellable; Dispatchers.IO задокументирован; `DescriptionEditScope/Runner` условны по `ai.description.enabled`. §6: pseudocode переписан под supplier pattern + ReplyParameters + single `ContentMessage` от sendMediaGroup; HTML-aware escape+truncate алгоритм. §7: Failure matrix нормализовала edit-исключения в DEBUG; Provider-misconfiguration через отдельный sanity checker. §8: `.env.example` очищен (убран `CLAUDE_STARTUP_TIMEOUT`, добавлены CLI_PATH/WORKING_DIR комментарии). §9: интеграционный тест явно помечен opt-in. §10: file inventory обновлён (5 новых, 1 удалён, NoOp добавлен). §11: добавлен пункт про `ClaudeProperties` валидацию для будущих провайдеров. Удалён `DescriptionException.Disabled`. |
| `docs/superpowers/plans/2026-04-19-ai-description-plan.md` | Task 2: удалён `class Disabled` из `DescriptionException`. Task 3.5: `@AutoConfiguration` без `@ConditionalOnProperty` + пояснение; новый Step 1.5 `DescriptionAgentSanityChecker`; тест переписан (bean DescriptionProperties при enabled=false, TempFileWriterStubConfig). Task 6: `cleanup`/`stage` в `withContext(NonCancellable)`. Task 9: CLI detection branches (cli-path vs PATH). Task 10: добавлен `import kotlinx.coroutines.delay` в concurrency test; комментарий в retry-loop без упоминания Disabled. Task 11: `DefaultClaudeInvoker` хранит только `workTimeout: Duration`, не весь `DescriptionProperties`; удалён артефакт "Step 1.5: Delete Task 12". Task 13: Step 2.1 `TempFileWriterAdapterTest`. Task 14: imports обновлены (FrameData, UnconfinedTestDispatcher, every); dispatcher `StandardTestDispatcher` → `UnconfinedTestDispatcher`. Task 15: `captionInitialPlaceholder/Success/Fallback` принимают `maxLength` и делают internal `escapeAndTrim`; private helper `escapeAndTrim` (HTML-aware); `MAX_CAPTION_LENGTH` const в formatter. Task 16: `runEdit(block: suspend () -> Unit)` без `inline`; `EditTarget.baseCaption` → `baseText` + `captionBudget`; sender упрощён (formatter делает escape+trim); imports `Deferred`, `ObjectProvider`, `DescriptionResult`, `DescriptionEditJobRunner` в шапку; минимальная реализация `TestDescriptionEditJobRunner`. Task 18: `apk add` не дублируется (расширяем существующую строку); smoke-check с `--entrypoint claude`. Task 19: entrypoint script ветвится по `CLAUDE_CLI_PATH`. |
| `docs/superpowers/specs/2026-04-19-ai-description-review-merged-iter-2.md` | Создан — содержит raw выводы всех 7 ревьюеров. |
| `docs/superpowers/specs/2026-04-19-ai-description-review-iter-2.md` | Этот файл. |

## Статистика

- **Всего уникальных групп:** ~29 (↓ с 52 в iter-1 — сходимость ревью)
- **Автоисправлено (clear-auto):** 24
- **Отклонено (false positives / cosmetic):** 14 (в т.ч. самими ревьюерами отмеченные как ок)
- **Обсуждено с пользователем:** 0 (применён iter-1 pattern "recommended" автономно — см. continuation-prompt)
- **Повторов (автоответ на iter-1):** 0 — все новые, как ожидалось (редкие остаточные правки после iter-1 cleanup)
- **Пользователь сказал "стоп":** Нет
- **Агенты:** codex-executor, gemini-executor, ccs-executor × 5 профилей (glm, albb-glm, albb-qwen, albb-kimi, albb-minimax)

## Ключевые уроки итерации 2

1. **Residual cleanup после iter-1 правок** — 3 ревьюера независимо нашли `CLAUDE_STARTUP_TIMEOUT` в design §8 (который был удалён из §4 в iter-1, но остался в другой секции); 2 ревьюера — `ClaudeAgentConfig.kt` в §10 file inventory. Важный урок: при правках иерархических документов искать все ссылки, не только первое упоминание.

2. **Новые regressions от iter-1 правок**: `@ConditionalOnProperty` на AutoConfiguration классе (добавлен в iter-1) ломает startup при дефолтном `enabled=false`, т.к. `DescriptionProperties` регистрируется только при `enabled=true`, а facade инжектит его безусловно. Выявлено сразу 3 ревьюерами. Исправление: conditional переносится на уровень `@Component` бинов.

3. **Compile-time ошибки в сниппетах** — `runEdit(block: () -> Unit)` с внутренним suspend-вызовом найден 3 ревьюерами. TDD-сниппеты нужно типизировать правильно (не "работает в IDE", а "компилируется").

4. **HTML-aware escape+truncation** — тонкость, которую iter-1 закрывал упрощённо (truncate-before-escape). 2 ревьюера нашли что после escape длина может превышать budget (`&` → `&amp;` +4). Правильная реализация — escape-first с откатом до конца entity при truncation.

5. **Structured concurrency во finally** — `suspend`-вызов в отменённой корутине (при TimeoutCancellationException) сразу бросает CancellationException. `withContext(NonCancellable)` обязателен для cleanup-блоков, которые должны выполниться несмотря на cancellation.

6. **Test dispatcher выбор** — `StandardTestDispatcher` без `advanceUntilIdle()` блокирует `.await()`. `UnconfinedTestDispatcher` выполняет корутины сразу и подходит для simple testing scenarios.

7. **Sanity checks как отдельные безусловные компоненты** — WARN о missing agent должен работать при `enabled=true, provider=foo` тоже. Если положить `@PostConstruct` в conditional autoconfig — при отсутствии провайдера ничего не сработает.

8. **Ревью сходится** — iter-2 дал 53 сырых замечания vs 106 в iter-1 (после iter-1 patch rate). Большинство новых — residual cleanup + edge cases. Следующая итерация должна дать <10 замечаний, преимущественно NIT.

## Следующие шаги

План и дизайн согласованы, compile-level ошибки устранены. Рекомендации перед стартом реализации:

1. `git diff master...feature/ai-description -- docs/superpowers/` — визуальная проверка сводной согласованности design ↔ plan.
2. При желании — iter-3 (в fresh session) для верификации остаточных edge cases. Но по количеству находок (нет reused issues из iter-1, все замечания mostly residual) — iter-3 ожидается с <10 находками, большинство NIT.
3. Альтернатива — переход к **Phase 1 реализации**: Task 1 → Task 2 (без .gitkeep, с TempFileWriter SPI) → Task 3 → Task 3.5 (NEW с auto-config и sanity checker) → Phase 2 (Claude components TDD). Выбор режима исполнения (Subagent-Driven Development vs Inline Execution) остаётся за пользователем.
