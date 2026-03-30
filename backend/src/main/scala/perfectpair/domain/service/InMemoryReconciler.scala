package perfectpair.domain.service

import perfectpair.common.model.*
import perfectpair.common.model.model.ID
import perfectpair.domain.model.*
import perfectpair.domain.model.Relationship.{ManyToMany, ManyToOne, OneToMany, OneToOne}
import perfectpair.domain.port.driven.*

import java.util.UUID
import scala.collection.mutable.ListBuffer

class InMemoryReconciler(
  val accountRepo:        AccountRepo,
  val movementRepo:       MovementRepo,
  val reconSpecRepo:      ReconciliationSpecRepo,
  val movementSpecRepo:   MovementSpecRepo,
  val reconRepo:          ReconciliationRepo,
  val fundFlowConfigRepo: FundFlowConfigRepo
):

  def getMovementsByRule(movementSpecs: Map[ID, Seq[Movement]], rule: Rule): Txn[Map[ID, Seq[Movement]]] =
    var msMap = movementSpecs

    rule match
      case StringAttributeMatchRule(inId, outId, _, _) =>
        val inMovements  = getMovements(inId)
        val outMovements = getMovements(outId)
        msMap = msMap + inMovements + outMovements
        msMap
      case AndRule(rules) =>
        rules.map(rule => msMap ++ getMovementsByRule(msMap, rule))
        msMap
      case ORRule(rules) =>
        rules.map(rule => msMap ++ getMovementsByRule(msMap, rule))
        msMap

  def reconcileFundFlow(account: Account): Txn[Unit] =

    val reconSpecs = reconSpecRepo.findByAccountID(account.id)
    for reconSpec <- reconSpecs
    do
      val movementsMap = getMovementsByRule(Map(), reconSpec.rule())

      // build a mapping of the two sets of
      val groupedMatches = ListBuffer[(ListBuffer[String], ListBuffer[String])]()

      reconSpec.relationship match
        case OneToOne =>
          val rule = reconSpec.rule()
          // sort the lists according to the rule key attributes
          // assume key prority is first found in json
          // todo add sort priority to key attributes

          rule match
            case amr: StringAttributeMatchRule =>
              val inMovements = movementsMap(amr.inMovementSpecID)
                .sortWith((x, y) =>
                  val xValue = x.properties.props(amr.inKey.key)
                  val yValue = y.properties.props(amr.inKey.key)
                  amr.inKey.compare(xValue, yValue) < 0
                )
              val outMovements = movementsMap(amr.outMovementSpecID)
                .sortWith((x, y) =>
                  val xValue = x.properties.props(amr.outKey.key)
                  val yValue = y.properties.props(amr.outKey.key)
                  amr.outKey.compare(xValue, yValue) < 0
                )
              // find matches
              var inIndex  = 0
              var outIndex = 0
              while (inIndex < inMovements.size && outIndex < outMovements.size) {
                val yMovement  = inMovements(inIndex)
                val xMovement  = outMovements(outIndex)
                val inValue    = amr.inKey.getValue(yMovement.properties.props)
                val outValue   = amr.outKey.getValue(xMovement.properties.props)
                val comparison = inValue.compareTo(outValue)
                comparison match
                  case 0 =>
                    inIndex += 1
                    outIndex += 1
                    groupedMatches.append((ListBuffer(yMovement.id.toString), ListBuffer(xMovement.id.toString)))
                  case -1 =>
                    inIndex += 1
                  case 1 =>
                    outIndex += 1
              }

            case perfectpair.common.model.AndRule(_) => ???
            case perfectpair.common.model.ORRule(_)  => ???

        case OneToMany  =>
        case ManyToOne  =>
        case ManyToMany =>

      // Create reconciliations from matches
      for aMatch <- groupedMatches
      do
        val reconciliation = Reconciliation(
          specId       = reconSpec.id,
          appliedRules = reconSpec.rule().sql,
          description  = "need a description. probabaly equality matcher"
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

  private def getMovements(movementSpecID: ID): Txn[(ID, Seq[Movement])] = {
    val movementSpec = movementSpecRepo.findById(movementSpecID).ErrIfNotFound("movementSpec", movementSpecID)
    movementSpec.id -> movementRepo.findUnReconciledByMovementSpec(movementSpec.id)
  }
