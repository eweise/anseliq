package onparity.domain.model

import onparity.common.model.model.{ID, Money}

import java.time.{LocalDate, OffsetDateTime}

case class MatchRateReport(
  accountId:         ID,
  accountName:       String,
  balance:           Money,
  reconciledBalance: Money,
  matchRatePercent:  Option[BigDecimal] // None when balance == 0
)

case class ExceptionBreakdownReport(
  byCategory: Map[String, Int], // DisputeCategory.toString -> count, open disputes only
  byStatus:   Map[String, Int]  // DisputeStatus.toString -> count, open disputes only
)

case class AgingBucketsReport(
  buckets:      Seq[(String, Int)], // ("0-3", "4-7", "8-14", "15+") -> count, in that fixed order
  overdueCount: Int
)

case class ReportsSummaryReport(
  matchRates: Seq[MatchRateReport],
  exceptions: ExceptionBreakdownReport,
  aging:      AgingBucketsReport
)

// Row shape backing the aging chart's drill-through list. accountId/accountName is the recon
// account this movement is counted against in agingBuckets (a movement touching two recon
// accounts appears once per account, same double-counting agingBuckets itself already has — kept
// consistent so the list's per-bucket totals match what the chart shows).
case class UnreconciledMovementDetail(
  movementId:  ID,
  referenceId: ID,
  amount:      Money,
  accountId:   ID,
  accountName: String,
  ageDays:     Long,
  agingBucket: String,
  createdAt:   OffsetDateTime
)

case class TrendPoint(
  date:                     LocalDate,
  avgMatchRatePercent:      Option[BigDecimal],
  openExceptionCount:       Int,
  openExceptionsByCategory: Map[String, Int], // DisputeCategory.toString -> count, summed across fund flows in scope
  unreconciledBacklogCount: Int,              // sum of the four aging buckets
  overdueCount:             Int
)

case class TrendReport(points: Seq[TrendPoint])
