package anseliq.domain.service

import anseliq.common.model.AppliedRules
import anseliq.common.model.model.ID
import anseliq.domain.DataSetup
import anseliq.domain.model.*
import anseliq.domain.model.helper.JSONBProps
import anseliq.domain.port.driven.Txn
import anseliq.testing.{TestAppContainer, rollBackOnly}
import com.augustnagro.magnum.transact

class ReportingServiceSuite extends munit.FunSuite:
  def createMovement(inAccountId: ID, outAccountId: ID, movementSpecId: ID, amount: BigDecimal)(using
    data: DataSetup
  ): Txn[Movement] =
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
    val app = TestAppContainer()
    transact(app.transactor):
      rollBackOnly:
        val data        = DataSetup(app)
        given DataSetup = data
        given clock: ClockService = app.clock
        val service = app.reportingService

        data.accountRepo.update(data.reconAccount.copy(balance = 300, reconciledBalance = 100))
        val zeroBalance = Account(
          name        = "zero-balance-recon",
          ledgerId    = data.ledger.id,
          accountType = AccountType.Recon
        )
        data.accountRepo.insert(zeroBalance)

        val result = service.summary(None)

        val recon = result.matchRates.find(_.accountId == data.reconAccount.id).get
        assertEquals(recon.balanceCoveragePercent, Some(BigDecimal("33.33")))

        val zero = result.matchRates.find(_.accountId == zeroBalance.id).get
        assertEquals(zero.balanceCoveragePercent, None)

  test("matchRates: computes reconciledMovementCount/totalMovementCount*100 (the real match rate)"):
    val app = TestAppContainer()
    transact(app.transactor):
      rollBackOnly:
        val data        = DataSetup(app)
        given DataSetup = data
        given clock: ClockService = app.clock
        val service = app.reportingService

        val spec = data.journalToRecon
        val m1   = createMovement(spec.inAccountId, spec.outAccountId, spec.id, 100)
        val m2   = createMovement(spec.inAccountId, spec.outAccountId, spec.id, 100)
        createMovement(spec.inAccountId, spec.outAccountId, spec.id, 100)
        createMovement(spec.inAccountId, spec.outAccountId, spec.id, 100)

        // Reconcile 2 of the 4 movements on data.reconAccount together.
        val recon = Reconciliation(specId = Some(data.reconSpec.id), appliedRules = AppliedRules.Manual)
        data.reconRepo.insert(recon)
        data.movementRepo.setReconciliationIds(Seq(m1.id -> recon.id, m2.id -> recon.id))

        val result  = service.summary(None)
        val account = result.matchRates.find(_.accountId == data.reconAccount.id).get

        assertEquals(account.totalMovementCount, 4)
        assertEquals(account.reconciledMovementCount, 2)
        assertEquals(account.matchRatePercent, Some(BigDecimal("50.00")))

  test("matchRates: only includes Recon-typed accounts"):
    val app = TestAppContainer()
    transact(app.transactor):
      rollBackOnly:
        val data        = DataSetup(app)
        given DataSetup = data
        given clock: ClockService = app.clock
        val service = app.reportingService

        val result = service.summary(None)

        assert(result.matchRates.exists(_.accountId == data.reconAccount.id))
        assert(!result.matchRates.exists(_.accountId == data.journalAccount.id))
        assert(!result.matchRates.exists(_.accountId == data.reportAccount.id))

  test("exceptions: groups open exceptions by category and status, excludes Resolved"):
    val app = TestAppContainer()
    transact(app.transactor):
      rollBackOnly:
        val data        = DataSetup(app)
        given DataSetup = data
        given clock: ClockService = app.clock
        val service = app.reportingService

        val m =
          createMovement(data.journalToRecon.inAccountId, data.journalToRecon.outAccountId, data.journalToRecon.id, 100)
        data.exceptionRepo.insert(
          ExceptionCase(
            referenceId = m.id,
            targetType  = ExceptionTarget.Movement,
            category    = ExceptionCategory.AmountMismatch,
            status      = ExceptionStatus.New,
            createdAt   = Some(clock.now),
            updatedAt   = Some(clock.now)
          )
        )
        data.exceptionRepo.insert(
          ExceptionCase(
            referenceId = m.id,
            targetType  = ExceptionTarget.Movement,
            category    = ExceptionCategory.Duplicate,
            status      = ExceptionStatus.Investigating,
            createdAt   = Some(clock.now),
            updatedAt   = Some(clock.now)
          )
        )
        data.exceptionRepo.insert(
          ExceptionCase(
            referenceId = m.id,
            targetType  = ExceptionTarget.Movement,
            category    = ExceptionCategory.AmountMismatch,
            status      = ExceptionStatus.Resolved,
            createdAt   = Some(clock.now),
            updatedAt   = Some(clock.now)
          )
        )

        // Scoped to this test's own ledger: the shared dev database this suite runs against also has
        // real exception data from manual QA testing, which would otherwise leak into an unscoped count.
        val result = service.summary(Some(data.ledger.id))

        assertEquals(result.exceptions.byCategory.get("AmountMismatch"), Some(1))
        assertEquals(result.exceptions.byCategory.get("Duplicate"), Some(1))
        assertEquals(result.exceptions.byStatus.get("New"), Some(1))
        assertEquals(result.exceptions.byStatus.get("Investigating"), Some(1))
        assertEquals(result.exceptions.byStatus.get("Resolved"), None)

  test("aging: assigns unreconciled movements to fixed day buckets at each boundary"):
    val app = TestAppContainer()
    transact(app.transactor):
      rollBackOnly:
        val data        = DataSetup(app)
        given DataSetup = data
        given clock: ClockService = app.clock
        val service = app.reportingService

        val spec = data.journalToRecon

        createMovementAgedDays(spec.inAccountId, spec.outAccountId, spec.id, 3)  // 0-3
        createMovementAgedDays(spec.inAccountId, spec.outAccountId, spec.id, 4)  // 4-7
        createMovementAgedDays(spec.inAccountId, spec.outAccountId, spec.id, 7)  // 4-7
        createMovementAgedDays(spec.inAccountId, spec.outAccountId, spec.id, 8)  // 8-14
        createMovementAgedDays(spec.inAccountId, spec.outAccountId, spec.id, 14) // 8-14
        createMovementAgedDays(spec.inAccountId, spec.outAccountId, spec.id, 15) // 15+

        // Scoped to this test's own ledger, for the same reason as the exceptions test above.
        val result  = service.summary(Some(data.ledger.id))
        val buckets = result.aging.buckets.toMap

        assertEquals(buckets("0-3"), 1)
        assertEquals(buckets("4-7"), 2)
        assertEquals(buckets("8-14"), 2)
        assertEquals(buckets("15+"), 1)

  test("aging: overdueCount respects the account's agingLimit; an account with no recon spec is skipped, not thrown"):
    val app = TestAppContainer()
    transact(app.transactor):
      rollBackOnly:
        val data        = DataSetup(app)
        given DataSetup = data
        given clock: ClockService = app.clock
        val service = app.reportingService

        data.reconSpecRepo.update(data.reconSpec.copy(agingLimit = 5))
        val spec = data.journalToRecon

        createMovementAgedDays(spec.inAccountId, spec.outAccountId, spec.id, 3)  // within limit
        createMovementAgedDays(spec.inAccountId, spec.outAccountId, spec.id, 10) // overdue

        val orphanAccount =
          Account(name = "orphan-recon", ledgerId = data.ledger.id, accountType = AccountType.Recon)
        data.accountRepo.insert(orphanAccount)
        val orphanFeed = FileFeed()
        data.fileFeedRepo.insert(orphanFeed)
        val orphanSpec = MovementSpec(
          inAccountId  = orphanAccount.id,
          outAccountId = data.journalAccount.id,
          fileFeedId   = orphanFeed.id
        )
        data.movementSpecRepo.insert(orphanSpec)
        createMovementAgedDays(
          orphanAccount.id,
          data.journalAccount.id,
          orphanSpec.id,
          20
        ) // no recon spec exists for orphanAccount

        // Scoped to this test's own ledger, for the same reason as the exceptions test above.
        val result = service.summary(Some(data.ledger.id)) // must not throw

        assertEquals(result.aging.overdueCount, 1)

  test("summary(ledgerId) scopes matchRates and aging to that ledger's accounts only"):
    val app = TestAppContainer()
    transact(app.transactor):
      rollBackOnly:
        val data        = DataSetup(app)
        given DataSetup = data
        given clock: ClockService = app.clock
        val service = app.reportingService

        data.accountRepo.update(data.reconAccount.copy(balance = 100, reconciledBalance = 50))

        val otherLedger = Ledger(name = "other-ff")
        data.ledgerRepo.insert(otherLedger)
        val otherAccount = Account(
          name              = "other-recon",
          ledgerId          = otherLedger.id,
          accountType       = AccountType.Recon,
          balance           = 200,
          reconciledBalance = 200
        )
        data.accountRepo.insert(otherAccount)

        val scoped = service.summary(Some(data.ledger.id))
        assertEquals(scoped.matchRates.map(_.accountId), Seq(data.reconAccount.id))

        val unscoped = service.summary(None)
        assert(unscoped.matchRates.exists(_.accountId == otherAccount.id))

  test("summary with an unknown ledgerId throws NotFoundError"):
    val app = TestAppContainer()
    transact(app.transactor):
      rollBackOnly:
        val data        = DataSetup(app)
        given DataSetup = data
        given clock: ClockService = app.clock
        val service = app.reportingService

        intercept[NotFoundError]:
          service.summary(Some(java.util.UUID.randomUUID()))

  test(
    "aggregateSnapshotsByDate: weighted-averages avgBalanceCoveragePercent by ratedAccountCount, and derives avgMatchRatePercent as a ratio of summed movement counts"
  ):
    // Pure function, no DB — two ledgers' snapshots sharing a date: one rated on 3 accounts
    // averaging 90%, the other on 1 account at 50%. Weighted average = (90*3 + 50*1) / 4 = 80.
    // Movement counts: ledger A has 8 reconciled of 10 total, ledger B has 1 of 2 — ratio of
    // sums = (8+1)/(10+2) = 75.00%.
    val date = java.time.LocalDate.of(2026, 1, 15)
    val rows = Vector(
      DailySnapshot(
        ledgerId                  = createID(),
        snapshotDate              = date,
        avgBalanceCoveragePercent = Some(BigDecimal(90)),
        ratedAccountCount         = 3,
        openExceptionCount        = 2,
        agingZeroToThree          = 1,
        agingFourToSeven          = 1,
        overdueCount              = 1,
        reconciledMovementCount   = 8,
        totalMovementCount        = 10
      ),
      DailySnapshot(
        ledgerId                  = createID(),
        snapshotDate              = date,
        avgBalanceCoveragePercent = Some(BigDecimal(50)),
        ratedAccountCount         = 1,
        openExceptionCount        = 5,
        agingEightToFourteen      = 2,
        agingFifteenPlus          = 1,
        overdueCount              = 3,
        reconciledMovementCount   = 1,
        totalMovementCount        = 2
      )
    )

    val points = ReportingService.aggregateSnapshotsByDate(rows)

    assertEquals(points.size, 1)
    val p = points.head
    assertEquals(p.date, date)
    assertEquals(p.avgBalanceCoveragePercent, Some(BigDecimal("80.00")))
    assertEquals(p.avgMatchRatePercent, Some(BigDecimal("75.00")))
    assertEquals(p.openExceptionCount, 7)
    assertEquals(p.unreconciledBacklogCount, 5) // 1+1+2+1
    assertEquals(p.overdueCount, 4)

  test("aggregateSnapshotsByDate: sums openExceptionsByCategory across ledgers sharing a date, per category"):
    val date = java.time.LocalDate.of(2026, 1, 15)
    val rows = Vector(
      DailySnapshot(
        ledgerId                 = createID(),
        snapshotDate             = date,
        exceptionsByCategoryJson = upickle.write(Map("AmountMismatch" -> 2, "FileMissing" -> 1))
      ),
      DailySnapshot(
        ledgerId                 = createID(),
        snapshotDate             = date,
        exceptionsByCategoryJson = upickle.write(Map("AmountMismatch" -> 3, "Duplicate" -> 1))
      )
    )

    val points = ReportingService.aggregateSnapshotsByDate(rows)

    assertEquals(points.size, 1)
    assertEquals(
      points.head.openExceptionsByCategory,
      Map("AmountMismatch" -> 5, "FileMissing" -> 1, "Duplicate" -> 1)
    )

  test(
    "aggregateSnapshotsByDate: a ledger with no rated accounts that day (None) is excluded from the weighted average, not treated as 0"
  ):
    val date = java.time.LocalDate.of(2026, 1, 15)
    val rows = Vector(
      DailySnapshot(
        ledgerId                  = createID(),
        snapshotDate              = date,
        avgBalanceCoveragePercent = Some(BigDecimal(80)),
        ratedAccountCount         = 2
      ),
      DailySnapshot(
        ledgerId                  = createID(),
        snapshotDate              = date,
        avgBalanceCoveragePercent = None,
        ratedAccountCount         = 0
      )
    )

    val points = ReportingService.aggregateSnapshotsByDate(rows)

    assertEquals(points.head.avgBalanceCoveragePercent, Some(BigDecimal("80.00")))

  test("computeAndStoreDailySnapshots: is idempotent within the same day and matches summary()'s own numbers"):
    val app = TestAppContainer()
    transact(app.transactor):
      rollBackOnly:
        val data        = DataSetup(app)
        given DataSetup = data
        given clock: ClockService = app.clock
        val service = app.reportingService

        data.accountRepo.update(data.reconAccount.copy(balance = 300, reconciledBalance = 150))

        service.computeAndStoreDailySnapshots()
        service.computeAndStoreDailySnapshots() // second run same day must overwrite, not duplicate

        val today = clock.now.toLocalDate
        val rows  = app.dailySnapshotRepo.findTrend(data.ledger.id, today, today)
        assertEquals(rows.size, 1, "idempotent upsert: exactly one row for this ledger today")

        val expected = service.summary(Some(data.ledger.id))
        assertEquals(rows.head.avgBalanceCoveragePercent, expected.matchRates.head.balanceCoveragePercent)
        assertEquals(rows.head.avgMatchRatePercent, expected.matchRates.head.matchRatePercent)
        assertEquals(rows.head.ratedAccountCount, 1)
        assertEquals(rows.head.overdueCount, expected.aging.overdueCount)

  test("computeAndStoreDailySnapshots + trend: builds a multi-day trend as balances change across simulated days"):
    val app = TestAppContainer()
    transact(app.transactor):
      rollBackOnly:
        val data        = DataSetup(app)
        given DataSetup = data
        given clock: ClockService = app.clock
        val service = app.reportingService

        data.accountRepo.update(
          data.reconAccount.copy(balance = 100, reconciledBalance = 25)
        ) // day 1: 25%
        service.computeAndStoreDailySnapshots()
        val day1 = clock.now.toLocalDate

        clock.addHours(24)
        data.accountRepo.update(
          data.reconAccount.copy(balance = 100, reconciledBalance = 75)
        ) // day 2: 75%
        service.computeAndStoreDailySnapshots()
        val day2 = clock.now.toLocalDate

        val trend  = service.trend(Some(data.ledger.id), days = 30)
        val byDate = trend.points.map(p => p.date -> p).toMap

        assertEquals(byDate(day1).avgBalanceCoveragePercent, Some(BigDecimal("25.00")))
        assertEquals(byDate(day2).avgBalanceCoveragePercent, Some(BigDecimal("75.00")))

  test("computeAndStoreDailySnapshots: a ledger isn't due until its dailyCutoffHours, then becomes due"):
    val app = TestAppContainer()
    transact(app.transactor):
      rollBackOnly:
        val data        = DataSetup(app)
        given DataSetup = data
        given clock: ClockService = app.clock
        val service = app.reportingService

        // Anchor to a known hour-of-day (00:xx) first, so the +1h/+3h steps below land at
        // deterministic, non-wrapping hours regardless of the real wall-clock time this test
        // happens to run at.
        clock.addHours(24 - clock.now.getHour)

        data.ledgerRepo.update(data.ledger.copy(dailyCutoffHours = 3))
        data.accountRepo.update(data.reconAccount.copy(balance = 100, reconciledBalance = 50))

        // Not assertEquals(..., 0/1) on the raw return value: computeAndStoreDailySnapshots counts
        // every due ledger in the whole database, not just this test's own — fragile against
        // any other ledger that happens to exist (e.g. DataSetupScenario1's permanent demo
        // data, deliberately never cleaned up since it's meant for manual UI testing). Checking
        // existsForDate for this test's own ledger.id is the properly-scoped equivalent.
        clock.addHours(1) // ~01:xx — before the 3am cutoff
        service.computeAndStoreDailySnapshots()
        assert(
          !app.dailySnapshotRepo.existsForDate(data.ledger.id, clock.now.toLocalDate),
          "not due yet: before this ledger's cutoff hour"
        )

        clock.addHours(3) // ~04:xx — past the 3am cutoff
        service.computeAndStoreDailySnapshots()
        assert(
          app.dailySnapshotRepo.existsForDate(data.ledger.id, clock.now.toLocalDate),
          "due now: past this ledger's cutoff hour"
        )

  test("computeAndStoreDailySnapshots: a ledger already snapshotted today is skipped on a later poll"):
    val app = TestAppContainer()
    transact(app.transactor):
      rollBackOnly:
        val data        = DataSetup(app)
        given DataSetup = data
        given clock: ClockService = app.clock
        val service = app.reportingService

        data.accountRepo.update(data.reconAccount.copy(balance = 100, reconciledBalance = 50))

        // Same reasoning as the cutoff-hours test above: the raw return count isn't scoped to this
        // test's own ledger, so it's compared here via the actual persisted snapshot row instead
        // of an exact 1/0 count — robust to any other ledger in the database also being due.
        service.computeAndStoreDailySnapshots()
        val today = clock.now.toLocalDate
        assert(app.dailySnapshotRepo.existsForDate(data.ledger.id, today), "first poll today: due")
        val afterFirstPoll = app.dailySnapshotRepo.findTrend(data.ledger.id, today, today).head

        service.computeAndStoreDailySnapshots() // a later poll the same day
        val afterSecondPoll = app.dailySnapshotRepo.findTrend(data.ledger.id, today, today).head
        assertEquals(
          afterSecondPoll,
          afterFirstPoll,
          "already snapshotted today: nothing left to do until tomorrow, even though a 1-minute poll keeps calling this"
        )
