package onparity.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import onparity.adapter.driven.DbDataSource
import onparity.common.model.model.ID
import onparity.domain.DataSetup
import onparity.domain.model.*
import onparity.domain.model.helper.JSONBProps
import onparity.domain.port.driven.Txn
import onparity.testing.rollBackOnly

import javax.sql.DataSource

class ReportingServiceSuite extends munit.FunSuite:
  given transactor: Transactor = Transactor(dataSource)
  given dataSource: DataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))

  def createMovement(inAccountId: ID, outAccountId: ID, movementSpecId: ID, amount: BigDecimal)(using data: DataSetup): Txn[Movement] =
    val m = Movement(
      inAccountId    = inAccountId,
      outAccountId   = outAccountId,
      movementSpecId = movementSpecId,
      amount         = amount,
      properties     = JSONBProps(Map())
    )
    data.movementRepo.insert(m)
    m

  def createMovementAgedDays(
    inAccountId:    ID,
    outAccountId:   ID,
    movementSpecId: ID,
    ageDays:        Long
  )(using data: DataSetup, clock: ClockService): Txn[Movement] =
    val m    = createMovement(inAccountId, outAccountId, movementSpecId, 100)
    val aged = m.copy(createdAt = Some(clock.now.minusDays(ageDays)))
    data.movementRepo.update(aged)
    aged

  test("matchRates: computes reconciledBalance/balance*100, excludes zero-balance accounts from the ratio"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup    = DataSetup()
        import data.given
        given clock: ClockService = ClockServiceLive()
        val service = ReportingService()

        data.accountRepo.update(data.reconAccount.copy(isRecon = true, balance = 300, reconciledBalance = 100))
        val zeroBalance = Account(name = "zero-balance-recon", fundFlowId = data.fundFlowConfig.id, isRecon = true)
        data.accountRepo.insert(zeroBalance)

        val result = service.summary(None)

        val recon = result.matchRates.find(_.accountId == data.reconAccount.id).get
        assertEquals(recon.matchRatePercent, Some(BigDecimal("33.33")))

        val zero = result.matchRates.find(_.accountId == zeroBalance.id).get
        assertEquals(zero.matchRatePercent, None)

  test("matchRates: only includes accounts where isRecon == true"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup    = DataSetup()
        import data.given
        given clock: ClockService = ClockServiceLive()
        val service = ReportingService()

        // data.reconAccount is isRecon = false by default; data.journalAccount too
        val result = service.summary(None)

        assert(!result.matchRates.exists(_.accountId == data.reconAccount.id))
        assert(!result.matchRates.exists(_.accountId == data.journalAccount.id))

  test("exceptions: groups open disputes by category and status, excludes Resolved"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup    = DataSetup()
        import data.given
        given clock: ClockService = ClockServiceLive()
        val service = ReportingService()

        val m = createMovement(data.journalToRecon.inAccountId, data.journalToRecon.outAccountId, data.journalToRecon.id, 100)
        data.disputeRepo.insert(Dispute(referenceId = m.id, targetType = DisputeTarget.Movement, category = DisputeCategory.AmountMismatch, status = DisputeStatus.New, createdAt = Some(clock.now), updatedAt = Some(clock.now)))
        data.disputeRepo.insert(Dispute(referenceId = m.id, targetType = DisputeTarget.Movement, category = DisputeCategory.Duplicate, status = DisputeStatus.Investigating, createdAt = Some(clock.now), updatedAt = Some(clock.now)))
        data.disputeRepo.insert(Dispute(referenceId = m.id, targetType = DisputeTarget.Movement, category = DisputeCategory.AmountMismatch, status = DisputeStatus.Resolved, createdAt = Some(clock.now), updatedAt = Some(clock.now)))

        // Scoped to this test's own fund flow: the shared dev database this suite runs against also has
        // real dispute data from manual QA testing, which would otherwise leak into an unscoped count.
        val result = service.summary(Some(data.fundFlowConfig.id))

        assertEquals(result.exceptions.byCategory.get("AmountMismatch"), Some(1))
        assertEquals(result.exceptions.byCategory.get("Duplicate"), Some(1))
        assertEquals(result.exceptions.byStatus.get("New"), Some(1))
        assertEquals(result.exceptions.byStatus.get("Investigating"), Some(1))
        assertEquals(result.exceptions.byStatus.get("Resolved"), None)

  test("aging: assigns unreconciled movements to fixed day buckets at each boundary"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup    = DataSetup()
        import data.given
        given clock: ClockService = ClockServiceLive()
        val service = ReportingService()

        data.accountRepo.update(data.reconAccount.copy(isRecon = true))
        val spec = data.journalToRecon

        createMovementAgedDays(spec.inAccountId, spec.outAccountId, spec.id, 3)  // 0-3
        createMovementAgedDays(spec.inAccountId, spec.outAccountId, spec.id, 4)  // 4-7
        createMovementAgedDays(spec.inAccountId, spec.outAccountId, spec.id, 7)  // 4-7
        createMovementAgedDays(spec.inAccountId, spec.outAccountId, spec.id, 8)  // 8-14
        createMovementAgedDays(spec.inAccountId, spec.outAccountId, spec.id, 14) // 8-14
        createMovementAgedDays(spec.inAccountId, spec.outAccountId, spec.id, 15) // 15+

        // Scoped to this test's own fund flow, for the same reason as the exceptions test above.
        val result = service.summary(Some(data.fundFlowConfig.id))
        val buckets = result.aging.buckets.toMap

        assertEquals(buckets("0-3"), 1)
        assertEquals(buckets("4-7"), 2)
        assertEquals(buckets("8-14"), 2)
        assertEquals(buckets("15+"), 1)

  test("aging: overdueCount respects the account's agingLimit; an account with no recon spec is skipped, not thrown"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup    = DataSetup()
        import data.given
        given clock: ClockService = ClockServiceLive()
        val service = ReportingService()

        data.accountRepo.update(data.reconAccount.copy(isRecon = true))
        data.reconSpecRepo.update(data.reconSpec.copy(agingLimit = 5))
        val spec = data.journalToRecon

        createMovementAgedDays(spec.inAccountId, spec.outAccountId, spec.id, 3)  // within limit
        createMovementAgedDays(spec.inAccountId, spec.outAccountId, spec.id, 10) // overdue

        val orphanAccount = Account(name = "orphan-recon", fundFlowId = data.fundFlowConfig.id, isRecon = true)
        data.accountRepo.insert(orphanAccount)
        val orphanSpec = MovementSpec(inAccountId = orphanAccount.id, outAccountId = data.journalAccount.id)
        data.movementSpecRepo.insert(orphanSpec)
        createMovementAgedDays(orphanAccount.id, data.journalAccount.id, orphanSpec.id, 20) // no recon spec exists for orphanAccount

        // Scoped to this test's own fund flow, for the same reason as the exceptions test above.
        val result = service.summary(Some(data.fundFlowConfig.id)) // must not throw

        assertEquals(result.aging.overdueCount, 1)

  test("summary(fundFlowId) scopes matchRates and aging to that fund flow's accounts only"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup    = DataSetup()
        import data.given
        given clock: ClockService = ClockServiceLive()
        val service = ReportingService()

        data.accountRepo.update(data.reconAccount.copy(isRecon = true, balance = 100, reconciledBalance = 50))

        val otherFundFlow = FundFlowConfig(name = "other-ff")
        data.fundFlowConfigRepo.insert(otherFundFlow)
        val otherAccount = Account(name = "other-recon", fundFlowId = otherFundFlow.id, isRecon = true, balance = 200, reconciledBalance = 200)
        data.accountRepo.insert(otherAccount)

        val scoped = service.summary(Some(data.fundFlowConfig.id))
        assertEquals(scoped.matchRates.map(_.accountId), Seq(data.reconAccount.id))

        val unscoped = service.summary(None)
        assert(unscoped.matchRates.exists(_.accountId == otherAccount.id))

  test("summary with an unknown fundFlowId throws NotFoundError"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup    = DataSetup()
        import data.given
        given clock: ClockService = ClockServiceLive()
        val service = ReportingService()

        intercept[NotFoundError]:
          service.summary(Some(java.util.UUID.randomUUID()))
