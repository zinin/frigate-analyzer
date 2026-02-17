## TASK

Execute the implementation plan for Telegram Video Export Command (`/export`).

Use `/superpowers:subagent-driven-development` skill for execution.

## DOCUMENTS

- Design: `docs/plans/2026-02-17-telegram-video-export-design.md`
- Plan: `docs/plans/2026-02-17-telegram-video-export-plan.md`

Read both documents first.

## IMPORTANT: DO NOT START WORK YET

After reading the documents:
1. Confirm you have loaded all context
2. Summarize your understanding briefly
3. **WAIT for user instruction before taking any action**

Do NOT begin implementation until the user explicitly tells you to start.

## SESSION CONTEXT

Key decisions and rationale from the brainstorming session:

### Architecture: Interface in telegram, Implementation in core

The `telegram` module does NOT depend on `service` module (despite what CLAUDE.md says about `core -> telegram -> service`). Actual deps:
- `telegram` → `common`, `model`
- `service` → `common`, `model`
- `core` → `telegram`, `service`

Therefore: `VideoExportService` interface goes in `telegram` module, `VideoExportServiceImpl` in `core` (which has access to both `service` repos and `ApplicationProperties`). No new module dependencies needed.

### Waiter pattern instead of state machine

Design doc specifies ConcurrentHashMap state machine. Plan uses ktgbotapi waiter pattern (`waitDataCallbackQuery`/`waitText`) — same UX, much less code. **Critical risk:** `waitDataCallbackQuery` might not exist in ktgbotapi v30.0.2. Task 7 in the plan has a fallback to manual state machine with `onDataCallbackQuery` + `ConcurrentHashMap`. Check API availability BEFORE writing Task 6.

### User decisions

- **UX**: Inline buttons for date/camera, text input for time range
- **Step order**: Date → Time → Camera (because camera might not have recordings for that period — user filters by available data)
- **Access**: ALL authorized users (OWNER + USER), not just OWNER
- **Fragment handling**: Frigate fragments are ~10 seconds each. No cutting — include all fragments overlapping with the requested range (round up)
- **Max duration**: 5 minutes hard limit
- **Size handling**: Compress with ffmpeg if > 45MB. Error if > 50MB after compression
- **Progress**: No intermediate "processing..." message — just send the video when ready
- **"Все камеры" option**: NOT included in v1 for simplicity

### Rejected alternatives

- **Single command with params** (`/export cam1 2025-12-28 09:00-10:00`) — rejected, user wanted interactive UX
- **Callback data encoding** (encode state in button callback_data) — rejected, 64-byte limit + still needs state for text input step
- **Splitting large videos into parts** — rejected in favor of 5-min limit + compression
- **Adding `service` dependency to `telegram` module** — rejected to avoid changing module boundaries

### Edge cases to watch

1. `sendVideo` with large files: existing code uses `ByteArray.asMultipartFile(name)` for photos. For 50MB video, `readBytes()` loads everything into memory — acceptable for v1 but note as tech debt
2. ffmpeg `concat demuxer` assumes compatible codecs across segments (Frigate records consistently, should work)
3. Time range parsing: support both `HH:MM-HH:MM` and `H:MM-H:MM` formats (the plan uses `H:mm` formatter)
4. Files might be missing from disk even though DB has records — filter with `Files.exists()`, warn in logs, error only if ALL files missing

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
