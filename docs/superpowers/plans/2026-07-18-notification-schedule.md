# Notification Schedule Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Global (OWNER-configured) daily schedule that suppresses recording-detection Telegram notifications outside a configured time window (e.g. deliver only 00:00–07:00).

**Architecture:** Three new string keys in the existing `app_settings` table, read through a new fail-open `NotificationScheduleService`; one new suppression branch (`OUT_OF_SCHEDULE`) in `NotificationDecisionServiceImpl` keyed off `recording.recordTimestamp` in the schedule's explicit timezone; UI is an extension of the `/notifications` dialog — stateless inline hour pickers and a zone screen under the new `nfs:g:sched:*` callback subtree.

**Tech Stack:** Kotlin 2.3, Spring Boot 4, R2DBC/PostgreSQL, ktgbotapi (inline keyboards + waiter API), JUnit 5 + mockk + kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-07-18-notification-schedule-design.md` (same branch).

## Global Constraints

- Gradle module names carry the `frigate-analyzer-` prefix: `:frigate-analyzer-model`, `:frigate-analyzer-service`, `:frigate-analyzer-telegram`.
- Do NOT run `./gradlew build` directly in the main session — dispatch the `build-runner` agent (project CLAUDE.md). Per-task module tests (`./gradlew :frigate-analyzer-<module>:test --tests "..."`) also go through `build-runner`.
- On ktlint errors: `./gradlew ktlintFormat`, then retry.
- `git add <file>` immediately after creating or modifying every file (project CLAUDE.md rule).
- Every user-visible string goes to BOTH `messages_en.properties` AND `messages_ru.properties` (`MessageKeyParityTest` fails otherwise).
- Telegram callback data must stay ≤ 64 bytes; longest new payload is `nfs:g:sched:z:Asia/Yekaterinburg` (32 bytes).
- Storage window format is `HH:mm-HH:mm` (hyphen); display format is `HH:mm–HH:mm` (en dash).
- Half-open interval semantics `[start, end)` everywhere; `start > end` = midnight crossing; `start == end` is invalid.
- New settings reads must be fail-open: corrupt/unreadable schedule → warn + treat as disabled (notifications flow).

---

### Task 1: `ScheduleWindow` + `NotificationSchedule` model types

✅ Done — see commit(s): `6394584`

---

### Task 2: `AppSettingsService.getString` / `setString` + negative caching of absent keys

✅ Done — see commit(s): `55350a5`, `d2448eb`, `a18ec03`

---

### Task 3: `AppSettingKeys` + `NotificationScheduleService`

✅ Done — see commit(s): `ac4172c`

---

### Task 4: `OUT_OF_SCHEDULE` suppression in the decision service

✅ Done — see commit(s): `2667433`, `17f6670`

---

### Task 5: `NotificationsViewState` schedule fields + `NotificationsViewStateFactory`

✅ Done — see commit(s): `a13d316`

---

### Task 6: Schedule status line and buttons on the `/notifications` main screen

✅ Done — see commit(s): `f2807f1`, `ef1f07e`

---

### Task 7: `ScheduleKeyboardRenderer` — hour pickers and zone screen

✅ Done — see commit(s): `e9a6460`

---

### Task 8: `ScheduleCallbackHandler` — pure dispatch for `nfs:g:sched:*`

✅ Done — see commit(s): `70e0c8f`, `985b9fe`

---

### Task 9: `ScheduleSettingsFlow` + bot wiring

**Files:**
- Create: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/handler/notifications/ScheduleSettingsFlow.kt`
- Modify: `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/bot/FrigateAnalyzerBot.kt`
- Modify: `modules/telegram/src/main/resources/messages_en.properties`
- Modify: `modules/telegram/src/main/resources/messages_ru.properties`

