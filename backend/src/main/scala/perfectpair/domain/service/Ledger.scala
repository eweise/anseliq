package perfectpair.domain.service

import perfectpair.common.model.model.{ID, Money, Properties}
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
