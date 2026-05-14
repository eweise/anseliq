# EventStore Design

**Date:** 2026-05-14
**Status:** Approved

## Overview

Implement `publishEvent` and `receiveEvent` on the `EventStore` domain service, backed by a new `recon.event` table. Events are durable, exclusively claimed by a single consumer, and support retry-on-failure with a configurable cap.

## Data Model

### Schema (`db/schema.sql`)

```sql
create table recon.event
(
    id            uuid primary key,
    event_type    text                                  not null,
    payload       text                                  not null,
    status        text                                  not null default 'Pending',
    retry_count   int                                   not null default 0,
    error_message text,
    created_at    timestamptz default CURRENT_TIMESTAMP not null,
    updated_at    timestamptz default CURRENT_TIMESTAMP not null
);

CREATE INDEX event_status_created_at ON recon.event (status, created_at);

CREATE TRIGGER update_event
    BEFORE INSERT OR UPDATE ON recon.event
    FOR EACH ROW EXECUTE PROCEDURE update_audit_fields_task();
```

### Scala Model (`domain/model/Event.scala`)

```scala
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
enum EventStatus derives DbCodec:
  case Pending, InProgress, Consumed, Error

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Event(
  @Id id:        ID                     = createID(),
  eventType:     String,
  payload:       String,
  status:        EventStatus            = EventStatus.Pending,
  retryCount:    Int                    = 0,
  errorMessage:  Option[String]         = None,
  var createdAt: Option[OffsetDateTime] = None,
  var updatedAt: Option[OffsetDateTime] = None
) derives DbCodec

object Event:
  val Table = TableInfo[Event, Event, UUID]
```

### RetryableException

```scala
class RetryableException(message: String) extends Exception(message)
```

Defined in `domain/model/Event.scala` alongside the model.

## Architecture

Follows the hexagonal pattern established by `JobRepo` / `JobManager`.

### Files

| Layer | File | Purpose |
|---|---|---|
| Domain model | `domain/model/Event.scala` | `Event`, `EventStatus`, `RetryableException` |
| Domain port | `domain/port/driven/EventRepo.scala` | `EventRepo` trait |
| Adapter | `adapter/driven/EventRepoLive.scala` | Magnum SQL implementation |
| Domain service | `domain/service/EventStore.scala` | `publishEvent`, `receiveEvent` |

### EventRepo Port

```scala
trait EventRepo extends DomainRepo[Event, Event]:
  def getNextPendingEvent(maxRetries: Int): Txn[Option[Event]]
```

### EventRepoLive Adapter

`getNextPendingEvent` selects the oldest `Pending` event whose `retry_count < maxRetries`, locks it exclusively, and updates its status to `InProgress` — all within the caller's transaction:

```sql
SELECT ...
FROM recon.event
WHERE status = 'Pending'
  AND retry_count < $maxRetries
ORDER BY created_at
LIMIT 1
FOR UPDATE SKIP LOCKED
```

Followed immediately by `UPDATE status = 'InProgress'`.

### EventStore Service

Constructor: `class EventStore(using EventRepo, DataSource, ClockService)`

#### publishEvent

```
publishEvent(event: Event): Txn[Unit]
```

Inserts the event with status `Pending`. Runs inside the caller's transaction.

#### receiveEvent

```
receiveEvent(pollingSeconds: Int, maxRetries: Int)(consumer: Event => Unit): Unit
```

Loops on `Schedule.fixedInterval(pollingSeconds.seconds)`. Each tick opens a transaction and:

1. Calls `getNextPendingEvent(maxRetries)` — claims the row (status now `InProgress`)
2. If no event: no-op, wait for next tick
3. Calls `consumer(event)` inside the same transaction
4. **Success** → update status to `Consumed`
5. **`RetryableException` and `retry_count < maxRetries - 1`** → update status to `Pending`, increment `retry_count`
6. **`RetryableException` and `retry_count >= maxRetries - 1`** → update status to `Error`, set `error_message`
7. **Any other exception** → update status to `Error`, set `error_message`

Steps 1–4/5/6/7 run inside a single `transact` block so a crash leaves the row visible as `InProgress` rather than silently lost.

## Testing

### EventRepoLiveSuite (`adapter/driven/EventRepoLiveSuite.scala`)

Uses `transact` + `rollBackOnly` for DB isolation.

- Insert a `Pending` event → `getNextPendingEvent` returns it and sets status to `InProgress`
- A second call to `getNextPendingEvent` (same or concurrent transaction) returns `None` when only one event exists
- An event with status `InProgress` is not returned by `getNextPendingEvent`
- An event with `retry_count >= maxRetries` is not returned

### EventStoreSuite (`domain/service/EventStoreSuite.scala`)

Uses a real DB (no mocking), `transact` + `rollBackOnly`.

- `publishEvent` followed by `getNextPendingEvent` returns the event
- `receiveEvent` tick calls consumer; event status becomes `Consumed`
- Consumer throws `RetryableException`: `retry_count` increments, status remains `Pending`
- Consumer throws `RetryableException` at `maxRetries`: status becomes `Error`
- Consumer throws a non-retryable exception: status becomes `Error`, `error_message` is set
