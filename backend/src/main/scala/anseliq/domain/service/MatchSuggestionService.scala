package anseliq.domain.service

import anseliq.common.model.{AndRule, AppliedRules, MatchSuggestionInfo, NoOpRule, ORRule, Rule, RuleMatch, StringAttributeMatchRule}
import anseliq.common.model.model.ID
import anseliq.domain.model.*
import anseliq.domain.port.driven.*
import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.scalalogging.StrictLogging

import java.time.temporal.ChronoUnit
import scala.util.Try

/**
 * Secondary matching: after the rule-based reconciler has run, asks the MatchSuggester which of an
 * unmatched movement's candidates it should reconcile with, and stores the answer as a pending
 * MatchSuggestion for a person to accept or reject. Nothing is reconciled here.
 *
 * Only OneToOne specs for now. A movement on the spec's in side is matched against unreconciled
 * movements on its out side with the same amount, created within candidateWindowDays of it, at
 * most maxCandidates of them. The suggester only judges between those, and its answer is checked
 * against them again before it's stored. Each request also carries the spec's context (what its rule
 * compares, and recent pairs people matched or rejected) so a general model can pick up this
 * account's reference conventions from examples rather than training.
 *
 * A person then reviews each pending suggestion: accepting it matches the movements through
 * ManualReconciler, exactly as a manual match would; rejecting it just records that. Either way the
 * reviewer is recorded on the suggestion.
 */
