package anseliq.view.pages

import com.raquo.laminar.api.L.*
import anseliq.common.http.HttpPath
import anseliq.common.model.{
  AppliedRules,
  Direction,
  ReconRelationship,
  ReconciliationDetailPayload,
  ReconciliationMovementPayload,
  RuleMatch
}
import anseliq.http.ApiClient
import anseliq.routing.Nav
import anseliq.state.AppState.apiBind
import anseliq.state.{AppState, SessionStore}
import anseliq.view.ViewHelper
import anseliq.view.components.{
  Buttons,
  Card,
  DetailList,
  Form,
  MovementLabels,
  PageHeader,
  Pill,
  ReconStatus,
  ReconciliationActions,
  Table
}

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
            if d.openExceptions.isEmpty then emptyNode else exceptionsSection(d),
            movementsSection(d.movements),
            detailsSection(d)
          )
      }
    )

  // Ledgers › <ledger> › Reconciliations › <id>. A spec-less (system Reversed) reconciliation
  // belongs to no ledger, so its trail is just its own name.
  private def breadcrumb(d: ReconciliationDetailPayload): HtmlElement =
    PageHeader.ledgerBreadcrumb(
      d.ledger,
      d.ledger.map(_ => "Reconciliations" -> "Reconciliations"),
      shortId(d.reconciliation.id) -> None
    )

  // Each side's total, named by its account, and what they were matched on — the answer to "why is
  // this Mismatched?" without adding up the movements table by hand. Money flows Primary → Recon →
  // Secondary, so the Primary side is what came into the reconciled account (IN) and the Secondary
  // side what went out of it (OUT).
  private def balanceSection(d: ReconciliationDetailPayload): HtmlElement =
    val primary   = d.movements.filter(_.direction.contains(Direction.IN))
    val secondary = d.movements.filter(_.direction.contains(Direction.OUT))
    val inTotal   = primary.map(_.amount).sum
    val outTotal  = secondary.map(_.amount).sum
    val diff      = inTotal - outTotal
    val balanced  = diff == BigDecimal(0)
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
        figure(sideLabel(primary, "Primary"), money(inTotal), plain),
        figure(sideLabel(secondary, "Secondary"), money(outTotal), plain),
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

  private def exceptionsSection(d: ReconciliationDetailPayload): HtmlElement =
    div(
      cls := s"${Card.cls} p-5 flex flex-col gap-3",
      h3(cls := "font-medium text-md-on-surface dark:text-white", "Open exceptions"),
      d.openExceptions.map { exception =>
        div(
          cls := "flex flex-wrap items-center gap-3 text-sm",
          span(cls := "font-medium text-md-on-surface dark:text-white", exception.category.toString),
          Pill(exception.status.toString, ExceptionDetailPage.statusTone(exception.status)),
          span(cls := Form.mutedTextCls, exception.assignedToName.fold("Unassigned")(n => s"Assigned to $n")),
          Buttons.textButton("Open thread", Observer[Unit](_ => Nav.toExceptionDetail(exception.id)))
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
      div(cls                := "mt-3", DetailList((reconRows ++ specRows)*))
    )

  private def money(amount: BigDecimal): String =
    if amount < 0 then f"-$$${-amount}%.2f" else f"$$${amount}%.2f"

  // One line per leaf rule: the condition both sides were compared on, then the value they matched.
  // Also used by ExceptionDetailPage for an exception's reconciliation.
  def appliedRulesView(rules: AppliedRules): HtmlElement =
    rules match
      case AppliedRules.Matched(matches) => ul(cls := "space-y-1", matches.map(ruleMatchLine))
      case AppliedRules.Manual           => span("Manual match")
      case s: AppliedRules.Suggested =>
        val source =
          s"Accepted suggestion from ${s.model} (${(s.confidence * 100).setScale(0, BigDecimal.RoundingMode.HALF_UP)}% confident)"
        // A checked explanation reads like a rule match; the model's own reason is the fallback.
        s.explanation.fold(span(s"$source: ${s.reason}")): e =>
          div(
            cls := "space-y-1",
            ul(ruleMatchLine(e)),
            div(cls := "text-xs text-md-on-surface-variant dark:text-gray-400", source)
          )
      case AppliedRules.Reversal => span("System reversal")

  // The condition both sides were compared on, then the value they matched.
  private def ruleMatchLine(m: RuleMatch): HtmlElement =
    li(
      cls := "flex flex-wrap items-center gap-2",
      code(cls := "font-mono text-xs text-md-on-surface-variant dark:text-gray-400", m.condition),
      span(cls := "text-gray-400 dark:text-gray-500", "→"),
      span(
        cls := "px-2 py-0.5 rounded font-mono text-xs bg-md-primary-container text-md-on-primary-container dark:bg-md-primary-container-dark dark:text-md-on-primary-container-dark",
        m.value
      )
    )

  // A side's account name(s), or its role while it has no movements yet (e.g. a OneToMany group
  // still waiting on its other side).
  private def sideLabel(movements: Seq[ReconciliationMovementPayload], role: String): String =
    val names = movements.flatMap(_.otherAccountName).distinct
    if names.isEmpty then role else names.mkString(" / ")

  private def prettyJson(json: String): String =
    try ujson.read(json).render(indent = 2)
    catch case _: Exception => json

  // Also used by ExceptionDetailPage for an exception's reconciliation.
  def movementsSection(movements: Seq[ReconciliationMovementPayload]): HtmlElement =
    div(
      cls := s"${Card.cls} p-4",
      h3(cls := "font-medium mb-3 text-md-on-surface dark:text-white", "Related Movements"),
      Table.create(
        Seq("ID", "Account", "Type", "Amount"),
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
            m.otherAccountName.map(span(_)).getOrElse(DetailList.empty),
            div(
              span(MovementLabels.typeLabel(m.movementType, m.adjustmentType)),
              m.adjustmentNote
                .map(n => div(cls := "text-xs text-md-on-surface-variant dark:text-gray-400", n))
                .getOrElse(emptyNode)
            ),
            span(m.amount.toString)
          )
        ),
        numCols = Set(3)
      )
    )

  // One-line description of a movement for choosing between them, e.g. "Report Account · 90.00".
  def movementChoiceLabel(m: ReconciliationMovementPayload): String =
    (m.otherAccountName.toSeq :+ m.amount.toString).mkString(" · ")
