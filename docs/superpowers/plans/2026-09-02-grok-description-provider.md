# Grok Build Description Provider Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Второй провайдер AI-описаний детекций, `application.ai.description.provider=grok`, через headless-вызов бинарника Grok Build, с общим провайдер-нейтральным ядром модуля `ai-description` и уведомлением владельца об отказе авторизации.

**Architecture:** Семафор, таймауты и retry уезжают из `ClaudeDescriptionAgent` в `core/DefaultDescriptionAgent`; провайдеры реализуют SPI `DescriptionBackend` (одна попытка describe). `GrokBackend` пишет `prompt.json` с inline base64-кадрами, запускает `grok --prompt-file … --json-schema … --output-format json` через `ProcessBuilder` и читает `structuredOutput`. Отказ авторизации это `DescriptionException.Unauthorized`, ядро публикует Spring-событие, core-модуль шлёт владельцу сообщение в Telegram.

**Tech Stack:** Kotlin 2.4.10, Spring Boot 4.1.0, Java 25, kotlinx-coroutines 1.11.0, Jackson 3 (`tools.jackson`), MockK, kotlin-test JUnit5, ktlint, Grok Build 1.0.13.

**Spec:** `docs/superpowers/specs/2026-09-02-grok-description-provider-design.md`

## Global Constraints

- Все команды Gradle (`./gradlew …`) запускаются только через агента `claude-forge:build-runner`, никогда напрямую в основной сессии (правило `CLAUDE.md`). На ошибки ktlint: `./gradlew ktlintFormat`, затем повтор.
- Тесты одного модуля: `./gradlew :frigate-analyzer-ai-description:test`, `./gradlew :frigate-analyzer-core:test`, `./gradlew :frigate-analyzer-telegram:test`. Один класс: добавить `--tests <FQCN>`.
- После создания или изменения файла обязательно `git add <file>` (правило `CLAUDE.md`). В `docs/superpowers/` коммитятся только spec и этот план, остальные untracked-файлы там не трогать.
- Каждое сообщение коммита заканчивается строкой `Claude-Session: https://claude.ai/code/session_015ZWCHwRx1akjYX3o1wtJ6S` (отдельный `-m`).
- Идентификаторы провайдеров: `claude`, `grok`. Префикс свойств Grok: `application.ai.description.grok`. Env: `GROK_CLI_PATH`, `GROK_MODEL` (default `grok-4.6`), `GROK_EFFORT` (default `low`), `GROK_HOME` (default `${application.temp-folder}/grok-home`), `GROK_WORKING_DIR` (default `${application.temp-folder}/grok-cwd`), `GROK_HTTP_PROXY`, `GROK_HTTPS_PROXY`, `GROK_NO_PROXY`.
- Версия Grok в образе: `ARG GROK_VERSION=1.0.13`.
- Конструкторы `@ConfigurationProperties`-классов вызываются только с именованными аргументами.
- Тексты `DescriptionException` провайдер-нейтральны: `Description timed out`, `Description provider returned an invalid response`, `Description provider transport error`, `Description provider rate-limited the request`, `Description provider rejected the credentials: <detail>`.
- Ключи i18n: `ai.description.auth.lost`, `ai.description.auth.restored`, в обоих бандлах `modules/telegram/src/main/resources/messages_{ru,en}.properties`. В значениях нет апострофов (MessageFormat).
- JSON Schema для `--json-schema`, ровно: `{"type":"object","properties":{"short":{"type":"string"},"detailed":{"type":"string"}},"required":["short","detailed"],"additionalProperties":false}`.
- System prompt Grok, константа: `You describe frames from a security camera for a notification message. Answer only through the structured output. Do not call tools and do not ask questions.`
- Env изоляции дочернего `grok`: `GROK_DISABLE_AUTOUPDATER=1`, `GROK_MEMORY=0`, `GROK_SUBAGENTS=0`, `GROK_CLAUDE_AGENTS_ENABLED=0`, `GROK_CLAUDE_HOOKS_ENABLED=0`, `GROK_CLAUDE_MCPS_ENABLED=0`, `GROK_CLAUDE_RULES_ENABLED=0`, `GROK_CLAUDE_SKILLS_ENABLED=0`, `GROK_CURSOR_AGENTS_ENABLED=0`, `GROK_CURSOR_HOOKS_ENABLED=0`, `GROK_CURSOR_MCPS_ENABLED=0`, `GROK_CURSOR_RULES_ENABLED=0`, `GROK_CURSOR_SKILLS_ENABLED=0`.
- Секреты (`auth.json`, `config.toml` с ключами, `application-local.yaml`) не печатать и не коммитить.
- Kotlin allopen через `kotlin-spring` применён ко всем модулям: `@Bean`-методы в `@AutoConfiguration` не требуют `open`.

