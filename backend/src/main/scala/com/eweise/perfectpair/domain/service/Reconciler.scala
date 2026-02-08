package com.eweise.perfectpair.domain.service

import com.eweise.perfectpair.common.model.{EqualityMatcher, SQLMatcher}
import com.eweise.perfectpair.domain.model
import com.eweise.perfectpair.domain.model.*
import com.eweise.perfectpair.domain.port.driven.*

def mapKeyToMovement(keys: Array[String], outMovements: Vector[Movement]) =
  outMovements
    .map: movement =>
      val outKey = keys.foldLeft("")((accum, key) => accum + movement.properties.props(key))
      outKey -> movement
    .toMap

class Reconciler(
  val fundFlowCache:      FundFlowCache,
  val accountRepo:        AccountRepo,
  val movementRepo:       MovementRepo,
  val reconSpecRepo:      ReconciliationSpecRepo,
  val movementSpecRepo:   MovementSpecRepo,
  val reconRepo:          ReconciliationRepo,
  val fundFlowConfigRepo: FundFlowConfigRepo
):

  def reconcileFundFlow(fundFlow: FundFlowConfig): Txn[Unit] =
    /* find reconciliation specs for account
      for each recon spec
     */

    val reconSpecs = reconSpecRepo.findByFundFlow(fundFlow.id)
    for reconSpec <- reconSpecs
    do
      reconSpec.matcher() match
        case eq: EqualityMatcher =>
          val inMovementSpec = movementSpecRepo.findById(eq.inMovementSpecID).getOrElse(
            throw NotFoundError(s"movement spec ${eq.inMovementSpecID} not found")
          )

          val outMovementSpec = movementSpecRepo.findById(eq.inMovementSpecID).getOrElse(
            throw NotFoundError(s"movement spec ${eq.inMovementSpecID} not found")
          )
        case _ => ()
