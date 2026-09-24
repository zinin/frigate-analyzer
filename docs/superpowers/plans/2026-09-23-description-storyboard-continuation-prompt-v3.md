## TASK

Finish the implementation plan for «раскадровка для AI-описаний» (description storyboard). All 11 tasks are done, and so are the final whole-branch review, its fix wave and the full build. What remains is the finishing stage: the external review decision, cleaning `docs/superpowers/`, the PR, and the final report.

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

- Design: `docs/superpowers/specs/2026-09-23-description-storyboard-design.md`
- Plan: `docs/superpowers/plans/2026-09-23-description-storyboard.md`. All tasks are trimmed to commit references; the section «Перед PR» at the end still applies.
- SDD ledger (git-ignored, authoritative): `.superpowers/sdd/2026-09-23-description-storyboard/progress.md`. It holds:
  - the preflight table;
  - **Rulings 1–13**, each with its reason and cost if wrong;
  - every deferred minor with its triage;
  - the last lines: final review, fix wave, build, pause.
- Final review report: `.superpowers/sdd/2026-09-23-description-storyboard/final-review.md`. See Recommendations 3–5, "Declined to judge", and the "Leave" triage.
- Fix-wave report: `.superpowers/sdd/2026-09-23-description-storyboard/final-fix-report.md`.

Read the design, the plan's «Перед PR» section, the tail of the ledger (from `Session 3` on) and `final-review.md`. Also read `CLAUDE.md`.

## PROGRESS

**Completed tasks:**
- [x] Tasks 1–9: see the plan's commit references (`55faa80` … `87cdf8e`)
- [x] Task 10: `StoryboardProperties` and `StoryboardBuilder` — `8a96310`
- [x] Task 11: the facade gives the description model the storyboard; docs — `518591b`
- [x] Final whole-branch review (opus): "With fixes", 0 Critical / 0 Important
- [x] One fix wave F1–F13 — `b73a321` (code: coverage decided by the tiles that came back, zero after a failed previous segment), `52d0ae5` (tests), `46d82b4` (docs). The scoped re-review addressed all 13, with no new breakage.
- [x] Full `./gradlew build` via `claude-forge:build-runner` at `46d82b4`: BUILD SUCCESSFUL.
  - Tests: core 534, ai-description 347 (1 `@Disabled`: `ClaudeBackendIntegrationTest`), service 125, telegram 417, model 24.
  - Testcontainers and ffmpeg integration tests ran; none were skipped.
  - No new compiler warnings. ktlint clean.

**Remaining (finishing stage, in order):**
- [ ] External review decision. `claude-mesh:do-plan` step 7 requires offering `/claude-mesh:code-review-fresh-session` BEFORE `superpowers:finishing-a-development-branch`. If the owner takes it, hold finishing entirely until its findings are applied: no `git rm`, no push, no PR, no merge. The previous session asked this with AskUserQuestion; the owner rejected that tool call and switched sessions. Do not re-ask on your own; wait for the owner's instruction.
- [ ] Separate commit `git rm -r docs/superpowers/` → `chore: remove design and plan documents before PR`. The plan documents must not be in the PR diff; they stay in the branch history.
- [ ] Push and PR — only when the owner asks. Remote `origin` (git@github.com:zinin/frigate-analyzer.git) answered `git ls-remote`; `gh` 2.46.0 is installed.
- [ ] Final message with every ledger `Ruling:` line (1–13), in order, each with its cost if wrong (SDD "Finish"). Delete `.superpowers/sdd/2026-09-23-description-storyboard/` only after the merge.

## SESSION CONTEXT

Answer in Russian; do not translate code identifiers or commands.

**Rulings made in session 3** (1–7 are older; all 13 are in the ledger):
- **8:** `StoryboardWiringTest` (ApplicationContextRunner). The storyboard beans exist only with `application.ai.description.enabled=true`.
- **9:** the builder's INFO line describes what the grid shows: `prev: sampling failed` / `next: sampling failed`, and the shown footage range.
- **10:** `StoryboardBuilder.build` catches `Throwable` after rethrowing `CancellationException`. The facade calls it outside its describe try/catch.
- **11:** after a failed PREVIOUS segment, the zero becomes 0.0 (spec 4.2). This replaces the PREVIOUS half of Ruling 2 and the plan's −4.0.
- **12:** the tiles that came back decide what is on the grid. An empty current-recording result → fallback; an empty neighbour → failed neighbour.
- **13:** CLAUDE.md's `superpowers:code-reviewer` step was done with its template in the final review. The fix wave had a scoped re-review; there was no third review.

**PR description must carry the rollout notes.** They now live only in the plan and the final review, and both are removed before the PR:
- Explicit `APP_AI_DESCRIPTION_MAX_FRAMES` / `APP_AI_DESCRIPTION_SHORT_MAX` in the production `.env` override the new defaults 4 / 400.
- No migrations: the new queries use `idx_recordings_record_timestamp`.
- After the deploy, check:
  - `docker compose exec frigate-analyzer ffmpeg -version`. Ruling 4's filter `fps=…:start_time=0:round=up` was verified only on ffmpeg 8.0.1; the image is Alpine and CI uses apt ffmpeg.
  - The `Storyboard for …` INFO lines: tile count, how often `next: missing after N s` and `sampling failed` appear, `built in`.
  - The WARN `Claude read N times for M staged frames`, which tells whether Claude reads the grid under the unchanged `FRAME_READING_RULE`.
  - A few real car events, checked by eye.
- CI: confirm that `StoryboardFrameSamplerIntegrationTest` ran on `ubuntu-latest` apt ffmpeg rather than being skipped.
- Deployment assumption: the Frigate host clock must not run ahead by more than 1 s (NTP). Otherwise the next segment never "settles" and every fresh description waits until the deadline.

**Deferred minors:**
- 63 in the ledger. The final review moved 14 into the fix wave (done) and left 48 with reasons.
- One new optional minor: `configuration.md:15-16` / `README.md:122-123` say that "without ffmpeg every description falls back". That holds only while the storyboard is on.
- None blocks the merge.

**Known pre-existing noise** (not from this branch):
- Compiler warnings: `DetectService.kt`, `DetectServiceDispatcher.kt`, `GrokBackendTest.kt:135`, `GrokPromptFileWriterTest.kt:98`, `GrokHomeGuardTest.kt` (7× opt-in), `JudgeStatsRepository.kt`, telegram `FrigateAnalyzerBot.kt:266`, `CancelExportHandler.kt:76`, `ScheduleSettingsFlow.kt:93/97`, `QuickExportHandler.kt:90`.
- Build noise: kapt `mapstruct` options in `service`; byte-buddy `sun.misc.Unsafe`; CDS "Sharing is only supported…"; netty `System::loadLibrary`.

**Owner's rules:**
- Subagents: opus, never haiku.
- No local memory (`~/.claude/projects/.../memory/`).
- Install packages via apt.
- `git add` only named files. The tree has many unrelated untracked files: `.taskmaster/`, `docs/*.md`, old files under `docs/superpowers/`, `tmp_diff_handler.txt`, `.superpowers/`.
- Commits in the repository style. No `Claude-Session:` line unless the session has a URL; session 3 had none.
- Never push without being asked.
- Gradle runs only through `claude-forge:build-runner`. Its command form is `JAVA_HOME=/usr/lib/jvm/zulu25 ./gradlew … --no-watch-fs --console=plain`; the machine default is Java 21.

HEAD at handoff: `971bd57` (plan trim) plus the commit carrying this prompt. The code head is `46d82b4`.

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
