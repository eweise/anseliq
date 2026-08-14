# Exceptions Page — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the `ComingSoonPage("Exceptions & Disputes")` placeholder with a real page listing every
`Dispute` and driving its status workflow (Investigate/Escalate/Resolve/Reopen).

**Architecture:** One new page (`ExceptionsPage.scala`), following `ReconciliationsPage.scala`'s exact
structure — `ApiClient.get`/`ApiClient.put` against the `Dispute` endpoints added in the backend plan, a
`Table.create` render, and the same reveal-a-field-then-confirm pattern `ReconciliationsPage` already uses
for its Write Off action, reused here for Resolve's required note.

**Tech Stack:** Scala 3, Laminar, the existing `Pill`/`Table`/`PageHeader`/`Buttons` components, `frontroute`.

## Global Constraints

- Design doc: `docs/superpowers/specs/2026-08-13-exceptions-page-design.md` — every field/signature below is
  copied from it verbatim.
- No "raise a dispute" UI anywhere in this pass. No filtering/sorting on the list.
- No new shared DTOs — `DisputePayload`/`DisputeStatusPayload`/`UpdateDisputeStatusRequest` already exist in
  `common/model/Payload.scala` from the Disputes backend plan.
- Verify with `sbt frontend/compile` (must succeed) and a live browser check via `sbt frontend/devServer` +
  the backend running (`sbt backend/run`) — this page's correctness can't be confirmed by compilation alone.

---

### Task 1: `ExceptionsPage` + routing

**Files:**
- Create: `frontend/src/main/scala/onparity/view/pages/ExceptionsPage.scala`
- Modify: `frontend/src/main/scala/onparity/routing/Routes.scala`
- Modify: `frontend/src/main/scala/onparity/view/layout/LeftNav.scala`

**Interfaces:**
- Consumes: `DisputePayload`, `DisputeStatusPayload`, `UpdateDisputeStatusRequest` (from
  `onparity.common.model`), `HttpPath.disputes`/`HttpPath.dispute` (from `onparity.common.http`),
  `ApiClient.get`/`ApiClient.put`, `Pill`, `Table.create`, `PageHeader.title`, `Buttons.textButton` (all
  existing).
- Produces: `ExceptionsPage.apply(): HtmlElement`, reachable at `/components/exceptions`.

- [ ] **Step 1: Create `ExceptionsPage.scala`**

```scala
package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.HttpPath
import onparity.common.model.{DisputePayload, DisputeStatusPayload, UpdateDisputeStatusRequest}
import onparity.http.ApiClient
import onparity.state.{AppState, SessionStore}
import onparity.view.components.{Buttons, PageHeader, Pill, Table}
import upickle.default.*

object ExceptionsPage:

  case class State(
    disputes:           Seq[DisputePayload] = Seq.empty,
    resolutionReasonBy: Map[String, String] = Map.empty, // dispute id -> in-progress reason text
    editingResolution:  Option[String]      = None        // dispute id currently showing the reason field
  )

  def apply(): HtmlElement =
    val state           = Var(State())
    val loadBus         = EventBus[Unit]()
    val updateStatusBus = EventBus[(String, DisputeStatusPayload, Option[String])]()

    def load() =
      ApiClient.get[Seq[DisputePayload]](HttpPath.url(HttpPath.disputes), SessionStore.token)

    div(
      loadBus.events.flatMapSwitch(_ => load()) --> {
        case Right(ds) => state.update(_.copy(disputes = ds))
        case Left(e)   => AppState.globalError.set(Some(e))
      },
      updateStatusBus.events.flatMapSwitch { (id, status, note) =>
        ApiClient.put[DisputePayload, UpdateDisputeStatusRequest](
          HttpPath.url(s"${HttpPath.dispute}/$id/status"),
          UpdateDisputeStatusRequest(status, note),
          SessionStore.token
        )
      } --> {
        case Right(_) =>
          state.update(_.copy(editingResolution = None))
          loadBus.emit(())
        case Left(e) => AppState.globalError.set(Some(e))
      },
      onMountCallback { _ => loadBus.emit(()) },
      cls := "flex flex-col items-center mb-8 pt-20",
      div(
        cls := "my-8 text-center w-full max-w-5xl",
        PageHeader.title("Exceptions & Disputes"),
        child <-- state.signal.map { s =>
          Table.create(
            Seq("Status", "Category", "Account", "Reference", "Detail", "Actions"),
            s.disputes.map(d => renderRow(d, s, state, updateStatusBus))
          )
        }
      )
    )

  private def statusTone(status: DisputeStatusPayload): Pill.Tone = status match
    case DisputeStatusPayload.New           => Pill.Tone.Accent
    case DisputeStatusPayload.Investigating => Pill.Tone.Warning
    case DisputeStatusPayload.Escalated     => Pill.Tone.Critical
    case DisputeStatusPayload.Resolved      => Pill.Tone.Good

  private def renderRow(
    d:               DisputePayload,
    s:               State,
    state:           Var[State],
    updateStatusBus: EventBus[(String, DisputeStatusPayload, Option[String])]
  ): Seq[HtmlElement] =
    Seq(
      Pill(d.status.toString, statusTone(d.status)),
      Pill(d.category.toString, Pill.Tone.Accent),
      span(d.accountName),
      span(d.referenceLabel),
      span(d.detail.getOrElse("")),
      actionsCell(d, s, state, updateStatusBus)
    )

  private def actionsCell(
    d:               DisputePayload,
    s:               State,
    state:           Var[State],
    updateStatusBus: EventBus[(String, DisputeStatusPayload, Option[String])]
  ): HtmlElement =
    div(
      cls := "flex flex-col gap-1",
      (if d.status != DisputeStatusPayload.Investigating then
         Buttons.textButton(
           "Investigate",
           Observer[Unit](_ => updateStatusBus.emit((d.id, DisputeStatusPayload.Investigating, None)))
         )
       else span(): HtmlElement),
      (if d.status != DisputeStatusPayload.Escalated then
         Buttons.textButton(
           "Escalate",
           Observer[Unit](_ => updateStatusBus.emit((d.id, DisputeStatusPayload.Escalated, None)))
         )
       else span(): HtmlElement),
      (if d.status == DisputeStatusPayload.Resolved then
         Buttons.textButton(
           "Reopen",
           Observer[Unit](_ => updateStatusBus.emit((d.id, DisputeStatusPayload.Investigating, None)))
         )
       else if s.editingResolution.contains(d.id) then
         div(
           cls := "flex gap-1",
           input(
             typ         := "text",
             placeholder := "resolution note",
             onInput.mapToValue --> Observer[String](v =>
               state.update(st => st.copy(resolutionReasonBy = st.resolutionReasonBy + (d.id -> v)))
             )
           ),
           Buttons.textButton(
             "Confirm",
             Observer[Unit](_ =>
               updateStatusBus.emit((d.id, DisputeStatusPayload.Resolved, s.resolutionReasonBy.get(d.id)))
             )
           )
         )
       else
         Buttons.textButton("Resolve", Observer[Unit](_ => state.update(_.copy(editingResolution = Some(d.id))))))
    )
```

