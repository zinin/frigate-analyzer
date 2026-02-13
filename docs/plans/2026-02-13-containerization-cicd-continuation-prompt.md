## TASK

Continue executing the implementation plan for containerization and CI/CD setup (Docker image publishing to DockerHub, GitHub Actions, production docker-compose).

## CRITICAL: DO NOT START WORKING

**STOP. READ THIS CAREFULLY.**

After loading all context below, you MUST:
1. Read the documents and understand the context
2. Report what you understood (brief summary)
3. **WAIT for explicit user instructions** before taking ANY action

**DO NOT:**
- Start implementing tasks
- Make any code changes
- Run any commands (except reading documents)
- Assume what task to work on next

**The user will tell you exactly what to do.** Until then, only read and summarize.

## DOCUMENTS

- Design: `docs/plans/2026-02-12-containerization-cicd-design.md`
- Plan: `docs/plans/2026-02-12-containerization-cicd-plan.md`

Read both documents to understand the full picture.

## PROGRESS

**All implementation tasks are COMPLETE.** 6 commits on branch `feature/docker-hub-publish`:

- [x] Task 1: Move Dockerfile to `docker/deploy/`, add curl for healthcheck, create `.dockerignore` (`cedf763`)
- [x] Task 2: Create production `docker/deploy/docker-compose.yml` (`f92c770`)
- [x] Task 3: Create `.env.example`, `application-docker.yaml.example`, update `.gitignore` (`55853ca`)
- [x] Task 4: Create CI workflow `.github/workflows/ci.yml` (`0b9a27c`)
- [x] Task 5: Create Docker publish workflow `.github/workflows/docker-publish.yml` (`c0a4135`)
- [x] Task 6: Verify + fix trailing slash in `application.yaml` (`8507e4e`)

**What remains:**
- [ ] Code review (if user requests)
- [ ] Build verification (`./gradlew build` to ensure nothing is broken)
- [ ] Branch finishing (merge/PR/cleanup — user decision)

## SESSION CONTEXT

### Key Files Created/Modified

```
.dockerignore                                       (NEW)
.github/workflows/ci.yml                            (NEW)
.github/workflows/docker-publish.yml                (NEW)
.gitignore                                          (MODIFIED - added 2 lines)
docker/deploy/Dockerfile                            (MOVED from docker/app/, +curl)
docker/deploy/docker-compose.yml                    (NEW)
docker/deploy/.env.example                          (NEW)
docker/deploy/application-docker.yaml.example       (NEW)
modules/core/src/main/resources/application.yaml    (MODIFIED - trailing slash removed)
```

### DockerHub Details
- DockerHub username: `avzinin` (profile: https://hub.docker.com/u/avzinin)
- Two images: `avzinin/frigate-analyzer` and `avzinin/frigate-analyzer-liquibase`
- GitHub repo: `git@github.com:zinin/frigate-analyzer.git`

### Key Decisions Applied
- **`HOST_PORT`** (not `APP_PORT`) in compose — prevents env leaking into container
- **`.dockerignore` JAR exception**: `!modules/core/build/libs/*.jar` — without it Docker build fails
- **`permissions: contents: read`** in both workflows for least-privilege
- **Trailing slash removed** from `FRIGATE_RECORDS_FOLDER` default in `application.yaml`
- **`LIQUI_CHANGELOG=master_frigate_analyzer.xml`** hardcoded in compose (required by liquibase Dockerfile)

### Verified False Positives (do NOT re-investigate)
- **JAR filename with -Pversion**: `archiveFileName` is fixed without version in `modules/core/build.gradle.kts:14` — always `frigate-analyzer-core.jar`
- **Running as root**: Dockerfile already creates `appuser:appgroup` and uses `USER appuser`
- **Gradle caching**: `gradle/actions/setup-gradle@v4` handles caching automatically
- **`apk` compatibility**: Base image is `azul/zulu-openjdk-alpine:25` — Alpine, `apk` is correct

### Build System Notes
- Gradle module for JAR: `:frigate-analyzer-core:bootJar` produces `modules/core/build/libs/frigate-analyzer-core.jar`
- Java 25 with Zulu distribution
- `./gradlew build` runs compile + tests + ktlint + jacoco
- Current branch: `feature/docker-hub-publish`

### Post-Implementation Steps (from plan, user-driven)
1. GitHub secrets: `DOCKERHUB_USERNAME` = `avzinin`, `DOCKERHUB_TOKEN` = DockerHub access token
2. First release: `git tag v0.1.0 && git push origin v0.1.0`
3. Deployment: curl compose + templates from GitHub, configure `.env` and YAML, `docker compose up -d`

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

## INSTRUCTIONS

1. Read the documents listed above
2. Understand current progress and session context
3. Provide a brief summary of what you understood
4. **STOP and WAIT** — do NOT proceed with any implementation
5. Ask: "What would you like me to work on?"

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
