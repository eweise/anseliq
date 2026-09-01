package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.HttpPath
import onparity.common.model.{ReconciliationPayload, WriteOffRequest}
import onparity.http.ApiClient
import onparity.routing.Nav
import onparity.state.AppState.apiBind
import onparity.state.{AppState, SessionStore}
import onparity.view.ViewHelper
import onparity.view.components.{Buttons, PageHeader, Pill, Table}
import upickle.default.*

object ReconciliationsPage:

  case class State(
    recons:          Seq[ReconciliationPayload] = Seq.empty,
    editingWriteOff: Option[String]             = None // reconciliation id currently showing the reason field
  )

  // Shared with ReconciliationDetailPage so both pages render status identically.
  def statusPill(r: ReconciliationPayload): HtmlElement =
    val (label, tone) = r.status match
      case "InProgress"                                     => "In Progress" -> Pill.Tone.Warning
      case "Final" if r.adjustmentReason.exists(_.nonEmpty) => "Written Off" -> Pill.Tone.Serious
      case "Final"                                          => "Final"       -> Pill.Tone.Good
      case "Voided"                                         => "Voided"      -> Pill.Tone.Neutral
      case "Reversed"                                       => "Reversed"    -> Pill.Tone.Neutral
      case other                                            => other         -> Pill.Tone.Neutral
    Pill(label, tone)

  def apply(accountId: String, specId: String): HtmlElement =
    val state       = Var(State())
    val loadBus     = EventBus[Unit]()
    val unmatchBus  = EventBus[String]()
    val writeOffBus = EventBus[(String, String)]()

    def load() =
      ApiClient.get[Seq[ReconciliationPayload]](
        HttpPath.url(s"${HttpPath.reconSpec}/$specId/reconciliations"),
        SessionStore.token
      )

    PageHeader.page(
      loadBus.events.flatMapSwitch(_ => load()).apiBind(rs => state.update(_.copy(recons = rs))),
      unmatchBus.events.flatMapSwitch { reconId =>
        ApiClient.postRaw(HttpPath.url(s"${HttpPath.reconciliation}/$reconId/unmatch"), "", SessionStore.token)
      }.apiBind(_ => loadBus.emit(())),
      writeOffBus.events.flatMapSwitch { (reconId, reason) =>
        ApiClient.postRaw(
          HttpPath.url(s"${HttpPath.reconciliation}/$reconId/write-off"),
          write(WriteOffRequest(reason)),
          SessionStore.token
        )
      }.apiBind { _ =>
        state.update(_.copy(editingWriteOff = None))
        loadBus.emit(())
      },
      onMountCallback(_ => loadBus.emit(())),
      PageHeader.titleRow(PageHeader.title("Reconciliations")),
      div(
        cls := "w-full px-8",
        Buttons.linkButton("Back to Recon Specs", Observer[Unit](_ => Nav.toReconSpecs(accountId))),
        Buttons.textButton("Manual Match", Observer[Unit](_ => Nav.toManualMatch(accountId, specId))),
        child <-- state.signal.map { s =>
          Table.create(
            Seq(
              "Description",
              "Status",
              "Applied Rules",
              "Balance",
              "Adjustment Reason",
              "Created At",
              "Updated At",
              "Actions"
            ),
            s.recons.map(r =>
              Seq(
                a(
                  href := s"/components/reconciliations/${r.id}",
                  cls  := "text-md-on-surface dark:text-gray-200 hover:underline cursor-pointer",
                  onClick --> { e =>
                    e.preventDefault()
                    Nav.toReconciliationDetail(r.id)
                  },
                  // description can be blank on real data — fall back to something visibly clickable
                  // rather than an empty, invisible link.
                  if r.description.trim.nonEmpty then r.description else "View"
                ),
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
                         if s.editingWriteOff.contains(r.id) then writeOffInput(r.id, writeOffBus)
                         else
                           Buttons.textButton(
                             "Write Off",
                             Observer[Unit](_ => state.update(_.copy(editingWriteOff = Some(r.id))))
                           )
                       else span(): HtmlElement),
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
