package perfectpair.domain.service

import perfectpair.domain.model.*
import perfectpair.domain.model.Relationship.{ManyToMany, ManyToOne, OneToMany, OneToOne}
import perfectpair.domain.port.driven.*

import java.util.UUID
import scala.collection.mutable.ListBuffer

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

  def reconcileAccount(account: Account): Txn[Unit] =

    val reconSpecs = reconSpecRepo.findByAccountID(account.id)
    for reconSpec <- reconSpecs
    do
      val matches = movementRepo.findEqualityMatches(
        reconSpec.rule().sql,
        reconSpec.inMovementSpecId,
        reconSpec.outMovementSpecId,
        reconSpec.relationship
      )

      // build a mapping of the two sets of
      val groupedMatches = ListBuffer[(ListBuffer[String], ListBuffer[String])]()

      reconSpec.relationship match
        case OneToOne =>
          for aMatch <- matches
          do groupedMatches.addOne(ListBuffer(aMatch._1), ListBuffer(aMatch._2))
        case OneToMany =>
          var leftGroup  = ListBuffer[String]()
          var rightGroup = ListBuffer[String]()
          for aMatch <- matches
          do
            if leftGroup.isEmpty then
              leftGroup.addOne(aMatch._1)
              rightGroup.addOne(aMatch._2)
            else if aMatch._1 != leftGroup.head then
              groupedMatches.addOne((leftGroup, rightGroup))
              leftGroup  = ListBuffer(aMatch._1)
              rightGroup = ListBuffer(aMatch._2)
            else rightGroup.addOne(aMatch._2)

          groupedMatches.addOne(leftGroup, rightGroup)
        case ManyToOne  =>
        case ManyToMany =>

      for aMatch <- groupedMatches
      do
        val reconciliation = Reconciliation(
          specId       = reconSpec.id,
          appliedRules = reconSpec.ruleJson
        )
        reconRepo.insert(reconciliation)

        for inMatch <- aMatch._1
        do
          val inMovement = movementRepo
            .findById(UUID.fromString(inMatch))
            .getOrElse(
              throw NotFoundError(s"inMovement ${inMatch} not found")
            )
          inMovement.reconciliationId = Some(reconciliation.id)
          movementRepo.update(inMovement)

        for outMatch <- aMatch._2
        do
          val outMovement = movementRepo
            .findById(UUID.fromString(outMatch))
            .getOrElse(
              throw NotFoundError(s"outMovement ${outMatch} not found")
            )
          outMovement.reconciliationId = Some(reconciliation.id)
          movementRepo.update(outMovement)
