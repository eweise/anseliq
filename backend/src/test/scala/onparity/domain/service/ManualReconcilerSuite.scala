package onparity.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import onparity.adapter.driven.{DbDataSource, DisputeRepoLive, EventRepoLive, JobRepoLive}
import onparity.domain.DataSetup
import onparity.domain.model.helper.JSONBProps
import onparity.domain.model.{
  AccountReconciled,
  Dispute,
  DisputeCategory,
  DisputeCommentAdded,
  DisputeStatusChanged,
  DisputeTarget,
  IllegalArgumentError,
  IllegalStateError,
  Movement,
  MovementSpec,
  ReconciliationStatus,
  ReconciliationVoided,
  createID
}
import onparity.domain.port.driven.{DisputeRepo, JobRepo, Txn}
import onparity.testing.rollBackOnly

import javax.sql.DataSource

class ManualReconcilerSuite extends munit.FunSuite:
  given transactor: Transactor   = Transactor(dataSource)
  given dataSource: DataSource   = DbDataSource.create(ConfigFactory.load("test-application.conf"))
  given clockSvc:   ClockService = ClockServiceLive()
  given eventRepo: EventRepoLive()
  given eventStore:  EventStore  = EventStore()
  given jobRepo:     JobRepo     = JobRepoLive()
  given disputeRepo: DisputeRepo = DisputeRepoLive()

  def createMovement(movementSpec: MovementSpec, amount: BigDecimal)(using data: DataSetup): Txn[Movement] =
    val m = Movement(
      inAccountId    = movementSpec.inAccountId,
      outAccountId   = movementSpec.outAccountId,
      movementSpecId = movementSpec.id,
      amount         = amount,
      properties     = JSONBProps(Map())
    )
    data.movementRepo.insert(m)
    m

  test("matchMovements with equal totals creates a Final reconciliation and links both movements"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val manualReconciler = ManualReconciler()

        val inMovement  = createMovement(data.journalToRecon, 100)
        val outMovement = createMovement(data.reportToRecon, 100)

        val recon = manualReconciler.matchMovements(
          data.reconSpec.id,
          Seq(inMovement.id),
          Seq(outMovement.id),
          "test match"
        )

        assertEquals(recon.status, ReconciliationStatus.Final)
        assertEquals(recon.balance, BigDecimal(0))
        assert(
          recon.createdAt.isDefined,
          "createdAt must be set on the returned reconciliation, not just the persisted row"
        )
        assert(
          recon.updatedAt.isDefined,
          "updatedAt must be set on the returned reconciliation, not just the persisted row"
        )

        val updatedIn  = data.movementRepo.findById(inMovement.id).get
        val updatedOut = data.movementRepo.findById(outMovement.id).get
        assertEquals(updatedIn.reconciliationId, Some(recon.id))
        assertEquals(updatedOut.reconciliationId, Some(recon.id))

        val account = data.accountRepo.findById(data.reconAccount.id).get
        assertEquals(account.reconciledBalance, BigDecimal(0)) // +100 (in) -100 (out) = 0
        assertEquals(account.unreconciledBalance, BigDecimal(0))

  test("matchMovements with unequal totals creates an InProgress reconciliation with the residual balance"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val manualReconciler = ManualReconciler()

        val inMovement   = createMovement(data.journalToRecon, 300)
        val outMovement1 = createMovement(data.reportToRecon, 100)
        val outMovement2 = createMovement(data.reportToRecon, 100)

        val recon = manualReconciler.matchMovements(
          data.reconSpec.id,
          Seq(inMovement.id),
          Seq(outMovement1.id, outMovement2.id),
          "test partial match"
        )

        assertEquals(recon.status, ReconciliationStatus.InProgress)
        assertEquals(recon.balance, BigDecimal(100))

        val account = data.accountRepo.findById(data.reconAccount.id).get
        assertEquals(account.reconciledBalance, BigDecimal(100)) // +300 (in) -100 -100 (out) = 100
        assertEquals(account.unreconciledBalance, BigDecimal(-100))

  test("matchMovements rejects a movement that is already reconciled"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val manualReconciler = ManualReconciler()

        val inMovement  = createMovement(data.journalToRecon, 100)
        val outMovement = createMovement(data.reportToRecon, 100)

        manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(outMovement.id), "first match")

        val anotherOut = createMovement(data.reportToRecon, 100)
        intercept[IllegalArgumentError]:
          manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(anotherOut.id), "second match")

  test("matchMovements rejects a movement from the wrong movement spec"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val manualReconciler = ManualReconciler()

        val wrongSpecMovement = createMovement(data.reportToRecon, 100) // belongs to outMovementSpecId, passed as "in"
        val outMovement       = createMovement(data.reportToRecon, 100)

        intercept[IllegalArgumentError]:
          manualReconciler.matchMovements(data.reconSpec.id, Seq(wrongSpecMovement.id), Seq(outMovement.id), "bad spec")

  test("matchMovements rejects an empty selection on either side"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val manualReconciler = ManualReconciler()

        val inMovement = createMovement(data.journalToRecon, 100)

        intercept[IllegalArgumentError]:
          manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(), "no out movements")

  test("unmatch reverses account balances and returns movements to unreconciled"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val manualReconciler = ManualReconciler()

        val inMovement  = createMovement(data.journalToRecon, 100)
        val outMovement = createMovement(data.reportToRecon, 60)
        val recon =
          manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(outMovement.id), "to undo")

        val beforeUnmatch = data.accountRepo.findById(data.reconAccount.id).get
        assertEquals(beforeUnmatch.reconciledBalance, BigDecimal(40)) // +100 -60

        manualReconciler.unmatch(recon.id)

        val voided = data.reconRepo.findById(recon.id).get
        assertEquals(voided.status, ReconciliationStatus.Voided)

        val updatedIn  = data.movementRepo.findById(inMovement.id).get
        val updatedOut = data.movementRepo.findById(outMovement.id).get
        assertEquals(updatedIn.reconciliationId, None)
        assertEquals(updatedOut.reconciliationId, None)

        val afterUnmatch = data.accountRepo.findById(data.reconAccount.id).get
        assertEquals(afterUnmatch.reconciledBalance, BigDecimal(0))
        assertEquals(afterUnmatch.unreconciledBalance, BigDecimal(0))

  test("unmatch on an already-voided reconciliation errors"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val manualReconciler = ManualReconciler()

        val inMovement  = createMovement(data.journalToRecon, 100)
        val outMovement = createMovement(data.reportToRecon, 100)
        val recon =
          manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(outMovement.id), "double undo")

        manualReconciler.unmatch(recon.id)
        intercept[IllegalStateError]:
          manualReconciler.unmatch(recon.id)

  test("writeOff closes an InProgress reconciliation with a reason and leaves account balances untouched"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val manualReconciler = ManualReconciler()

        val inMovement  = createMovement(data.journalToRecon, 100)
        val outMovement = createMovement(data.reportToRecon, 90)
        val recon =
          manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(outMovement.id), "residual match")
        assertEquals(recon.status, ReconciliationStatus.InProgress)

        val beforeWriteOff = data.accountRepo.findById(data.reconAccount.id).get

        manualReconciler.writeOff(recon.id, "bank fee")

        val closed = data.reconRepo.findById(recon.id).get
        assertEquals(closed.status, ReconciliationStatus.Final)
        assertEquals(closed.adjustmentReason, Some("bank fee"))
        assertEquals(closed.balance, BigDecimal(10)) // residual preserved as the historical write-off amount

        val afterWriteOff = data.accountRepo.findById(data.reconAccount.id).get
        assertEquals(afterWriteOff.reconciledBalance, beforeWriteOff.reconciledBalance)
        assertEquals(afterWriteOff.unreconciledBalance, beforeWriteOff.unreconciledBalance)

  test("writeOff on a Final reconciliation errors"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val manualReconciler = ManualReconciler()

        val inMovement  = createMovement(data.journalToRecon, 100)
        val outMovement = createMovement(data.reportToRecon, 100)
        val recon =
          manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(outMovement.id), "clean match")
        assertEquals(recon.status, ReconciliationStatus.Final)

        intercept[IllegalStateError]:
          manualReconciler.writeOff(recon.id, "should not work")

  test("writeOff on a Voided reconciliation errors"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val manualReconciler = ManualReconciler()

        val inMovement  = createMovement(data.journalToRecon, 100)
        val outMovement = createMovement(data.reportToRecon, 100)
        val recon =
          manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(outMovement.id), "voided match")
        manualReconciler.unmatch(recon.id)

        intercept[IllegalStateError]:
          manualReconciler.writeOff(recon.id, "should not work")

  test("after writeOff, InMemoryReconciler no longer tries to top up the reconciliation"):
    // reconcileAccountResumable commits its own transaction internally (unlike the rest of this
    // suite's tests, which call ManualReconciler methods directly within a rollBackOnly wrapper), so
    // it can't see this test's setup unless that setup is committed for real. Same real-commit +
    // explicit-teardown pattern as ReconcileAccountResumableSuite.
    given data: DataSetup = transact(transactor)(DataSetup())
    import data.given
    val manualReconciler    = ManualReconciler()
    val automaticReconciler = InMemoryReconciler()
    try
      val recon = transact(transactor):
        val inMovement  = createMovement(data.journalToRecon, 100)
        val outMovement = createMovement(data.reportToRecon, 90)
        val recon =
          manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(outMovement.id), "will write off")
        manualReconciler.writeOff(recon.id, "bank fee")

        // A fresh unmatched out-movement that would otherwise top up this reconciliation's residual
        createMovement(data.reportToRecon, 10)
        recon

      automaticReconciler.reconcileAccountResumable(data.reconAccount.id)

      val stillClosed = transact(transactor)(data.reconRepo.findById(recon.id).get)
      assertEquals(stillClosed.status, ReconciliationStatus.Final)
      assertEquals(stillClosed.balance, BigDecimal(10))
    finally
      transact(transactor):
        eventRepo
          .findByEventType("AccountReconciled")
          .filter(es => scala.util.Try(es.event[AccountReconciled].accountID == data.reconAccount.id).getOrElse(false))
          .foreach(es => eventRepo.deleteById(es.id))
        data.reconRepo.findByReconSpecID(data.reconSpec.id).foreach(r => data.reconRepo.deleteById(r.id))
        data.reconSpecRepo.deleteById(data.reconSpec.id)
        data.fundFlowConfigRepo.deleteById(data.fundFlowConfig.id)

  test("bulkUnreconcileByFile voids reconciliations and resolves dangling disputes for movements from one file"):
    // bulkUnreconcileByFile commits its own transactions internally (same reason as
    // reconcileAccountResumable above), so setup needs to be committed for real too.
    given data: DataSetup = transact(transactor)(DataSetup())
    import data.given
    val manualReconciler = ManualReconciler()
    val fileSourceEntry  = s"bad-amount-field-${java.util.UUID.randomUUID()}"

    def createMovementForFile(spec: MovementSpec, amount: BigDecimal): Txn[Movement] =
      val m = Movement(
        inAccountId    = spec.inAccountId,
        outAccountId   = spec.outAccountId,
        movementSpecId = spec.id,
        amount         = amount,
        properties     = JSONBProps(Map()),
        metaData       = JSONBProps(Map("file_source_entry" -> fileSourceEntry))
      )
      data.movementRepo.insert(m)
      m

    try
      transact(transactor):
        val inMovement  = createMovementForFile(data.journalToRecon, 100)
        val outMovement = createMovementForFile(data.reportToRecon, 90)
        val recon = manualReconciler.matchMovements(
          data.reconSpec.id,
          Seq(inMovement.id),
          Seq(outMovement.id),
          "wrong amount field"
        )
        // Simulates the AmountMismatch dispute InMemoryReconciler.streamMergeJoin would have raised
        // for this InProgress mismatch — matchMovements itself doesn't raise disputes.
        data.disputeRepo.insertIfNoOpenDispute(
          Dispute(
            referenceId = recon.id,
            targetType  = DisputeTarget.Reconciliation,
            category    = DisputeCategory.AmountMismatch,
            detail      = Some(s"Balance mismatch of ${recon.balance}"),
            createdAt   = Some(clockSvc.now),
            updatedAt   = Some(clockSvc.now)
          )
        )
        // Same file, but never reconciled — should be found but left alone (nothing to unreconcile).
        createMovementForFile(data.journalToRecon, 5)

      val result = manualReconciler.bulkUnreconcileByFile(fileSourceEntry)
      assertEquals(result.movementsFound, 3)
      assertEquals(result.reconciliationsVoided, 1)

      val (recons, movements, dispute, account) = transact(transactor):
        val recons  = data.reconRepo.findByReconSpecID(data.reconSpec.id)
        val reconIds = recons.map(_.id).toSet
        (
          recons,
          data.movementRepo.findByFileSourceEntry(fileSourceEntry),
          // Scoped to this test's own reconciliations, not just category: the suite runs alongside
          // others (e.g. DisputeServiceSuite) that also create/mutate AmountMismatch disputes
          // concurrently, so an unscoped findAll.find(category == ...) can grab someone else's.
          data.disputeRepo.findAll.find(d => reconIds.contains(d.referenceId) && d.category == DisputeCategory.AmountMismatch),
          data.accountRepo.findById(data.reconAccount.id).get
        )

      assertEquals(recons.map(_.status), Seq(ReconciliationStatus.Voided))
      assert(movements.forall(_.reconciliationId.isEmpty), "every movement from the file should be unreconciled")
      assertEquals(
        dispute.map(_.status),
        Some(onparity.domain.model.DisputeStatus.Resolved),
        "the dangling AmountMismatch dispute should have been auto-resolved, not left open"
      )
      assertEquals(account.reconciledBalance, BigDecimal(0), "the +10 residual should be reversed back out")
    finally
      transact(transactor):
        val recons     = data.reconRepo.findByReconSpecID(data.reconSpec.id)
        val reconIds   = recons.map(_.id).toSet
        val disputes   = data.disputeRepo.findAll.filter(d => reconIds.contains(d.referenceId))
        val disputeIds = disputes.map(_.id).toSet

        eventRepo
          .findByEventType("ReconciliationVoided")
          .filter(es => scala.util.Try(reconIds.contains(es.event[ReconciliationVoided].reconciliationID)).getOrElse(false))
          .foreach(es => eventRepo.deleteById(es.id))
        eventRepo
          .findByEventType("DisputeStatusChanged")
          .filter(es => scala.util.Try(disputeIds.contains(es.event[DisputeStatusChanged].disputeID)).getOrElse(false))
          .foreach(es => eventRepo.deleteById(es.id))
        eventRepo
          .findByEventType("DisputeCommentAdded")
          .filter(es => scala.util.Try(disputeIds.contains(es.event[DisputeCommentAdded].disputeID)).getOrElse(false))
          .foreach(es => eventRepo.deleteById(es.id))

        disputes.foreach(d => data.disputeRepo.deleteById(d.id)) // cascades: its comments
        recons.foreach(r => data.reconRepo.deleteById(r.id))
        data.reconSpecRepo.deleteById(data.reconSpec.id)
        data.fundFlowConfigRepo.deleteById(data.fundFlowConfig.id) // cascades: accounts -> movements

  test("reverseMovement refuses a movement that is still reconciled"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val manualReconciler = ManualReconciler()

        val inMovement  = createMovement(data.journalToRecon, 100)
        val outMovement = createMovement(data.reportToRecon, 100)
        manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(outMovement.id), "test")

        val stillReconciled = data.movementRepo.findById(inMovement.id).get
        intercept[IllegalStateError]:
          data.ledger.reverseMovement(stillReconciled)

  test("reverseMovement refuses to reverse an already-reversed movement"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given

        val m        = createMovement(data.journalToRecon, 100)
        val reversal = data.ledger.reverseMovement(m).get
        intercept[IllegalStateError]:
          data.ledger.reverseMovement(reversal)

  test("reverseMovementsByFile posts reversing entries, nets accounts back, and never touches the originals"):
    // reverseMovementsByFile commits its own transactions internally, same reason as
    // bulkUnreconcileByFile above, so setup needs to be committed for real too.
    given data: DataSetup = transact(transactor)(DataSetup())
    import data.given
    val manualReconciler = ManualReconciler()
    val fileSourceEntry  = s"bad-amount-field-${java.util.UUID.randomUUID()}"

    try
      val balanceBefore = transact(transactor)(data.accountRepo.findById(data.reconAccount.id).get)

      transact(transactor):
        data.ledger.recordMovements(
          data.journalToRecon,
          Seq(
            MovementRequest(
              amount      = 100,
              referenceId = createID(),
              properties  = Map.empty,
              metaData    = Map("file_source_entry" -> fileSourceEntry)
            )
          )
        )

      val result = manualReconciler.reverseMovementsByFile(fileSourceEntry)
      assertEquals(result.movementsReversed, 1)
      assertEquals(result.movementsSkippedStillReconciled, 0)

      val balanceAfter = transact(transactor)(data.accountRepo.findById(data.reconAccount.id).get)
      assertEquals(balanceAfter.balance, balanceBefore.balance, "reversal should net the balance back to pre-ingest")
      assertEquals(balanceAfter.unreconciledBalance, balanceBefore.unreconciledBalance)

      val movements = transact(transactor)(data.movementRepo.findByFileSourceEntry(fileSourceEntry))
      assertEquals(movements.size, 2, "original + reversal — the original is never deleted")
      val original = movements.find(m => !data.ledger.isReversal(m)).get
      val reversal = movements.find(data.ledger.isReversal).get
      assertEquals(original.amount, BigDecimal(100), "original untouched")
      assertEquals(reversal.amount, original.amount)
      assertEquals(reversal.inAccountId, original.outAccountId, "reversal swaps the accounts")
      assertEquals(reversal.outAccountId, original.inAccountId)

      // Retrying is safe: the already-reversed original is idempotently skipped (via
      // Ledger.reverseMovement's findExistingReferenceIds check), and the reversal row itself is
      // excluded from candidates by ledger.isReversal, so nothing gets double-reversed.
      val retry = manualReconciler.reverseMovementsByFile(fileSourceEntry)
      assertEquals(retry.movementsReversed, 0)
      assertEquals(retry.movementsSkippedStillReconciled, 0)
    finally
      transact(transactor):
        data.reconSpecRepo.deleteById(data.reconSpec.id)
        data.fundFlowConfigRepo.deleteById(data.fundFlowConfig.id) // cascades: accounts -> movements
