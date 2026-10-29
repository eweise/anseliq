package anseliq.domain.service

import anseliq.common.model.{NoOpRule, Rule}
import anseliq.common.model.model.ID
import anseliq.domain.model.*
import anseliq.domain.port.driven.*
import com.typesafe.scalalogging.StrictLogging

class ReconSpecService(
  reconSpecRepo:    ReconciliationSpecRepo,
  movementSpecRepo: MovementSpecRepo,
  accountRepo:      AccountRepo
) extends StrictLogging:

  def createReconSpec(spec: ReconciliationSpec): Txn[ReconciliationSpec] =
    logger.debug(s"[createReconSpec] ruleJson=${spec.ruleJson}")
    validateRule(spec.rule())
    validateSides(spec)
    reconSpecRepo.insert(spec)
    logger.debug(s"[createReconSpec] spec.rule()=${spec.rule()}")
    spec

  def updateReconSpec(
    id:                ID,
    agingLimit:        Int,
    relationship:      Relationship,
    inMovementSpecId:  ID,
    outMovementSpecId: ID,
    ruleJson:          String
  ): Txn[ReconciliationSpec] =
    val existing = reconSpecRepo.findById(id).ErrIfNotFound("reconciliation spec", id)
    logger.debug(s"[updateReconSpec] ruleJson=$ruleJson")
    val updated = existing.copy(
      agingLimit        = agingLimit,
      relationship      = relationship,
      inMovementSpecId  = inMovementSpecId,
      outMovementSpecId = outMovementSpecId,
      ruleJson          = ruleJson
    )
    validateRule(updated.rule())
    validateSides(updated)
    reconSpecRepo.update(updated)
    logger.debug(s"[updateReconSpec] updated.rule()=${updated.rule()}")
    updated

  // Rejects a rule automatic reconciliation can't run, so it fails here, where the person setting it
  // up can fix it, rather than on every later reconciliation run. NoOpRule is what a new rule form
  // starts as, so it gets its own message.
  private def validateRule(rule: Rule): Unit =
    rule match
      case NoOpRule => throw IllegalArgumentError("rule", "A reconciliation rule is required")
      case _ =>
        InMemoryReconciler.alternatives(rule) match
          case Left(reason) => throw IllegalArgumentError("rule", reason)
          case Right(_)     => ()

  // A reconciliation pairs the account's Primary side (inMovementSpecId: Primary → this Recon
  // account) with its Secondary side (outMovementSpecId: this Recon account → Secondary), which is
  // what lets the Recon account net to zero when the two sides match.
  private def validateSides(spec: ReconciliationSpec): Txn[Unit] =
    def counterpartType(movementSpecId: ID, reconSide: MovementSpec => ID, otherSide: MovementSpec => ID) =
      movementSpecRepo
        .findById(movementSpecId)
        .filter(ms => reconSide(ms) == spec.accountId)
        .flatMap(ms => accountRepo.findById(otherSide(ms)))
        .map(_.accountType)
    if !counterpartType(spec.inMovementSpecId, _.inAccountId, _.outAccountId).contains(AccountType.Primary) then
      throw IllegalArgumentError(
        "inMovementSpecId",
        "The Primary side must be a movement spec from a Primary account into this Recon account"
      )
    if !counterpartType(spec.outMovementSpecId, _.outAccountId, _.inAccountId).contains(AccountType.Secondary) then
      throw IllegalArgumentError(
        "outMovementSpecId",
        "The Secondary side must be a movement spec from this Recon account to a Secondary account"
      )

  def deleteReconSpec(id: ID): Txn[Unit] =
    reconSpecRepo.deleteById(id)
