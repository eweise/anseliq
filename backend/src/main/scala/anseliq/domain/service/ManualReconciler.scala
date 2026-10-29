package anseliq.domain.service

import anseliq.common.model.AppliedRules
import anseliq.common.model.model.{ID, Zero}
import anseliq.domain.model.*
import anseliq.domain.port.driven.*
import com.augustnagro.magnum.{Transactor, transact}

case class BulkUnreconcileResult(movementsFound: Int, reconciliationsVoided: Int)
case class BulkReversalResult(movementsReversed: Int, movementsSkippedStillReconciled: Int)
case class BadFilePreview(
  movementsFound:           Int,
  alreadyReversedCount:     Int,
  reconciledCount:          Int,
  eligibleForReversalCount: Int
)
case class FixBadFileResult(unreconcile: BulkUnreconcileResult, reversal: BulkReversalResult)

class ManualReconciler(
  val bookkeeper:       Bookkeeper,
  val movementRepo:     MovementRepo,
  val reconRepo:        ReconciliationRepo,
  val reconSpecRepo:    ReconciliationSpecRepo,
  val exceptionService: ExceptionService,
  val eventStore:       EventStore,
  val clock:            ClockService,
  val transactor:       Transactor
):

  def matchMovements(
    reconSpecId:    ID,
    inMovementIds:  Seq[ID],
    outMovementIds: Seq[ID],
    description:    String,
    // Why the movements were grouped: a person's own match unless they accepted a suggestion.
    appliedRules: AppliedRules = AppliedRules.Manual
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
    // Same rule as InMemoryReconciler: only a OneToMany group can still be completed by more
    // movements arriving later (InProgress); under any other relationship nothing will ever join
    // it, so an unbalanced manual match is Mismatched from the start.
    val status =
      if balance == Zero then ReconciliationStatus.Final
      else if reconSpec.relationship == Relationship.OneToMany then ReconciliationStatus.InProgress
      else ReconciliationStatus.Mismatched

    val now = clock.now
    val reconciliation = Reconciliation(
      specId       = Some(reconSpecId),
      appliedRules = appliedRules,
      description  = description,
      status       = status,
      balance      = balance,
      createdAt    = Some(now),
      updatedAt    = Some(now)
    )
    reconRepo.insert(reconciliation)

    bookkeeper.reconcile(inMovements ++ outMovements, reconciliation.id, reconSpec.accountId)

    if status == ReconciliationStatus.Mismatched then
      exceptionService.raiseSystemException(
        reconciliation.id,
        ExceptionTarget.Reconciliation,
        ExceptionCategory.AmountMismatch,
        s"Balance mismatch of $balance"
      )

    eventStore.publishEvent(ReconciliationManuallyMatched(reconciliationId = reconciliation.id))

    reconciliation

  /**
   * A person undoing a match. A written-off reconciliation can't be unmatched this way: a write-off
   * is a deliberate ledger entry, and undoing it is its own admin-only action (reverseWriteOff),
   * after which the reconciliation is Mismatched again and can be unmatched like any other.
   */
  def unmatch(reconciliationId: ID): Txn[Unit] = voidReconciliation(reconciliationId, allowWrittenOff = false)

  // allowWrittenOff: only the bad-file correction (bulkUnreconcileByFile) voids a written-off
  // reconciliation directly — there the movements' amounts were wrong, so the write-off was too.
  private def voidReconciliation(reconciliationId: ID, allowWrittenOff: Boolean): Txn[Unit] =
    val reconciliation = reconRepo.findById(reconciliationId).ErrIfNotFound("reconciliation", reconciliationId)
    if !allowWrittenOff && isWrittenOff(reconciliation) then
      throw IllegalStateError(
        s"reconciliation $reconciliationId was written off; reverse the write-off before unmatching it"
      )
    if reconciliation.status == ReconciliationStatus.Voided then
      throw IllegalStateError(s"reconciliation $reconciliationId is already voided")
    // A Reversed reconciliation permanently links a movement to its Bookkeeper.reverseMovement
    // contra-entry (see ReconciliationStatus.Reversed) specifically so both stay excluded from
    // every "unreconciled movement" query forever. Unmatching it would undo that on purpose,
    // putting both back in the matching pool — exactly the bug this status exists to prevent.
    if reconciliation.status == ReconciliationStatus.Reversed then
      throw IllegalStateError(s"reconciliation $reconciliationId is a system reversal and cannot be unmatched")

    val specId    = reconciliation.specId.ErrIfNotFound("reconciliation spec id", reconciliationId)
    val reconSpec = reconSpecRepo.findById(specId).ErrIfNotFound("reconciliation spec", specId)
    val movements = movementRepo.findByReconciliationId(reconciliationId)

    bookkeeper.unreconcile(movements, reconSpec.accountId)

    // A write-off's adjustment exists only to close this reconciliation's difference. Returned to
    // the unreconciled pool it would be matchable like a real movement, so it's reversed instead —
    // reverseMovement also links it to its reversal, permanently excluding both from matching.
    movements.filter(_.movementType.contains(MovementType.ManualAdjustment)).foreach(bookkeeper.reverseMovement)

    reconciliation.status = ReconciliationStatus.Voided
    reconRepo.update(reconciliation)

    exceptionService.resolveAutomatically(
      reconciliationId,
      ExceptionCategory.AmountMismatch,
      s"Auto-resolved: reconciliation $reconciliationId was voided."
    )

    eventStore.publishEvent(ReconciliationVoided(reconciliationId = reconciliationId))

  private def isWrittenOff(r: Reconciliation): Boolean =
    r.status == ReconciliationStatus.Final && r.adjustmentReason.exists(_.nonEmpty)

  /**
   * Undoes a write-off that shouldn't have happened (posted against the wrong movement, or the
   * "missing" money later turned up). Admin-only at the route, and a reason is required. Unlike
   * unmatch, the match itself is kept: the write-off adjustment is taken out of the reconciliation
   * and reversed (Bookkeeper.reverseMovement, so the original entry and its correction both stay on
   * the books), the difference comes back, and the reconciliation is Mismatched again with a new
   * AmountMismatch exception. From there it can be written off correctly, unmatched, or — for
   * OneToMany — completed by a late movement.
   */
  def reverseWriteOff(reconciliationId: ID, reason: String, reversedByUserId: Option[ID] = None): Txn[Reconciliation] =
    val reconciliation = reconRepo.findById(reconciliationId).ErrIfNotFound("reconciliation", reconciliationId)
    if !isWrittenOff(reconciliation) then
      throw IllegalStateError(s"reconciliation $reconciliationId hasn't been written off")
    if reason.isBlank then throw IllegalArgumentError("reason", "required to reverse a write-off")

    val specId                 = reconciliation.specId.ErrIfNotFound("reconciliation spec id", reconciliationId)
    val reconSpec              = reconSpecRepo.findById(specId).ErrIfNotFound("reconciliation spec", specId)
    val movements              = movementRepo.findByReconciliationId(reconciliationId)
    val (adjustments, matched) = movements.partition(_.movementType.contains(MovementType.ManualAdjustment))
    if adjustments.isEmpty then
      throw IllegalStateError(s"reconciliation $reconciliationId has no write-off adjustment to reverse")

    bookkeeper.unreconcile(adjustments, reconSpec.accountId)
    adjustments.foreach(bookkeeper.reverseMovement)

    // Same balance rule as matching (in-spec amounts − out-spec amounts), over the movements that
    // were actually matched — i.e. the difference the write-off had closed.
    def total(specId: ID) = matched.filter(_.movementSpecId == specId).map(_.amount).sum
    val balance = total(reconSpec.inMovementSpecId) - total(reconSpec.outMovementSpecId)

    reconciliation.balance          = balance
    reconciliation.status           = ReconciliationStatus.Mismatched
    reconciliation.adjustmentReason = None
    reconciliation.updatedAt        = Some(clock.now)
    reconRepo.update(reconciliation)

    // The write-off resolved this reconciliation's AmountMismatch exception; the mismatch is back,
    // so a new one is raised, and the two threads are linked so either leads to the other.
    val resolvedByWriteOff = exceptionService.latestResolved(reconciliationId, ExceptionCategory.AmountMismatch)
    val reraised = exceptionService.raiseSystemException(
      reconciliationId,
      ExceptionTarget.Reconciliation,
      ExceptionCategory.AmountMismatch,
      s"Write-off reversed ($reason). Balance mismatch of $balance"
    )
    val by = reversedByUserId.flatMap(exceptionService.displayName).fold("")(name => s" by $name")
    for earlier <- resolvedByWriteOff; later <- reraised do
      exceptionService.crossLink(
        earlier,
        later,
        earlierNote = s"The write-off was reversed$by ($reason). The mismatch is open again under a new exception.",
        laterNote =
          s"Raised because the write-off was reversed$by ($reason). The earlier exception was resolved by that write-off."
      )
    eventStore.publishEvent(
      ReconciliationWriteOffReversed(
        reconciliationId = reconciliationId,
        reason           = reason,
        reversedByUserId = reversedByUserId
      )
    )
    reconciliation

  /**
   * Closes a Mismatched (or still-InProgress OneToMany) reconciliation by posting its whole
   * remaining difference as a ManualAdjustment movement, rather than just relabelling it Final and
   * leaving the difference stranded on the reconciled account. `movementId` — one of the
   * reconciliation's own movements, chosen by the user as where the difference belongs (e.g. the
   * out-movement a bank fee was deducted from) — decides the accounts: the adjustment runs between
   * the reconciled account and that movement's other account, in whichever direction cancels the
   * difference on the reconciled account. The adjustment is linked into this reconciliation, so its
   * movements net to zero and its balance becomes zero; the written-off amount lives on as the
   * adjustment's amount.
   */
  def writeOff(reconciliationId: ID, movementId: ID, reason: String): Txn[Movement] =
    val reconciliation = reconRepo.findById(reconciliationId).ErrIfNotFound("reconciliation", reconciliationId)
    if reconciliation.status != ReconciliationStatus.Mismatched && reconciliation.status != ReconciliationStatus.InProgress
    then throw IllegalStateError(s"reconciliation $reconciliationId is not Mismatched or InProgress")

    val specId         = reconciliation.specId.ErrIfNotFound("reconciliation spec id", reconciliationId)
    val reconAccountId = reconSpecRepo.findById(specId).ErrIfNotFound("reconciliation spec", specId).accountId
    val movements      = movementRepo.findByReconciliationId(reconciliationId)

    val against = movements
      .find(_.id == movementId)
      .getOrElse(
        throw IllegalArgumentError(
          "movementId",
          s"movement $movementId is not part of reconciliation $reconciliationId"
        )
      )
    val counterpartyId =
      if against.inAccountId == reconAccountId then against.outAccountId
      else if against.outAccountId == reconAccountId then against.inAccountId
      else throw IllegalArgumentError("movementId", s"movement $movementId does not touch the reconciled account")

    // The difference as the ledger holds it on the reconciled account (what reconcile accumulated
    // into reconciledBalance), rather than reconciliation.balance, so the adjustment is guaranteed
    // to zero out exactly what was posted.
    val difference = movements.map(_.signedAmountFor(reconAccountId)).sum
    if difference == Zero then
      throw IllegalStateError(s"reconciliation $reconciliationId has no difference to write off")
    val (inAccountId, outAccountId) =
      if difference > Zero then (counterpartyId, reconAccountId) else (reconAccountId, counterpartyId)

    val adjustment = bookkeeper.recordAdjustment(
      inAccountId    = inAccountId,
      outAccountId   = outAccountId,
      amount         = difference.abs,
      movementSpecId = against.movementSpecId,
      // A fresh id per write-off, not one derived from the reconciliation: a reconciliation can be
      // written off again after a reversal, and a repeated id would collide with the first
      // adjustment. Retrying the same write-off is already ruled out by the status check above.
      referenceId = createId(),
      metaData = Map(
        "write_off_of_reconciliation_id" -> reconciliationId.toString,
        "write_off_against_movement_id"  -> movementId.toString
      ),
      adjustmentType = AdjustmentType.WriteOff,
      note           = Some(reason)
    )
    bookkeeper.reconcile(Seq(adjustment), reconciliationId, reconAccountId)

    reconciliation.adjustmentReason = Some(reason)
    reconciliation.balance          = Zero
    reconciliation.status           = ReconciliationStatus.Final
    reconRepo.update(reconciliation)

    exceptionService.resolveAutomatically(
      reconciliationId,
      ExceptionCategory.AmountMismatch,
      s"Auto-resolved: reconciliation $reconciliationId was written off ($reason)."
    )

    eventStore.publishEvent(ReconciliationWrittenOff(reconciliationId = reconciliationId, reason = reason))
    adjustment

  /**
   * For a bad ingest (e.g. a FileFeed misconfigured with the wrong amount field): finds every
   * movement tagged with this exact fileSourceEntry (see MovementRepoLive.findByFileSourceEntry)
   * and voids whichever reconciliations they'd been matched into, via unmatch — same ledger
   * reversal, same dangling-exception cleanup, same Voided-not-deleted audit trail as unmatching
   * one reconciliation by hand. Movements with no reconciliation yet are left as-is (already
   * unreconciled). This does NOT delete the movements or reverse their original ingest-time ledger
   * posting (Bookkeeper.recordMovements has no reversal counterpart) — the wrong amounts are still
   * on the books and still reflected in account balances until that's addressed separately; this
   * only undoes reconciliation so the (still-wrong) movements stop being matched against anything.
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
          batch.foreach(id => voidReconciliation(id, allowWrittenOff = true))

    BulkUnreconcileResult(movementsFound = movementCount, reconciliationsVoided = reconciliationIds.size)

  /**
   * For a bad ingest, after bulkUnreconcileByFile has cleared whatever reconciliations were
   * blocking it: posts a reversing movement (Bookkeeper.reverseMovement) for every movement tagged
   * with this fileSourceEntry that isn't still reconciled and isn't itself a reversal. The bad
   * movements are never deleted or edited — this is the permanent "wrong entry, then its reversal"
   * trail accounting audits require, not a correction in place. A corrected re-ingest of this file
   * needs a fresh identity: its reference_ids can't reuse the originals', which now permanently
   * belong to the reversed entries.
   *
   * Not a Txn, same batching reasoning as bulkUnreconcileByFile. Still-reconciled movements are
   * skipped and counted rather than failing the whole run — that's a signal bulkUnreconcileByFile
   * needs to run (again) first, not a reason to abort movements that ARE reversible.
   * Already-reversed movements are skipped too, via Bookkeeper.reverseMovement's own idempotency,
   * making a retry after a partial failure safe to just re-run.
   */
  def reverseMovementsByFile(fileSourceEntry: String, batchSize: Int = 1000): BulkReversalResult =
    val (candidates, stillReconciledCount) = transact(transactor):
      val movements              = movementRepo.findByFileSourceEntry(fileSourceEntry)
      val (reversals, originals) = movements.partition(bookkeeper.isReversal)
      // Same reasoning as ManualReconciler.previewBadFile: an original that already has a reversal
      // now legitimately carries a reconciliationId (pointing at that reversal's own Reversed
      // reconciliation — see Bookkeeper.reverseMovement), so without excluding it here, a retry after
      // a successful run would miscount it as "still reconciled" (blocked) instead of "already done".
      val alreadyReversedIds = reversals.flatMap(bookkeeper.reversalTargetId).toSet
      val pending            = originals.filterNot(m => alreadyReversedIds.contains(m.id))
      (pending.filter(_.reconciliationId.isEmpty), pending.count(_.reconciliationId.isDefined))

    var reversedCount = 0
    candidates
      .grouped(batchSize)
      .foreach: batch =>
        transact(transactor):
          batch.foreach(m => bookkeeper.reverseMovement(m).foreach(_ => reversedCount += 1))

    BulkReversalResult(movementsReversed = reversedCount, movementsSkippedStillReconciled = stillReconciledCount)

  /**
   * Read-only preview of what fixBadFile would do for this file, without doing it — lets a UI show
   * "N movements found, M would be reversed" before a human commits to the operation.
   */
  def previewBadFile(fileSourceEntry: String): Txn[BadFilePreview] =
    val movements              = movementRepo.findByFileSourceEntry(fileSourceEntry)
    val (reversals, originals) = movements.partition(bookkeeper.isReversal)
    // A reversal inherits its original's file_source_entry tag (see Bookkeeper.reverseMovement), so
    // both sides of an already-reversed pair show up in `movements` — without excluding those
    // originals here, a re-run of the preview after a fix would still claim they're "eligible",
    // even though a real fix call would (correctly) no-op on them via reverseMovement's own
    // idempotency check.
    val alreadyReversedIds = reversals.flatMap(bookkeeper.reversalTargetId).toSet
    val pending            = originals.filterNot(m => alreadyReversedIds.contains(m.id))
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
