## TASK

Finalize the development branch for Video Visualize — DetectService Extension (FA-18).

All 10 implementation tasks are **COMPLETE**. Use `/superpowers:finishing-a-development-branch` skill.

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
- Assume what to do next

**The user will tell you exactly what to do.** Until then, only read and summarize.

## DOCUMENTS

- Design: `docs/plans/2026-02-16-video-visualize-design.md`
- Plan: `docs/plans/2026-02-16-video-visualize-plan.md`

Read both documents first.

## PROGRESS

**All 10 tasks COMPLETE:**
- [x] Task 1: Response Models & Exception (`fd738b0`) — JobCreatedResponse, JobStatus, JobStatusResponse, JobStats, VideoAnnotationFailedException
- [x] Task 2: Configuration — VideoVisualizeConfig (`c928e7b`) — VideoVisualizeConfig data class в DetectProperties + application.yaml (main + test)
- [x] Task 3: Load Balancer — VIDEO_VISUALIZE support (`57c7b49`) — RequestType, ServerState, DetectServerProperties, StatisticsResponse, DetectServerLoadBalancer, test configs, docker template
- [x] Task 4: Test Dispatcher — Video Endpoints (`57a4526`) — mock endpoints в обоих диспетчерах: POST /detect/video/visualize (202), GET /jobs/{id} (completed), GET /jobs/{id}/download (binary mp4)
- [x] Task 5: DetectService.submitVideoVisualize (`f8cf084`) — multipart POST через FileSystemResource(videoPath), принимает AcquiredServer, не управляет слотами
- [x] Task 6: DetectService.getJobStatus (`20bb813`) — GET /jobs/{jobId} через URI template, возвращает JobStatusResponse
- [x] Task 7: DetectService.downloadJobResult (`a9a95b0`) — streaming в temp file через DataBuffer, cleanup на ошибке, возвращает Path
- [x] Task 8: VideoVisualizationService — Happy Path (`18cd3f7`) — оркестратор с 3 фазами (submit с retry → poll → download), slot lifecycle, orphan logging
- [x] Task 9: Error Scenarios (`c78b509`) — 3 error-сценария (job failed, timeout, retry) + 3 mock-диспетчера (JobFailedDispatcher, NeverCompletingDispatcher, TransientSubmitFailureDispatcher)
- [x] Task 10: Final Build (`d56fa41`) — ktlint-форматирование, BUILD SUCCESSFUL (все 50 тестов прошли)

**Branch:** `feature/FA-18` (21 commits ahead of master)

## SESSION CONTEXT

Key decisions from brainstorming + design review + implementation sessions:

### Architecture Decisions (brainstorming)

1. **Approach:** 3 HTTP methods in DetectService (submitVideoVisualize, getJobStatus, downloadJobResult) + VideoVisualizationService orchestrator with ONE public method `annotateVideo`.

2. **RequestType.VIDEO_VISUALIZE** — отдельный тип, НЕ переиспользуем VISUALIZE. Отдельные счётчики и capacity в конфиге серверов.

3. **Slot lifetime:** слот VIDEO_VISUALIZE удерживается до завершения job (completed/failed), а не освобождается после POST. Это потому что job реально нагружает сервер на всё время обработки.

4. **Async job API** — принципиально отличается от текущих sync-методов DetectService. POST возвращает job_id мгновенно, обработка идёт минуты. Polling через GET /jobs/{job_id}.

5. **Scope:** только DetectService + models + load balancer + VideoVisualizationService. Telegram-команда — отдельная задача.

### Design Review Decisions (iteration 1)

6. **[C1] Slot management pattern:** submitVideoVisualize принимает `AcquiredServer` как параметр — НЕ делает acquireServer/releaseServer внутри. VideoVisualizationService управляет slot lifecycle (acquire в начале annotateVideo, release в finally).

7. **[C2] Download streaming:** downloadJobResult возвращает `Path` (streaming запись во временный файл) вместо `ByteArray`. Это предотвращает OOM на больших видео.

8. **[C3] Retry policy:** По аналогии с существующими методами DetectService.

9. **[N1] Orphan job logging:** При timeout/cancel логировать WARN что job может ещё работать на сервере. Detection server API v2.2.0 не поддерживает DELETE /jobs/{id}.

10. **[N5] JobStatus enum:** `enum class JobStatus { QUEUED, PROCESSING, COMPLETED, FAILED }` с `@JsonProperty`.

11. **[C4] videoVisualizeRequests:** Обязательное поле в DetectServerProperties (без дефолта).

### Design Review Decisions (iteration 2)

12. **[C5] Slot management pseudocode (FIXED):** acquire внутри retry loop, release на failure, keep на success. completed flag для orphan logging.

13. **[C6] Temp file cleanup:** downloadJobResult удаляет temp file в catch при ошибке streaming.

14. **[C7] 404 при polling = терминальная ошибка:** Бросать VideoAnnotationFailedException, не ретраить бесконечно.

15. **[C8] Orphan logging only on failure:** var completed = false, логировать только если !completed && jobId != null.

16. **[N10] WebClient timeout:** Глобальный response-timeout=30s НЕ был проблемой при реализации (submit 202 мгновенно, download через streaming не блокируется).

17. **[N12] onProgress exception wrapping:** try { onProgress(status) } catch { logger.warn } — ошибка callback не должна убивать job.

18. **[N18] Input video as Path (NOT ByteArray):** annotateVideo и submitVideoVisualize принимают `videoPath: Path`.

### Implementation Notes (from Tasks 1-10 execution)

19. **Jackson import fix:** План указывал `tools.jackson.annotation.JsonProperty`, но правильный импорт — `com.fasterxml.jackson.annotation.JsonProperty`. В Jackson 3.x модуль `jackson-annotations` — исключение из миграции пакетов.

20. **jackson-annotations dependency:** При реализации Task 7 обнаружена отсутствующая зависимость `com.fasterxml.jackson.core:jackson-annotations` в model модуле. Добавлена в `modules/model/build.gradle.kts`.

21. **Все spec compliance reviews пройдены с первой попытки** (Tasks 7, 8, 9).

22. **ktlint fixes (Task 10):** DetectServerLoadBalancer.kt (line-length fix — extracted variables), DetectService.kt (import ordering + code style), JobStatus.kt (enum formatting).

23. **50 тестов всего**, все проходят. 4 новых теста для VideoVisualizationService (happy path + 3 error scenarios), 12 тестов DetectService (включая 3 новых для video endpoints).

## NEXT STEPS

Implementation is complete. Next steps:
1. **Code review** — run reviewers against the full diff (master..HEAD)
2. **Clean up plan docs** — `git rm docs/plans/` files before PR (per workflow rules)
3. **Create PR** — to master

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
