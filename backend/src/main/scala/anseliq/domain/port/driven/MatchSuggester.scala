package anseliq.domain.port.driven

import anseliq.common.model.model.{ID, Money, Properties}

import java.time.OffsetDateTime

// Judges which of an unmatched movement's candidates it should reconcile with, for movements the
// recon spec's rule couldn't match (reference ids in different formats, reworded descriptions). An
// adapter runs its model inside the install: AnselIQ is deployed on premise, so nothing here may
// call out to a remote service. The candidates are already narrowed by MatchSuggestionService, and
// whatever comes back is checked against them before it's stored.
trait MatchSuggester:
  /** Recorded on every suggestion, so one can be traced back after the model or prompt changes. */
  def model:         String
  def promptVersion: String

  /** A proposed match from request.candidates, or None if none of them is a convincing match. */
  def suggest(request: MatchRequest): Option[ProposedMatch]

// What the model sees of a movement: enough to judge a match, nothing else from the row.
case class MatchCandidate(
  movementId: ID,
  amount:     Money,
  createdAt:  OffsetDateTime,
  properties: Properties
)

case class MatchRequest(movement: MatchCandidate, candidates: Seq[MatchCandidate], context: MatchContext = MatchContext.empty)

// What a suggester knows about the spec beyond the movement in hand, so a general model can learn
// this account's reference conventions without being trained on them.
case class MatchContext(
  // What the spec's rule compares, e.g. "transaction_id = txn_id": the movement's field on the
  // left, the candidates' on the right. The rule's miss is why the movement is being asked about.
  ruleCondition: String,
  // Recent pairs the rule missed that a person matched by hand or accepted as a suggestion.
  pastMatches: Seq[MatchExample],
  // Recent suggestions a person rejected: pairs that looked alike but weren't the same transaction.
  pastNonMatches: Seq[MatchExample]
)

object MatchContext:
  val empty: MatchContext = MatchContext("", Seq.empty, Seq.empty)

case class MatchExample(movement: MatchCandidate, matchedWith: Seq[MatchCandidate])

// confidence is 0 to 1; rationale is a short reason a reviewer can check.
case class ProposedMatch(movementIds: Seq[ID], confidence: BigDecimal, rationale: String)
