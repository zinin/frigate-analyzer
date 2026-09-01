# Collapse detection notifications into a single Rich Message

**Status:** implemented in `feature/rich-message-notification`; kept as a record of the API
research and the live verification.
**Upstream:** `dev.inmo:tgbotapi` **36.0.0** (2026-08-21 10:20 UTC) covers Bot API
10.2; **36.1.0** (2026-08-21 19:35 UTC) is dependency bumps only and is the
latest on Maven Central. Pinned to `36.1.0` since this branch.
**Previous research:** 2026-07-26, when this was blocked on upstream.

## Goal

One detection event should produce **one** Telegram message. Today a single
recording produces two, because of Bot API limits we no longer have to live with:
the 1024-char caption cap and the inability to put long formatted text next to
photos.

## Current behaviour (what we are replacing)

`TelegramNotificationSender.sendRecording()` — `modules/telegram/src/main/kotlin/ru/zinin/frigate/analyzer/telegram/queue/TelegramNotificationSender.kt`

| Frames | Messages sent | Detail |
|---|---|---|
| 0 | 1 | `sendTextMessage(baseText, exportKeyboard)`; the AI describe job is cancelled — no editable target exists |
| 1 | **2** | `SendPhoto(caption = baseText + short placeholder, exportKeyboard)`, then `sendTextMessage(detailed placeholder in expandable blockquote)` as a reply to the photo |
| 2..N | **2+** | `sendMediaGroup` chunked by 10 (caption on the very first photo only), then `sendTextMessage(export prompt + short placeholder + detailed placeholder, exportKeyboard)` replying to the first album |

Once the AI description resolves, `DescriptionEditJobRunner` rewrites the
placeholders: `editMessageCaption` + `editMessageText` for the single-photo case,
`editMessageText` only for the album case.

Constants forcing the split: `MAX_CAPTION_LENGTH = 1024`,
`MAX_MEDIA_GROUP_SIZE = 10`, `MAX_EDIT_TEXT_LENGTH = 4096`
(see also `DescriptionMessageFormatter`).

## Verification, 2026-08-31

Test bot, **non-Premium** recipient (`is_premium: false`), desktop client;
messages 708–715 in that chat. Every row below was executed, not inferred.

| Question | Result |
|---|---|
| Does `InputRichMessageMedia` accept a **fresh upload**, not just `file_id`/URL? | **Yes.** `sendRichMessage` as multipart with `attach://` → `ok: true`. Works over raw HTTP and through ktgbotapi 36.1.0 |
| Do rich messages reach **non-Premium** recipients? | **Yes** — delivered and rendered on a non-Premium account |
| Inline keyboard on a rich message | **Yes**, `reply_markup` is accepted and echoed back |
| `editMessageText(rich_message = …)` on an already-sent rich message | **Yes**, formatting survives the edit |
| Can ktgbotapi 36.1.0 parse the response? | **Yes** for 10.2 content: `SectionHeading, Table, Paragraph, Photo×3, Details, Footer` |

Documented limits (unchanged): 32768 UTF-8 characters, ≤ 500 blocks, ≤ 16 nesting
levels, ≤ 50 media, ≤ 20 table columns.

### Not verified

| Question | Why it matters |
|---|---|
| Does `editMessageReplyMarkup` (markup-only, no text) work on a rich message? | The export keyboard now lives on the rich notification, and five call sites edit only its markup — `QuickExportHandler` (start, two progress updates, `restoreButton`) and `CancelExportHandler`. All five swallow a failure at `warn`, so if Bot API 10.2 rejects a markup-only edit here, the whole Quick Export UX degrades silently: no progress, no Cancel, no restore. One tap on a live notification settles it. |
| Does a `file_id` obtained in one chat work in another? | The design's only unverified assumption. Fallback if wrong: the next recipient re-uploads the bytes. |
| Ten frames in one `<tg-collage>` | Only ever rendered live with three. |

## Constraints found during verification

1. **Media must be re-declared on every edit.** Leaving `<img src="tg://photo?id=frame1"/>`
   in the HTML while omitting the `media` array fails with
   `400 Bad Request: RICH_MESSAGE_PHOTO_INVALID`. Passing
   `media: [{id: "frame1", media: {type: "photo", media: "<file_id>"}}]` — with the
   `file_id`s taken from the send response — keeps the photos in place.
   **Consequence:** `EditTarget` must carry the frame `file_id`s, not just a message id.
2. **Do not use Bot API 10.3 inline buttons (`<tg-button>`).** The server accepts them
   and the message is delivered, but ktgbotapi 36.1.0 throws while deserializing the
   response (`RawMessage$$serializer` does not know the `button` rich-text entity).
   The bot then sees a *failed* send for a message that actually arrived — a retry
   would duplicate the notification. Keep the keyboard in `reply_markup` until
   ktgbotapi ships 10.3.
3. **Uploading a new file during an edit is allowed by the protocol** (the doc's
   restriction applies only to inline messages) — verified over raw HTTP — but
   ktgbotapi's `EditChatMessageText` is not a `MultipartRequest`, so the library
   cannot do it. We do not need it: re-referencing by `file_id` is cheaper.
