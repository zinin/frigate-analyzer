## TASK

Execute the implementation plan for **AI Description Rate Limiter** feature in `frigate-analyzer` (Kotlin/Spring Boot 4 / WebFlux / R2DBC / Coroutines).

The feature adds a configurable sliding-window rate limit on Claude invocations triggered by detection events. Default: 10 requests / hour. On limit-exceed, the recording goes to Telegram as a plain notification — no AI placeholder, no second message, no edit-job, no Claude call (identical to `application.ai.description.enabled=false`).

Use `/superpowers:subagent-driven-development` skill for execution: dispatch a fresh subagent per Task with two-stage review between them. The plan is decomposed into 5 Tasks with explicit TDD steps.

## DOCUMENTS

- **Design:** `docs/superpowers/specs/2026-04-25-description-rate-limit-design.md` (391 lines, sections 1-8)
- **Plan:** `docs/superpowers/plans/2026-04-25-description-rate-limit.md` (Tasks 1-5 with bite-sized steps)
- **Iteration 1 review (already applied):** `docs/superpowers/specs/2026-04-25-description-rate-limit-review-iter-1.md` — table of every issue from 8 reviewers and how it was resolved (mostly auto-applied to design+plan)
- **Merged review output:** `docs/superpowers/specs/2026-04-25-description-rate-limit-review-merged-iter-1.md` — full text from all 8 agents

Read the design and plan first. The two review files are reference material — consult them only if you need to understand WHY a particular decision was made.

## IMPORTANT: DO NOT START WORK YET

After reading the documents:
1. Confirm you have loaded all context.
2. Summarize your understanding briefly (3-5 bullet points: what the feature does, key architectural decisions, how the work is decomposed).
3. **WAIT for user instruction before taking any action.**

Do NOT begin implementation until the user explicitly tells you to start.

## SESSION CONTEXT

### Branch state

- Current branch: `feature/description-rate-limit` (created off `master`).
- 4 commits already on the branch — all are planning artefacts (spec, plan, iter-1 review, merged review). No implementation code yet.
- Working tree: there are several untracked files unrelated to this feature (`diff.txt`, `tmp_diff_handler.txt`, `.claude/worktrees/`, old `docs/superpowers/plans/2026-04-19-*` from a previous feature, etc.). Leave them alone — they are not part of this work.

### Brainstorming decisions (all already encoded in design §2 — listed here for emphasis)

- **Skip behavior on limit-exceed.** When the limiter denies, suppress 4 channels at once: caption placeholder, second reply message, edit-job, Claude call. The user explicitly said: «не писать что описание идёт через LLM, и не отправлять второе сообщение». The single-point integration in `TelegramNotificationServiceImpl:52` (passing `null` to `descriptionHandle`) achieves all four because downstream code already gates on `descriptionHandle == null`.
- **Global counter, NOT per-camera.** User chose simplicity.
- **Sliding window, NOT fixed window or token bucket.** Sliding semantically matches "10 in last hour"; fixed has rebound; token bucket throttles bursts inappropriately for our event pattern.
- **In-memory, NOT DB.** This is short-window protection (minutes), not long-term abuse defense; service rarely restarts; no need for persistence.
- **`enabled=true, max=10, window=1h` defaults in YAML.** Users with active AI get protection out of the box.
- **Slot consumed when `tryAcquire()` returns true** (i.e. when permission is granted), NOT decremented on Claude failure. Internal retries inside `ClaudeDescriptionAgent.executeWithRetry()` do NOT pass through the limiter again — one logical describe = one slot.
- **WARN log lives in call site (`TelegramNotificationServiceImpl`), not in the limiter.** SRP: the limiter is domain-agnostic, only the call site knows `recording.id`.

### Iteration 1 review decisions (resolved BEFORE handoff to fresh session)

8 external reviewers (codex, gemini, ccs/glm/albb-glm/albb-qwen/albb-kimi/albb-minimax/deepseek) found 21 issues. 13 were auto-fixed by the orchestrator, 7 dismissed (false positives or out-of-scope), 0 escalated. Key decisions visible in the current spec/plan:

