# Merged Design Review — Iteration 1

## codex-executor (gpt-5.3-codex)

### Critical Issues
1. Деплой завязан на `latest`, поэтому релизы нерепродуцируемы и возможен рассинхрон версий `app`/`liquibase` (особенно при независимом жизненном цикле миграций). Для rollback это критично.

2. Публикация Docker-образов не защищена от "непроверенного" тега: workflow на `v*` запускается отдельно, а в нем тесты явно пропускаются. В итоге можно выпустить сломанный релиз из любого commit с тегом.

3. В проде предлагается `SPRING_PROFILES_ACTIVE=local`, но в репозитории уже есть `application-local.yaml` с чувствительными/локальными значениями. Это риск утечки секретов в образ и непредсказуемых конфигов при неполном bind mount.

4. Обязательные DB-переменные в compose не проверяются как required (`${VAR:?...}`), поэтому конфиг может "пройти", а контейнеры упадут уже на запуске/миграции.

### Concerns
1. `liquibase` запускается one-shot с `restart: "no"`; при временной недоступности БД деплой "залипнет" до ручного вмешательства.
2. В прод compose нет `healthcheck` у приложения, нет явной стратегии readiness/liveness.
3. Базовые образы плавающие (`latest`/непинованные), что ухудшает воспроизводимость и контроль supply-chain.
4. Контекст сборки приложения `.` без `dockerignore` увеличивает объём контекста и время сборки.
5. Инструкции деплоя тянут файлы из `master`, а не из конкретного релизного тега.

### Suggestions
1. Убрать `latest` из прод compose: использовать единый `IMAGE_TAG` для обоих сервисов.
2. Перестроить release-гейтинг: публикация только после успешного CI.
3. Ввести отдельный профиль `docker`/`prod` вместо `local`.
4. Для обязательных env использовать `${DB_HOST:?required}` и `${DB_PASS:?required}`.
5. Для миграций добавить retry/`restart: on-failure` или wait-for-DB стратегию.
6. Пиновать базовые образы и action-версии, добавить image scanning (Trivy).
7. Публиковать deploy-артефакты по тегу, а не скачивать из `master`.

### Questions
1. Telegram обязателен в проде всегда, или нужно официально поддержать `TELEGRAM_ENABLED=false`?
2. Нужно ли жестко запретить релизы из тегов, не принадлежащих `master`?
3. Требуется ли поддержка `arm64`?
4. Какой ожидаемый rollback-процесс при неудачной миграции БД?
5. Подтверждаете, что `modules/core/src/main/resources/application-local.yaml` должен быть исключен из репозитория?

---

## gemini-executor

### Критические замечания (Critical Issues)
1. **Отсутствие поддержки ARM64 (Multi-arch build)** — Frigate NVR часто запускается на ARM. Текущий план соберет образ только для `linux/amd64`.

### Опасения (Concerns)
1. **Версионирование внутри JAR** — `./gradlew :frigate-analyzer-core:bootJar` не передает версию из git-тега. Приложение внутри покажет `0.0.1-SNAPSHOT`.
2. **Надежность генерации AOT Cache (Java 25)** — при сборке БД недоступна. `|| true` скроет ошибку, AOT-кэш может быть не создан или некорректным.
3. **Контекст сборки Docker** — без `.dockerignore` в демон Docker отправляется всё содержимое проекта.

### Предложения (Suggestions)
1. Передавать версию при сборке: `./gradlew :frigate-analyzer-core:bootJar -Pversion=${{ steps.version.outputs.VERSION }}`
2. Создать специальный профиль для корректной генерации AOT-кэша без БД.
3. Добавить скрипт или подробную документацию для пользователей по развертыванию.

### Вопросы (Questions)
1. Планируется ли поддержка пользователей на Raspberry Pi / Apple Silicon (ARM64)?
2. Проверялся ли запуск `java ... -XX:AOTCacheOutput` без доступной базы данных?

---

## ccs-executor (glmt)

### Критические проблемы
1. Отсутствие healthcheck в production docker-compose.
2. Отсутствие vault/secrets management — `DB_PASS` и `TELEGRAM_BOT_TOKEN` в `.env` в открытом виде.
3. Отсутствие rollback стратегии для БД миграций.
4. Отсутствие тестирования Docker images — CI только тестирует JAR.
5. Различия в путях монтирования — trailing slash mismatch.

### Заботы (Concerns)
1. Отсутствие monitoring/logging стратегии.
2. Жёсткая привязка к конкретным версиям actions — отсутствует политика обновления.
3. Отсутствие rate limiting для API (нет reverse proxy).
4. Liquibase image использует `latest` тег — недетерминированный deployment.
5. Отсутствие validation для `.env` и YAML.

### Предложения (Suggestions)
1. Multi-arch build (`linux/amd64,linux/arm64`).
2. Image scanning (Trivy/Snyk).
3. Semantic release automation.
4. Separate environment configs для production и staging.
5. Документировать expected downtime во время миграций.
6. Добавить `workflow_dispatch` для manual rebuilds.
7. `README.md` в `docker/deploy/` с deployment инструкциями.
8. `HEALTHCHECK` в app Dockerfile.

### Вопросы автору
1. Как планируется обновлять базовые образы? Есть ли план security scanning?
2. Backup strategy перед обновлением?
3. Blue-green deployment для zero-downtime updates?
4. Logging driver configuration?
5. CPU/memory limits в docker-compose?
6. Testing strategy для миграций БД?
7. Network isolation / access control?
