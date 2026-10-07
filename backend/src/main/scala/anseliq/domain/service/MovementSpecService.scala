package anseliq.domain.service

import anseliq.common.model.model.ID
import anseliq.domain.model.*
import anseliq.domain.port.driven.*

class MovementSpecService(movementSpecRepo: MovementSpecRepo, accountRepo: AccountRepo):

  def createMovementSpec(spec: MovementSpec): Txn[MovementSpec] =
    validateAccountLink(spec.inAccountId, spec.outAccountId)
    movementSpecRepo.insert(spec)
    spec

  // Money only ever flows Primary → Recon → Secondary, so a movement spec is one of exactly two
  // links: a Primary account paying into a Recon account, or a Recon account paying out to a
  // Secondary one. Anything else is rejected: Recon-to-Recon, Primary-to-Secondary, or either link
  // reversed. A reversed ledger could still balance, but it reads backwards to the people using it.
  private def validateAccountLink(inAccountId: ID, outAccountId: ID): Txn[Unit] =
    val inAccount  = accountRepo.findById(inAccountId).ErrIfNotFound("account", inAccountId)
    val outAccount = accountRepo.findById(outAccountId).ErrIfNotFound("account", outAccountId)
    (outAccount.accountType, inAccount.accountType) match
      case (AccountType.Primary, AccountType.Recon) | (AccountType.Recon, AccountType.Secondary) => ()
      case (from, to) =>
        throw IllegalArgumentError(
          "movementSpec",
          s"A movement spec must be Primary → Recon or Recon → Secondary, not $from → $to"
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
