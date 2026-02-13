# Merged Design Review — Iteration 2

## codex-executor (gpt-5.3-codex)

### [CRIT-1] `.dockerignore` breaks app image build

**Severity:** Critical
**Description:** The `.dockerignore` in the plan contains `**/build/`, but the Dockerfile copies the JAR from `modules/core/build/libs/frigate-analyzer-core.jar`. This file will be excluded by the ignore rule, and the `COPY ${JAR_FILE} application.jar` step in `docker build` will fail.
**Suggestion:** Remove `**/build/` or add an exception `!modules/core/build/libs/frigate-analyzer-core.jar`. Alternative: build the JAR inside a Docker build stage so it does not depend on an artifact in the context.

### [IMP-1] `APP_PORT` variable conflict (host-port vs container-port)

**Severity:** Important
**Description:** `APP_PORT` is used both in port mapping (`"${APP_PORT:-8080}:8080"`) and enters the container via `env_file: .env`, where it affects `server.port` (through `${APP_PORT:8080}` in `application.yaml`). If `APP_PORT` is set to anything other than 8080, the application will listen on a different port, but the healthcheck and internal port mapping will remain on 8080 inside the container.
**Suggestion:** Separate the variables: e.g., `HOST_APP_PORT` for the compose port mapping and a fixed internal port `8080` for the application in the docker profile.

### [IMP-2] `latest` tag gets overwritten by any `v*` tag

**Severity:** Important
**Description:** The publish workflow pushes `latest` for every `v*` tag. If an old tag is later created/re-pushed (or a hotfix from an old branch), `latest` will roll back and could lead to an unnoticed downgrade for users deploying with `latest`.
**Suggestion:** Only publish `latest` in a separately controlled scenario (e.g., only for the final release), and for regular tag-builds push only the versioned tag.

### [MIN-1] Workflow `GITHUB_TOKEN` permissions not restricted

**Severity:** Minor
**Description:** Neither `ci.yml` nor `docker-publish.yml` set `permissions`, so default repository permissions apply, which may be excessive for build/publish tasks.
**Suggestion:** Explicitly set minimal permissions, e.g., `permissions: { contents: read }` (and expand only when truly needed).

---

## gemini-executor

### [IMP-1] Рассинхронизация имени JAR-файла при сборке Docker-образа

**Severity:** Important
**Description:** When `-Pversion` is passed to Gradle, the JAR file name changes from `frigate-analyzer-core.jar` to `frigate-analyzer-core-1.2.3.jar`, but the Dockerfile's `ARG JAR_FILE` default still references the old name without the version. This will cause Docker build failures.
**Suggestion:** Pass JAR name dynamically via `--build-arg`, or configure Gradle `bootJar` to always produce a fixed output name (e.g., `app.jar`).

### [IMP-2] Запуск приложения от имени root

**Severity:** Important
**Description:** The Dockerfile plan doesn't include creating a non-privileged user. Running Spring Boot as root inside the container is a security risk -- if the app is compromised, the attacker gains root within the container.
**Suggestion:** Add user/group creation (e.g., `spring:spring`) and `USER spring` before `ENTRYPOINT` in Task 1.

### [MIN-1] Отсутствие кэширования в CI/CD пайплайнах

**Severity:** Minor
**Description:** No Gradle dependency caching or Docker layer caching is mentioned. Every build will re-download all dependencies, increasing build times and GitHub Actions usage.
**Suggestion:** Use `gradle/actions/setup-gradle@v3` for Gradle caching; use `docker/setup-buildx-action` with `cache-from`/`cache-to` for Docker layer caching.

### [MIN-2] Совместимость пакетного менеджера (apk) и Java 25

**Severity:** Minor
**Description:** Task 1 uses `apk add` which only works on Alpine-based images. If the Java 25 Zulu base image is Debian/Ubuntu-based, this will fail.
**Suggestion:** Verify the base image. If not Alpine, use `apt-get` instead.

---

## ccs-executor (glmt)

### [CRIT-11] Отсутствует защита от pushing `latest` тега на нестабильные версии

**Severity:** Critical
**Description:** В `docker-publish.yml` триггер установлен на `tags: ['v*']`, но на DockerHub пушится и тег `latest`. Если кто-то создаст тег `v0.0.0-broken` или `vX.Y.Z-rc1` на ветке с экспериментальными изменениями, в DockerHub попадёт тег `latest`, указывающий на нестабильную версию.
**Suggestion:** Добавить фильтрацию тегов в GitHub Actions — публиковать `latest` только для релизных тегов без суффиксов (например, только `v1.2.3`, но не `v1.2.3-beta`).
