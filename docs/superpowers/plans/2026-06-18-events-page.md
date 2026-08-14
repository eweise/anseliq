# Events Page Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Laminar `EventsPage` that fetches and displays events by type, wired into the existing routing and nav.

**Architecture:** The page follows the `JobsPage` pattern exactly — a `Var[State]` + `EventBus[String]` drives `ApiClient.get` calls; the result feeds a `Table`. Common fields (`status`, `retryCount`, `errorMessage`, `createdAt`) are added as abstract vals to the `EventPayload` sealed trait so they're accessible without pattern matching on every row; the event-type-specific ID column is handled by a separate `extraCells` function that pattern-matches the union.

**Tech Stack:** Scala 3, Scala.js, Laminar, upickle, frontroute, Tailwind CSS

---

## File Map

| File | Action |
|------|--------|
| `common/src/main/scala/onparity/common/model/Payload.scala` | Add abstract vals to `EventPayload` sealed trait |
| `frontend/src/main/scala/onparity/view/pages/EventsPage.scala` | New page |
| `frontend/src/main/scala/onparity/routing/Routes.scala` | Add `path("events")(EventsPage())` |
| `frontend/src/main/scala/onparity/view/layout/LeftNav.scala` | Add "Events" nav item after "Jobs" |

---

### Task 1: Add common fields to EventPayload trait

**Files:**
- Modify: `common/src/main/scala/onparity/common/model/Payload.scala`

The three EventPayload subtypes already define `status`, `retryCount`, `errorMessage`, `createdAt`, and `updatedAt` as constructor parameters (which become `val`s). Declaring them as abstract `val`s on the sealed trait lets the page access them without pattern matching on every row. The concrete case classes satisfy these automatically in Scala 3.

- [ ] **Step 1: Add abstract vals to `EventPayload`**

In `common/src/main/scala/onparity/common/model/Payload.scala`, replace:

```scala
sealed trait EventPayload derives ReadWriter
```

with:

```scala
sealed trait EventPayload derives ReadWriter:
  val id:           String
  val eventType:    String
  val status:       String
  val retryCount:   Int
  val errorMessage: Option[String]
  val createdAt:    Option[OffsetDateTime]
  val updatedAt:    Option[OffsetDateTime]
```

The three case classes (`MoneyMovementsIngestedPayload`, `AccountReconciledPayload`, `NewFileDetectedPayload`) already declare all these fields — no changes to them are needed.

- [ ] **Step 2: Verify common compiles**

```bash
sbt "commonJVM/compile"
```

Expected: `[success] Total time: ...`

- [ ] **Step 3: Commit**

```bash
git add common/src/main/scala/onparity/common/model/Payload.scala
git commit -m "feat(common): add abstract vals to EventPayload sealed trait"
```

---

### Task 2: Create EventsPage

**Files:**
- Create: `frontend/src/main/scala/onparity/view/pages/EventsPage.scala`

The page follows `JobsPage` exactly:
- `State` holds `selectedEventType: String` (default `"MoneyMovementsIngested"`) and `events: Seq[EventPayload]`.
- `fetchBus: EventBus[String]` triggers `ApiClient.get` on emit.
- `onMountCallback` emits the default event type to fetch on load.
- Dropdown calls `Form.selectField` with the three event type strings.
- Table renders common columns from the trait's abstract vals, plus a type-specific ID column from `extraCells`.

- [ ] **Step 1: Create `EventsPage.scala`**

Create `frontend/src/main/scala/onparity/view/pages/EventsPage.scala`:

