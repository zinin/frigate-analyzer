# Frame Delivery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Дать пресету AI-описаний необязательный потолок длинной стороны кадра (`max-image-side`) и научить grok-backend распознавать ответ, который `grok` получил после молчаливого выброса картинок, отвергая его как `InvalidResponse`.

**Architecture:** Поле `maxImageSide` проходит путь `DescriptionProperties.Preset` → `DescriptionPreset` (каталог) → `VisionCallExecutor`, где итоговая сторона — меньшее из ненулевых значений фичи и пресета (`FrameDownscaler.effectiveMaxSide`). Новый `GrokImageStripDetector` после каждого запуска `grok` читает последний 1 МиБ `GROK_HOME/logs/unified.jsonl` (под тем же `GrokHomeGuard.shared`) и ищет событие `shell.turn.images_stripped` с `sid`, равным `sessionId` из stdout; `GrokBackend` превращает найденный выброс в `InvalidResponse`, а отсутствие записей сессии — в разовый WARN и fail-open.

**Tech Stack:** Kotlin 2.4.10, Spring Boot 4.1.0, Java 25, kotlinx-coroutines, Jackson 3 (`tools.jackson.*`), kotlin-logging, JUnit 5 + kotlin-test, MockK, logback `ListAppender` для проверок лога, ktlint.

**Spec:** `docs/superpowers/specs/2026-09-25-frame-delivery-design.md`

## Global Constraints

- Все команды Gradle (`./gradlew …`) в основной сессии запускаются **только** через агента `claude-forge:build-runner` (правило `CLAUDE.md`); субагент-исполнитель задачи запускает их сам. На ошибки ktlint: `./gradlew ktlintFormat`, затем повтор.
- Модуль: `:frigate-analyzer-ai-description`. Один класс: `./gradlew :frigate-analyzer-ai-description:test --tests '<FQCN>'`. Весь модуль: `./gradlew :frigate-analyzer-ai-description:test`. Линт: `./gradlew :frigate-analyzer-ai-description:ktlintCheck`.
- После создания или изменения файла — `git add <file>` (правило `CLAUDE.md`). В коммит попадают только файлы задачи; неотслеживаемые `.codex`, `docs/session-transfer-*`, `tmp/`, прочие `docs/superpowers/*` не трогать.
- Если системный промпт сессии даёт строки атрибуции (например `Claude-Session: <url>`), каждое сообщение коммита заканчивается ими отдельным `-m`; если не даёт — без хвоста. Id чужой сессии не копировать.
- Продакшен-код `core` и `telegram` не меняется. Новых переменных окружения и миграций БД нет.
- Новые параметры конструкторов — последними и со значением по умолчанию, где это указано; вызовы конструкторов — именованными аргументами.
- KDoc и комментарии — по-русски, как в модуле; тексты логов и исключений — по-английски.
- Точные значения (копировать дословно):
  - допустимый `max-image-side` пресета: `0` или `256..8192`;
  - текст проверки: `preset '$id': max-image-side must be 0 (no cap) or 256..8192, was $maxImageSide`;
  - суффикс подписи пресета в логе: `, max-image-side=N` (только при `N > 0`);
  - путь лога grok: `GROK_HOME/logs/unified.jsonl`; окно чтения `CAPTURE_TAIL_BYTES = 1 МиБ` (`1024L * 1024L`);
  - имя события: `shell.turn.images_stripped`; поля: `sid`, `msg`, `ctx.stripped`, `ctx.reason`;
  - detail исключения: `grok dropped $images of $frames frame(s) before the model call (reason=$reasons); if the endpoint limits request size, set max-image-side on the preset`;
  - WARN выброса: `Grok dropped $images of $frames frame(s) for $requestId (model=$model, reason=$reasons); rejecting the answer: <первые 300 символов ответа или "<no answer>">`;
  - WARN слепоты (один раз за процесс, дальше DEBUG): `Cannot verify frame delivery for grok runs: $reason; image drops by grok will go unnoticed`;
  - поле DEBUG-строки `Grok call …`: `strip=clean|stripped|blind`.
- Jackson 3: `JsonNode.isString` / `stringValue()`, `isIntegralNumber`, `canConvertToInt()`, `intValue()`, `isObject`; исключения разбора — `tools.jackson.core.JacksonException`.

## Review Focus

