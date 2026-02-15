## TASK

Execute the implementation plan for Telegram command menu registration.

Use `/superpowers:subagent-driven-development` skill for execution.

## DOCUMENTS

- Design: `docs/plans/2026-02-15-telegram-command-menu-design.md`
- Plan: `docs/plans/2026-02-15-telegram-command-menu.md`

Read both documents first.

## IMPORTANT: DO NOT START WORK YET

After reading the documents:
1. Confirm you have loaded all context
2. Summarize your understanding briefly
3. **WAIT for user instruction before taking any action**

Do NOT begin implementation until the user explicitly tells you to start.

## SESSION CONTEXT

### Key Decisions

1. **Role-based command scoping:** Default scope shows only /start and /help to all users. Owner gets all 5 commands via BotCommandScopeChat(chatId). User chose this over showing all commands to everyone.

2. **Lazy owner registration:** At startup, register default commands always. For owner commands — check DB for existing owner with chatId. If not found (first startup), owner commands registered when owner executes /start. This handles the cold-start scenario.

3. **Error handling (from review):** `setMyCommands` calls wrapped in try-catch. Registration is UX enhancement, not critical — bot must start even if registration fails. Log as `warn`.

4. **DRY command lists (from review):** Commands extracted into companion object constants `DEFAULT_COMMANDS` and `OWNER_COMMANDS`. Owner list built as `DEFAULT_COMMANDS + ownerOnlyCommands`.

5. **All descriptions in Russian** — consistent with existing bot messages.

### Verified API Details

- ktgbotapi 30.0.2 provides both `vararg` and `List<BotCommand>` overloads of `setMyCommands`
- Plan uses `List` version (via constants): `bot.setMyCommands(OWNER_COMMANDS, scope = ...)`
- ChatId construction pattern: `ChatId(RawChatId(longValue))` — already used in `TelegramNotificationSender.kt:30`
- `BotCommand(command: String, description: String)` — simple data class, no slash prefix needed

### Rejected Alternatives

- Separate `CommandMenuRegistrar` component — rejected as over-engineering for ~20 lines of code
- Showing all commands to all users — rejected, owner commands would confuse regular users
- `deleteMyCommands` on owner deactivation — skipped, owner self-removal is not a supported scenario

### Edge Cases

- Owner not in DB at startup → only default commands registered, owner commands added after /start
- `setMyCommands` API failure → logged as warn, bot continues normally
- Concurrent calls to `registerOwnerCommands` from startup and /start — theoretically possible but harmless (last call wins)

### Important: Placement in handleStart()

`registerOwnerCommands(chatId)` MUST be placed **inside the `if (username == properties.owner)` block** in `handleStart()`, NOT in the general flow. This was a review finding (PLAN-2).

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

### Git Branch

Working branch is `feature/telegram-command-menu` — switch to it before starting.

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
