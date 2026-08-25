package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.HttpPath
import onparity.common.model.{FundFlowPayload, ReportsSummaryPayload, TrendReportPayload}
import onparity.http.ApiClient
import onparity.state.AppState.apiBind
import onparity.state.{AppState, SessionStore}
import onparity.view.components.ChartCanvas.Series
import onparity.view.components.{ChartCanvas, Form, PageHeader, Pill}
import upickle.default.*

object ReportsPage:

  case class State(
    fundFlows: Seq[FundFlowPayload]          = Seq.empty,
    summary:   Option[ReportsSummaryPayload] = None,
    trend:     Option[TrendReportPayload]    = None
  )

  def apply(): HtmlElement =
    val state            = Var(State())
    val loadFundFlowsBus = EventBus[Unit]()
    val loadSummaryBus   = EventBus[Option[String]]()
    val loadTrendBus     = EventBus[Option[String]]()

    def loadFundFlows() =
      ApiClient.get[Seq[FundFlowPayload]](HttpPath.url(HttpPath.fundFlows), SessionStore.token)

    def loadSummary(fundFlowId: Option[String]) =
      val query = fundFlowId.map(id => s"?fundFlowId=$id").getOrElse("")
      ApiClient.get[ReportsSummaryPayload](HttpPath.url(s"${HttpPath.reports}/summary$query"), SessionStore.token)

    def loadTrend(fundFlowId: Option[String]) =
      val query = fundFlowId.map(id => s"?fundFlowId=$id").getOrElse("")
      ApiClient.get[TrendReportPayload](HttpPath.url(s"${HttpPath.reports}/trend$query"), SessionStore.token)

    def loadFor(fundFlowId: Option[String]): Unit =
      loadSummaryBus.emit(fundFlowId)
      loadTrendBus.emit(fundFlowId)

    div(
      loadFundFlowsBus.events.flatMapSwitch(_ => loadFundFlows()).apiBind(ffs => state.update(_.copy(fundFlows = ffs))),
      loadSummaryBus.events.flatMapSwitch(loadSummary).apiBind(s => state.update(_.copy(summary = Some(s)))),
      loadTrendBus.events.flatMapSwitch(loadTrend).apiBind(t => state.update(_.copy(trend = Some(t)))),
      onMountCallback { _ =>
        loadFundFlowsBus.emit(())
        loadFor(None)
      },
      cls := "flex flex-col mb-8 pt-20",
      div(
        cls := "my-8 w-full px-8",
        div(
          cls := "flex justify-between items-center mb-6",
          PageHeader.title("Reports & Analytics"),
          child <-- state.signal.map(s => fundFlowSelect(s.fundFlows, loadFor))
        ),
        child <-- state.signal.map {
          case State(_, None, _)              => div()
          case State(_, Some(summary), trend) => widgets(summary, trend)
        }
      )
    )

  private def fundFlowSelect(fundFlows: Seq[FundFlowPayload], loadFor: Option[String] => Unit): HtmlElement =
    val options = ("", "All fund flows") +: fundFlows.map(ff => ff.id.getOrElse("") -> ff.name)
    div(
      cls := "w-64",
      Form.selectField("fundFlowFilter", "Fund flow", options, "", v => loadFor(Option(v).filter(_.nonEmpty)))
    )

  private def widgets(summary: ReportsSummaryPayload, trend: Option[TrendReportPayload]): HtmlElement =
    div(
      cls := "grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6",
      matchRateCard(summary, trend),
      exceptionCard(summary, trend),
      agingCard(summary, trend)
    )

  private def matchRateCard(summary: ReportsSummaryPayload, trend: Option[TrendReportPayload]): HtmlElement =
    val rated    = summary.matchRates.filter(_.matchRatePercent.isDefined)
    val excluded = summary.matchRates.filterNot(_.matchRatePercent.isDefined)
    val avg      = if rated.isEmpty then None else Some(rated.flatMap(_.matchRatePercent).sum / rated.size)

    div(
      cls := "border border-md-outline rounded-lg p-4",
      h3(cls := "font-medium mb-1 text-md-on-surface dark:text-white", "Match rate"),
      div(
        cls := "text-sm text-md-on-surface-variant dark:text-gray-400 mb-3",
        avg match
          case Some(a) =>
            s"${a.setScale(1, BigDecimal.RoundingMode.HALF_UP)}% reconciled across ${rated.size} account(s)"
          case None => "No reconciling accounts with a balance to measure"
      ),
      ChartCanvas(
        "bar",
        Val(Series(rated.map(_.accountName), rated.flatMap(_.matchRatePercent).map(_.toDouble))),
        horizontal = true
      ),
      if excluded.nonEmpty then
        div(
          cls := "text-xs text-md-on-surface-variant dark:text-gray-400 mt-2",
          s"Excluded (zero balance): ${excluded.map(_.accountName).mkString(", ")}"
        )
      else div(),
      trendChart(
        "Match rate over time",
        trend.map(t =>
          t.points.filter(_.avgMatchRatePercent.isDefined).map(p => p.date -> p.avgMatchRatePercent.get.toDouble)
        )
      )
    )

  private def exceptionCard(summary: ReportsSummaryPayload, trend: Option[TrendReportPayload]): HtmlElement =
    val categoryLabels =
      Seq("AmountMismatch", "MissingCounterpart", "Duplicate", "TimingDifference", "DataQuality", "FileMissing")
    val categoryValues = categoryLabels.map(c => summary.exceptions.byCategory.getOrElse(c, 0).toDouble)
    val statusLabels   = Seq("New", "Investigating", "Escalated")

    div(
      cls := "border border-md-outline rounded-lg p-4",
      h3(cls := "font-medium mb-3 text-md-on-surface dark:text-white", "Open exceptions by category"),
      ChartCanvas("doughnut", Val(Series(categoryLabels, categoryValues))),
      div(
        cls := "flex gap-2 mt-3 flex-wrap",
        statusLabels.map(s => Pill(s"$s: ${summary.exceptions.byStatus.getOrElse(s, 0)}", statusTone(s)))
      ),
      exceptionTrendChart(categoryLabels, trend)
    )

  // One line per category rather than a single aggregate line, so a spike is traceable to which
  // kind of exception caused it. Categories with zero activity across the whole window are left
  // out of the chart/legend entirely — with 6 possible categories, most fund flows only ever see
  // one or two, and a legend padded with flat-zero lines is more noise than signal.
  private def exceptionTrendChart(categoryLabels: Seq[String], trend: Option[TrendReportPayload]): HtmlElement =
    trend match
      case Some(t) if t.points.nonEmpty =>
        val datasets = categoryLabels
          .map(cat => cat -> t.points.map(p => p.openExceptionsByCategory.getOrElse(cat, 0).toDouble))
          .filter(_._2.exists(_ > 0))
        if datasets.isEmpty then div()
        else
          div(
            cls := "mt-4 pt-4 border-t border-md-outline",
            h4(
              cls := "text-xs font-medium mb-2 text-md-on-surface-variant dark:text-gray-400",
              "Open exceptions over time"
            ),
            ChartCanvas.multiLine(Val(ChartCanvas.MultiSeries(t.points.map(_.date), datasets)))
          )
      case _ => div()

  private def statusTone(status: String): Pill.Tone = status match
    case "New"           => Pill.Tone.Accent
    case "Investigating" => Pill.Tone.Warning
    case "Escalated"     => Pill.Tone.Critical
    case _               => Pill.Tone.Neutral

  private def agingCard(summary: ReportsSummaryPayload, trend: Option[TrendReportPayload]): HtmlElement =
    div(
      cls := "border border-md-outline rounded-lg p-4",
      h3(cls := "font-medium mb-1 text-md-on-surface dark:text-white", "Unreconciled aging"),
      div(
        cls := "mb-3",
        Pill(
          s"${summary.aging.overdueCount} overdue",
          if summary.aging.overdueCount > 0 then Pill.Tone.Critical else Pill.Tone.Good
        )
      ),
      ChartCanvas("bar", Val(Series(summary.aging.buckets.map(_._1), summary.aging.buckets.map(_._2.toDouble)))),
      trendChart(
        "Unreconciled backlog over time",
        trend.map(_.points.map(p => p.date -> p.unreconciledBacklogCount.toDouble))
      )
    )

  // Shared by all three cards: a small line chart of one metric over the last 30 days, or nothing
  // while trend data is still loading / there's no history yet for this metric.
  private def trendChart(title: String, points: Option[Seq[(String, Double)]]): HtmlElement =
    points match
      case Some(pts) if pts.nonEmpty =>
        div(
          cls := "mt-4 pt-4 border-t border-md-outline",
          h4(cls := "text-xs font-medium mb-2 text-md-on-surface-variant dark:text-gray-400", title),
          ChartCanvas("line", Val(Series(pts.map(_._1), pts.map(_._2))))
        )
      case _ => div()
