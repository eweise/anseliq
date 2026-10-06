package anseliq.domain.service

import anseliq.adapter.driving.Payload
import anseliq.common.model.model.ID
import anseliq.common.model.*
import anseliq.domain.model.*
import anseliq.domain.port.driven.*
import upickle.default.write as upickleWrite

import java.util.UUID

class MovementSpecService(movementSpecRepo: MovementSpecRepo, accountRepo: AccountRepo):

  def createMovementSpec(payload: MovementSpecPayload): Txn[MovementSpecPayload] =
    val inAccountId  = UUID.fromString(payload.inAccountId)
    val outAccountId = UUID.fromString(payload.outAccountId)
    validateAccountLink(inAccountId, outAccountId)
    val spec = MovementSpec(
      name              = payload.name,
      inAccountId       = inAccountId,
      outAccountId      = outAccountId,
      fileFeedId        = UUID.fromString(payload.fileFeedId),
      routingFilterJson = payload.routingFilter.map(upickleWrite(_))
    )
    movementSpecRepo.insert(spec)
    Payload.movementSpecPayloadFromDomain(spec)

  // A link is only ever valid between one Recon account and one Primary-or-Secondary account —
  // never Recon-to-Recon, and never Primary-to-Secondary. Which side (in/out) the Recon account
  // occupies doesn't matter here, only that exactly one side is Recon.
  private def validateAccountLink(inAccountId: ID, outAccountId: ID): Txn[Unit] =
    val inAccount  = accountRepo.findById(inAccountId).ErrIfNotFound("account", inAccountId)
    val outAccount = accountRepo.findById(outAccountId).ErrIfNotFound("account", outAccountId)
    val reconSides = Seq(inAccount, outAccount).count(_.accountType == AccountType.Recon)
    if reconSides != 1 then
      throw IllegalArgumentException(
        "A movement spec must link exactly one Recon account with one Primary or Secondary account"
      )

  def updateMovementSpec(idStr: String, payload: MovementSpecPayload): Txn[MovementSpecPayload] =
    val id           = UUID.fromString(idStr)
    val inAccountId  = UUID.fromString(payload.inAccountId)
    val outAccountId = UUID.fromString(payload.outAccountId)
    validateAccountLink(inAccountId, outAccountId)
    var existing = movementSpecRepo.findById(id).ErrIfNotFound("movement spec", id)
    existing = existing.copy(
      name              = payload.name,
      inAccountId       = inAccountId,
      outAccountId      = outAccountId,
      fileFeedId        = UUID.fromString(payload.fileFeedId),
      routingFilterJson = payload.routingFilter.map(upickleWrite(_))
    )
    movementSpecRepo.update(existing)
    Payload.movementSpecPayloadFromDomain(existing)

  def deleteMovementSpec(idStr: String): Txn[Unit] =
    val id = UUID.fromString(idStr)
    movementSpecRepo.findById(id).ErrIfNotFound("movement spec", id)
    movementSpecRepo.deleteById(id)
