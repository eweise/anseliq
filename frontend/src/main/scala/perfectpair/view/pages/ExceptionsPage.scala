package perfectpair.view.pages

import com.raquo.laminar.api.L.*
import perfectpair.common.http.HttpPath
import perfectpair.common.model.{DisputePayload, DisputeStatusPayload, DisputeTargetPayload, UpdateDisputeStatusRequest}
import perfectpair.http.ApiClient
import perfectpair.state.{AppState, SessionStore}
import perfectpair.view.components.{Buttons, PageHeader, Pill, Table}
import upickle.default.*

object ExceptionsPage:

  case class State(
    disputes:          Seq[DisputePayload] = Seq.empty,
    editingResolution: Option[String]      = None // dispute id currently showing the reason field
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
            Seq("Status", "Category", "Type", "Account", "Reference", "Detail", "Actions"),
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

  private def targetLabel(targetType: DisputeTargetPayload): String = targetType match
    case DisputeTargetPayload.Movement       => "Movement"
    case DisputeTargetPayload.Reconciliation => "Reconciliation"

  private def renderRow(
    d:               DisputePayload,
    s:               State,
    state:           Var[State],
    updateStatusBus: EventBus[(String, DisputeStatusPayload, Option[String])]
  ): Seq[HtmlElement] =
    Seq(
      Pill(d.status.toString, statusTone(d.status)),
      Pill(d.category.toString, Pill.Tone.Accent),
      Pill(targetLabel(d.targetType), Pill.Tone.Neutral),
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
         resolutionInput(d, updateStatusBus)
       else
         Buttons.textButton("Resolve", Observer[Unit](_ => state.update(_.copy(editingResolution = Some(d.id))))))
    )

  // A local Var, not the page-level State: writing every keystroke into the outer State would trigger the
  // `child <-- state.signal.map(...)` at the top of this page to rebuild the whole table on each keystroke,
  // destroying and recreating this very input element and dropping whatever had been typed so far. Scoping
  // the note to a Var created only when this element is built keeps typing from touching outer State at all.
  private def resolutionInput(
    d:               DisputePayload,
    updateStatusBus: EventBus[(String, DisputeStatusPayload, Option[String])]
  ): HtmlElement =
    val noteVar = Var("")
    div(
      cls := "flex gap-1",
      input(
        typ         := "text",
        placeholder := "resolution note",
        onInput.mapToValue --> Observer[String](v => noteVar.set(v))
      ),
      Buttons.textButton(
        "Confirm",
        Observer[Unit](_ => updateStatusBus.emit((d.id, DisputeStatusPayload.Resolved, Some(noteVar.now()))))
      )
    )
