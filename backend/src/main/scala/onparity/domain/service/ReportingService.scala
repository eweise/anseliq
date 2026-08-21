package onparity.domain.service

import onparity.common.model.model.{ID, Money}
import onparity.domain.model.*
import onparity.domain.port.driven.*

import java.time.LocalDate
import java.time.temporal.ChronoUnit

class ReportingService(using
  accountRepo:        AccountRepo,
  disputeRepo:        DisputeRepo,
  movementRepo:       MovementRepo,
  reconRepo:          ReconciliationRepo,
  reconSpecRepo:      ReconciliationSpecRepo,
  fundFlowConfigRepo: FundFlowConfigRepo,
  dailySnapshotRepo:  DailySnapshotRepo,
  clock:              ClockService
):
  private val agingBucketOrder = Seq("0-3", "4-7", "8-14", "15+")

  def summary(fundFlowId: Option[ID]): Txn[ReportsSummaryReport] =
    fundFlowId.foreach(id => fundFlowConfigRepo.findById(id).ErrIfNotFound("fund flow", id))

    val accounts = fundFlowId match
      case Some(id) => accountRepo.findByFundFlow(id)
      case None     => accountRepo.findAll()
    val reconAccounts = accounts.filter(_.isRecon)

    ReportsSummaryReport(
      matchRates = matchRates(reconAccounts),
      exceptions = exceptionBreakdown(fundFlowId, accounts.map(_.id).toSet),
      aging      = agingBuckets(reconAccounts)
    )

  private def matchRates(reconAccounts: Vector[Account]): Seq[MatchRateReport] =
    reconAccounts.map { a =>
      MatchRateReport(a.id, a.name, a.balance, a.reconciledBalance, matchRatePercent(a.balance, a.reconciledBalance))
    }

  private def matchRatePercent(balance: Money, reconciledBalance: Money): Option[BigDecimal] =
    if balance.signum == 0 then None
    else
      val ratio = BigDecimal(
        reconciledBalance.bigDecimal.divide(balance.bigDecimal, 10, java.math.RoundingMode.HALF_UP)
      )
      Some((ratio * 100).setScale(2, BigDecimal.RoundingMode.HALF_UP))

  private def exceptionBreakdown(fundFlowId: Option[ID], accountIdsInScope: Set[ID]): Txn[ExceptionBreakdownReport] =
    val allOpen = disputeRepo.findAll.filter(_.status != DisputeStatus.Resolved)
    val inScope = fundFlowId match
      case None    => allOpen
      case Some(_) => allOpen.filter(d => disputeAccountId(d).exists(accountIdsInScope.contains))

    ExceptionBreakdownReport(
      byCategory = inScope.groupBy(_.category).map((k, v) => k.toString -> v.size),
      byStatus   = inScope.groupBy(_.status).map((k, v) => k.toString -> v.size)
    )

  private def disputeAccountId(d: Dispute): Txn[Option[ID]] =
    d.targetType match
      case DisputeTarget.Movement =>
        movementRepo.findById(d.referenceId).map(_.inAccountId)
      case DisputeTarget.Reconciliation =>
        for
          recon <- reconRepo.findById(d.referenceId)
          spec  <- reconSpecRepo.findById(recon.specId)
        yield spec.accountId

  private def agingBuckets(reconAccounts: Vector[Account]): Txn[AgingBucketsReport] =
    val counts  = scala.collection.mutable.Map(agingBucketOrder.map(_ -> 0)*)
    var overdue = 0

    reconAccounts.foreach { account =>
      val agingLimit = reconSpecRepo.findByAccountID(account.id).headOption.map(_.agingLimit)
      movementRepo.findUnReconciledByAccount(account.id).foreach { m =>
        val ageDays = ChronoUnit.DAYS.between(m.createdAt.get, clock.now)
        val bucket =
          if ageDays <= 3 then "0-3"
          else if ageDays <= 7 then "4-7"
          else if ageDays <= 14 then "8-14"
          else "15+"
        counts(bucket) += 1
        agingLimit.foreach(limit => if ageDays > limit then overdue += 1)
      }
    }

    AgingBucketsReport(agingBucketOrder.map(b => b -> counts(b)), overdue)

  // Storage shape for the match-rates snapshot blob — mirrors MatchRatePayload (String account id,
  // since ID/UUID has no upickle ReadWriter in this codebase) but stays in the domain layer rather
  // than reaching into adapter/driving's Payload conversions.
  private case class MatchRateSnapshot(
    accountId:         String,
    accountName:       String,
    balance:           Money,
    reconciledBalance: Money,
    matchRatePercent:  Option[BigDecimal]
  ) derives upickle.default.ReadWriter

  /**
   * Computes today's summary() for every fund flow and upserts one row per fund flow into
   * daily_snapshot, keyed by (fundFlowId, today). Safe to call more than once on the same day — the
   * upsert overwrites that day's row rather than duplicating it.
   */
  def computeAndStoreDailySnapshots(): Txn[Int] =
    val today     = clock.now.toLocalDate
    val fundFlows = fundFlowConfigRepo.findAll

    for ff <- fundFlows do
      val report = summary(Some(ff.id))
      val rated  = report.matchRates.flatMap(m => m.matchRatePercent.map(pct => pct -> m))
      val avgMatchRate =
        if rated.isEmpty then None
        else Some((rated.map(_._1).sum / rated.size).setScale(2, BigDecimal.RoundingMode.HALF_UP))
      val agingByBucket = report.aging.buckets.toMap

      dailySnapshotRepo.upsert(
        DailySnapshot(
          fundFlowId   = ff.id,
          snapshotDate = today,
          matchRatesJson = upickle.write(
            report.matchRates.map(m =>
              MatchRateSnapshot(m.accountId.toString, m.accountName, m.balance, m.reconciledBalance, m.matchRatePercent)
            )
          ),
          avgMatchRatePercent      = avgMatchRate,
          ratedAccountCount        = rated.size,
          exceptionsByCategoryJson = upickle.write(report.exceptions.byCategory),
          exceptionsByStatusJson   = upickle.write(report.exceptions.byStatus),
          openExceptionCount       = report.exceptions.byStatus.values.sum,
          agingZeroToThree         = agingByBucket.getOrElse("0-3", 0),
          agingFourToSeven         = agingByBucket.getOrElse("4-7", 0),
          agingEightToFourteen     = agingByBucket.getOrElse("8-14", 0),
          agingFifteenPlus         = agingByBucket.getOrElse("15+", 0),
          overdueCount             = report.aging.overdueCount
        )
      )

    fundFlows.size

  /**
   * Reads back the last `days` days of stored snapshots. When fundFlowId is None, aggregates across
   * every fund flow's snapshot for each date: counts sum, and avgMatchRatePercent is a weighted
   * average (weighted by each fund flow's ratedAccountCount that day) — equivalent to averaging
   * matchRatePercent over every rated account across all fund flows.
   */
  def trend(fundFlowId: Option[ID], days: Int = 30): Txn[TrendReport] =
    val today = clock.now.toLocalDate
    val from  = today.minusDays(days - 1)

    val scopeIds = fundFlowId match
      case Some(id) => Vector(id)
      case None     => fundFlowConfigRepo.findAll.map(_.id)

    val rows = scopeIds.flatMap(id => dailySnapshotRepo.findTrend(id, from, today))

    TrendReport(ReportingService.aggregateSnapshotsByDate(rows))

