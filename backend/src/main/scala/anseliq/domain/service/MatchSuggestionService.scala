package anseliq.domain.service

import anseliq.common.model.MatchSuggestionInfo
import anseliq.common.model.model.ID
import anseliq.domain.model.*
import anseliq.domain.port.driven.*
import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.scalalogging.StrictLogging

import java.time.temporal.ChronoUnit

/**
 * Secondary matching: after the rule-based reconciler has run, asks the MatchSuggester which of an
 * unmatched movement's candidates it should reconcile with, and stores the answer as a pending
 * MatchSuggestion for a person to accept or reject. Nothing is reconciled here.
 *
 * Only OneToOne specs for now. A movement on the spec's in side is matched against unreconciled
 * movements on its out side with the same amount, created within candidateWindowDays of it, at
 * most maxCandidates of them. The suggester only judges between those, and its answer is checked
 * against them again before it's stored.
 *
 * A person then reviews each pending suggestion: accepting it matches the movements through
 * ManualReconciler, exactly as a manual match would; rejecting it just records that. Either way the
 * reviewer is recorded on the suggestion.
 */
class MatchSuggestionService(
  reconSpecRepo:       ReconciliationSpecRepo,
  movementRepo:        MovementRepo,
  matchSuggestionRepo: MatchSuggestionRepo,
  suggester:           MatchSuggester,
  manualReconciler:    ManualReconciler,
  transactor:          Transactor,
  candidateWindowDays: Int,
  maxCandidates:       Int
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
   * suggestion yet.
   */
  def findRequests(accountId: ID): Txn[Vector[(ReconciliationSpec, MatchRequest)]] =
    val unreconciled = movementRepo.findUnReconciledByAccount(accountId)
    reconSpecRepo
      .findByAccountID(accountId)
      .filter(_.relationship == Relationship.OneToOne)
      .flatMap: spec =>
        val alreadySuggested = matchSuggestionRepo.findPendingByReconSpecId(spec.id).map(_.movementId).toSet
        val outSide          = unreconciled.filter(_.movementSpecId == spec.outMovementSpecId)
        unreconciled
          .filter(m => m.movementSpecId == spec.inMovementSpecId && !alreadySuggested.contains(m.id))
          .flatMap: movement =>
            val candidates = outSide.filter(isCandidate(movement, _)).take(maxCandidates)
            Option.when(candidates.nonEmpty)(spec -> MatchRequest(toCandidate(movement), candidates.map(toCandidate)))

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
   * still unreconciled. None if it doesn't, or the movement already has a pending suggestion.
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
        matchSuggestionRepo.insertIfNoPending(
          MatchSuggestion(
            reconSpecId              = spec.id,
            movementId               = movementId,
            suggestedMovementIdsJson = upickle.write(proposal.movementIds),
            confidence               = proposal.confidence.setScale(3, BigDecimal.RoundingMode.HALF_UP),
            rationale                = proposal.rationale.take(1000),
            model                    = suggester.model,
            promptVersion            = suggester.promptVersion
          )
        )

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
      description    = s"Suggested by ${suggestion.model}: ${suggestion.rationale}"
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
