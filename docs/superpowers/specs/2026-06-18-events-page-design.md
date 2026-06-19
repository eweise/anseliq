# Events Page Design

**Date:** 2026-06-18
**Branch:** convert-to-laminar

## Overview

Add a Laminar `EventsPage` to the frontend that fetches and displays events from the `GET /api/v1/events?eventType=` endpoint built earlier on this branch. The page follows the established `JobsPage` pattern exactly.

## Behavior

- On mount, fetches events for the default type (`MoneyMovementsIngested`).
- A dropdown lets the user switch event type; switching re-fetches and clears the current list.
- The table shows common columns for all types plus one event-type-specific ID column.

## Columns

| Column | Source | All types? |
|--------|--------|------------|
| Status | `status` | yes |
| Retries | `retryCount` | yes |
| Error | `errorMessage` | yes |
| Created At | `createdAt` | yes |
| Movement Spec ID | `movementSpecID` | `MoneyMovementsIngested` only |
| Account ID | `accountID` | `AccountReconciled` only |
| File Inventory ID | `fileInventoryID` | `NewFileDetected` only |

## State

```scala
case class State(
  selectedEventType: String             = "MoneyMovementsIngested",
  events:            Seq[EventPayload]  = Seq.empty
)
```

## Data Flow

1. `fetchBus: EventBus[String]` emits the selected event type string.
2. `fetchBus.events.flatMapSwitch` calls `ApiClient.get[Seq[EventPayload]]` with `?eventType=<type>`.
3. On success, `state.update(_.copy(events = events))`. On failure, `AppState.globalError.set(Some(e))`.
4. Dropdown `onChange` updates `state.selectedEventType`, clears `state.events`, emits to `fetchBus`.

## API Call

```scala
ApiClient.get[Seq[EventPayload]](
  HttpPath.url(s"${HttpPath.events}?eventType=$eventType"),
  SessionStore.token
)
```

`EventPayload` is a sealed trait with `derives ReadWriter` in common — upickle deserializes via the `$type` discriminator automatically.

## Event-Specific Column (extraCells)

Pattern-match on the `EventPayload` union to extract the subtype-specific ID field:

```scala
private def extraCells(event: EventPayload): Seq[HtmlElement] = event match
  case e: MoneyMovementsIngestedPayload => Seq(span(e.movementSpecID))
  case e: AccountReconciledPayload      => Seq(span(e.accountID))
  case e: NewFileDetectedPayload        => Seq(span(e.fileInventoryID))
```

## Files Changed

| File | Change |
|------|--------|
| `frontend/src/main/scala/perfectpair/view/pages/EventsPage.scala` | New file — full page implementation |
| `frontend/src/main/scala/perfectpair/routing/Routes.scala` | Add `path("events")(EventsPage())` |
| `frontend/src/main/scala/perfectpair/view/layout/LeftNav.scala` | Add nav item "Events" after "Jobs" |

No backend changes — the endpoint already exists.

## Out of Scope

- Pagination or infinite scroll
- Filtering by status
- Clicking a row for event detail
