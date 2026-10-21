package anseliq.testing

import anseliq.common.model.{Normalize, StringAttribute, StringAttributeMatchRule}
import anseliq.domain.port.driven.{MatchCandidate, MatchRequest, MatchSuggester, ProposedMatch}

// Stands in for a local model in HappyPathScenario: proposes the first candidate sharing a reference
// with the movement once case and punctuation are ignored ("INV-2026-0042" and "inv20260042"),
// the kind of near-miss an exact-match rule leaves behind, and explains it as normalize() on the
// two fields.
class ReferenceFormatSuggester extends MatchSuggester:
  val model         = "reference-format-demo"
  val promptVersion = "n/a"

  private def references(c: MatchCandidate): Map[String, String] =
    c.properties.removed("amount").filter((_, v) => Normalize(v).nonEmpty)

  def suggest(request: MatchRequest): Option[ProposedMatch] =
    val own = references(request.movement)
    request.candidates.iterator
      .flatMap: candidate =>
        for
          (theirField, theirs) <- references(candidate)
          (myField, mine)      <- own.find((_, v) => Normalize(v) == Normalize(theirs))
        yield ProposedMatch(
          movementIds = Seq(candidate.movementId),
          confidence  = BigDecimal("0.92"),
          rationale   = s"Same reference once formatting is ignored: $mine and $theirs",
          explanation = Some(
            StringAttributeMatchRule(StringAttribute(myField, Some(Normalize)), StringAttribute(theirField, Some(Normalize)))
          )
        )
      .nextOption()
