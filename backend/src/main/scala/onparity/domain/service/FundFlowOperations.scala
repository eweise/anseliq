package onparity.domain.service

import com.typesafe.scalalogging.StrictLogging
import onparity.adapter.driving.Payload
import onparity.common.model.model.{ID, Money, Properties}
import onparity.common.model.{
  AccountPayload,
  FileFeedPayload,
  FundFlowPayload,
  MovementSpecPayload,
  ReconciliationSpecPayload
}
import onparity.domain.model.*
import onparity.domain.model.helper.JSONBProps
import onparity.domain.port.driven.*
import upickle.default.write as upickleWrite

import java.util.UUID

class FundFlowOperations(using
  val fundFlowConfigRepo: FundFlowConfigRepo,
  val accountRepo:        AccountRepo,
  val movementRepo:       MovementRepo,
  val movementSpecRepo:   MovementSpecRepo,
  val fileFeedRepo:       FileFeedRepo,
  val reconSpecRepo:      ReconciliationSpecRepo
) extends StrictLogging:

  def createFundFlow(payload: FundFlowPayload): Txn[FundFlowPayload] =
    val fundFlow = Payload.fundFlowPayloadToDomain(payload)
    fundFlowConfigRepo.insert(fundFlow)
    Payload.fundFlowPayloadFromDomain(fundFlow)

  def updateFundFlow(payload: FundFlowPayload): Txn[FundFlowPayload] =
    if payload.id.isEmpty then IllegalArgumentException("Fund flow ID is required")
    val ffID       = UUID.fromString(payload.id.get)
    var existingFF = fundFlowConfigRepo.findById(ffID).ErrIfNotFound("fund flow", ffID)
    existingFF = existingFF.copy(name = payload.name, dailyCutoffHours = payload.dailyCutoffHours)
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

  def createFileFeed(payload: FileFeedPayload): Txn[FileFeedPayload] =
    val feed = FileFeed(
      name                 = payload.name,
      dataSourceJson       = payload.dataSourceConfig.map(upickleWrite(_)).getOrElse(""),
      expectedCadenceHours = payload.expectedCadenceHours
    )
    fileFeedRepo.insert(feed)
    Payload.fileFeedPayloadFromDomain(feed)

  def updateFileFeed(idStr: String, payload: FileFeedPayload): Txn[FileFeedPayload] =
    val id       = UUID.fromString(idStr)
    var existing = fileFeedRepo.findById(id).ErrIfNotFound("file feed", id)
    existing = existing.copy(
      name                 = payload.name,
      dataSourceJson       = payload.dataSourceConfig.map(upickleWrite(_)).getOrElse(""),
      expectedCadenceHours = payload.expectedCadenceHours
    )
    fileFeedRepo.update(existing)
    Payload.fileFeedPayloadFromDomain(existing)

  def deleteFileFeed(idStr: String): Txn[Unit] =
    fileFeedRepo.deleteById(UUID.fromString(idStr))

  def createMovementSpec(payload: MovementSpecPayload): Txn[MovementSpecPayload] =
    val spec = MovementSpec(
      name              = payload.name,
      inAccountId       = UUID.fromString(payload.inAccountId),
      outAccountId      = UUID.fromString(payload.outAccountId),
      fileFeedId        = UUID.fromString(payload.fileFeedId),
      routingFilterJson = payload.routingFilter.map(upickleWrite(_))
    )
    movementSpecRepo.insert(spec)

    updateAccountIsRecon(spec)

    Payload.movementSpecPayloadFromDomain(spec)

  private def updateAccountIsRecon(existing: MovementSpec): Txn[Unit] = {
    var inAccount = accountRepo.findById(existing.inAccountId).ErrIfNotFound("account", existing.inAccountId)
    inAccount = inAccount.copy(isRecon = accountRepo.isReconAccount(inAccount.id))
    accountRepo.update(inAccount)

    var outAccount = accountRepo.findById(existing.outAccountId).ErrIfNotFound("account", existing.outAccountId)
    outAccount = outAccount.copy(isRecon = accountRepo.isReconAccount(outAccount.id))
    accountRepo.update(outAccount)
  }

  def updateMovementSpec(idStr: String, payload: MovementSpecPayload): Txn[MovementSpecPayload] =
    val id       = UUID.fromString(idStr)
    var existing = movementSpecRepo.findById(id).ErrIfNotFound("movement spec", id)
    existing = existing.copy(
      name              = payload.name,
      inAccountId       = UUID.fromString(payload.inAccountId),
      outAccountId      = UUID.fromString(payload.outAccountId),
      fileFeedId        = UUID.fromString(payload.fileFeedId),
      routingFilterJson = payload.routingFilter.map(upickleWrite(_))
    )
    movementSpecRepo.update(existing)

    updateAccountIsRecon(existing)

    Payload.movementSpecPayloadFromDomain(existing)

  def deleteMovementSpec(idStr: String): Txn[Unit] =
    val id       = UUID.fromString(idStr)
    val existing = movementSpecRepo.findById(id).ErrIfNotFound("movement spec", id)
    movementSpecRepo.deleteById(id)
    updateAccountIsRecon(existing)

  def createReconSpec(payload: ReconciliationSpecPayload): Txn[ReconciliationSpecPayload] =
    val accountId =
      UUID.fromString(payload.accountId.getOrElse(throw IllegalArgumentException("Account ID is required")))
    val inMovementSpecId  = UUID.fromString(payload.inMovementSpecId)
    val outMovementSpecId = UUID.fromString(payload.outMovementSpecId)
    val ruleJson          = upickleWrite(payload.rule)
    logger.debug(s"[createReconSpec] payload.rule=${payload.rule} ruleJson=$ruleJson")
    val spec = ReconciliationSpec(
      accountId         = accountId,
      agingLimit        = payload.agingLimit,
      relationship      = Payload.reconRelationshipToDomain(payload.relationship),
      inMovementSpecId  = inMovementSpecId,
      outMovementSpecId = outMovementSpecId,
      ruleJson          = ruleJson
    )
    reconSpecRepo.insert(spec)
    logger.debug(s"[createReconSpec] spec.rule()=${spec.rule()}")
    Payload.reconSpecPayloadFromDomain(spec)

  def updateReconSpec(idStr: String, payload: ReconciliationSpecPayload): Txn[ReconciliationSpecPayload] =
    val id       = UUID.fromString(idStr)
    var existing = reconSpecRepo.findById(id).ErrIfNotFound("reconciliation spec", id)
    val ruleJson = upickleWrite(payload.rule)
    logger.debug(s"[updateReconSpec] payload.rule=${payload.rule} ruleJson=$ruleJson")
    existing = existing.copy(
      agingLimit        = payload.agingLimit,
      relationship      = Payload.reconRelationshipToDomain(payload.relationship),
      inMovementSpecId  = UUID.fromString(payload.inMovementSpecId),
      outMovementSpecId = UUID.fromString(payload.outMovementSpecId),
      ruleJson          = ruleJson
    )
    reconSpecRepo.update(existing)
    logger.debug(s"[updateReconSpec] existing.rule()=${existing.rule()}")
    Payload.reconSpecPayloadFromDomain(existing)

  def deleteReconSpec(idStr: String): Txn[Unit] =
    val id = UUID.fromString(idStr)
    reconSpecRepo.deleteById(id)