**Interfaces:**
- Consumes: `ScheduleCallbackHandler.dispatch/PREFIX/Outcome` (Task 8), `ScheduleKeyboardRenderer` (Task 7), `NotificationsViewStateFactory` (Task 5), `NotificationsMessageRenderer.render`, `NotificationScheduleService.getZone/setZone`, `TelegramUserService.findByChatIdAsDto`, ktgbotapi waiter API (`waitTextMessage`, pattern from `TimezoneCommandHandler`).
- Produces: `ScheduleSettingsFlow` with `suspend fun BehaviourContext.handle(data: String, callbackMsg: ContentMessage<TextContent>, current: TelegramUserDto, isOwner: Boolean)` — the bot routes every callback starting with `nfs:g:sched:` here BEFORE the generic nfs dispatch.

This is Telegram I/O glue mirroring the existing untested nfs-callback block in the bot; all decision logic is already unit-tested in Task 8, screens in Task 7. No new unit tests — the deliverable gate is compilation plus the full telegram module test suite staying green.

- [ ] **Step 1: Add i18n keys for the manual zone dialog**

`messages_en.properties`:

```properties
notifications.sched.zone.manual.prompt=Send an IANA timezone code (e.g. Europe/Moscow), or /cancel
notifications.sched.zone.saved=✅ Schedule timezone saved: {0}
notifications.sched.zone.invalid=⚠️ Unknown timezone. Open the zone screen and try again.
notifications.sched.zone.cancelled=Timezone input cancelled
notifications.sched.zone.timeout=⏰ Timed out waiting for a timezone.
```

`messages_ru.properties`:

```properties
notifications.sched.zone.manual.prompt=Пришлите код таймзоны IANA (например, Europe/Moscow) или /cancel
notifications.sched.zone.saved=✅ Часовой пояс расписания сохранён: {0}
notifications.sched.zone.invalid=⚠️ Неизвестный часовой пояс. Откройте экран пояса и попробуйте снова.
notifications.sched.zone.cancelled=Ввод часового пояса отменён
notifications.sched.zone.timeout=⏰ Время ожидания часового пояса истекло.
```

`git add` both files.

- [ ] **Step 2: Create `ScheduleSettingsFlow`**

`ScheduleSettingsFlow.kt`:

