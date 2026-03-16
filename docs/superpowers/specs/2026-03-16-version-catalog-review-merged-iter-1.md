# Merged Design Review — Iteration 1

## gemini-executor

### [RISK-1] Ненадёжная конвертация Provider в строку для mavenBom
**Тип:** Риск
**Серьёзность:** Средняя
**Где:** design.md, секция core/build.gradle.kts
**Описание:** `mavenBom(libs.testcontainers.bom.get().toString())` использует `MinimalExternalModuleDependency.toString()` — антипаттерн без гарантии стабильного формата `group:artifact:version` между версиями Gradle.
**Рекомендация:** Использовать `mavenBom(libs.testcontainers.bom)` напрямую, т.к. Spring dependency-management plugin v1.1.0+ поддерживает Provider объекты из Version Catalog.

### [GAP-1] Hardcoded tool versions (ktlint, jacoco)
**Тип:** Пробел
**Серьёзность:** Низкая
**Где:** root build.gradle.kts, subprojects {} блок
**Описание:** Версии ktlint (1.8.0) и jacoco (0.8.14) остаются hardcoded. Цель миграции — централизация версий.
**Рекомендация:** Опционально: добавить в [versions] и ссылаться через libs.versions.

### [ERROR-1] Дубль ktlint plugin в subprojects
**Тип:** Ошибка
**Серьёзность:** Низкая
**Где:** root build.gradle.kts, строки 64 и 66
**Описание:** `apply(plugin = "org.jlleitschuh.gradle.ktlint")` применяется дважды в subprojects {} блоке.
**Рекомендация:** Удалить дубликат в рамках побочных исправлений.

---

## ccs-executor (glm)

### [TYPE-1] Plugin accessor naming
**Тип:** Ошибка
**Серьёзность:** Критическая (ложноположительная)
**Описание:** GLM утверждает что `git-properties` генерирует неверный accessor. Фактически `git-properties` → `libs.plugins.git.properties` — это корректное поведение Gradle (дефисы → точки).

### [TYPE-2] Дубль ktlint plugin
**Тип:** Ошибка
**Серьёзность:** Средняя
**Совпадение:** gemini ERROR-1

### [TYPE-3] Spring Boot version mismatch в CLAUDE.md
**Тип:** Противоречие
**Серьёзность:** Средняя
**Описание:** CLAUDE.md говорит 4.0.2, фактически 4.0.3.

### [TYPE-4] ktlint tool version hardcoded
**Совпадение:** gemini GAP-1

### [TYPE-5] Jacoco version hardcoded
**Совпадение:** gemini GAP-1

### [TYPE-6] r2dbc-postgresql без версии
**Тип:** Риск
**Серьёзность:** Средняя
**Описание:** Полагается на Spring BOM. Нужно убедиться что BOM управляет этой зависимостью.

---

## ccs-executor (albb-glm)

### [ERROR-2] Дубль ktlint plugin
**Совпадение:** gemini ERROR-1

### [RISK-1] testcontainers BOM .get().toString()
**Совпадение:** gemini RISK-1

### [RISK-2] Spring Boot version в CLAUDE.md
**Совпадение:** glm TYPE-3

---

## ccs-executor (albb-qwen)

Все 8 замечаний — ложноположительные (неверное понимание механики accessor-ов Gradle Version Catalog).

---

## ccs-executor (albb-kimi)

### [TYPE-7] Дубль ktlint plugin
**Совпадение:** gemini ERROR-1

### [TYPE-5] testcontainers BOM syntax
**Совпадение:** gemini RISK-1

### [TYPE-9] Spring Boot version в CLAUDE.md
**Совпадение:** glm TYPE-3

Остальные замечания — ложноположительные или pre-existing issues не связанные с миграцией.

---

## ccs-executor (albb-minimax)

### [TYPE-1] git-properties accessor
**Ложноположительная** — accessor корректен.

### [TYPE-4] testcontainers BOM syntax
**Совпадение:** gemini RISK-1

### [TYPE-3] Spring Boot version в CLAUDE.md
**Совпадение:** glm TYPE-3

---

## codex-executor

Таймаут — результатов нет.
