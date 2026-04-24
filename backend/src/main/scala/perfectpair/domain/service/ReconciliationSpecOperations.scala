package perfectpair.domain.service

import perfectpair.adapter.driving.Payload
import perfectpair.common.model.ReconciliationSpecPayload
import perfectpair.domain.model.*
import perfectpair.domain.port.driven.*

import java.util.UUID

class ReconciliationSpecOperations(
  val reconSpecRepo: ReconciliationSpecRepo
):

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
      ruleJson          = upickle.write(payload.rule)
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
      ruleJson          = upickle.write(payload.rule)
    )
    reconSpecRepo.update(existing)
    Payload.reconSpecPayloadFromDomain(existing)

  def deleteReconSpec(idStr: String): Txn[Unit] =
    val id = UUID.fromString(idStr)
    reconSpecRepo.deleteById(id)
