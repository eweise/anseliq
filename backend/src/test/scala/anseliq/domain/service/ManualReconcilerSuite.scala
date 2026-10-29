package anseliq.domain.service

import anseliq.domain.DataSetup
import anseliq.domain.model.*
import anseliq.domain.model.helper.JSONBProps
import anseliq.domain.port.driven.Txn
import anseliq.testing.{TestAppContainer, rollBackOnly}
import com.augustnagro.magnum.transact

class ManualReconcilerSuite extends munit.FunSuite:
  private val app = TestAppContainer()

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
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val manualReconciler = app.manualReconciler

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

  test("matchMovements with unequal totals under a OneToOne spec is Mismatched and raises an AmountMismatch exception"):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val manualReconciler = app.manualReconciler

        val inMovement   = createMovement(data.journalToRecon, 300)
        val outMovement1 = createMovement(data.reportToRecon, 100)
        val outMovement2 = createMovement(data.reportToRecon, 100)

        val recon = manualReconciler.matchMovements(
          data.reconSpec.id,
          Seq(inMovement.id),
          Seq(outMovement1.id, outMovement2.id),
          "test partial match"
        )

        assertEquals(recon.status, ReconciliationStatus.Mismatched)
        assertEquals(recon.balance, BigDecimal(100))
        assert(
          data.exceptionRepo.findOpenByReferenceIdAndCategory(recon.id, ExceptionCategory.AmountMismatch).isDefined
        )

        val account = data.accountRepo.findById(data.reconAccount.id).get
        assertEquals(account.reconciledBalance, BigDecimal(100)) // +300 (in) -100 -100 (out) = 100
        assertEquals(account.unreconciledBalance, BigDecimal(-100))

  // Under OneToMany more of the "many" side can still arrive later, so an unbalanced manual group
  // is left InProgress with no exception — the same as an automatic OneToMany group.
  test("matchMovements with unequal totals under a OneToMany spec stays InProgress with no exception"):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val manualReconciler = app.manualReconciler
        data.reconSpecRepo.update(data.reconSpec.copy(relationship = Relationship.OneToMany))

        val inMovement  = createMovement(data.journalToRecon, 300)
        val outMovement = createMovement(data.reportToRecon, 100)
        val recon =
          manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(outMovement.id), "partial")

        assertEquals(recon.status, ReconciliationStatus.InProgress)
        assertEquals(
          data.exceptionRepo.findOpenByReferenceIdAndCategory(recon.id, ExceptionCategory.AmountMismatch),
          None
        )

  test("matchMovements rejects a movement that is already reconciled"):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val manualReconciler = app.manualReconciler

        val inMovement  = createMovement(data.journalToRecon, 100)
        val outMovement = createMovement(data.reportToRecon, 100)

        manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(outMovement.id), "first match")

        val anotherOut = createMovement(data.reportToRecon, 100)
        intercept[IllegalArgumentError]:
          manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(anotherOut.id), "second match")

  test("matchMovements rejects a movement from the wrong movement spec"):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val manualReconciler = app.manualReconciler

        val wrongSpecMovement = createMovement(data.reportToRecon, 100) // belongs to outMovementSpecId, passed as "in"
        val outMovement       = createMovement(data.reportToRecon, 100)

        intercept[IllegalArgumentError]:
          manualReconciler.matchMovements(data.reconSpec.id, Seq(wrongSpecMovement.id), Seq(outMovement.id), "bad spec")

  test("matchMovements rejects an empty selection on either side"):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val manualReconciler = app.manualReconciler

        val inMovement = createMovement(data.journalToRecon, 100)

        intercept[IllegalArgumentError]:
          manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(), "no out movements")

  test("unmatch reverses account balances and returns movements to unreconciled"):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val manualReconciler = app.manualReconciler

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
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val manualReconciler = app.manualReconciler

        val inMovement  = createMovement(data.journalToRecon, 100)
        val outMovement = createMovement(data.reportToRecon, 100)
        val recon =
          manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(outMovement.id), "double undo")

        manualReconciler.unmatch(recon.id)
        intercept[IllegalStateError]:
          manualReconciler.unmatch(recon.id)

  // in 100 (reconAccount <- journalAccount) vs out 90 (reconAccount -> reportAccount): matching
  // leaves +10 on reconAccount's reconciledBalance, which every writeOff test below closes out.
  private def mismatchedRecon(manualReconciler: ManualReconciler)(using
    data: DataSetup
  ): Txn[(Movement, Movement, Reconciliation)] =
    val inMovement  = createMovement(data.journalToRecon, 100)
    val outMovement = createMovement(data.reportToRecon, 90)
    val recon =
      manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(outMovement.id), "residual match")
    assertEquals(recon.status, ReconciliationStatus.Mismatched)
    (inMovement, outMovement, recon)

  test(
    "writeOff against the out-movement posts the difference to that movement's other account and closes the reconciliation"
  ):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val manualReconciler = app.manualReconciler

        val (_, outMovement, recon) = mismatchedRecon(manualReconciler)
        val reconBefore             = data.accountRepo.findById(data.reconAccount.id).get
        val reportBefore            = data.accountRepo.findById(data.reportAccount.id).get

        val adjustment = manualReconciler.writeOff(recon.id, outMovement.id, "bank fee")

        assertEquals(adjustment.movementType, Some(MovementType.ManualAdjustment))
        // Read back, not just the returned value, so the new columns' round-trip is covered too.
        val stored = data.movementRepo.findById(adjustment.id).get
        assertEquals(stored.adjustmentType, Some(AdjustmentType.WriteOff))
        assertEquals(stored.adjustmentNote, Some("bank fee"))
        assertEquals(adjustment.amount, BigDecimal(10))
        assertEquals(adjustment.outAccountId, data.reconAccount.id)
        assertEquals(adjustment.inAccountId, data.reportAccount.id)

        val closed = data.reconRepo.findById(recon.id).get
        assertEquals(closed.status, ReconciliationStatus.Final)
        assertEquals(closed.adjustmentReason, Some("bank fee"))
        assertEquals(closed.balance, BigDecimal(0))
        val linked = data.movementRepo.findByReconciliationID(recon.id)
        assert(linked.exists(_.id == adjustment.id), "adjustment is linked into the reconciliation")
        assertEquals(linked.map(_.signedAmountFor(data.reconAccount.id)).sum, BigDecimal(0))

        val reconAfter  = data.accountRepo.findById(data.reconAccount.id).get
        val reportAfter = data.accountRepo.findById(data.reportAccount.id).get
        assertEquals(reconAfter.balance, reconBefore.balance - 10)
        assertEquals(reconAfter.reconciledBalance, reconBefore.reconciledBalance - 10)
        assertEquals(reconAfter.unreconciledBalance, reconBefore.unreconciledBalance)
        assertEquals(reportAfter.balance, reportBefore.balance + 10)

  test("writeOff against the in-movement posts the difference to that movement's other account instead"):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val manualReconciler = app.manualReconciler

        val (inMovement, _, recon) = mismatchedRecon(manualReconciler)
        val journalBefore          = data.accountRepo.findById(data.journalAccount.id).get
        val reportBefore           = data.accountRepo.findById(data.reportAccount.id).get

        val adjustment = manualReconciler.writeOff(recon.id, inMovement.id, "over-posted journal entry")

        assertEquals(adjustment.outAccountId, data.reconAccount.id)
        assertEquals(adjustment.inAccountId, data.journalAccount.id)
        assertEquals(data.accountRepo.findById(data.journalAccount.id).get.balance, journalBefore.balance + 10)
        assertEquals(data.accountRepo.findById(data.reportAccount.id).get.balance, reportBefore.balance)
        assertEquals(data.reconRepo.findById(recon.id).get.balance, BigDecimal(0))

  test("writeOff rejects a movement that isn't part of the reconciliation"):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val manualReconciler = app.manualReconciler

        val (_, _, recon) = mismatchedRecon(manualReconciler)
        val unrelated     = createMovement(data.reportToRecon, 5)

        intercept[IllegalArgumentError]:
          manualReconciler.writeOff(recon.id, unrelated.id, "wrong movement")

  test("writeOff auto-resolves the reconciliation's open AmountMismatch exception"):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val manualReconciler = app.manualReconciler

        val (_, outMovement, recon) = mismatchedRecon(manualReconciler)
        // Raised by matchMovements itself, since the match left it Mismatched.
        val exception =
          data.exceptionRepo.findOpenByReferenceIdAndCategory(recon.id, ExceptionCategory.AmountMismatch).get

        manualReconciler.writeOff(recon.id, outMovement.id, "bank fee")

        assertEquals(data.exceptionRepo.findById(exception.id).get.status, ExceptionStatus.Resolved)

  test("unmatch refuses a written-off reconciliation"):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val manualReconciler = app.manualReconciler

        val (_, outMovement, recon) = mismatchedRecon(manualReconciler)
        manualReconciler.writeOff(recon.id, outMovement.id, "bank fee")

        intercept[IllegalStateError]:
          manualReconciler.unmatch(recon.id)
        assertEquals(data.reconRepo.findById(recon.id).get.status, ReconciliationStatus.Final)

  test("reverseWriteOff reverses the adjustment, keeps the match, and puts the reconciliation back to Mismatched"):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val manualReconciler = app.manualReconciler

        val (inMovement, outMovement, recon) = mismatchedRecon(manualReconciler)
        val reconBeforeWriteOff              = data.accountRepo.findById(data.reconAccount.id).get
        val adjustment                       = manualReconciler.writeOff(recon.id, outMovement.id, "bank fee")

        manualReconciler.reverseWriteOff(recon.id, "fee was refunded")

        val reverted = data.reconRepo.findById(recon.id).get
        assertEquals(reverted.status, ReconciliationStatus.Mismatched)
        assertEquals(reverted.balance, BigDecimal(10)) // in 100 - out 90, as before the write-off
        assertEquals(reverted.adjustmentReason, None)

        // The match is kept; only the adjustment leaves it, linked to its own reversal instead.
        assertEquals(data.movementRepo.findById(inMovement.id).get.reconciliationId, Some(recon.id))
        assertEquals(data.movementRepo.findById(outMovement.id).get.reconciliationId, Some(recon.id))
        val reversedVia =
          data.movementRepo.findById(adjustment.id).get.reconciliationId.flatMap(data.reconRepo.findById)
        assertEquals(reversedVia.map(_.status), Some(ReconciliationStatus.Reversed))

        // Balances are back to where the mismatch left them.
        val reconAfter = data.accountRepo.findById(data.reconAccount.id).get
        assertEquals(reconAfter.balance, reconBeforeWriteOff.balance)
        assertEquals(reconAfter.reconciledBalance, reconBeforeWriteOff.reconciledBalance)

        // A new open exception (the write-off resolved the first), carrying the reason.
        val exception = data.exceptionRepo.findOpenByReferenceIdAndCategory(recon.id, ExceptionCategory.AmountMismatch)
        assert(exception.exists(_.detail.exists(_.contains("fee was refunded"))))

        // The two threads link to each other, so either leads to the other.
        val all     = data.exceptionRepo.findByReferenceIdAndCategory(recon.id, ExceptionCategory.AmountMismatch)
        val earlier = all.find(_.status == ExceptionStatus.Resolved).get
        val later   = exception.get
        def linksFrom(d: ExceptionCase) =
          data.exceptionCommentRepo.findByExceptionId(d.id).flatMap(_.relatedExceptionId)
        assertEquals(linksFrom(earlier), Vector(later.id))
        assertEquals(linksFrom(later), Vector(earlier.id))

        // And now it can be unmatched like any other mismatch.
        manualReconciler.unmatch(recon.id)
        assertEquals(data.reconRepo.findById(recon.id).get.status, ReconciliationStatus.Voided)

  // Regression: every write-off of a reconciliation used to get the same adjustment reference id,
  // so a second write-off's adjustment was silently dropped by the insert's ON CONFLICT DO NOTHING
  // after the account balances had already moved — and the second reverse then found nothing.
  test("a reconciliation can be written off and reversed more than once, with balances back where they started"):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val manualReconciler = app.manualReconciler

        val (_, outMovement, recon) = mismatchedRecon(manualReconciler)
        val before                  = data.accountRepo.findById(data.reconAccount.id).get

        manualReconciler.writeOff(recon.id, outMovement.id, "bank fee")
        manualReconciler.reverseWriteOff(recon.id, "fee was refunded")
        val second = manualReconciler.writeOff(recon.id, outMovement.id, "bank fee after all")

        assert(data.movementRepo.findById(second.id).isDefined, "the second write-off's adjustment must be stored")
        assert(data.movementRepo.findByReconciliationID(recon.id).exists(_.id == second.id))

        manualReconciler.reverseWriteOff(recon.id, "refunded again")

        val reverted = data.reconRepo.findById(recon.id).get
        assertEquals(reverted.status, ReconciliationStatus.Mismatched)
        assertEquals(reverted.balance, BigDecimal(10))
        val after = data.accountRepo.findById(data.reconAccount.id).get
        assertEquals(after.balance, before.balance)
        assertEquals(after.reconciledBalance, before.reconciledBalance)

  test("reverseWriteOff rejects a reconciliation that wasn't written off, and a blank reason"):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val manualReconciler = app.manualReconciler

        val (_, outMovement, recon) = mismatchedRecon(manualReconciler)
        intercept[IllegalStateError]:
          manualReconciler.reverseWriteOff(recon.id, "nothing to reverse")

        manualReconciler.writeOff(recon.id, outMovement.id, "bank fee")
        intercept[IllegalArgumentError]:
          manualReconciler.reverseWriteOff(recon.id, "  ")

  test("writeOff on a Final reconciliation errors"):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val manualReconciler = app.manualReconciler

        val inMovement  = createMovement(data.journalToRecon, 100)
        val outMovement = createMovement(data.reportToRecon, 100)
        val recon =
          manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(outMovement.id), "clean match")
        assertEquals(recon.status, ReconciliationStatus.Final)

        intercept[IllegalStateError]:
          manualReconciler.writeOff(recon.id, outMovement.id, "should not work")

  test("writeOff on a Voided reconciliation errors"):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val manualReconciler = app.manualReconciler

        val inMovement  = createMovement(data.journalToRecon, 100)
        val outMovement = createMovement(data.reportToRecon, 100)
        val recon =
          manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(outMovement.id), "voided match")
        manualReconciler.unmatch(recon.id)

        intercept[IllegalStateError]:
          manualReconciler.writeOff(recon.id, outMovement.id, "should not work")

  test("after writeOff, InMemoryReconciler no longer tries to top up the reconciliation"):
    // reconcileAccountResumable commits its own transaction internally (unlike the rest of this
    // suite's tests, which call ManualReconciler methods directly within a rollBackOnly wrapper), so
    // it can't see this test's setup unless that setup is committed for real. Same real-commit +
    // explicit-teardown pattern as ReconcileAccountResumableSuite.
    val data                = transact(app.transactor)(DataSetup(app))
    given DataSetup         = data
    val manualReconciler    = app.manualReconciler
    val automaticReconciler = app.reconciler
    try
      val recon = transact(app.transactor):
        val inMovement  = createMovement(data.journalToRecon, 100)
        val outMovement = createMovement(data.reportToRecon, 90)
        val recon =
          manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(outMovement.id), "will write off")
        manualReconciler.writeOff(recon.id, outMovement.id, "bank fee")

        // A fresh unmatched out-movement that would otherwise top up this reconciliation's residual
        createMovement(data.reportToRecon, 10)
        recon

      automaticReconciler.reconcileAccountResumable(data.reconAccount.id)

      val stillClosed = transact(app.transactor)(data.reconRepo.findById(recon.id).get)
      assertEquals(stillClosed.status, ReconciliationStatus.Final)
      assertEquals(stillClosed.balance, BigDecimal(0))
    finally
      transact(app.transactor):
        app.eventRepo
          .findByEventType("AccountReconciled")
          .filter(es => scala.util.Try(es.event[AccountReconciled].accountID == data.reconAccount.id).getOrElse(false))
          .foreach(es => app.eventRepo.deleteById(es.id))
        // matchMovements raises an AmountMismatch exception for the unbalanced match (writeOff then
        // resolves it), so it has to go too — otherwise it's left committed, pointing at a
        // reconciliation that no longer exists, and the Exceptions page 404s looking it up.
        data.reconRepo.findByReconSpecID(data.reconSpec.id).foreach { r =>
          data.exceptionRepo.findAll.filter(_.referenceId == r.id).foreach(d => data.exceptionRepo.deleteById(d.id))
          data.reconRepo.deleteById(r.id)
        }
        data.reconSpecRepo.deleteById(data.reconSpec.id)
        data.ledgerRepo.deleteById(data.ledger.id)

  test("bulkUnreconcileByFile voids reconciliations and resolves dangling exceptions for movements from one file"):
    // bulkUnreconcileByFile commits its own transactions internally (same reason as
    // reconcileAccountResumable above), so setup needs to be committed for real too.
    val data             = transact(app.transactor)(DataSetup(app))
    val manualReconciler = app.manualReconciler
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
      transact(app.transactor):
        val inMovement  = createMovementForFile(data.journalToRecon, 100)
        val outMovement = createMovementForFile(data.reportToRecon, 90)
        val recon = manualReconciler.matchMovements(
          data.reconSpec.id,
          Seq(inMovement.id),
          Seq(outMovement.id),
          "wrong amount field"
        )
        // The match is unbalanced under the spec's OneToOne rule, so matchMovements has already
        // made it Mismatched and raised its AmountMismatch exception.
        assertEquals(recon.status, ReconciliationStatus.Mismatched)
        // Same file, but never reconciled — should be found but left alone (nothing to unreconcile).
        createMovementForFile(data.journalToRecon, 5)

      val result = manualReconciler.bulkUnreconcileByFile(fileSourceEntry)
      assertEquals(result.movementsFound, 3)
      assertEquals(result.reconciliationsVoided, 1)

      val (recons, movements, exception, account) = transact(app.transactor):
        val recons   = data.reconRepo.findByReconSpecID(data.reconSpec.id)
        val reconIds = recons.map(_.id).toSet
        (
          recons,
          data.movementRepo.findByFileSourceEntry(fileSourceEntry),
          // Scoped to this test's own reconciliations, not just category: the suite runs alongside
          // others (e.g. ExceptionServiceSuite) that also create/mutate AmountMismatch exceptions
          // concurrently, so an unscoped findAll.find(category == ...) can grab someone else's.
          data.exceptionRepo.findAll.find(d =>
            reconIds.contains(d.referenceId) && d.category == ExceptionCategory.AmountMismatch
          ),
          data.accountRepo.findById(data.reconAccount.id).get
        )

      assertEquals(recons.map(_.status), Seq(ReconciliationStatus.Voided))
      assert(movements.forall(_.reconciliationId.isEmpty), "every movement from the file should be unreconciled")
      assertEquals(
        exception.map(_.status),
        Some(anseliq.domain.model.ExceptionStatus.Resolved),
        "the dangling AmountMismatch exception should have been auto-resolved, not left open"
      )
      assertEquals(account.reconciledBalance, BigDecimal(0), "the +10 residual should be reversed back out")
    finally
      transact(app.transactor):
        val recons       = data.reconRepo.findByReconSpecID(data.reconSpec.id)
        val reconIds     = recons.map(_.id).toSet
        val exceptions   = data.exceptionRepo.findAll.filter(d => reconIds.contains(d.referenceId))
        val exceptionIds = exceptions.map(_.id).toSet

        app.eventRepo
          .findByEventType("ReconciliationVoided")
          .filter(es =>
            scala.util.Try(reconIds.contains(es.event[ReconciliationVoided].reconciliationID)).getOrElse(false)
          )
          .foreach(es => app.eventRepo.deleteById(es.id))
        app.eventRepo
          .findByEventType("ExceptionStatusChanged")
          .filter(es =>
            scala.util.Try(exceptionIds.contains(es.event[ExceptionStatusChanged].exceptionID)).getOrElse(false)
          )
          .foreach(es => app.eventRepo.deleteById(es.id))
        app.eventRepo
          .findByEventType("ExceptionCommentAdded")
          .filter(es =>
            scala.util.Try(exceptionIds.contains(es.event[ExceptionCommentAdded].exceptionID)).getOrElse(false)
          )
          .foreach(es => app.eventRepo.deleteById(es.id))

        exceptions.foreach(d => data.exceptionRepo.deleteById(d.id)) // cascades: its comments
        recons.foreach(r => data.reconRepo.deleteById(r.id))
        data.reconSpecRepo.deleteById(data.reconSpec.id)
        data.ledgerRepo.deleteById(data.ledger.id) // cascades: accounts -> movements

  test("reverseMovement refuses a movement that is still reconciled"):
    transact(app.transactor):
      rollBackOnly:
        val data             = DataSetup(app)
        given DataSetup      = data
        val manualReconciler = app.manualReconciler

        val inMovement  = createMovement(data.journalToRecon, 100)
        val outMovement = createMovement(data.reportToRecon, 100)
        manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(outMovement.id), "test")

        val stillReconciled = data.movementRepo.findById(inMovement.id).get
        intercept[IllegalStateError]:
          data.bookkeeper.reverseMovement(stillReconciled)

  test("reverseMovement refuses to reverse an already-reversed movement"):
    transact(app.transactor):
      rollBackOnly:
        val data        = DataSetup(app)
        given DataSetup = data

        val m        = createMovement(data.journalToRecon, 100)
        val reversal = data.bookkeeper.reverseMovement(m).get
        intercept[IllegalStateError]:
          data.bookkeeper.reverseMovement(reversal)

  test("reverseMovementsByFile posts reversing entries, nets accounts back, and never touches the originals"):
    // reverseMovementsByFile commits its own transactions internally, same reason as
    // bulkUnreconcileByFile above, so setup needs to be committed for real too.
    val data             = transact(app.transactor)(DataSetup(app))
    val manualReconciler = app.manualReconciler
    val fileSourceEntry  = s"bad-amount-field-${java.util.UUID.randomUUID()}"

    try
      val balanceBefore = transact(app.transactor)(data.accountRepo.findById(data.reconAccount.id).get)

      transact(app.transactor):
        data.bookkeeper.recordMovements(
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

      val balanceAfter = transact(app.transactor)(data.accountRepo.findById(data.reconAccount.id).get)
      assertEquals(balanceAfter.balance, balanceBefore.balance, "reversal should net the balance back to pre-ingest")
      assertEquals(balanceAfter.unreconciledBalance, balanceBefore.unreconciledBalance)

      val movements = transact(app.transactor)(data.movementRepo.findByFileSourceEntry(fileSourceEntry))
      assertEquals(movements.size, 2, "original + reversal — the original is never deleted")
      val original = movements.find(m => !data.bookkeeper.isReversal(m)).get
      val reversal = movements.find(data.bookkeeper.isReversal).get
      assertEquals(original.amount, BigDecimal(100), "original untouched")
      assertEquals(reversal.amount, original.amount)
      assertEquals(reversal.inAccountId, original.outAccountId, "reversal swaps the accounts")
      assertEquals(reversal.outAccountId, original.inAccountId)
      assertEquals(original.movementType, Some(MovementType.Ingestion))
      assertEquals(reversal.movementType, Some(MovementType.Reversal))

      // Retrying is safe: the already-reversed original is idempotently skipped (via
      // Bookkeeper.reverseMovement's findExistingReferenceIds check), and the reversal row itself is
      // excluded from candidates by bookkeeper.isReversal, so nothing gets double-reversed.
      val retry = manualReconciler.reverseMovementsByFile(fileSourceEntry)
      assertEquals(retry.movementsReversed, 0)
      assertEquals(retry.movementsSkippedStillReconciled, 0)
    finally
      transact(app.transactor):
        data.reconSpecRepo.deleteById(data.reconSpec.id)
        data.ledgerRepo.deleteById(data.ledger.id) // cascades: accounts -> movements
