package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.HttpPath
import onparity.common.model.{FundFlowPayload, ReportsSummaryPayload}
import onparity.http.ApiClient
import onparity.state.{AppState, SessionStore}
import onparity.view.components.ChartCanvas.Series
import onparity.view.components.{ChartCanvas, Form, PageHeader, Pill}
import upickle.default.*

object ReportsPage:

  case class State(
    fundFlows: Seq[FundFlowPayload]          = Seq.empty,
    summary:   Option[ReportsSummaryPayload] = None
  )

  def apply(): HtmlElement =
    val state            = Var(State())
    val loadFundFlowsBus = EventBus[Unit]()
    val loadSummaryBus   = EventBus[Option[String]]()

    def loadFundFlows() =
      ApiClient.get[Seq[FundFlowPayload]](HttpPath.url(HttpPath.fundFlows), SessionStore.token)

    def loadSummary(fundFlowId: Option[String]) =
      val query = fundFlowId.map(id => s"?fundFlowId=$id").getOrElse("")
      ApiClient.get[ReportsSummaryPayload](HttpPath.url(s"${HttpPath.reports}/summary$query"), SessionStore.token)

    div(
      loadFundFlowsBus.events.flatMapSwitch(_ => loadFundFlows()) --> {
        case Right(ffs) => state.update(_.copy(fundFlows = ffs))
        case Left(e)    => AppState.globalError.set(Some(e))
      },
      loadSummaryBus.events.flatMapSwitch(loadSummary) --> {
        case Right(s) => state.update(_.copy(summary = Some(s)))
        case Left(e)  => AppState.globalError.set(Some(e))
      },
      onMountCallback { _ =>
        loadFundFlowsBus.emit(())
        loadSummaryBus.emit(None)
      },
      cls := "flex flex-col items-center mb-8 pt-20",
      div(
        cls := "my-8 w-full max-w-5xl",
        div(
          cls := "flex justify-between items-center mb-6",
          PageHeader.title("Reports & Analytics"),
          child <-- state.signal.map(s => fundFlowSelect(s.fundFlows, loadSummaryBus))
        ),
        child <-- state.signal.map {
          case State(_, None)          => div()
          case State(_, Some(summary)) => widgets(summary)
        }
      )
    )

  private def fundFlowSelect(fundFlows: Seq[FundFlowPayload], loadSummaryBus: EventBus[Option[String]]): HtmlElement =
    val options = ("", "All fund flows") +: fundFlows.map(ff => ff.id.getOrElse("") -> ff.name)
    div(
      cls := "w-64",
      Form.selectField("fundFlowFilter", "Fund flow", options, "", v => loadSummaryBus.emit(Option(v).filter(_.nonEmpty)))
    )

  private def widgets(summary: ReportsSummaryPayload): HtmlElement =
    div(
      cls := "grid grid-cols-1 gap-6",
      matchRateCard(summary),
      exceptionCard(summary),
      agingCard(summary)
    )

  private def matchRateCard(summary: ReportsSummaryPayload): HtmlElement =
    val rated    = summary.matchRates.filter(_.matchRatePercent.isDefined)
    val excluded = summary.matchRates.filterNot(_.matchRatePercent.isDefined)
    val avg      = if rated.isEmpty then None else Some(rated.flatMap(_.matchRatePercent).sum / rated.size)

    div(
      cls := "border border-md-outline rounded-lg p-4",
      h3(cls := "font-medium mb-1 text-md-on-surface dark:text-white", "Match rate"),
      div(
        cls := "text-sm text-md-on-surface-variant dark:text-gray-400 mb-3",
        avg match
          case Some(a) => s"${a.setScale(1, BigDecimal.RoundingMode.HALF_UP)}% reconciled across ${rated.size} account(s)"
          case None    => "No reconciling accounts with a balance to measure"
      ),
      ChartCanvas("bar", Val(Series(rated.map(_.accountName), rated.flatMap(_.matchRatePercent).map(_.toDouble))), horizontal = true),
      if excluded.nonEmpty then
        div(
          cls := "text-xs text-md-on-surface-variant dark:text-gray-400 mt-2",
          s"Excluded (zero balance): ${excluded.map(_.accountName).mkString(", ")}"
        )
      else div()
    )

  private def exceptionCard(summary: ReportsSummaryPayload): HtmlElement =
    val categoryLabels = Seq("AmountMismatch", "MissingCounterpart", "Duplicate", "TimingDifference", "DataQuality")
    val categoryValues = categoryLabels.map(c => summary.exceptions.byCategory.getOrElse(c, 0).toDouble)
    val statusLabels    = Seq("New", "Investigating", "Escalated")

    div(
      cls := "border border-md-outline rounded-lg p-4",
      h3(cls := "font-medium mb-3 text-md-on-surface dark:text-white", "Open exceptions by category"),
      ChartCanvas("doughnut", Val(Series(categoryLabels, categoryValues))),
      div(
        cls := "flex gap-2 mt-3 flex-wrap",
        statusLabels.map(s => Pill(s"$s: ${summary.exceptions.byStatus.getOrElse(s, 0)}", statusTone(s)))
      )
    )

  private def statusTone(status: String): Pill.Tone = status match
    case "New"           => Pill.Tone.Accent
    case "Investigating" => Pill.Tone.Warning
    case "Escalated"     => Pill.Tone.Critical
    case _                => Pill.Tone.Neutral

  private def agingCard(summary: ReportsSummaryPayload): HtmlElement =
    div(
      cls := "border border-md-outline rounded-lg p-4",
      h3(cls := "font-medium mb-1 text-md-on-surface dark:text-white", "Unreconciled aging"),
      div(
        cls := "mb-3",
        Pill(s"${summary.aging.overdueCount} overdue", if summary.aging.overdueCount > 0 then Pill.Tone.Critical else Pill.Tone.Good)
      ),
      ChartCanvas("bar", Val(Series(summary.aging.buckets.map(_._1), summary.aging.buckets.map(_._2.toDouble))))
    )
