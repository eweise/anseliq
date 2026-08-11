package perfectpair.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import perfectpair.adapter.driven.*
import perfectpair.domain.DataSetup
import perfectpair.domain.model.*
import perfectpair.domain.model.helper.JSONBProps
import perfectpair.domain.port.driven.*

import javax.sql.DataSource

class ReconcileAccountResumableSuite extends munit.FunSuite:
  given transactor: Transactor = Transactor(dataSource)

  given clockService: ClockService = ClockServiceLive()

  given dataSource: DataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))

  given eventRepo: EventRepo = EventRepoLive()

  given eventStore: EventStore = EventStore()

  given movementRepo: MovementRepo = MovementRepoLive()

  given accountRepo: AccountRepo = AccountRepoLive()

  given movementSpecRepo: MovementSpecRepo = MovementSpecRepoLive()

  given reconSpecRepo: ReconciliationSpecRepo = ReconciliationSpecRepoLive()

  given reconRepo: ReconciliationRepo = ReconciliationRepoLive()

  given fundFlowConfigRepo: FundFlowConfigRepo = FundFlowConfigRepoLive()

  given jobRepo: JobRepo = JobRepoLive()

  given ledger: Ledger = Ledger()

  private def teardown(data: DataSetup): Unit =
    transact(transactor):
      // reconcileAccountResumable publishes a real, permanently-committed AccountReconciled event
      // (not rolled back like the rest of this test's data would be under rollBackOnly), same
      // reason ingestResumable's MoneyMovementsIngested events needed cleanup in IngestResumableSuite.
      eventRepo
        .findByEventType("AccountReconciled")
        .filter(es => es.event[AccountReconciled].accountID == data.reconAccount.id)
        .foreach(es => eventRepo.deleteById(es.id))
      data.reconRepo.findByReconSpecID(data.reconSpec.id).foreach(r => data.reconRepo.deleteById(r.id))
      data.reconSpecRepo.deleteById(data.reconSpec.id)
      data.fundFlowConfigRepo.deleteById(data.fundFlowConfig.id)

  private def insertMovement(data: DataSetup, spec: MovementSpec, idValue: String): Txn[Movement] =
    val m = Movement(
      inAccountId    = spec.inAccountId,
      outAccountId   = spec.outAccountId,
      movementSpecId = spec.id,
      amount         = 100,
      properties     = JSONBProps(Map("id" -> idValue))
    )
    data.movementRepo.insert(m)
    m

  test("matches the same pairs as reconcileAccount for OneToOne"):
    val data = transact(transactor)(DataSetup())
    try
      transact(transactor):
        for id <- Seq("3", "1", "2")
        do
          insertMovement(data, data.journalToRecon, id)
          insertMovement(data, data.reportToRecon, id)

      val reconciler = InMemoryReconciler()
      val result     = reconciler.reconcileAccountResumable(data.reconAccount.id, batchSize = 10)

      assertEquals(result.completed, true)
      assertEquals(result.reconciliationSpecInsertCount(data.reconSpec.id), 3)

      val movements = transact(transactor)(data.movementRepo.findByAccount(data.reconAccount.id))
      assert(movements.forall(_.reconciliationId.nonEmpty), "every movement should be reconciled")
    finally teardown(data)

  test("earlier batches commit durably even when a later batch is rolled back"):
    val data = transact(transactor)(DataSetup())
    try
      transact(transactor):
        for id <- Seq("1", "2", "3", "4")
        do
          insertMovement(data, data.journalToRecon, id)
          insertMovement(data, data.reportToRecon, id)

      val reconciler = InMemoryReconciler()
      val result = reconciler.reconcileAccountResumable(
        data.reconAccount.id,
        batchSize       = 1,
        onBatchComplete = progress => if progress.checkpoint.lastInMatchKey.contains("3") then BatchDecision.Rollback else BatchDecision.Commit
      )

      assertEquals(result.completed, false)
      assertEquals(result.reconciliationSpecInsertCount(data.reconSpec.id), 2, "only ids 1 and 2 should have committed")

      val reconciledCount = transact(transactor):
        data.movementRepo.findByAccount(data.reconAccount.id).count(_.reconciliationId.nonEmpty)
      assertEquals(reconciledCount, 4, "2 committed matches x 2 movements each")
    finally teardown(data)

  test("resuming from a job's progressJson picks up where it left off, including a late arrival with a smaller key"):
    val data = transact(transactor)(DataSetup())
    val job = transact(transactor):
      val j = Job(referenceId = data.reconAccount.id, jobType = JobType.Reconciliation, jobStatus = JobStatus.InProgress)
      jobRepo.insert(j)
      j
    try
      transact(transactor):
        for id <- Seq("1", "2", "4")
        do
          insertMovement(data, data.journalToRecon, id)
          insertMovement(data, data.reportToRecon, id)

      val reconciler = InMemoryReconciler()
      val first = reconciler.reconcileAccountResumable(
        data.reconAccount.id,
        batchSize       = 1,
        jobId           = Some(job.id),
        onBatchComplete = progress => if progress.checkpoint.lastInMatchKey.contains("2") then BatchDecision.Rollback else BatchDecision.Commit
      )
      assertEquals(first.completed, false)
      assertEquals(first.reconciliationSpecInsertCount(data.reconSpec.id), 1, "only id 1 committed")

      // Late arrival: id "3" sorts before the checkpoint ("1"), proving a fresh trigger still finds it.
      transact(transactor):
        insertMovement(data, data.journalToRecon, "3")
        insertMovement(data, data.reportToRecon, "3")

      val second = reconciler.reconcileAccountResumable(data.reconAccount.id, batchSize = 10, jobId = Some(job.id))
      assertEquals(second.completed, true)
      assertEquals(second.reconciliationSpecInsertCount(data.reconSpec.id), 3, "ids 2, 3, and 4")

      val allReconciled = transact(transactor):
        data.movementRepo.findByAccount(data.reconAccount.id).forall(_.reconciliationId.nonEmpty)
      assert(allReconciled)
    finally
      transact(transactor)(jobRepo.deleteById(job.id))
      teardown(data)
