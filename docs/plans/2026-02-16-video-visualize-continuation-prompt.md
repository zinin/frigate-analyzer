## TASK

Continue executing the implementation plan for Video Visualize — DetectService Extension (FA-18).

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

**Completed tasks:**
- [x] Task 1: Response Models & Exception (`fd738b0`) — JobCreatedResponse, JobStatus, JobStatusResponse, JobStats, VideoAnnotationFailedException
- [x] Task 2: Configuration — VideoVisualizeConfig (`c928e7b`) — VideoVisualizeConfig data class в DetectProperties + application.yaml (main + test)
- [x] Task 3: Load Balancer — VIDEO_VISUALIZE support (`57c7b49`) — RequestType, ServerState, DetectServerProperties, StatisticsResponse, DetectServerLoadBalancer, test configs, docker template

**Remaining tasks (7 of 10):**
- [ ] Task 4: Test Dispatcher — Video Endpoints (mock endpoints in DetectServiceDispatcher)
- [ ] Task 5: DetectService.submitVideoVisualize
- [ ] Task 6: DetectService.getJobStatus
- [ ] Task 7: DetectService.downloadJobResult
- [ ] Task 8: VideoVisualizationService — Happy Path
- [ ] Task 9: VideoVisualizationService — Error Scenarios
- [ ] Task 10: Final Build

## SESSION CONTEXT

Key decisions from brainstorming + design review sessions (2 iterations, 35 issues total):

### Architecture Decisions (brainstorming)

1. **Approach:** 3 HTTP methods in DetectService (submitVideoVisualize, getJobStatus, downloadJobResult) + VideoVisualizationService orchestrator with ONE public method `annotateVideo`.

2. **RequestType.VIDEO_VISUALIZE** — отдельный тип, НЕ переиспользуем VISUALIZE. Отдельные счётчики и capacity в конфиге серверов.

3. **Slot lifetime:** слот VIDEO_VISUALIZE удерживается до завершения job (completed/failed), а не освобождается после POST. Это потому что job реально нагружает сервер на всё время обработки.

4. **Async job API** — принципиально отличается от текущих sync-методов DetectService. POST возвращает job_id мгновенно, обработка идёт минуты. Polling через GET /jobs/{job_id}.

5. **Scope:** только DetectService + models + load balancer + VideoVisualizationService. Telegram-команда — отдельная задача.

### Design Review Decisions (iteration 1)

6. **[C1] Slot management pattern:** submitVideoVisualize принимает `AcquiredServer` как параметр — НЕ делает acquireServer/releaseServer внутри. VideoVisualizationService управляет slot lifecycle (acquire в начале annotateVideo, release в finally).

7. **[C2] Download streaming:** downloadJobResult возвращает `Path` (streaming запись во временный файл) вместо `ByteArray`. Это предотвращает OOM на больших видео.

8. **[C3] Retry policy:** Делать по аналогии с существующими методами DetectService. Проверить как они работают перед реализацией.

9. **[N1] Orphan job logging:** При timeout/cancel логировать WARN что job может ещё работать на сервере. Detection server API v2.2.0 не поддерживает DELETE /jobs/{id}.

10. **[N5] JobStatus enum:** `enum class JobStatus { QUEUED, PROCESSING, COMPLETED, FAILED }` с `@JsonProperty`. Используется в `JobStatusResponse.status`.

11. **[C4] videoVisualizeRequests:** Обязательное поле в DetectServerProperties (без дефолта).

12. **Destination:** Standalone → Telegram. Видео отправляется пользователю, временный файл удаляется.

### Design Review Decisions (iteration 2)

13. **[C5] Slot management pseudocode (FIXED):** Design теперь показывает корректный retry pattern: acquire внутри retry loop, release на failure, keep на success. completed flag для orphan logging.

14. **[C6] Temp file cleanup:** downloadJobResult удаляет temp file в catch при ошибке streaming. Plan Task 7 обновлён: single OutputStream + try-catch с Files.deleteIfExists.

15. **[C7] 404 при polling = терминальная ошибка:** Если getJobStatus получает 404 (сервер перезагрузился, job потерян) — бросать VideoAnnotationFailedException, не ретраить бесконечно.

16. **[C8] Orphan logging only on failure:** var completed = false, логировать только если !completed && jobId != null.

17. **[N9] Idempotency — accepted risk:** Retry submit может создать duplicate orphan job. API не поддерживает idempotency key. Риск минимален.

18. **[N10] WebClient timeout:** Глобальный response-timeout=30s может мешать video запросам. Проверить при реализации Task 5 и 7. При необходимости переопределить на уровне отдельных запросов.

19. **[N11] Single OutputStream:** downloadJobResult открывает OutputStream один раз перед collect (не на каждый DataBuffer chunk).

20. **[N12] onProgress exception wrapping:** try { onProgress(status) } catch { logger.warn } — ошибка callback не должна убивать job.

21. **[N13] Gradle paths:** Правильные пути `:core:test`, `:model:test` (НЕ `:modules-core:test`). Исправлено в плане.

22. **[N14] Docker template:** Task 3 включает обновление `docker/deploy/application-docker.yaml.example`.

23. **[N18] Input video as Path (NOT ByteArray):** annotateVideo и submitVideoVisualize принимают `videoPath: Path` вместо `bytes: ByteArray`. Видео собирается из файлов Frigate на диске — streaming upload через FileSystemResource.

24. **[N19] detectEvery validation:** @field:Min(1) для detectEvery: Int? в VideoVisualizeConfig.

25. **[S8] Submit retry test:** Task 9 включает тест retry при transient submit failure.

26. **[S6] jobId в логах:** Включать jobId во все логи VideoVisualizationService при реализации Task 8.

### Implementation Notes (from Tasks 1-3 execution)

27. **Jackson import fix:** План указывал `tools.jackson.annotation.JsonProperty`, но правильный импорт — `com.fasterxml.jackson.annotation.JsonProperty`. В Jackson 3.x модуль `jackson-annotations` — исключение из миграции пакетов, он остаётся в `com.fasterxml.jackson.annotation`. Это подтверждено Jackson 3 migration guide и реальной кодбазой.

**Note:** Plan и Design doc синхронизированы после обоих раундов ревью. Оба документа отражают актуальные решения.

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
