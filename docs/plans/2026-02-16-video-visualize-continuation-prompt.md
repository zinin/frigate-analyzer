## TASK

Execute the implementation plan for Video Visualize — DetectService Extension (FA-18).

Use `/superpowers:subagent-driven-development` skill for execution.

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

- Design: `docs/plans/2026-02-16-video-visualize-design.md`
- Plan: `docs/plans/2026-02-16-video-visualize-plan.md`

Read both documents first.

## PROGRESS

**Completed tasks:** None — implementation has not started yet.

**Remaining tasks (all 10):**
- [ ] Task 1: Response Models & Exception (JobCreatedResponse, JobStatusResponse, JobStatus enum, JobStats, VideoAnnotationFailedException)
- [ ] Task 2: Configuration — VideoVisualizeConfig in DetectProperties + application.yaml
- [ ] Task 3: Load Balancer — VIDEO_VISUALIZE support (RequestType, ServerState, DetectServerProperties, statistics)
- [ ] Task 4: Test Dispatcher — Video Endpoints (mock endpoints in DetectServiceDispatcher)
- [ ] Task 5: DetectService.submitVideoVisualize
- [ ] Task 6: DetectService.getJobStatus
- [ ] Task 7: DetectService.downloadJobResult
- [ ] Task 8: VideoVisualizationService — Happy Path
- [ ] Task 9: VideoVisualizationService — Error Scenarios
- [ ] Task 10: Final Build

## SESSION CONTEXT

Key decisions from brainstorming + design review sessions:

### Architecture Decisions (brainstorming)

1. **Approach:** 3 HTTP methods in DetectService (submitVideoVisualize, getJobStatus, downloadJobResult) + VideoVisualizationService orchestrator with ONE public method `annotateVideo`.

2. **RequestType.VIDEO_VISUALIZE** — отдельный тип, НЕ переиспользуем VISUALIZE. Отдельные счётчики и capacity в конфиге серверов.

3. **Slot lifetime:** слот VIDEO_VISUALIZE удерживается до завершения job (completed/failed), а не освобождается после POST. Это потому что job реально нагружает сервер на всё время обработки.

4. **Async job API** — принципиально отличается от текущих sync-методов DetectService. POST возвращает job_id мгновенно, обработка идёт минуты. Polling через GET /jobs/{job_id}.

5. **Scope:** только DetectService + models + load balancer + VideoVisualizationService. Telegram-команда — отдельная задача.

### Design Review Decisions (iteration 1)

6. **[C1] Slot management pattern (CHANGED from original plan):** submitVideoVisualize принимает `AcquiredServer` как параметр — НЕ делает acquireServer/releaseServer внутри. VideoVisualizationService управляет slot lifecycle (acquire в начале annotateVideo, release в finally). Это отличается от плана, где submitVideoVisualize делал acquire внутри и возвращал Pair<AcquiredServer, JobCreatedResponse>.

7. **[C2] Download streaming (CHANGED from original plan):** downloadJobResult возвращает `Path` (streaming запись во временный файл) вместо `ByteArray`. Это предотвращает OOM на больших видео. Plan пока показывает ByteArray — при реализации Task 7 нужно использовать Path.

8. **[C3] Retry policy:** Делать по аналогии с существующими методами DetectService. Проверить как они работают перед реализацией.

9. **[N1] Orphan job logging:** При timeout/cancel логировать WARN что job может ещё работать на сервере. Detection server API v2.2.0 не поддерживает DELETE /jobs/{id}.

10. **[N5] JobStatus enum (NEW, not in original plan):** Добавить `enum class JobStatus { QUEUED, PROCESSING, COMPLETED, FAILED }` с `@JsonProperty`. Использовать в `JobStatusResponse.status` вместо String. Design doc обновлён, plan — нет.

11. **[N4] detect-every in plan YAML:** Было пропущено в plan YAML блоке, добавлено.

12. **[C4] videoVisualizeRequests:** Оставить обязательным полем в DetectServerProperties (без дефолта).

13. **Destination:** Standalone → Telegram. Видео отправляется пользователю, временный файл удаляется.

### IMPORTANT: Plan vs Design Discrepancies

The plan was written BEFORE the design review. Several plan details conflict with review decisions:

- **Task 5 (submitVideoVisualize):** Plan shows method doing `acquireServer` internally and returning `Pair<AcquiredServer, JobCreatedResponse>`. ACTUAL design: method takes `AcquiredServer` as parameter, returns only `JobCreatedResponse`. No acquire/release inside.

- **Task 7 (downloadJobResult):** Plan shows returning `ByteArray`. ACTUAL design: returns `Path` (streaming to temp file).

- **Task 1 (Response Models):** Plan doesn't include `JobStatus` enum. MUST add it based on review decision N5.

- **Task 8 (VideoVisualizationService):** Plan shows `annotateVideo` returning `ByteArray`. ACTUAL design: returns `Path`. Orchestrator manages acquire/release (not DetectService).

**Always follow the DESIGN DOC over the PLAN when they conflict.**

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