---

## Структура файлов

Модуль `modules/ai-description/src/main/kotlin/ru/zinin/frigate/analyzer/ai/description/`:

| Файл | Ответственность |
|---|---|
| `api/DescriptionException.kt` (modify) | Нейтральные тексты, `detail`, новый `Unauthorized` |
| `api/DescriptionProviderAuthEvent.kt` (create) | Spring-событие `LOST`/`RESTORED` |
| `core/DescriptionBackend.kt` (create) | SPI провайдера: одна попытка describe |
| `core/DefaultDescriptionAgent.kt` (create) | Семафор, таймауты, retry, машина состояний авторизации |
| `core/ResultNormalizer.kt` (create) | Проверка полей и обрезка «…» |
| `core/LanguageNames.kt` (create) | `ru` → `Russian`, `en` → `English` |
| `claude/ClaudeBackend.kt` (create, заменяет `ClaudeDescriptionAgent.kt`) | stage → prompt → invoker → parse |
| `claude/ClaudeResponseParser.kt`, `ClaudePromptBuilder.kt`, `ClaudeExceptionMapper.kt`, `ClaudeImageStager.kt`, `ClaudeAsyncClientFactory.kt` (modify) | Делегирование в core, условие `provider=claude`, ветка `Unauthorized` |
| `grok/GrokPromptBuilder.kt` (create) | Тексты промпта и system prompt |
| `grok/GrokPromptFileWriter.kt` (create) | `prompt.json` из ACP-блоков |
| `grok/GrokCommand.kt`, `grok/GrokCommandBuilder.kt` (create) | argv и env |
| `grok/GrokProcessRunner.kt`, `grok/DefaultGrokProcessRunner.kt` (create) | Шов и `ProcessBuilder` |
| `grok/GrokOutputParser.kt` (create) | JSON stdout → `GrokOutput`, error JSON → message |
| `grok/GrokExceptionMapper.kt` (create) | Классификация ошибок |
| `grok/GrokHomeGuard.kt` (create) | shared/exclusive блокировка `GROK_HOME` |
| `grok/GrokHomeSweeper.kt` (create) | Очистка `sessions/` и `logs/` |
| `grok/GrokBackend.kt` (create) | Оркестрация одной попытки, проверки в `init` |
| `config/GrokProperties.kt` (create) | `application.ai.description.grok.*` |
| `config/AiDescriptionAutoConfiguration.kt` (modify) | `@Bean` агента с `@ConditionalOnBean(DescriptionBackend)` |
| `config/DescriptionAgentSanityChecker.kt` (modify) | `KNOWN_PROVIDERS = claude, grok` |

Модуль `core`: `application/DescriptionAuthAlertNotifier.kt` (create), `src/main/resources/application.yaml` и `src/test/resources/application.yaml` (modify), `src/test/kotlin/.../config/properties/GrokPropertiesBindingTest.kt` (create).

Модуль `telegram`: `messages_ru.properties`, `messages_en.properties` (modify).

