package anseliq.view.pages

import com.raquo.laminar.api.L.*
import anseliq.common.http.HttpPath
import anseliq.common.model.{DisputePayload, DisputeStatusPayload, DisputeTargetPayload, ReportsSummaryPayload}
import anseliq.http.ApiClient
import anseliq.routing.Nav
import anseliq.state.AppState.apiBind
import anseliq.state.SessionStore
import anseliq.view.components.ChartCanvas.Series
import anseliq.view.components.{Card, ChartCanvas, PageHeader, Pill, Table}

object DashboardPage:

  enum DisputeFilter:
    case AssignedToMe, CreatedByMe, All

  case class State(
    disputes: Seq[DisputePayload]           = Seq.empty,
    filter:   DisputeFilter                 = DisputeFilter.AssignedToMe,
    summary:  Option[ReportsSummaryPayload] = None
  )

  // Role-gated at the top level: only Analyst has real dashboard content so far (Admin/Viewer
  // dashboards are explicitly future work) — checked once at mount, not reactively, since role
  // can't change without a fresh login (same convention LeftNav already uses).
  def apply(): HtmlElement =
    if SessionStore.currentUserNow.exists(_.role == "Analyst") then analystDashboard()
    else genericDashboard()

  private def genericDashboard(): HtmlElement =
    PageHeader.page(
      PageHeader.titleRow(
        PageHeader.title(
          s"Welcome${SessionStore.currentUserNow.map(u => s", ${u.email}").getOrElse("")}"
        )
      )
    )

  private def analystDashboard(): HtmlElement =
    val state          = Var(State())
    val loadBus        = EventBus[Unit]()
    val loadSummaryBus = EventBus[Unit]()
    val myUserId       = SessionStore.currentUserNow.map(_.userId)

    def filtered(s: State): Seq[DisputePayload] = s.filter match
      case DisputeFilter.AssignedToMe => s.disputes.filter(d => myUserId.isDefined && d.assignedToUserId == myUserId)
      case DisputeFilter.CreatedByMe  => s.disputes.filter(d => myUserId.isDefined && d.raisedByUserId == myUserId)
      case DisputeFilter.All          => s.disputes

    PageHeader.page(
      loadBus.events
        .flatMapSwitch(_ => ApiClient.get[Seq[DisputePayload]](HttpPath.url(HttpPath.disputes), SessionStore.token))
        .apiBind(ds => state.update(_.copy(disputes = ds))),
      // No fundFlowId query param -> aggregated across every fund flow, same as ReportsPage's
      // default "All fund flows" view. A per-fund-flow filter isn't needed here; that's what the
      // Reports page is for.
      loadSummaryBus.events
        .flatMapSwitch(_ =>
          ApiClient.get[ReportsSummaryPayload](HttpPath.url(s"${HttpPath.reports}/summary"), SessionStore.token)
        )
        .apiBind(s => state.update(_.copy(summary = Some(s)))),
      onMountCallback { _ =>
        loadBus.emit(())
        loadSummaryBus.emit(())
      },
      PageHeader.titleRow(PageHeader.title("Dashboard")),
      div(
        cls := "w-full px-8",
        child.maybe <-- state.signal.map(_.summary.map(agingCard)),
        h3(cls := "font-medium mt-6 mb-2 text-md-on-surface dark:text-white", "My Disputes"),
        div(
          cls := "flex gap-2 mb-4",
          filterButton("Assigned to me", DisputeFilter.AssignedToMe, state),
          filterButton("Created by me", DisputeFilter.CreatedByMe, state),
          filterButton("All disputes", DisputeFilter.All, state)
        ),
        child <-- state.signal.map { s =>
          Table.create(
            Seq("Status", "Category", "Type", "Account", "Reference", "Detail"),
            filtered(s).map(renderRow)
          )
        }
      )
    )

  // Mirrors ReportsPage's own agingCard exactly (minus the trend sparkline, which needs the
  // separate /reports/trend fetch that this page doesn't otherwise need) — not worth extracting
  // into a shared component for two call sites this small. Clickable as a whole (not per-bucket:
  // ChartCanvas is a shared component with no click/element-hit-testing plumbing today, and a
  // whole-card link to the drill-through list is enough to satisfy "click the graph to see the
  // movements" without adding that machinery just for this one card).
  private def agingCard(summary: ReportsSummaryPayload): HtmlElement =
    div(
      cls := s"${Card.cls} p-4 mb-6 max-w-xl cursor-pointer hover:shadow-md transition-shadow",
      onClick --> (_ => Nav.toAllUnreconciledMovements()),
      h3(cls := "font-medium mb-1 text-md-on-surface dark:text-white", "Unreconciled aging"),
      div(
        cls := "mb-3",
        Pill(
          s"${summary.aging.overdueCount} overdue",
          if summary.aging.overdueCount > 0 then Pill.Tone.Critical else Pill.Tone.Good
        )
      ),
      ChartCanvas(
        "bar",
        Val(Series(summary.aging.buckets.map(_._1), summary.aging.buckets.map(_._2.toDouble))),
        monochrome = true
      )
    )

  private def filterButton(label: String, filter: DisputeFilter, state: Var[State]): HtmlElement =
    button(
      typ := "button",
      cls <-- state.signal.map { s =>
        val base = "px-3 py-1.5 rounded-full text-xs font-medium transition-colors"
        if s.filter == filter then s"$base bg-md-primary text-md-on-primary"
        else s"$base bg-md-surface-variant dark:bg-gray-700 text-md-on-surface-variant dark:text-gray-300"
      },
      onClick --> (_ => state.update(_.copy(filter = filter))),
      label
    )

  // Row rendering mirrors ExceptionsPage's exactly — not worth extracting into a shared component
  // for a single point of reuse.
  private def statusTone(status: DisputeStatusPayload): Pill.Tone = status match
    case DisputeStatusPayload.New           => Pill.Tone.Accent
    case DisputeStatusPayload.Investigating => Pill.Tone.Warning
    case DisputeStatusPayload.Escalated     => Pill.Tone.Critical
    case DisputeStatusPayload.Resolved      => Pill.Tone.Good

  private def targetLabel(targetType: DisputeTargetPayload): String = targetType match
    case DisputeTargetPayload.Movement       => "Movement"
    case DisputeTargetPayload.Reconciliation => "Reconciliation"
    case DisputeTargetPayload.MovementSpec   => "Movement Spec"
    case DisputeTargetPayload.FileFeed       => "File Feed"

  private def renderRow(d: DisputePayload): Seq[HtmlElement] =
    Seq(
      Pill(d.status.toString, statusTone(d.status)),
      Pill(d.category.toString, Pill.Tone.Neutral),
      Pill(targetLabel(d.targetType), Pill.Tone.Neutral),
      span(d.accountName),
      span(d.referenceLabel),
      a(
        href := s"/components/exceptions/${d.id}",
        cls  := "flex items-center gap-2 hover:underline cursor-pointer",
        onClick --> { e =>
          e.preventDefault()
          Nav.toExceptionDetail(d.id)
        },
        d.detail
          .map(text => span(cls := "truncate max-w-xs text-md-on-surface dark:text-gray-200", text))
          .getOrElse(div()),
        span(
          cls := "text-md-primary dark:text-md-primary-dark text-xs font-medium whitespace-nowrap",
          "View →"
        )
      )
    )