```scala
package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.HttpPath
import onparity.common.model.*
import onparity.http.ApiClient
import onparity.state.{AppState, SessionStore}
import onparity.view.ViewHelper
import onparity.view.components.{Form, Table}
import upickle.default.*

object EventsPage:

  def apply(): HtmlElement =
    val state    = Var(State())
    val fetchBus = EventBus[String]()

    div(
      onMountCallback(_ => fetchBus.emit(state.now().selectedEventType)),
      fetchBus.events.flatMapSwitch { eventType =>
        ApiClient.get[Seq[EventPayload]](
          HttpPath.url(s"${HttpPath.events}?eventType=$eventType"),
          SessionStore.token
        )
      } --> {
        case Right(events) => state.update(_.copy(events = events))
        case Left(e)       => AppState.globalError.set(Some(e))
      },
      cls := "flex flex-col items-center mb-8 pt-20 px-8 w-full",
      div(
        cls := "w-full max-w-5xl",
        h2(cls := "text-2xl font-medium mb-6", "Events"),
        div(
          cls := "mb-6 max-w-xs",
          Form.selectField(
            "event-type-select",
            "Event Type",
            eventTypeOptions,
            state.now().selectedEventType,
            v =>
              state.update(s => s.copy(selectedEventType = v, events = Seq.empty))
              fetchBus.emit(v)
          )
        ),
        child <-- state.signal.map { s =>
          val extraHeader = s.selectedEventType match
            case "MoneyMovementsIngested" => "Movement Spec ID"
            case "AccountReconciled"      => "Account ID"
            case "NewFileDetected"        => "File Inventory ID"
            case _                        => "ID"
          val headers = Seq("Status", "Retries", "Error", "Created At", extraHeader)
          val rows = s.events.map { event =>
            Seq(
              span(event.status),
              span(event.retryCount.toString),
              span(event.errorMessage.getOrElse("-")),
              span(event.createdAt.map(ViewHelper.formatDateTime).getOrElse("-"))
            ) ++ extraCells(event)
          }
          Table.create(headers, rows)
        }
      )
    )

  private val eventTypeOptions: Seq[(String, String)] = Seq(
    "MoneyMovementsIngested" -> "MoneyMovementsIngested",
    "AccountReconciled"      -> "AccountReconciled",
    "NewFileDetected"        -> "NewFileDetected"
  )

  private def extraCells(event: EventPayload): Seq[HtmlElement] = event match
    case e: MoneyMovementsIngestedPayload => Seq(span(e.movementSpecID))
    case e: AccountReconciledPayload      => Seq(span(e.accountID))
    case e: NewFileDetectedPayload        => Seq(span(e.fileInventoryID))

  case class State(
    selectedEventType: String            = "MoneyMovementsIngested",
    events:            Seq[EventPayload] = Seq.empty
  )
```

- [ ] **Step 2: Verify frontend compiles**

```bash
sbt "frontend/compile"
```

Expected: `[success] Total time: ...`

- [ ] **Step 3: Commit**

```bash
git add frontend/src/main/scala/onparity/view/pages/EventsPage.scala
git commit -m "feat(frontend): add EventsPage"
```

---

### Task 3: Wire routing and nav

**Files:**
- Modify: `frontend/src/main/scala/onparity/routing/Routes.scala`
- Modify: `frontend/src/main/scala/onparity/view/layout/LeftNav.scala`

- [ ] **Step 1: Add route**

In `frontend/src/main/scala/onparity/routing/Routes.scala`, add `path("events")(EventsPage())` after the jobs route:

```scala
          path("jobs")(JobsPage()),
          path("events")(EventsPage()),
```

- [ ] **Step 2: Add nav item**

In `frontend/src/main/scala/onparity/view/layout/LeftNav.scala`, add a nav item for Events after the Jobs item:

```scala
          navItem("Jobs", "/components/jobs", Icons.document),
          navItem("Events", "/components/events", Icons.document),
```

- [ ] **Step 3: Verify frontend compiles**

```bash
sbt "frontend/compile"
```

Expected: `[success] Total time: ...`

- [ ] **Step 4: Commit**

```bash
git add frontend/src/main/scala/onparity/routing/Routes.scala \
        frontend/src/main/scala/onparity/view/layout/LeftNav.scala
git commit -m "feat(frontend): wire EventsPage into routing and nav"
```
