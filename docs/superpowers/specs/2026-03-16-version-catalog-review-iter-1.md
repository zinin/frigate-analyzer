# Review Iteration 1 — 2026-03-16 09:33

## Источник

- Design: `docs/superpowers/specs/2026-03-16-version-catalog-design.md`
- Plan: `docs/superpowers/plans/2026-03-16-version-catalog.md`
- Review agents: codex-executor (failed/timeout), gemini-executor, ccs-executor (glm, albb-glm, albb-qwen, albb-kimi, albb-minimax)
- Merged output: `docs/superpowers/specs/2026-03-16-version-catalog-review-merged-iter-1.md`

## Замечания

### [RISK-1] mavenBom Provider — использование .get().toString()

> `mavenBom(libs.testcontainers.bom.get().toString())` — антипаттерн. Spring dependency-management plugin v1.1.0+ поддерживает Provider напрямую.

**Источник:** gemini-executor, ccs-executor (albb-glm, albb-kimi, albb-minimax)
**Статус:** Новое
**Ответ:** Исправить на `mavenBom(libs.testcontainers.bom)`
**Действие:** Обновлён design.md и plan.md — заменено на `mavenBom(libs.testcontainers.bom)`

---

### [ERROR-1] Дубль ktlint plugin apply в subprojects

> Строки 64 и 66 root build.gradle.kts: `apply(plugin = "org.jlleitschuh.gradle.ktlint")` применяется дважды.

**Источник:** gemini-executor, ccs-executor (glm, albb-glm, albb-kimi)
**Статус:** Новое (pre-existing issue)
**Ответ:** Удалить дубль в рамках миграции
**Действие:** Добавлен Step 3 в Task 2 плана; добавлено в побочные исправления design.md

---

### [CONCERN-1] CLAUDE.md — Spring Boot 4.0.2 vs 4.0.3

> CLAUDE.md указывает 4.0.2, фактическая версия 4.0.3.

**Источник:** ccs-executor (glm, albb-glm, albb-kimi, albb-minimax)
**Статус:** Новое (pre-existing issue)
**Ответ:** Обновить CLAUDE.md до 4.0.3
**Действие:** CLAUDE.md обновлён; добавлен Step 5 в Task 2 плана

---

### [SUGGESTION-1] Hardcoded tool versions — ktlint 1.8.0, jacoco 0.8.14

> Версии tool-ов не в Version Catalog, хотя цель миграции — централизация.

**Источник:** gemini-executor, ccs-executor (glm)
**Статус:** Новое
**Ответ:** Добавить в [versions] секцию: `ktlint-tool = "1.8.0"`, `jacoco = "0.8.14"`
**Действие:** Добавлены в [versions] секцию design.md и plan.md; добавлен Step 4 в Task 2 плана

---

### [SUGGESTION-2] r2dbc-postgresql без версии

> Полагается на Spring BOM. Нужно убедиться что BOM управляет зависимостью.

**Источник:** ccs-executor (glm)
**Статус:** Новое
**Ответ:** Оставить без версии — уже работает, билд подтвердит
**Действие:** Без изменений в документах

---

## Изменения в документах

| Файл | Изменение |
|------|-----------|
| design.md | Добавлены `ktlint-tool` и `jacoco` в [versions]; `mavenBom` → Provider; добавлены изменения в subprojects {}; расширены побочные исправления |
| plan.md | Добавлены `ktlint-tool` и `jacoco` в [versions]; `mavenBom` → Provider; добавлены Steps 3-5 в Task 2 |
| CLAUDE.md | Spring Boot 4.0.2 → 4.0.3 |

## Статистика

- Всего замечаний: 5
- Новых: 5
- Повторов (автоответ): 0
- Пользователь сказал "стоп": Нет
- Агенты: gemini-executor, ccs-executor (glm, albb-glm, albb-qwen, albb-kimi, albb-minimax). codex-executor — таймаут.
