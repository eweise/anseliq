package anseliq.domain.service

import anseliq.common.model.model.{ID, Money}
import anseliq.domain.model.*
import anseliq.domain.port.driven.*

import java.time.temporal.ChronoUnit

class ReportingService(
  accountRepo:       AccountRepo,
  exceptionRepo:     ExceptionRepo,
  movementRepo:      MovementRepo,
  reconRepo:         ReconciliationRepo,
  reconSpecRepo:     ReconciliationSpecRepo,
  movementSpecRepo:  MovementSpecRepo,
  ledgerRepo:        LedgerRepo,
  dailySnapshotRepo: DailySnapshotRepo,
  clock:             ClockService
):
  private val agingBucketOrder = Seq("0-3", "4-7", "8-14", "15+")

  def summary(ledgerId: Option[ID]): Txn[ReportsSummaryReport] =
    ledgerId.foreach(id => ledgerRepo.findById(id).ErrIfNotFound("ledger", id))

    val accounts = ledgerId match
      case Some(id) => accountRepo.findByLedger(id)
      case None     => accountRepo.findAll()
    val reconAccounts = accounts.filter(_.accountType == AccountType.Recon)

    ReportsSummaryReport(
      matchRates = matchRates(reconAccounts),
      exceptions = exceptionBreakdown(ledgerId, accounts.map(_.id).toSet),
      aging      = agingBuckets(reconAccounts)
    )

  private def matchRates(reconAccounts: Vector[Account]): Txn[Seq[MatchRateReport]] =
    reconAccounts.map { a =>
      val total        = movementRepo.countByAccount(a.id)
      val unreconciled = movementRepo.countUnreconciledByAccount(a.id)
      val reconciled   = total - unreconciled
      MatchRateReport(
        accountId               = a.id,
        accountName             = a.name,
        balance                 = a.balance,
        reconciledBalance       = a.reconciledBalance,
        balanceCoveragePercent  = balanceCoveragePercent(a.balance, a.reconciledBalance),
        reconciledMovementCount = reconciled,
        totalMovementCount      = total,
        matchRatePercent        = movementMatchRatePercent(reconciled, total)
      )
    }

  // reconciledBalance/balance*100 — a $ coverage ratio, not a rate of anything. balance is a
  // running net position that shrinks toward zero as things settle while reconciledBalance only
  // ever grows, so this can legitimately exceed 100%.
  private def balanceCoveragePercent(balance: Money, reconciledBalance: Money): Option[BigDecimal] =
    if balance.signum == 0 then None
    else
      val ratio = BigDecimal(
        reconciledBalance.bigDecimal.divide(balance.bigDecimal, 10, java.math.RoundingMode.HALF_UP)
      )
      Some((ratio * 100).setScale(2, BigDecimal.RoundingMode.HALF_UP))

  // The real match rate: how many of an account's movements have actually been reconciled, as a
  // true 0-100% ratio — "reconciled" = reconciliation_id IS NOT NULL, same definition used
  // everywhere else in the codebase (e.g. movement search's `reconciled` flag).
  private def movementMatchRatePercent(reconciled: Int, total: Int): Option[BigDecimal] =
    if total == 0 then None
    else
      val ratio = BigDecimal(reconciled) / BigDecimal(total)
      Some((ratio * 100).setScale(2, BigDecimal.RoundingMode.HALF_UP))

  private def exceptionBreakdown(ledgerId: Option[ID], accountIdsInScope: Set[ID]): Txn[ExceptionBreakdownReport] =
    val allOpen = exceptionRepo.findAll.filter(_.status != ExceptionStatus.Resolved)
    val inScope = ledgerId match
      case None    => allOpen
      case Some(_) => allOpen.filter(d => exceptionAccountId(d).exists(accountIdsInScope.contains))

    ExceptionBreakdownReport(
      byCategory = inScope.groupBy(_.category).map((k, v) => k.toString -> v.size),
      byStatus   = inScope.groupBy(_.status).map((k, v) => k.toString -> v.size)
    )

  private def exceptionAccountId(d: ExceptionCase): Txn[Option[ID]] =
    d.targetType match
      case ExceptionTarget.Movement =>
        movementRepo.findById(d.referenceId).map(_.inAccountId)
      case ExceptionTarget.Reconciliation =>
        for
          recon  <- reconRepo.findById(d.referenceId)
          specId <- recon.specId
          spec   <- reconSpecRepo.findById(specId)
        yield spec.accountId
      case ExceptionTarget.MovementSpec =>
        movementSpecRepo.findById(d.referenceId).map(_.inAccountId)
      case ExceptionTarget.FileFeed =>
        // A feed can route to several accounts (a multi-account BAI-style feed), so there's no
        // single owning account — scope by whichever spec happens to be first, same best-effort
        // fallback as an empty result: a feed with zero specs configured yet just has no account
        // to scope by, and the exception is only visible in the unscoped (all ledgers) view.
        movementSpecRepo.findByFileFeedId(d.referenceId).headOption.map(_.inAccountId)

  private def agingBucketFor(ageDays: Long): String =
    if ageDays <= 3 then "0-3"
    else if ageDays <= 7 then "4-7"
    else if ageDays <= 14 then "8-14"
    else "15+"

  private def agingBuckets(reconAccounts: Vector[Account]): Txn[AgingBucketsReport] =
    val counts  = scala.collection.mutable.Map(agingBucketOrder.map(_ -> 0)*)
    var overdue = 0

    reconAccounts.foreach { account =>
      val agingLimit = reconSpecRepo.findByAccountId(account.id).headOption.map(_.agingLimit)
      movementRepo.findUnreconciledByAccount(account.id).foreach { m =>
        val ageDays = ChronoUnit.DAYS.between(m.createdAt.get, clock.now)
        counts(agingBucketFor(ageDays)) += 1
        agingLimit.foreach(limit => if ageDays > limit then overdue += 1)
      }
    }

    AgingBucketsReport(agingBucketOrder.map(b => b -> counts(b)), overdue)

  // Drill-through row list backing the aging chart on the Dashboard/Reports pages — same account
  // scoping (Recon-typed accounts, optionally narrowed to one ledger) and the same per-account
  // iteration as agingBuckets above, so a bucket's count here always matches the chart.
  def unreconciledMovements(ledgerId: Option[ID]): Txn[Seq[UnreconciledMovementDetail]] =
    ledgerId.foreach(id => ledgerRepo.findById(id).ErrIfNotFound("ledger", id))

    val accounts = ledgerId match
      case Some(id) => accountRepo.findByLedger(id)
      case None     => accountRepo.findAll()
    val reconAccounts = accounts.filter(_.accountType == AccountType.Recon)

    reconAccounts.flatMap { account =>
      movementRepo.findUnreconciledByAccount(account.id).map { m =>
        val ageDays = ChronoUnit.DAYS.between(m.createdAt.get, clock.now)
        UnreconciledMovementDetail(
          movementId  = m.id,
          referenceId = m.referenceId,
          amount      = m.amount,
          accountId   = account.id,
          accountName = account.name,
          ageDays     = ageDays,
          agingBucket = agingBucketFor(ageDays),
          createdAt   = m.createdAt.get
        )
      }
    }

  // Storage shape for the match-rates snapshot blob — mirrors MatchRatePayload (String account id,
  // since ID/UUID has no upickle ReadWriter in this codebase) but stays in the domain layer rather
  // than reaching into adapter/driving's Payload conversions.
  private case class MatchRateSnapshot(
    accountId:               String,
    accountName:             String,
    balance:                 Money,
    reconciledBalance:       Money,
    balanceCoveragePercent:  Option[BigDecimal],
    reconciledMovementCount: Int,
    totalMovementCount:      Int,
    matchRatePercent:        Option[BigDecimal]
  ) derives upickle.default.ReadWriter

  /**
   * Meant to be polled frequently (e.g. every minute) rather than run once on a fixed schedule,
   * since "the right time to snapshot" depends on each ledger's own dailyCutoffHours, not on when
   * the app happened to start. For each ledger, today's snapshot becomes due once clock.now's
   * time-of-day passes that ledger's cutoff hour — letting a ledger that expects late-arriving data
   * delay its snapshot instead of capturing a partial day. A ledger already snapshotted for today
   * is skipped (checked via existsForDate) so a 1-minute poll cadence doesn't recompute summary()
   * every minute for the rest of the day once it's already run. Doesn't backfill: a period whose
   * cutoff passed while the app was down is never generated retroactively, only today's is ever
   * considered.
   */
  def computeAndStoreDailySnapshots(): Txn[Int] =
    val now   = clock.now
    val today = now.toLocalDate

    val dueLedgers = ledgerRepo.findAll.filter { ledger =>
      val cutoff = today.atStartOfDay.atOffset(now.getOffset).plusHours(ledger.dailyCutoffHours)
      !now.isBefore(cutoff) && !dailySnapshotRepo.existsForDate(ledger.id, today)
    }

    dueLedgers.foreach(storeDailySnapshot)
    dueLedgers.size

  /** Computes and stores (or replaces) the ledger's snapshot for clock.now's date. */
  def storeDailySnapshot(ledger: Ledger): Txn[Unit] =
    val today  = clock.now.toLocalDate
    val report = summary(Some(ledger.id))
    val rated  = report.matchRates.flatMap(m => m.balanceCoveragePercent.map(pct => pct -> m))
    val avgBalanceCoverage =
      if rated.isEmpty then None
      else Some((rated.map(_._1).sum / rated.size).setScale(2, BigDecimal.RoundingMode.HALF_UP))
    // Ratio of sums, not an average of per-account percentages — the correct way to combine
    // counts across accounts (an account with 1000 movements shouldn't count the same as one
    // with 2 when computing the ledger's overall match rate).
    val totalReconciled = report.matchRates.map(_.reconciledMovementCount).sum
    val totalMovements  = report.matchRates.map(_.totalMovementCount).sum
    val avgMatchRate =
      if totalMovements == 0 then None
      else
        Some(
          (BigDecimal(totalReconciled) / BigDecimal(totalMovements) * 100)
            .setScale(2, BigDecimal.RoundingMode.HALF_UP)
        )
    val agingByBucket = report.aging.buckets.toMap

    dailySnapshotRepo.upsert(
      DailySnapshot(
        ledgerId     = ledger.id,
        snapshotDate = today,
        matchRatesJson = upickle.write(
          report.matchRates.map(m =>
            MatchRateSnapshot(
              m.accountId.toString,
              m.accountName,
              m.balance,
              m.reconciledBalance,
              m.balanceCoveragePercent,
              m.reconciledMovementCount,
              m.totalMovementCount,
              m.matchRatePercent
            )
          )
        ),
        avgBalanceCoveragePercent = avgBalanceCoverage,
        avgMatchRatePercent       = avgMatchRate,
        ratedAccountCount         = rated.size,
        reconciledMovementCount   = totalReconciled,
        totalMovementCount        = totalMovements,
        exceptionsByCategoryJson  = upickle.write(report.exceptions.byCategory),
        exceptionsByStatusJson    = upickle.write(report.exceptions.byStatus),
        openExceptionCount        = report.exceptions.byStatus.values.sum,
        agingZeroToThree          = agingByBucket.getOrElse("0-3", 0),
        agingFourToSeven          = agingByBucket.getOrElse("4-7", 0),
        agingEightToFourteen      = agingByBucket.getOrElse("8-14", 0),
        agingFifteenPlus          = agingByBucket.getOrElse("15+", 0),
        overdueCount              = report.aging.overdueCount
      )
    )

  /**
   * Reads back the last `days` days of stored snapshots. When ledgerId is None, aggregates across
   * every ledger's snapshot for each date: counts sum, avgBalanceCoveragePercent is a weighted
   * average (weighted by each ledger's ratedAccountCount that day) — equivalent to averaging
   * balanceCoveragePercent over every rated account across all ledgers — and avgMatchRatePercent is
   * a ratio of summed reconciled/total movement counts across all ledgers.
   */
  def trend(ledgerId: Option[ID], days: Int = 30): Txn[TrendReport] =
    val today = clock.now.toLocalDate
    val from  = today.minusDays(days - 1)

    val scopeIds = ledgerId match
      case Some(id) => Vector(id)
      case None     => ledgerRepo.findAll.map(_.id)

    val rows = scopeIds.flatMap(id => dailySnapshotRepo.findTrend(id, from, today))

    TrendReport(ReportingService.aggregateSnapshotsByDate(rows))