4. `EditChatMessageRichText` is typed as returning `TextContent` while at runtime it
   returns `RichMessageContent`; reading the result needs a cast.

## Presentation decisions (taken from the live render)

> **These are the research recommendations, and the design revised two of them.** The shipped
> renderer uses `<tg-collage>` for every count from two frames up — the slideshow was rejected
> deliberately ("it hides nine frames out of ten"). The live render below was done with **three**
> frames on desktop; behaviour at the full ten is still unverified. Read this section as the
> record of what the research suggested, not as a description of current behaviour.

- **Frames: `<tg-collage>` for ≤ 4, `<tg-slideshow>` above that.** Plain stacked
  `<img>` renders each frame full-width — three of them already make a wall of
  photos, and `LOCAL_VIZ_MAX_FRAMES` is 10. The collage reproduces the familiar
  Telegram album layout (one large + two per row); the slideshow collapses to a
  single swipeable frame with dots.
- **Detailed AI description in `<details>`,** not `<blockquote expandable>`. Collapsed,
  `<details>` costs one line; the expandable blockquote shows three faded lines.
- **Keyboard via `reply_markup`,** not buttons embedded in the HTML — see constraint 2,
  and inline chips read as small and easy to miss next to a real keyboard button.
- **The metadata table renders well** and is more compact than today's eight
  `key: value` lines.

## Sketch of the target message

A single rich message per recording, carrying what today needs two:

- heading + a metadata `<table>` (camera / file / detections / frames / processing
  time / timestamps — currently built by
  `TelegramNotificationServiceImpl.formatRecordingMessage`, i18n keys `notification.recording.*`),
- all visualized frames as a collage or slideshow (≤ 10, well under the 50-media cap),
- the AI short description,
- the AI detailed description inside `<details>`,
- the Quick Export inline keyboard from `QuickExportHandler.createExportKeyboard`.

Placeholders (`ai.description.placeholder.short` / `.detailed`) still go out with
the initial send and get replaced by one `editMessageText(richMessage = …)` that
re-declares the frames by `file_id` — so `DescriptionEditJobRunner`'s two-target
`EditTarget` collapses to a single message id plus the frame ids, and the
`isMediaGroup` branch disappears entirely.

Total text stays far below the 32768 cap (metadata is ~8 short lines and
`APP_AI_DESCRIPTION_DETAILED_MAX` is capped at 3500), so the existing
truncation logic in `DescriptionMessageFormatter` can be simplified rather than
carried over.

## Newly unlocked optimisation

Frame bytes are currently re-uploaded once **per subscriber**. Since rich-message
media can be referenced by `file_id`, the first recipient can carry the upload and
every further recipient reuse the returned ids. This removes the reason for the
storage-channel workaround that earlier research kept as plan B.

## Upgrade cost

- `ktgbotapi` `35.1.0` → `36.1.0`, and the lockstep pin `ktor` `3.5.1` → `3.5.2`
  in `gradle/libs.versions.toml`; `coroutines` stays at `1.11.0`, the library is
  still built against Kotlin `2.3.21`.
- 36.0.0 breaking changes reviewed against our call sites: none apply. We do not
  construct `Common*ContentMessageImpl` positionally; `ReplyParameters(message.metaInfo)`
  in `StatusCommandHandler.kt` still resolves (`metaInfo` remains a `Triple` and the
  matching factory survived the move to a sealed hierarchy); the new ephemeral
  behaviour of `reply(to = …)` cannot trigger, our targets have no `ephemeralMessageId`.

## Still open

- Rendering on **mobile**, in particular whether the metadata table stays readable on
  a narrow screen. Only the desktop client was checked.
- Behaviour with a **full 10 frames** (the test used three).
- Whether one large message behaves better than today's two under flood control.

## Code that will be touched

| File | Change |
|---|---|
| `telegram/queue/TelegramNotificationSender.kt` | Three send branches collapse into one rich-message send; frames wrapped as `InputRichMessageMedia` + `TelegramMediaPhoto(bytes.asMultipartFile(...))` |
| `telegram/queue/DescriptionEditJobRunner.kt` | `EditTarget` loses `captionMessageId` / `isMediaGroup`, gains the frame `file_id`s; one edit instead of two |
| `telegram/service/impl/DescriptionMessageFormatter.kt` | **Deleted.** Replaced by `RichNotificationRenderer`; the 1024/4096 budget arithmetic went with it |
| `.claude/rules/telegram.md`, `.claude/rules/ai-description.md` | Document the new single-message flow |

## Sources

- [Bot API reference](https://core.telegram.org/bots/api) — `sendRichMessage`, `InputRichMessage`, `InputRichMessageMedia`, Rich HTML style, Rich Message Limits
- [Bot API changelog](https://core.telegram.org/bots/api-changelog) — 10.2 (2026-07-14), 10.3 (2026-08-24, not yet in ktgbotapi)
- [ktgbotapi CHANGELOG](https://github.com/InsanusMokrassar/ktgbotapi/blob/master/CHANGELOG.md) — 36.0.0 entry
- [Telegram blog — Rich Text Editor](https://telegram.org/blog/communities-editor-invisible-messages)
