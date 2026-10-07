package anseliq.adapter.driving

import anseliq.common.http.HttpPath
import anseliq.common.model.*
import anseliq.domain.model.*
import anseliq.domain.service.*
import com.augustnagro.magnum.*
import upickle.default.*

import java.util.UUID

class ReportRoutes(
  transactor:       Transactor,
  reportingService: ReportingService
) extends ApiRoutes:

  // Reports
  @cask.route(s"${HttpPath.reports}/summary", methods = HttpCmd.GET)
  def getReportsSummary(fundFlowId: Option[String] = None): String =
    transact(transactor):
      val report = reportingService.summary(fundFlowId.map(UUID.fromString))
      write(PayloadMapper.reportsSummaryPayloadFromDomain(report))

  @cask.route(s"${HttpPath.reports}/trend", methods = HttpCmd.GET)
  def getReportsTrend(fundFlowId: Option[String] = None, days: Int = 30): String =
    transact(transactor):
      val report = reportingService.trend(fundFlowId.map(UUID.fromString), days)
      write(PayloadMapper.trendReportPayloadFromDomain(report))

  // Drill-through list backing the aging chart's click-through — same fundFlowId scoping as
  // /reports/summary (omitted -> every fund flow), one row per (recon account, unreconciled
  // movement).
  @cask.route(s"${HttpPath.reports}/unreconciled-movements", methods = HttpCmd.GET)
  def getReportsUnreconciledMovements(fundFlowId: Option[String] = None): String =
    transact(transactor):
      val rows = reportingService.unreconciledMovements(fundFlowId.map(UUID.fromString))
      write(rows.map(PayloadMapper.unreconciledMovementPayloadFromDomain))

  initialize()
