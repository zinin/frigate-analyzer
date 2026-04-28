# Telegram Outbox Follow-up

## Problem

The notification tracker currently mutates object-tracking state before Telegram notification enqueue/fan-out is guaranteed to succeed.

Example flow:

1. A recording contains a new object.
2. `NotificationDecisionService` calls `ObjectTrackerService`.
3. `ObjectTrackerService` creates or updates rows in `object_tracks`.
4. The decision is `shouldNotify=true`.
5. `RecordingProcessingFacade` calls `TelegramNotificationService.sendRecordingNotification(...)`.
6. Enqueue/fan-out fails after tracker state has already been committed.
7. The next recording segment sees the same object as an existing track and may suppress the notification.

Result: a rare failure between tracker mutation and Telegram enqueue can cause the first notification for a new object to be lost, while later repeat segments are suppressed.

For the current notification-controls/object-tracking iteration, this is an accepted at-most-once delivery risk. The feature does not introduce an outbox or compensation mechanism yet.

## Why this matters

This is a delivery-semantics issue, not an IoU/tracking issue. The tracker correctly records the object as seen, but notification delivery is not atomically tied to that state change.

The risk is most visible when all of the following happen:

- the object is new for the camera;
- tracker state commits successfully;
- notification enqueue/fan-out fails;
- the object stays visible in subsequent recording segments;
- duplicate suppression then treats those subsequent segments as repeats.

## Possible solutions

### 1. Transactional outbox

Create a `notification_outbox` table and write the notification intent in the same transaction as the tracker decision. A background dispatcher sends pending rows and marks them delivered.

Pros:
- strongest delivery guarantees;
- retryable and observable;
- can support other channels later.

Cons:
- larger scope;
- requires schema, dispatcher task, retry policy, cleanup, and monitoring;
- may require reshaping current `TelegramNotificationService` APIs.

### 2. Compensation on fan-out failure

Return IDs of newly-created/updated tracks from `ObjectTrackerService`. If `sendRecordingNotification` fails, delete newly-created tracks or restore previous state.

Pros:
- smaller than a full outbox;
- preserves current synchronous flow.

Cons:
- harder to make correct for matched-track updates;
- rollback can race with later recordings;
- requires exposing tracker mutation details through `DetectionDelta`.

### 3. Accepted at-most-once risk

Document the gap and keep the current implementation.

Pros:
- minimal scope;
- no new infrastructure.

Cons:
- a narrow failure window can lose the first notification for a new object.

## Recommended future direction

Prefer a transactional outbox if notification delivery guarantees become important. It is the cleanest long-term boundary: detection/tracking code records notification intent, and a separate dispatcher owns Telegram delivery/retries.

A future task should define:

- outbox schema and retention;
- payload shape for recording notifications and signal notifications;
- retry/backoff policy;
- idempotency key strategy;
- dispatcher scheduling/concurrency;
- observability for pending/failed/delivered notifications;
- migration path from direct enqueue to outbox-backed enqueue.
