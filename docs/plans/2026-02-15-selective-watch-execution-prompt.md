## TASK

Execute the implementation plan for selective file watching in Frigate recordings.

Use `/superpowers:subagent-driven-development` skill for execution.

## DOCUMENTS

- Design: `docs/plans/2026-02-15-selective-watch-design.md`
- Plan: `docs/plans/2026-02-15-selective-watch-plan.md`

Read both documents first.

## IMPORTANT: DO NOT START WORK YET

After reading the documents:
1. Confirm you have loaded all context
2. Summarize your understanding briefly
3. **WAIT for user instruction before taking any action**

Do NOT begin implementation until the user explicitly tells you to start.

## SESSION CONTEXT

Key decisions from brainstorming session:

1. **Approach chosen:** Date-filtered registration + periodic cleanup (Approach 1). WatchService already catches ENTRY_CREATE for new directories — we only add date filtering at registration time and periodic cleanup of expired watches.

2. **Rejected alternatives:**
   - Full rescan on @Scheduled — unnecessary FS load, potential event loss between scans, WatchService already handles new dirs
   - Only current day folders — too rigid, loses events at midnight boundary

3. **Config grouping:** User explicitly requested moving `disable-first-scan-task` and `frigate-records-folder` out of top-level `ApplicationProperties` into a new `records-watcher` group. This is a refactoring step — all references must be updated.

4. **RecordsWatcherProperties pattern:** Follow the same pattern as `PipelineProperties` — separate `@ConfigurationProperties(prefix = "application.records-watcher")` class, registered in `@EnableConfigurationProperties` on `FrigateAnalyzerApplication`.

5. **Clock bean:** Already exists at `modules/common/src/main/kotlin/ru/zinin/frigate/analyzer/common/config/ClockConfig.kt` — inject it into `WatchRecordsTask`, do NOT create a new one.

6. **Files that reference old fields** (must ALL be updated in Task 3):
   - `ApplicationProperties.kt` — remove `disableFirstScanTask`, `frigateRecordsFolder`
   - `ApplicationListener.kt` — uses `applicationProperties.disableFirstScanTask`
   - `WatchRecordsTask.kt` — uses `applicationProperties.frigateRecordsFolder`
   - `FirstTimeScanTask.kt` — uses `applicationProperties.frigateRecordsFolder`
   - `DetectServiceTest.kt` — constructs `ApplicationProperties` with both fields
   - `application.yaml` (main + test) — yaml keys change

7. **Edge case — date extraction:** The function scans path components from right to left looking for `YYYY-MM-DD` regex match. Root recordings folder has no date → always registered (needed to catch new date-directory creation events).

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
