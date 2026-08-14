# Events Endpoint Design

**Date:** 2026-06-18
**Branch:** convert-to-laminar

## Overview

Add a `GET /api/v1/events` endpoint that retrieves `EventState` records from the database. Supports an optional `?eventType=` query parameter to filter by event type. Returns typed payloads per event subtype, including all `EventState` metadata fields.

## Endpoint

```
GET /api/v1/events
GET /api/v1/events?eventType=MoneyMovementsIngested
GET /api/v1/events?eventType=AccountReconciled
GET /api/v1/events?eventType=NewFileDetected
```

- No `eventType` param → returns all events
- With `eventType` param → returns only events matching that type
- Response: JSON array of `EventPayload` discriminated by `$type` (uPickle sealed trait encoding)

## Common Package Changes

**File:** `common/src/main/scala/onparity/common/model/Payload.scala`

Add a sealed `EventPayload` trait with one concrete case class per `Event` subtype. Each subtype contains:
- Metadata from `EventState`: `id`, `eventType`, `status` (String), `retryCount`, `errorMessage`, `createdAt`, `updatedAt`
- Event-specific fields from the parsed `Event` domain object

```scala
sealed trait EventPayload derives ReadWriter

case class MoneyMovementsIngestedPayload(
  id:             String,
  eventType:      String,
  movementSpecID: String,
  status:         String,
  retryCount:     Int,
  errorMessage:   Option[String],
  createdAt:      Option[OffsetDateTime],
  updatedAt:      Option[OffsetDateTime]
) extends EventPayload

case class AccountReconciledPayload(
  id:           String,
  eventType:    String,
  accountID:    String,
  status:       String,
  retryCount:   Int,
  errorMessage: Option[String],
  createdAt:    Option[OffsetDateTime],
  updatedAt:    Option[OffsetDateTime]
) extends EventPayload

case class NewFileDetectedPayload(
  id:              String,
  eventType:       String,
  fileInventoryID: String,
  status:          String,
  retryCount:      Int,
  errorMessage:    Option[String],
  createdAt:       Option[OffsetDateTime],
  updatedAt:       Option[OffsetDateTime]
) extends EventPayload
```

`status` is `String` (not an enum) to avoid mirroring the backend `EventStatus` enum into common — consistent with `ReconciliationPayload`.

Add path constant to `HttpPath`:
```scala
val events = s"$prefix/events"
```

## Backend Changes

### `EventRepo` port

**File:** `backend/src/main/scala/onparity/domain/port/driven/EventRepo.scala`

Add:
```scala
def findByEventType(eventType: String): Txn[Vector[EventState]]
```

### `EventRepoLive`

**File:** `backend/src/main/scala/onparity/adapter/driven/EventRepoLive.scala`

Implement:
```scala
override def findByEventType(eventType: String): Txn[Vector[EventState]] =
  sql"SELECT ${e.all} FROM $e WHERE event_type = $eventType".query[EventState].run()
```

### `Payload` object

**File:** `backend/src/main/scala/onparity/adapter/driving/Payload.scala`

Add `eventPayloadFromDomain(state: EventState): EventPayload`. Matches on `state.eventType`, deserializes the stored JSON payload into the typed `Event` subtype via `state.event[T]`, then constructs the appropriate payload case class:

```scala
def eventPayloadFromDomain(state: EventState): EventPayload =
  state.eventType match
    case "MoneyMovementsIngested" =>
      val e = state.event[MoneyMovementsIngested]
      MoneyMovementsIngestedPayload(state.id.toString, state.eventType, e.movementSpecID.toString,
        state.status.toString, state.retryCount, state.errorMessage, state.createdAt, state.updatedAt)
    case "AccountReconciled" =>
      val e = state.event[AccountReconciled]
      AccountReconciledPayload(state.id.toString, state.eventType, e.accountID.toString,
        state.status.toString, state.retryCount, state.errorMessage, state.createdAt, state.updatedAt)
    case "NewFileDetected" =>
      val e = state.event[NewFileDetected]
      NewFileDetectedPayload(state.id.toString, state.eventType, e.fileInventoryID.toString,
        state.status.toString, state.retryCount, state.errorMessage, state.createdAt, state.updatedAt)
    case other => throw IllegalArgumentException(s"Unknown event type: $other")
```

### Route

**File:** `backend/src/main/scala/onparity/adapter/driving/Routes.scala`

Add `eventRepo: EventRepo` to `App`'s `using` parameters. Add endpoint:

```scala
@cask.route(s"${HttpPath.events}", methods = HttpCmd.GET)
def getEvents(request: cask.Request): String =
  val eventTypeFilter = request.queryParams.get("eventType").flatMap(_.headOption)
  transact(transactor):
    val states = eventTypeFilter match
      case Some(et) => eventRepo.findByEventType(et)
      case None     => eventRepo.findAll
    write(states.map(Payload.eventPayloadFromDomain))
```

`eventRepo` is already wired as a `given` in `Main.scala` (line 35) — no change needed there.

## Error Handling

- Unknown `eventType` string in the DB payload throws `IllegalArgumentException` — propagates as a 500. No special handling needed; this would indicate a data integrity issue.
- Invalid / unrecognised `eventType` query param returns an empty array (the SQL `WHERE` clause simply matches nothing).

## Files Changed

| File | Change |
|------|--------|
| `common/src/main/scala/onparity/common/model/Payload.scala` | Add `EventPayload` sealed trait + 3 subtypes |
| `common/src/main/scala/onparity/common/http/http.scala` | Add `val events` to `HttpPath` |
| `backend/src/main/scala/onparity/domain/port/driven/EventRepo.scala` | Add `findByEventType` |
| `backend/src/main/scala/onparity/adapter/driven/EventRepoLive.scala` | Implement `findByEventType` |
| `backend/src/main/scala/onparity/adapter/driving/Payload.scala` | Add `eventPayloadFromDomain` |
| `backend/src/main/scala/onparity/adapter/driving/Routes.scala` | Add `eventRepo` using param + `getEvents` route |