Деплой и документация: `docker/deploy/Dockerfile`, `docker-compose.yml`, `docker-entrypoint.sh`, `.env.example`, `README.md`, `CLAUDE.md`, `.claude/rules/ai-description.md`, `.claude/rules/configuration.md`.

---

### Task 1: Провайдер-нейтральные исключения и событие авторизации
✅ Done — see commit(s): `37d56bc`

### Task 2: `ResultNormalizer` и `LanguageNames` в `core/`
✅ Done — see commit(s): `fa6ea23`

### Task 3: SPI `DescriptionBackend` и `DefaultDescriptionAgent`
✅ Done — see commit(s): `7461389`

### Task 4: `ClaudeBackend` вместо `ClaudeDescriptionAgent`, агент из автоконфигурации
✅ Done — see commit(s): `322ddf9`

### Task 5: `ClaudeExceptionMapper` распознаёт отказ авторизации
✅ Done — see commit(s): `a7b9b91`

### Task 6: `GrokProperties`, yaml и тесты биндинга
✅ Done — see commit(s): `b6f06c9`

### Task 7: `GrokPromptBuilder` и `GrokPromptFileWriter`
✅ Done — see commit(s): `856c480`

### Task 8: `GrokCommandBuilder`
✅ Done — see commit(s): `d0df685`

### Task 9: `GrokProcessRunner` и `DefaultGrokProcessRunner`
✅ Done — see commit(s): `a42cc38`

### Task 10: `GrokOutputParser` и `GrokExceptionMapper`
✅ Done — see commit(s): `849e51f`

### Task 11: `GrokHomeGuard` и `GrokHomeSweeper`
✅ Done — see commit(s): `14f861c`

### Task 12: `GrokBackend`, автоконфигурация для `provider=grok`
✅ Done — see commit(s): `cad3614`

### Task 13: Уведомление владельца об авторизации (`core` + i18n)
✅ Done — see commit(s): `c451687`

### Task 14: Docker: образ, compose, entrypoint, `.env.example`
✅ Done — see commit(s): `d4822a5`, `9c62bb6`, `fea89b7`

### Task 15: Документация
✅ Done — see commit(s): `52cd60b`

### Task 16: Полная сборка, ревью и живая проверка

**Files:**
- Нет новых файлов. Возможны точечные правки по результатам сборки и ревью.

- [ ] **Step 1: ktlint и полная сборка**

Run (через build-runner): `./gradlew ktlintFormat` затем `./gradlew build`
Expected: BUILD SUCCESSFUL, все тесты всех модулей зелёные. Ошибки чинить в том же коммите, что и вызвавшая их задача, если они локальны, иначе отдельным коммитом `fix(...)`.

- [ ] **Step 2: Ревью кода**

Запустить агента `superpowers:code-reviewer` (правило `CLAUDE.md`) на диффе `master..HEAD`. Критичные замечания исправить, повторить до чистого результата, затем снова `./gradlew build`.

- [ ] **Step 3: Живая проверка на стенде (вручную, вне CI)**

```bash
# 1. Образ
docker build -f docker/deploy/Dockerfile -t frigate-analyzer:grok .
docker run --rm --entrypoint grok frigate-analyzer:grok --version        # grok 1.0.13

# 2. Деплой с provider=grok
cd docker/deploy
mkdir -p grok-home && sudo chown 1000:1000 grok-home
# в .env: APP_AI_DESCRIPTION_ENABLED=true, APP_AI_DESCRIPTION_PROVIDER=grok, IMAGE_TAG под собранный образ
docker compose up -d
docker compose logs frigate-analyzer | grep -i "grok"                    # INFO про бинарник, WARN про auth.json
docker compose exec frigate-analyzer grok login --device-code             # URL + код
ls -la grok-home/auth.json                                                # появился, 0600, владелец 1000

# 3. Изоляция промпта
docker compose exec -e GROK_HOME=/application/grok-home frigate-analyzer \
  sh -c 'cd /tmp/frigate-analyzer/grok-cwd && GROK_CLAUDE_SKILLS_ENABLED=0 GROK_CLAUDE_RULES_ENABLED=0 grok inspect | head -30'
# ожидание: 0 skills, 0 rules, 0 plugins
```

