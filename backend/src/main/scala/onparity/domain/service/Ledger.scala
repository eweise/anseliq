package onparity.domain.service

import onparity.common.model.model.{ID, Money, Properties, Zero}
import onparity.domain.model.helper.JSONBProps
import onparity.domain.model.{ErrIfNotFound, Movement, MovementSpec}
import onparity.domain.port.driven.{AccountRepo, MovementRepo, Txn}

case class MovementRequest(
  amount:      Money,
  referenceId: ID,
  properties:  Properties,
  metaData:    Properties = Map()
)

class Ledger(using movementRepo: MovementRepo, accountRepo: AccountRepo):

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
