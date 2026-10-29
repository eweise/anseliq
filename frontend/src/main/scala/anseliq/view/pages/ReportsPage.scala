package anseliq.view.pages

import com.raquo.laminar.api.L.*
import anseliq.common.http.HttpPath
import anseliq.common.model.{LedgerPayload, ReportsSummaryPayload, TrendReportPayload}
import anseliq.http.ApiClient
import anseliq.state.AppState.apiBind
import anseliq.state.{AppState, SessionStore}
import anseliq.view.components.ChartCanvas.Series
import anseliq.view.components.{Card, ChartCanvas, Form, PageHeader, Pill}
import upickle.default.*

object ReportsPage:

  case class State(
    ledgers: Seq[LedgerPayload]          = Seq.empty,
    summary:   Option[ReportsSummaryPayload] = None,
    trend:     Option[TrendReportPayload]    = None
  )

  def apply(): HtmlElement =
    val state            = Var(State())
    val loadLedgersBus = EventBus[Unit]()
    val loadSummaryBus   = EventBus[Option[String]]()
    val loadTrendBus     = EventBus[Option[String]]()

    def loadLedgers() =
      ApiClient.get[Seq[LedgerPayload]](HttpPath.url(HttpPath.ledgers), SessionStore.token)

    def loadSummary(ledgerId: Option[String]) =
      val query = ledgerId.map(id => s"?ledgerId=$id").getOrElse("")
      ApiClient.get[ReportsSummaryPayload](HttpPath.url(s"${HttpPath.reports}/summary$query"), SessionStore.token)

    def loadTrend(ledgerId: Option[String]) =
      val query = ledgerId.map(id => s"?ledgerId=$id").getOrElse("")
      ApiClient.get[TrendReportPayload](HttpPath.url(s"${HttpPath.reports}/trend$query"), SessionStore.token)

    def loadFor(ledgerId: Option[String]): Unit =
      loadSummaryBus.emit(ledgerId)
      loadTrendBus.emit(ledgerId)

    PageHeader.page(
      loadLedgersBus.events.flatMapSwitch(_ => loadLedgers()).apiBind(ffs => state.update(_.copy(ledgers = ffs))),
      loadSummaryBus.events.flatMapSwitch(loadSummary).apiBind(s => state.update(_.copy(summary = Some(s)))),
      loadTrendBus.events.flatMapSwitch(loadTrend).apiBind(t => state.update(_.copy(trend = Some(t)))),
      onMountCallback { _ =>
        loadLedgersBus.emit(())
        loadFor(None)
      },
      PageHeader.titleRow(
        cls := "flex justify-between items-center",
        PageHeader.title("Reports & Analytics"),
        child <-- state.signal.map(s => ledgerSelect(s.ledgers, loadFor))
      ),
      div(
        cls := "w-full px-8",
        child <-- state.signal.map {
          case State(_, None, _)              => div()
          case State(_, Some(summary), trend) => widgets(summary, trend)
        }
      )
    )

  private def ledgerSelect(ledgers: Seq[LedgerPayload], loadFor: Option[String] => Unit): HtmlElement =
    val options = ("", "All ledgers") +: ledgers.map(ff => ff.id.getOrElse("") -> ff.name)
    div(
      cls := "w-64",
      Form.selectField("ledgerFilter", "Ledger", options, "", v => loadFor(Option(v).filter(_.nonEmpty)))
    )

  private def widgets(summary: ReportsSummaryPayload, trend: Option[TrendReportPayload]): HtmlElement =
    div(
      cls := "grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6",
      matchRateCard(summary, trend),
      balanceCoverageCard(summary, trend),
      exceptionCard(summary, trend),
      agingCard(summary, trend)
    )

  // The real match rate: reconciledMovementCount/totalMovementCount*100 per account, a true 0-100%
  // ratio. Accounts with no movements at all are excluded from both the average and the chart —
  // there's nothing to rate yet, not a 0%.
  private def matchRateCard(summary: ReportsSummaryPayload, trend: Option[TrendReportPayload]): HtmlElement =
    val rated    = summary.matchRates.filter(_.matchRatePercent.isDefined)
    val excluded = summary.matchRates.filterNot(_.matchRatePercent.isDefined)
    val totalReconciled = rated.map(_.reconciledMovementCount).sum
    val totalMovements  = rated.map(_.totalMovementCount).sum

    div(
      cls := s"${Card.cls} p-4",
      h3(cls := "font-medium mb-1 text-md-on-surface dark:text-white", "Match rate"),
      div(
        cls := "text-sm text-md-on-surface-variant dark:text-gray-400 mb-3",
        if rated.isEmpty then "No accounts with movements to measure"
        else
          val pct = BigDecimal(totalReconciled) / BigDecimal(totalMovements) * 100
          s"${pct.setScale(1, BigDecimal.RoundingMode.HALF_UP)}% matched ($totalReconciled of $totalMovements movements)"
      ),
      ChartCanvas(
        "bar",
        Val(Series(rated.map(_.accountName), rated.flatMap(_.matchRatePercent).map(_.toDouble))),
        horizontal = true
      ),
      if excluded.nonEmpty then
        div(
          cls := "text-xs text-md-on-surface-variant dark:text-gray-400 mt-2",
          s"Excluded (no movements): ${excluded.map(_.accountName).mkString(", ")}"
        )
      else div(),
      trendChart(
        "Match rate over time",
        trend.map(t =>
          t.points.filter(_.avgMatchRatePercent.isDefined).map(p => p.date -> p.avgMatchRatePercent.get.toDouble)
        )
      )
    )

  // reconciledBalance/balance*100 — a $ coverage ratio, not a rate of anything (can exceed 100%
  // since balance is a running net position that shrinks as things settle, while reconciledBalance
  // only ever grows). Kept as a separate metric from the real match rate above.
  private def balanceCoverageCard(summary: ReportsSummaryPayload, trend: Option[TrendReportPayload]): HtmlElement =
    val rated    = summary.matchRates.filter(_.balanceCoveragePercent.isDefined)
    val excluded = summary.matchRates.filterNot(_.balanceCoveragePercent.isDefined)
    val avg      = if rated.isEmpty then None else Some(rated.flatMap(_.balanceCoveragePercent).sum / rated.size)

    div(
      cls := s"${Card.cls} p-4",
      h3(cls := "font-medium mb-1 text-md-on-surface dark:text-white", "Balance coverage"),
      div(
        cls := "text-sm text-md-on-surface-variant dark:text-gray-400 mb-3",
        avg match
          case Some(a) =>
            s"${a.setScale(1, BigDecimal.RoundingMode.HALF_UP)}% reconciled across ${rated.size} account(s)"
          case None => "No reconciling accounts with a balance to measure"
      ),
      ChartCanvas(
        "bar",
        Val(Series(rated.map(_.accountName), rated.flatMap(_.balanceCoveragePercent).map(_.toDouble))),
        horizontal = true
      ),
      if excluded.nonEmpty then
        div(
          cls := "text-xs text-md-on-surface-variant dark:text-gray-400 mt-2",
          s"Excluded (zero balance): ${excluded.map(_.accountName).mkString(", ")}"
        )
      else div(),
      trendChart(
        "Balance coverage over time",
        trend.map(t =>
          t.points
            .filter(_.avgBalanceCoveragePercent.isDefined)
            .map(p => p.date -> p.avgBalanceCoveragePercent.get.toDouble)
        )
      )
    )

  private def exceptionCard(summary: ReportsSummaryPayload, trend: Option[TrendReportPayload]): HtmlElement =
    val categoryLabels =
      Seq(
        "AmountMismatch",
        "MissingCounterpart",
        "Duplicate",
        "TimingDifference",
        "DataQuality",
        "FileMissing",
        "UnroutedRecord",
        "ExcessiveParseErrors"
      )
    val categoryValues = categoryLabels.map(c => summary.exceptions.byCategory.getOrElse(c, 0).toDouble)
    val statusLabels   = Seq("New", "Investigating", "Escalated")

    div(
      cls := s"${Card.cls} p-4",
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
  // out of the chart/legend entirely — with 6 possible categories, most ledgers only ever see
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
      cls := s"${Card.cls} p-4",
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
      ),
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
