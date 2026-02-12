# Containerization & CI/CD Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Setup Docker image publishing to DockerHub via GitHub Actions and production docker-compose for deployment.

**Architecture:** Two Docker images (app + liquibase) built and published on git tag. Production compose pulls images from DockerHub, uses `.env` for simple config and YAML bind mount for nested detect-server config.

**Tech Stack:** GitHub Actions, Docker, Docker Compose, DockerHub, Spring Boot 4.0 externalized config

**Design doc:** `docs/plans/2026-02-12-containerization-cicd-design.md`

---

### Task 1: Move Dockerfile from `docker/app/` to `docker/deploy/`

**Files:**
- Move: `docker/app/Dockerfile` -> `docker/deploy/Dockerfile`
- Delete: `docker/app/` directory

**Step 1: Create deploy directory and move Dockerfile**

```bash
mkdir -p docker/deploy
git mv docker/app/Dockerfile docker/deploy/Dockerfile
```

**Step 2: Verify the file is in the new location**

```bash
ls docker/deploy/Dockerfile
# Expected: docker/deploy/Dockerfile
ls docker/app/ 2>/dev/null
# Expected: error or empty (directory removed)
```

**Step 3: Commit**

```bash
git add -A docker/app docker/deploy/Dockerfile
git commit -m "Move app Dockerfile to docker/deploy/"
```

---

### Task 2: Create production docker-compose

**Files:**
- Create: `docker/deploy/docker-compose.yml`

**Step 1: Create docker-compose.yml**

```yaml
services:
  frigate-analyzer-liquibase:
    image: avzinin/frigate-analyzer-liquibase:latest
    environment:
      - DB_HOST=${DB_HOST}
      - DB_PORT=${DB_PORT:-5432}
      - DB_NAME=${DB_NAME:-frigate_analyzer}
      - DB_USER=${DB_USER:-frigate_analyzer_rw}
      - DB_PASS=${DB_PASS}
      - LIQUI_CHANGELOG=master_frigate_analyzer.xml
    restart: "no"

  frigate-analyzer:
    image: avzinin/frigate-analyzer:latest
    depends_on:
      frigate-analyzer-liquibase:
        condition: service_completed_successfully
    env_file: .env
    volumes:
      - ${FRIGATE_RECORDS_FOLDER:-/mnt/data/frigate/recordings}:/mnt/data/frigate/recordings:ro
      - ./application-local.yaml:/application/config/application-local.yaml:ro
    environment:
      - SPRING_PROFILES_ACTIVE=local
    ports:
      - "${APP_PORT:-8080}:8080"
    restart: unless-stopped
```

Note: `LIQUI_CHANGELOG=master_frigate_analyzer.xml` is required — the existing liquibase Dockerfile uses `${LIQUI_CHANGELOG}` in its CMD. See `docker/liquibase/Dockerfile:7`.

**Step 2: Validate compose syntax**

```bash
cd docker/deploy && docker compose config --quiet && echo "OK" ; cd ../..
```

Expected: `OK` (no syntax errors). If `docker compose` is not available, skip validation.

**Step 3: Commit**

```bash
git add docker/deploy/docker-compose.yml
git commit -m "Add production docker-compose for deployment"
```

---

### Task 3: Create configuration templates

**Files:**
- Create: `docker/deploy/.env.example`
- Create: `docker/deploy/application-local.yaml.example`

**Step 1: Create .env.example**

```env
# Database (external PostgreSQL)
DB_HOST=192.168.1.100
DB_PORT=5432
DB_NAME=frigate_analyzer
DB_USER=frigate_analyzer_rw
DB_PASS=secret

# Telegram
TELEGRAM_BOT_TOKEN=your-bot-token
TELEGRAM_OWNER=your-username

# Paths
FRIGATE_RECORDS_FOLDER=/mnt/data/frigate/recordings

# App
APP_PORT=8080
```

**Step 2: Create application-local.yaml.example**

```yaml
# Detect servers configuration
# Copy this file to application-local.yaml and adjust for your setup
application:
  detect-servers:
    mypc:
      host: 192.168.1.50
      port: 3001
      frame-requests:
        simultaneous-count: 4
        priority: 1
      frames-extract-requests:
        simultaneous-count: 1
        priority: 3
      visualize-requests:
        simultaneous-count: 1
        priority: 1
```

**Step 3: Update .gitignore for deploy directory**

The existing `.gitignore` has `docker/.env` but not `docker/deploy/.env`. Also `**/application-local.yaml` already covers `docker/deploy/application-local.yaml`. Add rule for deploy `.env`:

Append to `.gitignore` in the `### Environment files ###` section:

```
docker/deploy/.env
```

**Step 4: Commit**

```bash
git add docker/deploy/.env.example docker/deploy/application-local.yaml.example .gitignore
git commit -m "Add deployment configuration templates and .gitignore rules"
```

---

### Task 4: Create CI workflow (build & test)

**Files:**
- Create: `.github/workflows/ci.yml`

**Step 1: Create ci.yml**

```yaml
name: CI

on:
  push:
    branches: [ master ]
  pull_request:
    branches: [ master ]

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 25
        uses: actions/setup-java@v4
        with:
          java-version: '25'
          distribution: 'zulu'

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Build and test
        run: ./gradlew build
```

