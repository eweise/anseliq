package perfectpair.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import perfectpair.adapter.driven.{DbDataSource, EventRepoLive}
import perfectpair.domain.DataSetup
import perfectpair.domain.model.helper.JSONBProps
import perfectpair.domain.model.{Movement, MovementSpec, Relationship, ReconciliationSpec, ReconciliationStatus, createID}
import perfectpair.domain.port.driven.Txn
import perfectpair.testing.rollBackOnly

import java.util.UUID
import javax.sql.DataSource

class InmemoryReconcilerSuite extends munit.FunSuite:
  given transactor: Transactor = Transactor(dataSource)

  given dataSource: DataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))

  given clockSvc: ClockService = ClockServiceLive()
  
  given eventRepo : EventRepoLive()

  given eventStore: EventStore = EventStore()

  def createMovement(data: DataSetup, movementSpec: MovementSpec, idValue: String): Txn[Movement] =
    val m = Movement(
      inAccountId    = movementSpec.inAccountId,
      outAccountId   = movementSpec.outAccountId,
      movementSpecId = movementSpec.id,
      amount         = 100,
      properties     = JSONBProps(Map("foo" -> "bar", "id" -> idValue))
    )

    data.movementRepo.insert(m)
    m


  test("OneToMany partial reconciliation completes across multiple runs"):
    transact(transactor):
      rollBackOnly:
        val data: DataSetup = DataSetup()
        import data.given
        val ops = FundFlowOperations()

        given clock: ClockService = ClockServiceLive()

        // Override the reconSpec to use OneToMany
        val oneToManySpec = ReconciliationSpec(
          accountId         = data.reconAccount.id,
          ruleJson          = upickle.write(data.rule),
          relationship      = Relationship.OneToMany,
          inMovementSpecId  = data.journalToRecon.id,
          outMovementSpecId = data.reportToRecon.id
        )
        data.reconSpecRepo.delete(data.reconSpec)
        data.reconSpecRepo.insert(oneToManySpec)

        val keyID = createID().toString

        // Post one "in" movement of 300 and two "out" movements of 100 each
        ops.postAmount(data.journalToRecon, 300, referenceID = createID(), Map("id" -> keyID))
        ops.postAmount(data.reportToRecon, 100, referenceID  = createID(), Map("id" -> keyID))
        ops.postAmount(data.reportToRecon, 100, referenceID  = createID(), Map("id" -> keyID))

        val reconciler = InMemoryReconciler()

        // First reconciliation — should be InProgress with balance 100
        reconciler.reconcileAccount(data.reconAccount.id)
        val recons1 = data.reconRepo.findByReconSpecID(oneToManySpec.id)
        assertEquals(recons1.size, 1)
        assertEquals(recons1.head.status, ReconciliationStatus.InProgress)
        assertEquals(recons1.head.balance, BigDecimal(100))

        // Account should reflect partial reconciliation
        // In movement: +300 on recon account, Out movements: -100, -100 on recon account
        // Reconciled: +300 (in) + -100 -100 (out) = 100, Unreconciled: 0
        val acct1 = data.accountRepo.findById(data.reconAccount.id).get
        assertEquals(acct1.reconciledBalance, BigDecimal(100))
        assertEquals(acct1.unreconciledBalance, BigDecimal(0))

        // Post the final "out" movement of 100
        ops.postAmount(data.reportToRecon, 100, referenceID = createID(), Map("id" -> keyID))

        // Second reconciliation — should complete to Final with balance 0
        reconciler.reconcileAccount(data.reconAccount.id)
        val recons2 = data.reconRepo.findByReconSpecID(oneToManySpec.id)
        assertEquals(recons2.size, 1)
        assertEquals(recons2.head.status, ReconciliationStatus.Final)
        assertEquals(recons2.head.balance, BigDecimal(0))

        // Account should be fully reconciled: +300 -100 -100 -100 = 0
        val acct2 = data.accountRepo.findById(data.reconAccount.id).get
        assertEquals(acct2.reconciledBalance, BigDecimal(0))
        assertEquals(acct2.unreconciledBalance, BigDecimal(0))

        // All movements should be reconciled
        val unreconciled = data.movementRepo.findUnReconciledByAccount(data.reconAccount.id)
        assert(unreconciled.isEmpty)

  test("Post Amount balances and Reconcile"):
    transact(transactor):
      rollBackOnly:
        val data: DataSetup = DataSetup()
        import data.given
        val ops = FundFlowOperations()

        given clock: ClockService = ClockServiceLive()
        val reconciler = InMemoryReconciler()

        for _ <- 1 to 3
        do
          val keyID = UUID.randomUUID().toString
          ops.postAmount(data.journalToRecon, 1000, referenceID = createID(), Map("id" -> keyID))
          ops.postAmount(data.reportToRecon, 1000, referenceID  = createID(), Map("id" -> keyID))

        reconciler.reconcileAccount(data.reconAccount.id)
        val noUnreconciled = data.movementRepo.findUnReconciledByAccount(data.reconAccount.id)
        assert(noUnreconciled.isEmpty)

        // 3 pairs reconciled: each pair is +1000 (in) -1000 (out) = 0 net
        val acct1 = data.accountRepo.findById(data.reconAccount.id).get
        assertEquals(acct1.reconciledBalance, BigDecimal(0))
        assertEquals(acct1.unreconciledBalance, BigDecimal(0))
        assertEquals(acct1.balance, BigDecimal(0))

        ops.postAmount(data.journalToRecon, 1000, referenceID = createID(), Map("id" -> createID().toString))
        ops.postAmount(data.journalToRecon, 1000, referenceID = createID(), Map("id" -> createID().toString))
        ops.postAmount(data.reportToRecon, 1000, referenceID  = createID(), Map("id" -> createID().toString))

        reconciler.reconcileAccount(data.reconAccount.id)
        val unreconciled = data.movementRepo.findUnReconciledByAccount(data.reconAccount.id)
        assertEquals(unreconciled.size, 3)

        // 3 unmatched movements: +1000, +1000 (in), -1000 (out) = +1000 unreconciled
        val acct2 = data.accountRepo.findById(data.reconAccount.id).get
        assertEquals(acct2.reconciledBalance, BigDecimal(0))
        assertEquals(acct2.unreconciledBalance, BigDecimal(1000))
        assertEquals(acct2.balance, BigDecimal(1000))