```kotlin
package ru.zinin.frigate.analyzer.telegram.bot.handler.notifications

import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitTextMessage
import dev.inmo.tgbotapi.types.message.abstracts.ContentMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import ru.zinin.frigate.analyzer.service.NotificationScheduleService
import ru.zinin.frigate.analyzer.telegram.dto.TelegramUserDto
import ru.zinin.frigate.analyzer.telegram.i18n.MessageResolver
import ru.zinin.frigate.analyzer.telegram.service.TelegramUserService
import java.time.DateTimeException
import java.time.ZoneId

private val logger = KotlinLogging.logger {}

/**
 * Telegram I/O for the /notifications schedule sub-dialog: maps [ScheduleCallbackHandler]
 * outcomes to screen edits and runs the manual-zone waiter (same conventions as /timezone:
 * 120 s timeout, /cancel, error reply on unknown zone).
 */
@Component
@ConditionalOnProperty(prefix = "application.telegram", name = ["enabled"], havingValue = "true")
class ScheduleSettingsFlow(
    private val callbackHandler: ScheduleCallbackHandler,
    private val scheduleService: NotificationScheduleService,
    private val userService: TelegramUserService,
    private val viewStateFactory: NotificationsViewStateFactory,
    private val mainRenderer: NotificationsMessageRenderer,
    private val scheduleRenderer: ScheduleKeyboardRenderer,
    private val msg: MessageResolver,
) {
    suspend fun BehaviourContext.handle(
        data: String,
        callbackMsg: ContentMessage<TextContent>,
        current: TelegramUserDto,
        isOwner: Boolean,
    ) {
        val chatId =
            current.chatId ?: run {
                // Owner is always ACTIVE with a chatId — reaching here means a broken invariant.
                logger.warn { "sched callback from user without chatId: ${current.username}" }
                return
            }
        val lang = current.languageCode ?: "en"
        when (val outcome = callbackHandler.dispatch(data, isOwner, chatId, current.username)) {
            ScheduleCallbackHandler.Outcome.RenderMain -> renderMain(callbackMsg, current, isOwner)

            ScheduleCallbackHandler.Outcome.RenderStartPicker -> edit(callbackMsg, scheduleRenderer.startPicker(lang))

            is ScheduleCallbackHandler.Outcome.RenderEndPicker ->
                edit(callbackMsg, scheduleRenderer.endPicker(outcome.startHour, outcome.rejectedEqualEnd, lang))

            ScheduleCallbackHandler.Outcome.RenderZoneScreen ->
                edit(callbackMsg, scheduleRenderer.zoneScreen(scheduleService.getZone()?.id, lang))

            ScheduleCallbackHandler.Outcome.AwaitManualZone -> manualZoneInput(callbackMsg, current, isOwner, lang)

            ScheduleCallbackHandler.Outcome.Unauthorized -> Unit

            ScheduleCallbackHandler.Outcome.Ignore -> Unit
        }
    }

    private suspend fun BehaviourContext.manualZoneInput(
        callbackMsg: ContentMessage<TextContent>,
        current: TelegramUserDto,
        isOwner: Boolean,
        lang: String,
    ) {
        val cid = callbackMsg.chat.id
        sendTextMessage(cid, msg.get("notifications.sched.zone.manual.prompt", lang))
        val completed =
            withTimeoutOrNull(MANUAL_ZONE_TIMEOUT_MS) {
                val inputMsg = waitTextMessage().filter { it.chat.id == cid }.first()
                val input = inputMsg.content.text.trim()
                if (input == "/cancel") {
                    sendTextMessage(cid, msg.get("notifications.sched.zone.cancelled", lang))
                    return@withTimeoutOrNull
                }
                try {
                    val zone = ZoneId.of(input)
                    scheduleService.setZone(zone, current.username)
                    sendTextMessage(cid, msg.get("notifications.sched.zone.saved", lang, zone.id))
                    renderMain(callbackMsg, current, isOwner)
                } catch (e: DateTimeException) {
                    sendTextMessage(cid, msg.get("notifications.sched.zone.invalid", lang))
                }
            }
        if (completed == null) {
            sendTextMessage(cid, msg.get("notifications.sched.zone.timeout", lang))
        }
    }

    private suspend fun BehaviourContext.renderMain(
        callbackMsg: ContentMessage<TextContent>,
        current: TelegramUserDto,
        isOwner: Boolean,
    ) {
        val updated = current.chatId?.let { userService.findByChatIdAsDto(it) } ?: current
        val state = viewStateFactory.build(updated, isOwner)
        edit(callbackMsg, mainRenderer.render(state))
    }

    private suspend fun BehaviourContext.edit(
        callbackMsg: ContentMessage<TextContent>,
        rendered: RenderedNotifications,
    ) {
        try {
            editMessageText(callbackMsg, rendered.text, replyMarkup = rendered.keyboard)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.message?.contains("message is not modified", ignoreCase = true) == true) {
                logger.debug { "sched edit no-op (message not modified)" }
            } else {
                logger.warn(e) { "Failed to edit /notifications schedule screen" }
            }
        }
    }

    companion object {
        private const val MANUAL_ZONE_TIMEOUT_MS = 120_000L
    }
}
```

`git add` the file.

- [ ] **Step 3: Wire into `FrigateAnalyzerBot`**

1. Constructor: add `private val scheduleSettingsFlow: ScheduleSettingsFlow,` after `notificationsViewStateFactory`.
2. Add import `ru.zinin.frigate.analyzer.telegram.bot.handler.notifications.ScheduleCallbackHandler` and `...ScheduleSettingsFlow`.
3. Inside the `onDataCallbackQuery(initialFilter = { it.data.startsWith("nfs:") })` block, immediately after `val owner = userService.isOwner(current.username)` and BEFORE the `notificationsSettingsCallbackHandler.dispatch(...)` call, insert:

```kotlin
                    // Routing invariant: the sched subtree MUST be intercepted BEFORE the generic
                    // notificationsSettingsCallbackHandler.dispatch (which silently IGNOREs it).
                    if (callback.data.startsWith(ScheduleCallbackHandler.PREFIX)) {
                        @Suppress("UNCHECKED_CAST")
                        with(scheduleSettingsFlow) {
                            handle(callback.data, callbackMsg as ContentMessage<TextContent>, current, owner)
                        }
                        return@onDataCallbackQuery
                    }
```

