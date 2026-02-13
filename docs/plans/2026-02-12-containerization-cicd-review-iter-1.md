# Review Iteration 1 — 2026-02-13

## Источник

- Design: `docs/plans/2026-02-12-containerization-cicd-design.md`
- Plan: `docs/plans/2026-02-12-containerization-cicd-plan.md`
- Review agents: codex-executor (gpt-5.3-codex), gemini-executor, ccs-executor (glmt)
- Merged output: `docs/plans/2026-02-12-containerization-cicd-review-merged-iter-1.md`

## Замечания

### CRIT-1: `latest` тег в production compose — нерепродуцируемый деплой

> Деплой завязан на `latest`, поэтому релизы нерепродуцируемы и возможен рассинхрон версий app/liquibase.

**Источник:** codex-executor, ccs-executor
**Статус:** Новое
**Ответ:** Параметризовать IMAGE_TAG
**Действие:** В compose заменено `latest` на `${IMAGE_TAG:-latest}`, добавлено `IMAGE_TAG` в `.env.example`

---

### CRIT-2: Docker publish workflow не запускает тесты

> Публикация Docker-образов не защищена от "непроверенного" тега — тесты пропускаются, можно выпустить сломанный релиз.

**Источник:** codex-executor
**Статус:** Новое
**Ответ:** Добавить тесты в publish
**Действие:** В docker-publish.yml добавлен шаг `./gradlew build` перед bootJar

---

### CRIT-3: Профиль `local` в проде — путаница конфигов

> В проде предлагается `SPRING_PROFILES_ACTIVE=local`, но в репозитории есть application-local.yaml с локальными значениями.

**Источник:** codex-executor
**Статус:** Новое
**Ответ:** Переименовать в `docker`
**Действие:** Профиль переименован в `docker`, файл в `application-docker.yaml`

---

### CRIT-4: Обязательные env переменные не validated

> Обязательные DB-переменные в compose не проверяются как required (`${VAR:?...}`).

**Источник:** codex-executor, ccs-executor
**Статус:** Новое
**Ответ:** Оставить как есть — достаточно примеров в .env.example
**Действие:** Без изменений

---

### CRIT-5: ARM64 не поддерживается

> Frigate NVR часто запускается на ARM. Текущий план соберет образ только для linux/amd64.

**Источник:** gemini-executor, ccs-executor, codex-executor
**Статус:** Новое
**Ответ:** Отложить
**Действие:** Без изменений (TODO на будущее)

---

### CRIT-6: Healthcheck отсутствует

> В прод compose нет healthcheck у приложения, нет явной стратегии readiness/liveness.

**Источник:** ccs-executor, codex-executor
**Статус:** Новое
**Ответ:** Добавить healthcheck
**Действие:** Добавлен healthcheck в docker-compose.yml, curl установлен в Dockerfile

---

### CON-3: Docker build context без .dockerignore

> Контекст сборки `.` без `.dockerignore` увеличивает объём контекста и время сборки.

**Источник:** codex-executor, gemini-executor
**Статус:** Новое
**Ответ:** Добавить .dockerignore
**Действие:** Добавлено создание `.dockerignore` в Task 1

---

### CON-5: JAR версия 0.0.1-SNAPSHOT

> `./gradlew :frigate-analyzer-core:bootJar` не передает версию из git-тега. Приложение покажет 0.0.1-SNAPSHOT.

**Источник:** gemini-executor
**Статус:** Новое
**Ответ:** Менять версию при выпуске tag, SNAPSHOT только в master
**Действие:** Добавлен `-Pversion` в docker-publish.yml

---

### CON-6: AOT Cache может быть некорректным

> При сборке Docker-образа БД недоступна, `|| true` скроет ошибку.

**Источник:** gemini-executor
**Статус:** Новое
**Ответ:** Оставить как есть — стандартный паттерн Spring Boot AOT
**Действие:** Без изменений

---

### CRIT-10: Trailing slash в путях

> docker-compose использует `recordings`, application.yaml `recordings/`.

**Источник:** ccs-executor
**Статус:** Новое
**Ответ:** Унифицировать — убрать trailing slash
**Действие:** Добавлена проверка в Task 6

---

### CON-4: Deploy инструкции из master вместо тега

> Инструкции деплоя тянут файлы из master, а не из конкретного релизного тега.

**Источник:** codex-executor
**Статус:** Новое
**Ответ:** Исправить на тег
**Действие:** Обновлены curl-ссылки на `v0.1.0` вместо `master`

---

### Отложенные замечания (операционные, за рамками текущей задачи)

- CRIT-7: Secrets management (CCS) → отложено
- CRIT-8: Rollback стратегия для миграций (CCS, Codex) → отложено
- CRIT-9: Docker image testing (CCS) → отложено
- CON-1: Liquibase retry on failure (Codex) → отложено
- CON-2: Pin base images (Codex) → отложено
- CON-7: Monitoring/logging (CCS) → отложено
- CON-8: Rate limiting / reverse proxy (CCS) → отложено

## Изменения в документах

| Файл | Изменение |
|------|-----------|
| design.md | IMAGE_TAG параметризация, профиль docker, healthcheck, .dockerignore, версия в CI, deploy по тегу |
| plan.md | Все соответствующие изменения в задачах 1-6, новые шаги для .dockerignore и healthcheck |

## Статистика

- Всего замечаний: 18
- Новых: 11
- Повторов (автоответ): 0
- Применено изменений: 8
- Без изменений (осознанно): 3
- Отложено (TODO): 7
- Пользователь сказал "стоп": Нет
- Агенты: codex-executor, gemini-executor, ccs-executor