- **`RateLimit` data class has Kotlin-defaults `enabled=false, maxRequests=10, window=1h`.** This was the orchestrator's tactical choice: it auto-resolves 3 critical issues by sparing tests that build `CommonSection(...)` directly from needing updates. Production `application.yaml` overrides `enabled=true`. Spring Boot binder is the special case — `AiDescriptionAutoConfigurationTest` MUST add three explicit property values, see plan Step 1.4.
- **The integration gate is a `when` expression with 3 explicit branches:** `descriptionSupplier == null` → no work; `limiter == null` → fail-open (logged separately, no WARN); `tryAcquire()` → grant or deny (deny writes WARN). Replaces the cryptic `(getIfAvailable()?.tryAcquire() != false)`.
- **4 integration tests in `TelegramNotificationServiceImplTest`** (not 2): skip-on-deny, single-invoke-multi-recipient, AI-off bypass, limiter-missing fail-open. Plan Step 3.2(c) has full code.
- **Concurrency test uses `Dispatchers.Default`** to get real thread parallelism (without it, `runBlocking` event-loop serializes and the test passes even without `Mutex`).
- **`cleanup keeps deque bounded` test formula:** `clock.current = baseInstant.plus(window.multipliedBy((i + 1).toLong())).plusMillis(1)` — gradient time progression by `(i+1)*window+1ms` per iteration. The earlier formula was buggy (timestamps stayed in the same window).
- **`MutableClock.withZone(zone)` returns `Clock.fixed(current, zone)`** — not `throw`. Hardens the test helper.
- **Files NOT touched** thanks to data-class defaults: `ClaudeDescriptionAgentTest`, `ClaudeDescriptionAgentIntegrationTest`, `ClaudeDescriptionAgentValidationTest`, `RecordingProcessingFacadeTest`. Plan File Structure section lists this explicitly.
- **`TelegramNotificationServiceImplSignalLossTest.kt:38`** uses named-arg constructor of `TelegramNotificationServiceImpl(...)` — Plan Step 3.4 has explicit instructions to add `rateLimiterProvider` mock there. Don't miss this file.
- **Gradle project paths.** `settings.gradle.kts` includes modules as `:ai-description`, `:telegram`, `:core` (NOT `:modules:ai-description`). Use `:ai-description:test` etc. throughout.

### Project-specific rules (`/opt/github/zinin/frigate-analyzer/CLAUDE.md`)

- **NEVER run `./gradlew build` directly.** Always dispatch the `build` skill — it dispatches the `build-runner` agent. Plan steps already follow this.
- **Order:** code-reviewer → fix issues → build. Plan Task 5 follows this.
- **On ktlint errors:** `./gradlew ktlintFormat` then retry. The build skill knows.
- **ALWAYS `git add <file>`** after creating or modifying files.
- **Before opening a PR:** `git rm` everything in `docs/superpowers/` and commit. Plan Step 6.1 covers this. Don't run it now — only when ready for PR.

## PLAN QUALITY WARNING

The plan was written for a multi-step task and reviewed by 8 external models (iteration 1). Iteration 1 fixed all known critical issues, but the plan may still contain:

- Errors or inaccuracies in implementation details (especially in line numbers — they may have shifted by ±1-2).
- Oversights about edge cases that 8 reviewers all missed.
- Assumptions that don't match the actual codebase (e.g., the iter-1 review confirmed `executeWithRetry()` exists in `ClaudeDescriptionAgent.kt:102`, but other private internals may have changed).
- Missing steps for files not yet observed (e.g., if `NoOpTelegramNotificationService` ever grows to delegate to the impl, that path is not in the plan).

**If you notice any issues during implementation:**
1. STOP before proceeding with the problematic step.
2. Clearly describe the problem you found.
3. Explain why the plan doesn't work or seems incorrect.
4. Ask the user how to proceed.

Do NOT silently work around plan issues or make significant deviations without user approval. The user has been through 1 review iteration already and would rather pause than discover drift after merge.

### Specifically, watch for

- **Spring Boot binder behavior with Kotlin-data-class defaults** in nested `@ConfigurationProperties`. The plan asserts that `AiDescriptionAutoConfigurationTest` MUST pass all three rate-limit properties even though the data class has defaults — because Spring Boot binder doesn't fall back to Kotlin defaults when binding nested config that has at least one explicit property under it. If you find this assertion is wrong (binder DOES fall back), you can simplify the test, but verify experimentally first, don't just trust the plan.
- **The `kotlinx.coroutines.Deferred<Result<DescriptionResult>>?` lambda type** in test code uses qualified-import path `kotlinx.coroutines.Deferred<Result<ru.zinin.frigate.analyzer.ai.description.api.DescriptionResult>>?`. Adjust imports as needed if you prefer top-of-file imports.
- **`ObjectProvider<DescriptionRateLimiter>` mock** in tests: relaxed `mockk(relaxed = true)` for `SignalLossTest` is enough; for the new tests in `TelegramNotificationServiceImplTest` we explicitly stub `getIfAvailable()` to return either `null` or a `DescriptionRateLimiter` mock.

## INSTRUCTIONS

1. Read the documents listed above.
2. Provide a brief summary (3-5 bullets) of your understanding.
3. **STOP and WAIT** — do NOT proceed with any implementation.
4. Ask the user: "Готов начинать с Task 1? Или нужно что-то прояснить заранее?"
