package onparity.domain.service

import com.augustnagro.magnum.{DbTx, Transactor, transact}
import com.typesafe.scalalogging.Logger
import net.logstash.logback.argument.StructuredArguments.kv
import onparity.common.model.*
import onparity.common.model.Direction.{IN, OUT}
import onparity.common.model.model.{ID, Money, Zero}
import onparity.domain.model.*
import onparity.domain.model.Relationship.{ManyToMany, OneToMany, OneToOne}
import onparity.domain.port.driven.*
import onparity.util.{LogContext, PerfLogger}

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

  private val pageSize = 10000

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
          var remainingInMovements  = unreconciledMovementsMap(reconSpec.inMovementSpecId).toBuffer
          var remainingOutMovements = unreconciledMovementsMap(reconSpec.outMovementSpecId).toBuffer

          // Which physical side ("in" or "out") holds the single record for a given match key isn't
          // fixed by the spec — either side can be the "one". compareMovements always expects an
          // in-role movement first, so when the anchor comes from the out side we flip the argument
          // order rather than the anchor/candidate roles.
          def keyMatches(anchor: Movement, anchorIsIn: Boolean, candidate: Movement): Boolean =
            if anchorIsIn then compareMovements(rule, anchor, candidate) == 0
            else compareMovements(rule, candidate, anchor) == 0

          // Phase 1: complete existing InProgress reconciliations. Whichever spec contributed exactly
          // one movement so far is a candidate anchor, and we grow from the other spec's unreconciled
          // pool. When the reconciliation currently holds exactly one movement from *each* spec (a
          // partial 1:1 pair), which side is the true anchor is genuinely ambiguous until more
          // movements arrive — so both directions are searched; late arrivals share the same match
          // key value regardless of which side they land on, so the two searches can't double-count.
          // Balance is always tracked as (sum of in-side amounts) - (sum of out-side amounts),
          // independent of which side happens to be the anchor, so its sign stays meaningful.
          val inProgressRecons = reconRepo.findInProgressBySpecId(reconSpec.id)
          for recon <- inProgressRecons do
            val reconMovements = movementRepo.findByReconciliationID(recon.id)
            val inAnchors      = reconMovements.filter(_.movementSpecId == reconSpec.inMovementSpecId)
            val outAnchors     = reconMovements.filter(_.movementSpecId == reconSpec.outMovementSpecId)

            val matchedIn  = ListBuffer[Movement]()
            val matchedOut = ListBuffer[Movement]()

            if inAnchors.size == 1 then
              val fromOut = remainingOutMovements.filter(m => keyMatches(inAnchors.head, true, m))
              remainingOutMovements = remainingOutMovements.filterNot(m => fromOut.exists(_.id == m.id))
              matchedOut ++= fromOut

            if outAnchors.size == 1 then
              val fromIn = remainingInMovements.filter(m => keyMatches(outAnchors.head, false, m))
              remainingInMovements = remainingInMovements.filterNot(m => fromIn.exists(_.id == m.id))
              matchedIn ++= fromIn

            val matched = matchedIn ++ matchedOut
            if matched.nonEmpty then
              recon.balance = recon.balance + matchedIn.map(_.amount).sum - matchedOut.map(_.amount).sum
              ledger.reconcile(matched.toSeq, recon.id, accountId)
              if recon.balance == Zero then recon.status = ReconciliationStatus.Final
              reconRepo.update(recon)

          // Phase 2: match remaining unreconciled movements. For each shared key, whichever side has
          // exactly one movement is the anchor; the other side's movements (however many) become the
          // group. A key where both sides have more than one movement is genuinely ManyToMany, which
          // this relationship doesn't support, so it's left unmatched rather than guessed at.
          val inMovements  = remainingInMovements.toSeq
          val outMovements = remainingOutMovements.toSeq
          var inIndex      = 0
          var outIndex     = 0
          while inIndex < inMovements.size && outIndex < outMovements.size do
            val inMovement  = inMovements(inIndex)
            val outMovement = outMovements(outIndex)
            val comparison  = compareMovements(rule, inMovement, outMovement)
            if comparison == 0 then
              var inEnd = inIndex + 1
              while inEnd < inMovements.size && compareMovements(rule, inMovements(inEnd), outMovement) == 0 do
                inEnd += 1
              var outEnd = outIndex + 1
              while outEnd < outMovements.size && compareMovements(rule, inMovement, outMovements(outEnd)) == 0 do
                outEnd += 1

              val inGroup  = inMovements.slice(inIndex, inEnd)
              val outGroup = outMovements.slice(outIndex, outEnd)

              if inGroup.size == 1 || outGroup.size == 1 then
                val now     = clock.now
                val balance = inGroup.map(_.amount).sum - outGroup.map(_.amount).sum

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

                ledger.reconcile(inGroup ++ outGroup, reconciliation.id, accountId)
              else
                logger.warn(
                  "skipping ambiguous OneToMany match group (multiple movements on both sides share the same key)",
                  kv("recon_spec_id", reconSpec.id)
                )

              inIndex  = inEnd
              outIndex = outEnd
            else if comparison < 0 then inIndex += 1
            else outIndex += 1
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
    batchSize:       Int                                = 10000,
    jobId:           Option[ID]                         = None,
    onBatchComplete: SpecBatchProgress => BatchDecision = _ => BatchDecision.Commit
  ): ReconciliationResumableResult =
    val logger = Logger(getClass.getName)
    LogContext.withContext("account_id" -> accountId):
      logger.info("reconcileAccountResumable", kv("account_id", accountId), kv("batch_size", batchSize))
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
          logger.debug("reconciling reconSpec", kv("recon_spec_id", reconSpec.id))
          val startCheckpoint = checkpoints.getOrElse(reconSpec.id, ReconciliationCheckpoint())
          reconSpec.relationship match
            case OneToOne =>
              logger.debug("processing OneToOne")
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
          end match
        end for
      // end boundary

      if specCounts.values.sum > 0 then
        transact(transactor)(eventStore.publishEvent(AccountReconciled(accountID = accountId)))

      ReconciliationResumableResult(
        reconciliationSpecInsertCount = specCounts,
        processingTimeSeconds         = Instant.now().getEpochSecond - beginTime.getEpochSecond,
        completed                     = completed
      )
    // end  LogContext
  end reconcileAccountResumable

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
      PerfLogger.time("fetchPage"):
        val unreconciled = movementRepo.findUnreconciledPage(movementSpecId, matchKeyExpr, afterKey, pageSize)
        Logger(getClass.getName)
          .debug(
            "fetch_page",
            kv("unreconciled_count", unreconciled.size),
            kv("match_key_expr", matchKeyExpr),
            kv("after_key", afterKey)
          )
        unreconciled

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
    val logger  = Logger(getClass.getName)
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
    end ensureIn

    def ensureOut(): Boolean =
      if outIdx < outPage.size then true
      else if outPage.size < pageSize then false
      else
        outPage = fetchPage(reconSpec.outMovementSpecId, outExpr, lastOutKey)
        outIdx  = 0
        outPage.nonEmpty
    end ensureOut

    def commitBatch(): Unit =
      val logger = Logger(getClass.getName)

      val matches = pendingMatches.toVector
      pendingMatches.clear()

      val checkpoint = ReconciliationCheckpoint(lastInMatchKey = lastInKey, lastOutMatchKey = lastOutKey)
      logger.info("committing batch", kv("checkpoint", checkpoint))

      val decision = transact(transactor):
        val now = clock.now
        val reconciliations = matches.map: _ =>
          Reconciliation(specId = reconSpec.id, appliedRules = rule.sql, createdAt = Some(now), updatedAt = Some(now))
        reconRepo.insertAll(reconciliations)

        var batchDelta: Money = Zero
        val idPairs = matches.zip(reconciliations).flatMap { case ((inMov, outMov), recon) =>
          inMov.reconciliationId  = Some(recon.id)
          outMov.reconciliationId = Some(recon.id)
          batchDelta              = batchDelta + inMov.signedAmountFor(accountId) + outMov.signedAmountFor(accountId)
          Seq(inMov.id -> recon.id, outMov.id -> recon.id)
        }
        movementRepo.setReconciliationIds(idPairs)

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
      end decision

      decision match
        case BatchDecision.Commit =>
          totalMatches += matches.size
        case BatchDecision.Rollback =>
          throw ReconciliationStopped
    end commitBatch

    var stopped = false
    try
      LogContext.withContext(
        "recon_spec_id" -> reconSpec.id,
        "account_id"    -> accountId
      ):
        PerfLogger.time("create pending batches"):
          while ensureIn() && ensureOut() do
            val inMovement  = inPage(inIdx)
            val outMovement = outPage(outIdx)
            val inKey       = matchKeyValue(rule, IN, inMovement)
            val outKey      = matchKeyValue(rule, OUT, outMovement)
            val comparison  = inKey.compareTo(outKey)

            logger.debug("done comparison", kv("comparison_outcome", comparison))
            comparison match
              case 0 =>
                pendingMatches += (inMovement -> outMovement)
                lastInKey  = Some(inKey)
                lastOutKey = Some(outKey)
                inIdx += 1
                outIdx += 1
                logger.debug(
                  "batch_full",
                  kv("pending_matches_size", pendingMatches.size),
                  kv("batchSize", batchSize)
                )
                if pendingMatches.size >= batchSize then commitBatch()
              case x if x < 0 =>
                lastInKey = Some(inKey)
                inIdx += 1
              case _ =>
                lastOutKey = Some(outKey)
                outIdx += 1
            end match
          end while

        if pendingMatches.nonEmpty then commitBatch()
      // end PerLogger
    // end LogContext
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
    val inExpr  = matchKeyExpr(rule, IN, "a")
    val outExpr = matchKeyExpr(rule, OUT, "a")

    val inProgressRecons = transact(transactor)(reconRepo.findInProgressBySpecId(reconSpec.id))
    for recon <- inProgressRecons do
      transact(transactor):
        val reconMovements = movementRepo.findByReconciliationID(recon.id)
        val inAnchors      = reconMovements.filter(_.movementSpecId == reconSpec.inMovementSpecId)
        val outAnchors     = reconMovements.filter(_.movementSpecId == reconSpec.outMovementSpecId)

        // Whichever spec contributed exactly one movement so far is a candidate anchor; the other
        // spec is searched for more movements sharing its key. When both specs currently have
        // exactly one movement (a partial 1:1 pair), which side is the true anchor is ambiguous
        // until more arrive, so both directions are searched — late arrivals share the same match
        // key value regardless of which side they land on, so the two searches can't double-count.
        // Balance is tracked as (sum of in-side amounts) - (sum of out-side amounts), independent
        // of which side happens to be the anchor, so its sign stays meaningful.
        val matchedIn  = ListBuffer[Movement]()
        val matchedOut = ListBuffer[Movement]()
        if inAnchors.size == 1 then
          val inKey = matchKeyValue(rule, IN, inAnchors.head)
          matchedOut ++= movementRepo.findUnreconciledByExactMatchKey(reconSpec.outMovementSpecId, outExpr, inKey)
        if outAnchors.size == 1 then
          val outKey = matchKeyValue(rule, OUT, outAnchors.head)
          matchedIn ++= movementRepo.findUnreconciledByExactMatchKey(reconSpec.inMovementSpecId, inExpr, outKey)

        val matched = matchedIn ++ matchedOut
        if matched.nonEmpty then
          val newBalance = recon.balance + matchedIn.map(_.amount).sum - matchedOut.map(_.amount).sum
          movementRepo.setReconciliationIds(matched.map(_.id -> recon.id))
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

    // Each pending group is (inMovements-for-that-key, outMovements-for-that-key); one side is
    // always a singleton, but which side isn't fixed by the spec.
    val pendingGroups = ListBuffer[(Vector[Movement], Vector[Movement])]()

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

        val idPairs = ListBuffer[(ID, ID)]()

        for (inGroup, outGroup) <- groups do
          val balance = inGroup.map(_.amount).sum - outGroup.map(_.amount).sum
          val status  = if balance == Zero then ReconciliationStatus.Final else ReconciliationStatus.InProgress

          val reconciliation = Reconciliation(
            specId       = reconSpec.id,
            appliedRules = rule.sql,
            status       = status,
            balance      = balance,
            createdAt    = Some(now),
            updatedAt    = Some(now)
          )
          reconRepo.insert(reconciliation)

          for mov <- inGroup ++ outGroup do idPairs += (mov.id -> reconciliation.id)

          batchDelta = batchDelta + (inGroup ++ outGroup).map(_.signedAmountFor(accountId)).sum

        movementRepo.setReconciliationIds(idPairs)
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
          // Whichever side has exactly one movement for this key is the anchor; the other side's
          // movements (however many) become the group. Gather both runs before deciding, since
          // either physical side can be the "one" for a given OneToMany spec.
          val groupIns = ListBuffer[Movement]()
          groupIns += inPage(inIdx)
          lastInKey = Some(inKey)
          inIdx += 1
          var continueInGroup = true
          while continueInGroup && ensureIn() do
            val nextInKey = matchKeyValue(rule, IN, inPage(inIdx))
            if nextInKey == inKey then
              groupIns += inPage(inIdx)
              lastInKey = Some(nextInKey)
              inIdx += 1
            else continueInGroup = false

          val groupOuts = ListBuffer[Movement]()
          groupOuts += outPage(outIdx)
          lastOutKey = Some(outKey)
          outIdx += 1
          var continueOutGroup = true
          while continueOutGroup && ensureOut() do
            val nextOutKey = matchKeyValue(rule, OUT, outPage(outIdx))
            if nextOutKey == inKey then
              groupOuts += outPage(outIdx)
              lastOutKey = Some(nextOutKey)
              outIdx += 1
            else continueOutGroup = false

          if groupIns.size == 1 || groupOuts.size == 1 then
            pendingGroups += (groupIns.toVector -> groupOuts.toVector)
            if pendingGroups.size >= batchSize then commitBatch()
          else
            Logger(getClass.getName).warn(
              "skipping ambiguous OneToMany match group (multiple movements on both sides share the same key)",
              kv("recon_spec_id", reconSpec.id),
              kv("match_key", inKey)
            )
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
