# Require /start Activation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Заблокировать все команды (кроме `/start`) и некомандный текст для пользователей, не завершивших активацию (owner без записи в БД или статус `INVITED`), отвечая локализованным сообщением "сначала отправьте /start" вместо текущего "unauthorized" / тихого молчания.

**Architecture:** Ввести sealed-класс `AuthResult` (`Active(role, user)` / `NeedsActivation` / `Unauthorized`). `AuthorizationFilter` получает новый публичный метод `authorize(...)` вместо `getRole(...)`. Роутер `FrigateAnalyzerBot.registerRoutes()` делает exhaustive `when` по трём веткам результата. `QuickExportHandler` адаптируется под новую сигнатуру без изменения наблюдаемого поведения. Поведение `/start` (`StartCommandHandler`) не меняется.

**Tech Stack:** Kotlin 2.x, Spring Boot 4, R2DBC, kotlinx.coroutines, JUnit5, MockK, kotlin.test, `dev.inmo:tgbotapi`.

**Project commands:**
- Один тест: `./gradlew :frigate-analyzer-telegram:test --tests "<FQCN>"`
- Все тесты модуля: `./gradlew :frigate-analyzer-telegram:test`
- Полный build: **через `build` skill / build-runner агент** (CLAUDE.md запрещает прямой `./gradlew build`)
- На ktlint-ошибки: `./gradlew ktlintFormat` и retry

**Plan location:** `docs/superpowers/plans/2026-05-24-require-start-activation.md`
**Spec:** `docs/superpowers/specs/2026-05-24-require-start-activation-design.md`
**Branch:** `fix/require-start-activation`

---

## File Structure

**Создаются:**
- `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/filter/AuthResult.kt`
- `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/filter/AuthorizationFilterTest.kt`

**Изменяются:**
- `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/filter/AuthorizationFilter.kt`
- `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBot.kt`
- `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandler.kt`
- `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/quickexport/QuickExportHandlerTest.kt`
- `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/cancel/CancelExportHandler.kt`
- `modules/telegram/src/test/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/cancel/CancelExportHandlerTest.kt`
- `modules/telegram/src/main/resources/messages_ru.properties`
- `modules/telegram/src/main/resources/messages_en.properties`
- `.claude/rules/telegram.md`

---

## Task 1: Add `AuthResult` sealed class

✅ Done — see commit: `5802d35`

---

## Task 2: Добавить i18n-ключ `common.error.activation.required`

✅ Done — see commit: `9bbebe8`

---

## Task 3: Написать тесты для `authorize(...)` (red)

✅ Done — included in Task 4 commit (staged then committed together): `3569a28`

> Note: Tests #7 and #8 (`authorize(message)` path) initially used `mockk<Username>` for the ktgbotapi inline-value-class — code-quality review flagged this as unreliable. Fixed via real `CommonUser(id=..., username = Username("@alice"))` per `QuickExportHandlerTest.kt:287-340` pattern, before final commit.

---

## Task 4: Реализовать `authorize(...)` в `AuthorizationFilter` (green)

✅ Done — see commit: `3569a28`

> Task 4 commit includes: `AuthorizationFilter.kt` (authorize() + legacy getRole() preserved), `AuthResult.kt` (added KDoc on `Active` documenting `status == ACTIVE && chatId != null` invariant), and the staged `AuthorizationFilterTest.kt` from Task 3.

---

## Task 5: Обновить `FrigateAnalyzerBot.registerRoutes()`

✅ Done — see commit: `9470c35`

> Step 5 manual smoke-test (10 scenarios) is DEFERRED — automated session had no dev environment with Telegram bot infrastructure. The checklist remains below in the original plan for the user to execute manually before merging.

### Manual smoke-test checklist (deferred to user)

**Окружение:** локальная сборка с пустой БД (или Liquibase rollback для `telegram_users`). Owner в env-конфиге выставлен на ваш Telegram-username.

| # | Действие | Ожидание |
|---|---|---|
| 1 | Owner на чистой БД: `/help` | reply: `common.error.activation.required` («Для использования бота сначала отправьте /start.»). Handler НЕ вызван. |
| 2 | Owner на чистой БД: `/start` | StartCommandHandler работает: invite + activate, ответ `command.start.welcome.owner`. |
| 3 | Owner после `/start`: `/help` | full help-меню (owner-секция включена). |
| 4 | Owner после `/start`: `/timezone Europe/Moscow` | TZ сохраняется (был баг до этой задачи). |
| 5 | Owner после `/start`: non-command текст «привет» | бот молчит (no-op). |
| 6 | Не-приглашённый user (другой Telegram-аккаунт): `/help` | reply: `common.error.unauthorized` («Доступ запрещён»). |
| 7 | Не-приглашённый user: «привет» (non-command) | reply: `common.error.unauthorized`. |
| 8 | INVITED user (вручную добавлен через `/adduser alice` от owner-а, но alice не делала `/start`): `/help` от имени alice | reply: `common.error.activation.required`. |
| 9 | INVITED user (alice): `/start` | StartCommandHandler: activate (INVITED → ACTIVE), ответ `command.start.welcome.user`. |
| 10 | Owner в env-конфиге `MyOwner`, реальный Telegram username `myowner`, чистая БД, `/help` | reply: `common.error.activation.required` (а не unauthorized). Проверка case-insensitivity. |

