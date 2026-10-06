package anseliq.domain.service

import anseliq.adapter.driving.Payload
import anseliq.common.model.model.{ID, Money, Properties}
import anseliq.common.model.*
import anseliq.domain.model.*
import anseliq.domain.model.helper.JSONBProps
import anseliq.domain.port.driven.*

import java.util.UUID

class AccountService(accountRepo: AccountRepo, movementRepo: MovementRepo):

  def createAccount(payload: AccountPayload): Txn[AccountPayload] =
    val newAccount = Account(
      name        = payload.name,
      fundFlowId  = UUID.fromString(payload.fundFlowId),
      accountType = Payload.accountTypeToDomain(payload.accountType),
      properties  = JSONBProps(payload.properties)
    )
    accountRepo.insert(newAccount)
    Payload.accountPayloadFromDomain(newAccount)

  def updateAccount(idStr: String, payload: AccountPayload): Txn[AccountPayload] =
    if idStr.trim == "" then throw IllegalArgumentException("Account ID is required")
    val accountID       = UUID.fromString(idStr)
    var existingAccount = accountRepo.findById(accountID).ErrIfNotFound("account", accountID)
    existingAccount = existingAccount.copy(
      name        = payload.name,
      accountType = Payload.accountTypeToDomain(payload.accountType),
      properties  = JSONBProps(payload.properties)
    )
    accountRepo.update(existingAccount)
    Payload.accountPayloadFromDomain(existingAccount)

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
