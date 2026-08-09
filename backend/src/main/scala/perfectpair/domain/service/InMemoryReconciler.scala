package perfectpair.domain.service

import perfectpair.common.model.*
import perfectpair.common.model.Direction.{IN, OUT}
import perfectpair.common.model.model.{ID, Money, Zero}
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
    var reconciledDelta: Money = Zero

    val reconSpecs = reconSpecRepo.findByAccountID(accountID)
    for reconSpec <- reconSpecs
    do
      val unreconciledMovementsMap = getUnreconciledMovementsByRule(Map(), reconSpec)

      val groupedMatches   = ListBuffer[(mutable.HashSet[String], mutable.HashSet[String])]()
      var reconInsertCount = 0

      reconSpec.relationship match
        case OneToOne =>
          val rule = reconSpec.rule()

          val inMovements  = unreconciledMovementsMap(reconSpec.inMovementSpecId)
          val outMovements = unreconciledMovementsMap(reconSpec.outMovementSpecId)

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

        case OneToMany =>
          val rule                  = reconSpec.rule()
          val inMovements           = unreconciledMovementsMap(reconSpec.inMovementSpecId)
          var remainingOutMovements = unreconciledMovementsMap(reconSpec.outMovementSpecId).toBuffer

          // Phase 1: complete existing InProgress reconciliations
          val inProgressRecons = reconRepo.findInProgressBySpecId(reconSpec.id)
          for recon <- inProgressRecons do
            val reconMovements = movementRepo.findByReconciliationID(recon.id)
            val inMovement     = reconMovements.find(_.movementSpecId == reconSpec.inMovementSpecId)
            inMovement.foreach { inMov =>
              val matched = remainingOutMovements.filter(outMov => compareMovements(rule, inMov, outMov) == 0)
              remainingOutMovements = remainingOutMovements.filterNot(m => matched.exists(_.id == m.id))

              for outMov <- matched do
                outMov.reconciliationId = Some(recon.id)
                movementRepo.update(outMov)
                recon.balance   = recon.balance - outMov.amount
                reconciledDelta = reconciledDelta + outMov.signedAmountFor(accountID)

              if matched.nonEmpty then
                if recon.balance == Zero then recon.status = ReconciliationStatus.Final
                reconRepo.update(recon)
            }

          // Phase 2: match remaining unreconciled movements
          val outMovements = remainingOutMovements.toSeq
          var inIndex      = 0
          var outIndex     = 0
          while inIndex < inMovements.size && outIndex < outMovements.size do
            val inMovement  = inMovements(inIndex)
            val outMovement = outMovements(outIndex)
            val comparison  = compareMovements(rule, inMovement, outMovement)
            if comparison == 0 then
              val matchedOuts = ListBuffer(outMovement)
              outIndex += 1
              while outIndex < outMovements.size && compareMovements(rule, inMovement, outMovements(outIndex)) == 0 do
                matchedOuts += outMovements(outIndex)
                outIndex += 1

              val now     = clock.now
              var balance = inMovement.amount
              for out <- matchedOuts do balance = balance - out.amount

              val status =
                if balance == Zero then ReconciliationStatus.Final
                else ReconciliationStatus.InProgress

              val reconciliation = Reconciliation(
                specId       = reconSpec.id,
                appliedRules = rule.sql,
                description  = "",
                status       = status,
                balance      = balance,
                createdAt    = Some(now),
                updatedAt    = Some(now)
              )
              reconRepo.insert(reconciliation)
              reconInsertCount += 1

              inMovement.reconciliationId = Some(reconciliation.id)
              movementRepo.update(inMovement)
              reconciledDelta = reconciledDelta + inMovement.signedAmountFor(accountID)
              for out <- matchedOuts do
                out.reconciliationId = Some(reconciliation.id)
                movementRepo.update(out)
                reconciledDelta = reconciledDelta + out.signedAmountFor(accountID)

              inIndex += 1
            else if comparison < 0 then inIndex += 1
            else outIndex += 1
        case ManyToOne  =>
        case ManyToMany =>

      // Create reconciliations from matches (used by OneToOne)
      for matches <- groupedMatches
      do
        val now = clock.now
        val reconciliation = Reconciliation(
          specId       = reconSpec.id,
          appliedRules = reconSpec.rule().sql,
          description  = "",
          createdAt    = Some(now),
          updatedAt    = Some(now)
        )
        reconRepo.insert(reconciliation)
        reconInsertCount += 1

        var matchBalance = Zero
        for inMatches <- matches._1
        do
          val inMovement = movementRepo
            .findById(UUID.fromString(inMatches))
            .getOrElse(
              throw NotFoundError(s"inMovement ${inMatches} not found")
            )
          inMovement.reconciliationId = Some(reconciliation.id)
          movementRepo.update(inMovement)
          matchBalance    = matchBalance + inMovement.amount
          reconciledDelta = reconciledDelta + inMovement.signedAmountFor(accountID)

        for outMatches <- matches._2
        do
          val outMovement = movementRepo
            .findById(UUID.fromString(outMatches))
            .getOrElse(
              throw NotFoundError(s"outMovement ${outMatches} not found")
            )
          outMovement.reconciliationId = Some(reconciliation.id)
          movementRepo.update(outMovement)
          matchBalance           = matchBalance - outMovement.amount
          reconciliation.balance = reconciliation.balance + matchBalance
          reconciledDelta        = reconciledDelta + outMovement.signedAmountFor(accountID)

      reconciliationSpecInsertCount.put(reconSpec.id, reconInsertCount)

    val account = accountRepo
      .findById(accountID)
      .getOrElse(
        throw NotFoundError(s"account $accountID not found")
      )
    account.reconciledBalance   = account.reconciledBalance + reconciledDelta
    account.unreconciledBalance = account.unreconciledBalance - reconciledDelta
    accountRepo.update(account)

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

  private def getUnreconciledMovementsByRule(
    movementSpecs: Map[ID, Seq[Movement]],
    reconSpec:     ReconciliationSpec
  ): Txn[Map[ID, Seq[Movement]]] =
    var msMap        = movementSpecs
    var inMovements  = getUnreconciledMovements(reconSpec.inMovementSpecId)
    var outMovements = getUnreconciledMovements(reconSpec.outMovementSpecId)

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

  private def getUnreconciledMovements(movementSpecID: ID): Txn[Seq[Movement]] = {
    val movementSpec = movementSpecRepo.findById(movementSpecID).ErrIfNotFound("movementSpec", movementSpecID)
    movementRepo.findUnReconciledByMovementSpec(movementSpec.id)
  }