Затем дождаться одной записи с детекциями:

- в уведомлении Telegram появились короткое и подробное описания на языке `APP_AI_DESCRIPTION_LANGUAGE`;
- в логе на DEBUG строка `Grok describe for recording …: input_tokens=… total_cost_usd=…` (включить `ru.zinin.frigate.analyzer.ai.description: DEBUG` в `application-docker.yaml`);
- размер `grok-home/sessions/` до и после часового прохода sweeper-а (или дождаться строки `Grok home sweep removed N entries` на DEBUG);
- имитация потери авторизации: `mv grok-home/auth.json grok-home/auth.json.bak`, следующая запись даёт «Описание недоступно», владельцу приходит сообщение `ai.description.auth.lost` с командой; `mv` обратно, следующая запись даёт описание и сообщение `ai.description.auth.restored`;
- при десяти кадрах по 300–800 КБ вызов укладывается в `APP_AI_DESCRIPTION_TIMEOUT`; если API отвергает размер запроса, снизить `APP_AI_DESCRIPTION_MAX_FRAMES` и записать предел в `configuration.md`.

Результаты живой проверки записать в описание PR.

- [ ] **Step 4: Убрать документы superpowers перед PR**

Правило владельца: spec и план не попадают в диф PR, они остаются в истории ветки.

```bash
git rm docs/superpowers/specs/2026-09-02-grok-description-provider-design.md \
       docs/superpowers/plans/2026-09-02-grok-description-provider.md
git commit -m "docs: remove superpowers design and plan documents before PR" \
           -m "Claude-Session: https://claude.ai/code/session_015ZWCHwRx1akjYX3o1wtJ6S"
```

Остальные untracked-файлы в `docs/superpowers/plans/` не трогать.

- [ ] **Step 5: PR**

Открыть PR из `feature/grok-description-provider` в `master` (`gh pr create`). Описание: цель, архитектура в трёх предложениях, список env-переменных, процедура входа, результаты живой проверки, и последняя строка `https://claude.ai/code/session_015ZWCHwRx1akjYX3o1wtJ6S`.

---

## Self-review

- **Spec coverage.** Ядро и SPI: Tasks 1–3. Claude backend и условия: Task 4. Claude `Unauthorized`: Task 5. `GrokProperties`, yaml, биндинг: Task 6. Prompt и `prompt.json`: Task 7. argv/env: Task 8. Runner: Task 9. Разбор и классификация: Task 10. Guard и sweeper: Task 11. `GrokBackend`, sanity checker, автоконфиг для grok: Task 12. Событие → владелец, i18n: Task 13. Dockerfile, compose, entrypoint, `.env.example`: Task 14. Документация: Task 15. Живая проверка и риски spec-а: Task 16.
- **Placeholder scan.** Каждый шаг с кодом содержит код; нет «TBD», «add validation», «similar to Task N».
- **Type consistency.** `DescriptionBackend.describe(request)`, `DefaultDescriptionAgent(backend, descriptionProperties, eventPublisher, timeSource)`, `GrokCommand(argv, environment, workingDirectory)`, `GrokProcessResult(exitCode, stdout, stderrTail)`, `GrokOutput(stopReason, sessionId, short, detailed, usageSummary)`, `GrokExceptionMapper.fromFailure(exitCode, errorMessage, stderrTail)` / `fromStopReason(stopReason)`, `GrokHomeGuard.shared/exclusive`, `GrokHomeSweeper.sweep(): Int`, `DescriptionProviderAuthEvent(provider, state, detail, recoveryHint)` используются одинаково во всех задачах.
