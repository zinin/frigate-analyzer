# WatchService Registration Performance — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Сократить регистрацию каталогов в WatchService с 9 минут 9 секунд до миллисекунд, отсекая поддеревья дат вне окна наблюдения и не спускаясь ниже каталога камеры.

**Architecture:** `Files.walk` (плоский неуправляемый стрим по всем ~3.2 млн файлов) заменяется на `Files.walkFileTree` с visitor'ом, который в `preVisitDirectory` возвращает `SKIP_SUBTREE` для дат вне окна и для каталогов камер. Арифметика путей (дата, окно, prune-предикат, глубина) выносится в отдельный файл `RecordingsTree.kt` и переиспользуется в `FirstTimeScanTask`, который получает собственное окно `FIRST_SCAN_PERIOD`.

**Tech Stack:** Kotlin 2.4.10, Spring Boot 4.1.0, Java 25 NIO (`walkFileTree`), Coroutines/Flow, JUnit 5, mockk, AssertJ, ktlint.

**Spec:** `docs/superpowers/specs/2026-08-02-watch-records-registration-design.md`

## Global Constraints

- Ветка работы — `perf/watch-records-registration`. Она уже создана и содержит коммит со спекой.
- **НИКОГДА не запускать `./gradlew` напрямую.** Любая сборка, тест или линт — через агент `claude-forge:build-runner` (или команду `/build`). Это правило из `CLAUDE.md`.
- После реализации каждой задачи: сначала агент код-ревью (`superpowers:requesting-code-review`), чинить критичные замечания до чистоты, только потом сборка.
- На ошибках ktlint: `./gradlew ktlintFormat` (через build-runner), затем повторить.
- **Всегда `git add <file>` после создания или изменения файла.** Правило из `CLAUDE.md`.
- Gradle-путь модуля — `:frigate-analyzer-core` (имена проектов переименованы в `settings.gradle.kts`). Тесты модуля: `./gradlew :frigate-analyzer-core:test`.
- Gradle запускает тесты с рабочим каталогом = каталог модуля (`modules/core`). Это уже используется в `ObjectTrackerPropertiesBindingTest`.
- Весь код задач живёт в пакете `ru.zinin.frigate.analyzer.core.task`. Функции с видимостью `internal` в одном пакете доступны без импортов — ни один импорт при переезде между файлами пакета не меняется.
- Тесты создают деревья через `Files.createTempDirectory` и убирают в `finally { root.toFile().deleteRecursively() }` — конвенция существующего `WatchRecordsLoopTest`.
- Продовый `modules/core/src/main/resources/application.yaml` **затеняется** тестовым `modules/core/src/test/resources/application.yaml` на тестовом classpath. Проверять продовый yaml можно только через `FileSystemResource("src/main/resources/application.yaml")` — как это делает `ObjectTrackerPropertiesBindingTest`.
- Перед созданием PR: `git rm` всех файлов из `docs/superpowers/` и коммит — планы и спеки не должны попадать в диф PR. Правило из глобального `CLAUDE.md`.

---

### Task 1: `RecordingsTree.kt` — арифметика путей по дереву записей

✅ Done — see commit(s): `6e15523`, `3a520e5`, `046c4f8`, `244e261`

---

### Task 2: `registerAllDirs` — обход с отсечением поддеревьев

✅ Done — see commit(s): `9fcf3ac`

---

### Task 3: конфигурация — `FIRST_SCAN_PERIOD` и детали health

✅ Done — see commit(s): `ed4161a`, `220f78d`, `36e8e37`

---

### Task 4: `FirstTimeScanTask` — окно, изоляция ошибок, тестируемость

✅ Done — see commit(s): `005ad16`, `b25d862`

---

### Финальная проверка

Порядок «сначала ревью, потом сборка» задан в `CLAUDE.md`.

- [ ] **Код-ревью**

Через skill `superpowers:requesting-code-review` — на диффе ветки относительно `master`. Чинить критичные замечания до чистоты.

На что смотреть прицельно:
- в `preVisitDirectory` порядок правил: guard `> CAMERA_DEPTH` → prune по дате → детектор → регистрация; prune идёт ДО регистрации, иначе каталог вне окна успеет получить watch key;
- проверка `>= CAMERA_DEPTH` (остановка спуска) вызывается после `computeIfAbsent`, а не вместо проверки даты — глубина не должна влиять на отсев;
- ни одна ветка не превратилась в `!isWithinWatchPeriod(...)`;
- `visitFileFailed` возвращает `CONTINUE`, а не наследует пробрасывающую реализацию `SimpleFileVisitor`;
- внешний `.catch` в `scan()` пробрасывает `CancellationException`;
- в `scan()` нет второго `readAttributes` — атрибуты приходят из `visitFile`.

- [ ] **Полная сборка**

Через `claude-forge:build-runner`: `./gradlew build`

Ожидается: BUILD SUCCESSFUL. На ошибках ktlint — `./gradlew ktlintFormat`, затем повторить сборку.

- [ ] **Перед PR: убрать документы планирования из диффа**

```bash
git rm -r docs/superpowers/specs/2026-08-02-watch-records-registration-design.md \
          docs/superpowers/plans/2026-08-02-watch-records-registration.md
git commit -m "chore: drop planning docs from the branch"
```

Документы остаются доступны в истории ветки.

## Приложение: контрольные числа канонической фикстуры

Дерево: 4 даты × 2 часа × 2 камеры × 3 файла. Часы зафиксированы на `2026-05-23T12:00:00Z`.

| Величина | `registerAllDirs(root)`, `P1D` | `registerAllDirs(root/2026-05-23)` | `scan()`, `P1D` | `scan()`, `P0D` |
|---|---|---|---|---|
| `registered` / `indexed` | 15 | 7 | 24 | 12 |
| `prunedSubtrees` | 2 | 0 | 2 | 3 |
| `visitedEntries` | 17 | 7 | 41 | 23 |
| `visitedFiles` | **0** | **0** | — | — |
| `failed` | 0 | 0 | — | — |
| посещено `.mp4` | **0** | **0** | 24 | 12 |

Для `scan()` файлы вне окна тоже не перечисляются — отсекаются вместе с датой. `visitedEntries = 41` при `P1D`: корень 1 + даты 4 + часы 4 + камеры 8 + файлы 24. При `P0D`: 1 + 4 + 2 + 4 + 12 = 23.

`registered = 15` — корень 1 + даты 2 + часы 4 + камеры 8.
`visitedEntries = 17` — 15 зарегистрированных + 2 отсечённые даты. Проверяемый инвариант «файлы не перечислялись» — `visitedFiles == 0`: он безусловен и держится на любом вызове (повторном, из `runIteration`, при сбоях), в отличие от арифметики `registered + prunedSubtrees`, которая верна только для первого прохода по пустой map.

Тест с посторонним файлом на уровне даты: `registered = 15`, `visitedEntries = 18`, `visitedFiles = 1`. Тест со `start` вне корня (дерево `a/b/c/d` + 1 файл): `registered = 5`, `visitedEntries = 6`, `visitedFiles = 1`. Тест с `chmod 000` на каталоге часа: `registered = 12`, `failed = 1`, `visitedEntries = 15` (12 + 2 pruned + 1 failed). Несуществующий и симлинк-`start` бросают до каких-либо счётчиков.