class MatchSuggestionService(
  reconSpecRepo:       ReconciliationSpecRepo,
  reconRepo:           ReconciliationRepo,
  movementRepo:        MovementRepo,
  matchSuggestionRepo: MatchSuggestionRepo,
  suggester:           MatchSuggester,
  manualReconciler:    ManualReconciler,
  transactor:          Transactor,
  candidateWindowDays: Int,
  maxCandidates:       Int,
  // Of each kind (past matches, past non-matches): a few is enough, and small models get less
  // accurate as the prompt grows.
  maxExamples: Int = 3
) extends StrictLogging:

  /**
   * Runs the suggester over the account's unmatched movements. Reads and writes are separate short
   * transactions so none is held open while the model runs. A suggester failure fails the job, to
   * be retried with the rest.
   */
  def suggestForAccount(accountId: ID): MatchSuggestionInfo =
    val beginTime = System.currentTimeMillis()
    val requests  = transact(transactor)(findRequests(accountId))
    val created = requests.count: (spec, request) =>
      suggester.suggest(request).exists(proposal => transact(transactor)(record(spec, request, proposal)).isDefined)
    MatchSuggestionInfo(
      accountId             = accountId.toString,
      movementsConsidered   = requests.size,
      suggestionsCreated    = created,
      processingTimeSeconds = (System.currentTimeMillis() - beginTime) / 1000
    )

  /**
   * A request for each of the account's unmatched movements that has candidates and no pending
   * suggestion yet. A candidate a person already rejected for that movement isn't offered again, so
   * a rejected pair isn't suggested on every run.
   */
  def findRequests(accountId: ID): Txn[Vector[(ReconciliationSpec, MatchRequest)]] =
    val unreconciled = movementRepo.findUnReconciledByAccount(accountId)
    reconSpecRepo
      .findByAccountID(accountId)
      .filter(_.relationship == Relationship.OneToOne)
      .flatMap: spec =>
        val alreadySuggested = matchSuggestionRepo.findPendingByReconSpecId(spec.id).map(_.movementId).toSet
        val rejectedPairs = matchSuggestionRepo
          .findRejectedByReconSpecId(spec.id)
          .flatMap(s => s.suggestedMovementIds.map(s.movementId -> _))
          .toSet
        val outSide          = unreconciled.filter(_.movementSpecId == spec.outMovementSpecId)
        lazy val context     = contextFor(spec) // only looked up if some movement has candidates
        unreconciled
          .filter(m => m.movementSpecId == spec.inMovementSpecId && !alreadySuggested.contains(m.id))
          .flatMap: movement =>
            val candidates = outSide
              .filter(other => isCandidate(movement, other) && !rejectedPairs.contains(movement.id -> other.id))
              .take(maxCandidates)
            Option.when(candidates.nonEmpty)(spec -> MatchRequest(toCandidate(movement), candidates.map(toCandidate), context))

  /** The spec's rule in words, and its most recent hand-made matches and rejected suggestions. */
  def contextFor(spec: ReconciliationSpec): Txn[MatchContext] =
    val pastMatches = reconRepo
      .findRecentManualOrSuggested(spec.id, maxExamples)
      .flatMap: recon =>
        val movements = movementRepo.findByReconciliationID(recon.id)
        val outSide   = movements.filter(_.movementSpecId == spec.outMovementSpecId).map(toCandidate)
        movements.find(_.movementSpecId == spec.inMovementSpecId).map(m => MatchExample(toCandidate(m), outSide))
    val pastNonMatches = matchSuggestionRepo
      .findRecentRejected(spec.id, maxExamples)
      .flatMap: suggestion =>
        movementRepo
          .findById(suggestion.movementId)
          .map(m => MatchExample(toCandidate(m), suggestion.suggestedMovementIds.flatMap(movementRepo.findById).map(toCandidate)))
    MatchContext(describeRule(spec.rule()), pastMatches, pastNonMatches)

  private def describeRule(rule: Rule): String = rule match
    case and: AndRule => and.rules.map(describeRule).mkString(" and ")
    case or: ORRule   => or.rules.map(describeRule).mkString("(", " or ", ")")
    case NoOpRule     => "nothing"
    case leaf         => RuleMatch(leaf, "").condition

  private def isCandidate(movement: Movement, other: Movement): Boolean =
    other.amount.compare(movement.amount) == 0 &&
      (for
        a <- movement.createdAt
        b <- other.createdAt
      yield ChronoUnit.DAYS.between(a, b).abs <= candidateWindowDays).getOrElse(false)

  private def toCandidate(m: Movement): MatchCandidate =
    MatchCandidate(movementId = m.id, amount = m.amount, createdAt = m.createdAt.get, properties = m.props)

  /**
   * Stores the proposal as a pending suggestion if it holds up: one movement (OneToOne), from the
   * request's candidates, with the same amount, a confidence between 0 and 1, and both movements
   * still unreconciled. None if it doesn't, or the movement already has a pending suggestion. Its
   * explanation is kept only if it checks out (see checkExplanation); one that doesn't is dropped
   * without dropping the suggestion.
   */
  def record(spec: ReconciliationSpec, request: MatchRequest, proposal: ProposedMatch): Txn[Option[MatchSuggestion]] =
    val movementId   = request.movement.movementId
    val candidateIds = request.candidates.map(_.movementId).toSet
    val problem =
      if proposal.movementIds.size != 1 then Some(s"proposed ${proposal.movementIds.size} movements for a OneToOne spec")
      else if !proposal.movementIds.forall(candidateIds.contains) then Some("proposed a movement that wasn't a candidate")
      else if proposal.confidence < 0 || proposal.confidence > 1 then Some(s"confidence ${proposal.confidence} is outside 0 to 1")
      else
        val involved = (movementId +: proposal.movementIds).flatMap(movementRepo.findById)
        if involved.size != proposal.movementIds.size + 1 || involved.exists(_.reconciliationId.isDefined) then
          Some("a movement is missing or already reconciled")
        else
          val proposedTotal = involved.filter(m => proposal.movementIds.contains(m.id)).map(_.amount).sum
          Option.when(proposedTotal.compare(request.movement.amount) != 0)("amounts don't match")

    problem match
      case Some(reason) =>
        logger.warn(s"discarding ${suggester.model} suggestion for movement $movementId: $reason")
        None
      case None =>
        val explanation = proposal.explanation.flatMap: rule =>
          val checked = request.candidates.find(_.movementId == proposal.movementIds.head).flatMap(checkExplanation(rule, request.movement, _))
          if checked.isEmpty then logger.info(s"dropping ${suggester.model}'s explanation for movement $movementId: it doesn't hold for these movements")
          checked
        matchSuggestionRepo.insertIfNoPending(
          MatchSuggestion(
            reconSpecId              = spec.id,
            movementId               = movementId,
            suggestedMovementIdsJson = upickle.write(proposal.movementIds),
            confidence               = proposal.confidence.setScale(3, BigDecimal.RoundingMode.HALF_UP),
            rationale                = proposal.rationale.take(1000),
            explanationJson          = explanation.map(upickle.write(_)),
            model                    = suggester.model,
            promptVersion            = suggester.promptVersion
          )
        )

  /**
   * The explanation as a RuleMatch if it holds: each side names a field (not a constant, which
   * would just restate the answer) that the movement has, and the rule reduces both to the same
   * non-empty value. Checked here rather than trusted, since a model can claim anything.
   */
  def checkExplanation(rule: StringAttributeMatchRule, movement: MatchCandidate, candidate: MatchCandidate): Option[RuleMatch] =
    if rule.inKey.isConstant || rule.outKey.isConstant then None
    else
      for
        in  <- Try(rule.inKey.getValue(movement.properties)).toOption
        out <- Try(rule.outKey.getValue(candidate.properties)).toOption
        if in.nonEmpty && in == out
      yield RuleMatch(rule, in)

  /**
   * The spec's pending suggestions that can still be accepted: ones whose movements are all still
   * unreconciled. Accepting another suggestion, a manual match or a later reconciler run can match
   * a movement out from under one; those are left out rather than shown and then refused.
   */
  def pendingForSpec(reconSpecId: ID): Txn[Vector[MatchSuggestion]] =
    val pending     = matchSuggestionRepo.findPendingByReconSpecId(reconSpecId)
    val involvedIds = pending.flatMap(s => s.movementId +: s.suggestedMovementIds).distinct
    // One lookup per id, not findAllById: Magnum's generic version can't bind a UUID array.
    val unreconciled = involvedIds.flatMap(movementRepo.findById).filter(_.reconciliationId.isEmpty).map(_.id).toSet
    pending.filter(s => (s.movementId +: s.suggestedMovementIds).forall(unreconciled.contains))

  /** Matches the suggestion's movements as a manual match would, and marks it Accepted. */
  def accept(suggestionId: ID, reviewedByUserId: ID): Txn[Reconciliation] =
    val suggestion = findPending(suggestionId)
    val reconciliation = manualReconciler.matchMovements(
      reconSpecId    = suggestion.reconSpecId,
      inMovementIds  = Seq(suggestion.movementId),
      outMovementIds = suggestion.suggestedMovementIds,
      description    = s"Suggested by ${suggestion.model}: ${suggestion.rationale}",
      appliedRules = AppliedRules.Suggested(
        suggestionId  = suggestion.id.toString,
        model         = suggestion.model,
        promptVersion = suggestion.promptVersion,
        confidence    = suggestion.confidence,
        reason        = suggestion.rationale,
        explanation   = suggestion.explanation
      )
    )
    suggestion.status = MatchSuggestionStatus.Accepted
    suggestion.reviewedByUserId = Some(reviewedByUserId)
    matchSuggestionRepo.update(suggestion)
    reconciliation

  def reject(suggestionId: ID, reviewedByUserId: ID): Txn[Unit] =
    val suggestion = findPending(suggestionId)
    suggestion.status = MatchSuggestionStatus.Rejected
    suggestion.reviewedByUserId = Some(reviewedByUserId)
    matchSuggestionRepo.update(suggestion)

  private def findPending(suggestionId: ID): Txn[MatchSuggestion] =
    val suggestion = matchSuggestionRepo.findById(suggestionId).ErrIfNotFound("match suggestion", suggestionId)
    if suggestion.status != MatchSuggestionStatus.Pending then
      throw IllegalStateError(s"match suggestion $suggestionId was already ${suggestion.status}")
    suggestion
