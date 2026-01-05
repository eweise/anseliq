package com.eweise.perfectpair.domain.service

import com.eweise.perfectpair.domain.model
import com.eweise.perfectpair.domain.model.*
import com.eweise.perfectpair.domain.model.Direction.{IN, OUT}
import com.eweise.perfectpair.domain.port.driven.*
import com.eweise.perfectpair.common.model.{EqualityMatcher, SQLMatcher}

def mapKeyToMovement(keys: Array[String], outMovements: Vector[Movement]) =
  outMovements
    .map: movement =>
      val outKey = keys.foldLeft("")((accum, key) => accum + movement.properties.props(key))
      outKey -> movement
    .toMap

class Reconciler(
  val fundFlowCache: FundFlowCache,
  val accountRepo: AccountRepo,
  val movementRepo: MovementRepo,
  val reconRepo: ReconciliationRepo,
  val fundFlowConfigRepo: FundFlowConfigRepo
):

  // todo this needs to pass in a reconciliation Account ID, not a fund flow
  def reconcileAccount(
    reconAccount: Account
  ): Txn[Unit] =

    val fundFlow = fundFlowCache
      .getConfigByID(reconAccount.fundFlowId)
      .getOrElse(throw NotFoundException(s"no fund flow found for ${reconAccount.fundFlowId}"))

    val reconAccountSpec = fundFlow.fundFlow.allAccounts
      .find(r => r.name == reconAccount.name && r.isReconSpec)
      .getOrElse(throw NotFoundException(s"no reconciliation account found for fund flow ${fundFlow.name}"))
      .asInstanceOf[model.ReconciliationAccountSpec]

    reconAccountSpec.matcher match
      case EqualityMatcher(inKeys, outKeys) =>
        val inMovements  = movementRepo.findUnReconciledByAccountAndDirection(reconAccount.id, IN)
        val outMovements = movementRepo.findUnReconciledByAccountAndDirection(reconAccount.id, OUT)

        val inKeysToMovements  = mapKeyToMovement(inKeys, inMovements)
        val outKeysToMovements = mapKeyToMovement(outKeys, outMovements)

        val matches = inKeysToMovements.keys.toSet.intersect(outKeysToMovements.keys.toSet)

        for
          aMatch      <- matches
          inMovement  <- inKeysToMovements.get(aMatch)
          outMovement <- outKeysToMovements.get(aMatch)
        do
          val reconciliation = Reconciliation()
          reconRepo.insert(reconciliation)
          movementRepo.update(
            inMovement.copy(
              status = MovementStatus.Reconciled,
              reconciliationId = Some(reconciliation.id)
            )
          )
          movementRepo.update(
            outMovement.copy(
              status = MovementStatus.Reconciled,
              reconciliationId = Some(reconciliation.id)
            )
          )
        ()

      case SQLMatcher(sql) => throw NotImplementedError("SQLMatcher not implemented")
