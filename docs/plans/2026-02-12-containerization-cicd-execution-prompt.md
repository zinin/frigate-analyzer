## TASK

Execute the implementation plan for containerization and CI/CD setup (Docker image publishing to DockerHub, GitHub Actions, production docker-compose).

Use `/superpowers:subagent-driven-development` skill for execution.

## DOCUMENTS

- Design: `docs/plans/2026-02-12-containerization-cicd-design.md`
- Plan: `docs/plans/2026-02-12-containerization-cicd-plan.md`

Read both documents first.

## IMPORTANT: DO NOT START WORK YET

After reading the documents:
1. Confirm you have loaded all context
2. Summarize your understanding briefly
3. **WAIT for user instruction before taking any action**

Do NOT begin implementation until the user explicitly tells you to start.

## SESSION CONTEXT

Key decisions and context from the brainstorming session not fully captured in documents:

### DockerHub Details
- DockerHub username: `avzinin` (profile: https://hub.docker.com/u/avzinin)
- Two images: `avzinin/frigate-analyzer` and `avzinin/frigate-analyzer-liquibase`
- GitHub repo: `git@github.com:zinin/frigate-analyzer.git`

### Deployment Target
- Docker Compose will run on the same host where Frigate recordings are stored
- `/mnt/data/frigate/recordings/` is available locally on the deployment host
- No NFS/CIFS mounts needed

### Configuration Strategy Rationale
- `.env` for simple key-value settings (DB, Telegram, paths) because `application.yaml` is already fully parameterized via env variables
- YAML bind mount specifically for `detect-servers` section — this has complex nested structure (multiple servers, each with multiple request types with simultaneous-count and priority) that is impractical to express as flat env variables
- Spring Boot automatically discovers `./config/` directory relative to WORKDIR (`/application`), so mounting to `/application/config/application-local.yaml` works without any extra JVM args
- Profile `local` is activated via `SPRING_PROFILES_ACTIVE=local` env var in compose

### Rejected Alternatives
- **Containerfile rename:** Not needed — `Dockerfile` is standard for DockerHub, rename is purely cosmetic
- **.dockerignore:** Not needed — Dockerfile copies only a specific JAR file, not the whole context
- **DB in production compose:** Rejected — user manages their own PostgreSQL externally
- **Build on every push to master:** Rejected — Docker images only built on tag `v*`, master gets only build+test CI
- **Single image for app+liquibase:** Rejected — separate images allow independent lifecycle

### Important Implementation Details
- Liquibase Dockerfile uses `${LIQUI_CHANGELOG}` env var in CMD — must pass `LIQUI_CHANGELOG=master_frigate_analyzer.xml` in production compose
- App Dockerfile `ARG JAR_FILE=modules/core/build/libs/frigate-analyzer-core.jar` — CI builds JAR first, then Docker image uses it from that path
- App Docker build context must be project root (`.`) because JAR path is relative to root
- Liquibase Docker build context must be `docker/` because Dockerfile does `COPY ./liquibase/migration/`
- `.gitignore` already has `**/application-local.yaml` and `docker/.env`, but need to add `docker/deploy/.env`
- Current branch is `feature/docker-hub-publish` (created during brainstorming)

### Build System Notes
- Gradle module for JAR: `:frigate-analyzer-core:bootJar` produces `modules/core/build/libs/frigate-analyzer-core.jar`
- Java 25 with Zulu distribution (matches `azul/zulu-openjdk-alpine:25` in Dockerfile)
- `./gradlew build` runs compile + tests + ktlint + jacoco

## PLAN QUALITY WARNING

The plan was written for a large task and may contain:
- Errors or inaccuracies in implementation details
- Oversights about edge cases or dependencies
- Assumptions that don't match the actual codebase
- Missing steps or incomplete instructions

**If you notice any issues during implementation:**
1. STOP before proceeding with the problematic step
2. Clearly describe the problem you found
3. Explain why the plan doesn't work or seems incorrect
4. Ask the user how to proceed

Do NOT silently work around plan issues or make significant deviations without user approval.

