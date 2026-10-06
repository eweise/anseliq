package anseliq.domain.service

import anseliq.common.model.{NoOpRule, Rule}
import anseliq.common.model.model.ID
import anseliq.domain.model.*
import anseliq.domain.port.driven.*
import com.typesafe.scalalogging.StrictLogging

class ReconSpecService(reconSpecRepo: ReconciliationSpecRepo) extends StrictLogging:

  def createReconSpec(spec: ReconciliationSpec): Txn[ReconciliationSpec] =
    logger.debug(s"[createReconSpec] ruleJson=${spec.ruleJson}")
    validateRule(spec.rule())
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
        InMemoryReconciler.leafMatchRules(rule) match
          case Left(reason) => throw IllegalArgumentError("rule", reason)
          case Right(Seq()) => throw IllegalArgumentError("rule", "A reconciliation rule needs at least one condition")
          case Right(_)     => ()

  def deleteReconSpec(id: ID): Txn[Unit] =
    reconSpecRepo.deleteById(id)
