package onparity.domain.service

import com.fasterxml.uuid.Generators
import onparity.common.model.model.{ID, Money, Properties, Zero}
import onparity.domain.model.helper.JSONBProps
import onparity.domain.model.{
  ErrIfNotFound,
  IllegalStateError,
  Movement,
  MovementSpec,
  Reconciliation,
  ReconciliationStatus
}
import onparity.domain.port.driven.{AccountRepo, MovementRepo, ReconciliationRepo, Txn}

case class MovementRequest(
  amount:      Money,
  referenceId: ID,
  properties:  Properties,
  metaData:    Properties = Map()
)

class Ledger(using
  movementRepo: MovementRepo,
  accountRepo:  AccountRepo,
  reconRepo:    ReconciliationRepo,
  clock:        ClockService
):

  // Record many movements for the same in and out accounts in order to handle batch inserts.
  // Filters out requests whose referenceId already exists *before* computing balance deltas: the
  // movement insert itself is separately deduped via ON CONFLICT(reference_id) DO NOTHING, but that
  // alone doesn't stop a retried/redelivered batch (e.g. a crashed ingestion job resubmitted) from
  // double-counting account balances for movements that were already recorded.
  //
  // The account locks are acquired *before* that existence check, not after — findByIdForUpdate's
  // FOR UPDATE blocks a second concurrent caller for the same accounts until the first commits, so
  // taking the lock first makes the check-then-insert atomic with respect to a racing call. Doing
  // it the other way around (check, then lock) would leave a window where two concurrent calls for
  // the same referenceIds both see "nothing exists yet", both insert (one succeeds, one no-ops via
  // ON CONFLICT), and both still apply the balance delta — the exact double-count this method
  // exists to prevent, just reached via a race instead of a sequential retry.
  def recordMovements(
    movementSpec:     MovementSpec,
    movementRequests: Seq[MovementRequest]
  ): Txn[Unit] =
    // locks the accounts for updating.
    val inAccount = accountRepo
      .findByIdForUpdate(movementSpec.inAccountId)
      .ErrIfNotFound("In Account", movementSpec.inAccountId)
    val outAccount = accountRepo
      .findByIdForUpdate(movementSpec.outAccountId)
      .ErrIfNotFound("Out Account", movementSpec.outAccountId)

    val existingReferenceIds = movementRepo.findExistingReferenceIds(movementRequests.map(_.referenceId))
    val newRequests          = movementRequests.filterNot(req => existingReferenceIds.contains(req.referenceId))

    if newRequests.nonEmpty then
      val movements = newRequests.map: req =>
        val (movementInAccount, movementOutAccount) =
          if req.amount < 0 then (outAccount, inAccount)
          else (inAccount, outAccount)

        val absAmount = req.amount.abs
        movementInAccount.balance += absAmount
        movementInAccount.unreconciledBalance += absAmount
        movementOutAccount.balance -= absAmount
        movementOutAccount.unreconciledBalance -= absAmount

        Movement(
          inAccountId       = movementInAccount.id,
          outAccountId      = movementOutAccount.id,
          referenceId       = req.referenceId,
          outAccountBalance = movementOutAccount.balance,
          inAccountBalance  = movementInAccount.balance,
          amount            = req.amount.abs,
          properties        = JSONBProps(req.properties),
          metaData          = JSONBProps(req.metaData),
          movementSpecId    = movementSpec.id
        )
      movementRepo.insertAll(movements)
      accountRepo.update(inAccount)
      accountRepo.update(outAccount)
    end if
  end recordMovements

  // Links `movements` to `reconciliationId` and applies their combined effect on accountId's
  // reconciled/unreconciled balance in one step, so the two can never drift apart.
  def reconcile(movements: Seq[Movement], reconciliationId: ID, accountId: ID): Txn[Unit] =
    var delta: Money = Zero
    for m <- movements do
      m.reconciliationId = Some(reconciliationId)
      movementRepo.update(m)
      delta = delta + m.signedAmountFor(accountId)
    end for
    markReconciled(accountId, delta)

  // Moves `delta` from unreconciledBalance to reconciledBalance. balance is untouched since
  // reconciliation only reclassifies already-recorded movements, it doesn't record new ones.
  def markReconciled(accountId: ID, delta: Money): Txn[Unit] =
    val account = accountRepo.findByIdForUpdate(accountId).ErrIfNotFound("Account", accountId)
    account.reconciledBalance += delta
    account.unreconciledBalance -= delta
    accountRepo.update(account)

  // Inverse of reconcile: clears the reconciliation link and reverses the balance effect.
  def unreconcile(movements: Seq[Movement], accountId: ID): Txn[Unit] =
    var delta: Money = Zero
    for m <- movements do
      delta              = delta + m.signedAmountFor(accountId)
      m.reconciliationId = None
      movementRepo.update(m)
    end for
    markUnreconciled(accountId, delta)

  def markUnreconciled(accountId: ID, delta: Money): Txn[Unit] =
    markReconciled(accountId, -delta)

  private val reversalOfKey = "reversal_of_movement_id"

  def isReversal(movement: Movement): Boolean = movement.metaData.props.contains(reversalOfKey)

  /** If movement is a reversal, the id of the movement it reverses — lets a caller (e.g. a preview
    * that already has a whole file's movements in hand) figure out which originals have already
    * been reversed without a per-movement findExistingReferenceIds round-trip. */
  def reversalTargetId(movement: Movement): Option[ID] =
    movement.metaData.props.get(reversalOfKey).map(java.util.UUID.fromString)

  /**
   * Posts a reversing movement that exactly undoes movement's balance effect (accounts swapped,
   * same amount) rather than deleting or editing movement — accounting audit trail requires the
   * original wrong entry to stay in place permanently, with its reversal as a separate, linked
   * entry. Refuses a movement that's still reconciled (its reconciliation must be voided first —
   * see ManualReconciler.unmatch/bulkUnreconcileByFile — since reversing a still-matched movement's
   * balance without also undoing the match would leave a Reconciliation referencing a movement
   * whose ledger effect no longer matches its counterpart) or one that's itself already a reversal
   * (reversing a reversal isn't a meaningful operation here).
   *
   * Links movement and its reversal via a real, spec-less ReconciliationStatus.Reversed
   * Reconciliation (through the ordinary reconcile(), reused as-is — its balance delta is always
   * exactly zero here since a movement and its reversal net to nothing on any account, so this
   * costs nothing beyond setting reconciliationId on both). This is *why* the reversal actually
   * works, not incidental bookkeeping: every "find unreconciled movements" query already filters on
   * reconciliation_id IS NULL, so this permanently and automatically excludes both movement and its
   * reversal from ever being matched again — without it, the reversal (which necessarily carries
   * the same match-key properties as movement, see below) would be just as eligible for automatic
   * matching as any real movement, and could get erroneously matched against movement's original
   * counterpart.
   *
   * Idempotent: the reversal's referenceId is deterministic (derived from movement.id) and checked
   * via findExistingReferenceIds *before* touching balances — the same guard recordMovements itself
   * uses — so calling this again for an already-reversed movement is a safe no-op (None) instead of
   * double-reversing.
   */
  def reverseMovement(movement: Movement): Txn[Option[Movement]] =
    if movement.reconciliationId.isDefined then
      throw IllegalStateError(s"movement ${movement.id} is still reconciled; unreconcile it before reversing")
    if isReversal(movement) then throw IllegalStateError(s"movement ${movement.id} is itself a reversal")

    val reversalReferenceId = Generators.nameBasedGenerator().generate(s"reversal of ${movement.id}")
    if movementRepo.findExistingReferenceIds(Seq(reversalReferenceId)).nonEmpty then None
    else
      val originalIn  = accountRepo.findByIdForUpdate(movement.inAccountId).ErrIfNotFound("Account", movement.inAccountId)
      val originalOut = accountRepo.findByIdForUpdate(movement.outAccountId).ErrIfNotFound("Account", movement.outAccountId)

      originalIn.balance -= movement.amount
      originalIn.unreconciledBalance -= movement.amount
      originalOut.balance += movement.amount
      originalOut.unreconciledBalance += movement.amount
      accountRepo.update(originalIn)
      accountRepo.update(originalOut)

      val reversal = Movement(
        inAccountId       = movement.outAccountId,
        outAccountId      = movement.inAccountId,
        referenceId       = reversalReferenceId,
        outAccountBalance = originalIn.balance,
        inAccountBalance  = originalOut.balance,
        amount            = movement.amount,
        properties        = movement.properties,
        metaData          = JSONBProps(movement.metaData.props + (reversalOfKey -> movement.id.toString)),
        movementSpecId    = movement.movementSpecId
      )
      movementRepo.insert(reversal)

      val now = clock.now
      val reconciliation = Reconciliation(
        specId       = None,
        appliedRules = "system: Ledger.reverseMovement",
        description  = s"Reversal of movement ${movement.id}",
        status       = ReconciliationStatus.Reversed,
        balance      = Zero,
        createdAt    = Some(now),
        updatedAt    = Some(now)
      )
      reconRepo.insert(reconciliation)
      reconcile(Seq(movement, reversal), reconciliation.id, movement.inAccountId)

      Some(reversal)
