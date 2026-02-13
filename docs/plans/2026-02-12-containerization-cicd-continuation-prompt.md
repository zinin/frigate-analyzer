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

## PROGRESS

**Completed tasks:**
- [x] Design document created and reviewed
- [x] Plan document created and reviewed
- [x] External design review (iteration 1) — 3 agents (Codex, Gemini, CCS)
- [x] Review findings applied to design and plan documents

**Remaining tasks (from plan):**
- [ ] Task 1: Move Dockerfile, add healthcheck support, create .dockerignore
- [ ] Task 2: Create production docker-compose
- [ ] Task 3: Create configuration templates (.env.example, application-docker.yaml.example, .gitignore)
- [ ] Task 4: Create CI workflow (build & test)
- [ ] Task 5: Create Docker publish workflow
- [ ] Task 6: Verify and final commit

## SESSION CONTEXT

Key decisions and context from the brainstorming and review sessions not fully captured in documents:

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
- Spring Boot automatically discovers `./config/` directory relative to WORKDIR (`/application`), so mounting to `/application/config/application-docker.yaml` works without any extra JVM args
- Profile `docker` is activated via `SPRING_PROFILES_ACTIVE=docker` env var in compose

### Review Decisions (from iteration 1)
- **IMAGE_TAG parametrization**: Compose uses `${IMAGE_TAG:-latest}` for reproducible deploys, documented in .env.example
- **Tests in docker-publish**: `./gradlew build` runs before bootJar to ensure safe releases
- **Profile renamed local→docker**: `application-docker.yaml` instead of `application-local.yaml` to avoid confusion with dev config
- **.dockerignore added**: Excludes .git, build/, .gradle, docs/ from Docker build context
- **Version from tag**: `-Pversion=$VERSION` passed to bootJar so JAR contains correct version
- **Healthcheck**: curl-based healthcheck in compose targeting `/frigate-analyzer/actuator/health`, curl installed in Dockerfile
- **Trailing slash unified**: All `FRIGATE_RECORDS_FOLDER` defaults should be without trailing slash
- **Deploy instructions from tag**: curl commands use `v0.1.0` instead of `master`

### Rejected/Deferred (from review)
- ARM64 multi-arch build — deferred (not needed now)
- Secrets management — deferred (operational concern)
- Rollback strategy for migrations — deferred
- Docker image testing in CI — deferred
- Required env var validation (`${VAR:?}`) — not needed, errors are obvious at startup
- AOT Cache without DB — standard Spring Boot pattern, `|| true` is fine
- Monitoring/logging, rate limiting, pinning base images — deferred (operational)

### Important Implementation Details
- Liquibase Dockerfile uses `${LIQUI_CHANGELOG}` env var in CMD — must pass `LIQUI_CHANGELOG=master_frigate_analyzer.xml` in production compose
- App Dockerfile `ARG JAR_FILE=modules/core/build/libs/frigate-analyzer-core.jar` — CI builds JAR first, then Docker image uses it from that path
- App Docker build context must be project root (`.`) because JAR path is relative to root
- Liquibase Docker build context must be `docker/` because Dockerfile does `COPY ./liquibase/migration/`
- `.gitignore` already has `**/application-local.yaml` and `docker/.env`, need to add `docker/deploy/.env` and `**/application-docker.yaml`
- Current branch is `feature/docker-hub-publish`
- Healthcheck uses `curl` — must install curl alongside ffmpeg in Dockerfile: `apk add --no-cache ffmpeg curl`

### Build System Notes
- Gradle module for JAR: `:frigate-analyzer-core:bootJar` produces `modules/core/build/libs/frigate-analyzer-core.jar`
- Java 25 with Zulu distribution (matches `azul/zulu-openjdk-alpine:25` in Dockerfile)
- `./gradlew build` runs compile + tests + ktlint + jacoco
- Version can be overridden via `-Pversion=X.Y.Z`

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

## SPECIAL INSTRUCTIONS

### Code Review Policy

**DO NOT launch code review agents automatically.** Only run code review when the user explicitly requests it.

When the user asks for code review:

**Step 1: Ask which reviewers to use:**

Use AskUserQuestion with multiSelect: true and header: "Reviewers":
- Question: "Какие code review агенты запустить?"
- Options (all checked by default):
  - **superpowers:code-reviewer** — основной ревью (Claude)
  - **codex-code-reviewer** — Codex CLI ревью
  - **ccs-code-reviewer** — CCS ревью (PROFILE=glmt)
  - **gemini-code-reviewer** — Gemini CLI ревью

User can deselect agents they don't want to run.

**Step 2: Ask how to run the reviews:**

Use AskUserQuestion with these options:
- **Background tasks (Recommended)** — независимые агенты в фоне, можно продолжать работу пока они выполняются
- **Team of reviewers** — создать команду code reviewers через TeamCreate

**Step 3a: If "Background tasks" selected:**

Launch **only selected** agents **in parallel** in a single message, ALL with `run_in_background: true`.

After launching, display:
```
N code review агентов запущены параллельно в фоне:
  [list only selected agents with descriptions]

Ожидаю результаты. Вы можете продолжать работу — я сообщу, когда ревью завершатся.
Если хотите отменить ожидание какого-то агента, скажите об этом.
```

**Do NOT block user input.** Continue accepting user instructions while agents work.
When each agent completes, read its output_file.
After all agents finish (or user cancels some), proceed to **Step 4: Process Results**.

**Step 3b: If "Team of reviewers" selected:**

1. Create a team via TeamCreate with name `code-review`
2. Create tasks via TaskCreate (one per selected reviewer)
3. Spawn teammates via Task tool with `team_name: "code-review"` — only selected agents
4. Assign tasks to teammates
5. Wait for all to complete, then proceed to **Step 4: Process Results**
6. Shut down the team when done

**Step 4: Process Results**

After collecting results from all reviewers:

1. **Deduplicate:** If multiple agents found the same issue (same file, same problem), merge into one entry. Note all agents that found it.

2. **Analyze each issue:** For every finding, check against the actual codebase:
   - Is the issue real? (read the code, verify the claim)
   - Is the severity level correct? (Critical/Important/Minor)
   - Could this be a false positive or misunderstanding of the codebase?

3. **Present a summary table:**

| Суть проблемы | Уровень | Кто нашёл | Вердикт |
|---|---|---|---|
| Описание проблемы + `file:line` | Critical / Important / Minor | [перечислить нашедших] | Справедливо / Ложное срабатывание / Спорно (пояснение) |

4. **For each "Спорно" verdict**, briefly explain why you are unsure.

5. **Offer to fix only issues marked "Справедливо":**
   ```
   Справедливых замечаний: N. Хотите, чтобы я исправил их?
   ```
   Wait for user confirmation before making any changes.
