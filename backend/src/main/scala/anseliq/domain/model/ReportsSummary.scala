package anseliq.domain.model

import anseliq.common.model.model.{ID, Money}

import java.time.{LocalDate, OffsetDateTime}

case class MatchRateReport(
  accountId:               ID,
  accountName:             String,
  balance:                 Money,
  reconciledBalance:       Money,
  balanceCoveragePercent:  Option[BigDecimal], // reconciledBalance/balance*100 — None when balance == 0.
  // balance is a running net position that shrinks as things settle while reconciledBalance only
  // ever grows, so this can legitimately exceed 100% — it's not a rate of anything, just a $
  // coverage ratio. matchRatePercent below is the real, count-based match rate.
  reconciledMovementCount: Int,
  totalMovementCount:      Int,
  matchRatePercent:        Option[BigDecimal] // reconciledMovementCount/totalMovementCount*100 — None when totalMovementCount == 0
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
  date:                      LocalDate,
  avgBalanceCoveragePercent: Option[BigDecimal],
  avgMatchRatePercent:       Option[BigDecimal],
  openExceptionCount:        Int,
  openExceptionsByCategory:  Map[String, Int], // DisputeCategory.toString -> count, summed across fund flows in scope
  unreconciledBacklogCount:  Int,              // sum of the four aging buckets
  overdueCount:              Int
)

case class TrendReport(points: Seq[TrendPoint])