- [ ] **Step 2: Wire the route in `Routes.scala`**

Replace this line:

```scala
          path("coming-soon" / "exceptions")(ComingSoonPage("Exceptions & Disputes")),
```

with:

```scala
          path("exceptions")(ExceptionsPage()),
```

(Leave the other four `coming-soon` routes untouched.)

- [ ] **Step 3: Point the nav item at the real route in `LeftNav.scala`**

Replace:

```scala
      NavItem("Exceptions & Disputes", "/components/coming-soon/exceptions", Icons.document),
```

with:

```scala
      NavItem("Exceptions & Disputes", "/components/exceptions", Icons.document),
```

- [ ] **Step 4: Type-check**

Run: `sbt frontend/compile`
Expected: `[success]` with no errors.

- [ ] **Step 5: Visual check**

Start the backend (`sbt backend/run`) and frontend dev server (`sbt frontend/devServer`), wait for both to
respond (`curl -s -o /dev/null http://localhost:8080/` and `http://localhost:9876/`). Log in, click
"Exceptions & Disputes" in the sidebar.

If there's no existing dispute to look at, raise one first via curl (see the backend plan's smoke-test
step for the exact command, or reuse the one already sitting in the dev DB from that plan's own smoke
test — check with `curl -s http://localhost:8080/api/v1/disputes`).

Confirm: the table renders with a colored status pill (Accent for New / Warning for Investigating /
Critical for Escalated / Good for Resolved) and an Accent category pill. Click "Investigate" — the row's
status pill should update to "Investigating" (Warning tone) and the button row should now show
"Escalate"/"Resolve" instead of "Investigate". Click "Resolve" — a text input and "Confirm" button should
appear; click "Confirm" without typing anything first — confirm the global error banner appears (backend
rejects the missing resolution note). Type a note, click "Confirm" again — confirm the status pill updates
to "Resolved" (Good tone) and the action button changes to "Reopen". Click "Reopen" — confirm it goes back
to "Investigating".

Stop both dev servers when done.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/main/scala/onparity/view/pages/ExceptionsPage.scala frontend/src/main/scala/onparity/routing/Routes.scala frontend/src/main/scala/onparity/view/layout/LeftNav.scala
git commit -m "Add ExceptionsPage, replacing the Exceptions & Disputes coming-soon placeholder

Lists every Dispute and drives its status workflow (Investigate/Escalate/
Resolve/Reopen), reusing ReconciliationsPage's reveal-a-field-then-confirm
pattern for Resolve's required note."
```

---

## Self-Review

**Spec coverage:** Page structure, status/category pills, all four action-button states (including the
Resolved -> Reopen case and the Resolve -> note-field-then-Confirm case), routing swap, nav-item href swap.
Every section of the design doc maps to a step in this one task. "Out of Scope" items (raise-from-UI,
filtering, the other four coming-soon pages) are untouched.

**Placeholder scan:** No TBD/TODO. Full page code is written out, not described.

**Type consistency:** `EventBus[(String, DisputeStatusPayload, Option[String])]` has the identical type
everywhere it's declared and used (the `apply` method, `renderRow`, `actionsCell`).
