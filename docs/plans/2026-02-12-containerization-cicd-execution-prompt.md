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

## SPECIAL INSTRUCTIONS

### Code Review Policy

**DO NOT launch code review agents automatically.** Only run code review when the user explicitly requests it.

When the user asks for code review:

**Step 1: Ask the user how to run the reviews:**

Use AskUserQuestion with these options:
- **Background tasks (Recommended)** — 4 независимых агента в фоне, вы можете продолжать работу пока они выполняются
- **Team of reviewers** — создать команду из 4 code reviewers через TeamCreate для координированного ревью

**Step 2a: If "Background tasks" selected:**

Launch 4 agents **in parallel** in a single message, ALL with `run_in_background: true`:
1. `superpowers:code-reviewer` — основной ревью
2. `codex-code-reviewer` — Codex CLI ревью
3. `ccs-code-reviewer` with PROFILE=glmt — CCS ревью
4. `gemini-code-reviewer` — Gemini CLI ревью

After launching, display:
```
Четыре code review агента запущены параллельно в фоне:
  1. superpowers:code-reviewer — основной ревью
  2. codex-code-reviewer — Codex CLI ревью
  3. ccs-code-reviewer (glmt) — CCS ревью
  4. gemini-code-reviewer — Gemini CLI ревью

Ожидаю результаты. Вы можете продолжать работу — я сообщу, когда ревью завершатся.
Если хотите отменить ожидание какого-то агента, скажите об этом.
```

**Do NOT block user input.** Continue accepting user instructions while agents work.
When each agent completes, read its output_file.
After all agents finish (or user cancels some), proceed to **Step 3: Process Results**.

**Step 2b: If "Team of reviewers" selected:**

1. Create a team via TeamCreate with name `code-review`
2. Create 4 tasks via TaskCreate (one per reviewer)
3. Spawn 4 teammates via Task tool with `team_name: "code-review"`:
   - `superpowers:code-reviewer`
   - `codex-code-reviewer`
   - `ccs-code-reviewer` with PROFILE=glmt
   - `gemini-code-reviewer`
4. Assign tasks to teammates
5. Wait for all to complete, then proceed to **Step 3: Process Results**
6. Shut down the team when done

**Step 3: Process Results**

After collecting results from all reviewers:

1. **Deduplicate:** If multiple agents found the same issue (same file, same problem), merge into one entry. Note all agents that found it.

2. **Analyze each issue:** For every finding, check against the actual codebase:
   - Is the issue real? (read the code, verify the claim)
   - Is the severity level correct? (Critical/Important/Minor)
   - Could this be a false positive or misunderstanding of the codebase?

3. **Present a summary table:**

| Суть проблемы | Уровень | Кто нашёл | Вердикт |
|---|---|---|---|
| Описание проблемы + `file:line` | Critical / Important / Minor | code-reviewer, codex, ccs | Справедливо / Ложное срабатывание / Спорно (пояснение) |

4. **For each "Спорно" verdict**, briefly explain why you are unsure.

5. **Offer to fix only issues marked "Справедливо":**
   ```
   Справедливых замечаний: N. Хотите, чтобы я исправил их?
   ```
   Wait for user confirmation before making any changes.