object ReportingService:

  // Pure, DB-free so it's cheap to unit test the weighted-average math directly. Groups arbitrary
  // (possibly multi-fund-flow) snapshot rows by date, summing the count fields and weighted-averaging
  // avgMatchRatePercent by ratedAccountCount — equivalent to averaging matchRatePercent over every
  // rated account across all snapshotted fund flows for that date.
  private[service] def aggregateSnapshotsByDate(rows: Seq[DailySnapshot]): Seq[TrendPoint] =
    rows
      .groupBy(_.snapshotDate)
      .toSeq
      .sortBy(_._1)
      .map { case (date, dayRows) =>
        val weightedRated   = dayRows.flatMap(r => r.avgMatchRatePercent.map(pct => pct -> r.ratedAccountCount))
        val totalRatedCount = weightedRated.map(_._2).sum
        val avgMatchRate =
          if totalRatedCount == 0 then None
          else
            Some(
              (weightedRated.map((pct, count) => pct * count).sum / totalRatedCount)
                .setScale(2, BigDecimal.RoundingMode.HALF_UP)
            )

        TrendPoint(
          date                = date,
          avgMatchRatePercent = avgMatchRate,
          openExceptionCount  = dayRows.map(_.openExceptionCount).sum,
          unreconciledBacklogCount =
            dayRows.map(r => r.agingZeroToThree + r.agingFourToSeven + r.agingEightToFourteen + r.agingFifteenPlus).sum,
          overdueCount = dayRows.map(_.overdueCount).sum
        )
      }
