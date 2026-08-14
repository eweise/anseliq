package onparity.domain.model

import onparity.common.model.model.{ID, Money}

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