object ReportingService:

  // Pure, DB-free so it's cheap to unit test the weighted-average math directly. Groups arbitrary
  // (possibly multi-ledger) snapshot rows by date, summing the count fields, weighted-averaging
  // avgBalanceCoveragePercent by ratedAccountCount (equivalent to averaging balanceCoveragePercent
  // over every rated account across all snapshotted ledgers for that date), and deriving
  // avgMatchRatePercent as a ratio of summed reconciledMovementCount/totalMovementCount (not a
  // weighted average of percentages — the correct way to combine counts).
  private[service] def aggregateSnapshotsByDate(rows: Seq[DailySnapshot]): Seq[TrendPoint] =
    rows
      .groupBy(_.snapshotDate)
      .toSeq
      .sortBy(_._1)
      .map { case (date, dayRows) =>
        val weightedRated   = dayRows.flatMap(r => r.avgBalanceCoveragePercent.map(pct => pct -> r.ratedAccountCount))
        val totalRatedCount = weightedRated.map(_._2).sum
        val avgBalanceCoverage =
          if totalRatedCount == 0 then None
          else
            Some(
              (weightedRated.map((pct, count) => pct * count).sum / totalRatedCount)
                .setScale(2, BigDecimal.RoundingMode.HALF_UP)
            )

        val totalReconciled = dayRows.map(_.reconciledMovementCount).sum
        val totalMovements  = dayRows.map(_.totalMovementCount).sum
        val avgMatchRate =
          if totalMovements == 0 then None
          else
            Some(
              (BigDecimal(totalReconciled) / BigDecimal(totalMovements) * 100)
                .setScale(2, BigDecimal.RoundingMode.HALF_UP)
            )

        val byCategory = dayRows
          .flatMap(r => upickle.read[Map[String, Int]](r.exceptionsByCategoryJson))
          .groupMapReduce(_._1)(_._2)(_ + _)

        TrendPoint(
          date                      = date,
          avgBalanceCoveragePercent = avgBalanceCoverage,
          avgMatchRatePercent       = avgMatchRate,
          openExceptionCount        = dayRows.map(_.openExceptionCount).sum,
          openExceptionsByCategory  = byCategory,
          unreconciledBacklogCount =
            dayRows.map(r => r.agingZeroToThree + r.agingFourToSeven + r.agingEightToFourteen + r.agingFifteenPlus).sum,
          overdueCount = dayRows.map(_.overdueCount).sum
        )
      }
