package perfectpair.domain.service

import perfectpair.adapter.driving.Payload
import perfectpair.common.model.{AccountPayload, FundFlowPayload, MovementSpecPayload, ReconciliationSpecPayload}
import perfectpair.common.model.model.{ID, Money, Properties}
import perfectpair.domain.model.*
import perfectpair.domain.model.helper.JSONBProps
import perfectpair.domain.port.driven.*
import upickle.default.write as upickleWrite

import java.util.UUID

class FundFlowOperations(using
  val fundFlowConfigRepo: FundFlowConfigRepo,
  val accountRepo:        AccountRepo,
  val movementRepo:       MovementRepo,
  val movementSpecRepo:   MovementSpecRepo,
  val reconSpecRepo:      ReconciliationSpecRepo
):

  def createFundFlow(payload: FundFlowPayload): Txn[FundFlowPayload] =
    val fundFlow = Payload.fundFlowPayloadToDomain(payload)
    fundFlowConfigRepo.insert(fundFlow)
    Payload.fundFlowPayloadFromDomain(fundFlow)

  def updateFundFlow(payload: FundFlowPayload): Txn[FundFlowPayload] =
    if payload.id.isEmpty then IllegalArgumentException("Fund flow ID is required")
    val ffID       = UUID.fromString(payload.id.get)
    var existingFF = fundFlowConfigRepo.findById(ffID).ErrIfNotFound("fund flow", ffID)
    existingFF = existingFF.copy(name = payload.name)
    fundFlowConfigRepo.update(existingFF)
    Payload.fundFlowPayloadFromDomain(existingFF)

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
    val accountID       = UUID.fromString(idStr)
    var existingAccount = accountRepo.findById(accountID).ErrIfNotFound("account", accountID)
    existingAccount = existingAccount.copy(
      name       = payload.name,
      properties = JSONBProps(payload.properties)
    )
    accountRepo.update(existingAccount)
    Payload.accountPayloadFromDomain(existingAccount)

  def createMovementSpec(payload: MovementSpecPayload): Txn[MovementSpecPayload] =
    val spec = MovementSpec(
      name           = payload.name,
      inAccountId    = UUID.fromString(payload.inAccountId),
      outAccountId   = UUID.fromString(payload.outAccountId),
      dataSourceJson = payload.dataSourceConfig.map(upickleWrite(_)).getOrElse("")
    )
    movementSpecRepo.insert(spec)
    Payload.movementSpecPayloadFromDomain(spec)

  def updateMovementSpec(idStr: String, payload: MovementSpecPayload): Txn[MovementSpecPayload] =
    val id       = UUID.fromString(idStr)
    var existing = movementSpecRepo.findById(id).ErrIfNotFound("movement spec", id)
    existing = existing.copy(
      name           = payload.name,
      inAccountId    = UUID.fromString(payload.inAccountId),
      outAccountId   = UUID.fromString(payload.outAccountId),
      dataSourceJson = payload.dataSourceConfig.map(upickleWrite(_)).getOrElse("")
    )
    movementSpecRepo.update(existing)
    Payload.movementSpecPayloadFromDomain(existing)

  def deleteMovementSpec(idStr: String): Txn[Unit] =
    val id = UUID.fromString(idStr)
    movementSpecRepo.deleteById(id)

  def createReconSpec(payload: ReconciliationSpecPayload): Txn[ReconciliationSpecPayload] =
    val accountId =
      UUID.fromString(payload.accountId.getOrElse(throw IllegalArgumentException("Account ID is required")))
    val inMovementSpecId  = UUID.fromString(payload.inMovementSpecId)
    val outMovementSpecId = UUID.fromString(payload.outMovementSpecId)
    val spec = ReconciliationSpec(
      accountId         = accountId,
      description       = payload.description,
      agingLimit        = payload.agingLimit,
      relationship      = Payload.reconRelationshipToDomain(payload.relationship),
      inMovementSpecId  = inMovementSpecId,
      outMovementSpecId = outMovementSpecId,
      ruleJson          = upickleWrite(payload.rule)
    )
    reconSpecRepo.insert(spec)
    Payload.reconSpecPayloadFromDomain(spec)

  def updateReconSpec(idStr: String, payload: ReconciliationSpecPayload): Txn[ReconciliationSpecPayload] =
    val id       = UUID.fromString(idStr)
    var existing = reconSpecRepo.findById(id).ErrIfNotFound("reconciliation spec", id)
    existing = existing.copy(
      description       = payload.description,
      agingLimit        = payload.agingLimit,
      relationship      = Payload.reconRelationshipToDomain(payload.relationship),
      inMovementSpecId  = UUID.fromString(payload.inMovementSpecId),
      outMovementSpecId = UUID.fromString(payload.outMovementSpecId),
      ruleJson          = upickleWrite(payload.rule)
    )
    reconSpecRepo.update(existing)
    Payload.reconSpecPayloadFromDomain(existing)

  def deleteReconSpec(idStr: String): Txn[Unit] =
    val id = UUID.fromString(idStr)
    reconSpecRepo.deleteById(id)
