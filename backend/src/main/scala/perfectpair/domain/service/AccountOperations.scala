package perfectpair.domain.service

import perfectpair.adapter.driving.Payload
import perfectpair.common.model.AccountPayload
import perfectpair.common.model.model.{ID, Money, Properties}
import perfectpair.domain.model.*
import perfectpair.domain.model.helper.JSONBProps
import perfectpair.domain.port.driven.*

import java.util.UUID

class AccountOperations(
  val accountRepo:        AccountRepo,
  val movementRepo:       MovementRepo,
  val movementSpecRepo:   MovementSpecRepo,
  val fundFlowConfigRepo: FundFlowConfigRepo
):

  def postAmount(
    movementSpec: MovementSpec,
    amount:       Money,
    referenceID:  ID,
    properties:   Properties = Map[String, String]()
  ): Txn[Unit] =
    // todo do I need to find the accounts if the movement
    // spec already has the ids? I think no
    val inAccount = accountRepo
      .findById(movementSpec.inAccountId)
      .getOrElse:
        throw NotFoundError(s"no account found for ${movementSpec.inAccountId}")

    val outAccount = accountRepo
      .findById(movementSpec.outAccountId)
      .getOrElse:
        throw NotFoundError(s"no account found for ${movementSpec.outAccountId}")

    val movement = Movement(
      referenceId    = referenceID,
      amount         = amount,
      movementSpecId = movementSpec.id,
      properties     = JSONBProps(properties),
      inAccountId    = inAccount.id,
      outAccountId   = outAccount.id
    )
    movementRepo.insert(movement)

  def createAccount(payload: AccountPayload): Txn[AccountPayload] =
    val newAccount = Account(
      name       = payload.name,
      fundFlowId = UUID.fromString(payload.fundFlowId),
      properties = JSONBProps(payload.properties)
    )
    accountRepo.insert(newAccount)
    Payload.accountPayloadFromDomain(newAccount)

  def updateAccount(idStr: String, payload: AccountPayload): Txn[AccountPayload] =
    if idStr.trim == "" then IllegalArgumentException("Account ID is required")

    val accountID = UUID.fromString(idStr)

    var existingAccount = accountRepo.findById(accountID).ErrIfNotFound("account", accountID)
    existingAccount = existingAccount.copy(
      name       = payload.name,
      properties = JSONBProps(payload.properties)
    )
    accountRepo.update(existingAccount)
    Payload.accountPayloadFromDomain(existingAccount)
