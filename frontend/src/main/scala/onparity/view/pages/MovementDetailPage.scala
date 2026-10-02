package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.HttpPath
import onparity.common.model.{
  DisputeCategoryPayload,
  DisputePayload,
  DisputeTargetPayload,
  MovementDetailPayload,
  RaiseDisputeRequest
}
import onparity.common.model.model.Money
import onparity.http.ApiClient
import onparity.routing.Nav
import onparity.state.AppState.apiBind
import onparity.state.{AppState, SessionStore}
import onparity.view.components.{Buttons, Card, DetailList, Form, MovementLabels, PageHeader, Pill, ReconStatus}

object MovementDetailPage:

  case class State(detail: Option[MovementDetailPayload] = None)

  def apply(id: String): HtmlElement =
    val state   = Var(State())
    val loadBus = EventBus[Unit]()

    def load() =
      ApiClient.get[MovementDetailPayload](HttpPath.url(s"${HttpPath.movement}/$id"), SessionStore.token)

    PageHeader.detailPage(
      loadBus.events.flatMapSwitch(_ => load()).apiBind(d => state.update(_.copy(detail = Some(d)))),
      onMountCallback(_ => loadBus.emit(())),
      child <-- state.signal.map {
        case State(None) => div()
        case State(Some(d)) =>
          div(
            cls := "relative flex flex-col gap-6",
            PageHeader.ledgerBreadcrumb(
              d.fundFlow,
              d.fundFlow.map(_ => "Movements" -> "Movements"),
              ReconciliationDetailPage.shortId(d.id) -> None
            ),
            div(
              cls := "flex flex-wrap items-center gap-3",
              h2(
                cls := "text-2xl font-medium text-gray-900 dark:text-white",
                "Movement ",
                span(cls := "font-mono", ReconciliationDetailPage.shortId(d.id)),
                span(cls := "ml-3 tabular-nums", d.amount.toString)
              ),
              d.movementType.map(_ => Pill(MovementLabels.typeLabel(d.movementType, d.adjustmentType), Pill.Tone.Neutral))
                .getOrElse(emptyNode)
            ),
            raiseDispute(d.id, Observer[Unit](_ => loadBus.emit(()))),
            reconciliationSection(d),
            if d.openDisputes.isEmpty then emptyNode else disputesSection(d.openDisputes),
            movementSection(d),
            propertiesSection("Properties", d.properties, last = false),
            propertiesSection("Metadata", d.metaData, last     = true)
          )
      }
    )

  // Where the movement went: the reconciliation it's in (linked, with its status), or a note that
  // it's still waiting to be matched.
  private def reconciliationSection(d: MovementDetailPayload): HtmlElement =
    div(
      cls := s"${Card.cls} p-5 flex flex-wrap items-center gap-3 text-sm",
      h3(cls := "font-medium text-md-on-surface dark:text-white mr-2", "Reconciliation"),
      d.reconciliation match
        case Some(r) =>
          Seq(
            span(cls := "text-md-on-surface dark:text-gray-200", "Reconciled in"),
            a(
              href := s"/components/reconciliations/${r.id}",
              cls  := "font-mono text-md-primary hover:underline cursor-pointer",
              onClick --> { e =>
                e.preventDefault()
                Nav.toReconciliationDetail(r.id)
              },
              ReconciliationDetailPage.shortId(r.id)
            ),
            ReconStatus.pill(r)
          )
        case None =>
          Seq(
            span(
              cls := Form.mutedTextCls,
              "Not reconciled yet. It's matched on the next reconciliation run, or by hand with Manual match on the ledger page."
            )
          )
    )

  private def disputesSection(disputes: Seq[DisputePayload]): HtmlElement =
    div(
      cls := s"${Card.cls} p-5 flex flex-col gap-3",
      h3(cls := "font-medium text-md-on-surface dark:text-white", "Open disputes"),
      disputes.map { dispute =>
        div(
          cls := "flex flex-wrap items-center gap-3 text-sm",
          span(cls := "font-medium text-md-on-surface dark:text-white", dispute.category.toString),
          Pill(dispute.status.toString, ExceptionDetailPage.statusTone(dispute.status)),
          span(cls := Form.mutedTextCls, dispute.assignedToName.fold("Unassigned")(n => s"Assigned to $n")),
          Buttons.textButton("Open thread", Observer[Unit](_ => Nav.toExceptionDetail(dispute.id)))
        )
      }
    )

  // The categories a person can raise against a single movement (see DisputeCategory.validTargets
  // on the backend); the reconciliation- and file-level ones are raised by the system.
  private val movementCategories = Seq(
    DisputeCategoryPayload.MissingCounterpart -> "Missing counterpart",
    DisputeCategoryPayload.Duplicate          -> "Duplicate",
    DisputeCategoryPayload.TimingDifference   -> "Timing difference",
    DisputeCategoryPayload.DataQuality        -> "Data quality"
  )

  private def raiseDispute(movementId: String, onRaised: Observer[Unit]): HtmlElement =
    val open     = Var(false)
    val category = Var(movementCategories.head._1)
    val detail   = Var("")
    val raiseBus = EventBus[Unit]()
    div(
      cls := "flex flex-col gap-3",
      raiseBus.events.flatMapSwitch { _ =>
        ApiClient.post[DisputePayload, RaiseDisputeRequest](
          HttpPath.url(HttpPath.dispute),
          RaiseDisputeRequest(
            referenceId = movementId,
            targetType  = DisputeTargetPayload.Movement,
            category    = category.now(),
            detail      = Option(detail.now().trim).filter(_.nonEmpty)
          ),
          SessionStore.token
        )
      }.apiBind { _ =>
        open.set(false)
        onRaised.onNext(())
      },
      child <-- open.signal.map {
        case false =>
          div(Buttons.textButton("Raise dispute", Observer[Unit](_ => open.set(true))))
        case true =>
          div(
            cls := "flex flex-col gap-3 p-4 rounded-lg border border-md-outline dark:border-gray-700 bg-md-surface-variant dark:bg-md-surface-alt-dark max-w-xl",
            div(cls := "font-medium text-md-on-surface dark:text-white", "Raise a dispute on this movement"),
            Form.selectField(
              "disputeCategory",
              "Category",
              movementCategories.map((c, label) => c.toString -> label),
              category.now().toString,
              v => category.set(DisputeCategoryPayload.valueOf(v))
            ),
            Form.field("disputeDetail", "Detail (optional)", "", detail.set),
            div(
              cls := "flex gap-2",
              Buttons.linkButton("Raise", Observer[Unit](_ => raiseBus.emit(()))),
              Buttons.textButton("Cancel", Observer[Unit](_ => open.set(false)))
            )
          )
      }
    )

  private def movementSection(d: MovementDetailPayload): HtmlElement =
    div(
      cls := s"${Card.cls} p-4",
      h3(cls := "font-medium mb-3 text-md-on-surface dark:text-white", "Movement"),
      DetailList(
        "ID"                -> DetailList.mono(d.id),
        "Reference ID"      -> DetailList.mono(d.referenceId),
        "Type"              -> span(MovementLabels.typeLabel(d.movementType, d.adjustmentType)),
        "Adjustment Note"   -> d.adjustmentNote.map(span(_)).getOrElse(DetailList.empty),
        "Amount"            -> span(cls := "tabular-nums", d.amount.toString),
        "Movement Spec"     -> span(d.movementSpecName),
        "Primary Account"   -> accountView(d.inAccountName, d.inAccountBalance),
        "Secondary Account" -> accountView(d.outAccountName, d.outAccountBalance)
      )
    )

  // Account name, with its current balance as secondary detail rather than a parenthetical.
  private def accountView(name: String, balance: Money): HtmlElement =
    span(
      name,
      span(
        cls := "ml-2 text-xs text-md-on-surface-variant dark:text-gray-400 tabular-nums",
        s"Balance ${balance}"
      )
    )

  private def propertiesSection(title: String, props: Map[String, String], last: Boolean): HtmlElement =
    div(
      cls := s"${Card.cls} p-4",
      h3(cls := "font-medium mb-3 text-md-on-surface dark:text-white", title),
      if props.isEmpty then div(cls := "text-sm text-md-on-surface-variant dark:text-gray-400", "None")
      else
        DetailList(
          props.toSeq.sortBy(_._1).map((k, v) => k -> (if v.isEmpty then DetailList.empty else span(cls := "break-all", v)))*
        )
    )