(`ContentMessage` is already imported in this file for the existing `editMessageText` cast; if not, add `dev.inmo.tgbotapi.types.message.abstracts.ContentMessage`.)

4. Guard the routing invariant: in the existing `NotificationsSettingsCallbackHandlerTest` add a
   test asserting that `dispatch("nfs:g:sched:on", ...)` returns the IGNORE outcome (copy the
   dispatch-call signature from a neighbouring test in that file) — if someone later reorders the
   bot checks, the silent swallow becomes visible.

`git add` the files.

- [ ] **Step 4: Full telegram module test run**

Via `build-runner` agent: `./gradlew :frigate-analyzer-telegram:test`
Expected: PASS (includes `MessageKeyParityTest` for the new keys). Fix any ktlint findings with `./gradlew ktlintFormat`.

- [ ] **Step 5: Manual waiter verification (live bot) — REQUIRED before merge**

The manual-zone waiter runs from a callback context for the first time (see the plan-quality
warning). On a live bot verify:

1. `zman` → valid zone (e.g. `Europe/Berlin`) → saved message + main screen re-rendered.
2. `zman` → invalid input → error message, flow exits (one-shot).
3. `zman` → `/cancel` → cancelled message.
4. `zman` → no input for 120 s → timeout message.
5. `zman`, then immediately click another `nfs:` button — the button spinner must not hang for
   120 s (ktgbotapi callback-concurrency semantics are unverified for waiters).
6. Double `zman` click → no duplicated saves/messages.

Document the waiter limitations while here (Task 10 covers the rules file): the waiter does not
survive a bot restart; one active waiter per chat.

If any check fails — STOP and discuss (do not improvise around the waiter).

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(telegram): wire schedule flow into bot"
```

---

### Task 10: Documentation + full build

**Files:**
- Modify: `.claude/rules/telegram-notifications.md`
- Modify: `.claude/rules/database.md`

**Interfaces:** none (docs + verification only).

- [ ] **Step 1: Update the module rule doc**

In `.claude/rules/telegram-notifications.md`:

1. Components table — add rows:

```markdown
| `NotificationsViewStateFactory` | `bot/handler/notifications/` | Single assembly point for `NotificationsViewState` (command, re-render, schedule flow) |
| `ScheduleCallbackHandler` | `bot/handler/notifications/` | Pure dispatch for `nfs:g:sched:*`, mutates schedule settings |
| `ScheduleKeyboardRenderer` | `bot/handler/notifications/` | Hour-picker and timezone screens of the schedule sub-dialog |
| `ScheduleSettingsFlow` | `bot/handler/notifications/` | Telegram I/O: maps dispatch outcomes to screen edits, manual-zone waiter |
```

2. Callback Protocol table — add rows:

```markdown
| `nfs:g:sched:on` / `nfs:g:sched:off` | Enable / disable the detection schedule (OWNER only; `on` without a configured window opens the picker) |
| `nfs:g:sched:cfg` | Open the start-hour picker |
| `nfs:g:sched:s:<H>` | Start hour chosen → end-hour picker (start rides in callback data) |
| `nfs:g:sched:e:<S>:<E>` | End hour chosen → save window `[S:00, E:00)`, materialize zone if unset, auto-enable |
| `nfs:g:sched:zone` | Open the timezone screen (presets as in `/timezone` + manual input) |
| `nfs:g:sched:z:<olson>` | Set schedule zone from preset |
| `nfs:g:sched:zman` | Manual zone input via waiter (120 s timeout, `/cancel`) |
| `nfs:g:sched:home` | Back to the main screen |
```

3. State Storage table — add rows:

```markdown
| Schedule enabled | `app_settings` → `notifications.recording.schedule.enabled` | absent = `FALSE` |
| Schedule window | `app_settings` → `notifications.recording.schedule.window` (`HH:mm-HH:mm`, `[start,end)`, start>end crosses midnight) | absent |
| Schedule zone | `app_settings` → `notifications.recording.schedule.zone` (IANA id) | absent |
```

4. Consumers section — append:

```markdown
- **Detection schedule** — `NotificationDecisionServiceImpl` additionally suppresses recording
  notifications with reason `OUT_OF_SCHEDULE` when the schedule is enabled and
  `recording.recordTimestamp` falls outside the window. The timestamp is event time (a morning
  backlog run still delivers night events) evaluated in the schedule's own zone — the zone the
  window is interpreted in, independent of the camera and of the owner's personal `/timezone`.
  The gate applies before per-user fan-out (suppresses for ALL users; only the OWNER sees it)
  and only matters while the global recording flag is on. Schedule reads are fail-open:
  corrupt/unreadable settings degrade to "no schedule" with a warn log — deliberately
  asymmetric with the global flag, whose read failures keep the recording retryable; a schedule
  read failure produces extra notifications, never lost ones. Signal-loss alerts ignore the
  schedule.
