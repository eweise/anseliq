package anseliq.domain.service

import anseliq.common.model.model.ID
import anseliq.domain.model.*
import anseliq.domain.port.driven.*

class MovementSpecService(movementSpecRepo: MovementSpecRepo, accountRepo: AccountRepo):

  def createMovementSpec(spec: MovementSpec): Txn[MovementSpec] =
    validateAccountLink(spec.inAccountId, spec.outAccountId)
    movementSpecRepo.insert(spec)
    spec

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

  def updateMovementSpec(
    id:                ID,
    name:              String,
    inAccountId:       ID,
    outAccountId:      ID,
    fileFeedId:        ID,
    routingFilterJson: Option[String]
  ): Txn[MovementSpec] =
    validateAccountLink(inAccountId, outAccountId)
    val existing = movementSpecRepo.findById(id).ErrIfNotFound("movement spec", id)
    val updated = existing.copy(
      name              = name,
      inAccountId       = inAccountId,
      outAccountId      = outAccountId,
      fileFeedId        = fileFeedId,
      routingFilterJson = routingFilterJson
    )
    movementSpecRepo.update(updated)
    updated

  def deleteMovementSpec(id: ID): Txn[Unit] =
    movementSpecRepo.findById(id).ErrIfNotFound("movement spec", id)
    movementSpecRepo.deleteById(id)
