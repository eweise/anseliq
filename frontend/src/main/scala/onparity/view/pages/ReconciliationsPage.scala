package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.HttpPath
import onparity.common.model.{ReconciliationDetailPayload, ReconciliationMovementPayload, ReconciliationPayload, WriteOffRequest}
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
    editingWriteOff: Option[String]             = None // reconciliation id currently showing the write-off form
  )

  // Shared with ReconciliationDetailPage so both pages render status identically.
  def statusPill(r: ReconciliationPayload): HtmlElement =
    val (label, tone) = r.status match
      case "InProgress"                                     => "In Progress" -> Pill.Tone.Warning
      case "Mismatched"                                     => "Mismatched"  -> Pill.Tone.Critical
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
    val writeOffBus = EventBus[(String, String, String)]() // (reconciliation id, movement id, reason)

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
      writeOffBus.events.flatMapSwitch { (reconId, movementId, reason) =>
        ApiClient.postRaw(
          HttpPath.url(s"${HttpPath.reconciliation}/$reconId/write-off"),
          write(WriteOffRequest(reason = reason, movementId = movementId)),
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
                span(r.appliedRules.summary),
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
                      (if r.status == "Mismatched" || r.status == "InProgress" then
                         if s.editingWriteOff.contains(r.id) then
                           writeOffForm(
                             r,
                             writeOffBus,
                             onCancel = Observer[Unit](_ => state.update(_.copy(editingWriteOff = None)))
                           )
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

  private val controlCls =
    "px-2 py-1 text-xs rounded border border-md-outline dark:border-gray-600 bg-white dark:bg-gray-800 text-md-on-surface dark:text-gray-200"

  // Local Vars, not the page-level State: writing every keystroke into the outer State would trigger the
  // `child <-- state.signal.map(...)` above to rebuild the whole table on each keystroke, destroying and
  // recreating this very form and dropping whatever had been entered so far. Scoping the form's values to
  // Vars created only when this element is built keeps editing from touching outer State at all.
  //
  // The chosen movement decides which accounts the difference is posted between (see
  // ManualReconciler.writeOff), so the reconciliation's movements are loaded when the form opens.
  private def writeOffForm(
    r:           ReconciliationPayload,
    writeOffBus: EventBus[(String, String, String)],
    onCancel:    Observer[Unit]
  ): HtmlElement =
    val movementsVar = Var(Seq.empty[ReconciliationMovementPayload])
    val movementVar  = Var("")
    val reasonVar    = Var("")
    div(
      cls := "flex flex-col gap-1 min-w-56",
      ApiClient
        .get[ReconciliationDetailPayload](HttpPath.url(s"${HttpPath.reconciliation}/${r.id}"), SessionStore.token)
        .apiBind(d => movementsVar.set(d.movements)),
      span(cls := "text-xs text-md-on-surface-variant dark:text-gray-400", s"Write off ${r.balance} against:"),
      select(
        cls := controlCls,
        onChange.mapToValue --> movementVar,
        option(value := "", "Choose a movement…"),
        children <-- movementsVar.signal.map(_.map(m =>
          option(value := m.id, ReconciliationDetailPage.movementChoiceLabel(m))
        ))
      ),
      input(
        typ         := "text",
        placeholder := "Reason",
        cls         := controlCls,
        onInput.mapToValue --> reasonVar
      ),
      div(
        cls := "flex gap-1",
        button(
          cls := "text-md-primary dark:text-md-primary-dark rounded-full px-3 py-1 text-xs font-medium hover:bg-md-primary-container dark:hover:bg-md-primary-container-dark disabled:opacity-40 disabled:pointer-events-none",
          typ := "button",
          disabled <-- movementVar.signal.combineWith(reasonVar.signal).map((m, reason) => m.isEmpty || reason.trim.isEmpty),
          onClick.mapToUnit --> Observer[Unit](_ => writeOffBus.emit((r.id, movementVar.now(), reasonVar.now().trim))),
          "Confirm"
        ),
        button(
          cls := "text-md-on-surface-variant dark:text-gray-400 rounded-full px-3 py-1 text-xs font-medium hover:bg-md-surface-variant dark:hover:bg-gray-700",
          typ := "button",
          onClick.mapToUnit --> onCancel,
          "Cancel"
        )
      )
    )
