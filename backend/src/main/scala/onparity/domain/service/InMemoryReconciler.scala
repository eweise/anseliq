package onparity.domain.service

import com.augustnagro.magnum.{DbTx, Transactor, transact}
import com.typesafe.scalalogging.Logger
import net.logstash.logback.argument.StructuredArguments.kv
import onparity.common.model.*
import onparity.common.model.Direction.{IN, OUT}
import onparity.common.model.model.{ID, Money, Zero}
import onparity.domain.model.*
import onparity.domain.model.Relationship.{OneToMany, OneToOne}
import onparity.domain.port.driven.*
import onparity.util.{LogContext, PerfLogger}

import java.time.{Instant, OffsetDateTime}
import java.util.UUID
import scala.collection.mutable.ListBuffer
import scala.util.boundary
import scala.util.boundary.break

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
  val jobRepo:            JobRepo,
  val disputeRepo:        DisputeRepo
):

  private val pageSize = 10000

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

  // ASCII unit separator: joins a composite key's per-attribute values on the Scala side, mirroring
  // chr(31) used in the SQL-side concatenation in matchKeyExpr below.
  private val compositeKeyDelimiter = 31.toChar.toString

  // Supports StringAttributeMatchRule/DateAttributeMatchRule, singly or ANDed together via
  // AndRule (each sub-rule contributes one attribute to a composite key, in rule order — the
  // in-side and out-side lists below are always built from the same AndRule traversal, so they
  // pair up correctly). OrRule and any other Rule are unsupported and throw clearly rather than
  // silently comparing the wrong thing.
  private def matchKeyAttributes(rule: Rule, direction: Direction): Seq[StringAttribute | DateAttribute] =
    rule match
      case s: StringAttributeMatchRule => Seq(if direction == IN then s.inKey else s.outKey)
      case d: DateAttributeMatchRule   => Seq(if direction == IN then d.inKey else d.outKey)
      case AndRule(rules) => rules.flatMap(r => matchKeyAttributes(r, direction))
      case other =>
        throw UnsupportedOperationException(
          s"reconcileAccountResumable only supports StringAttributeMatchRule/DateAttributeMatchRule and AndRule combinations of them; got ${other.getClass.getSimpleName}"
        )

  // Deliberately doesn't reuse Rule.sql/Attribute.modifySQL: those access properties via jsonb
  // bracket indexing and (when a modifier is present) cast that to ::text, which preserves JSON
  // quoting (e.g. "20" with literal quote characters) — that doesn't match the plain unquoted
  // Scala string that matchKeyValue/afterKey use, so it's built here as ->> text extraction plus
  // an explicit modifier translation instead. StringAttribute's SubstringModifier has no real
  // implementation upstream (its own .apply is `???`), so it's unsupported here too.
  private def attrSqlExpr(attr: StringAttribute | DateAttribute, tableAlias: String): String =
    attr match
      case s: StringAttribute =>
        s.modifier match
          case None => s"($tableAlias.properties['props'] ->> '${s.key}')"
          case Some(m) =>
            throw UnsupportedOperationException(
              s"reconcileAccountResumable does not support string modifier ${m.getClass.getSimpleName}"
            )
      case d: DateAttribute =>
        val base = s"($tableAlias.properties['props'] ->> '${d.key}')::date"
        d.modifier match
          case None                       => base
          case Some(DateAdd(interval, v)) => s"($base + interval '$v ${interval.toString.toLowerCase}')::date"

  // A composite key concatenates each attribute's (text-cast) SQL fragment with chr(31) (ASCII
  // unit separator) — vanishingly unlikely to appear inside a real business identifier or date,
  // so concatenation equality still corresponds to "every attribute is equal". The explicit
  // ::text cast (even for the already-text String case) matters: MovementRepoLive binds
  // afterKey/keyValue as plain text, so the SQL side must always resolve to text too, or
  // Postgres has no applicable > / = operator for e.g. a bare `date`.
  private def matchKeyExpr(rule: Rule, direction: Direction, tableAlias: String): String =
    matchKeyAttributes(rule, direction)
      .map(a => s"(${attrSqlExpr(a, tableAlias)})::text")
      .mkString(" || chr(31) || ")

  private def matchKeyValue(rule: Rule, direction: Direction, movement: Movement): String =
    matchKeyAttributes(rule, direction).map {
      case s: StringAttribute => s.getValue(movement.props)
      case d: DateAttribute   => d.getValue(movement.props).toString
    }
      .mkString(compositeKeyDelimiter)

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
   * A keyset-paginated cursor over one side (in or out) of a match, fetching `pageSize` rows at a
   * time via `fetchPage` and transparently refetching the next page once the current one is
   * exhausted. `lastKey` tracks the most recently consumed row's match key, which becomes the
   * checkpoint for resuming this side on a later call.
   */
  private class MergeCursor(
    movementSpecId: ID,
    rule:           Rule,
    direction:      Direction,
    keyExpr:        String,
    startKey:       Option[String]
  ):
    private var page = fetchPage(movementSpecId, keyExpr, startKey)
    private var idx  = 0
    var lastKey: Option[String] = startKey

    /**
     * True if `current`/`key` are valid, fetching the next page first if the current one ran out.
     */
    def ensure(): Boolean =
      if idx < page.size then true
      else if page.size < pageSize then false
      else
        page = fetchPage(movementSpecId, keyExpr, lastKey)
        idx  = 0
        page.nonEmpty

    def key:     String   = matchKeyValue(rule, direction, page(idx))
    def current: Movement = page(idx)

    /** Consumes the current row, recording its key as the new checkpoint watermark. */
    def advance(): Unit =
      lastKey = Some(key)
      idx += 1

  /**
   * Gathers the current row plus every immediately-following row on this cursor that shares its
   * match key, consuming all of them (crossing page boundaries transparently via `ensure`). Used by
   * OneToMany to collect the full run of same-key rows on one side before deciding which side is
   * the "one" and which is the "many".
   */
  private def gatherRun(cursor: MergeCursor): Vector[Movement] =
    val matchedKey = cursor.key
    val buf        = ListBuffer(cursor.current)
    cursor.advance()
    var continue = true
    while continue && cursor.ensure() do
      if cursor.key == matchedKey then
        buf += cursor.current
        cursor.advance()
      else continue = false
    buf.toVector

  /**
   * Shared engine behind both reconcileOneToOneStreaming and reconcileOneToManyStreaming: streams
   * both sides via keyset pagination instead of loading every unreconciled movement into memory,
   * and commits every `batchSize` groups in its own transaction instead of one transaction for the
   * whole account. If `jobId` is provided, the checkpoint for this spec is persisted on that job's
   * progressJson inside each batch's transaction, so a crashed attempt can be resumed by calling
   * this again with the same jobId — but a fresh call (no jobId, or a different job) always starts
   * from startCheckpoint with no additional lower bound, so late-arriving movements are never
   * permanently skipped.
   *
   * The two relationship types differ only in how a key match becomes a "group" and how that group
   * becomes a Reconciliation — everything else (pagination, batching, the transaction/checkpoint/
   * rollback plumbing) is identical, so it lives here once:
   *   - `formGroup` is called with both cursors positioned at a confirmed key match. It's
   *     responsible for consuming whatever rows it wants from each cursor (via `advance`/
   *     `gatherRun`) and returning the resulting (inMovements, outMovements) group, or None to skip
   *     a group it can't resolve (already consumed either way, so it won't be retried).
   *   - `toReconciliation` builds the Reconciliation for one group, given the batch's shared
   *     timestamp.
   *
   * `raiseDisputeOnMismatch` controls whether a group landing InProgress auto-raises a dispute right
   * here. True for OneToOne, where a group only ever forms once both sides have actually shown up, so
   * InProgress is a genuine terminal mismatch. False for OneToMany: a group's first formation says
   * nothing about whether the rest of the "many" side is still in transit (a different file, a delayed
   * batch) — that's aging-gated separately in completeInProgressReconciliations, not raised on sight.
   */
  private def streamMergeJoin(
    reconSpec:              ReconciliationSpec,
    accountId:              ID,
    startCheckpoint:        ReconciliationCheckpoint,
    batchSize:              Int,
    jobId:                  Option[ID],
    allCheckpoints:         Map[ID, ReconciliationCheckpoint],
    onBatchComplete:        SpecBatchProgress => BatchDecision,
    raiseDisputeOnMismatch: Boolean
  )(
    formGroup:        (MergeCursor, MergeCursor) => Option[(Vector[Movement], Vector[Movement])],
    toReconciliation: (Vector[Movement], Vector[Movement], OffsetDateTime) => Reconciliation
  ): (Int, ReconciliationCheckpoint, Boolean) =
    val logger = Logger(getClass.getName)
    val rule   = reconSpec.rule()

    val inCursor =
      MergeCursor(reconSpec.inMovementSpecId, rule, IN, matchKeyExpr(rule, IN, "a"), startCheckpoint.lastInMatchKey)
    val outCursor =
      MergeCursor(reconSpec.outMovementSpecId, rule, OUT, matchKeyExpr(rule, OUT, "a"), startCheckpoint.lastOutMatchKey)

    var totalGroups   = 0
    val pendingGroups = ListBuffer[(Vector[Movement], Vector[Movement])]()

    def commitBatch(): Unit =
      val groups = pendingGroups.toVector
      pendingGroups.clear()

      val checkpoint = ReconciliationCheckpoint(lastInMatchKey = inCursor.lastKey, lastOutMatchKey = outCursor.lastKey)
      logger.info("committing batch", kv("checkpoint", checkpoint))

      val decision = transact(transactor):
        val now             = clock.now
        val reconciliations = groups.map { case (inGroup, outGroup) => toReconciliation(inGroup, outGroup, now) }
        reconRepo.insertAll(reconciliations)

        var batchDelta: Money = Zero
        val idPairs = ListBuffer[(ID, ID)]()
        groups.zip(reconciliations).foreach { case ((inGroup, outGroup), recon) =>
          for mov <- inGroup ++ outGroup do idPairs += (mov.id -> recon.id)
          batchDelta = batchDelta + (inGroup ++ outGroup).map(_.signedAmountFor(accountId)).sum
        }
        movementRepo.setReconciliationIds(idPairs)

        // One markReconciled call for the whole batch's net delta, rather than one per group —
        // ledger.reconcile (unchanged, still used by reconcileAccount) does the equivalent sum
        // internally per reconciliation; here the sum spans every reconciliation in the batch.
        ledger.markReconciled(accountId, batchDelta)

        // See raiseDisputeOnMismatch's doc above streamMergeJoin: only meaningful where a group's
        // first formation is itself proof that nothing more is expected to arrive for it.
        if raiseDisputeOnMismatch then
          reconciliations.filter(_.status == ReconciliationStatus.InProgress).foreach: recon =>
            disputeRepo
              .insertIfNoOpenDispute(
                Dispute(
                  referenceId = recon.id,
                  targetType  = DisputeTarget.Reconciliation,
                  category    = DisputeCategory.AmountMismatch,
                  detail      = Some(s"Balance mismatch of ${recon.balance}"),
                  createdAt   = Some(now),
                  updatedAt   = Some(now)
                )
              )
              .foreach(dispute => eventStore.publishEvent(DisputeRaised(disputeID = dispute.id)))

        jobId.foreach: id =>
          val job                = jobRepo.findById(id).ErrIfNotFound("Job", id)
          val updatedCheckpoints = allCheckpoints + (reconSpec.id -> checkpoint)
          jobRepo.update(job.copy(progressJson = Some(upickle.write(updatedCheckpoints))))

        val progress      = SpecBatchProgress(reconSpec.id, groups.size, checkpoint)
        val batchDecision = onBatchComplete(progress)
        if batchDecision == BatchDecision.Rollback then summon[DbTx].connection.rollback()
        batchDecision
      end decision

      decision match
        case BatchDecision.Commit =>
          totalGroups += groups.size
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
          while inCursor.ensure() && outCursor.ensure() do
            val comparison = inCursor.key.compareTo(outCursor.key)
            logger.debug("done comparison", kv("comparison_outcome", comparison))
            if comparison == 0 then
              formGroup(inCursor, outCursor).foreach: group =>
                pendingGroups += group
                logger.debug("batch_full", kv("pending_groups_size", pendingGroups.size), kv("batchSize", batchSize))
                if pendingGroups.size >= batchSize then commitBatch()
            else if comparison < 0 then inCursor.advance()
            else outCursor.advance()
          end while

        if pendingGroups.nonEmpty then commitBatch()
      // end PerfLogger
    // end LogContext
    catch case ReconciliationStopped => stopped = true

    (
      totalGroups,
      ReconciliationCheckpoint(lastInMatchKey = inCursor.lastKey, lastOutMatchKey = outCursor.lastKey),
      stopped
    )
  end streamMergeJoin

  /**
   * OneToOne: every key match is an unconditional 1:1 pair (unlike OneToMany, there's no ambiguity
   * about which side is "the one" — both sides always contribute exactly one movement). The pair's
   * balance is still verified, the same as OneToMany: a key match with mismatched amounts (a short
   * payment, a fee deduction, a rounding difference) is exactly the kind of thing reconciliation is
   * supposed to catch, so it's InProgress rather than silently Final — see
   * DisputeCategory.AmountMismatch. Unlike OneToMany, though, a 1:1 pair can never be completed
   * later: there's no third movement that could ever arrive to close the gap under a OneToOne rule,
   * so a mismatched pair stays InProgress permanently until resolved manually
   * (ManualReconciler.writeOff) — no top-up phase.
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
    val rule = reconSpec.rule()
    streamMergeJoin(reconSpec, accountId, startCheckpoint, batchSize, jobId, allCheckpoints, onBatchComplete, raiseDisputeOnMismatch = true)(
      formGroup = (inCursor, outCursor) =>
        val group = Vector(inCursor.current) -> Vector(outCursor.current)
        inCursor.advance()
        outCursor.advance()
        Some(group)
      ,
      toReconciliation = (inGroup, outGroup, now) =>
        val balance = inGroup.map(_.amount).sum - outGroup.map(_.amount).sum
        val status  = if balance == Zero then ReconciliationStatus.Final else ReconciliationStatus.InProgress
        Reconciliation(
          specId       = reconSpec.id,
          appliedRules = rule.sql,
          status       = status,
          balance      = balance,
          createdAt    = Some(now),
          updatedAt    = Some(now)
        )
    )

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
   * OneToMany: for each shared key, whichever side has exactly one movement is the anchor; the
   * other side's movements (however many, gathered via gatherRun) become the group. A key where
   * both sides have more than one movement is genuinely ManyToMany, which this relationship doesn't
   * support, so it's skipped (both runs are still consumed, so it isn't retried) rather than
   * guessed at. Unlike OneToOne, the group's balance is always computed and checked — a OneToMany
   * group can legitimately arrive partially (some of the "many" side not posted yet), which is what
   * completeInProgressReconciliations (Phase 1, above) exists to top up on a later run.
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
    val rule = reconSpec.rule()
    streamMergeJoin(reconSpec, accountId, startCheckpoint, batchSize, jobId, allCheckpoints, onBatchComplete, raiseDisputeOnMismatch = false)(
      formGroup = (inCursor, outCursor) =>
        val matchedKey = inCursor.key
        val inGroup    = gatherRun(inCursor)
        val outGroup   = gatherRun(outCursor)
        if inGroup.size == 1 || outGroup.size == 1 then Some(inGroup -> outGroup)
        else
          Logger(getClass.getName).warn(
            "skipping ambiguous OneToMany match group (multiple movements on both sides share the same key)",
            kv("recon_spec_id", reconSpec.id),
            kv("match_key", matchedKey)
          )
          None
      ,
      toReconciliation = (inGroup, outGroup, now) =>
        val balance = inGroup.map(_.amount).sum - outGroup.map(_.amount).sum
        val status  = if balance == Zero then ReconciliationStatus.Final else ReconciliationStatus.InProgress
        Reconciliation(
          specId       = reconSpec.id,
          appliedRules = rule.sql,
          status       = status,
          balance      = balance,
          createdAt    = Some(now),
          updatedAt    = Some(now)
        )
    )
