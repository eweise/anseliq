package perfectpair.domain.service

import com.augustnagro.magnum.{DbTx, Transactor, transact}
import com.typesafe.scalalogging.Logger
import net.logstash.logback.argument.StructuredArguments.kv
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

case class ReconciliationCheckpoint(
  lastInMatchKey:  Option[String] = None,
  lastOutMatchKey: Option[String] = None
) derives upickle.default.ReadWriter

case class SpecBatchProgress(specId: ID, matchesInBatch: Int, checkpoint: ReconciliationCheckpoint)

case class ReconciliationResumableResult(
  reconciliationSpecInsertCount: Map[ID, Int],
  processingTimeSeconds:         Long,
  completed:                     Boolean
):
  def totalCount: Int = reconciliationSpecInsertCount.values.sum

private case object ReconciliationStopped extends RuntimeException

class InMemoryReconciler(using
  val ledger:             Ledger,
  val movementRepo:       MovementRepo,
  val reconSpecRepo:      ReconciliationSpecRepo,
  val movementSpecRepo:   MovementSpecRepo,
  val reconRepo:          ReconciliationRepo,
  val fundFlowConfigRepo: FundFlowConfigRepo,
  val eventStore:         EventStore,
  val clock:              ClockService,
  val transactor:         Transactor,
  val jobRepo:            JobRepo
):

  private val pageSize = 500

  /**
   * Main reconciliation method
   */
  def reconcileAccount(accountId: ID): Txn[ReconciliationResult] =
    val logger = Logger(getClass.getName)
    logger.info("reconcile account", kv("account_id", accountId))

    val beginTime                     = Instant.now()
    val reconciliationSpecInsertCount = scala.collection.mutable.Map[ID, Int]()

    val reconSpecs = reconSpecRepo.findByAccountID(accountId)
    for reconSpec <- reconSpecs
    do
      val unreconciledMovementsMap = getUnreconciledMovementsByRule(reconSpec)

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

              for outMov <- matched do recon.balance = recon.balance - outMov.amount

              if matched.nonEmpty then
                ledger.reconcile(matched.toSeq, recon.id, accountId)
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

              ledger.reconcile(inMovement +: matchedOuts.toSeq, reconciliation.id, accountId)

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

        val matchedMovements = ListBuffer[Movement]()

        var matchBalance = Zero
        for inMatches <- matches._1
        do
          val inMovement = movementRepo
            .findById(UUID.fromString(inMatches))
            .getOrElse(
              throw NotFoundError(s"inMovement ${inMatches} not found")
            )
          matchedMovements += inMovement
          matchBalance = matchBalance + inMovement.amount

        for outMatches <- matches._2
        do
          val outMovement = movementRepo
            .findById(UUID.fromString(outMatches))
            .getOrElse(
              throw NotFoundError(s"outMovement ${outMatches} not found")
            )
          matchedMovements += outMovement
          matchBalance           = matchBalance - outMovement.amount
          reconciliation.balance = reconciliation.balance + matchBalance

        reconciliation.status =
          if reconciliation.balance == Zero then ReconciliationStatus.Final else ReconciliationStatus.InProgress
        reconRepo.update(reconciliation)
        ledger.reconcile(matchedMovements.toSeq, reconciliation.id, accountId)

      reconciliationSpecInsertCount.put(reconSpec.id, reconInsertCount)

    eventStore.publishEvent(AccountReconciled(accountID = accountId))

    ReconciliationResult(
      reconciliationSpecInsertCount = reconciliationSpecInsertCount.toMap,
      processingTimeSeconds         = Instant.now().getEpochSecond - beginTime.getEpochSecond
    )

  def reconcileAccountResumable(
    accountId:       ID,
    batchSize:       Int                                = 500,
    jobId:           Option[ID]                         = None,
    onBatchComplete: SpecBatchProgress => BatchDecision = _ => BatchDecision.Commit
  ): ReconciliationResumableResult =
    val beginTime = Instant.now()

    val startingCheckpoints: Map[ID, ReconciliationCheckpoint] =
      jobId
        .flatMap(id => transact(transactor)(jobRepo.findById(id)))
        .flatMap(_.progressJson)
        .map(upickle.read[Map[ID, ReconciliationCheckpoint]](_))
        .getOrElse(Map.empty)

    val reconSpecs = transact(transactor)(reconSpecRepo.findByAccountID(accountId))

    var specCounts  = Map.empty[ID, Int]
    var checkpoints = startingCheckpoints
    var completed   = true

    boundary:
      for reconSpec <- reconSpecs do
        val startCheckpoint = checkpoints.getOrElse(reconSpec.id, ReconciliationCheckpoint())
        reconSpec.relationship match
          case OneToOne =>
            val (matchCount, endingCheckpoint, stopped) =
              reconcileOneToOneStreaming(
                reconSpec,
                accountId,
                startCheckpoint,
                batchSize,
                jobId,
                checkpoints,
                onBatchComplete
              )
            specCounts += (reconSpec.id  -> matchCount)
            checkpoints += (reconSpec.id -> endingCheckpoint)
            if stopped then
              completed = false
              break()
          case OneToMany =>
            completeInProgressReconciliations(reconSpec, accountId)
            val (matchCount, endingCheckpoint, stopped) =
              reconcileOneToManyStreaming(
                reconSpec,
                accountId,
                startCheckpoint,
                batchSize,
                jobId,
                checkpoints,
                onBatchComplete
              )
            specCounts += (reconSpec.id  -> matchCount)
            checkpoints += (reconSpec.id -> endingCheckpoint)
            if stopped then
              completed = false
              break()
          case ManyToOne | ManyToMany =>
            specCounts += (reconSpec.id -> 0)

    if specCounts.values.sum > 0 then
      transact(transactor)(eventStore.publishEvent(AccountReconciled(accountID = accountId)))

    ReconciliationResumableResult(
      reconciliationSpecInsertCount = specCounts,
      processingTimeSeconds         = Instant.now().getEpochSecond - beginTime.getEpochSecond,
      completed                     = completed
    )

  // v1 scope: single-attribute rules only (StringAttributeMatchRule / DateAttributeMatchRule),
  // and only when the attribute has no modifier — StringAttribute.modifySQL/DateAttribute.modifySQL
  // assume a JSONB-bracket-access fragment and (for String, when a modifier is present) cast it to
  // ::text, which preserves JSON quoting (e.g. "20" with literal quote characters). That doesn't
  // match a plain unquoted Scala string, which is what afterKey (and the checkpoint values derived
  // from Movement.props) actually are, so composite rules and modifiers are out of scope here and
  // throw clearly rather than silently comparing the wrong representation.
  private def matchKeyAttribute(rule: Rule, direction: Direction): StringAttribute | DateAttribute =
    rule match
      case s: StringAttributeMatchRule => if direction == IN then s.inKey else s.outKey
      case d: DateAttributeMatchRule   => if direction == IN then d.inKey else d.outKey
      case other =>
        throw UnsupportedOperationException(
          s"reconcileAccountResumable only supports single-attribute rules (StringAttributeMatchRule/DateAttributeMatchRule); got ${other.getClass.getSimpleName}"
        )

  private def matchKeyExpr(rule: Rule, direction: Direction, tableAlias: String): String =
    matchKeyAttribute(rule, direction) match
      case s: StringAttribute if s.modifier.isEmpty =>
        s"($tableAlias.properties['props'] ->> '${s.key}')"
      case d: DateAttribute if d.modifier.isEmpty =>
        s"($tableAlias.properties['props'] ->> '${d.key}')::date"
      case _ =>
        throw UnsupportedOperationException(
          "reconcileAccountResumable does not support attribute modifiers yet"
        )

  private def matchKeyValue(rule: Rule, direction: Direction, movement: Movement): String =
    matchKeyAttribute(rule, direction) match
      case s: StringAttribute => s.getValue(movement.props)
      case d: DateAttribute   => d.getValue(movement.props).toString

  private def fetchPage(
    movementSpecId: ID,
    matchKeyExpr:   String,
    afterKey:       Option[String]
  ): Vector[Movement] =
    transact(transactor):
      movementRepo.findUnreconciledPage(movementSpecId, matchKeyExpr, afterKey, pageSize)

  /**
   * Streams both sides of a OneToOne match via keyset pagination instead of loading every
   * unreconciled movement into memory, and commits every `batchSize` matches in its own transaction
   * instead of one transaction for the whole account. If `jobId` is provided, the checkpoint for
   * this spec is persisted on that job's progressJson inside each batch's transaction, so a crashed
   * attempt can be resumed by calling this again with the same jobId — but a fresh call (no jobId,
   * or a different job) always starts from startCheckpoint with no additional lower bound, so
   * late-arriving movements are never permanently skipped.
   */
  private def reconcileOneToOneStreaming(
    reconSpec:       ReconciliationSpec,
    accountId:       ID,
    startCheckpoint: ReconciliationCheckpoint,
    batchSize:       Int,
    jobId:           Option[ID],
    allCheckpoints:  Map[ID, ReconciliationCheckpoint],
    onBatchComplete: SpecBatchProgress => BatchDecision
  ): (Int, ReconciliationCheckpoint, Boolean) =
    val rule    = reconSpec.rule()
    val inExpr  = matchKeyExpr(rule, IN, "a")
    val outExpr = matchKeyExpr(rule, OUT, "a")

    var inPage       = fetchPage(reconSpec.inMovementSpecId, inExpr, startCheckpoint.lastInMatchKey)
    var outPage      = fetchPage(reconSpec.outMovementSpecId, outExpr, startCheckpoint.lastOutMatchKey)
    var inIdx        = 0
    var outIdx       = 0
    var lastInKey    = startCheckpoint.lastInMatchKey
    var lastOutKey   = startCheckpoint.lastOutMatchKey
    var totalMatches = 0

    val pendingMatches = ListBuffer[(Movement, Movement)]()

    def ensureIn(): Boolean =
      if inIdx < inPage.size then true
      else if inPage.size < pageSize then false
      else
        inPage = fetchPage(reconSpec.inMovementSpecId, inExpr, lastInKey)
        inIdx  = 0
        inPage.nonEmpty

    def ensureOut(): Boolean =
      if outIdx < outPage.size then true
      else if outPage.size < pageSize then false
      else
        outPage = fetchPage(reconSpec.outMovementSpecId, outExpr, lastOutKey)
        outIdx  = 0
        outPage.nonEmpty

    def commitBatch(): Unit =
      val matches = pendingMatches.toVector
      pendingMatches.clear()

      val checkpoint = ReconciliationCheckpoint(lastInMatchKey = lastInKey, lastOutMatchKey = lastOutKey)

      val decision = transact(transactor):
        val now = clock.now
        val reconciliations = matches.map: _ =>
          Reconciliation(specId = reconSpec.id, appliedRules = rule.sql, createdAt = Some(now), updatedAt = Some(now))
        reconRepo.insertAll(reconciliations)

        var batchDelta: Money = Zero
        val updatedMovements = matches.zip(reconciliations).flatMap { case ((inMov, outMov), recon) =>
          inMov.reconciliationId  = Some(recon.id)
          outMov.reconciliationId = Some(recon.id)
          batchDelta              = batchDelta + inMov.signedAmountFor(accountId) + outMov.signedAmountFor(accountId)
          Seq(inMov, outMov)
        }
        movementRepo.updateAll(updatedMovements)

        // One markReconciled call for the whole batch's net delta, rather than one per match —
        // ledger.reconcile (unchanged, still used by reconcileAccount) does the equivalent sum
        // internally per reconciliation; here the sum spans every reconciliation in the batch.
        ledger.markReconciled(accountId, batchDelta)

        jobId.foreach: id =>
          val job                = jobRepo.findById(id).ErrIfNotFound("Job", id)
          val updatedCheckpoints = allCheckpoints + (reconSpec.id -> checkpoint)
          jobRepo.update(job.copy(progressJson = Some(upickle.write(updatedCheckpoints))))

        val progress      = SpecBatchProgress(reconSpec.id, matches.size, checkpoint)
        val batchDecision = onBatchComplete(progress)
        if batchDecision == BatchDecision.Rollback then summon[DbTx].connection.rollback()
        batchDecision

      decision match
        case BatchDecision.Commit =>
          totalMatches += matches.size
        case BatchDecision.Rollback =>
          throw ReconciliationStopped

    var stopped = false
    try
      while ensureIn() && ensureOut() do
        val inMovement  = inPage(inIdx)
        val outMovement = outPage(outIdx)
        val inKey       = matchKeyValue(rule, IN, inMovement)
        val outKey      = matchKeyValue(rule, OUT, outMovement)
        val comparison  = inKey.compareTo(outKey)

        comparison match
          case 0 =>
            pendingMatches += (inMovement -> outMovement)
            lastInKey  = Some(inKey)
            lastOutKey = Some(outKey)
            inIdx += 1
            outIdx += 1
            if pendingMatches.size >= batchSize then commitBatch()
          case x if x < 0 =>
            lastInKey = Some(inKey)
            inIdx += 1
          case _ =>
            lastOutKey = Some(outKey)
            outIdx += 1

      if pendingMatches.nonEmpty then commitBatch()
    catch case ReconciliationStopped => stopped = true

    (totalMatches, ReconciliationCheckpoint(lastInMatchKey = lastInKey, lastOutMatchKey = lastOutKey), stopped)

  /**
   * Phase 1 of OneToMany: complete existing InProgress reconciliations against newly-unreconciled
   * out-movements sharing their in-movement's match key. Bounded by however many reconciliations
   * are currently InProgress (not by the size of the backlog), so — unlike Phase 2 — this doesn't
   * need pagination: findInProgressBySpecId and the per-reconciliation exact-key lookup are both
   * small, targeted queries. Each reconciliation's completion commits as its own small transaction.
   */
  private def completeInProgressReconciliations(reconSpec: ReconciliationSpec, accountId: ID): Unit =
    val rule    = reconSpec.rule()
    val outExpr = matchKeyExpr(rule, OUT, "a")

    val inProgressRecons = transact(transactor)(reconRepo.findInProgressBySpecId(reconSpec.id))
    for recon <- inProgressRecons do
      transact(transactor):
        val reconMovements = movementRepo.findByReconciliationID(recon.id)
        reconMovements
          .find(_.movementSpecId == reconSpec.inMovementSpecId)
          .foreach: inMov =>
            val inKey   = matchKeyValue(rule, IN, inMov)
            val matched = movementRepo.findUnreconciledByExactMatchKey(reconSpec.outMovementSpecId, outExpr, inKey)

            if matched.nonEmpty then
              var newBalance: Money = recon.balance
              val updatedOuts = matched.map: outMov =>
                outMov.reconciliationId = Some(recon.id)
                newBalance              = newBalance - outMov.amount
                outMov
              movementRepo.updateAll(updatedOuts)
              ledger.markReconciled(accountId, matched.map(_.signedAmountFor(accountId)).sum)

              recon.balance                           = newBalance
              if newBalance == Zero then recon.status = ReconciliationStatus.Final
              reconRepo.update(recon)

  /**
   * Phase 2 of OneToMany: stream both sides via keyset pagination (same mechanics as
   * reconcileOneToOneStreaming), grouping consecutive out-rows sharing the same key as the current
   * in-row into one reconciliation. A group can span a page boundary transparently, since
   * ensureOut() already fetches the next page on demand whenever the current one runs out — the
   * "does the group continue" check and "do we need more rows" check are the same check.
   */
  private def reconcileOneToManyStreaming(
    reconSpec:       ReconciliationSpec,
    accountId:       ID,
    startCheckpoint: ReconciliationCheckpoint,
    batchSize:       Int,
    jobId:           Option[ID],
    allCheckpoints:  Map[ID, ReconciliationCheckpoint],
    onBatchComplete: SpecBatchProgress => BatchDecision
  ): (Int, ReconciliationCheckpoint, Boolean) =
    val rule    = reconSpec.rule()
    val inExpr  = matchKeyExpr(rule, IN, "a")
    val outExpr = matchKeyExpr(rule, OUT, "a")

    var inPage      = fetchPage(reconSpec.inMovementSpecId, inExpr, startCheckpoint.lastInMatchKey)
    var outPage     = fetchPage(reconSpec.outMovementSpecId, outExpr, startCheckpoint.lastOutMatchKey)
    var inIdx       = 0
    var outIdx      = 0
    var lastInKey   = startCheckpoint.lastInMatchKey
    var lastOutKey  = startCheckpoint.lastOutMatchKey
    var totalGroups = 0

    // Each pending group is (inMovement, outMovements-for-that-group)
    val pendingGroups = ListBuffer[(Movement, Vector[Movement])]()

    def ensureIn(): Boolean =
      if inIdx < inPage.size then true
      else if inPage.size < pageSize then false
      else
        inPage = fetchPage(reconSpec.inMovementSpecId, inExpr, lastInKey)
        inIdx  = 0
        inPage.nonEmpty

    def ensureOut(): Boolean =
      if outIdx < outPage.size then true
      else if outPage.size < pageSize then false
      else
        outPage = fetchPage(reconSpec.outMovementSpecId, outExpr, lastOutKey)
        outIdx  = 0
        outPage.nonEmpty

    def commitBatch(): Unit =
      val groups = pendingGroups.toVector
      pendingGroups.clear()

      val checkpoint = ReconciliationCheckpoint(lastInMatchKey = lastInKey, lastOutMatchKey = lastOutKey)

      val decision = transact(transactor):
        val now = clock.now
        var batchDelta: Money = Zero

        for (inMov, outMovs) <- groups do
          var balance = inMov.amount
          for out <- outMovs do balance = balance - out.amount
          val status = if balance == Zero then ReconciliationStatus.Final else ReconciliationStatus.InProgress

          val reconciliation = Reconciliation(
            specId       = reconSpec.id,
            appliedRules = rule.sql,
            status       = status,
            balance      = balance,
            createdAt    = Some(now),
            updatedAt    = Some(now)
          )
          reconRepo.insert(reconciliation)

          inMov.reconciliationId = Some(reconciliation.id)
          val updatedMovements = outMovs.map: outMov =>
            outMov.reconciliationId = Some(reconciliation.id)
            outMov
          movementRepo.updateAll(inMov +: updatedMovements)

          batchDelta = batchDelta + inMov.signedAmountFor(accountId) + outMovs.map(_.signedAmountFor(accountId)).sum

        ledger.markReconciled(accountId, batchDelta)

        jobId.foreach: id =>
          val job                = jobRepo.findById(id).ErrIfNotFound("Job", id)
          val updatedCheckpoints = allCheckpoints + (reconSpec.id -> checkpoint)
          jobRepo.update(job.copy(progressJson = Some(upickle.write(updatedCheckpoints))))

        val progress      = SpecBatchProgress(reconSpec.id, groups.size, checkpoint)
        val batchDecision = onBatchComplete(progress)
        if batchDecision == BatchDecision.Rollback then summon[DbTx].connection.rollback()
        batchDecision

      decision match
        case BatchDecision.Commit =>
          totalGroups += groups.size
        case BatchDecision.Rollback =>
          throw ReconciliationStopped

    var stopped = false
    try
      while ensureIn() && ensureOut() do
        val inMovement = inPage(inIdx)
        val inKey      = matchKeyValue(rule, IN, inMovement)
        val outKey     = matchKeyValue(rule, OUT, outPage(outIdx))
        val comparison = inKey.compareTo(outKey)

        if comparison == 0 then
          val groupOuts = ListBuffer[Movement]()
          groupOuts += outPage(outIdx)
          lastOutKey = Some(outKey)
          outIdx += 1
          var continueGroup = true
          while continueGroup && ensureOut() do
            val nextOutKey = matchKeyValue(rule, OUT, outPage(outIdx))
            if nextOutKey == inKey then
              groupOuts += outPage(outIdx)
              lastOutKey = Some(nextOutKey)
              outIdx += 1
            else continueGroup = false

          pendingGroups += (inMovement -> groupOuts.toVector)
          lastInKey = Some(inKey)
          inIdx += 1
          if pendingGroups.size >= batchSize then commitBatch()
        else if comparison < 0 then
          lastInKey = Some(inKey)
          inIdx += 1
        else
          lastOutKey = Some(outKey)
          outIdx += 1

      if pendingGroups.nonEmpty then commitBatch()
    catch case ReconciliationStopped => stopped = true

    (totalGroups, ReconciliationCheckpoint(lastInMatchKey = lastInKey, lastOutMatchKey = lastOutKey), stopped)

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
    reconSpec: ReconciliationSpec
  ): Txn[Map[ID, Seq[Movement]]] =
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
