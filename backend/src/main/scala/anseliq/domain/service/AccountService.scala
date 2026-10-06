package anseliq.domain.service

import anseliq.common.model.model.{ID, Money, Properties}
import anseliq.domain.model.*
import anseliq.domain.model.helper.JSONBProps
import anseliq.domain.port.driven.*

class AccountService(accountRepo: AccountRepo, movementRepo: MovementRepo):

  def createAccount(account: Account): Txn[Account] =
    accountRepo.insert(account)
    account

  def updateAccount(id: ID, name: String, accountType: AccountType, properties: JSONBProps): Txn[Account] =
    val existing = accountRepo.findById(id).ErrIfNotFound("account", id)
    val updated  = existing.copy(name = name, accountType = accountType, properties = properties)
    accountRepo.update(updated)
    updated

  def postAmount(
    movementSpec: MovementSpec,
    amount:       Money,
    referenceID:  ID,
    properties:   Properties = Map[String, String]()
  ): Txn[Unit] =
    val inAccount = accountRepo
      .findById(movementSpec.inAccountId)
      .getOrElse:
        throw NotFoundError(s"no account found for ${movementSpec.inAccountId}")

    val outAccount = accountRepo
      .findById(movementSpec.outAccountId)
      .getOrElse:
        throw NotFoundError(s"no account found for ${movementSpec.outAccountId}")

    inAccount.balance             = inAccount.balance + amount
    inAccount.unreconciledBalance = inAccount.unreconciledBalance + amount
    accountRepo.update(inAccount)

    outAccount.balance             = outAccount.balance - amount
    outAccount.unreconciledBalance = outAccount.unreconciledBalance - amount
    accountRepo.update(outAccount)

    val movement = Movement(
      referenceId       = referenceID,
      amount            = amount,
      movementSpecId    = movementSpec.id,
      properties        = JSONBProps(properties),
      inAccountId       = inAccount.id,
      inAccountBalance  = inAccount.balance,
      outAccountId      = outAccount.id,
      outAccountBalance = outAccount.balance
    )
    movementRepo.insert(movement)
