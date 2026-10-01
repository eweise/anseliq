package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.HttpPath
import onparity.common.model.{
  AppliedRules,
  Direction,
  ReconciliationDetailPayload,
  ReconciliationMovementPayload,
  ReconciliationSpecSummaryPayload
}
import onparity.http.ApiClient
import onparity.routing.Nav
import onparity.state.AppState.apiBind
import onparity.state.{AppState, SessionStore}
import onparity.view.ViewHelper
import onparity.view.components.{Card, DetailList, PageHeader, Table}

object ReconciliationDetailPage:

  case class State(detail: Option[ReconciliationDetailPayload] = None)

  def apply(id: String): HtmlElement =
    val state   = Var(State())
    val loadBus = EventBus[Unit]()

    def load() =
      ApiClient.get[ReconciliationDetailPayload](HttpPath.url(s"${HttpPath.reconciliation}/$id"), SessionStore.token)

    PageHeader.detailPage(
      loadBus.events.flatMapSwitch(_ => load()).apiBind(d => state.update(_.copy(detail = Some(d)))),
      onMountCallback(_ => loadBus.emit(())),
      PageHeader.backLink(Observer[Unit](_ => Nav.back())),
      child <-- state.signal.map {
        case State(None) => div()
        case State(Some(d)) =>
          div(
            PageHeader.title(s"Reconciliation — ${d.reconciliation.description}"),
            reconciliationSection(d),
            d.spec.map(reconSpecSection).getOrElse(div()),
            movementsSection(d.movements)
          )
      }
    )

  private def reconciliationSection(d: ReconciliationDetailPayload): HtmlElement =
    val r = d.reconciliation
    div(
      cls := s"${Card.cls} p-4 mb-4",
      h3(cls := "font-medium mb-3 text-md-on-surface dark:text-white", "Reconciliation"),
      DetailList(
        "ID"                -> DetailList.mono(r.id),
        "Description"       -> (if r.description.isEmpty then DetailList.empty else span(r.description)),
        "Status"            -> ReconciliationsPage.statusPill(r),
        "Balance"           -> span(cls := "tabular-nums", r.balance.toString),
        "Applied Rules"     -> appliedRulesView(r.appliedRules),
        "Adjustment Reason" -> r.adjustmentReason.map(span(_)).getOrElse(DetailList.empty),
        "Created At"        -> span(ViewHelper.formatDateTime(r.createdAt)),
        "Updated At"        -> span(ViewHelper.formatDateTime(r.updatedAt))
      )
    )

  // One line per leaf rule: the condition both sides were compared on, then the value they matched.
  // Also used by ExceptionDetailPage for a disputed reconciliation.
  def appliedRulesView(rules: AppliedRules): HtmlElement =
    rules match
      case AppliedRules.Matched(matches) =>
        ul(
          cls := "space-y-1",
          matches.map: m =>
            li(
              cls := "flex flex-wrap items-center gap-2",
              code(cls := "font-mono text-xs text-md-on-surface-variant dark:text-gray-400", m.condition),
              span(cls := "text-gray-400 dark:text-gray-500", "→"),
              span(
                cls := "px-2 py-0.5 rounded font-mono text-xs bg-md-primary-container text-md-on-primary-container dark:bg-md-primary-container-dark dark:text-md-on-primary-container-dark",
                m.value
              )
            )
        )
      case AppliedRules.Manual   => span("Manual match")
      case AppliedRules.Reversal => span("System reversal")

  private def reconSpecSection(s: ReconciliationSpecSummaryPayload): HtmlElement =
    div(
      cls := s"${Card.cls} p-4 mb-4",
      h3(cls := "font-medium mb-3 text-md-on-surface dark:text-white", "Reconciliation Spec"),
      DetailList(
        "ID"           -> DetailList.mono(s.id),
        "Account"      -> span(s.accountName),
        "Aging Limit"  -> span(s"${s.agingLimit} day(s)"),
        "Relationship" -> span(s.relationship.toString),
        "Rule JSON" -> pre(
          cls := "font-mono text-xs whitespace-pre-wrap break-all p-2 rounded bg-md-surface-variant dark:bg-gray-900",
          prettyJson(s.ruleJson)
        )
      )
    )

  private def prettyJson(json: String): String =
    try ujson.read(json).render(indent = 2)
    catch case _: Exception => json

  // Also used by ExceptionDetailPage for a disputed reconciliation.
  def movementsSection(movements: Seq[ReconciliationMovementPayload]): HtmlElement =
    div(
      cls := s"${Card.cls} p-4",
      h3(cls := "font-medium mb-3 text-md-on-surface dark:text-white", "Related Movements"),
      Table.create(
        Seq("ID", "Direction", "Other Account", "Type", "Amount"),
        movements.map(m =>
          Seq(
            a(
              href := s"/components/movements/${m.id}",
              cls  := "font-mono text-xs text-md-on-surface dark:text-gray-200 hover:underline cursor-pointer",
              onClick --> { e =>
                e.preventDefault()
                Nav.toMovementDetail(m.id)
              },
              m.id
            ),
            m.direction.map(d => span(d.toString)).getOrElse(DetailList.empty),
            m.otherAccountName.map(span(_)).getOrElse(DetailList.empty),
            span(movementTypeLabel(m.movementType)),
            span(m.amount.toString)
          )
        ),
        numCols = Set(4)
      )
    )

  private def movementTypeLabel(movementType: Option[String]): String =
    movementType match
      case Some("ManualAdjustment") => "Write-off adjustment"
      case Some(other)              => other
      case None                     => "—"

  // One-line description of a movement for choosing between them, e.g. "OUT · 90.00 → Report Account".
  def movementChoiceLabel(m: ReconciliationMovementPayload): String =
    val amount = (m.direction.map(_.toString).toSeq :+ m.amount.toString).mkString(" · ")
    val other = for
      d    <- m.direction
      name <- m.otherAccountName
    yield s" ${if d == Direction.IN then "←" else "→"} $name"
    amount + other.getOrElse("")
