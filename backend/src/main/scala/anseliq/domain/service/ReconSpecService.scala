package anseliq.domain.service

import anseliq.adapter.driving.Payload
import anseliq.common.model.*
import anseliq.domain.model.*
import anseliq.domain.port.driven.*
import com.typesafe.scalalogging.StrictLogging
import upickle.default.write as upickleWrite

import java.util.UUID

class ReconSpecService(reconSpecRepo: ReconciliationSpecRepo) extends StrictLogging:

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
