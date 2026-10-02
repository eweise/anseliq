package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.HttpPath
import onparity.common.model.{
  AppliedRules,
  Direction,
  ReconRelationship,
  ReconciliationDetailPayload,
  ReconciliationMovementPayload
}
import onparity.http.ApiClient
import onparity.routing.Nav
import onparity.state.AppState.apiBind
import onparity.state.{AppState, SessionStore}
import onparity.view.ViewHelper
import onparity.view.components.{Buttons, Card, DetailList, Form, PageHeader, Pill, ReconStatus, ReconciliationActions, Table}

object ReconciliationDetailPage:

  case class State(detail: Option[ReconciliationDetailPayload] = None)

  // Reconciliation ids are time-ordered UUIDs whose leading groups are near-identical, so the
  // last 8 hex digits are what tells two apart at a glance.
  def shortId(id: String): String = id.takeRight(8)

  def apply(id: String): HtmlElement =
    val state   = Var(State())
    val loadBus = EventBus[Unit]()

    def load() =
      ApiClient.get[ReconciliationDetailPayload](HttpPath.url(s"${HttpPath.reconciliation}/$id"), SessionStore.token)

    PageHeader.detailPage(
      loadBus.events.flatMapSwitch(_ => load()).apiBind(d => state.update(_.copy(detail = Some(d)))),
      onMountCallback(_ => loadBus.emit(())),
      child <-- state.signal.map {
        case State(None) => div()
        case State(Some(d)) =>
          val r = d.reconciliation
          div(
            cls := "relative flex flex-col gap-6",
            breadcrumb(d),
            div(
              cls := "flex flex-col gap-1",
              div(
                cls := "flex flex-wrap items-center gap-3",
                h2(
                  cls := "text-2xl font-medium text-gray-900 dark:text-white",
                  "Reconciliation ",
                  span(cls := "font-mono", shortId(r.id))
                ),
                ReconStatus.pill(r)
              ),
              if r.description.trim.nonEmpty then p(cls := s"text-sm ${Form.mutedTextCls}", r.description)
              else emptyNode
            ),
            ReconciliationActions(
              r,
              d.movements,
              onChanged  = Observer[Unit](_ => loadBus.emit(())),
              agingLimit = d.spec.map(_.agingLimit)
            ),
            balanceSection(d),
            if d.openDisputes.isEmpty then emptyNode else disputesSection(d),
            movementsSection(d.movements),
            detailsSection(d)
          )
      }
    )

  // Ledgers › <ledger> › Reconciliations › <id>. A spec-less (system Reversed) reconciliation
  // belongs to no ledger, so its trail is just its own name.
  private def breadcrumb(d: ReconciliationDetailPayload): HtmlElement =
    PageHeader.ledgerBreadcrumb(
      d.fundFlow,
      d.fundFlow.map(_ => "Reconciliations" -> "Reconciliations"),
      shortId(d.reconciliation.id) -> None
    )

  // In and out totals as the reconciled account sees them, and what they were matched on — the
  // answer to "why is this Mismatched?" without adding up the movements table by hand.
  private def balanceSection(d: ReconciliationDetailPayload): HtmlElement =
    val inTotal  = d.movements.filter(_.direction.contains(Direction.IN)).map(_.amount).sum
    val outTotal = d.movements.filter(_.direction.contains(Direction.OUT)).map(_.amount).sum
    val diff     = inTotal - outTotal
    val balanced = diff == BigDecimal(0)
    val relationshipNote = d.spec.map(_.relationship).map {
      case ReconRelationship.OneToOne =>
        "OneToOne rule: exactly one movement on each side, so nothing else can join this pair."
      case ReconRelationship.OneToMany =>
        "OneToMany rule: more movements on the \"many\" side can still join until it balances."
      case ReconRelationship.ManyToMany => "ManyToMany rule."
    }
    def figure(label: String, value: String, tone: String) =
      div(
        div(cls := Form.sectionLabelCls, label),
        div(cls := s"mt-1 text-xl font-semibold tabular-nums $tone", value)
      )
    val plain = "text-md-on-surface dark:text-white"
    div(
      cls := s"${Card.cls} p-5 flex flex-col gap-4",
      h3(
        cls := "font-medium text-md-on-surface dark:text-white",
        if balanced then "Balance" else "Why it doesn't balance"
      ),
      div(
        cls := "grid grid-cols-1 sm:grid-cols-3 gap-4",
        figure("In", money(inTotal), plain),
        figure("Out", money(outTotal), plain),
        figure(
          "Difference",
          money(diff),
          if balanced then "text-md-good dark:text-md-good-dark" else "text-md-critical dark:text-md-critical-dark"
        )
      ),
      div(
        cls := "flex flex-col gap-2 text-sm",
        div(cls := Form.sectionLabelCls, "Matched on"),
        appliedRulesView(d.reconciliation.appliedRules),
        relationshipNote.map(n => p(cls := Form.mutedTextCls, n)).getOrElse(emptyNode)
      )
    )

  private def disputesSection(d: ReconciliationDetailPayload): HtmlElement =
    div(
      cls := s"${Card.cls} p-5 flex flex-col gap-3",
      h3(cls := "font-medium text-md-on-surface dark:text-white", "Open disputes"),
      d.openDisputes.map { dispute =>
        div(
          cls := "flex flex-wrap items-center gap-3 text-sm",
          span(cls := "font-medium text-md-on-surface dark:text-white", dispute.category.toString),
          Pill(dispute.status.toString, ExceptionDetailPage.statusTone(dispute.status)),
          span(cls := Form.mutedTextCls, dispute.assignedToName.fold("Unassigned")(n => s"Assigned to $n")),
          Buttons.textButton("Open thread", Observer[Unit](_ => Nav.toExceptionDetail(dispute.id)))
        )
      }
    )

  // Reference detail nobody needs to act on, collapsed so it doesn't push the movements down.
  private def detailsSection(d: ReconciliationDetailPayload): HtmlElement =
    val r = d.reconciliation
    val reconRows = Seq(
      "ID"                -> DetailList.mono(r.id),
      "Adjustment Reason" -> r.adjustmentReason.map(span(_)).getOrElse(DetailList.empty),
      "Created At"        -> span(ViewHelper.formatDateTime(r.createdAt)),
      "Updated At"        -> span(ViewHelper.formatDateTime(r.updatedAt))
    )
    val specRows = d.spec.toSeq.flatMap { s =>
      Seq(
        "Account"      -> span(s.accountName),
        "Aging Limit"  -> span(s"${s.agingLimit} day(s)"),
        "Relationship" -> span(s.relationship.toString),
        "Rule JSON" -> pre(
          cls := "font-mono text-xs whitespace-pre-wrap break-all p-2 rounded bg-md-surface-variant dark:bg-gray-900",
          prettyJson(s.ruleJson)
        )
      )
    }
    htmlTag("details")(
      cls := s"${Card.cls} p-5",
      htmlTag("summary")(cls := "cursor-pointer font-medium text-md-on-surface dark:text-white", "Details"),
      div(cls := "mt-3", DetailList((reconRows ++ specRows)*))
    )

  private def money(amount: BigDecimal): String =
    if amount < 0 then f"-$$${-amount}%.2f" else f"$$${amount}%.2f"

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
