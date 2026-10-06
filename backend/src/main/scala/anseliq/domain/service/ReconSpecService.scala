package anseliq.domain.service

import anseliq.common.model.model.ID
import anseliq.domain.model.*
import anseliq.domain.port.driven.*
import com.typesafe.scalalogging.StrictLogging

class ReconSpecService(reconSpecRepo: ReconciliationSpecRepo) extends StrictLogging:

  def createReconSpec(spec: ReconciliationSpec): Txn[ReconciliationSpec] =
    logger.debug(s"[createReconSpec] ruleJson=${spec.ruleJson}")
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
    reconSpecRepo.update(updated)
    logger.debug(s"[updateReconSpec] updated.rule()=${updated.rule()}")
    updated

  def deleteReconSpec(id: ID): Txn[Unit] =
    reconSpecRepo.deleteById(id)
