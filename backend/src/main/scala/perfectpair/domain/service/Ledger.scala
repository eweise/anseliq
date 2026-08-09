package perfectpair.domain.service

import perfectpair.common.model.model.{ID, Money, Properties, Zero}
import perfectpair.domain.model.helper.JSONBProps
import perfectpair.domain.model.{ErrIfNotFound, Movement, MovementSpec}
import perfectpair.domain.port.driven.{AccountRepo, MovementRepo, Txn}

case class MovementRequest(
  amount:      Money,
  referenceId: ID,
  properties:  Properties
)

class Ledger(using movementRepo: MovementRepo, accountRepo: AccountRepo):

  // Record many movements for the same in and out accounts in order to handle batch inserts
  def recordMovements(
    movementSpec:     MovementSpec,
    movementRequests: Seq[MovementRequest]
  ): Txn[Unit] =
    val inAccount = accountRepo
      .findByIdForUpdate(movementSpec.inAccountId)
      .ErrIfNotFound("In Account", movementSpec.inAccountId)
    val outAccount = accountRepo
      .findByIdForUpdate(movementSpec.outAccountId)
      .ErrIfNotFound("Out Account", movementSpec.outAccountId)

    val movements = movementRequests.map: req =>
      val (movementInAccount, movementOutAccount) =
        if req.amount < 0 then (outAccount, inAccount)
        else (inAccount, outAccount)

      case class MovementRequest(
        amount:      Money,
        referenceId: ID,
        properties:  Properties
      )

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
        movementSpecId    = movementSpec.id
      )
    movementRepo.insertAll(movements)
    accountRepo.update(inAccount)
    accountRepo.update(outAccount)

  // Moves `delta` from unreconciledBalance to reconciledBalance. balance is untouched since
  // reconciliation only reclassifies already-recorded movements, it doesn't record new ones.
  def markReconciled(accountId: ID, delta: Money): Txn[Unit] =
    val account = accountRepo.findByIdForUpdate(accountId).ErrIfNotFound("Account", accountId)
    account.reconciledBalance   += delta
    account.unreconciledBalance -= delta
    accountRepo.update(account)

  def markUnreconciled(accountId: ID, delta: Money): Txn[Unit] =
    markReconciled(accountId, -delta)

  // Links `movements` to `reconciliationId` and applies their combined effect on accountId's
  // reconciled/unreconciled balance in one step, so the two can never drift apart.
  def reconcile(movements: Seq[Movement], reconciliationId: ID, accountId: ID): Txn[Unit] =
    var delta: Money = Zero
    for m <- movements do
      m.reconciliationId = Some(reconciliationId)
      movementRepo.update(m)
      delta = delta + m.signedAmountFor(accountId)
    markReconciled(accountId, delta)

  // Inverse of reconcile: clears the reconciliation link and reverses the balance effect.
  def unreconcile(movements: Seq[Movement], accountId: ID): Txn[Unit] =
    var delta: Money = Zero
    for m <- movements do
      delta = delta + m.signedAmountFor(accountId)
      m.reconciliationId = None
      movementRepo.update(m)
    markUnreconciled(accountId, delta)
