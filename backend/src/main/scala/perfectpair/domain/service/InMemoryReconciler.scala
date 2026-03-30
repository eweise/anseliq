package perfectpair.domain.service

import perfectpair.common.model.*
import perfectpair.common.model.Direction.{IN, OUT}
import perfectpair.common.model.model.ID
import perfectpair.domain.model.*
import perfectpair.domain.model.Relationship.{ManyToMany, ManyToOne, OneToMany, OneToOne}
import perfectpair.domain.port.driven.*

import java.util.UUID
import scala.collection.mutable.ListBuffer
import scala.util.boundary
import scala.util.boundary.break

class InMemoryReconciler(
  val accountRepo:        AccountRepo,
  val movementRepo:       MovementRepo,
  val reconSpecRepo:      ReconciliationSpecRepo,
  val movementSpecRepo:   MovementSpecRepo,
  val reconRepo:          ReconciliationRepo,
  val fundFlowConfigRepo: FundFlowConfigRepo
):

  def getMovementsByRule(
    movementSpecs: Map[ID, Seq[Movement]],
    reconSpec:     ReconciliationSpec
  ): Txn[Map[ID, Seq[Movement]]] =
    var msMap        = movementSpecs
    var inMovements  = getMovements(reconSpec.inMovementSpecId)
    var outMovements = getMovements(reconSpec.outMovementSpecId)

    val inComparables = getComparableForAttributes(Seq(), reconSpec.rule(), IN)

    inMovements = inMovements.sortWith((a, b) =>
      var result = 0
      boundary:
        for (c <- inComparables) {
          result = c.compare(a.props, b.props)
          if result != 0 then break()
        }
      result < 0
    )

    val outComparables = getComparableForAttributes(Seq(), reconSpec.rule(), OUT)

    outMovements = outMovements.sortWith((a, b) =>
      var result = 0
      boundary:
        for (c <- outComparables) {
          result = c.compare(a.props, b.props)
          if result != 0 then break()
        }
      result < 0
    )

    Map(
      reconSpec.inMovementSpecId  -> inMovements,
      reconSpec.outMovementSpecId -> outMovements
    )

//    reconSpec.rule() match
//      case StringAttributeMatchRule =>
//        msMap = msMap + inMovements + outMovements
//        msMap
//      case AndRule(rules) => ???
////        rules.map(rule => msMap ++ getMovementsByRule(msMap, rule))
////        msMap
//      case ORRule(rules) => ??? // todo implement

  def reconcileAccount(account: Account): Txn[Unit] =

    val reconSpecs = reconSpecRepo.findByAccountID(account.id)
    for reconSpec <- reconSpecs
    do
      val movementsMap = getMovementsByRule(Map(), reconSpec)

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
              val inMovements = movementsMap(reconSpec.inMovementSpecId)
                .sortWith((x, y) =>
                  val xValue = x.properties.props(amr.inKey.key)
                  val yValue = y.properties.props(amr.inKey.key)
                  amr.inKey.compare(xValue, yValue) < 0
                )
              val outMovements = movementsMap(reconSpec.outMovementSpecId)
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
                  case x if x < 0 =>
                    inIndex += 1
                  case x if x > 0 =>
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

  private def getComparableForAttributes(
    comparables: Seq[CanCompareProperties],
    rule:        Rule,
    direction:   Direction
  ): Seq[CanCompareProperties] =
    rule match
      case rc: RuleCollection =>
        comparables ++ (for
          r       <- rc.rules
          matches <- getComparableForAttributes(comparables, r, direction)
        yield matches)
      case _ => comparables

  private def getMovements(movementSpecID: ID): Txn[Seq[Movement]] = {
    val movementSpec = movementSpecRepo.findById(movementSpecID).ErrIfNotFound("movementSpec", movementSpecID)
    movementRepo.findUnReconciledByMovementSpec(movementSpec.id)
  }
