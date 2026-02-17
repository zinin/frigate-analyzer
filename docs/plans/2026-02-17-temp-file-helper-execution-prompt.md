## TASK

Execute the implementation plan for TempFileHelper.

Use `/superpowers:subagent-driven-development` skill for execution.

## DOCUMENTS

- Design: `docs/plans/2026-02-17-temp-file-helper-design.md`
- Plan: `docs/plans/2026-02-17-temp-file-helper-plan.md`

Read both documents first.

## IMPORTANT: DO NOT START WORK YET

After reading the documents:
1. Confirm you have loaded all context
2. Summarize your understanding briefly
3. **WAIT for user instruction before taking any action**

Do NOT begin implementation until the user explicitly tells you to start.

## SESSION CONTEXT

Key decisions and context from the brainstorming session:

### Approach: Suspend-first with `withContext(Dispatchers.IO)`

- **Chosen over** Reactor-style (`Mono`/`Flux<DataBuffer>`) and hybrid approaches
- **Reason:** Project already uses coroutines idiomatically (`suspend fun` + `withContext(IO)` everywhere). Reactor would be inconsistent.

### Back-pressure discussion

- `Flow<ByteArray>` has built-in back-pressure (pull-based) — next chunk is not read until collector processes the current one
- Memory bounded to one buffer (32KB default)
- No need for `AsynchronousFileChannel` — regular `InputStream` inside `withContext(IO)` is simpler and equivalent for local files
- User explicitly wants chunked read/write to avoid loading entire files into memory

### Module placement: `core` (not `service` or `common`)

- **Reason:** Needs `ApplicationProperties.tempFolder` which lives in `core`
- Package: `ru.zinin.frigate.analyzer.core.helper` (next to existing `SpringProfileHelper`)

### Cleanup mechanism: `@Scheduled`

- Chosen over coroutine-with-delay and Task+ApplicationListener patterns
- `@EnableScheduling` is already enabled in `FrigateAnalyzerApplication.kt`
- Uses `runBlocking(Dispatchers.IO)` inside `@Scheduled` since Spring scheduling doesn't support coroutines natively

### Refactoring scope

- **DetectService.downloadJobResult()** — replace `Files.createTempFile` and `Files.deleteIfExists` with `TempFileHelper` calls. `DataBufferUtils.write(flux, tempFile)` stays as-is (writes from WebClient stream, not TempFileHelper's concern)
- **VideoServiceImpl — NOT refactored** — uses `Files.createDirectories()` for ffmpeg work dirs, different pattern, and lives in `service` module which has no dependency on `core`

### DetectService constructor change

- After refactoring, `DetectService` takes `TempFileHelper` instead of `ApplicationProperties` (was only used for `tempFolder`)
- `DetectServiceTest` needs updating to pass `TempFileHelper` instead of `ApplicationProperties`

### Testing patterns in this project

- JUnit 5, `kotlinx-coroutines-test` (`runTest`), no mockk needed for TempFileHelper (pure file ops)
- `@TempDir` for filesystem isolation
- `Clock.fixed()` for deterministic timestamps (e.g., `Clock.fixed(Instant.parse("2026-02-17T10:30:45Z"), ZoneOffset.UTC)`)
- See `WatchRecordsTaskTest.kt` for Clock usage reference

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
