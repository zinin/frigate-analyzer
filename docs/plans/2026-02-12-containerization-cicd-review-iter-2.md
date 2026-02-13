# Review Iteration 2 — 2026-02-13

## Источник

- Design: `docs/plans/2026-02-12-containerization-cicd-design.md`
- Plan: `docs/plans/2026-02-12-containerization-cicd-plan.md`
- Review agents: codex-executor (gpt-5.3-codex), gemini-executor, ccs-executor (glmt)
- Merged output: `docs/plans/2026-02-12-containerization-cicd-review-merged-iter-2.md`

## Замечания

### CRIT-1: `.dockerignore` исключает `**/build/`, что ломает Docker build

> `.dockerignore` содержит `**/build/`, но Dockerfile копирует JAR из `modules/core/build/libs/frigate-analyzer-core.jar`. Это правило исключит JAR из Docker context, и `COPY` упадёт с ошибкой.

**Источник:** codex-executor
**Статус:** Новое
**Ответ:** Добавить исключение `!modules/core/build/libs/*.jar`
**Действие:** Добавлено исключение в `.dockerignore` в плане

---

### IMP-1: `APP_PORT` конфликт host-port vs container-port

> `APP_PORT` из `.env` попадает в контейнер через `env_file`, где Spring Boot использует его как `server.port` (`application.yaml:2`). Если пользователь ставит `APP_PORT=9090`, приложение слушает 9090 внутри контейнера, но healthcheck и port mapping ожидают 8080 → всё ломается.

**Источник:** codex-executor
**Статус:** Новое
**Ответ:** Переименовать в `HOST_PORT`
**Действие:** `APP_PORT` → `HOST_PORT` в compose и `.env.example` в обоих документах

---

### IMP-2: `latest` тег пушится на любой `v*` тег

> Если создать тег `v1.0.0-rc1` или `v0.0.0-broken`, `latest` на DockerHub будет указывать на нестабильную версию.

**Источник:** codex-executor, ccs-executor
**Статус:** Новое
**Ответ:** Оставить как есть — риск низкий для персонального проекта
**Действие:** Без изменений

---

### MIN-1: `GITHUB_TOKEN` permissions не ограничены

> Ни `ci.yml`, ни `docker-publish.yml` не устанавливают `permissions`. Используются дефолтные права, которые могут быть избыточными.

**Источник:** codex-executor
**Статус:** Новое
**Ответ:** Добавить `permissions: { contents: read }`
**Действие:** Добавлено `permissions: contents: read` в оба workflow в плане, добавлено в дизайн

---

### Ложные срабатывания

| Замечание | Агент | Причина |
|-----------|-------|---------|
| JAR filename меняется при `-Pversion` | gemini-executor | `archiveFileName` фиксирован в `build.gradle.kts:14` без версии |
| Запуск от root | gemini-executor | Dockerfile уже создаёт `appuser` (строки 16-19) и `USER appuser` (строка 35) |
| Нет кэширования Gradle | gemini-executor | `gradle/actions/setup-gradle@v4` кэширует автоматически |
| `apk` несовместим с не-Alpine | gemini-executor | Базовый образ `azul/zulu-openjdk-alpine:25` — Alpine |

## Изменения в документах

| Файл | Изменение |
|------|-----------|
| design.md | `APP_PORT` → `HOST_PORT` в compose и .env.example, `permissions` в описании обоих workflow, уточнение .dockerignore |
| plan.md | `!modules/core/build/libs/*.jar` в .dockerignore, `APP_PORT` → `HOST_PORT` в compose и .env.example, `permissions: contents: read` в обоих workflow YAML |

## Статистика

- Всего замечаний: 9
- Новых: 4
- Ложных срабатываний: 4
- Повторов (автоответ): 0 (1 дублирующееся замечание Codex+CCS)
- Применено изменений: 3
- Без изменений (осознанно): 1
- Пользователь сказал "стоп": Нет
- Агенты: codex-executor, gemini-executor, ccs-executor
