package anseliq.view.pages

import com.raquo.laminar.api.L.*
import anseliq.common.http.HttpPath
import anseliq.common.model.{ExceptionPayload, ReportsSummaryPayload}
import anseliq.http.ApiClient
import anseliq.routing.Nav
import anseliq.state.AppState.apiBind
import anseliq.state.SessionStore
import anseliq.view.components.ChartCanvas.Series
import anseliq.view.components.{Card, ChartCanvas, ExceptionViews, PageHeader, Pill}

object DashboardPage:

  enum ExceptionFilter:
    case AssignedToMe, CreatedByMe, All

  case class State(
    exceptions: Seq[ExceptionPayload]         = Seq.empty,
    filter:     ExceptionFilter               = ExceptionFilter.AssignedToMe,
    summary:    Option[ReportsSummaryPayload] = None
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

    def filtered(s: State): Seq[ExceptionPayload] = s.filter match
      case ExceptionFilter.AssignedToMe =>
        s.exceptions.filter(d => myUserId.isDefined && d.assignedToUserId == myUserId)
      case ExceptionFilter.CreatedByMe => s.exceptions.filter(d => myUserId.isDefined && d.raisedByUserId == myUserId)
      case ExceptionFilter.All         => s.exceptions

    PageHeader.page(
      loadBus.events
        .flatMapSwitch(_ => ApiClient.get[Seq[ExceptionPayload]](HttpPath.exceptions, SessionStore.token))
        .apiBind(ds => state.update(_.copy(exceptions = ds))),
      // No ledgerId query param -> aggregated across every ledger, same as ReportsPage's
      // default "All ledgers" view. A per-ledger filter isn't needed here; that's what the
      // Reports page is for.
      loadSummaryBus.events
        .flatMapSwitch(_ => ApiClient.get[ReportsSummaryPayload](s"${HttpPath.reports}/summary", SessionStore.token))
        .apiBind(s => state.update(_.copy(summary = Some(s)))),
      onMountCallback { _ =>
        loadBus.emit(())
        loadSummaryBus.emit(())
      },
      PageHeader.titleRow(PageHeader.title("Dashboard")),
      div(
        cls := "w-full px-8",
        child.maybe <-- state.signal.map(_.summary.map(agingCard)),
        h3(cls := "font-medium mt-6 mb-2 text-md-on-surface dark:text-white", "My Exceptions"),
        div(
          cls := "flex gap-2 mb-4",
          filterButton("Assigned to me", ExceptionFilter.AssignedToMe, state),
          filterButton("Created by me", ExceptionFilter.CreatedByMe, state),
          filterButton("All exceptions", ExceptionFilter.All, state)
        ),
        child <-- state.signal.map { s =>
          ExceptionViews.table(filtered(s))
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

  private def filterButton(label: String, filter: ExceptionFilter, state: Var[State]): HtmlElement =
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
