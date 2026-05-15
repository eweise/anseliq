package perfectpair.domain.service

import perfectpair.common.model.*
import perfectpair.common.model.Direction.{IN, OUT}
import perfectpair.common.model.model.ID
import perfectpair.domain.model.*
import perfectpair.domain.model.Relationship.{ManyToMany, ManyToOne, OneToMany, OneToOne}
import perfectpair.domain.port.driven.*

import java.time.Instant
import java.util.UUID
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.boundary
import scala.util.boundary.break

case class ReconciliationResult(
  reconciliationSpecInsertCount: Map[ID, Int],
  processingTimeSeconds:         Long
):
  def totalCount: Int = reconciliationSpecInsertCount.values.sum

class InMemoryReconciler(using
  val accountRepo:        AccountRepo,
  val movementRepo:       MovementRepo,
  val reconSpecRepo:      ReconciliationSpecRepo,
  val movementSpecRepo:   MovementSpecRepo,
  val reconRepo:          ReconciliationRepo,
  val fundFlowConfigRepo: FundFlowConfigRepo,
  val eventStore:         EventStore,
  val clock:              ClockService
):

  /**
   * Main reconciliation method
   */
  def reconcileAccount(accountID: ID): Txn[ReconciliationResult] =
    val beginTime                     = Instant.now()
    val reconciliationSpecInsertCount = scala.collection.mutable.Map[ID, Int]()

    val reconSpecs = reconSpecRepo.findByAccountID(accountID)
    for reconSpec <- reconSpecs
    do
      val movementsMap = getMovementsByRule(Map(), reconSpec)

      // build a mapping of the two sets of
      val groupedMatches = ListBuffer[(mutable.HashSet[String], mutable.HashSet[String])]()

      reconSpec.relationship match
        case OneToOne =>
          val rule = reconSpec.rule()

          val inMovements  = movementsMap(reconSpec.inMovementSpecId)
          val outMovements = movementsMap(reconSpec.outMovementSpecId)

          // find matches
          var inIndex  = 0
          var outIndex = 0
          while (inIndex < inMovements.size && outIndex < outMovements.size) {
            val inMovement  = inMovements(inIndex)
            val outMovement = outMovements(outIndex)
            val comparison  = compareMovements(rule, inMovement, outMovement)
            comparison match
              case 0 =>
                inIndex += 1
                outIndex += 1
                groupedMatches.append(
                  (mutable.HashSet(inMovement.id.toString), mutable.HashSet(outMovement.id.toString))
                )
              case x if x < 0 =>
                inIndex += 1
              case x if x > 0 =>
                outIndex += 1
          }

        // The IN side is always the One
        // the OUT side is always the Many
        case OneToMany =>
          val rule = reconSpec.rule()

          val inMovements  = movementsMap(reconSpec.inMovementSpecId)
          val outMovements = movementsMap(reconSpec.outMovementSpecId)

          // find matches
          var inIndex        = 0
          var outIndex       = 0
          var inMovementSet  = new mutable.HashSet[String]()
          var outMovementSet = new mutable.HashSet[String]()
          groupedMatches.append((inMovementSet, outMovementSet))
          while (inIndex < inMovements.size && outIndex < outMovements.size) {
            val inMovement              = inMovements(inIndex)
            val outMovement             = outMovements(outIndex)
            val comparison              = compareMovements(rule, inMovement, outMovement)
            var previousComparisonFound = false
            comparison match
              case 0 =>
                inMovementSet.add(inMovement.id.toString)
                outMovementSet.add(outMovement.id.toString)
                outIndex += 1
                previousComparisonFound = true
              case _ =>
                if comparison < 0 then inIndex += 1 else outIndex += 1
                if previousComparisonFound then
                  inMovementSet  = mutable.HashSet[String]()
                  outMovementSet = mutable.HashSet[String]()
                  groupedMatches.append((inMovementSet, outMovementSet))
                  previousComparisonFound = false
          }
        case ManyToOne  =>
        case ManyToMany =>

      // Create reconciliations from matches
      var reconInsertCount = 0
      for aMatch <- groupedMatches
      do
        val now = clock.now
        val reconciliation = Reconciliation(
          specId       = reconSpec.id,
          appliedRules = reconSpec.rule().sql,
          description  = "need a description. probably equality matcher",
          createdAt    = Some(now),
          updatedAt    = Some(now)
        )
        reconRepo.insert(reconciliation)
        reconInsertCount += 1

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
          reconciliationSpecInsertCount.put(reconSpec.id, reconInsertCount)

    eventStore.publishEvent(AccountReconciled(accountID = accountID))
    
    ReconciliationResult(
      reconciliationSpecInsertCount = reconciliationSpecInsertCount.toMap,
      processingTimeSeconds         = Instant.now().getEpochSecond - beginTime.getEpochSecond
    )

  /**
   * Compares two movements based on the rule argument. If the rule is a collection (AND, OR) then
   * it aggregates the collection of rule outcomes.
   * @param rule
   * @param inMovement
   * @param outMovement
   * @return
   */
  private def compareMovements(rule: Rule, inMovement: Movement, outMovement: Movement): Int =
    rule match
      case s: StringAttributeMatchRule =>
        val inValue  = s.inKey.getValue(inMovement.props)
        val outValue = s.outKey.getValue(outMovement.props)
        inValue.compareTo(outValue)

      case d: DateAttributeMatchRule =>
        val inValue  = d.inKey.getValue(inMovement.props)
        val outValue = d.outKey.getValue(outMovement.props)
        inValue.compareTo(outValue)
      case AndRule(rules) =>
        rules.foldLeft(0) { (result, r) =>
          if result == 0 then
            val subRuleResult = compareMovements(r, inMovement, outMovement)
            result + subRuleResult
          else result
        }
      case _ => ??? // todo implement me

  private def getMovementsByRule(
    movementSpecs: Map[ID, Seq[Movement]],
    reconSpec:     ReconciliationSpec
  ): Txn[Map[ID, Seq[Movement]]] =
    var msMap        = movementSpecs
    var inMovements  = getMovements(reconSpec.inMovementSpecId)
    var outMovements = getMovements(reconSpec.outMovementSpecId)

    val inComparables = getComparableForAttributes(Seq(), reconSpec.rule(), IN)
    inMovements = doSort(inComparables, inMovements)

    val outComparables = getComparableForAttributes(Seq(), reconSpec.rule(), OUT)
    outMovements = doSort(outComparables, outMovements)

    Map(
      reconSpec.inMovementSpecId  -> inMovements,
      reconSpec.outMovementSpecId -> outMovements
    )

  /**
   * doSort sort the movements by applying the Seq of comparators until one of them is not equal
   * @param comparables
   * @param movements
   * @return
   */
  private def doSort(comparables: Seq[CanCompareProperties], movements: Seq[Movement]): Seq[Movement] =
    movements.sortWith((a, b) =>
      var result = 0
      boundary:
        for (c <- comparables) {
          result = c.compare(a.props, b.props)
          if result != 0 then break()
        }
      result < 0
    )

  private def getComparableForAttributes(
    comparables: Seq[CanCompareProperties],
    rule:        Rule,
    direction:   Direction
  ): Seq[CanCompareProperties] =
    var foo = rule.isInstanceOf[CanCompareProperties]
    rule match
      case rc: RuleCollection =>
        comparables ++ (for
          rule    <- rc.rules
          matches <- getComparableForAttributes(comparables, rule, direction)
        yield matches)
      case am: AttributeMatcher =>
        val comparator = direction match
          case IN  => am.inKey
          case OUT => am.outKey
        comparables :+ comparator

  private def getMovements(movementSpecID: ID): Txn[Seq[Movement]] = {
    val movementSpec = movementSpecRepo.findById(movementSpecID).ErrIfNotFound("movementSpec", movementSpecID)
    movementRepo.findUnReconciledByMovementSpec(movementSpec.id)
  }