```

5. Operational notes — append:

```markdown
- **Schedule ops:** `app_settings` values and key absence are cached per-process without TTL —
  direct SQL edits or inserts of `notifications.recording.schedule.*` are NOT picked up until restart (single-instance
  deployment assumed). The manual-zone waiter does not survive a bot restart; one active waiter
  per chat. Rollback: disable via the `/notifications` toggle (instant), or
  `DELETE FROM app_settings WHERE setting_key LIKE 'notifications.recording.schedule.%'` +
  restart for a full reset.
```

6. In `.claude/rules/database.md`, `app_settings` section — after the seeding sentence add:

```markdown
Schedule keys `notifications.recording.schedule.{enabled,window,zone}` are NOT seeded — they are
created on first configuration via `/notifications`; absent keys mean "schedule disabled".
```

`git add .claude/rules/telegram-notifications.md .claude/rules/database.md`

- [ ] **Step 2: Branch-wide code review**

Dispatch the `superpowers:code-reviewer` agent over the full feature-branch diff; fix critical
findings and repeat until clean (project CLAUDE.md: review BEFORE build).

- [ ] **Step 3: Full build**

Dispatch the `build-runner` agent: `./gradlew build`
Expected: BUILD SUCCESSFUL (all modules, all tests, ktlint). On ktlint errors run `./gradlew ktlintFormat` and retry.

- [ ] **Step 4: Commit**

```bash
git commit -m "docs: schedule settings in telegram-notifications rule"
```

---

## Self-Review (performed at plan time)

- **Spec coverage:** storage keys → Task 3; `AppSettingsService` strings → Task 2; window/zone types + `[start,end)` + midnight + DST → Task 1; `OUT_OF_SCHEDULE` + branch order + tracker-error + fail-open never-throws → Task 4; ViewState/factory fields → Task 5; status line + buttons → Task 6; pickers/zone screens + stateless callback data + equal-end warning → Tasks 7–8; auto-enable on save, zone materialization, `on`-without-window → Task 8; OWNER-only → Task 8; manual zone waiter (120 s, `/cancel`) → Task 9; bot routing → Task 9; docs → Task 10. No DB migration by design.
- **Known intentional deviation:** none after the iter-1 review sync — the spec now records the two-row keyboard (owner keyboard 5 → 7 rows) and the bot-level interception of `nfs:g:sched:*` (formerly implied handler-level dispatch), in addition to the pre-planning amendments (inline warning instead of toast; `on` without window opens picker; never-throws schedule reads).
- **Type consistency:** `ScheduleWindow.ofHours/parse/storageFormat/displayFormat/contains`, `NotificationSchedule.contains(Instant)`, `NotificationScheduleService` method set, `Outcome.RenderEndPicker(startHour, rejectedEqualEnd)` ↔ `endPicker(startHour, showEqualWarning, lang)`, `PREFIX = "nfs:g:sched:"` — verified consistent across Tasks 1/3/7/8/9.
- **Single placeholder-by-reference:** Task 4 asks the implementer to copy the literal `DetectionDelta(...)` argument list from the existing NEW_OBJECTS test in the same file (constructor not visible at plan time; copying the in-file literal is safer than inventing one).