Пять входов, которые спека подразумевает, а прямые тесты легко пропускают; у каждого — тест в задаче-владельце:

1. **Окно хвоста начинается ровно с начала строки.** Строка на границе окна обязана сохраниться, иначе детектор слепнет по off-by-one — Task 3, тест `a line starting exactly at the window boundary is kept`.
2. **Многобайтный текст, разрезанный границей окна.** Кириллица из заметок о камерах в промпте судьи может попасть в лог grok; разрез двухбайтного символа не должен ни бросать, ни прятать наши строки — Task 3, тест `multi-byte text cut by the window boundary does not hide our lines`.
3. **`ctx.stripped` отсутствует или не целое.** Выброс всё равно засчитывается (за 1) и ответ отвергается — Task 3, тест `a strip without an integer count still counts as one dropped frame`.
4. **Выброс в запуске без ответа** (`stopReason=max_tokens`). Отчёт про выброс, а не про стоп-причину, и без NPE при логе фрагмента — Task 4, тест `a strip is reported even when grok returned no answer`.
5. **Потолок одного пресета не протекает в вызовы другого** того же executor-а (владелец переключил пресет в `/ai`) — Task 2, тест `a cap on one preset does not reach calls resolved to another`.

## File Structure

| Файл | Действие | Ответственность |
|---|---|---|
| `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/config/DescriptionProperties.kt` | Modify | `Preset.maxImageSide` + проверка |
| `…/ai/description/api/DescriptionPreset.kt` | Modify | поле `maxImageSide` в представлении пресета |
| `…/ai/description/core/DescriptionPresetCatalogBuilder.kt` | Modify | копирует поле в представление |
| `…/ai/description/core/PresetLogFormat.kt` | Modify | суффикс `, max-image-side=N` |
| `…/ai/description/core/FrameDownscaler.kt` | Modify | `effectiveMaxSide(featureCap, presetCap)` |
| `…/ai/description/core/VisionCallExecutor.kt` | Modify | уменьшение по потолку фичи и пресета |
| `…/ai/description/core/VisionLimits.kt` | Modify | KDoc поля `maxImageSide` |
| `…/ai/description/grok/GrokImageStripDetector.kt` | Create | `CapturedLog`, `StripCheck`, чтение и разбор хвоста `unified.jsonl` |
| `…/ai/description/grok/GrokBackend.kt` | Modify | захват хвоста под guard, проверка, отказ |
| `…/ai/description/grok/GrokBackendFactory.kt` | Modify | передаёт детектор в backend |
| `modules/ai-description/src/test/kotlin/…/grok/GrokUnifiedLogFixtures.kt` | Create | строки лога grok 1.0.41 для тестов |
| `…/test/…/grok/GrokImageStripDetectorTest.kt` | Create | детектор |
| `…/test/…/grok/GrokBackendEndToEndTest.kt` | Create | stub-`grok` через настоящий runner |
| тесты `DescriptionPresetsValidationTest`, `AiDescriptionAutoConfigurationTest`, `DescriptionPresetCatalogBuilderTest`, `FrameDownscalerTest`, `VisionCallExecutorTest`, `GrokBackendTest`, `GrokBackendFactoryTest`, `PartialSchemaRecoveryTest` | Modify | новые тесты и новые параметры конструкторов |
| `README.md`, `.claude/rules/ai-description.md`, `.claude/rules/configuration.md`, `docker/deploy/application-docker.yaml.example`, `docker/deploy/.env.example`, `CLAUDE.md` | Modify | документация |

`…` в путях = `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer` (main) и `modules/ai-description/src/test/kotlin/ru/zinin/frigate/analyzer/ai/description` (test).

---

### Task 1: Поле `max-image-side` у пресета — объявление, проверка, каталог, лог

✅ Done — see commit(s): `4a00b54`

---

### Task 2: Итоговая сторона кадра — меньшее из потолков фичи и пресета

✅ Done — see commit(s): `b7645f0`

---

### Task 3: `GrokImageStripDetector` — чтение и разбор хвоста `unified.jsonl`

✅ Done — see commit(s): `fa4f71d`

---

### Task 4: `GrokBackend` отвергает ответ после выброса картинок

✅ Done — see commit(s): `0be1d37`

---

### Task 5: Документация

✅ Done — see commit(s): `4929f7f`

---

### Task 6: Ревью ветки, полная сборка, ручная проверка

✅ Done — see commit(s): `d593935`