Key points:
- `actions/setup-java@v4` with `distribution: 'zulu'` matches the Docker image (`azul/zulu-openjdk-alpine:25`)
- `gradle/actions/setup-gradle@v4` handles Gradle wrapper and caching automatically
- `./gradlew build` runs compile + tests + ktlint + jacoco (all configured in `build.gradle.kts`)

**Step 2: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "Add CI workflow for build and test on push/PR to master"
```

---

### Task 5: Create Docker publish workflow

**Files:**
- Create: `.github/workflows/docker-publish.yml`

**Step 1: Create docker-publish.yml**

```yaml
name: Docker Publish

on:
  push:
    tags: [ 'v*' ]

jobs:
  publish:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 25
        uses: actions/setup-java@v4
        with:
          java-version: '25'
          distribution: 'zulu'

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Build JAR
        run: ./gradlew :frigate-analyzer-core:bootJar

      - name: Extract version from tag
        id: version
        run: echo "VERSION=${GITHUB_REF_NAME#v}" >> "$GITHUB_OUTPUT"

      - name: Log in to Docker Hub
        uses: docker/login-action@v3
        with:
          username: ${{ secrets.DOCKERHUB_USERNAME }}
          password: ${{ secrets.DOCKERHUB_TOKEN }}

      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v3

      - name: Build and push app image
        uses: docker/build-push-action@v6
        with:
          context: .
          file: docker/deploy/Dockerfile
          push: true
          tags: |
            avzinin/frigate-analyzer:${{ steps.version.outputs.VERSION }}
            avzinin/frigate-analyzer:latest

      - name: Build and push liquibase image
        uses: docker/build-push-action@v6
        with:
          context: docker
          file: docker/liquibase/Dockerfile
          push: true
          tags: |
            avzinin/frigate-analyzer-liquibase:${{ steps.version.outputs.VERSION }}
            avzinin/frigate-analyzer-liquibase:latest
```

Key points:
- `./gradlew :frigate-analyzer-core:bootJar` builds only the JAR (skips tests — they ran in CI workflow already)
- App image context is `.` (project root) because Dockerfile references `modules/core/build/libs/frigate-analyzer-core.jar`
- Liquibase image context is `docker/` because its Dockerfile does `COPY ./liquibase/migration/`
- Version extraction: `v1.2.3` -> `1.2.3` via `${GITHUB_REF_NAME#v}`
- Requires GitHub secrets: `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`

**Step 2: Commit**

```bash
git add .github/workflows/docker-publish.yml
git commit -m "Add Docker publish workflow for tag-based releases"
```

---

### Task 6: Verify and final commit

**Step 1: Verify file structure**

```bash
# All new/moved files should be present:
ls -la docker/deploy/Dockerfile
ls -la docker/deploy/docker-compose.yml
ls -la docker/deploy/.env.example
ls -la docker/deploy/application-local.yaml.example
ls -la .github/workflows/ci.yml
ls -la .github/workflows/docker-publish.yml

# Old location should not exist:
ls docker/app/ 2>/dev/null && echo "ERROR: docker/app/ still exists" || echo "OK: docker/app/ removed"
```

**Step 2: Verify .gitignore covers deploy secrets**

```bash
# These patterns should match:
# docker/deploy/.env -> matched by "docker/deploy/.env"
# docker/deploy/application-local.yaml -> matched by "**/application-local.yaml"
git check-ignore docker/deploy/.env docker/deploy/application-local.yaml
```

Expected output:
```
docker/deploy/.env
docker/deploy/application-local.yaml
```

**Step 3: Review all changes**

```bash
git log --oneline master..HEAD
```

Expected: 5 commits (move Dockerfile, compose, templates, CI, Docker publish).

---

## Post-Implementation: GitHub Setup

After merging, the user needs to configure GitHub repository secrets:

1. Go to `github.com/zinin/frigate-analyzer/settings/secrets/actions`
2. Add `DOCKERHUB_USERNAME` = `avzinin`
3. Add `DOCKERHUB_TOKEN` = DockerHub access token (create at hub.docker.com/settings/security)

## Post-Implementation: First Release

```bash
git tag v0.1.0
git push origin v0.1.0
```

This triggers `docker-publish.yml` which builds and pushes:
- `avzinin/frigate-analyzer:0.1.0` + `:latest`
- `avzinin/frigate-analyzer-liquibase:0.1.0` + `:latest`

## Post-Implementation: Deployment on Host

```bash
# On the host with Frigate recordings:
mkdir -p ~/frigate-analyzer && cd ~/frigate-analyzer

# Download compose and templates
curl -O https://raw.githubusercontent.com/zinin/frigate-analyzer/master/docker/deploy/docker-compose.yml
curl -O https://raw.githubusercontent.com/zinin/frigate-analyzer/master/docker/deploy/.env.example
curl -O https://raw.githubusercontent.com/zinin/frigate-analyzer/master/docker/deploy/application-local.yaml.example

# Configure
cp .env.example .env
cp application-local.yaml.example application-local.yaml
# Edit .env and application-local.yaml with your values

# Start
docker compose up -d
```
