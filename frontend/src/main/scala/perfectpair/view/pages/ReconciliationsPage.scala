package perfectpair.view.pages

import com.raquo.laminar.api.L.*
import perfectpair.common.http.HttpPath
import perfectpair.common.model.{ReconciliationPayload, ReconciliationSpecPayload, WriteOffRequest}
import perfectpair.http.ApiClient
import perfectpair.routing.Nav
import perfectpair.state.{AppState, SessionStore}
import perfectpair.view.ViewHelper
import perfectpair.view.components.{Buttons, PageHeader, Pill, Table}
import upickle.default.*

object ReconciliationsPage:

  case class State(
    recons:          Seq[ReconciliationPayload] = Seq.empty,
    editingWriteOff: Option[String]             = None // reconciliation id currently showing the reason field
  )

  private def statusPill(r: ReconciliationPayload): HtmlElement =
    val (label, tone) = r.status match
      case "InProgress"                                    => "In Progress" -> Pill.Tone.Warning
      case "Final" if r.adjustmentReason.exists(_.nonEmpty) => "Written Off" -> Pill.Tone.Serious
      case "Final"                                          => "Final"      -> Pill.Tone.Good
      case "Voided"                                         => "Voided"     -> Pill.Tone.Neutral
      case other                                            => other        -> Pill.Tone.Neutral
    Pill(label, tone)

  def apply(accountId: String): HtmlElement =
    val spec   = AppState.editReconSpec.now().getOrElse(ReconciliationSpecPayload())
    val specId = spec.id.getOrElse("")
    val state  = Var(State())
    val loadBus     = EventBus[Unit]()
    val unmatchBus  = EventBus[String]()
    val writeOffBus = EventBus[(String, String)]()

    def load() =
      ApiClient.get[Seq[ReconciliationPayload]](
        HttpPath.url(s"${HttpPath.reconSpec}/$specId/reconciliations"),
        SessionStore.token
      )

    div(
      loadBus.events.flatMapSwitch(_ => load()) --> {
        case Right(rs) => state.update(_.copy(recons = rs))
        case Left(e)   => AppState.globalError.set(Some(e))
      },
      unmatchBus.events.flatMapSwitch { reconId =>
        ApiClient.postRaw(HttpPath.url(s"${HttpPath.reconciliation}/$reconId/unmatch"), "", SessionStore.token)
      } --> {
        case Right(_) => loadBus.emit(())
        case Left(e)  => AppState.globalError.set(Some(e))
      },
      writeOffBus.events.flatMapSwitch { (reconId, reason) =>
        ApiClient.postRaw(
          HttpPath.url(s"${HttpPath.reconciliation}/$reconId/write-off"),
          write(WriteOffRequest(reason)),
          SessionStore.token
        )
      } --> {
        case Right(_) =>
          state.update(_.copy(editingWriteOff = None))
          loadBus.emit(())
        case Left(e) => AppState.globalError.set(Some(e))
      },
      onMountCallback { _ => loadBus.emit(()) },
      cls := "flex flex-col items-center mb-8 pt-20",
      div(
        cls := "my-8 text-center",
        PageHeader.title("Reconciliations"),
        Buttons.linkButton("Back to Recon Specs", Observer[Unit](_ => Nav.toReconSpecs(accountId))),
        Buttons.textButton("Manual Match", Observer[Unit](_ => Nav.toManualMatch(accountId))),
        child <-- state.signal.map { s =>
          Table.create(
            Seq("Description", "Status", "Applied Rules", "Balance", "Adjustment Reason", "Created At", "Updated At", "Actions"),
            s.recons.map(r =>
              Seq(
                span(r.description),
                statusPill(r),
                span(r.appliedRules),
                span(r.balance.toString),
                span(r.adjustmentReason.getOrElse("")),
                span(ViewHelper.formatDateTime(r.createdAt)),
                span(ViewHelper.formatDateTime(r.updatedAt)),
                div(
                  cls := (if r.status == "Voided" then "opacity-60" else ""),
                  if r.status == "Voided" then span("—"): HtmlElement
                  else
                    div(
                      cls := "flex flex-col gap-1",
                      (if r.status == "InProgress" then
                         if s.editingWriteOff.contains(r.id) then
                           writeOffInput(r.id, writeOffBus)
                         else
                           Buttons.textButton("Write Off", Observer[Unit](_ => state.update(_.copy(editingWriteOff = Some(r.id)))))
                       else
                         span(): HtmlElement),
                      Buttons.textButton("Unmatch", Observer[Unit](_ => unmatchBus.emit(r.id)))
                    )
                )
              )
            ),
            numCols = Set(3)
          )
        }
      )
    )

  // A local Var, not the page-level State: writing every keystroke into the outer State would trigger the
  // `child <-- state.signal.map(...)` above to rebuild the whole table on each keystroke, destroying and
  // recreating this very input element and dropping whatever had been typed so far. Scoping the reason to a
  // Var created only when this element is built keeps typing from touching outer State at all.
  private def writeOffInput(reconId: String, writeOffBus: EventBus[(String, String)]): HtmlElement =
    val reasonVar = Var("")
    div(
      cls := "flex gap-1",
      input(
        typ         := "text",
        placeholder := "reason",
        onInput.mapToValue --> Observer[String](v => reasonVar.set(v))
      ),
      Buttons.textButton("Confirm", Observer[Unit](_ => writeOffBus.emit((reconId, reasonVar.now()))))
    )
