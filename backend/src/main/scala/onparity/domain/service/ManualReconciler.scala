package onparity.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import onparity.common.model.model.{ID, Money, Zero}
import onparity.domain.model.*
import onparity.domain.port.driven.*

case class BulkUnreconcileResult(movementsFound: Int, reconciliationsVoided: Int)
case class BulkReversalResult(movementsReversed: Int, movementsSkippedStillReconciled: Int)
case class BadFilePreview(
  movementsFound:           Int,
  alreadyReversedCount:     Int,
  reconciledCount:          Int,
  eligibleForReversalCount: Int
)
case class FixBadFileResult(unreconcile: BulkUnreconcileResult, reversal: BulkReversalResult)

class ManualReconciler(using
  val ledger:             Ledger,
  val movementRepo:       MovementRepo,
  val reconRepo:          ReconciliationRepo,
  val reconSpecRepo:      ReconciliationSpecRepo,
  val disputeRepo:        DisputeRepo,
  val disputeCommentRepo: DisputeCommentRepo,
  val eventStore:         EventStore,
  val clock:              ClockService,
  val transactor:         Transactor
):

  def matchMovements(
    reconSpecId:    ID,
    inMovementIds:  Seq[ID],
    outMovementIds: Seq[ID],
    description:    String
  ): Txn[Reconciliation] =
    if inMovementIds.isEmpty then throw IllegalArgumentError("inMovementIds", "must select at least one in-movement")
    if outMovementIds.isEmpty then throw IllegalArgumentError("outMovementIds", "must select at least one out-movement")

    val reconSpec = reconSpecRepo.findById(reconSpecId).ErrIfNotFound("reconciliation spec", reconSpecId)

    val inMovements  = inMovementIds.map(id => movementRepo.findById(id).ErrIfNotFound("movement", id))
    val outMovements = outMovementIds.map(id => movementRepo.findById(id).ErrIfNotFound("movement", id))

    for m <- inMovements do
      if m.movementSpecId != reconSpec.inMovementSpecId then
        throw IllegalArgumentError("inMovementIds", s"movement ${m.id} does not belong to this spec's in-movement-spec")
      if m.reconciliationId.isDefined then
        throw IllegalArgumentError("inMovementIds", s"movement ${m.id} is already reconciled")

    for m <- outMovements do
      if m.movementSpecId != reconSpec.outMovementSpecId then
        throw IllegalArgumentError(
          "outMovementIds",
          s"movement ${m.id} does not belong to this spec's out-movement-spec"
        )
      if m.reconciliationId.isDefined then
        throw IllegalArgumentError("outMovementIds", s"movement ${m.id} is already reconciled")

    val balance = inMovements.map(_.amount).sum - outMovements.map(_.amount).sum
    val status  = if balance == Zero then ReconciliationStatus.Final else ReconciliationStatus.InProgress

    val now = clock.now
    val reconciliation = Reconciliation(
      specId       = reconSpecId,
      appliedRules = "manual",
      description  = description,
      status       = status,
      balance      = balance,
      createdAt    = Some(now),
      updatedAt    = Some(now)
    )
    reconRepo.insert(reconciliation)

    ledger.reconcile(inMovements ++ outMovements, reconciliation.id, reconSpec.accountId)

    eventStore.publishEvent(ReconciliationManuallyMatched(reconciliationID = reconciliation.id))

    reconciliation

  def unmatch(reconciliationId: ID): Txn[Unit] =
    val reconciliation = reconRepo.findById(reconciliationId).ErrIfNotFound("reconciliation", reconciliationId)
    if reconciliation.status == ReconciliationStatus.Voided then
      throw IllegalStateError(s"reconciliation $reconciliationId is already voided")

    val reconSpec =
      reconSpecRepo.findById(reconciliation.specId).ErrIfNotFound("reconciliation spec", reconciliation.specId)
    val movements = movementRepo.findByReconciliationID(reconciliationId)

    ledger.unreconcile(movements, reconSpec.accountId)

    reconciliation.status = ReconciliationStatus.Voided
    reconRepo.update(reconciliation)

    resolveDanglingAmountMismatchDispute(reconciliationId)

    eventStore.publishEvent(ReconciliationVoided(reconciliationID = reconciliationId))

  // Voiding a reconciliation makes any AmountMismatch dispute raised against it (see
  // InMemoryReconciler.streamMergeJoin's raiseDisputeOnMismatch) moot — the mismatch it was
  // tracking no longer exists once the movements are back in the unreconciled pool. Same
  // auto-resolve shape as Ingestor.resolveFileMissingDispute: status change + an explanatory
  // comment, not just silently forgotten.
  private def resolveDanglingAmountMismatchDispute(reconciliationId: ID): Txn[Unit] =
    disputeRepo
      .findOpenByReferenceIdAndCategory(reconciliationId, DisputeCategory.AmountMismatch)
      .foreach: dispute =>
        dispute.status    = DisputeStatus.Resolved
        dispute.updatedAt = Some(clock.now)
        disputeRepo.update(dispute)
        eventStore.publishEvent(
          DisputeStatusChanged(disputeID = dispute.id, newStatus = DisputeStatus.Resolved.toString)
        )

        val comment = disputeCommentRepo.insertReturning(
          DisputeComment(
            disputeId = dispute.id,
            body      = Some(s"Auto-resolved: reconciliation $reconciliationId was voided."),
            newStatus = Some(DisputeStatus.Resolved),
            createdAt = Some(clock.now)
          )
        )
        eventStore.publishEvent(DisputeCommentAdded(disputeID = dispute.id, commentID = comment.id))

  def writeOff(reconciliationId: ID, reason: String): Txn[Unit] =
    val reconciliation = reconRepo.findById(reconciliationId).ErrIfNotFound("reconciliation", reconciliationId)
    if reconciliation.status != ReconciliationStatus.InProgress then
      throw IllegalStateError(s"reconciliation $reconciliationId is not InProgress")

    reconciliation.adjustmentReason = Some(reason)
    reconciliation.status           = ReconciliationStatus.Final
    reconRepo.update(reconciliation)

    eventStore.publishEvent(ReconciliationWrittenOff(reconciliationID = reconciliationId, reason = reason))

  /**
   * For a bad ingest (e.g. a FileFeed misconfigured with the wrong amount field): finds every
   * movement tagged with this exact fileSourceEntry (see MovementRepoLive.findByFileSourceEntry)
   * and voids whichever reconciliations they'd been matched into, via unmatch — same ledger
   * reversal, same dangling-dispute cleanup, same Voided-not-deleted audit trail as unmatching one
   * reconciliation by hand. Movements with no reconciliation yet are left as-is (already
   * unreconciled). This does NOT delete the movements or reverse their original ingest-time ledger
   * posting (Ledger.recordMovements has no reversal counterpart) — the wrong amounts are still on
   * the books and still reflected in account balances until that's addressed separately; this only
   * undoes reconciliation so the (still-wrong) movements stop being matched against anything.
   *
   * Not a Txn: voids commit in their own batches of `batchSize` (reusing unmatch's per-
   * reconciliation Txn as-is) rather than one transaction for however many reconciliations a bad
   * file touched, so a very dirty file doesn't require one unbounded transaction. Already-Voided
   * reconciliations are skipped, making a retry after a partial failure safe to just re-run.
   */
  def bulkUnreconcileByFile(fileSourceEntry: String, batchSize: Int = 1000): BulkUnreconcileResult =
    val (movementCount, reconciliationIds) = transact(transactor):
      val movements = movementRepo.findByFileSourceEntry(fileSourceEntry)
      val ids       = movements.flatMap(_.reconciliationId).distinct
      // Not reconRepo.findAllById(ids): Magnum's generic findAllById binds ids as a JDBC array,
      // which the Postgres driver can't do for a UUID id column ("Unable to find server array type
      // for provided name OTHER") — the same reason MovementRepoLive.findExistingReferenceIds/
      // setReconciliationIds hand-roll their own SQL instead of using generic bulk finders.
      val toVoid = ids.flatMap(reconRepo.findById).filterNot(_.status == ReconciliationStatus.Voided).map(_.id)
      (movements.size, toVoid)

    reconciliationIds
      .grouped(batchSize)
      .foreach: batch =>
        transact(transactor):
          batch.foreach(id => unmatch(id))

    BulkUnreconcileResult(movementsFound = movementCount, reconciliationsVoided = reconciliationIds.size)

  /**
   * For a bad ingest, after bulkUnreconcileByFile has cleared whatever reconciliations were
   * blocking it: posts a reversing movement (Ledger.reverseMovement) for every movement tagged with
   * this fileSourceEntry that isn't still reconciled and isn't itself a reversal. The bad movements
   * are never deleted or edited — this is the permanent "wrong entry, then its reversal" trail
   * accounting audits require, not a correction in place. A corrected re-ingest of this file needs
   * a fresh identity: its reference_ids can't reuse the originals', which now permanently belong to
   * the reversed entries.
   *
   * Not a Txn, same batching reasoning as bulkUnreconcileByFile. Still-reconciled movements are
   * skipped and counted rather than failing the whole run — that's a signal bulkUnreconcileByFile
   * needs to run (again) first, not a reason to abort movements that ARE reversible.
   * Already-reversed movements are skipped too, via Ledger.reverseMovement's own idempotency,
   * making a retry after a partial failure safe to just re-run.
   */
  def reverseMovementsByFile(fileSourceEntry: String, batchSize: Int = 1000): BulkReversalResult =
    val (candidates, stillReconciledCount) = transact(transactor):
      val reversible = movementRepo.findByFileSourceEntry(fileSourceEntry).filterNot(ledger.isReversal)
      (reversible.filter(_.reconciliationId.isEmpty), reversible.count(_.reconciliationId.isDefined))

    var reversedCount = 0
    candidates
      .grouped(batchSize)
      .foreach: batch =>
        transact(transactor):
          batch.foreach(m => ledger.reverseMovement(m).foreach(_ => reversedCount += 1))

    BulkReversalResult(movementsReversed = reversedCount, movementsSkippedStillReconciled = stillReconciledCount)

  /**
   * Read-only preview of what fixBadFile would do for this file, without doing it — lets a UI show
   * "N movements found, M would be reversed" before a human commits to the operation.
   */
  def previewBadFile(fileSourceEntry: String): Txn[BadFilePreview] =
    val movements               = movementRepo.findByFileSourceEntry(fileSourceEntry)
    val (reversals, originals)  = movements.partition(ledger.isReversal)
    // A reversal inherits its original's file_source_entry tag (see Ledger.reverseMovement), so
    // both sides of an already-reversed pair show up in `movements` — without excluding those
    // originals here, a re-run of the preview after a fix would still claim they're "eligible",
    // even though a real fix call would (correctly) no-op on them via reverseMovement's own
    // idempotency check.
    val alreadyReversedIds      = reversals.flatMap(ledger.reversalTargetId).toSet
    val pending                 = originals.filterNot(m => alreadyReversedIds.contains(m.id))
    BadFilePreview(
      movementsFound           = movements.size,
      alreadyReversedCount     = reversals.size,
      reconciledCount          = pending.count(_.reconciliationId.isDefined),
      eligibleForReversalCount = pending.count(_.reconciliationId.isEmpty)
    )

  /**
   * The full bad-ingest fix, in the order that makes each step valid: void whatever reconciliations
   * are in the way (bulkUnreconcileByFile), then reverse every movement tagged with this file
   * (reverseMovementsByFile). Neither step deletes anything — see each method's own doc for why.
   * Safe to call again on a partially-fixed file: both steps are independently idempotent.
   */
  def fixBadFile(fileSourceEntry: String, batchSize: Int = 1000): FixBadFileResult =
    val unreconcileResult = bulkUnreconcileByFile(fileSourceEntry, batchSize)
    val reversalResult    = reverseMovementsByFile(fileSourceEntry, batchSize)
    FixBadFileResult(unreconcileResult, reversalResult)
