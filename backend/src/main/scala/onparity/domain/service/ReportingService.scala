package onparity.domain.service

import onparity.common.model.model.{ID, Money}
import onparity.domain.model.*
import onparity.domain.port.driven.*

import java.time.temporal.ChronoUnit

class ReportingService(using
  accountRepo:       AccountRepo,
  disputeRepo:        DisputeRepo,
  movementRepo:       MovementRepo,
  reconRepo:          ReconciliationRepo,
  reconSpecRepo:      ReconciliationSpecRepo,
  fundFlowConfigRepo: FundFlowConfigRepo,
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
      val ratio = BigDecimal(reconciledBalance.bigDecimal.divide(balance.bigDecimal, 10, java.math.RoundingMode.HALF_UP))
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
