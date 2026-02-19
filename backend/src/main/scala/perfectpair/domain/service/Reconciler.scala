package perfectpair.domain.service

import perfectpair.domain.model.*
import perfectpair.domain.port.driven.*

import java.util.UUID

def mapKeyToMovement(keys: Array[String], outMovements: Vector[Movement]) =
  outMovements
    .map: movement =>
      val outKey = keys.foldLeft("")((accum, key) => accum + movement.properties.props(key))
      outKey -> movement
    .toMap

class Reconciler(
  val accountRepo:        AccountRepo,
  val movementRepo:       MovementRepo,
  val reconSpecRepo:      ReconciliationSpecRepo,
  val movementSpecRepo:   MovementSpecRepo,
  val reconRepo:          ReconciliationRepo,
  val fundFlowConfigRepo: FundFlowConfigRepo
):

  def reconcileFundFlow(fundFlow: FundFlowConfig): Txn[Unit] =

    val reconSpecs = reconSpecRepo.findByFundFlow(fundFlow.id)
    for reconSpec <- reconSpecs
    do
      val matches = movementRepo.findEqualityMatches(reconSpec.rule().sql)

      for aMatch <- matches
      do
        val reconciliation = Reconciliation(
          specId      = reconSpec.id,
          description = "need a description. probabaly equality matcher"
        )

        val inMovement = movementRepo
          .findById(UUID.fromString(aMatch._1))
          .getOrElse(
            throw NotFoundError(s"inMovement ${aMatch._1} not found")
          )

        val outMovement = movementRepo
          .findById(UUID.fromString(aMatch._2))
          .getOrElse(
            throw NotFoundError(s"outMovement ${aMatch._2} not found")
          )

        inMovement.reconciliationId  = Some(reconciliation.id)
        outMovement.reconciliationId = Some(reconciliation.id)

        reconRepo.insert(reconciliation)
        movementRepo.update(inMovement)
        movementRepo.update(outMovement)
