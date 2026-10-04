package anseliq.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import anseliq.common.model.AppliedRules
import anseliq.adapter.driven.DbDataSource
import anseliq.domain.DataSetup
import anseliq.domain.model.*
import anseliq.testing.rollBackOnly

import java.util.concurrent.{ConcurrentLinkedQueue, CyclicBarrier}
import javax.sql.DataSource
import scala.jdk.CollectionConverters.*

class LedgerSuite extends munit.FunSuite:
  given transactor: Transactor = Transactor(dataSource)
  given dataSource: DataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))

  def assertBalanceInvariant(reconciled: BigDecimal, unreconciled: BigDecimal, balance: BigDecimal): Unit =
    assertEquals(reconciled + unreconciled, balance, "reconciledBalance + unreconciledBalance must equal balance")

  test("recordMovements updates account balances and keeps reconciled + unreconciled equal to balance"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        data.ledger.recordMovements(
          data.journalToRecon,
          Seq(
            MovementRequest(amount = 100, referenceId = createID(), properties = Map()),
            MovementRequest(amount = -30, referenceId = createID(), properties = Map()),
            MovementRequest(amount = 50, referenceId  = createID(), properties = Map())
          )
        )

        val reconAccount   = data.accountRepo.findById(data.reconAccount.id).get
        val journalAccount = data.accountRepo.findById(data.journalAccount.id).get

        assertEquals(reconAccount.balance, BigDecimal(120)) // +100 -30 +50
        assertEquals(journalAccount.balance, BigDecimal(-120))

        assertBalanceInvariant(reconAccount.reconciledBalance, reconAccount.unreconciledBalance, reconAccount.balance)
        assertBalanceInvariant(
          journalAccount.reconciledBalance,
          journalAccount.unreconciledBalance,
          journalAccount.balance
        )

        val recorded = data.movementRepo.findByAccount(data.reconAccount.id)
        assert(recorded.nonEmpty)
        assert(
          recorded.forall(_.movementType.contains(MovementType.Ingestion)),
          "every movement recordMovements creates should be tagged Ingestion"
        )

  test("markReconciled moves an amount from unreconciled to reconciled without changing balance"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        data.ledger.recordMovements(
          data.journalToRecon,
          Seq(MovementRequest(amount = 100, referenceId = createID(), properties = Map()))
        )

        data.ledger.markReconciled(data.reconAccount.id, 60)

        val reconAccount = data.accountRepo.findById(data.reconAccount.id).get
        assertEquals(reconAccount.balance, BigDecimal(100), "markReconciled must not change balance")
        assertEquals(reconAccount.reconciledBalance, BigDecimal(60))
        assertEquals(reconAccount.unreconciledBalance, BigDecimal(40))
        assertBalanceInvariant(reconAccount.reconciledBalance, reconAccount.unreconciledBalance, reconAccount.balance)

  test("markUnreconciled reverses a prior markReconciled by the same amount"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        data.ledger.recordMovements(
          data.journalToRecon,
          Seq(MovementRequest(amount = 100, referenceId = createID(), properties = Map()))
        )

        data.ledger.markReconciled(data.reconAccount.id, 60)
        data.ledger.markUnreconciled(data.reconAccount.id, 60)

        val reconAccount = data.accountRepo.findById(data.reconAccount.id).get
        assertEquals(reconAccount.balance, BigDecimal(100))
        assertEquals(reconAccount.reconciledBalance, BigDecimal(0))
        assertEquals(reconAccount.unreconciledBalance, BigDecimal(100))
        assertBalanceInvariant(reconAccount.reconciledBalance, reconAccount.unreconciledBalance, reconAccount.balance)

  test("reconcile links matched movements to a reconciliation and shifts balance from unreconciled to reconciled"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given

        data.ledger.recordMovements(
          data.journalToRecon,
          Seq(MovementRequest(amount = 100, referenceId = createID(), properties = Map()))
        )
        val movement       = data.movementRepo.findByAccount(data.reconAccount.id).head
        val reconciliation = Reconciliation(specId = Some(data.reconSpec.id), appliedRules = AppliedRules.Manual)
        data.reconRepo.insert(reconciliation)

        data.ledger.reconcile(Seq(movement), reconciliation.id, data.reconAccount.id)

        val updatedMovement = data.movementRepo.findById(movement.id).get
        assertEquals(updatedMovement.reconciliationId, Some(reconciliation.id))

        val reconAccount = data.accountRepo.findById(data.reconAccount.id).get
        assertEquals(reconAccount.balance, BigDecimal(100), "reconcile must not change balance")
        assertEquals(reconAccount.reconciledBalance, BigDecimal(100))
        assertEquals(reconAccount.unreconciledBalance, BigDecimal(0))
        assertBalanceInvariant(reconAccount.reconciledBalance, reconAccount.unreconciledBalance, reconAccount.balance)

  test("unreconcile clears the reconciliation link and reverses the balance shift"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given

        data.ledger.recordMovements(
          data.journalToRecon,
          Seq(MovementRequest(amount = 100, referenceId = createID(), properties = Map()))
        )
        val movement       = data.movementRepo.findByAccount(data.reconAccount.id).head
        val reconciliation = Reconciliation(specId = Some(data.reconSpec.id), appliedRules = AppliedRules.Manual)
        data.reconRepo.insert(reconciliation)
        data.ledger.reconcile(Seq(movement), reconciliation.id, data.reconAccount.id)

        val reconciledMovement = data.movementRepo.findById(movement.id).get
        data.ledger.unreconcile(Seq(reconciledMovement), data.reconAccount.id)

        val updatedMovement = data.movementRepo.findById(movement.id).get
        assertEquals(updatedMovement.reconciliationId, None)

        val reconAccount = data.accountRepo.findById(data.reconAccount.id).get
        assertEquals(reconAccount.balance, BigDecimal(100))
        assertEquals(reconAccount.reconciledBalance, BigDecimal(0))
        assertEquals(reconAccount.unreconciledBalance, BigDecimal(100))
        assertBalanceInvariant(reconAccount.reconciledBalance, reconAccount.unreconciledBalance, reconAccount.balance)

  test("recordMovements does not double-count balances when a movement's referenceId is replayed"):
    // Simulates a retried/redelivered batch: the exact same MovementRequests (same deterministic
    // referenceIds) get recorded twice. The Movement rows are already deduped via
    // ON CONFLICT(reference_id) DO NOTHING, but that alone doesn't stop the balance delta from
    // being applied twice unless recordMovements itself excludes already-recorded referenceIds.
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val requests = Seq(
          MovementRequest(amount = 100, referenceId = createID(), properties = Map()),
          MovementRequest(amount = -30, referenceId = createID(), properties = Map())
        )

        data.ledger.recordMovements(data.journalToRecon, requests)
        data.ledger.recordMovements(data.journalToRecon, requests) // replay of the exact same batch

        val reconAccount   = data.accountRepo.findById(data.reconAccount.id).get
        val journalAccount = data.accountRepo.findById(data.journalAccount.id).get

        assertEquals(reconAccount.balance, BigDecimal(70), "replayed batch must not double the balance")
        assertEquals(journalAccount.balance, BigDecimal(-70))
        assertEquals(
          data.movementRepo.findByAccount(data.reconAccount.id).size,
          2,
          "replayed batch must not duplicate movement rows"
        )

        assertBalanceInvariant(reconAccount.reconciledBalance, reconAccount.unreconciledBalance, reconAccount.balance)
        assertBalanceInvariant(
          journalAccount.reconciledBalance,
          journalAccount.unreconciledBalance,
          journalAccount.balance
        )

  test("recordMovements applies only the new requests when a batch partially overlaps an already-recorded one"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val alreadyRecorded = MovementRequest(amount = 100, referenceId = createID(), properties = Map())
        val brandNew        = MovementRequest(amount = 25, referenceId = createID(), properties = Map())

        data.ledger.recordMovements(data.journalToRecon, Seq(alreadyRecorded))
        data.ledger.recordMovements(data.journalToRecon, Seq(alreadyRecorded, brandNew))

        val reconAccount = data.accountRepo.findById(data.reconAccount.id).get
        assertEquals(reconAccount.balance, BigDecimal(125), "only the brand-new request should add to the balance")
        assertEquals(data.movementRepo.findByAccount(data.reconAccount.id).size, 2)

  test("recordMovements under real concurrency: two racing callers of the same batch do not double-count the balance"):
    // Not rolled back — needs two genuinely separate connections/transactions calling
    // recordMovements at the same time for the DataSetup's own rollBackOnly-free data to be visible
    // to both. This exercises the fix directly: findByIdForUpdate's FOR UPDATE lock is acquired
    // *before* the existence check now, so the second caller serializes behind the first and its
    // own existence check correctly sees the first caller's already-committed movements. Before that
    // reordering, both callers could pass the (unlocked) existence check before either inserted,
    // and both would apply the balance delta — the second caller's insert would silently no-op via
    // ON CONFLICT, but its account update would not.
    val data = transact(transactor)(DataSetup())

    val requests = Seq(
      MovementRequest(amount = 100, referenceId = createID(), properties = Map()),
      MovementRequest(amount = -30, referenceId = createID(), properties = Map())
    )

    val barrier = CyclicBarrier(2)
    val errors  = ConcurrentLinkedQueue[Throwable]()

    def race(): Unit =
      try
        barrier.await()
        transact(transactor)(data.ledger.recordMovements(data.journalToRecon, requests))
      catch case ex: Throwable => errors.add(ex)

    val t1 = Thread(() => race())
    val t2 = Thread(() => race())
    t1.start()
    t2.start()
    t1.join()
    t2.join()

    try
      assert(errors.isEmpty, s"recordMovements must not surface the race as an error: ${errors.asScala.toList}")

      val reconAccount = transact(transactor)(data.accountRepo.findById(data.reconAccount.id).get)
      assertEquals(reconAccount.balance, BigDecimal(70), "the batch must be applied exactly once despite the race")

      val movements = transact(transactor)(data.movementRepo.findByAccount(data.reconAccount.id))
      assertEquals(movements.size, 2, "no duplicate movement rows despite the race")
    finally
      transact(transactor):
        // reconciliation_spec's FK to account isn't ON DELETE CASCADE, so it must go first;
        // fund_flow_config's cascade then takes care of the accounts/movement specs/movements.
        data.reconSpecRepo.deleteById(data.reconSpec.id)
        data.fundFlowConfigRepo.deleteById(data.fundFlowConfig.id)

  test("recordMovements with a negative amount swaps the in/out accounts"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        data.ledger.recordMovements(
          data.journalToRecon,
          Seq(MovementRequest(amount = -40, referenceId = createID(), properties = Map()))
        )

        val reconAccount   = data.accountRepo.findById(data.reconAccount.id).get
        val journalAccount = data.accountRepo.findById(data.journalAccount.id).get

        assertEquals(reconAccount.balance, BigDecimal(-40))
        assertEquals(journalAccount.balance, BigDecimal(40))

        assertBalanceInvariant(reconAccount.reconciledBalance, reconAccount.unreconciledBalance, reconAccount.balance)
        assertBalanceInvariant(
          journalAccount.reconciledBalance,
          journalAccount.unreconciledBalance,
          journalAccount.balance
        )