---

## Task 6: Обновить `QuickExportHandler.handle()` + тесты

✅ Done — see commit: `210c4af`

---

## Task 6b: Обновить `CancelExportHandler.handle()` + тесты

✅ Done — see commit: `a365997`

---

## Task 7: Удалить legacy `getRole(...)` из `AuthorizationFilter`

✅ Done — see commit: `3094785`

> Final grep audit before deletion confirmed zero call-sites of `getRole` anywhere in `modules/` (other than the legacy method definitions themselves). After deletion, `grep -rn 'getRole' modules/ --include='*.kt'` returns empty.

---

## Task 8: Обновить документацию `.claude/rules/telegram.md`

✅ Done — see commit: `5e32597`

> Both the "Authorization" section and the "Bot Architecture" sentence mentioning `getRole()` were updated to `authorize()`.

---

## Финальная верификация

✅ Done — verified by `build-runner` agent:
- Full build: BUILD SUCCESSFUL in 3:16
- 500 tests / 0 failures / 1 skipped (pre-existing in ai-description)
- Telegram module: 202 tests, 0 failures
- ktlint: clean (no format needed)
- jacocoTestCoverageVerification: OK
- bootJar produced

Final code review confirmed: ✅ Ready for PR.

---

## External code review iteration (2026-05-25)

✅ Done — 5 ревьюеров (Claude superpowers, Codex, Ollama Kimi / DeepSeek / MiniMax; GLM пропущен из-за таймаута).

**Auto-fixes** (commit `bbb2fca`):
- Removed unused `properties: TelegramProperties` from `AuthorizationFilter` (после CRITICAL-4 случайный artefact).
- Added `AuthResult.NeedsActivation` coverage в `QuickExportHandlerTest` и `CancelExportHandlerTest`.

**Disputed decisions** (commit `7c75e22`):
- Defensive guard в `AuthorizationFilter.authorize()`: `ACTIVE && chatId == null` → `logger.warn` + `NeedsActivation` вместо invariant-breaking `Active`. Защищает от снапшот-восстановления / ручной правки БД.

**Auto-decided "не исправлять":**
- `makeActiveUser` duplication между 2 test файлами — over-DRY для маленького helper-а.
- Inconsistent `-> Unit` vs `-> { Unit }` для no-op when-веток — structural artefact ktlint (single-line vs multi-line when arms).

**Reviewer findings, отклонённые как pre-existing / out of scope:**
- Codex Critical (`authorize` отбрасывает chatId/userId) — pre-existing username-only auth, design § Non-goals.
- `StartCommandHandler.kt:53` case-sensitive `==` — pre-existing follow-up issue.
- `onCommand` case-sensitive routing — pre-existing ktgbotapi поведение.
- `onContentMessage` отвечает на photos/stickers/voice — pre-existing, documented.
- `registerRoutes()` без юнит-тестов — Non-goal в design.
- Остальное (smoke-test, docs/superpowers cleanup, type-system invariants) — out of scope / запланированные шаги user-а.

Build verification после правок: 205 telegram тестов, 0 fail. Один flaky `ExportExecutorTest > second parallel execute` зарегистрирован — прошёл при retry, не связан с правками.

---

### Remaining (manual, deferred to user)

1. **`git rm -r docs/superpowers/`** + commit `chore: remove design/plan docs from tree before PR` (per global CLAUDE.md superpowers workflow — plan documents must NOT appear in the PR diff; remain accessible in branch git history).
2. **Manual smoke-test** (10 scenarios from Task 5 above) against real bot + DB before merging.
3. **Create PR** from `fix/require-start-activation` → `master`.

### Known pre-existing limitations (out of scope, documented as Non-goals)

- `userService.findByUsername` is case-sensitive at DB layer (Spring Data derived query). In practice works because Telegram sends the same username case consistently. Documented in design § Non-goals.
- `StartCommandHandler.kt:53` still uses case-sensitive `username == properties.owner` direct comparison. Recommend follow-up issue if case-insensitive owner onboarding becomes a problem (e.g., env=`MyOwner`, Telegram=`myowner` — `/start` would take the non-owner branch).
- Race condition on two concurrent `/start` from the same owner on empty DB (check-then-insert in `inviteUser`). Documented in design § Non-goals.
