package perfectpair.domain.service

import perfectpair.adapter.driving.Payload
import perfectpair.common.model.MovementSpecPayload
import perfectpair.domain.model.*
import perfectpair.domain.port.driven.*

import java.util.UUID

class MovementSpecOperations(
  val movementSpecRepo: MovementSpecRepo
):

  def createMovementSpec(payload: MovementSpecPayload): Txn[MovementSpecPayload] =
    val spec = MovementSpec(
      name           = payload.name,
      inAccountId    = UUID.fromString(payload.inAccountId),
      outAccountId   = UUID.fromString(payload.outAccountId),
      dataSourceJson = payload.dataSourceJson
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
      dataSourceJson = payload.dataSourceJson
    )
    movementSpecRepo.update(existing)
    Payload.movementSpecPayloadFromDomain(existing)

  def deleteMovementSpec(idStr: String): Txn[Unit] =
    val id = UUID.fromString(idStr)
    movementSpecRepo.deleteById(id)
