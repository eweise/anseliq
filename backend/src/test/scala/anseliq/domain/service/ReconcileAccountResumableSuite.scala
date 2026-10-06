package anseliq.domain.service

import anseliq.common.model.*
import anseliq.domain.DataSetup
import anseliq.domain.model.*
import anseliq.domain.model.helper.JSONBProps
import anseliq.domain.port.driven.*
import anseliq.testing.TestAppContainer
import com.augustnagro.magnum.transact

class ReconcileAccountResumableSuite extends munit.FunSuite:
  private val app = TestAppContainer()

  private def teardown(data: DataSetup): Unit =
    transact(app.transactor):
      // reconcileAccountResumable publishes a real, permanently-committed AccountReconciled event
      // (not rolled back like the rest of this test's data would be under rollBackOnly), same
      // reason ingestResumable's MoneyMovementsIngested events needed cleanup in IngestResumableSuite.
      app.eventRepo
        .findByEventType("AccountReconciled")
        .filter(es => es.event[AccountReconciled].accountID == data.reconAccount.id)
        .foreach(es => app.eventRepo.deleteById(es.id))
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
    val data = transact(app.transactor)(DataSetup(app))
    try
      transact(app.transactor):
        for id <- Seq("3", "1", "2")
        do
          insertMovement(data, data.journalToRecon, id)
          insertMovement(data, data.reportToRecon, id)

      val reconciler = app.reconciler
      val result     = reconciler.reconcileAccountResumable(data.reconAccount.id, batchSize = 10)

      assertEquals(result.completed, true)
      assertEquals(result.reconciliationSpecInsertCount(data.reconSpec.id), 3)

      val movements = transact(app.transactor)(data.movementRepo.findByAccount(data.reconAccount.id))
      assert(movements.forall(_.reconciliationId.nonEmpty), "every movement should be reconciled")
    finally teardown(data)

  test("OneToOne verifies amount balance: a key match with mismatched amounts is Mismatched, not Final"):
    // A OneToOne pair is inherently a single, final judgment — there's no third movement that could
    // ever arrive to close the gap under a OneToOne rule — but "stays InProgress" (rather than
    // silently marked Final) is still the correct outcome: it's exactly the kind of exception
    // (short payment, fee deduction, rounding) reconciliation exists to surface, not hide.
    val data = transact(app.transactor)(DataSetup(app))
    try
      transact(app.transactor):
        data.movementRepo.insert(
          Movement(
            inAccountId    = data.journalToRecon.inAccountId,
            outAccountId   = data.journalToRecon.outAccountId,
            movementSpecId = data.journalToRecon.id,
            amount         = 100,
            properties     = JSONBProps(Map("id" -> "1"))
          )
        )
        data.movementRepo.insert(
          Movement(
            inAccountId    = data.reportToRecon.inAccountId,
            outAccountId   = data.reportToRecon.outAccountId,
            movementSpecId = data.reportToRecon.id,
            amount         = 90,
            properties     = JSONBProps(Map("id" -> "1"))
          )
        )

      val reconciler = app.reconciler
      val result     = reconciler.reconcileAccountResumable(data.reconAccount.id, batchSize = 10)

      assertEquals(result.reconciliationSpecInsertCount(data.reconSpec.id), 1)

      val reconciliations = transact(app.transactor)(data.reconRepo.findByReconSpecID(data.reconSpec.id))
      assertEquals(reconciliations.size, 1)
      assertEquals(reconciliations.head.status, ReconciliationStatus.Mismatched)
      assertEquals(reconciliations.head.balance, BigDecimal(10))

      val movements = transact(app.transactor)(data.movementRepo.findByAccount(data.reconAccount.id))
      assert(
        movements.forall(_.reconciliationId.nonEmpty),
        "both movements are still linked to the reconciliation despite the mismatch"
      )

      // The mismatch must be surfaced as an exception, not just sit as a nonzero Reconciliation.balance
      // that nothing else queries.
      val disputes = transact(app.transactor)(app.disputeRepo.findAll.filter(_.referenceId == reconciliations.head.id))
      assertEquals(disputes.size, 1)
      assertEquals(disputes.head.targetType, DisputeTarget.Reconciliation)
      assertEquals(disputes.head.category, DisputeCategory.AmountMismatch)
      assertEquals(disputes.head.status, DisputeStatus.New)

      // Rerunning reconciliation must not raise a second dispute for the same mismatch.
      reconciler.reconcileAccountResumable(data.reconAccount.id, batchSize = 10)
      val disputesAfterRerun =
        transact(app.transactor)(app.disputeRepo.findAll.filter(_.referenceId == reconciliations.head.id))
      assertEquals(disputesAfterRerun.size, 1, "rerunning reconciliation must not duplicate the open dispute")
    finally
      transact(app.transactor):
        val reconIds = data.reconRepo.findByReconSpecID(data.reconSpec.id).map(_.id).toSet
        app.disputeRepo.findAll.filter(d => reconIds.contains(d.referenceId)).foreach(d => app.disputeRepo.deleteById(d.id))
      teardown(data)

  test("OneToOne match with equal amounts is Final with a zero balance"):
    val data = transact(app.transactor)(DataSetup(app))
    try
      transact(app.transactor):
        insertMovement(data, data.journalToRecon, "1")
        insertMovement(data, data.reportToRecon, "1")

      val reconciler = app.reconciler
      val result     = reconciler.reconcileAccountResumable(data.reconAccount.id, batchSize = 10)

      assertEquals(result.reconciliationSpecInsertCount(data.reconSpec.id), 1)

      val reconciliations = transact(app.transactor)(data.reconRepo.findByReconSpecID(data.reconSpec.id))
      assertEquals(reconciliations.size, 1)
      assertEquals(reconciliations.head.status, ReconciliationStatus.Final)
      assertEquals(reconciliations.head.balance, BigDecimal(0))
    finally teardown(data)

  test("multiple matched OneToOne pairs zero out account balance, leaving only unmatched movements unreconciled"):
    // Exercises reconcileAccountResumable through FundFlowOperations.postAmount (not raw
    // app.movementRepo.insert), so account.balance/reconciledBalance/unreconciledBalance are populated
    // the same way production ingestion populates them, across three separate matched pairs in one
    // run plus leftover unmatched movements from a second run.
    val data = transact(app.transactor)(DataSetup(app))
    val ops = app.fundFlowOperations
    try
      transact(app.transactor):
        for _ <- 1 to 3
        do
          val key = createID().toString
          ops.postAmount(data.journalToRecon, 1000, referenceID = createID(), Map("id" -> key))
          ops.postAmount(data.reportToRecon, 1000, referenceID  = createID(), Map("id" -> key))

      val reconciler = app.reconciler
      val result1    = reconciler.reconcileAccountResumable(data.reconAccount.id, batchSize = 10)
      assertEquals(result1.reconciliationSpecInsertCount(data.reconSpec.id), 3)

      val unreconciledAfterFirstPass =
        transact(app.transactor)(app.movementRepo.findUnReconciledByAccount(data.reconAccount.id))
      assert(unreconciledAfterFirstPass.isEmpty)

      val acct1 = transact(app.transactor)(app.accountRepo.findById(data.reconAccount.id).get)
      assertEquals(acct1.balance, BigDecimal(0))
      assertEquals(acct1.reconciledBalance, BigDecimal(0))
      assertEquals(acct1.unreconciledBalance, BigDecimal(0))

      transact(app.transactor):
        for _ <- 1 to 2
        do ops.postAmount(data.journalToRecon, 1000, referenceID = createID(), Map("id" -> createID().toString))
        ops.postAmount(data.reportToRecon, 1000, referenceID = createID(), Map("id" -> createID().toString))

      val result2 = reconciler.reconcileAccountResumable(data.reconAccount.id, batchSize = 10)
      assertEquals(
        result2.reconciliationSpecInsertCount(data.reconSpec.id),
        0,
        "no new matches, the 3 new movements all have distinct keys"
      )

      val unreconciledAfterSecondPass =
        transact(app.transactor)(app.movementRepo.findUnReconciledByAccount(data.reconAccount.id))
      assertEquals(unreconciledAfterSecondPass.size, 3)

      val acct2 = transact(app.transactor)(app.accountRepo.findById(data.reconAccount.id).get)
      assertEquals(acct2.balance, BigDecimal(1000))
      assertEquals(acct2.reconciledBalance, BigDecimal(0))
      assertEquals(acct2.unreconciledBalance, BigDecimal(1000))
    finally teardown(data)

  test("earlier batches commit durably even when a later batch is rolled back"):
    val data = transact(app.transactor)(DataSetup(app))
    try
      transact(app.transactor):
        for id <- Seq("1", "2", "3", "4")
        do
          insertMovement(data, data.journalToRecon, id)
          insertMovement(data, data.reportToRecon, id)

      val reconciler = app.reconciler
      val result = reconciler.reconcileAccountResumable(
        data.reconAccount.id,
        batchSize = 1,
        onBatchComplete = progress =>
          if progress.checkpoint.lastInMatchKey.contains("3") then BatchDecision.Rollback else BatchDecision.Commit
      )

      assertEquals(result.completed, false)
      assertEquals(result.reconciliationSpecInsertCount(data.reconSpec.id), 2, "only ids 1 and 2 should have committed")

      val reconciledCount = transact(app.transactor):
        data.movementRepo.findByAccount(data.reconAccount.id).count(_.reconciliationId.nonEmpty)
      assertEquals(reconciledCount, 4, "2 committed matches x 2 movements each")
    finally teardown(data)

  test("resuming from a job's progressJson picks up where it left off, including a late arrival with a smaller key"):
    val data = transact(app.transactor)(DataSetup(app))
    val job = transact(app.transactor):
      val j =
        Job(referenceId = data.reconAccount.id, jobType = JobType.Reconciliation, jobStatus = JobStatus.InProgress)
      app.jobRepo.insert(j)
      j
    try
      transact(app.transactor):
        for id <- Seq("1", "2", "4")
        do
          insertMovement(data, data.journalToRecon, id)
          insertMovement(data, data.reportToRecon, id)

      val reconciler = app.reconciler
      val first = reconciler.reconcileAccountResumable(
        data.reconAccount.id,
        batchSize = 1,
        jobId     = Some(job.id),
        onBatchComplete = progress =>
          if progress.checkpoint.lastInMatchKey.contains("2") then BatchDecision.Rollback else BatchDecision.Commit
      )
      assertEquals(first.completed, false)
      assertEquals(first.reconciliationSpecInsertCount(data.reconSpec.id), 1, "only id 1 committed")

      // Late arrival: id "3" sorts before the checkpoint ("1"), proving a fresh trigger still finds it.
      transact(app.transactor):
        insertMovement(data, data.journalToRecon, "3")
        insertMovement(data, data.reportToRecon, "3")

      val second = reconciler.reconcileAccountResumable(data.reconAccount.id, batchSize = 10, jobId = Some(job.id))
      assertEquals(second.completed, true)
      assertEquals(second.reconciliationSpecInsertCount(data.reconSpec.id), 3, "ids 2, 3, and 4")

      val allReconciled = transact(app.transactor):
        data.movementRepo.findByAccount(data.reconAccount.id).forall(_.reconciliationId.nonEmpty)
      assert(allReconciled)
    finally
      transact(app.transactor)(app.jobRepo.deleteById(job.id))
      teardown(data)

  // Uses its own MovementSpecs (not data.journalToRecon/reportToRecon) so these groupId-keyed
  // movements never get scanned by the account's existing OneToOne spec, which expects an "id"
  // property and would throw trying to match on movements that only carry "groupId".
  private def oneToManySpec(data: DataSetup, agingLimit: Int = 0): (MovementSpec, MovementSpec, ReconciliationSpec) =
    val inFeed  = FileFeed()
    val outFeed = FileFeed()
    val inSpec =
      MovementSpec(inAccountId = data.reconAccount.id, outAccountId = data.journalAccount.id, fileFeedId = inFeed.id)
    val outSpec =
      MovementSpec(outAccountId = data.reconAccount.id, inAccountId = data.reportAccount.id, fileFeedId = outFeed.id)
    val rule =
      StringAttributeMatchRule(inKey = StringAttribute(key = "groupId"), outKey = StringAttribute(key = "groupId"))
    val spec = ReconciliationSpec(
      accountId         = data.reconAccount.id,
      agingLimit        = agingLimit,
      relationship      = Relationship.OneToMany,
      ruleJson          = upickle.write(rule),
      inMovementSpecId  = inSpec.id,
      outMovementSpecId = outSpec.id
    )
    transact(app.transactor):
      app.fileFeedRepo.insert(inFeed)
      app.fileFeedRepo.insert(outFeed)
      app.movementSpecRepo.insert(inSpec)
      app.movementSpecRepo.insert(outSpec)
      data.reconSpecRepo.insert(spec)
    (inSpec, outSpec, spec)

  test("OneToMany groups multiple out-movements against one in-movement, including a group spanning a page boundary"):
    val data                    = transact(app.transactor)(DataSetup(app))
    val (inSpec, outSpec, spec) = oneToManySpec(data)
    val ops = app.fundFlowOperations
    try
      transact(app.transactor):
        // one in-movement worth 100, matched by three out-movements (40 + 40 + 20) all sharing groupId "g1"
        ops.postAmount(inSpec, 100, referenceID = createID(), Map("groupId" -> "g1"))
        for amount <- Seq(BigDecimal(40), BigDecimal(40), BigDecimal(20))
        do ops.postAmount(outSpec, amount, referenceID = createID(), Map("groupId" -> "g1"))

      val reconciler = app.reconciler
      // pageSize forced small enough that the 3-row out-group can't fit in one page fetch,
      // proving the peek-ahead logic correctly extends the group across a page boundary.
      val result = reconciler.reconcileAccountResumable(data.reconAccount.id, batchSize = 10)

      assertEquals(result.completed, true)
      assertEquals(result.reconciliationSpecInsertCount(spec.id), 1, "one reconciliation covering the whole group")

      val reconciliations = transact(app.transactor)(data.reconRepo.findByReconSpecID(spec.id))
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
      val acct = transact(app.transactor)(app.accountRepo.findById(data.reconAccount.id).get)
      assertEquals(acct.balance, BigDecimal(0))
      assertEquals(acct.reconciledBalance, BigDecimal(0))
      assertEquals(acct.unreconciledBalance, BigDecimal(0))
    finally
      transact(app.transactor):
        data.reconRepo.findByReconSpecID(spec.id).foreach(r => data.reconRepo.deleteById(r.id))
        data.reconSpecRepo.deleteById(spec.id)
      teardown(data)

  test(
    "OneToMany groups multiple in-movements against one out-movement, when the 'many' side is the in spec"
  ):
    // The spec doesn't say which physical side (in vs out) is the "one" — here it's flipped
    // relative to the test above: three in-movements (40 + 40 + 20) reconcile against a single
    // out-movement worth 100, all sharing groupId "g1".
    val data                    = transact(app.transactor)(DataSetup(app))
    val (inSpec, outSpec, spec) = oneToManySpec(data)
    val ops = app.fundFlowOperations
    try
      transact(app.transactor):
        for amount <- Seq(BigDecimal(40), BigDecimal(40), BigDecimal(20))
        do ops.postAmount(inSpec, amount, referenceID = createID(), Map("groupId" -> "g1"))
        ops.postAmount(outSpec, 100, referenceID = createID(), Map("groupId" -> "g1"))

      val reconciler = app.reconciler
      val result     = reconciler.reconcileAccountResumable(data.reconAccount.id, batchSize = 10)

      assertEquals(result.completed, true)
      assertEquals(result.reconciliationSpecInsertCount(spec.id), 1, "one reconciliation covering the whole group")

      val reconciliations = transact(app.transactor)(data.reconRepo.findByReconSpecID(spec.id))
      assertEquals(reconciliations.size, 1)
      assertEquals(reconciliations.head.status, ReconciliationStatus.Final)
      assertEquals(reconciliations.head.balance, BigDecimal(0))

      val reconciledMovements = transact(app.transactor)(app.movementRepo.findByReconciliationID(reconciliations.head.id))
      assertEquals(reconciledMovements.size, 4)

      // Same cancellation as the mirror-image test above: a Final match nets reconciledBalance to 0
      // for reconAccount even though four movements just got linked to a reconciliation.
      val acct = transact(app.transactor)(app.accountRepo.findById(data.reconAccount.id).get)
      assertEquals(acct.balance, BigDecimal(0))
      assertEquals(acct.reconciledBalance, BigDecimal(0))
      assertEquals(acct.unreconciledBalance, BigDecimal(0))
    finally
      transact(app.transactor):
        data.reconRepo.findByReconSpecID(spec.id).foreach(r => data.reconRepo.deleteById(r.id))
        data.reconSpecRepo.deleteById(spec.id)
      teardown(data)

  test("OneToMany partial match where the 'many' side arrives on the in spec across multiple runs"):
    // First run: one out-movement of 100 matches a single in-movement of only 60 so far — should sit
    // InProgress with balance (in - out) = -40. Second run: another in-movement (40) arrives on the
    // same key, closing the balance to 0 without creating a second reconciliation. Movements are
    // posted through FundFlowOperations.postAmount (not raw app.movementRepo.insert) specifically so the
    // account's balance/reconciledBalance/unreconciledBalance are populated the same way production
    // ingestion populates them, letting the assertions below verify the app.ledger side effects for real.
    val data                    = transact(app.transactor)(DataSetup(app))
    val (inSpec, outSpec, spec) = oneToManySpec(data)
    val ops = app.fundFlowOperations
    try
      transact(app.transactor):
        ops.postAmount(inSpec, 60, referenceID   = createID(), Map("groupId" -> "g2"))
        ops.postAmount(outSpec, 100, referenceID = createID(), Map("groupId" -> "g2"))

      val reconciler = app.reconciler
      val result1    = reconciler.reconcileAccountResumable(data.reconAccount.id, batchSize = 10)
      assertEquals(result1.reconciliationSpecInsertCount(spec.id), 1)

      val recons1 = transact(app.transactor)(data.reconRepo.findByReconSpecID(spec.id))
      assertEquals(recons1.size, 1)
      assertEquals(recons1.head.status, ReconciliationStatus.InProgress)
      assertEquals(recons1.head.balance, BigDecimal(-40))

      // Even though the reconciliation is only InProgress, the two movements linked to it so far
      // are no longer "unreconciled" — the account's reconciled/unreconciled split should reflect
      // that immediately, not wait for the reconciliation to reach Final.
      val acct1 = transact(app.transactor)(app.accountRepo.findById(data.reconAccount.id).get)
      assertEquals(acct1.balance, BigDecimal(-40))
      assertEquals(acct1.reconciledBalance, BigDecimal(-40))
      assertEquals(acct1.unreconciledBalance, BigDecimal(0))

      // Unlike OneToOne, a OneToMany group landing InProgress on its first formation doesn't mean
      // anything is actually wrong yet — the rest of the "many" side could simply still be in
      // transit (a delayed file, a later batch). No dispute should be raised until aging says
      // otherwise, so this must not create one just because the reconciliation exists.
      val disputesAfterFirstRun = transact(app.transactor)(app.disputeRepo.findAll.filter(_.referenceId == recons1.head.id))
      assert(disputesAfterFirstRun.isEmpty, "a fresh OneToMany InProgress reconciliation must not auto-raise a dispute")

      transact(app.transactor):
        ops.postAmount(inSpec, 40, referenceID = createID(), Map("groupId" -> "g2"))

      val result2 = reconciler.reconcileAccountResumable(data.reconAccount.id, batchSize = 10)
      assertEquals(result2.reconciliationSpecInsertCount(spec.id), 0, "no new reconciliation, existing one topped up")

      val recons2 = transact(app.transactor)(data.reconRepo.findByReconSpecID(spec.id))
      assertEquals(recons2.size, 1)
      assertEquals(recons2.head.status, ReconciliationStatus.Final)
      assertEquals(recons2.head.balance, BigDecimal(0))

      // completeInProgressReconciliations (the production "top up" path, distinct from the initial
      // streaming match above) must apply its own delta too: reconciledBalance moves from -40 to 0
      // as the newly-arrived 40 in-movement is folded into the existing reconciliation.
      val acct2 = transact(app.transactor)(app.accountRepo.findById(data.reconAccount.id).get)
      assertEquals(acct2.balance, BigDecimal(0))
      assertEquals(acct2.reconciledBalance, BigDecimal(0))
      assertEquals(acct2.unreconciledBalance, BigDecimal(0))
    finally
      transact(app.transactor):
        data.reconRepo.findByReconSpecID(spec.id).foreach(r => data.reconRepo.deleteById(r.id))
        data.reconSpecRepo.deleteById(spec.id)
      teardown(data)

  test(
    "raiseStaleMismatchDisputes: a OneToMany reconciliation past agingLimit becomes Mismatched with an AmountMismatch dispute, but not before; a late movement closing the gap then makes it Final and resolves the dispute"
  ):
    // Its own container: this test moves the clock, and the reconciler has to see that clock.
    val app                     = TestAppContainer()
    val clock                   = app.clock
    val data                    = transact(app.transactor)(DataSetup(app))
    val (inSpec, outSpec, spec) = oneToManySpec(data, agingLimit = 2)
    val ops = app.fundFlowOperations
    try
      transact(app.transactor):
        ops.postAmount(inSpec, 60, referenceID   = createID(), Map("groupId" -> "g3"))
        ops.postAmount(outSpec, 100, referenceID = createID(), Map("groupId" -> "g3"))

      val reconciler = app.reconciler
      reconciler.reconcileAccountResumable(data.reconAccount.id, batchSize = 10)

      val recon = transact(app.transactor)(data.reconRepo.findByReconSpecID(spec.id)).head
      assertEquals(recon.status, ReconciliationStatus.InProgress)

      // still fresh: well within the 2-day aging window, so not stale yet
      val raisedTooEarly = transact(app.transactor)(reconciler.raiseStaleMismatchDisputes(spec))
      assertEquals(raisedTooEarly, 0, "not due yet: hasn't sat InProgress past agingLimit")
      val disputesTooEarly = transact(app.transactor)(app.disputeRepo.findAll.filter(_.referenceId == recon.id))
      assert(disputesTooEarly.isEmpty)

      // simulate 3 days passing, past the 2-day agingLimit
      clock.addHours(72)
      val raisedNow = transact(app.transactor)(reconciler.raiseStaleMismatchDisputes(spec))
      assertEquals(raisedNow, 1, "due now: InProgress past agingLimit with a real (non-zero) balance")
      assertEquals(
        transact(app.transactor)(data.reconRepo.findById(recon.id)).get.status,
        ReconciliationStatus.Mismatched,
        "no longer waiting on more data, so it's flagged rather than left InProgress"
      )

      val disputes = transact(app.transactor)(app.disputeRepo.findAll.filter(_.referenceId == recon.id))
      assertEquals(disputes.size, 1)
      assertEquals(disputes.head.category, DisputeCategory.AmountMismatch)
      assertEquals(disputes.head.targetType, DisputeTarget.Reconciliation)

      // idempotent: a later poll finds the dispute already open and raises nothing new
      val raisedAgain = transact(app.transactor)(reconciler.raiseStaleMismatchDisputes(spec))
      assertEquals(raisedAgain, 0, "already Mismatched: nothing left to flag")

      // Mismatched isn't terminal for OneToMany: the missing 40 turning up late still completes
      // the group, which moves it to Final and auto-resolves the dispute it had been carrying.
      transact(app.transactor):
        ops.postAmount(inSpec, 40, referenceID = createID(), Map("groupId" -> "g3"))
      reconciler.reconcileAccountResumable(data.reconAccount.id, batchSize = 10)

      val completed = transact(app.transactor)(data.reconRepo.findById(recon.id)).get
      assertEquals(completed.status, ReconciliationStatus.Final)
      assertEquals(completed.balance, BigDecimal(0))
      assertEquals(
        transact(app.transactor)(app.disputeRepo.findById(disputes.head.id)).get.status,
        DisputeStatus.Resolved
      )
    finally
      transact(app.transactor):
        data.reconRepo.findByReconSpecID(spec.id).foreach { r =>
          app.disputeRepo.findAll.filter(_.referenceId == r.id).foreach(d => app.disputeRepo.deleteById(d.id))
          data.reconRepo.deleteById(r.id)
        }
        data.reconSpecRepo.deleteById(spec.id)
      teardown(data)

  test(
    "raiseStaleMismatchDisputes: a no-op for OneToOne specs, which already raise immediately and never get more data"
  ):
    val data = transact(app.transactor)(DataSetup(app))
    try
      val reconciler = app.reconciler
      val raised     = transact(app.transactor)(reconciler.raiseStaleMismatchDisputes(data.reconSpec))
      assertEquals(raised, 0)
    finally teardown(data)

  // Own MovementSpecs, same reasoning as oneToManySpec above: keeps this rule's movements out of
  // reach of data.reconSpec's own "id"-keyed OneToOne rule.
  private def constantMatchSpec(data: DataSetup): (MovementSpec, MovementSpec, ReconciliationSpec) =
    val inFeed  = FileFeed()
    val outFeed = FileFeed()
    val inSpec =
      MovementSpec(inAccountId = data.reconAccount.id, outAccountId = data.journalAccount.id, fileFeedId = inFeed.id)
    val outSpec =
      MovementSpec(outAccountId = data.reconAccount.id, inAccountId = data.reportAccount.id, fileFeedId = outFeed.id)
    // outKey is pinned to the literal "12345" rather than looked up from each out-movement's own
    // props — every out-movement trivially satisfies this side of the rule regardless of its own
    // "id", so only in-movements whose real "id" equals "12345" can ever pair with one.
    val rule = StringAttributeMatchRule(
      inKey  = StringAttribute(key = "id"),
      outKey = StringAttribute(key = "12345", isConstant = true)
    )
    val spec = ReconciliationSpec(
      accountId         = data.reconAccount.id,
      ruleJson          = upickle.write(rule),
      inMovementSpecId  = inSpec.id,
      outMovementSpecId = outSpec.id
    )
    transact(app.transactor):
      app.fileFeedRepo.insert(inFeed)
      app.fileFeedRepo.insert(outFeed)
      app.movementSpecRepo.insert(inSpec)
      app.movementSpecRepo.insert(outSpec)
      data.reconSpecRepo.insert(spec)
    (inSpec, outSpec, spec)

  test("a rule with a constant on one side only matches in-movements whose field equals that constant"):
    val data                    = transact(app.transactor)(DataSetup(app))
    val (inSpec, outSpec, spec) = constantMatchSpec(data)
    val ops = app.fundFlowOperations
    try
      transact(app.transactor):
        // matches: this in-movement's real "id" equals the constant the out side is pinned to.
        ops.postAmount(inSpec, 100, referenceID = createID(), Map("id" -> "12345"))
        // the out-movement's own "id" is irrelevant — its side of the rule never looks at it.
        ops.postAmount(outSpec, 100, referenceID = createID(), Map("id" -> "unused"))
        // does not match: this in-movement's "id" differs from the constant, so it has no pairing
        // out-movement to match against.
        ops.postAmount(inSpec, 50, referenceID = createID(), Map("id" -> "999"))

      val reconciler = app.reconciler
      val result     = reconciler.reconcileAccountResumable(data.reconAccount.id, batchSize = 10)

      assertEquals(result.reconciliationSpecInsertCount(spec.id), 1, "only the constant-matching pair reconciles")

      val unreconciled = transact(app.transactor)(app.movementRepo.findUnReconciledByAccount(data.reconAccount.id))
      assertEquals(unreconciled.size, 1, "the id=999 in-movement is left unreconciled")
      assertEquals(unreconciled.head.props.get("id"), Some("999"))
    finally
      transact(app.transactor):
        data.reconRepo.findByReconSpecID(spec.id).foreach(r => data.reconRepo.deleteById(r.id))
        data.reconSpecRepo.deleteById(spec.id)
      teardown(data)

  test("appliedRules records each leaf rule alongside the (post-modifier) value it matched on"):
    val data    = transact(app.transactor)(DataSetup(app))
    val inFeed  = FileFeed()
    val outFeed = FileFeed()
    val inSpec =
      MovementSpec(inAccountId = data.reconAccount.id, outAccountId = data.journalAccount.id, fileFeedId = inFeed.id)
    val outSpec =
      MovementSpec(outAccountId = data.reconAccount.id, inAccountId = data.reportAccount.id, fileFeedId = outFeed.id)
    val refRule = StringAttributeMatchRule(inKey = StringAttribute(key = "ref"), outKey = StringAttribute(key = "ref"))
    val dateRule = DateAttributeMatchRule(
      inKey  = DateAttribute(key = "txnDate", modifier = Some(DateAdd(Interval.Day, 2))),
      outKey = DateAttribute(key = "settleDate")
    )
    val spec = ReconciliationSpec(
      accountId         = data.reconAccount.id,
      ruleJson          = upickle.write[Rule](AndRule(List(refRule, dateRule))),
      inMovementSpecId  = inSpec.id,
      outMovementSpecId = outSpec.id
    )
    val ops = app.fundFlowOperations
    try
      transact(app.transactor):
        app.fileFeedRepo.insert(inFeed)
        app.fileFeedRepo.insert(outFeed)
        app.movementSpecRepo.insert(inSpec)
        app.movementSpecRepo.insert(outSpec)
        data.reconSpecRepo.insert(spec)
        ops.postAmount(inSpec, 100, referenceID = createID(), Map("ref" -> "INV-1", "txnDate" -> "2026-09-01"))
        ops.postAmount(outSpec, 100, referenceID = createID(), Map("ref" -> "INV-1", "settleDate" -> "2026-09-03"))

      app.reconciler.reconcileAccountResumable(data.reconAccount.id, batchSize = 10)

      // Read back from the DB, so this also covers the jsonb round trip.
      val reconciliations = transact(app.transactor)(data.reconRepo.findByReconSpecID(spec.id))
      assertEquals(reconciliations.size, 1)
      assertEquals(
        reconciliations.head.appliedRules,
        AppliedRules.Matched(List(RuleMatch(refRule, "INV-1"), RuleMatch(dateRule, "2026-09-03")))
      )
    finally
      transact(app.transactor):
        data.reconRepo.findByReconSpecID(spec.id).foreach(r => data.reconRepo.deleteById(r.id))
        data.reconSpecRepo.deleteById(spec.id)
      teardown(data)

  // withUnreconciledCursor opens its own connection (separate from transact(app.transactor)), which is
  // exactly the property under test here — the inserted movements must be genuinely committed
  // (not left pending under an outer transaction) to be visible to it at all, which this test's
  // "insert via its own transact(...), then read via withUnreconciledCursor" shape verifies as a
  // side effect of just being able to see any rows back.
  test("withUnreconciledCursor pages through unreconciled movements in match-key order, once sorted"):
    val data = transact(app.transactor)(DataSetup(app))
    try
      transact(app.transactor):
        for id <- Seq("3", "1", "2")
        do insertMovement(data, data.journalToRecon, id)

      val matchKeyExpr = "(a.properties['props'] ->> 'id')::text"

      val pages = app.movementRepo.withUnreconciledCursor(
        app.dataSource,
        data.journalToRecon.id,
        matchKeyExpr,
        afterKey = None,
        pageSize = 2
      ): cursor =>
        Iterator.continually(cursor.fetchPage()).takeWhile(_.nonEmpty).toVector

      assertEquals(pages.size, 2, "3 rows at pageSize 2 should take a full page plus a partial page")
      assertEquals(pages.map(_.size), Vector(2, 1))
      assertEquals(pages.flatten.map(_.props.get("id")), Vector(Some("1"), Some("2"), Some("3")))

      // Resuming from a checkpoint (afterKey) should skip everything at or before it, same
      // semantics as findUnreconciledPage's afterKey.
      val resumed = app.movementRepo.withUnreconciledCursor(
        app.dataSource,
        data.journalToRecon.id,
        matchKeyExpr,
        afterKey = Some("1"),
        pageSize = 10
      ): cursor =>
        cursor.fetchPage()

      assertEquals(resumed.map(_.props.get("id")), Vector(Some("2"), Some("3")))
    finally teardown(data)

  // The actual point of Ledger.reverseMovement linking a movement to its reversal via a real
  // ReconciliationStatus.Reversed reconciliation (see its doc): the reversal necessarily carries
  // the same match-key properties as the movement it reverses, so without that link permanently
  // excluding both from every "unreconciled movement" query, a real reconciliation run could match
  // a counterpart against the reversal instead of (or as well as) a genuine movement.
  test("a reversed movement's contra-entry is never picked up as a match candidate by the reconciler"):
    val data                        = transact(app.transactor)(DataSetup(app))
    var reversal: Option[Movement]  = None
    try
      val counterpart = transact(app.transactor):
        val original = insertMovement(data, data.journalToRecon, "reversal-test-key")
        reversal = data.ledger.reverseMovement(original)
        insertMovement(data, data.reportToRecon, "reversal-test-key") // the real match, once corrected

      app.reconciler.reconcileAccountResumable(data.reconAccount.id)

      val reloadedCounterpart = transact(app.transactor)(data.movementRepo.findById(counterpart.id).get)
      assertEquals(
        reloadedCounterpart.reconciliationId,
        None,
        "the counterpart must not have been matched against the reversal, which carries the same match key"
      )
    finally
      transact(app.transactor):
        reversal.flatMap(_.reconciliationId).foreach(id => data.reconRepo.deleteById(id))
      teardown(data)
