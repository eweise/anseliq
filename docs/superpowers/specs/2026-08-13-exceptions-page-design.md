# Exceptions Page — Design

**Date:** 2026-08-13

## Goal

Replace the `ComingSoonPage("Exceptions & Disputes")` placeholder with a real page that lists every
`Dispute` (via `GET /api/v1/disputes`, added in the Disputes backend work) and lets a user move a dispute
through its status workflow — Investigate, Escalate, Resolve (with a required note), or Reopen a resolved
one. No way to raise a new dispute from the UI yet — that's explicitly deferred, matching how the backend
spec scoped "raise" as a later addition.

## Page

`frontend/src/main/scala/perfectpair/view/pages/ExceptionsPage.scala`, structured like
`ReconciliationsPage.scala` (top-level page, no back button — `EventsPage`/`JobsPage` are the closer
precedent since those are also flat nav destinations with no parent context):

```scala
package perfectpair.view.pages

import com.raquo.laminar.api.L.*
import perfectpair.common.http.HttpPath
import perfectpair.common.model.{DisputePayload, DisputeStatusPayload, UpdateDisputeStatusRequest}
import perfectpair.http.ApiClient
import perfectpair.state.{AppState, SessionStore}
import perfectpair.view.components.{Buttons, PageHeader, Pill, Table}
import upickle.default.*

object ExceptionsPage:

  case class State(
    disputes:           Seq[DisputePayload]  = Seq.empty,
    resolutionReasonBy:  Map[String, String]  = Map.empty, // dispute id -> in-progress reason text
    editingResolution:   Option[String]       = None        // dispute id currently showing the reason field
  )

  def apply(): HtmlElement =
    val state            = Var(State())
    val loadBus          = EventBus[Unit]()
    val updateStatusBus  = EventBus[(String, DisputeStatusPayload, Option[String])]()

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

## Routing

`Routes.scala` — replace the exceptions coming-soon entry with a real route (the other four coming-soon
placeholders — Recon Rules, Reports & Analytics, Users & Roles, Settings — are untouched):

```scala
path("exceptions")(ExceptionsPage()),
```

removing:

```scala
path("coming-soon" / "exceptions")(ComingSoonPage("Exceptions & Disputes")),
```

`LeftNav.scala` — point the existing "Exceptions & Disputes" nav item at the real route:

```scala
NavItem("Exceptions & Disputes", "/components/exceptions", Icons.document),
```

(was `"/components/coming-soon/exceptions"`)

## Out of Scope

- Raising a new dispute from the UI (no "Flag as Exception" button anywhere yet).
- Filtering/sorting the list — matches the backend's unfiltered `GET /api/v1/disputes`.
- Any change to the other four coming-soon placeholder pages/routes.
