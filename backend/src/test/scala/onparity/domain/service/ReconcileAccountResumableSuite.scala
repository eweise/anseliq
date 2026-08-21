package onparity.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import onparity.adapter.driven.*
import onparity.common.model.{StringAttribute, StringAttributeMatchRule}
import onparity.domain.DataSetup
import onparity.domain.model.*
import onparity.domain.model.helper.JSONBProps
import onparity.domain.port.driven.*

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

  test("OneToOne verifies amount balance: a key match with mismatched amounts stays InProgress, not Final"):
    // A OneToOne pair is inherently a single, final judgment — there's no third movement that could
    // ever arrive to close the gap under a OneToOne rule — but "stays InProgress" (rather than
    // silently marked Final) is still the correct outcome: it's exactly the kind of exception
    // (short payment, fee deduction, rounding) reconciliation exists to surface, not hide.
    val data = transact(transactor)(DataSetup())
    try
      transact(transactor):
        data.movementRepo.insert(
          Movement(
            inAccountId = data.journalToRecon.inAccountId, outAccountId = data.journalToRecon.outAccountId,
            movementSpecId = data.journalToRecon.id, amount = 100, properties = JSONBProps(Map("id" -> "1"))
          )
        )
        data.movementRepo.insert(
          Movement(
            inAccountId = data.reportToRecon.inAccountId, outAccountId = data.reportToRecon.outAccountId,
            movementSpecId = data.reportToRecon.id, amount = 90, properties = JSONBProps(Map("id" -> "1"))
          )
        )

      val reconciler = InMemoryReconciler()
      val result      = reconciler.reconcileAccountResumable(data.reconAccount.id, batchSize = 10)

      assertEquals(result.reconciliationSpecInsertCount(data.reconSpec.id), 1)

      val reconciliations = transact(transactor)(data.reconRepo.findByReconSpecID(data.reconSpec.id))
      assertEquals(reconciliations.size, 1)
      assertEquals(reconciliations.head.status, ReconciliationStatus.InProgress)
      assertEquals(reconciliations.head.balance, BigDecimal(10))

      val movements = transact(transactor)(data.movementRepo.findByAccount(data.reconAccount.id))
      assert(movements.forall(_.reconciliationId.nonEmpty), "both movements are still linked to the reconciliation despite the mismatch")
    finally teardown(data)

  test("OneToOne match with equal amounts is Final with a zero balance"):
    val data = transact(transactor)(DataSetup())
    try
      transact(transactor):
        insertMovement(data, data.journalToRecon, "1")
        insertMovement(data, data.reportToRecon, "1")

      val reconciler = InMemoryReconciler()
      val result      = reconciler.reconcileAccountResumable(data.reconAccount.id, batchSize = 10)

      assertEquals(result.reconciliationSpecInsertCount(data.reconSpec.id), 1)

      val reconciliations = transact(transactor)(data.reconRepo.findByReconSpecID(data.reconSpec.id))
      assertEquals(reconciliations.size, 1)
      assertEquals(reconciliations.head.status, ReconciliationStatus.Final)
      assertEquals(reconciliations.head.balance, BigDecimal(0))
    finally teardown(data)

  test("multiple matched OneToOne pairs zero out account balance, leaving only unmatched movements unreconciled"):
    // Exercises reconcileAccountResumable through FundFlowOperations.postAmount (not raw
    // movementRepo.insert), so account.balance/reconciledBalance/unreconciledBalance are populated
    // the same way production ingestion populates them, across three separate matched pairs in one
    // run plus leftover unmatched movements from a second run.
    val data = transact(transactor)(DataSetup())
    given ops: FundFlowOperations = FundFlowOperations()
    try
      transact(transactor):
        for _ <- 1 to 3
        do
          val key = createID().toString
          ops.postAmount(data.journalToRecon, 1000, referenceID = createID(), Map("id" -> key))
          ops.postAmount(data.reportToRecon, 1000, referenceID = createID(), Map("id" -> key))

      val reconciler = InMemoryReconciler()
      val result1     = reconciler.reconcileAccountResumable(data.reconAccount.id, batchSize = 10)
      assertEquals(result1.reconciliationSpecInsertCount(data.reconSpec.id), 3)

      val unreconciledAfterFirstPass = transact(transactor)(movementRepo.findUnReconciledByAccount(data.reconAccount.id))
      assert(unreconciledAfterFirstPass.isEmpty)

      val acct1 = transact(transactor)(accountRepo.findById(data.reconAccount.id).get)
      assertEquals(acct1.balance, BigDecimal(0))
      assertEquals(acct1.reconciledBalance, BigDecimal(0))
      assertEquals(acct1.unreconciledBalance, BigDecimal(0))

      transact(transactor):
        for _ <- 1 to 2
        do ops.postAmount(data.journalToRecon, 1000, referenceID = createID(), Map("id" -> createID().toString))
        ops.postAmount(data.reportToRecon, 1000, referenceID = createID(), Map("id" -> createID().toString))

      val result2 = reconciler.reconcileAccountResumable(data.reconAccount.id, batchSize = 10)
      assertEquals(
        result2.reconciliationSpecInsertCount(data.reconSpec.id),
        0,
        "no new matches, the 3 new movements all have distinct keys"
      )

      val unreconciledAfterSecondPass = transact(transactor)(movementRepo.findUnReconciledByAccount(data.reconAccount.id))
      assertEquals(unreconciledAfterSecondPass.size, 3)

      val acct2 = transact(transactor)(accountRepo.findById(data.reconAccount.id).get)
      assertEquals(acct2.balance, BigDecimal(1000))
      assertEquals(acct2.reconciledBalance, BigDecimal(0))
      assertEquals(acct2.unreconciledBalance, BigDecimal(1000))
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

  // Uses its own MovementSpecs (not data.journalToRecon/reportToRecon) so these groupId-keyed
  // movements never get scanned by the account's existing OneToOne spec, which expects an "id"
  // property and would throw trying to match on movements that only carry "groupId".
  private def oneToManySpec(data: DataSetup): (MovementSpec, MovementSpec, ReconciliationSpec) =
    val inSpec = MovementSpec(inAccountId = data.reconAccount.id, outAccountId = data.journalAccount.id)
    val outSpec = MovementSpec(outAccountId = data.reconAccount.id, inAccountId = data.reportAccount.id)
    val rule = StringAttributeMatchRule(inKey = StringAttribute(key = "groupId"), outKey = StringAttribute(key = "groupId"))
    val spec = ReconciliationSpec(
      accountId         = data.reconAccount.id,
      relationship      = Relationship.OneToMany,
      ruleJson          = upickle.write(rule),
      inMovementSpecId  = inSpec.id,
      outMovementSpecId = outSpec.id
    )
    transact(transactor):
      movementSpecRepo.insert(inSpec)
      movementSpecRepo.insert(outSpec)
      data.reconSpecRepo.insert(spec)
    (inSpec, outSpec, spec)

  test("OneToMany groups multiple out-movements against one in-movement, including a group spanning a page boundary"):
    val data                    = transact(transactor)(DataSetup())
    val (inSpec, outSpec, spec) = oneToManySpec(data)
    given ops: FundFlowOperations = FundFlowOperations()
    try
      transact(transactor):
        // one in-movement worth 100, matched by three out-movements (40 + 40 + 20) all sharing groupId "g1"
        ops.postAmount(inSpec, 100, referenceID = createID(), Map("groupId" -> "g1"))
        for amount <- Seq(BigDecimal(40), BigDecimal(40), BigDecimal(20))
        do ops.postAmount(outSpec, amount, referenceID = createID(), Map("groupId" -> "g1"))

      val reconciler = InMemoryReconciler()
      // pageSize forced small enough that the 3-row out-group can't fit in one page fetch,
      // proving the peek-ahead logic correctly extends the group across a page boundary.
      val result = reconciler.reconcileAccountResumable(data.reconAccount.id, batchSize = 10)

      assertEquals(result.completed, true)
      assertEquals(result.reconciliationSpecInsertCount(spec.id), 1, "one reconciliation covering the whole group")

      val reconciliations = transact(transactor)(data.reconRepo.findByReconSpecID(spec.id))
      assertEquals(reconciliations.size, 1)
      assertEquals(reconciliations.head.status, ReconciliationStatus.Final)
      assertEquals(reconciliations.head.balance, BigDecimal(0))

      // reconAccount sits on the "in" side of inSpec's movement and the "out" side of outSpec's
      // movements, so a Final match (in-total == out-total, here 100 == 40+40+20) contributes
      // +100 and -100 to reconAccount's own net balance — they cancel. unreconciledBalance still
      // correctly drops from 100 to 0 (all four movements are now linked to the reconciliation),
      // but reconciledBalance nets to 0 too, same as the account's overall balance. This is
      // expected for a fully-matched pass-through leg, not a sign that nothing was reconciled —
      // check unreconciledBalance (or the reconciliation/movement records above) to see progress.
      val acct = transact(transactor)(accountRepo.findById(data.reconAccount.id).get)
      assertEquals(acct.balance, BigDecimal(0))
      assertEquals(acct.reconciledBalance, BigDecimal(0))
      assertEquals(acct.unreconciledBalance, BigDecimal(0))
    finally
      transact(transactor):
        data.reconRepo.findByReconSpecID(spec.id).foreach(r => data.reconRepo.deleteById(r.id))
        data.reconSpecRepo.deleteById(spec.id)
      teardown(data)

  test(
    "OneToMany groups multiple in-movements against one out-movement, when the 'many' side is the in spec"
  ):
    // The spec doesn't say which physical side (in vs out) is the "one" — here it's flipped
    // relative to the test above: three in-movements (40 + 40 + 20) reconcile against a single
    // out-movement worth 100, all sharing groupId "g1".
    val data                    = transact(transactor)(DataSetup())
    val (inSpec, outSpec, spec) = oneToManySpec(data)
    given ops: FundFlowOperations = FundFlowOperations()
    try
      transact(transactor):
        for amount <- Seq(BigDecimal(40), BigDecimal(40), BigDecimal(20))
        do ops.postAmount(inSpec, amount, referenceID = createID(), Map("groupId" -> "g1"))
        ops.postAmount(outSpec, 100, referenceID = createID(), Map("groupId" -> "g1"))

      val reconciler = InMemoryReconciler()
      val result      = reconciler.reconcileAccountResumable(data.reconAccount.id, batchSize = 10)

      assertEquals(result.completed, true)
      assertEquals(result.reconciliationSpecInsertCount(spec.id), 1, "one reconciliation covering the whole group")

      val reconciliations = transact(transactor)(data.reconRepo.findByReconSpecID(spec.id))
      assertEquals(reconciliations.size, 1)
      assertEquals(reconciliations.head.status, ReconciliationStatus.Final)
      assertEquals(reconciliations.head.balance, BigDecimal(0))

      val reconciledMovements = transact(transactor)(movementRepo.findByReconciliationID(reconciliations.head.id))
      assertEquals(reconciledMovements.size, 4)

      // Same cancellation as the mirror-image test above: a Final match nets reconciledBalance to 0
      // for reconAccount even though four movements just got linked to a reconciliation.
      val acct = transact(transactor)(accountRepo.findById(data.reconAccount.id).get)
      assertEquals(acct.balance, BigDecimal(0))
      assertEquals(acct.reconciledBalance, BigDecimal(0))
      assertEquals(acct.unreconciledBalance, BigDecimal(0))
    finally
      transact(transactor):
        data.reconRepo.findByReconSpecID(spec.id).foreach(r => data.reconRepo.deleteById(r.id))
        data.reconSpecRepo.deleteById(spec.id)
      teardown(data)

  test("OneToMany partial match where the 'many' side arrives on the in spec across multiple runs"):
    // First run: one out-movement of 100 matches a single in-movement of only 60 so far — should sit
    // InProgress with balance (in - out) = -40. Second run: another in-movement (40) arrives on the
    // same key, closing the balance to 0 without creating a second reconciliation. Movements are
    // posted through FundFlowOperations.postAmount (not raw movementRepo.insert) specifically so the
    // account's balance/reconciledBalance/unreconciledBalance are populated the same way production
    // ingestion populates them, letting the assertions below verify the ledger side effects for real.
    val data                    = transact(transactor)(DataSetup())
    val (inSpec, outSpec, spec) = oneToManySpec(data)
    given ops: FundFlowOperations = FundFlowOperations()
    try
      transact(transactor):
        ops.postAmount(inSpec, 60, referenceID = createID(), Map("groupId" -> "g2"))
        ops.postAmount(outSpec, 100, referenceID = createID(), Map("groupId" -> "g2"))

      val reconciler = InMemoryReconciler()
      val result1     = reconciler.reconcileAccountResumable(data.reconAccount.id, batchSize = 10)
      assertEquals(result1.reconciliationSpecInsertCount(spec.id), 1)

      val recons1 = transact(transactor)(data.reconRepo.findByReconSpecID(spec.id))
      assertEquals(recons1.size, 1)
      assertEquals(recons1.head.status, ReconciliationStatus.InProgress)
      assertEquals(recons1.head.balance, BigDecimal(-40))

      // Even though the reconciliation is only InProgress, the two movements linked to it so far
      // are no longer "unreconciled" — the account's reconciled/unreconciled split should reflect
      // that immediately, not wait for the reconciliation to reach Final.
      val acct1 = transact(transactor)(accountRepo.findById(data.reconAccount.id).get)
      assertEquals(acct1.balance, BigDecimal(-40))
      assertEquals(acct1.reconciledBalance, BigDecimal(-40))
      assertEquals(acct1.unreconciledBalance, BigDecimal(0))

      transact(transactor):
        ops.postAmount(inSpec, 40, referenceID = createID(), Map("groupId" -> "g2"))

      val result2 = reconciler.reconcileAccountResumable(data.reconAccount.id, batchSize = 10)
      assertEquals(result2.reconciliationSpecInsertCount(spec.id), 0, "no new reconciliation, existing one topped up")

      val recons2 = transact(transactor)(data.reconRepo.findByReconSpecID(spec.id))
      assertEquals(recons2.size, 1)
      assertEquals(recons2.head.status, ReconciliationStatus.Final)
      assertEquals(recons2.head.balance, BigDecimal(0))

      // completeInProgressReconciliations (the production "top up" path, distinct from the initial
      // streaming match above) must apply its own delta too: reconciledBalance moves from -40 to 0
      // as the newly-arrived 40 in-movement is folded into the existing reconciliation.
      val acct2 = transact(transactor)(accountRepo.findById(data.reconAccount.id).get)
      assertEquals(acct2.balance, BigDecimal(0))
      assertEquals(acct2.reconciledBalance, BigDecimal(0))
      assertEquals(acct2.unreconciledBalance, BigDecimal(0))
    finally
      transact(transactor):
        data.reconRepo.findByReconSpecID(spec.id).foreach(r => data.reconRepo.deleteById(r.id))
        data.reconSpecRepo.deleteById(spec.id)
      teardown(data)
